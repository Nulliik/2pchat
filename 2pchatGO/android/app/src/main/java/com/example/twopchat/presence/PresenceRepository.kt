package com.example.twopchat.presence

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for peer connection presence.
 *
 * All state changes go through the versioned [observeOnline] / [observeOffline]
 * ingests. Pulls (JNI `isPeerOnline`) are read-only and can never overwrite a
 * newer event, which fixes the stale-pull-overwrites-fresh-push desync.
 *
 * State is keyed by the authenticated fingerprint; nicknames are aliases.
 * An offline flip is debounced by [OFFLINE_UI_GRACE_MS] so a session-replacement
 * burst (close of the old session racing the connect of the new one) does not
 * flicker the UI; a live Go recheck inside the grace window cancels a false
 * offline if the peer actually came back.
 */
@Stable
internal object PresenceRepository {

    sealed class PeerPresence {
        object Unknown : PeerPresence()

        data class Online(
            val transport: String?,
            val endpoint: String?,
            val sinceMs: Long,
        ) : PeerPresence()

        data class Offline(val sinceMs: Long) : PeerPresence()

        val isOnline: Boolean get() = this is Online
    }

    private const val OFFLINE_UI_GRACE_MS = 2_500L

    // Canonical key: fingerprint when bound, otherwise the peer name.
    private val states = mutableStateMapOf<String, PeerPresence>()
    private val nameToKey = mutableStateMapOf<String, String>()
    private val versions = ConcurrentHashMap<String, AtomicLong>()
    private val offlineJobs = ConcurrentHashMap<String, Job>()

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    private fun onMain(block: () -> Unit) {
        // Main.immediate runs the block inline when already on the main thread,
        // so main-thread callers keep synchronous semantics.
        mainScope.launch { block() }
    }

    private fun version(key: String): AtomicLong =
        versions.computeIfAbsent(key) { AtomicLong() }

    private fun resolveKey(peerNameOrFp: String): String =
        nameToKey[peerNameOrFp] ?: peerNameOrFp

    /** Binds a nickname to the canonical fingerprint key. */
    fun bindName(name: String, fingerprint: String) {
        if (name.isBlank() || fingerprint.isBlank()) return
        onMain { nameToKey[name] = fingerprint }
    }

    fun currentVersion(peerNameOrFp: String): Long =
        version(resolveKey(peerNameOrFp)).get()

    fun displayName(key: String): String =
        nameToKey.entries.firstOrNull { it.value == key }?.key ?: key

    fun state(peerNameOrFp: String): PeerPresence =
        states[resolveKey(peerNameOrFp)] ?: PeerPresence.Unknown

    fun isOnline(peerNameOrFp: String): Boolean =
        state(peerNameOrFp).isOnline

    /** Display names of every peer currently Online. */
    fun onlineNames(): List<String> {
        val result = ArrayList<String>(states.size)
        for ((key, value) in states) {
            if (value is PeerPresence.Online) result.add(displayName(key))
        }
        return result
    }

    /** Authoritative state for reconciliation (keyed by canonical key). */
    fun currentSnapshot(): Map<String, PeerPresence> = states.toMap()

    /**
     * Ingests a live-session observation. Returns the ingest version.
     * Cancels any pending offline grace for the peer.
     */
    fun observeOnline(peerNameOrFp: String, transport: String? = null, endpoint: String? = null): Long {
        val key = resolveKey(peerNameOrFp)
        val v = version(key).incrementAndGet()
        onMain {
            offlineJobs.remove(key)?.cancel()
            states[key] = PeerPresence.Online(transport, endpoint, System.currentTimeMillis())
        }
        return v
    }

    /**
     * Ingests a session-closed observation.
     *
     * @param verifyOnline optional live Go recheck evaluated after the grace
     *        delay; if it reports the peer online (session replacement), the
     *        offline flip is cancelled.
     * @param immediate apply the offline state now (e.g. explicit wire-level
     *        status=offline) instead of after the grace delay.
     */
    fun observeOffline(
        peerNameOrFp: String,
        verifyOnline: (() -> Boolean)? = null,
        immediate: Boolean = false,
    ): Long {
        val key = resolveKey(peerNameOrFp)
        val v = version(key).incrementAndGet()
        onMain {
            offlineJobs.remove(key)?.cancel()
            if (immediate) {
                states[key] = PeerPresence.Offline(System.currentTimeMillis())
            } else {
                offlineJobs[key] = mainScope.launch {
                    delay(OFFLINE_UI_GRACE_MS)
                    if (version(key).get() != v) return@launch // superseded by a newer event
                    if (verifyOnline?.invoke() == true) return@launch // peer is live again (replacement)
                    states[key] = PeerPresence.Offline(System.currentTimeMillis())
                    offlineJobs.remove(key)
                }
            }
        }
        return v
    }

    /** Moves a peer's state from an old name to a new name (optionally to its fingerprint). */
    fun rename(oldName: String, newName: String, fingerprint: String? = null) {
        onMain {
            val oldKey = resolveKey(oldName)
            val newKey = fingerprint?.takeIf { it.isNotBlank() } ?: newName
            if (oldKey != newKey) {
                val carried = states.remove(oldKey)
                if (carried != null) states[newKey] = carried
                for ((alias, key) in nameToKey.entries.toList()) {
                    if (key == oldKey) nameToKey[alias] = newKey
                }
            }
            nameToKey[newName] = newKey
        }
    }

    /** Drops all state and aliases for a peer. */
    fun forget(peerNameOrFp: String) {
        val key = resolveKey(peerNameOrFp)
        onMain {
            offlineJobs.remove(key)?.cancel()
            states.remove(key)
            versions.remove(key)
            nameToKey.entries.retainAll { it.value != key }
        }
    }

    /** Clears every peer. Called when the relay stops or the account is deleted. */
    fun clearAll() {
        onMain {
            for (job in offlineJobs.values) job.cancel()
            offlineJobs.clear()
            states.clear()
            nameToKey.clear()
            versions.clear()
        }
    }
}
