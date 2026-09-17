package com.example.twopchat.security

import android.content.Context
import com.example.twopchat.relay.P2PMessageRelay

internal fun performAccountDeletion(
    shutdownRuntime: () -> Boolean,
    wipePersistentData: () -> Boolean,
): Boolean {
    if (!shutdownRuntime()) return false
    return wipePersistentData()
}

internal class AccountMutationGate {
    private val operation = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val isWiping: Boolean get() = operation.get() == "wipe"
    val isBusy: Boolean get() = operation.get() != null

    fun run(kind: String, action: () -> Boolean): Boolean {
        if (!operation.compareAndSet(null, kind)) return false
        try {
            return action()
        } finally {
            operation.set(null)
        }
    }
}

internal class AccountRuntimeGate {
    private val lock = Object()
    private var version = 0L
    private var closed = false
    private var inFlight = 0
    private val depth = ThreadLocal.withInitial { 0 }

    internal inner class Admission(val version: Long) : AutoCloseable {
        override fun close() {
            depth.set(depth.get() - 1)
            synchronized(lock) {
                inFlight--
                lock.notifyAll()
            }
        }
    }

    fun current(): Long? = synchronized(lock) { version.takeUnless { closed } }

    fun enter(expected: Long? = null): Admission? = synchronized(lock) {
        if (closed || (expected != null && expected != version)) return null
        inFlight++
        depth.set(depth.get() + 1)
        Admission(version)
    }

    fun <T> run(version: Long, rejected: T, action: () -> T): T {
        val admission = enter(version) ?: return rejected
        try {
            return action()
        } finally {
            admission.close()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        version++
    }

    fun open(): Boolean = synchronized(lock) {
        if (inFlight != 0) return false
        version++
        closed = false
        true
    }

    fun isEntered(): Boolean = depth.get() != 0

    fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        if (isEntered() || Thread.currentThread().isInterrupted) return false
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return try {
            synchronized(lock) {
                while (inFlight != 0) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                }
                !Thread.currentThread().isInterrupted
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

object AccountLifecycle {
    internal val mutations = AccountMutationGate()

    fun deleteAccount(context: Context): Boolean = AccountDataWiper.wipe(context.applicationContext)

    fun installProfile(context: Context, install: () -> Boolean): Boolean = mutations.run("install") {
        performProfileInstallation(
            shutdownRuntime = { P2PMessageRelay.shutdownForAccountDeletion(context.applicationContext) },
            install = install,
            reopen = { P2PMessageRelay.completeProfileInstallation() },
        )
    }
}

internal fun performProfileInstallation(
    shutdownRuntime: () -> Boolean,
    install: () -> Boolean,
    reopen: () -> Boolean,
): Boolean = shutdownRuntime() && install() && reopen()
