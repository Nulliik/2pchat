package com.example.twopchat.yggdrasil

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.twopchat.service.P2PRelayService

class BootUpReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootUpReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "Wrong action: ${intent.action}")
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, P2PRelayService::class.java),
            )
        }.onFailure {
            Log.w(TAG, "Failed to start P2PRelayService on boot", it)
        }

        val preferences = yggdrasilPrefs(context)
        if (!preferences.getBoolean(PREF_KEY_ENABLED, false)) {
            Log.i(TAG, "Yggdrasil disabled, not starting service")
            return
        }
        Log.i(TAG, "Yggdrasil enabled, starting service")
        val serviceIntent = Intent(context, PacketTunnelProvider::class.java)
        serviceIntent.action = PacketTunnelProvider.ACTION_START

        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.i(TAG, "Need to ask for VPN permission")
            runCatching {
                val notification = createPermissionMissingNotification(context)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(444, notification)
            }.onFailure {
                Log.w(TAG, "Failed to post permission missing notification on boot", it)
            }
        } else {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }.onFailure {
                Log.w(TAG, "Failed to start PacketTunnelProvider on boot", it)
            }
        }
    }
}
