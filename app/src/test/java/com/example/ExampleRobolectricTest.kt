package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppTab
import com.example.ui.DocAnizerViewModel
import com.mydocanizer.llm.engine.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class ExampleRobolectricTest {

    @Test
    fun `1 - validate app resources and strings`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val appName = application.getString(R.string.app_name)
        assertEquals("Local LLM Engine", appName)

        val vaultTitle = application.getString(R.string.vault_title)
        assertEquals("Dokumenten Tresor", vaultTitle)

        val chatNav = application.getString(R.string.nav_chat)
        assertNotNull(chatNav)
        assertTrue(chatNav.isNotBlank())
    }

    @Test
    fun `2 - validate docanizer viewmodel initialization and stateflows`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocAnizerViewModel(application)

        assertNotNull(viewModel.engineState)
        assertNotNull(viewModel.currentModel)
        assertNotNull(viewModel.backendInfo)
        assertNotNull(viewModel.chatMessages)
        assertNotNull(viewModel.inputText)
        assertNotNull(viewModel.isGenerating)
        assertNotNull(viewModel.currentStreamingText)
        assertNotNull(viewModel.engineConfig)
        assertNotNull(viewModel.systemPrompt)
        assertNotNull(viewModel.importedModelFiles)


        assertEquals(AppTab.CHAT, viewModel.currentTab.value)
        assertEquals(false, viewModel.isGenerating.value)
        assertEquals("", viewModel.inputText.value)
        assertTrue(viewModel.chatMessages.value.isNotEmpty())
        assertEquals(ChatRole.ASSISTANT, viewModel.chatMessages.value[0].role)
        assertTrue(viewModel.systemPrompt.value.isNotBlank())

        viewModel.selectTab(AppTab.IMPORT)
        assertEquals(AppTab.IMPORT, viewModel.currentTab.value)
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
