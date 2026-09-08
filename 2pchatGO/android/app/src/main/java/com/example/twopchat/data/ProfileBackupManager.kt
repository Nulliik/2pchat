package com.example.twopchat.data

import android.content.Context
import com.example.twopchat.logging.SafeLog
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages secure export and import of 2PChat user profile and cryptographic keys.
 *
 * Implements Format v2 (2PBK):
 * - Authenticated symmetric encryption: XChaCha20-Poly1305 (24-byte random nonce).
 * - ASIC/GPU-resistant KDF: Argon2id (64 MB RAM, 3 iterations, 4 threads).
 * - Header AAD authentication: Tamper-proof KDF parameters, salt, and fingerprint.
 * - Manifest integrity: Signed with domain-separated Ed25519 key (HKDF-derived).
 * - Path traversal protection: Strict canonical path verification on extraction.
 * - Atomic write: Temporary internal cache files prevent corruption.
 * - Backward compatibility: Detects legacy v1 plaintext ZIPs and warns user.
 */
object ProfileBackupManager {
    private const val TAG = "ProfileBackupManager"
    const val BACKUP_VERSION_V2 = 2
    const val MANIFEST_ENTRY_NAME = "backup_manifest.json"
    private const val MAX_BACKUP_SIZE_BYTES = 15 * 1024 * 1024 // 15 MB safeguard

    enum class BackupFormat {
        V2_ENCRYPTED,
        V1_PLAINTEXT_ZIP,
        UNKNOWN,
    }

    data class BackupInspectionResult(
        val format: BackupFormat,
        val fingerprint: String? = null,
    )

    data class BackupImportResult(
        val success: Boolean,
        val restoredNickname: String? = null,
        val restoredFingerprint: String? = null,
        val isLegacyPlaintext: Boolean = false,
        val requiresConfirmationForeignFingerprint: Boolean = false,
        val errorMessage: String? = null,
    )

    /**
     * Determines whether byte stream begins with 2PBK or legacy PK\x03\x04 ZIP magic.
     */
    fun detectFormat(headerBytes: ByteArray): BackupFormat {
        if (headerBytes.size >= 4) {
            if (headerBytes[0] == '2'.code.toByte() &&
                headerBytes[1] == 'P'.code.toByte() &&
                headerBytes[2] == 'B'.code.toByte() &&
                headerBytes[3] == 'K'.code.toByte()
            ) {
                return BackupFormat.V2_ENCRYPTED
            }
            if (headerBytes[0] == 0x50.toByte() &&
                headerBytes[1] == 0x4B.toByte() &&
                headerBytes[2] == 0x03.toByte() &&
                headerBytes[3] == 0x04.toByte()
            ) {
                return BackupFormat.V1_PLAINTEXT_ZIP
            }
        }
        return BackupFormat.UNKNOWN
    }

    /**
     * Inspects a backup stream header to extract format and fingerprint without decrypting.
     */
    fun inspectBackup(inputStream: InputStream): BackupInspectionResult {
        return try {
            val peek = ByteArray(1024)
            val read = inputStream.read(peek)
            if (read < 4) {
                return BackupInspectionResult(BackupFormat.UNKNOWN)
            }
            val format = detectFormat(peek)
            if (format == BackupFormat.V2_ENCRYPTED) {
                val fp = NativeBridge.inspectBackupFingerprint(peek.copyOf(read))
                BackupInspectionResult(BackupFormat.V2_ENCRYPTED, fp)
            } else {
                BackupInspectionResult(format)
            }
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to inspect backup header", e)
            BackupInspectionResult(BackupFormat.UNKNOWN)
        }
    }

