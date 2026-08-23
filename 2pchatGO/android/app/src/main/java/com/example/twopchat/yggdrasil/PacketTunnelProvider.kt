package com.example.twopchat.yggdrasil

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.twopchat.config.*
import com.example.twopchat.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
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
    }

    private var yggdrasil = Yggdrasil()
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
            Log.w(TAG, "Failed to startForeground in onCreate", e)
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
        Log.i(TAG, "VPN permission revoked by system or another VPN connected -> stopping Yggdrasil and marking disabled")
        isTunnelActive = false
        // When another VPN starts, Android revokes our VPN slot.
        // We MUST update preferences to disabled so we don't aggressively fight the user's other VPN!
        yggdrasilPrefs(this).edit().putBoolean(PREF_KEY_ENABLED, false).apply()
        com.example.twopchat.config.P2PPreferences.prefs(this).edit().putBoolean("settings_yggdrasil", false).apply()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = yggdrasilPrefs(this)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                preferences.edit().putBoolean(PREF_KEY_ENABLED, false).apply()
                stop(); START_NOT_STICKY
            }
            ACTION_START -> {
                Log.d(TAG, "Starting explicitly...")
                if (!enabled) {
                    preferences.edit().putBoolean(PREF_KEY_ENABLED, true).apply()
                }
                if (started.get() && isTunnelHealthy()) {
                    Log.d(TAG, "Tunnel already started and healthy; skipping redundant start")
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (!enabled) {
                    Log.d(TAG, "Yggdrasil is disabled in settings; ignoring ACTION_CONNECT")
                    stop(stopService = true)
                    return START_NOT_STICKY
                }
                if (isTunnelHealthy()) {
                    connect()
                } else {
                    if (started.get()) {
                        Log.w(TAG, "Tunnel reports started but its workers are dead; rebuilding it")
                        stop(stopService = false)
                    }
                    start()
                }
                START_STICKY
            }
            ACTION_REGENERATE_KEYS -> {
                Log.i(TAG, "Regenerating Yggdrasil node keys...")
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
                Log.i(TAG, "Applying updated Yggdrasil peer configuration...")
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
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
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
            Log.e(TAG, "Unable to start Yggdrasil tunnel", error)
            stop(stopService = false)
        }
    }

    private fun startTunnel() {
        // A network callback may run before Android has granted VPN consent,
        // or after it revoked a previous grant. Avoid starting native Yggdrasil
        // when Builder.establish() cannot create its TUN descriptor.
        if (VpnService.prepare(this) != null) {
            Log.w(TAG, "VPN consent is missing; Yggdrasil tunnel was not started")
            updateRuntimeState("", STATE_DISABLED)
            stop(stopService = true)
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
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

        // Acquire multicast lock
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        yggdrasil.startJSON(config.getJSONByteArray())

        val address = yggdrasil.addressString
        updateRuntimeState(address, STATE_ENABLED)
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt().coerceIn(1280, 1420))
            .setSession("Yggdrasil")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // This is a split tunnel which only owns 200::/7. Advertising public
        // DNS servers on this VPN makes Android bind resolver traffic to tun1,
        // even though those addresses cannot be carried by Yggdrasil. Keep DNS
        // on the underlying Wi-Fi/mobile network instead. Device logs showed
        // the previous setup breaking every tracker lookup after tun1 started.

        var establishedParcel: ParcelFileDescriptor? = null
        for (attempt in 1..3) {
            establishedParcel = builder.establish()
            if (establishedParcel != null && establishedParcel.fileDescriptor.valid()) {
                break
            }
            Log.w(TAG, "VPN establish returned null on attempt $attempt/3, waiting for kernel FD release...")
            try { Thread.sleep(350L) } catch (_: InterruptedException) {}
        }
        parcel = establishedParcel
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            Log.e(TAG, "VPN establishment failed after 3 attempts")
            stop(stopService = true)
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread(name = "Yggdrasil-Reader") {
            reader()
        }
        writerThread = thread(name = "Yggdrasil-Writer") {
            writer()
        }
        updateThread = thread(name = "Yggdrasil-Updater") {
            updater()
        }

        isTunnelActive = true

        val intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun stop(stopService: Boolean = true) {
        isTunnelActive = false
        val wasStarted = started.getAndSet(false)
        if (wasStarted) {
            runCatching { yggdrasil.stop() }
                .onFailure { Log.w(TAG, "Unable to stop native Yggdrasil cleanly", it) }
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
        yggdrasil.retryPeersNow()
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
        val probeStartedAt = System.currentTimeMillis()
        var wasConnected = false
        updates@ while (started.get()) {
            if (readerThread?.isAlive != true || writerThread?.isAlive != true) {
                Log.w(TAG, "Tunnel packet worker stopped unexpectedly; rebuilding it")
                if (started.get()) {
                    stop(stopService = false)
                    try { Thread.sleep(500L) } catch (_: InterruptedException) {}
                    start()
                }
                return
            }
            val treeJSON = yggdrasil.treeJSON
            if ((application as GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                val routes = yggdrasil.routingEntries.toInt()
                val peerCount = jsonArrayLength(yggdrasil.peersJSON)
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
                updateRuntimeState(yggdrasil.addressString, state, peerCount, routes, treeNodes, yggdrasil.peersJSON)
                intent.putExtra("state", state)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                val isConnected = state == STATE_CONNECTED && routes > 0
                if (isConnected && !wasConnected) {
                    // The first reconnect after an Android network change is
                    // intentionally early and may announce IPv4 only. Publish
                    // the Yggdrasil endpoint as soon as it is truly routable.
                    com.example.twopchat.relay.P2PMessageRelay.triggerImmediateReconnect(applicationContext)
                }
                wasConnected = isConnected
                lastStateUpdate = curTime
            }

            // The first start uses the embedded offline public-peer snapshot.
            // After it has had time to establish links, retain only the best
            // live links for future starts without tearing down active TUN sockets.
            if (
                !publicPeerPoolPruned.get() &&
                curTime - probeStartedAt >= 25_000
            ) {
                config.retainBestLivePeers(yggdrasil.peersJSON)
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
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
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
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted || !readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                if (n <= 0) break@reads
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
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
            Log.w(TAG, "Failed to persist Yggdrasil runtime state", e)
        }
    }
}
