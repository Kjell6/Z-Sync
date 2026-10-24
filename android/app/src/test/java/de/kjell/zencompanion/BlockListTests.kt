package de.kjell.zencompanion

import de.kjell.zencompanion.data.BlockList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlockListTests {
    private val sample = """
        [Adblock Plus 2.0]
        ! Version: 202610242150
        ! Title: EasyList
        ! Last modified: 24 Oct 2026 21:50 UTC
        ||ads.example^
        ||tracker.example^${'$'}third-party
        ||pixel.example^${'$'}script,image
        ||popup.example^${'$'}popup
        ||scam.example^${'$'}document
        ||one-site.example^${'$'}domain=news.example
        ||path.example/ads/
        ##.ad-banner
        @@||ads.example^${'$'}domain=shop.example|~other.example
        @@||cdn.ads.example^
        @@||own.tracker.example^${'$'}~third-party
        ||own.tracker.example^
        @@||safe.example^${'$'}document
        ||widget.example^
        @@||jetzt.de^${'$'}generichide
        ||cdn2.example^
        @@||cdn2.example^${'$'}script
        @@||ads.example^${'$'}domain=bad"site.example|shop2.example
    """.trimIndent()

    private val list = BlockList.parse(sample)

    @Test
    fun readsTheHeader() {
        assertEquals("202610242150", list.version)
        assertEquals("24 Oct 2026 21:50 UTC", list.lastModified)
    }

    @Test
    fun keepsOnlyDomainRulesItCanApply() {
        // `pixel.example` is limited to some request types, so it is skipped.
        assertEquals(setOf("ads.example", "own.tracker.example", "widget.example", "cdn2.example"), list.anywhere)
        assertEquals(setOf("tracker.example"), list.thirdParty)
        assertEquals(5, list.ruleCount)
    }

    @Test
    fun blocksTheDomainAndItsSubdomains() {
        assertTrue(list.blocks("ads.example", "news.example"))
        assertTrue(list.blocks("eu.ads.example", "news.example"))
        assertFalse(list.blocks("badads.example", "news.example"))
        assertFalse(list.blocks("popup.example", "news.example"))
        assertFalse(list.blocks("path.example", "news.example"))
    }

    @Test
    fun thirdPartyRulesSpareTheSiteItself() {
        assertTrue(list.blocks("tracker.example", "news.example"))
        assertFalse(list.blocks("tracker.example", "www.tracker.example"))
        assertFalse(list.blocks("tracker.example", null))
    }

    @Test
    fun exceptionsUndoBlocks() {
        assertFalse("allowed on shop.example", list.blocks("ads.example", "www.shop.example"))
        assertTrue("the ~ site is not an exception", list.blocks("ads.example", "other.example"))
        assertFalse("allowed everywhere", list.blocks("cdn.ads.example", "news.example"))
        assertFalse("first party allowed", list.blocks("own.tracker.example", "app.tracker.example"))
        assertTrue("third party still blocked", list.blocks("own.tracker.example", "news.example"))
        assertFalse("page exception", list.blocks("widget.example", "safe.example"))
        assertFalse("an exception for some types allows all", list.blocks("cdn2.example", "news.example"))
    }

    @Test
    fun exceptionSitesMustBePlainNames() {
        assertFalse(list.blocks("ads.example", "shop2.example"))
        assertTrue(list.blocks("ads.example", "bad\"site.example"))
    }

    @Test
    fun hostsAreCaseAndDotInsensitive() {
        assertTrue(list.blocks("ADS.Example.", "news.example"))
    }

    @Test
    fun sameSiteHandlesShortSecondLevelDomains() {
        assertTrue(BlockList.isSameSite("cdn.bbc.co.uk", "www.bbc.co.uk"))
        assertFalse(BlockList.isSameSite("bbc.co.uk", "itv.co.uk"))
        assertTrue(BlockList.isSameSite("a.example.com", "b.example.com"))
        assertFalse(BlockList.isSameSite("example.com", "example.net"))
    }

    @Test
    fun bundledListsParse() {
        for (name in listOf("easylist", "easyprivacy")) {
            val file = File("src/main/assets/content-blocking/$name.txt")
            val parsed = BlockList.parse(file.readText())
            assertTrue("$name has rules", parsed.ruleCount > 10_000)
            assertTrue("$name has a version", parsed.version != null)
        }
    }
}
