package de.kjell.zencompanion

import android.content.ContextWrapper
import android.content.SharedPreferences
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SnapshotCache
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.ActivityRepository
import de.kjell.zencompanion.ui.AndroidPreferencesRepository
import de.kjell.zencompanion.ui.AppViewModel
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.LoginCompleter
import de.kjell.zencompanion.ui.PreferencesRepository
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.screens.FxAWebLogin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests: AppViewModel is constructed directly with fake repositories
 * (the production assembly always goes through `AppViewModel.Factory`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTests {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val context = FakeAppContext()
    private lateinit var secure: FakeSecurePrefsStore

    @Before
    fun setUp() {
        AccountStore.restoreDefaultFactoriesForTests()
        AccountStore.resetForTests()
        secure = FakeSecurePrefsStore()
        AccountStore.secureStoreFactory = { secure }
        AccountStore.legacyStoreFactory = { EmptyLegacyPlainStore() }
    }

    @After
    fun tearDown() {
        AccountStore.resetForTests()
        AccountStore.restoreDefaultFactoriesForTests()
        SnapshotCache.invalidateMemory()
    }

    private fun snapshot(uid: String = "uid-1") = AccountStore.AccountSnapshot(
        email = "user@example.com",
        uid = uid,
        sessionTokenHex = "aa",
        kBHex = "bb",
    )

    private fun newViewModel(
        login: LoginCompleter = FakeLoginCompleter(snapshot()),
        browser: FakeBrowserRepository = FakeBrowserRepository(),
        preferences: PreferencesRepository = FakePreferencesRepository(),
        activity: ActivityRepository = FakeActivityRepository(),
    ) = AppViewModel(context, login, browser, preferences, activity)

    @Test
    fun completeWebLoginSuccessSetsSession() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(login = FakeLoginCompleter(snapshot("uid-42")))
        val login = FxAWebLogin("user@example.com", "uid-42", "session", "keyfetch", "unwrap")

        vm.completeWebLogin(login)

        assertEquals("uid-42", vm.session.value?.uid)
    }

    @Test
    fun completeWebLoginFailureRethrowsAndLeavesSessionNull() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = newViewModel(login = FakeLoginCompleter(null, IllegalStateException("boom")))
        val login = FxAWebLogin("user@example.com", "uid-42", "session", "keyfetch", "unwrap")

        var thrown: Exception? = null
        try {
            vm.completeWebLogin(login)
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals("boom", thrown?.message)
        assertNull(vm.session.value)
    }

    @Test
    fun completeSignInStorageFailureThrowsAndKeepsSessionNull() = runTest(mainDispatcherRule.testDispatcher) {
        AccountStore.secureStoreFactory = { null }
        val vm = newViewModel()

        var thrown: Exception? = null
        try {
            vm.completeSignIn(snapshot())
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown is SyncError.StorageUnavailable)
        assertNull(vm.session.value)
    }

    @Test
    fun completeWebLoginStorageFailureShowsFriendlyErrorAndKeepsSessionNull() = runTest(mainDispatcherRule.testDispatcher) {
        AccountStore.secureStoreFactory = { null }
        val vm = newViewModel(login = FakeLoginCompleter(snapshot("uid-42")))
        val login = FxAWebLogin("user@example.com", "uid-42", "session", "keyfetch", "unwrap")

        var thrown: Exception? = null
        try {
            vm.completeWebLogin(login)
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown?.cause is SyncError.StorageUnavailable)
        assertNull(vm.session.value)
    }

    @Test
    fun enterDemoStillEntersMemoryOnlySessionWhenSaveFails() = runTest(mainDispatcherRule.testDispatcher) {
        AccountStore.secureStoreFactory = { null }
        val vm = newViewModel()

        vm.enterDemo()

        assertEquals(DemoCatalog.account.uid, vm.session.value?.uid)
        assertTrue(secure.values.isEmpty())
        assertFalse(AccountStore.isSignedIn(context))
    }

    @Test
    fun refreshWaitsForInFlightReloadThenFetchesAgain() = runTest(mainDispatcherRule.testDispatcher) {
        val browser = FakeBrowserRepository()
        browser.refreshGate = CompletableDeferred()
        val vm = newViewModel(browser = browser)

        vm.reload()
        runCurrent()
        assertEquals(1, browser.refreshCalls)

        vm.refresh()
        advanceTimeBy(1_000)
        runCurrent()
        // The manual refresh must wait for the blocked background reload.
        assertEquals(1, browser.refreshCalls)

        browser.refreshGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, browser.refreshCalls)
    }

    @Test
    fun saveKindPersistsThroughPreferencesRepository() {
        val repository = AndroidPreferencesRepository(context)

        assertEquals(SaveKind.PINNED, repository.load().saveKind)

        repository.setSaveKind(SaveKind.NORMAL)
        assertEquals(SaveKind.NORMAL, repository.load().saveKind)

        repository.setSaveKind(SaveKind.PINNED)
        assertEquals(SaveKind.PINNED, repository.load().saveKind)
    }

    @Test
    fun toolbarPlacementPersistsThroughPreferencesRepository() {
        val repository = AndroidPreferencesRepository(context)

        assertEquals(ToolbarPlacement.TOP, repository.load().toolbarPlacement)

        repository.setToolbarPlacement(ToolbarPlacement.BOTTOM)
        assertEquals(ToolbarPlacement.BOTTOM, repository.load().toolbarPlacement)

        repository.setToolbarPlacement(ToolbarPlacement.TOP)
        assertEquals(ToolbarPlacement.TOP, repository.load().toolbarPlacement)
    }

    @Test
    fun toolbarPlacementFromStorageIgnoresUnknownValue() {
        assertEquals(ToolbarPlacement.TOP, ToolbarPlacement.fromStorage(null))
        assertEquals(ToolbarPlacement.TOP, ToolbarPlacement.fromStorage("sideways"))
        assertEquals(ToolbarPlacement.BOTTOM, ToolbarPlacement.fromStorage("bottom"))
    }

    @Test
    fun selectSpacePersistsOnlyValidIndex() = runTest(mainDispatcherRule.testDispatcher) {
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "One",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = emptyList(),
        )
        val browser = FakeBrowserRepository(snapshot = ZenSpaces.ZenSnapshot(listOf(space)))
        val vm = newViewModel(browser = browser)

        vm.completeSignIn(snapshot())
        advanceUntilIdle()
        assertEquals(1, vm.browser.value.snapshot.spaces.size)

        vm.selectSpace(0)
        assertEquals(0, vm.browser.value.selectedIndex)
        assertEquals("s1", browser.lastSpace)

        browser.lastSpace = null
        vm.selectSpace(3)
        assertEquals(0, vm.browser.value.selectedIndex)
        assertNull(browser.lastSpace)
    }

    private fun snapshotWithPinnedTab(): ZenSpaces.ZenSnapshot {
        val tab = ZenSpaces.ZenTab(id = "tab-1", url = "https://example.com", title = "Example")
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "One",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = listOf(ZenSpaces.ZenItem.Tab(tab)),
        )
        return ZenSpaces.ZenSnapshot(listOf(space))
    }

    @Test
    fun reviewPromptFiresOnceWhenSyncedTabsAreVisible() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakePreferencesRepository()
        val vm = newViewModel(
            browser = FakeBrowserRepository(snapshot = snapshotWithPinnedTab()),
            preferences = prefs,
        )
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.completeSignIn(snapshot())
        runCurrent()
        vm.requestReviewIfEligible()
        runCurrent()
        assertEquals(1, received.size)
        assertTrue(prefs.reviewRequested)

        vm.requestReviewIfEligible()
        runCurrent()
        assertEquals(1, received.size)
    }

    @Test
    fun reviewPromptSkippedWithoutSyncedTabs() = runTest(mainDispatcherRule.testDispatcher) {
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "One",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = emptyList(),
        )
        val vm = newViewModel(browser = FakeBrowserRepository(snapshot = ZenSpaces.ZenSnapshot(listOf(space))))
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.completeSignIn(snapshot())
        advanceUntilIdle()
        vm.requestReviewIfEligible()
        runCurrent()
        assertTrue(received.isEmpty())
        assertFalse(vm.browser.value.snapshot.hasSyncedTabs)
    }

    @Test
    fun reviewPromptSkippedInDemo() = runTest(mainDispatcherRule.testDispatcher) {
        val browser = FakeBrowserRepository(snapshot = snapshotWithPinnedTab())
        browser.demo = true
        val vm = newViewModel(browser = browser)
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.requestReviewIfEligible()
        runCurrent()
        assertTrue(received.isEmpty())
    }

    @Test
    fun reviewPromptWaitsTwelveSecondsOnFirstLook() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakePreferencesRepository().apply { shareTipShown = true }
        val vm = newViewModel(
            browser = FakeBrowserRepository(snapshot = snapshotWithPinnedTab()),
            preferences = prefs,
        )
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.completeSignIn(snapshot())
        runCurrent()
        assertTrue(prefs.reviewArmed)
        assertTrue(received.isEmpty())

        advanceTimeBy(11_999)
        runCurrent()
        assertTrue(received.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, received.size)
        assertTrue(prefs.reviewRequested)
    }

    @Test
    fun reviewPromptOnNextOpenAfterBouncingEarly() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakePreferencesRepository().apply {
            shareTipShown = true
            reviewArmed = true
        }
        val vm = newViewModel(
            browser = FakeBrowserRepository(snapshot = snapshotWithPinnedTab()),
            preferences = prefs,
        )
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.completeSignIn(snapshot())
        runCurrent()
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(received.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, received.size)
    }

    @Test
    fun reviewPromptWaitsUntilShareTipIsDismissed() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakePreferencesRepository()
        val vm = newViewModel(
            browser = FakeBrowserRepository(snapshot = snapshotWithPinnedTab()),
            preferences = prefs,
        )
        val received = mutableListOf<Unit>()
        backgroundScope.launch { vm.reviewRequests.collect { received += it } }

        vm.completeSignIn(snapshot())
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(vm.browser.value.showShareTip)
        assertTrue(received.isEmpty())

        vm.dismissShareTip()
        runCurrent()
        assertTrue(received.isEmpty())

        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(1, received.size)
        assertTrue(prefs.reviewRequested)
    }
}

