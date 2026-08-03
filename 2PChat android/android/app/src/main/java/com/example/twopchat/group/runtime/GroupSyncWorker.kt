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
import com.example.twopchat.PythonBridge
import java.util.concurrent.TimeUnit

class GroupSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        PythonBridge.ensurePythonStarted(applicationContext)
        PythonBridge.init(applicationContext)
        check(PythonBridge.isInitialized) { "cryptographic identity bridge is unavailable" }
        GroupChatCoordinator.initialize(applicationContext)
        GroupChatCoordinator.runAntiEntropy()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object GroupWorkScheduler {
    private const val PERIODIC_WORK = "twopchat-group-anti-entropy"
    private const val IMMEDIATE_WORK = "twopchat-group-outbox-now"
    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<GroupSyncWorker>(
            15,
            TimeUnit.MINUTES,
        ).setConstraints(connected).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<GroupSyncWorker>()
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(IMMEDIATE_WORK)
    }
}
