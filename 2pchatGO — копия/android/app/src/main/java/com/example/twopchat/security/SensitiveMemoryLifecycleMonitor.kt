package com.example.twopchat.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.twopchat.logging.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Monitors application lifecycle and system events to purge sensitive memory:
 * - App backgrounded: Schedules a delayed cleanup (default: 5 minutes) via [DefaultLifecycleObserver].
 * - App foregrounded: Cancels any pending background cleanup job.
 * - Screen locked / turned off: Purges sensitive memory immediately via dynamic [BroadcastReceiver].
 */
object SensitiveMemoryLifecycleMonitor : DefaultLifecycleObserver {
    private const val TAG = "SensitiveMemoryLifecycleMonitor"

    /** Default background timeout before memory is purged (5 minutes). */
    const val DEFAULT_BACKGROUND_TIMEOUT_MS = 5 * 60 * 1000L

    @Volatile
    var customTimeoutMs: Long? = null

    val backgroundTimeoutMs: Long
        get() {
            customTimeoutMs?.let { return it }
            return try {
                val ctx = com.example.twopchat.yggdrasil.GlobalApplication.getContext()
                val prefs = com.example.twopchat.config.P2PPreferences.prefs(ctx)
                val autolockMinutes = prefs.getInt("passcode_autolock_minutes", 5)
                maxOf(0L, autolockMinutes * 60 * 1000L)
            } catch (_: Throwable) {
                DEFAULT_BACKGROUND_TIMEOUT_MS
            }
        }

    private var backgroundJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var isScreenReceiverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                SafeLog.i(TAG, "Screen turned off / device locked; purging sensitive memory")
                SensitiveMemoryRegistry.clearAll()
            }
        }
    }

    /**
     * Registers dynamic BroadcastReceiver for ACTION_SCREEN_OFF.
     */
    fun registerScreenOffReceiver(context: Context) {
        synchronized(this) {
            if (!isScreenReceiverRegistered) {
                try {
                    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                    context.applicationContext.registerReceiver(screenOffReceiver, filter)
                    isScreenReceiverRegistered = true
                    SafeLog.d(TAG, "Registered ACTION_SCREEN_OFF receiver")
                } catch (e: Exception) {
                    SafeLog.e(TAG, "Failed to register screen-off receiver", e)
                }
            }
        }
    }

    /**
     * Unregisters dynamic BroadcastReceiver for ACTION_SCREEN_OFF.
     */
    fun unregisterScreenOffReceiver(context: Context) {
        synchronized(this) {
            if (isScreenReceiverRegistered) {
                try {
                    context.applicationContext.unregisterReceiver(screenOffReceiver)
                    isScreenReceiverRegistered = false
                    SafeLog.d(TAG, "Unregistered ACTION_SCREEN_OFF receiver")
                } catch (e: Exception) {
                    SafeLog.e(TAG, "Failed to unregister screen-off receiver", e)
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App entered background
        backgroundJob?.cancel()
        val timeout = backgroundTimeoutMs
        if (timeout <= 0L) {
            SafeLog.i(TAG, "Immediate background purge requested (timeout=0)")
            SensitiveMemoryRegistry.clearAll()
            return
        }

        SafeLog.d(TAG, "App backgrounded; scheduling sensitive memory purge in ${timeout}ms")
        backgroundJob = scope.launch {
            delay(timeout)
            SafeLog.i(TAG, "Background timeout reached (${timeout}ms); purging sensitive memory")
            SensitiveMemoryRegistry.clearAll()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App returned to foreground
        if (backgroundJob != null) {
            SafeLog.d(TAG, "App returned to foreground; canceling pending background purge job")
            backgroundJob?.cancel()
            backgroundJob = null
        }
    }

    @androidx.annotation.VisibleForTesting
    fun hasPendingBackgroundJob(): Boolean = backgroundJob?.isActive == true

    @androidx.annotation.VisibleForTesting
    fun cancelBackgroundJobForTesting() {
        backgroundJob?.cancel()
        backgroundJob = null
    }
}