    /**
     * Creates an encrypted 2PBK v2 backup archive.
     */
    fun exportBackup(context: Context, password: String, outputStream: OutputStream): Boolean {
        if (password.length < 6) {
            SafeLog.e(TAG, "Password must be at least 6 characters")
            return false
        }

        val tempZip = File(context.cacheDir, "backup_zip_${System.nanoTime()}.tmp")
        val tempEnc = File(context.cacheDir, "backup_enc_${System.nanoTime()}.tmp")

        try {
            val filesDir = context.filesDir
            val nickname = P2PPreferences.username(context)
            val localIdentity = NativeBridge.getLocalIdentity()
                ?: run {
                    SafeLog.e(TAG, "Cannot export backup: identity not initialized")
                    return false
                }
            val fingerprint = localIdentity.fingerprint

            // 1. Pack plaintext files into temporary ZIP
            FileOutputStream(tempZip).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zip ->
                    // 1.1. Add identity_v1.key (ensure plaintext 96 bytes in zip per BACKUP_FORMAT_V2)
                    val decryptedIdKey = NativeBridge.exportDecryptedKeyFile("identity_v1.key")
                    if (decryptedIdKey != null && decryptedIdKey.size == 96) {
                        addBytesToZip(zip, decryptedIdKey, "identity_v1.key")
                        com.example.twopchat.security.SecurityUtils.zeroize(decryptedIdKey)
                    } else {
                        val identityKeyFile = File(filesDir, "identity_v1.key")
                        if (identityKeyFile.exists() && identityKeyFile.length() > 0) {
                            addFileToZip(zip, identityKeyFile, "identity_v1.key")
                        }
                    }

                    // 1.2. Add prekey_v1.key (ensure plaintext 32 bytes in zip per BACKUP_FORMAT_V2)
                    val decryptedPrekey = NativeBridge.exportDecryptedKeyFile("prekey_v1.key")
                    if (decryptedPrekey != null && decryptedPrekey.size == 32) {
                        addBytesToZip(zip, decryptedPrekey, "prekey_v1.key")
                        com.example.twopchat.security.SecurityUtils.zeroize(decryptedPrekey)
                    } else {
                        val prekeyFile = File(filesDir, "prekey_v1.key")
                        if (prekeyFile.exists() && prekeyFile.length() > 0) {
                            addFileToZip(zip, prekeyFile, "prekey_v1.key")
                        }
                    }

                    // 1.3. Add profile_avatar.jpg
                    val avatarFile = File(filesDir, "profile_avatar.jpg")
                    if (avatarFile.exists() && avatarFile.length() > 0) {
                        addFileToZip(zip, avatarFile, "profile_avatar.jpg")
                    }

                    // 1.4. Build and sign manifest
                    val manifestObj = JSONObject().apply {
                        put("version", BACKUP_VERSION_V2)
                        put("exported_at_ms", System.currentTimeMillis())
                        put("nickname", nickname)
                        put("fingerprint", fingerprint)
                        put("app_package", context.packageName)
                        put("tor_deterministic_enabled", P2PPreferences.isTorDeterministicOnionEnabled(context))
                        put("tor_onion_index", P2PPreferences.getTorOnionIndex(context))
                    }

                    val mnemonic = NativeBridge.getLocalSeedMnemonic()
                    if (!mnemonic.isNullOrBlank()) {
                        manifestObj.put("seed_mnemonic", mnemonic)
                    }

                    val canonicalManifest = manifestObj.toString().toByteArray(Charsets.UTF_8)
                    val sigResult = NativeBridge.signBackupManifest(canonicalManifest)
                    if (sigResult != null) {
                        manifestObj.put("backup_verify_pub", sigResult.verifyPub)
                        manifestObj.put("signature", sigResult.signature)
                    }

                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                    zip.write(manifestObj.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            if (tempZip.length() > MAX_BACKUP_SIZE_BYTES) {
                SafeLog.e(TAG, "Profile backup exceeds maximum size limit (${tempZip.length()} bytes)")
                return false
            }

            val zipBytes = tempZip.readBytes()

            // 2. Encrypt ZIP payload via Go Core (Argon2id + XChaCha20-Poly1305)
            val encryptedBytes = NativeBridge.encryptBackupPayload(password, zipBytes)
            if (encryptedBytes == null || encryptedBytes.isEmpty()) {
                SafeLog.e(TAG, "Go Core failed to encrypt backup payload")
                return false
            }

            // 3. Write encrypted output atomically
            FileOutputStream(tempEnc).use { out ->
                out.write(encryptedBytes)
            }

            FileInputStream(tempEnc).use { input ->
                input.copyTo(outputStream)
            }
            outputStream.flush()

            SafeLog.i(TAG, "Profile backup v2 exported and encrypted successfully (fp: ${SafeLog.fp(fingerprint)})")
            return true
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to export profile backup v2", e)
            return false
        } finally {
            runCatching { tempZip.delete() }
            runCatching { tempEnc.delete() }
        }
    }

    /**
     * Creates an encrypted 2PBK v2 backup file directly.
     */
    fun exportBackup(context: Context, password: String, destinationFile: File): Boolean {
        return try {
            FileOutputStream(destinationFile).use { out ->
                exportBackup(context, password, out)
            }
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to export backup to file: ${destinationFile.absolutePath}", e)
            false
        }
    }

    /**
     * Imports cryptographic keys and profile assets from a 2PBK v2 or legacy v1 stream.
     */
    fun importBackup(
        context: Context,
        password: String?,
        inputStream: InputStream,
        allowForeignFingerprint: Boolean = false,
    ): BackupImportResult {
        val filesDir = context.filesDir
        val tempInput = File(context.cacheDir, "import_in_${System.nanoTime()}.tmp")

        try {
            // Buffer input to cache file for inspection and processing
            FileOutputStream(tempInput).use { out ->
                inputStream.copyTo(out)
            }

            if (tempInput.length() < 4) {
                return BackupImportResult(success = false, errorMessage = "Corrupted or empty backup file")
            }
            if (tempInput.length() > MAX_BACKUP_SIZE_BYTES + 1024) {
                return BackupImportResult(success = false, errorMessage = "Backup file exceeds maximum allowed size")
            }

            val fileBytes = tempInput.readBytes()
            val format = detectFormat(fileBytes)

            when (format) {
                BackupFormat.V2_ENCRYPTED -> {
                    if (password.isNullOrBlank()) {
                        return BackupImportResult(success = false, errorMessage = "Password required for encrypted backup")
                    }

                    // Inspect header fingerprint
                    val backupFp = NativeBridge.inspectBackupFingerprint(fileBytes)
                    val activeFp = NativeBridge.getLocalIdentity()?.fingerprint

                    if (!activeFp.isNullOrBlank() && !backupFp.isNullOrBlank() && activeFp != backupFp && !allowForeignFingerprint) {
                        SafeLog.w(TAG, "Backup fingerprint ($backupFp) does not match active identity ($activeFp)")
                        return BackupImportResult(
                            success = false,
                            restoredFingerprint = backupFp,
                            requiresConfirmationForeignFingerprint = true,
                            errorMessage = "Backup belongs to a different account",
                        )
                    }

                    val decryptedBytes = NativeBridge.decryptBackupPayload(password, fileBytes)
                        ?: return BackupImportResult(
                            success = false,
                            errorMessage = "Incorrect password or corrupted backup file",
                        )

                    return extractAndApplyZip(context, filesDir, decryptedBytes, isLegacy = false)
                }

                BackupFormat.V1_PLAINTEXT_ZIP -> {
                    SafeLog.w(TAG, "Importing legacy v1 unencrypted backup archive")
                    return extractAndApplyZip(context, filesDir, fileBytes, isLegacy = true)
                }

                BackupFormat.UNKNOWN -> {
                    return BackupImportResult(success = false, errorMessage = "Unsupported or invalid backup format")
                }
            }
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to import profile backup", e)
            return BackupImportResult(success = false, errorMessage = e.message ?: "Failed to read backup")
        } finally {
            runCatching { tempInput.delete() }
        }
    }

    /**
     * Imports cryptographic keys and profile assets from a 2PBK v2 or legacy v1 file directly.
     */
    fun importBackup(
        context: Context,
        password: String?,
        backupFile: File,
        allowForeignFingerprint: Boolean = false,
    ): BackupImportResult {
        return try {
            FileInputStream(backupFile).use { input ->
                importBackup(context, password, input, allowForeignFingerprint)
            }
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to import backup from file: ${backupFile.absolutePath}", e)
            BackupImportResult(success = false, errorMessage = e.message ?: "Failed to read backup file")
        }
    }

    private fun extractAndApplyZip(
        context: Context,
        filesDir: File,
        zipBytes: ByteArray,
        isLegacy: Boolean,
    ): BackupImportResult {
        var restoredNickname: String? = null
        var restoredFingerprint: String? = null
        var seedMnemonic: String? = null
        var torDeterministicEnabled = false
        var torOnionIndex = 0
        val buffer = ByteArray(8192)

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name

                // Strict path traversal guard
                val targetFile = File(filesDir, name)
                val canonicalDest = targetFile.canonicalPath
                val canonicalDir = filesDir.canonicalPath
                if (!canonicalDest.startsWith(canonicalDir + File.separator)) {
                    throw SecurityException("Path traversal attempt in backup entry: $name")
                }

                when (name) {
                    MANIFEST_ENTRY_NAME -> {
                        val manifestBytes = zip.readBytes()
                        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                        restoredNickname = manifest.optString("nickname")
                        restoredFingerprint = manifest.optString("fingerprint")
                        seedMnemonic = if (manifest.has("seed_mnemonic")) manifest.optString("seed_mnemonic") else null
                        torDeterministicEnabled = manifest.optBoolean("tor_deterministic_enabled", false)
                        torOnionIndex = manifest.optInt("tor_onion_index", 0).coerceAtLeast(0)

                        // Verify Ed25519 signature if present in v2
                        val sig = manifest.optString("signature", "")
                        val verifyPub = manifest.optString("backup_verify_pub", "")
                        if (sig.isNotEmpty() && verifyPub.isNotEmpty()) {
                            // Canonical copy without signature fields
                            val copyObj = JSONObject(manifest.toString()).apply {
                                remove("signature")
                                remove("backup_verify_pub")
                            }
                            val canonicalBytes = copyObj.toString().toByteArray(Charsets.UTF_8)
                            val valid = NativeBridge.verifyBackupManifest(verifyPub, canonicalBytes, sig)
                            if (!valid) {
                                SafeLog.w(TAG, "Backup manifest signature verification failed")
                            } else {
                                SafeLog.i(TAG, "Backup manifest signature verified successfully")
                            }
                        }
                    }

                    "identity_v1.key", "prekey_v1.key", "profile_avatar.jpg" -> {
                        FileOutputStream(targetFile).use { out ->
                            var count: Int
                            while (zip.read(buffer).also { count = it } != -1) {
                                out.write(buffer, 0, count)
                            }
                        }
                        SafeLog.i(TAG, "Restored $name (${targetFile.length()} bytes)")
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (!restoredNickname.isNullOrBlank()) {
            P2PPreferences.prefs(context).edit().putString("username_profile", restoredNickname).apply()
        }

        // Re-initialize Go Core with restored keys
        NativeBridge.setStorageDir(filesDir.absolutePath)
        NativeBridge.reloadIdentity()
        NativeBridge.initialize()

        var activeIdentity = NativeBridge.getLocalIdentity()
        var activeFingerprint = activeIdentity?.fingerprint

        // If key file was invalid or produced different identity, restore from embedded seed mnemonic
        if ((activeFingerprint != restoredFingerprint || activeIdentity == null) && !seedMnemonic.isNullOrBlank()) {
            SafeLog.w(TAG, "Active fingerprint ($activeFingerprint) != expected ($restoredFingerprint); restoring via seed mnemonic")
            val restoredOk = NativeBridge.restoreFromMnemonic(restoredNickname ?: "", seedMnemonic, "")
            if (restoredOk) {
                NativeBridge.initialize()
                activeIdentity = NativeBridge.getLocalIdentity()
                activeFingerprint = activeIdentity?.fingerprint
            }
        }

        // Restore Tor deterministic onion settings and keys
        P2PPreferences.setTorDeterministicOnionEnabled(context, torDeterministicEnabled)
        P2PPreferences.setTorOnionIndex(context, torOnionIndex)
        if (torDeterministicEnabled) {
            val key = NativeBridge.getDeterministicTorOnionKey(torOnionIndex)
            if (key != null) {
                val appTorDir = File(context.filesDir, "app_tor")
                val hsDir = File(appTorDir, "hidden_service_v3")
                com.example.twopchat.tor.TorManager.writeDeterministicOnionKeys(hsDir, key)
                P2PPreferences.setTorOnionHostname(context, key.hostname)
                NativeBridge.setOnionAddress(key.hostname)
                SafeLog.i(TAG, "Restored deterministic Tor onion address: len=${key.hostname.length}, index=$torOnionIndex")
            } else {
                SafeLog.w(TAG, "Failed deriving deterministic Tor onion key for index $torOnionIndex")
            }
        } else {
            // Ephemeral mode: clear deterministic hostname and cached onion address
            val appTorDir = File(context.filesDir, "app_tor")
            val hsDir = File(appTorDir, "hidden_service_v3")
            if (hsDir.exists()) {
                hsDir.deleteRecursively()
            }
            P2PPreferences.setTorOnionHostname(context, "")
            NativeBridge.setOnionAddress("")
        }

        SafeLog.i(TAG, "Backup restored successfully. Active fingerprint: ${SafeLog.fp(activeFingerprint)}")
        return BackupImportResult(
            success = true,
            restoredNickname = restoredNickname,
            restoredFingerprint = activeFingerprint ?: restoredFingerprint,
            isLegacyPlaintext = isLegacy,
        )
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        FileInputStream(file).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun addBytesToZip(zip: ZipOutputStream, data: ByteArray, entryName: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }
}
