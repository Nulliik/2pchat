package com.example.twopchat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
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

    suspend fun fetchPreview(rawUrl: String): LinkPreviewMetadata? = withContext(Dispatchers.IO) {
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
                val protocol = urlObj.protocol?.lowercase(java.util.Locale.ROOT) ?: ""
                if (protocol != "http" && protocol != "https") {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache.put(targetUrl, fallback)
                    return@withContext fallback
                }

                val host = urlObj.host.orEmpty()
                if (isPrivateOrInternalHost(host)) {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache.put(targetUrl, fallback)
                    return@withContext fallback
                }

                connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.instanceFollowRedirects = false // Follow redirects manually
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

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
                android.util.Log.e("LinkPreview", "Failed response code $responseCode for $currentUrl")
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache.put(targetUrl, fallback)
                return@withContext fallback
            }

            val contentType = finalConnection.contentType ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("xhtml", ignoreCase = true)) {
                android.util.Log.e("LinkPreview", "Non-HTML content type: $contentType for $currentUrl")
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
                } catch (_: Exception) {}
            }
            if (!imageUrl.isNullOrBlank() && !isSafeHttpUrl(imageUrl)) imageUrl = null

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
            android.util.Log.e("LinkPreview", "Error fetching preview for $targetUrl", e)
            val fallbackHost = extractHost(targetUrl)
            val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
            cache.put(targetUrl, fallback)
            fallback
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {}
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

    internal fun isSafeHttpUrl(rawUrl: String): Boolean {
        return try {
            val url = URL(rawUrl)
            val protocol = url.protocol.lowercase(java.util.Locale.ROOT)
            (protocol == "http" || protocol == "https") && !isPrivateOrInternalHost(url.host.orEmpty())
        } catch (_: Exception) {
            false
        }
    }

    internal fun isPrivateOrInternalHost(host: String): Boolean {
        if (host.isBlank()) return true
        val lower = host.lowercase(java.util.Locale.ROOT)
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) return true
        return try {
            val addresses = java.net.InetAddress.getAllByName(host)
            addresses.any { addr ->
                addr.isLoopbackAddress ||
                addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress ||
                (addr.address.size == 16 && (addr.address[0].toInt() and 0xFE) == 0xFC)
            }
        } catch (_: Exception) {
            true
        }
    }

    suspend fun fetchImage(rawUrl: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        var currentUrl = rawUrl
        var redirects = 0
        var connection: HttpURLConnection? = null
        try {
            while (redirects <= MAX_REDIRECTS) {
                if (!isSafeHttpUrl(currentUrl)) return@withContext null
                connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 6_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "2PChat Link Preview")
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
fun rememberNetworkImage(url: String?): android.graphics.Bitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(LinkPreviewFetcher.getImageFromCache(url)) }
    if (bitmap == null) {
        LaunchedEffect(url) {
            val decoded = LinkPreviewFetcher.fetchImage(url)
            if (decoded != null) {
                LinkPreviewFetcher.putImageToCache(url, decoded)
                bitmap = decoded
            }
        }
    }
    return bitmap
}
