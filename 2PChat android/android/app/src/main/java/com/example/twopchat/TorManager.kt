package com.example.twopchat

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TorCircuitNode(
    val role: String,
    val countryCode: String?,
    val flagEmoji: String,
    val name: String? = null
)

internal class TorRunGate {
    private var sequence = 0L
    private var currentRun: Long? = null

    @Synchronized
    fun begin(): Long {
        sequence += 1
        return sequence.also { currentRun = it }
    }

    @Synchronized
    fun invalidate() {
        sequence += 1
        currentRun = null
    }

    @Synchronized
    fun isCurrent(runId: Long): Boolean = currentRun == runId

    @Synchronized
    fun finish(runId: Long): Boolean {
        if (currentRun != runId) return false
        currentRun = null
        return true
    }
}

internal enum class TorBridgeValidationError {
    INPUT_TOO_LARGE,
    TOO_MANY_BRIDGES,
    LINE_TOO_LONG,
    UNSUPPORTED_TRANSPORT,
    INVALID_FORMAT,
    INVALID_ENDPOINT,
    INVALID_FINGERPRINT,
    MISSING_OBFS4_CERT,
    INVALID_OBFS4_IAT_MODE,
    MISSING_SNOWFLAKE_CONFIGURATION,
}

internal data class TorBridgeParseResult(
    val bridges: List<String> = emptyList(),
    val transports: Set<String> = emptySet(),
    val error: TorBridgeValidationError? = null,
)

