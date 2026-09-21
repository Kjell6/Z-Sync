package de.kjell.zencompanion

import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.SearchEngineTemplate
import de.kjell.zencompanion.data.SearchEngineValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchEngineTests {

    @Test
    fun testBuiltInsHaveStableOrderAndIds() {
        assertEquals(
            listOf("duckduckgo", "google", "ecosia", "brave", "bing"),
            SearchEngines.builtIn.map { it.id },
        )
        assertEquals(true, SearchEngines.builtIn.all { it.isBuiltIn })
    }

    @Test
    fun testDisplayNames() {
        assertEquals("DuckDuckGo", SearchEngine.DUCKDUCKGO.displayName)
        assertEquals("Google", SearchEngine.GOOGLE.displayName)
        assertEquals("Ecosia", SearchEngine.ECOSIA.displayName)
        assertEquals("Brave", SearchEngine.BRAVE.displayName)
        assertEquals("Bing", SearchEngine.BING.displayName)
    }

    @Test
    fun testFormatQueryTemplates() {
        assertEquals(
            "https://duckduckgo.com/?q=jetpack+compose",
            SearchEngine.DUCKDUCKGO.formatQuery("jetpack compose"),
        )
        assertEquals(
            "https://www.google.com/search?q=jetpack+compose",
            SearchEngine.GOOGLE.formatQuery("jetpack compose"),
        )
        assertEquals(
            "https://www.ecosia.org/search?q=jetpack+compose",
            SearchEngine.ECOSIA.formatQuery("jetpack compose"),
        )
        assertEquals(
            "https://search.brave.com/search?q=jetpack+compose",
            SearchEngine.BRAVE.formatQuery("jetpack compose"),
        )
        assertEquals(
            "https://www.bing.com/search?q=jetpack+compose",
            SearchEngine.BING.formatQuery("jetpack compose"),
        )
    }

    @Test
    fun testFormatQuerySpecialCharacters() {
        val query = "kotlin & swift = fast?"
        val formatted = SearchEngine.DUCKDUCKGO.formatQuery(query)
        assertEquals("https://duckduckgo.com/?q=kotlin+%26+swift+%3D+fast%3F", formatted)
    }

    @Test
    fun testCustomTemplateFormatQuery() {
        val engine = SearchEngine(
            id = "kagi",
            displayName = "Kagi",
            template = "https://kagi.com/search?q={query}",
            isBuiltIn = false,
        )
        assertEquals("https://kagi.com/search?q=swift", engine.formatQuery("swift"))
    }

    @Test
    fun testFromKeyLookup() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey("duckduckgo"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey("DUCKDUCKGO"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey("DuckDuckGo"))
        assertEquals(SearchEngine.GOOGLE, SearchEngines.fromKey("google"))
        assertEquals(SearchEngine.GOOGLE, SearchEngines.fromKey("GOOGLE"))
        assertEquals(SearchEngine.ECOSIA, SearchEngines.fromKey("ecosia"))
        assertEquals(SearchEngine.BRAVE, SearchEngines.fromKey("brave"))
        assertEquals(SearchEngine.BING, SearchEngines.fromKey("bing"))

        // Defaults to DuckDuckGo for unknown or null keys
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey(null))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey("unknown_engine"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngines.fromKey(""))
    }

    @Test
    fun testFromKeyMatchesCustomCandidates() {
        val custom = SearchEngine("uuid-1", "Kagi", "https://kagi.com/search?q={query}", false)
        assertEquals(custom, SearchEngines.fromKey("kagi", listOf(custom)))
        assertEquals(custom, SearchEngines.fromKey("Kagi", listOf(custom)))
    }

    // MARK: Validation

    @Test
    fun testValidateRejectsEmptyName() {
        assertEquals(
            SearchEngineValidation.EMPTY_NAME,
            SearchEngineTemplate.validate("  ", "https://x.com/?q={query}"),
        )
    }

    @Test
    fun testValidateRejectsNonHttpTemplates() {
        assertEquals(
            SearchEngineValidation.INVALID_URL,
            SearchEngineTemplate.validate("Evil", "javascript:alert(1){query}"),
        )
        assertEquals(
            SearchEngineValidation.INVALID_URL,
            SearchEngineTemplate.validate("Evil", "file:///tmp/{query}"),
        )
    }

    @Test
    fun testValidateRequiresPlaceholder() {
        assertEquals(
            SearchEngineValidation.MISSING_PLACEHOLDER,
            SearchEngineTemplate.validate("Kagi", "https://kagi.com/search?q=test"),
        )
    }

    @Test
    fun testValidateAcceptsGoodDrafts() {
        assertNull(SearchEngineTemplate.validate("Kagi", "https://kagi.com/search?q={query}"))
        assertNull(SearchEngineTemplate.validate("Searx", "http://localhost:8080/search?q={query}"))
    }

    // MARK: Paste derivation

    @Test
    fun testDeriveReplacesPreferredQueryParameter() {
        assertEquals(
            "https://kagi.com/search?q={query}",
            SearchEngineTemplate.derive("https://kagi.com/search?q=blauer+kaffee"),
        )
    }

    @Test
    fun testDeriveKeepsOtherParametersAndFragment() {
        assertEquals(
            "https://x.com/s?lang=de&q={query}#top",
            SearchEngineTemplate.derive("https://x.com/s?lang=de&q=hello#top"),
        )
    }

    @Test
    fun testDeriveFallsBackToLastValuedParameter() {
        assertEquals(
            "https://search.example/find?term={query}",
            SearchEngineTemplate.derive("https://search.example/find?term=abc"),
        )
    }

    @Test
    fun testDeriveRejectsNonSearches() {
        assertNull(SearchEngineTemplate.derive("https://example.com/path"))
        assertNull(SearchEngineTemplate.derive("not a url"))
        assertNull(SearchEngineTemplate.derive("javascript:alert(1)"))
    }

    // MARK: Suggested name

    @Test
    fun testSuggestedNameUsesRegistrableDomain() {
        // A sub-domain like "de" or a "search." prefix must not become the name.
        assertEquals(
            "Yahoo",
            SearchEngineTemplate.suggestedName("https://de.search.yahoo.com/search?p={query}"),
        )
        assertEquals(
            "Brave",
            SearchEngineTemplate.suggestedName("https://search.brave.com/search?q={query}"),
        )
        assertEquals(
            "Kagi",
            SearchEngineTemplate.suggestedName("https://www.kagi.com/search?q={query}"),
        )
        assertEquals(
            "Duckduckgo",
            SearchEngineTemplate.suggestedName("https://duckduckgo.com/?q={query}"),
        )
        assertEquals(
            "Bbc",
            SearchEngineTemplate.suggestedName("https://www.bbc.co.uk/search?q={query}"),
        )
        assertEquals(
            "Localhost",
            SearchEngineTemplate.suggestedName("http://localhost:8080/search?q={query}"),
        )
        assertNull(SearchEngineTemplate.suggestedName("not a url"))
    }

    @Test
    fun testFormatBrowserInput() {
        assertEquals("", de.kjell.zencompanion.ui.screens.formatBrowserInput(""))
        assertEquals("", de.kjell.zencompanion.ui.screens.formatBrowserInput("   "))

        // Standard URLs with scheme
        assertEquals("https://google.com", de.kjell.zencompanion.ui.screens.formatBrowserInput("https://google.com"))
        assertEquals("http://example.com/test", de.kjell.zencompanion.ui.screens.formatBrowserInput("http://example.com/test"))

        // Host domain without scheme
        assertEquals("https://kotlinlang.org", de.kjell.zencompanion.ui.screens.formatBrowserInput("kotlinlang.org"))
        assertEquals("https://github.com/torvalds/linux", de.kjell.zencompanion.ui.screens.formatBrowserInput("github.com/torvalds/linux"))

        // Search query
        assertEquals(
            "https://duckduckgo.com/?q=android+jetpack+compose",
            de.kjell.zencompanion.ui.screens.formatBrowserInput("android jetpack compose"),
        )
    }
}
