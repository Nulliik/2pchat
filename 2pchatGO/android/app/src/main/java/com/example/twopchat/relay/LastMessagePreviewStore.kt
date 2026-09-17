package com.example.twopchat.relay

import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.security.SecureStorage
import com.example.twopchat.security.SensitiveMemoryHolder
import com.example.twopchat.security.SensitiveMemoryRegistry

object LastMessagePreviewStore : SensitiveMemoryHolder {
    class Update internal constructor(internal val text: String, internal var peerName: String)

    private val pending = mutableMapOf<String, Update>()
    private val revisions = mutableMapOf<String, Long>()
    private var generation = 0L

    internal fun historyRevision(prefs: SharedPreferences, peerName: String): Long? = synchronized(P2PPreferences) {
        if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs) ||
            ActiveChatStore.isDeleting(prefs, peerName) || peerName in pending) return null
        generation
    }

    internal fun refreshFromHistory(
        prefs: SharedPreferences,
        peerName: String,
        expectedRevision: Long,
        text: String,
        encrypt: (String) -> String = SecureStorage::encrypt,
    ) {
        val update = synchronized(P2PPreferences) {
            if (historyRevision(prefs, peerName) != expectedRevision) return
            publish(peerName, text)
        }
        persist(prefs, peerName, update, encrypt)
    }

    init {
        SensitiveMemoryRegistry.register(this)
    }

    fun publish(peerName: String, text: String): Update = synchronized(P2PPreferences) {
        generation++
        revisions[peerName] = generation
        Update(text, peerName).also {
            pending[peerName] = it
            P2PPreferences.lastMessageCache[peerName] = text
        }
    }

    fun persist(
        prefs: SharedPreferences,
        @Suppress("UNUSED_PARAMETER") peerName: String,
        update: Update,
        encrypt: (String) -> String = SecureStorage::encrypt,
    ) {
        val encrypted = encrypt(update.text)
        synchronized(P2PPreferences) {
            val target = update.peerName
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs) || pending[target] !== update ||
                P2PPreferences.lastMessageCache[target] != update.text) return
            prefs.edit().putString(P2PPreferences.lastMessage(target), encrypted).apply()
            if (pending[target] === update) pending.remove(target)
        }
    }

    fun set(prefs: SharedPreferences, peerName: String, text: String) {
        val update = synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs)) return
            publish(peerName, text)
        }
        persist(prefs, peerName, update)
    }

    fun seed(prefs: SharedPreferences, peerName: String, text: String) {
        val update = synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs) || P2PPreferences.lastMessageCache.containsKey(peerName)) return
            publish(peerName, text)
        }
        persist(prefs, peerName, update)
    }

    fun get(
        prefs: SharedPreferences,
        peerName: String,
        decrypt: (String?) -> String? = SecureStorage::decrypt,
    ): String? {
        val key = P2PPreferences.lastMessage(peerName)
        val snapshot = synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs)) return null
            P2PPreferences.lastMessageCache[peerName]?.let { return it }
            generation to prefs.getString(key, null)
        }
        val text = decrypt(snapshot.second)
        return synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs)) return null
            P2PPreferences.lastMessageCache[peerName]?.let { return it }
            if (generation != snapshot.first || prefs.getString(key, null) != snapshot.second) return null
            if (text != null) P2PPreferences.lastMessageCache.putIfAbsent(peerName, text) ?: text else null
        }
    }

    fun refresh(
        prefs: SharedPreferences,
        peerName: String,
        decrypt: (String?) -> String? = SecureStorage::decrypt,
    ) {
        val key = P2PPreferences.lastMessage(peerName)
        val snapshot = synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs) || pending.containsKey(peerName)) return
            Triple(generation, prefs.getString(key, null), P2PPreferences.lastMessageCache[peerName])
        }
        val text = decrypt(snapshot.second)
        synchronized(P2PPreferences) {
            if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(prefs) || generation != snapshot.first || pending.containsKey(peerName) ||
                prefs.getString(key, null) != snapshot.second) return
            val cache = P2PPreferences.lastMessageCache
            val previous = snapshot.third
            if (previous == null) {
                if (text != null) cache.putIfAbsent(peerName, text)
            } else if (text == null) {
                cache.remove(peerName, previous)
            } else {
                cache.replace(peerName, previous, text)
            }
        }
    }

    fun remove(peerName: String) = synchronized(P2PPreferences) {
        generation++
        pending.remove(peerName)
        revisions.remove(peerName)
        P2PPreferences.lastMessageCache.remove(peerName)
        Unit
    }

    fun move(fromName: String, toName: String): Boolean = synchronized(P2PPreferences) {
        if (fromName == toName) return false
        generation++
        val sourceWins = (revisions[fromName] ?: 0L) > (revisions[toName] ?: 0L) ||
            (toName !in pending && !P2PPreferences.lastMessageCache.containsKey(toName))
        val update = pending.remove(fromName)
        val text = P2PPreferences.lastMessageCache.remove(fromName)
        val sourceRevision = revisions.remove(fromName)
        if (sourceWins) {
            pending.remove(toName)
            if (update != null) {
                update.peerName = toName
                pending[toName] = update
            }
            if (text != null) P2PPreferences.lastMessageCache[toName] = text
            else P2PPreferences.lastMessageCache.remove(toName)
            if (sourceRevision != null) revisions[toName] = sourceRevision
        }
        sourceWins
    }

    override fun clearSensitiveMemory() = synchronized(P2PPreferences) {
        generation++
        pending.clear()
        revisions.clear()
        P2PPreferences.lastMessageCache.clear()
    }
}
