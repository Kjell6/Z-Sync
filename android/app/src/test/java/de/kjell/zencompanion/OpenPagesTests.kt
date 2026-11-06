package de.kjell.zencompanion

import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.KeptPagesPolicy
import de.kjell.zencompanion.data.LeaveAction
import de.kjell.zencompanion.data.OpenPagesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenPagesTests {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_000_000L
    private val exit = OpenPagesStore.PageExit(failed = false, pinnedHere = false, browsedAway = false)

    private fun store(dir: java.io.File = folder.root) = OpenPagesStore(dir, now = { clock })

    private fun sameDocument(a: String, b: String) = a == b

    private val a = "https://a.example/"
    private val b = "https://b.example/"
    private val c = "https://c.example/"

    @Test
    fun closingTheBrowserDropsThrowawayPagesAndLeavesKeptOnes() {
        val pages = store()
        val removed = mutableListOf<String>()
        pages.onPageRemoved = { removed += it }
        val kept = pages.open(a)
        val throwaway = pages.open(b)
        pages.toggleKeep(kept.id, failed = false)

        pages.endSession(keepEveryPage = false) { exit }

        assertEquals(listOf(kept.id), pages.pages.value.map { it.id })
        assertNull(pages.activeId.value)
        assertEquals(listOf(throwaway.id), removed)
    }

    @Test
    fun keptPagesSurviveARestartAndThrowawayOnesDoNot() {
        val pages = store()
        val kept = pages.open(a, "A")
        pages.open(b, "B")
        pages.toggleKeep(kept.id, failed = false)

        val reopened = store().pages.value

        assertEquals(listOf(kept.id), reopened.map { it.id })
        assertEquals(a, reopened.single().url)
        assertEquals("A", reopened.single().title)
    }

    @Test
    fun aKeptPageTakesItsLatestAddressAcrossARestart() {
        val pages = store()
        val page = pages.open(a, "A")
        pages.toggleKeep(page.id, failed = false)

        pages.update(page.id, b, "")
        pages.update(page.id, "", "B")

        val reopened = store().pages.value.single()
        assertEquals(b, reopened.url)
        assertEquals("B", reopened.title)
    }

    @Test
    fun tappingKeptAgainMakesThePageThrowaway() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)
        pages.toggleKeep(page.id, failed = false)

        assertFalse(pages.page(page.id)!!.isKept)
        assertTrue(store().pages.value.isEmpty())
    }

    @Test
    fun aNewOrFailedPageCannotBeKept() {
        val pages = store()
        val blank = pages.open("")
        pages.toggleKeep(blank.id, failed = false)
        assertFalse(pages.page(blank.id)!!.isKept)

        val failed = pages.open(a)
        pages.toggleKeep(failed.id, failed = true)
        assertFalse(pages.page(failed.id)!!.isKept)
    }

    @Test
    fun keepEveryPageKeepsOnClose() {
        val pages = store()
        val page = pages.open(a)
        pages.endSession(keepEveryPage = true) { exit }
        assertTrue(pages.page(page.id)!!.isKept)
    }

    @Test
    fun keepEveryPageSkipsAnUntouchedTabFromASpaceButNotOneBrowsedAwayFrom() {
        val pages = store()
        pages.open(a, openedFromSpace = true)
        pages.endSession(keepEveryPage = true) { exit }
        assertTrue(pages.pages.value.isEmpty())

        val browsed = pages.open(a, openedFromSpace = true)
        pages.endSession(keepEveryPage = true) { exit.copy(browsedAway = true) }
        assertEquals(listOf(browsed.id), pages.pages.value.map { it.id })
    }

    @Test
    fun keepEveryPageSkipsAPageTheUserReleased() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)
        pages.toggleKeep(page.id, failed = false)
        pages.endSession(keepEveryPage = true) { exit }
        assertTrue(pages.pages.value.isEmpty())
    }

    @Test
    fun leavePolicy() {
        fun action(
            isKept: Boolean = false,
            keepEvery: Boolean = true,
            url: String = a,
            failed: Boolean = false,
            pinned: Boolean = false,
            released: Boolean = false,
            fromSpace: Boolean = false,
            browsedAway: Boolean = false,
        ) = KeptPagesPolicy.onLeave(isKept, keepEvery, url, failed, pinned, released, fromSpace, browsedAway)

        assertEquals(LeaveAction.UPDATE, action(isKept = true, keepEvery = false))
        assertEquals(LeaveAction.DISCARD, action(keepEvery = false))
        assertEquals(LeaveAction.KEEP, action())
        assertEquals(LeaveAction.DISCARD, action(url = "about:blank"))
        assertEquals(LeaveAction.DISCARD, action(url = ""))
        assertEquals(LeaveAction.DISCARD, action(failed = true))
        assertEquals(LeaveAction.DISCARD, action(pinned = true))
        assertEquals(LeaveAction.DISCARD, action(released = true))
        assertEquals(LeaveAction.DISCARD, action(fromSpace = true))
        assertEquals(LeaveAction.KEEP, action(fromSpace = true, browsedAway = true))
    }

    @Test
    fun neighboursFollowTheListOrder() {
        val pages = store()
        val first = pages.open(a)
        val second = pages.open(b)
        val third = pages.open(c)

        pages.activate(second.id)

        val (previous, next) = pages.neighbours()
        assertEquals(first.id, previous?.id)
        assertEquals(third.id, next?.id)
    }

    @Test
    fun aMovedPageKeepsItsPlaceAcrossARestart() {
        val pages = store()
        val first = pages.open(a)
        val second = pages.open(b)
        val third = pages.open(c)
        listOf(first, second, third).forEach { pages.toggleKeep(it.id, failed = false) }

        pages.move(first.id, third.id)
        assertEquals(listOf(second.id, third.id, first.id), pages.pages.value.map { it.id })

        pages.move(third.id, second.id)
        assertEquals(listOf(third.id, second.id, first.id), pages.pages.value.map { it.id })
        assertEquals(listOf(third.id, second.id, first.id), store().pages.value.map { it.id })
    }

    @Test
    fun aLinkOpensBesideItsOpenerAndClosingReturnsThere() {
        val pages = store()
        val opener = pages.open(a)
        val other = pages.open(b)
        pages.activate(opener.id)
        val link = pages.open(c, openerId = opener.id)

        assertEquals(listOf(a, c, b), pages.pages.value.map { it.url })

        assertEquals(opener.id, pages.close(link.id))
        assertEquals(listOf(opener.id, other.id), pages.pages.value.map { it.id })
    }

    @Test
    fun closingTheLastPageLeavesNothingOnScreen() {
        val pages = store()
        val page = pages.open(a)
        assertNull(pages.close(page.id))
        assertNull(pages.activeId.value)
    }

    @Test
    fun pinningAKeptPageTakesItOutOfTheList() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)

        pages.pinned(page.id, a, ::sameDocument)

        assertFalse(pages.page(page.id)!!.isKept)
        assertTrue(store().pages.value.isEmpty())
    }

    @Test
    fun aPinOfAnEarlierAddressLeavesAMovedPageKept() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)
        pages.update(page.id, b, "")

        pages.pinned(page.id, a, ::sameDocument)

        assertTrue(pages.page(page.id)!!.isKept)
    }

    @Test
    fun aKeptPageLosesItsWayBackWhenItsOpenerGoesWithTheBrowser() {
        val pages = store()
        val opener = pages.open(a)
        val linked = pages.open(b, openerId = opener.id)
        pages.toggleKeep(linked.id, failed = false)

        pages.endSession(keepEveryPage = false) { exit }

        assertEquals(listOf(linked.id), pages.pages.value.map { it.id })
        assertEquals(null, pages.page(linked.id)!!.openerId)
    }

    @Test
    fun leavingTheAppCountsAsUseOfThePageOnScreenOnly() {
        val pages = store()
        val idle = pages.open(a)
        val used = pages.open(b)
        val start = clock

        clock += KeptPageLifetime.DAY.millis!!
        pages.markActiveUsed()

        assertEquals(start, pages.page(idle.id)!!.lastUsedAt)
        assertEquals(clock, pages.page(used.id)!!.lastUsedAt)
    }

    @Test
    fun aFailedPinLeavesThePageItsOwn() {
        val pages = store()
        val page = pages.open(a)
        pages.markPinned(page.id, a)

        pages.unmarkPinned(page.id)

        assertEquals(null, pages.page(page.id)!!.pinnedUrl)
    }

    @Test
    fun closingTheBrowserOnlyRefreshesThePageOnScreen() {
        val pages = store()
        val idle = pages.open(a)
        val used = pages.open(b)
        val start = clock

        clock += 20 * KeptPageLifetime.DAY.millis!!
        pages.endSession(keepEveryPage = true) { exit }
        assertEquals(start, pages.page(idle.id)!!.lastUsedAt)
        assertEquals(clock, pages.page(used.id)!!.lastUsedAt)

        clock += 10 * KeptPageLifetime.DAY.millis!!
        pages.closeExpired(KeptPageLifetime.MONTH)
        assertEquals(listOf(used.id), pages.pages.value.map { it.id })
    }

    @Test
    fun keptPagesCloseAfterTheChosenPeriod() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)
        pages.endSession(keepEveryPage = false) { exit }

        clock += KeptPageLifetime.DAY.millis!! - 1
        pages.closeExpired(KeptPageLifetime.DAY)
        assertEquals(1, pages.pages.value.size)

        clock += 1
        pages.closeExpired(KeptPageLifetime.DAY)
        assertTrue(pages.pages.value.isEmpty())
        assertTrue(store().pages.value.isEmpty())
    }

    @Test
    fun thePageOnScreenNeverExpires() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)

        clock += 10 * KeptPageLifetime.DAY.millis!!
        pages.closeExpired(KeptPageLifetime.DAY)

        assertEquals(page.id, pages.activeId.value)
    }

    @Test
    fun keptPagesStayByDefault() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)
        pages.endSession(keepEveryPage = false) { exit }

        clock += 365 * KeptPageLifetime.DAY.millis!!
        pages.closeExpired(KeptPageLifetime.NEVER)

        assertEquals(listOf(page.id), pages.pages.value.map { it.id })
    }

    @Test
    fun pagesCloseAfterAMonthUnlessToldOtherwise() {
        assertEquals(KeptPageLifetime.MONTH, KeptPageLifetime.fromStorage(null))
        assertEquals(KeptPageLifetime.MONTH, KeptPageLifetime.fromStorage("nonsense"))
        assertEquals(KeptPageLifetime.NEVER, KeptPageLifetime.fromStorage("never"))
    }

    @Test
    fun pagesStayWhenTheBrowserClosesLikeInAnyBrowser() {
        val pages = store()
        val page = pages.open(a)
        pages.endSession(keepEveryPage = true) { exit }

        assertEquals(listOf(page.id), pages.pages.value.map { it.id })
        assertEquals(listOf(a), store().pages.value.map { it.url })
    }

    @Test
    fun aPageThatWasPinnedStaysOnceTheUserBrowsesOn() {
        val pages = store()
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)

        pages.pinned(page.id, a, ::sameDocument)
        assertFalse(pages.page(page.id)!!.isKept)

        pages.update(page.id, b, "")
        pages.endSession(keepEveryPage = true) { exit }
        assertEquals(listOf(page.id), pages.pages.value.map { it.id })
    }

    @Test
    fun aDamagedFileLosesTheListNotTheApp() {
        java.io.File(folder.root, "pages.json").writeText("not json")
        assertTrue(store().pages.value.isEmpty())
    }

    @Test
    fun closeAllLeavesNothingOnDisk() {
        val dir = java.io.File(folder.root, "pages")
        val pages = store(dir)
        val page = pages.open(a)
        pages.toggleKeep(page.id, failed = false)

        pages.closeAll()

        assertTrue(pages.pages.value.isEmpty())
        assertFalse(dir.exists())
    }
}
