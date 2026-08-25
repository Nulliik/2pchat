package com.example.twopchat.group.attachments

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GroupAttachmentBlock(
    val index: Int,
    val ciphertextCid: String,
    val nonceBase64: String,
    val plaintextBytes: Int,
    val ciphertextBytes: Int,
)

data class GroupAttachmentManifest(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val plaintextSize: Long,
    val plaintextSha256: String,
    val chunkSize: Int,
    val contentKeyBase64: String,
    val blocks: List<GroupAttachmentBlock>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("attachment_id", attachmentId)
        put("file_name", File(fileName).name.take(200))
        put("mime_type", mimeType.take(160))
        put("plaintext_size", plaintextSize)
        put("plaintext_sha256", plaintextSha256)
        put("chunk_size", chunkSize)
        put("content_key", contentKeyBase64)
        put("blocks", JSONArray().apply {
            blocks.forEach { block ->
                put(JSONObject().apply {
                    put("index", block.index)
                    put("cid", block.ciphertextCid)
                    put("nonce", block.nonceBase64)
                    put("plaintext_bytes", block.plaintextBytes)
                    put("ciphertext_bytes", block.ciphertextBytes)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): GroupAttachmentManifest {
            require(json.optInt("version") == 1)
            val attachmentId = json.optString("attachment_id").validateToken(128)
            val fileName = File(json.optString("file_name")).name.take(200)
            require(fileName.isNotBlank())
            val plaintextSize = json.optLong("plaintext_size", -1L)
            require(plaintextSize in 0..MAX_ATTACHMENT_BYTES)
            val chunkSize = json.optInt("chunk_size", -1)
            require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE)
            val array = json.optJSONArray("blocks") ?: JSONArray()
            require(array.length() <= MAX_BLOCKS)
            val blocks = buildList {
                for (index in 0 until array.length()) {
                    val block = array.getJSONObject(index)
                    add(
                        GroupAttachmentBlock(
                            index = block.optInt("index", -1),
                            ciphertextCid = block.optString("cid").validateHex(64),
                            nonceBase64 = block.optString("nonce").take(64),
                            plaintextBytes = block.optInt("plaintext_bytes", -1),
                            ciphertextBytes = block.optInt("ciphertext_bytes", -1),
                        )
                    )
                }
            }
            require(blocks.map { it.index } == blocks.indices.toList())
            val expectedBlockCount = if (plaintextSize == 0L) {
                0
            } else {
                ((plaintextSize + chunkSize - 1L) / chunkSize).toInt()
            }
            require(blocks.size == expectedBlockCount)
            blocks.forEachIndexed { index, block ->
                val expectedPlaintextBytes = if (index == blocks.lastIndex) {
                    (plaintextSize - index.toLong() * chunkSize).toInt()
                } else {
                    chunkSize
                }
                require(block.plaintextBytes == expectedPlaintextBytes)
                require(block.ciphertextBytes == block.plaintextBytes + 16)
                require(block.nonceBase64.decodeBase64().size == 12)
            }
            val contentKey = json.optString("content_key").take(128)
            require(contentKey.decodeBase64().size == 32)
            return GroupAttachmentManifest(
                attachmentId = attachmentId,
                fileName = fileName,
                mimeType = json.optString("mime_type").take(160),
                plaintextSize = plaintextSize,
                plaintextSha256 = json.optString("plaintext_sha256").validateHex(64),
                chunkSize = chunkSize,
                contentKeyBase64 = contentKey,
                blocks = blocks,
            )
        }
    }
}

/**
 * Content-addressed encrypted block store used by group attachments.
 *
 * The manifest itself must only be transported inside the encrypted group
 * event.  Block files contain nonce-independent ciphertext and can therefore
 * be replicated by peers which have no access to the content key.
 */
class GroupAttachmentStore(
    private val rootDirectory: File,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private data class VerifiedBlockStamp(
        val length: Long,
        val lastModifiedMs: Long,
    )

    private val verifiedBlocks = ConcurrentHashMap<String, VerifiedBlockStamp>()

    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE)
        rootDirectory.mkdirs()
        require(rootDirectory.isDirectory) { "attachment block directory is unavailable" }
    }

    fun encrypt(
        source: File,
        mimeType: String,
    ): GroupAttachmentManifest {
        require(source.isFile) { "attachment source does not exist" }
        require(source.length() <= MAX_ATTACHMENT_BYTES) { "attachment is too large" }
        val attachmentId = UUID.randomUUID().toString()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintextDigest = MessageDigest.getInstance("SHA-256")
        val blocks = mutableListOf<GroupAttachmentBlock>()
        var totalPlaintext = 0L

        FileInputStream(source).use { input ->
            val buffer = ByteArray(chunkSize)
            var index = 0
            while (true) {
                val count = readChunk(input, buffer)
                if (count <= 0) break
                plaintextDigest.update(buffer, 0, count)
                val plaintext = buffer.copyOf(count)
                val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                val ciphertext = encryptBlock(key, nonce, attachmentId, index, plaintext)
                val cid = sha256Hex(ciphertext)
                val destination = blockFile(cid)
                if (!destination.exists()) {
                    val temporary = File(rootDirectory, ".$cid.${UUID.randomUUID()}.tmp")
                    FileOutputStream(temporary).use { it.write(ciphertext) }
                    if (!temporary.renameTo(destination)) {
                        temporary.delete()
                        if (!destination.exists()) error("failed to commit attachment block")
                    }
                }
                verifiedBlocks[cid] = destination.verifiedStamp()
                blocks += GroupAttachmentBlock(
                    index = index,
                    ciphertextCid = cid,
                    nonceBase64 = nonce.base64(),
                    plaintextBytes = count,
                    ciphertextBytes = ciphertext.size,
                )
                totalPlaintext += count
                index++
                require(index <= MAX_BLOCKS) { "attachment has too many blocks" }
            }
        }

        return GroupAttachmentManifest(
            attachmentId = attachmentId,
            fileName = source.name.take(200),
            mimeType = mimeType.take(160),
            plaintextSize = totalPlaintext,
            plaintextSha256 = plaintextDigest.digest().hex(),
            chunkSize = chunkSize,
            contentKeyBase64 = key.base64(),
            blocks = blocks,
        )
    }

    /**
     * Assemble all currently available blocks. [fetchMissing] may retrieve a
     * missing CID from any replica and must return the raw ciphertext block.
     */
    fun assemble(
        manifest: GroupAttachmentManifest,
        destination: File,
        fetchMissing: ((String) -> ByteArray?)? = null,
    ) {
        val key = manifest.contentKeyBase64.decodeBase64()
        require(key.size == 32) { "invalid attachment content key" }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            java.io.BufferedOutputStream(FileOutputStream(temporary), 64 * 1024).use { output ->
                manifest.blocks.forEach { block ->
                    val ciphertext = readBlock(block.ciphertextCid)
                        ?: run {
                        fetchMissing?.invoke(block.ciphertextCid)?.also {
                            putVerifiedBlock(block.ciphertextCid, it)
                        } ?: throw MissingGroupBlockException(block.ciphertextCid)
                    }
                    require(ciphertext.size == block.ciphertextBytes) { "attachment block size mismatch" }
                    require(sha256Hex(ciphertext) == block.ciphertextCid) {
                        "attachment block CID mismatch"
                    }
                    val nonce = block.nonceBase64.decodeBase64()
                    require(nonce.size == 12) { "invalid attachment block nonce" }
                    val plaintext = decryptBlock(
                        key,
                        nonce,
                        manifest.attachmentId,
                        block.index,
                        ciphertext,
                    )
                    require(plaintext.size == block.plaintextBytes) {
                        "attachment plaintext block size mismatch"
                    }
                    output.write(plaintext)
                    digest.update(plaintext)
                    total += plaintext.size
                }
                output.flush()
            }
            require(total == manifest.plaintextSize) { "attachment size mismatch" }
            require(digest.digest().hex() == manifest.plaintextSha256) {
                "attachment hash mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "failed to commit assembled attachment" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun missingBlocks(manifest: GroupAttachmentManifest): List<String> =
        manifest.blocks.mapNotNull { block ->
            val cid = block.ciphertextCid
            val file = blockFile(cid)
            if (!file.isFile || file.length() != block.ciphertextBytes.toLong()) {
                verifiedBlocks.remove(cid)
                cid
            } else {
                val stamp = file.verifiedStamp()
                if (verifiedBlocks[cid] == stamp || verifyBlockFile(cid, file, stamp)) {
                    null
                } else {
                    cid
                }
            }
        }

    fun readBlock(cid: String): ByteArray? {
        val normalized = cid.validateHex(64)
        val file = blockFile(normalized)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (sha256Hex(bytes) != normalized) {
            verifiedBlocks.remove(normalized)
            file.delete()
            return null
        }
        verifiedBlocks[normalized] = file.verifiedStamp()
        return bytes
    }

    fun putVerifiedBlock(cid: String, ciphertext: ByteArray) {
        val normalized = cid.validateHex(64)
        require(ciphertext.size <= MAX_CHUNK_SIZE + 32) { "attachment block too large" }
        require(sha256Hex(ciphertext) == normalized) { "attachment block CID mismatch" }
        val destination = blockFile(normalized)
        if (destination.exists() && readBlock(normalized) != null) return
        if (destination.exists() && !destination.delete()) {
            error("failed to replace corrupt attachment block")
        }
        val temporary = File(rootDirectory, ".$normalized.${UUID.randomUUID()}.tmp")
        java.io.BufferedOutputStream(FileOutputStream(temporary), 64 * 1024).use {
            it.write(ciphertext)
            it.flush()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            check(destination.exists()) { "failed to commit replicated attachment block" }
        }
        verifiedBlocks[normalized] = destination.verifiedStamp()
    }

    fun discard(manifest: GroupAttachmentManifest) {
        manifest.blocks.forEach { block ->
            verifiedBlocks.remove(block.ciphertextCid)
            blockFile(block.ciphertextCid).delete()
        }
    }

    private fun verifyBlockFile(
        cid: String,
        file: File,
        stamp: VerifiedBlockStamp,
    ): Boolean {
        val valid = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex() == cid
        }
        if (valid) {
            verifiedBlocks[cid] = stamp
        } else {
            verifiedBlocks.remove(cid)
            file.delete()
        }
        return valid
    }

    private fun File.verifiedStamp(): VerifiedBlockStamp =
        VerifiedBlockStamp(length(), lastModified())

    private fun blockFile(cid: String): File = File(rootDirectory, cid)
}

class MissingGroupBlockException(val cid: String) :
    IllegalStateException("missing attachment block $cid")

private fun encryptBlock(
    key: ByteArray,
    nonce: ByteArray,
    attachmentId: String,
    index: Int,
    plaintext: ByteArray,
): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    updateAAD("$attachmentId:$index".toByteArray(Charsets.UTF_8))
    doFinal(plaintext)
}

