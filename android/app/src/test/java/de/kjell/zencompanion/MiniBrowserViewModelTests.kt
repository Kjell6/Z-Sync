package de.kjell.zencompanion

import androidx.compose.ui.text.input.TextFieldValue
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.components.PinDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniBrowserViewModelTests {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        SearchEngines.invalidateCache()
    }

    private fun testSpace(id: String): ZenSpaces.ZenSpace = ZenSpaces.ZenSpace(
        id = id,
        name = id,
        icon = null,
        containerGuid = null,
        theme = null,
        pinned = emptyList(),
    )

    private fun newViewModel(
        repository: FakeMiniBrowserRepository = FakeMiniBrowserRepository(),
        initialUrl: String? = "https://start.example/",
        spaces: List<ZenSpaces.ZenSpace> = listOf(testSpace("s1"), testSpace("s2")),
    ) = MiniBrowserViewModel(
        repository = repository,
        initialUrl = initialUrl,
        initialTitle = "Start",
        spaces = spaces,
        currentSpaceId = "s1",
    )

    @Test
    fun submitAddressResolvesUrlsAndSearchQueries() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()

        assertEquals("https://example.com", vm.submitAddress("example.com"))
        assertEquals("https://example.com", vm.submitAddress("https://example.com"))
        assertEquals("", vm.submitAddress("   "))
        assertEquals(
            SearchEngine.DUCKDUCKGO.formatQuery("hello world"),
            vm.submitAddress("hello world"),
        )
    }

    @Test
    fun bannerTimerCommitsAndHidesAfterFiveSeconds() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        assertTrue(vm.state.value.showPinBanner)

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(repository.added.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, repository.added.size)
        assertFalse(vm.state.value.showPinBanner)
        assertEquals("https://start.example/", repository.added[0].url)
        assertEquals("s1", repository.added[0].spaceId)
    }

    @Test
    fun menuOpenCancelsTimerAndDismissRestartsWith1500ms() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        vm.onMenuWillOpen()

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(repository.added.isEmpty())
        assertTrue(vm.state.value.showPinBanner)

        vm.onMenuDidDismiss()
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(repository.added.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, repository.added.size)
        assertFalse(vm.state.value.showPinBanner)
    }

    @Test
    fun destinationChangeRestartsWith1200ms() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        vm.changePinDestination(PinDestination(spaceId = "s2"))

        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(1, repository.added.size)
        assertEquals("s2", repository.added[0].spaceId)
    }

    @Test
    fun backPrecedenceHandlesMenusFocusHistoryThenDismiss() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)
        vm.onAddressFocusChange(true)
        vm.triggerPinBanner()
        vm.onSpaceMenuOpenChange(true)

        assertEquals(MiniBrowserViewModel.BackAction.Handled, vm.onBackPressed(webViewCanGoBack = false))
        assertFalse(vm.state.value.isSpaceMenuOpen)

        assertEquals(MiniBrowserViewModel.BackAction.ClearAddressFocus, vm.onBackPressed(webViewCanGoBack = false))
        assertFalse(vm.state.value.isAddressFocused)

        assertEquals(MiniBrowserViewModel.BackAction.GoBack, vm.onBackPressed(webViewCanGoBack = true))

        assertEquals(MiniBrowserViewModel.BackAction.Dismiss, vm.onBackPressed(webViewCanGoBack = false))
        assertEquals(1, repository.added.size)

        // Drain the banner timer still scheduled from the trigger.
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun emptySpacesNeverTriggerBannerOrWrite() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository, spaces = emptyList())

        vm.triggerPinBanner()
        assertFalse(vm.state.value.showPinBanner)

        vm.commitPendingPinSave()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(repository.added.isEmpty())
    }

    @Test
    fun newLaunchKeyResetsStateButRepeatedKeyKeepsIt() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)
        vm.triggerPinBanner()
        assertTrue(vm.state.value.showPinBanner)

        vm.startNewLaunch(
            launchKey = 2,
            initialUrl = "https://two.example/",
            initialTitle = "Two",
            spaces = listOf(testSpace("s1"), testSpace("s2")),
            currentSpaceId = "s2",
        )
        assertFalse(vm.state.value.showPinBanner)
        assertEquals("https://two.example/", vm.state.value.currentUrl)
        assertEquals(PinDestination(spaceId = "s2"), vm.state.value.pinnedDestination)

        vm.onCurrentUrlChange("https://three.example/")
        vm.startNewLaunch(
            launchKey = 2,
            initialUrl = "https://two.example/",
            initialTitle = "Two",
            spaces = listOf(testSpace("s1"), testSpace("s2")),
            currentSpaceId = "s2",
        )
        assertEquals("https://three.example/", vm.state.value.currentUrl)

        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun normalSaveTargetsSpaceRootAndShowsFallbackNotice() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository(
            saveKind = SaveKind.NORMAL,
            fallBackToPinned = true,
        )
        val vm = newViewModel(repository)

        assertTrue(vm.state.value.hideFolders)
        vm.changePinDestination(PinDestination(spaceId = "s2", folderId = "folder-1"))
        assertEquals(PinDestination(spaceId = "s2"), vm.state.value.pinnedDestination)

        vm.triggerPinBanner()
        vm.commitPendingPinSave()
        runCurrent()

        assertEquals(1, repository.added.size)
        assertEquals(SaveKind.NORMAL, repository.added[0].kind)
        assertEquals("s2", repository.added[0].spaceId)
        assertNull(repository.added[0].folderId)
        assertTrue(vm.state.value.fallbackNotice)

        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(vm.state.value.fallbackNotice)

        // Drain the banner timer still scheduled from the trigger.
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun deferredPinSaveRunsOnlyOnce() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        vm.commitPendingPinSave()
        vm.commitPendingPinSave()

        assertEquals(1, repository.added.size)

        // Drain the banner timer still scheduled from the trigger.
        advanceTimeBy(5_000)
        runCurrent()
    }
}

private class FakeMiniBrowserRepository(
    var saveKind: SaveKind = SaveKind.PINNED,
    var fallBackToPinned: Boolean = false,
) : BrowserRepository {
    data class AddedTab(
        val url: String,
        val title: String,
        val spaceId: String,
        val folderId: String?,
        val kind: SaveKind,
    )

    val added = mutableListOf<AddedTab>()

    override val cachedSnapshot: ZenSpaces.ZenSnapshot?
        get() = null

    override fun lastSpaceId(): String? = null

    override fun setLastSpaceId(id: String?) = Unit

    override suspend fun refresh(): ZenSpaces.ZenSnapshot = ZenSpaces.ZenSnapshot(emptyList())

    override suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): SpacesSyncService.AddTabOutcome {
        added += AddedTab(url, title, spaceId, folderId, kind)
        val effective = if (fallBackToPinned) SaveKind.PINNED else kind
        return SpacesSyncService.AddTabOutcome(
            recordId = "tab-${added.size}",
            kind = effective,
            fellBackToPinned = fallBackToPinned,
        )
    }

    override suspend fun deleteTab(id: String) = Unit

    override fun isDemo(): Boolean = false

    override fun saveKind(): SaveKind = saveKind
}
