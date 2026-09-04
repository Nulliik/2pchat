package com.example.twopchat.ui.chat

import com.example.twopchat.logging.SafeLog

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.ProxyConfig
import com.example.twopchat.tor.TorManager
import com.example.twopchat.yggdrasil.GlobalApplication
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.Socket
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LinkPreviewMetadata(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

object LinkPreviewFetcher {
    private const val MAX_REDIRECTS = 5
    private const val MAX_HTML_CHARS = 262_144
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 512
    private val cache = object : android.util.LruCache<String, LinkPreviewMetadata>(128) {}
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16L).coerceIn(2L * 1024L * 1024L, 16L * 1024L * 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    private val META_TAG_PATTERN = Pattern.compile("<meta\\s+([^>]+)>", Pattern.CASE_INSENSITIVE)
    private val META_CONTENT_PATTERN = Pattern.compile(
        """content\s*=\s*["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE
    )

    fun getImageFromCache(url: String): android.graphics.Bitmap? = imageCache[url]
    fun putImageToCache(url: String, bitmap: android.graphics.Bitmap) {
        imageCache.put(url, bitmap)
    }

    private fun extractMetaContent(html: String, nameOrProperty: String): String? {
        val targetAttributePattern = Pattern.compile(
            """(?:name|property)\s*=\s*["']${Pattern.quote(nameOrProperty)}["']""",
            Pattern.CASE_INSENSITIVE
        )
        val metaMatcher = META_TAG_PATTERN.matcher(html)
        while (metaMatcher.find()) {
            val attributes = metaMatcher.group(1) ?: continue
            val hasTargetAttr = targetAttributePattern.matcher(attributes).find()
            
            if (hasTargetAttr) {
                val contentMatcher = META_CONTENT_PATTERN.matcher(attributes)
                if (contentMatcher.find()) {
                    return contentMatcher.group(1)
                }
            }
        }
        return null
    }

    private val HTML_TITLE_PATTERN = Pattern.compile(
        """<title[^>]*>(.*?)</title>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    suspend fun fetchPreview(rawUrl: String, context: Context? = null): LinkPreviewMetadata? = withContext(Dispatchers.IO) {
        val ctx = context ?: try { GlobalApplication.appContext } catch (_: Throwable) { null }
        val isTorEnabled = ctx?.let { P2PPreferences.isTorEnabled(it) } ?: false
        val isTorRunning = TorManager.isTorRunning.value
        if (isTorEnabled && !isTorRunning) {
            SafeLog.w("LinkPreview", "Tor is enabled but not running; suppressing link preview fetch to prevent IP leak.")
            return@withContext null
        }

        val targetUrl = if (!rawUrl.startsWith("http://", ignoreCase = true) && 
                            !rawUrl.startsWith("https://", ignoreCase = true)) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        cache[targetUrl]?.let { return@withContext it }

        var currentUrl = targetUrl
        var redirectCount = 0
        var connection: HttpURLConnection? = null
        var responseCode = 0

        try {
            while (redirectCount <= MAX_REDIRECTS) {
                val urlObj = URL(currentUrl)
                val protocol = urlObj.protocol?.lowercase(Locale.ROOT) ?: ""
                if (protocol != "http" && protocol != "https") {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache.put(targetUrl, fallback)
                    return@withContext fallback
                }

                val host = urlObj.host.orEmpty()
                val conn = openSafeConnection(urlObj, 4000, 4000, context = ctx)
                if (conn == null) {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache.put(targetUrl, fallback)
                    return@withContext fallback
                }

                connection = conn
                responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                            location
                        } else {
                            URI(currentUrl).resolve(location).toString()
                        }
                        redirectCount++
                        connection.disconnect()
                        if (redirectCount > MAX_REDIRECTS) break
                        continue
                    }
                }
                break
            }

            val finalConnection = connection ?: throw Exception("Failed to open connection")

            if (responseCode !in 200..299) {
                SafeLog.e("LinkPreview", "Failed response code $responseCode for $currentUrl")
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache.put(targetUrl, fallback)
                return@withContext fallback
            }

            val contentType = finalConnection.contentType ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("xhtml", ignoreCase = true)) {
                SafeLog.e("LinkPreview", "Non-HTML content type: $contentType for $currentUrl")
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache.put(targetUrl, fallback)
                return@withContext fallback
            }

            val reader = finalConnection.inputStream.bufferedReader(Charsets.UTF_8)
            val htmlBuilder = StringBuilder()
            val charBuffer = CharArray(4096)
            var charsRead: Int
            var totalChars = 0
            while (reader.read(charBuffer).also { charsRead = it } != -1) {
                htmlBuilder.append(charBuffer, 0, charsRead)
                totalChars += charsRead
                if (totalChars >= MAX_HTML_CHARS) {
                    break
                }
            }
            val html = htmlBuilder.toString()

            var title = extractMetaContent(html, "og:title") ?: extractMetaContent(html, "title")
            if (title.isNullOrBlank()) {
                val titleMatcher = HTML_TITLE_PATTERN.matcher(html)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)?.trim()
                }
            }

            var description = extractMetaContent(html, "og:description") ?: extractMetaContent(html, "description")
            var imageUrl = extractMetaContent(html, "og:image")
            var siteName = extractMetaContent(html, "og:site_name") ?: extractHost(targetUrl)

            title = decodeHtmlEntities(title)?.trim()
            description = decodeHtmlEntities(description)?.trim()
            if (title.isNullOrBlank() && description.isNullOrBlank()) {
                title = siteName
            }

            if (!imageUrl.isNullOrBlank() && !imageUrl.startsWith("http://", ignoreCase = true) && !imageUrl.startsWith("https://", ignoreCase = true)) {
                try {
                    val baseUri = URI(currentUrl)
                    imageUrl = baseUri.resolve(imageUrl).toString()
                } catch (_: java.net.URISyntaxException) {
                    // intentionally ignored: invalid relative image URL syntax
                } catch (_: IllegalArgumentException) {
                    // intentionally ignored: invalid URI format
                } catch (e: Exception) {
                    SafeLog.d("LinkPreview", "Failed resolving relative image URL: ${e.javaClass.simpleName}")
                }
            }
            if (!imageUrl.isNullOrBlank() && !isSafeHttpUrl(imageUrl, context = ctx)) imageUrl = null

            val result = LinkPreviewMetadata(
                url = targetUrl,
                title = title?.take(120),
                description = description?.take(200),
                imageUrl = imageUrl,
                siteName = siteName,
            )
            cache.put(targetUrl, result)
            result
        } catch (e: Exception) {
            SafeLog.e("LinkPreview", "Error fetching preview for $targetUrl", e)
            val fallbackHost = extractHost(targetUrl)
            val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
            cache.put(targetUrl, fallback)
            fallback
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                SafeLog.d("LinkPreview", "HttpURLConnection disconnect failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun extractHost(urlStr: String): String {
        return try {
            val uri = URI(urlStr)
            uri.host?.removePrefix("www.") ?: urlStr
        } catch (_: Exception) {
            urlStr
        }
    }

    private fun decodeHtmlEntities(text: String?): String? {
        if (text == null) return null
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }

    internal fun isNumericIpAddress(raw: String): Boolean {
        val clean = raw.removePrefix("[").removeSuffix("]")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return android.net.InetAddresses.isNumericAddress(clean)
        }
        if (clean.contains(':')) {
            return try {
                InetAddress.getByName(clean) is java.net.Inet6Address
            } catch (_: Exception) {
                false
            }
        }
        val parts = clean.split('.')
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    internal fun isSafeHttpUrl(rawUrl: String, context: Context? = null): Boolean {
        return try {
            val url = URL(rawUrl)
            val protocol = url.protocol.lowercase(Locale.ROOT)
            if (protocol != "http" && protocol != "https") return false
            val host = url.host.orEmpty()
            if (host.isBlank()) return false
            val lowerHost = host.lowercase(Locale.ROOT)
            if (lowerHost == "localhost" || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal") || lowerHost.endsWith(".lan")) return false

            val cleanHost = host.removePrefix("[").removeSuffix("]")
            if (isNumericIpAddress(cleanHost)) {
                val addr = try { InetAddress.getByName(cleanHost) } catch (_: Exception) { null }
                return addr != null && !isPrivateOrInternalAddress(addr)
            }

            val ctx = context ?: try { GlobalApplication.appContext } catch (_: Throwable) { null }
            val isTorEnabled = ctx?.let { P2PPreferences.isTorEnabled(it) } ?: false
            val isTorRunning = TorManager.isTorRunning.value
            if (isTorEnabled && !isTorRunning) return false

            val effectiveProxy = ctx?.let { ProxyConfig.getEffectiveProxyConfig(it) }
            if (effectiveProxy != null && effectiveProxy.enabled) {
                // In proxy/Tor mode, remote proxy resolves DNS; do not resolve locally
                return true
            }

            !isPrivateOrInternalHost(host)
        } catch (_: Exception) {
            false
        }
    }

    internal fun isPrivateOrInternalAddress(addr: java.net.InetAddress): Boolean {
        return addr.isLoopbackAddress ||
                addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress ||
                (addr.address.size == 16 && (addr.address[0].toInt() and 0xFE) == 0xFC)
    }

    internal fun isPrivateOrInternalHost(host: String): Boolean {
        if (host.isBlank()) return true
        val lower = host.lowercase(Locale.ROOT)
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".lan")) return true
        val clean = host.removePrefix("[").removeSuffix("]")
        if (isNumericIpAddress(clean)) {
            val addr = try { InetAddress.getByName(clean) } catch (_: Exception) { null }
            return addr == null || isPrivateOrInternalAddress(addr)
        }
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isEmpty() || addresses.any { isPrivateOrInternalAddress(it) }
        } catch (_: Exception) {
            true
        }
    }

    internal fun openSafeConnection(
        urlObj: URL,
        connectTimeout: Int = 4000,
        readTimeout: Int = 4000,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        context: Context? = null,
    ): HttpURLConnection? {
        val protocol = urlObj.protocol?.lowercase(Locale.ROOT) ?: ""
        if (protocol != "http" && protocol != "https") return null
        val host = urlObj.host.orEmpty()
        if (host.isBlank()) return null
        val lowerHost = host.lowercase(Locale.ROOT)
        if (lowerHost == "localhost" || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal") || lowerHost.endsWith(".lan")) return null

        val cleanHost = host.removePrefix("[").removeSuffix("]")
        if (isNumericIpAddress(cleanHost)) {
            val addr = try { InetAddress.getByName(cleanHost) } catch (_: Exception) { null }
            if (addr == null || isPrivateOrInternalAddress(addr)) return null
        }

        val ctx = context ?: try { GlobalApplication.appContext } catch (_: Throwable) { null }
        val isTorEnabled = ctx?.let { P2PPreferences.isTorEnabled(it) } ?: false
        val isTorRunning = TorManager.isTorRunning.value
        if (isTorEnabled && !isTorRunning) {
            SafeLog.w("LinkPreview", "Tor is enabled but not running; refusing to connect to prevent IP leak.")
            return null
        }

        val effectiveProxy = ctx?.let { ProxyConfig.getEffectiveProxyConfig(it) }
        if (effectiveProxy != null && effectiveProxy.enabled) {
            // SOCKS5 proxy routing (Tor or custom proxy).
            // Crucial: we do NOT resolve host via InetAddress.getAllByName(host) here!
            // The SOCKS5 proxy performs the remote DNS lookup, preventing DNS and IP leaks.
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                InetSocketAddress(effectiveProxy.host, effectiveProxy.port)
            )
            return (urlObj.openConnection(proxy) as HttpURLConnection).apply {
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                this.instanceFollowRedirects = false
                this.setRequestProperty("User-Agent", userAgent)
            }
        }

        // Clearnet mode: resolve host and pin address to prevent SSRF and DNS rebinding
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            return null
        }
        if (addresses.isEmpty() || addresses.any { isPrivateOrInternalAddress(it) }) return null

        val pinnedAddress = addresses.first()
        val port = if (urlObj.port != -1) urlObj.port else urlObj.defaultPort
        val file = urlObj.file.ifEmpty { "/" }

        val literal = pinnedAddress.hostAddress?.let(::formatIpLiteral) ?: return null
        val defaultPort = if (protocol == "https") 443 else 80
        val authority = if (port == defaultPort) literal else "$literal:$port"
        val connectionUrl = URL("$protocol://$authority$file")

        val conn = (connectionUrl.openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            this.instanceFollowRedirects = false
            this.setRequestProperty("User-Agent", userAgent)
            val hostHeader = if (port == defaultPort) host else "$host:$port"
            this.setRequestProperty("Host", hostHeader)
        }

        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = PinnedAddressSslSocketFactory(
                delegate = HttpsURLConnection.getDefaultSSLSocketFactory(),
                tlsHost = host,
                pinnedAddress = pinnedAddress,
                connectTimeoutMs = connectTimeout,
            )
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        }

        return conn
    }

    private fun formatIpLiteral(address: String): String =
        if (address.contains(':')) "[$address]" else address

    private class PinnedAddressSslSocketFactory(
        private val delegate: SSLSocketFactory,
        private val tlsHost: String,
        private val pinnedAddress: InetAddress,
        private val connectTimeoutMs: Int,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun connect(port: Int, localAddress: InetAddress? = null, localPort: Int = 0): Socket {
            val plain = Socket()
            if (localAddress != null) plain.bind(InetSocketAddress(localAddress, localPort))
            plain.connect(InetSocketAddress(pinnedAddress, port), connectTimeoutMs)
            return delegate.createSocket(plain, tlsHost, port, true)
        }

        override fun createSocket(host: String, port: Int): Socket = connect(port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = connect(port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = connect(port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = connect(port, localAddress, localPort)

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            // Never reuse a socket which HttpURLConnection may have opened
            // after performing its own DNS lookup.
            socket.close()
            return connect(port)
        }
    }

    suspend fun fetchImage(rawUrl: String, context: Context? = null): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val ctx = context ?: try { GlobalApplication.appContext } catch (_: Throwable) { null }
        val isTorEnabled = ctx?.let { P2PPreferences.isTorEnabled(it) } ?: false
        val isTorRunning = TorManager.isTorRunning.value
        if (isTorEnabled && !isTorRunning) {
            SafeLog.w("LinkPreview", "Tor is enabled but not running; suppressing image fetch to prevent IP leak.")
            return@withContext null
        }

        var currentUrl = rawUrl
        var redirects = 0
        var connection: HttpURLConnection? = null
        try {
            while (redirects <= MAX_REDIRECTS) {
                val conn = openSafeConnection(URL(currentUrl), 6_000, 6_000, "2PChat Link Preview", context = ctx)
                    ?: return@withContext null
                connection = conn
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    connection.disconnect()
                    redirects++
                    continue
                }
                if (responseCode !in 200..299) return@withContext null
                if (!connection.contentType.orEmpty().startsWith("image/", ignoreCase = true)) return@withContext null
                if (connection.contentLengthLong > MAX_IMAGE_BYTES) return@withContext null

                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) return@withContext null
                        output.write(buffer, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION ||
                    bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION) {
                    sampleSize *= 2
                }
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize },
                )
            }
            null
        } catch (_: OutOfMemoryError) {
            imageCache.evictAll()
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

@Composable
fun rememberNetworkImage(url: String?, context: Context? = null): android.graphics.Bitmap? {
    if (url.isNullOrBlank()) return null
    val localCtx = context ?: androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember<androidx.compose.runtime.MutableState<android.graphics.Bitmap?>>(url) { mutableStateOf(LinkPreviewFetcher.getImageFromCache(url)) }
    if (bitmap == null) {
        LaunchedEffect(url) {
            val decoded = LinkPreviewFetcher.fetchImage(url, localCtx)
            if (decoded != null) {
                LinkPreviewFetcher.putImageToCache(url, decoded)
                bitmap = decoded
            }
        }
    }
    return bitmap
}
