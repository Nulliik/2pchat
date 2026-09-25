package com.example.twopchat.yggdrasil

import com.example.twopchat.logging.SafeLog
import java.io.InputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val TAG = "YggUserStack"

/** Retransmit-loop outcome for one stream scan. */
internal enum class RetransmitAction { SKIP, RETRY }

/**
 * Retransmit decision for one stream. A stream is retransmitted
 * indefinitely once its oldest segment is unacknowledged past
 * MAX_SEGMENT_RETRIES: the peer side retransmits symmetrically, and
 * cumulative ACKs recover the stream when the mesh route returns. Only an
 * explicitly closed stream (RST, FIN, local stop) is skipped. Closing on
 * retry exhaustion used to tear down the authenticated session on every
 * transient mesh route gap.
 */
internal fun retransmitDecision(isClosed: Boolean): RetransmitAction =
    if (isClosed) RetransmitAction.SKIP else RetransmitAction.RETRY

/**
 * Bounded receive window for the user-space TCP shim. Yggdrasil transports IP
 * packets, not an ordered byte stream, so payload must never be handed to the
 * local TCP socket before its predecessor has arrived.
 */
internal class TcpReceiveWindow(initialSequence: Long) {
    private var nextSequence = initialSequence
    private val pending = java.util.TreeMap<Long, ByteArray>()

    @Synchronized
    fun accept(sequence: Long, payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty() || sequence < nextSequence) return emptyList()
        if (sequence > nextSequence) {
            if (pending.size < MAX_REORDERED_SEGMENTS && pending.values.sumOf { it.size } + payload.size <= MAX_REORDERED_BYTES) {
                pending.putIfAbsent(sequence, payload.copyOf())
            }
            return emptyList()
        }

        val ready = mutableListOf(payload)
        nextSequence += payload.size
        while (true) {
            val following = pending.remove(nextSequence) ?: break
            ready += following
            nextSequence += following.size
        }
        return ready
    }

    @Synchronized
    fun expectedSequence(): Long = nextSequence

    private companion object {
        const val MAX_REORDERED_SEGMENTS = 64
        const val MAX_REORDERED_BYTES = 64 * 1024
    }
}

/**
 * Pure Kotlin User-Space TCP/IP Stack & SOCKS5 Server for Yggdrasil Proxy Mode.
 * Translates SOCKS5 connections to raw IPv6/TCP packets on the mesh without requiring VPN permissions.
 */
