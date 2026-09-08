package com.example.twopchat.group.runtime

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.twopchat.NativeBridge
import java.util.concurrent.TimeUnit

class GroupSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        val prefs = com.example.twopchat.config.P2PPreferences.prefs(applicationContext)
        val hasLocalIdentity = prefs.getBoolean("onboarding_completed", false) &&
            !prefs.getString("username_profile", null).isNullOrBlank()
        if (!hasLocalIdentity) {
            com.example.twopchat.service.OutboxWorkScheduler.cancelAll(applicationContext)
            return Result.success()
        }
        if (com.example.twopchat.config.P2PPreferences.isPasscodeConfigured(applicationContext) &&
            com.example.twopchat.config.P2PPreferences.isAppLocked()) {
            return Result.success()
        }
        if (!NativeBridge.initialize()) {
            return Result.retry()
        }
        GroupChatCoordinator.initialize(applicationContext)
        GroupChatCoordinator.runAntiEntropy()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
        Result.retry()
    } catch (e: Exception) {
        com.example.twopchat.logging.SafeLog.e("GroupSyncWorker", "Error in group anti-entropy", e)
        Result.failure()
    }
}

object GroupWorkScheduler {
    private const val PERIODIC_WORK = "twopchat-group-anti-entropy"
    private const val IMMEDIATE_WORK = "twopchat-group-outbox-now"
    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val request = PeriodicWorkRequestBuilder<GroupSyncWorker>(
            15,
            TimeUnit.MINUTES,
        ).setConstraints(connected).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        com.example.twopchat.service.OutboxWorkScheduler.schedulePeriodicDrain(appContext)
        com.example.twopchat.service.OutboxWorkScheduler.schedulePeriodicHeartbeats(appContext)
    }

    fun runSoon(context: Context, expedited: Boolean = false) {
        val appContext = context.applicationContext
        val request = OneTimeWorkRequestBuilder<GroupSyncWorker>()
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        com.example.twopchat.service.OutboxWorkScheduler.triggerImmediateDrain(appContext, expedited)
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(appContext).cancelUniqueWork(IMMEDIATE_WORK)
        com.example.twopchat.service.OutboxWorkScheduler.cancelAll(appContext)
    }
}
