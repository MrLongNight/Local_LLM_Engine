package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.internal.NativeEngineHandle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBoundaryAndIpcTest {

    @Test
    fun cxxExceptionAtJniBoundaryBecomesJavaException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diagnosticsFile = File(context.cacheDir, "jni-exception-boundary-test.log")
        assertTrue("Native engine must initialize for the JNI boundary test",
            NativeEngineHandle.ensureLoaded(diagnosticsFile))
        val handle = NativeEngineHandle.nativeCreateEngine()
        assertNotEquals("Native engine handle must be valid", 0L, handle)
        try {
            val thrown = runCatching {
                NativeEngineHandle.nativeThrowCppExceptionForTest(handle)
            }.exceptionOrNull()
            assertTrue("C++ exception must be translated to a Java exception, got $thrown",
                thrown is IllegalStateException)
            assertTrue("Translated exception must retain the native message",
                thrown?.message?.contains("deliberate JNI exception-boundary test") == true)
        } finally {
            NativeEngineHandle.nativeDestroyEngine(handle)
        }
    }

    @Test
    fun inferenceRequestParcelablePreservesThinkingModeAcrossIpc() {
        val original = InferenceRequest.single(
            userPrompt = "Test request",
            enableThinking = true
        )
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = InferenceRequest.CREATOR.createFromParcel(parcel)

            assertEquals(true, restored.enableThinking)
            assertEquals(original.messages, restored.messages)
        } finally {
            parcel.recycle()
        }
    }
}
