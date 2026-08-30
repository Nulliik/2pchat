package com.example.twopchat.ui.chat.state

import android.util.LruCache
import com.example.twopchat.ui.chat.Message

/**
 * Fast in-memory cache for recent messages of active chats.
 * Enables instant (0 ms) rendering when navigating to a ChatScreen.
 */
object ChatHistoryCache {
    private const val MAX_CACHED_CHATS = 16
    private val lock = Any()
    private val cache = LruCache<String, List<Message>>(MAX_CACHED_CHATS)

    fun get(peerName: String): List<Message>? {
        synchronized(lock) {
            return cache.get(peerName)?.toList()
        }
    }

    fun put(peerName: String, messages: List<Message>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            cache.put(peerName, messages.toList())
        }
    }

    fun appendOrUpdate(peerName: String, message: Message) {
        synchronized(lock) {
            val existing = cache.get(peerName) ?: return
            val mutable = existing.toMutableList()
            val index = mutable.indexOfFirst { it.id == message.id }
            if (index >= 0) {
                mutable[index] = message
            } else {
                mutable.add(message)
            }
            cache.put(peerName, mutable)
        }
    }

    fun remove(peerName: String) {
        synchronized(lock) {
            cache.remove(peerName)
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.evictAll()
        }
    }
}
