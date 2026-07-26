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
    STICKER(StickerSupport.ATTACHMENT_TYPE),
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

internal fun attachmentCategory(
    type: String?,
    fileName: String,
    isMine: Boolean = false,
): AttachmentCategory? {
    if (StickerSupport.ATTACHMENT_TYPE.equals(type, ignoreCase = true) ||
        StickerSupport.PACK_ATTACHMENT_TYPE.equals(type, ignoreCase = true) ||
        StickerSupport.isStickerFileName(fileName) ||
        StickerSupport.isStickerPackFileName(fileName)
    ) {
        return AttachmentCategory.STICKER.takeUnless { isMine }
    }
    return AttachmentCategory.entries.firstOrNull {
        it != AttachmentCategory.STICKER && it.messageType.equals(type, ignoreCase = true)
    } ?: when (VoiceMessageSupport.attachmentType(fileName, "")) {
        "VIDEO" -> AttachmentCategory.VIDEO
        "IMAGE" -> AttachmentCategory.IMAGE
        GifStorageManager.ATTACHMENT_TYPE -> AttachmentCategory.IMAGE
        "VOICE" -> AttachmentCategory.VOICE
        else -> AttachmentCategory.FILE
    }
}

object AttachmentStorageManager {
    private fun managedRoots(context: Context): List<File> = listOf(
        File(context.filesDir, "attachments"),
        File(context.filesDir, "config/downloads"),
        File(context.filesDir, "sticker_cache/received"),
        File(context.filesDir, "sticker_cache/received_packs"),
    )

    private fun allowedRoots(context: Context): List<File> = listOf(
        context.filesDir,
        context.cacheDir,
    )

    private fun protectedLibraryRoots(context: Context): List<File> = listOf(
        File(context.filesDir, "gif_library"),
        File(context.filesDir, "sticker_packs"),
    )

    private fun recordFile(
        record: StoredAttachmentRecord,
        allowedRoots: List<File>,
        protectedRoots: List<File>,
    ): File? {
        val path = record.uri
        if (path.isBlank() || "://" in path) return null
        val file = File(path)
        return file.takeIf {
            isFileInsideAnyRoot(it, allowedRoots) &&
                !isFileInsideAnyRoot(it, protectedRoots)
        }
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

    private fun managedFileCategory(context: Context, file: File): AttachmentCategory? =
        if (isFileInsideAnyRoot(
                file,
                listOf(File(context.filesDir, "sticker_cache/received_packs")),
            )
        ) {
            AttachmentCategory.STICKER
        } else {
            attachmentCategory(null, file.name)
        }

    fun calculateUsage(context: Context): Map<AttachmentCategory, AttachmentCategoryUsage> {
        val appContext = context.applicationContext
        val allowedRoots = allowedRoots(appContext)
        val protectedRoots = protectedLibraryRoots(appContext)
        val records = ChatDatabaseHelper.getInstance(appContext).getStoredAttachments()
        val categoryByPath = mutableMapOf<String, AttachmentCategory>()

        records.forEach { record ->
            val file = recordFile(record, allowedRoots, protectedRoots) ?: return@forEach
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            attachmentCategory(
                record.attachmentType,
                record.attachmentName ?: file.name,
                record.isMine,
            )?.let { categoryByPath.putIfAbsent(canonicalPath, it) }
        }
        scanManagedFiles(appContext).forEach { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            managedFileCategory(appContext, file)?.let {
                categoryByPath.putIfAbsent(canonicalPath, it)
            }
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
        val protectedRoots = protectedLibraryRoots(appContext)
        val recordsByPath = records.mapNotNull { record ->
            val file = recordFile(record, allowedRoots, protectedRoots) ?: return@mapNotNull null
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                ?: return@mapNotNull null
            canonicalPath to record
        }.groupBy({ it.first }, { it.second })

        val selectedPaths = recordsByPath.mapNotNull { (path, pathRecords) ->
            if (pathRecords.any {
                    attachmentCategory(
                        it.attachmentType,
                        it.attachmentName ?: File(path).name,
                        it.isMine,
                    ) in categories
                }
            ) {
                path
            } else {
                null
            }
        }.toMutableSet()
        scanManagedFiles(appContext).forEach { file ->
            if (managedFileCategory(appContext, file) in categories) {
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
