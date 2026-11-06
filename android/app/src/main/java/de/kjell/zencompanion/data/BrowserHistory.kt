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
    return url.substringBefore('#')
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

/**
 * Up to [limit] pages for the text in the address field, leaving out the page
 * that is already open. A site whose name starts with the text comes first,
 * then the pages visited most.
 */
internal fun visitSuggestions(
    visits: List<BrowserVisit>,
    text: String,
    current: String? = null,
    limit: Int = 3,
): List<BrowserVisit> {
    val words = words(text)
    if (words.isEmpty()) return emptyList()
    val open = historyAddress(current)
    return visits
        .filter { it.url != open && it.matches(words) }
        .sortedWith(
            compareByDescending<BrowserVisit> { it.host.lowercase().startsWith(words.first()) }
                .thenByDescending { it.visits },
        )
        .take(limit)
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

    private val _visits = MutableStateFlow<List<BrowserVisit>>(emptyList())
    /** Newest first. */
    val visits: StateFlow<List<BrowserVisit>> = _visits.asStateFlow()

    fun start(context: Context) {
        val stored = File(context.filesDir, FILE)
        file = stored
        scope.launch {
            val saved = runCatching { decode(stored.readText()) }.getOrDefault(emptyList())
            // Anything recorded while this was reading stays on top.
            _visits.value = (_visits.value + saved).distinctBy { it.url }
        }
    }

    fun record(url: String?, title: String) {
        val updated = withVisit(_visits.value, url, title, System.currentTimeMillis())
        if (updated === _visits.value) return
        _visits.value = updated
        scheduleSave()
    }

    fun remove(visit: BrowserVisit) {
        _visits.value = _visits.value.filter { it.url != visit.url }
        scheduleSave()
    }

    fun clear() {
        _visits.value = emptyList()
        pendingSave?.cancel()
        scope.launch { file?.delete() }
    }

    fun suggestions(text: String, current: String?): List<BrowserVisit> =
        visitSuggestions(_visits.value, text, current)

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(SAVE_DELAY_MS)
            val target = file ?: return@launch
            val temp = File(target.parentFile, "$FILE.tmp")
            runCatching {
                temp.writeText(encode(_visits.value))
                temp.renameTo(target)
            }
        }
    }

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
