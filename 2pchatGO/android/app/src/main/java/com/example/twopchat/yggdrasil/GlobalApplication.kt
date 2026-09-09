package com.example.twopchat.yggdrasil

import com.example.twopchat.logging.SafeLog
import com.example.twopchat.config.P2PPreferences

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val PREF_KEY_ENABLED = "settings_yggdrasil"
const val PREF_KEY_PEERS_NOTE = "peers_note"
const val MAIN_CHANNEL_ID = "Yggdrasil Service"
const val SERVICE_NOTIFICATION_ID = 1000

class GlobalApplication: Application(), YggStateReceiver.StateReceiver {
    private var currentState: State = State.Disabled
    private var updaterConnections: Int = 0
    private val isFullProfileInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        lateinit var appContext: Context
            private set

        fun getContext(): Context = appContext

        @Volatile
        var isHeadlessWorkerOverride: Boolean? = null
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // libgojni.so (Yggdrasil/gomobile) and lib2pcore.so each embed a Go
        // runtime. Loading both runtimes in one Android process corrupts cgo
        // callback unwinding and aborts with "fatal error: unknown caller pc".
        // The VPN service has its own process (:yggdrasil), so keep all main-process
        // P2P/UI initialization strictly isolated from the Yggdrasil process.
        if (isYggdrasilServiceProcess(currentProcessName(this))) {
            return
        }

        // Consent/storage initialization is local and asynchronous. Until it finishes,
        // optional diagnostics remain disabled. Never load lib2pcore in :yggdrasil.
        com.example.twopchat.diagnostics.PublicDiagnostics.installCrashHandler()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.example.twopchat.diagnostics.PublicDiagnostics.initialize(applicationContext) }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                initFullProfile()
            }
            override fun onActivityStarted(activity: Activity) {
                AppForegroundTracker.onActivityStarted()
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                AppForegroundTracker.onActivityStopped()
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val isHeadlessRun = !isForegroundActivityLaunch()
        if (isHeadlessRun) {
            SafeLog.i("GlobalApplication", "Headless background worker run detected; initializing minimal profile")
            initMinimalProfile()
        } else {
            SafeLog.i("GlobalApplication", "Foreground activity launch detected; initializing full profile")
            initFullProfile()
        }
    }

    fun isForegroundActivityLaunch(): Boolean {
        isHeadlessWorkerOverride?.let { return !it }
        if (AppForegroundTracker.isAppInForeground()) return true
        return runCatching {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
    }

    fun isMainProcessForegroundActivityLaunch(): Boolean = isForegroundActivityLaunch()

    /**
     * Minimal profile for headless execution (e.g. WorkManager background workers):
     * NativeBridge + SQLCipher (lazy), crash handler,
     * without Tor daemon, without FGS, without Keystore UI-prewarm,
     * without registering sensitive memory screen monitors or VPN state receivers.
     */
    fun initMinimalProfile() {
        setupCrashHandler()
        loadSqlcipherLibrary()
    }

    /**
     * Full profile for interactive foreground user sessions:
     * Minimal profile + Keystore prewarm, VPN/Yggdrasil state receivers,
     * NetworkStateCallback, and SensitiveMemory screen off/lifecycle monitors.
     */
    fun initFullProfile() {
        if (!isFullProfileInitialized.compareAndSet(false, true)) return

        setupCrashHandler()
        loadSqlcipherLibrary()

        if (com.example.twopchat.BuildConfig.DEBUG) {
            try {
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder()
                        .detectNetwork()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectCustomSlowCalls()
                        .penaltyLog()
                        .build()
                )
            } catch (_: Throwable) {
                // intentionally ignored: StrictMode initialization in unit tests or unsupported Android runtime
            }
        }

        // Asynchronously initialize and warm up Android Keystore, MasterKey, SecureStorage, and
        // EncryptedSharedPreferences on Dispatchers.IO so that crypto operations do not stall the UI thread.
        com.example.twopchat.security.KeystoreProvider.initAsync(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = yggdrasilPrefs(applicationContext)
                if (!prefs.contains(PREF_KEY_ENABLED)) {
                    // Wait for explicit VPN consent. Starting from a network callback
                    // before it is granted makes Builder.establish() return null.
                    prefs.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
                }
            } catch (e: Throwable) {
                SafeLog.w("GlobalApplication", "Failed to initialize default yggdrasil preferences", e)
            }
        }

        val callback = NetworkStateCallback(this)
        callback.register()
        val receiver = YggStateReceiver(this)
        receiver.register(this)

        try {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                com.example.twopchat.security.SensitiveMemoryLifecycleMonitor
            )
            com.example.twopchat.security.SensitiveMemoryLifecycleMonitor.registerScreenOffReceiver(this)
        } catch (e: Throwable) {
            SafeLog.w("GlobalApplication", "Failed to register sensitive memory lifecycle monitor", e)
        }
    }

    private fun setupCrashHandler() {
        com.example.twopchat.diagnostics.PublicDiagnostics.installCrashHandler()
    }

    private fun loadSqlcipherLibrary() {
        val oldPolicy = android.os.StrictMode.allowThreadDiskReads()
        try {
            android.os.StrictMode.allowThreadDiskWrites()
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: Throwable) {
                SafeLog.e("GlobalApplication", "Failed to load sqlcipher", e)
            }
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
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

object AppForegroundTracker {
    private val startedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var isForegroundOverride: Boolean? = null
        internal set

    fun onActivityStarted() {
        startedActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        if (startedActivities.get() > 0) {
            startedActivities.decrementAndGet()
        }
    }

    fun isAppInForeground(): Boolean {
        isForegroundOverride?.let { return it }
        if (startedActivities.get() > 0) return true
        return runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
    }

    fun resetForTesting() {
        startedActivities.set(0)
        isForegroundOverride = null
    }
}
