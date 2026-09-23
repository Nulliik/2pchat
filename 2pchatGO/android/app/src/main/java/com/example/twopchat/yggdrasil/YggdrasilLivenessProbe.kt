package com.example.twopchat.yggdrasil

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Runtime liveness test for the Yggdrasil mesh.
 *
 * The native mesh reports `state` and a `peers` count, but `peers` is the number
 * of configured/tracked links, not live ones, and neither proves the data plane
 * can actually carry traffic. This probe verifies that on two separately
 * labeled planes:
 *
 * 1. MESH — loads a well-known Yggdrasil service through the active mesh path
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
 * 2. CLEARNET — DNS resolve + a small HTTP GET of the device's own Wi-Fi /
 *    mobile network. Both modes are deliberately split-tunnelled (VPN routes
 *    only 200::/7; the SOCKS stack rejects non-mesh destinations), so clearnet
 *    traffic never touches the mesh. This plane exists to distinguish "the
 *    phone has internet, the mesh is dead" from "the phone has no network at
 *    all" — the exact case a `peers=6` log line alone cannot tell.
 *
 * Verdicts:
 *  - LIVE     — the mesh data plane carried a TCP connect (and, for the web
 *               directory, an HTTP status over the mesh).
 *  - PARTIAL  — the mesh has Up links but the data plane is UNVERIFIED
 *               (defensive: reachable only if the service and all peer
 *               targets are absent, which normally cannot happen).
 *  - DEAD     — the mesh data plane is unreachable or has no Up links.
 */
object YggdrasilLivenessProbe {

    const val RUNTIME_PREFS_FILE = "yggdrasil_runtime_ephemeral"
    const val PREF_LIVENESS = "yggdrasil_liveness"

    const val CLEARNET_DOMAIN = "example.com"
    const val CLEARNET_URL = "http://example.com/"

    /** Direct-connect budget for VPN mode: 200::/7 is routed through the TUN. */
    const val VPN_PROBE_TIMEOUT_MS = 6_000
    /** SOCKS budget must cover the user-space stack's 12 s mesh TCP handshake deadline. */
    const val SOCKS_PROBE_TIMEOUT_MS = 15_000

    /** Read budget for the follow-up HTTP status fetch over the connected socket. */
    const val HTTP_CHECK_TIMEOUT_MS = 5_000

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

    data class ClearnetResult(
        val ok: Boolean,
        val dnsMs: Long?,
        val httpMs: Long?,
        val detail: String,
    )

    data class ProbeTarget(val host: String, val port: Int, val label: String)

    data class Report(
        val verdict: Verdict,
        val mesh: MeshResult,
        val upPeers: Int,
        val configuredPeers: Int,
        val clearnet: ClearnetResult,
    ) {
        /** One greppable line for the app log and the diagnostics dialog. */
        fun summaryLine(): String {
            val meshPart = when (mesh.state) {
                MeshState.LIVE ->
                    "mesh=OK target=${mesh.target.orEmpty()}${mesh.rttMs?.let { " rtt=${it}ms" } ?: ""}"
                MeshState.LIVE_PORT_CLOSED -> "mesh=ALIVE(rst) target=${mesh.target.orEmpty()}"
                MeshState.DEAD -> "mesh=DEAD(${mesh.detail})${mesh.target?.let { " target=$it" } ?: ""}"
                MeshState.UNVERIFIED -> "mesh=UNVERIFIED(${mesh.detail})"
            }
            val clearnetPart = if (clearnet.ok) {
                "clearnet=OK dns=${clearnet.dnsMs}ms http=${clearnet.httpMs}ms"
            } else {
                "clearnet=DEAD(${clearnet.detail})"
            }
            val line = "[LIVENESS] $verdict $meshPart up=$upPeers/$configuredPeers $clearnetPart"
            return if (verdict == Verdict.DEAD && clearnet.ok) {
                "$line => phone network is fine; the Yggdrasil mesh has no active data path"
            } else {
                line
            }
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

    /** Combines plane results into the final verdict. */
    fun evaluate(
        mesh: MeshResult,
        upPeers: Int,
        configuredPeers: Int,
        clearnet: ClearnetResult,
    ): Report {
        val verdict = when {
            mesh.state == MeshState.LIVE || mesh.state == MeshState.LIVE_PORT_CLOSED -> Verdict.LIVE
            mesh.state == MeshState.UNVERIFIED && upPeers > 0 -> Verdict.PARTIAL
            else -> Verdict.DEAD
        }
        return Report(verdict, mesh, upPeers, configuredPeers, clearnet)
    }

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
            val clearnet = probeClearnet()
            evaluate(mesh, upPeers, configuredPeers, clearnet)
        }
        persist(context, report)
        return report
    }

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
            addAll(selectTargets(readStoredYggEndpoints(context), ownAddress, max = 1))
        }
        var lastDead = MeshResult(MeshState.DEAD, null, null, "no probe attempted")
        for (target in targets) {
            val result = probeMeshTcp(target, mode, socksHost, socksPort)
            if (result.state == MeshState.LIVE || result.state == MeshState.LIVE_PORT_CLOSED) {
                return result
            }
            lastDead = result
        }
        return lastDead
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
            // peer and the peer answered — the data plane is alive, only this
            // peer's relay port is closed.
            MeshResult(MeshState.LIVE_PORT_CLOSED, label, null, e.message ?: "refused")
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
            return MeshResult(MeshState.LIVE, label, rttMs, "connected$http")
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
            return MeshResult(MeshState.LIVE, label, rttMs, "connected via socks$http")
        }
    }

    /**
     * Minimal HTTP GET over an already-connected socket: the plain TUN socket
     * in VPN mode, the SOCKS data pipe in PROXY mode. Proves the mesh carried
     * real page bytes, not just a TCP handshake. Returns "" on no data or
     * " http=200" / " http=<status>" on a status line. Never throws.
     */
    private fun httpCheck(socket: Socket, host: String): String {
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
            if (statusLine.isNullOrEmpty()) ", http=timeout"
            else if (statusLine.contains(" 200")) ", http=200"
            else ", http=${statusLine.take(32)}"
        } catch (e: Exception) {
            ", http=error"
        }
    }

    /**
     * Clearnet plane: DNS resolve + tiny HTTP GET of the device's own
     * network, always with Proxy.NO_PROXY so it never routes through Tor,
     * the Yggdrasil SOCKS stack, or any other proxy.
     */
    private fun probeClearnet(): ClearnetResult {
        return try {
            val dnsStart = System.currentTimeMillis()
            InetAddress.getByName(CLEARNET_DOMAIN)
            val dnsMs = System.currentTimeMillis() - dnsStart
            val connection = (URL(CLEARNET_URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
            }
            val httpStart = System.currentTimeMillis()
            connection.responseCode
            connection.inputStream.use { it.read(ByteArray(256)) }
            val httpMs = System.currentTimeMillis() - httpStart
            connection.disconnect()
            ClearnetResult(ok = true, dnsMs = dnsMs, httpMs = httpMs, detail = "ok")
        } catch (e: Exception) {
            ClearnetResult(ok = false, dnsMs = null, httpMs = null, detail = e.javaClass.simpleName)
        }
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
