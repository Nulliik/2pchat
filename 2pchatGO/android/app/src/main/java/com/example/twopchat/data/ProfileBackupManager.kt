package com.example.twopchat.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages secure export and import of 2PChat user profile and cryptographic keys.
 * Ensures Go Core identity keys (identity_v1.key, prekey_v1.key) and profile assets
 * are fully preserved across backups, migrations, and device restores.
 */
object ProfileBackupManager {
    private const val TAG = "ProfileBackupManager"
    private const val BACKUP_VERSION = 2
    private const val MANIFEST_ENTRY_NAME = "backup_manifest.json"

    data class BackupImportResult(
        val success: Boolean,
        val restoredNickname: String? = null,
        val restoredFingerprint: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Creates a ZIP archive containing all cryptographic identity keys and profile assets.
     */
    fun exportBackup(context: Context, outputStream: java.io.OutputStream): Boolean {
        return try {
            val filesDir = context.filesDir
            val nickname = P2PPreferences.username(context)
            val localIdentity = NativeBridge.getLocalIdentity()
            val fingerprint = localIdentity?.fingerprint.orEmpty()

            val manifestJson = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("exported_at_ms", System.currentTimeMillis())
                put("nickname", nickname)
                put("fingerprint", fingerprint)
                put("app_package", context.packageName)
            }.toString()

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
                // 1. Write manifest
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 2. Add identity_v1.key
                val identityKeyFile = File(filesDir, "identity_v1.key")
                if (identityKeyFile.exists() && identityKeyFile.length() > 0) {
                    addFileToZip(zip, identityKeyFile, "identity_v1.key")
                }

                // 3. Add prekey_v1.key
                val prekeyFile = File(filesDir, "prekey_v1.key")
                if (prekeyFile.exists() && prekeyFile.length() > 0) {
                    addFileToZip(zip, prekeyFile, "prekey_v1.key")
                }

                // 4. Add profile_avatar.jpg
                val avatarFile = File(filesDir, "profile_avatar.jpg")
                if (avatarFile.exists() && avatarFile.length() > 0) {
                    addFileToZip(zip, avatarFile, "profile_avatar.jpg")
                }
            }
            Log.i(TAG, "Profile and cryptographic keys exported successfully (fp: $fingerprint)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to export profile backup", e)
            false
        }
    }

    /**
     * Restores cryptographic keys and profile assets from a backup ZIP stream.
     */
    fun importBackup(context: Context, inputStream: InputStream): BackupImportResult {
        val filesDir = context.filesDir
        var restoredNickname: String? = null
        var restoredFingerprint: String? = null

        try {
            val buffer = ByteArray(8192)
            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Prevent path traversal vulnerabilities
                    if (name.contains("..") || name.startsWith("/")) {
                        entry = zip.nextEntry
                        continue
                    }

                    when (name) {
                        MANIFEST_ENTRY_NAME -> {
                            val manifestBytes = zip.readBytes()
                            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                            restoredNickname = manifest.optString("nickname")
                            restoredFingerprint = manifest.optString("fingerprint")
                        }
                        "identity_v1.key", "prekey_v1.key", "profile_avatar.jpg" -> {
                            val targetFile = File(filesDir, name)
                            FileOutputStream(targetFile).use { out ->
                                var count: Int
                                while (zip.read(buffer).also { count = it } != -1) {
                                    out.write(buffer, 0, count)
                                }
                            }
                            Log.i(TAG, "Restored $name (${targetFile.length()} bytes)")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (!restoredNickname.isNullOrBlank()) {
                P2PPreferences.prefs(context).edit().putString("username_profile", restoredNickname).apply()
            }

            // Re-initialize Go Core with the restored cryptographic keys
            NativeBridge.setStorageDir(filesDir.absolutePath)
            NativeBridge.reloadIdentity()
            NativeBridge.initialize()

            val activeIdentity = NativeBridge.getLocalIdentity()
            val activeFingerprint = activeIdentity?.fingerprint

            Log.i(TAG, "Backup import complete. Active identity fingerprint: $activeFingerprint")
            return BackupImportResult(
                success = true,
                restoredNickname = restoredNickname,
                restoredFingerprint = activeFingerprint ?: restoredFingerprint,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import profile backup", e)
            return BackupImportResult(
                success = false,
                errorMessage = e.message ?: "Failed to read backup archive",
            )
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        FileInputStream(file).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }
}
