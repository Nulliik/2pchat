package com.example.twopchat.group.runtime

/** A bounded connected ring complements durable replicas; every first-time receiver relays. */
internal object GroupRelayPlanner {
    fun successors(localId: String, connectedIds: Collection<String>, fanout: Int = 3): List<String> {
        require(fanout > 0)
        val ring = (connectedIds + localId).distinct().sorted()
        val index = ring.indexOf(localId)
        return (1..minOf(fanout, ring.size - 1)).map { ring[(index + it) % ring.size] }
    }
}
