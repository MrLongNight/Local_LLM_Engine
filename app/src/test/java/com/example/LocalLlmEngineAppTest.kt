package com.example

import android.app.Application
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppTab
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.BackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalLlmEngineAppTest {

    @Test
    fun `1 - validate app resources and strings`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val appName = application.getString(R.string.app_name)
        assertTrue(
            appName == "Local LLM Engine" ||
                appName == "Local LLM Engine – DMMV-Test"
        )
        assertEquals("Local LLM Engine", application.getString(R.string.title_local_llm_engine))

        val chatNav = application.getString(R.string.nav_chat)
        assertNotNull(chatNav)
        assertTrue(chatNav.isNotBlank())
    }

    @Test
    fun `2 - validate engine viewmodel initialization and stateflows`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = EngineViewModel(application)

        assertNotNull(viewModel.engineState)
        assertNotNull(viewModel.currentModel)
        assertNotNull(viewModel.backendInfo)
        assertNotNull(viewModel.chatMessages)
        assertNotNull(viewModel.inputText)
        assertNotNull(viewModel.isGenerating)
        assertNotNull(viewModel.currentStreamingText)
        assertNotNull(viewModel.engineConfig)
        assertNotNull(viewModel.systemPrompt)
        assertNotNull(viewModel.importedModelEntries)
        assertNotNull(viewModel.isConfigSmokeTesting)
        assertNotNull(viewModel.configSmokeReport)
        assertEquals(false, viewModel.isConfigSmokeTesting.value)
        assertEquals(null, viewModel.configSmokeReport.value)


        assertEquals(AppTab.CHAT, viewModel.currentTab.value)
        assertEquals(false, viewModel.isGenerating.value)
        assertEquals("", viewModel.inputText.value)
        assertTrue(viewModel.chatMessages.value.isEmpty())
        assertTrue(viewModel.systemPrompt.value.isNotBlank())
        assertEquals(BackendPreference.VULKAN, viewModel.engineConfig.value.backendPreference)
        assertEquals(99, viewModel.engineConfig.value.gpuLayers) // requested full offload; actual offload is read from native backend diagnostics

        viewModel.selectTab(AppTab.IMPORT)
        assertEquals(AppTab.IMPORT, viewModel.currentTab.value)
        viewModel.selectTab(AppTab.SETTINGS)
        assertEquals(AppTab.SETTINGS, viewModel.currentTab.value)
        viewModel.selectTab(AppTab.CHAT)
        assertEquals(AppTab.CHAT, viewModel.currentTab.value)
        viewModel.updateInputText("Testnachricht")
        assertEquals("Testnachricht", viewModel.inputText.value)
    }

    @Test
    fun `3 - EngineConfig parcel retains independent batch settings`() {
        val original = com.mydocanizer.llm.engine.EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = 6,
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 37,
            batchThreads = 2,
            microBatchSize = 32
        )
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = com.mydocanizer.llm.engine.EngineConfig.CREATOR.createFromParcel(parcel)
            assertEquals(original, restored)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `4 - validate mainactivity oncreate lifecycle and compose tree`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }
}
