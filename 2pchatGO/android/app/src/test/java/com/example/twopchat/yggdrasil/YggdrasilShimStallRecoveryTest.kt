package com.example.twopchat.yggdrasil

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilShimStallRecoveryTest {
    @Test
    fun `mesh receive keeps draining while ACK send waits for receive progress`() {
        val meshA = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:aaaa::1")
        val meshB = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:bbbb::2")
        meshA.linkTo(meshB)
        val enteredSend = CountDownLatch(1)
        val receiveProgress = CountDownLatch(1)
        val arm = AtomicBoolean(true)
        val blocked = AtomicBoolean(false)
        val drained = java.util.concurrent.atomic.AtomicInteger()
        val firstPayload = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val sourceMesh = object : MeshTransport by meshA {
            override fun sendPacket(data: ByteArray, length: Long) {
                if (data.size > 60 && data[53].toInt() == 0x18) {
                    firstPayload.compareAndSet(null, data.copyOf())
                }
                meshA.sendPacket(data, length)
            }
        }
        val coupledMesh = object : MeshTransport by meshB {
            override fun sendPacket(data: ByteArray, length: Long) {
                // The real gomobile/actor boundary can backpressure a write
                // until its read side drains. Model that dependency, rather
                // than a timed route outage that eventually heals itself.
                if (data.size >= 60 && data[53].toInt() == 0x10 && arm.compareAndSet(true, false)) {
                    blocked.set(true)
                    enteredSend.countDown()
                    check(receiveProgress.await(10, TimeUnit.SECONDS))
                }
                meshB.sendPacket(data, length)
            }

            override fun receivePacket(buffer: ByteArray): Int {
                val size = meshB.receivePacket(buffer)
                if (size > 0 && blocked.get() && drained.incrementAndGet() >= 128) {
                    receiveProgress.countDown()
                }
                return size
            }
        }
        ServerSocket(0).use { listener ->
            val payload = ByteArray(256 * 1024) { (it * 31).toByte() }
            val received = java.util.concurrent.CompletableFuture<ByteArray>()
            val reader = thread(isDaemon = true) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 15_000
                        received.complete(socket.getInputStream().readNBytes(payload.size))
                    }
                } catch (error: Throwable) {
                    received.completeExceptionally(error)
                }
            }
            val socksPort = freePort()
            val stackA = YggdrasilUserSpaceStack(sourceMesh, socksPort, 1)
            val stackB = YggdrasilUserSpaceStack(coupledMesh, freePort(), listener.localPort,
                inboundSourceAddress = "127.0.0.1")
            try {
                stackA.start()
                stackB.start()
                connect(socksPort, listener.localPort).use { client ->
                    val sent = java.util.concurrent.CompletableFuture<Unit>()
                    thread(isDaemon = true) {
                        try {
                            client.getOutputStream().write(payload)
                            sent.complete(Unit)
                        } catch (error: Throwable) {
                            sent.completeExceptionally(error)
                        }
                    }
                    assertTrue("ACK send was not exercised", enteredSend.await(5, TimeUnit.SECONDS))
                    // More ACKs than the outbound queue can hold. A blocking
                    // put would recreate the deadlock even with a writer thread.
                    val duplicate = checkNotNull(firstPayload.get())
                    repeat(128) { meshA.sendPacket(duplicate, duplicate.size.toLong()) }
                    assertTrue("ACK send blocked the only mesh reader",
                        receiveProgress.await(2, TimeUnit.SECONDS))
                    org.junit.Assert.assertArrayEquals(payload, received.get(15, TimeUnit.SECONDS))
                    sent.get(5, TimeUnit.SECONDS)
                }
            } finally {
                receiveProgress.countDown()
                stackA.stop()
                stackB.stop()
                meshA.close()
                meshB.close()
                listener.close()
                reader.join(2_000)
            }
        }
    }

    @Test
    fun `acknowledged route gap does not trigger stall timeout`() {
        val meshA = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:aaaa::1")
        val meshB = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:bbbb::2")
        meshA.linkTo(meshB)
        ServerSocket(0).use { listener ->
            val received = LinkedBlockingQueue<String>()
            val reader = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    val buf = ByteArray(32)
                    while (true) {
                        val n = socket.getInputStream().read(buf)
                        if (n <= 0) break
                        received.offer(String(buf, 0, n))
                    }
                }
            }
            val socksPort = freePort()
            val stackA = YggdrasilUserSpaceStack(
                mesh = meshA, socksPort = socksPort, localTargetPort = 1,
                retransmitBaseRtoMs = 50, retransmitMaxRtoMs = 200,
                streamStallTimeoutMs = 1_500,
            )
            val stackB = YggdrasilUserSpaceStack(
                mesh = meshB, socksPort = freePort(), localTargetPort = listener.localPort,
                inboundSourceAddress = "127.0.0.1",
                retransmitBaseRtoMs = 50, retransmitMaxRtoMs = 200,
                streamStallTimeoutMs = 1_500,
            )
            try {
                stackA.start()
                stackB.start()
                connect(socksPort, listener.localPort).use { client ->
                    meshA.cutRoute()
                    meshB.cutRoute()
                    client.getOutputStream().write("delayed".toByteArray())
                    Thread.sleep(350)
                    meshA.restoreRoute()
                    meshB.restoreRoute()
                    assertEquals("delayed", received.poll(5, TimeUnit.SECONDS))
                    Thread.sleep(1_600)
                    client.getOutputStream().write("still-open".toByteArray())
                    assertEquals("still-open", received.poll(5, TimeUnit.SECONDS))
                }
            } finally {
                stackA.stop()
                stackB.stop()
                meshA.close()
                meshB.close()
                reader.join(2_000)
            }
        }
    }

    @Test
    fun `stalled mesh send cannot block stream watchdog`() {
        val meshA = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:aaaa::1")
        val meshB = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:bbbb::2")
        meshA.linkTo(meshB)
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val blockSend = AtomicBoolean(false)
        val blockingMesh = object : MeshTransport by meshA {
            override fun sendPacket(data: ByteArray, length: Long) {
                if (blockSend.get()) {
                    enteredSend.countDown()
                    releaseSend.await(5, TimeUnit.SECONDS)
                }
                meshA.sendPacket(data, length)
            }
        }
        ServerSocket(0).use { listener ->
            val accepted = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    while (socket.getInputStream().read() >= 0) { /* drain */ }
                }
            }
            val socksPort = freePort()
            val stackA = YggdrasilUserSpaceStack(
                mesh = blockingMesh, socksPort = socksPort, localTargetPort = 1,
                streamStallTimeoutMs = 1_500,
            )
            val stackB = YggdrasilUserSpaceStack(
                mesh = meshB, socksPort = freePort(), localTargetPort = listener.localPort,
                inboundSourceAddress = "127.0.0.1",
                streamStallTimeoutMs = 1_500,
            )
            try {
                stackA.start()
                stackB.start()
                connect(socksPort, listener.localPort).use { client ->
                    blockSend.set(true)
                    client.getOutputStream().write("blocked".toByteArray())
                    assertTrue("send did not block", enteredSend.await(5, TimeUnit.SECONDS))
                    client.soTimeout = 4_000
                    assertEquals(-1, client.getInputStream().read())
                }
            } finally {
                releaseSend.countDown()
                stackA.stop()
                stackB.stop()
                meshA.close()
                meshB.close()
                accepted.join(2_000)
            }
        }
    }

    @Test
    fun `persistent route loss closes stale stream and permits new connection`() {
        val meshA = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:aaaa::1")
        val meshB = YggdrasilShimGapDeliveryTest.FakeMesh("200:1000:bbbb::2")
        meshA.linkTo(meshB)
        ServerSocket(0).use { listener ->
            val received = LinkedBlockingQueue<String>()
            val acceptor = thread(isDaemon = true) {
                repeat(2) {
                    val socket = listener.accept()
                    thread(isDaemon = true) {
                        socket.use {
                            val data = ByteArray(32)
                            while (true) {
                                val n = socket.getInputStream().read(data)
                                if (n <= 0) break
                                received.offer(String(data, 0, n))
                            }
                        }
                    }
                }
            }
            val socksPort = freePort()
            val stackA = YggdrasilUserSpaceStack(
                mesh = meshA, socksPort = socksPort, localTargetPort = 1,
                retransmitBaseRtoMs = 50, retransmitMaxRtoMs = 200,
                streamStallTimeoutMs = 1_500,
            )
            val stackB = YggdrasilUserSpaceStack(
                mesh = meshB, socksPort = freePort(), localTargetPort = listener.localPort,
                inboundSourceAddress = "127.0.0.1",
                retransmitBaseRtoMs = 50, retransmitMaxRtoMs = 200,
                streamStallTimeoutMs = 1_500,
            )
            try {
                stackA.start()
                stackB.start()
                connect(socksPort, listener.localPort).use { first ->
                    first.getOutputStream().write("first".toByteArray())
                    assertEquals("first", received.poll(5, TimeUnit.SECONDS))
                    meshA.cutRoute()
                    meshB.cutRoute()
                    first.getOutputStream().write("stalled".toByteArray())
                    first.soTimeout = 5_000
                    assertEquals(-1, first.getInputStream().read())
                }
                meshA.restoreRoute()
                meshB.restoreRoute()
                connect(socksPort, listener.localPort).use { second ->
                    second.getOutputStream().write("recovered".toByteArray())
                    assertEquals("recovered", received.poll(5, TimeUnit.SECONDS))
                }
            } finally {
                stackA.stop()
                stackB.stop()
                meshA.close()
                meshB.close()
                acceptor.join(2_000)
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun connect(socksPort: Int, targetPort: Int): Socket {
        val socket = Socket("127.0.0.1", socksPort)
        socket.soTimeout = 5_000
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, 0))
        assertEquals(5, input.read())
        assertEquals(0, input.read())
        val request = ByteArrayOutputStream().apply {
            write(byteArrayOf(5, 1, 0, 4))
            write(InetAddress.getByName("200:1000:bbbb::2").address)
            write(targetPort shr 8)
            write(targetPort and 255)
        }
        output.write(request.toByteArray())
        val reply = ByteArray(10)
        var count = 0
        while (count < reply.size) {
            val n = input.read(reply, count, reply.size - count)
            assertTrue("SOCKS reply ended early", n > 0)
            count += n
        }
        assertEquals(0, reply[1].toInt())
        return socket
    }
}
