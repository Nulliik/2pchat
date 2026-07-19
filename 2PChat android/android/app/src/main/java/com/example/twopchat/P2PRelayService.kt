package com.example.twopchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Process owner for the listener; it keeps receiving while no Activity is visible. */
class P2PRelayService : Service() {
    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContext = applicationContext
        val action = intent?.action
        kotlin.concurrent.thread(start = true, name = "P2PRelayServiceInit") {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            PythonBridge.init(appContext)
            if (action == ACTION_RESTART) {
                P2PMessageRelay.restartServer(appContext)
            } else {
                P2PMessageRelay.startServer(appContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        P2PMessageRelay.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        private const val CHANNEL_ID = "p2p_connectivity"
        private const val NOTIFICATION_ID = 50001
    }
}
