package de.kjell.zencompanion.ui.browser

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
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (continuation.isActive) continuation.resume(body)
            } catch (thrown: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(thrown)
            } finally {
                connection.disconnect()
            }
        }
    }
}
