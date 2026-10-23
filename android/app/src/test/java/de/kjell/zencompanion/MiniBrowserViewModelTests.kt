package de.kjell.zencompanion

import androidx.compose.ui.text.input.TextFieldValue
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.SearchSuggestionClient
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
        assertEquals(R.string.save_fallback_normal_off, vm.state.value.noticeRes)

        advanceTimeBy(4_000)
        runCurrent()
        assertNull(vm.state.value.noticeRes)

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

    override suspend fun renameTab(id: String, label: String) = Unit

    override suspend fun moveTab(id: String, spaceId: String, folderId: String?) = Unit

    override suspend fun pinTab(id: String, spaceId: String) = Unit

    override fun isDemo(): Boolean = false

    override fun saveKind(): SaveKind = saveKind
}
