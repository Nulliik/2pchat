package com.example.twopchat.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val changelog: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: String,
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    private const val GITHUB_OWNER = "Nulliik"
    private const val GITHUB_REPO = "2pchat"
    private const val USER_AGENT = "2PChat-Android-App"

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "0.0.8.2"
        } catch (_: Throwable) {
            "0.0.8.2"
        }
    }

    fun isNewerVersion(current: String, candidate: String): Boolean {
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")
        val cleanCandidate = candidate.trim().removePrefix("v").removePrefix("V")

        val currentParts = cleanCurrent.split(".", "-").mapNotNull { it.toIntOrNull() }
        val candidateParts = cleanCandidate.split(".", "-").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, candidateParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val cand = candidateParts.getOrElse(i) { 0 }
            if (cand > curr) return true
            if (cand < curr) return false
        }
        return false
    }

    internal fun parseGitHubReleaseJson(jsonStr: String): ReleaseInfo? {
        return try {
            val root = JSONObject(jsonStr)
            val tagName = root.optString("tag_name", "").trim()
            if (tagName.isBlank()) return null

            val versionName = tagName.removePrefix("v").removePrefix("V")
            val title = root.optString("name", tagName)
            val changelog = root.optString("body", "").trim()
            val publishedAt = root.optString("published_at", "")

            val assets = root.optJSONArray("assets") ?: return null
            var apkUrl = ""
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isBlank()) return null

            ReleaseInfo(
                versionName = versionName,
                tagName = tagName,
                title = title,
                changelog = changelog,
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
                publishedAt = publishedAt,
            )
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun checkLatestRelease(
        context: Context,
        owner: String = GITHUB_OWNER,
        repo: String = GITHUB_REPO,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName(context)
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

        try {
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Error("GitHub API HTTP $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = parseGitHubReleaseJson(body)
                ?: return@withContext UpdateCheckResult.Error("No valid APK found in latest release")

            if (isNewerVersion(currentVersion, release.versionName)) {
                UpdateCheckResult.UpdateAvailable(release, currentVersion)
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to connect to update server")
        }
    }

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "apk_updates").apply {
                if (!exists()) mkdirs()
            }
            val destinationFile = File(updatesDir, "2pchat-update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }

            // Handle GitHub direct redirects
            val finalConn = if (connection.responseCode in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            } else {
                connection
            }

            val totalBytes = finalConn.contentLengthLong
            var bytesDownloaded = 0L

            finalConn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    var lastProgressTime = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime > 100L || bytesDownloaded == totalBytes) {
                            lastProgressTime = now
                            val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                            withContext(Dispatchers.Main) {
                                onProgress(bytesDownloaded, totalBytes, progress)
                            }
                        }
                    }
                }
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists() || apkFile.length() <= 0) return false

            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
