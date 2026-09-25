package com.example.twopchat.yggdrasil

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Integration test: two [YggdrasilUserSpaceStack] instances over an
 * in-memory mesh. Establishes a real virtual TCP stream (SOCKS5 -> shim A
 * -> mesh -> shim B -> local listener), cuts the mesh route after the
 * handshake while data is in flight, holds the cut past the pre-fix
 * retransmit-exhaustion close threshold (12 retries), restores the route,
 * and verifies that the pending segment is delivered and the stream stays
 * usable. Regression test for "session goes offline ~60 s after the first
 * message": before the fix, retransmit-exhaustion closed the stream
 * (~65 s) and the ACK budget tore down the Go session (~62 s).
 *
 * The RTO is scaled down via the test-only constructor seams
 * (retransmitBaseRtoMs/retransmitMaxRtoMs) so 12 exhaustion retries take
 * seconds instead of ~73 s; the test asserts the segment was actually
 * retransmitted 12+ times (the old close threshold) before recovery.
 */
class YggdrasilShimGapDeliveryTest {

    /** In-memory mesh: one node with a cuttable link to [peer]. */
    class FakeMesh(@Volatile var address: String?) : MeshTransport {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val open = AtomicBoolean(true)
        @Volatile var peer: FakeMesh? = null
        @Volatile var cut = false
        @Volatile var packetsSent = 0
        @Volatile var packetsReceived = 0


        fun linkTo(other: FakeMesh) {
            peer = other
            other.peer = this
        }

        fun cutRoute() {
            cut = true
        }

        fun restoreRoute() {
            cut = false
        }

        override fun addressString(): String? = address

        override fun sendPacket(data: ByteArray, length: Long) {
            packetsSent++
            if (!cut) {
                peer?.inbound?.offer(data.copyOf(length.toInt()))
            }
        }

        override fun receivePacket(buffer: ByteArray): Int {
            if (!open.get()) return 0
            val pkt = inbound.poll(25, TimeUnit.MILLISECONDS) ?: return 0
            packetsReceived++
            val n = minOf(pkt.size, buffer.size)
            System.arraycopy(pkt, 0, buffer, 0, n)
            return n
        }

        override fun close() {
            open.set(false)
        }
    }

    private var client: Socket? = null
    private var stackA: YggdrasilUserSpaceStack? = null
    private var stackB: YggdrasilUserSpaceStack? = null
    private var listener: ServerSocket? = null
    private var meshA: FakeMesh? = null
    private var meshB: FakeMesh? = null
    private var reader: Thread? = null

    @After
    fun tearDown() {
        runCatching { client?.close() }
        stackA?.stop()
        stackB?.stop()
        meshA?.close()
        meshB?.close()
        runCatching { listener?.close() }
        runCatching { reader?.join(2_000) }
        client = null
        stackA = null
        stackB = null
        listener = null
        meshA = null
        meshB = null
        reader = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun receivedSnapshot(received: ByteArrayOutputStream): String =
        synchronized(received) { received.toString(Charsets.US_ASCII) }

    private fun assertEventuallyEquals(
        expected: String,
        received: ByteArrayOutputStream,
        timeoutMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (receivedSnapshot(received) == expected) return
            Thread.sleep(50)
        }
        fail("Expected to receive \"$expected\" within ${timeoutMs}ms, got \"${receivedSnapshot(received)}\"")
    }

