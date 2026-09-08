package com.example.twopchat.service

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import org.json.JSONObject

/**
 * Diagnostic statistics for background persistence workers (OutboxDrainWorker and SuccessionHeartbeatWorker).
 */
data class DrainStats(
    val lastDrainTimestamp: Long = 0L,
    val lastHeartbeatTimestamp: Long = 0L,
    val drainOutcomes: Map<String, Int> = emptyMap(), // "success" -> N, "skipped_locked" -> N
    val expeditedCount: Int = 0,
)

/**
 * Diagnostic statistics for group invite generation, relay delivery, and lifecycle.
 */
data class GroupInviteStats(
    val invitesGenerated: Int = 0,
    val invitesRelayed: Int = 0,
    val invitesAccepted: Int = 0,
    val invitesExpired: Int = 0,
    val invitesRejected: Int = 0,
    val relayAttemptsWithoutCapability: Int = 0,
)

/**
 * Thread-safe observability helper to track background worker outcomes,
 * execution frequency, and expedited requests.
 */
object BackgroundDiagnostics {
    private const val PREF_LAST_DRAIN_TS = "diag_last_drain_ts"
    private const val PREF_LAST_HEARTBEAT_TS = "diag_last_heartbeat_ts"
    private const val PREF_DRAIN_OUTCOMES_JSON = "diag_drain_outcomes_json"
    private const val PREF_EXPEDITED_COUNT = "diag_expedited_count"

    @Synchronized
    fun recordDrainOutcome(context: Context, outcome: String) {
        val sp = P2PPreferences.prefs(context)
        val now = System.currentTimeMillis()
        val currentJson = sp.getString(PREF_DRAIN_OUTCOMES_JSON, "{}") ?: "{}"
        val outcomesMap = runCatching {
            val json = JSONObject(currentJson)
            val map = mutableMapOf<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optInt(k, 0)
            }
            map
        }.getOrDefault(mutableMapOf())

        val currentCount = outcomesMap[outcome] ?: 0
        outcomesMap[outcome] = currentCount + 1

        val newJson = JSONObject(outcomesMap as Map<*, *>).toString()
        sp.edit()
            .putLong(PREF_LAST_DRAIN_TS, now)
            .putString(PREF_DRAIN_OUTCOMES_JSON, newJson)
            .apply()
    }

    @Synchronized
    fun recordHeartbeat(context: Context) {
        val sp = P2PPreferences.prefs(context)
        val now = System.currentTimeMillis()
        sp.edit().putLong(PREF_LAST_HEARTBEAT_TS, now).apply()
    }

    @Synchronized
    fun recordExpeditedDrain(context: Context) {
        val sp = P2PPreferences.prefs(context)
        val current = sp.getInt(PREF_EXPEDITED_COUNT, 0)
        sp.edit().putInt(PREF_EXPEDITED_COUNT, current + 1).apply()
    }

    private const val PREF_INVITES_GENERATED = "diag_invites_generated"
    private const val PREF_INVITES_RELAYED = "diag_invites_relayed"
    private const val PREF_INVITES_ACCEPTED = "diag_invites_accepted"
    private const val PREF_INVITES_EXPIRED = "diag_invites_expired"
    private const val PREF_INVITES_REJECTED = "diag_invites_rejected"
    private const val PREF_RELAY_NO_CAP = "diag_relay_no_cap"

    @Synchronized
    fun recordInviteGenerated(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_INVITES_GENERATED, sp.getInt(PREF_INVITES_GENERATED, 0) + 1).apply()
    }

    @Synchronized
    fun recordInviteRelayed(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_INVITES_RELAYED, sp.getInt(PREF_INVITES_RELAYED, 0) + 1).apply()
    }

    @Synchronized
    fun recordInviteAccepted(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_INVITES_ACCEPTED, sp.getInt(PREF_INVITES_ACCEPTED, 0) + 1).apply()
    }

    @Synchronized
    fun recordInviteExpired(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_INVITES_EXPIRED, sp.getInt(PREF_INVITES_EXPIRED, 0) + 1).apply()
    }

    @Synchronized
    fun recordInviteRejected(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_INVITES_REJECTED, sp.getInt(PREF_INVITES_REJECTED, 0) + 1).apply()
    }

    @Synchronized
    fun recordRelayAttemptWithoutCapability(context: Context) {
        val sp = P2PPreferences.prefs(context)
        sp.edit().putInt(PREF_RELAY_NO_CAP, sp.getInt(PREF_RELAY_NO_CAP, 0) + 1).apply()
    }

    @Synchronized
    fun getInviteStats(context: Context): GroupInviteStats {
        val sp = P2PPreferences.prefs(context)
        return GroupInviteStats(
            invitesGenerated = sp.getInt(PREF_INVITES_GENERATED, 0),
            invitesRelayed = sp.getInt(PREF_INVITES_RELAYED, 0),
            invitesAccepted = sp.getInt(PREF_INVITES_ACCEPTED, 0),
            invitesExpired = sp.getInt(PREF_INVITES_EXPIRED, 0),
            invitesRejected = sp.getInt(PREF_INVITES_REJECTED, 0),
            relayAttemptsWithoutCapability = sp.getInt(PREF_RELAY_NO_CAP, 0),
        )
    }

    @Synchronized
    fun getStats(context: Context): DrainStats {
        val sp = P2PPreferences.prefs(context)
        val lastDrain = sp.getLong(PREF_LAST_DRAIN_TS, 0L)
        val lastHeartbeat = sp.getLong(PREF_LAST_HEARTBEAT_TS, 0L)
        val expeditedCount = sp.getInt(PREF_EXPEDITED_COUNT, 0)
        val outcomesJson = sp.getString(PREF_DRAIN_OUTCOMES_JSON, "{}") ?: "{}"

        val outcomesMap = runCatching {
            val json = JSONObject(outcomesJson)
            val map = mutableMapOf<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optInt(k, 0)
            }
            map
        }.getOrDefault(emptyMap())

        return DrainStats(
            lastDrainTimestamp = lastDrain,
            lastHeartbeatTimestamp = lastHeartbeat,
            drainOutcomes = outcomesMap,
            expeditedCount = expeditedCount,
        )
    }

    @Synchronized
    fun resetForTesting(context: Context) {
        P2PPreferences.prefs(context).edit()
            .remove(PREF_LAST_DRAIN_TS)
            .remove(PREF_LAST_HEARTBEAT_TS)
            .remove(PREF_DRAIN_OUTCOMES_JSON)
            .remove(PREF_EXPEDITED_COUNT)
            .remove(PREF_INVITES_GENERATED)
            .remove(PREF_INVITES_RELAYED)
            .remove(PREF_INVITES_ACCEPTED)
            .remove(PREF_INVITES_EXPIRED)
            .remove(PREF_INVITES_REJECTED)
            .remove(PREF_RELAY_NO_CAP)
            .apply()
    }
}
