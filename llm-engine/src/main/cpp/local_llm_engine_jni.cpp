#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <exception>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <unistd.h>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"

#define TAG "LocalLlmEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Native llama.cpp/ggml diagnostics are persisted separately from Kotlin events.
// The filename uses the same engine-*.log pattern consumed by EngineDiagnostics export.
static std::mutex g_native_log_file_mutex;
static FILE* g_native_log_file = nullptr;
static constexpr long MAX_NATIVE_LOG_FILE_BYTES = 512L * 1024L;

static void local_llm_native_log_callback(enum ggml_log_level level, const char* text, void* /*user_data*/) {
    if (text == nullptr || level < GGML_LOG_LEVEL_INFO) return;

    int android_priority = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_WARN: android_priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_ERROR: android_priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_DEBUG: return;
        default: android_priority = ANDROID_LOG_INFO; break;
    }
    __android_log_write(android_priority, TAG, text);

    std::lock_guard<std::mutex> lock(g_native_log_file_mutex);
    if (g_native_log_file == nullptr) return;

    const auto now = std::chrono::system_clock::now();
    const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count() % 1000;
    const std::time_t epoch = std::chrono::system_clock::to_time_t(now);
    std::tm local_time{};
    localtime_r(&epoch, &local_time);
    char timestamp[40];
    std::snprintf(
        timestamp, sizeof(timestamp), "%04d-%02d-%02d %02d:%02d:%02d.%03lld",
        local_time.tm_year + 1900, local_time.tm_mon + 1, local_time.tm_mday,
        local_time.tm_hour, local_time.tm_min, local_time.tm_sec,
        static_cast<long long>(millis)
    );

    // Prefix every native log line with a sortable timestamp; flush immediately so
    // useful diagnostics survive a driver abort or process-level crash.
    const char* cursor = text;
    while (*cursor != '\0') {
        const char* line_end = std::strchr(cursor, '\n');
        const size_t line_length = line_end
            ? static_cast<size_t>(line_end - cursor)
            : std::strlen(cursor);
        if (line_length > 0) {
            const long current_size = std::ftell(g_native_log_file);
            const long incoming_size = static_cast<long>(line_length) + 64L;
            if (current_size >= 0 && current_size + incoming_size > MAX_NATIVE_LOG_FILE_BYTES) {
                std::fflush(g_native_log_file);
                if (ftruncate(fileno(g_native_log_file), 0) == 0) {
                    std::fseek(g_native_log_file, 0, SEEK_END);
                    std::fprintf(g_native_log_file,
                        "%s [NATIVE] Native diagnostics rotated at 512 KiB; older native lines omitted\n",
                        timestamp);
                }
            }
            const size_t safe_length = std::min(line_length, static_cast<size_t>(4096));
            std::fprintf(
                g_native_log_file, "%s [NATIVE] %.*s\n",
                timestamp, static_cast<int>(safe_length), cursor
            );
        }
        if (line_end == nullptr) break;
        cursor = line_end + 1;
    }
    std::fflush(g_native_log_file);
}

static constexpr int OVERFLOW_HEADROOM = 8;
static constexpr int BATCH_SIZE = 512;
static constexpr float DEFAULT_TEMP = 0.7f;

enum BackendMode {
    BACKEND_AUTO = 0,
    BACKEND_VULKAN = 1,
    BACKEND_CPU = 2
};

struct EngineInstance {
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    llama_batch batch;
    common_sampler* sampler = nullptr;
    common_chat_templates_ptr chat_templates;

    int32_t context_size = 4096;
    int32_t batch_size = BATCH_SIZE;
    int32_t n_threads = 4;
    std::string active_backend = "CPU";
    std::atomic<bool> cancel_requested{false};
    std::mutex mtx;

    // Chat session state
    std::vector<common_chat_msg> chat_msgs;
    llama_pos system_prompt_position = 0;
    llama_pos current_position = 0;
    llama_pos stop_generation_position = 0;
    std::string cached_token_chars;
    std::ostringstream assistant_ss;
    bool is_batch_allocated = false;

    // Inference tracking
    int32_t last_finish_reason = 0; // 0=STOP, 1=MAX_TOKENS, 2=CANCELLED, 3=ERROR
    int64_t inference_start_us = 0;
    int32_t tokens_generated = 0;

