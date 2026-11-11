package com.example.ui

import android.content.Context
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import org.junit.Assert.assertEquals
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
            vulkanMatvecWorkgroup = VulkanMatvecWorkgroup.LARGE
        )
        val source = ModelSource.ContentUri(
            uriString = "content://com.android.externalstorage.documents/document/primary%3AModels%2Fqwen.gguf"
        )

        store.save(source, config, resolvedDisplayName = "qwen.gguf")
        val restored = store.load()

        assertNotNull(restored)
        assertEquals(source.copy(displayName = "qwen.gguf"), restored?.source)
        assertEquals(config, restored?.config)
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
            vulkanMatvecWorkgroup = VulkanMatvecWorkgroup.LARGE
        )
        val parcel = Parcel.obtain()
        try {
            config.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = EngineConfig.CREATOR.createFromParcel(parcel)
            assertEquals(config, restored)
            assertEquals(VulkanMatvecWorkgroup.LARGE, restored.vulkanMatvecWorkgroup)
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
