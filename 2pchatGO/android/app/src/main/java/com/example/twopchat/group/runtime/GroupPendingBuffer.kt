package com.example.twopchat.group.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <T> withGroupEventLocks(
    controlMutex: Mutex,
    emitMutex: Mutex,
    block: suspend () -> T,
): T = controlMutex.withLock {
    emitMutex.withLock { block() }
}

internal class GroupPendingBuffer<T>(
    private val capacity: Int,
    private val ttlMs: Long?,
    private val key: (T) -> Any,
    private val receivedAtMs: (T) -> Long,
    private val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.KEEP_FIRST,
) {
    enum class DuplicatePolicy { KEEP_FIRST, REPLACE }

    private class Entry<T>(var value: T, var claimed: Boolean = false, var requeued: Boolean = false)

    private val entries = mutableListOf<Entry<T>>()

    init {
        require(capacity > 0)
        require(ttlMs == null || ttlMs >= 0L)
    }

    fun add(value: T, nowMs: Long): Boolean = synchronized(entries) {
        prune(nowMs)
        entries.firstOrNull { key(it.value) == key(value) }?.let {
            if (duplicatePolicy == DuplicatePolicy.REPLACE) {
                it.value = value
                entries.remove(it)
                entries.add(it)
            }
            if (it.claimed) it.requeued = true
            return@synchronized true
        }
        if (entries.size >= capacity) {
            val oldest = entries.indexOfFirst { !it.claimed }
            if (oldest < 0) return@synchronized false
            entries.removeAt(oldest)
        }
        entries.add(Entry(value))
        true
    }

    fun size(): Int = synchronized(entries) { entries.size }

    suspend fun drain(
        nowMs: Long,
        ready: (T) -> Boolean,
        obsolete: (T) -> Boolean = { false },
        comparator: Comparator<T>? = null,
        onFailure: (T, Exception) -> Unit,
        process: suspend (T) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val claimed = synchronized(entries) {
            prune(nowMs)
            entries.removeAll { !it.claimed && obsolete(it.value) }
            val selected = entries.filter { !it.claimed && ready(it.value) }
                .let { selected ->
                    if (comparator == null) selected else selected.sortedWith { a, b ->
                        comparator.compare(a.value, b.value)
                    }
                }
            selected.forEach {
                it.claimed = true
                it.requeued = false
            }
            selected.toMutableList()
        }
        try {
            while (claimed.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val entry = claimed.first()
                val value = synchronized(entries) { entry.value }
                try {
                    process(value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(value, error)
                }
                synchronized(entries) {
                    if (!entry.requeued) entries.remove(entry)
                    claimed.removeAt(0)
                    entry.claimed = false
                }
            }
        } finally {
            synchronized(entries) { claimed.forEach { it.claimed = false } }
        }
    }

    private fun prune(nowMs: Long) {
        val ttl = ttlMs ?: return
        entries.removeAll { !it.claimed && nowMs - receivedAtMs(it.value) > ttl }
    }
}
