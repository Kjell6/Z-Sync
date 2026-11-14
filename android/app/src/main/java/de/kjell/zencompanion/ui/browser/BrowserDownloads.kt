package de.kjell.zencompanion.ui.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.activity.result.contract.ActivityResultContract
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** True for an http or https address that names a host. */
private fun isWebUri(uri: URI): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

/**
 * The `Referer` a download may carry: only the page's origin, never its path,
 * query or fragment, and nothing on the way down to plain http (the
 * `strict-origin` policy). The downloader follows redirects on its own, and a
 * redirect seen while probing can differ from the one the download gets, so a
 * page's private query must not be in the header at all. Hotlink checks only
 * look at the site, which the origin still names.
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
    val origin = "$pageScheme://${page.host.lowercase()}"
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

// MARK: - Where the file goes

/**
 * What the system's save screen is asked for: the name it suggests and the
 * type the file has.
 */
internal data class DownloadTarget(val fileName: String, val mimeType: String)

/**
 * The system's "save to" screen. The reader picks the folder and may rename
 * the file; a cancel comes back as null, and nothing is saved.
 */
internal class CreateDownloadDocument : ActivityResultContract<DownloadTarget, Uri?>() {
    override fun createIntent(context: Context, input: DownloadTarget): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** The type the save screen is asked for: the page's, or from the file's name. */
internal fun downloadMimeType(download: BrowserDownload): String =
    download.mimeType?.takeIf { it.isNotEmpty() } ?: typeForName(announcedDownloadName(download))

/** The type of a file from its extension, or a stream of bytes when unknown. */
internal fun typeForName(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

// MARK: - What the capsule shows

/** A file the browser is showing, wherever the page that started it went. */
internal data class DownloadTask(
    val id: Long,
    val name: String,
    val state: DownloadState,
    val bytes: Long = 0,
    /** -1 when the server sent no length. */
    val total: Long = -1,
) {
    /** How far this one file has come, or null while its size is unknown. */
    val fraction: Float?
        get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Where one download stands. */
internal sealed interface DownloadState {
    /** Still arriving, or being copied into the reader's document. */
    data object Downloading : DownloadState

    /** The save screen is up: the reader is choosing the folder. */
    data object ChoosingLocation : DownloadState

    data object Saved : DownloadState

    data class Failed(val reason: DownloadFailure?) : DownloadState
}

/** Why a download did not make it, as a short line the reader can act on. */
internal enum class DownloadFailure {
    /** The connection failed or the server refused the request. */
    NETWORK,

    /** There was no room for the file. */
    STORAGE,

    /** The file could not be written where the reader chose. */
    SAVE,
}

/**
 * The downloader's reason codes, as numbers: the unit tests run against the
 * mockable framework whose constants all read 0, and these are the values
 * `DownloadManager.COLUMN_REASON` carries on a device.
 */
private const val REASON_INSUFFICIENT_SPACE = 198
private const val REASON_DEVICE_NOT_FOUND = 199

/**
 * The downloader's errors about the way to the file: ERROR_UNHANDLED_HTTP_CODE,
 * ERROR_HTTP_DATA_ERROR, ERROR_TOO_MANY_REDIRECTS and ERROR_CANNOT_RESUME.
 * Its remaining codes (ERROR_UNKNOWN, ERROR_FILE_ERROR, ERROR_FILE_ALREADY_EXISTS)
 * are about writing, which reads as a save that failed.
 */
private val REASON_NETWORK_ERRORS = setOf(1002, 1004, 1005, 1006)

/** The downloader's own reason code, as one of our short lines. */
internal fun downloadFailure(reasonCode: Int): DownloadFailure = when {
    reasonCode == REASON_INSUFFICIENT_SPACE -> DownloadFailure.STORAGE
    reasonCode == REASON_DEVICE_NOT_FOUND -> DownloadFailure.STORAGE
    // An HTTP status as the reason: the server, or the way to it, said no.
    reasonCode in 400..599 -> DownloadFailure.NETWORK
    reasonCode in REASON_NETWORK_ERRORS -> DownloadFailure.NETWORK
    else -> DownloadFailure.SAVE
}

/**
 * The line the capsule shows: which files it speaks for and what it says
 * about them. Work in flight wins over a result that is fading out, so the
 * line always names something the reader can still act on.
 */
internal data class DownloadSummary(
    val state: DownloadState,
    /** The files the line speaks for. */
    val tasks: List<DownloadTask>,
) {
    val count: Int get() = tasks.size
    val name: String get() = tasks.firstOrNull()?.name ?: ""
    val ids: List<Long> get() = tasks.map { it.id }
    val reason: DownloadFailure? get() = (state as? DownloadState.Failed)?.reason
    val fraction: Float? get() = combinedDownloadFraction(tasks)
}

internal fun downloadSummary(tasks: List<DownloadTask>): DownloadSummary? {
    if (tasks.isEmpty()) return null
    val downloading = tasks.filter { it.state == DownloadState.Downloading }
    if (downloading.isNotEmpty()) return DownloadSummary(DownloadState.Downloading, downloading)
    val choosing = tasks.filter { it.state == DownloadState.ChoosingLocation }
    if (choosing.isNotEmpty()) return DownloadSummary(DownloadState.ChoosingLocation, choosing)
    val failed = tasks.filter { it.state is DownloadState.Failed }
    if (failed.isNotEmpty()) return DownloadSummary(failed.first().state, failed)
    return DownloadSummary(DownloadState.Saved, tasks)
}

/**
 * The share of all the bytes at once, or null while any size is unknown: one
 * unknown file makes the whole line honest about not knowing.
 */
internal fun combinedDownloadFraction(tasks: List<DownloadTask>): Float? {
    if (tasks.isEmpty()) return null
    var received = 0.0
    var total = 0.0
    for (task in tasks) {
        if (task.total <= 0) return null
        received += task.bytes.coerceIn(0, task.total).toDouble()
        total += task.total.toDouble()
    }
    if (total <= 0) return null
    return (received / total).coerceIn(0.0, 1.0).toFloat()
}

/** "42" for a line that knows how far it is. */
internal fun downloadPercent(fraction: Float?): Int? =
    fraction?.let { (it.coerceIn(0f, 1f) * 100).roundToInt() }

/** How long a finished or failed file stays on screen. */
internal const val DOWNLOAD_RESULT_VISIBLE_MS = 5_000L

/** How often the system downloader is asked how far it has come. */
internal const val DOWNLOAD_POLL_MS = 500L

/** A staged file this old belongs to a session that is long over. */
private const val STAGED_LEFTOVER_MS = 24 * 60 * 60 * 1000L

/** A file the system downloader is fetching into the app's own folder. */
internal class QueuedDownload(val id: Long, val file: File)

/**
 * The downloads the browser is showing: one entry per file, owned by the
 * browser rather than by a page. A window opened only for its file can close
 * while the capsule still reports it on the page the reader is looking at.
 */
internal object BrowserDownloadCentre {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nextId = AtomicLong(1)

    /** What stops a file still arriving, kept until it is over. */
    private val cancels = HashMap<Long, () -> Unit>()

    /** A file is about to be picked a folder for; the save screen is up. */
    fun startChoosing(name: String): Long {
        val id = nextId.getAndIncrement()
        _tasks.update { it + DownloadTask(id, name, DownloadState.ChoosingLocation) }
        return id
    }

    /** The reader chose the folder: the file starts arriving now. */
    fun markDownloading(id: Long) {
        update(id) { it.copy(state = DownloadState.Downloading) }
    }

    /** How the reader stops a file that is still arriving. */
    fun setCancel(id: Long, stop: () -> Unit) {
        synchronized(cancels) { cancels[id] = stop }
    }

    fun remove(id: Long) {
        synchronized(cancels) { cancels.remove(id) }
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** The capsule's cancel: the file stops arriving and nothing is saved. */
    fun cancel(id: Long) {
        val stop = synchronized(cancels) { cancels.remove(id) }
        _tasks.update { list -> list.filterNot { it.id == id } }
        stop?.invoke()
    }

    /**
     * A web file: the system downloader fetches it into the app's own folder,
     * then it is copied into the document the reader chose. Blocks on the
     * network only off the main thread.
     */
    fun startWebDownload(context: Context, download: BrowserDownload, destination: Uri, id: Long) {
        val app = context.applicationContext
        val name = webDownloadName(download)
        markDownloading(id)
        scope.launch {
            val queued = withContext(Dispatchers.IO) {
                enqueueWebDownload(app, download, id, name)
            }
            if (queued == null) {
                fail(id, DownloadFailure.NETWORK)
                return@launch
            }
            setCancel(id) {
                app.getSystemService(DownloadManager::class.java).remove(queued.id)
                queued.file.delete()
            }
            // The reader may have cancelled while the file was being queued.
            // Both run on the main thread, so this sees every cancel so far.
            if (_tasks.value.none { it.id == id }) {
                synchronized(cancels) { cancels.remove(id) }?.invoke()
                return@launch
            }
            watch(app, queued, destination, id)
        }
    }

    fun pageFileSaved(id: Long) = saved(id)

    fun pageFileFailed(id: Long, reason: DownloadFailure?) = fail(id, reason)

    private suspend fun watch(context: Context, queued: QueuedDownload, destination: Uri, id: Long) {
        val manager = context.getSystemService(DownloadManager::class.java)
        while (true) {
            delay(DOWNLOAD_POLL_MS)
            val row = withContext(Dispatchers.IO) { readDownload(manager, queued.id) }
            if (row == null) {
                finishFailed(queued, id, DownloadFailure.SAVE)
                return
            }
            when (row.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> update(id) { it.copy(bytes = row.bytes, total = row.total) }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    val copied = withContext(Dispatchers.IO) {
                        copyToDocument(context, queued.file, destination)
                    }
                    queued.file.parentFile?.delete()
                    queued.file.delete()
                    if (copied) saved(id) else fail(id, DownloadFailure.SAVE)
                    return
                }

                else -> {
                    finishFailed(queued, id, downloadFailure(row.reason))
                    return
                }
            }
        }
    }

    private fun finishFailed(queued: QueuedDownload, id: Long, reason: DownloadFailure) {
        queued.file.delete()
        queued.file.parentFile?.delete()
        fail(id, reason)
    }

    private fun saved(id: Long) {
        update(id) { it.copy(state = DownloadState.Saved) }
        forgetLater(id)
    }

    private fun fail(id: Long, reason: DownloadFailure?) {
        update(id) { it.copy(state = DownloadState.Failed(reason)) }
        forgetLater(id)
    }

    private fun update(id: Long, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    /**
     * A file the app did not outlive stays in the staging folder. Anything a
     * day old is from an earlier session, never from a download still running.
     */
    fun clearStagedLeftovers(context: Context) {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val cutoff = System.currentTimeMillis() - STAGED_LEFTOVER_MS
        root.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.deleteRecursively()
        }
    }

    /** A result stays about five seconds, then goes. */
    private fun forgetLater(id: Long) {
        synchronized(cancels) { cancels.remove(id) }
        scope.launch {
            delay(DOWNLOAD_RESULT_VISIBLE_MS)
            _tasks.update { list -> list.filterNot { it.id == id } }
        }
    }
}

private class DownloadRow(val status: Int, val bytes: Long, val total: Long, val reason: Int)

/** How the system downloader stands on one file, or null when it forgot it. */
private fun readDownload(manager: DownloadManager, id: Long): DownloadRow? {
    val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull() ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        return DownloadRow(
            status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
        )
    }
}

/**
 * Hands a web file to the system's downloader, so it keeps going when the app
 * is closed and the system shows the progress. It writes into the app's own
 * external files folder, never into the reader's document: the downloader
 * cannot write a Storage Access Framework address, so the finished file is
 * copied there instead. The downloader sends the same headers to every
 * redirect, so it gets the file's final address and only that address's
 * cookies: a redirect to another site never sees the sign-in. Blocks on the
 * network; call it off the main thread.
 */
internal fun enqueueWebDownload(
    context: Context,
    download: BrowserDownload,
    taskId: Long,
    fileName: String,
): QueuedDownload? = runCatching {
    val target = resolveDownloadUrl(download.url) { probeDownload(it, download.userAgent) }
    // A folder of its own, so two files of the same name cannot overwrite
    // each other while they wait to be copied.
    val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), taskId.toString())
    folder.mkdirs()
    val file = File(folder, fileName)
    val request = DownloadManager.Request(Uri.parse(target ?: download.url))
        .setTitle(fileName)
        // The file waits in a private folder, so the system's notification
        // only reports progress: a finished one could not open it.
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "$taskId/$fileName")
    download.mimeType?.takeIf { it.isNotEmpty() }?.let(request::setMimeType)
    target?.let(CookieManager.getInstance()::getCookie)?.let { request.addRequestHeader("Cookie", it) }
    download.userAgent?.takeIf { it.isNotEmpty() }?.let { request.addRequestHeader("User-Agent", it) }
    // The policy again for where the file really comes from, so a secure page
    // whose file redirects to plain http sends no Referer at all.
    downloadReferrer(download.referer, target ?: download.url)
        ?.takeIf(::isWebDownload)
        ?.let { request.addRequestHeader("Referer", it) }
    val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
    QueuedDownload(id, file)
}.getOrNull()

