package com.example.twopchat.group.simulation

import com.example.twopchat.group.model.ConversationId
import com.example.twopchat.group.model.DeviceId
import com.example.twopchat.group.model.EventId
import com.example.twopchat.group.model.GroupEvent
import com.example.twopchat.group.model.GroupEventKind
import com.example.twopchat.group.model.GroupEventOrder
import com.example.twopchat.group.model.GroupEventState
import com.example.twopchat.group.model.HybridLogicalClock
import com.example.twopchat.group.model.UserId
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.PriorityQueue

data class NetworkFaultProfile(
    /** Zero disables deterministic packet loss; otherwise every Nth attempt is lost. */
    val lossEvery: Int = 0,
    /** Zero disables duplication; otherwise every Nth attempt gets one duplicate frame. */
    val duplicateEvery: Int = 0,
    val maxDelayTicks: Int = 1,
) {
    init {
        require(lossEvery == 0 || lossEvery >= 2) {
            "lossEvery must be zero or at least two so retry can make progress"
        }
        require(duplicateEvery == 0 || duplicateEvery >= 2) {
            "duplicateEvery must be zero or at least two"
        }
        require(maxDelayTicks >= 1) { "max delay must be positive" }
    }
}

data class SimulationStats(
    val sendAttempts: Long,
    val lostAttempts: Long,
    val offlineDrops: Long,
    val duplicateFramesInjected: Long,
    val framesDelivered: Long,
    val duplicateDeliveriesIgnored: Long,
    val outOfOrderDeliveries: Long,
    val maxObservedDelayTicks: Int,
)

/**
 * A deterministic, single-threaded event-log network used by local JVM tests.
 *
 * Every node stores immutable events keyed by event id. Transport faults affect
 * frames, never the log. Repeated [gossipRound] calls are an anti-entropy pass:
 * online peers exchange every event missing from the receiver, so reconnecting
 * nodes eventually converge despite loss, duplication, delay and reordering.
 */
