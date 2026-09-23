package com.example.twopchat.presence

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for peer connection presence.
 *
 * All state changes go through the versioned [observeOnline] / [observeOffline]
 * ingests. Pull observations only apply through the *IfVersion methods,
 * which fail (drop) if a newer event landed since the pull captured its
 * version — a stale pull can never overwrite a fresh push.
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
    private val eventSeq = ConcurrentHashMap<String, AtomicLong>()
    private val offlineJobs = ConcurrentHashMap<String, Job>()

    // Lazy so the Main dispatcher is bound on first use, not at class load
    // (keeps the object testable with Dispatchers.setMain).
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main.immediate) }

    private fun onMain(block: () -> Unit) {
        // Main.immediate runs the block inline when already on the main thread,
        // so main-thread callers keep synchronous semantics.
        mainScope.launch { block() }
    }

    private fun version(key: String): AtomicLong =
        versions.computeIfAbsent(key) { AtomicLong() }

    private fun resolveKey(peerNameOrFp: String): String =
        nameToKey[peerNameOrFp] ?: peerNameOrFp

    // Go event sequences start from a small number while [versions] is
    // epoch-milliseconds based. The two MUST NOT share one counter, so each
    // key keeps an independent monotonic [eventSeq] lane.
    private fun eventSeqOf(key: String): AtomicLong =
        eventSeq.computeIfAbsent(key) { AtomicLong() }

    /**
     * Accepts [seq] for [key] iff it is strictly greater than the last
     * accepted sequence. `seq <= 0` means "unordered" (local reconcile /
     * pull path) and is always accepted. The CAS loop guarantees that a
     * concurrently arriving newer event wins, and the stale one is dropped.
     */
    private fun acceptSeq(key: String, seq: Long): Boolean {
        if (seq <= 0) return true
        var current = eventSeqOf(key).get()
        while (seq > current && !eventSeqOf(key).compareAndSet(current, seq)) {
            current = eventSeqOf(key).get()
        }
        return seq > current
    }

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
     * Repairs state from a Go core session snapshot (JSON array of
     * `{"fp","endpoint","transport","online"}`). Adds peers Go reports online
     * that are missing locally, and flips locally-online fingerprint keys that
     * vanished from the live set. Uses seq=0 (trusted reconcile pass, not an
     * event), so the offline grace and live recheck still apply.
     *
     * @return number of state changes applied.
     */
    fun reconcileWithSnapshot(snapshotJson: String, log: ((String) -> Unit)? = null): Int {
        if (snapshotJson.isBlank()) return 0
        val liveOnline = HashSet<String>()
        val liveRoutes = HashMap<String, Pair<String?, String?>>()
        try {
            val arr = JSONArray(snapshotJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fp = obj.optString("fp").ifBlank { continue }
                if (obj.optBoolean("online", false)) {
                    liveOnline.add(fp)
                    val transport = obj.optString("transport").ifBlank { null }
                    val endpoint = obj.optString("endpoint").ifBlank { null }
                    liveRoutes[fp] = transport to endpoint
                }
            }
        } catch (e: Exception) {
            log?.invoke("Presence reconcile skipped, malformed snapshot: ${e.javaClass.simpleName}")
            return 0
        }

        var changes = 0
        onMain {
            for (fp in liveOnline) {
                if (states[fp]?.isOnline != true) {
                    val (transport, endpoint) = liveRoutes[fp] ?: (null to null)
                    observeOnline(fp, transport, endpoint)
                    log?.invoke("Presence reconcile: added online $fp")
                    changes++
                }
            }
            for ((key, state) in states) {
                if (state.isOnline && isFingerprintKey(key) && key !in liveOnline) {
                    observeOffline(key)
                    log?.invoke("Presence reconcile: cleared stale online $key")
                    changes++
                }
            }
        }
        return changes
    }

    // Fingerprint-like keys (long identifier strings) can be correlated with the
    // Go snapshot; nickname keys are left to the per-peer maintenance pull.
    private fun isFingerprintKey(key: String): Boolean =
        key.length >= 40 && key.all { it.isLetterOrDigit() }

    /**
     * Ingests a live-session observation. Returns the ingest version.
     * Cancels any pending offline grace for the peer.
     *
     * Pass [fingerprint] when it is known: the alias binding and the state
     * write then land in one serialized Main block, so state can never end
     * up under a nickname key that later dangles behind the alias.
     *
     * @param seq Go event sequence; stale (already-seen) sequences are dropped.
     */
    fun observeOnline(
        peerNameOrFp: String,
        transport: String? = null,
        endpoint: String? = null,
        seq: Long = 0,
        fingerprint: String? = null,
    ): Long {
        val key = fingerprint?.takeIf { it.isNotBlank() } ?: resolveKey(peerNameOrFp)
        if (!acceptSeq(key, seq)) return version(key).get()
        val v = version(key).incrementAndGet()
        onMain {
            offlineJobs.remove(key)?.cancel()
            if (fingerprint != null && fingerprint != peerNameOrFp) {
                nameToKey[peerNameOrFp] = fingerprint
            }
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
     * @param fingerprint canonical key; see [observeOnline].
     */
    fun observeOffline(
        peerNameOrFp: String,
        verifyOnline: (() -> Boolean)? = null,
        immediate: Boolean = false,
        seq: Long = 0,
        fingerprint: String? = null,
    ): Long {
        val key = fingerprint?.takeIf { it.isNotBlank() } ?: resolveKey(peerNameOrFp)
        if (!acceptSeq(key, seq)) return version(key).get()
        val v = version(key).incrementAndGet()
        onMain {
            offlineJobs.remove(key)?.cancel()
            if (fingerprint != null && fingerprint != peerNameOrFp) {
                nameToKey[peerNameOrFp] = fingerprint
            }
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

    /**
     * Pull-side ingest, version-gated: applies only if no newer event landed
     * after [expectedVersion] was captured. The CAS is on the same counter
     * events increment, so a push that raced the pull wins and the stale pull
     * result is dropped.
     */
    fun observeOnlineIfVersion(
        peerNameOrFp: String,
        transport: String? = null,
        endpoint: String? = null,
        expectedVersion: Long,
        fingerprint: String? = null,
    ): Boolean {
        val key = fingerprint?.takeIf { it.isNotBlank() } ?: resolveKey(peerNameOrFp)
        val v = version(key)
        if (!v.compareAndSet(expectedVersion, expectedVersion + 1)) return false
        onMain {
            offlineJobs.remove(key)?.cancel()
            if (fingerprint != null && fingerprint != peerNameOrFp) {
                nameToKey[peerNameOrFp] = fingerprint
            }
            states[key] = PeerPresence.Online(transport, endpoint, System.currentTimeMillis())
        }
        return true
    }

    /** Pull-side offline flip, version-gated like [observeOnlineIfVersion]. */
    fun observeOfflineIfVersion(
        peerNameOrFp: String,
        immediate: Boolean = false,
        expectedVersion: Long,
        fingerprint: String? = null,
    ): Boolean {
        val key = fingerprint?.takeIf { it.isNotBlank() } ?: resolveKey(peerNameOrFp)
        val v = version(key)
        if (!v.compareAndSet(expectedVersion, expectedVersion + 1)) return false
        val version = v.get()
        onMain {
            offlineJobs.remove(key)?.cancel()
            if (fingerprint != null && fingerprint != peerNameOrFp) {
                nameToKey[peerNameOrFp] = fingerprint
            }
            if (immediate) {
                states[key] = PeerPresence.Offline(System.currentTimeMillis())
            } else {
                offlineJobs[key] = mainScope.launch {
                    delay(OFFLINE_UI_GRACE_MS)
                    if (v.get() != version) return@launch // superseded by a newer event
                    states[key] = PeerPresence.Offline(System.currentTimeMillis())
                    offlineJobs.remove(key)
                }
            }
        }
        return true
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
            eventSeq.remove(key)
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
            eventSeq.clear()
        }
    }
}
