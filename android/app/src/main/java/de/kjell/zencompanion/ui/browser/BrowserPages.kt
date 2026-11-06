package de.kjell.zencompanion.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.kjell.zencompanion.data.BrowserSettings
import de.kjell.zencompanion.data.OpenPage
import de.kjell.zencompanion.data.OpenPagesStore
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.util.PageTitle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener

/**
 * The live side of one open page: its chrome state, its blocking and, while it
 * is awake, its WebView. Survives the Activity, so a theme change or a rotation
 * only rebuilds the WebView from [history].
 */
class PageSession(
    val id: String,
    val viewModel: MiniBrowserViewModel,
    /** The address a page without history loads when it first gets a WebView. */
    var startUrl: String,
) {
    private val main = Handler(Looper.getMainLooper())
    val blocker: PageBlocker = run {
        lateinit var created: PageBlocker
        created = PageBlocker { generation, count ->
            main.post { if (generation == created.generation) viewModel.onBlockedCountChange(count) }
        }
        created
    }

    var webView by mutableStateOf<WebView?>(null)
    /** The page as it last looked, for the overview. */
    var shot by mutableStateOf<ImageBitmap?>(null)
    /** Bumped to reload after a blocking change. */
    var blockingReload by mutableIntStateOf(0)
    /** A crashed renderer takes its WebView with it; a new key builds a fresh one. */
    var webViewGeneration by mutableIntStateOf(0)
    /** Scroll offset to put back after a reload finishes. */
    var reloadScroll: Int? = null
    /** The back and forward list while the page has no WebView. */
    var history: Bundle? = null
    /** `window.open` waiting for this page's WebView. */
    var pendingPopup: Message? = null
    /** A new page goes straight into the address field, once. */
    var opensWithSearch = false

    init {
        viewModel.documentTitle = documentTitle@{
            val view = webView ?: return@documentTitle null
            suspendCancellableCoroutine { continuation ->
                view.post {
                    if (!continuation.isActive) return@post
                    view.evaluateJavascript(PageTitle.DOCUMENT_TITLE_SCRIPT) { raw ->
                        if (continuation.isActive) {
                            continuation.resume(PageTitle.pickJsPayload(raw, view.url))
                        }
                    }
                }
            }
        }
        viewModel.readerPayload = reader@{
            val view = webView ?: return@reader null
            suspendCancellableCoroutine { continuation ->
                view.post {
                    if (!continuation.isActive) return@post
                    view.evaluateJavascript(ReaderExtractor.jsonScript) { raw ->
                        if (!continuation.isActive) return@evaluateJavascript
                        val payload = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                        continuation.resume(payload)
                    }
                }
            }
        }
    }
}

/**
 * The open pages' WebViews and chrome state, on top of [store].
 *
 * The pages live here and not in the browser screen, so they outlast it:
 * closing the browser drops the throwaway pages and leaves the kept ones. Only
 * the most recently used pages hold a WebView, listed in [live]; the others
 * sleep with their history and wake when they are shown.
 */