private fun decryptBlock(
    key: ByteArray,
    nonce: ByteArray,
    attachmentId: String,
    index: Int,
    ciphertext: ByteArray,
): ByteArray = try {
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD("$attachmentId:$index".toByteArray(Charsets.UTF_8))
        doFinal(ciphertext)
    }
} catch (error: Exception) {
    throw SecurityException("attachment block authentication failed", error)
}

fun purgeStaleDownloads(downloadsDir: File, maxAgeMs: Long = 48 * 3600 * 1000L): Int {
    if (!downloadsDir.exists() || !downloadsDir.isDirectory) return 0
    val now = System.currentTimeMillis()
    var purgedCount = 0
    downloadsDir.listFiles()?.forEach { file ->
        if (file.isFile && (now - file.lastModified()) > maxAgeMs) {
            if (file.delete()) purgedCount++
        }
    }
    return purgedCount
}

private fun readChunk(input: FileInputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val count = input.read(buffer, total, buffer.size - total)
        if (count < 0) break
        total += count
    }
    return total
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).hex()

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.decodeBase64(): ByteArray = try {
    Base64.decode(this, Base64.NO_WRAP)
} catch (error: IllegalArgumentException) {
    throw SecurityException("invalid Base64 attachment field", error)
}

private fun String.validateHex(expectedLength: Int): String = lowercase().also {
    require(it.length == expectedLength && it.all { char -> char in '0'..'9' || char in 'a'..'f' }) {
        "invalid hexadecimal field"
    }
}

private fun String.validateToken(maxLength: Int): String = also {
    require(it.isNotBlank() && it.length <= maxLength)
    require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" })
}

const val DEFAULT_CHUNK_SIZE = 512 * 1024
const val MIN_CHUNK_SIZE = 256 * 1024
// A block plus Base64/JSON framing must fit GroupWireProtocol.MAX_WIRE_BYTES.
const val MAX_CHUNK_SIZE = DEFAULT_CHUNK_SIZE
const val MAX_BLOCKS = 1024
const val MAX_ATTACHMENT_BYTES = 512L * 1024L * 1024L
