package de.kjell.zencompanion

import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.SearchSuggestions
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bodies are the live answers from 2026-10-11. Each engine returned
 * `["query", ["…"]]`. Google adds more elements after the list.
 */
class SearchSuggestionTests {
    @Test
    fun urlInputDoesNotBuildASuggestRequest() {
        val urls = listOf(
            "https://example.com/private",
            "http://example.com/foo bar",
            "example.com",
            "192.168.0.1:8080",
            "mailto:support@kjell.cc",
            "   \n\t",
            "",
        )
        SearchEngines.builtIn.forEach { engine ->
            urls.forEach { text ->
                assertNull(
                    "$engine.id must not be asked about $text",
                    SearchSuggestions.requestUrl(text, engine),
                )
            }
        }
    }

    @Test
    fun searchQueryBuildsTheCheckedEndpoint() {
        fun value(engine: SearchEngine, name: String): String? {
            val raw = SearchSuggestions.requestUrl("zen browser", engine)!!
            val query = URI(raw).rawQuery ?: return null
            return query.split("&").firstNotNullOfOrNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8) == name) {
                    URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                } else {
                    null
                }
            }
        }

        assertTrue(SearchSuggestions.requestUrl("zen browser", SearchEngine.DUCKDUCKGO)!!.startsWith("https://duckduckgo.com/ac/?"))
        assertEquals("zen browser", value(SearchEngine.DUCKDUCKGO, "q"))
        assertEquals("list", value(SearchEngine.DUCKDUCKGO, "type"))

        assertTrue(SearchSuggestions.requestUrl("zen browser", SearchEngine.GOOGLE)!!.startsWith("https://suggestqueries.google.com/complete/search?"))
        assertEquals("zen browser", value(SearchEngine.GOOGLE, "q"))
        assertEquals("firefox", value(SearchEngine.GOOGLE, "client"))

        assertTrue(SearchSuggestions.requestUrl("zen browser", SearchEngine.ECOSIA)!!.startsWith("https://ac.ecosia.org/autocomplete?"))
        assertEquals("list", value(SearchEngine.ECOSIA, "type"))

        assertTrue(SearchSuggestions.requestUrl("zen browser", SearchEngine.BRAVE)!!.startsWith("https://search.brave.com/api/suggest?"))
        assertEquals("zen browser", value(SearchEngine.BRAVE, "q"))

        assertTrue(SearchSuggestions.requestUrl("zen browser", SearchEngine.BING)!!.startsWith("https://www.bing.com/osjson.aspx?"))
        assertEquals("zen browser", value(SearchEngine.BING, "query"))
        assertNull(value(SearchEngine.BING, "q"))
    }

    @Test
    fun tappedSuggestionSearchesUnlessItIsAnHttpAddress() {
        assertEquals(
            SearchEngine.DUCKDUCKGO.searchURL("node.js"),
            SearchSuggestions.target("node.js", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://zen-browser.app",
            SearchSuggestions.target("https://zen-browser.app", SearchEngine.DUCKDUCKGO),
        )
    }

    @Test
    fun dotWithSpaceStillSuggests() {
        assertTrue(SearchSuggestions.requestUrl("hello.world foo", SearchEngine.GOOGLE)!!.contains("hello.world"))
    }

    @Test
    fun customEngineHasNoSuggestRequest() {
        val custom = SearchEngine(
            id = "kagi",
            displayName = "Kagi",
            template = "https://kagi.com/search?q={query}",
            isBuiltIn = false,
        )
        assertNull(SearchSuggestions.endpoint(custom))
        assertNull(SearchSuggestions.requestUrl("kagi", custom))
    }

    @Test
    fun parseLiveSuggestBodies() {
        val duckDuckGo = """["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser review","zen browser twilight","zen browser github"]]"""
        assertEquals(
            listOf(
                "zen browser", "zen browser download", "zen browser windows", "zen browser reddit",
                "zen browser portable", "zen browser review", "zen browser twilight", "zen browser github",
            ),
            SearchSuggestions.parse(duckDuckGo),
        )

        val google = """["zen browser",["zen browser","zen browser download","zen browser android","zen browser linux","zen browser ios","zen browser windows","zen browser arch","zen browser reddit","zen browser adblocker","zen browser github"],[],{"google:suggestsubtypes":[[512,433],[512]]}]"""
        val googleSuggestions = SearchSuggestions.parse(google)
        assertEquals(8, googleSuggestions.size)
        assertEquals("zen browser", googleSuggestions.first())
        assertFalse(googleSuggestions.contains("zen browser adblocker"))

        val ecosia = """["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser github","zen browser twilight","zen browser vs firefox"]]"""
        assertEquals("zen browser vs firefox", SearchSuggestions.parse(ecosia).last())

        val brave = """["zen browser",["zen browser","zen browser android","zen browser download","zen browser windows","zen browser arch","zen browser ios","zen browser apk","zen browser mobile"]]"""
        assertEquals("zen browser android", SearchSuggestions.parse(brave)[1])

        val bing = """["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser github","zen browser twilight","zen browser vs firefox","zen browser vs brave","zen browser available in windows","zen browser winget","zen browser android"]]"""
        assertEquals(8, SearchSuggestions.parse(bing).size)
        assertFalse(SearchSuggestions.parse(bing).contains("zen browser android"))
    }

    @Test
    fun parseDropsBlankDuplicatesAndNonStrings() {
        val body = """["q",["ok","  ","ok",1,null," second "]]"""
        assertEquals(listOf("ok", "second"), SearchSuggestions.parse(body))
    }

    @Test
    fun parseRejectsAnythingElse() {
        assertTrue(SearchSuggestions.parse("[]").isEmpty())
        assertTrue(SearchSuggestions.parse("""["only"]""").isEmpty())
        assertTrue(SearchSuggestions.parse("""{"suggestions":["a"]}""").isEmpty())
        assertTrue(SearchSuggestions.parse("not json").isEmpty())
        assertTrue(SearchSuggestions.parse("").isEmpty())
    }
}
