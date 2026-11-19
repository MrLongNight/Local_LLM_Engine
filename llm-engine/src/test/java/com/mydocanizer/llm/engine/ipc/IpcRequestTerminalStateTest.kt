package com.mydocanizer.llm.engine.ipc

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcRequestTerminalStateTest {

    @Test
    fun firstTerminalEventWinsAndRejectsLateOrForeignCallbacks() {
        val state = IpcRequestTerminalState("request-1")
        val events = mutableListOf<String>()

        assertFalse(state.dispatch("request-2") { events += "foreign-token" })
        assertTrue(state.dispatch("request-1") { events += "token" })
        assertTrue(state.terminate("request-1") { events += "completed" })
        assertFalse(state.dispatch("request-1") { events += "late-token" })
        assertFalse(state.terminate("request-1") { events += "late-error" })
        assertFalse(state.terminate { events += "late-cancel" })

        assertEquals(listOf("token", "completed"), events)
        assertTrue(state.isTerminal())
    }

    @Test
    fun terminalTransitionWaitsForInFlightCallbackAndThenRejectsLaterCallbacks() {
        val state = IpcRequestTerminalState("request-1")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val terminalFinished = CountDownLatch(1)

        val callbackThread = thread(name = "ipc-token-callback") {
            state.dispatch("request-1") {
                callbackEntered.countDown()
                check(releaseCallback.await(2L, TimeUnit.SECONDS))
                events += "token"
            }
            callbackFinished.countDown()
        }
        assertTrue("Token callback must enter the request gate", callbackEntered.await(1L, TimeUnit.SECONDS))

        val terminalThread = thread(name = "ipc-terminal-callback") {
            state.terminate("request-1") { events += "completed" }
            terminalFinished.countDown()
        }
        assertFalse(
            "Terminal transition must wait until the in-flight callback exits",
            terminalFinished.await(50L, TimeUnit.MILLISECONDS)
        )

        releaseCallback.countDown()
        assertTrue(callbackFinished.await(1L, TimeUnit.SECONDS))
        assertTrue(terminalFinished.await(1L, TimeUnit.SECONDS))
        callbackThread.join(1_000L)
        terminalThread.join(1_000L)

        assertEquals(listOf("token", "completed"), events.toList())
        assertFalse(state.dispatch("request-1") { events += "late-progress" })
        assertEquals(listOf("token", "completed"), events.toList())
    }

    @Test
    fun invalidatedServiceConnectionEpochRejectsLateCallbacksFromOldBinding() {
        val epoch = IpcServiceConnectionEpoch()
        val oldBinding = epoch.begin()

        assertTrue(epoch.isCurrent(oldBinding))
        assertTrue(epoch.invalidate(oldBinding))

        val replacementBinding = epoch.begin()
        assertFalse(epoch.isCurrent(oldBinding))
        assertTrue(epoch.isCurrent(replacementBinding))
        assertFalse("A stale callback cannot invalidate the replacement binding", epoch.invalidate(oldBinding))

        epoch.invalidateCurrent()
        assertFalse(epoch.isCurrent(replacementBinding))
    }
}
