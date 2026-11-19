package com.example.ui

import com.example.ui.modelimport.LastModelStore
import android.content.Context
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LastModelStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LastModelStore

    @Before
    fun setUp() {
        context.getSharedPreferences("last_model_restore", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = LastModelStore(context)
    }

    @Test
    fun persistsLastSafModelAndExactLoadConfiguration() {
        val config = EngineConfig(
            contextSize = 4096,
            batchSize = 256,
            threads = 6,
            temperature = 0.25f,
            topP = 0.95f,
            maxPredictTokens = 384,
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 37,
            timeoutConfig = TimeoutConfig(modelLoadMs = 240_000L),
            batchThreads = 3,
            microBatchSize = 64,
            vulkanMatvecWorkgroup = VulkanMatvecWorkgroup.LARGE,
            flashAttentionMode = FlashAttentionMode.ENABLED,
            reloadAfterAbort = ReloadAfterAbort.EAGER,
            gdnPolicy = GdnPolicy.AR_UNFUSED_CH_FUSED
        )
        val source = ModelSource.ContentUri(
            uriString = "content://com.android.externalstorage.documents/document/primary%3AModels%2Fqwen.gguf"
        )

        store.save(source, config, resolvedDisplayName = "qwen.gguf")
        val restored = store.load()

        assertNotNull(restored)
        assertEquals(source.copy(displayName = "qwen.gguf"), restored?.source)
        assertEquals(config.copy(timeoutConfig = TimeoutConfig()), restored?.config)
    }

    @Test
    fun modelTimeoutOverridesAreNotPersistedWithTheRememberedModel() {
        assertEquals(3_000L, TimeoutConfig().abortGracePeriodMs)
        val source = ModelSource.FilePath("/private/models/timeout-test.gguf")
        val config = EngineConfig(
            timeoutConfig = TimeoutConfig(
                prefillMs = 2_000L,
                abortGracePeriodMs = 300L
            )
        )

        store.save(source, config)

        val preferences = context.getSharedPreferences("last_model_restore", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("timeout_abort_grace_ms"))
        assertFalse(preferences.contains("timeout_prefill_ms"))
        assertEquals(3_000L, store.load()?.config?.timeoutConfig?.abortGracePeriodMs)
        assertEquals(120_000L, store.load()?.config?.timeoutConfig?.prefillMs)
    }

    @Test
    fun legacyRememberedTimeoutOverridesAreIgnoredAndRemoved() {
        val preferences = context.getSharedPreferences("last_model_restore", Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("version", 1)
            .putString("source_type", "file")
            .putString("source_value", "/private/models/legacy-timeout.gguf")
            .putLong("timeout_abort_grace_ms", 300L)
            .putLong("timeout_prefill_ms", 2_000L)
            .commit()

        val restored = store.load()

        assertEquals(ModelSource.FilePath("/private/models/legacy-timeout.gguf"), restored?.source)
        assertEquals(TimeoutConfig(), restored?.config?.timeoutConfig)
        assertFalse(preferences.contains("timeout_abort_grace_ms"))
        assertFalse(preferences.contains("timeout_prefill_ms"))
    }

    @Test
    fun engineConfigParcelablePreservesVulkanMatvecWorkgroupAcrossIpc() {
        val config = EngineConfig(
            contextSize = 8192,
            batchSize = 512,
            threads = 6,
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 99,
            batchThreads = 4,
            microBatchSize = 128,
            vulkanMatvecWorkgroup = VulkanMatvecWorkgroup.LARGE,
            gdnPolicy = GdnPolicy.BOTH_UNFUSED
        )
        val parcel = Parcel.obtain()
        try {
            config.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = EngineConfig.CREATOR.createFromParcel(parcel)
            assertEquals(config, restored)
            assertEquals(VulkanMatvecWorkgroup.LARGE, restored.vulkanMatvecWorkgroup)
            assertEquals(GdnPolicy.BOTH_UNFUSED, restored.gdnPolicy)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun persistsAppPrivateModelPathAndCanClearRememberedSelection() {
        val source = ModelSource.FilePath("/data/user/0/com.example/files/models/model.gguf")
        store.save(source, EngineConfig())

        assertEquals(source, store.load()?.source)

        store.clear()
        assertEquals(null, store.load())
    }
}
