package com.example.twopchat.yggdrasil

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.yggdrasil.YggdrasilLivenessProbe.MeshResult
import com.example.twopchat.yggdrasil.YggdrasilLivenessProbe.MeshState
import com.example.twopchat.yggdrasil.YggdrasilLivenessProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException

/**
 * Unit tests for the Yggdrasil mesh liveness probe: peersJSON parsing,
 * probe-target extraction from stored peer endpoints, and verdict logic.
 */
class YggdrasilLivenessProbeTest {

    private val peersJsonMixed = """
        [
          {"Up":true,"URI":"tls://a:443","Cost":10,"Latency":50},
          {"Up":false,"URI":"tls://b:443","Cost":10,"Latency":0},
          {"Up":true,"URI":"tls://c:443","Cost":10,"Latency":80}
        ]
    """.trimIndent()

    @Test
    fun countUpPeersCountsOnlyUpTrueEntries() {
        assertEquals(2, YggdrasilLivenessProbe.countUpPeers(peersJsonMixed))
    }

    @Test
    fun countUpPeersReturnsZeroOnMalformedInput() {
        assertEquals(0, YggdrasilLivenessProbe.countUpPeers("not-json"))
        assertEquals(0, YggdrasilLivenessProbe.countUpPeers("null"))
        assertEquals(0, YggdrasilLivenessProbe.countUpPeers(""))
        assertEquals(0, YggdrasilLivenessProbe.countUpPeers(null))
        // Entries that are not objects must be skipped, not crash the count.
        assertEquals(1, YggdrasilLivenessProbe.countUpPeers("""["str",{"Up":true}]"""))
    }

    @Test
    fun countConfiguredPeersCountsArrayLength() {
        assertEquals(3, YggdrasilLivenessProbe.countConfiguredPeers(peersJsonMixed))
        assertEquals(0, YggdrasilLivenessProbe.countConfiguredPeers("garbage"))
        assertEquals(0, YggdrasilLivenessProbe.countConfiguredPeers(null))
    }

    @Test
    fun parseTargetAcceptsYggdrasilIpv6WithAndWithoutPort() {
        val t = YggdrasilLivenessProbe.parseTarget("[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]:50001", "alice")
        assertEquals("200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7", t?.host)
        assertEquals(50001, t?.port)

        val noPort = YggdrasilLivenessProbe.parseTarget("[3fe:abcd:1234:5678:9abc:def0:1234:5678]", "bob")
        assertEquals("3fe:abcd:1234:5678:9abc:def0:1234:5678", noPort?.host)
        assertEquals(50001, noPort?.port)

        val bare = YggdrasilLivenessProbe.parseTarget("200:abcd:1234:5678:9abc:def0:1234:5678:7777", "carol")
        assertEquals(7777, bare?.port)
    }

    @Test
    fun meshServiceEndpointIsAValidYggdrasilWebTarget() {
        // Regression guard: the well-known Web directory must stay a 200::/7
        // address (mesh-only) on port 80, or the primary liveness target would
        // silently probe the clearnet instead of the mesh.
        val t = YggdrasilLivenessProbe.parseTarget(
            YggdrasilLivenessProbe.MESH_SERVICE_ENDPOINT, "ygg-web-dir", YggdrasilLivenessProbe.MESH_SERVICE_PORT
        )
        assertEquals("21e:a51c:885b:7db0:166e:927:98cd:d186", t?.host)
        assertEquals(80, t?.port)
    }

    @Test
    fun parseTargetRejectsNonYggdrasilAndInvalidEndpoints() {
        assertNull(YggdrasilLivenessProbe.parseTarget("192.168.1.10:50001", "lan"))
        assertNull(YggdrasilLivenessProbe.parseTarget("[fe80::1]:50001", "link-local"))
        assertNull(YggdrasilLivenessProbe.parseTarget("[400::1]:50001", "out-of-range"))
        assertNull(YggdrasilLivenessProbe.parseTarget("abc123.onion:50001", "onion"))
        assertNull(YggdrasilLivenessProbe.parseTarget("hello", "garbage"))
        assertNull(YggdrasilLivenessProbe.parseTarget("[200::1]:0", "bad-port"))
        assertNull(YggdrasilLivenessProbe.parseTarget("", "empty"))
    }

    @Test
    fun selectTargetsExcludesOwnAddressAndCapsCountDeterministically() {
        val endpoints = mapOf(
            "zeta" to "[200:aaaa::1]:50001",
            "alpha" to "[200:bbbb::1]:50001",
            "self" to "200:cccc::1",
            "bad" to "10.0.0.5:50001",
        )
        val own = "200:cccc::1"
        val targets = YggdrasilLivenessProbe.selectTargets(endpoints, own, max = 2)
        assertEquals(2, targets.size)
        assertEquals("alpha", targets[0].label)
        assertEquals("zeta", targets[1].label)
        assertFalse(targets.any { it.label == "self" })
    }

