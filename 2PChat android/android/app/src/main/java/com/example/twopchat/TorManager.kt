package com.example.twopchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = 9050
    private const val DEFAULT_CONTROL_PORT = 9051

    private val _isTorRunning = MutableStateFlow(false)
    val isTorRunning: StateFlow<Boolean> = _isTorRunning.asStateFlow()

    private var torProcess: Process? = null
    private var torJob: Job? = null
    private var isLifecycleRegistered = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initLifecycle(context: Context) {
        if (isLifecycleRegistered) return
        try {
            Handler(Looper.getMainLooper()).post {
                if (isLifecycleRegistered) return@post
                try {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStop(owner: LifecycleOwner) {
                            Log.i(TAG, "Application entering background/stopped; terminating embedded Tor process")
                            stopTor()
                        }
                    })
                    isLifecycleRegistered = true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register ProcessLifecycleOwner observer: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not post to main looper for lifecycle observer: ${e.message}")
        }
    }

    fun generateTorrcContent(dataDir: String, socksPort: Int = DEFAULT_SOCKS_PORT, controlPort: Int = DEFAULT_CONTROL_PORT): String {
        return """
            DataDirectory $dataDir
            SocksPort 127.0.0.1:$socksPort
            ControlPort 127.0.0.1:$controlPort
            CookieAuthentication 1
            SafeSocks 0
        """.trimIndent()
    }

    suspend fun waitForSocksPort(socksPort: Int = DEFAULT_SOCKS_PORT, timeoutMs: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (isActive && System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 400)
                    return@withContext true
                }
            } catch (_: Exception) {
                try {
                    delay(150)
                } catch (_: Exception) {
                    break
                }
            }
        }
        false
    }

    @Synchronized
    fun startTor(context: Context) {
        if (_isTorRunning.value) {
            Log.d(TAG, "Tor is already running")
            return
        }

        initLifecycle(context)
        torJob?.cancel()
        torJob = scope.launch {
            try {
                val appTorDir = File(context.filesDir, "app_tor")
                if (!appTorDir.exists()) {
                    appTorDir.mkdirs()
                }

                // Cleanup legacy tor_bin files from filesDir and codeCacheDir if present
                val legacyBinFiles = listOf(
                    File(appTorDir, "tor_bin"),
                    File(context.codeCacheDir, "tor_bin")
                )
                legacyBinFiles.forEach { file ->
                    if (file.exists()) {
                        runCatching { file.delete() }
                    }
                }

                val torrcFile = File(appTorDir, "torrc")
                val torrcContent = generateTorrcContent(appTorDir.absolutePath)
                torrcFile.writeText(torrcContent)

                Log.i(TAG, "Initialized torrc at ${torrcFile.absolutePath}")

                // Directly execute libtor.so from nativeLibraryDir (pre-labeled with execute SELinux context by Android PM)
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val libTorSo = File(nativeLibDir, "libtor.so")
                val torExecutable: File? = if (libTorSo.exists()) libTorSo else null

                if (!isActive) return@launch

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

                val portReady = waitForSocksPort(timeoutMs = 15000)
                if (!isActive) return@launch

                if (portReady) {
                    Log.i(TAG, "SOCKS5 port 9050 is ready and accepting connections")
                    _isTorRunning.value = true
                    PythonBridge.applyProxyConfiguration()
                } else {
                    Log.w(TAG, "SOCKS5 port 9050 not responding within 15s; Tor daemon startup failed. Cleaning up.")
                    stopTor()
                    _isTorRunning.value = false
                    PythonBridge.applyProxyConfiguration()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Tor startup cancelled")
                stopTor()
                _isTorRunning.value = false
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "Failed to start Tor daemon", e)
                stopTor()
                _isTorRunning.value = false
                PythonBridge.applyProxyConfiguration()
            }
        }
    }

    @Synchronized
    fun stopTor() {
        torJob?.cancel()
        torJob = null
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
