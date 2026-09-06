package com.example.twopchat.security

import android.content.Context
import androidx.security.crypto.MasterKey
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.logging.SafeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Centralized provider for Android Keystore master keys and crypto prewarming.
 *
 * Ensures expensive Keystore and Tink operations are initialized once on Dispatchers.IO
 * and cached in memory for the process lifetime, preventing UI thread stalls (e.g. keystore2 IPC).
 */
@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object KeystoreProvider {
    private const val TAG = "KeystoreProvider"
    private val masterKeyDeferred = CompletableDeferred<MasterKey>()
    private val lock = Any()

    @Volatile
    private var isInitializing = false

    /**
     * Suspendable access to the shared application [MasterKey], resolving on [Dispatchers.IO].
     */
    suspend fun getMasterKey(context: Context): MasterKey {
        if (masterKeyDeferred.isCompleted) {
            return masterKeyDeferred.getCompleted()
        }
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (masterKeyDeferred.isCompleted) {
                    return@withContext masterKeyDeferred.getCompleted()
                }
                val appContext = context.applicationContext
                val key = buildMasterKey(appContext)
                masterKeyDeferred.complete(key)
                key
            }
        }
    }

    /**
     * Thread-safe access to the [MasterKey].
     * Returns immediately from cache if already initialized.
     * If called before async initialization completes, ensures execution on Dispatchers.IO.
     */
    fun getOrBuildMasterKey(context: Context): MasterKey {
        if (masterKeyDeferred.isCompleted) {
            return masterKeyDeferred.getCompleted()
        }
        return synchronized(lock) {
            if (masterKeyDeferred.isCompleted) {
                masterKeyDeferred.getCompleted()
            } else {
                val appContext = context.applicationContext
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    SafeLog.w(TAG, "MasterKey requested synchronously on Main Thread before async prewarm completed!")
                    com.example.twopchat.AppLog.append(
                        appContext,
                        "[KeystoreProvider] WARNING: MasterKey requested synchronously on Main Thread before async prewarm completed!\n",
                    )
                }
                val key = runBlocking(Dispatchers.IO) {
                    buildMasterKey(appContext)
                }
                masterKeyDeferred.complete(key)
                key
            }
        }
    }

    /**
     * Non-blocking asynchronous initialization to be called during early startup
     * (e.g. in [android.app.Application.onCreate]).
     *
     * Prewarms:
     * 1. Android Keystore [MasterKey]
     * 2. [SecureStorage] local AES key and DB passphrase
     * 3. [P2PPreferences] encrypted preferences
     */
    fun initAsync(context: Context) {
        synchronized(lock) {
            if (isInitializing || masterKeyDeferred.isCompleted) return
            isInitializing = true
        }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Prewarm MasterKey
                val masterKey = withContext(Dispatchers.IO) {
                    buildMasterKey(appContext)
                }
                synchronized(lock) {
                    if (!masterKeyDeferred.isCompleted) {
                        masterKeyDeferred.complete(masterKey)
                    }
                }

                // 2. Prewarm SecureStorage
                SecureStorage.prewarm(appContext)

                // 3. Prewarm P2PPreferences
                P2PPreferences.warmUp(appContext)

                SafeLog.i(TAG, "Keystore and secure storage prewarm completed successfully")
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Failed during KeystoreProvider async initialization", e)
            }
        }
    }

    private fun buildMasterKey(appContext: Context): MasterKey {
        return MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
}
