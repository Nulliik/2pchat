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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.twopchat.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "PacketTunnelProvider"
const val KEY_ENABLE_CHROME_FIX = "enable_chrome_fix"
const val KEY_DNS_SERVERS = "dns_servers"
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
                stop(); START_NOT_STICKY
            }
            ACTION_START -> {
                Log.d(TAG, "Starting explicitly...")
                if (!enabled) {
                    preferences.edit().putBoolean(PREF_KEY_ENABLED, true).apply()
                }
                start()
                START_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
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
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = yggdrasilPrefs(this)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        parcel = builder.establish()
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stop()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread {
            reader()
        }
        writerThread = thread {
            writer()
        }
        updateThread = thread {
            updater()
        }

        val intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stop(stopService: Boolean = true) {
        if (!started.compareAndSet(true, false)) {
            return
        }

        yggdrasil.stop()

        // БАГ 2 ИСПРАВЛЕН: Сначала прерываем потоки, потом закрываем стримы.
        // Если закрыть стримы раньше — потоки reader/writer получат NPE или IOException.
        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        // Закрываем стримы после того, как потоки прерваны
        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        updateRuntimeState("", STATE_DISABLED)

        stopForeground(true)
        if (stopService) {
            stopSelf()
        }
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.retryPeersNow()
    }

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
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            // The first start uses the embedded offline public-peer snapshot.
            // After it has had time to establish links, retain only the best
            // live links and restart once so the excess dial attempts stop.
            if (
                !publicPeerPoolPruned.get() &&
                curTime - probeStartedAt >= 25_000 &&
                config.retainBestLivePeers(yggdrasil.peersJSON)
            ) {
                publicPeerPoolPruned.set(true)
                thread(name = "Yggdrasil-pruned-restart") {
                    stop(stopService = false)
                    yggdrasil = Yggdrasil()
                    start()
                }
                return
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
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
            val sharedPrefs = applicationContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
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
