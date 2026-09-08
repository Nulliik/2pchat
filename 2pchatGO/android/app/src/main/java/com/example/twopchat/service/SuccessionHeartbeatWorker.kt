package com.example.twopchat.service

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.logging.SafeLog
import kotlinx.coroutines.CancellationException

/**
 * Background worker responsible for periodically checking group ownership
 * and emitting succession heartbeats for groups where the local user is the owner.
 *
 * Prevents split-brain leadership handover when the owner's device is in extended Doze.
 */
class SuccessionHeartbeatWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        SafeLog.d(TAG, "Starting succession heartbeat check")

        // Guard 1: Onboarding check. If no local identity is configured, cancel schedules and exit.
        val prefs = P2PPreferences.prefs(context)
        val hasLocalIdentity = prefs.getBoolean("onboarding_completed", false) &&
            !prefs.getString("username_profile", null).isNullOrBlank()
        if (!hasLocalIdentity) {
            SafeLog.i(TAG, "Onboarding incomplete / no identity configured; cancelling schedules")
            OutboxWorkScheduler.cancelAll(context)
            return Result.success()
        }

        // Guard 2: App lock policy (defer until user unlock if protected by factor)
        if (P2PPreferences.isPasscodeConfigured(context) && P2PPreferences.isAppLocked()) {
            SafeLog.i(TAG, "App is locked with user passcode; deferring heartbeat emission until unlock")
            return Result.success()
        }

        return try {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }

            if (!NativeBridge.initialize()) {
                SafeLog.w(TAG, "Native bridge is unavailable; scheduling retry")
                return Result.retry()
            }

            GroupChatCoordinator.initialize(context)
            val groups = GroupChatCoordinator.listGroups()
            var emittedCount = 0

            val now = System.currentTimeMillis()
            for (group in groups) {
                // Only owners emit heartbeats
                if (group.localDeviceId == group.ownerDeviceId) {
                    val lastEmit = P2PPreferences.getLastHeartbeatEmitTime(context, group.groupId)
                    // Check minimum throttling interval (1 hour) and 24-hour target threshold
                    if (now - lastEmit >= 24 * 3600_000L && now - lastEmit >= GroupChatCoordinator.MIN_HEARTBEAT_INTERVAL_MS) {
                        val emitted = GroupChatCoordinator.maybeEmitHeartbeat(group.groupId, force = false)
                        if (emitted) {
                            emittedCount++
                            SafeLog.i(TAG, "Emitted background succession heartbeat for group ${group.groupId}")
                        }
                    }
                }
            }

            if (emittedCount > 0) {
                BackgroundDiagnostics.recordHeartbeat(context)
                // Immediately trigger outbox drain to flush the newly generated heartbeat tasks
                OutboxWorkScheduler.triggerImmediateDrain(context, expedited = true)
            }

            SafeLog.i(TAG, "Succession heartbeat check completed (emitted $emittedCount heartbeat(s))")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteDatabaseLockedException) {
            SafeLog.w(TAG, "Database locked during heartbeat check; scheduling retry", e)
            Result.retry()
        } catch (e: Exception) {
            SafeLog.e(TAG, "Unexpected error during succession heartbeat check", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SuccessionHeartbeatWorker"
    }
}
