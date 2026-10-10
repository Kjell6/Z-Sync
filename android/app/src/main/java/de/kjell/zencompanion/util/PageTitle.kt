package de.kjell.zencompanion.util

import org.json.JSONArray
import java.net.URI

/**
 * Port of `PageTitle.swift`. The name stored for a pinned or shared page.
 *
 * Share intents and WebView often report the URL as the title. A browser tab
 * shows `document.title`, so a string that is only the address is not a title.
 */
internal object PageTitle {
    private val whitespace = Regex("\\s+")
    private val titleTag = Regex("<title\\b[^>]*>([\\s\\S]*?)</title\\s*>", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val entity = Regex("&(?:#x([0-9A-Fa-f]+)|#([0-9]+)|([A-Za-z][A-Za-z0-9]+));")
    private val charset = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    /** `document.title` and a social title, joined by a record separator. */
    const val DOCUMENT_TITLE_SCRIPT = """
        (function() {
          function clean(value) {
            return String(value || "").replace(/\s+/g, " ").trim();
          }
          var title = clean(document.title);
          var node = document.querySelector('meta[property="og:title"], meta[name="og:title"], meta[name="twitter:title"]');
          var social = clean(node && node.content);
          return title + "\u001e" + social;
        })()
    """

    /** Trimmed title, or null when it is empty or just the page address. */
    fun usable(raw: String, url: String?): String? {
        val collapsed = raw.replace(whitespace, " ").trim()
        if (collapsed.isEmpty()) return null
        if (isAbsoluteHttp(collapsed)) return null
        if (url != null && identifies(collapsed, url)) return null
        return collapsed
    }

    /** Usable title, otherwise the host, otherwise the URL, otherwise "Tab". */
    fun headline(pageTitle: String, url: String?): String {
        usable(pageTitle, url)?.let { return it }
        host(url)?.let { return it }
        if (!url.isNullOrEmpty()) return url
        return "Tab"
    }

    /** True when both addresses are the same http(s) document. */
    fun sameDocument(lhs: String?, rhs: String?): Boolean {
        if (lhs.isNullOrEmpty() || rhs.isNullOrEmpty()) return false
        val left = identity(lhs)
        val right = identity(rhs)
        return if (left != null && right != null) left == right else lhs == rhs
    }

    /** `<title>` when it is a real title, otherwise `og:title` / `twitter:title`. */
    fun extract(html: String, url: String?): String? {
        val fromTitle = titleTag.find(html)?.groupValues?.getOrNull(1)
        if (fromTitle != null) {
            usable(decodeEntities(fromTitle), url)?.let { return it }
        }
        val social = socialTitle(html) ?: return null
        return usable(decodeEntities(social), url)
    }

    fun htmlString(data: ByteArray, contentType: String?): String {
        val declared = charsetName(contentType) ?: charsetName(String(data.take(2048).toByteArray(), Charsets.ISO_8859_1))
        val charset = declared?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(data, charset)
    }

    /** Picks a usable title from [DOCUMENT_TITLE_SCRIPT]. Android JSON-encodes the result. */
    fun pickJsPayload(raw: String?, url: String?): String? {
        val payload = unquoteJs(raw) ?: return null
        val parts = payload.split("\u001e", limit = 2)
        val title = parts.getOrNull(0).orEmpty()
        val social = parts.getOrNull(1).orEmpty()
        return usable(title, url) ?: usable(social, url)
    }

    fun unquoteJs(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
    }

    private fun isAbsoluteHttp(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }

    private fun identifies(title: String, url: String): Boolean {
        val host = host(url) ?: return false
        val bare = host.removePrefix("www.")
        val lower = title.lowercase()
        if (lower == host || lower == bare || lower == "www.$bare") return true
        val target = identity(url) ?: return false
        return listOf("https://$title", "http://$title").any { candidate ->
            identity(candidate) == target
        }
    }

    private fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url.trim()).host }.getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun identity(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        var host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        val path = when (val value = uri.path.orEmpty()) {
            "", "/" -> ""
            else -> value.trimEnd('/').lowercase()
        }
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return host + path + query
    }

    private fun socialTitle(html: String): String? {
        var twitter: String? = null
        for (match in metaTag.findAll(html)) {
            val tag = match.value
            val key = (attribute("property", tag) ?: attribute("name", tag))?.lowercase()
            val content = attribute("content", tag) ?: continue
            if (key == "og:title") return content
            if (key == "twitter:title" && twitter == null) twitter = content
        }
        return twitter
    }

    private fun attribute(name: String, tag: String): String? {
        val pattern = Regex(
            "\\b${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(tag) ?: return null
        for (index in 1..3) {
            val group = match.groups[index] ?: continue
            return group.value
        }
        return null
    }

    fun decodeEntities(text: String): String {
        val builder = StringBuilder()
        var cursor = 0
        for (match in entity.findAll(text)) {
            builder.append(text, cursor, match.range.first)
            cursor = match.range.last + 1
            builder.append(decodedEntity(match) ?: match.value)
        }
        builder.append(text, cursor, text.length)
        return builder.toString()
    }

    private fun decodedEntity(match: MatchResult): String? {
        val hex = match.groupValues[1]
        val decimal = match.groupValues[2]
        val name = match.groupValues[3]
        val code = when {
            hex.isNotEmpty() -> hex.toIntOrNull(16)
            decimal.isNotEmpty() -> decimal.toIntOrNull()
            else -> null
        }
        if (code != null) return codePointString(code)
        if (name.isNotEmpty()) return namedEntities[name.lowercase()]
        return null
    }

    private fun codePointString(code: Int): String? {
        if (code !in 0..0x10FFFF || code in 0xD800..0xDFFF) return null
        return String(Character.toChars(code))
    }

    private fun charsetName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return charset.find(header)?.groupValues?.getOrNull(1)
    }
}