private class FakeLoginCompleter(
    private val result: AccountStore.AccountSnapshot?,
    private val failure: Exception? = null,
) : LoginCompleter {
    override suspend fun complete(login: FxAWebLogin): AccountStore.AccountSnapshot {
        failure?.let { throw it }
        return result!!
    }
}

private class FakeBrowserRepository(
    var snapshot: ZenSpaces.ZenSnapshot? = null,
) : BrowserRepository {
    data class AddedTab(
        val url: String,
        val title: String,
        val spaceId: String,
        val folderId: String?,
        val kind: SaveKind = SaveKind.PINNED,
    )

    val added = mutableListOf<AddedTab>()
    val deleted = mutableListOf<String>()
    var lastSpace: String? = null
    var refreshGate: CompletableDeferred<Unit>? = null
    var refreshCalls = 0
    var demo = false

    override val cachedSnapshot: ZenSpaces.ZenSnapshot?
        get() = snapshot

    override fun lastSpaceId(): String? = lastSpace

    override fun setLastSpaceId(id: String?) {
        lastSpace = id
    }

    override suspend fun refresh(): ZenSpaces.ZenSnapshot {
        refreshCalls++
        refreshGate?.await()
        return snapshot ?: ZenSpaces.ZenSnapshot(emptyList())
    }

    override suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): SpacesSyncService.AddTabOutcome {
        added += AddedTab(url, title, spaceId, folderId, kind)
        return SpacesSyncService.AddTabOutcome("tab-${added.size}", kind)
    }

    override suspend fun deleteTab(id: String) {
        deleted += id
    }

    override fun isDemo(): Boolean = demo

    override fun saveKind(): SaveKind = SaveKind.PINNED
}

