package com.example.twopchat.group.storage

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in SQLCipher storage experiment, not an end-to-end sync benchmark.
 * Run only in a separate QA application id. No production DB, network, or protocol changes.
 * Keeps production encryption/PRAGMAs intact; setup, input construction and assertions are untimed.
 */
@RunWith(AndroidJUnit4::class)
class GroupIngestBenchmarkInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val groupId = "synthetic-ingest-benchmark"

    private fun requireIsolatedApplication() {
        org.junit.Assume.assumeTrue(
            "Run with -PgroupQaApplicationId=com.example.twopchat.batchbench",
            context.packageName == "com.example.twopchat.batchbench",
        )
    }

    private fun createGroup(db: GroupDatabaseHelper) {
        db.createGroup(
            StoredGroup(groupId, "Synthetic benchmark", localDeviceId = "owner",
                ownerDeviceId = "owner", currentEpoch = 1, createdAtMs = 1),
            members = listOf(StoredGroupMember(groupId, "owner", "owner", "Synthetic",
                "OWNER", Long.MAX_VALUE, joinedEpoch = 1, createdAtMs = 1)),
            initialEpochKey = StoredGroupEpochKey(groupId, 1, ByteArray(32) { 7 }, 1),
        )
    }

    private fun events(start: Int, count: Int, bytes: Int): List<Pair<StoredGroupEvent, Boolean>> =
        List(count) { index ->
            val seq = (start + index + 1).toLong()
            StoredGroupEvent(
                groupId = groupId, eventId = "event-$seq", epoch = 1,
                authorDeviceId = "owner", authorSeq = seq,
                hlcPhysicalMs = seq, hlcLogical = 0, kind = "MESSAGE",
                body = "x".repeat(bytes), payload = ByteArray(bytes) { 42 },
                createdAtMs = seq, receivedAtMs = seq + 10_000,
            ) to true
        }

    private inline fun withDatabase(name: String, block: (GroupDatabaseHelper) -> Unit) {
        context.deleteDatabase(name)
        val helper = GroupDatabaseHelper(context, name)
        try {
            createGroup(helper)
            block(helper)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private fun emit(row: JSONObject) {
        // sendStatus keeps only the last value per key; logcat preserves every row.
        android.util.Log.i("BATCH_BENCH", row.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("batch_bench_json", row.toString())
        })
    }

    @Test
    fun compareSingleTransactionsWithBoundedBatches() {
        requireIsolatedApplication()
        // Two warm-up pairs and seven measured pairs; alternate order to limit warm/JIT bias.
        // 100 is the current wire MAX_SYNC_EVENTS; 1000 models ten history pages, not one huge tx.
        for ((count, bytes) in listOf(100 to 256, 1000 to 256, 100 to 4096)) {
            val input = events(1000, count, bytes)
            for (round in -2..6) {
                val modes = if (round % 2 == 0) listOf("single", "batch100")
                    else listOf("batch100", "single")
                for (mode in modes) {
                    withDatabase("batch-benchmark-timing.db") { db ->
                        events(0, 1000, bytes).chunked(100).forEach { db.ingestEventsBatch(it) }
                        val sql = db.safeWritableDatabase
                        fun pragma(name: String): String = sql.rawQuery("PRAGMA $name", emptyArray()).use {
                            check(it.moveToFirst()); it.getString(0)
                        }
                        val journal = pragma("journal_mode")
                        val synchronous = pragma("synchronous")
                        val chunks = input.chunked(100)
                        var inserted = 0
                        val start = SystemClock.elapsedRealtimeNanos()
                        if (mode == "single") {
                            for ((event, unread) in input) if (db.ingestEvent(event, unread)) inserted++
                        } else {
                            for (chunk in chunks) inserted += db.ingestEventsBatch(chunk)
                        }
                        val nanos = SystemClock.elapsedRealtimeNanos() - start
                        assertEquals(count, inserted)
                        assertEquals(1000 + count, db.getGroup(groupId)?.unreadCount)
                        val timeline = db.loadTimeline(groupId, limit = 100)
                        assertEquals(100, timeline.size)
                        assertEquals("event-${1000 + count}", timeline.first().messageId)
                        assertNotNull(db.getEvent(groupId, "event-1001"))
                        assertEquals(0, db.ingestEventsBatch(input))
                        assertEquals(1000 + count, db.getGroup(groupId)?.unreadCount)
                        emit(JSONObject().apply {
                            put("scope", "storage_only"); put("mode", mode)
                            put("round", round); put("warmup", round < 0)
                            put("count", count); put("body_bytes", bytes); put("payload_bytes", bytes)
                            put("preexisting_events", 1000); put("elapsed_ns", nanos)
                            put("journal_mode", journal); put("synchronous", synchronous)
                            put("api", android.os.Build.VERSION.SDK_INT)
                            put("abi", android.os.Build.SUPPORTED_ABIS.first())
                            put("transaction_calls", if (mode == "single") count else chunks.size)
                            put("correctness", "PASS")
                        })
                    }
                }
            }
        }
    }

    @Test
    fun characterizeFailureIsolationAndBatchRollbackAfterReopen() {
        requireIsolatedApplication()
        val good = events(0, 2, 256)
        val invalid = good[0].first.copy(eventId = "invalid", authorDeviceId = "") to true
        val input = listOf(good[0], invalid, good[1])
        for (mode in listOf("single", "batch100")) {
            val name = "batch-benchmark-rollback.db"
            context.deleteDatabase(name)
            var db = GroupDatabaseHelper(context, name)
            try {
                createGroup(db)
                var rejected = 0
                if (mode == "single") {
                    for ((event, unread) in input) {
                        try { db.ingestEvent(event, unread) } catch (_: IllegalArgumentException) { rejected++ }
                    }
                } else {
                    try { db.ingestEventsBatch(input) } catch (_: IllegalArgumentException) { rejected++ }
                }
                assertEquals(1, rejected)
                db.close()
                db = GroupDatabaseHelper(context, name)
                val expected = if (mode == "single") 2 else 0
                assertEquals(expected, db.loadTimeline(groupId, 20).size)
                assertEquals(expected, db.getGroup(groupId)?.unreadCount)
                assertNull(db.getEvent(groupId, "invalid"))
                // Retry of valid events must recover without duplicates after reopening.
                db.ingestEventsBatch(good)
                assertEquals(0, db.ingestEventsBatch(good))
                assertEquals(2, db.getGroup(groupId)?.unreadCount)
                assertTrue(db.loadTimeline(groupId, 20).all { it.unread })
                emit(JSONObject().apply {
                    put("scope", "storage_failure_semantics"); put("mode", mode)
                    put("persisted_before_retry", expected); put("reopen_retry", "PASS")
                    put("drop_in_equivalent", false)
                })
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}
