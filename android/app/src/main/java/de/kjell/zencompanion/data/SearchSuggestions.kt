package de.kjell.zencompanion.data

import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Public suggest endpoint of one built-in engine.
 * Custom engines have none: they only store a search template.
 *
 * Checked against the live services on 2026-10-11. Every built-in answers
 * with one JSON array whose second element is the suggestion strings:
 * `["typed query", ["first", "second"]]`.
 * Google appends further elements. They are ignored.
 * DuckDuckGo and Ecosia only use that array when `type=list` is set.
 * Google only uses it when `client=firefox` (other clients return JSONP).
 * Bing's parameter is `query`, not `q`.
 */
internal data class SearchSuggestEndpoint(
    val base: String,
    val queryName: String,
    val extra: Map<String, String> = emptyMap(),
)

internal object SearchSuggestions {
    /** Same cap as iOS: the list sits over the page and is not scrolled. */
    const val LIMIT = 8

    /** Wait after the last keystroke before a request leaves the device. */
    const val DEBOUNCE_MS = 200L

    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    fun endpoint(engine: SearchEngine): SearchSuggestEndpoint? {
        if (!engine.isBuiltIn) return null
        return when (engine.id) {
            "duckduckgo" -> SearchSuggestEndpoint(
                base = "https://duckduckgo.com/ac/",
                queryName = "q",
                extra = mapOf("type" to "list"),
            )
            "google" -> SearchSuggestEndpoint(
                base = "https://suggestqueries.google.com/complete/search",
                queryName = "q",
                extra = mapOf("client" to "firefox"),
            )
            "ecosia" -> SearchSuggestEndpoint(
                base = "https://ac.ecosia.org/autocomplete",
                queryName = "q",
                extra = mapOf("type" to "list"),
            )
            "brave" -> SearchSuggestEndpoint(
                base = "https://search.brave.com/api/suggest",
                queryName = "q",
            )
            "bing" -> SearchSuggestEndpoint(
                base = "https://www.bing.com/osjson.aspx",
                queryName = "query",
            )
            else -> null
        }
    }

    /**
     * Absolute suggest URL, or null when nothing may be sent: blank text,
     * a web address, or an engine with no suggest endpoint.
     */
    fun requestUrl(raw: String, engine: SearchEngine): String? {
        val query = raw.trim()
        if (query.isEmpty() || isDirectNavigation(query)) return null
        val endpoint = endpoint(engine) ?: return null
        return endpoint.url(query)
    }

    /** True when this text would open an address rather than a search. */
    fun isDirectNavigation(query: String): Boolean {
        if (schemePrefix.containsMatchIn(query)) return true
        val scheme = runCatching { URI(query).scheme }.getOrNull()
        if (!scheme.isNullOrEmpty()) return true
        return query.contains('.') && !query.any { it.isWhitespace() }
    }

    /** Suggestion strings, or an empty list when [body] is not the array above. */
    fun parse(body: String): List<String> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        if (root.length() < 2) return emptyList()
        val items = root.optJSONArray(1) ?: return emptyList()
        val seen = LinkedHashSet<String>()
        for (index in 0 until items.length()) {
            if (seen.size == LIMIT) break
            val value = items.opt(index)
            if (value !is String) continue
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) seen.add(trimmed)
        }
        return seen.toList()
    }

    private fun SearchSuggestEndpoint.url(query: String): String? {
        val pairs = buildList {
            add(queryName to query)
            extra.keys.sorted().forEach { key -> add(key to extra.getValue(key)) }
        }
        val queryString = pairs.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        val separator = if (base.contains('?')) "&" else "?"
        val raw = base + separator + queryString
        // The query is already UTF-8 form-encoded. Re-parsing it as a URI would
        // turn a space's "+" into "%2B", which the engines would search as a plus.
        return if (runCatching { URI(raw) }.isSuccess) raw else null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
