#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <stdexcept>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <cstdlib>
#include <unistd.h>
#include <sys/stat.h>

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
static constexpr long MAX_NATIVE_LOG_FILE_BYTES = 2L * 1024L * 1024L;

static void local_llm_native_log_callback(enum ggml_log_level level, const char* text, void* /*user_data*/) {
    if (text == nullptr) return;

    // llama.cpp reports the actual Vulkan feature set (FP16, integer dot,
    // subgroup size, shared memory, UMA and matrix cores) as DEBUG. Preserve
    // only its compact device-capability line; do not enable noisy per-op DEBUG.
    const bool is_vulkan_capability_line =
        level == GGML_LOG_LEVEL_DEBUG && std::strstr(text, "ggml_vulkan:") != nullptr;
    if (level < GGML_LOG_LEVEL_INFO && !is_vulkan_capability_line) return;

    int android_priority = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_WARN: android_priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_ERROR: android_priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_DEBUG: android_priority = ANDROID_LOG_DEBUG; break;
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
                        "%s [NATIVE] Native diagnostics rotated at 2 MiB; older native lines omitted\n",
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

// Persist compact inference timings through the same native log stream consumed by
// EngineDiagnostics. The message contains timings and backend only, never prompt text.
static void log_native_info_message(const std::string& message) {
    local_llm_native_log_callback(GGML_LOG_LEVEL_INFO, message.c_str(), nullptr);
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
    // Non-owning in llama.cpp's llama_file(FILE*) wrapper. Keep this FILE* alive
    // for the full model lifetime; it wraps a dup() of the borrowed SAF descriptor.
    FILE* model_file_stream = nullptr;
    llama_batch batch;
    common_sampler* sampler = nullptr;
    common_chat_templates_ptr chat_templates;

    int32_t context_size = 4096;
    int32_t batch_size = BATCH_SIZE;
    int32_t n_threads = 4;
    int32_t n_threads_batch = 4;
    int32_t micro_batch_size = BATCH_SIZE;
    float default_temperature = DEFAULT_TEMP;
    float default_top_p = 0.9f;
    std::string active_backend = "CPU";
    // Correlates native prompt and decode timings with the Kotlin request diagnostics.
    std::string inference_run_id;
    std::atomic<bool> cancel_requested{false};
#if defined(LOCAL_LLM_ENABLE_TEST_HOOKS)
    // Debug-only fault injection used to prove PID-level abort while JNI is blocked.
    std::atomic<bool> block_next_inference_for_test{false};
    std::atomic<bool> native_inference_block_entered_for_test{false};
#endif
    std::mutex mtx;

    // Chat session state
    std::vector<common_chat_msg> chat_msgs;
    llama_pos system_prompt_position = 0;
    llama_pos current_position = 0;
    llama_pos stop_generation_position = 0;
    std::string cached_token_chars;
    std::ostringstream assistant_ss;
    bool is_batch_allocated = false;
    bool generation_finished = false;

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
        generation_finished = false;
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
        // The generation limit is an absolute KV position too. Move it by the
        // same amount as the retained sequence or a context shift can extend
        // generation beyond the caller's maxPredictTokens.
        if (stop_generation_position > system_prompt_position) {
            stop_generation_position = std::max(
                system_prompt_position, stop_generation_position - n_discard);
        }
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
        if (model_file_stream != nullptr) {
            std::fclose(model_file_stream);
            model_file_stream = nullptr;
        }
        active_backend = "None";
        inference_run_id.clear();
    }

    void unload() {
        std::lock_guard<std::mutex> lock(mtx);
        unload_unlocked();
    }
};

// llama.cpp documents this callback as CPU-only in the pinned revision. Vulkan
// cancellation remains process-level because the GPU backend does not guarantee
// interruption of an in-flight decode through this callback.
static bool llama_decode_abort_requested(void* user_data) {
    const auto* engine = static_cast<const EngineInstance*>(user_data);
    return engine != nullptr && engine->cancel_requested.load(std::memory_order_relaxed);
}

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

