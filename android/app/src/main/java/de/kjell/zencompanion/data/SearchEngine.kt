package de.kjell.zencompanion.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A search provider: a display name plus a URL template whose `{query}`
 * placeholder is replaced with the URL-encoded query.
 *
 * Built-ins live in [SearchEngines.builtIn]; user-defined engines are stored
 * on the device only and never synced. Mirrors the iOS `SearchEngine`.
 */
data class SearchEngine(
    val id: String,
    val displayName: String,
    val template: String,
    val isBuiltIn: Boolean,
) {
    /** Replaces `{query}` in the template with the UTF-8 URL-encoded query. */
    fun formatQuery(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        return template.replace(SearchEngines.PLACEHOLDER, encoded)
    }

    fun searchURL(query: String): String = formatQuery(query)

    companion object {
        val DUCKDUCKGO = SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={query}", true)
        val GOOGLE = SearchEngine("google", "Google", "https://www.google.com/search?q={query}", true)
        val ECOSIA = SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q={query}", true)
        val BRAVE = SearchEngine("brave", "Brave", "https://search.brave.com/search?q={query}", true)
        val BING = SearchEngine("bing", "Bing", "https://www.bing.com/search?q={query}", true)
    }
}

/** Why a custom-engine draft cannot be saved yet. */
enum class SearchEngineValidation {
    EMPTY_NAME,
    INVALID_URL,
    MISSING_PLACEHOLDER,
}

/**
 * Validation and the "paste a search link" template derivation for custom
 * engines. Pure and side-effect free, so it is unit-testable on the JVM.
 */
object SearchEngineTemplate {
    const val PLACEHOLDER = "{query}"

    /** null when the draft is ready to save, otherwise the first problem. */
    fun validate(name: String, template: String): SearchEngineValidation? {
        if (name.isBlank()) return SearchEngineValidation.EMPTY_NAME
        val trimmed = template.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return SearchEngineValidation.INVALID_URL
        }
        if (runCatching { URI(trimmed.replace(PLACEHOLDER, "test")) }.isFailure) {
            return SearchEngineValidation.INVALID_URL
        }
        if (!trimmed.contains(PLACEHOLDER)) return SearchEngineValidation.MISSING_PLACEHOLDER
        return null
    }

    /**
     * Turns a real search URL (e.g. copied from the address bar) into a
     * template by replacing the query parameter's value with `{query}`.
     * Prefers well-known parameter names (`q`, `query`, …), else the last
     * parameter that has a value.
     */
    fun derive(fromPastedURL: String): String? {
        val trimmed = fromPastedURL.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val question = trimmed.indexOf('?')
        if (question < 0) return null

        val base = trimmed.substring(0, question)
        var remainder = trimmed.substring(question + 1)
        var fragment = ""
        val hash = remainder.indexOf('#')
        if (hash >= 0) {
            fragment = remainder.substring(hash)
            remainder = remainder.substring(0, hash)
        }
        if (remainder.isEmpty()) return null

        val preferred = setOf("q", "query", "search", "p", "text", "wd", "k")
        val pairs = remainder.split('&').toMutableList()

        fun keyOf(pair: String): String {
            val rawKey = pair.substringBefore('=')
            return runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey).lowercase()
        }

        fun valueOf(pair: String): String {
            val index = pair.indexOf('=')
            return if (index < 0) "" else pair.substring(index + 1)
        }

        var chosen: Int? = null
        for ((index, pair) in pairs.withIndex()) {
            if (valueOf(pair).isEmpty()) continue
            if (preferred.contains(keyOf(pair))) {
                chosen = index
                break
            }
        }
        if (chosen == null) {
            chosen = pairs.indexOfLast { valueOf(it).isNotEmpty() }.takeIf { it >= 0 }
        }
        val index = chosen ?: return null

        val originalKey = pairs[index].substringBefore('=')
        pairs[index] = "$originalKey=$PLACEHOLDER"
        return base + "?" + pairs.joinToString("&") + fragment
    }

    /**
     * Best-effort display name for a pasted search link: the registrable
     * domain's label, so `de.search.yahoo.com` -> "Yahoo" and `www.bbc.co.uk`
     * -> "Bbc" (not the sub-domain "de" or the "www").
     */
    fun suggestedName(fromTemplate: String): String? {
        val host = runCatching { URI(fromTemplate.substringBefore('?')).host }.getOrNull() ?: return null
        val labels = host.lowercase().split('.').filter { it.isNotEmpty() }.toMutableList()
        if (labels.firstOrNull() == "www") labels.removeAt(0)
        if (labels.isEmpty()) return null

        val label = when {
            labels.size == 1 -> labels[0]
            labels.size >= 3 && compoundSuffixes.contains(labels.takeLast(2).joinToString(".")) ->
                labels[labels.size - 3]
            else -> labels[labels.size - 2]
        }
        return label.replaceFirstChar { it.uppercase() }
    }

    /**
     * Common country-code second-level domains, so `co.uk` style suffixes are
     * skipped when looking for the registrable label.
     */
    private val compoundSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.br", "net.br", "org.br", "gov.br",
        "co.in", "net.in", "org.in", "gov.in",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "co.za", "org.za", "gov.za",
        "co.kr", "or.kr",
        "com.mx", "com.tr", "com.sg", "com.hk", "com.tw", "co.id", "co.il",
    )
}

