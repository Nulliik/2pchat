package com.example.twopchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.twopchat.group.runtime.GroupWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Process owner for the listener; it keeps receiving while no Activity is visible. */
class P2PRelayService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setContentTitle("2PChat")
            .setContentText("P2P listener is active")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            releaseLocks()
            stopSelf()
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            GroupWorkScheduler.schedule(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContext = applicationContext
        val action = intent?.action
        serviceScope.launch(Dispatchers.IO) {
            val preferences = P2PPreferences.prefs(appContext)
            val hasLocalIdentity =
                preferences.getBoolean("onboarding_completed", false) &&
                    !preferences.getString("username_profile", null).isNullOrBlank()
            if (!hasLocalIdentity) {
                Log.i(TAG, "Relay start deferred until local identity is configured")
                stopSelf(startId)
                return@launch
            }

            acquireLocks()
            NativeBridge.initialize()
            if (action == ACTION_RESTART) {
                P2PMessageRelay.restartServer(appContext)
            } else {
                P2PMessageRelay.startServer(appContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        P2PMessageRelay.stopServer()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "2PChat:P2PRelayServiceWakeLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // 10-minute timeout; re-acquired on next ping cycle
                }
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "2PChat:P2PRelayServiceWifiLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock / WifiLock", e)
        }
    }

    private fun refreshLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                it.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh WakeLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock / WifiLock", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P2P connectivity", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_RESTART = "com.example.twopchat.P2PRelayService.RESTART"
        private const val TAG = "P2PRelayService"
        private const val CHANNEL_ID = "p2p_connectivity"
        private const val NOTIFICATION_ID = 50001

        @Volatile
        private var instance: P2PRelayService? = null

        fun refreshWakeLock() {
            instance?.refreshLocks()
        }
    }
}
