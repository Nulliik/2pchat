package com.example.twopchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.example.twopchat.MainActivity
import com.example.twopchat.NativeBridge
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.R
import com.example.twopchat.config.*
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.twopchat.logging.SafeLog
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isReceiverRegistered = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val powerAndScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    SafeLog.d(TAG, "Screen became active: ${intent.action}")
                    acquireWifiLock()
                    refreshLocks()
                    P2PMessageRelay.onScreenOn(context)
                    P2PMessageRelay.triggerImmediateReconnect(context)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    SafeLog.d(TAG, "Screen turned off -> entering adaptive battery saver mode")
                    P2PMessageRelay.onScreenOff()
                    // Release aggressive high-perf Wi-Fi lock to allow Wi-Fi radio power-saving (DTIM)
                    releaseWifiLock()
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isIdle = powerManager?.isDeviceIdleMode == true
                    SafeLog.d(TAG, "Device idle/Doze mode changed: isIdle=$isIdle")
                    if (!isIdle) {
                        acquireWifiLock()
                        refreshLocks()
                        P2PMessageRelay.onScreenOn(context)
                        P2PMessageRelay.triggerImmediateReconnect(context)
                    } else {
                        P2PMessageRelay.onScreenOff()
                        releaseWifiLock()
                    }
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    SafeLog.d(TAG, "Power save mode changed")
                    P2PMessageRelay.triggerMaintenanceWakeup("POWER_SAVE_CHANGED")
                }
            }
        }
    }

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
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("2pchat_background_daemon")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
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
            SafeLog.e(TAG, "Failed to start foreground service", e)
            releaseLocks()
            stopSelf()
            return
        }

        registerReceiversAndNetworkCallbacks()

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
                SafeLog.i(TAG, "Relay start deferred until local identity is configured")
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
        unregisterReceiversAndNetworkCallbacks()
        releaseLocks()
        P2PMessageRelay.stopServer()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerReceiversAndNetworkCallbacks() {
        try {
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                }
                registerReceiver(powerAndScreenReceiver, filter)
                isReceiverRegistered = true
            }

            if (networkCallback == null) {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        SafeLog.i(TAG, "Network became available -> triggering fast reconnect")
                        NativeBridge.onNetworkChanged()
                        P2PMessageRelay.resetPeerBackoffs()
                        P2PMessageRelay.triggerImmediateReconnect(applicationContext)
                        P2PMessageRelay.triggerMaintenanceWakeup("NETWORK_AVAILABLE")
                    }

                    override fun onLost(network: Network) {
                        SafeLog.i(TAG, "Network lost -> notifying native bridge")
                        NativeBridge.onNetworkChanged()
                        P2PMessageRelay.triggerMaintenanceWakeup("NETWORK_LOST")
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            P2PMessageRelay.resetPeerBackoffs()
                            P2PMessageRelay.triggerImmediateReconnect(applicationContext)
                            P2PMessageRelay.triggerMaintenanceWakeup("NETWORK_VALIDATED")
                        }
                    }
                }
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, callback)
                networkCallback = callback
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to register power/network listeners", e)
        }
    }

    private fun unregisterReceiversAndNetworkCallbacks() {
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(powerAndScreenReceiver)
                isReceiverRegistered = false
            }
            networkCallback?.let {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivityManager?.unregisterNetworkCallback(it)
                networkCallback = null
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to unregister power/network listeners", e)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "2PChat:P2PRelayServiceWakeLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // 10-minute timeout; refreshed on next active cycle
                }
            }
            acquireWifiLock()
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to acquire WakeLock / WifiLock", e)
        }
    }

    private fun acquireWifiLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: true
            if (isInteractive && wifiLock == null) {
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
            SafeLog.e(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to release WifiLock", e)
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
            SafeLog.e(TAG, "Failed to refresh WakeLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            releaseWifiLock()
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to release WakeLock / WifiLock", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P2P connectivity", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    companion object {
        const val ACTION_RESTART = "com.example.twopchat.service.P2PRelayService.RESTART"
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
