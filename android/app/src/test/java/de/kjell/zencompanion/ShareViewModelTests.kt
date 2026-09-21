package de.kjell.zencompanion

import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.share.SharePhase
import de.kjell.zencompanion.share.ShareRepository
import de.kjell.zencompanion.share.ShareViewModel
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.PinDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTests {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun cachedSnapshotStartsInPick() = runTest(mainDispatcherRule.testDispatcher) {
        val space = testSpace("s1")
        val vm = ShareViewModel(
            repository = FakeShareRepository(
                signedIn = true,
                cached = ZenSpaces.ZenSnapshot(listOf(space)),
                fresh = ZenSpaces.ZenSnapshot(listOf(space)),
            ),
            initialUrl = "https://example.com",
            initialPageTitle = "Example",
        )

        assertEquals(SharePhase.pick, vm.state.value.phase)
        assertEquals(listOf(space), vm.state.value.spaces)
        assertEquals(PinDestination(spaceId = "s1"), vm.state.value.destination)
    }

    @Test
    fun emptyFreshSnapshotIsIgnored() = runTest(mainDispatcherRule.testDispatcher) {
        val cachedSpace = testSpace("s1")
        val vm = ShareViewModel(
            repository = FakeShareRepository(
                signedIn = true,
                cached = ZenSpaces.ZenSnapshot(listOf(cachedSpace)),
                fresh = ZenSpaces.ZenSnapshot(emptyList()),
            ),
            initialUrl = "https://example.com",
            initialPageTitle = "Example",
        )

        assertEquals(listOf(cachedSpace), vm.state.value.spaces)
        assertEquals(SharePhase.pick, vm.state.value.phase)
    }

    @Test
    fun vanishedFolderFallsBackToSpaceRoot() = runTest(mainDispatcherRule.testDispatcher) {
        val space = testSpace("s1")
        val vm = ShareViewModel(
            repository = FakeShareRepository(
                signedIn = true,
                cached = ZenSpaces.ZenSnapshot(listOf(space)),
                fresh = ZenSpaces.ZenSnapshot(listOf(space)),
            ),
            initialUrl = "https://example.com",
            initialPageTitle = "Example",
        )

        vm.selectDestination(PinDestination(spaceId = "s1", folderId = "gone"))
        vm.refreshSpaces()

        assertEquals(PinDestination(spaceId = "s1"), vm.state.value.destination)
    }

    @Test
    fun saveAddsTabAndEmitsFinishedAfterAutoCloseDelay() = runTest(mainDispatcherRule.testDispatcher) {
        val space = testSpace("s1")
        val repository = FakeShareRepository(
            signedIn = true,
            cached = ZenSpaces.ZenSnapshot(listOf(space)),
            fresh = ZenSpaces.ZenSnapshot(listOf(space)),
        )
        val vm = ShareViewModel(
            repository = repository,
            initialUrl = "https://example.com/page",
            initialPageTitle = "Page",
        )
        val events = mutableListOf<ShareViewModel.Event>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.save()
        assertEquals(SharePhase.saved, vm.state.value.phase)
        assertEquals(1, repository.added.size)
        assertEquals("https://example.com/page", repository.added[0].url)
        assertEquals("Page", repository.added[0].title)
        assertEquals("s1", repository.added[0].spaceId)
        assertEquals("s1", repository.lastSpace)
        assertTrue(events.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(ShareViewModel.Event.Finished), events)
    }

    @Test
    fun normalSaveIgnoresFolderAndShowsFallbackNotice() = runTest(mainDispatcherRule.testDispatcher) {
        val space = testSpace("s1")
        val repository = FakeShareRepository(
            signedIn = true,
            cached = ZenSpaces.ZenSnapshot(listOf(space)),
            fresh = ZenSpaces.ZenSnapshot(listOf(space)),
            saveKind = SaveKind.NORMAL,
            fallBackToPinned = true,
        )
        val vm = ShareViewModel(
            repository = repository,
            initialUrl = "https://example.com/page",
            initialPageTitle = "Page",
        )
        val events = mutableListOf<ShareViewModel.Event>()
        backgroundScope.launch { vm.events.collect { events += it } }

        assertTrue(vm.state.value.hideFolders)
        vm.selectDestination(PinDestination(spaceId = "s1", folderId = "folder-1"))
        assertEquals(PinDestination(spaceId = "s1"), vm.state.value.destination)

        vm.save()
        assertEquals(SharePhase.saved, vm.state.value.phase)
        assertTrue(vm.state.value.savedAsPinnedFallback)
        assertEquals(1, repository.added.size)
        assertEquals(SaveKind.NORMAL, repository.added[0].kind)
        assertNull(repository.added[0].folderId)

        // The fallback notice stays up for 2.4s before the sheet closes.
        advanceTimeBy(2_399)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(ShareViewModel.Event.Finished), events)
    }

    @Test
    fun saveWithNoSpacesFailsWithoutRepositoryWrite() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeShareRepository(
            signedIn = true,
            cached = null,
            fresh = ZenSpaces.ZenSnapshot(emptyList()),
        )
        val vm = ShareViewModel(
            repository = repository,
            initialUrl = "https://example.com",
            initialPageTitle = "Example",
        )

        vm.save()
        advanceUntilIdle()

        assertEquals(SharePhase.failed, vm.state.value.phase)
        assertEquals("no spaces", vm.state.value.errorText)
        assertTrue(repository.added.isEmpty())
        assertNull(repository.lastSpace)
    }
}

private fun testSpace(id: String): ZenSpaces.ZenSpace = ZenSpaces.ZenSpace(
    id = id,
    name = id,
    icon = null,
    containerGuid = null,
    theme = null,
    pinned = emptyList(),
)

private class FakeShareRepository(
    private val signedIn: Boolean,
    private val cached: ZenSpaces.ZenSnapshot?,
    private val fresh: ZenSpaces.ZenSnapshot,
    private val saveKind: SaveKind = SaveKind.PINNED,
    private val fallBackToPinned: Boolean = false,
) : ShareRepository {
    data class AddedTab(
        val url: String,
        val title: String,
        val spaceId: String,
        val folderId: String?,
        val kind: SaveKind,
    )

    val added = mutableListOf<AddedTab>()
    var lastSpace: String? = null

    override fun isSignedIn(): Boolean = signedIn

    override val cachedSnapshot: ZenSpaces.ZenSnapshot?
        get() = cached

    override fun lastSpaceId(): String? = lastSpace

    override fun setLastSpaceId(id: String?) {
        lastSpace = id
    }

    override suspend fun refresh(): ZenSpaces.ZenSnapshot = fresh

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

    override fun errorText(error: Exception): String = error.message ?: "error"

    override fun noSpacesErrorText(): String = "no spaces"

    override fun saveKind(): SaveKind = saveKind
}
