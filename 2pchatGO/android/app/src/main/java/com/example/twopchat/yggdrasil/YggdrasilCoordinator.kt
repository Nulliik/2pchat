package com.example.twopchat.yggdrasil

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.P2PPreferences.YggdrasilMode

object YggdrasilCoordinator {
    private const val TAG = "YggdrasilCoordinator"
    private const val STOP_SETTLE_MS = 2_200L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context, requestedMode: YggdrasilMode? = null) {
        val mode = requestedMode ?: P2PPreferences.getYggdrasilMode(context)
        when (mode) {
            YggdrasilMode.PROXY -> {
                // Ensure VPN service is stopped
                stopVpn(context)
                NativeBridge.setYggdrasilConfig("proxy", "127.0.0.1:${P2PPreferences.DEFAULT_YGGDRASIL_PROXY_PORT}")
                // VpnService teardown closes its TUN readers asynchronously.
                // Starting Proxy immediately let its ENABLED state be overwritten
                // by the old VPN's final DISABLED update.
                mainHandler.postDelayed({
                    if (P2PPreferences.getYggdrasilMode(context) != YggdrasilMode.PROXY) return@postDelayed
                    val intent = Intent(context, YggdrasilProxyService::class.java).apply {
                        action = YggdrasilProxyService.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, intent)
                }, STOP_SETTLE_MS)
            }
            YggdrasilMode.VPN -> {
                // Ensure Proxy service is stopped
                stopProxy(context)
                NativeBridge.setYggdrasilConfig("vpn", "")
                mainHandler.postDelayed({
                    if (P2PPreferences.getYggdrasilMode(context) != YggdrasilMode.VPN) return@postDelayed
                    val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                        action = PacketTunnelProvider.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, intent)
                }, STOP_SETTLE_MS)
            }
        }
    }

    fun stop(context: Context) {
        stopProxy(context)
        stopVpn(context)
    }

    fun stopProxy(context: Context) {
        // Sending START_SERVICE(ACTION_STOP) from a broadcast receiver is
        // rejected by modern Android while the app is in the background.
        // stopService is explicitly allowed and invokes the service teardown.
        context.stopService(Intent(context, YggdrasilProxyService::class.java))
    }

    fun stopVpn(context: Context) {
        // VpnService is also bound by Android. stopService() alone only drops
        // our start request and can leave tun0 alive under that system binding.
        // Its ACTION_STOP closes the ParcelFileDescriptor before stopping.
        val intent = Intent(context, PacketTunnelProvider::class.java).apply {
            action = PacketTunnelProvider.ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun connect(context: Context) {
        val mode = P2PPreferences.getYggdrasilMode(context)
        val targetClass = if (mode == YggdrasilMode.VPN) PacketTunnelProvider::class.java else YggdrasilProxyService::class.java
        val action = if (mode == YggdrasilMode.VPN) PacketTunnelProvider.ACTION_CONNECT else YggdrasilProxyService.ACTION_CONNECT
        val intent = Intent(context, targetClass).apply { this.action = action }
        context.startService(intent)
    }

    fun reloadPeers(context: Context) {
        val mode = P2PPreferences.getYggdrasilMode(context)
        val targetClass = if (mode == YggdrasilMode.VPN) PacketTunnelProvider::class.java else YggdrasilProxyService::class.java
        val action = if (mode == YggdrasilMode.VPN) PacketTunnelProvider.ACTION_RELOAD_PEERS else YggdrasilProxyService.ACTION_RELOAD_PEERS
        val intent = Intent(context, targetClass).apply { this.action = action }
        context.startService(intent)
    }

    fun regenerateKeys(context: Context) {
        val mode = P2PPreferences.getYggdrasilMode(context)
        val targetClass = if (mode == YggdrasilMode.VPN) PacketTunnelProvider::class.java else YggdrasilProxyService::class.java
        val action = if (mode == YggdrasilMode.VPN) PacketTunnelProvider.ACTION_REGENERATE_KEYS else YggdrasilProxyService.ACTION_REGENERATE_KEYS
        val intent = Intent(context, targetClass).apply { this.action = action }
        context.startService(intent)
    }

    fun isRunning(context: Context): Boolean {
        return YggdrasilProxyService.isProxyActive(context) || PacketTunnelProvider.isTunnelActive(context)
    }
}
