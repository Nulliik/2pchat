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
                val wasTunnelActive = PacketTunnelProvider.isTunnelActive(context) || PacketTunnelProvider.isTunnelActive
                if (wasTunnelActive) {
                    stopVpn(context)
                }
                NativeBridge.setYggdrasilConfig("proxy", "127.0.0.1:${P2PPreferences.DEFAULT_YGGDRASIL_PROXY_PORT}")
                val delayMs = if (wasTunnelActive) STOP_SETTLE_MS else 0L
                mainHandler.postDelayed({
                    if (P2PPreferences.getYggdrasilMode(context) != YggdrasilMode.PROXY) return@postDelayed
                    val intent = Intent(context, YggdrasilProxyService::class.java).apply {
                        action = YggdrasilProxyService.ACTION_START
                    }
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        try { context.startService(intent) } catch (_: Exception) {}
                    }
                }, delayMs)
            }
            YggdrasilMode.VPN -> {
                stopProxy(context)
                NativeBridge.setYggdrasilConfig("vpn", "")
                mainHandler.postDelayed({
                    if (P2PPreferences.getYggdrasilMode(context) != YggdrasilMode.VPN) return@postDelayed
                    val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                        action = PacketTunnelProvider.ACTION_START
                    }
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        try { context.startService(intent) } catch (_: Exception) {}
                    }
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
        try {
            context.stopService(Intent(context, YggdrasilProxyService::class.java))
        } catch (_: Exception) {}
    }

    fun stopVpn(context: Context) {
        if (PacketTunnelProvider.isTunnelActive(context) || PacketTunnelProvider.isTunnelActive) {
            val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                action = PacketTunnelProvider.ACTION_STOP
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                try { context.stopService(intent) } catch (_: Exception) {}
            }
        }
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
