package com.example.twopchat.ui.chat

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

    private val OG_TITLE_PATTERN = Pattern.compile(
        """<meta\s+(?:[^>]*?\s+)?(?:property|name)=["'](?:og:title|title)["']\s+content=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE
    )
    private val OG_TITLE_ALT_PATTERN = Pattern.compile(
        """<meta\s+content=["']([^"']+)["']\s+(?:[^>]*?\s+)?(?:property|name)=["'](?:og:title|title)["']""",
        Pattern.CASE_INSENSITIVE
    )
    private val HTML_TITLE_PATTERN = Pattern.compile(
        """<title[^>]*>(.*?)</title>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val OG_DESC_PATTERN = Pattern.compile(
        """<meta\s+(?:[^>]*?\s+)?(?:property|name)=["'](?:og:description|description)["']\s+content=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE
    )
    private val OG_DESC_ALT_PATTERN = Pattern.compile(
        """<meta\s+content=["']([^"']+)["']\s+(?:[^>]*?\s+)?(?:property|name)=["'](?:og:description|description)["']""",
        Pattern.CASE_INSENSITIVE
    )

    private val OG_IMAGE_PATTERN = Pattern.compile(
        """<meta\s+(?:[^>]*?\s+)?(?:property|name)=["']og:image["']\s+content=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE
    )
    private val OG_IMAGE_ALT_PATTERN = Pattern.compile(
        """<meta\s+content=["']([^"']+)["']\s+(?:[^>]*?\s+)?(?:property|name)=["']og:image["']""",
        Pattern.CASE_INSENSITIVE
    )

    private val OG_SITENAME_PATTERN = Pattern.compile(
        """<meta\s+(?:[^>]*?\s+)?(?:property|name)=["']og:site_name["']\s+content=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE
    )

    suspend fun fetchPreview(rawUrl: String): LinkPreviewMetadata? = withContext(Dispatchers.IO) {
        val targetUrl = if (!rawUrl.startsWith("http://", ignoreCase = true) && 
                            !rawUrl.startsWith("https://", ignoreCase = true)) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        cache[targetUrl]?.let { return@withContext it }

        var connection: HttpURLConnection? = null
        try {
            val urlObj = URL(targetUrl)
            connection = urlObj.openConnection() as HttpURLConnection
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache[targetUrl] = fallback
                return@withContext fallback
            }

            val contentType = connection.contentType ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("xhtml", ignoreCase = true)) {
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache[targetUrl] = fallback
                return@withContext fallback
            }

            val inputStream = connection.inputStream
            val buffer = ByteArray(65536)
            val bytesRead = inputStream.read(buffer)
            if (bytesRead <= 0) {
                val fallbackHost = extractHost(targetUrl)
                val fallback = LinkPreviewMetadata(url = targetUrl, siteName = fallbackHost)
                cache[targetUrl] = fallback
                return@withContext fallback
            }

            val html = String(buffer, 0, bytesRead, Charsets.UTF_8)
            var title = extractMeta(html, OG_TITLE_PATTERN) ?: extractMeta(html, OG_TITLE_ALT_PATTERN)
            if (title.isNullOrBlank()) {
                val titleMatcher = HTML_TITLE_PATTERN.matcher(html)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)?.trim()
                }
            }

            var description = extractMeta(html, OG_DESC_PATTERN) ?: extractMeta(html, OG_DESC_ALT_PATTERN)
            var imageUrl = extractMeta(html, OG_IMAGE_PATTERN) ?: extractMeta(html, OG_IMAGE_ALT_PATTERN)
            var siteName = extractMeta(html, OG_SITENAME_PATTERN) ?: extractHost(targetUrl)

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
        } catch (_: Exception) {
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

    private fun extractMeta(html: String, pattern: Pattern): String? {
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
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
}
