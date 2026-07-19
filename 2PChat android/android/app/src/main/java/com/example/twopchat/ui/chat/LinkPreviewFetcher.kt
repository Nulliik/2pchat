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
import java.util.concurrent.ConcurrentHashMap
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
    private val cache = ConcurrentHashMap<String, LinkPreviewMetadata>()
    private val imageCache = ConcurrentHashMap<String, android.graphics.Bitmap>()

    fun getImageFromCache(url: String): android.graphics.Bitmap? = imageCache[url]
    fun putImageToCache(url: String, bitmap: android.graphics.Bitmap) {
        imageCache[url] = bitmap
    }

    private fun extractMetaContent(html: String, nameOrProperty: String): String? {
        val metaMatcher = Pattern.compile("<meta\\s+([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(html)
        while (metaMatcher.find()) {
            val attributes = metaMatcher.group(1) ?: continue
            val hasTargetAttr = Pattern.compile(
                """(?:name|property)\s*=\s*["']${Pattern.quote(nameOrProperty)}["']""",
                Pattern.CASE_INSENSITIVE
            ).matcher(attributes).find()
            
            if (hasTargetAttr) {
                val contentMatcher = Pattern.compile(
                    """content\s*=\s*["']([^"']+)["']""",
                    Pattern.CASE_INSENSITIVE
                ).matcher(attributes)
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
            while (redirectCount < 5) {
                val urlObj = URL(currentUrl)
                val protocol = urlObj.protocol?.lowercase(java.util.Locale.ROOT) ?: ""
                if (protocol != "http" && protocol != "https") {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache[targetUrl] = fallback
                    return@withContext fallback
                }

                val host = urlObj.host.orEmpty()
                if (isPrivateOrInternalHost(host)) {
                    val fallbackHost = extractHost(targetUrl)
                    val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                    cache[targetUrl] = fallback
                    return@withContext fallback
                }

                connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.instanceFollowRedirects = false // Follow redirects manually
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")

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
                cache[targetUrl] = fallback
                return@withContext fallback
            }

            val contentType = finalConnection.contentType ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("xhtml", ignoreCase = true)) {
                android.util.Log.e("LinkPreview", "Non-HTML content type: $contentType for $currentUrl")
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache[targetUrl] = fallback
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
                if (totalChars >= 262144) { // limit to 256KB
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
                    val baseUri = URI(targetUrl)
                    imageUrl = baseUri.resolve(imageUrl).toString()
                } catch (_: Exception) {}
            }

            val result = LinkPreviewMetadata(
                url = targetUrl,
                title = title?.take(120),
                description = description?.take(200),
                imageUrl = imageUrl,
                siteName = siteName,
            )
            cache[targetUrl] = result
            result
        } catch (e: Exception) {
            android.util.Log.e("LinkPreview", "Error fetching preview for $targetUrl", e)
            val fallbackHost = extractHost(targetUrl)
            val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
            cache[targetUrl] = fallback
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

    private fun isPrivateOrInternalHost(host: String): Boolean {
        if (host.isBlank()) return true
        val lower = host.lowercase(java.util.Locale.ROOT)
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) return true
        return try {
            val addresses = java.net.InetAddress.getAllByName(host)
            addresses.any { addr ->
                addr.isLoopbackAddress ||
                addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress
            }
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun rememberNetworkImage(url: String?): android.graphics.Bitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(LinkPreviewFetcher.getImageFromCache(url)) }
    if (bitmap == null) {
        LaunchedEffect(url) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.inputStream.use { stream ->
                        val decoded = android.graphics.BitmapFactory.decodeStream(stream)
                        if (decoded != null) {
                            LinkPreviewFetcher.putImageToCache(url, decoded)
                            bitmap = decoded
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
    return bitmap
}
