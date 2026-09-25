package com.example.twopchat.yggdrasil

import java.io.ByteArrayOutputStream
import java.net.InetAddress
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
 * handshake while data is in flight, restores it, and verifies that the
 * pending segment is delivered and the stream stays usable. This is the
 * regression test for "session goes offline ~60 s after the first
 * message": before the fix, retransmit exhaustion closed the stream
 * (~65 s) and the ACK budget tore down the Go session (~62 s).
 *
 * The cut is held only ~1.5 s (a few RTO cycles), which is enough to prove
 * the no-close-on-exhaustion contract end to end without waiting for the
 * old 65 s teardown.
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
        val stackA = YggdrasilUserSpaceStack(mesh = meshA, socksPort = socksPort, localTargetPort = 1)
        val stackB = YggdrasilUserSpaceStack(
            mesh = meshB,
            socksPort = freePort(),
            localTargetPort = listener.localPort,
            // Host-specific: on this Windows box a socket bound to 127.0.0.2
            // cannot connect to 127.0.0.1 (verified with a standalone probe),
            // so the inbound marker address is injected as loopback here.
            // The production default (127.0.0.2 marker for the Go core's
            // transport classification) is unchanged.
            inboundSourceAddress = "127.0.0.1"
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

        // 5. Cut the route in both directions while data is in flight.
        meshA.cutRoute()
        meshB.cutRoute()
        out.write("second".toByteArray())
        out.flush()
        // A few retransmit cycles (RTO 600ms, 1200ms, ...) are lost on the
        // cut route. Pre-fix, this window also ended in a closed stream.
        Thread.sleep(1_500)

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
}
