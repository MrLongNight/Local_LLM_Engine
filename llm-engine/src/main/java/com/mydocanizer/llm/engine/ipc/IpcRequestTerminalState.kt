package com.mydocanizer.llm.engine.ipc

import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes callbacks for one IPC request and makes its first terminal transition final.
 *
 * The action executes while the request lock is held. A concurrent token/progress callback
 * therefore either completes before the terminal transition or is rejected afterwards.
 */
internal class IpcRequestTerminalState(
    private val requestId: String
) {
    private val lock = Any()
    private var terminal = false

    fun dispatch(callbackRequestId: String?, action: () -> Unit): Boolean = synchronized(lock) {
        if (terminal || callbackRequestId != requestId) return@synchronized false
        action()
        true
    }

    fun terminate(callbackRequestId: String?, action: () -> Unit): Boolean = synchronized(lock) {
        if (terminal || callbackRequestId != requestId) return@synchronized false
        terminal = true
        action()
        true
    }

    fun terminate(action: () -> Unit): Boolean = synchronized(lock) {
        if (terminal) return@synchronized false
        terminal = true
        action()
        true
    }

    fun isTerminal(): Boolean = synchronized(lock) { terminal }
}

/**
 * Gives every Android ServiceConnection a unique generation. Late callbacks from a binding
 * that has already disconnected/unbound cannot replace the currently connected service/PID.
 */
internal class IpcServiceConnectionEpoch {
    private val currentGeneration = AtomicLong(0L)

    fun begin(): Long = currentGeneration.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = currentGeneration.get() == generation

    fun invalidate(generation: Long): Boolean =
        currentGeneration.compareAndSet(generation, generation + 1L)

    fun invalidateCurrent(): Long = currentGeneration.incrementAndGet()

    fun current(): Long = currentGeneration.get()
}