static constexpr const char* UTF8_REPLACEMENT_CHARACTER = "\xEF\xBF\xBD";

static std::string normalize_utf8_incremental(
        const std::string& incoming,
        std::string& pending) {
    std::string input = pending;
    input.append(incoming);
    pending.clear();

    std::string output;
    output.reserve(input.size());
    size_t offset = 0;

    while (offset < input.size()) {
        const auto lead = static_cast<unsigned char>(input[offset]);
        if (lead <= 0x7F) {
            output.push_back(static_cast<char>(lead));
            ++offset;
            continue;
        }

        size_t sequence_length = 0;
        if (lead >= 0xC2 && lead <= 0xDF) {
            sequence_length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            sequence_length = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            sequence_length = 4;
        } else {
            output.append(UTF8_REPLACEMENT_CHARACTER);
            ++offset;
            continue;
        }

        const size_t available = input.size() - offset;
        const size_t checked = std::min(sequence_length, available);
        bool malformed = false;
        for (size_t index = 1; index < checked; ++index) {
            const auto continuation =
                static_cast<unsigned char>(input[offset + index]);
            if ((continuation & 0xC0) != 0x80) {
                malformed = true;
                break;
            }

            // Reject overlong encodings, UTF-16 surrogate code points and
            // code points above U+10FFFF, not merely malformed continuation bytes.
            if (index == 1 &&
                ((lead == 0xE0 && continuation < 0xA0) ||
                 (lead == 0xED && continuation > 0x9F) ||
                 (lead == 0xF0 && continuation < 0x90) ||
                 (lead == 0xF4 && continuation > 0x8F))) {
                malformed = true;
                break;
            }
        }

        if (malformed) {
            output.append(UTF8_REPLACEMENT_CHARACTER);
            ++offset;
            continue;
        }

        if (available < sequence_length) {
            // Only a valid, incomplete trailing code point is retained.
            // Its maximum size is three bytes, regardless of output length.
            pending.assign(input, offset, available);
            break;
        }

        output.append(input, offset, sequence_length);
        offset += sequence_length;
    }
    return output;
}

