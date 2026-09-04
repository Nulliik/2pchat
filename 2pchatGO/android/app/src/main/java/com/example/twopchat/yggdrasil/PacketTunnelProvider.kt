package com.example.twopchat.yggdrasil

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.example.twopchat.logging.SafeLog
import androidx.core.app.ServiceCompat
import com.example.twopchat.config.*
import com.example.twopchat.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "PacketTunnelProvider"
private const val PREF_YGG_RUNTIME_IP = "yggdrasil_runtime_ip"
private const val PREF_YGG_RUNTIME_STATE = "yggdrasil_runtime_state"
private const val PREF_YGG_RUNTIME_PEERS = "yggdrasil_runtime_peers"
private const val PREF_YGG_RUNTIME_ROUTES = "yggdrasil_runtime_routes"
private const val PREF_YGG_RUNTIME_TREE_NODES = "yggdrasil_runtime_tree_nodes"

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "com.example.twopchat.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "com.example.twopchat.yggdrasil.PacketTunnelProvider.START"
        const val ACTION_STOP = "com.example.twopchat.yggdrasil.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "com.example.twopchat.yggdrasil.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "com.example.twopchat.yggdrasil.PacketTunnelProvider.CONNECT"
        const val ACTION_REGENERATE_KEYS = "com.example.twopchat.yggdrasil.PacketTunnelProvider.REGENERATE_KEYS"
        const val ACTION_RELOAD_PEERS = "com.example.twopchat.yggdrasil.PacketTunnelProvider.RELOAD_PEERS"

        @Volatile
        var isTunnelActive: Boolean = false
            internal set

        private fun yggLog(context: Context?, message: String, level: String = "INFO", error: Throwable? = null) {
            val fullMsg = if (error != null) "$message: ${SafeLog.getStackTraceString(error)}" else message
            if (error != null || level == "ERROR") {
                SafeLog.e(TAG, fullMsg)
            } else if (level == "WARN") {
                SafeLog.w(TAG, fullMsg)
            } else if (level == "DEBUG") {
                SafeLog.d(TAG, fullMsg)
            } else {
                SafeLog.i(TAG, fullMsg)
            }
            if (context != null && level != "DEBUG") {
                try {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault()).format(Date())
                    com.example.twopchat.AppLog.append(context, "$timestamp [KOTLIN_$level] [YGGDRASIL] $TAG: $fullMsg\n")
                } catch (e: Exception) {
                    SafeLog.d(TAG, "Failed appending log to AppLog: ${e.javaClass.simpleName}")
                }
            }
        }

        /** Returns the cross-process tunnel state visible to the main app. */
        fun isTunnelActive(context: Context): Boolean {
            if (isTunnelActive) return true
            return runCatching {
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                connectivity.allNetworks.any { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    val links = connectivity.getLinkProperties(network)
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
                        links?.linkAddresses.orEmpty().any { link ->
                            val host = link.address.hostAddress.orEmpty().substringBefore('%').lowercase()
                            host.startsWith("200:") || host.startsWith("300:") ||
                                host.startsWith("0200:") || host.startsWith("0300:")
                        }
                }
            }.getOrDefault(false)
        }
    }

    private var yggdrasil: Yggdrasil? = null
    private var started = AtomicBoolean()
    private var publicPeerPoolPruned = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
        val notification = createServiceNotification(this, State.Disabled)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to startForeground in onCreate", e)
        }
    }

    override fun onDestroy() {
        isTunnelActive = false
        // Finish our side of the VPN before Service teardown. Calling stopSelf()
        // from onDestroy() can leave VpnService.Callback bound to an already
        // destroyed service until the system notices the closed TUN descriptor.
        stop(stopService = false)
        super.onDestroy()
    }

    override fun onRevoke() {
        SafeLog.i(TAG, "VPN permission revoked by system or another VPN connected -> transitioning Yggdrasil to Proxy mode so it coexists with the user's VPN")
        isTunnelActive = false
        stop(stopService = true)
        // Automatically switch to Proxy mode so the user does not lose Yggdrasil mesh connectivity when their external VPN connects
        P2PPreferences.setYggdrasilMode(this, P2PPreferences.YggdrasilMode.PROXY)
        val enabled = yggdrasilPrefs(this).getBoolean(PREF_KEY_ENABLED, false)
        if (enabled) {
            YggdrasilCoordinator.start(this, P2PPreferences.YggdrasilMode.PROXY)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            SafeLog.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = yggdrasilPrefs(this)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                SafeLog.d(TAG, "Stopping...")
                preferences.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
                stop(); START_NOT_STICKY
            }
            ACTION_START -> {
                SafeLog.d(TAG, "Starting explicitly...")
                if (!enabled) {
                    preferences.edit().putBoolean(PREF_KEY_ENABLED, true).apply()
                }
                if (started.get() && isTunnelHealthy()) {
                    SafeLog.d(TAG, "Tunnel already started and healthy; skipping redundant start")
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_CONNECT -> {
                SafeLog.d(TAG, "Connecting...")
                if (!enabled) {
                    SafeLog.d(TAG, "Yggdrasil is disabled in settings; ignoring ACTION_CONNECT")
                    stop(stopService = true)
                    return START_NOT_STICKY
                }
                if (isTunnelHealthy()) {
                    connect()
                } else {
                    if (started.get()) {
                        SafeLog.w(TAG, "Tunnel reports started but its workers are dead; rebuilding it")
                        stop(stopService = false)
                    }
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
                SafeLog.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    SafeLog.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                SafeLog.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            startTunnel()
        } catch (error: Throwable) {
            // A native/config/VPN setup failure used to leave `started=true`.
            // Every later reconnect was then ignored as an already running
            // tunnel even though no usable TUN workers existed.
            SafeLog.e(TAG, "Unable to start Yggdrasil tunnel", error)
            stop(stopService = false)
        }
    }

    private fun startTunnel() {
        // A network callback may run before Android has granted VPN consent,
        // or after it revoked a previous grant. Avoid starting native Yggdrasil
        // when Builder.establish() cannot create its TUN descriptor.
        if (VpnService.prepare(this) != null) {
            SafeLog.w(TAG, "VPN consent is missing; Yggdrasil tunnel was not started")
            updateRuntimeState("", STATE_DISABLED)
            stop(stopService = true)
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to startForeground in startTunnel with specialUse, falling back", e)
            try {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            } catch (fallbackEx: Exception) {
                SafeLog.e(TAG, "Failed startForeground fallback in startTunnel", fallbackEx)
            }
        }

        // Acquire multicast lock
        multicastLock = try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("Yggdrasil").apply {
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

        val address = ygg.addressString
        if (address.isNullOrBlank()) {
            yggLog(applicationContext, "Yggdrasil returned empty/null address after startJSON", "ERROR")
            updateRuntimeState("", STATE_DISABLED)
            stop(stopService = true)
            return
        }

        updateRuntimeState(address, STATE_ENABLED)
        yggLog(applicationContext, "Yggdrasil started in SYSTEM VPN (TUN) mode with IPv6: $address")
        val builder = Builder()
            .addAddress(address, 7)
            // 200::/7 covers the complete Yggdrasil allocation (200:: through
            // 3ff::).  "300::" is not a valid canonical network address for
            // a /7 prefix; Android rejects it with IllegalArgumentException.
            .addRoute("200::", 7)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .allowBypass()
            .setBlocking(true)
            .setMtu(ygg.mtu.toInt().coerceIn(1280, 1420))
            .setSession("Yggdrasil")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // This is a split tunnel which only owns 200::/7 and 300::/7. Keep DNS
        // on the underlying Wi-Fi/mobile network instead.

        var establishedParcel: ParcelFileDescriptor? = null
        for (attempt in 1..3) {
            try {
                establishedParcel = builder.establish()
                if (establishedParcel != null && establishedParcel.fileDescriptor.valid()) {
                    break
                }
            } catch (e: Throwable) {
                yggLog(applicationContext, "builder.establish() threw on attempt $attempt/3", "WARN", e)
            }
            yggLog(applicationContext, "VPN establish returned null on attempt $attempt/3, waiting for kernel FD release...", "WARN")
            try { Thread.sleep(350L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                // intentionally ignored: retry delay before re-attempting establish()
            }
        }
        parcel = establishedParcel
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            yggLog(applicationContext, "VPN establishment failed after 3 attempts", "ERROR")
            stop(stopService = true)
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread(name = "Yggdrasil-Reader") {
            try {
                reader()
            } catch (e: Throwable) {
                yggLog(applicationContext, "Yggdrasil-Reader thread terminated with error", "ERROR", e)
            }
        }
        writerThread = thread(name = "Yggdrasil-Writer") {
            try {
                writer()
            } catch (e: Throwable) {
                yggLog(applicationContext, "Yggdrasil-Writer thread terminated with error", "ERROR", e)
            }
        }
        updateThread = thread(name = "Yggdrasil-Updater") {
            try {
                updater()
            } catch (e: Throwable) {
                yggLog(applicationContext, "Yggdrasil-Updater thread terminated with error", "ERROR", e)
            }
        }

        isTunnelActive = true
        yggLog(applicationContext, "Yggdrasil SYSTEM VPN (TUN) tunnel active. MTU=${ygg.mtu}")

        val intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun stop(stopService: Boolean = true) {
        yggLog(applicationContext, "Stopping Yggdrasil SYSTEM VPN (TUN)...")
        isTunnelActive = false
        val wasStarted = started.getAndSet(false)
        if (wasStarted) {
            yggdrasil?.let { ygg ->
                runCatching { ygg.stop() }
                    .onFailure { SafeLog.w(TAG, "Unable to stop native Yggdrasil cleanly", it) }
            }
        }

        // БАГ 2 ИСПРАВЛЕН: Сначала прерываем потоки, потом закрываем стримы.
        // Если закрыть стримы раньше — потоки reader/writer получат NPE или IOException.
        val threads = listOfNotNull(readerThread, writerThread, updateThread)
        threads.forEach(Thread::interrupt)

        // Закрываем стримы после того, как потоки прерваны
        runCatching { readerStream?.close() }
        readerStream = null
        runCatching { writerStream?.close() }
        writerStream = null
        runCatching { parcel?.close() }
        parcel = null
        // Do not let a restart overlap old native/FD users. Closing the streams above
        // unblocks reads; bounded joins avoid hanging service shutdown indefinitely.
        threads.filter { it !== Thread.currentThread() }.forEach { thread ->
            try { thread.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        readerThread = null
        writerThread = null
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
        if (!started.get()) {
            return
        }
        yggdrasil?.retryPeersNow()
    }

    private fun isTunnelHealthy(): Boolean =
        started.get() &&
            parcel?.fileDescriptor?.valid() == true &&
            readerThread?.isAlive == true &&
            writerThread?.isAlive == true &&
            updateThread?.isAlive == true

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        // Publish immediately: a 10-second blank period made a successful
        // fresh connection look failed on phones.
        var lastStateUpdate = 0L
        var lastLoggedState = ""
        var lastLoggedPeers = -1
        var lastLogTime = 0L
        val probeStartedAt = System.currentTimeMillis()
        updates@ while (started.get()) {
            if (readerThread?.isAlive != true || writerThread?.isAlive != true) {
                SafeLog.w(TAG, "Tunnel packet worker stopped unexpectedly; rebuilding it")
                if (started.get()) {
                    stop(stopService = false)
                    try { Thread.sleep(500L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        // intentionally ignored: backoff before restarting packet worker
                    }
                    start()
                }
                return
            }
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

            // The first start uses the embedded offline public-peer snapshot.
            // After it has had time to establish links, retain only the best
            // live links for future starts without tearing down active TUN sockets.
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
            val isStable = publicPeerPoolPruned.get()
            if (sleep(isStable)) return
        }
    }

    private fun sleep(isStable: Boolean = false): Boolean {
        try {
            Thread.sleep(if (isStable) 5000L else 1500L)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                SafeLog.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                SafeLog.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            val ygg = yggdrasil ?: break@writes
            try {
                val len = ygg.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                SafeLog.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    continue
                }
                break@writes
            }
        }
        // БАГ 3 ИСПРАВЛЕН: Не закрываем writerStream здесь — это делает stop().
        // Double-close вызывал исключение.
    }

    private fun reader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                SafeLog.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted || !readerStream.fd.valid()) {
                SafeLog.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            val ygg = yggdrasil ?: break@reads
            try {
                val n = readerStream.read(b)
                if (n <= 0) break@reads
                ygg.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                SafeLog.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        // БАГ 3 ИСПРАВЛЕН: Не закрываем readerStream здесь — это делает stop().
        // Double-close вызывал исключение.
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
