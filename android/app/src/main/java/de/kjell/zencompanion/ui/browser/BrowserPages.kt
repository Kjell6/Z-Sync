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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.BrowserSettings
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.OpenPage
import de.kjell.zencompanion.data.OpenPagesStore
import de.kjell.zencompanion.data.historyAddress
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

private const val TITLE_LOOKUP_MS = 1_000L

/** A long article takes longer to extract than a title to look up. */
private const val READER_EXTRACTION_MS = 5_000L

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
    /** Bumped when [shot] is let go, so a load still under way does not bring it back. */
    var shotGeneration = 0
    /**
     * A card out of range asked for its picture to go while the picture was
     * still in memory or on its way to disk. Main thread only.
     */
    var shotDropWanted = false
    /** True when [shot] is the picture on disk, so letting go of it loses nothing. */
    var shotStored = false
    /** Bumped to reload after a blocking change. */
    var blockingReload by mutableIntStateOf(0)
    /** A crashed renderer takes its WebView with it; a new key builds a fresh one. */
    var webViewGeneration by mutableIntStateOf(0)
    /** Scroll offset to put back after a reload finishes. */
    var reloadScroll: Int? = null
    /** The back and forward list while the page has no WebView. */
    var history: Bundle? = null
    /** A kept page's history file has been read into [history], or never needs to be. */
    var historyLoaded = false
    /** `window.open` waiting for this page's WebView. */
    var pendingPopup: Message? = null
    /** A new page goes straight into the address field, once. */
    var opensWithSearch = false
    /** The address last put in the history, so a reload or a wake is no new visit. */
    var lastVisit: String? = null
    /** The page's open questions, oldest first. Shown while it is on screen. */
    val prompts = mutableStateListOf<BrowserPrompt>()

    /** The WebView is gone: its questions end with it. */
    fun dismissPrompts() {
        val open = prompts.toList()
        prompts.clear()
        open.forEach(BrowserPrompt::dismiss)
    }

    init {
        viewModel.documentTitle = documentTitle@{
            val view = webView ?: return@documentTitle null
            // A WebView destroyed in the meantime never answers, and the pin
            // waiting on it must still save with the title it already has.
            withTimeoutOrNull(TITLE_LOOKUP_MS) {
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
        }
        viewModel.readerPayload = reader@{
            val view = webView ?: return@reader null
            // Same as the title: a WebView destroyed mid-request never answers,
            // and the reader must not stay loading for good.
            withTimeoutOrNull(READER_EXTRACTION_MS) {
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
}

/**
 * The open pages' WebViews and chrome state, on top of [store].
 *
 * The pages live here and not in the browser screen, so they outlast it, the
 * way tabs outlast a browser window. Only
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

    /** One write at a time, so two can't share a temp file or outrun a delete. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

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
        viewModel.onPinFailed = { store.unmarkPinned(id) }
        viewModel.onPinned = { url ->
            store.pinned(id, url, PageTitle::sameDocument)
            sessions[id]?.let(::holdReleasedShot)
        }
        val session = PageSession(id, viewModel, page.url)
        if (page.isKept) {
            // The history file is read once a WebView asks for it ([takeHistory]).
            // Coming back to a page is not visiting it again.
            session.lastVisit = historyAddress(page.url)
        }
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

    private val settling = Handler(Looper.getMainLooper())

    /** A swipe started: the order holds until the user settles on a page. */
    fun beginSwitching() {
        settling.removeCallbacksAndMessages(null)
        store.beginSwitching()
    }

    /** A swipe ended. A page stayed on for a moment becomes the most recent one. */
    fun finishSwitching(afterMs: Long = SETTLE_MS) {
        settling.removeCallbacksAndMessages(null)
        settling.postDelayed({ store.releaseSwitchingOrder() }, afterMs)
    }

    /** A page was chosen in the deck: it is the current one at once. */
    fun releaseSwitchingOrder() {
        settling.removeCallbacksAndMessages(null)
        store.releaseSwitchingOrder()
    }

    /** Wakes the pages beside the active one, so they follow the finger as real pages. */
    fun prepareNeighbours() {
        val (previous, next) = store.neighbours()
        listOfNotNull(previous, next).forEach { page ->
            session(page.id)?.let { session ->
                // The swipe shows this picture until the woken page paints.
                loadShot(session)
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
        // Nothing of the pages stays in memory either, sign-out included.
        shotBitmaps.clear()
        syncActive()
    }

    fun toggleKeep(id: String) {
        val session = sessions[id] ?: return
        // Letting go deletes the page's files: what it had there stays in memory.
        if (store.page(id)?.isKept == true) {
            loadHistory(session)
            restoreShot(session)
        }
        store.toggleKeep(id, failed = session.viewModel.state.value.pageError != null)
        if (store.page(id)?.isKept == true) {
            saveHistory(session)
            capture(session)
        } else {
            holdReleasedShot(session)
        }
    }

    /** Back on a page a link opened, with no history left in it. */
    fun canReturnToOpener(id: String): Boolean = store.page(id)?.openerId != null

    /** Called as a WebView goes: a kept page's history stays with the page. */
    fun webViewReleased(session: PageSession, view: WebView) {
        if (session.webView === view) {
            session.dismissPrompts()
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

    /** A page that stays is saved now: the app can end without saying so. */
    fun pageLoaded(session: PageSession) {
        store.promote(session.id, keepEveryPage(), ::exit)
        saveHistory(session)
        recordVisit(session)
    }

    /** A page that changes its address without loading, as single-page sites do. */
    fun pageAddressChanged(session: PageSession) {
        if (!session.viewModel.state.value.isLoading) recordVisit(session)
    }

    private fun recordVisit(session: PageSession) {
        val state = session.viewModel.state.value
        val address = historyAddress(session.webView?.url) ?: return
        // A failed load also ends in `onPageFinished`.
        if (state.pageError != null || address == session.lastVisit) return
        session.lastVisit = address
        BrowserHistory.record(address, state.currentTitle)
    }

    /** Titles often arrive after the page has finished. */
    fun pageTitleChanged(session: PageSession) {
        val address = session.lastVisit ?: return
        if (historyAddress(session.webView?.url) != address) return
        BrowserHistory.updateTitle(address, session.viewModel.state.value.currentTitle)
    }

    /** The app went to the background and may not come back. */
    fun appWentToBackground() {
        store.promote(keepEveryPage(), ::exit)
        store.markActiveUsed()
        BrowserHistory.saveNow()
        sessions.values.forEach(::saveHistory)
        keptShotsToDisk()
    }

    fun closeExpired() {
        store.closeExpired(BrowserSettings.getKeptPageLifetime(context))
    }

    private fun keepEveryPage(): Boolean = !keepsOnlyChosenPages()

    /** Keeping a page is a choice only in this mode; the Keep controls exist for it alone. */
    fun keepsOnlyChosenPages(): Boolean = BrowserSettings.getKeepOnlyChosenPages(context)

    fun lifetime(): KeptPageLifetime = BrowserSettings.getKeptPageLifetime(context)

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

    /** Reads a kept page's history file, once, if nothing newer is in memory. */
    private fun loadHistory(session: PageSession) {
        if (session.historyLoaded) return
        session.historyLoaded = true
        if (session.history == null && store.page(session.id)?.isKept == true) {
            session.history = readHistory(store.historyFile(session.id))
        }
    }

    /** The back and forward list a new WebView starts from; the WebView holds it from then on. */
    fun takeHistory(session: PageSession): Bundle? {
        loadHistory(session)
        return session.history.also { session.history = null }
    }

    /** Takes the WebView's back and forward list while it still has one. */
    fun saveHistory(session: PageSession) {
        val view = session.webView
        // A sleeping page holds what its WebView gave up. One whose history
        // was never read has nothing here: its file is already current.
        val bundle = if (view != null) {
            Bundle().takeIf { runCatching { view.saveState(it) }.getOrNull() != null }
        } else {
            session.history
        } ?: return
        session.history = bundle
        if (store.page(session.id)?.isKept == true) {
            val file = store.historyFile(session.id)
            writeKept(session.id, file) { writeHistory(file, bundle) }
        }
    }

    /** Nothing of a page outlasts its keeping, whichever comes first. */
    private fun writeKept(id: String, file: File, write: () -> Unit) =
        writes.launch {
            if (store.page(id)?.isKept != true) return@launch
            write()
            if (store.page(id)?.isKept != true) file.delete()
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
        session.shotStored = false
        if (store.page(session.id)?.isKept == true) {
            writeShot(session.id, bitmap)
            // On its way to disk: the map is for pages that are not kept.
            shotBitmaps.remove(session.id)
        } else {
            shotBitmaps[session.id] = bitmap
        }
    }

    /**
     * The pictures of pages that are not kept, so a page kept later can still
     * store its own. A kept page's picture leaves as it is queued for writing.
     */
    private val shotBitmaps = HashMap<String, Bitmap>()

    /** Picture writes queued or running, per page: until done the disk may be behind. */
    private val shotWrites = HashMap<String, Int>()

    private fun keptShotsToDisk() {
        val kept = store.pages.value.filter { it.isKept }.map { it.id }.toSet()
        shotBitmaps.filterKeys { it in kept }.forEach { (id, bitmap) -> writeShot(id, bitmap) }
        shotBitmaps.keys.removeAll(kept)
        shotBitmaps.keys.retainAll(sessions.keys)
    }

    /** A page let go of keeping has lost its file: its picture waits here in case it is kept again. */
    private fun holdReleasedShot(session: PageSession) {
        if (store.page(session.id)?.isKept == true || session.id in shotBitmaps) return
        val shot = session.shot ?: return
        shotBitmaps[session.id] = shot.asAndroidBitmap()
        // The file goes with the release, so the picture in memory is no disk copy.
        session.shotStored = false
    }

    private fun writeShot(id: String, bitmap: Bitmap) {
        val target = store.snapshotFile(id)
        shotWrites[id] = (shotWrites[id] ?: 0) + 1
        // Whether this write put this very picture on disk. One that fails
        // leaves the older file behind, so the card keeps the newer picture.
        var written = false
        writeKept(id, target) {
            written = runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".tmp")
                val compressed = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                compressed && temp.renameTo(target)
            }.getOrDefault(false)
        }.invokeOnCompletion {
            launchOnMain {
                val left = (shotWrites[id] ?: 1) - 1
                if (left > 0) {
                    shotWrites[id] = left
                } else {
                    shotWrites.remove(id)
                    sessions[id]?.shotStored = written
                    // The card asked to drop its picture during the write and
                    // nothing else calls it: it can go only if the disk now
                    // holds what the page shows.
                    if (written) sessions[id]?.takeIf { it.shotDropWanted }?.let(::dropShot)
                }
            }
        }
    }

    /**
     * Reads a kept page's picture back from its file. Letting go of the page
     * deletes that file, and a card far from the deck's focus has already
     * dropped the picture, so it must be here before that happens. One small
     * JPEG, read on the spot for a rare action the reader started.
     */
    private fun restoreShot(session: PageSession) {
        if (session.shot != null) return
        val file = store.snapshotFile(session.id)
        if (!file.exists()) return
        // Pictures are opaque: 565 needs half the memory at the same size.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val image = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull() ?: return
        session.shot = image.asImageBitmap()
        session.shotStored = false
    }

    /** Loads the stored picture of a page that has none in memory. */
    fun loadShot(session: PageSession) {
        // Back in range: a drop wanted earlier is no longer wanted.
        session.shotDropWanted = false
        if (session.shot != null) return
        val file = store.snapshotFile(session.id)
        if (!file.exists()) return
        val generation = session.shotGeneration
        io.launch {
            // Pictures are opaque: 565 needs half the memory at the same size.
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val image = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull() ?: return@launch
            launchOnMain {
                // Let go again while it loaded: the card is no longer near.
                if (session.shot == null && session.shotGeneration == generation) {
                    session.shot = image.asImageBitmap()
                    session.shotStored = true
                }
            }
        }
    }

    /**
     * A card far from the deck's focus lets go of its picture, but only when
     * the disk can give it back: the page is kept, its picture is written and
     * no newer one is on its way. The page on screen and pages with a WebView
     * keep theirs.
     */
    fun dropShot(session: PageSession) {
        val id = session.id
        // Nothing to drop: the card keeps its picture on purpose, or has none
        // on disk to load again later.
        if (id == store.activeId.value || session.webView != null) {
            session.shotDropWanted = false
            return
        }
        if (store.page(id)?.isKept != true) {
            session.shotDropWanted = false
            return
        }
        // Still in memory or on its way to disk. Nothing calls this again when
        // the write ends, so remember the wish and finish it there.
        if (id in shotBitmaps || id in shotWrites) {
            session.shotDropWanted = true
            return
        }
        session.shotDropWanted = false
        if (session.shot != null && !(session.shotStored && store.snapshotFile(id).exists())) return
        session.shotGeneration++
        session.shot = null
        session.shotStored = false
    }

    private fun launchOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        /** Pages with a live WebView, the one on screen included. */
        const val LIVE_LIMIT = 4

        /** How long a page swiped to has to stay on to count as the current one. */
        private const val SETTLE_MS = 3_000L

        /** The same hold after a card is chosen, short enough not to feel stuck. */
        const val CHOSEN_SETTLE_MS = 1_500L

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
