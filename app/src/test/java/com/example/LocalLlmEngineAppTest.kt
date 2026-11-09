package com.example

import android.app.Application
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
        assertEquals("Local LLM Engine", appName)

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


        assertEquals(AppTab.CHAT, viewModel.currentTab.value)
        assertEquals(false, viewModel.isGenerating.value)
        assertEquals("", viewModel.inputText.value)
        assertTrue(viewModel.chatMessages.value.isEmpty())
        assertTrue(viewModel.systemPrompt.value.isNotBlank())
        assertEquals(BackendPreference.VULKAN, viewModel.engineConfig.value.backendPreference)
        assertEquals(37, viewModel.engineConfig.value.gpuLayers)

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
    fun `4 - validate mainactivity oncreate lifecycle and compose tree`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }
}
