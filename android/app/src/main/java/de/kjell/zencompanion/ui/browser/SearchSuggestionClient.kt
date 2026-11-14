package de.kjell.zencompanion.ui.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The most of a suggest answer this client accepts, 64 KiB. */
private const val MAX_BODY_BYTES = 64 * 1024

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
                val body = connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        // A broken or hostile endpoint must not fill memory.
                        if (buffer.size() > MAX_BODY_BYTES) {
                            throw IOException("suggest body exceeds $MAX_BODY_BYTES bytes")
                        }
                    }
                    buffer.toString("UTF-8")
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (thrown: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(thrown)
            } finally {
                connection.disconnect()
            }
        }
    }
}