    // Last prompt-stage timings are kept on the existing EngineInstance so Kotlin can
    // persist them to the normal exportable diagnostics log without parsing logcat.
    int64_t last_prompt_template_us = 0;
    int64_t last_prompt_tokenization_us = 0;
    int64_t last_prompt_decode_us = 0;
    int32_t last_prompt_tokens = 0;
    int32_t last_prompt_batch_size = 0;
    int32_t last_prompt_threads = 0;

    void reset_short_term() {
        stop_generation_position = 0;
        cached_token_chars.clear();
        assistant_ss.str("");
        last_finish_reason = 0;
        tokens_generated = 0;
        inference_start_us = 0;
    }

    void reset_long_term(bool clear_kv = true) {
        chat_msgs.clear();
        system_prompt_position = 0;
        current_position = 0;
        if (clear_kv && context != nullptr) {
            llama_memory_clear(llama_get_memory(context), false);
        }
    }

    void shift_context() {
        if (!context) return;
        const int n_discard = (current_position - system_prompt_position) / 2;
        if (n_discard <= 0) return;
        LOGI("Controlled context shift: discarding %d non-system tokens starting at %d",
             n_discard, system_prompt_position);
        llama_memory_seq_rm(llama_get_memory(context), 0, system_prompt_position, system_prompt_position + n_discard);
        llama_memory_seq_add(llama_get_memory(context), 0, system_prompt_position + n_discard, current_position, -n_discard);
        current_position -= n_discard;
    }

    // Caller must hold mtx.
    void unload_unlocked() {
        reset_short_term();
        reset_long_term(false);
        if (sampler) {
            common_sampler_free(sampler);
            sampler = nullptr;
        }
        chat_templates.reset();
        if (is_batch_allocated) {
            llama_batch_free(batch);
            is_batch_allocated = false;
        }
        if (context) {
            llama_free(context);
            context = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
        active_backend = "None";
    }

    void unload() {
        std::lock_guard<std::mutex> lock(mtx);
        unload_unlocked();
    }
};

static inline EngineInstance* fromHandle(jlong handle) {
    return reinterpret_cast<EngineInstance*>(handle);
}

struct ModelLoadProgressContext {
    JNIEnv* env;
    jobject listener;
    jmethodID on_progress;
};

static void invoke_model_load_progress(ModelLoadProgressContext* context, float progress) {
    if (!context || !context->env || !context->listener || !context->on_progress) return;
    const float clamped = std::max(0.0f, std::min(1.0f, progress));
    context->env->CallVoidMethod(
        context->listener, context->on_progress, static_cast<jfloat>(clamped));
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        LOGW("Java model-load progress listener threw; continuing model load");
    }
}

// llama.cpp's callback measures GGUF tensor/weight loading. Other phases are reported
// explicitly below and the final SHA-256 pass reports actual bytes read from Kotlin.
static bool report_model_load_progress(float progress, void* user_data) {
    auto* context = static_cast<ModelLoadProgressContext*>(user_data);
    const float clamped = std::max(0.0f, std::min(1.0f, progress));
    invoke_model_load_progress(context, clamped * 0.70f);
    return true;
}

static void report_model_load_stage(ModelLoadProgressContext* context, float stage_progress) {
    invoke_model_load_progress(context, stage_progress);
}

struct PromptEvalProgressContext {
    JNIEnv* env;
    jobject listener;
    jmethodID on_progress;
};

static void report_prompt_eval_progress(
        PromptEvalProgressContext* context, int processed, int total) {
    if (!context || !context->env || !context->listener || !context->on_progress) return;
    context->env->CallVoidMethod(
        context->listener, context->on_progress,
        static_cast<jint>(processed), static_cast<jint>(total));
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        LOGW("Java prompt-progress listener threw; continuing inference");
    }
}

struct BackendDeviceSelection {
    ggml_backend_dev_t device = nullptr;
    std::string backend_name;
    std::string device_name;
    std::string description;
};

