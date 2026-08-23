package com.example.twopchat.security

import android.content.Context
import android.util.Log
import com.example.twopchat.relay.MessageNotificationService
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.group.runtime.GroupWorkScheduler
import com.example.twopchat.security.IdentityKeyStore
import java.io.File

/**
 * One auditable place for removing every account-bound artifact from this app.
 *
 * This is deliberately a list of named steps rather than a best-effort call to
 * Android's cache APIs.  It makes both the data covered by account deletion and
 * a failed cleanup step visible in logcat under the `AccountDataWiper` tag.
 */
object AccountDataWiper {
    private val preservedRuntimeFiles = setOf("profileInstalled")

    fun wipe(context: Context): Boolean {
        val appContext = context.applicationContext
        val steps = listOf(
            "cancel background group work" to { GroupWorkScheduler.cancel(appContext); true },
            "close encrypted chat databases" to {
                ChatDatabaseHelper.closeAllConnections()
                SecureStorage.clearDbPassphrase()
                true
            },
            "clear in-memory account state" to {
                com.example.twopchat.bridge.P2PBridgeProvider.reset()
                MessageNotificationService.clearAvatarCache()
                true
            },
            "clear all SharedPreferences" to {
                P2PPreferences.clearInMemoryState()
                clearAllSharedPreferences(appContext)
            },
            "delete chat databases" to { deleteDatabases(appContext) },
            "delete internal account files" to {
                deleteChildren(appContext.filesDir, preservedRuntimeFiles)
            },
            "delete internal cache" to { deleteChildren(appContext.cacheDir) },
            "delete app-specific external files" to { deleteExternalFiles(appContext) },
            "delete identity wrapping key" to { IdentityKeyStore.deleteKey(); true },
            "delete storage wrapping key" to { SecureStorage.deleteKey(); true },
        )
        return runAccountDataWipe(steps)
    }

    private fun clearAllSharedPreferences(context: Context): Boolean {
        // Discover and clear all live instances first, then remove every preference XML.
        // This covers both known and future preference files in memory and on disk.
        val preferencesDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val dynamicallyDiscoveredPrefs = preferencesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .map { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) }

        val knownPreferences = listOf(
            P2PPreferences.prefs(context),
            context.getSharedPreferences(P2PPreferences.FILE_NAME, Context.MODE_PRIVATE),
            context.getSharedPreferences("2pchat_notification_ids", Context.MODE_PRIVATE),
            context.getSharedPreferences("2pchat_network_traffic", Context.MODE_PRIVATE),
            context.getSharedPreferences("2pchat_lock_state", Context.MODE_PRIVATE),
        )
        val allPrefs = (knownPreferences + dynamicallyDiscoveredPrefs).distinct()
        val cleared = allPrefs.all { it.edit().clear().commit() }
        return deleteChildren(preferencesDir) && cleared
    }

    private fun deleteDatabases(context: Context): Boolean {
        // deleteDatabase() may report false even when Android removed the main
        // file (for example while cleaning up SQLite sidecars). Verify the
        // security-relevant postcondition instead: no database artifacts remain.
        context.databaseList().forEach(context::deleteDatabase)
        val databasesDir = File(context.applicationInfo.dataDir, "databases")
        val dirDeleted = deleteChildren(databasesDir)
        return dirDeleted &&
            context.databaseList().isEmpty() &&
            databasesDir.listFiles().orEmpty().isEmpty()
    }

    private fun deleteExternalFiles(context: Context): Boolean =
        (context.externalCacheDirs.asList() + context.getExternalFilesDirs(null).asList())
            .filterNotNull()
            .distinct()
            .all(::deleteChildren)

    private fun deleteChildren(directory: File, preserveNames: Set<String> = emptySet()): Boolean =
        directory.listFiles().orEmpty()
            .filterNot { it.name in preserveNames }
            .all { it.deleteRecursively() }
}

/** Runs account-wipe steps in order and reports every failed step to logcat. */
internal fun runAccountDataWipe(
    steps: List<Pair<String, () -> Boolean>>,
    onFailure: (String, Exception?) -> Unit = { name, error ->
        if (error == null) {
            Log.e("AccountDataWiper", "Account wipe step failed: $name")
        } else {
            Log.e("AccountDataWiper", "Account wipe step failed: $name", error)
        }
    },
): Boolean {
    var success = true
    for ((name, action) in steps) {
        var errorReported = false
        val completed = try {
            action()
        } catch (error: Exception) {
            onFailure(name, error)
            errorReported = true
            false
        }
        if (!completed && !errorReported) {
            onFailure(name, null)
        }
        if (!completed) {
            success = false
        }
    }
    return success
}
