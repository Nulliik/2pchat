package com.example.twopchat

import android.content.Context
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.StoredAttachmentRecord
import java.io.File

enum class AttachmentCategory(val messageType: String) {
    VIDEO("VIDEO"),
    IMAGE("IMAGE"),
    FILE("FILE"),
    VOICE("VOICE"),
}

data class AttachmentCategoryUsage(
    val bytes: Long = 0L,
    val fileCount: Int = 0,
)

data class AttachmentCleanupResult(
    val deletedBytes: Long,
    val deletedFiles: Int,
    val detachedMessages: Int,
    val failedFiles: Int,
    val skippedActiveTransfers: Int,
)

internal fun isFileInsideAnyRoot(file: File, roots: Collection<File>): Boolean {
    return try {
        val candidate = file.canonicalFile.path
        roots.any { root ->
            val rootPath = root.canonicalFile.path
            candidate == rootPath || candidate.startsWith(rootPath + File.separator)
        }
    } catch (_: Exception) {
        false
    }
}

object AttachmentStorageManager {
    private fun managedRoots(context: Context): List<File> = listOf(
        File(context.filesDir, "attachments"),
        File(context.filesDir, "config/downloads"),
    )

    private fun allowedRoots(context: Context): List<File> = listOf(
        context.filesDir,
        context.cacheDir,
    )

    private fun category(type: String?, fileName: String): AttachmentCategory {
        return AttachmentCategory.entries.firstOrNull {
            it.messageType.equals(type, ignoreCase = true)
        } ?: when (VoiceMessageSupport.attachmentType(fileName, "")) {
            "VIDEO" -> AttachmentCategory.VIDEO
            "IMAGE" -> AttachmentCategory.IMAGE
            "VOICE" -> AttachmentCategory.VOICE
            else -> AttachmentCategory.FILE
        }
    }

    private fun recordFile(
        record: StoredAttachmentRecord,
        allowedRoots: List<File>,
    ): File? {
        val path = record.uri
        if (path.isBlank() || "://" in path) return null
        val file = File(path)
        return file.takeIf { isFileInsideAnyRoot(it, allowedRoots) }
    }

    private fun scanManagedFiles(context: Context): Sequence<File> {
        val roots = managedRoots(context)
        return roots.asSequence().flatMap { root ->
            if (!root.exists()) {
                emptySequence()
            } else {
                root.walkTopDown()
                    .filter { it.isFile && isFileInsideAnyRoot(it, roots) }
            }
        }
    }

    fun calculateUsage(context: Context): Map<AttachmentCategory, AttachmentCategoryUsage> {
        val appContext = context.applicationContext
        val allowedRoots = allowedRoots(appContext)
        val records = ChatDatabaseHelper.getInstance(appContext).getStoredAttachments()
        val categoryByPath = mutableMapOf<String, AttachmentCategory>()

        records.forEach { record ->
            val file = recordFile(record, allowedRoots) ?: return@forEach
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            categoryByPath.putIfAbsent(
                canonicalPath,
                category(record.attachmentType, record.attachmentName ?: file.name),
            )
        }
        scanManagedFiles(appContext).forEach { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            categoryByPath.putIfAbsent(canonicalPath, category(null, file.name))
        }

        val totals = AttachmentCategory.entries.associateWith {
            AttachmentCategoryUsage()
        }.toMutableMap()
        categoryByPath.forEach { (path, fileCategory) ->
            val file = File(path)
            if (!file.isFile) return@forEach
            val current = totals.getValue(fileCategory)
            totals[fileCategory] = current.copy(
                bytes = current.bytes + file.length().coerceAtLeast(0L),
                fileCount = current.fileCount + 1,
            )
        }
        return totals
    }

    fun clear(
        context: Context,
        categories: Set<AttachmentCategory>,
    ): AttachmentCleanupResult {
        if (categories.isEmpty()) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }
        val appContext = context.applicationContext
        val database = ChatDatabaseHelper.getInstance(appContext)
        val records = database.getStoredAttachments()
        val allowedRoots = allowedRoots(appContext)
        val recordsByPath = records.mapNotNull { record ->
            val file = recordFile(record, allowedRoots) ?: return@mapNotNull null
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                ?: return@mapNotNull null
            canonicalPath to record
        }.groupBy({ it.first }, { it.second })

        val selectedPaths = recordsByPath.mapNotNull { (path, pathRecords) ->
            if (pathRecords.any {
                    category(it.attachmentType, it.attachmentName ?: File(path).name) in categories
                }
            ) {
                path
            } else {
                null
            }
        }.toMutableSet()
        scanManagedFiles(appContext).forEach { file ->
            if (category(null, file.name) in categories) {
                runCatching { file.canonicalPath }.getOrNull()?.let(selectedPaths::add)
            }
        }

        var deletedBytes = 0L
        var deletedFiles = 0
        var failedFiles = 0
        var skippedActiveTransfers = 0
        val detachedMessageIds = mutableSetOf<String>()

        selectedPaths.forEach { path ->
            val pathRecords = recordsByPath[path].orEmpty()
            if (pathRecords.any { P2PMessageRelay.isFileTransferActive(it.messageId) }) {
                skippedActiveTransfers += 1
                return@forEach
            }
            val file = File(path)
            val existed = file.isFile
            val size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
            val removed = !file.exists() || (file.isFile && file.delete())
            if (removed) {
                if (existed) {
                    deletedBytes += size
                    deletedFiles += 1
                }
                detachedMessageIds += pathRecords.map(StoredAttachmentRecord::messageId)
            } else {
                failedFiles += 1
            }
        }

        val detachedMessages = database.clearAttachmentUris(detachedMessageIds)
        return AttachmentCleanupResult(
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            detachedMessages = detachedMessages,
            failedFiles = failedFiles,
            skippedActiveTransfers = skippedActiveTransfers,
        )
    }
}
