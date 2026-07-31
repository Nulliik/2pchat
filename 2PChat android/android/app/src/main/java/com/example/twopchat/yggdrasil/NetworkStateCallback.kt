package com.example.twopchat.yggdrasil

import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "Network"

class NetworkStateCallback(val context: Context) : ConnectivityManager.NetworkCallback() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "onAvailable")

        val preferences = yggdrasilPrefs(context)
        if (preferences.getBoolean(PREF_KEY_ENABLED, true)) {
            scope.launch {
                // The message often arrives before the connection is fully established
                delay(1000)
                val intent = Intent(context, PacketTunnelProvider::class.java)
                intent.action = PacketTunnelProvider.ACTION_CONNECT
                try {
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }
        }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(TAG, "onLost")
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(request, this)
    }
}
