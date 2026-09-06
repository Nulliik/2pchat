package com.example.twopchat.data.cache

import com.example.twopchat.ui.chat.Message

/**
 * Bounded in-memory LRU cache of decrypted [Message] objects.
 * Prevents redundant AES-GCM decryptions of message text, attachments, and metadata
 * during list scrolling, page loads, and UI recompositions.
 *
 * Security & performance invariants:
 * - In-memory only: NEVER persisted to disk or serialized.
 * - Bounded capacity: 256 messages (~1-2 MB RAM max) to prevent memory bloat.
 * - Thread-safe: synchronized access on internal LRU map.
 * - Evicted / cleared on logout, database wipe, or chat screen exit.
 * - Invalidated immediately on message update or deletion.
 */
object MessageCache {
    const val MAX_ENTRIES = 256
    private val lock = Any()

    private val cache = object : LinkedHashMap<String, Message>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Message>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun get(messageId: String): Message? {
        synchronized(lock) {
            return cache[messageId]
        }
    }

    fun put(messageId: String, message: Message) {
        synchronized(lock) {
            cache[messageId] = message
        }
    }

    fun invalidate(messageId: String) {
        synchronized(lock) {
            cache.remove(messageId)
        }
    }

    fun invalidateAll(messageIds: Collection<String>) {
        synchronized(lock) {
            for (id in messageIds) {
                cache.remove(id)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return cache.size
        }
    }
}