/**
 * The engine catalogue and persistence. The selection is stored as the engine
 * id (built-in or custom), so a stored value survives and unknown ids fall
 * back to DuckDuckGo — including after a selected custom engine is deleted.
 */
object SearchEngines {
    const val PLACEHOLDER = "{query}"

    const val PREFS_KEY = "zen_companion_search_engine"
    const val LEGACY_PREFS_KEY = "selected_search_engine"
    const val CUSTOM_PREFS_KEY = "zen_companion_custom_search_engines"
    const val PREFS_NAME = "zen_prefs"

    val builtIn: List<SearchEngine> = listOf(
        SearchEngine.DUCKDUCKGO,
        SearchEngine.GOOGLE,
        SearchEngine.ECOSIA,
        SearchEngine.BRAVE,
        SearchEngine.BING,
    )

    @Volatile
    private var cachedEngine: SearchEngine? = null

    fun getPreferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Built-ins in stable order, then the user's own engines. */
    fun all(context: Context): List<SearchEngine> = builtIn + custom(context)

    /** User-defined engines, in the order they were added. */
    fun custom(context: Context): List<SearchEngine> =
        decodeCustom(getPreferences(context).getString(CUSTOM_PREFS_KEY, null))

    fun setCustom(context: Context, engines: List<SearchEngine>) {
        getPreferences(context).edit()
            .putString(CUSTOM_PREFS_KEY, encodeCustom(engines.filter { !it.isBuiltIn }))
            .apply()
    }

    /** Resolves the persisted selection against [candidates]. */
    fun resolve(context: Context): SearchEngine {
        val raw = getPreferences(context).getString(PREFS_KEY, null)
            ?: getPreferences(context).getString(LEGACY_PREFS_KEY, null)
        return fromKey(raw, all(context))
    }

    fun set(context: Context, engine: SearchEngine) {
        cachedEngine = engine
        getPreferences(context).edit()
            .putString(PREFS_KEY, engine.id)
            .putString(LEGACY_PREFS_KEY, engine.id)
            .apply()
    }

    /** Matches an id or display name (case-insensitive); defaults to DuckDuckGo. */
    fun fromKey(key: String?, candidates: List<SearchEngine> = builtIn): SearchEngine {
        if (key.isNullOrBlank()) return SearchEngine.DUCKDUCKGO
        return candidates.firstOrNull {
            it.id.equals(key, ignoreCase = true) || it.displayName.equals(key, ignoreCase = true)
        } ?: SearchEngine.DUCKDUCKGO
    }

    /** Cached selection for the address bar; requires [AppContextHolder]. */
    var current: SearchEngine
        get() {
            cachedEngine?.let { return it }
            val ctx = runCatching { AppContextHolder.appContext }.getOrNull()
            return if (ctx != null) {
                val engine = resolve(ctx)
                cachedEngine = engine
                engine
            } else {
                SearchEngine.DUCKDUCKGO
            }
        }
        set(value) {
            cachedEngine = value
            val ctx = runCatching { AppContextHolder.appContext }.getOrNull()
            if (ctx != null) set(ctx, value)
        }

    fun invalidateCache() {
        cachedEngine = null
    }

    private fun encodeCustom(engines: List<SearchEngine>): String {
        val array = JSONArray()
        engines.forEach { engine ->
            array.put(
                JSONObject().apply {
                    put("id", engine.id)
                    put("name", engine.displayName)
                    put("template", engine.template)
                },
            )
        }
        return array.toString()
    }

    private fun decodeCustom(raw: String?): List<SearchEngine> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val template = obj.optString("template").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SearchEngine(
                    id = id,
                    displayName = obj.optString("name").ifBlank { "Search" },
                    template = template,
                    isBuiltIn = false,
                )
            }
        }.getOrDefault(emptyList())
    }
}
