package com.example.twopchat.security

import android.content.Context
import com.example.twopchat.relay.P2PMessageRelay

internal fun performAccountDeletion(
    shutdownRuntime: () -> Boolean,
    wipePersistentData: () -> Boolean,
): Boolean {
    if (!shutdownRuntime()) return false
    return wipePersistentData()
}

object AccountLifecycle {
    fun deleteAccount(context: Context): Boolean {
        val appContext = context.applicationContext
        return performAccountDeletion(
            shutdownRuntime = {
                P2PMessageRelay.shutdownForAccountDeletion(appContext)
            },
            wipePersistentData = {
                AccountDataWiper.wipe(appContext)
            },
        )
    }
}
