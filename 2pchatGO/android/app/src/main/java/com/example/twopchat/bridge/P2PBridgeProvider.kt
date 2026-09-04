package com.example.twopchat.bridge

import android.content.Context
import com.example.twopchat.logging.SafeLog
/**
 * Provider for the high-performance Native Go P2P Core (lib2pcore.so).
 */
object P2PBridgeProvider {
    private const val TAG = "P2PBridgeProvider"

    @Volatile
    private var cachedBridge: IP2PBridge? = null

    @Synchronized
    fun get(context: Context): IP2PBridge {
        val existing = cachedBridge
        if (existing != null) {
            return existing
        }
        SafeLog.i(TAG, "⚡ Initializing Native Go Core (NativeBridgeImpl / lib2pcore.so)")
        val bridge = NativeBridgeImpl()
        cachedBridge = bridge
        return bridge
    }

    @Synchronized
    fun reset() {
        cachedBridge?.shutdownAllSessions()
        cachedBridge = null
    }
}
