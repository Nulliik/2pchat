package com.example.twopchat.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val revoked = mutableListOf<File>()
    private val backend = object : DiagnosticsBackend {
        var enabled = false
        override fun setEnabled(enabled: Boolean): Boolean { this.enabled = enabled; return true }
        override fun snapshot() = validCore().put("enabled", enabled).toString()
    }
    private fun store(now: () -> Long = System::currentTimeMillis, core: DiagnosticsBackend = backend) = DiagnosticsStore(
        File(temporary.root, "private"), File(temporary.root, "exports"), core,
        "0.0.9.3(1)", 30, 35, now, { revoked.add(it) },
    )

    @Test fun defaultOffRevocationAndPreviewInvalidation() {
        val store = store()
        assertFalse(store.enabled)
        store.recordCrash(IllegalStateException("PRIVATE"))
        assertFalse(File(temporary.root, "private/pending_crash").exists())
        assertThrows(IllegalStateException::class.java) { store.preview() }
        store.setEnabled(true)
        val preview = store.preview()
        val exported = store.export(preview)
        store.clear()
        assertFalse(exported.exists())
        assertTrue(revoked.contains(exported))
        assertThrows(IllegalStateException::class.java) { store.export(preview) }
        store.recordCrash(IllegalStateException("PRIVATE"))
        store.setEnabled(false)
        assertFalse(File(temporary.root, "private/pending_crash").exists())
        assertFalse(store().enabled)
    }

    @Test fun crashSurvivesRestartWithoutMessagesAndExpires() {
        val store = store()
        store.setEnabled(true)
        store.recordCrash(IllegalStateException("alice@example.org /private/secret.key"))
        val crash = File(temporary.root, "private/pending_crash")
        assertEquals(10L, crash.length())
        assertFalse(crash.readText().contains("alice"))
        assertTrue(store().preview().text.contains("illegal_state"))
        val expired = store(now = { System.currentTimeMillis() + DiagnosticsStore.CRASH_RETENTION_MS + 1000 })
        assertTrue(expired.preview().text.contains("none_recorded"))
        assertFalse(crash.exists())
    }

    @Test fun corruptConsentAndCrashNeverReachPublicReport() {
        var store = store()
        store.setEnabled(true)
        File(temporary.root, "private/pending_crash").writeText("private-name".repeat(1000))
        assertTrue(store.preview().text.contains("none_recorded"))
        File(temporary.root, "private/consent").writeBytes(byteArrayOf(1, 1))
        store = store()
        assertFalse(store.enabled)
        assertFalse(backend.enabled)
    }

    @Test fun stalePreviewCannotExtendCrashRetention() {
        var now = System.currentTimeMillis()
        var nanos = 0L
        val store = DiagnosticsStore(File(temporary.root, "private"), File(temporary.root, "exports"), backend,
            "0.0.9.3(1)", 30, 35, { now }, {}, { nanos })
        store.setEnabled(true)
        val preview = store.preview()
        nanos = DiagnosticsStore.PREVIEW_RETENTION_NANOS + 1
        assertThrows(IllegalStateException::class.java) { store.export(preview) }
        store.recordCrash(IllegalStateException("private"))
        now = System.currentTimeMillis()
        val crashPreview = store.preview()
        now += DiagnosticsStore.CRASH_RETENTION_MS + 1000
        assertThrows(IllegalStateException::class.java) { store.export(crashPreview) }
        assertFalse(File(temporary.root, "private/pending_crash").exists())
    }

    @Test fun zipContainsExactlyReviewedBytesWithNoSourceFileOrTimeMetadata() {
        File(temporary.root, "app.log").writeText("Alice 192.168.1.42 alice@example.com secret-message")
        val store = store()
        store.setEnabled(true)
        val preview = store.preview()
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = store.export(preview)
            val firstBytes = first.readBytes()
            ZipFile(first).use { zip ->
                assertEquals(1, zip.size())
                val entry = zip.entries().nextElement()
                assertEquals("report.txt", entry.name)
                assertEquals(0L, entry.time)
                assertNull(entry.comment)
                assertEquals(preview.text, zip.getInputStream(entry).reader().readText())
            }
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val second = store.export(preview)
            assertArrayEquals(firstBytes, second.readBytes())
            assertFalse(first.exists())
            assertNotEquals(first.parent, second.parent)
            assertEquals(first.name, second.name)
            assertTrue(revoked.contains(first))
            assertFalse(preview.text.contains("Alice"))
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun crashHandlerDelegatesExactlyOnceEvenIfRecordingFails() {
        var delegations = 0
        val original = IllegalStateException("private")
        val thread = Thread.currentThread()
        DiagnosticsCrashHandler(
            { throw OutOfMemoryError("private") },
            Thread.UncaughtExceptionHandler { actualThread, actualError ->
                assertSame(thread, actualThread)
                assertSame(original, actualError)
                delegations++
            },
            { fail("must use existing handler") },
        ).uncaughtException(thread, original)
        assertEquals(1, delegations)
        var terminated = false
        DiagnosticsCrashHandler({}, null, { terminated = true }).uncaughtException(thread, original)
        assertTrue(terminated)
    }

    @Test fun crashCaptureDoesNotWaitForBusyCollector() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val core = object : DiagnosticsBackend {
            override fun setEnabled(enabled: Boolean) = true
            override fun snapshot(): String {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return validCore().toString()
            }
        }
        val store = store(core = core)
        store.setEnabled(true)
        val reader = Thread { store.preview() }
        reader.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val completed = CountDownLatch(1)
            val crashing = Thread { store.recordCrash(IllegalStateException("private")); completed.countDown() }
            crashing.start()
            assertTrue("crash capture must not block on the store", completed.await(1, TimeUnit.SECONDS))
            crashing.join(1000)
        } finally {
            release.countDown()
            reader.join(5000)
        }
    }
}