static bool findRegisteredVulkanDevice(BackendDeviceSelection& out) {
    // Do not probe the Android Vulkan loader independently. The inference
    // backend must be registered by the exact ggml/llama.cpp runtime we use.
    ggml_backend_reg_t registry = ggml_backend_reg_by_name("Vulkan");
    if (registry == nullptr) {
        LOGI("llama.cpp Vulkan backend is not registered");
        return false;
    }

    const size_t device_count = ggml_backend_reg_dev_count(registry);
    for (size_t i = 0; i < device_count; ++i) {
        ggml_backend_dev_t device = ggml_backend_reg_dev_get(registry, i);
        if (device == nullptr) continue;

        const auto type = ggml_backend_dev_type(device);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU &&
            type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
            continue;
        }

        out.device = device;
        out.backend_name = ggml_backend_reg_name(registry);
        out.device_name = ggml_backend_dev_name(device);
        out.description = ggml_backend_dev_description(device);
        LOGI("llama.cpp backend registry selected %s device %s (%s)",
             out.backend_name.c_str(), out.device_name.c_str(), out.description.c_str());
        return true;
    }

    LOGI("llama.cpp Vulkan backend is registered but exposes no GPU/IGPU device");
    return false;
}

static ggml_backend_dev_t findRegisteredCpuDevice() {
    ggml_backend_dev_t device =
        ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (device == nullptr) {
        LOGE("llama.cpp CPU backend is not registered");
    }
    return device;
}

// Exportable runtime evidence for the exact backend registry used by llama.cpp.
// Memory values come from ggml's own device API, not Android's generic RAM estimate.
static std::string collectBackendDiagnostics() {
    std::ostringstream ss;
    const size_t registry_count = ggml_backend_reg_count();
    ss << "registryCount=" << registry_count;
    bool vulkan_registered = false;

    for (size_t r = 0; r < registry_count; ++r) {
        ggml_backend_reg_t registry = ggml_backend_reg_get(r);
        if (registry == nullptr) continue;
        const char* raw_name = ggml_backend_reg_name(registry);
        const std::string backend_name = raw_name ? raw_name : "unknown";
        ss << ";backend[" << r << "]=" << backend_name;
        if (backend_name == "Vulkan") vulkan_registered = true;

        const size_t device_count = ggml_backend_reg_dev_count(registry);
        for (size_t d = 0; d < device_count; ++d) {
            ggml_backend_dev_t device = ggml_backend_reg_dev_get(registry, d);
            if (device == nullptr) continue;

            size_t memory_free = 0;
            size_t memory_total = 0;
            ggml_backend_dev_memory(device, &memory_free, &memory_total);
            const char* raw_device_name = ggml_backend_dev_name(device);
            const char* raw_description = ggml_backend_dev_description(device);
            ss << ";device[" << r << "," << d << "]="
               << (raw_device_name ? raw_device_name : "unknown")
               << ";description=" << (raw_description ? raw_description : "")
               << ";type=" << static_cast<int>(ggml_backend_dev_type(device))
               << ";freeBytes=" << memory_free
               << ";totalBytes=" << memory_total;
        }
    }

    ss << ";vulkanRegistered=" << (vulkan_registered ? "true" : "false");
    return ss.str();
}

static bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = reinterpret_cast<const unsigned char*>(string);
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

static int decode_tokens(EngineInstance* engine, const llama_tokens& tokens, llama_pos start_pos, bool want_last_logit, PromptEvalProgressContext* progress_context) {
    if (!engine || !engine->context) return -1;
    for (int i = 0; i < (int)tokens.size(); i += engine->batch_size) {
        if (engine->cancel_requested.load()) return -2;
        const int cur_batch_size = std::min((int)tokens.size() - i, engine->batch_size);
        common_batch_clear(engine->batch);

        if (start_pos + i + cur_batch_size >= engine->context_size - OVERFLOW_HEADROOM) {
            engine->shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool compute_logit = want_last_logit && (i + j == (int)tokens.size() - 1);
            common_batch_add(engine->batch, token_id, position, {0}, compute_logit);
        }

        const int res = llama_decode(engine->context, engine->batch);
        if (res != 0) {
            LOGE("llama_decode failed with code %d", res);
            return res;
        }
        report_prompt_eval_progress(
            progress_context,
            std::min(i + cur_batch_size, static_cast<int>(tokens.size())),
            static_cast<int>(tokens.size()));
    }
    return 0;
}