private class FakePreferencesRepository : PreferencesRepository {
    var state = PreferencesState()
    var syncSetupDismissedFlag = false
    var shareTipShown = false
    var reviewRequested = false
    var reviewArmed = false

    override fun load(): PreferencesState = state

    override fun setSearchEngine(engine: SearchEngine) {
        state = state.copy(searchEngine = engine)
    }

    override fun setCustomSearchEngines(engines: List<SearchEngine>) {
        state = state.copy(customSearchEngines = engines)
    }

    override fun setAlwaysOpenExternally(enabled: Boolean) {
        state = state.copy(alwaysOpenExternally = enabled)
    }

    override fun setEssentialsGrouping(grouping: ZenSpaces.EssentialsGrouping) {
        state = state.copy(essentialsGrouping = grouping)
    }

    override fun setSaveKind(kind: SaveKind) {
        state = state.copy(saveKind = kind)
    }

    override fun setToolbarPlacement(placement: ToolbarPlacement) {
        state = state.copy(toolbarPlacement = placement)
    }

    override fun syncSetupHintDismissed(): Boolean = syncSetupDismissedFlag

    override fun setSyncSetupHintDismissed(dismissed: Boolean) {
        syncSetupDismissedFlag = dismissed
    }

    override fun didShowShareExtensionTip(): Boolean = shareTipShown

    override fun setDidShowShareExtensionTip(shown: Boolean) {
        shareTipShown = shown
    }

    override fun didRequestReview(): Boolean = reviewRequested

    override fun setDidRequestReview(requested: Boolean) {
        reviewRequested = requested
    }

    override fun didArmReviewPrompt(): Boolean = reviewArmed

    override fun setDidArmReviewPrompt(armed: Boolean) {
        reviewArmed = armed
    }
}

private class FakeActivityRepository : ActivityRepository {
    override suspend fun load(): SyncedActivityService.Activity =
        SyncedActivityService.Activity(emptyList())
}

private class FakeAppContext : ContextWrapper(null) {
    private val prefs = mutableMapOf<String, String>()
    private val files = File(System.getProperty("java.io.tmpdir"), "zen-appvm-${System.nanoTime()}")

    override fun getFilesDir(): File = files

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        FakePrefsSharedPreferences(prefs)
}

private class FakePrefsSharedPreferences(
    private val values: MutableMap<String, String>,
) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakePrefsEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakePrefsEditor(
    private val values: MutableMap<String, String>,
) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
        if (key != null && value != null) values[key] = value
    }

    override fun putStringSet(key: String?, defValues: MutableSet<String>?): SharedPreferences.Editor = this

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

    override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }

    override fun clear(): SharedPreferences.Editor = apply { values.clear() }

    override fun commit(): Boolean = true

    override fun apply() = Unit
}

private class FakeSecurePrefsStore : AccountStore.AccountPrefsStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String): Boolean {
        values[key] = value
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

private class EmptyLegacyPlainStore : AccountStore.LegacyPlainStore {
    override fun read(key: String): String? = null

    override fun delete() = Unit
}
