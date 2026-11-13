package de.kjell.zencompanion.data

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One address the reader has been to. A second visit updates the entry, so the
 * list holds each page once, at the time it was last open.
 */
data class BrowserVisit(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val visits: Int,
) {
    val host: String
        get() = hostOf(url)

    /** The address without its scheme, so two pages of a site can be told apart. */
    val displayAddress: String
        get() = url.substringAfter("://", url).removePrefix("www.").removeSuffix("/")
}

private fun hostOf(url: String): String {
    val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
    return host.removePrefix("www.")
}

internal const val MAX_HISTORY_ENTRIES = 5_000

/** The address history keeps, or null for what is not a web page. A jump inside a page is the same page. */
internal fun historyAddress(url: String?): String? {
    if (url.isNullOrEmpty()) return null
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return withoutCredentials(url.substringBefore('#'))
}

/** Without `user:pass@`: a login typed into an address does not belong in a history file. */
private fun withoutCredentials(address: String): String {
    val authorityStart = address.indexOf("://") + 3
    if (authorityStart < 3) return address
    val authorityEnd = address.indexOfAny(charArrayOf('/', '?'), authorityStart).takeIf { it >= 0 } ?: address.length
    val at = address.lastIndexOf('@', authorityEnd - 1)
    if (at < authorityStart) return address
    return address.substring(0, authorityStart) + address.substring(at + 1)
}

/** [visits] newest first, with the page at the top and the oldest over the limit gone. */
internal fun withVisit(
    visits: List<BrowserVisit>,
    url: String?,
    title: String,
    now: Long,
    limit: Int = MAX_HISTORY_ENTRIES,
): List<BrowserVisit> {
    val address = historyAddress(url) ?: return visits
    val earlier = visits.firstOrNull { it.url == address }
    val visit = BrowserVisit(
        url = address,
        title = title.ifEmpty { earlier?.title.orEmpty() },
        lastVisited = now,
        visits = (earlier?.visits ?: 0) + 1,
    )
    return (listOf(visit) + visits.filter { it.url != address }).take(limit)
}

/** [visits] with the page at [address] under its real title, once the page has one. */
internal fun withVisitTitle(visits: List<BrowserVisit>, address: String, title: String): List<BrowserVisit> {
    if (title.isEmpty() || visits.none { it.url == address && it.title != title }) return visits
    return visits.map { if (it.url == address) it.copy(title = title) else it }
}

private fun words(text: String) = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

private fun BrowserVisit.matches(words: List<String>): Boolean {
    val title = title.lowercase()
    val address = url.lowercase()
    return words.all { it in title || it in address }
}

/** Every word must appear in the title or the address. */
internal fun searchVisits(visits: List<BrowserVisit>, query: String): List<BrowserVisit> {
    val words = words(query)
    return if (words.isEmpty()) visits else visits.filter { it.matches(words) }
}

private const val MINIMUM_TYPED_LETTERS = 2

private val NON_WORD_CHARACTERS = Regex("[^\\p{L}\\p{N}]+")

private fun tokens(text: String) = text.lowercase().split(NON_WORD_CHARACTERS).filter { it.isNotEmpty() }

/** The typed text as words, without the parts every address shares. */
private fun typedWords(text: String): List<String> {
    var value = text.trim().lowercase()
    listOf("https://", "http://", "www.").forEach { value = value.removePrefix(it) }
    return tokens(value)
}

private fun BrowserVisit.isCloseMatch(typed: List<String>): Boolean {
    val pageWords = tokens(title) + tokens(displayAddress.substringBefore('?'))
    return typed.all { word -> pageWords.any { it.startsWith(word) } }
}

/**
 * Up to [limit] pages for the text in the address field, leaving out the page
 * that is already open. Only a real match counts: every typed word must start
 * a word of the page's title or address. A site whose name starts with the
 * text comes first, then the pages visited most.
 */
