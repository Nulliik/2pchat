package com.example.twopchat.diagnostics

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

internal interface DiagnosticsBackend {
    fun setEnabled(enabled: Boolean): Boolean
    fun snapshot(): String?
}

internal class ReportPreview private constructor(
    val text: String,
    internal val generation: Long,
    internal val createdNanos: Long,
    internal val hasCrash: Boolean,
) {
    companion object {
        fun create(versionName: String, versionCode: Int, sdk: Int, core: String?, crash: CrashSummary?, generation: Long, createdNanos: Long) =
            ReportPreview(PublicReport.render(versionName, versionCode, sdk, core, crash), generation, createdNanos, crash != null)
    }
}

/** All normal operations run on Dispatchers.IO. Crash writes never wait for this lock. */
internal class DiagnosticsStore(
    private val privateDir: File,
    private val exportDir: File,
    private val backend: DiagnosticsBackend,
    private val versionName: String,
    private val versionCode: Int,
    private val sdk: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val revoke: (File) -> Unit = {},
    private val monotonicNow: () -> Long = System::nanoTime,
) {
    private val lock = ReentrantLock()
    private val consent = File(privateDir, "consent")
    private val pendingCrash = File(privateDir, "pending_crash")
    private var generation = 0L
    @Volatile var enabled = false
        private set

    init {
        lock.withLock {
            enabled = runCatching {
                consent.inputStream().use { it.read() == 1 && it.read() == -1 }
            }.getOrDefault(false)
            backend.setEnabled(enabled)
            cleanupExports()
            // Incomplete writes are never promoted to consent or crash records.
            File(privateDir, "consent.tmp").delete()
            File(privateDir, "pending_crash.tmp").delete()
            if (!enabled) eraseCrash() else readCrash() // Reject expired/corrupt records.
        }
    }

    fun setEnabled(value: Boolean) = lock.withLock {
        // Fail closed in memory even if storage is unavailable. Persist opt-in
        // before enabling; failure to persist opt-out is surfaced to the UI.
        enabled = false
        generation++
        backend.setEnabled(false)
        atomicWrite(consent) { it.write(0) }
        cleanupExports()
        eraseCrash()
        if (value) atomicWrite(consent) { it.write(1) }
        enabled = value
        if (value) backend.setEnabled(true)
    }

    fun clear() = lock.withLock {
        generation++
        backend.setEnabled(enabled)
        eraseCrash()
        cleanupExports()
    }

    fun preview(): ReportPreview = lock.withLock {
        check(enabled) { "Diagnostics are disabled" }
        ReportPreview.create(versionName, versionCode, sdk, backend.snapshot(), readCrash(), generation, monotonicNow())
    }

    fun reviewedText(preview: ReportPreview): String = lock.withLock {
        check(enabled && preview.generation == generation) { "Diagnostic preview expired" }
        val crash = readCrash()
        check(monotonicNow() - preview.createdNanos in 0..PREVIEW_RETENTION_NANOS) { "Diagnostic preview expired" }
        check(crash != null || !preview.hasCrash) { "Diagnostic preview expired" }
        preview.text
    }

    fun export(preview: ReportPreview): File = lock.withLock {
        val text = reviewedText(preview)
        cleanupExports()
        // Unique URI capability per share; a previous recipient cannot read a
        // future export by reopening a previously granted fixed content URI.
        val directory = File(exportDir, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Could not prepare diagnostic export" }
        val file = File(directory, EXPORT_NAME)
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                // Epoch zero produces a fixed DOS/extended time independent of
                // report creation time. Never copy filesystem/ZIP metadata.
                zip.putNextEntry(ZipEntry("report.txt").apply { time = 0L })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            file
        } catch (failure: Exception) {
            file.delete()
            directory.delete()
            throw IOException("Could not prepare diagnostic export", failure)
        }
    }

    fun recordCrash(throwable: Throwable) {
        if (!enabled || !lock.tryLock()) return
        try {
            if (!enabled) return
            val summary = CrashSummary.from(throwable, versionCode)
            atomicWrite(pendingCrash) { stream ->
                val data = DataOutputStream(stream)
                data.writeInt(CRASH_MAGIC)
                data.writeInt(summary.versionCode)
                data.writeByte(summary.kind.ordinal)
                data.writeByte(summary.area.ordinal)
                data.flush()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun readCrash(): CrashSummary? {
        if (!pendingCrash.exists()) return null
        val age = now() - pendingCrash.lastModified()
        val summary = runCatching {
            require(age in 0..CRASH_RETENTION_MS && pendingCrash.length() == 10L)
            DataInputStream(pendingCrash.inputStream()).use { input ->
                require(input.readInt() == CRASH_MAGIC)
                val build = input.readInt()
                val kind = input.readUnsignedByte()
                val area = input.readUnsignedByte()
                require(build in 1..10_000_000 && input.read() == -1)
                CrashSummary(build, CrashKind.entries[kind], CrashArea.entries[area])
            }
        }.getOrNull()
        if (summary == null) eraseCrash()
        return summary
    }

    private fun eraseCrash() {
        listOf(pendingCrash, File(privateDir, "pending_crash.tmp")).forEach { file ->
            check(!file.exists() || file.delete()) { "Could not delete crash summary" }
        }
    }

    private fun cleanupExports() {
        exportDir.listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.name.matches(EXPORT_DIRECTORY)) {
                val file = File(directory, EXPORT_NAME)
                revoke(file)
                check(!file.exists() || file.delete()) { "Could not delete diagnostic export" }
                directory.delete()
            }
        }
    }

    private fun atomicWrite(target: File, write: (FileOutputStream) -> Unit) {
        check(privateDir.isDirectory || privateDir.mkdirs()) { "Diagnostic storage unavailable" }
        val temporary = File(privateDir, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                output.fd.sync()
            }
            // Android rename replaces atomically. Platforms which cannot replace
            // may lose the old record on interruption; a missing record is disabled/empty.
            if (!temporary.renameTo(target)) {
                check(!target.exists() || target.delete()) { "Diagnostic storage unavailable" }
                check(temporary.renameTo(target)) { "Diagnostic storage unavailable" }
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val EXPORT_NAME = "2pchat-diagnostics.zip"
        const val CRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val PREVIEW_RETENTION_NANOS = 15L * 60 * 1_000_000_000
        private const val CRASH_MAGIC = 0x32504431
        private val EXPORT_DIRECTORY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal class DiagnosticsCrashHandler(
    private val record: (Throwable) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            record(throwable)
        } catch (_: Throwable) {
            // Best effort only: never render the failure or prevent process termination.
        } finally {
            if (delegate != null) delegate.uncaughtException(thread, throwable) else terminate()
        }
    }
}
