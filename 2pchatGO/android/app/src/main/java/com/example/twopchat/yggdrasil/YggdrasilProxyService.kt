package com.example.twopchat.yggdrasil

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.example.twopchat.logging.SafeLog
import androidx.core.app.ServiceCompat
import com.example.twopchat.config.*
import com.example.twopchat.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "YggdrasilProxyService"
private const val PROXY_NOTIFICATION_ID = 2002

private const val PREF_YGG_RUNTIME_IP = "yggdrasil_runtime_ip"
private const val PREF_YGG_RUNTIME_STATE = "yggdrasil_runtime_state"
private const val PREF_YGG_RUNTIME_PEERS = "yggdrasil_runtime_peers"
private const val PREF_YGG_RUNTIME_ROUTES = "yggdrasil_runtime_routes"
private const val PREF_YGG_RUNTIME_TREE_NODES = "yggdrasil_runtime_tree_nodes"

class YggdrasilProxyService : Service() {
    companion object {
        const val STATE_INTENT = "com.example.twopchat.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "com.example.twopchat.yggdrasil.YggdrasilProxyService.START"
        const val ACTION_STOP = "com.example.twopchat.yggdrasil.YggdrasilProxyService.STOP"
        const val ACTION_TOGGLE = "com.example.twopchat.yggdrasil.YggdrasilProxyService.TOGGLE"
        const val ACTION_CONNECT = "com.example.twopchat.yggdrasil.YggdrasilProxyService.CONNECT"
        const val ACTION_REGENERATE_KEYS = "com.example.twopchat.yggdrasil.YggdrasilProxyService.REGENERATE_KEYS"
        const val ACTION_RELOAD_PEERS = "com.example.twopchat.yggdrasil.YggdrasilProxyService.RELOAD_PEERS"

        @Volatile
        var isProxyActive: Boolean = false
            internal set

        private fun yggLog(context: Context?, message: String, level: String = "INFO", error: Throwable? = null) {
            val fullMsg = if (error != null) "$message: ${SafeLog.getStackTraceString(error)}" else message
            if (error != null || level == "ERROR") {
                SafeLog.e(TAG, fullMsg)
            } else if (level == "WARN") {
                SafeLog.w(TAG, fullMsg)
            } else {
                SafeLog.i(TAG, fullMsg)
            }
            if (context != null) {
                try {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault()).format(Date())
                    com.example.twopchat.AppLog.append(context, "$timestamp [KOTLIN_$level] [YGGDRASIL] $TAG: $fullMsg\n")
                } catch (_: Exception) {}
            }
        }

