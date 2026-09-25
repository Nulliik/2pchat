package com.example.twopchat.yggdrasil

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Runtime liveness test for the Yggdrasil mesh.
 *
 * The native mesh reports `state` and a `peers` count, but `peers` is the number
 * of configured/tracked links, not live ones, and neither proves the data plane
 * can actually carry traffic. The mesh is a standalone overlay: 200::/7
 * traffic cannot reach the clearnet and vice versa, so the verdict is based
 * on the mesh plane alone. The phone's own Wi-Fi / mobile network state is
 * irrelevant to whether the mesh is alive and is deliberately not probed.
 *
 * MESH — loads a well-known Yggdrasil service through the active mesh path
 *    and verifies the page bytes come back. Primary target is the Yggdrasil
 *    Web directory (http://[21e:a51c:885b:7db0:166e:927:98cd:d186]/ — a
 *    200::/7 address, so it can only be reached through the mesh); fallback
 *    targets are authenticated peers' own endpoints (`peer_ygg_*` prefs,
 *    relay port 50001). VPN mode routes 200::/7 through the TUN; PROXY mode
 *    goes through the local SOCKS5 user-space stack (127.0.0.1:9053). A
 *    completed TCP connect is definitive proof the mesh carries data; a fast
 *    RST means the SYN reached the peer, so the data plane is alive even
 *    though that peer's relay port is closed.
 *
 * Verdicts:
 *  - LIVE     — the mesh data plane carried a TCP connect (and, for the web
 *               directory, an HTTP status over the mesh).
 *  - PARTIAL  — the control plane has live links but the independently probed
 *               service did not answer. A service timeout does not prove that
 *               independent peer-to-peer routes are unavailable.
 *  - DEAD     — the mesh has no Up links, so it cannot have a usable route.
 */
object YggdrasilLivenessProbe {

    const val RUNTIME_PREFS_FILE = "yggdrasil_runtime_ephemeral"
    const val PREF_LIVENESS = "yggdrasil_liveness"

    /**
     * A DEAD verdict may request an engine peer-redial, but probes must not
     * turn a persistent outage into a tight reconnect loop. One minute also
     * matches the regular probe cadence.
     */
    const val PEER_RETRY_COOLDOWN_MS = 60_000L

    /** Direct-connect budget for VPN mode: 200::/7 is routed through the TUN. */
    const val VPN_PROBE_TIMEOUT_MS = 6_000
    /** SOCKS budget must cover the user-space stack's 12 s mesh TCP handshake deadline. */
    const val SOCKS_PROBE_TIMEOUT_MS = 15_000

    /**
     * Read budget for the follow-up HTTP status fetch over the connected socket.
     * Mesh paths under load can be slower than the browser's patience; 5 s
     * produced false `http=timeout` verdicts while Chrome loaded fine.
     */
    const val HTTP_CHECK_TIMEOUT_MS = 10_000

    /** Relay port the peer app listens on for P2P messages (P2PPreferences.DEFAULT_LISTENER_PORT). */
    const val PEER_RELAY_PORT = 50001

    /**
     * Well-known Yggdrasil Web directory (yggdrasil-network.github.io/services.html).
     * First hextet 0x21e is inside 200::/7, so the page is only reachable
     * through the mesh — this is the "load a test page" liveness check.
     */
    const val MESH_SERVICE_ENDPOINT = "21e:a51c:885b:7db0:166e:927:98cd:d186"
    const val MESH_SERVICE_PORT = 80

    enum class Verdict { LIVE, PARTIAL, DEAD }

    enum class MeshState { LIVE, LIVE_PORT_CLOSED, DEAD, UNVERIFIED }

    data class MeshResult(
        val state: MeshState,
        val target: String?,
        val rttMs: Long?,
        val detail: String,
    )

    data class ProbeTarget(val host: String, val port: Int, val label: String)

    data class HttpResult(val statusCode: Int?, val detail: String)

    data class Report(
        val verdict: Verdict,
        val mesh: MeshResult,
        val upPeers: Int,
        val configuredPeers: Int,
    ) {
        /** One greppable line for the app log and the diagnostics dialog. */
        fun summaryLine(): String {
            val meshPart = when (mesh.state) {
                MeshState.LIVE ->
                    "mesh=OK target=${mesh.target.orEmpty()}${mesh.rttMs?.let { " rtt=${it}ms" } ?: ""}"
                MeshState.LIVE_PORT_CLOSED -> "mesh=ALIVE(rst) target=${mesh.target.orEmpty()}"
                MeshState.DEAD -> {
                    val state = if (verdict == Verdict.DEAD) "DEAD" else "DEGRADED"
                    "mesh=$state(${mesh.detail})${mesh.target?.let { " target=$it" } ?: ""}"
                }
                MeshState.UNVERIFIED -> "mesh=UNVERIFIED(${mesh.detail})"
            }
            return "[LIVENESS] $verdict $meshPart up=$upPeers/$configuredPeers"
        }
    }

