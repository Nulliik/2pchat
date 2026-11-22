package com.example.twopchat.ui.chat.state

import android.util.LruCache
import com.example.twopchat.security.SensitiveMemoryHolder
import com.example.twopchat.security.SensitiveMemoryRegistry
import com.example.twopchat.ui.chat.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fast in-memory cache for recent messages of active chats.
 * Enables instant (0 ms) rendering when navigating to a ChatScreen.
 */
object ChatHistoryCache : SensitiveMemoryHolder {
    init {
        SensitiveMemoryRegistry.register(this)
    }

    override fun clearSensitiveMemory() {
        clear()
    }

    private const val MAX_CACHED_CHATS = 16
    private val lock = Any()
    private val cache = LruCache<String, List<Message>>(MAX_CACHED_CHATS)
    private val generations = mutableMapOf<String, Long>()
    // Legacy non-suspending prefetch callers cannot carry a read generation. Keep
    // invalidated names fenced from their late cache fills; guarded screen loads remain usable.
    private val invalidatedPeers = mutableSetOf<String>()
    private val historyMutex = Mutex()

    /** Serialize DB reads/cache fills with clear-history DB deletion. */
    suspend fun <T> withHistoryAccess(block: suspend () -> T): T = historyMutex.withLock { block() }

    /**
     * Immediately evict all peer-name/fingerprint cache aliases, then delete the DB rows
     * under the same lock used by reloads. This prevents a reload from observing pre-delete
     * rows and publishing them back into memory while deletion is pending.
     */
    suspend fun clearHistory(peerName: String, aliases: Collection<String>, delete: suspend () -> Unit) {
        val names = (listOf(peerName) + aliases).filter { it.isNotBlank() }.distinct()
        invalidate(names)
        withHistoryAccess {
            try {
                delete()
            } finally {
                // Deletion failure must not make an old cache snapshot visible again.
                invalidate(names)
            }
        }
    }

    /** Capture before an asynchronous DB read and use [putIfUnchanged] afterwards. */
    fun generation(peerName: String): Long = synchronized(lock) { generations[peerName] ?: 0L }

    fun get(peerName: String): List<Message>? {
        synchronized(lock) {
            return cache.get(peerName)?.toList()
        }
    }

    fun put(peerName: String, messages: List<Message>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            if (peerName in invalidatedPeers) return
            cache.put(peerName, messages.toList())
        }
    }

    fun putIfUnchanged(peerName: String, messages: List<Message>, generation: Long): Boolean {
        if (messages.isEmpty()) return false
        synchronized(lock) {
            if ((generations[peerName] ?: 0L) != generation) return false
            invalidatedPeers.remove(peerName)
            cache.put(peerName, messages.toList())
            return true
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

    fun remove(peerName: String) = invalidate(listOf(peerName))

    fun invalidate(peerNames: Collection<String>) {
        synchronized(lock) {
            peerNames.filter { it.isNotBlank() }.distinct().forEach { peer ->
                cache.remove(peer)
                invalidatedPeers.add(peer)
                generations[peer] = (generations[peer] ?: 0L) + 1L
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.evictAll()
            generations.clear()
            invalidatedPeers.clear()
        }
    }
}
