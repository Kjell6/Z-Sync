package de.kjell.zencompanion.ui.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** One suggest response body. Throw on any failure; the caller shows no list. */
fun interface SearchSuggestionClient {
    suspend fun fetch(url: String): String
}

/**
 * Direct request to the engine. No cookie jar is installed, so this does not
 * attach WebView cookies or the Mozilla session.
 */
class LiveSearchSuggestionClient : SearchSuggestionClient {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.setRequestProperty("Accept", "application/json, text/javascript, */*")
                val code = connection.responseCode
                if (code !in 200..299) {
                    continuation.resumeWithException(IllegalStateException("suggest http $code"))
                    return@suspendCancellableCoroutine
                }
                // A suggest answer is a few hundred bytes. Like iOS, a known
                // length over the limit is refused before reading, and an
                // unknown one stops once it passes the limit.
                if (connection.contentLengthLong > MAX_BODY_BYTES) {
                    continuation.resumeWithException(IOException("suggest body too large"))
                    return@suspendCancellableCoroutine
                }
                val body = connection.inputStream.use { readLimited(it, MAX_BODY_BYTES) }
                if (continuation.isActive) continuation.resume(body)
            } catch (thrown: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(thrown)
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        /** The same 64 KiB ceiling as iOS's suggestion client. */
        const val MAX_BODY_BYTES = 64 * 1024
    }
}

/**
 * Reads [input] as UTF-8, throwing as soon as more than [limit] bytes arrive,
 * so a huge answer is never held in memory.
 */
internal fun readLimited(input: InputStream, limit: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) throw IOException("suggest body too large")
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}