    // ------------------------------------------------------------------
    // Pure, JVM-testable logic
    // ------------------------------------------------------------------

    /**
     * Counts `Up:true` entries in the native `peersJSON` (live transport-plane
     * links). The `peers=` log value is the array length; this is the live one.
     * Malformed input yields 0, never an exception.
     */
    fun countUpPeers(peersJson: String?): Int {
        if (peersJson.isNullOrBlank() || peersJson == "null") return 0
        return try {
            val arr = JSONArray(peersJson)
            var up = 0
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optBoolean("Up", false) == true) up++
            }
            up
        } catch (_: Exception) {
            0
        }
    }

    /** Number of configured/tracked peers in the native `peersJSON`. */
    fun countConfiguredPeers(peersJson: String?): Int {
        if (peersJson.isNullOrBlank() || peersJson == "null") return 0
        return try {
            JSONArray(peersJson).length()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Parses a stored peer endpoint (`[200:...]:50001` or `200:...:50001`)
     * into a probe target. Returns null unless the host is a syntactically
     * valid Yggdrasil IPv6 address in 200::/7 (first hextet 0x200..0x3ff,
     * matching [P2PPreferences.isYggdrasilEndpoint]).
     */
    fun parseTarget(endpoint: String, label: String, defaultPort: Int = PEER_RELAY_PORT): ProbeTarget? {
        val value = endpoint.trim()
        if (value.isEmpty()) return null
        val host: String
        val port: Int
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 1) return null
            host = value.substring(1, close)
            val rest = value.substring(close + 1)
            port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
        } else if (value.count { it == ':' } > 1) {
            val idx = value.lastIndexOf(':')
            if (idx < 1) return null
            val portPart = value.substring(idx + 1)
            if (portPart.all { it.isDigit() }) {
                host = value.substring(0, idx)
                port = portPart.toIntOrNull() ?: return null
            } else {
                host = value
                port = defaultPort
            }
        } else {
            return null
        }
        if (host.isEmpty() || port !in 1..65535) return null
        val firstHextet = host.substringBefore(':').substringBefore('%').toIntOrNull(16) ?: return null
        if (firstHextet !in 0x0200..0x03ff) return null
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
        if (addr !is Inet6Address) return null
        return ProbeTarget(host, port, label)
    }

    /**
     * Deterministic selection of at most [max] probe targets from stored peer
     * endpoints. Own address is excluded; order is by label for stable retries.
     */
    fun selectTargets(endpoints: Map<String, String>, ownAddress: String?, max: Int = 2): List<ProbeTarget> {
        val own = ownAddress?.trim().orEmpty()
        return endpoints
            .mapNotNull { (label, endpoint) -> parseTarget(endpoint, label) }
            .filter { it.host != own }
            .sortedBy { it.label }
            .take(max)
    }

    /**
     * Parses the verdict back out of a persisted `[LIVENESS] ...` summary
     * line (the main screen reads the latest line, not the probe object).
     * Returns null for blank or unrecognized input.
     */
    fun parseVerdict(summaryLine: String?): Verdict? {
        val line = summaryLine?.trim().orEmpty()
        if (line.isEmpty()) return null
        return when {
            line.contains("] LIVE ") -> Verdict.LIVE
            line.contains("] PARTIAL ") -> Verdict.PARTIAL
            line.contains("] DEAD ") -> Verdict.DEAD
            else -> null
        }
    }

    /**
     * Combines the independent service probe and link counts into the final
     * verdict. A timeout to one service is insufficient evidence to call the
     * mesh dead while authenticated public peers are still Up.
     */
    fun evaluate(
        mesh: MeshResult,
        upPeers: Int,
        configuredPeers: Int,
    ): Report {
        val verdict = when {
            mesh.state == MeshState.LIVE || mesh.state == MeshState.LIVE_PORT_CLOSED -> Verdict.LIVE
            upPeers > 0 -> Verdict.PARTIAL
            else -> Verdict.DEAD
        }
        return Report(verdict, mesh, upPeers, configuredPeers)
    }

    /** True only when a confirmed mesh outage is eligible for a peer redial. */
    internal fun shouldRetryPeers(
        report: Report,
        nowMs: Long,
        lastRetryMs: Long,
    ): Boolean =
        report.verdict == Verdict.DEAD &&
            (lastRetryMs <= 0L || nowMs < lastRetryMs || nowMs - lastRetryMs >= PEER_RETRY_COOLDOWN_MS)

    // ------------------------------------------------------------------
    // Device-side execution (called from both mesh services' updater loops)
    // ------------------------------------------------------------------

    /**
     * Runs the full liveness check off the lifecycle dispatcher, persists the
     * latest summary for the diagnostics dialog, and returns the report.
     * Never throws: a probe failure must not kill the updater loop.
     */
    suspend fun run(
        context: Context,
        mode: P2PPreferences.YggdrasilMode,
        peersJson: String?,
        ownAddress: String?,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 9053,
    ): Report {
        val report = withContext(Dispatchers.IO) {
            val upPeers = countUpPeers(peersJson)
            val configuredPeers = countConfiguredPeers(peersJson)
            val mesh = runMeshProbe(context, mode, ownAddress, socksHost, socksPort)
            val base = evaluate(mesh, upPeers, configuredPeers)
            withDeadHysteresis(base)
        }
        persist(context, report)
        return report
    }

    /** Kept as the run-path normalization hook for compatibility. */
    internal fun withDeadHysteresis(base: Report): Report = base

    private fun runMeshProbe(
        context: Context,
        mode: P2PPreferences.YggdrasilMode,
        ownAddress: String?,
        socksHost: String,
        socksPort: Int,
    ): MeshResult {
        val serviceTarget = ProbeTarget(MESH_SERVICE_ENDPOINT, MESH_SERVICE_PORT, "ygg-web-dir")
        val targets = buildList {
            add(serviceTarget)
            // Two authenticated peers: a single peer sitting in doze must not
            // decide the verdict when another reachable one exists.
            addAll(selectTargets(readStoredYggEndpoints(context), ownAddress, max = 2))
        }
        var lastFailure: MeshResult? = null
        for (target in targets) {
            val result = probeMeshTcp(target, mode, socksHost, socksPort)
            if (result.state == MeshState.LIVE || result.state == MeshState.LIVE_PORT_CLOSED) {
                return result
            }
            // Prefer an ambiguous-but-connected failure (UNVERIFIED) over a
            // hard timeout when reporting the final diagnosis.
            val previous = lastFailure
            if (previous == null || (previous.state == MeshState.DEAD && result.state != MeshState.DEAD)) {
                lastFailure = result
            }
        }
        return lastFailure ?: MeshResult(MeshState.DEAD, null, null, "no probe attempted")
    }

    private fun probeMeshTcp(
        target: ProbeTarget,
        mode: P2PPreferences.YggdrasilMode,
        socksHost: String,
        socksPort: Int,
    ): MeshResult {
        val label = "[${target.host}]:${target.port}"
        return try {
            if (mode == P2PPreferences.YggdrasilMode.VPN) {
                probeDirect(target, label)
            } else {
                probeSocks(target, socksHost, socksPort, label)
            }
        } catch (e: SocketTimeoutException) {
            MeshResult(MeshState.DEAD, label, null, "timeout")
        } catch (e: java.net.SocketException) {
            // Fast RST / connection refused: the mesh delivered our SYN to the
            // peer and the peer answered. A Proxy-mode SocketException can
            // instead be the local SOCKS listener being unavailable.
            if (isRemotePortClosed(mode, e)) {
                MeshResult(MeshState.LIVE_PORT_CLOSED, label, null, e.message ?: "refused")
            } else {
                MeshResult(MeshState.DEAD, label, null, e.javaClass.simpleName)
            }
        } catch (e: Exception) {
            MeshResult(MeshState.DEAD, label, null, e.javaClass.simpleName)
        }
    }

    /**
     * VPN mode: 200::/7 is routed through the TUN, so a plain connect suffices.
     * After the connect, the HTTP check runs over the same socket.
     */
    private fun probeDirect(target: ProbeTarget, label: String): MeshResult {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(target.host, target.port), VPN_PROBE_TIMEOUT_MS)
            socket.soTimeout = HTTP_CHECK_TIMEOUT_MS
            val rttMs = (System.nanoTime() - start) / 1_000_000L
            val http = httpCheck(socket, target.host)
            return connectedTargetResult(target, label, rttMs, http)
        }
    }

    /**
     * PROXY mode: minimal SOCKS5 CONNECT to the mesh user-space stack
     * (greeting 05 01 00 -> 05 00; request 05 01 00 04 + 16-byte IPv6 + port).
     * Reply 05 00 means the stack completed a mesh TCP handshake with the
     * peer; 05 04 means the handshake failed within its 12 s deadline.
     */
    private fun probeSocks(
        target: ProbeTarget,
        socksHost: String,
        socksPort: Int,
        label: String,
    ): MeshResult {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.soTimeout = SOCKS_PROBE_TIMEOUT_MS
            socket.connect(InetSocketAddress(socksHost, socksPort), SOCKS_PROBE_TIMEOUT_MS)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            if (input.read() != 5 || input.read() != 0) {
                return MeshResult(MeshState.DEAD, label, null, "socks greeting refused")
            }

            val request = ByteArray(22)
            request[0] = 0x05 // SOCKS5
            request[1] = 0x01 // CONNECT
            request[2] = 0x00 // no auth
            request[3] = 0x04 // IPv6
            System.arraycopy(InetAddress.getByName(target.host).address, 0, request, 4, 16)
            request[20] = (target.port shr 8).toByte()
            request[21] = target.port.toByte()
            output.write(request)
            output.flush()

            val replyVer = input.read()
            val replyCode = input.read()
            if (replyVer != 5) {
                return MeshResult(MeshState.DEAD, label, null, "bad socks reply version=$replyVer")
            }
            if (replyCode != 0) {
                return MeshResult(MeshState.DEAD, label, null, "socks code=$replyCode")
            }
            val rttMs = (System.nanoTime() - start) / 1_000_000L
            socket.soTimeout = HTTP_CHECK_TIMEOUT_MS
            val http = httpCheck(socket, target.host)
            return connectedTargetResult(target, label, rttMs, http, " via socks")
        }
    }

    /**
     * Minimal HTTP GET over an already-connected socket: the plain TUN socket
     * in VPN mode, the SOCKS data pipe in PROXY mode. Proves the mesh carried
     * real page bytes, not just a TCP handshake. Never throws.
     */
    private fun httpCheck(socket: Socket, host: String): HttpResult {
        return try {
            val out = socket.getOutputStream()
            out.write(
                ("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: 2PChat-Liveness/1.0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()
            val input = socket.getInputStream()
            val sb = StringBuilder()
            val buf = ByteArray(512)
            while (true) {
                val n = input.read(buf) // bounded by socket.soTimeout
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                if (sb.length > 2048 || sb.contains("\r\n\r\n")) break
            }
            val statusLine = sb.lineSequence().firstOrNull()
            val status = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
            HttpResult(status, if (status == null) "http=timeout" else "http=$status")
        } catch (e: Exception) {
            HttpResult(null, "http=error")
        }
    }

    internal fun isRemotePortClosed(mode: P2PPreferences.YggdrasilMode, error: Exception): Boolean =
        mode == P2PPreferences.YggdrasilMode.VPN && error is ConnectException

    internal fun connectedTargetResult(target: ProbeTarget, label: String, rttMs: Long, http: HttpResult, transportDetail: String = ""): MeshResult {
        // A completed TCP handshake over the mesh already proves the data
        // plane. The web directory returning any HTTP status (even a
        // redirect) proves bytes flowed too. Only zero bytes leaves the
        // target's own health unproven — that is unverified, not dead: the
        // service may be down while the mesh itself is fine.
        if (target.label == "ygg-web-dir" && http.statusCode == null) {
            return MeshResult(MeshState.UNVERIFIED, label, rttMs, http.detail)
        }
        return MeshResult(MeshState.LIVE, label, rttMs, "connected$transportDetail, ${http.detail}")
    }

    private fun readStoredYggEndpoints(context: Context): Map<String, String> {
        return try {
            P2PPreferences.prefs(context).all
                .filterKeys { it.startsWith("peer_ygg_") }
                .mapValues { (_, value) -> value as? String ?: "" }
                .filterValues { it.isNotBlank() }
                .mapKeys { (key, _) -> key.removePrefix("peer_ygg_") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(context: Context, report: Report) {
        try {
            context
                .getSharedPreferences(RUNTIME_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LIVENESS, report.summaryLine())
                .apply()
        } catch (_: Exception) {
            // Diagnostics persistence is best-effort.
        }
    }
}
