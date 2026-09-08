package com.example.twopchat.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.twopchat.logging.SafeLog
import java.util.concurrent.TimeUnit

/**
 * Manages background work scheduling for message and queue persistence.
 *
 * Implements:
 * 1. Unique work policies: KEEP for periodic schedules, REPLACE for immediate drains.
 * 2. Expedited work requests for urgent user-facing outbox delivery.
 * 3. Network constraints: NetworkType.CONNECTED.
 * 4. Coordinated cancellation upon account wipe or logout.
 */
object OutboxWorkScheduler {
    private const val TAG = "OutboxWorkScheduler"

    const val WORK_DRAIN_IMMEDIATE = "twopchat-outbox-drain-now"
    const val WORK_DRAIN_PERIODIC = "twopchat-outbox-drain-periodic"
    const val WORK_HEARTBEAT_PERIODIC = "twopchat-succession-heartbeat"
    const val WORK_GROUP_SYNC = "twopchat-group-anti-entropy"

    private val connectedConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Enqueues an immediate outbox drain pass.
     * Uses ExistingWorkPolicy.REPLACE to prevent pile-up of redundant one-off tasks.
     *
     * @param expedited if true, requests system expedited execution quota.
     */
    fun triggerImmediateDrain(context: Context, expedited: Boolean = false) {
        val appContext = context.applicationContext
        try {
            val builder = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setConstraints(connectedConstraint)

            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                BackgroundDiagnostics.recordExpeditedDrain(appContext)
            }

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_DRAIN_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                builder.build(),
            )
            SafeLog.d(TAG, "Enqueued immediate outbox drain (expedited=$expedited)")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to enqueue immediate outbox drain", e)
        }
    }

    /**
     * Schedules periodic outbox drain fallback (every 15 minutes).
     * Uses ExistingPeriodicWorkPolicy.KEEP to preserve existing schedules.
     */
    fun schedulePeriodicDrain(context: Context) {
        val appContext = context.applicationContext
        try {
            val request = PeriodicWorkRequestBuilder<OutboxDrainWorker>(
                15,
                TimeUnit.MINUTES,
            ).setConstraints(connectedConstraint).build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_DRAIN_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            SafeLog.d(TAG, "Scheduled periodic outbox drain")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to schedule periodic outbox drain", e)
        }
    }

    /**
     * Schedules periodic succession heartbeat checks for group owners (every 12 hours).
     * Uses ExistingPeriodicWorkPolicy.KEEP to preserve existing schedules.
     */
    fun schedulePeriodicHeartbeats(context: Context) {
        val appContext = context.applicationContext
        try {
            val request = PeriodicWorkRequestBuilder<SuccessionHeartbeatWorker>(
                12,
                TimeUnit.HOURS,
                2,
                TimeUnit.HOURS,
            ).setConstraints(connectedConstraint).build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_HEARTBEAT_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            SafeLog.d(TAG, "Scheduled periodic succession heartbeats")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to schedule periodic succession heartbeats", e)
        }
    }

    /**
     * Cancels all background work (drain, heartbeat, group sync).
     * Called during account data wipe or logout.
     */
    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        try {
            val wm = WorkManager.getInstance(appContext)
            wm.cancelUniqueWork(WORK_DRAIN_IMMEDIATE)
            wm.cancelUniqueWork(WORK_DRAIN_PERIODIC)
            wm.cancelUniqueWork(WORK_HEARTBEAT_PERIODIC)
            wm.cancelUniqueWork(WORK_GROUP_SYNC)
            SafeLog.i(TAG, "Cancelled all background persistence work")
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to cancel background persistence work", e)
        }
    }
}
