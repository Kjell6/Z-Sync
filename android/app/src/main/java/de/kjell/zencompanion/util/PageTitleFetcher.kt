package de.kjell.zencompanion.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Reads the start of an http(s) document and returns its browser tab title.
 * The share sheet has no WebView, so this is how a shared link gets the name
 * a browser would show. Only the first 64KB is kept: `<title>` is in `<head>`.
 */
internal suspend fun fetchRemotePageTitle(url: String): String? =
    withContext(Dispatchers.IO) { PageTitleFetcher.fetch(url) }

internal object PageTitleFetcher {
    private const val BYTE_LIMIT = 65_536
    private const val TIMEOUT_MS = 4_000
    private const val MAX_REDIRECTS = 5

    /** A browser UA: many sites omit `<title>` for clients that do not look like a browser. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    fun fetch(rawUrl: String): String? {
        var current = rawUrl.trim()
        repeat(MAX_REDIRECTS) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val connection = runCatching { uri.toURL().openConnection() as HttpURLConnection }.getOrNull()
                ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = runCatching { URI(current).resolve(location).toString() }.getOrNull() ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = connection.contentType?.lowercase().orEmpty()
                if (type.isNotEmpty() && !type.contains("html") && !type.contains("xml") && !type.contains("text/")) {
                    return null
                }
                val bytes = connection.inputStream.use { stream ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8_192)
                    while (buffer.size() < BYTE_LIMIT) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, minOf(read, BYTE_LIMIT - buffer.size()))
                    }
                    buffer.toByteArray()
                }
                val html = PageTitle.htmlString(bytes, connection.contentType)
                val title = PageTitle.extract(html, rawUrl) ?: return null
                if (PageTitle.usable(title, current) == null) return null
                return title
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