/** Copies a finished file into the document the reader chose. */
internal fun copyToDocument(context: Context, file: File, destination: Uri): Boolean {
    val copied = runCatching {
        val output = context.contentResolver.openOutputStream(destination) ?: return false
        output.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
        true
    }.getOrDefault(false)
    // A half-written file is worse than none: drop the document on failure.
    if (!copied) discardDocument(context, destination)
    return copied
}

/** Deletes the document the reader chose, for a file that never completed. */
private fun discardDocument(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
}

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

/** Longest wait for a page to hand over the file it was asked to read. */
private const val PAGE_FILE_TIMEOUT_MS = 120_000L

/**
 * Receives the file [pageFileScript] reads. A page could call this itself, so
 * only a token handed out for a real download opens a file. The file is
 * written straight into the document the reader picked; a download that never
 * begins, or is cancelled, leaves no document behind.
 */
internal class PageFileBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFinished: (taskId: Long, saved: Boolean, reason: DownloadFailure?) -> Unit,
) {
    private class Pending(val uri: Uri, val taskId: Long)

    private val expected = HashMap<String, Pending>()
    private val open = HashMap<String, Pair<PageFile, Long>>()

    /** A token for the page, with the document it writes into. */
    fun expect(uri: Uri, taskId: Long): String {
        val token = UUID.randomUUID().toString()
        synchronized(this) { expected[token] = Pending(uri, taskId) }
        // A page that goes away before it reads the file must not leave an
        // empty document behind, nor a capsule that never ends.
        scope.launch {
            delay(PAGE_FILE_TIMEOUT_MS)
            val abandoned = synchronized(this) { expected.remove(token) } ?: return@launch
            discardDocument(context, abandoned.uri)
            onFinished(abandoned.taskId, false, DownloadFailure.SAVE)
        }
        return token
    }

    @JavascriptInterface
    fun begin(token: String, name: String?, mimeType: String?): Boolean {
        val pending = synchronized(this) { expected.remove(token) } ?: return false
        val file = runCatching { PageFile.inDocument(context, pending.uri) }.getOrNull()
        if (file == null) {
            discardDocument(context, pending.uri)
            onFinished(pending.taskId, false, DownloadFailure.SAVE)
            return false
        }
        synchronized(this) { open[token] = file to pending.taskId }
        return true
    }

    @JavascriptInterface
    fun chunk(token: String, base64: String) {
        val current = synchronized(this) { open[token] } ?: return
        val written = runCatching { current.first.output.write(Base64.decode(base64, Base64.DEFAULT)) }
        if (written.isFailure) close(token, saved = false)
    }

    @JavascriptInterface
    fun end(token: String) = close(token, saved = true)

    @JavascriptInterface
    fun fail(token: String) {
        val pending = synchronized(this) { expected.remove(token) }
        if (pending != null) {
            discardDocument(context, pending.uri)
            onFinished(pending.taskId, false, DownloadFailure.SAVE)
            return
        }
        close(token, saved = false)
    }

    /** The reader cancelled: nothing more is written and the document goes. */
    fun cancel(token: String) {
        val pending = synchronized(this) { expected.remove(token) }
        if (pending != null) discardDocument(context, pending.uri)
        close(token, saved = false, tell = false)
    }

    private fun close(token: String, saved: Boolean, tell: Boolean = true) {
        val current = synchronized(this) { open.remove(token) } ?: return
        val kept = current.first.finish(saved)
        if (tell) onFinished(current.second, kept, if (kept) null else DownloadFailure.SAVE)
    }
}

/** A file being written into the document the reader chose. */
private class PageFile(val output: OutputStream, private val discard: () -> Unit) {
    /** True when the file is complete and closed. */
    fun finish(saved: Boolean): Boolean {
        val closed = runCatching { output.close() }.isSuccess
        val keep = saved && closed
        if (!keep) runCatching { discard() }
        return keep
    }

    companion object {
        fun inDocument(context: Context, uri: Uri): PageFile {
            val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "no output stream" }
            return PageFile(output) { discardDocument(context, uri) }
        }
    }
}