static jstring new_java_string_from_utf8(JNIEnv* env, const std::string& utf8) {
    if (utf8.empty()) return env->NewStringUTF("");

    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    size_t offset = 0;
    while (offset < utf8.size()) {
        const auto lead = static_cast<unsigned char>(utf8[offset]);
        uint32_t code_point = 0;
        size_t length = 0;

        if (lead <= 0x7F) {
            code_point = lead;
            length = 1;
        } else if (lead <= 0xDF) {
            code_point = lead & 0x1F;
            length = 2;
        } else if (lead <= 0xEF) {
            code_point = lead & 0x0F;
            length = 3;
        } else {
            code_point = lead & 0x07;
            length = 4;
        }

        for (size_t index = 1; index < length; ++index) {
            code_point = (code_point << 6) |
                (static_cast<unsigned char>(utf8[offset + index]) & 0x3F);
        }
        offset += length;

        if (code_point <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (code_point >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (code_point & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

static jstring flush_incomplete_utf8(JNIEnv* env, EngineInstance* engine) {
    if (engine->cached_token_chars.empty()) return nullptr;
    engine->cached_token_chars.clear();
    const std::string replacement(UTF8_REPLACEMENT_CHARACTER);
    engine->assistant_ss << replacement;
    return new_java_string_from_utf8(env, replacement);
}

static bool append_token_to_batch(
        llama_batch& batch,
        llama_token token,
        llama_pos position,
        llama_seq_id sequence_id,
        bool output_logits,
        int32_t capacity) {
    if (capacity <= 0 || batch.n_tokens < 0 || batch.n_tokens >= capacity ||
        batch.token == nullptr || batch.pos == nullptr ||
        batch.n_seq_id == nullptr || batch.seq_id == nullptr ||
        batch.logits == nullptr || batch.seq_id[batch.n_tokens] == nullptr) {
        LOGE("Invalid or exhausted llama_batch while appending token");
        return false;
    }

    const int32_t index = batch.n_tokens++;
    batch.token[index] = token;
    batch.pos[index] = position;
    batch.n_seq_id[index] = 1;
    batch.seq_id[index][0] = sequence_id;
    batch.logits[index] = output_logits ? 1 : 0;
    return true;
}

static int decode_tokens(EngineInstance* engine, const llama_tokens& tokens, llama_pos start_pos, bool want_last_logit, PromptEvalProgressContext* progress_context) {
    if (!engine || !engine->context) return -1;
    for (int i = 0; i < (int)tokens.size(); i += engine->batch_size) {
        if (engine->cancel_requested.load()) return -2;
        const int cur_batch_size = std::min((int)tokens.size() - i, engine->batch_size);
        engine->batch.n_tokens = 0;

        if (start_pos + i + cur_batch_size >= engine->context_size - OVERFLOW_HEADROOM) {
            engine->shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool compute_logit = want_last_logit && (i + j == (int)tokens.size() - 1);
            if (!append_token_to_batch(engine->batch, token_id, position, 0, compute_logit, engine->batch_size)) {
                return -3;
            }
        }

        const int res = llama_decode(engine->context, engine->batch);
        if (res == 2) {
            LOGI("llama_decode aborted by native cancellation callback");
            return -2;
        }
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
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeInitBackend(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jboolean enable_vulkan_perf_profiler) {
    const bool profiler_requested = enable_vulkan_perf_profiler == JNI_TRUE;
    if (profiler_requested) {
        // llama.cpp reads these variables during Vulkan backend discovery.
        // Configure them before llama_backend_init() so query pools are created
        // before the first model is loaded or graph is executed.
        const int profiler_result = setenv("GGML_VK_PERF_LOGGER", "1", 1);
        const int frequency_result = setenv("GGML_VK_PERF_LOGGER_FREQUENCY", "1", 1);
        unsetenv("GGML_VK_PERF_LOGGER_CONCURRENT");
        if (profiler_result == 0 && frequency_result == 0) {
            log_native_info_message(
                "VULKAN_PERF_PROFILER_CONFIG - enabled=true,frequency=1,mode=per_operation_gpu_timestamps,scope=debug_only");
        } else {
            unsetenv("GGML_VK_PERF_LOGGER");
            unsetenv("GGML_VK_PERF_LOGGER_FREQUENCY");
            LOGE("Could not configure Vulkan per-operation profiler: errno=%d", errno);
            log_native_info_message("VULKAN_PERF_PROFILER_CONFIG - enabled=false,reason=setenv_failed");
        }
    } else {
        // Do not inherit an accidental profiler setting in release builds.
        unsetenv("GGML_VK_PERF_LOGGER");
        unsetenv("GGML_VK_PERF_LOGGER_FREQUENCY");
        unsetenv("GGML_VK_PERF_LOGGER_CONCURRENT");
        log_native_info_message("VULKAN_PERF_PROFILER_CONFIG - enabled=false,scope=release");
    }

#if defined(LOCAL_LLM_INTEGER_DOT_GLSLC_SUPPORTED)
    log_native_info_message("VULKAN_INTEGER_DOT_SHADER_COMPILED=true");
#else
    log_native_info_message("VULKAN_INTEGER_DOT_SHADER_COMPILED=false");
#endif
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
        jint model_fd,
        jint context_size,
        jint batch_size,
        jint threads,
        jfloat temperature,
        jfloat top_p,
        jint backend_mode,
        jint gpu_layers,
        jint batch_threads,
        jint micro_batch_size,
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

    // SAF supplies an already-open descriptor. Duplicate the descriptor number,
    // not the GGUF bytes, and let llama.cpp consume the open FILE* interface. The
    // pinned upstream API supports llama_model_load_from_file_ptr(FILE*, params),
    // avoiding a second open("/proc/self/fd/N") which SAF providers may deny.
    FILE* descriptor_stream = nullptr;
    if (model_fd >= 0) {
        const int duplicated_fd = dup(model_fd);
        if (duplicated_fd < 0) {
            LOGE("Could not duplicate borrowed SAF model descriptor fd=%d: %s", model_fd, strerror(errno));
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 106;
        }
        descriptor_stream = fdopen(duplicated_fd, "rb");
        if (descriptor_stream == nullptr) {
            const int saved_errno = errno;
            close(duplicated_fd);
            LOGE("Could not create FILE* for borrowed SAF descriptor fd=%d: %s",
                 model_fd, strerror(saved_errno));
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 106;
        }
        LOGI("Loading GGUF through borrowed SAF descriptor fd=%d using llama_model_load_from_file_ptr (no path reopen, no copy)",
             model_fd);
    }

    auto close_descriptor_stream = [&descriptor_stream]() {
        if (descriptor_stream != nullptr) {
            std::fclose(descriptor_stream);
            descriptor_stream = nullptr;
        }
    };
    auto load_model_from_source = [&]() -> llama_model* {
        if (descriptor_stream != nullptr) {
            if (std::fseek(descriptor_stream, 0, SEEK_SET) != 0) {
                throw std::runtime_error(
                    std::string("Could not rewind borrowed SAF descriptor stream: ") + strerror(errno));
            }
            return llama_model_load_from_file_ptr(descriptor_stream, model_params);
        }
        return llama_model_load_from_file(model_path, model_params);
    };

    llama_model* model = nullptr;
    try {
        model = load_model_from_source();
    } catch (const std::exception& error) {
        LOGE("llama.cpp model load threw a C++ exception: %s", error.what());
        close_descriptor_stream();
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 103;
    } catch (...) {
        LOGE("llama.cpp model load threw an unknown C++ exception");
        close_descriptor_stream();
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
            model = load_model_from_source();
        } catch (const std::exception& error) {
            LOGE("AUTO CPU fallback llama.cpp model load threw a C++ exception: %s", error.what());
            close_descriptor_stream();
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 103;
        } catch (...) {
            LOGE("AUTO CPU fallback llama.cpp model load threw an unknown C++ exception");
            close_descriptor_stream();
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 104;
        }
    }

    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGE("llama.cpp model load failed on the selected backend (and CPU fallback if AUTO)");
        close_descriptor_stream();
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
    engine->model_file_stream = descriptor_stream;
    descriptor_stream = nullptr; // EngineInstance owns the duplicate until model unload.
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
    const int resolved_batch_threads = batch_threads > 0 ? batch_threads : n_threads;
    const int resolved_micro_batch = micro_batch_size > 0
        ? std::min(micro_batch_size, b_size)
        : b_size;
    engine->n_threads_batch = resolved_batch_threads;
    engine->micro_batch_size = resolved_micro_batch;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = b_size;
    ctx_params.n_ubatch = resolved_micro_batch;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = resolved_batch_threads;
    if (!use_vulkan) {
        ctx_params.abort_callback = llama_decode_abort_requested;
        ctx_params.abort_callback_data = engine;
    }

    llama_context* ctx = nullptr;
    try {
        ctx = llama_init_from_model(model, ctx_params);
    } catch (const std::exception& error) {
        LOGE("llama_init_from_model threw a C++ exception: %s", error.what());
        llama_model_free(model);
        engine->model = nullptr;
        if (engine->model_file_stream != nullptr) {
            std::fclose(engine->model_file_stream);
            engine->model_file_stream = nullptr;
        }
        engine->active_backend = "None";
        return 104;
    } catch (...) {
        LOGE("llama_init_from_model threw an unknown C++ exception");
        llama_model_free(model);
        engine->model = nullptr;
        if (engine->model_file_stream != nullptr) {
            std::fclose(engine->model_file_stream);
            engine->model_file_stream = nullptr;
        }
        engine->active_backend = "None";
        return 104;
    }
    if (!ctx) {
        LOGE("Failed to create llama_context with dynamic n_ctx=%d", ctx_size);
        llama_model_free(model);
        engine->model = nullptr;
        if (engine->model_file_stream != nullptr) {
            std::fclose(engine->model_file_stream);
            engine->model_file_stream = nullptr;
        }
        engine->active_backend = "None";
        return 4;
    }
    engine->context = ctx;
    report_model_load_stage(&progress_context, 0.80f); // context created

    engine->batch = llama_batch_init(b_size, 0, 1);
    report_model_load_stage(&progress_context, 0.81f); // template and sampler setup begins
    engine->is_batch_allocated = true;

    try {
        engine->chat_templates = common_chat_templates_init(model, "");

        engine->default_temperature =
            std::isfinite(temperature) && temperature >= 0.0f ? temperature : DEFAULT_TEMP;
        engine->default_top_p =
            std::isfinite(top_p) && top_p >= 0.0f && top_p <= 1.0f ? top_p : 0.9f;
        common_params_sampling sparams;
        sparams.temp = engine->default_temperature;
        sparams.top_p = engine->default_top_p;
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

    LOGI("Model loaded: n_ctx=%d, n_batch=%d, n_ubatch=%d, n_threads=%d, n_threads_batch=%d, active_backend=%s",
         ctx_size, b_size, resolved_micro_batch, n_threads, resolved_batch_threads,
         engine->active_backend.c_str());
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
       << ";batchThreads=" << engine->n_threads_batch
       << ";microBatchSize=" << engine->micro_batch_size
       << ";pid=" << getpid()
       << ";runId=" << engine->inference_run_id
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
        jstring j_inference_run_id,
        jobjectArray jroles,
        jobjectArray jcontents,
        jint max_predict,
        jfloat request_temperature,
        jfloat request_top_p,
        jboolean enable_thinking,
        jobject progress_listener) {
    auto* engine = fromHandle(handle);
#if defined(LOCAL_LLM_ENABLE_TEST_HOOKS)
    if (engine && engine->block_next_inference_for_test.exchange(false)) {
        engine->native_inference_block_entered_for_test.store(true);
        LOGW("DEBUG_TEST_HOOK: nativeProcessChat is deliberately blocked in JNI (pid=%d)", getpid());
        // Deliberately non-cooperative. The client watchdog must terminate this process;
        // cancelling a coroutine or calling nativeCancel cannot release this JNI frame.
        for (;;) {
            usleep(250000);
        }
    }
#endif
    if (!engine || !engine->model || !engine->context) return 1;

    std::string inference_run_id = "unknown";
    if (j_inference_run_id != nullptr) {
        const char* raw_run_id = env->GetStringUTFChars(j_inference_run_id, nullptr);
        if (raw_run_id != nullptr) {
            inference_run_id.assign(raw_run_id);
            env->ReleaseStringUTFChars(j_inference_run_id, raw_run_id);
        }
    }

    std::lock_guard<std::mutex> lock(engine->mtx);
    engine->inference_run_id = inference_run_id;
    // The request's cancellation flag is cleared by nativeResetCancellation()
    // before entering JNI. Never clear it here: a Binder cancel racing with
    // nativeProcessChat must not be lost.
    engine->reset_short_term();
    engine->reset_long_term(true);

    jsize count = env->GetArrayLength(jroles);
    if (count == 0 || count != env->GetArrayLength(jcontents)) {
        return 2;
    }

    // Recreate the sampler for every request so the sampler history is fresh
    // and optional request overrides do not mutate the model's stored defaults.
    const float effective_temperature =
        std::isfinite(request_temperature) && request_temperature >= 0.0f
            ? request_temperature : engine->default_temperature;
    const float effective_top_p =
        std::isfinite(request_top_p) && request_top_p >= 0.0f && request_top_p <= 1.0f
            ? request_top_p : engine->default_top_p;
    if (engine->sampler != nullptr) {
        common_sampler_free(engine->sampler);
        engine->sampler = nullptr;
    }
    try {
        common_params_sampling request_sampling;
        request_sampling.temp = effective_temperature;
        request_sampling.top_p = effective_top_p;
        engine->sampler = common_sampler_init(engine->model, request_sampling);
    } catch (const std::exception& error) {
        LOGE("Request sampler initialization failed: %s", error.what());
        engine->last_finish_reason = 3;
        return 7;
    } catch (...) {
        LOGE("Request sampler initialization failed with an unknown exception");
        engine->last_finish_reason = 3;
        return 7;
    }
    if (engine->sampler == nullptr) {
        LOGE("Request sampler initialization returned null");
        engine->last_finish_reason = 3;
        return 7;
    }
    LOGI("Request sampler configured: temperature=%.3f, top_p=%.3f",
         effective_temperature, effective_top_p);

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
    common_chat_prompt rendered_prompt;
    bool used_chat_template = false;
    try {
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = (enable_thinking == JNI_TRUE);
        const auto rendered = common_chat_templates_apply(engine->chat_templates.get(), inputs);
        rendered_prompt = rendered.prompt;
        full_prompt = rendered_prompt.str();
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
            const auto rendered = common_chat_templates_apply(engine->chat_templates.get(), inputs);
            rendered_prompt = rendered.prompt;
            full_prompt = rendered_prompt.str();
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
    llama_tokens tokens;
    if (used_chat_template) {
        // The rendered template already contains model-specific BOS/EOS markers.
        // Do not add a second BOS during tokenization. Parse control tokens only
        // in template-owned parts; user input parts must remain literal text.
        for (const auto& part : rendered_prompt.parts) {
            if (part.text.empty()) continue;
            auto part_tokens = common_tokenize(
                engine->context,
                part.text,
                false,
                !part.is_input
            );
            tokens.insert(tokens.end(), part_tokens.begin(), part_tokens.end());
        }
    } else {
        tokens = common_tokenize(engine->context, full_prompt, false, false);
    }
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
    // Vulkan may enqueue work asynchronously. Include completion of that GPU work
    // in prefill time rather than charging the wait to the first generated token.
    if (dec_res == 0) {
        llama_synchronize(engine->context);
    }
    const int64_t prompt_duration_ms = (llama_time_us() - prompt_start_us) / 1000;
    engine->last_prompt_decode_us = prompt_duration_ms * 1000;
    engine->last_prompt_tokens = prompt_size;
    if (dec_res == -2) {
        engine->last_finish_reason = 2; // CANCELLED during prompt prefill
        LOGI("Prompt prefill cancelled cooperatively after %d of %d tokens",
             engine->last_prompt_tokens, prompt_size);
        return 6; // dedicated cancellation result; not a decode error
    }
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
    if (!engine) return nullptr;

    // Model unload/destruction uses the same mutex. Acquire it before inspecting
    // context/model pointers so a close cannot free llama_context during this token step.
    std::lock_guard<std::mutex> lock(engine->mtx);
    if (!engine->model || !engine->context) return nullptr;

    if (engine->generation_finished) return nullptr;

    if (engine->cancel_requested.load()) {
        LOGI("Inference cancelled cooperatively");
        engine->last_finish_reason = 2; // CANCELLED
        engine->generation_finished = true;
        return nullptr;
    }

    if (engine->current_position >= engine->context_size - OVERFLOW_HEADROOM) {
        LOGI("Context full, performing controlled shift...");
        engine->shift_context();
    }

    if (engine->current_position >= engine->stop_generation_position) {
        LOGI("Reached stop generation token count");
        engine->last_finish_reason = 1; // MAX_TOKENS
        engine->generation_finished = true;
        return flush_incomplete_utf8(env, engine);
    }

    if (engine->sampler == nullptr) {
        LOGE("Token generation requested without an initialized sampler");
        engine->last_finish_reason = 3; // ERROR
        return nullptr;
    }

    const int64_t token_step_start_us = llama_time_us();
    const int64_t sample_start_us = llama_time_us();
    const llama_token new_token_id = common_sampler_sample(engine->sampler, engine->context, -1);
    common_sampler_accept(engine->sampler, new_token_id, true);
    const int64_t sample_duration_us = llama_time_us() - sample_start_us;

    engine->batch.n_tokens = 0;
    if (!append_token_to_batch(engine->batch, new_token_id, engine->current_position, 0, true, engine->batch_size)) {
        engine->last_finish_reason = 3; // ERROR
        return nullptr;
    }

    const int64_t decode_start_us = llama_time_us();
    const int decode_result = llama_decode(engine->context, engine->batch);
    const int64_t decode_submit_duration_us = llama_time_us() - decode_start_us;
    if (decode_result == 2) {
        LOGI("Token decode aborted by native cancellation callback");
        engine->last_finish_reason = 2; // CANCELLED
        engine->generation_finished = true;
        return nullptr;
    }
    if (decode_result != 0) {
        LOGE("llama_decode failed during token generation with code %d", decode_result);
        engine->last_finish_reason = 3; // ERROR
        return nullptr;
    }
    // llama_decode can return after asynchronous backend work has been submitted.
    // Measure the full llama_synchronize() envelope separately: for Vulkan this may
    // include queue submission, fence polling/wait, and deferred host/device copies.
    // It is not a pure GPU-kernel timer; per-operation GPU timestamps are separate.
    const int64_t backend_sync_start_us = llama_time_us();
    llama_synchronize(engine->context);
    const int64_t backend_sync_wait_us = llama_time_us() - backend_sync_start_us;
    const int64_t decode_duration_us = decode_submit_duration_us + backend_sync_wait_us;

    engine->current_position++;

    const struct llama_vocab* vocab = llama_model_get_vocab(engine->model);
    if (llama_vocab_is_eog(vocab, new_token_id)) {
        const int64_t stop_step_duration_us = llama_time_us() - token_step_start_us;
        LOGI("Generated EOG token: %d (excluded from visible token count; stop_step_ms=%.3f)",
             new_token_id,
             static_cast<double>(stop_step_duration_us) / 1000.0);
        engine->last_finish_reason = 0; // STOP
        engine->generation_finished = true;
        return flush_incomplete_utf8(env, engine);
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

    const int64_t piece_start_us = llama_time_us();
    auto token_piece = common_token_to_piece(engine->context, new_token_id);
    const std::string valid_output =
        normalize_utf8_incremental(token_piece, engine->cached_token_chars);
    const int64_t piece_duration_us = llama_time_us() - piece_start_us;
    const int64_t token_total_duration_us = llama_time_us() - token_step_start_us;
    if (engine->tokens_generated <= 4 || engine->tokens_generated % 8 == 0) {
        std::ostringstream metric;
        metric << "TOKEN_STEP_METRIC - index=" << engine->tokens_generated
               << ", sampleUs=" << sample_duration_us
               << ", decodeUs=" << decode_duration_us
               << ", decodeSubmitUs=" << decode_submit_duration_us
               << ", backendSyncWaitUs=" << backend_sync_wait_us
               << ", pieceUs=" << piece_duration_us
               << ", totalUs=" << token_total_duration_us
               << ", pid=" << getpid()
               << ", runId=" << engine->inference_run_id
               << ", backend=" << engine->active_backend;
        log_native_info_message(metric.str());
    }
    if (valid_output.empty()) {
        return env->NewStringUTF("");
    }

    engine->assistant_ss << valid_output;
    return new_java_string_from_utf8(env, valid_output);
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

#if defined(LOCAL_LLM_ENABLE_TEST_HOOKS)
JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeArmBlockNextInferenceForTest(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->native_inference_block_entered_for_test.store(false);
    engine->block_next_inference_for_test.store(true);
    LOGW("DEBUG_TEST_HOOK: next nativeProcessChat will block (pid=%d)", getpid());
}

JNIEXPORT jboolean JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeIsBlockNextInferenceArmedForTest(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    return engine && engine->block_next_inference_for_test.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeIsNativeInferenceBlockEnteredForTest(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    return engine && engine->native_inference_block_entered_for_test.load() ? JNI_TRUE : JNI_FALSE;
}
#endif

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeResetCancellation(
        JNIEnv* /*env*/,
        jclass /*clazz*/,
        jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) {
        engine->cancel_requested.store(false);
    }
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
