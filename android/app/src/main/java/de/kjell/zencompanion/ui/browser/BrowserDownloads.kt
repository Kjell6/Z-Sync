package de.kjell.zencompanion.ui.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/** What a page's download listener reports. */
data class BrowserDownload(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    /** The page the file came from. */
    val referer: String?,
)

/** A name that stays inside the Downloads folder, whatever the page suggested. */
internal fun safeDownloadName(suggested: String?, fallback: String = "download"): String {
    val name = suggested.orEmpty()
        .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trim('.')
    return name.ifEmpty { fallback }
}

/** Adds the extension of [mimeType] to a name that has none. */
internal fun nameWithExtension(name: String, extension: String?): String =
    if (extension.isNullOrEmpty() || name.substringAfterLast('.', "").isNotEmpty()) name else "$name.$extension"

/** The name the system's downloader gives a web file. */
internal fun webDownloadName(download: BrowserDownload): String = safeDownloadName(
    URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType),
)

/**
 * The name a download is asked about. A web file is named as the downloader
 * will name it; a page-local one names itself only once the page reads it, so
 * its type is the best there is to go on.
 */
internal fun announcedDownloadName(download: BrowserDownload): String {
    if (isWebDownload(download.url)) return webDownloadName(download)
    val extension = download.mimeType
        ?.takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return nameWithExtension(PAGE_LOCAL_NAME, extension)
}

/** What a page-local file is called until its page gives it a real name. */
private const val PAGE_LOCAL_NAME = "download"

/** True for addresses only the page itself can read. */
internal fun isPageLocalDownload(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme == "blob" || scheme == "data"
}

internal fun isWebDownload(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme == "http" || scheme == "https"
}

/** The port a scheme uses when the address names none. */
private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

/** The port an address really reaches, defaulted from its scheme. */
private fun effectivePort(uri: URI): Int = uri.port.takeIf { it != -1 } ?: defaultPort(uri.scheme.lowercase())

/** True for an http or https address that names a host. */
private fun isWebUri(uri: URI): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

/**
 * The `Referer` a download may carry, following the browser default
 * `strict-origin-when-cross-origin`: the page's own address within its site,
 * only the site's address elsewhere, and nothing on the way down to plain
 * http. A page's private query, or the part of the page the reader was on,
 * then stays on that page instead of reaching a third-party file host.
 */
internal fun downloadReferrer(pageUrl: String?, targetUrl: String): String? {
    // Parsed without android.net.Uri, so the policy itself is plain Java and
    // can be tested off the device.
    val page = pageUrl?.let { raw -> runCatching { URI(raw) }.getOrNull() }?.takeIf(::isWebUri) ?: return null
    val target = runCatching { URI(targetUrl) }.getOrNull() ?: return null
    val pageScheme = page.scheme.lowercase()
    val targetScheme = target.scheme?.lowercase() ?: return null
    if (pageScheme == "https" && targetScheme == "http") return null
    val port = page.port
    val sameOrigin = pageScheme == targetScheme &&
        page.host.equals(target.host, ignoreCase = true) &&
        effectivePort(page) == effectivePort(target)
    if (sameOrigin) return pageUrl.substringBefore('#')
    val host = page.host.lowercase()
    val origin = "$pageScheme://$host"
    return if (port == -1 || port == defaultPort(pageScheme)) "$origin/" else "$origin:$port/"
}

/** Where a response sends the download next, or null when it is the file. */
internal fun nextDownloadHop(url: String, status: Int, location: String?): String? {
    if (status !in 300..399 || status == HttpURLConnection.HTTP_NOT_MODIFIED || location.isNullOrEmpty()) return null
    return runCatching { URL(URL(url), location).toString() }.getOrNull()?.takeIf(::isWebDownload)
}

/**
 * Follows the redirects of [start] by hand. Null when there are too many to
 * tell where the file really is.
 */
internal fun resolveDownloadUrl(start: String, probe: (String) -> Pair<Int, String?>): String? {
    var current = start
    repeat(MAX_DOWNLOAD_REDIRECTS) {
        val (status, location) = probe(current)
        current = nextDownloadHop(current, status, location) ?: return current
    }
    return null
}

private const val MAX_DOWNLOAD_REDIRECTS = 8
private const val PROBE_TIMEOUT_MS = 10_000

/** Status and redirect of [url], asking with only that address's own cookies. */
private fun probeDownload(url: String, userAgent: String?): Pair<Int, String?> {
    fun ask(method: String): Pair<Int, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            userAgent?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("User-Agent", it) }
            return connection.responseCode to connection.getHeaderField("Location")
        } finally {
            connection.disconnect()
        }
    }
    return runCatching {
        val answer = ask("HEAD")
        // Not every server answers HEAD. Only the headers of GET are read.
        if (answer.first == 405 || answer.first == 501) ask("GET") else answer
    }.getOrDefault(0 to null)
}

/**
 * Hands a web file to the system's downloader, so it keeps going when the app
 * is closed and the system shows the progress. The downloader sends the same
 * headers to every redirect, so it gets the file's final address and only that
 * address's cookies: a redirect to another site never sees the sign-in.
 * Blocks on the network; call it off the main thread.
 */
