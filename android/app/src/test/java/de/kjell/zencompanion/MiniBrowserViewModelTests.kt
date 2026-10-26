package de.kjell.zencompanion

import androidx.compose.ui.text.input.TextFieldValue
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.browser.SearchSuggestionClient
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.screens.BrowserPageError
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
        suggestionClient: SearchSuggestionClient = ForbiddenSuggestionClient(),
        suggestionDebounceMs: Long = 200,
    ) = MiniBrowserViewModel(
        repository = repository,
        initialUrl = initialUrl,
        initialTitle = "Start",
        spaces = spaces,
        currentSpaceId = "s1",
        suggestionClient = suggestionClient,
        suggestionDebounceMs = suggestionDebounceMs,
    )

    @Test
    fun aFailedLoadShowsThePanelForTheFailedAddress() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        vm.onPageLoadFailed(BrowserPageError.HOST_NOT_FOUND, "https://wikipeida.org/")

        assertEquals(BrowserPageError.HOST_NOT_FOUND, vm.state.value.pageError)
        assertEquals("https://wikipeida.org/", vm.state.value.failedUrl)
        // WebView's own error page title never becomes the page title.
        vm.onCurrentTitleChange("Webpage not available")
        assertEquals("Start", vm.state.value.currentTitle)
    }

    @Test
    fun aNewLoadClearsThePanel() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        vm.onPageLoadFailed(BrowserPageError.NOT_RESPONDING, "https://slow.example/")
        vm.clearPageError()

        assertNull(vm.state.value.pageError)
        assertEquals("", vm.state.value.failedUrl)
    }

    @Test
    fun revealingThePageUnderneathRestoresItsAddress() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        vm.onPageLoadFailed(BrowserPageError.INSECURE, "https://expired.badssl.com/")
        vm.onCurrentUrlChange("https://expired.badssl.com/")

        vm.revealPage("https://example.com/")

        assertNull(vm.state.value.pageError)
        assertEquals("https://example.com/", vm.state.value.currentUrl)
    }

    @Test
    fun searchUsesTheHostThatCouldNotBeFound() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        assertEquals("", vm.searchForFailedHost())

        vm.onPageLoadFailed(BrowserPageError.HOST_NOT_FOUND, "https://wikipeida.org/wiki/Aurora")

        assertEquals(SearchEngines.current.formatQuery("wikipeida.org"), vm.searchForFailedHost())
    }

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
    fun pinSaveUsesDocumentTitleInsteadOfTheUrl() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)
        vm.onCurrentTitleChange("https://start.example/")
        vm.documentTitle = { "Real Page" }

        vm.triggerPinBanner()
        vm.commitPendingPinSave()
        runCurrent()

        assertEquals("Real Page", repository.added.first().title)
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun urlShapedTitleDoesNotReplaceARealTitle() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()
        vm.onCurrentTitleChange("Real Page")
        vm.onCurrentTitleChange("https://start.example/")
        assertEquals("Real Page", vm.state.value.currentTitle)
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
        // Recreating the screen for the same launch reopens the current page;
        // a new launch never sees it.
        assertEquals("https://three.example/", vm.restoredUrl(2))
        assertNull(vm.restoredUrl(3))

        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun normalSaveTargetsSpaceRootAndShowsFallbackNotice() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository(
            saveKind = SaveKind.NORMAL,
            fallBackToPinned = true,
            capability = ZenSpaces.NormalTabsCapability.ENABLED,
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
        assertEquals(MiniBrowserViewModel.Notice(R.string.save_fallback_normal_off), vm.state.value.notice)

        advanceTimeBy(6_000)
        runCurrent()
        assertNull(vm.state.value.notice)

        // Drain the banner timer still scheduled from the trigger.
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun navigatingAwayCommitsThePinnedPage() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository, initialUrl = "https://pin.example/a")
        vm.onCurrentTitleChange("Page A")

        vm.triggerPinBanner()
        vm.onCurrentTitleChange("Page B")
        vm.onCurrentUrlChange("https://pin.example/b")
        runCurrent()

        assertEquals(listOf("https://pin.example/a"), repository.added.map { it.url })
        assertEquals("Page A", repository.added[0].title)
        assertFalse(vm.state.value.showPinBanner)

        // The banner timer must not save a second time.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, repository.added.size)
    }

    @Test
    fun pinFailureShowsNotice() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository(failure = SyncError.Conflict())
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        vm.commitPendingPinSave()
        runCurrent()

        assertEquals(
            MiniBrowserViewModel.Notice(R.string.browser_pin_failed, R.string.error_conflict),
            vm.state.value.notice,
        )
        advanceTimeBy(6_000)
        runCurrent()
    }

    @Test
    fun pinKindStartsFromTheSettingAndCanChange() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        assertEquals(SaveKind.PINNED, vm.state.value.saveKind)

        vm.selectPinSaveKind(SaveKind.NORMAL)
        assertEquals(SaveKind.NORMAL, vm.state.value.saveKind)

        vm.commitPendingPinSave()
        runCurrent()

        assertEquals(SaveKind.NORMAL, repository.added.single().kind)
    }

    @Test
    fun normalKindNeedsTheCapability() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository(
            saveKind = SaveKind.NORMAL,
            capability = ZenSpaces.NormalTabsCapability.DISABLED,
        )
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        assertEquals(SaveKind.PINNED, vm.state.value.saveKind)

        vm.selectPinSaveKind(SaveKind.NORMAL)
        assertEquals(SaveKind.PINNED, vm.state.value.saveKind)
    }

    @Test
    fun pinKindIsNotPersisted() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeMiniBrowserRepository()
        val vm = newViewModel(repository)

        vm.triggerPinBanner()
        vm.selectPinSaveKind(SaveKind.NORMAL)
        vm.startNewLaunch(
            launchKey = 99,
            initialUrl = "https://start.example/",
            initialTitle = "Start",
            spaces = listOf(testSpace("s1"), testSpace("s2")),
            currentSpaceId = "s1",
        )

        assertEquals(SaveKind.PINNED, vm.state.value.saveKind)
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

    @Test
    fun urlInputDoesNotRequestSuggestions() = runTest(mainDispatcherRule.testDispatcher) {
        val client = RecordingSuggestionClient()
        val vm = newViewModel(initialUrl = null, suggestionClient = client)
        vm.onAddressFocusChange(true)

        listOf(
            "https://example.com/private",
            "http://example.com/foo bar",
            "example.com",
            "   ",
        ).forEach { text ->
            vm.onAddressTextChange(TextFieldValue(text))
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertTrue(client.requested.isEmpty())
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test
    fun searchQueryRequestsSuggestionsAfterDebounce() = runTest(mainDispatcherRule.testDispatcher) {
        val client = RecordingSuggestionClient("""["zen",["zen browser","zen browser download"]]""")
        val vm = newViewModel(initialUrl = null, suggestionClient = client)
        vm.onAddressFocusChange(true)
        vm.onAddressTextChange(TextFieldValue("zen browser"))

        advanceTimeBy(199)
        runCurrent()
        assertTrue(client.requested.isEmpty())

        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, client.requested.size)
        assertTrue(client.requested.single().contains("q=zen+browser"))
        assertEquals(listOf("zen browser", "zen browser download"), vm.state.value.suggestions)
    }

    @Test
    fun changingToAUrlCancelsThePendingQuery() = runTest(mainDispatcherRule.testDispatcher) {
        val client = RecordingSuggestionClient()
        val vm = newViewModel(
            initialUrl = null,
            suggestionClient = client,
            suggestionDebounceMs = 30_000,
        )
        vm.onAddressFocusChange(true)
        vm.onAddressTextChange(TextFieldValue("hello"))
        advanceTimeBy(100)
        vm.onAddressTextChange(TextFieldValue("https://secret.example/path"))
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(client.requested.isEmpty())
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test
    fun customEngineDoesNotRequestSuggestions() = runTest(mainDispatcherRule.testDispatcher) {
        SearchEngines.current = SearchEngine(
            id = "kagi",
            displayName = "Kagi",
            template = "https://kagi.com/search?q={query}",
            isBuiltIn = false,
        )
        val client = RecordingSuggestionClient()
        val vm = newViewModel(initialUrl = null, suggestionClient = client)
        vm.onAddressFocusChange(true)
        vm.onAddressTextChange(TextFieldValue("hello"))
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun closingTheFieldDropsTheList() = runTest(mainDispatcherRule.testDispatcher) {
        val client = RecordingSuggestionClient("""["zen",["zen browser"]]""")
        val vm = newViewModel(initialUrl = null, suggestionClient = client)
        vm.onAddressFocusChange(true)
        vm.onAddressTextChange(TextFieldValue("zen"))

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("zen browser"), vm.state.value.suggestions)

        vm.endAddressEdit()
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test
    fun selectedSuggestionSearchesAndClosesTheList() = runTest(mainDispatcherRule.testDispatcher) {
        val client = RecordingSuggestionClient("""["zen",["zen browser"]]""")
        val vm = newViewModel(initialUrl = null, suggestionClient = client)
        vm.onAddressFocusChange(true)
        vm.onAddressTextChange(TextFieldValue("zen"))
        advanceTimeBy(500)
        runCurrent()

        val target = vm.selectSuggestion("node.js")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(SearchEngine.DUCKDUCKGO.searchURL("node.js"), target)
        assertTrue(vm.state.value.suggestions.isEmpty())
        // The text write that follows a pick must not start another lookup.
        assertEquals(1, client.requested.size)
    }

    @Test
    fun theZoomReadoutResetsBackToOneHundredPercent() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel()

        vm.zoomIn()
        vm.zoomIn()
        assertEquals(1.2f, vm.state.value.pageZoom, 0.001f)

        vm.resetPageZoom()
        assertEquals(MiniBrowserZoom.default, vm.state.value.pageZoom, 0.001f)
        assertFalse(MiniBrowserZoom.canReset(vm.state.value.pageZoom))
    }
}

private class RecordingSuggestionClient(
    private val body: String = "[]",
) : SearchSuggestionClient {
    val requested = mutableListOf<String>()

    override suspend fun fetch(url: String): String {
        requested += url
        return body
    }
}

private class ForbiddenSuggestionClient : SearchSuggestionClient {
    override suspend fun fetch(url: String): String {
        error("suggest request was not allowed: $url")
    }
}

private class FakeMiniBrowserRepository(
    var saveKind: SaveKind = SaveKind.PINNED,
    var fallBackToPinned: Boolean = false,
    var failure: Exception? = null,
    var capability: ZenSpaces.NormalTabsCapability = ZenSpaces.NormalTabsCapability.ENABLED,
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
        get() = ZenSpaces.ZenSnapshot(emptyList()).copy(normalTabsCapability = capability)

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
        failure?.let { throw it }
        val effective = if (fallBackToPinned) SaveKind.PINNED else kind
        return SpacesSyncService.AddTabOutcome(
            recordId = "tab-${added.size}",
            kind = effective,
            fellBackToPinned = fallBackToPinned,
        )
    }

    override suspend fun deleteTab(id: String) = Unit

    override suspend fun renameTab(id: String, label: String) = Unit

    override suspend fun moveTab(id: String, spaceId: String, folderId: String?) = Unit

    override suspend fun pinTab(id: String, spaceId: String) = Unit

    override fun isDemo(): Boolean = false

    override fun saveKind(): SaveKind = saveKind
}