        fun isProxyActive(context: Context): Boolean {
            if (isProxyActive) return true
            val prefs = P2PPreferences.prefs(context)
            val state = prefs.getString(PREF_YGG_RUNTIME_STATE, STATE_DISABLED)
            return state == STATE_ENABLED || state == STATE_CONNECTED
        }
    }

    private var yggdrasil: Yggdrasil? = null
    private var started = AtomicBoolean()
    private var publicPeerPoolPruned = AtomicBoolean()
    private lateinit var config: ConfigurationProxy
    private var updateThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var userStack: YggdrasilUserSpaceStack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
        promoteToForeground()
    }

    private fun promoteToForeground() {
        val notification = createServiceNotification(this, State.Disabled)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    PROXY_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(PROXY_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to startForeground with specialUse, fallback to regular startForeground", e)
            try {
                startForeground(PROXY_NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isProxyActive = false
        stop(stopService = false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent == null) {
            SafeLog.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = yggdrasilPrefs(this)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                SafeLog.d(TAG, "Stopping Yggdrasil proxy service...")
                preferences.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
                stop(); START_NOT_STICKY
            }
            ACTION_START -> {
                SafeLog.d(TAG, "Starting Yggdrasil proxy service explicitly...")
                if (!enabled) {
                    preferences.edit().putBoolean(PREF_KEY_ENABLED, true).apply()
                }
                if (started.get() && isProxyHealthy()) {
                    SafeLog.d(TAG, "Proxy already started and healthy; skipping redundant start")
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_CONNECT -> {
                SafeLog.d(TAG, "Connecting Yggdrasil peers...")
                if (!enabled) {
                    SafeLog.d(TAG, "Yggdrasil is disabled in settings; ignoring ACTION_CONNECT")
                    stop(stopService = true)
                    return START_NOT_STICKY
                }
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_REGENERATE_KEYS -> {
                SafeLog.i(TAG, "Regenerating Yggdrasil node keys...")
                val restart = started.get() || enabled
                if (started.get()) {
                    stop(stopService = false)
                }
                config.resetKeys()
                updateRuntimeState("", STATE_DISABLED)
                if (restart) {
                    start()
                }
                START_STICKY
            }
            ACTION_RELOAD_PEERS -> {
                SafeLog.i(TAG, "Applying updated Yggdrasil peer configuration...")
                val restart = started.get() || enabled
                if (started.get()) {
                    stop(stopService = false)
                }
                config.applyPeerPreferences()
                if (restart) {
                    start()
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            ACTION_TOGGLE -> {
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    return START_NOT_STICKY
                }
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            startProxyEngine()
        } catch (error: Throwable) {
            SafeLog.e(TAG, "Unable to start Yggdrasil proxy service", error)
            stop(stopService = false)
        }
    }

    private fun startProxyEngine() {
        val notification = createServiceNotification(this, State.Enabled)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    PROXY_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(PROXY_NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to startForeground in startProxyEngine", e)
            try {
                startForeground(PROXY_NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
        }

        // Acquire multicast lock
        multicastLock = try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("YggdrasilProxy").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Could not acquire MulticastLock", e)
            null
        }

        val ygg = yggdrasil ?: Yggdrasil().also { yggdrasil = it }
        try {
            ygg.startJSON(config.getJSONByteArray())
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to execute startJSON on native Yggdrasil", e)
            updateRuntimeState("", STATE_DISABLED)
            stop(stopService = true)
            return
        }

        var address = ygg.addressString
        for (i in 1..10) {
            if (!address.isNullOrBlank()) break
            try { Thread.sleep(100L) } catch (_: InterruptedException) {}
            address = ygg.addressString
        }
        if (address.isNullOrBlank()) {
            yggLog(applicationContext, "Yggdrasil returned empty/null address after startJSON", "ERROR")
            updateRuntimeState("", STATE_DISABLED)
            stop(stopService = true)
            return
        }

        yggLog(applicationContext, "Yggdrasil started in PROXY mode (127.0.0.1:9053) with IPv6: $address")
        updateRuntimeState(address, STATE_ENABLED)

        try {
            userStack = YggdrasilUserSpaceStack(ygg, socksPort = 9053, localTargetPort = 50001).apply {
                start()
            }
            yggLog(applicationContext, "Yggdrasil UserSpace SOCKS5 stack listening on 127.0.0.1:9053 -> target: 50001")
        } catch (e: Throwable) {
            yggLog(applicationContext, "Failed to initialize YggdrasilUserSpaceStack", "ERROR", e)
        }

        updateThread = thread(name = "Yggdrasil-Proxy-Updater") {
            try {
                updater()
            } catch (e: Throwable) {
                yggLog(applicationContext, "Yggdrasil-Proxy-Updater thread terminated with error", "ERROR", e)
            }
        }

        isProxyActive = true

        val intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun stop(stopService: Boolean = true) {
        yggLog(applicationContext, "Stopping Yggdrasil PROXY service...")
        isProxyActive = false
        userStack?.stop()
        userStack = null
        val wasStarted = started.getAndSet(false)
        if (wasStarted) {
            yggdrasil?.let { ygg ->
                runCatching { ygg.stop() }
                    .onFailure { SafeLog.w(TAG, "Unable to stop native Yggdrasil cleanly", it) }
            }
            yggdrasil = null
        }

        updateThread?.interrupt()
        try { updateThread?.join(1_500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        updateThread = null

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        intent.setPackage(packageName)
        sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        updateRuntimeState("", STATE_DISABLED)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (stopService) {
            stopSelf()
        }
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        multicastLock = null
    }

    private fun connect() {
        if (!started.get()) return
        yggdrasil?.retryPeersNow()
    }

    private fun isProxyHealthy(): Boolean =
        started.get() && updateThread?.isAlive == true && userStack != null

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = 0L
        var lastLoggedState = ""
        var lastLoggedPeers = -1
        var lastLogTime = 0L
        val probeStartedAt = System.currentTimeMillis()
        updates@ while (started.get()) {
            val ygg = yggdrasil ?: break@updates
            val treeJSON = runCatching { ygg.treeJSON }.getOrNull()
            if ((application as? GlobalApplication)?.needUiUpdates() == true) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", runCatching { ygg.addressString }.getOrDefault(""))
                intent.putExtra("subnet", runCatching { ygg.subnetString }.getOrDefault(""))
                intent.putExtra("pubkey", runCatching { ygg.publicKeyString }.getOrDefault(""))
                intent.putExtra("peers", runCatching { ygg.peersJSON }.getOrDefault(""))
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                val routes = ygg.routingEntries.toInt()
                val peerCount = jsonArrayLength(ygg.peersJSON)
                var treeNodes = 0
                var state = STATE_ENABLED
                if (routes > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    treeNodes = jsonArrayLength(treeJSON)
                    if (treeNodes > 1)
                        state = STATE_CONNECTED
                }
                updateRuntimeState(ygg.addressString, state, peerCount, routes, treeNodes, ygg.peersJSON)
                intent.putExtra("state", state)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                lastStateUpdate = curTime

                if (state != lastLoggedState || peerCount != lastLoggedPeers || curTime - lastLogTime >= 30_000L) {
                    yggLog(applicationContext, "Mesh state=$state, peers=$peerCount, routes=$routes, treeNodes=$treeNodes, IPv6=${ygg.addressString}")
                    lastLoggedState = state
                    lastLoggedPeers = peerCount
                    lastLogTime = curTime
                }
            }

            if (
                !publicPeerPoolPruned.get() &&
                curTime - probeStartedAt >= 25_000
            ) {
                config.retainBestLivePeers(ygg.peersJSON)
                publicPeerPoolPruned.set(true)
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break@updates
            }
        }
    }

    private fun jsonArrayLength(value: String?): Int = try {
        if (value.isNullOrBlank() || value == "null") 0 else JSONArray(value).length()
    } catch (_: Exception) {
        0
    }

    private fun updateRuntimeState(
        address: String,
        state: String,
        peerCount: Int = 0,
        routes: Int = 0,
        treeNodes: Int = 0,
        peersJson: String = ""
    ) {
        try {
            val sharedPrefs = com.example.twopchat.config.P2PPreferences.prefs(applicationContext)
            val editor = sharedPrefs.edit()
                .putString(PREF_YGG_RUNTIME_IP, address)
                .putString(PREF_YGG_RUNTIME_STATE, state)
                .putInt(PREF_YGG_RUNTIME_PEERS, peerCount)
                .putInt(PREF_YGG_RUNTIME_ROUTES, routes)
                .putInt(PREF_YGG_RUNTIME_TREE_NODES, treeNodes)
            if (peersJson.isNotEmpty()) {
                editor.putString("yggdrasil_runtime_peers_json", peersJson)
            } else if (state == STATE_DISABLED) {
                editor.putString("yggdrasil_runtime_peers_json", "")
            }
            editor.apply()
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to persist Yggdrasil runtime state", e)
        }
    }
}

