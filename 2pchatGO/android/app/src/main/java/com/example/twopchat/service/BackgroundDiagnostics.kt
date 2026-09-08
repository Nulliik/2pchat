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
            .apply()
    }
}
