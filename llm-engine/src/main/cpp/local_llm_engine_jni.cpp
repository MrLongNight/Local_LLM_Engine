#include <android/log.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <chrono>
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

    void unload() {
        std::lock_guard<std::mutex> lock(mtx);
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
};

static inline EngineInstance* fromHandle(jlong handle) {
    return reinterpret_cast<EngineInstance*>(handle);
}

static bool probeVulkanDevice(std::string& out_device_name) {
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "LocalLlmEngine";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "llama.cpp";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = vkCreateInstance(&createInfo, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGD("Vulkan vkCreateInstance returned %d", res);
        return false;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(devices[0], &props);
    out_device_name = props.deviceName;
    LOGI("Found Vulkan physical device: %s (Type: %d)", props.deviceName, props.deviceType);

    vkDestroyInstance(instance, nullptr);
    return true;
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

static int decode_tokens(EngineInstance* engine, const llama_tokens& tokens, llama_pos start_pos, bool want_last_logit) {
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
    }
    return 0;
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
    engine->unload();

    const char* model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (!model_path) return 2;

    LOGI("Loading GGUF model: %s (mode=%d, gpu_layers=%d)", model_path, backend_mode, gpu_layers);

    llama_model_params model_params = llama_model_default_params();

    // GPU / Vulkan offload resolution
    std::string vk_device_name;
    bool has_vulkan = probeVulkanDevice(vk_device_name);

    if (backend_mode == BACKEND_VULKAN) {
        if (!has_vulkan) {
            LOGE("Vulkan backend explicitly requested, but no Vulkan GPU device is available");
            env->ReleaseStringUTFChars(jmodel_path, model_path);
            return 101; // Vulkan init failure error code (no silent fallback!)
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
            LOGI("AUTO mode transparently fell back to CPU backend (Vulkan unavailable)");
        }
    } else { // BACKEND_CPU
        model_params.n_gpu_layers = 0;
        engine->active_backend = "CPU (ARM NEON / AVX)";
        LOGI("Configured CPU backend");
    }

    llama_model* model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGE("llama_model_load_from_file failed");
        return 3;
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

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama_context with dynamic n_ctx=%d", ctx_size);
        llama_model_free(model);
        engine->model = nullptr;
        return 4;
    }
    engine->context = ctx;

    engine->batch = llama_batch_init(b_size, 0, 1);
    engine->is_batch_allocated = true;

    engine->chat_templates = common_chat_templates_init(model, "");

    common_params_sampling sparams;
    sparams.temp = temperature >= 0.0f ? temperature : DEFAULT_TEMP;
    sparams.top_p = (top_p >= 0.0f && top_p <= 1.0f) ? top_p : 0.9f;
    engine->sampler = common_sampler_init(model, sparams);

    engine->reset_short_term();
    engine->reset_long_term(true);
    engine->cancel_requested.store(false);

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
    engine->cancel_requested.store(false);
    engine->reset_short_term();
    engine->reset_long_term(true);

    jsize count = env->GetArrayLength(jroles);
    if (count == 0 || count != env->GetArrayLength(jcontents)) {
        return 2;
    }

    const bool has_chat_template = common_chat_templates_was_explicit(engine->chat_templates.get());
    std::string full_prompt;

    for (jsize i = 0; i < count; i++) {
        auto jrole = (jstring)env->GetObjectArrayElement(jroles, i);
        auto jcontent = (jstring)env->GetObjectArrayElement(jcontents, i);
        const char* role_str = env->GetStringUTFChars(jrole, nullptr);
        const char* content_str = env->GetStringUTFChars(jcontent, nullptr);

        common_chat_msg msg;
        msg.role = role_str ? role_str : "user";
        msg.content = content_str ? content_str : "";

        if (has_chat_template) {
            bool is_last_user = (i == count - 1 && msg.role == "user");
            std::string formatted = common_chat_format_single(
                    engine->chat_templates.get(),
                    engine->chat_msgs,
                    msg,
                    is_last_user,
                    /* use_jinja */ false);
            full_prompt += formatted;
        } else {
            full_prompt += msg.role + ": " + msg.content + "\n";
            if (i == count - 1) {
                full_prompt += "assistant: ";
            }
        }
        engine->chat_msgs.push_back(msg);

        env->ReleaseStringUTFChars(jrole, role_str);
        env->ReleaseStringUTFChars(jcontent, content_str);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
    }

    auto tokens = common_tokenize(engine->context, full_prompt, has_chat_template, has_chat_template);
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

    int dec_res = decode_tokens(engine, tokens, engine->current_position, true);
    if (dec_res != 0) {
        LOGE("Prompt decoding failed with code %d", dec_res);
        return 4;
    }

    engine->current_position += prompt_size;
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
    engine->tokens_generated++;

    const struct llama_vocab* vocab = llama_model_get_vocab(engine->model);
    if (llama_vocab_is_eog(vocab, new_token_id)) {
        LOGI("Generated EOG token: %d", new_token_id);
        engine->last_finish_reason = 0; // STOP
        return nullptr;
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
