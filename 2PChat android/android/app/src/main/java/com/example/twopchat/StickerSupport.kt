package com.example.twopchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BuiltinSticker(
    val packId: String,
    val stickerId: String,
    val emoji: String,
    val backgroundColor: Long,
    val localFilePath: String? = null,
)

data class BuiltinStickerPack(
    val id: String,
    val title: String,
    val stickers: List<BuiltinSticker>,
    val author: String = "2PChat",
    val isBuiltin: Boolean = true,
    val isOwned: Boolean = false,
)

data class WebPInfo(
    val width: Int,
    val height: Int,
    val animated: Boolean,
)

internal fun <T> moveItemByOffset(items: List<T>, fromIndex: Int, offset: Int): List<T> {
    if (fromIndex !in items.indices || items.size < 2) return items
    val toIndex = (fromIndex + offset).coerceIn(0, items.lastIndex)
    if (fromIndex == toIndex) return items
    return items.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

/**
 * Sticker files use the existing encrypted P2P file transport. The reserved
 * filename prefix is protocol metadata which older clients safely interpret as
 * a normal WEBP image.
 */
object StickerSupport {
    const val ATTACHMENT_TYPE = "STICKER"
    const val PACK_ATTACHMENT_TYPE = "STICKER_PACK"
    const val FILE_PREFIX = "2psticker_"
    const val PACK_FILE_PREFIX = "2pstickerpack_"
    const val PACK_FILE_EXTENSION = ".2psticker"
    const val MAX_DIMENSION = 512
    const val MAX_STATIC_BYTES = 128 * 1024L
    const val MAX_ANIMATED_BYTES = 512 * 1024L
    const val MAX_CACHE_BYTES = 100L * 1024L * 1024L
    const val MAX_PACK_STICKERS = 120
    const val MAX_PACK_BYTES = 32L * 1024L * 1024L
    private data class InstalledPacksSnapshot(
        val rootPath: String,
        val packs: List<BuiltinStickerPack>,
    )
    data class PackMutationResult(
        val pack: BuiltinStickerPack?,
        val addedCount: Int = 0,
        val rejectedCount: Int = 0,
    )

    private val installedPacksLock = Any()
    @Volatile
    private var installedPacksSnapshot: InstalledPacksSnapshot? = null
    private const val OWNED_MARKER = ".owned"
    private const val ORDER_FILE = "sticker_pack_order.json"
    private const val MAX_SOURCE_BYTES = 20L * 1024L * 1024L

    val builtinPacks: List<BuiltinStickerPack> = listOf(
        BuiltinStickerPack(
            id = "moods",
            title = "2P Moods",
            stickers = listOf(
                BuiltinSticker("moods", "love", "❤️", 0xFFFFD9E2),
                BuiltinSticker("moods", "laugh", "😂", 0xFFFFE8A3),
                BuiltinSticker("moods", "cool", "😎", 0xFFCFE8FF),
                BuiltinSticker("moods", "party", "🥳", 0xFFE4D5FF),
                BuiltinSticker("moods", "fire", "🔥", 0xFFFFD2B8),
                BuiltinSticker("moods", "hello", "👋", 0xFFD9F3DF),
                BuiltinSticker("moods", "wow", "🤯", 0xFFFFE0B2),
                BuiltinSticker("moods", "thanks", "🙏", 0xFFDCE5FF),
                BuiltinSticker("moods", "thumbsup", "👍", 0xFFC8E6C9),
                BuiltinSticker("moods", "thumbsdown", "👎", 0xFFFFCDD2),
                BuiltinSticker("moods", "sob", "😭", 0xFFBBDEFB),
                BuiltinSticker("moods", "scream", "😱", 0xFFE1BEE7),
                BuiltinSticker("moods", "poo", "💩", 0xFFD7CCC8),
                BuiltinSticker("moods", "clown", "🤡", 0xFFFFF9C4),
                BuiltinSticker("moods", "hundred", "💯", 0xFFFFCCBC),
                BuiltinSticker("moods", "tada", "🎉", 0xFFF8BBD0),
                BuiltinSticker("moods", "loved", "🥰", 0xFFFFCDD2),
                BuiltinSticker("moods", "devil", "😈", 0xFFE1BEE7),
                BuiltinSticker("moods", "sleepy", "😴", 0xFFC5CAE9),
                BuiltinSticker("moods", "thinking", "🤔", 0xFFFFF59D),
                BuiltinSticker("moods", "shh", "🤫", 0xFFB2DFDB),
                BuiltinSticker("moods", "salute", "🫡", 0xFFDCEDC8),
                BuiltinSticker("moods", "rocket", "🚀", 0xFFB3E5FC),
                BuiltinSticker("moods", "star", "⭐", 0xFFFFF176),
            ),
        ),
        BuiltinStickerPack(
            id = "animals",
            title = "2P Animals",
            stickers = listOf(
                BuiltinSticker("animals", "fox", "🦊", 0xFFFFE0B2),
                BuiltinSticker("animals", "cat", "🐱", 0xFFFFF9C4),
                BuiltinSticker("animals", "dog", "🐶", 0xFFFFECB3),
                BuiltinSticker("animals", "panda", "🐼", 0xFFE0E0E0),
                BuiltinSticker("animals", "lion", "🦁", 0xFFFFE082),
                BuiltinSticker("animals", "tiger", "🐯", 0xFFFFCC80),
                BuiltinSticker("animals", "frog", "🐸", 0xFFC8E6C9),
                BuiltinSticker("animals", "unicorn", "🦄", 0xFFF8BBD0),
                BuiltinSticker("animals", "monkey", "🐵", 0xFFD7CCC8),
                BuiltinSticker("animals", "bunny", "🐰", 0xFFF5F5F5),
                BuiltinSticker("animals", "owl", "🦉", 0xFFD7CCC8),
                BuiltinSticker("animals", "penguin", "🐧", 0xFFCFD8DC),
            ),
        ),
    )

    fun isStickerFileName(fileName: String): Boolean {
        val safeName = File(fileName).name
        return safeName.startsWith(FILE_PREFIX, ignoreCase = true) &&
            safeName.endsWith(".webp", ignoreCase = true)
    }

    fun isStickerPackFileName(fileName: String): Boolean {
        val safeName = File(fileName).name
        return safeName.startsWith(PACK_FILE_PREFIX, ignoreCase = true) &&
            safeName.endsWith(PACK_FILE_EXTENSION, ignoreCase = true)
    }

    fun fileName(sticker: BuiltinSticker): String =
        "$FILE_PREFIX${safeId(sticker.packId)}--${safeId(sticker.stickerId)}.webp"

    fun packIdFromStickerFileName(fileName: String): String? {
        val safeName = File(fileName).name
        if (!isStickerFileName(safeName)) return null
        val payload = safeName
            .removePrefix(FILE_PREFIX)
            .substringBeforeLast(".webp", "")
        return payload.substringBefore("--").ifBlank {
            payload.substringBefore("_")
        }.take(40).takeIf { it.matches(Regex("[a-z0-9_-]+")) }
    }

    fun packIdFromArchiveFileName(fileName: String): String? {
        val safeName = File(fileName).name
        if (!isStickerPackFileName(safeName)) return null
        return safeName
            .removePrefix(PACK_FILE_PREFIX)
            .removeSuffix(PACK_FILE_EXTENSION)
            .take(40)
            .takeIf { it.matches(Regex("[a-z0-9_-]+")) }
    }

    fun availablePacks(context: Context): List<BuiltinStickerPack> =
        builtinPacks + installedPacks(context)

    fun managedPacks(context: Context): List<BuiltinStickerPack> = installedPacks(context)

    fun ownedPacks(context: Context): List<BuiltinStickerPack> =
        installedPacks(context).filter { it.isOwned }

    private fun installedPacks(context: Context): List<BuiltinStickerPack> {
        val directory = installedPacksDirectory(context)
        val rootPath = directory.absolutePath
        installedPacksSnapshot
            ?.takeIf { it.rootPath == rootPath }
            ?.let { return it.packs }
        return synchronized(installedPacksLock) {
            installedPacksSnapshot
                ?.takeIf { it.rootPath == rootPath }
                ?.packs
                ?: directory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isDirectory }
                    .mapNotNull(::readInstalledPack)
                    .filter { installed -> builtinPacks.none { it.id == installed.id } }
                    .toList()
                    .let { packs ->
                        val order = readPackOrder(context)
                        val orderIndex = order.withIndex().associate { it.value to it.index }
                        packs.sortedWith(
                            compareBy<BuiltinStickerPack> {
                                orderIndex[it.id] ?: Int.MAX_VALUE
                            }.thenBy { it.title.lowercase() },
                        )
                    }
                    .also { packs ->
                        installedPacksSnapshot = InstalledPacksSnapshot(rootPath, packs)
                    }
        }
    }

    fun findPack(context: Context, packId: String): BuiltinStickerPack? {
        builtinPacks.firstOrNull { it.id == packId }?.let { return it }
        val directory = installedPacksDirectory(context)
        val normalizedPackId = safeId(packId)
        installedPacksSnapshot
            ?.takeIf { it.rootPath == directory.absolutePath }
            ?.let { snapshot ->
                return snapshot.packs.firstOrNull { it.id == normalizedPackId }
            }
        return readInstalledPack(File(directory, normalizedPackId))
    }

    fun findPeerPackPreview(context: Context, packId: String): BuiltinStickerPack? =
        readInstalledPack(File(peerPackPreviewDirectory(context), safeId(packId)))

    fun installPeerPackPreview(context: Context, packId: String): BuiltinStickerPack? =
        synchronized(installedPacksLock) {
            val normalizedPackId = safeId(packId)
            findPack(context, normalizedPackId)?.let { return@synchronized it }
            val previewDirectory = File(peerPackPreviewDirectory(context), normalizedPackId)
            val preview = readInstalledPack(previewDirectory) ?: return@synchronized null
            val root = installedPacksDirectory(context)
            val target = File(root, normalizedPackId)
            val staging = File(root, "${normalizedPackId}_new_${System.nanoTime()}")
            if (staging.exists()) staging.deleteRecursively()
            if (!staging.mkdirs()) return@synchronized null
            try {
                File(previewDirectory, "pack.json").copyTo(File(staging, "pack.json"))
                preview.stickers.forEach { sticker ->
                    val source = sticker.localFilePath?.let(::File)
                        ?: return@synchronized null
                    val copied = File(staging, source.name)
                    source.copyTo(copied)
                    if (validateWebP(copied) == null) return@synchronized null
                }
                if (target.exists()) return@synchronized readInstalledPack(target)
                if (!staging.renameTo(target)) return@synchronized null
                appendPackOrder(context, normalizedPackId)
                invalidatePackCaches(context, normalizedPackId)
                readInstalledPack(target)?.also {
                    previewDirectory.deleteRecursively()
                }
            } catch (_: Exception) {
                null
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }

    fun createCustomPack(
        context: Context,
        title: String,
        author: String,
        sources: List<Uri>,
    ): PackMutationResult = synchronized(installedPacksLock) {
        val normalizedTitle = title.trim().take(80)
        val normalizedAuthor = author.trim().take(80)
        if (normalizedTitle.isBlank() || normalizedAuthor.isBlank() ||
            sources.isEmpty() || sources.size > MAX_PACK_STICKERS
        ) {
            return@synchronized PackMutationResult(null, rejectedCount = sources.size)
        }
        val root = installedPacksDirectory(context)
        val packId = uniquePackId(root, normalizedTitle)
        val staging = File(root, "${packId}_new_${System.nanoTime()}").apply { mkdirs() }
        val created = mutableListOf<BuiltinSticker>()
        var rejected = 0
        try {
            sources.forEachIndexed { index, source ->
                val stickerId = uniqueStickerId(created, index)
                val target = File(staging, fileName(BuiltinSticker(packId, stickerId, "", 0L)))
                if (normalizeStickerSource(context, source, target)) {
                    created += BuiltinSticker(
                        packId = packId,
                        stickerId = stickerId,
                        emoji = "",
                        backgroundColor = 0L,
                        localFilePath = target.absolutePath,
                    )
                } else {
                    rejected += 1
                    target.delete()
                }
            }
            if (created.isEmpty() ||
                !writePackManifest(staging, packId, normalizedTitle, normalizedAuthor, created)
            ) {
                return@synchronized PackMutationResult(null, rejectedCount = sources.size)
            }
            File(staging, OWNED_MARKER).writeText("local", Charsets.UTF_8)
            val targetDirectory = File(root, packId)
            if (!staging.renameTo(targetDirectory)) {
                return@synchronized PackMutationResult(null, rejectedCount = sources.size)
            }
            appendPackOrder(context, packId)
            invalidatePackCaches(context, packId)
            val pack = readInstalledPack(targetDirectory)
            PackMutationResult(pack, created.size, rejected)
        } catch (_: Exception) {
            PackMutationResult(null, rejectedCount = sources.size)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun copyPackAsOwned(
        context: Context,
        sourcePackId: String,
        title: String,
        author: String,
    ): BuiltinStickerPack? = synchronized(installedPacksLock) {
        val sourcePack = findPack(context, sourcePackId) ?: return@synchronized null
        val normalizedTitle = title.trim().take(80)
        val normalizedAuthor = author.trim().take(80)
        if (normalizedTitle.isBlank() || normalizedAuthor.isBlank() ||
            sourcePack.stickers.isEmpty()
        ) {
            return@synchronized null
        }
        val root = installedPacksDirectory(context)
        val packId = uniquePackId(root, normalizedTitle)
        val staging = File(root, "${packId}_new_${System.nanoTime()}").apply { mkdirs() }
        try {
            val copied = sourcePack.stickers.mapIndexed { index, sourceSticker ->
                val stickerId = uniqueStickerId(emptyList(), index)
                val target = File(staging, fileName(BuiltinSticker(packId, stickerId, "", 0L)))
                val source = runCatching { prepareSticker(context, sourceSticker) }.getOrNull()
                    ?: return@synchronized null
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (validateWebP(target) == null) return@synchronized null
                BuiltinSticker(
                    packId = packId,
                    stickerId = stickerId,
                    emoji = sourceSticker.emoji,
                    backgroundColor = 0L,
                    localFilePath = target.absolutePath,
                )
            }
            if (!writePackManifest(
                    staging,
                    packId,
                    normalizedTitle,
                    normalizedAuthor,
                    copied,
                )
            ) {
                return@synchronized null
            }
            File(staging, OWNED_MARKER).writeText("local", Charsets.UTF_8)
            val targetDirectory = File(root, packId)
            if (!staging.renameTo(targetDirectory)) return@synchronized null
            appendPackOrder(context, packId)
            invalidatePackCaches(context, packId)
            readInstalledPack(targetDirectory)
        } catch (_: Exception) {
            null
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun addStickersToPack(
        context: Context,
        packId: String,
        sources: List<Uri>,
    ): PackMutationResult = synchronized(installedPacksLock) {
        val pack = readInstalledPack(File(installedPacksDirectory(context), safeId(packId)))
            ?: return@synchronized PackMutationResult(null, rejectedCount = sources.size)
        if (!pack.isOwned || sources.isEmpty()) {
            return@synchronized PackMutationResult(pack, rejectedCount = sources.size)
        }
        val availableSlots = (MAX_PACK_STICKERS - pack.stickers.size).coerceAtLeast(0)
        val acceptedSources = sources.take(availableSlots)
        val directory = File(installedPacksDirectory(context), pack.id)
        val additions = mutableListOf<BuiltinSticker>()
        var rejected = sources.size - acceptedSources.size
        acceptedSources.forEachIndexed { index, source ->
            val stickerId = uniqueStickerId(pack.stickers + additions, pack.stickers.size + index)
            val target = File(directory, fileName(BuiltinSticker(pack.id, stickerId, "", 0L)))
            if (normalizeStickerSource(context, source, target)) {
                additions += BuiltinSticker(
                    packId = pack.id,
                    stickerId = stickerId,
                    emoji = "",
                    backgroundColor = 0L,
                    localFilePath = target.absolutePath,
                )
            } else {
                rejected += 1
                target.delete()
            }
        }
        if (additions.isEmpty()) {
            return@synchronized PackMutationResult(pack, rejectedCount = rejected)
        }
        if (!writePackManifest(
                directory,
                pack.id,
                pack.title,
                pack.author,
                pack.stickers + additions,
            )
        ) {
            additions.forEach { it.localFilePath?.let(::File)?.delete() }
            return@synchronized PackMutationResult(pack, rejectedCount = sources.size)
        }
        invalidatePackCaches(context, pack.id)
        PackMutationResult(
            readInstalledPack(directory),
            addedCount = additions.size,
            rejectedCount = rejected,
        )
    }

    fun renameOwnedPack(context: Context, packId: String, title: String): BuiltinStickerPack? =
        synchronized(installedPacksLock) {
            val directory = File(installedPacksDirectory(context), safeId(packId))
            val pack = readInstalledPack(directory) ?: return@synchronized null
            val normalizedTitle = title.trim().take(80)
            if (!pack.isOwned || normalizedTitle.isBlank()) return@synchronized null
            if (!writePackManifest(
                    directory,
                    pack.id,
                    normalizedTitle,
                    pack.author,
                    pack.stickers,
                )
            ) {
                return@synchronized null
            }
            invalidatePackCaches(context, pack.id)
            readInstalledPack(directory)
        }

    fun removeSticker(
        context: Context,
        packId: String,
        stickerId: String,
    ): BuiltinStickerPack? = synchronized(installedPacksLock) {
        val directory = File(installedPacksDirectory(context), safeId(packId))
        val pack = readInstalledPack(directory) ?: return@synchronized null
        if (!pack.isOwned || pack.stickers.size <= 1) return@synchronized null
        val target = pack.stickers.firstOrNull { it.stickerId == stickerId }
            ?: return@synchronized null
        val remaining = pack.stickers.filterNot { it.stickerId == stickerId }
        if (!writePackManifest(directory, pack.id, pack.title, pack.author, remaining)) {
            return@synchronized null
        }
        target.localFilePath?.let(::File)?.delete()
        invalidatePackCaches(context, pack.id)
        readInstalledPack(directory)
    }

    fun moveSticker(
        context: Context,
        packId: String,
        stickerId: String,
        offset: Int,
    ): BuiltinStickerPack? = synchronized(installedPacksLock) {
        val directory = File(installedPacksDirectory(context), safeId(packId))
        val pack = readInstalledPack(directory) ?: return@synchronized null
        if (!pack.isOwned) return@synchronized null
        val fromIndex = pack.stickers.indexOfFirst { it.stickerId == stickerId }
        if (fromIndex < 0) return@synchronized null
        val reordered = moveItemByOffset(pack.stickers, fromIndex, offset)
        if (reordered === pack.stickers) return@synchronized pack
        if (!writePackManifest(directory, pack.id, pack.title, pack.author, reordered)) {
            return@synchronized null
        }
        invalidatePackCaches(context, pack.id)
        readInstalledPack(directory)
    }

    fun updateStickerEmoji(
        context: Context,
        packId: String,
        stickerId: String,
        emoji: String,
    ): BuiltinStickerPack? = synchronized(installedPacksLock) {
        val directory = File(installedPacksDirectory(context), safeId(packId))
        val pack = readInstalledPack(directory) ?: return@synchronized null
        val normalizedEmoji = emoji.trim().take(16)
        if (!pack.isOwned || normalizedEmoji.isBlank()) return@synchronized null
        val index = pack.stickers.indexOfFirst { it.stickerId == stickerId }
        if (index < 0) return@synchronized null
        val updatedStickers = pack.stickers.toMutableList().apply {
            this[index] = this[index].copy(emoji = normalizedEmoji)
        }
        if (!writePackManifest(
                directory,
                pack.id,
                pack.title,
                pack.author,
                updatedStickers,
            )
        ) {
            return@synchronized null
        }
        invalidatePackCaches(context, pack.id)
        readInstalledPack(directory)
    }

    fun deleteManagedPack(context: Context, packId: String): Boolean =
        synchronized(installedPacksLock) {
            val normalizedId = safeId(packId)
            if (builtinPacks.any { it.id == normalizedId }) return@synchronized false
            val directory = File(installedPacksDirectory(context), normalizedId)
            if (!directory.isDirectory || !directory.deleteRecursively()) return@synchronized false
            writePackOrder(context, readPackOrder(context).filterNot { it == normalizedId })
            invalidatePackCaches(context, normalizedId)
            true
        }

    fun moveManagedPack(context: Context, packId: String, offset: Int): Boolean =
        synchronized(installedPacksLock) {
            val ids = installedPacks(context).map { it.id }.toMutableList()
            val fromIndex = ids.indexOf(safeId(packId))
            if (fromIndex < 0) return@synchronized false
            val reordered = moveItemByOffset(ids, fromIndex, offset)
            if (reordered === ids) return@synchronized false
            writePackOrder(context, reordered)
            installedPacksSnapshot = null
            true
        }

    fun importPackArchive(context: Context, uri: Uri): BuiltinStickerPack? {
        return try {
            val displayName = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return null
            val safeName = File(displayName).name
            if (!isStickerPackFileName(safeName)) return null
            val temporaryDirectory =
                File(context.cacheDir, "sticker_pack_uri_${System.nanoTime()}").apply { mkdirs() }
            val temporary = File(temporaryDirectory, safeName)
            try {
                if (!copyUriWithLimit(context, uri, temporary, MAX_PACK_BYTES)) return null
                importPackArchive(context, temporary)
            } finally {
                temporaryDirectory.deleteRecursively()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun inspectWebP(bytes: ByteArray): WebPInfo? {
        if (bytes.size < 30 ||
            !bytes.matchesAscii(0, "RIFF") ||
            !bytes.matchesAscii(8, "WEBP")
        ) {
            return null
        }
        return when {
            bytes.matchesAscii(12, "VP8X") -> {
                val width = 1 + bytes.readUInt24Le(24)
                val height = 1 + bytes.readUInt24Le(27)
                WebPInfo(width, height, animated = (bytes[20].toInt() and 0x02) != 0)
            }
            bytes.matchesAscii(12, "VP8 ") &&
                bytes[23].toInt() and 0xff == 0x9d &&
                bytes[24].toInt() and 0xff == 0x01 &&
                bytes[25].toInt() and 0xff == 0x2a -> {
                val width = bytes.readUInt16Le(26) and 0x3fff
                val height = bytes.readUInt16Le(28) and 0x3fff
                WebPInfo(width, height, animated = false)
            }
            bytes.matchesAscii(12, "VP8L") &&
                bytes[20].toInt() and 0xff == 0x2f -> {
                val b1 = bytes[21].toInt() and 0xff
                val b2 = bytes[22].toInt() and 0xff
                val b3 = bytes[23].toInt() and 0xff
                val b4 = bytes[24].toInt() and 0xff
                val width = 1 + b1 + ((b2 and 0x3f) shl 8)
                val height = 1 + ((b2 and 0xc0) shr 6) + (b3 shl 2) + ((b4 and 0x0f) shl 10)
                WebPInfo(width, height, animated = false)
            }
            else -> null
        }?.takeIf { it.width in 1..MAX_DIMENSION && it.height in 1..MAX_DIMENSION }
    }

    fun validateWebP(file: File): WebPInfo? {
        val fileSize = file.length()
        if (!file.isFile || fileSize < 30L || fileSize > MAX_ANIMATED_BYTES) return null
        val header = ByteArray(30)
        val headerRead = file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            offset
        }
        if (headerRead != header.size) return null
        val info = inspectWebP(header) ?: return null
        val declaredRiffSize = header.readUInt32Le(4) + 8L
        if (declaredRiffSize != fileSize) return null
        val maxBytes = if (info.animated) MAX_ANIMATED_BYTES else MAX_STATIC_BYTES
        if (fileSize > maxBytes) return null
        if (info.animated && !hasSafeAnimationTimeline(file.readBytes())) return null
        return info
    }

    fun prepareSticker(context: Context, sticker: BuiltinSticker): File {
        sticker.localFilePath
            ?.let { File(it) }
            ?.takeIf { validateWebP(it) != null }
            ?.let { cacheSticker(it, cacheDirectory(context)) }
            ?.let { return it }
        return prepareBuiltinSticker(context, sticker)
    }

    fun prepareBuiltinSticker(context: Context, sticker: BuiltinSticker): File {
        val cacheDir = cacheDirectory(context)
        val target = File(cacheDir, fileName(sticker))
        if (validateWebP(target) != null) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        val temporary = File(cacheDir, "${target.name}.tmp")
        val bitmap = Bitmap.createBitmap(MAX_DIMENSION, MAX_DIMENSION, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = sticker.backgroundColor.toInt()
                style = Paint.Style.FILL
                setShadowLayer(18f, 0f, 10f, Color.argb(70, 0, 0, 0))
            }
            canvas.drawRoundRect(RectF(38f, 38f, 474f, 474f), 128f, 128f, background)
            val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 260f
            }
            val centerY = 256f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
            canvas.drawText(sticker.emoji, 256f, centerY, emojiPaint)
            FileOutputStream(temporary).use { output ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(format, 95, output)) { "Could not encode sticker WEBP" }
            }
        } finally {
            bitmap.recycle()
        }
        check(validateWebP(temporary) != null) { "Generated sticker failed WEBP validation" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Could not commit generated sticker" }
        trimCache(cacheDir, MAX_CACHE_BYTES, keepFile = target)
        return target
    }

    @Synchronized
    fun createPackArchive(context: Context, packId: String): File? {
        val pack = findPack(context, safeId(packId)) ?: return null
        if (pack.stickers.isEmpty() || pack.stickers.size > MAX_PACK_STICKERS) return null
        val exportDirectory = File(context.cacheDir, "sticker_pack_exports").apply { mkdirs() }
        val target = File(exportDirectory, "$PACK_FILE_PREFIX${safeId(pack.id)}$PACK_FILE_EXTENSION")
        if (target.isFile && target.length() in 1..MAX_PACK_BYTES) return target
        val temporary = File(exportDirectory, "${target.name}.tmp")
        val prepared = pack.stickers.mapNotNull { sticker ->
            runCatching { sticker to prepareSticker(context, sticker) }.getOrNull()
        }
        if (prepared.size != pack.stickers.size ||
            prepared.sumOf { it.second.length() } > MAX_PACK_BYTES
        ) {
            return null
        }

        val manifest = JSONObject().apply {
            put("format", 1)
            put("pack_id", safeId(pack.id))
            put("title", pack.title.take(80))
            put("author", pack.author.take(80))
            put("stickers", JSONArray().apply {
                prepared.forEach { (sticker, file) ->
                    put(JSONObject().apply {
                        put("sticker_id", safeId(sticker.stickerId))
                        put("emoji", sticker.emoji.take(16))
                        put("file", fileName(sticker))
                        put("sha256", sha256(file))
                    })
                }
            })
        }
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            prepared.forEach { (sticker, file) ->
                zip.putNextEntry(ZipEntry(fileName(sticker)))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (temporary.length() !in 1..MAX_PACK_BYTES) {
            temporary.delete()
            return null
        }
        if (target.exists()) target.delete()
        return if (temporary.renameTo(target)) target else null
    }

    fun importPackArchive(context: Context, archive: File): BuiltinStickerPack? =
        synchronized(installedPacksLock) {
            unpackPackArchive(context, archive, install = true)
        }

    fun cachePeerPackPreview(context: Context, archive: File): BuiltinStickerPack? =
        synchronized(installedPacksLock) {
            unpackPackArchive(context, archive, install = false)
        }

    internal fun clearPeerPackPreview(context: Context, packId: String): Boolean {
        val directory = File(peerPackPreviewDirectory(context), safeId(packId))
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun unpackPackArchive(
        context: Context,
        archive: File,
        install: Boolean,
    ): BuiltinStickerPack? {
        if (!isStickerPackFileName(archive.name) ||
            !archive.isFile ||
            archive.length() !in 1..MAX_PACK_BYTES
        ) {
            return null
        }
        val stagingRoot = File(context.cacheDir, "sticker_pack_imports").apply { mkdirs() }
        val staging = File(stagingRoot, "import_${System.nanoTime()}").apply { mkdirs() }
        var totalUncompressed = 0L
        var entryCount = 0
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_PACK_STICKERS + 1 || entry.isDirectory) return null
                    val name = File(entry.name).name
                    if (name != entry.name ||
                        (name != "pack.json" && !isStickerFileName(name))
                    ) {
                        return null
                    }
                    val output = File(staging, name)
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalUncompressed += count
                            if (totalUncompressed > MAX_PACK_BYTES) return null
                            stream.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifestFile = File(staging, "pack.json")
            if (!manifestFile.isFile || manifestFile.length() > 128 * 1024L) return null
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (json.optInt("format") != 1) return null
            val packId = safeId(json.optString("pack_id"))
            if (packId != packIdFromArchiveFileName(archive.name)) return null
            if (builtinPacks.any { it.id == packId }) return null
            val title = json.optString("title").trim().take(80)
            val author = json.optString("author").trim().take(80)
            val stickersJson = json.optJSONArray("stickers") ?: return null
            if (title.isBlank() || author.isBlank() ||
                stickersJson.length() !in 1..MAX_PACK_STICKERS
            ) {
                return null
            }
            val seenIds = mutableSetOf<String>()
            for (index in 0 until stickersJson.length()) {
                val item = stickersJson.optJSONObject(index) ?: return null
                val stickerId = safeId(item.optString("sticker_id"))
                if (!seenIds.add(stickerId)) return null
                val fileName = File(item.optString("file")).name
                if (fileName != item.optString("file") || !isStickerFileName(fileName)) return null
                val file = File(staging, fileName)
                if (validateWebP(file) == null ||
                    !sha256(file).equals(item.optString("sha256"), ignoreCase = true)
                ) {
                    return null
                }
            }
            val destinationRoot = if (install) {
                installedPacksDirectory(context)
            } else {
                peerPackPreviewDirectory(context)
            }
            val packDirectory = File(destinationRoot, packId)
            if (install && File(packDirectory, OWNED_MARKER).isFile) return null
            val replacement = File(destinationRoot, "${packId}_new_${System.nanoTime()}")
            if (replacement.exists()) replacement.deleteRecursively()
            if (!staging.renameTo(replacement)) return null
            if (packDirectory.exists()) packDirectory.deleteRecursively()
            if (!replacement.renameTo(packDirectory)) return null
            if (install) {
                File(
                    File(context.cacheDir, "sticker_pack_exports"),
                    "$PACK_FILE_PREFIX$packId$PACK_FILE_EXTENSION",
                ).delete()
                appendPackOrder(context, packId)
                installedPacksSnapshot = null
            } else {
                packDirectory.setLastModified(System.currentTimeMillis())
                trimPeerPackPreviews(destinationRoot, keepPackId = packId)
            }
            return readInstalledPack(packDirectory)
        } catch (_: Exception) {
            return null
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Validates an incoming sticker before copying it out of the generic
     * downloads directory. A content hash prevents collisions and the original
     * marker suffix preserves sticker semantics when a message is forwarded.
     */
    fun cacheIncomingSticker(context: Context, incoming: File): File? {
        return cacheSticker(
            incoming,
            receivedCacheDirectory(context),
            receivedCacheLimitBytes(context),
        )
    }

    private fun cacheSticker(
        incoming: File,
        cacheDir: File,
        maxCacheBytes: Long = MAX_CACHE_BYTES,
    ): File? {
        if (!isStickerFileName(incoming.name) || validateWebP(incoming) == null) return null
        val digest = sha256(incoming)
        val target = File(
            cacheDir,
            "${incoming.nameWithoutExtension}_${digest.take(20)}.webp",
        )
        if (!target.isFile || validateWebP(target) == null) {
            val temporary = File(cacheDir, "${target.name}.tmp")
            incoming.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (validateWebP(temporary) == null) {
                temporary.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
        }
        target.setLastModified(System.currentTimeMillis())
        trimCache(cacheDir, maxCacheBytes, keepFile = target)
        return target
    }

    fun trimReceivedCache(context: Context): Long =
        trimCache(
            receivedCacheDirectory(context),
            receivedCacheLimitBytes(context),
        )

    private fun receivedCacheLimitBytes(context: Context): Long =
        P2PPreferences.stickerCacheLimitMb(context) * 1024L * 1024L

    private fun cacheDirectory(context: Context): File =
        File(context.filesDir, "sticker_cache").apply { mkdirs() }

    internal fun receivedCacheDirectory(context: Context): File =
        File(cacheDirectory(context), "received").apply { mkdirs() }

    internal fun peerPackPreviewDirectory(context: Context): File =
        File(cacheDirectory(context), "received_packs").apply { mkdirs() }

    private fun installedPacksDirectory(context: Context): File =
        File(context.filesDir, "sticker_packs").apply { mkdirs() }

    private fun trimPeerPackPreviews(root: File, keepPackId: String) {
        val directories = root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != keepPackId }
            .sortedBy { it.lastModified() }
        var total = root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        directories.forEach { directory ->
            if (total <= MAX_CACHE_BYTES) return
            val size = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
            if (directory.deleteRecursively()) total -= size
        }
    }

    private fun uniquePackId(root: File, title: String): String {
        val base = safeId(title).take(32)
        var candidate = base
        var suffix = 2
        while (File(root, candidate).exists() || builtinPacks.any { it.id == candidate }) {
            candidate = "${base.take(35)}_${suffix++}".take(40)
        }
        return candidate
    }

    private fun uniqueStickerId(existing: List<BuiltinSticker>, index: Int): String {
        val used = existing.mapTo(mutableSetOf()) { it.stickerId }
        var suffix = 0
        while (true) {
            val timePart = System.nanoTime().toString(36).takeLast(8)
            val candidate = safeId("sticker_${index + 1}_${timePart}_${suffix++}")
            if (candidate !in used) return candidate
        }
    }

    private fun normalizeStickerSource(context: Context, uri: Uri, target: File): Boolean {
        val source = File(context.cacheDir, "sticker_source_${System.nanoTime()}.tmp")
        return try {
            if (!copyUriWithLimit(context, uri, source, MAX_SOURCE_BYTES)) return false
            if (validateWebP(source) != null) {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                return validateWebP(target) != null
            }
            val bitmap = decodeStickerBitmap(source) ?: return false
            try {
                encodeStickerBitmap(bitmap, target)
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            false
        } finally {
            source.delete()
            if (validateWebP(target) == null) target.delete()
        }
    }

    private fun decodeStickerBitmap(source: File): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val scale = (MAX_DIMENSION.toFloat() / maxOf(width, height)).coerceAtMost(1f)
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DIMENSION * 2) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return null
        val maxDimension = maxOf(decoded.width, decoded.height)
        if (maxDimension <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / maxDimension
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun encodeStickerBitmap(bitmap: Bitmap, target: File): Boolean {
        val formats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Bitmap.CompressFormat.WEBP_LOSSLESS to 100,
                Bitmap.CompressFormat.WEBP_LOSSY to 92,
                Bitmap.CompressFormat.WEBP_LOSSY to 84,
                Bitmap.CompressFormat.WEBP_LOSSY to 76,
                Bitmap.CompressFormat.WEBP_LOSSY to 68,
                Bitmap.CompressFormat.WEBP_LOSSY to 60,
            )
        } else {
            @Suppress("DEPRECATION")
            listOf(
                Bitmap.CompressFormat.WEBP to 92,
                Bitmap.CompressFormat.WEBP to 82,
                Bitmap.CompressFormat.WEBP to 72,
                Bitmap.CompressFormat.WEBP to 62,
            )
        }
        for ((format, quality) in formats) {
            target.outputStream().use { output ->
                if (!bitmap.compress(format, quality, output)) return@use
            }
            if (validateWebP(target) != null) return true
        }
        target.delete()
        return false
    }

    private fun copyUriWithLimit(
        context: Context,
        uri: Uri,
        target: File,
        maxBytes: Long,
    ): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        return try {
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return false
                        output.write(buffer, 0, count)
                    }
                }
            }
            target.length() in 1..maxBytes
        } catch (_: Exception) {
            false
        } finally {
            if (target.length() !in 1..maxBytes) target.delete()
        }
    }

    private fun writePackManifest(
        directory: File,
        packId: String,
        title: String,
        author: String,
        stickers: List<BuiltinSticker>,
    ): Boolean {
        if (stickers.size !in 1..MAX_PACK_STICKERS) return false
        val items = JSONArray()
        for (sticker in stickers) {
            val file = sticker.localFilePath?.let(::File) ?: return false
            if (file.parentFile != directory || validateWebP(file) == null) return false
            items.put(JSONObject().apply {
                put("sticker_id", safeId(sticker.stickerId))
                put("emoji", sticker.emoji.take(16))
                put("file", file.name)
                put("sha256", sha256(file))
            })
        }
        val manifest = JSONObject().apply {
            put("format", 1)
            put("pack_id", safeId(packId))
            put("title", title.trim().take(80))
            put("author", author.trim().take(80))
            put("stickers", items)
        }
        val target = File(directory, "pack.json")
        val temporary = File(directory, "pack.json.tmp")
        val backup = File(directory, "pack.json.bak")
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            backup.delete()
            if (target.exists() && !target.renameTo(backup)) return false
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                return false
            }
            backup.delete()
            true
        } catch (_: Exception) {
            if (!target.exists()) backup.renameTo(target)
            false
        } finally {
            temporary.delete()
        }
    }

    private fun readPackOrder(context: Context): List<String> {
        val file = File(context.filesDir, ORDER_FILE)
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val id = safeId(array.optString(index))
                    if (id !in this) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePackOrder(context: Context, ids: List<String>) {
        val target = File(context.filesDir, ORDER_FILE)
        val temporary = File(context.filesDir, "$ORDER_FILE.tmp")
        temporary.writeText(JSONArray(ids.distinct()).toString(), Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) temporary.delete()
    }

    private fun appendPackOrder(context: Context, packId: String) {
        val order = readPackOrder(context)
        if (packId !in order) writePackOrder(context, order + packId)
    }

    private fun invalidatePackCaches(context: Context, packId: String) {
        installedPacksSnapshot = null
        File(
            File(context.cacheDir, "sticker_pack_exports"),
            "$PACK_FILE_PREFIX${safeId(packId)}$PACK_FILE_EXTENSION",
        ).delete()
    }

    private fun readInstalledPack(directory: File): BuiltinStickerPack? {
        if (!directory.isDirectory) return null
        return try {
            val manifest = JSONObject(File(directory, "pack.json").readText(Charsets.UTF_8))
            if (manifest.optInt("format") != 1) return null
            val packId = safeId(manifest.optString("pack_id"))
            if (packId != directory.name) return null
            val items = manifest.optJSONArray("stickers") ?: return null
            if (items.length() !in 1..MAX_PACK_STICKERS) return null
            val stickers = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val stickerId = safeId(item.optString("sticker_id"))
                val fileName = File(item.optString("file")).name
                val file = File(directory, fileName)
                BuiltinSticker(
                    packId = packId,
                    stickerId = stickerId,
                    emoji = item.optString("emoji").take(16),
                    backgroundColor = 0x00000000,
                    localFilePath = file.absolutePath,
                ).takeIf {
                    fileName == item.optString("file") &&
                        isStickerFileName(fileName) &&
                        validateWebP(file) != null
                }
            }
            if (stickers.size != items.length()) return null
            BuiltinStickerPack(
                id = packId,
                title = manifest.optString("title").trim().take(80),
                author = manifest.optString("author").trim().take(80),
                stickers = stickers,
                isBuiltin = false,
                isOwned = File(directory, OWNED_MARKER).isFile,
            ).takeIf { it.title.isNotBlank() && it.author.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun trimCache(
        cacheDir: File,
        maxBytes: Long,
        keepFile: File? = null,
    ): Long {
        if (maxBytes < 0L) return 0L
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var total = files.sumOf { it.length() }
        var deletedBytes = 0L
        for (file in files) {
            if (total <= maxBytes) break
            if (keepFile != null && file.absolutePath == keepFile.absolutePath) continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                deletedBytes += size
            }
        }
        return deletedBytes
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun safeId(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "sticker" }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        value.indices.all { index -> offset + index < size && this[offset + index].toInt() == value[index].code }

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUInt24Le(offset: Int): Int =
        readUInt16Le(offset) or ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.readUInt32Le(offset: Int): Long =
        (readUInt24Le(offset).toLong() and 0x00ff_ffffL) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun hasSafeAnimationTimeline(bytes: ByteArray): Boolean {
        var offset = 12
        var frameCount = 0
        var durationMs = 0L
        var hasAnimationHeader = false
        while (offset + 8 <= bytes.size) {
            val chunkSizeLong = bytes.readUInt32Le(offset + 4)
            if (chunkSizeLong > Int.MAX_VALUE) return false
            val chunkSize = chunkSizeLong.toInt()
            val payloadOffset = offset + 8
            val paddedSizeLong = chunkSizeLong + (chunkSizeLong and 1L)
            if (paddedSizeLong > Int.MAX_VALUE) return false
            val paddedSize = paddedSizeLong.toInt()
            if (payloadOffset > bytes.size || paddedSize > bytes.size - payloadOffset) return false
            when {
                bytes.matchesAscii(offset, "ANIM") -> hasAnimationHeader = chunkSize >= 6
                bytes.matchesAscii(offset, "ANMF") -> {
                    if (chunkSize < 16) return false
                    val frameDuration = bytes.readUInt24Le(payloadOffset + 12)
                    if (frameDuration <= 0) return false
                    frameCount += 1
                    durationMs += frameDuration
                    if (frameCount > 90 || durationMs > 3_000L) return false
                }
            }
            offset = payloadOffset + paddedSize
        }
        return offset == bytes.size && hasAnimationHeader && frameCount in 1..90
    }
}

class StickerSendRateLimiter(
    private val maxEvents: Int = 3,
    private val windowMs: Long = 1_000L,
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        while (timestamps.isNotEmpty() && nowEpochMs - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxEvents) return false
        timestamps.addLast(nowEpochMs)
        return true
    }
}
