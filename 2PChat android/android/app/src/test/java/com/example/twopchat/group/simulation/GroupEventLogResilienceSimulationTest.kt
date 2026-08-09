package com.example.twopchat.group.simulation

import com.example.twopchat.group.model.DeviceId
import com.example.twopchat.group.model.GroupEventKind
import com.example.twopchat.group.model.ReplicaCandidate
import com.example.twopchat.group.model.ReplicaPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEventLogResilienceSimulationTest {
    @Test
    fun longLivedGroupConvergesAcrossRepeatedOfflineGenerations() {
        val simulation = DeterministicGroupNetworkSimulator(
            participantCount = PARTICIPANTS,
            seed = 91_337L,
            faults = NetworkFaultProfile(lossEvery = 5, duplicateEvery = 7, maxDelayTicks = 23),
        )

        repeat(12) { generation ->
            val offline = simulation.participantIds.filterIndexed { index, _ ->
                (index + generation) % 5 == 0
            }
            offline.forEach { simulation.setOnline(it, false) }
            simulation.participantIds.forEachIndexed { index, participant ->
                repeat(3) { eventInGeneration ->
                    simulation.appendEvent(
                        author = participant,
                        kind = if ((generation + index + eventInGeneration) % 4 == 0) {
                            GroupEventKind.MEDIA_MESSAGE
                        } else {
                            GroupEventKind.TEXT_MESSAGE
                        },
                        payload = "generation=$generation;participant=$index;event=$eventInGeneration",
                    )
                }
            }
            simulation.runUntilIdle()
            offline.forEach { simulation.setOnline(it, true) }
            simulation.gossipRound()
            simulation.runUntilIdle()
        }

        simulation.participantIds.forEach { simulation.setOnline(it, true) }
        val rounds = simulation.gossipUntilConverged(maxRounds = 20)

        assertTrue("long-lived group did not converge", rounds in 0..20)
        assertTrue(simulation.hasConverged())
        val expected = PARTICIPANTS * 12 * 3
        simulation.participantIds.forEach { participant ->
            assertEquals(expected, simulation.eventCount(participant))
        }
    }

    @Test
    fun twentyParticipantsConvergeAfterOfflineLossDuplicateReorderAndDelay() {
        val simulation = newFaultySimulation()
        val temporarilyOffline = simulation.participantIds.slice(4..9).toSet()
        temporarilyOffline.forEach { simulation.setOnline(it, false) }

        simulation.participantIds.forEachIndexed { participantIndex, participant ->
            repeat(EVENTS_PER_PARTICIPANT) { sequence ->
                simulation.appendEvent(
                    author = participant,
                    kind = if (sequence % 3 == 0) {
                        GroupEventKind.MEDIA_MESSAGE
                    } else {
                        GroupEventKind.TEXT_MESSAGE
                    },
                    payload = "participant=$participantIndex;sequence=$sequence",
                )
            }
        }

        // Let initial frames expire while six authors/receivers are offline.
        simulation.runUntilIdle()
        assertFalse(simulation.hasConverged())
        temporarilyOffline.forEach { simulation.setOnline(it, true) }

        val rounds = simulation.gossipUntilConverged(maxRounds = 12)

        assertTrue("anti-entropy did not converge", rounds in 1..12)
        assertTrue(simulation.hasConverged())
        val expectedEventCount = PARTICIPANTS * EVENTS_PER_PARTICIPANT
        val canonical = simulation.canonicalEventIds(simulation.participantIds.first())
        simulation.participantIds.forEach { participant ->
            assertEquals(expectedEventCount, simulation.eventCount(participant))
            assertEquals(canonical, simulation.canonicalEventIds(participant))
        }

        // Every author feed remains gap-free even though network arrival order was not.
        simulation.participantIds.forEach { author ->
            val sequences = simulation.events(simulation.participantIds.first())
                .filter { it.authorDeviceId == author }
                .map { it.senderSequence }
                .sorted()
            assertEquals((0L until EVENTS_PER_PARTICIPANT.toLong()).toList(), sequences)
        }

        val stats = simulation.stats()
        assertTrue("loss was not exercised", stats.lostAttempts > 0)
        assertTrue("offline delivery was not exercised", stats.offlineDrops > 0)
        assertTrue("duplication was not exercised", stats.duplicateFramesInjected > 0)
        assertTrue("duplicate idempotency was not exercised", stats.duplicateDeliveriesIgnored > 0)
        assertTrue("reordering was not exercised", stats.outOfOrderDeliveries > 0)
        assertTrue("delay was not exercised", stats.maxObservedDelayTicks > 1)
    }

    @Test
    fun eventAuthoredOfflineIsRecoveredAfterReconnect() {
        val simulation = DeterministicGroupNetworkSimulator(
            participantCount = 20,
            seed = 4_242L,
            faults = NetworkFaultProfile(lossEvery = 3, duplicateEvery = 4, maxDelayTicks = 11),
        )
        val offlineAuthor = simulation.participantIds.last()
        simulation.setOnline(offlineAuthor, false)
        val delayed = simulation.appendEvent(
            author = offlineAuthor,
            payload = "created with no route",
        )
        repeat(20) { index ->
            simulation.appendEvent(
                author = simulation.participantIds[index % (PARTICIPANTS - 1)],
                payload = "online-$index",
            )
        }
        simulation.runUntilIdle()

        simulation.participantIds.dropLast(1).forEach { participant ->
            assertFalse(delayed.id in simulation.canonicalEventIds(participant))
        }

        simulation.setOnline(offlineAuthor, true)
        val rounds = simulation.gossipUntilConverged(maxRounds = 10)

        assertTrue(rounds in 1..10)
        simulation.participantIds.forEach { participant ->
            assertTrue(delayed.id in simulation.canonicalEventIds(participant))
        }
    }

    @Test
    fun identicalSeedAndActionsProduceIdenticalLogsAndFaultStatistics() {
        val first = runDeterministicScenario()
        val second = runDeterministicScenario()

        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
    }

    @Test
    fun hrwReplicaPlanningScalesDeterministicallyToTenThousandParticipants() {
        val candidates = (0 until 10_000).map { index ->
            ReplicaCandidate(
                deviceId = DeviceId("scale-device-${index.toString().padStart(5, '0')}"),
                available = index % 101 != 0,
            )
        }
        val selected = ReplicaPlanner.selectReplicas(
            objectKey = "large-group-segment",
            candidates = candidates,
            replicaCount = 7,
        )
        val selectedFromReverseOrder = ReplicaPlanner.selectReplicas(
            objectKey = "large-group-segment",
            candidates = candidates.asReversed(),
            replicaCount = 7,
        )

        assertEquals(7, selected.size)
        assertEquals(7, selected.distinct().size)
        assertEquals(selected, selectedFromReverseOrder)
        assertTrue(selected.all { selectedId ->
            candidates.single { it.deviceId == selectedId }.available
        })

        val failed = selected.take(2).toSet()
        val repaired = ReplicaPlanner.replaceFailedReplicas(
            objectKey = "large-group-segment",
            candidates = candidates,
            replicaCount = 7,
            failedDeviceIds = failed,
        )
        assertEquals(7, repaired.size)
        assertTrue(repaired.none { it in failed })
        assertEquals(selected.toSet() - failed, repaired.toSet().intersect(selected.toSet()))

        // Multiple objects should not collapse onto one fixed seven-node set.
        val placementSpread = (0 until 20)
            .flatMap { segment ->
                ReplicaPlanner.selectReplicas("segment-$segment", candidates, replicaCount = 7)
            }
            .toSet()
        assertTrue("HRW placement did not spread load", placementSpread.size > 50)
    }

    private fun runDeterministicScenario(): Pair<List<List<String>>, SimulationStats> {
        val simulation = newFaultySimulation()
        simulation.setOnline(2, false)
        simulation.setOnline(7, false)
        simulation.participantIds.forEachIndexed { index, participant ->
            repeat(2) { sequence ->
                simulation.appendEvent(participant, payload = "$index:$sequence")
            }
        }
        simulation.runUntilIdle()
        simulation.setOnline(2, true)
        simulation.setOnline(7, true)
        assertTrue(simulation.gossipUntilConverged(12) >= 0)
        return simulation.participantIds.map { participant ->
            simulation.canonicalEventIds(participant).map { it.value }
        } to simulation.stats()
    }

    private fun newFaultySimulation() = DeterministicGroupNetworkSimulator(
        participantCount = PARTICIPANTS,
        seed = 77_777L,
        faults = NetworkFaultProfile(
            lossEvery = 4,
            duplicateEvery = 5,
            maxDelayTicks = 17,
        ),
    )

    private companion object {
        const val PARTICIPANTS = 20
        const val EVENTS_PER_PARTICIPANT = 5
    }
}
