#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <exception>
#include <iterator>
#include <limits>
#include <unistd.h>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "ggml-backend.h"

#define TAG "LocalLlmEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static constexpr int OVERFLOW_HEADROOM = 8;
static constexpr int BATCH_SIZE = 512;
static constexpr float DEFAULT_TEMP = 0.7f;

static constexpr int FINISH_STOP = 0;
static constexpr int FINISH_MAX_TOKENS = 1;
static constexpr int FINISH_CANCELLED = 2;
static constexpr int FINISH_ERROR = 3;


enum BackendMode {
    BACKEND_AUTO = 0,
    BACKEND_VULKAN = 1,
    BACKEND_CPU = 2
};

struct EngineInstance {
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    llama_batch batch{};
    common_sampler* sampler = nullptr;
    float default_temperature = DEFAULT_TEMP;
    float default_top_p = 0.9f;
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
    int32_t max_predict_tokens = 1024;
    std::string cached_token_chars;
    std::ostringstream assistant_ss;
    bool is_batch_allocated = false;

    // Inference tracking
    int32_t last_finish_reason = FINISH_ERROR;
    int64_t inference_start_us = 0;
    int32_t tokens_generated = 0;

    void reset_short_term() {
        // Reset the sampler's repetition/grammar/history state whenever a new
        // independent request or model lifecycle reset starts.
        if (sampler) {
            common_sampler_reset(sampler);
        }
        max_predict_tokens = 1024;
        cached_token_chars.clear();
        assistant_ss.str("");
        last_finish_reason = FINISH_ERROR;
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

    void swap_model_resources(EngineInstance& other) {
        using std::swap;
        swap(model, other.model);
        swap(context, other.context);
        swap(batch, other.batch);
        swap(sampler, other.sampler);
        swap(chat_templates, other.chat_templates);
        swap(context_size, other.context_size);
        swap(batch_size, other.batch_size);
        swap(n_threads, other.n_threads);
        swap(active_backend, other.active_backend);
        swap(default_temperature, other.default_temperature);
        swap(default_top_p, other.default_top_p);
        swap(is_batch_allocated, other.is_batch_allocated);
    }

    void unload_locked() {
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
        unload_locked();
    }
};

static bool abort_decode(void* user_data) {
    auto* engine = static_cast<EngineInstance*>(user_data);
    return engine == nullptr || engine->cancel_requested.load(std::memory_order_acquire);
}

/**
 * Evaluate prompt tokens in bounded batches. The llama context's abort callback
 * is also active inside llama_decode(), so cancellation works during prefill.
 * Only the final token of the complete prompt requests logits for sampling.
 */
static int decode_tokens(
        EngineInstance* engine,
        const std::vector<llama_token>& tokens,
        llama_pos start_position,
        bool request_last_logits) {
    if (engine == nullptr || engine->context == nullptr || !engine->is_batch_allocated) {
        return 1;
    }

    const size_t batch_capacity = static_cast<size_t>(std::max(1, engine->batch_size));
    for (size_t offset = 0; offset < tokens.size(); offset += batch_capacity) {
        if (engine->cancel_requested.load(std::memory_order_acquire)) {
            return -2;
        }

        common_batch_clear(engine->batch);
        const size_t count = std::min(batch_capacity, tokens.size() - offset);
        for (size_t i = 0; i < count; ++i) {
            const size_t token_index = offset + i;
            const bool request_logits = request_last_logits && token_index + 1 == tokens.size();
            common_batch_add(
                    engine->batch,
                    tokens[token_index],
                    start_position + static_cast<llama_pos>(token_index),
                    {0},
                    request_logits
            );
        }

        const int result = llama_decode(engine->context, engine->batch);
        if (result != 0) {
            return engine->cancel_requested.load(std::memory_order_acquire) ? -2 : result;
        }
    }
    return 0;
}

static inline EngineInstance* fromHandle(jlong handle) {
    return reinterpret_cast<EngineInstance*>(handle);
}

static bool getVulkanBackendDeviceName(std::string& out_device_name) {
    // Check llama.cpp's registered backend, not merely Android's Vulkan loader.
    (void) llama_supports_gpu_offload();
    ggml_backend_reg_t registry = ggml_backend_reg_by_name("Vulkan");
    if (registry == nullptr || ggml_backend_reg_dev_count(registry) == 0) return false;
    ggml_backend_dev_t device = ggml_backend_reg_dev_get(registry, 0);
    if (device == nullptr) return false;
    const char* name = ggml_backend_dev_name(device);
    out_device_name = name != nullptr ? name : "Vulkan device";
    LOGI("Registered llama.cpp Vulkan backend device: %s", out_device_name.c_str());
    return true;
}

static void append_codepoint_utf8(std::string& output, uint32_t codepoint) {
    if (codepoint <= 0x7F) {
        output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7FF) {
        output.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
    } else if (codepoint <= 0xFFFF) {
        output.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
    } else {
        output.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
    }
}

static bool utf8_from_jstring(JNIEnv* env, jstring input, std::string& output) {
    if (input == nullptr) return false;
    const jsize length = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (chars == nullptr) return false;

    output.clear();
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF) {
            if (i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                codepoint = 0x10000 + (((codepoint - 0xD800) << 10) | (chars[++i] - 0xDC00));
            } else {
                codepoint = 0xFFFD;
            }
        } else if (codepoint >= 0xDC00 && codepoint <= 0xDFFF) {
            codepoint = 0xFFFD;
        }
        append_codepoint_utf8(output, codepoint);
    }
    env->ReleaseStringChars(input, chars);
    return true;
}

