package com.example.twopchat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

import java.util.concurrent.ConcurrentHashMap

/** Canonical keys for relay state which is intentionally small key/value metadata. */
object P2PPreferences {
    const val FILE_NAME = "2pchat_prefs"
    private const val ENCRYPTED_FILE_NAME = "2pchat_secure_prefs"
    const val ACTIVE_CHATS = "active_chats"
    const val LISTENER_PORT = "listener_port"
    const val WIFI_DISCOVERY = "settings_wifi"
    const val STICKER_CACHE_LIMIT_MB = "settings_sticker_cache_limit_mb"
    const val UPNP_ENABLED = "settings_upnp"
    const val DEFAULT_LISTENER_PORT = 50001

    fun isWifiDiscoveryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(WIFI_DISCOVERY, true)

    fun isUpnpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(UPNP_ENABLED, false)
    const val DEFAULT_STICKER_CACHE_LIMIT_MB = 100
    val STICKER_CACHE_LIMIT_OPTIONS_MB = listOf(50, 100, 250, 500)
    const val MIN_LISTENER_PORT = 1024
    const val MAX_LISTENER_PORT = 65535

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Volatile
    private var isAppLockedState = false

    fun isAppLocked(): Boolean = isAppLockedState

    fun setAppLocked(locked: Boolean) {
        isAppLockedState = locked
    }

    /** Drops account-derived caches after the backing preferences are erased. */
    fun clearInMemoryState() {
        synchronized(this) {
            cachedPrefs = null
            fingerprintToPeerNameCache.clear()
            fingerprintCacheInitialized = false
            isAppLockedState = false
        }
    }

    private val fingerprintToPeerNameCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var fingerprintCacheInitialized = false

    fun findPeerNameByFingerprint(context: Context, fingerprint: String): String? {
        if (fingerprint.isBlank()) return null
        if (!fingerprintCacheInitialized) {
            synchronized(this) {
                if (!fingerprintCacheInitialized) {
                    val allEntries = prefs(context).all
                    for ((key, value) in allEntries) {
                        if (key.startsWith("peer_fingerprint_") && value is String && value.isNotBlank()) {
                            val peerName = key.removePrefix("peer_fingerprint_")
                            fingerprintToPeerNameCache[value] = peerName
                        }
                    }
                    fingerprintCacheInitialized = true
                }
            }
        }
        return fingerprintToPeerNameCache[fingerprint]
    }

    fun updateFingerprintCache(fingerprint: String, peerName: String) {
        if (fingerprint.isNotBlank() && peerName.isNotBlank()) {
            fingerprintToPeerNameCache[fingerprint] = peerName
        }
    }

    @Suppress("DEPRECATION")
    fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val preferences = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                android.util.Log.e("P2PPreferences", "Failed to initialize EncryptedSharedPreferences", e)
                throw IllegalStateException("EncryptedSharedPreferences initialization failed: Keystore unavailable", e)
            }
            migrateLegacyPreferences(appContext, preferences)
            cachedPrefs = preferences
            preferences
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

    fun stickerCacheLimitMb(context: Context): Int {
        val stored = prefs(context).getInt(
            STICKER_CACHE_LIMIT_MB,
            DEFAULT_STICKER_CACHE_LIMIT_MB,
        )
        return stored.takeIf { it in STICKER_CACHE_LIMIT_OPTIONS_MB }
            ?: DEFAULT_STICKER_CACHE_LIMIT_MB
    }

    fun setStickerCacheLimitMb(context: Context, limitMb: Int) {
        require(limitMb in STICKER_CACHE_LIMIT_OPTIONS_MB) {
            "Unsupported sticker cache limit: $limitMb MB"
        }
        prefs(context).edit().putInt(STICKER_CACHE_LIMIT_MB, limitMb).apply()
    }

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
        // commit() is intentional here: we must ensure the block is written before returning.
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

    fun saveDraft(context: Context, chatId: String, text: String) {
        if (chatId.isBlank()) return
        val trimmed = text.trim()
        val prefs = prefs(context)
        if (trimmed.isEmpty()) {
            prefs.edit().remove("draft_$chatId").apply()
        } else {
            val encrypted = SecureStorage.encrypt(text)
            prefs.edit().putString("draft_$chatId", encrypted).apply()
        }
    }

    fun getDraft(context: Context, chatId: String): String? {
        if (chatId.isBlank()) return null
        val stored = prefs(context).getString("draft_$chatId", null) ?: return null
        return SecureStorage.decrypt(stored) ?: stored
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
