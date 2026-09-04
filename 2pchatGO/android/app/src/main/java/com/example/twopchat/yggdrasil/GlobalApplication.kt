package com.example.twopchat.yggdrasil

import com.example.twopchat.logging.SafeLog

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
    private var currentState: State = State.Disabled
    private var updaterConnections: Int = 0

    companion object {
        lateinit var appContext: Context
            private set

        fun getContext(): Context = appContext
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                SafeLog.e("FATAL_CRASH", "Uncaught exception in thread ${thread.name}", throwable)
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                com.example.twopchat.AppLog.append(
                    applicationContext,
                    "[FATAL_CRASH] Thread: ${thread.name}\n$sw\n"
                )
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            System.loadLibrary("sqlcipher")
        } catch (e: Throwable) {
            SafeLog.e("GlobalApplication", "Failed to load sqlcipher", e)
        }

        val prefs = yggdrasilPrefs(applicationContext)
        if (!prefs.contains(PREF_KEY_ENABLED)) {
            // Wait for explicit VPN consent. Starting from a network callback
            // before it is granted makes Builder.establish() return null.
            prefs.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
        }

        // libgojni.so (Yggdrasil/gomobile) and lib2pcore.so each embed a Go
        // runtime. Loading both runtimes in one Android process corrupts cgo
        // callback unwinding and aborts with "fatal error: unknown caller pc".
        // The VPN service has its own process (:yggdrasil), so keep all main-process
        // P2P/UI initialization strictly isolated from the Yggdrasil process.
        if (isYggdrasilServiceProcess(currentProcessName(this))) {
            return
        }

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
                runCatching {
                    val notification = createServiceNotification(this, state)
                    val notificationManager: NotificationManager =
                        this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val mode = com.example.twopchat.config.P2PPreferences.getYggdrasilMode(this)
                    val notifId = if (mode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY) 2002 else SERVICE_NOTIFICATION_ID
                    if (mode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY) {
                        notificationManager.cancel(SERVICE_NOTIFICATION_ID)
                    } else {
                        notificationManager.cancel(2002)
                    }
                    notificationManager.notify(notifId, notification)
                }.onFailure {
                    SafeLog.w("GlobalApplication", "Could not post Yggdrasil service notification", it)
                }
            }
            if (state == State.Connected) {
                com.example.twopchat.relay.P2PMessageRelay.refreshAnnouncement(this)
                com.example.twopchat.relay.P2PMessageRelay.triggerImmediateReconnect(this)
            }
            currentState = state
        }
    }
}

internal fun currentProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return runCatching { Application.getProcessName() }.getOrNull()
    }
    return runCatching {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }.getOrNull()
}

internal fun isYggdrasilServiceProcess(processName: String?): Boolean =
    processName?.endsWith(":yggdrasil") == true

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

    val mode = com.example.twopchat.config.P2PPreferences.getYggdrasilMode(context)
    val isProxy = mode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY

    val title = if (isProxy) "2PChat Mesh" else "2PChat VPN"
    val text = when (state) {
        State.Disabled -> if (isProxy) "Mesh Proxy Disabled" else "Yggdrasil Disabled"
        State.Enabled -> if (isProxy) "Mesh Proxy Active" else "Yggdrasil Enabled"
        State.Connected -> "Yggdrasil Connected"
        else -> if (isProxy) "Mesh Proxy Running" else "Yggdrasil Service Running"
    }

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(com.example.twopchat.R.drawable.ic_logo_default_fg)
        .setContentIntent(pendingIntent)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setGroup("2pchat_background_daemon")
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
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
