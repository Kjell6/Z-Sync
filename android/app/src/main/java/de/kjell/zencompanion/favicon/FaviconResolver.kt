package de.kjell.zencompanion.favicon

import de.kjell.zencompanion.sync.ZenSpaces
import java.net.URI

/** Port of `FaviconResolver` (Shared/ZenTheme.swift). */
object FaviconResolver {
    fun url(pageURL: String, directURL: String?): String? {
        if (!directURL.isNullOrEmpty()) {
            val scheme = runCatching { URI(directURL).scheme }.getOrNull()
            if (scheme == "https" || scheme == "http") return directURL
        }
        if (pageURL.isEmpty()) return null
        val uri = runCatching { URI(pageURL) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        return "https://icons.duckduckgo.com/ip3/$host.ico"
    }

    /** True for `about:`, `chrome:`, `resource:`, `data:` URLs that carry no
     *  web host — they render with a static placeholder icon, not a favicon. */
    fun isLocalURL(urlString: String): Boolean {
        val scheme = runCatching { URI(urlString).scheme }.getOrNull()?.lowercase() ?: return false
        return scheme != "http" && scheme != "https"
    }

    /** Unique remote icon URLs for every non-static tab in a snapshot. */
    fun urls(snapshot: ZenSpaces.ZenSnapshot): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        fun consider(tab: ZenSpaces.ZenTab) {
            if (tab.hasStaticIcon == true && !tab.icon.isNullOrEmpty()) return
            val url = url(pageURL = tab.url, directURL = tab.iconURL) ?: return
            if (seen.add(url)) result.add(url)
        }
        for (space in snapshot.spaces) {
            for (item in space.pinned) {
                when (item) {
                    is ZenSpaces.ZenItem.Tab -> consider(item.tab)
                    is ZenSpaces.ZenItem.Folder -> item.folder.tabs.forEach(::consider)
                    is ZenSpaces.ZenItem.Split -> item.split.tabs.forEach(::consider)
                }
            }
            for (item in space.tabs) {
                when (item) {
                    is ZenSpaces.ZenItem.Tab -> consider(item.tab)
                    is ZenSpaces.ZenItem.Folder -> item.folder.tabs.forEach(::consider)
                    is ZenSpaces.ZenItem.Split -> item.split.tabs.forEach(::consider)
                }
            }
        }
        snapshot.essentials.values.forEach { tabs -> tabs.forEach(::consider) }
        return result
    }
}
