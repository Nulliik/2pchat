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
    private const val BOOTSTRAP_TIMEOUT_MS = 30000L

    private val _isTorRunning = MutableStateFlow(false)
    val isTorRunning: StateFlow<Boolean> = _isTorRunning.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow(0)
    val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

    private val _lastBootstrapFailureReason = MutableStateFlow<String?>(null)
    val lastBootstrapFailureReason: StateFlow<String?> = _lastBootstrapFailureReason.asStateFlow()

    private var torProcess: Process? = null
    private var torJob: Job? = null
    private var isLifecycleRegistered = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private val BOOTSTRAP_REGEX = Regex("""Bootstrapped\s+(\d+)%""")

    fun parseBootstrapProgress(logLine: String): Int? {
        val match = BOOTSTRAP_REGEX.find(logLine) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

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

    fun generateTorrcContent(
        dataDir: String,
        socksPort: Int = DEFAULT_SOCKS_PORT,
        controlPort: Int = DEFAULT_CONTROL_PORT,
        bridges: List<String> = emptyList(),
        obfs4PluginPath: String? = null,
        snowflakePluginPath: String? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("DataDirectory $dataDir")
        sb.appendLine("SocksPort 127.0.0.1:$socksPort")
        sb.appendLine("ControlPort 127.0.0.1:$controlPort")
        sb.appendLine("CookieAuthentication 1")
        sb.appendLine("SafeSocks 0")

        if (bridges.isNotEmpty()) {
            sb.appendLine("UseBridges 1")
            if (!obfs4PluginPath.isNullOrBlank()) {
                sb.appendLine("ClientTransportPlugin obfs4 exec $obfs4PluginPath")
            }
            if (!snowflakePluginPath.isNullOrBlank()) {
                sb.appendLine("ClientTransportPlugin snowflake exec $snowflakePluginPath")
            }
            bridges.forEach { bridgeLine ->
                if (bridgeLine.isNotBlank()) {
                    val formattedBridge = if (bridgeLine.trim().startsWith("Bridge ")) bridgeLine.trim() else "Bridge ${bridgeLine.trim()}"
                    sb.appendLine(formattedBridge)
                }
            }
        }

        return sb.toString().trimIndent()
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
    fun startTor(context: Context, bridges: List<String> = emptyList()) {
        if (_isTorRunning.value) {
            Log.d(TAG, "Tor is already running")
            return
        }

        initLifecycle(context)
        torJob?.cancel()
        _bootstrapProgress.value = 0
        _lastBootstrapFailureReason.value = null

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

                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val obfs4File = File(nativeLibDir, "libobfs4proxy.so")
                val snowflakeFile = File(nativeLibDir, "libsnowflake.so")

                val torrcFile = File(appTorDir, "torrc")
                val torrcContent = generateTorrcContent(
                    dataDir = appTorDir.absolutePath,
                    bridges = bridges,
                    obfs4PluginPath = if (obfs4File.exists()) obfs4File.absolutePath else null,
                    snowflakePluginPath = if (snowflakeFile.exists()) snowflakeFile.absolutePath else null
                )
                torrcFile.writeText(torrcContent)

                Log.i(TAG, "Initialized torrc at ${torrcFile.absolutePath} (Bridges active: ${bridges.isNotEmpty()})")

                // Directly execute libtor.so from nativeLibraryDir (pre-labeled with execute SELinux context by Android PM)
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
                                    parseBootstrapProgress(line)?.let { progress ->
                                        _bootstrapProgress.value = progress
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    Log.w(TAG, "Native libtor.so binary not found or executable; operating in socket fallback mode")
                }

                // 30-second bootstrap readiness check loop
                val startTime = System.currentTimeMillis()
                var fullyBootstrapped = false

                while (isActive && System.currentTimeMillis() - startTime < BOOTSTRAP_TIMEOUT_MS) {
                    if (waitForSocksPort(timeoutMs = 400) && _bootstrapProgress.value >= 100) {
                        fullyBootstrapped = true
                        break
                    }
                    delay(300)
                }

                if (!isActive) return@launch

                if (fullyBootstrapped) {
                    Log.i(TAG, "Tor fully bootstrapped (100%) and SOCKS5 port 9050 ready; enabling proxy in PythonBridge.")
                    _isTorRunning.value = true
                    PythonBridge.applyProxyConfiguration()
                } else {
                    val progress = _bootstrapProgress.value
                    if (progress < 50) {
                        Log.w(TAG, "Tor bootstrap stuck at $progress% (<50%) for 30s; direct Tor likely blocked by ISP. Disabling proxy and falling back to direct connection.")
                        _lastBootstrapFailureReason.value = "BOOTSTRAP_STUCK_UNDER_50_PERCENT"
                    } else {
                        Log.w(TAG, "Tor bootstrap timed out at $progress% (<100%) after 30s. Disabling proxy and falling back to direct connection.")
                        _lastBootstrapFailureReason.value = "BOOTSTRAP_INCOMPLETE_TIMEOUT"
                    }
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
            _bootstrapProgress.value = 0
        }
    }
}
