package com.example.twopchat.group.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroupRuntimeCancellationTest {
    private data class Record(val id: Int, val receivedAt: Long = 0L)

    private fun buffer(capacity: Int = 4) = GroupPendingBuffer<Record>(
        capacity, 100L, { it.id }, { it.receivedAt },
    )

    @Test
    fun cancellationRetainsCurrentAndUnprocessedRecords() = runBlocking {
        val pending = buffer()
        (1..3).forEach { pending.add(Record(it), 0L) }
        val entered = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.drain(0L, { true }, onFailure = { _, _ -> fail("Cancellation rejected") }) {
                if (it.id == 2) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                processed.add(it.id)
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf(1), processed)
        assertEquals(2, pending.size())
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { processed.add(it.id) }
        assertEquals(listOf(1, 2, 3), processed)
        assertEquals(0, pending.size())
    }

    @Test
    fun concurrentDrainCannotStealClaimedRecordsOrEvictThem() = runBlocking {
        val pending = buffer(2)
        pending.add(Record(1), 0L)
        pending.add(Record(2), 0L)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
                CompletableDeferred<Unit>().await()
            }
        }
        assertEquals(false, pending.add(Record(3), 200L))
        pending.drain(200L, { true }, onFailure = { _, e -> throw e }) {
            fail("Another drain stole a claimed record")
        }
        assertEquals(2, pending.size())
        job.cancelAndJoin()
        val remaining = mutableListOf<Int>()
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { remaining.add(it.id) }
        assertEquals(listOf(1, 2), remaining)
    }

    @Test
    fun rebufferedRecordSurvivesAndNestedDrainDoesNotReenterIt() = runBlocking {
        val pending = buffer()
        pending.add(Record(1), 0L)
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { record ->
            pending.add(record, 0L)
            pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
                fail("Nested drain reentered a claimed record")
            }
        }
        assertEquals(1, pending.size())
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { }
        assertEquals(0, pending.size())
    }

    @Test
    fun ordinaryRejectionContinuesButFatalErrorsPropagate() = runBlocking {
        val pending = buffer()
        (1..3).forEach { pending.add(Record(it), 0L) }
        val rejected = mutableListOf<Int>()
        val fatal = AssertionError("fatal")
        try {
            pending.drain(0L, { true }, onFailure = { record, _ -> rejected.add(record.id) }) {
                if (it.id == 1) throw SecurityException("invalid")
                throw fatal
            }
            fail("Fatal error was swallowed")
        } catch (actual: AssertionError) {
            assertEquals("fatal", actual.message)
        }
        assertEquals(listOf(1), rejected)
        assertEquals(2, pending.size())
    }

    @Test
    fun laterCallbackCanDrainEarlierRequeuedDependency() = runBlocking {
        val pending = buffer()
        pending.add(Record(1), 0L)
        pending.add(Record(2), 0L)
        val processed = mutableListOf<Int>()
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { record ->
            if (record.id == 1) {
                pending.add(record, 0L)
            } else {
                pending.drain(0L, { it.id == 1 }, onFailure = { _, e -> throw e }) {
                    processed.add(it.id)
                }
                processed.add(record.id)
            }
        }
        assertEquals(listOf(1, 2), processed)
        assertEquals(0, pending.size())
    }

    @Test
    fun finishingOlderBatchDoesNotReleaseAnotherDrainersClaim() = runBlocking {
        val pending = buffer()
        pending.add(Record(1), 0L)
        pending.add(Record(2), 0L)
        val laterEntered = CompletableDeferred<Unit>()
        val finishOlder = CompletableDeferred<Unit>()
        val older = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
                if (it.id == 1) {
                    pending.add(it, 0L)
                } else {
                    laterEntered.complete(Unit)
                    finishOlder.await()
                }
            }
        }
        laterEntered.await()
        val newer = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.drain(0L, { it.id == 1 }, onFailure = { _, e -> throw e }) {
                CompletableDeferred<Unit>().await()
            }
        }
        finishOlder.complete(Unit)
        older.join()
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
            fail("Older batch released another drainer's claim")
        }
        newer.cancelAndJoin()
        assertEquals(1, pending.size())
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) { }
        assertEquals(0, pending.size())
    }

    @Test
    fun replacementDuringSuccessfulCallbackRemainsPending() = runBlocking {
        val pending = GroupPendingBuffer<Record>(
            1, null, { it.id }, { it.receivedAt },
            GroupPendingBuffer.DuplicatePolicy.REPLACE,
        )
        pending.add(Record(1), 0L)
        pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
            pending.add(Record(1, 10L), 10L)
            assertEquals(Record(1), it)
        }
        val processed = mutableListOf<Record>()
        pending.drain(200L, { true }, onFailure = { _, e -> throw e }) { processed.add(it) }
        assertEquals(listOf(Record(1, 10L)), processed)
    }

    @Test
    fun replacementDuringClaimSurvivesRejectionAndDoesNotExpire() = runBlocking {
        val pending = GroupPendingBuffer<Record>(
            1, null, { it.id }, { it.receivedAt },
            GroupPendingBuffer.DuplicatePolicy.REPLACE,
        )
        pending.add(Record(1), 0L)
        pending.drain(0L, { true }, onFailure = { _, _ -> }) {
            assertTrue(pending.add(Record(1, 10L), 10L))
            throw SecurityException("old placeholder rejected")
        }
        val processed = mutableListOf<Record>()
        pending.drain(Long.MAX_VALUE, { true }, onFailure = { _, e -> throw e }) { processed.add(it) }
        assertEquals(listOf(Record(1, 10L)), processed)
        assertEquals(0, pending.size())
    }

    @Test
    fun cancellationRetainsReplacementAndUnprocessedWork() = runBlocking {
        val pending = GroupPendingBuffer<Record>(
            2, null, { it.id }, { it.receivedAt },
            GroupPendingBuffer.DuplicatePolicy.REPLACE,
        )
        pending.add(Record(1), 0L)
        pending.add(Record(2), 0L)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.drain(0L, { true }, onFailure = { _, e -> throw e }) {
                pending.add(Record(1, 5L), 5L)
                CompletableDeferred<Unit>().await()
            }
        }
        job.cancelAndJoin()
        val processed = mutableListOf<Record>()
        pending.drain(200L, { true }, onFailure = { _, e -> throw e }) { processed.add(it) }
        assertEquals(listOf(Record(2), Record(1, 5L)), processed)
    }

    @Test
    fun replacementBeforeDrainWinsWithoutEvictingAnotherId() = runBlocking {
        val pending = GroupPendingBuffer<Record>(
            2, null, { it.id }, { it.receivedAt },
            GroupPendingBuffer.DuplicatePolicy.REPLACE,
        )
        pending.add(Record(1), 0L)
        pending.add(Record(2), 0L)
        assertTrue(pending.add(Record(1, 5L), 5L))
        val processed = mutableListOf<Record>()
        pending.drain(200L, { true }, onFailure = { _, e -> throw e }) { processed.add(it) }
        assertEquals(listOf(Record(2), Record(1, 5L)), processed)
    }

    @Test
    fun ttlCapacityDeduplicationAndOrderingRemainBounded() = runBlocking {
        val pending = buffer(2)
        pending.add(Record(1, -101L), 0L)
        pending.add(Record(2), 0L)
        pending.add(Record(3), 0L)
        pending.add(Record(3), 0L)
        assertEquals(2, pending.size())
        pending.add(Record(4), 0L)
        val processed = mutableListOf<Int>()
        pending.drain(
            0L, { true }, comparator = compareByDescending { it.id },
            onFailure = { _, e -> throw e },
        ) { processed.add(it.id) }
        assertEquals(listOf(4, 3), processed)
    }
}
