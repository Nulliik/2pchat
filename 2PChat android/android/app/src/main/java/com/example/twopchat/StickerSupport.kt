package com.example.twopchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
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
)

data class WebPInfo(
    val width: Int,
    val height: Int,
    val animated: Boolean,
)

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
        builtinPacks + installedPacksDirectory(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull(::readInstalledPack)
            .filter { installed -> builtinPacks.none { it.id == installed.id } }
            .sortedBy { it.title.lowercase() }
            .toList()

    fun findPack(context: Context, packId: String): BuiltinStickerPack? =
        builtinPacks.firstOrNull { it.id == packId } ?:
            readInstalledPack(File(installedPacksDirectory(context), safeId(packId)))

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
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_ANIMATED_BYTES) return null
        val bytes = file.readBytes()
        val info = inspectWebP(bytes) ?: return null
        val declaredRiffSize = bytes.readUInt32Le(4) + 8L
        if (declaredRiffSize != bytes.size.toLong()) return null
        val maxBytes = if (info.animated) MAX_ANIMATED_BYTES else MAX_STATIC_BYTES
        if (file.length() > maxBytes) return null
        if (info.animated && !hasSafeAnimationTimeline(bytes)) return null
        return info
    }

    fun prepareSticker(context: Context, sticker: BuiltinSticker): File {
        sticker.localFilePath
            ?.let { File(it) }
            ?.takeIf { validateWebP(it) != null }
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
        trimCache(cacheDir)
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

    fun importPackArchive(context: Context, archive: File): BuiltinStickerPack? {
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
            val packDirectory = File(installedPacksDirectory(context), packId)
            val replacement = File(installedPacksDirectory(context), "${packId}_new")
            if (replacement.exists()) replacement.deleteRecursively()
            if (!staging.renameTo(replacement)) return null
            if (packDirectory.exists()) packDirectory.deleteRecursively()
            if (!replacement.renameTo(packDirectory)) return null
            File(
                File(context.cacheDir, "sticker_pack_exports"),
                "$PACK_FILE_PREFIX$packId$PACK_FILE_EXTENSION",
            ).delete()
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
        if (!isStickerFileName(incoming.name) || validateWebP(incoming) == null) return null
        val cacheDir = cacheDirectory(context)
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
        trimCache(cacheDir)
        return target
    }

    private fun cacheDirectory(context: Context): File =
        File(context.filesDir, "sticker_cache").apply { mkdirs() }

    private fun installedPacksDirectory(context: Context): File =
        File(context.filesDir, "sticker_packs").apply { mkdirs() }

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
            ).takeIf { it.title.isNotBlank() && it.author.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun trimCache(cacheDir: File) {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
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
