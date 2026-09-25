package com.example.twopchat.yggdrasil

import mobile.Yggdrasil

/**
 * Mesh transport seam for [YggdrasilUserSpaceStack]. Production runs
 * [GomobileMeshTransport] over the gomobile node; tests use an in-memory
 * fake so two stacks can be driven inside one JVM.
 */
internal interface MeshTransport {
    /**
     * The node address string. May return null (or throw) while the node is
     * still deriving its address; callers must fall back to the last known
     * good value.
     */
    fun addressString(): String?

    /** Sends one mesh packet. May throw; the caller decides. */
    fun sendPacket(data: ByteArray, length: Long)

    /** Copies one mesh packet into [buffer]; returns 0 or negative if none. */
    fun receivePacket(buffer: ByteArray): Int

    /** Releases transport resources. Ownership stays with the stack owner. */
    fun close()
}

internal class GomobileMeshTransport(private val ygg: Yggdrasil) : MeshTransport {
    override fun addressString(): String? = runCatching { ygg.addressString }.getOrNull()

    override fun sendPacket(data: ByteArray, length: Long) {
        ygg.sendBuffer(data, length)
    }
    override fun receivePacket(buffer: ByteArray): Int = ygg.recvBuffer(buffer).toInt()
    override fun close() {}
}