class DeterministicGroupNetworkSimulator(
    participantCount: Int,
    private val seed: Long,
    private val faults: NetworkFaultProfile,
) {
    init {
        require(participantCount >= 2) { "a group simulation needs at least two participants" }
    }

    val participantIds: List<DeviceId> =
        (0 until participantCount).map { DeviceId("device-${it.toString().padStart(5, '0')}") }

    private val groupId = ConversationId("simulation-group")
    private val nodes = participantIds.associateWith { deviceId ->
        Node(
            deviceId = deviceId,
            userId = UserId("user-${deviceId.value.removePrefix("device-")}"),
        )
    }
    private val queue = PriorityQueue<Frame>(FRAME_COMPARATOR)
    private val attempts = mutableMapOf<TransferKey, Int>()
    private var currentTick = 0L
    private var nextFrameOrdinal = 0L
    private var sendAttempts = 0L
    private var lostAttempts = 0L
    private var offlineDrops = 0L
    private var duplicateFramesInjected = 0L
    private var framesDelivered = 0L
    private var duplicateDeliveriesIgnored = 0L
    private var outOfOrderDeliveries = 0L
    private var maxObservedDelayTicks = 0

    fun setOnline(deviceId: DeviceId, online: Boolean) {
        node(deviceId).online = online
    }

    fun setOnline(participantIndex: Int, online: Boolean) {
        setOnline(participantIds[participantIndex], online)
    }

    fun isOnline(deviceId: DeviceId): Boolean = node(deviceId).online

    fun appendEvent(
        author: DeviceId,
        kind: GroupEventKind = GroupEventKind.TEXT_MESSAGE,
        payload: String,
        broadcast: Boolean = true,
    ): GroupEvent {
        val node = node(author)
        val sequence = node.nextSenderSequence++
        node.clock = node.clock.tick(currentTick)
        val event = GroupEvent(
            groupId = groupId,
            id = EventId("${author.value}:$sequence"),
            authorUserId = node.userId,
            authorDeviceId = author,
            senderSequence = sequence,
            epoch = 1L,
            timestamp = node.clock,
            kind = kind,
            state = GroupEventState.REPLICATED,
            payloadDigest = "sha256:${hash64(payload).toULong().toString(16)}",
        )
        node.appendLocal(event)
        if (broadcast) {
            participantIds.asSequence()
                .filterNot { it == author }
                .forEach { receiver -> scheduleMissing(author, receiver, event) }
        }
        return event
    }

    /**
     * Schedules a full-mesh anti-entropy round among online nodes. Missing-event
     * checks happen before queued frames are delivered, intentionally allowing
     * duplicate in-flight frames just like concurrent real peers.
     */
    fun gossipRound() {
        val onlineNodes = participantIds.filter(::isOnline)
        onlineNodes.forEach { sender ->
            onlineNodes.asSequence()
                .filterNot { it == sender }
                .forEach { receiver ->
                    val receiverLog = node(receiver).log
                    node(sender).log.values
                        .asSequence()
                        .filterNot { it.id in receiverLog }
                        .forEach { event -> scheduleMissing(sender, receiver, event) }
                }
        }
    }

    fun runUntilIdle(maxFrames: Int = 2_000_000) {
        var processed = 0
        while (queue.isNotEmpty()) {
            check(processed++ < maxFrames) { "simulation exceeded the frame safety limit" }
            val frame = queue.remove()
            currentTick = maxOf(currentTick, frame.deliverAtTick)
            val senderNode = node(frame.sender)
            val receiverNode = node(frame.receiver)
            if (!senderNode.online || !receiverNode.online) {
                offlineDrops++
                continue
            }
            framesDelivered++
            when (receiverNode.receive(frame.event)) {
                ReceiveResult.DUPLICATE -> duplicateDeliveriesIgnored++
                ReceiveResult.OUT_OF_ORDER -> outOfOrderDeliveries++
                ReceiveResult.APPENDED -> Unit
            }
        }
    }

    fun gossipUntilConverged(maxRounds: Int): Int {
        require(maxRounds >= 0) { "max rounds must not be negative" }
        repeat(maxRounds) { round ->
            if (hasConverged()) return round
            gossipRound()
            runUntilIdle()
        }
        return if (hasConverged()) maxRounds else -1
    }

    fun hasConverged(): Boolean {
        val first = nodes.getValue(participantIds.first()).log.keys
        return nodes.values.all { it.log.keys == first }
    }

    fun eventCount(deviceId: DeviceId): Int = node(deviceId).log.size

    fun canonicalEventIds(deviceId: DeviceId): List<EventId> =
        node(deviceId).log.values
            .sortedBy(GroupEvent::order)
            .map(GroupEvent::id)

    fun events(deviceId: DeviceId): List<GroupEvent> =
        node(deviceId).log.values.toList()

    fun stats(): SimulationStats = SimulationStats(
        sendAttempts = sendAttempts,
        lostAttempts = lostAttempts,
        offlineDrops = offlineDrops,
        duplicateFramesInjected = duplicateFramesInjected,
        framesDelivered = framesDelivered,
        duplicateDeliveriesIgnored = duplicateDeliveriesIgnored,
        outOfOrderDeliveries = outOfOrderDeliveries,
        maxObservedDelayTicks = maxObservedDelayTicks,
    )

    private fun scheduleMissing(sender: DeviceId, receiver: DeviceId, event: GroupEvent) {
        val transferKey = TransferKey(sender, receiver, event.id)
        val attempt = attempts.getOrDefault(transferKey, 0)
        attempts[transferKey] = attempt + 1
        sendAttempts++

        if (!node(sender).online) {
            offlineDrops++
            return
        }

        val stableBucket = positiveMod(
            hash64("$seed|loss|${sender.value}|${receiver.value}|${event.id.value}"),
            faults.lossEvery,
        )
        if (faults.lossEvery > 0 && (stableBucket + attempt) % faults.lossEvery == 0) {
            lostAttempts++
            return
        }

        val delay = 1 + positiveMod(
            hash64("$seed|delay|${sender.value}|${receiver.value}|${event.id.value}|$attempt"),
            faults.maxDelayTicks,
        )
        maxObservedDelayTicks = maxOf(maxObservedDelayTicks, delay)
        enqueueFrame(sender, receiver, event, delay)

        val duplicateBucket = positiveMod(
            hash64("$seed|duplicate|${sender.value}|${receiver.value}|${event.id.value}"),
            faults.duplicateEvery,
        )
        if (faults.duplicateEvery > 0 &&
            (duplicateBucket + attempt) % faults.duplicateEvery == 0
        ) {
            duplicateFramesInjected++
            val duplicateDelay = 1 + positiveMod(
                hash64("$seed|duplicate-delay|${event.id.value}|$attempt"),
                faults.maxDelayTicks,
            )
            maxObservedDelayTicks = maxOf(maxObservedDelayTicks, duplicateDelay)
            enqueueFrame(sender, receiver, event, duplicateDelay)
        }
    }

    private fun enqueueFrame(
        sender: DeviceId,
        receiver: DeviceId,
        event: GroupEvent,
        delay: Int,
    ) {
        queue += Frame(
            sender = sender,
            receiver = receiver,
            event = event,
            deliverAtTick = currentTick + delay,
            tieBreaker = hash64(
                "$seed|tie|${sender.value}|${receiver.value}|${event.id.value}|$nextFrameOrdinal"
            ),
            ordinal = nextFrameOrdinal++,
        )
    }

    private fun node(deviceId: DeviceId): Node =
        checkNotNull(nodes[deviceId]) { "unknown participant: $deviceId" }

    private fun positiveMod(value: Long, modulo: Int): Int {
        if (modulo == 0) return 0
        return java.lang.Long.remainderUnsigned(value, modulo.toLong()).toInt()
    }

    private fun hash64(input: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }

    private data class TransferKey(
        val sender: DeviceId,
        val receiver: DeviceId,
        val eventId: EventId,
    )

    private data class Frame(
        val sender: DeviceId,
        val receiver: DeviceId,
        val event: GroupEvent,
        val deliverAtTick: Long,
        val tieBreaker: Long,
        val ordinal: Long,
    )

    private class Node(
        val deviceId: DeviceId,
        val userId: UserId,
    ) {
        var online: Boolean = true
        var nextSenderSequence: Long = 0L
        var clock = HybridLogicalClock(0L, 0, deviceId.value)
        val log = LinkedHashMap<EventId, GroupEvent>()
        private var greatestReceivedOrder: GroupEventOrder? = null

        fun appendLocal(event: GroupEvent) {
            check(log.putIfAbsent(event.id, event) == null) { "local event id collision" }
        }

        fun receive(event: GroupEvent): ReceiveResult {
            if (event.id in log) return ReceiveResult.DUPLICATE
            log[event.id] = event
            val previousGreatest = greatestReceivedOrder
            val result = if (previousGreatest != null && event.order < previousGreatest) {
                ReceiveResult.OUT_OF_ORDER
            } else {
                ReceiveResult.APPENDED
            }
            if (previousGreatest == null || event.order > previousGreatest) {
                greatestReceivedOrder = event.order
            }
            clock = clock.observe(event.timestamp, nowMs = event.timestamp.physicalTimeMs)
            return result
        }
    }

    private enum class ReceiveResult {
        APPENDED,
        DUPLICATE,
        OUT_OF_ORDER,
    }

    private companion object {
        val FRAME_COMPARATOR = Comparator<Frame> { left, right ->
            val byTick = left.deliverAtTick.compareTo(right.deliverAtTick)
            if (byTick != 0) {
                byTick
            } else {
                val byTie = java.lang.Long.compareUnsigned(left.tieBreaker, right.tieBreaker)
                if (byTie != 0) byTie else left.ordinal.compareTo(right.ordinal)
            }
        }
    }
}
