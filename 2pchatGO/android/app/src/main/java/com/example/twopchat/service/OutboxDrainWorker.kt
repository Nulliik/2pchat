package com.example.twopchat.service

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.logging.SafeLog
import com.example.twopchat.relay.P2PMessageRelay
import kotlinx.coroutines.CancellationException

/**
 * Background worker responsible for draining queued 1-on-1 and group messages.
 *
 * Adheres strictly to the following production invariants:
 * 1. P2P Result Semantics: In a pure P2P network without central relays, "peer offline"
 *    is normal operation. OutboxDrainWorker performs best-effort drainage and returns
 *    Result.success() so as not to trigger a battery-draining exponential backoff storm.
 *    Transient local failures (e.g. SQLite database locked) return Result.retry().
 * 2. App Lock Policy: If the application has a configured passcode and is currently
 *    locked, drainage is deferred until user unlock (returns Result.success()).
 * 3. Headless Execution: Executes without initializing the Tor daemon, starting FGS,
 *    or touching foreground UI components.
 */
class OutboxDrainWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        SafeLog.d(TAG, "Starting outbox drain execution")

        // Guard 1: Onboarding check. If no local identity is configured, skip silently.
        val prefs = P2PPreferences.prefs(context)
        val hasLocalIdentity = prefs.getBoolean("onboarding_completed", false) &&
            !prefs.getString("username_profile", null).isNullOrBlank()
        if (!hasLocalIdentity) {
            SafeLog.i(TAG, "Onboarding incomplete / no identity configured; cancelling schedules and skipping outbox drain")
            OutboxWorkScheduler.cancelAll(context)
            BackgroundDiagnostics.recordDrainOutcome(context, "skipped_no_identity")
            return Result.success()
        }

        // Guard 2: App lock policy. If locked with user factor, defer drain until unlocked.
        if (P2PPreferences.isPasscodeConfigured(context) && P2PPreferences.isAppLocked()) {
            SafeLog.i(TAG, "App is locked with user passcode; deferring outbox drain until unlock")
            BackgroundDiagnostics.recordDrainOutcome(context, "skipped_locked")
            return Result.success()
        }

        return try {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }

            // Guard 3: NativeBridge initialization (idempotent)
            if (!NativeBridge.initialize()) {
                SafeLog.w(TAG, "Native bridge is unavailable; scheduling retry")
                BackgroundDiagnostics.recordDrainOutcome(context, "retry_bridge")
                return Result.retry()
            }

            // 1. Drain group chat outbox
            var flushedGroupCount = 0
            try {
                GroupChatCoordinator.initialize(context)
                flushedGroupCount = GroupChatCoordinator.flushDueOutbox(limit = 200)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SQLiteDatabaseLockedException) throw e
                SafeLog.w(TAG, "Non-fatal error draining group outbox", e)
            }

            // 2. Drain 1-on-1 offline messages across all peers
            try {
                P2PMessageRelay.processAllOfflineQueues(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SQLiteDatabaseLockedException) throw e
                SafeLog.w(TAG, "Non-fatal error draining offline queues", e)
            }

            SafeLog.i(TAG, "Outbox drain completed successfully (flushed $flushedGroupCount group task(s))")
            BackgroundDiagnostics.recordDrainOutcome(context, "success")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteDatabaseLockedException) {
            SafeLog.w(TAG, "Database locked during outbox drain; scheduling retry", e)
            BackgroundDiagnostics.recordDrainOutcome(context, "retry_db_locked")
            Result.retry()
        } catch (e: Exception) {
            SafeLog.e(TAG, "Unexpected error during outbox drain execution", e)
            BackgroundDiagnostics.recordDrainOutcome(context, "failure")
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "OutboxDrainWorker"
    }
}
