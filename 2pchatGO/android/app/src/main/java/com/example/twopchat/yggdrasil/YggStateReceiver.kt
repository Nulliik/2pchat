package com.example.twopchat.yggdrasil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

const val STATE_ENABLED = "enabled"
const val STATE_DISABLED = "disabled"
const val STATE_CONNECTED = "connected"
const val STATE_RECONNECTING = "reconnecting"

class YggStateReceiver(var receiver: StateReceiver): BroadcastReceiver() {

    companion object {
        const val YGG_STATE_INTENT = "com.example.twopchat.yggdrasil.YggStateReceiver.STATE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val state = when (intent?.getStringExtra("state")) {
            STATE_ENABLED -> State.Enabled
            STATE_DISABLED -> State.Disabled
            STATE_CONNECTED -> State.Connected
            STATE_RECONNECTING -> State.Reconnecting
            else -> State.Unknown
        }
        val proxyPort = intent?.getIntExtra("proxy_port", -1) ?: -1
        val proxyAddr = intent?.getStringExtra("proxy_addr")
        if (proxyPort > 0) {
            com.example.twopchat.config.P2PPreferences.setYggdrasilProxyPort(context, proxyPort)
        }
        if (!proxyAddr.isNullOrBlank()) {
            val mode = com.example.twopchat.config.P2PPreferences.getYggdrasilMode(context)
            com.example.twopchat.NativeBridge.setYggdrasilConfig(mode.id, proxyAddr)
        }

        receiver.onStateChange(state)
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(YGG_STATE_INTENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }

    interface StateReceiver {
        fun onStateChange(state: State)
    }
}

/**
 * A class-supporter with an Yggdrasil state
 */
enum class State {
    Unknown, Disabled, Enabled, Connected, Reconnecting;
}
