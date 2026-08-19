package com.example.twopchat.yggdrasil

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

const val PREF_KEY_ENABLED = "settings_yggdrasil"
const val PREF_KEY_PEERS_NOTE = "peers_note"
const val MAIN_CHANNEL_ID = "Yggdrasil Service"
const val SERVICE_NOTIFICATION_ID = 1000

class GlobalApplication: Application(), YggStateReceiver.StateReceiver {
    private lateinit var config: ConfigurationProxy
    private var currentState: State = State.Disabled
    private var updaterConnections: Int = 0

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        val prefs = yggdrasilPrefs(applicationContext)
        if (!prefs.contains(PREF_KEY_ENABLED)) {
            // A VpnService cannot establish its TUN device until the user has
            // approved Android's VPN consent dialog. Starting it optimistically
            // from the first network callback makes establish() return null and
            // leaves a fresh install looking permanently offline.
            prefs.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
        }
        config = ConfigurationProxy(applicationContext)
        val callback = NetworkStateCallback(this)
        callback.register()
        val receiver = YggStateReceiver(this)
        receiver.register(this)
    }

    fun subscribe() {
        updaterConnections++
    }

    fun unsubscribe() {
        if (updaterConnections > 0) {
            updaterConnections--
        }
    }

    fun needUiUpdates(): Boolean {
        return updaterConnections > 0
    }

    fun getCurrentState(): State {
        return currentState
    }

    override fun onStateChange(state: State) {
        if (state != currentState) {
            if (state != State.Disabled) {
                val notification = createServiceNotification(this, state)
                val notificationManager: NotificationManager =
                    this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
            }
            if (state == State.Connected) {
                com.example.twopchat.P2PMessageRelay.refreshAnnouncement(this)
                com.example.twopchat.P2PMessageRelay.triggerImmediateReconnect(this)
            }
            currentState = state
        }
    }
}

fun createServiceNotification(context: Context, state: State): Notification {
    createNotificationChannels(context)

    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    } ?: Intent()
    
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    val text = when (state) {
        State.Disabled -> "Yggdrasil Disabled"
        State.Enabled -> "Yggdrasil Enabled"
        State.Connected -> "Yggdrasil Connected"
        else -> "Yggdrasil Service Running"
    }

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle("2PChat VPN")
        .setContentText(text)
        .setSmallIcon(com.example.twopchat.R.drawable.ic_logo_default_fg)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

fun createPermissionMissingNotification(context: Context): Notification {
    createNotificationChannels(context)
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    } ?: Intent()
    
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle("2PChat VPN Permission Required")
        .setContentText("Please launch the app to enable Yggdrasil routing.")
        .setSmallIcon(com.example.twopchat.R.drawable.ic_logo_default_fg)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
}

private fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Yggdrasil Service"
        val descriptionText = "Yggdrasil background routing daemon"
        val importance = NotificationManager.IMPORTANCE_MIN
        val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