    @Test
    fun evaluateVerdictsFromMeshPlane() {
        assertEquals(
            Verdict.LIVE,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.LIVE, "t", 50L, "connected"), 3, 6
            ).verdict
        )
        assertEquals(
            Verdict.LIVE,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.LIVE_PORT_CLOSED, "t", null, "refused"), 3, 6
            ).verdict
        )
        assertEquals(
            Verdict.PARTIAL,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.UNVERIFIED, null, null, "no endpoint"), 3, 6
            ).verdict
        )
        assertEquals(
            Verdict.DEAD,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.UNVERIFIED, null, null, "no endpoint"), 0, 6
            ).verdict
        )
        assertEquals(
            Verdict.DEAD,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.DEAD, "t", null, "timeout"), 3, 6
            ).verdict
        )
    }

    @Test
    fun summaryLineCarriesVerdictCountsAndDiagnosis() {
        val live = YggdrasilLivenessProbe.evaluate(
            MeshResult(MeshState.LIVE, "[200:aaaa::1]:50001", 243L, "connected"), 3, 6,
        )
        val liveLine = live.summaryLine()
        assertTrue(liveLine.contains("[LIVENESS] LIVE"))
        assertTrue(liveLine.contains("mesh=OK"))
        assertTrue(liveLine.contains("rtt=243ms"))
        assertTrue(liveLine.contains("up=3/6"))
        // The mesh is a standalone 200::/7 overlay: the verdict line must
        // never mention the clearnet, which the mesh cannot reach.
        assertFalse(liveLine.contains("clearnet"))

        val dead = YggdrasilLivenessProbe.evaluate(
            MeshResult(MeshState.DEAD, "[200:aaaa::1]:50001", null, "timeout"), 0, 6,
        )
        val deadLine = dead.summaryLine()
        assertTrue(deadLine.contains("[LIVENESS] DEAD"))
        assertTrue(deadLine.contains("mesh=DEAD(timeout)"))
        assertFalse(deadLine.contains("clearnet"))
    }

    @Test
    fun parseVerdictRoundTripsSummaryLines() {
        val lines = listOf(
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.LIVE, "[200:aaaa::1]:80", 50L, "connected, http=200"), 3, 6
            ).summaryLine() to YggdrasilLivenessProbe.Verdict.LIVE,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.UNVERIFIED, null, null, "no endpoint"), 3, 6
            ).summaryLine() to YggdrasilLivenessProbe.Verdict.PARTIAL,
            YggdrasilLivenessProbe.evaluate(
                MeshResult(MeshState.DEAD, "[200:aaaa::1]:80", null, "timeout"), 0, 6
            ).summaryLine() to YggdrasilLivenessProbe.Verdict.DEAD,
        )
        for ((line, expected) in lines) {
            assertEquals(expected, YggdrasilLivenessProbe.parseVerdict(line))
        }
        assertNull(YggdrasilLivenessProbe.parseVerdict(null))
        assertNull(YggdrasilLivenessProbe.parseVerdict(""))
        assertNull(YggdrasilLivenessProbe.parseVerdict("unrelated log line"))
    }

    @Test
    fun localSocksFailuresNeverCountAsMeshPortClosed() {
        assertFalse(YggdrasilLivenessProbe.isRemotePortClosed(P2PPreferences.YggdrasilMode.PROXY, ConnectException("Connection refused")))
        assertFalse(YggdrasilLivenessProbe.isRemotePortClosed(P2PPreferences.YggdrasilMode.VPN, SocketException("Network is unreachable")))
        assertTrue(YggdrasilLivenessProbe.isRemotePortClosed(P2PPreferences.YggdrasilMode.VPN, ConnectException("Connection refused")))
    }

    @Test
    fun connectedTargetSemanticsAfterMeshHandshake() {
        val web = YggdrasilLivenessProbe.ProbeTarget("21e:a51c:885b:7db0:166e:927:98cd:d186", 80, "ygg-web-dir")
        val peer = YggdrasilLivenessProbe.ProbeTarget("200:aaaa::1", 50001, "peer")
        // Any HTTP status came back over the mesh -> bytes flowed -> LIVE,
        // even a redirect/error code: the service's health is not the mesh's.
        assertEquals(MeshState.LIVE, YggdrasilLivenessProbe.connectedTargetResult(web, "[web]:80", 5, YggdrasilLivenessProbe.HttpResult(301, "http=301")).state)
        // TCP completed through the mesh but zero bytes: unproven, not dead.
        assertEquals(MeshState.UNVERIFIED, YggdrasilLivenessProbe.connectedTargetResult(web, "[web]:80", 5, YggdrasilLivenessProbe.HttpResult(null, "http=timeout")).state)
        // A peer relay answering the handshake needs no HTTP at all.
        assertEquals(MeshState.LIVE, YggdrasilLivenessProbe.connectedTargetResult(peer, "[peer]:50001", 5, YggdrasilLivenessProbe.HttpResult(null, "http=timeout")).state)
    }

    @Test
    fun deadVerdictNeedsTwoConsecutiveSamplesWhileLinksAreUp() {
        val meshDead = MeshResult(MeshState.DEAD, "[200:aaaa::1]:50001", null, "timeout")
        fun deadReport(up: Int) = YggdrasilLivenessProbe.evaluate(meshDead, up, 6)
        val liveReport = YggdrasilLivenessProbe.evaluate(
            MeshResult(MeshState.LIVE, "t", 10L, "connected"), 3, 6
        )
        // Deterministic start: a live sample resets the streak.
        assertEquals(Verdict.LIVE, YggdrasilLivenessProbe.withDeadHysteresis(liveReport).verdict)
        // First failed sample with Up links: downgraded to PARTIAL.
        assertEquals(Verdict.PARTIAL, YggdrasilLivenessProbe.withDeadHysteresis(deadReport(3)).verdict)
        // Second consecutive failure confirms DEAD.
        assertEquals(Verdict.DEAD, YggdrasilLivenessProbe.withDeadHysteresis(deadReport(3)).verdict)
        // Reset, then a no-links failure is DEAD immediately (control plane
        // itself reports nothing up — nothing to be hysteresis about).
        assertEquals(Verdict.LIVE, YggdrasilLivenessProbe.withDeadHysteresis(liveReport).verdict)
        assertEquals(Verdict.DEAD, YggdrasilLivenessProbe.withDeadHysteresis(deadReport(0)).verdict)
    }
}