internal class YggdrasilUserSpaceStack(
    private val mesh: MeshTransport,
    private val socksPort: Int = 9053,
    private val localTargetPort: Int = 50001,
    private val inboundSourceAddress: String = "127.0.0.2",
    private val addressDiscoveryTimeoutMs: Long = ADDRESS_DISCOVERY_TIMEOUT_MS,
    private val retransmitBaseRtoMs: Long = INITIAL_RTO_MS,
    private val retransmitMaxRtoMs: Long = MAX_RTO_MS
) {
    private val running = AtomicBoolean(false)
    private var socksServer: ServerSocket? = null
    private var udpRelaySocket: DatagramSocket? = null
    private var workerThreads = mutableListOf<Thread>()

    // Serializes "check running -> bind listeners" in start() with the
    // teardown in stop(): a stopped stack must never end up with an
    // orphaned listener. A running check alone leaves a TOCTOU window
    // between the check and the bind.
    private val lifecycleLock = Any()
    // A stop is terminal for this stack instance. The service creates a fresh
    // stack for its next start, while an address-discovery failure remains
    // retryable because it does not set this flag.
    private val stopRequested = AtomicBoolean(false)
    internal val isRunning: Boolean get() = running.get()
    // TCP data, ACKs and retransmissions originate on different threads. The
    // gomobile buffer boundary does not provide a packet-atomic multi-writer
    // contract, so serialize every outbound mesh packet here.
    private val yggSendLock = Any()

    // The node address is stable for the lifetime of the key (it only
    // changes on key regeneration, which stops the whole stack). If the
    // gomobile bridge transiently returns an empty/invalid address, the
    // last known-good address must keep being used: an all-zero source
    // address makes the peer's shim key every packet under "::" and drop
    // data + ACKs, which used to kill the session after one retransmit
    // budget (~65s) with no mesh problem at all.
    @Volatile
    private var cachedLocalIp: ByteArray? = null

    private val localIp: ByteArray
        get() = resolveLocalAddress(mesh.addressString(), cachedLocalIp)
            .also { if (isYggdrasilAddress(it)) cachedLocalIp = it }

    private class StreamSession(
        val streamKey: String,
        val remoteIp: ByteArray,
        val remotePort: Int,
        val localPort: Int,
        val initialSeqSent: Long = 1000L,
        var clientSocket: Socket? = null,
        var clientIn: InputStream? = null,
        var clientOut: OutputStream? = null,
        var seqSent: AtomicLong = AtomicLong(initialSeqSent),
        var seqRecv: AtomicLong = AtomicLong(0L),
        var receiveWindow: TcpReceiveWindow = TcpReceiveWindow(0L),
        val unacknowledged: ConcurrentSkipListMap<Long, PendingSegment> = ConcurrentSkipListMap(),
        var isEstablished: AtomicBoolean = AtomicBoolean(false),
        var isClosed: AtomicBoolean = AtomicBoolean(false)
    )

    private data class PendingSegment(
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val sequence: Long,
        val payload: ByteArray,
        @Volatile var lastSentAtMs: Long = System.currentTimeMillis(),
        @Volatile var retries: Int = 0,
    ) {
        val endExclusive: Long get() = sequence + payload.size
    }

    private val activeSessions = ConcurrentHashMap<String, StreamSession>()
    private val pendingHandshakes = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>>()
    private val pendingInboundSessions = ConcurrentHashMap.newKeySet<String>()
    private val portCounter = AtomicInteger(40000)
    private data class UdpRelaySession(val client: InetSocketAddress)
    private val udpSessions = ConcurrentHashMap<Int, UdpRelaySession>()
    // BEP-15 tracker connect IDs are tied to the UDP source endpoint. Keep a
    // stable mesh-side port for every native client socket across its requests.
    private val udpClientPorts = ConcurrentHashMap<String, Int>()

    private fun waitForLocalAddress(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // stop() during the wait invalidates the start: bail out
            // immediately instead of holding the starting thread until the
            // deadline; the caller must not open the listener afterwards.
            if (!running.get()) return ByteArray(16)
            val ip = localIp
            if (isYggdrasilAddress(ip)) return ip
            if (System.currentTimeMillis() >= deadline) return ip
            try {
                Thread.sleep(ADDRESS_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return ip
            }
        }
    }

    fun start() {
        // Coordinate the initial transition with stop(). Without this lock,
        // stop() can observe the pre-start false value and return just before
        // this thread marks the stack running and binds its listeners.
        val mayStart = synchronized(lifecycleLock) {
            !stopRequested.get() && running.compareAndSet(false, true)
        }
        if (!mayStart) return

        // 0. Fail closed without a valid node address. A packet sent from an
        // all-zero source address is keyed by the peer's shim under "::" and
        // dropped with its ACKs, so the first handshake would fail even on a
        // healthy route. Serving traffic before an address exists was the
        // "online, messages don't flow, offline" bug: the owner starts the
        // node before this stack, so a valid address is expected promptly,
        // and a timeout means the node is broken - never open the SOCKS port.
        val readyAddress = waitForLocalAddress(addressDiscoveryTimeoutMs)
        if (!running.get()) {
            // stop() raced with the address wait: the stack is being torn
            // down, so the starting thread must not open the listener.
            SafeLog.i(TAG, "Stack stopped while waiting for the node address; start aborted")
            return
        }
        if (!isYggdrasilAddress(readyAddress)) {
            running.set(false)
            SafeLog.e(
                TAG,
                "No valid Yggdrasil node address after ${addressDiscoveryTimeoutMs}ms; refusing to accept traffic from an empty source address"
            )
            throw IllegalStateException(
                "Yggdrasil node address not available within ${addressDiscoveryTimeoutMs}ms"
            )
        }

        val listenersBound = synchronized(lifecycleLock) {
            if (!running.get()) {
                // stop() won the race: the stack is being torn down, so no
                // listener may be opened.
                false
            } else {
                // 1. Start SOCKS5 Listener on 127.0.0.1:socksPort
                try {
                    socksServer = ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort))
                    }
                    SafeLog.i(TAG, "SOCKS5 proxy server successfully bound on 127.0.0.1:$socksPort")
                    val tSocks = thread(name = "Ygg-SOCKS5-Acceptor") {
                        acceptSocksLoop()
                    }
                    workerThreads.add(tSocks)
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "Failed to bind SOCKS5 server on port $socksPort", e)
                }

                try {
                    udpRelaySocket = DatagramSocket(InetSocketAddress("127.0.0.1", socksPort + 1))
                    workerThreads.add(thread(name = "Ygg-UDP-Relay") { udpRelayLoop() })
                    SafeLog.i(TAG, "Yggdrasil UDP relay bound on 127.0.0.1:${socksPort + 1}")
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "Failed to bind Yggdrasil UDP relay", e)
                }

                // 2. Start Mesh Packet Receiver
                val tRecv = thread(name = "Ygg-Mesh-Receiver") {
                    meshReceiveLoop()
                }
                workerThreads.add(tRecv)
                workerThreads.add(thread(name = "Ygg-TCP-Retransmit") { retransmitLoop() })
                true
            }
        }
        if (!listenersBound) {
            SafeLog.i(TAG, "Stack stopped while starting; listeners were not opened")
            return
        }
        runCatching {
            com.example.twopchat.AppLog.append(
                GlobalApplication.getContext(),
                "[YGGDRASIL] [YggUserStack] SOCKS5 server listening on 127.0.0.1:$socksPort, UDP relay on 127.0.0.1:${socksPort + 1}\n"
            )
        }
    }

    fun stop() {
        // Record cancellation even when a just-created start thread has not
        // reached its initial state transition yet.
        val needsTeardown = synchronized(lifecycleLock) {
            stopRequested.set(true)
            running.compareAndSet(true, false)
        }
        if (!needsTeardown) return

        synchronized(lifecycleLock) {
            runCatching {
                com.example.twopchat.AppLog.append(
                    GlobalApplication.getContext(),
                    "[YGGDRASIL] [YggUserStack] Stopping user-space stack\n"
                )
            }
            runCatching { socksServer?.close() }
            runCatching { udpRelaySocket?.close() }
            activeSessions.values.forEach { sess ->
                runCatching { sess.clientSocket?.close() }
            }
            activeSessions.clear()
            pendingHandshakes.values.forEach { it.complete(false) }
            pendingHandshakes.clear()
            pendingInboundSessions.clear()
            udpSessions.clear()
            udpClientPorts.clear()

            workerThreads.forEach { it.interrupt() }
            workerThreads.clear()
        }
        SafeLog.i(TAG, "Yggdrasil User-Space Proxy Stack stopped")
    }

    private fun acceptSocksLoop() {
        while (running.get()) {
            try {
                val client = socksServer?.accept() ?: break
                thread(name = "Ygg-SOCKS5-Client") {
                    handleSocksClient(client)
                }
            } catch (_: Throwable) {
                if (!running.get()) break
            }
        }
    }

    private fun handleSocksClient(client: Socket) {
        var session: StreamSession? = null
        var sessionKey: String? = null
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. Negotiate SOCKS5
            val ver = input.read()
            if (ver != 5) {
                client.close()
                return
            }
            val nMethods = input.read()
            if (nMethods !in 1..255) {
                client.close()
                return
            }
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            // Reply: No Auth Required (0x05, 0x00)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. Read SOCKS5 Request
            val req = ByteArray(4)
            input.readFully(req)
            if (req[0].toInt() != 5) {
                client.close()
                return
            }
            val cmd = req[1].toInt()
            val atyp = req[3].toInt()

            if (cmd != 1) { // CONNECT only
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close()
                return
            }

            val targetIpBytes: ByteArray
            when (atyp) {
                1 -> { // IPv4
                    val ip4 = ByteArray(4)
                    input.readFully(ip4)
                    targetIpBytes = ip4
                }
                3 -> { // Domain name
                    val len = input.read()
                    if (len !in 1..255) throw EOFException("Invalid SOCKS5 domain length")
                    val domainBytes = ByteArray(len)
                    input.readFully(domainBytes)
                    val domain = String(domainBytes).trim('[', ']')
                    targetIpBytes = InetAddress.getByName(domain).address
                }
                4 -> { // IPv6
                    val ip6 = ByteArray(16)
                    input.readFully(ip6)
                    targetIpBytes = ip6
                }
                else -> {
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    client.close()
                    return
                }
            }

            val portBuf = ByteArray(2)
            input.readFully(portBuf)
            val targetPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

            // This listener is exclusively the Yggdrasil transport. Never use it
            // to reach clearnet/private destinations through the mesh.
            if (!isYggdrasilAddress(targetIpBytes)) {
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            client.soTimeout = 0

            // 3. Initiate virtual stream handshake over Yggdrasil
            val localPort = portCounter.getAndIncrement()
            if (localPort > 60000) portCounter.set(40000)

            val currentKey = "${bytesToHex(targetIpBytes)}:$targetPort:$localPort"
            sessionKey = currentKey
            val currentSession = StreamSession(
                streamKey = currentKey,
                remoteIp = targetIpBytes,
                remotePort = targetPort,
                localPort = localPort,
                clientSocket = client,
                clientIn = input,
                clientOut = output
            )
            session = currentSession
            activeSessions[currentKey] = currentSession

            val handshakeFuture = java.util.concurrent.CompletableFuture<Boolean>()
            pendingHandshakes[sessionKey] = handshakeFuture

            val synSeq = consumeSynSequence(session.seqSent)
            val deadline = System.currentTimeMillis() + 12_000L
            var rto = 1_000L
            var success = false
            while (System.currentTimeMillis() < deadline && !handshakeFuture.isDone && running.get()) {
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = targetIpBytes,
                    srcPort = localPort,
                    dstPort = targetPort,
                    seq = synSeq,
                    ack = 0L,
                    flags = 0x02 // SYN
                )
                // Never wait past the advertised 12 s deadline: the last
                // retransmit used to block up to its full 4 s RTO beyond it,
                // so a client with a 15 s budget raced the code=4 reply.
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    success = handshakeFuture.get(
                        minOf(rto, remaining),
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )
                    if (success) break
                } catch (_: java.util.concurrent.TimeoutException) {
                    rto = minOf(rto * 2, 4_000L)
                } catch (_: Throwable) {
                    break
                }
            }
            pendingHandshakes.remove(sessionKey)

            if (!success) {
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                activeSessions.remove(sessionKey)
                client.close()
                return
            }

            // SOCKS5 success reply
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
            output.flush()

            // 4. Pipe client data to Yggdrasil TCP packets
            val buf = ByteArray(16384)
            while (running.get() && !session.isClosed.get()) {
                val read = input.read(buf)
                if (read <= 0) break

                var offset = 0
                while (offset < read && !session.isClosed.get() && running.get()) {
                    awaitSendWindow(session)
                    val segLen = minOf(read - offset, MAX_TCP_PAYLOAD)
                    val chunk = buf.copyOfRange(offset, offset + segLen)
                    val currentSeq = session.seqSent.getAndAdd(segLen.toLong())
                    sendTrackedPayload(session, targetIpBytes, localPort, targetPort, currentSeq, chunk)
                    offset += segLen
                    if (offset < read) {
                        try {
                            Thread.sleep(SEND_WINDOW_WAIT_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }

            // Send FIN
            sendTcpPacket(
                srcIp = localIp,
                dstIp = targetIpBytes,
                srcPort = localPort,
                dstPort = targetPort,
                seq = session.seqSent.get(),
                ack = session.seqRecv.get(),
                flags = 0x11 // FIN | ACK
            )
        } catch (e: Exception) {
            SafeLog.d(TAG, "Failed sending FIN packet: ${e.javaClass.simpleName}")
        } finally {
            val s = session
            s?.isClosed?.set(true)
            runCatching { client.close() }
            // Always evict, mirroring the inbound path: the stream is
            // finished (FIN), a closed session is no longer retransmitted,
            // and a lingering entry would block future reconnects.
            if (sessionKey != null) {
                activeSessions.remove(sessionKey)
            }
        }
    }

    private fun meshReceiveLoop() {
        val buf = ByteArray(65535)
        while (running.get()) {
            try {
                val len = mesh.receivePacket(buf)
                if (len <= 0) {
                    Thread.sleep(10)
                    continue
                }
                if (len < 40) continue // Minimum IPv6 header size

                handleInboundPacket(buf, len)
            } catch (_: Throwable) {
                if (!running.get()) break
            }
        }
    }

    /** Receives YUDP framing from the native Go tracker client. */
    private fun udpRelayLoop() {
        val socket = udpRelaySocket ?: return
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = packet.data
                if (packet.length < 23 || String(data, 0, 4) != "YUDP") continue
                val dstIp = data.copyOfRange(4, 20)
                if (!isYggdrasilAddress(dstIp)) continue
                val dstPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
                val client = InetSocketAddress(packet.address, packet.port)
                val clientKey = "${packet.address.hostAddress}:${packet.port}"
                val localPort = udpClientPorts.computeIfAbsent(clientKey) {
                    portCounter.getAndIncrement().also { if (it > 60000) portCounter.set(40000) }
                }
                udpSessions[localPort] = UdpRelaySession(client)
                sendUdpPacket(localIp, dstIp, localPort, dstPort, data.copyOfRange(22, packet.length))
            } catch (_: Throwable) { if (!running.get()) break }
        }
    }

    private fun handleInboundPacket(pkt: ByteArray, len: Int) {
        if (len < 48) return
        val nextHeader = pkt[6].toInt() and 0xFF
        if (nextHeader == 17) { handleInboundUdp(pkt, len); return }
        if (nextHeader != 6 || len < 60) return // TCP only

        val ipv6PayloadLength = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
        val packetEnd = 40 + ipv6PayloadLength
        if (packetEnd > len || ipv6PayloadLength < 20) return

        val srcIp = pkt.copyOfRange(8, 24)
        val dstIp = pkt.copyOfRange(24, 40)

        val tcpOffset = 40

        val srcPort = ((pkt[tcpOffset].toInt() and 0xFF) shl 8) or (pkt[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((pkt[tcpOffset + 2].toInt() and 0xFF) shl 8) or (pkt[tcpOffset + 3].toInt() and 0xFF)

        val seq = ByteBuffer.wrap(pkt, tcpOffset + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val ack = ByteBuffer.wrap(pkt, tcpOffset + 8, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val dataOffset = ((pkt[tcpOffset + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || tcpOffset + dataOffset > packetEnd) return
        val flags = pkt[tcpOffset + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadStart = tcpOffset + dataOffset
        val payloadLen = packetEnd - payloadStart

        val sessionKeyOutbound = "${bytesToHex(srcIp)}:$srcPort:$dstPort"
        val sessionKeyInbound = "${bytesToHex(srcIp)}:$srcPort:$dstPort"

        // Check if this is a reply to our outbound SOCKS connection
        val outSession = activeSessions[sessionKeyOutbound]
        if (com.example.twopchat.BuildConfig.DEBUG) {
            SafeLog.d(TAG, "TCP RX ports=$srcPort->$dstPort seq=$seq ack=$ack flags=$flags bytes=$payloadLen expected=${outSession?.seqRecv?.get()} flight=${outSession?.unacknowledged?.size}")
        }
        if (outSession != null) {
            acknowledgeOutbound(outSession, ack)
            if (isSyn && isAck) {
                outSession.seqRecv.set(seq + 1)
                outSession.receiveWindow = TcpReceiveWindow(seq + 1)
                outSession.isEstablished.set(true)
                // Send ACK to complete handshake
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = outSession.seqSent.get(),
                    ack = outSession.seqRecv.get(),
                    flags = 0x10 // ACK
                )
                pendingHandshakes[sessionKeyOutbound]?.complete(true)
                return
            }

            if (isRst) {
                pendingHandshakes[sessionKeyOutbound]?.complete(false)
                outSession.isClosed.set(true)
                runCatching { outSession.clientSocket?.close() }
                activeSessions.remove(sessionKeyOutbound)
                return
            }

            if (payloadLen > 0) {
                val payload = pkt.copyOfRange(payloadStart, packetEnd)
                val inOrder = outSession.receiveWindow.accept(seq, payload)
                outSession.seqRecv.set(outSession.receiveWindow.expectedSequence())
                runCatching {
                    for (segment in inOrder) outSession.clientOut?.write(segment)
                    if (inOrder.isNotEmpty()) outSession.clientOut?.flush()
                }
                // Send ACK
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = outSession.seqSent.get(),
                    ack = outSession.seqRecv.get(),
                    flags = 0x10 // ACK
                )
            }

            if (isFin) {
                outSession.seqRecv.incrementAndGet()
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = outSession.seqSent.get(),
                    ack = outSession.seqRecv.get(),
                    flags = 0x10 // ACK
                )
                outSession.isClosed.set(true)
                runCatching { outSession.clientSocket?.close() }
                activeSessions.remove(sessionKeyOutbound)
            }
            return
        }

        // Inbound connection from mesh peer destined to local app port
        if (isSyn && !isAck) {
            val existingInbound = activeSessions[sessionKeyInbound]
            if (existingInbound != null) {
                if (existingInbound.isClosed.get()) {
                    // Stale closed session – evict it so the new handshake can proceed.
                    runCatching { existingInbound.clientSocket?.close() }
                    activeSessions.remove(sessionKeyInbound)
                    SafeLog.d(TAG, "Evicted stale closed session for $sessionKeyInbound; accepting new SYN")
                } else {
                    // Duplicate SYN – peer retransmitting because our SYN-ACK was lost/delayed in mesh.
                    sendTcpPacket(
                        srcIp = localIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        seq = existingInbound.initialSeqSent,
                        ack = existingInbound.seqRecv.get(),
                        flags = 0x12 // SYN | ACK
                    )
                    return
                }
            }
            if (!pendingInboundSessions.add(sessionKeyInbound)) {
                return
            }
            thread(name = "Ygg-Inbound-Worker") {
                try {
                    handleInboundMeshConnection(srcIp, srcPort, dstPort, seq)
                } finally {
                    pendingInboundSessions.remove(sessionKeyInbound)
                }
            }
        }
    }

    private fun handleInboundUdp(pkt: ByteArray, len: Int) {
        val payloadLength = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
        val end = 40 + payloadLength
        if (payloadLength < 8 || end > len) return
        val dstPort = ((pkt[42].toInt() and 0xFF) shl 8) or (pkt[43].toInt() and 0xFF)
        val session = udpSessions[dstPort] ?: return
        val payload = pkt.copyOfRange(48, end)
        runCatching { udpRelaySocket?.send(DatagramPacket(payload, payload.size, session.client.address, session.client.port)) }
    }

    private fun handleInboundMeshConnection(srcIp: ByteArray, srcPort: Int, dstPort: Int, initialSeq: Long) {
        val sessionKey = "${bytesToHex(srcIp)}:$srcPort:$dstPort"
        try {
            // Mark mesh-proxied inbound streams with a dedicated source
            // address; the native listener otherwise sees only loopback and
            // reports a false Direct P2P route to the UI.
            val localSocket = Socket().apply {
                bind(InetSocketAddress(InetAddress.getByName(inboundSourceAddress), 0))
                connect(InetSocketAddress("127.0.0.1", localTargetPort), LOCAL_CORE_CONNECT_TIMEOUT_MS)
            }
            val session = StreamSession(
                streamKey = sessionKey,
                remoteIp = srcIp,
                remotePort = srcPort,
                localPort = dstPort,
                initialSeqSent = 5000L,
                clientSocket = localSocket,
                clientIn = localSocket.getInputStream(),
                clientOut = localSocket.getOutputStream(),
                seqSent = AtomicLong(5000L),
                seqRecv = AtomicLong(initialSeq + 1),
                receiveWindow = TcpReceiveWindow(initialSeq + 1),
                isEstablished = AtomicBoolean(true)
            )
            activeSessions[sessionKey] = session

            // Send SYN-ACK
            sendTcpPacket(
                srcIp = localIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = session.seqSent.getAndIncrement(),
                ack = session.seqRecv.get(),
                flags = 0x12 // SYN | ACK
            )

            // Pipe data from local 2PChat listener back to the Yggdrasil peer
            val buf = ByteArray(16384)
            val input = session.clientIn ?: return
            while (running.get() && !session.isClosed.get()) {
                val read = input.read(buf)
                if (read <= 0) break

                var offset = 0
                while (offset < read && !session.isClosed.get() && running.get()) {
                    awaitSendWindow(session)
                    val segLen = minOf(read - offset, MAX_TCP_PAYLOAD)
                    val chunk = buf.copyOfRange(offset, offset + segLen)
                    val currentSeq = session.seqSent.getAndAdd(segLen.toLong())
                    sendTrackedPayload(session, srcIp, dstPort, srcPort, currentSeq, chunk)
                    offset += segLen
                    if (offset < read) {
                        try {
                            Thread.sleep(SEND_WINDOW_WAIT_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }

            // Send FIN
            sendTcpPacket(
                srcIp = localIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = session.seqSent.get(),
                ack = session.seqRecv.get(),
                flags = 0x11 // FIN | ACK
            )
        } catch (_: Throwable) {
            // If connection refused locally, send RST
            sendTcpPacket(
                srcIp = localIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = 0L,
                ack = initialSeq + 1,
                flags = 0x14 // RST | ACK
            )
        } finally {
            pendingInboundSessions.remove(sessionKey)
            val sess = activeSessions[sessionKey]
            sess?.isClosed?.set(true)
            runCatching { sess?.clientSocket?.close() }
            // Always evict from activeSessions – the retransmit loop checks isClosed
            // independently, so leaving a dead entry here only blocks future reconnects.
            activeSessions.remove(sessionKey)
        }
    }

    private fun sendTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray? = null
    ) {
        val payloadLen = payload?.size ?: 0
        if (com.example.twopchat.BuildConfig.DEBUG) {
            SafeLog.d(TAG, "TCP TX ports=$srcPort->$dstPort seq=$seq ack=$ack flags=$flags bytes=$payloadLen")
        }
        val tcpLen = 20 + payloadLen
        val totalLen = 40 + tcpLen

        val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // 1. IPv6 Header (40 bytes)
        packet.putInt(0x60000000) // Version 6, Traffic Class 0, Flow Label 0
        packet.putShort(tcpLen.toShort()) // Payload length
        packet.put(6.toByte()) // Next Header: TCP
        packet.put(64.toByte()) // Hop Limit: 64
        packet.put(srcIp)
        packet.put(dstIp)

        // 2. TCP Header (20 bytes)
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putInt((seq and 0xFFFFFFFFL).toInt())
        packet.putInt((ack and 0xFFFFFFFFL).toInt())
        packet.put((5 shl 4).toByte()) // Data offset 5 (20 bytes)
        packet.put(flags.toByte())
        packet.putShort(65535.toShort()) // Window Size
        packet.putShort(0.toShort()) // Checksum (computed below)
        packet.putShort(0.toShort()) // Urgent pointer

        // 3. TCP Payload
        if (payload != null) {
            packet.put(payload)
        }

        val raw = packet.array()
        val csum = computeTcpChecksum(raw, 40, tcpLen, srcIp, dstIp)
        raw[40 + 16] = ((csum ushr 8) and 0xFF).toByte()
        raw[40 + 17] = (csum and 0xFF).toByte()

        try {
            synchronized(yggSendLock) {
                mesh.sendPacket(raw, totalLen.toLong())
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed sending TCP packet buffer to mesh: ${e.javaClass.simpleName}")
        }
    }

    private fun sendTrackedPayload(
        session: StreamSession,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        sequence: Long,
        payload: ByteArray,
    ) {
        val pending = PendingSegment(dstIp.copyOf(), srcPort, dstPort, sequence, payload.copyOf())
        session.unacknowledged[sequence] = pending
        sendTcpPacket(localIp, pending.dstIp, srcPort, dstPort, sequence, session.seqRecv.get(), 0x18, pending.payload)
    }

    /**
     * Yggdrasil delivers IP packets, but has no TCP congestion control for
     * this user-space shim. A bounded flight window prevents a profile/avatar
     * burst from overrunning the real mesh path before ACKs can return.
     */
    private fun awaitSendWindow(session: StreamSession) {
        while (running.get() && !session.isClosed.get() && session.unacknowledged.size >= MAX_IN_FLIGHT_SEGMENTS) {
            try {
                Thread.sleep(SEND_WINDOW_WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun acknowledgeOutbound(session: StreamSession, acknowledgedSequence: Long) {
        if (acknowledgedSequence <= 0L) return
        session.unacknowledged.entries.removeIf { (_, segment) -> segment.endExclusive <= acknowledgedSequence }
    }

    private fun retransmitLoop() {
        while (running.get()) {
            try {
                Thread.sleep(RETRANSMIT_SCAN_MS)
                val now = System.currentTimeMillis()
                for (session in activeSessions.values) {
                    // Decision is extracted into retransmitDecision so the
                    // no-close-on-exhaustion contract is unit-testable without
                    // a live mesh. A closed stream is never retransmitted:
                    // RST/FIN/stop are the only teardown triggers.
                    if (retransmitDecision(session.isClosed.get()) == RetransmitAction.SKIP) {
                        if (session.unacknowledged.isEmpty()) {
                            activeSessions.remove(session.streamKey, session)
                        }
                        continue
                    }
                    val oldestEntry = session.unacknowledged.firstEntry() ?: continue
                    val segment = oldestEntry.value
                    val backoffFactor = 1L shl minOf(segment.retries, 5)
                    val rtoMs = minOf(retransmitBaseRtoMs * backoffFactor, retransmitMaxRtoMs)
                    if (now - segment.lastSentAtMs < rtoMs) continue
                    if (segment.retries == MAX_SEGMENT_RETRIES) {
                        // First exhaustion: the segment has survived 12 RTOs
                        // (~65s). This is a mesh route gap, not a dead
                        // session: the peer side retransmits symmetrically
                        // and cumulative ACKs recover the stream as soon as
                        // the route returns. Closing here used to tear down
                        // the authenticated session on every transient flap.
                        SafeLog.w(TAG, "Ygg stream segment seq=${segment.sequence} unacknowledged after $MAX_SEGMENT_RETRIES retries; continuing at capped RTO (mesh route gap)")
                    }
                    segment.retries += 1
                    segment.lastSentAtMs = now
                    SafeLog.d(TAG, "Retransmitting Ygg segment seq=${segment.sequence}, retry=${segment.retries}, rtoMs=$rtoMs")
                    sendTcpPacket(localIp, segment.dstIp, segment.srcPort, segment.dstPort, segment.sequence, session.seqRecv.get(), 0x18, segment.payload)
                }
            } catch (_: InterruptedException) {
                if (!running.get()) return
            }
        }
    }

    private fun sendUdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val raw = ByteBuffer.allocate(40 + udpLen).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x60000000); putShort(udpLen.toShort()); put(17); put(64)
            put(srcIp); put(dstIp); putShort(srcPort.toShort()); putShort(dstPort.toShort())
            putShort(udpLen.toShort()); putShort(0); put(payload)
        }.array()
        val csum = computeTransportChecksum(raw, 40, udpLen, srcIp, dstIp, 17)
        raw[46] = ((csum ushr 8) and 0xFF).toByte(); raw[47] = (csum and 0xFF).toByte()
        runCatching {
            synchronized(yggSendLock) {
                mesh.sendPacket(raw, raw.size.toLong())
            }
        }
    }

    private fun computeTcpChecksum(
        pkt: ByteArray,
        tcpOffset: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Int {
        var sum = 0L

        // Pseudo-header: Src IP (16 bytes) + Dst IP (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += tcpLen.toLong()
        sum += 6L // Protocol TCP

        // TCP Header + Payload
        for (i in tcpOffset until (tcpOffset + tcpLen - 1) step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        if (tcpLen % 2 != 0) {
            sum += (pkt[tcpOffset + tcpLen - 1].toInt() and 0xFF) shl 8
        }

        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeTransportChecksum(pkt: ByteArray, offset: Int, length: Int, srcIp: ByteArray, dstIp: ByteArray, protocol: Int): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += length.toLong() + protocol
        for (i in offset until offset + length - 1 step 2) sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        if (length % 2 != 0) sum += (pkt[offset + length - 1].toInt() and 0xFF) shl 8
        while ((sum ushr 16) > 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("Unexpected EOF in SOCKS5 request")
            offset += read
        }
    }

    private fun isYggdrasilAddress(address: ByteArray): Boolean =
        address.size == 16 && (address[0].toInt() and 0xFF) in 0x02..0x03

    companion object {
        /** Atomically reserves the sequence number occupied by an outbound SYN. */
        internal fun consumeSynSequence(sequence: AtomicLong): Long = sequence.getAndIncrement()

        /**
         * Resolves the local Yggdrasil node address (16-byte IPv6, 200::/7).
         * Falls back to the last known-good address when the live lookup is
         * transiently empty/invalid, and to an all-zero address only when
         * nothing has ever been resolved.
         */
        internal fun resolveLocalAddress(raw: String?, lastGood: ByteArray?): ByteArray {
            if (!raw.isNullOrBlank()) {
                try {
                    val parsed = InetAddress.getByName(raw.trim().removeSurrounding("[", "]")).address
                    if (parsed.size == 16) return parsed
                } catch (_: Throwable) {
                    // Fall through to the cached address.
                }
            }
            return lastGood ?: ByteArray(16)
        }

        /**
         * Maximum TCP segment payload size for Yggdrasil mesh link.
         * The raw IPv6 packet is further encapsulated by Yggdrasil. Keep 320
         * bytes of headroom below the IPv6 minimum MTU; 1200-byte payloads
         * were observed to black-hole on real mesh paths.
         */
        const val MAX_TCP_PAYLOAD = 900
        // Four segments (at most 3.6 KiB of application data) is deliberately
        // conservative for public Yggdrasil routes. It bounds buffering and
        // prevents an avatar/profile burst from creating a mesh-wide retry
        // storm before cumulative ACKs have returned.
        private const val MAX_IN_FLIGHT_SEGMENTS = 4
        private const val SEND_WINDOW_WAIT_MS = 5L
        private const val LOCAL_CORE_CONNECT_TIMEOUT_MS = 5_000
        private const val RETRANSMIT_SCAN_MS = 150L
        private const val INITIAL_RTO_MS = 600L
        private const val MAX_RTO_MS = 8_000L
        internal const val MAX_SEGMENT_RETRIES = 12

        /** How long start() waits for a valid node address before accepting traffic. */
        internal const val ADDRESS_DISCOVERY_TIMEOUT_MS = 5_000L
        private const val ADDRESS_POLL_INTERVAL_MS = 50L

        fun segmentPayload(data: ByteArray, maxSegSize: Int = MAX_TCP_PAYLOAD): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            if (data.size <= maxSegSize) return listOf(data)
            val segments = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < data.size) {
                val len = minOf(data.size - offset, maxSegSize)
                segments.add(data.copyOfRange(offset, offset + len))
                offset += len
            }
            return segments
        }
    }
}
