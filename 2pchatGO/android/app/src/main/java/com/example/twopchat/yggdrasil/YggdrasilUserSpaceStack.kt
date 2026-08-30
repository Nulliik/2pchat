package com.example.twopchat.yggdrasil

import android.util.Log
import mobile.Yggdrasil
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val TAG = "YggUserStack"

/**
 * Pure Kotlin User-Space TCP/IP Stack & SOCKS5 Server for Yggdrasil Proxy Mode.
 * Translates SOCKS5 connections to raw IPv6/TCP packets on the mesh without requiring VPN permissions.
 */
class YggdrasilUserSpaceStack(
    private val ygg: Yggdrasil,
    private val socksPort: Int = 9053,
    private val localTargetPort: Int = 50001
) {
    private val running = AtomicBoolean(false)
    private var socksServer: ServerSocket? = null
    private var udpRelaySocket: DatagramSocket? = null
    private var workerThreads = mutableListOf<Thread>()

    private val localIp: ByteArray by lazy {
        try {
            Inet6Address.getByName(ygg.addressString).address
        } catch (_: Throwable) {
            ByteArray(16)
        }
    }

    private class StreamSession(
        val streamKey: String,
        val remoteIp: ByteArray,
        val remotePort: Int,
        val localPort: Int,
        var clientSocket: Socket? = null,
        var clientIn: InputStream? = null,
        var clientOut: OutputStream? = null,
        var seqSent: AtomicLong = AtomicLong(1000L),
        var seqRecv: AtomicLong = AtomicLong(0L),
        var isEstablished: AtomicBoolean = AtomicBoolean(false),
        var isClosed: AtomicBoolean = AtomicBoolean(false)
    )

    private val activeSessions = ConcurrentHashMap<String, StreamSession>()
    private val pendingHandshakes = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>>()
    private val portCounter = AtomicInteger(40000)
    private data class UdpRelaySession(val client: InetSocketAddress)
    private val udpSessions = ConcurrentHashMap<Int, UdpRelaySession>()
    // BEP-15 tracker connect IDs are tied to the UDP source endpoint. Keep a
    // stable mesh-side port for every native client socket across its requests.
    private val udpClientPorts = ConcurrentHashMap<String, Int>()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        // 1. Start SOCKS5 Listener on 127.0.0.1:socksPort
        try {
            socksServer = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort))
            }
            Log.i(TAG, "SOCKS5 proxy server successfully bound on 127.0.0.1:$socksPort")
            val tSocks = thread(name = "Ygg-SOCKS5-Acceptor") {
                acceptSocksLoop()
            }
            workerThreads.add(tSocks)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind SOCKS5 server on port $socksPort", e)
        }

        try {
            udpRelaySocket = DatagramSocket(InetSocketAddress("127.0.0.1", socksPort + 1))
            workerThreads.add(thread(name = "Ygg-UDP-Relay") { udpRelayLoop() })
            Log.i(TAG, "Yggdrasil UDP relay bound on 127.0.0.1:${socksPort + 1}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind Yggdrasil UDP relay", e)
        }

        // 2. Start Mesh Packet Receiver
        val tRecv = thread(name = "Ygg-Mesh-Receiver") {
            meshReceiveLoop()
        }
        workerThreads.add(tRecv)

        Log.i(TAG, "Yggdrasil User-Space Proxy Stack started on 127.0.0.1:$socksPort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        runCatching { socksServer?.close() }
        runCatching { udpRelaySocket?.close() }
        activeSessions.values.forEach { sess ->
            runCatching { sess.clientSocket?.close() }
        }
        activeSessions.clear()
        pendingHandshakes.values.forEach { it.complete(false) }
        pendingHandshakes.clear()
        udpSessions.clear()
        udpClientPorts.clear()

        workerThreads.forEach { it.interrupt() }
        workerThreads.clear()
        Log.i(TAG, "Yggdrasil User-Space Proxy Stack stopped")
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

            val sessionKey = "${bytesToHex(targetIpBytes)}:$targetPort:$localPort"
            val session = StreamSession(
                streamKey = sessionKey,
                remoteIp = targetIpBytes,
                remotePort = targetPort,
                localPort = localPort,
                clientSocket = client,
                clientIn = input,
                clientOut = output
            )
            activeSessions[sessionKey] = session

            val handshakeFuture = java.util.concurrent.CompletableFuture<Boolean>()
            pendingHandshakes[sessionKey] = handshakeFuture

            // Send TCP SYN packet
            sendTcpPacket(
                srcIp = localIp,
                dstIp = targetIpBytes,
                srcPort = localPort,
                dstPort = targetPort,
                seq = session.seqSent.get(),
                ack = 0L,
                flags = 0x02 // SYN
            )

            val success = try {
                handshakeFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {
                false
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

                val chunk = buf.copyOf(read)
                val currentSeq = session.seqSent.getAndAdd(read.toLong())
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = targetIpBytes,
                    srcPort = localPort,
                    dstPort = targetPort,
                    seq = currentSeq,
                    ack = session.seqRecv.get(),
                    flags = 0x18, // PSH | ACK
                    payload = chunk
                )
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
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun meshReceiveLoop() {
        val buf = ByteArray(65535)
        while (running.get()) {
            try {
                val len = ygg.recvBuffer(buf)
                if (len <= 0) {
                    Thread.sleep(10)
                    continue
                }
                if (len < 40) continue // Minimum IPv6 header size

                handleInboundPacket(buf, len.toInt())
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
        if (outSession != null) {
            if (isSyn && isAck) {
                outSession.seqRecv.set(seq + 1)
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
                outSession.seqRecv.addAndGet(payloadLen.toLong())
                runCatching {
                    outSession.clientOut?.write(pkt, payloadStart, payloadLen)
                    outSession.clientOut?.flush()
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
            thread(name = "Ygg-Inbound-Worker") {
                handleInboundMeshConnection(srcIp, srcPort, dstPort, seq)
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
            val localSocket = Socket("127.0.0.1", localTargetPort)
            val session = StreamSession(
                streamKey = sessionKey,
                remoteIp = srcIp,
                remotePort = srcPort,
                localPort = dstPort,
                clientSocket = localSocket,
                clientIn = localSocket.getInputStream(),
                clientOut = localSocket.getOutputStream(),
                seqSent = AtomicLong(5000L),
                seqRecv = AtomicLong(initialSeq + 1),
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

                val chunk = buf.copyOf(read)
                val currentSeq = session.seqSent.getAndAdd(read.toLong())
                sendTcpPacket(
                    srcIp = localIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = currentSeq,
                    ack = session.seqRecv.get(),
                    flags = 0x18, // PSH | ACK
                    payload = chunk
                )
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
            ygg.sendBuffer(raw, totalLen.toLong())
        } catch (_: Throwable) {}
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
        runCatching { ygg.sendBuffer(raw, raw.size.toLong()) }
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
}