internal fun visitSuggestions(
    visits: List<BrowserVisit>,
    text: String,
    current: String? = null,
    limit: Int = 2,
): List<BrowserVisit> {
    val typed = typedWords(text)
    if (typed.sumOf { it.length } < MINIMUM_TYPED_LETTERS) return emptyList()
    val open = historyAddress(current)
    return visits
        .filter { it.url != open && it.isCloseMatch(typed) }
        // Worked out once per page: this runs on every keystroke.
        .map { it to it.host.lowercase().startsWith(typed.first()) }
        .sortedWith(compareByDescending<Pair<BrowserVisit, Boolean>> { it.second }.thenByDescending { it.first.visits })
        .take(limit)
        .map { it.first }
}

/**
 * The pages opened in the browser, on this phone only: it never enters Zen's
 * data, stays out of device backups, and goes on sign-out.
 *
 * iOS port: `BrowserHistoryStore` in `Browser/BrowserHistory.swift`.
 */
object BrowserHistory {
    private const val FILE = "browser-history.json"
    private const val SAVE_DELAY_MS = 1_000L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var file: File? = null
    private var pendingSave: Job? = null
    private val loadLock = Any()
    private var cleared = false

    private val _visits = MutableStateFlow<List<BrowserVisit>>(emptyList())
    /** Newest first. */
    val visits: StateFlow<List<BrowserVisit>> = _visits.asStateFlow()

    fun start(context: Context) {
        val stored = File(context.filesDir, FILE)
        file = stored
        scope.launch {
            val saved = runCatching { decode(stored.readText()) }.getOrDefault(emptyList())
            // Locked, because a merge into an empty list can't tell that list
            // from one a sign-out has just emptied.
            synchronized(loadLock) {
                // A sign-out while this was reading must not bring the list back.
                if (cleared) return@launch
                // Anything recorded while this was reading stays on top.
                _visits.update { (it + saved).distinctBy(BrowserVisit::url).take(MAX_HISTORY_ENTRIES) }
            }
        }
    }

    fun record(url: String?, title: String) {
        if (historyAddress(url) == null) return
        _visits.update { withVisit(it, url, title, System.currentTimeMillis()) }
        scheduleSave()
    }

    fun updateTitle(address: String, title: String) {
        _visits.update { withVisitTitle(it, address, title) }
        scheduleSave()
    }

    fun remove(visit: BrowserVisit) {
        _visits.update { visits -> visits.filter { it.url != visit.url } }
        scheduleSave()
    }

    fun clear() {
        synchronized(loadLock) {
            cleared = true
            _visits.value = emptyList()
        }
        pendingSave?.cancel()
        pendingSave = null
        scope.launch {
            file?.delete()
            temporaryFile()?.delete()
        }
    }

    /** The app is leaving and may not come back: a waiting save goes now. */
    fun saveNow() {
        if (pendingSave?.isActive != true) return
        pendingSave?.cancel()
        pendingSave = scope.launch { write() }
    }

    fun suggestions(text: String, current: String?): List<BrowserVisit> =
        visitSuggestions(_visits.value, text, current)

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(SAVE_DELAY_MS)
            write()
        }
    }

    private fun write() {
        val target = file ?: return
        val temp = temporaryFile() ?: return
        runCatching {
            temp.writeText(encode(_visits.value))
            temp.renameTo(target)
        }
    }

    private fun temporaryFile(): File? = file?.let { File(it.parentFile, "$FILE.tmp") }

    private fun encode(visits: List<BrowserVisit>): String = JSONArray().apply {
        visits.forEach { visit ->
            put(
                JSONObject()
                    .put("url", visit.url)
                    .put("title", visit.title)
                    .put("lastVisited", visit.lastVisited)
                    .put("visits", visit.visits),
            )
        }
    }.toString()

    private fun decode(text: String): List<BrowserVisit> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            BrowserVisit(
                url = item.getString("url"),
                title = item.optString("title"),
                lastVisited = item.getLong("lastVisited"),
                visits = item.optInt("visits", 1),
            )
        }
    }
}
