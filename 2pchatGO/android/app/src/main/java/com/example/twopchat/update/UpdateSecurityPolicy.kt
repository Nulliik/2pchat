package com.example.twopchat.update

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.ProxyConfig
import com.example.twopchat.tor.TorManager
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/**
 * Security policy for app update discovery and download (SEC-01).
 * Enforces HTTPS-only, host allowlisting, Tor SOCKS5 proxy routing with fail-closed policy,
 * and version downgrade prevention.
 */
object UpdateSecurityPolicy {

    val ALLOWED_UPDATE_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 20_000

    /**
     * Validates that an update URL uses HTTPS and points to an authorized GitHub distribution host.
     */
    fun isValidUpdateUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        val url = try {
            URL(rawUrl)
        } catch (_: Throwable) {
            return false
        }

        // Scheme MUST be HTTPS
        val protocol = url.protocol?.lowercase(Locale.ROOT) ?: return false
        if (protocol != "https") {
            return false
        }

        // Host MUST be in the allowed GitHub domain set
        val host = url.host?.lowercase(Locale.ROOT) ?: return false
        if (host !in ALLOWED_UPDATE_HOSTS) {
            return false
        }

        // Port must be default or standard HTTPS (443)
        if (url.port != -1 && url.port != 443) {
            return false
        }

        // No user info allowed in update URLs
        if (url.userInfo != null) {
            return false
        }

        return true
    }

    /**
     * Rejects updates with versionCode <= current installed versionCode (downgrade prevention).
     */
    fun isDowngrade(currentVersionCode: Long, candidateVersionCode: Long): Boolean {
        return candidateVersionCode <= currentVersionCode
    }

    /**
     * Opens an HttpURLConnection adhering to Tor / Proxy policies and URL security validation.
     * Fails closed if Tor is enabled but TorManager is not running.
     */
    fun openSecureConnection(
        rawUrl: String,
        context: Context?,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): Result<HttpURLConnection> {
        if (!isValidUpdateUrl(rawUrl)) {
            return Result.failure(SecurityException("Untrusted or insecure update URL: $rawUrl"))
        }

        val ctx = context
        val isTorEnabled = ctx?.let { P2PPreferences.isTorEnabled(it) } ?: false
        val isTorRunning = TorManager.isTorRunning.value

        if (isTorEnabled && !isTorRunning) {
            return Result.failure(
                SecurityException("Tor is enabled but Tor daemon is not running; failing closed to prevent IP leak.")
            )
        }

        return try {
            val urlObj = URL(rawUrl)
            val effectiveProxy = ctx?.let { ProxyConfig.getEffectiveProxyConfig(it) }

            val conn = if (effectiveProxy != null && effectiveProxy.enabled) {
                val socksProxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(effectiveProxy.host, effectiveProxy.port)
                )
                urlObj.openConnection(socksProxy) as HttpURLConnection
            } else {
                urlObj.openConnection() as HttpURLConnection
            }

            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false // We validate redirects manually

            Result.success(conn)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}

/**
 * Interface for verifying APK signatures when a release keystore is configured.
 */
interface ApkSignerVerifier {
    fun signerDigests(apk: File): List<ByteArray>?
    fun currentAppSignerDigests(): List<ByteArray>
    fun versionCodeOf(apk: File): Long?
}

/**
 * Verifies downloaded update artifacts before passing them to the package installer.
 */
class UpdateVerifier(
    private val signer: ApkSignerVerifier? = null,
    private val currentVersionCode: Long,
) {
    sealed class Result {
        object Ok : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(apk: File): Result {
        if (!apk.exists() || !apk.isFile || apk.length() == 0L) {
            return Result.Rejected("empty-or-missing-file")
        }

        // If a signer verifier is provided, verify certificates
        if (signer != null) {
            val archive = signer.signerDigests(apk) ?: return Result.Rejected("unreadable-signature")
            val expected = signer.currentAppSignerDigests()
            if (archive.none { a -> expected.any { it.contentEquals(a) } }) {
                return Result.Rejected("foreign-signer")
            }
            val vc = signer.versionCodeOf(apk) ?: return Result.Rejected("no-version")
            if (UpdateSecurityPolicy.isDowngrade(currentVersionCode, vc)) {
                return Result.Rejected("downgrade")
            }
        }

        return Result.Ok
    }
}
