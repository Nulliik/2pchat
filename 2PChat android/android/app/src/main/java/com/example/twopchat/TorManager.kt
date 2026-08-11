package com.example.twopchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = 9050
    private const val DEFAULT_CONTROL_PORT = 9051

    private val _isTorRunning = MutableStateFlow(false)
    val isTorRunning: StateFlow<Boolean> = _isTorRunning.asStateFlow()

    private var torProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun generateTorrcContent(dataDir: String, socksPort: Int = DEFAULT_SOCKS_PORT, controlPort: Int = DEFAULT_CONTROL_PORT): String {
        return """
            DataDirectory $dataDir
            SocksPort 127.0.0.1:$socksPort
            ControlPort 127.0.0.1:$controlPort
            CookieAuthentication 1
            AvoidDisjointLooseHops 1
            SafeSocks 0
        """.trimIndent()
    }

    @Synchronized
    fun startTor(context: Context) {
        if (_isTorRunning.value) {
            Log.d(TAG, "Tor is already running")
            return
        }

        scope.launch {
            try {
                val appTorDir = File(context.filesDir, "app_tor")
                if (!appTorDir.exists()) {
                    appTorDir.mkdirs()
                }

                val torrcFile = File(appTorDir, "torrc")
                val torrcContent = generateTorrcContent(appTorDir.absolutePath)
                torrcFile.writeText(torrcContent)

                Log.i(TAG, "Initialized torrc at ${torrcFile.absolutePath}")

                // Attempt to launch embedded Tor binary if available on system/apk
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val torExecutable = File(nativeLibDir, "libtor.so")

                if (torExecutable.exists() && torExecutable.canExecute()) {
                    val processBuilder = ProcessBuilder(
                        torExecutable.absolutePath,
                        "-f", torrcFile.absolutePath
                    ).directory(appTorDir)

                    torProcess = processBuilder.start()
                    Log.i(TAG, "Started embedded Tor process from ${torExecutable.absolutePath}")
                } else {
                    Log.w(TAG, "Native libtor.so not executable directly; operating in socket fallback mode")
                }

                _isTorRunning.value = true
                PythonBridge.applyProxyConfiguration()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tor daemon", e)
                _isTorRunning.value = false
            }
        }
    }

    @Synchronized
    fun stopTor() {
        if (!_isTorRunning.value && torProcess == null) return
        try {
            torProcess?.let { proc ->
                if (proc.isAlive) {
                    proc.destroy()
                    Log.i(TAG, "Stopped embedded Tor process")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Tor process", e)
        } finally {
            torProcess = null
            _isTorRunning.value = false
        }
    }
}