class BrowserPages(
    private val context: Context,
    val store: OpenPagesStore,
    private val repository: BrowserRepository,
) {
    private val sessions = HashMap<String, PageSession>()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The sessions that hold a WebView, the one on screen included. */
    val live = mutableStateListOf<PageSession>()

    /** Most recently used last; decides which WebView goes next. */
    private val recent = ArrayList<PageSession>()

    /** The session on screen. Compose state, so a swipe can end in one frame. */
    var active by mutableStateOf<PageSession?>(null)
        private set

    private var spaces: List<ZenSpaces.ZenSpace> = emptyList()
    private var currentSpaceId: String = ""

    init {
        store.onPageRemoved = { id ->
            live.removeAll { it.id == id }
            recent.removeAll { it.id == id }
            sessions.remove(id)
            shotBitmaps.remove(id)
        }
    }

    /** The spaces a pin can go to, as the browser opens. */
    fun updateSpaces(spaces: List<ZenSpaces.ZenSpace>, currentSpaceId: String) {
        this.spaces = spaces
        this.currentSpaceId = currentSpaceId
        sessions.values.forEach { it.viewModel.updateSpaces(spaces, currentSpaceId) }
    }

    fun session(id: String): PageSession? {
        sessions[id]?.let { return it }
        val page = store.page(id) ?: return null
        return makeSession(page).also { sessions[id] = it }
    }

    private fun makeSession(page: OpenPage): PageSession {
        val viewModel = MiniBrowserViewModel(
            repository = repository,
            initialUrl = page.url.ifEmpty { null },
            initialTitle = page.title,
            spaces = spaces,
            currentSpaceId = currentSpaceId,
        )
        val id = page.id
        viewModel.onPageChange = { url, title -> store.update(id, url, title) }
        viewModel.onPinStarted = { url -> store.markPinned(id, url) }
        viewModel.onPinned = { url -> store.pinned(id, url, PageTitle::sameDocument) }
        val session = PageSession(id, viewModel, page.url)
        if (page.isKept) session.history = readHistory(store.historyFile(id))
        return session
    }

    /** Opens a page and puts it on screen. */
    fun open(url: String?, title: String? = null, openedFromSpace: Boolean = false, openerId: String? = null, popup: Message? = null): PageSession {
        leaveScreen(store.activeId.value)
        val page = store.open(url.orEmpty(), title.orEmpty(), openedFromSpace, openerId)
        val session = makeSession(page)
        session.pendingPopup = popup
        session.opensWithSearch = url.isNullOrEmpty() && popup == null
        sessions[page.id] = session
        bringLive(session)
        syncActive()
        return session
    }

    fun activate(id: String) {
        val current = store.activeId.value
        if (current != id) leaveScreen(current)
        store.activate(id)
        session(id)?.let(::bringLive)
        syncActive()
    }

    private fun syncActive() {
        active = store.activeId.value?.let(::session)
    }

    /** The page leaves the screen: its sound stops and its pin goes through. */
    private fun leaveScreen(id: String?) {
        val session = id?.let { sessions[it] } ?: return
        session.viewModel.commitPendingPinSave()
        session.viewModel.collapseMenu()
        saveHistory(session)
        session.webView?.pausePage()
    }

    private fun bringLive(session: PageSession) {
        if (session !in live) live.add(session)
        recent.remove(session)
        recent.add(session)
        session.webView?.onResume()
        while (live.size > LIVE_LIMIT) {
            val oldest = recent.firstOrNull { it in live && it.id != store.activeId.value } ?: break
            recent.remove(oldest)
            live.remove(oldest)
        }
    }

    /** Wakes the pages beside the active one, so they follow the finger as real pages. */
    fun prepareNeighbours() {
        val (previous, next) = store.neighbours()
        listOfNotNull(previous, next).forEach { page ->
            session(page.id)?.let { session ->
                if (session !in live) live.add(session)
                recent.remove(session)
                recent.add(0, session)
                session.webView?.onResume()
            }
        }
        active?.let(::capture)
    }

    fun close(id: String) {
        store.close(id)?.let { next -> session(next)?.let(::bringLive) }
        syncActive()
    }

    fun closeAll() {
        store.closeAll()
        live.clear()
        recent.clear()
        sessions.clear()
        syncActive()
    }

    fun toggleKeep(id: String) {
        val session = sessions[id] ?: return
        store.toggleKeep(id, failed = session.viewModel.state.value.pageError != null)
        if (store.page(id)?.isKept == true) {
            saveHistory(session)
            capture(session)
        }
    }

    /** Back on a page a link opened, with no history left in it. */
    fun canReturnToOpener(id: String): Boolean = store.page(id)?.openerId != null

    /** Called as a WebView goes: a kept page's history stays with the page. */
    fun webViewReleased(session: PageSession, view: WebView) {
        if (session.webView === view) {
            saveHistory(session)
            session.webView = null
        }
    }

    /** The browser is closing. What happens to each page is the store's call. */
    fun leaveBrowser() {
        active?.let { capture(it) }
        sessions.values.forEach {
            it.viewModel.commitPendingPinSave()
            saveHistory(it)
            it.webView?.pausePage()
        }
        store.endSession(keepEveryPage(), ::exit)
        // Kept pages let go of their WebViews; the history is saved on release.
        live.clear()
        recent.clear()
        syncActive()
        keptShotsToDisk()
    }

    /** The app went to the background and may not come back. */
    fun appWentToBackground() {
        store.promote(keepEveryPage(), ::exit)
        sessions.values.forEach(::saveHistory)
        keptShotsToDisk()
    }

    fun closeExpired() {
        store.closeExpired(BrowserSettings.getKeptPageLifetime(context))
    }

    private fun keepEveryPage(): Boolean = BrowserSettings.getKeepEveryPage(context)

    private fun exit(page: OpenPage): OpenPagesStore.PageExit {
        val session = sessions[page.id]
        val state = session?.viewModel?.state?.value
        return OpenPagesStore.PageExit(
            failed = state?.pageError != null,
            pinnedHere = PageTitle.sameDocument(page.pinnedUrl.orEmpty(), page.url),
            browsedAway = session?.webView?.canGoBack() ?: (state?.canGoBack == true),
        )
    }

    // MARK: - History

    /** Takes the WebView's back and forward list while it still has one. */
    fun saveHistory(session: PageSession) {
        val view = session.webView ?: return
        val bundle = Bundle()
        if (runCatching { view.saveState(bundle) }.getOrNull() == null) return
        session.history = bundle
        if (store.page(session.id)?.isKept == true) {
            val file = store.historyFile(session.id)
            io.launch { writeHistory(file, bundle) }
        }
    }

    // MARK: - Pictures

    /**
     * Draws what the page shows right now. Only the WebView is drawn, so the
     * bar and menus never end up in it.
     */
    fun capture(session: PageSession) {
        val view = session.webView ?: return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        val scale = minOf(1f, SHOT_WIDTH_PX.toFloat() / width)
        val bitmap = runCatching {
            Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.RGB_565)
        }.getOrNull() ?: return
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        // A WebView draws in page coordinates; move to what is on screen.
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        runCatching { view.draw(canvas) }
        session.shot = bitmap.asImageBitmap()
        shotBitmaps[session.id] = bitmap
        if (store.page(session.id)?.isKept == true) writeShot(session.id, bitmap)
    }

    /** The latest pictures, so a page kept later can still store its own. */
    private val shotBitmaps = HashMap<String, Bitmap>()

    private fun keptShotsToDisk() {
        val kept = store.pages.value.filter { it.isKept }.map { it.id }.toSet()
        shotBitmaps.filterKeys { it in kept }.forEach { (id, bitmap) -> writeShot(id, bitmap) }
        shotBitmaps.keys.retainAll(sessions.keys)
    }

    private fun writeShot(id: String, bitmap: Bitmap) {
        val target = store.snapshotFile(id)
        io.launch {
            runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".tmp")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                temp.renameTo(target)
            }
        }
    }

    /** Loads the stored picture of a page that has none in memory. */
    fun loadShot(session: PageSession) {
        if (session.shot != null) return
        val file = store.snapshotFile(session.id)
        if (!file.exists()) return
        io.launch {
            val image = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return@launch
            launchOnMain { if (session.shot == null) session.shot = image.asImageBitmap() }
        }
    }

    private fun launchOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        /** Pages with a live WebView, the one on screen included. */
        const val LIVE_LIMIT = 4

        /** Sharp enough to stand in for the whole page while it zooms. */
        private const val SHOT_WIDTH_PX = 720

        @Volatile private var shared: BrowserPages? = null

        fun shared(context: Context, repository: BrowserRepository): BrowserPages =
            shared ?: synchronized(this) {
                shared ?: BrowserPages(
                    context.applicationContext,
                    OpenPagesStore.shared(context),
                    repository,
                ).also { shared = it }
            }

        private fun writeHistory(file: File, bundle: Bundle) {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".tmp")
                temp.writeBytes(encodeHistory(bundle))
                temp.renameTo(file)
            }
        }

        private fun readHistory(file: File): Bundle? {
            if (!file.exists()) return null
            return runCatching { decodeHistory(file.readBytes()) }.getOrNull()
        }

        /**
         * `saveState` fills a Bundle with one byte array. The array is written
         * as it is, so nothing depends on how a Bundle is stored; a file the
         * WebView no longer accepts just reopens the page at its address.
         */
        private fun encodeHistory(bundle: Bundle): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                val entries = bundle.keySet().mapNotNull { key ->
                    @Suppress("DEPRECATION")
                    (bundle.get(key) as? ByteArray)?.let { key to it }
                }
                data.writeInt(entries.size)
                for ((key, value) in entries) {
                    data.writeUTF(key)
                    data.writeInt(value.size)
                    data.write(value)
                }
            }
            return out.toByteArray()
        }

        private fun decodeHistory(raw: ByteArray): Bundle {
            val bundle = Bundle()
            DataInputStream(ByteArrayInputStream(raw)).use { data ->
                repeat(data.readInt()) {
                    val key = data.readUTF()
                    val value = ByteArray(data.readInt())
                    data.readFully(value)
                    bundle.putByteArray(key, value)
                }
            }
            return bundle
        }
    }
}

/** The page leaves the screen: sound and video stop with it. */
internal fun WebView.pausePage() {
    evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
    onPause()
}
