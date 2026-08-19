package com.example.twopchat

import android.content.Context

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
