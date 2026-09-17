package com.example.twopchat.relay

import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences

object ActiveChatStore {
    private val retired = java.util.WeakHashMap<SharedPreferences, Boolean>()
    private var revision = 0L
    internal val persistenceLock = Any()
    private val lifecycles = java.util.WeakHashMap<SharedPreferences, MutableMap<String, Chat>>()
    private val deleting = java.util.WeakHashMap<SharedPreferences, MutableSet<String>>()
    private var accountVersion = 0L
    @Volatile internal var accountClosed = false
        private set

    internal class AccountToken internal constructor(internal val version: Long)

    internal fun captureAccount(): AccountToken? = synchronized(persistenceLock) {
        if (accountClosed) null else AccountToken(accountVersion)
    }

    internal fun <T> withAccountWrite(token: AccountToken, rejected: T, action: () -> T): T =
        synchronized(persistenceLock) {
            if (accountClosed || token.version != accountVersion) rejected else action()
        }

    internal fun rejectAccountWork() {
        accountClosed = true
    }

    internal class Chat(var name: String, var version: Long = 0L, var target: Chat? = null)
    internal class Task internal constructor(val prefs: SharedPreferences, internal val chat: Chat, internal val version: Long, internal val account: Long)

    private fun canonical(chat: Chat): Chat {
        var current = chat
        while (current.target != null) current = checkNotNull(current.target)
        return current
    }

    internal fun admit(prefs: SharedPreferences, name: String): Task? = synchronized(persistenceLock) {
        if (accountClosed || isRetired(prefs) || isDeleting(prefs, name)) return null
        val chat = canonical(lifecycles.getOrPut(prefs) { mutableMapOf() }.getOrPut(name) { Chat(name) })
        if (isDeleting(prefs, chat.name)) return null
        Task(prefs, chat, chat.version, accountVersion)
    }

    internal fun resolve(task: Task): String? = synchronized(persistenceLock) {
        if (accountClosed || task.account != accountVersion || isRetired(task.prefs) || task.chat.version != task.version) return null
        canonical(task.chat).name.takeUnless { isDeleting(task.prefs, it) }
    }

    internal fun redirect(prefs: SharedPreferences, from: String, to: String): Boolean = synchronized(persistenceLock) {
        if (accountClosed || isRetired(prefs) || isDeleting(prefs, from) || isDeleting(prefs, to)) return false
        val chats = lifecycles.getOrPut(prefs) { mutableMapOf() }
        val source = canonical(chats.getOrPut(from) { Chat(from) })
        val target = canonical(chats.getOrPut(to) { Chat(to) })
        if (source !== target) source.target = target
        target.name = to
        synchronized(P2PPreferences) { revision++ }
        true
    }

    internal fun beginDelete(prefs: SharedPreferences, aliases: Collection<String>) = synchronized(persistenceLock) {
        val names = aliases.toMutableSet()
        val chats = lifecycles[prefs]?.values.orEmpty()
        val targets = chats.filter { chat ->
            aliases.any { it.equals(chat.name, true) || it.equals(canonical(chat).name, true) }
        }.map(::canonical).toSet()
        chats.filter { canonical(it) in targets }.forEach { chat ->
            names.add(chat.name)
            names.add(canonical(chat).name)
            chat.version++
        }
        synchronized(P2PPreferences) {
            deleting.getOrPut(prefs) { mutableSetOf() }.addAll(names)
            revision++
        }
        names.toSet()
    }

    internal fun completeDelete(prefs: SharedPreferences, aliases: Collection<String>) = synchronized(persistenceLock) {
        synchronized(P2PPreferences) {
            deleting[prefs]?.removeAll(aliases.toSet())
            revision++
        }
    }

    internal fun isDeleting(prefs: SharedPreferences, name: String): Boolean = synchronized(P2PPreferences) {
        deleting[prefs].orEmpty().any { it.equals(name, true) }
    }

    internal fun closeAccount(): Unit = synchronized(persistenceLock) {
        synchronized(P2PPreferences) {
            accountClosed = true
            accountVersion++
            revision++
        }
    }

    internal fun openAccount(): Unit = synchronized(persistenceLock) {
        synchronized(P2PPreferences) {
            accountVersion++
            accountClosed = false
            revision++
        }
    }

    fun retire(prefs: SharedPreferences) = synchronized(P2PPreferences) {
        retired[prefs] = true
        revision++
    }

    fun isRetired(prefs: SharedPreferences): Boolean = synchronized(P2PPreferences) {
        retired.containsKey(prefs)
    }

    fun revision(): Long = synchronized(P2PPreferences) { revision }

    fun mergeIfUnchanged(prefs: SharedPreferences, expectedRevision: Long, chats: Set<String>): Set<String> =
        synchronized(P2PPreferences) {
            if (revision != expectedRevision) return snapshot(prefs)
            update(prefs, { current ->
                (current + chats).filter {
                    it.isNotBlank() && it != "null" && it != "Saved Messages" && !isDeleting(prefs, it)
                }.toSet()
            })
        }

    fun snapshot(prefs: SharedPreferences): Set<String> = synchronized(P2PPreferences) {
        prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty().toSet()
    }

    fun update(
        prefs: SharedPreferences,
        transform: (Set<String>) -> Set<String>,
        edit: SharedPreferences.Editor.() -> Unit = {},
    ): Set<String> = synchronized(P2PPreferences) {
        if (accountClosed || isRetired(prefs)) return emptySet()
        val current = snapshot(prefs)
        val updated = transform(current).filterNot { isDeleting(prefs, it) }.toSet()
        val editor = prefs.edit()
        if (updated != current) editor.putStringSet(P2PPreferences.ACTIVE_CHATS, updated)
        editor.edit()
        if (updated != current) revision++
        editor.apply()
        updated
    }

    fun add(prefs: SharedPreferences, peerName: String) {
        update(prefs, { it + peerName })
    }
}