    @Test
    fun pendingMessageSurvivesRouteGapAndStreamStaysUsable() {
        // 1. B-side listener on an ephemeral port (stands in for the local
        //    Go core that the shim would normally proxy to).
        val listener = ServerSocket(0)
        this.listener = listener
        val received = ByteArrayOutputStream()
        val reader = Thread {
            try {
                val conn = listener.accept()
                conn.use {
                    val input = conn.getInputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(received) {
                            received.write(buf, 0, n)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()
        this.reader = reader

        // 2. Two stacks over an in-memory mesh.
        val meshA = FakeMesh("200:1000:aaaa::1")
        val meshB = FakeMesh("200:1000:bbbb::2")
        meshA.linkTo(meshB)
        this.meshA = meshA
        this.meshB = meshB

        val socksPort = freePort()
        val stackA = YggdrasilUserSpaceStack(
            mesh = meshA,
            socksPort = socksPort,
            localTargetPort = 1,
            retransmitBaseRtoMs = 50,
            retransmitMaxRtoMs = 400
        )
        val stackB = YggdrasilUserSpaceStack(
            mesh = meshB,
            socksPort = freePort(),
            localTargetPort = listener.localPort,
            // Host-specific: on this Windows box a socket bound to 127.0.0.2
            // cannot connect to 127.0.0.1 (verified with a standalone probe),
            // so the inbound marker address is injected as loopback here.
            // The production default (127.0.0.2 marker for the Go core's
            // transport classification) is unchanged.
            inboundSourceAddress = "127.0.0.1",
            retransmitBaseRtoMs = 50,
            retransmitMaxRtoMs = 400
        )
        this.stackA = stackA
        this.stackB = stackB
        stackA.start()
        stackB.start()

        // 3. SOCKS5 CONNECT through stack A to stack B's listener.
        val client = Socket("127.0.0.1", socksPort)
        this.client = client
        val out = client.getOutputStream()
        val input = client.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05.toByte(), input.read().toByte())
        assertEquals(0x00.toByte(), input.read().toByte())

        val target = InetAddress.getByName("200:1000:bbbb::2").address
        val req = ByteArrayOutputStream()
        req.write(0x05)
        req.write(0x01)
        req.write(0x00)
        req.write(0x04) // IPv6
        req.write(target)
        req.write(listener.localPort shr 8)
        req.write(listener.localPort and 0xFF)
        out.write(req.toByteArray())
        val reply = ByteArray(10)
        var replyLen = 0
        while (replyLen < reply.size) {
            val r = input.read(reply, replyLen, reply.size - replyLen)
            if (r < 0) break
            replyLen += r
        }
        if (replyLen != 10 || reply[0].toInt() != 0x05) {
            fail(
                "SOCKS connect failed: len=$replyLen reply=${reply.contentToString()} " +
                    "sentA=${meshA.packetsSent} sentB=${meshB.packetsSent} " +
                    "rxA=${meshA.packetsReceived} rxB=${meshB.packetsReceived}"
            )
        }
        assertEquals("SOCKS connect failed: code=${reply[1].toInt()}", 0x00.toByte(), reply[1])

        // 4. Baseline: data flows through both shims.
        out.write("hello".toByteArray())
        out.flush()
        assertEventuallyEquals("hello", received, 10_000)

        // 5. Cut the route in both directions while data is in flight, and
        //    hold the cut past the pre-fix close threshold. With the scaled
        //    RTO (50/100/200/400ms) 12 exhaustion retries take ~4 s; the
        //    pre-fix code closed the stream at exactly that point.
        val sentBeforeCut = meshA.packetsSent
        meshA.cutRoute()
        meshB.cutRoute()
        out.write("second".toByteArray())
        out.flush()
        Thread.sleep(6_000)
        // The old code's close condition fired at 12 retransmissions of the
        // oldest segment. Prove this test crossed it: initial send + 12
        // retransmits = 13 mesh send attempts for the pending data.
        org.junit.Assert.assertTrue(
            "gap must outlast the pre-fix close threshold (12 retransmissions); " +
                "mesh A made ${meshA.packetsSent - sentBeforeCut} send attempts during the cut",
            meshA.packetsSent - sentBeforeCut >= 13
        )

        // 6. Restore: the pending segment must be delivered, stream alive.
        meshA.restoreRoute()
        meshB.restoreRoute()
        assertEventuallyEquals("hellosecond", received, 20_000)

        // 7. The stream is still usable after the gap.
        out.write("third".toByteArray())
        out.flush()
        assertEventuallyEquals("hellosecondthird", received, 10_000)
    }

    @Test
    fun startWaitsForNodeAddressBeforeAcceptingTraffic() {
        // A stack whose node has not derived its address yet must not accept
        // traffic: the first handshake would be sent from the zero source
        // address and silently dropped by the peer's shim. start() must block
        // until a valid address is published.
        val mesh = FakeMesh(address = null)
        this.meshA = mesh
        val socksPort = freePort()
        val stack = YggdrasilUserSpaceStack(mesh = mesh, socksPort = socksPort, localTargetPort = 1)
        this.stackA = stack

        val started = AtomicBoolean(false)
        val startThread = Thread {
            stack.start()
            started.set(true)
        }
        startThread.isDaemon = true
        startThread.start()

        // No valid address yet: start() has not returned.
        Thread.sleep(300)
        org.junit.Assert.assertFalse("start() must wait for a valid node address", started.get())

        // Publish a valid address; start() must proceed.
        mesh.address = "200:1000:cccc::3"
        startThread.join(YggdrasilUserSpaceStack.ADDRESS_DISCOVERY_TIMEOUT_MS + 2_000)
        org.junit.Assert.assertTrue("start() must return once the address is valid", started.get())

        stack.stop()
    }

    @Test
    fun startFailsClosedWhenNodeAddressNeverAppears() {
        // The timeout path of the address gate: if no valid node address
        // appears within the budget, start() must fail and the SOCKS port
        // must stay closed - never accept traffic from an empty source
        // address. Once an address exists, the same stack starts normally.
        val mesh = FakeMesh(address = null)
        this.meshA = mesh
        val socksPort = freePort()
        val stack = YggdrasilUserSpaceStack(
            mesh = mesh,
            socksPort = socksPort,
            localTargetPort = 1,
            addressDiscoveryTimeoutMs = 500
        )
        this.stackA = stack

        var failure: Throwable? = null
        try {
            stack.start()
        } catch (t: Throwable) {
            failure = t
        }
        org.junit.Assert.assertNotNull(
            "start() must fail when no valid node address appears within the timeout",
            failure
        )

        val portOpen = runCatching {
            Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 500) }
        }.isSuccess
        org.junit.Assert.assertFalse(
            "SOCKS port must not be bound after a failed start",
            portOpen
        )

        mesh.address = "200:1000:cccc::4"
        stack.start()
        val portOpenAfter = runCatching {
            Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 500) }
        }.isSuccess
        org.junit.Assert.assertTrue(
            "SOCKS port must be bound after a successful start",
            portOpenAfter
        )
    }

    @Test
    fun stopDuringAddressWaitDoesNotOpenListener() {
        // Lifecycle race: stop() while start() is still blocked in the
        // address gate. The address may appear afterwards, but the starting
        // thread must not proceed to bind the SOCKS port.
        val mesh = FakeMesh(address = null)
        this.meshA = mesh
        val socksPort = freePort()
        val stack = YggdrasilUserSpaceStack(
            mesh = mesh,
            socksPort = socksPort,
            localTargetPort = 1,
            addressDiscoveryTimeoutMs = 5_000
        )
        this.stackA = stack

        val started = AtomicBoolean(false)
        val startThread = Thread {
            stack.start()
            started.set(true)
        }
        startThread.isDaemon = true
        startThread.start()

        // start() is blocked in the address gate; stop the stack.
        Thread.sleep(300)
        stack.stop()

        // The address appears only after stop(): start() must return
        // without binding the listener.
        mesh.address = "200:1000:cccc::5"
        startThread.join(YggdrasilUserSpaceStack.ADDRESS_DISCOVERY_TIMEOUT_MS + 2_000)
        org.junit.Assert.assertTrue("start() must return after stop()", started.get())

        val portOpen = runCatching {
            Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 500) }
        }.isSuccess
        org.junit.Assert.assertFalse(
            "SOCKS port must not be bound when stop() races the address wait",
            portOpen
        )
    }

    @Test
    fun stoppedStackNeverKeepsListenerBound() {
        // start()/stop() race at the bind: whichever thread wins, a stopped
        // stack must never keep the SOCKS listener open. Without the
        // lifecycle lock, stop() could tear down while socksServer is still
        // null and start() would then bind an orphaned listener.
        repeat(100) { i ->
            val mesh = FakeMesh(address = "200:1000:cccc::7")
            val socksPort = freePort()
            val stack = YggdrasilUserSpaceStack(
                mesh = mesh,
                socksPort = socksPort,
                localTargetPort = 1
            )
            val startThread = Thread { stack.start() }
            startThread.isDaemon = true
            startThread.start()
            stack.stop()
            startThread.join(2_000)
            if (stack.isRunning) {
                // start() won the whole race: clean it up.
                stack.stop()
            }
            val portOpen = runCatching {
                Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 200) }
            }.isSuccess
            org.junit.Assert.assertFalse(
                "iteration $i: stopped stack must not keep the SOCKS listener bound",
                portOpen
            )
        }
    }
}
