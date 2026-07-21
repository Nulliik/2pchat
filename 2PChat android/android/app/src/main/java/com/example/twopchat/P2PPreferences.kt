package com.example.twopchat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Canonical keys for relay state which is intentionally small key/value metadata. */
object P2PPreferences {
    const val FILE_NAME = "2pchat_prefs"
    private const val ENCRYPTED_FILE_NAME = "2pchat_secure_prefs"
    const val ACTIVE_CHATS = "active_chats"
    const val LISTENER_PORT = "listener_port"
    const val WIFI_DISCOVERY = "settings_wifi"
    const val DEFAULT_LISTENER_PORT = 50001
    const val MIN_LISTENER_PORT = 1024
    const val MAX_LISTENER_PORT = 65535

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Suppress("DEPRECATION")
    fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacyPreferences(appContext, encrypted)
            cachedPrefs = encrypted
            encrypted
        }
    }

    private fun migrateLegacyPreferences(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val editor = target.edit()
        for ((key, value) in legacy.all) {
            if (target.contains(key)) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        check(editor.commit()) { "Unable to migrate preferences into encrypted storage" }
        check(legacy.edit().clear().commit()) { "Unable to remove legacy plaintext preferences" }
    }

    fun listenerPort(context: Context): Int =
        prefs(context).getInt(LISTENER_PORT, DEFAULT_LISTENER_PORT)
            .coerceIn(MIN_LISTENER_PORT, MAX_LISTENER_PORT)

    fun peerFingerprint(peerName: String) = "peer_fingerprint_$peerName"
    fun fingerprintMismatch(peerName: String) = "fingerprint_mismatch_$peerName"
    fun pendingPeerFingerprint(peerName: String) = "pending_peer_fingerprint_$peerName"
    fun pendingPeerEndpoint(peerName: String) = "pending_peer_endpoint_$peerName"
    fun lastEndpoint(peerName: String) = "last_endpoint_$peerName"
    fun lastMessage(peerName: String) = "last_msg_$peerName"
    fun unreadCount(peerName: String) = "unread_count_$peerName"
    fun draftMessage(peerName: String) = "draft_msg_$peerName"
    fun transport(peerName: String) = "transport_$peerName"
    fun verifiedPeer(peerName: String) = "verified_peer_$peerName"
    fun pinnedMessageId(peerName: String) = "pinned_msg_id_$peerName"
    fun pinnedMessageText(peerName: String) = "pinned_msg_text_$peerName"
    fun pinnedMessageSender(peerName: String) = "pinned_msg_sender_$peerName"
    fun pinnedBy(peerName: String) = "pinned_by_$peerName"
    fun pinnedStateVersion(peerName: String) = "pinned_state_version_$peerName"
    fun pinnedStateActor(peerName: String) = "pinned_state_actor_$peerName"

    private const val PINNED_STATE_LOCAL_ACTOR = "pinned_state_local_actor"

    @Synchronized
    internal fun nextLocalPinnedStateVersion(
        context: Context,
        peerName: String,
    ): PinnedMessageStateVersion {
        val prefs = prefs(context)
        val actor = prefs.getString(PINNED_STATE_LOCAL_ACTOR, null)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val current = currentPinnedStateVersion(prefs, peerName)
        val next = nextPinnedMessageStateVersion(current, actor)
        check(
            prefs.edit()
                .putString(PINNED_STATE_LOCAL_ACTOR, actor)
                .putLong(pinnedStateVersion(peerName), next.counter)
                .putString(pinnedStateActor(peerName), next.actor)
                .commit()
        ) { "Unable to persist pinned-message version" }
        return next
    }

    internal fun currentPinnedStateVersion(
        prefs: SharedPreferences,
        peerName: String,
    ): PinnedMessageStateVersion = PinnedMessageStateVersion(
        counter = prefs.getLong(pinnedStateVersion(peerName), 0L),
        actor = prefs.getString(pinnedStateActor(peerName), "").orEmpty(),
    )

    fun isPeerVerified(context: Context, peerName: String): Boolean =
        prefs(context).getBoolean(verifiedPeer(peerName), false)

    fun setPeerVerified(context: Context, peerName: String, verified: Boolean) {
        prefs(context).edit().putBoolean(verifiedPeer(peerName), verified).apply()
    }

    fun isPeerIdentityChangePending(context: Context, peerName: String): Boolean {
        val prefs = prefs(context)
        return shouldBlockPeerTraffic(
            mismatch = prefs.getBoolean(fingerprintMismatch(peerName), false),
            pendingFingerprint = prefs.getString(pendingPeerFingerprint(peerName), null),
        )
    }

    /**
     * Pins the first unexpected identity until the user makes an explicit choice.
     * A later connection cannot silently replace the fingerprint shown in the warning.
     */
    fun recordPendingPeerIdentity(
        context: Context,
        peerName: String,
        fingerprint: String,
        endpoint: String,
    ) {
        if (peerName.isBlank() || fingerprint.isBlank()) return
        val prefs = prefs(context)
        val pendingKey = pendingPeerFingerprint(peerName)
        val existingPending = prefs.getString(pendingKey, null)
        val editor = prefs.edit().putBoolean(fingerprintMismatch(peerName), true)
        if (existingPending.isNullOrBlank()) {
            editor.putString(pendingKey, fingerprint)
        }
        if (endpoint.isNotBlank() && (existingPending.isNullOrBlank() || existingPending == fingerprint)) {
            editor.putString(pendingPeerEndpoint(peerName), endpoint)
        }
        // The session callback is synchronous. Persist the security boundary before
        // Python can process any application frames or another sender can race it.
        editor.commit()
    }

    internal fun acceptPendingPeerIdentity(context: Context, peerName: String): AcceptedPeerIdentity? {
        val prefs = prefs(context)
        val current = prefs.getString(peerFingerprint(peerName), null).orEmpty()
        val pending = prefs.getString(pendingPeerFingerprint(peerName), null).orEmpty()
        if (!canAcceptPendingPeerFingerprint(current, pending)) return null
        val endpoint = prefs.getString(pendingPeerEndpoint(peerName), null).orEmpty()
        val editor = prefs.edit()
            .putString(peerFingerprint(peerName), pending)
            .putBoolean(fingerprintMismatch(peerName), false)
            .putBoolean(verifiedPeer(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
        if (endpoint.isNotBlank()) editor.putString(lastEndpoint(peerName), endpoint)
        if (!editor.commit()) return null
        return AcceptedPeerIdentity(current, pending, endpoint)
    }

    fun rejectPendingPeerIdentity(context: Context, peerName: String): Boolean =
        prefs(context).edit()
            .putBoolean(fingerprintMismatch(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
            .commit()
}

internal data class AcceptedPeerIdentity(
    val previousFingerprint: String,
    val acceptedFingerprint: String,
    val endpoint: String,
)

internal fun shouldBlockPeerTraffic(mismatch: Boolean, pendingFingerprint: String?): Boolean =
    mismatch || !pendingFingerprint.isNullOrBlank()

internal fun canAcceptPendingPeerFingerprint(current: String?, pending: String?): Boolean =
    !current.isNullOrBlank() && !pending.isNullOrBlank() && current != pending
