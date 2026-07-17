package com.example.twopchat

import android.content.Context
import android.util.Log
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.security.IdentityKeyStore

internal fun performAccountDeletion(
    shutdownRuntime: () -> Boolean,
    wipePersistentData: () -> Boolean,
): Boolean {
    if (!shutdownRuntime()) return false
    return wipePersistentData()
}

object AccountLifecycle {
    private const val TAG = "AccountLifecycle"
    private val preservedRuntimeFiles = setOf("chaquopy", "profileInstalled")

    fun deleteAccount(context: Context): Boolean {
        val appContext = context.applicationContext
        return performAccountDeletion(
            shutdownRuntime = {
                P2PMessageRelay.shutdownForAccountDeletion(appContext)
            },
            wipePersistentData = {
                wipePersistentData(appContext)
            },
        )
    }

    private fun wipePersistentData(context: Context): Boolean {
        var success = true

        ChatDatabaseHelper.closeAllConnections()
        PythonBridge.clearAccountCaches()

        if (!context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
        ) {
            Log.e(TAG, "Failed to synchronously clear account preferences")
            success = false
        }

        for (database in context.databaseList()) {
            if (!context.deleteDatabase(database)) {
                Log.e(TAG, "Failed to delete database $database")
                success = false
            }
        }

        context.filesDir.listFiles().orEmpty()
            .filterNot { it.name in preservedRuntimeFiles }
            .forEach { file ->
                if (!file.deleteRecursively()) {
                    Log.e(TAG, "Failed to delete account file ${file.absolutePath}")
                    success = false
                }
            }
        context.cacheDir.listFiles().orEmpty().forEach { file ->
            if (!file.deleteRecursively()) {
                Log.e(TAG, "Failed to delete cache file ${file.absolutePath}")
                success = false
            }
        }

        try {
            IdentityKeyStore.deleteKey()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to delete identity wrapping key", error)
            success = false
        }
        try {
            SecureStorage.deleteKey()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to delete local storage wrapping key", error)
            success = false
        }

        return success
    }
}
