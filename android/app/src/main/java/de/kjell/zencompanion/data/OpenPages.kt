package de.kjell.zencompanion.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One page open on this phone. Pages stay open like in any browser, and on
 * disk, until they are closed; with "Keep only chosen pages" on, only those the
 * user keeps do. Neither kind ever enters Zen's data.
 */
data class OpenPage(
    val id: String,
    /** Empty for a new page nothing has loaded in yet. */
    val url: String,
    val title: String,
    /** When the page was saved to stay; null until then. */
    val keptAt: Long? = null,
    val lastUsedAt: Long,
    /** A space's own tab: it stays only once the user browses away. */
    val openedFromSpace: Boolean = false,
    /** The page a link opened this one from. Back returns there. */
    val openerId: String? = null,
    /** The user stopped keeping this page, so it goes with the browser. */
    val released: Boolean = false,
    /** The address saved from this page into a space. */
    val pinnedUrl: String? = null,
) {
    val isKept: Boolean get() = keptAt != null
}

/** How long a page stays once the user has left it. A month by default. */
enum class KeptPageLifetime(val storageValue: String, val millis: Long?) {
    NEVER("never", null),
    DAY("day", 86_400_000L),
    WEEK("week", 7 * 86_400_000L),
    MONTH("month", 30 * 86_400_000L),
    ;

    companion object {
        fun fromStorage(value: String?): KeptPageLifetime =
            entries.firstOrNull { it.storageValue == value } ?: MONTH
    }
}

/** What ending a browser session does with a page. */
enum class LeaveAction { UPDATE, KEEP, DISCARD }

object KeptPagesPolicy {
    /**
     * A kept page stays and takes its latest address. Any other page stays too,
     * unless "Keep only chosen pages" is on. Even then a page is not kept
     * when there is nothing worth reopening (no web address, a failed load),
     * when it was just pinned into a space, when the user stopped keeping it
     * during this visit, or when it is a space's tab that was opened and left
     * without browsing away from it.
     */
    fun onLeave(
        isKept: Boolean,
        keepEveryPage: Boolean,
        url: String,
        failed: Boolean,
        pinnedHere: Boolean,
        releasedHere: Boolean,
        openedFromSpace: Boolean,
        browsedAway: Boolean,
    ): LeaveAction {
        if (isKept) return LeaveAction.UPDATE
        if (!keepEveryPage) return LeaveAction.DISCARD
        if (!isWebAddress(url)) return LeaveAction.DISCARD
        if (failed || pinnedHere || releasedHere) return LeaveAction.DISCARD
        if (openedFromSpace && !browsedAway) return LeaveAction.DISCARD
        return LeaveAction.KEEP
    }

    fun isWebAddress(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "http" || scheme == "https"
    }
}

/**
 * Every page open on this phone, the one on screen and what stays on disk.
 *
 * Kept pages live under [dir] as one JSON file plus a picture and a history
 * file per page, and go on sign-out. The app's backup rules leave them out.
 * The store knows nothing about WebViews; `BrowserPages` holds those.
 */
class OpenPagesStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _pages = MutableStateFlow(read())
    private val _activeId = MutableStateFlow<String?>(null)

    /** Oldest first; new pages join at the end, or right after their opener. */
    val pages: StateFlow<List<OpenPage>> = _pages

    /** The page on screen; null while the browser is closed. */
    val activeId: StateFlow<String?> = _activeId

    /** Told about every page that ended, so its WebView can go too. */
    var onPageRemoved: (String) -> Unit = {}

    fun page(id: String): OpenPage? = _pages.value.firstOrNull { it.id == id }

    /** The pages either side of the active one, for the address bar swipe. */
    fun neighbours(): Pair<OpenPage?, OpenPage?> {
        val pages = _pages.value
        val index = pages.indexOfFirst { it.id == _activeId.value }
        if (index < 0) return null to null
        return pages.getOrNull(index - 1) to pages.getOrNull(index + 1)
    }

    fun canKeep(page: OpenPage, failed: Boolean): Boolean =
        page.isKept || (!failed && KeptPagesPolicy.isWebAddress(page.url))

    /** Opens a new page and puts it on screen. */
    fun open(url: String, title: String = "", openedFromSpace: Boolean = false, openerId: String? = null): OpenPage {
        val page = OpenPage(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            lastUsedAt = now(),
            openedFromSpace = openedFromSpace,
            openerId = openerId,
        )
        val pages = _pages.value
        val after = pages.indexOfFirst { it.id == openerId }
        _pages.value = if (after >= 0) {
            pages.subList(0, after + 1) + page + pages.subList(after + 1, pages.size)
        } else {
            pages + page
        }
        _activeId.value = page.id
        return page
    }

    /** Puts a page on screen. */
    fun activate(id: String) {
        if (page(id) == null) return
        val at = now()
        val previous = _activeId.value
        change { pages ->
            pages.map { if (it.id == id || it.id == previous) it.copy(lastUsedAt = at) else it }
        }
        _activeId.value = id
    }

    /** Puts a page where another one is. The swipe follows the same order. */
    fun move(id: String, onto: String) {
        if (id == onto) return
        val pages = _pages.value.toMutableList()
        val from = pages.indexOfFirst { it.id == id }
        val to = pages.indexOfFirst { it.id == onto }
        if (from < 0 || to < 0) return
        pages.add(to, pages.removeAt(from))
        change { pages }
    }

    /** The page moved on. A blank title or address keeps the one it had. */
    fun update(id: String, url: String, title: String) {
        val current = page(id) ?: return
        val next = current.copy(url = url.ifEmpty { current.url }, title = title.ifBlank { current.title })
        if (next == current) return
        if (current.isKept) {
            change { pages -> pages.map { if (it.id == id) next else it } }
        } else {
            _pages.value = _pages.value.map { if (it.id == id) next else it }
        }
    }

    /** Keep Page / Kept. Tapping again lets the page go with the browser. */
    fun toggleKeep(id: String, failed: Boolean) {
        val page = page(id) ?: return
        when {
            page.isKept -> release(id)
            canKeep(page, failed) -> keep(id)
        }
    }

    private fun keep(id: String) {
        val at = now()
        change { pages -> pages.map { if (it.id == id) it.copy(keptAt = at, released = false) else it } }
    }

    private fun release(id: String, byUser: Boolean = true) {
        change { pages ->
            pages.map { if (it.id == id) it.copy(keptAt = null, released = it.released || byUser) else it }
        }
        removeFiles(id)
    }

    /** A save from this page started: it does not stay as an open page too. */
    /** The write failed: the page never reached the space and stays its own. */
    fun unmarkPinned(id: String) {
        _pages.value = _pages.value.map { if (it.id == id) it.copy(pinnedUrl = null) else it }
    }

    /** The app is leaving with this page on screen: it counts as used now. */
    fun markActiveUsed() {
        val id = _activeId.value ?: return
        val at = now()
        change { pages -> pages.map { if (it.id == id) it.copy(lastUsedAt = at) else it } }
    }

    fun markPinned(id: String, url: String) {
        _pages.value = _pages.value.map { if (it.id == id) it.copy(pinnedUrl = url) else it }
    }

    /** Pinned, the page belongs to the space. A page that moved on stays what it was. */
    fun pinned(id: String, url: String, sameDocument: (String, String) -> Boolean) {
        val page = page(id) ?: return
        if (!page.isKept || !sameDocument(url, page.url)) return
        // Still on screen, it stays if the user browses on from here.
        if (id == _activeId.value) release(id, byUser = false) else close(id)
    }

    /**
     * Ends a page. Back on screen comes the page that opened it, otherwise its
     * neighbour. Returns the page now on screen.
     */
    fun close(id: String): String? {
        val pages = _pages.value
        val index = pages.indexOfFirst { it.id == id }
        if (index < 0) return _activeId.value
        val page = pages[index]
        val rest = pages.filterNot { it.id == id }
            .map { if (it.openerId == id) it.copy(openerId = page.openerId) else it }
        change { rest }
        removeFiles(id)
        onPageRemoved(id)
        if (_activeId.value == id) {
            val next = rest.firstOrNull { it.id == page.openerId } ?: rest.getOrNull(index.coerceAtMost(rest.size - 1))
            _activeId.value = null
            next?.let { activate(it.id) }
        }
        return _activeId.value
    }

    /** Ends every page. */
    fun closeAll() {
        val ids = _pages.value.map { it.id }
        _pages.value = emptyList()
        _activeId.value = null
        dir.deleteRecursively()
        ids.forEach(onPageRemoved)
    }

    /**
     * Saves the pages that stay. [leaving] answers what the
     * store cannot know about a page: whether it failed and has history.
     */
    fun promote(keepEveryPage: Boolean, leaving: (OpenPage) -> PageExit) {
        for (page in _pages.value) {
            val exit = leaving(page)
            val action = KeptPagesPolicy.onLeave(
                isKept = page.isKept,
                keepEveryPage = keepEveryPage,
                url = page.url,
                failed = exit.failed,
                pinnedHere = exit.pinnedHere,
                releasedHere = page.released,
                openedFromSpace = page.openedFromSpace,
                browsedAway = exit.browsedAway,
            )
            if (action == LeaveAction.KEEP) keep(page.id)
        }
    }

    /** The browser is closing: pages that were not saved to stay go. */
    fun endSession(keepEveryPage: Boolean, leaving: (OpenPage) -> PageExit) {
        promote(keepEveryPage, leaving)
        val at = now()
        val onScreen = _activeId.value
        val gone = _pages.value.filterNot { it.isKept }
        _activeId.value = null
        change { pages ->
            // Only what was in use: the others keep counting down.
            val staying = pages.filter { it.isKept }
            val ids = staying.map { it.id }.toSet()
            staying.map {
                it.copy(
                    lastUsedAt = if (it.id == onScreen) at else it.lastUsedAt,
                    // A page's way back ends with the page it led to.
                    openerId = it.openerId?.takeIf { opener -> opener in ids },
                )
            }
        }
        gone.forEach {
            removeFiles(it.id)
            onPageRemoved(it.id)
        }
    }

    /**
     * Pages past the chosen lifetime close by themselves. The page on
     * screen is in use, so it is never one of them.
     */
    fun closeExpired(lifetime: KeptPageLifetime) {
        val span = lifetime.millis ?: return
        val deadline = now() - span
        _pages.value
            .filter { it.isKept && it.id != _activeId.value && it.lastUsedAt <= deadline }
            .forEach { close(it.id) }
    }

    fun snapshotFile(id: String): File = File(dir, "$id.jpg")

    fun historyFile(id: String): File = File(dir, "$id.state")

    private fun removeFiles(id: String) {
        snapshotFile(id).delete()
        historyFile(id).delete()
    }

    private fun change(transform: (List<OpenPage>) -> List<OpenPage>) {
        synchronized(this) {
            val next = transform(_pages.value)
            _pages.value = next
            dir.mkdirs()
            val temp = File(dir, "$FILE_NAME.tmp")
            temp.writeText(encode(next.filter { it.isKept }))
            if (!temp.renameTo(File(dir, FILE_NAME))) temp.delete()
        }
    }

    private fun read(): List<OpenPage> {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return emptyList()
        // A damaged file loses the list, not the app.
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    /** What the store cannot know about a page that is being left. */
    data class PageExit(val failed: Boolean, val pinnedHere: Boolean, val browsedAway: Boolean)

    companion object {
        private const val FILE_NAME = "pages.json"

        @Volatile private var shared: OpenPagesStore? = null

        fun shared(context: Context): OpenPagesStore =
            shared ?: synchronized(this) {
                shared ?: OpenPagesStore(File(context.applicationContext.noBackupFilesDir, "open_pages"))
                    .also { shared = it }
            }

        internal fun encode(pages: List<OpenPage>): String {
            val array = JSONArray()
            for (page in pages) {
                array.put(
                    JSONObject()
                        .put("id", page.id)
                        .put("url", page.url)
                        .put("title", page.title)
                        .put("keptAt", page.keptAt)
                        .put("lastUsedAt", page.lastUsedAt),
                )
            }
            return array.toString()
        }

        internal fun decode(raw: String): List<OpenPage> {
            val array = JSONArray(raw)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                OpenPage(
                    id = item.getString("id"),
                    url = item.getString("url"),
                    title = item.optString("title"),
                    keptAt = item.getLong("keptAt"),
                    lastUsedAt = item.getLong("lastUsedAt"),
                )
            }
        }
    }
}
