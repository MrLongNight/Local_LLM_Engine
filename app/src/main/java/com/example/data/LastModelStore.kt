package com.example.data

import android.content.Context
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.TimeoutConfig
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import java.io.File

/**
 * Persists only the last successfully loaded model source and its load-time settings.
 * Data stays in app-private SharedPreferences; the GGUF itself is never copied here.
 */
internal class LastModelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(source: ModelSource, config: EngineConfig, resolvedDisplayName: String? = null) {
        val sourceType: String
        val sourceValue: String
        val displayName: String?
        when (source) {
            is ModelSource.FilePath -> {
                sourceType = TYPE_FILE
                sourceValue = source.path
                displayName = File(source.path).name
            }
            is ModelSource.ContentUri -> {
                sourceType = TYPE_CONTENT_URI
                sourceValue = source.uriString
                displayName = source.displayName ?: resolvedDisplayName
            }
            else -> throw IllegalArgumentException(
                "Only app-private files and persisted SAF document URIs can be remembered"
            )
        }
        require(sourceValue.isNotBlank()) { "Model source must not be blank" }

        preferences.edit()
            .putInt(KEY_VERSION, VERSION)
            .putString(KEY_SOURCE_TYPE, sourceType)
            .putString(KEY_SOURCE_VALUE, sourceValue)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putInt(KEY_CONTEXT_SIZE, config.contextSize)
            .putInt(KEY_BATCH_SIZE, config.batchSize)
            .putInt(KEY_THREADS, config.threads)
            .putFloat(KEY_TEMPERATURE, config.temperature)
            .putFloat(KEY_TOP_P, config.topP)
            .putInt(KEY_MAX_PREDICT_TOKENS, config.maxPredictTokens)
            .putString(KEY_BACKEND, config.backendPreference.name)
            .putInt(KEY_GPU_LAYERS, config.gpuLayers)
            .putInt(KEY_BATCH_THREADS, config.batchThreads)
            .putInt(KEY_MICRO_BATCH_SIZE, config.microBatchSize)
            .putString(KEY_VULKAN_MATVEC_WORKGROUP, config.vulkanMatvecWorkgroup.name)
            .putString(KEY_FLASH_ATTENTION_MODE, config.flashAttentionMode.name)
            .putString(KEY_RELOAD_AFTER_ABORT, config.reloadAfterAbort.name)
            .putString(KEY_GDN_POLICY, config.gdnPolicy.name)
            .putInt(KEY_TOP_K, config.topK)
            .putFloat(KEY_MIN_P, config.minP)
            .putLong(KEY_TIMEOUT_BIND, config.timeoutConfig.serviceBindMs)
            .putLong(KEY_TIMEOUT_MODEL_LOAD, config.timeoutConfig.modelLoadMs)
            .putLong(KEY_TIMEOUT_PREFILL, config.timeoutConfig.prefillMs)
            .putLong(KEY_TIMEOUT_FIRST_TOKEN, config.timeoutConfig.firstTokenMs)
            .putLong(KEY_TIMEOUT_INTER_TOKEN, config.timeoutConfig.interTokenMs)
            .putLong(KEY_TIMEOUT_TOTAL, config.timeoutConfig.totalInferenceMs)
            .putLong(KEY_TIMEOUT_ABORT_GRACE, config.timeoutConfig.abortGracePeriodMs)
            .putLong(KEY_TIMEOUT_RECONNECT, config.timeoutConfig.reconnectMs)
            .apply()
    }

    fun load(): RememberedModel? {
        if (!preferences.contains(KEY_SOURCE_TYPE)) return null
        val version = preferences.getInt(KEY_VERSION, VERSION)
        require(version == VERSION) { "Unsupported remembered-model version: $version" }

        val sourceValue = preferences.getString(KEY_SOURCE_VALUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Remembered model source is empty")
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
        val source = when (preferences.getString(KEY_SOURCE_TYPE, null)) {
            TYPE_FILE -> ModelSource.FilePath(sourceValue)
            TYPE_CONTENT_URI -> ModelSource.ContentUri(sourceValue, displayName)
            else -> throw IllegalStateException("Unknown remembered model source type")
        }

        val timeoutDefaults = TimeoutConfig()
        val timeout = TimeoutConfig(
            serviceBindMs = preferences.getLong(KEY_TIMEOUT_BIND, timeoutDefaults.serviceBindMs),
            modelLoadMs = preferences.getLong(KEY_TIMEOUT_MODEL_LOAD, timeoutDefaults.modelLoadMs),
            prefillMs = preferences.getLong(KEY_TIMEOUT_PREFILL, timeoutDefaults.prefillMs),
            firstTokenMs = preferences.getLong(KEY_TIMEOUT_FIRST_TOKEN, timeoutDefaults.firstTokenMs),
            interTokenMs = preferences.getLong(KEY_TIMEOUT_INTER_TOKEN, timeoutDefaults.interTokenMs),
            totalInferenceMs = preferences.getLong(KEY_TIMEOUT_TOTAL, timeoutDefaults.totalInferenceMs),
            abortGracePeriodMs = preferences.getLong(KEY_TIMEOUT_ABORT_GRACE, timeoutDefaults.abortGracePeriodMs),
            reconnectMs = preferences.getLong(KEY_TIMEOUT_RECONNECT, timeoutDefaults.reconnectMs)
        )
        val config = EngineConfig(
            contextSize = preferences.getInt(KEY_CONTEXT_SIZE, 2048),
            batchSize = preferences.getInt(KEY_BATCH_SIZE, 128),
            threads = preferences.getInt(KEY_THREADS, 4),
            temperature = preferences.getFloat(KEY_TEMPERATURE, 0.7f),
            topP = preferences.getFloat(KEY_TOP_P, 0.9f),
            maxPredictTokens = preferences.getInt(KEY_MAX_PREDICT_TOKENS, 512),
            backendPreference = runCatching {
                BackendPreference.valueOf(
                    preferences.getString(KEY_BACKEND, BackendPreference.VULKAN.name)
                        ?: BackendPreference.VULKAN.name
                )
            }.getOrDefault(BackendPreference.VULKAN),
            gpuLayers = preferences.getInt(KEY_GPU_LAYERS, 99),
            timeoutConfig = timeout,
            batchThreads = preferences.getInt(KEY_BATCH_THREADS, 0),
            microBatchSize = preferences.getInt(KEY_MICRO_BATCH_SIZE, 0),
            vulkanMatvecWorkgroup = runCatching {
                VulkanMatvecWorkgroup.valueOf(
                    preferences.getString(KEY_VULKAN_MATVEC_WORKGROUP, VulkanMatvecWorkgroup.AUTO.name)
                        ?: VulkanMatvecWorkgroup.AUTO.name
                )
            }.getOrDefault(VulkanMatvecWorkgroup.AUTO),
            flashAttentionMode = runCatching {
                FlashAttentionMode.valueOf(
                    preferences.getString(KEY_FLASH_ATTENTION_MODE, FlashAttentionMode.AUTO.name)
                        ?: FlashAttentionMode.AUTO.name
                )
            }.getOrDefault(FlashAttentionMode.AUTO),
            reloadAfterAbort = runCatching {
                ReloadAfterAbort.valueOf(
                    preferences.getString(KEY_RELOAD_AFTER_ABORT, ReloadAfterAbort.LAZY.name)
                        ?: ReloadAfterAbort.LAZY.name
                )
            }.getOrDefault(ReloadAfterAbort.LAZY),
            gdnPolicy = runCatching {
                GdnPolicy.valueOf(
                    preferences.getString(KEY_GDN_POLICY, GdnPolicy.ARCH_DEFAULT.name)
                        ?: GdnPolicy.ARCH_DEFAULT.name
                )
            }.getOrDefault(GdnPolicy.ARCH_DEFAULT),
            topK = preferences.getInt(KEY_TOP_K, 20),
            minP = preferences.getFloat(KEY_MIN_P, 0.05f)
        )
        return RememberedModel(source, config)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    data class RememberedModel(
        val source: ModelSource,
        val config: EngineConfig
    )

    private companion object {
        const val PREFERENCES_NAME = "last_model_restore"
        const val VERSION = 1

        const val TYPE_FILE = "file"
        const val TYPE_CONTENT_URI = "content_uri"

        const val KEY_VERSION = "version"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_VALUE = "source_value"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_CONTEXT_SIZE = "context_size"
        const val KEY_BATCH_SIZE = "batch_size"
        const val KEY_THREADS = "threads"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_PREDICT_TOKENS = "max_predict_tokens"
        const val KEY_BACKEND = "backend"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_BATCH_THREADS = "batch_threads"
        const val KEY_MICRO_BATCH_SIZE = "micro_batch_size"
        const val KEY_VULKAN_MATVEC_WORKGROUP = "vulkan_matvec_workgroup"
        const val KEY_RELOAD_AFTER_ABORT = "reload_after_abort"
        const val KEY_GDN_POLICY = "gdn_policy"
        const val KEY_TOP_K = "sampler_top_k"
        const val KEY_MIN_P = "sampler_min_p"
        const val KEY_FLASH_ATTENTION_MODE = "flash_attention_mode"
        const val KEY_TIMEOUT_BIND = "timeout_bind_ms"
        const val KEY_TIMEOUT_MODEL_LOAD = "timeout_model_load_ms"
        const val KEY_TIMEOUT_PREFILL = "timeout_prefill_ms"
        const val KEY_TIMEOUT_FIRST_TOKEN = "timeout_first_token_ms"
        const val KEY_TIMEOUT_INTER_TOKEN = "timeout_inter_token_ms"
        const val KEY_TIMEOUT_TOTAL = "timeout_total_ms"
        const val KEY_TIMEOUT_ABORT_GRACE = "timeout_abort_grace_ms"
        const val KEY_TIMEOUT_RECONNECT = "timeout_reconnect_ms"
    }
}