static bool is_valid_utf8(const std::string& value) {
    size_t i = 0;
    while (i < value.size()) {
        const uint8_t lead = static_cast<uint8_t>(value[i]);
        uint32_t codepoint;
        size_t length;
        uint32_t minimum;
        if (lead <= 0x7F) {
            ++i;
            continue;
        } else if (lead >= 0xC2 && lead <= 0xDF) {
            codepoint = lead & 0x1F;
            length = 2;
            minimum = 0x80;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            codepoint = lead & 0x0F;
            length = 3;
            minimum = 0x800;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            codepoint = lead & 0x07;
            length = 4;
            minimum = 0x10000;
        } else {
            return false;
        }
        if (i + length > value.size()) return false;
        for (size_t j = 1; j < length; ++j) {
            const uint8_t next = static_cast<uint8_t>(value[i + j]);
            if ((next & 0xC0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        if (codepoint < minimum || codepoint > 0x10FFFF ||
            (codepoint >= 0xD800 && codepoint <= 0xDFFF)) return false;
        i += length;
    }
    return true;
}

static jstring jstring_from_utf8(JNIEnv* env, const std::string& value) {
    if (!is_valid_utf8(value)) return nullptr;
    std::vector<jchar> utf16;
    utf16.reserve(value.size());

    size_t i = 0;
    while (i < value.size()) {
        const uint8_t lead = static_cast<uint8_t>(value[i]);
        uint32_t codepoint;
        size_t length;
        if (lead <= 0x7F) {
            codepoint = lead;
            length = 1;
        } else if (lead <= 0xDF) {
            codepoint = lead & 0x1F;
            length = 2;
        } else if (lead <= 0xEF) {
            codepoint = lead & 0x0F;
            length = 3;
        } else {
            codepoint = lead & 0x07;
            length = 4;
        }
        for (size_t j = 1; j < length; ++j) {
            codepoint = (codepoint << 6) |
                    (static_cast<uint8_t>(value[i + j]) & 0x3F);
        }
        i += length;

        if (codepoint <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        }
    }

    if (utf16.empty()) return env->NewStringUTF("");
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

extern "C" {

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

JNIEXPORT jboolean JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeIsVulkanBackendAvailable(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    std::string device_name;
    return getVulkanBackendDeviceName(device_name) ? JNI_TRUE : JNI_FALSE;
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
        jint gpu_layers) {
    auto* engine = fromHandle(handle);
    if (!engine) return 1;

    std::lock_guard<std::mutex> lock(engine->mtx);

    std::string model_path;
    if (!utf8_from_jstring(env, jmodel_path, model_path) || model_path.empty()) {
        LOGE("Model path is null, empty, or could not be converted to UTF-8");
        return 2;
    }

    // Keep the active model alive until the replacement has loaded completely.
    // On any failure, release partial resources and restore the previous model.
    EngineInstance previous;
    engine->swap_model_resources(previous);
    engine->reset_short_term();
    engine->reset_long_term(false);
    auto restore_previous = [&](int error_code) {
        engine->unload_locked();
        engine->swap_model_resources(previous);
        engine->reset_short_term();
        engine->reset_long_term(false);
        engine->cancel_requested.store(false, std::memory_order_release);
        return error_code;
    };

    LOGI("Loading GGUF model transactionally: %s (mode=%d, gpu_layers=%d)",
         model_path.c_str(), backend_mode, gpu_layers);

    llama_model_params model_params = llama_model_default_params();

    // GPU / Vulkan offload resolution
    std::string vk_device_name;
    bool has_vulkan = getVulkanBackendDeviceName(vk_device_name);

    if (backend_mode == BACKEND_VULKAN) {
        if (!has_vulkan) {
            LOGE("Vulkan backend explicitly requested, but no registered llama.cpp Vulkan backend is available");
            return restore_previous(101); // Explicit Vulkan never silently falls back.
        }
        model_params.n_gpu_layers = gpu_layers > 0 ? gpu_layers : 999;
        engine->active_backend = "Vulkan GPU (" + vk_device_name + ")";
        LOGI("Configured explicit Vulkan GPU offload with %d layers", model_params.n_gpu_layers);
    } else if (backend_mode == BACKEND_AUTO) {
        if (has_vulkan) {
            model_params.n_gpu_layers = gpu_layers > 0 ? gpu_layers : 999;
            engine->active_backend = "Vulkan GPU (" + vk_device_name + ")";
            LOGI("AUTO mode selected Vulkan GPU (%s) with %d layers", vk_device_name.c_str(), model_params.n_gpu_layers);
        } else {
            model_params.n_gpu_layers = 0;
            engine->active_backend = "CPU (ARM NEON / AVX)";
            LOGI("AUTO mode transparently fell back to CPU backend (no registered llama.cpp GPU backend)");
        }
    } else { // BACKEND_CPU
        model_params.n_gpu_layers = 0;
        engine->active_backend = "CPU (ARM NEON / AVX)";
        LOGI("Configured CPU backend");
    }

    llama_model* model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model && backend_mode == BACKEND_AUTO && has_vulkan) {
        LOGW("AUTO Vulkan model load failed; retrying model load on CPU");
        model_params.n_gpu_layers = 0;
        engine->active_backend = "CPU (ARM NEON / AVX)";
        model = llama_model_load_from_file(model_path.c_str(), model_params);
    }

    if (!model) {
        LOGE("llama_model_load_from_file failed on the selected backend");
        return restore_previous(3);
    }
    engine->model = model;

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
    ctx_params.abort_callback = abort_decode;
    ctx_params.abort_callback_data = engine;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama_context with dynamic n_ctx=%d", ctx_size);
        return restore_previous(4);
    }
    engine->context = ctx;

    engine->batch = llama_batch_init(b_size, 0, 1);
    engine->is_batch_allocated = true;
    if (engine->batch.token == nullptr || engine->batch.pos == nullptr ||
        engine->batch.n_seq_id == nullptr || engine->batch.seq_id == nullptr ||
        engine->batch.logits == nullptr) {
        LOGE("Failed to allocate llama_batch buffers for batch size %d", b_size);
        return restore_previous(5);
    }

    // A malformed or unsupported GGUF Jinja template must not let a C++ exception
    // cross the JNI boundary. Keep the model usable with the plain role/content format.
    try {
        engine->chat_templates = common_chat_templates_init(model, "");
    } catch (const std::exception& error) {
        LOGW("Could not initialize model chat template; using role/content fallback: %s", error.what());
        engine->chat_templates.reset();
    } catch (...) {
        LOGW("Could not initialize model chat template; using role/content fallback");
        engine->chat_templates.reset();
    }

    common_params_sampling sparams;
    sparams.temp = temperature >= 0.0f ? temperature : DEFAULT_TEMP;
    sparams.top_p = (top_p >= 0.0f && top_p <= 1.0f) ? top_p : 0.9f;
    engine->default_temperature = sparams.temp;
    engine->default_top_p = sparams.top_p;
    engine->sampler = common_sampler_init(model, sparams);
    if (engine->sampler == nullptr) {
        LOGE("Failed to initialize llama.cpp sampler");
        return restore_previous(6);
    }

    engine->reset_short_term();
    engine->reset_long_term(true);
    engine->cancel_requested.store(false);
    // The newly loaded model is now active; free the previously loaded resources.
    previous.unload_locked();

    LOGI("Model loaded: n_ctx=%d, batch=%d, threads=%d, active_backend=%s",
         ctx_size, b_size, n_threads, engine->active_backend.c_str());
    return 0;
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

JNIEXPORT jstring JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeGetModelArchitecture(
        JNIEnv* env, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model) return env->NewStringUTF("unknown");
    char architecture[128] = {};
    const int32_t result = llama_model_meta_val_str(
            engine->model, "general.architecture", architecture, sizeof(architecture));
    if (result < 0 || architecture[0] == '\0') return env->NewStringUTF("unknown");
    return env->NewStringUTF(architecture);
}

JNIEXPORT void JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeResetCancellation(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) engine->cancel_requested.store(false, std::memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeSetRequestSampling(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jfloat temperature, jfloat top_p) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model) return 1;
    if (!std::isfinite(temperature) || (temperature < 0.0f && temperature != -1.0f)) return 2;
    if (!std::isfinite(top_p) || (top_p < 0.0f && top_p != -1.0f) || top_p > 1.0f) return 4;

    std::lock_guard<std::mutex> lock(engine->mtx);
    common_params_sampling sparams;
    sparams.temp = temperature >= 0.0f ? temperature : engine->default_temperature;
    sparams.top_p = top_p >= 0.0f ? top_p : engine->default_top_p;
    common_sampler* replacement = common_sampler_init(engine->model, sparams);
    if (!replacement) return 3;

    if (engine->sampler) common_sampler_free(engine->sampler);
    engine->sampler = replacement;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_mydocanizer_llm_engine_internal_NativeEngineHandle_nativeProcessChat(
        JNIEnv* env,
        jclass /*clazz*/,
        jlong handle,
        jobjectArray jroles,
        jobjectArray jcontents,
        jint max_predict) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->model || !engine->context) return 1;

    std::lock_guard<std::mutex> lock(engine->mtx);
    engine->reset_short_term();
    engine->reset_long_term(true);
    engine->last_finish_reason = FINISH_ERROR;
    engine->inference_start_us = llama_time_us();
    if (engine->cancel_requested.load(std::memory_order_acquire)) {
        engine->last_finish_reason = FINISH_CANCELLED;
        return -2;
    }

    jsize count = env->GetArrayLength(jroles);
    if (count == 0 || count != env->GetArrayLength(jcontents)) {
        return 2;
    }

    std::vector<common_chat_msg> requested_messages;
    requested_messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto jrole = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jcontent = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        if (jrole == nullptr || jcontent == nullptr) {
            if (jrole) env->DeleteLocalRef(jrole);
            if (jcontent) env->DeleteLocalRef(jcontent);
            LOGE("Chat message contained a null role or content");
            return 3;
        }

        std::string role_str;
        std::string content_str;
        if (!utf8_from_jstring(env, jrole, role_str) ||
            !utf8_from_jstring(env, jcontent, content_str)) {
            env->DeleteLocalRef(jrole);
            env->DeleteLocalRef(jcontent);
            return 3;
        }

        common_chat_msg message;
        message.role = std::move(role_str);
        message.content = std::move(content_str);
        requested_messages.push_back(std::move(message));

        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
    }

    const bool has_chat_template = engine->chat_templates != nullptr &&
            common_chat_templates_was_explicit(engine->chat_templates.get());
    // 2 = Jinja renderer, 1 = legacy template renderer, 0 = plain role/content fallback.
    int template_mode = has_chat_template ? 2 : 0;

    auto build_prompt = [&](const std::vector<common_chat_msg>& messages, int mode) -> std::string {
        engine->chat_msgs.clear();
        std::string prompt;
        for (size_t i = 0; i < messages.size(); ++i) {
            const auto& message = messages[i];
            const bool add_assistant = (i == messages.size() - 1 && message.role == "user");

            if (mode == 2 || mode == 1) {
                const std::string formatted = common_chat_format_single(
                        engine->chat_templates.get(),
                        engine->chat_msgs,
                        message,
                        add_assistant,
                        mode == 2
                );
                prompt += formatted;
            } else {
                prompt += message.role + ": " + message.content + "\n";
                if (add_assistant) prompt += "assistant: ";
            }
            engine->chat_msgs.push_back(message);
        }
        return prompt;
    };

    auto render_prompt = [&](const std::vector<common_chat_msg>& messages) -> std::string {
        if (template_mode == 2) {
            try {
                return build_prompt(messages, 2);
            } catch (const std::exception& error) {
                LOGW("Jinja chat template failed; trying legacy formatter: %s", error.what());
                template_mode = 1;
            }
        }
        if (template_mode == 1) {
            try {
                return build_prompt(messages, 1);
            } catch (const std::exception& error) {
                LOGW("Legacy chat template failed; using role/content fallback: %s", error.what());
                template_mode = 0;
            }
        }
        return build_prompt(messages, 0);
    };

    std::vector<common_chat_msg> selected_messages = requested_messages;
    std::string full_prompt = render_prompt(selected_messages);
    auto tokens = common_tokenize(engine->context, full_prompt, template_mode != 0, template_mode != 0);
    if (tokens.empty()) {
        LOGE("Prompt tokenization returned empty tokens");
        engine->last_finish_reason = FINISH_ERROR;
        return 3;
    }

    const int max_allowed = engine->context_size - OVERFLOW_HEADROOM;
    size_t removed_message_count = 0;
    while (tokens.size() > static_cast<size_t>(max_allowed)) {
        if (engine->cancel_requested.load(std::memory_order_acquire)) {
            engine->last_finish_reason = FINISH_CANCELLED;
            return -2;
        }

        // Keep every system/developer message and the newest message. Remove the
        // oldest complete conversational turn instead of truncating the prompt tail.
        const size_t last_index = selected_messages.size() - 1;
        const auto first_old_message = std::find_if(
                selected_messages.begin(),
                selected_messages.begin() + static_cast<std::ptrdiff_t>(last_index),
                [](const common_chat_msg& message) {
                    return message.role != "system" && message.role != "developer";
                }
        );

        if (first_old_message == selected_messages.begin() + static_cast<std::ptrdiff_t>(last_index)) {
            LOGE("System/developer prompt and newest message exceed the model context window");
            engine->last_finish_reason = FINISH_ERROR;
            return 5;
        }

        const size_t erase_start = static_cast<size_t>(
                std::distance(selected_messages.begin(), first_old_message));
        size_t erase_end = erase_start + 1;
        while (erase_end < last_index) {
            const std::string& role = selected_messages[erase_end].role;
            if (role == "user" || role == "system" || role == "developer") break;
            ++erase_end;
        }

        if (erase_end <= erase_start) {
            engine->last_finish_reason = FINISH_ERROR;
            return 5;
        }
        removed_message_count += erase_end - erase_start;
        selected_messages.erase(
                selected_messages.begin() + static_cast<std::ptrdiff_t>(erase_start),
                selected_messages.begin() + static_cast<std::ptrdiff_t>(erase_end)
        );

        full_prompt = render_prompt(selected_messages);
        tokens = common_tokenize(engine->context, full_prompt, template_mode != 0, template_mode != 0);
        if (tokens.empty()) {
            LOGE("Prompt tokenization returned empty tokens after trimming conversation history");
            engine->last_finish_reason = FINISH_ERROR;
            return 3;
        }
    }

    if (removed_message_count > 0) {
        LOGW("Trimmed %zu older chat messages at turn boundaries to fit context", removed_message_count);
    }

    engine->system_prompt_position = 0;
    std::vector<common_chat_msg> protected_messages;
    for (const auto& message : selected_messages) {
        if (message.role == "system" || message.role == "developer") {
            protected_messages.push_back(message);
        } else {
            break;
        }
    }
    if (!protected_messages.empty()) {
        // The prompt is composed incrementally with the same formatter, so tokenizing
        // its leading system/developer segment gives the KV boundary that context
        // shifting must never remove.
        const std::string protected_prompt = build_prompt(protected_messages, template_mode);
        const bool parse_special_tokens = template_mode != 0;
        const auto protected_tokens = common_tokenize(
                engine->context, protected_prompt, parse_special_tokens, parse_special_tokens);
        if (protected_tokens.empty()) {
            LOGE("Could not tokenize protected system/developer prompt prefix");
            return 3;
        }
        engine->system_prompt_position = static_cast<llama_pos>(
                std::min(protected_tokens.size(), tokens.size()));
        // Restore the complete conversation formatter state after the prefix pass.
        (void) build_prompt(selected_messages, template_mode);
    }

    int prompt_size = static_cast<int>(tokens.size());

    int dec_res = decode_tokens(engine, tokens, engine->current_position, true);
    if (dec_res != 0) {
        if (engine->cancel_requested.load(std::memory_order_acquire) || dec_res == -2) {
            engine->last_finish_reason = FINISH_CANCELLED;
            return -2;
        }
        LOGE("Prompt decoding failed with code %d", dec_res);
        engine->last_finish_reason = FINISH_ERROR;
        return 4;
    }
    engine->current_position += prompt_size;
    engine->max_predict_tokens = max_predict > 0 ? max_predict : 1024;
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
        engine->last_finish_reason = FINISH_CANCELLED;
        return nullptr;
    }

    if (engine->current_position >= engine->context_size - OVERFLOW_HEADROOM) {
        LOGI("Context full, performing controlled shift...");
        engine->shift_context();
    }

    if (engine->tokens_generated >= engine->max_predict_tokens) {
        LOGI("Reached stop generation token count");
        engine->last_finish_reason = FINISH_MAX_TOKENS;
        return nullptr;
    }

    const llama_token new_token_id = common_sampler_sample(engine->sampler, engine->context, -1);
    common_sampler_accept(engine->sampler, new_token_id, true);

    common_batch_clear(engine->batch);
    common_batch_add(engine->batch, new_token_id, engine->current_position, {0}, true);

    if (llama_decode(engine->context, engine->batch) != 0) {
        LOGE("llama_decode failed during token generation");
        engine->last_finish_reason = engine->cancel_requested.load(std::memory_order_acquire)
                ? FINISH_CANCELLED : FINISH_ERROR;
        return nullptr;
    }

    engine->current_position++;
    engine->tokens_generated++;

    const struct llama_vocab* vocab = llama_model_get_vocab(engine->model);
    if (llama_vocab_is_eog(vocab, new_token_id)) {
        LOGI("Generated EOG token: %d", new_token_id);
        engine->last_finish_reason = FINISH_STOP;
        return nullptr;
    }

    auto token_piece = common_token_to_piece(engine->context, new_token_id);
    engine->cached_token_chars += token_piece;

    if (is_valid_utf8(engine->cached_token_chars)) {
        jstring result = jstring_from_utf8(env, engine->cached_token_chars);
        if (result == nullptr) {
            engine->last_finish_reason = FINISH_ERROR;
            return nullptr;
        }
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
