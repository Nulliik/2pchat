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

    fun waitForSocksPort(socksPort: Int = DEFAULT_SOCKS_PORT, timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 400)
                    return true
                }
            } catch (_: Exception) {
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return false
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
                val libTorSo = File(nativeLibDir, "libtor.so")
                var torExecutable: File? = null

                if (libTorSo.exists()) {
                    val binFile = File(appTorDir, "tor_bin")
                    try {
                        if (!binFile.exists() || binFile.length() != libTorSo.length()) {
                            libTorSo.copyTo(binFile, overwrite = true)
                        }
                        binFile.setExecutable(true, false)
                        if (binFile.canExecute()) {
                            torExecutable = binFile
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not copy libtor.so to binFile: ${e.message}")
                    }

                    if (torExecutable == null && libTorSo.canExecute()) {
                        torExecutable = libTorSo
                    }
                }

                if (torExecutable != null) {
                    val processBuilder = ProcessBuilder(
                        torExecutable.absolutePath,
                        "-f", torrcFile.absolutePath
                    ).directory(appTorDir).redirectErrorStream(true)

                    val proc = processBuilder.start()
                    torProcess = proc
                    Log.i(TAG, "Started embedded Tor process from ${torExecutable.absolutePath}")

                    scope.launch {
                        try {
                            proc.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    Log.d(TAG, "[TOR_LOG] $line")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    Log.w(TAG, "Native libtor.so binary not found or executable; operating in socket fallback mode")
                }

                val portReady = waitForSocksPort(timeoutMs = 5000)
                if (portReady) {
                    Log.i(TAG, "SOCKS5 port 9050 is ready and accepting connections")
                    _isTorRunning.value = true
                    PythonBridge.applyProxyConfiguration()
                } else {
                    Log.w(TAG, "SOCKS5 port 9050 not responding; Tor daemon startup pending/failed. Falling back to direct connection.")
                    _isTorRunning.value = false
                    PythonBridge.applyProxyConfiguration()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tor daemon", e)
                _isTorRunning.value = false
                PythonBridge.applyProxyConfiguration()
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