extern "C" {


JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeSetDiagnosticsLogFile(
        JNIEnv* env, jclass /*clazz*/, jstring jpath) {
    if (jpath == nullptr) return;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return;

    FILE* new_file = std::fopen(path, "a");
    env->ReleaseStringUTFChars(jpath, path);

    {
        std::lock_guard<std::mutex> lock(g_native_log_file_mutex);
        if (g_native_log_file != nullptr) {
            std::fclose(g_native_log_file);
        }
        g_native_log_file = new_file;
    }

    // llama_log_set also forwards ggml backend/driver diagnostics to this callback.
    // Install before llama_backend_init so Vulkan device discovery is captured too.
    llama_log_set(local_llm_native_log_callback, nullptr);
    if (new_file != nullptr) {
        LOGI("Native llama.cpp/ggml diagnostics file configured");
    } else {
        LOGE("Could not open native diagnostics file; native messages remain available in logcat");
    }
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeInitBackend(JNIEnv* /*env*/, jclass /*clazz*/) {
    llama_backend_init();
    LOGI("llama_backend_init completed");
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeShutdownBackend(JNIEnv* /*env*/, jclass /*clazz*/) {
    llama_backend_free();
    LOGI("llama_backend_free completed");
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetSystemInfo(JNIEnv* env, jclass /*clazz*/) {
    const char* sys_info = llama_print_system_info();
    return env->NewStringUTF(sys_info ? sys_info : "N/A");
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetBackendDiagnostics(
        JNIEnv* env, jclass /*clazz*/) {
    try {
        const std::string diagnostics = collectBackendDiagnostics();
        return env->NewStringUTF(diagnostics.c_str());
    } catch (const std::exception& error) {
        const std::string message = std::string("backendDiagnosticsException=") + error.what();
        return env->NewStringUTF(message.c_str());
    } catch (...) {
        return env->NewStringUTF("backendDiagnosticsException=unknown");
    }
}

JNIEXPORT jlong JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeCreateEngine(JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* engine = new EngineInstance();
    LOGI("Created controlled EngineInstance handle: %p", engine);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeLoadModel(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle,
        jstring jmodel_path,
        jint context_size,
        jint batch_size,
        jint threads,
        jfloat temperature,
        jfloat top_p,
        jint backend_mode,
        jint gpu_layers,
        jobject progress_listener) {
    auto* engine = fromHandle(handle);
    if (!engine) return 1;

    std::lock_guard<std::mutex> lock(engine->mtx);
    engine->unload_unlocked();

    const char* model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (!model_path) return 2;

    LOGI("Loading GGUF model: %s (mode=%d, gpu_layers=%d)", model_path, backend_mode, gpu_layers);

    llama_model_params model_params = llama_model_default_params();
    ModelLoadProgressContext progress_context{env, progress_listener, nullptr};
    if (progress_listener != nullptr) {
        jclass listener_class = env->GetObjectClass(progress_listener);
        if (listener_class != nullptr) {
            progress_context.on_progress = env->GetMethodID(listener_class, "onProgress", "(F)V");
            env->DeleteLocalRef(listener_class);
        }
        if (progress_context.on_progress == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            LOGE("Invalid native model-load progress listener");
            return 5;
        }
        model_params.progress_callback = report_model_load_progress;
        model_params.progress_callback_user_data = &progress_context;
    }

    // Progress stages below intentionally map llama.cpp's loader callback to
    // coarse UI milestones. Revalidate this mapping whenever the pinned llama.cpp
    // revision changes; callback semantics are vendor-version dependent.
    // Select devices from llama.cpp's own backend registry. A Vulkan loader
    // or physical device visible to Android is not proof that ggml-vulkan was
    // compiled, registered, or made available to llama.cpp.
    BackendDeviceSelection vulkan_device;
    const bool has_registered_vulkan = findRegisteredVulkanDevice(vulkan_device);
    ggml_backend_dev_t cpu_device = findRegisteredCpuDevice();
    if (cpu_device == nullptr) {
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 102;
    }

    std::vector<ggml_backend_dev_t> selected_devices;
    bool use_vulkan = false;
    const int requested_gpu_layers = gpu_layers > 0 ? gpu_layers : 999;

    if (backend_mode == BACKEND_VULKAN) {
        if (!has_registered_vulkan) {
            LOGE("Vulkan explicitly requested, but no Vulkan device is registered in llama.cpp");
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 101; // no silent fallback for an explicit Vulkan request
        }
        selected_devices = {vulkan_device.device, nullptr};
        model_params.devices = selected_devices.data();
        model_params.n_gpu_layers = requested_gpu_layers;
        use_vulkan = true;
    } else if (backend_mode == BACKEND_AUTO && has_registered_vulkan) {
        selected_devices = {vulkan_device.device, nullptr};
        model_params.devices = selected_devices.data();
        model_params.n_gpu_layers = requested_gpu_layers;
        use_vulkan = true;
    } else {
        selected_devices = {cpu_device, nullptr};
        model_params.devices = selected_devices.data();
        model_params.n_gpu_layers = 0;
    }

    LOGI("Model device selection: requestedMode=%d, registeredVulkan=%s, selected=%s, requestedGpuLayers=%d",
         backend_mode, has_registered_vulkan ? "true" : "false",
         use_vulkan ? vulkan_device.device_name.c_str() : "CPU",
         model_params.n_gpu_layers);

    // Persist a stage marker before entering the native tensor-weight loader.
    report_model_load_stage(&progress_context, 0.01f);

    llama_model* model = nullptr;
    try {
        model = llama_model_load_from_file(model_path, model_params);
    } catch (const std::exception& error) {
        LOGE("llama_model_load_from_file threw a C++ exception: %s", error.what());
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 103;
    } catch (...) {
        LOGE("llama_model_load_from_file threw an unknown C++ exception");
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 103;
    }

    // AUTO is a policy, not merely a device-presence check: if registered
    // Vulkan cannot load this model, retry with an explicitly CPU-only device list.
    if (model == nullptr && backend_mode == BACKEND_AUTO && use_vulkan) {
        LOGW("Vulkan model load failed; AUTO is retrying with the registered CPU backend");
        selected_devices = {cpu_device, nullptr};
        model_params.devices = selected_devices.data();
        model_params.n_gpu_layers = 0;
        use_vulkan = false;
        try {
            model = llama_model_load_from_file(model_path, model_params);
        } catch (const std::exception& error) {
            LOGE("AUTO CPU fallback llama_model_load_from_file threw a C++ exception: %s", error.what());
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 104;
        } catch (...) {
            LOGE("AUTO CPU fallback llama_model_load_from_file threw an unknown C++ exception");
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 104;
        }
    }

    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGE("llama_model_load_from_file failed on the selected backend (and CPU fallback if AUTO)");
        return 3;
    }

    if (use_vulkan) {
        const int total_offloadable_layers = std::max(0, llama_model_n_layer(model)) + 1;
        const int requested_offloadable_layers =
            std::min(model_params.n_gpu_layers, total_offloadable_layers);
        engine->active_backend = "Vulkan selected (" + vulkan_device.device_name +
            " / " + vulkan_device.description +
            "; requested layers " + std::to_string(requested_offloadable_layers) +
            "/" + std::to_string(total_offloadable_layers) + ")";
        LOGI("Vulkan backend registered and selected for model load: device=%s, requested_layers=%d/%d. " 
             "This is selection/configuration evidence, not a per-operation GPU execution measurement.",
             vulkan_device.device_name.c_str(), requested_offloadable_layers,
             total_offloadable_layers);
    } else {
        engine->active_backend = "CPU (registered ggml CPU backend)";
        LOGI("Model loaded with the registered CPU backend (n_gpu_layers=0)");
    }

    engine->model = model;
    report_model_load_stage(&progress_context, 0.72f); // weights complete; context setup begins

    int n_threads = threads;
    if (n_threads <= 0) {
        n_threads = std::max(2, (int)sysconf(_SC_NPROCESSORS_ONLN) - 2);
    }
    engine->n_threads = n_threads;

    // Dynamic context size resolution:
    // Do NOT hardcode 2048! Check user config vs trained context.
    int ctx_size = context_size > 0 ? context_size : 4096;
    const int trained_ctx = llama_model_n_ctx_train(model);
    if (trained_ctx > 0 && ctx_size > trained_ctx) {
        LOGW("User context size %d exceeds model train context %d. Enforcing %d",
             ctx_size, trained_ctx, trained_ctx);
        ctx_size = trained_ctx;
    }
    engine->context_size = ctx_size;

    int b_size = batch_size > 0 ? batch_size : BATCH_SIZE;
    engine->batch_size = b_size;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = b_size;
    ctx_params.n_ubatch = b_size;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context* ctx = nullptr;
    try {
        ctx = llama_init_from_model(model, ctx_params);
    } catch (const std::exception& error) {
        LOGE("llama_init_from_model threw a C++ exception: %s", error.what());
        llama_model_free(model);
        engine->model = nullptr;
        engine->active_backend = "None";
        return 104;
    } catch (...) {
        LOGE("llama_init_from_model threw an unknown C++ exception");
        llama_model_free(model);
        engine->model = nullptr;
        engine->active_backend = "None";
        return 104;
    }
    if (!ctx) {
        LOGE("Failed to create llama_context with dynamic n_ctx=%d", ctx_size);
        llama_model_free(model);
        engine->model = nullptr;
        return 4;
    }
    engine->context = ctx;
    report_model_load_stage(&progress_context, 0.80f); // context created

    engine->batch = llama_batch_init(b_size, 0, 1);
    report_model_load_stage(&progress_context, 0.81f); // template and sampler setup begins
    engine->is_batch_allocated = true;

    try {
        engine->chat_templates = common_chat_templates_init(model, "");

        common_params_sampling sparams;
        sparams.temp = temperature >= 0.0f ? temperature : DEFAULT_TEMP;
        sparams.top_p = (top_p >= 0.0f && top_p <= 1.0f) ? top_p : 0.9f;
        engine->sampler = common_sampler_init(model, sparams);
    } catch (const std::exception& error) {
        LOGE("Native chat-template/sampler setup threw a C++ exception: %s", error.what());
        engine->unload_unlocked();
        return 105;
    } catch (...) {
        LOGE("Native chat-template/sampler setup threw an unknown C++ exception");
        engine->unload_unlocked();
        return 105;
    }
    report_model_load_stage(&progress_context, 0.86f); // native runtime ready

    engine->reset_short_term();
    engine->reset_long_term(true);
    engine->cancel_requested.store(false);

    LOGI("Model loaded: n_ctx=%d, batch=%d, threads=%d, active_backend=%s",
         ctx_size, b_size, n_threads, engine->active_backend.c_str());
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetLastPromptMetrics(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return env->NewStringUTF("");
    std::ostringstream ss;
    ss << "templateUs=" << engine->last_prompt_template_us
       << ";tokenizationUs=" << engine->last_prompt_tokenization_us
       << ";decodeUs=" << engine->last_prompt_decode_us
       << ";promptTokens=" << engine->last_prompt_tokens
       << ";batchSize=" << engine->last_prompt_batch_size
       << ";threads=" << engine->last_prompt_threads
       << ";backend=" << engine->active_backend;
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetActiveBackend(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return env->NewStringUTF("None");
    return env->NewStringUTF(engine->active_backend.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetModelMetadata(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model) return env->NewStringUTF("");

    char desc[256];
    llama_model_desc(engine->model, desc, sizeof(desc));
    uint64_t n_params = llama_model_n_params(engine->model);
    int32_t n_ctx_train = llama_model_n_ctx_train(engine->model);
    uint64_t size_bytes = llama_model_size(engine->model);

    std::ostringstream ss;
    ss << desc << ";" << n_params << ";" << n_ctx_train << ";" << size_bytes << ";" << engine->active_backend;
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeProcessChat(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle,
        jobjectArray jroles,
        jobjectArray jcontents,
        jint max_predict,
        jboolean enable_thinking,
        jobject progress_listener) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model || !engine->context) return 1;

    std::lock_guard<std::mutex> lock(engine->mtx);
    engine->cancel_requested.store(false);
    engine->reset_short_term();
    engine->reset_long_term(true);

    jsize count = env->GetArrayLength(jroles);
    if (count == 0 || count != env->GetArrayLength(jcontents)) {
        return 2;
    }

    engine->last_prompt_template_us = 0;
    engine->last_prompt_tokenization_us = 0;
    engine->last_prompt_decode_us = 0;
    engine->last_prompt_tokens = 0;
    engine->last_prompt_batch_size = engine->batch_size;
    engine->last_prompt_threads = engine->n_threads;
    const int64_t prompt_prepare_start_us = llama_time_us();
    const bool has_chat_template = common_chat_templates_was_explicit(engine->chat_templates.get());
    std::vector<common_chat_msg> messages;
    messages.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; i++) {
        auto jrole = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jcontent = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        if (jrole == nullptr || jcontent == nullptr) {
            if (jrole != nullptr) env->DeleteLocalRef(jrole);
            if (jcontent != nullptr) env->DeleteLocalRef(jcontent);
            LOGE("Prompt message %d contains a null role or content", i);
            return 2;
        }

        const char* role_str = env->GetStringUTFChars(jrole, nullptr);
        const char* content_str = env->GetStringUTFChars(jcontent, nullptr);
        if (role_str == nullptr || content_str == nullptr) {
            if (role_str != nullptr) env->ReleaseStringUTFChars(jrole, role_str);
            if (content_str != nullptr) env->ReleaseStringUTFChars(jcontent, content_str);
            env->DeleteLocalRef(jrole);
            env->DeleteLocalRef(jcontent);
            LOGE("Could not read prompt message %d from JNI", i);
            return 2;
        }

        common_chat_msg msg;
        msg.role = role_str;
        msg.content = content_str;
        messages.push_back(std::move(msg));

        env->ReleaseStringUTFChars(jrole, role_str);
        env->ReleaseStringUTFChars(jcontent, content_str);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
    }

    std::string full_prompt;
    bool used_chat_template = false;
    try {
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = (enable_thinking == JNI_TRUE);
        full_prompt = common_chat_templates_apply(engine->chat_templates.get(), inputs).prompt;
        used_chat_template = true;
        LOGI("Formatted prompt with llama.cpp Jinja template (%zu messages, enable_thinking=%s)",
             messages.size(), enable_thinking == JNI_TRUE ? "true" : "false");
    } catch (const std::exception& jinja_error) {
        LOGW("Jinja chat-template rendering failed: %s", jinja_error.what());
        try {
            common_chat_templates_inputs inputs;
            inputs.messages = messages;
            inputs.add_generation_prompt = true;
            inputs.use_jinja = false;
            inputs.enable_thinking = (enable_thinking == JNI_TRUE);
            full_prompt = common_chat_templates_apply(engine->chat_templates.get(), inputs).prompt;
            used_chat_template = true;
            LOGI("Formatted prompt with llama.cpp legacy renderer");
        } catch (const std::exception& legacy_error) {
            LOGE("Legacy chat-template rendering failed: %s", legacy_error.what());
            if (has_chat_template) return 5;
        }
    }

    if (!used_chat_template) {
        for (const auto& message : messages) {
            full_prompt += message.role + ": " + message.content + "\n";
        }
        full_prompt += "assistant: ";
        LOGW("No usable model chat template; using role/content fallback");
    }
    engine->chat_msgs = messages;
    const int64_t template_duration_ms = (llama_time_us() - prompt_prepare_start_us) / 1000;
    engine->last_prompt_template_us = template_duration_ms * 1000;

    LOGI("Prompt prepared: messages=%zu, template=%s, characters=%zu, template_ms=%lld",
         messages.size(), used_chat_template ? "llama.cpp" : "fallback", full_prompt.size(),
         static_cast<long long>(template_duration_ms));
    const int64_t tokenization_start_us = llama_time_us();
    auto tokens = common_tokenize(engine->context, full_prompt, used_chat_template, used_chat_template);
    const int64_t tokenization_duration_ms = (llama_time_us() - tokenization_start_us) / 1000;
    engine->last_prompt_tokenization_us = tokenization_duration_ms * 1000;
    if (tokens.empty()) {
        LOGE("Prompt tokenization returned empty tokens");
        return 3;
    }

    int prompt_size = (int)tokens.size();
    const int max_allowed = engine->context_size - OVERFLOW_HEADROOM;
    if (prompt_size > max_allowed) {
        LOGW("Prompt size %d exceeds context size %d, controlled truncation", prompt_size, max_allowed);
        tokens.resize(max_allowed);
        prompt_size = max_allowed;
    }

    PromptEvalProgressContext progress_context{env, progress_listener, nullptr};
    if (progress_listener != nullptr) {
        jclass listener_class = env->GetObjectClass(progress_listener);
        if (listener_class != nullptr) {
            progress_context.on_progress = env->GetMethodID(listener_class, "onProgress", "(II)V");
            env->DeleteLocalRef(listener_class);
        }
        if (progress_context.on_progress == nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    report_prompt_eval_progress(&progress_context, 0, prompt_size);
    LOGI("Prompt evaluation started: prompt_tokens=%d, batch_size=%d",
         prompt_size, engine->batch_size);
    const int64_t prompt_start_us = llama_time_us();
    int dec_res = decode_tokens(
        engine, tokens, engine->current_position, true, &progress_context);
    const int64_t prompt_duration_ms = (llama_time_us() - prompt_start_us) / 1000;
    engine->last_prompt_decode_us = prompt_duration_ms * 1000;
    engine->last_prompt_tokens = prompt_size;
    if (dec_res != 0) {
        LOGE("Prompt decoding failed with code %d", dec_res);
        return 4;
    }

    engine->current_position += prompt_size;
    LOGI("Prompt stage metrics: template_ms=%lld, tokenization_ms=%lld, "
         "decode_ms=%lld, prompt_tokens=%d, batch_size=%d, backend=%s",
         static_cast<long long>(template_duration_ms),
         static_cast<long long>(tokenization_duration_ms),
         static_cast<long long>(prompt_duration_ms),
         prompt_size, engine->batch_size, engine->active_backend.c_str());
    LOGI("Prompt evaluation completed: prompt_tokens=%d, duration_ms=%lld",
         prompt_size, static_cast<long long>(prompt_duration_ms));
    int predict_len = max_predict > 0 ? max_predict : 1024;
    engine->stop_generation_position = engine->current_position + predict_len;
    engine->inference_start_us = llama_time_us();

    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGenerateNextToken(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model || !engine->context) return nullptr;

    if (engine->cancel_requested.load()) {
        LOGI("Inference cancelled cooperatively");
        engine->last_finish_reason = 2; // CANCELLED
        return nullptr;
    }

    if (engine->current_position >= engine->context_size - OVERFLOW_HEADROOM) {
        LOGI("Context full, performing controlled shift...");
        engine->shift_context();
    }

    if (engine->current_position >= engine->stop_generation_position) {
        LOGI("Reached stop generation token count");
        engine->last_finish_reason = 1; // MAX_TOKENS
        return nullptr;
    }

    if (engine->sampler == nullptr) {
        LOGE("Token generation requested without an initialized sampler");
        engine->last_finish_reason = 3; // ERROR
        return nullptr;
    }

    const int64_t token_step_start_us = llama_time_us();
    const llama_token new_token_id = common_sampler_sample(engine->sampler, engine->context, -1);
    common_sampler_accept(engine->sampler, new_token_id, true);

    common_batch_clear(engine->batch);
    common_batch_add(engine->batch, new_token_id, engine->current_position, {0}, true);

    if (llama_decode(engine->context, engine->batch) != 0) {
        LOGE("llama_decode failed during token generation");
        engine->last_finish_reason = 3; // ERROR
        return nullptr;
    }

    engine->current_position++;

    const struct llama_vocab* vocab = llama_model_get_vocab(engine->model);
    if (llama_vocab_is_eog(vocab, new_token_id)) {
        const int64_t stop_step_duration_us = llama_time_us() - token_step_start_us;
        LOGI("Generated EOG token: %d (excluded from visible token count; stop_step_ms=%.3f)",
             new_token_id,
             static_cast<double>(stop_step_duration_us) / 1000.0);
        engine->last_finish_reason = 0; // STOP
        return nullptr;
    }

    // The EOG token is decoded into the native context but is not returned to the UI.
    // Count only ordinary model-output tokens in results and tokens-per-second metrics.
    engine->tokens_generated++;
    const int64_t token_step_duration_us = llama_time_us() - token_step_start_us;
    if (engine->tokens_generated <= 4 || engine->tokens_generated % 8 == 0) {
        LOGI("Token step metric: index=%d, duration_ms=%.3f, backend=%s",
             engine->tokens_generated,
             static_cast<double>(token_step_duration_us) / 1000.0,
             engine->active_backend.c_str());
    }

    auto token_piece = common_token_to_piece(engine->context, new_token_id);
    engine->cached_token_chars += token_piece;

    if (is_valid_utf8(engine->cached_token_chars.c_str())) {
        jstring result = env->NewStringUTF(engine->cached_token_chars.c_str());
        engine->assistant_ss << engine->cached_token_chars;
        engine->cached_token_chars.clear();
        return result;
    } else {
        // Incomplete multibyte UTF-8 codepoint: return empty string so caller continues without breaking
        return env->NewStringUTF("");
    }
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetLastFinishReason(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    return engine ? engine->last_finish_reason : 3;
}

JNIEXPORT jlong JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetInferenceDurationUs(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || engine->inference_start_us == 0) return 0;
    return llama_time_us() - engine->inference_start_us;
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetTokensGenerated(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    return engine ? engine->tokens_generated : 0;
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeCancel(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) {
        engine->cancel_requested.store(true);
        LOGI("Native cancellation signal sent to handle %p", engine);
    }
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeUnloadModel(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) {
        engine->unload();
        LOGI("Model unloaded from handle %p", engine);
    }
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeDestroyEngine(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) {
        engine->unload();
        delete engine;
        LOGI("Destroyed handle %p", engine);
    }
}

} // extern "C"