internal fun enqueueWebDownload(context: Context, download: BrowserDownload): Boolean = runCatching {
    val name = webDownloadName(download)
    val target = resolveDownloadUrl(download.url) { probeDownload(it, download.userAgent) }
    val request = DownloadManager.Request(Uri.parse(target ?: download.url))
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    download.mimeType?.takeIf { it.isNotEmpty() }?.let(request::setMimeType)
    target?.let(CookieManager.getInstance()::getCookie)?.let { request.addRequestHeader("Cookie", it) }
    download.userAgent?.takeIf { it.isNotEmpty() }?.let { request.addRequestHeader("User-Agent", it) }
    // The policy again for where the file really comes from: a same-site
    // address that redirects to another host must not carry the page's path.
    downloadReferrer(download.referer, target ?: download.url)
        ?.takeIf(::isWebDownload)
        ?.let { request.addRequestHeader("Referer", it) }
    context.getSystemService(DownloadManager::class.java).enqueue(request)
}.isSuccess

/** Runs in the page: reads a `blob:` or `data:` address and passes it over in pieces. */
internal fun pageFileScript(url: String, token: String): String {
    val address = JSONObject.quote(url)
    val key = JSONObject.quote(token)
    return """
        (function () {
          var api = window.$PAGE_FILE_BRIDGE;
          var url = $address, token = $key;
          var name = '';
          var links = document.querySelectorAll('a[download]');
          for (var i = 0; i < links.length; i++) {
            if (links[i].href === url) name = links[i].download;
          }
          var STEP = 262144 * 3;
          fetch(url).then(function (response) {
            return response.blob();
          }).then(function (blob) {
            if (!api.begin(token, name, blob.type)) return;
            var at = 0;
            function next() {
              if (at >= blob.size) { api.end(token); return; }
              var reader = new FileReader();
              reader.onerror = function () { api.fail(token); };
              reader.onload = function () {
                var text = reader.result;
                api.chunk(token, text.slice(text.indexOf(',') + 1));
                at += STEP;
                next();
              };
              reader.readAsDataURL(blob.slice(at, at + STEP));
            }
            next();
          }).catch(function () { api.fail(token); });
        })()
    """.trimIndent()
}

internal const val PAGE_FILE_BRIDGE = "ZenPageFile"

/**
 * Receives the file [pageFileScript] reads. A page could call this itself, so
 * only a token handed out for a real download opens a file.
 */
internal class PageFileBridge(
    private val context: Context,
    private val onFinished: (saved: Boolean) -> Unit,
) {
    private val expected = HashSet<String>()
    private val open = HashMap<String, PageFile>()

    fun expect(): String = UUID.randomUUID().toString().also {
        synchronized(this) { expected += it }
    }

    @JavascriptInterface
    fun begin(token: String, name: String?, mimeType: String?): Boolean {
        val claimed = synchronized(this) { expected.remove(token) }
        if (!claimed) return false
        val mime = mimeType?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val fileName = nameWithExtension(safeDownloadName(name), extension)
        val file = runCatching { PageFile.create(context, fileName, mime) }.getOrNull()
        if (file == null) {
            onFinished(false)
            return false
        }
        synchronized(this) { open[token] = file }
        return true
    }

    @JavascriptInterface
    fun chunk(token: String, base64: String) {
        val file = synchronized(this) { open[token] } ?: return
        val written = runCatching { file.output.write(Base64.decode(base64, Base64.DEFAULT)) }
        if (written.isFailure) close(token, saved = false)
    }

    @JavascriptInterface
    fun end(token: String) = close(token, saved = true)

    @JavascriptInterface
    fun fail(token: String) {
        val claimed = synchronized(this) { expected.remove(token) }
        if (claimed) onFinished(false) else close(token, saved = false)
    }

    private fun close(token: String, saved: Boolean) {
        val file = synchronized(this) { open.remove(token) } ?: return
        onFinished(file.finish(saved))
    }
}

/** A file being written into the shared Downloads folder. */
private class PageFile(
    val output: OutputStream,
    private val publish: () -> Unit,
    private val discard: () -> Unit,
) {
    fun finish(saved: Boolean): Boolean {
        val keep = saved && runCatching { output.close() }.isSuccess
        if (!saved) runCatching { output.close() }
        return runCatching { if (keep) publish() else discard() }.isSuccess && keep
    }

    companion object {
        fun create(context: Context, name: String, mimeType: String): PageFile =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                inMediaStore(context, name, mimeType)
            } else {
                inPublicFolder(name)
            }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun inMediaStore(context: Context, name: String, mimeType: String): PageFile {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            return PageFile(
                output = checkNotNull(resolver.openOutputStream(uri)),
                publish = {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                },
                discard = { resolver.delete(uri, null, null) },
            )
        }

        /** Android 8 and 9, where the storage permission was asked for first. */
        @Suppress("DEPRECATION")
        private fun inPublicFolder(name: String): PageFile {
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            folder.mkdirs()
            val file = uniqueFile(folder, name)
            return PageFile(FileOutputStream(file), publish = {}, discard = { file.delete() })
        }

        private fun uniqueFile(folder: File, name: String): File {
            val first = File(folder, name)
            if (!first.exists()) return first
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            return generateSequence(2) { it + 1 }
                .map { File(folder, "$base ($it)$extension") }
                .first { !it.exists() }
        }
    }
}
