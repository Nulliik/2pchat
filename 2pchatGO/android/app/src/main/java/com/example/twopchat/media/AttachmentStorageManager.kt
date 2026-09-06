package com.example.twopchat.media

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.StoredAttachmentRecord
import com.example.twopchat.logging.SafeLog
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.security.TemporaryCacheSanitizer
import com.example.twopchat.ui.chat.AttachmentImageCache
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
    private const val TAG = "AttachmentStorageManager"

    private fun managedRoots(context: Context): List<File> = listOf(
        File(context.filesDir, "attachments"),
        File(context.filesDir, "config/downloads"),
        File(context.filesDir, "group_downloads"),
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
        File(context.filesDir, "sticker_cache/installed"),
    )

    /**
     * Path-based exclusion check: Ensures user sticker packs, GIF library, and installed
     * collections are strictly protected from retention and LRU pruning regardless of timestamps.
     */
    fun isPathProtected(file: File, context: Context? = null): Boolean {
        if (context != null && isFileInsideAnyRoot(file, protectedLibraryRoots(context))) return true
        val normalized = try {
            file.canonicalPath.replace('\\', '/')
        } catch (_: Throwable) {
            file.path.replace('\\', '/')
        }
        return normalized.contains("/sticker_packs/") ||
            normalized.contains("/gif_library/") ||
            normalized.contains("/sticker_cache/installed") ||
            normalized.contains("/stickers/") ||
            normalized.contains("/gifs/")
    }

    /**
     * Overwrites file contents with zeros before unlinking.
     *
     * Note on Flash Storage (NAND wear leveling):
     * On modern mobile storage (UFS/eMMC), hardware wear leveling and garbage collection
     * can leave remnants of overwritten sectors in physically relocated NAND blocks.
     * Zero-filling provides best-effort mitigation against casual software inspection,
     * but does not replace full-disk hardware-backed encryption (FBE/Keystore).
     */
    fun secureDelete(file: File): Boolean {
        return try {
            if (!file.exists()) return true
            TemporaryCacheSanitizer.shredFile(file)
        } catch (_: Throwable) {
            file.delete()
        }
    }

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
            if (isPathProtected(file, appContext)) return@forEach
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            attachmentCategory(
                record.attachmentType,
                record.attachmentName ?: file.name,
                record.isMine,
            )?.let { categoryByPath.putIfAbsent(canonicalPath, it) }
        }
        scanManagedFiles(appContext).forEach { file ->
            if (isPathProtected(file, appContext)) return@forEach
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
        val totalBytes = totals.values.sumOf { it.bytes }
        P2PPreferences.setCachedMediaBytes(appContext, totalBytes)
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
            if (isPathProtected(file, appContext)) return@mapNotNull null
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
            if (!isPathProtected(file, appContext) && managedFileCategory(appContext, file) in categories) {
                runCatching { file.canonicalPath }.getOrNull()?.let(selectedPaths::add)
            }
        }

        var deletedBytes = 0L
        var deletedFiles = 0
        var failedFiles = 0
        var skippedActiveTransfers = 0
        val detachedMessageIds = mutableSetOf<String>()
        val pathsToShred = mutableSetOf<String>()

        selectedPaths.forEach { path ->
            val pathRecords = recordsByPath[path].orEmpty()
            if (pathRecords.any { P2PMessageRelay.isFileTransferActive(it.messageId) } ||
                P2PMessageRelay.isFileTransferActive(path)
            ) {
                skippedActiveTransfers += 1
                return@forEach
            }
            detachedMessageIds += pathRecords.map(StoredAttachmentRecord::messageId)
            pathsToShred += path
        }

        // Phase 1: Atomically clear attachment URIs in SQLCipher
        val detachedMessages = database.clearAttachmentUris(detachedMessageIds)

        // Phase 2: Shred physical files after verifying zero remaining DB references (refcount protection)
        val deletedHashes = mutableListOf<String>()
        pathsToShred.forEach { path ->
            val file = File(path)
            val remainingRefs = database.countMessagesWithAttachmentPath(path)
            if (remainingRefs == 0) {
                val existed = file.isFile
                val size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
                val shredded = secureDelete(file)
                if (shredded) {
                    if (existed) {
                        deletedBytes += size
                        deletedFiles += 1
                        deletedHashes += database.hashUri(path)
                    }
                } else {
                    failedFiles += 1
                }
            } else {
                SafeLog.d(TAG, "Skipping physical shred for $path; $remainingRefs active references remain")
            }
        }

        if (deletedHashes.isNotEmpty()) {
            database.deleteMediaAccessLogs(deletedHashes)
        }
        if (deletedBytes > 0L) {
            P2PPreferences.adjustCachedMediaBytes(appContext, -deletedBytes)
            AttachmentImageCache.clear()
        }

        return AttachmentCleanupResult(
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            detachedMessages = detachedMessages,
            failedFiles = failedFiles,
            skippedActiveTransfers = skippedActiveTransfers,
        )
    }

    /**
     * Cleans files older than [days] based on media_access_log (with fallback to lastModified).
     * Protected paths, active file transfers, and files still referenced by active messages are preserved.
     */
    fun applyRetentionPolicy(context: Context, days: Int): AttachmentCleanupResult {
        if (days <= 0) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }
        val appContext = context.applicationContext
        val database = ChatDatabaseHelper.getInstance(appContext)
        val cutoffMs = System.currentTimeMillis() - (days.toLong() * 86_400_000L)
        val allowedRoots = allowedRoots(appContext)
        val protectedRoots = protectedLibraryRoots(appContext)
        val records = database.getStoredAttachments()
        val accessTimestamps = database.getMediaAccessTimestamps()

        val recordsByPath = records.mapNotNull { record ->
            val file = recordFile(record, allowedRoots, protectedRoots) ?: return@mapNotNull null
            if (isPathProtected(file, appContext)) return@mapNotNull null
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
            canonicalPath to record
        }.groupBy({ it.first }, { it.second })

        val pathsToEvaluate = recordsByPath.keys.toMutableSet()
        scanManagedFiles(appContext).forEach { file ->
            if (!isPathProtected(file, appContext)) {
                runCatching { file.canonicalPath }.getOrNull()?.let(pathsToEvaluate::add)
            }
        }

        val expiredPaths = mutableSetOf<String>()
        val expiredMessageIds = mutableSetOf<String>()

        pathsToEvaluate.forEach { path ->
            val file = File(path)
            if (!file.isFile) return@forEach
            val pathRecords = recordsByPath[path].orEmpty()
            if (pathRecords.any { P2PMessageRelay.isFileTransferActive(it.messageId) } ||
                P2PMessageRelay.isFileTransferActive(path)
            ) {
                return@forEach
            }
            val uriHash = database.hashUri(path)
            val lastAccess = accessTimestamps[uriHash]
                ?: file.lastModified().coerceAtMost(System.currentTimeMillis())

            if (lastAccess < cutoffMs) {
                expiredPaths += path
                expiredMessageIds += pathRecords.map(StoredAttachmentRecord::messageId)
            }
        }

        if (expiredPaths.isEmpty() && expiredMessageIds.isEmpty()) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }

        // Phase 1: Atomically clear attachment URIs in SQLCipher
        val detachedMessages = database.clearAttachmentUris(expiredMessageIds)

        // Phase 2: Shred physical files that have zero remaining DB references
        var deletedBytes = 0L
        var deletedFiles = 0
        var failedFiles = 0
        var skippedActiveTransfers = 0
        val deletedHashes = mutableListOf<String>()

        expiredPaths.forEach { path ->
            val file = File(path)
            if (P2PMessageRelay.isFileTransferActive(path)) {
                skippedActiveTransfers += 1
                return@forEach
            }
            val remainingRefs = database.countMessagesWithAttachmentPath(path)
            if (remainingRefs == 0) {
                val size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
                val existed = file.isFile
                if (secureDelete(file)) {
                    if (existed) {
                        deletedBytes += size
                        deletedFiles += 1
                        deletedHashes += database.hashUri(path)
                    }
                } else {
                    failedFiles += 1
                }
            } else {
                SafeLog.d(TAG, "Skipping file shred for $path; $remainingRefs active references remain")
            }
        }

        if (deletedHashes.isNotEmpty()) {
            database.deleteMediaAccessLogs(deletedHashes)
        }
        if (deletedBytes > 0L) {
            P2PPreferences.adjustCachedMediaBytes(appContext, -deletedBytes)
            AttachmentImageCache.clear()
        }

        return AttachmentCleanupResult(
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            detachedMessages = detachedMessages,
            failedFiles = failedFiles,
            skippedActiveTransfers = skippedActiveTransfers,
        )
    }

    /**
     * Rescans all managed media files on disk and synchronizes CACHED_MEDIA_BYTES in preferences
     * to eliminate any counter drift over time.
     * Guaranteed deduplication: Each physical file canonical path is counted exactly once.
     */
    fun reconcileCachedMediaBytes(context: Context, force: Boolean = true): Long {
        val appContext = context.applicationContext
        if (!force) {
            val cached = P2PPreferences.getCachedMediaBytes(appContext)
            if (cached < 100 * 1024 * 1024L) {
                return cached
            }
        }
        val usage = calculateUsage(appContext)
        val total = usage.values.sumOf { it.bytes }
        P2PPreferences.setCachedMediaBytes(appContext, total)
        return total
    }

    /**
     * Enforces [limitMb] maximum cache size using LRU eviction based on media_access_log.
     * Features Fast Check via [P2PPreferences.getCachedMediaBytes] to skip heavy I/O if within limits,
     * unless [forceReconcile] is requested.
     */
    fun enforceMaxCacheSize(
        context: Context,
        limitMb: Int,
        forceReconcile: Boolean = false,
    ): AttachmentCleanupResult {
        if (limitMb <= 0) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }
        val appContext = context.applicationContext
        val maxBytes = limitMb * 1024L * 1024L

        // Fast Check: If cached total size is already known and within limit, skip heavy scan!
        if (!forceReconcile) {
            val currentCachedBytes = P2PPreferences.getCachedMediaBytes(appContext)
            if (currentCachedBytes in 1..maxBytes) {
                return AttachmentCleanupResult(0L, 0, 0, 0, 0)
            }
        }

        val database = ChatDatabaseHelper.getInstance(appContext)
        val allowedRoots = allowedRoots(appContext)
        val protectedRoots = protectedLibraryRoots(appContext)
        val records = database.getStoredAttachments()
        val accessTimestamps = database.getMediaAccessTimestamps()

        val recordsByPath = records.mapNotNull { record ->
            val file = recordFile(record, allowedRoots, protectedRoots) ?: return@mapNotNull null
            if (isPathProtected(file, appContext)) return@mapNotNull null
            val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
            canonicalPath to record
        }.groupBy({ it.first }, { it.second })

        val candidatePaths = recordsByPath.keys.toMutableSet()
        scanManagedFiles(appContext).forEach { file ->
            if (!isPathProtected(file, appContext)) {
                runCatching { file.canonicalPath }.getOrNull()?.let(candidatePaths::add)
            }
        }

        data class CacheItem(val path: String, val size: Long, val lastAccess: Long)
        val items = mutableListOf<CacheItem>()
        var totalSize = 0L

        candidatePaths.forEach { path ->
            val file = File(path)
            if (!file.isFile) return@forEach
            val size = file.length().coerceAtLeast(0L)
            totalSize += size
            val uriHash = database.hashUri(path)
            val ts = accessTimestamps[uriHash]
                ?: file.lastModified().coerceAtMost(System.currentTimeMillis())
            items += CacheItem(path, size, ts)
        }

        // Sync cached total size with reality
        P2PPreferences.setCachedMediaBytes(appContext, totalSize)

        if (totalSize <= maxBytes) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }

        // Sort LRU: oldest accessed first
        items.sortBy { it.lastAccess }

        val targetEvictionBytes = totalSize - maxBytes
        var plannedEvictionBytes = 0L
        val pathsToEvict = mutableListOf<String>()
        val messageIdsToDetach = mutableSetOf<String>()

        for (item in items) {
            if (plannedEvictionBytes >= targetEvictionBytes) break
            val pathRecords = recordsByPath[item.path].orEmpty()
            if (pathRecords.any { P2PMessageRelay.isFileTransferActive(it.messageId) } ||
                P2PMessageRelay.isFileTransferActive(item.path)
            ) {
                continue
            }
            pathsToEvict += item.path
            messageIdsToDetach += pathRecords.map(StoredAttachmentRecord::messageId)
            plannedEvictionBytes += item.size
        }

        if (pathsToEvict.isEmpty() && messageIdsToDetach.isEmpty()) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }

        // Phase 1: Atomically clear DB URIs
        val detachedMessages = database.clearAttachmentUris(messageIdsToDetach)

        // Phase 2: Shred physical files after refcount re-check
        var deletedBytes = 0L
        var deletedFiles = 0
        var failedFiles = 0
        var skippedActiveTransfers = 0
        val deletedHashes = mutableListOf<String>()

        pathsToEvict.forEach { path ->
            val file = File(path)
            if (P2PMessageRelay.isFileTransferActive(path)) {
                skippedActiveTransfers += 1
                return@forEach
            }
            val remainingRefs = database.countMessagesWithAttachmentPath(path)
            if (remainingRefs == 0) {
                val size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
                val existed = file.isFile
                if (secureDelete(file)) {
                    if (existed) {
                        deletedBytes += size
                        deletedFiles += 1
                        deletedHashes += database.hashUri(path)
                    }
                } else {
                    failedFiles += 1
                }
            } else {
                SafeLog.d(TAG, "Skipping physical shred for $path; $remainingRefs active references remain")
            }
        }

        if (deletedHashes.isNotEmpty()) {
            database.deleteMediaAccessLogs(deletedHashes)
        }
        if (deletedBytes > 0L) {
            P2PPreferences.adjustCachedMediaBytes(appContext, -deletedBytes)
            AttachmentImageCache.clear()
        }

        return AttachmentCleanupResult(
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            detachedMessages = detachedMessages,
            failedFiles = failedFiles,
            skippedActiveTransfers = skippedActiveTransfers,
        )
    }

    /**
     * Executes retention and cache size limits in background or on user request.
     * Order of operations:
     * 1. If [force] is true, perform initial full reconciliation with disk.
     * 2. Apply retention policy (time-based cleanup).
     * 3. Apply maximum cache size policy (LRU size-based cleanup).
     * 4. If [force] is true or items were deleted, perform final disk reconciliation as source of truth.
     */
    fun runCacheMaintenance(context: Context, force: Boolean = false): AttachmentCleanupResult {
        val appContext = context.applicationContext
        val retentionDays = P2PPreferences.mediaRetentionDays(appContext)
        val maxCacheSizeMb = P2PPreferences.maxCacheSizeMb(appContext)

        if (force) {
            // Step 1: Initial full reconciliation before eviction checks
            reconcileCachedMediaBytes(appContext, force = true)
        }

        if (retentionDays <= 0 && maxCacheSizeMb <= 0) {
            return AttachmentCleanupResult(0L, 0, 0, 0, 0)
        }

        val now = System.currentTimeMillis()
        val lastMaintenance = P2PPreferences.getLastCacheMaintenanceTime(appContext)
        val cooldownMs = 24 * 60 * 60 * 1000L

        if (!force && (now - lastMaintenance < cooldownMs)) {
            // If cache size limit is set, check if cached bytes exceed limit; otherwise defer
            if (maxCacheSizeMb > 0) {
                val cachedBytes = P2PPreferences.getCachedMediaBytes(appContext)
                val maxBytes = maxCacheSizeMb * 1024L * 1024L
                if (cachedBytes in 1..maxBytes) {
                    return AttachmentCleanupResult(0L, 0, 0, 0, 0)
                }
            } else {
                return AttachmentCleanupResult(0L, 0, 0, 0, 0)
            }
        }

        var totalDeletedBytes = 0L
        var totalDeletedFiles = 0
        var totalDetachedMessages = 0
        var totalFailedFiles = 0
        var totalSkippedActiveTransfers = 0

        // Step 2: Time-based retention
        if (retentionDays > 0) {
            val res = applyRetentionPolicy(appContext, retentionDays)
            totalDeletedBytes += res.deletedBytes
            totalDeletedFiles += res.deletedFiles
            totalDetachedMessages += res.detachedMessages
            totalFailedFiles += res.failedFiles
            totalSkippedActiveTransfers += res.skippedActiveTransfers
        }

        // Step 3: Size-based LRU eviction
        if (maxCacheSizeMb > 0) {
            val res = enforceMaxCacheSize(appContext, maxCacheSizeMb, forceReconcile = force)
            totalDeletedBytes += res.deletedBytes
            totalDeletedFiles += res.deletedFiles
            totalDetachedMessages += res.detachedMessages
            totalFailedFiles += res.failedFiles
            totalSkippedActiveTransfers += res.skippedActiveTransfers
        }

        // Step 4: Final reconciliation as source of truth if forced or items were cleaned up
        if (force || totalDeletedFiles > 0) {
            reconcileCachedMediaBytes(appContext, force = true)
        }

        P2PPreferences.setLastCacheMaintenanceTime(appContext, now)

        return AttachmentCleanupResult(
            deletedBytes = totalDeletedBytes,
            deletedFiles = totalDeletedFiles,
            detachedMessages = totalDetachedMessages,
            failedFiles = totalFailedFiles,
            skippedActiveTransfers = totalSkippedActiveTransfers,
        )
    }

    fun deleteMessageAttachments(
        context: Context,
        attachmentUri: String?,
        albumMediaUris: List<String> = emptyList(),
    ) {
        val appContext = context.applicationContext
        val database = ChatDatabaseHelper.getInstance(appContext)
        val roots = managedRoots(appContext) + listOf(appContext.cacheDir)
        val allPaths = (listOfNotNull(attachmentUri) + albumMediaUris)
            .filter { it.isNotBlank() && "://" !in it }
            .distinct()

        allPaths.forEach { path ->
            try {
                val file = File(path)
                if (file.isFile && isFileInsideAnyRoot(file, roots) && !isPathProtected(file, appContext)) {
                    // Refcount awareness: only shred if no other message still points to this path
                    val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.path }
                    val remaining = database.countMessagesWithAttachmentPath(canonicalPath)
                    if (remaining <= 1) {
                        // Capture size BEFORE shredding to prevent drift
                        val sizeBeforeShred = if (file.exists()) file.length().coerceAtLeast(0L) else 0L
                        if (secureDelete(file)) {
                            if (sizeBeforeShred > 0L) {
                                P2PPreferences.adjustCachedMediaBytes(appContext, -sizeBeforeShred)
                            }
                            AttachmentImageCache.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                SafeLog.d(TAG, "Failed to shred attachment file: ${e.javaClass.simpleName}")
            }
        }
    }
}