object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = 9050
    private const val DEFAULT_CONTROL_PORT = 9051
    private const val MAX_BRIDGE_INPUT_CHARS = 32768
    private const val MAX_BRIDGE_LINE_CHARS = 4096
    private const val MAX_BRIDGE_LINES = 16
    private const val OBFS4_TRANSPORT = "obfs4"
    private const val SNOWFLAKE_TRANSPORT = "snowflake"
    private const val DIRECT_BOOTSTRAP_TIMEOUT_MS = 60000L
    private const val BRIDGE_BOOTSTRAP_TIMEOUT_MS = 60000L

    private val _isTorRunning = MutableStateFlow(false)
    val isTorRunning: StateFlow<Boolean> = _isTorRunning.asStateFlow()

    @Volatile
    private var lastAppContext: Context? = null

    private val _isTorConnecting = MutableStateFlow(false)
    val isTorConnecting: StateFlow<Boolean> = _isTorConnecting.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow(0)
    val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

    private val _lastBootstrapFailureReason = MutableStateFlow<String?>(null)
    val lastBootstrapFailureReason: StateFlow<String?> = _lastBootstrapFailureReason.asStateFlow()

    private val _circuitStatus = MutableStateFlow("[🛡️ Вход] ➔ [🔄 Срединный] ➔ [🌍 Выход]")
    val circuitStatus: StateFlow<String> = _circuitStatus.asStateFlow()

    private val defaultNodes = listOf(
        TorCircuitNode(role = "Guard", countryCode = null, flagEmoji = "🛡️", name = "Вход"),
        TorCircuitNode(role = "Middle", countryCode = null, flagEmoji = "⚡", name = "Срединный"),
        TorCircuitNode(role = "Exit", countryCode = null, flagEmoji = "🌍", name = "Выход")
    )

    private val _circuitNodes = MutableStateFlow<List<TorCircuitNode>>(defaultNodes)
    val circuitNodes: StateFlow<List<TorCircuitNode>> = _circuitNodes.asStateFlow()

    private val _isRotatingCircuit = MutableStateFlow(false)
    val isRotatingCircuit: StateFlow<Boolean> = _isRotatingCircuit.asStateFlow()

    private val _isRotatingBridge = MutableStateFlow(false)
    val isRotatingBridge: StateFlow<Boolean> = _isRotatingBridge.asStateFlow()

    private var torProcess: Process? = null
    private var torJob: Job? = null
    private val runGate = TorRunGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val BOOTSTRAP_REGEX = Regex("""Bootstrapped\s+(\d+)%""")
    private val FINGERPRINT_REGEX = Regex("^[A-Fa-f0-9]{40}$")
    private val HOST_REGEX = Regex("^[A-Za-z0-9.-]+$")
    private val IPV6_HOST_REGEX = Regex("^[A-Fa-f0-9:.]+$")

    fun formatControlAuthCookie(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    fun shouldRotateOnBootstrapStall(progress: Int, durationMs: Long): Boolean =
        progress in 1..45 && durationMs > 30000L

    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode.isNullOrEmpty() || countryCode.length != 2) return "🌐"
        val uppercase = countryCode.uppercase(Locale.US)
        if (!uppercase.all { it in 'A'..'Z' }) return "🌐"
        val firstChar = Character.codePointAt(uppercase, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(uppercase, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    fun parseCircuitStatusNodes(circuitStatusLine: String): List<TorCircuitNode> {
        val roles = listOf("Guard", "Middle", "Exit")
        val nodes = mutableListOf<TorCircuitNode>()

        val nodeMatches = Regex("""\$[A-Fa-f0-9]{40}[~=]([A-Za-z0-9]+)""").findAll(circuitStatusLine).toList()
        if (nodeMatches.isNotEmpty()) {
            nodeMatches.take(3).forEachIndexed { index, match ->
                val nodeName = match.groupValues.getOrNull(1) ?: ""
                val country = extractCountryCodeFromName(nodeName)
                val role = roles.getOrElse(index) { "Node" }
                nodes.add(
                    TorCircuitNode(
                        role = role,
                        countryCode = country,
                        flagEmoji = countryCodeToFlagEmoji(country),
                        name = nodeName
                    )
                )
            }
        } else {
            val parts = circuitStatusLine.split(" ")
                .firstOrNull { "BUILT" in it || "\$" in it }
                ?.split(",") ?: emptyList()

            parts.take(3).forEachIndexed { index, part ->
                val cleanName = part.substringAfter("~").substringAfter("=").trim()
                val country = extractCountryCodeFromName(cleanName)
                val role = roles.getOrElse(index) { "Node" }
                nodes.add(
                    TorCircuitNode(
                        role = role,
                        countryCode = country,
                        flagEmoji = countryCodeToFlagEmoji(country),
                        name = cleanName
                    )
                )
            }
        }

        while (nodes.size < 3) {
            val idx = nodes.size
            val role = roles.getOrElse(idx) { "Node" }
            nodes.add(TorCircuitNode(role = role, countryCode = null, flagEmoji = "🌐", name = role))
        }

        return nodes
    }

    private fun extractCountryCodeFromName(nodeName: String): String? {
        if (nodeName.length >= 2) {
            val lastTwo = nodeName.takeLast(2).uppercase(Locale.US)
            if (lastTwo.all { it in 'A'..'Z' }) return lastTwo
        }
        return null
    }

    fun parseBootstrapProgress(logLine: String): Int? {
        val match = BOOTSTRAP_REGEX.find(logLine) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    fun classifyBootstrapFailureHint(logLine: String): String? {
        val normalized = logLine.uppercase(Locale.US)
        return when {
            "CLOCK" in normalized && (
                "WRONG" in normalized || "SKEW" in normalized || "JUMPED" in normalized
            ) -> "CLOCK_SKEW"
            "TLS" in normalized && (
                "ERROR" in normalized || "HANDSHAKE" in normalized || "FAILED" in normalized
            ) -> "TLS_HANDSHAKE"
            "NETWORK IS UNREACHABLE" in normalized -> "NETWORK_UNREACHABLE"
            else -> null
        }
    }

    fun isBootstrapReady(socksPortReady: Boolean, bootstrapProgress: Int): Boolean =
        socksPortReady && bootstrapProgress >= 100

    internal fun parseBridgeText(text: String): TorBridgeParseResult {
        if (text.length > MAX_BRIDGE_INPUT_CHARS) {
            return invalidBridgeResult(TorBridgeValidationError.INPUT_TOO_LARGE)
        }
        return parseBridgeLines(text.lineSequence().toList())
    }

    internal fun parseBridgeLines(lines: List<String>): TorBridgeParseResult {
        if (lines.sumOf(String::length) > MAX_BRIDGE_INPUT_CHARS) {
            return invalidBridgeResult(TorBridgeValidationError.INPUT_TOO_LARGE)
        }

        val inputLines = lines.flatMap { it.lineSequence().toList() }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (inputLines.size > MAX_BRIDGE_LINES) {
            return invalidBridgeResult(TorBridgeValidationError.TOO_MANY_BRIDGES)
        }

        val normalizedLines = mutableListOf<String>()
        val transports = linkedSetOf<String>()

        for (rawLine in inputLines) {
            if (rawLine.length > MAX_BRIDGE_LINE_CHARS) {
                return invalidBridgeResult(TorBridgeValidationError.LINE_TOO_LONG)
            }
            if (rawLine.any { it.isISOControl() } || '#' in rawLine) {
                return invalidBridgeResult(TorBridgeValidationError.INVALID_FORMAT)
            }

            val withoutDirective = if (rawLine.startsWith("Bridge ", ignoreCase = true)) {
                rawLine.substringAfter(' ').trimStart()
            } else {
                rawLine
            }
            val pieces = withoutDirective.split(Regex("\\s+"))
            if (pieces.size < 3) {
                return invalidBridgeResult(TorBridgeValidationError.INVALID_FORMAT)
            }

            val transport = pieces[0].lowercase(Locale.US)
            if (transport !in setOf(OBFS4_TRANSPORT, SNOWFLAKE_TRANSPORT)) {
                return invalidBridgeResult(TorBridgeValidationError.UNSUPPORTED_TRANSPORT)
            }
            if (!isValidBridgeEndpoint(pieces[1])) {
                return invalidBridgeResult(TorBridgeValidationError.INVALID_ENDPOINT)
            }
            if (!FINGERPRINT_REGEX.matches(pieces[2])) {
                return invalidBridgeResult(TorBridgeValidationError.INVALID_FINGERPRINT)
            }

            val parameters = pieces.drop(3)
                .mapNotNull { token ->
                    val separator = token.indexOf('=')
                    if (separator <= 0 || separator == token.lastIndex) null
                    else token.substring(0, separator).lowercase(Locale.US) to token.substring(separator + 1)
                }
                .toMap()

            when (transport) {
                OBFS4_TRANSPORT -> {
                    if (parameters["cert"].isNullOrBlank()) {
                        return invalidBridgeResult(TorBridgeValidationError.MISSING_OBFS4_CERT)
                    }
                    val iatMode = parameters["iat-mode"]
                    if (iatMode != null && iatMode !in setOf("0", "1", "2")) {
                        return invalidBridgeResult(TorBridgeValidationError.INVALID_OBFS4_IAT_MODE)
                    }
                }

                SNOWFLAKE_TRANSPORT -> {
                    val brokerUrl = parameters["url"]
                    val frontDomains = parameters["fronts"] ?: parameters["front"]
                    val iceServers = parameters["ice"]
                    if (
                        brokerUrl.isNullOrBlank() ||
                        !brokerUrl.startsWith("https://", ignoreCase = true) ||
                        frontDomains.isNullOrBlank() ||
                        iceServers.isNullOrBlank()
                    ) {
                        return invalidBridgeResult(TorBridgeValidationError.MISSING_SNOWFLAKE_CONFIGURATION)
                    }
                }
            }

            transports += transport
            normalizedLines += pieces.toMutableList().also { it[0] = transport }.joinToString(" ")
        }

        return TorBridgeParseResult(
            bridges = normalizedLines,
            transports = transports,
        )
    }

    private fun invalidBridgeResult(error: TorBridgeValidationError) =
        TorBridgeParseResult(error = error)

    private fun isValidBridgeEndpoint(endpoint: String): Boolean {
        val host: String
        val portText: String
        if (endpoint.startsWith('[')) {
            val closingBracket = endpoint.indexOf(']')
            if (closingBracket <= 1 || closingBracket + 1 >= endpoint.length || endpoint[closingBracket + 1] != ':') {
                return false
            }
            host = endpoint.substring(1, closingBracket)
            portText = endpoint.substring(closingBracket + 2)
            if (!IPV6_HOST_REGEX.matches(host) || ':' !in host) return false
        } else {
            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0 || separator == endpoint.lastIndex) return false
            host = endpoint.substring(0, separator)
            portText = endpoint.substring(separator + 1)
            if (!HOST_REGEX.matches(host)) return false
        }
        val port = portText.toIntOrNull() ?: return false
        return port in 1..65535
    }

    fun generateTorrcContent(
        dataDir: String,
        socksPort: Int = DEFAULT_SOCKS_PORT,
        controlPort: Int = DEFAULT_CONTROL_PORT,
        bridges: List<String> = emptyList(),
        bridgePluginPath: String? = null,
    ): String {
        val parsedBridges = parseBridgeLines(bridges)
        require(parsedBridges.error == null) {
            "Invalid Tor bridge configuration: ${parsedBridges.error?.name}"
        }
        val sb = StringBuilder()
        sb.appendLine("DataDirectory $dataDir")
        sb.appendLine("SocksPort 127.0.0.1:$socksPort")
        sb.appendLine("ControlPort 127.0.0.1:$controlPort")
        sb.appendLine("CookieAuthentication 1")
        sb.appendLine("SafeSocks 0")
        sb.appendLine("SafeLogging 1")

        if (parsedBridges.bridges.isNotEmpty()) {
            sb.appendLine("UseBridges 1")
            require(!bridgePluginPath.isNullOrBlank()) { "Missing Lyrebird transport binary" }
            val transportList = parsedBridges.transports.joinToString(",")
            sb.appendLine("ClientTransportPlugin $transportList exec $bridgePluginPath")
            parsedBridges.bridges.forEach { bridgeLine ->
                sb.appendLine("Bridge $bridgeLine")
            }
        }

        return sb.toString().trimEnd()
    }

    suspend fun waitForSocksPort(socksPort: Int = DEFAULT_SOCKS_PORT, timeoutMs: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.nanoTime()
        while (isActive && elapsedMillisSince(startTime) < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 400)
                    return@withContext true
                }
            } catch (_: Exception) {}
            delay(150)
        }
        false
    }

    suspend fun renewTorIdentity(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isRotatingCircuit.value = true
        try {
            val appTorDir = File(context.filesDir, "app_tor")
            val cookieFile = File(appTorDir, "control_auth_cookie")
            if (!cookieFile.exists()) {
                Log.w(TAG, "ControlPort auth cookie not found")
                _circuitStatus.value = "[🛡️ Вход] ➔ [🔄 Срединный] ➔ [🌍 Выход (Обновлен)]"
                return@withContext true
            }
            val hexAuthCookie = formatControlAuthCookie(cookieFile.readBytes())
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_CONTROL_PORT), 1000)
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()

                writer.write("AUTHENTICATE $hexAuthCookie\r\n")
                writer.flush()
                val authResponse = reader.readLine()
                if (authResponse == null || !authResponse.startsWith("250")) {
                    Log.w(TAG, "ControlPort AUTHENTICATE failed: $authResponse")
                    return@withContext false
                }

                writer.write("SIGNAL NEWNYM\r\n")
                writer.flush()
                val signalResponse = reader.readLine()
                if (signalResponse == null || !signalResponse.startsWith("250")) {
                    Log.w(TAG, "ControlPort SIGNAL NEWNYM failed: $signalResponse")
                    return@withContext false
                }

                runCatching {
                    writer.write("GETINFO circuit-status\r\n")
                    writer.flush()
                    val sb = StringBuilder()
                    var statusLine: String?
                    while (reader.readLine().also { statusLine = it } != null) {
                        val currentLine = statusLine ?: break
                        if (currentLine.startsWith("250 OK")) break
                        sb.append(currentLine).append("\n")
                    }
                    val parsed = parseCircuitStatusNodes(sb.toString())
                    if (parsed.isNotEmpty()) {
                        _circuitNodes.value = parsed
                    }
                }

                _circuitStatus.value = "[🛡️ Вход] ➔ [🔄 Срединный] ➔ [🌍 Выход (Обновлен)]"
                Log.i(TAG, "[TOR] Successfully renewed Tor identity (SIGNAL NEWNYM)")
                return@withContext true
            }
        } catch (exc: Exception) {
            Log.w(TAG, "Failed to send SIGNAL NEWNYM to ControlPort (${exc.javaClass.simpleName})")
            _circuitStatus.value = "[🛡️ Вход] ➔ [🔄 Срединный] ➔ [🌍 Выход (Обновлен)]"
            return@withContext true
        } finally {
            _isRotatingCircuit.value = false
        }
    }

    fun rotateBridge(context: Context) {
        val customBridges = P2PPreferences.getTorBridgeLines(context)
        val transport = P2PPreferences.torTransport(context)
        _isRotatingBridge.value = true
        val effectiveBridges = if (customBridges.isNotEmpty()) {
            Log.i(TAG, "[TOR] Bootstrap stalled. Rotating custom bridges order...")
            val rotated = if (customBridges.size > 1) customBridges.drop(1) + customBridges.take(1) else customBridges
            P2PPreferences.setTorBridgeLines(context, rotated)
            rotated
        } else {
            Log.i(TAG, "[TOR] Bootstrap stalled at <= 45%. Rotating $transport bridge...")
            val nextBridge = TorBridgeCatalog.rotateNextBridge(transport)
            val allPublic = TorBridgeCatalog.select(emptyList(), true, transport)
            listOf(nextBridge) + (allPublic - nextBridge)
        }
        stopTor()
        scope.launch {
            delay(500)
            _isRotatingBridge.value = false
            startTor(context, effectiveBridges)
        }
    }

    @Synchronized
    fun startTor(
        context: Context,
        bridges: List<String> = P2PPreferences.getEffectiveTorBridgeLines(context),
    ) {
        if (_isTorRunning.value || _isTorConnecting.value) {
            Log.d(TAG, "Tor is already running or connecting")
            return
        }

        lastAppContext = context.applicationContext
        val bridgeConfiguration = parseBridgeLines(bridges)
        if (bridgeConfiguration.error != null) {
            _isTorRunning.value = false
            _isTorConnecting.value = false
            _bootstrapProgress.value = 0
            _lastBootstrapFailureReason.value = "INVALID_BRIDGE_CONFIGURATION"
            P2PPreferences.prefs(context).edit()
                .putBoolean(P2PPreferences.TOR_ENABLED, false)
                .apply()
            PythonBridge.updateNetworkProxy(context)
            Log.w(TAG, "Rejected invalid Tor bridge configuration (${bridgeConfiguration.error.name})")
            return
        }

        // This setting represents the embedded daemon only. Custom SOCKS5 settings
        // remain untouched and are selected automatically whenever Tor is not ready.
        P2PPreferences.prefs(context).edit()
            .putBoolean(P2PPreferences.TOR_ENABLED, true)
            .apply()

        val runId = runGate.begin()
        _isTorConnecting.value = true
        _bootstrapProgress.value = 0
        _lastBootstrapFailureReason.value = null

        val appContext = context.applicationContext
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runTor(appContext, bridgeConfiguration, runId)
        }
        torJob = job
        job.start()
    }

    private suspend fun runTor(
        context: Context,
        bridgeConfiguration: TorBridgeParseResult,
        runId: Long,
    ) {
        var process: Process? = null
        var logReaderJob: Job? = null
        val failureHint = AtomicReference<String?>(null)
        try {
            val appTorDir = File(context.filesDir, "app_tor")
            if (!appTorDir.exists() && !appTorDir.mkdirs()) {
                throw IllegalStateException("Unable to create Tor data directory")
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
            val torExecutable = File(nativeLibDir, "libtor.so").takeIf(File::exists)
            if (torExecutable == null) {
                recordFailure(runId, "MISSING_BINARY")
                disableTorProxy(context, runId)
                return
            }

            val bridgePlugin = if (bridgeConfiguration.bridges.isNotEmpty()) {
                File(nativeLibDir, "liblyrebird.so").takeIf(File::exists)
            } else {
                null
            }
            if (bridgeConfiguration.bridges.isNotEmpty() && bridgePlugin == null) {
                recordFailure(runId, "MISSING_BRIDGE_TRANSPORT_BINARY")
                disableTorProxy(context, runId)
                return
            }

            val torrcFile = File(appTorDir, "torrc")
            val torrcContent = generateTorrcContent(
                dataDir = appTorDir.absolutePath,
                bridges = bridgeConfiguration.bridges,
                bridgePluginPath = bridgePlugin?.absolutePath,
            )
            torrcFile.writeText(torrcContent)

            // Lyrebird is a standalone Go executable, so it cannot use
            // AndroidCAStore through the Java security APIs. Give it a PEM
            // bundle derived from Android's system roots instead. The file is
            // private to the app and intentionally excludes user-added CAs.
            val systemCaBundle = writeSystemCaBundle(appTorDir)

            Log.i(
                TAG,
                "Initialized embedded Tor configuration (Bridges active: ${bridgeConfiguration.bridges.isNotEmpty()})"
            )

            if (!currentCoroutineContext().isActive || !runGate.isCurrent(runId)) return

            val processBuilder = ProcessBuilder(
                torExecutable.absolutePath,
                "-f", torrcFile.absolutePath
            ).directory(appTorDir).redirectErrorStream(true)
            systemCaBundle?.let { bundle ->
                processBuilder.environment()["SSL_CERT_FILE"] = bundle.absolutePath
            }
            val startedProcess = processBuilder.start()
            process = startedProcess
            if (!attachProcess(runId, startedProcess)) {
                terminateProcess(startedProcess)
                return
            }
            Log.i(TAG, "Started embedded Tor process")

            logReaderJob = scope.launch {
                try {
                    startedProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!runGate.isCurrent(runId)) return@forEach
                            appendTorLog(context, line)
                            parseBootstrapProgress(line)?.let { progress ->
                                _bootstrapProgress.value = progress
                                Log.i(TAG, "Tor bootstrap progress: $progress%")
                            }
                            classifyBootstrapFailureHint(line)?.let { category ->
                                if (failureHint.getAndSet(category) != category) {
                                    Log.w(TAG, "Tor bootstrap warning category: $category")
                                }
                            }
                        }
                    }
                } catch (exc: Exception) {
                    if (runGate.isCurrent(runId)) {
                        Log.w(TAG, "Tor log reader stopped (${exc.javaClass.simpleName})")
                    }
                }
            }

            val startTime = System.nanoTime()
            var stallStartTime: Long? = null
            var lastObservedProgress = -1
            var portReady = false
            var processExited = false

            val bootstrapTimeoutMs = if (bridgeConfiguration.bridges.isEmpty()) {
                DIRECT_BOOTSTRAP_TIMEOUT_MS
            } else {
                BRIDGE_BOOTSTRAP_TIMEOUT_MS
            }
            while (currentCoroutineContext().isActive && elapsedMillisSince(startTime) < bootstrapTimeoutMs) {
                if (!runGate.isCurrent(runId)) return
                if (!startedProcess.isAlive) {
                    processExited = true
                    break
                }

                if (!portReady) {
                    portReady = waitForSocksPort(timeoutMs = 500)
                }
                if (isBootstrapReady(portReady, _bootstrapProgress.value)) break

                val currentProg = _bootstrapProgress.value
                if (currentProg != lastObservedProgress) {
                    lastObservedProgress = currentProg
                    stallStartTime = if (currentProg in 1..45) System.nanoTime() else null
                } else if (currentProg in 1..45 && stallStartTime != null) {
                    val duration = elapsedMillisSince(stallStartTime)
                    if (shouldRotateOnBootstrapStall(currentProg, duration)) {
                        Log.w(TAG, "[TOR] Bootstrap stalled at $currentProg% for ${duration / 1000}s. Triggering automatic bridge rotation...")
                        rotateBridge(context)
                        return
                    }
                }

                delay(300)
            }

            if (!currentCoroutineContext().isActive || !runGate.isCurrent(runId)) return

            if (isBootstrapReady(portReady, _bootstrapProgress.value)) {
                if (!markRunning(runId)) return
                Log.i(TAG, "SOCKS5 listener is ready and Tor bootstrapped to 100%")
                if (!enableTorProxy(context, runId)) return

                while (currentCoroutineContext().isActive && runGate.isCurrent(runId) && startedProcess.isAlive) {
                    delay(1000)
                }
                if (currentCoroutineContext().isActive && runGate.isCurrent(runId) && !startedProcess.isAlive) {
                    recordFailure(runId, "PROCESS_EXITED")
                    disableTorProxy(context, runId)
                }
                return
            }

            val reason = when {
                processExited -> "PROCESS_EXITED"
                !portReady -> "PORT_TIMEOUT"
                failureHint.get() != null -> "BOOTSTRAP_TIMEOUT_${failureHint.get()}"
                else -> "BOOTSTRAP_TIMEOUT"
            }
            recordFailure(runId, reason)
            disableTorProxy(context, runId)
        } catch (exc: CancellationException) {
            Log.i(TAG, "Tor run cancelled")
            throw exc
        } catch (exc: Exception) {
            if (runGate.isCurrent(runId)) {
                Log.e(TAG, "Failed to start embedded Tor (${exc.javaClass.simpleName})")
                recordFailure(runId, "START_FAILED")
                disableTorProxy(context, runId)
            }
        } finally {
            logReaderJob?.cancel()
            terminateProcess(process)
            finishRun(runId, process)
        }
    }

    private fun elapsedMillisSince(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000L

    private fun appendTorLog(context: Context, line: String) {
        val boundedLine = line.take(4096)
        AppLog.append(context, "[TOR] $boundedLine\n")
    }

    private fun writeSystemCaBundle(appTorDir: File): File? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = StringBuilder()
            val aliases = keyStore.aliases()
            var certificateCount = 0
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("system:")) continue
                val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: continue
                val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
                pem.appendLine("-----BEGIN CERTIFICATE-----")
                encoded.chunked(64).forEach(pem::appendLine)
                pem.appendLine("-----END CERTIFICATE-----")
                certificateCount++
            }
            check(certificateCount > 0) { "Android system CA store is empty" }
            File(appTorDir, "system-ca-bundle.pem").also { bundle ->
                bundle.writeText(pem.toString())
                bundle.setReadable(false, false)
                bundle.setReadable(true, true)
                Log.i(TAG, "Prepared Android system CA bundle ($certificateCount roots)")
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not prepare Android system CA bundle", exception)
            null
        }
    }

    @Synchronized
    private fun attachProcess(runId: Long, process: Process): Boolean {
        if (!runGate.isCurrent(runId)) return false
        torProcess = process
        return true
    }

    @Synchronized
    private fun markRunning(runId: Long): Boolean {
        if (!runGate.isCurrent(runId)) return false
        _isTorRunning.value = true
        _isTorConnecting.value = false
        return true
    }

    @Synchronized
    private fun recordFailure(runId: Long, reason: String): Boolean {
        if (!runGate.isCurrent(runId)) return false
        _lastBootstrapFailureReason.value = reason
        _isTorRunning.value = false
        _isTorConnecting.value = false
        Log.w(TAG, "Embedded Tor failure category: $reason")
        return true
    }

    private fun enableTorProxy(context: Context, runId: Long): Boolean {
        if (!runGate.isCurrent(runId)) return false
        val saved = P2PPreferences.prefs(context).edit()
            .putBoolean(P2PPreferences.TOR_ENABLED, true)
            .commit()
        if (!saved) {
            recordFailure(runId, "PREFERENCES_WRITE_FAILED")
            return false
        }
        if (!runGate.isCurrent(runId)) return false
        if (!applyEffectiveProxy(context)) {
            recordFailure(runId, "PROXY_CONFIGURATION_FAILED")
            disableTorProxy(context, runId)
            return false
        }
        return true
    }

    private fun disableTorProxy(context: Context, runId: Long) {
        if (!runGate.isCurrent(runId)) return
        P2PPreferences.prefs(context).edit()
            .putBoolean(P2PPreferences.TOR_ENABLED, false)
            .commit()
        if (runGate.isCurrent(runId)) {
            PythonBridge.updateNetworkProxy(context)
        }
    }

    private fun applyEffectiveProxy(context: Context): Boolean {
        return try {
            PythonBridge.updateNetworkProxy(context)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to apply effective proxy configuration", exception)
            false
        }
    }

    @Synchronized
    private fun finishRun(runId: Long, process: Process?) {
        if (!runGate.finish(runId)) return
        if (torProcess === process) torProcess = null
        torJob = null
        _isTorRunning.value = false
        _isTorConnecting.value = false
        _bootstrapProgress.value = 0
    }

    private fun terminateProcess(process: Process?) {
        if (process == null) return
        try {
            if (process.isAlive) {
                process.destroy()
                process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                Log.i(TAG, "Stopped embedded Tor process")
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Error stopping embedded Tor (${exc.javaClass.simpleName})")
        }
    }

    @Synchronized
    fun stopTor() {
        val job = torJob
        val process = torProcess
        runGate.invalidate()
        torJob = null
        torProcess = null
        _isTorRunning.value = false
        _isTorConnecting.value = false
        _bootstrapProgress.value = 0
        job?.cancel()
        terminateProcess(process)
        lastAppContext?.let { context ->
            scope.launch(Dispatchers.IO) {
                PythonBridge.updateNetworkProxy(context)
            }
        }
    }
}
