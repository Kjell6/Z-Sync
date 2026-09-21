package de.kjell.zencompanion.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.AppEvents
import de.kjell.zencompanion.data.BrowserSettings
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.SearchEngineTemplate
import de.kjell.zencompanion.data.SearchEngineValidation
import de.kjell.zencompanion.data.SnapshotCache
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.favicon.FaviconLoader
import de.kjell.zencompanion.sync.FxAClient
import de.kjell.zencompanion.sync.FxACrypto
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.screens.FxAWebLogin
import de.kjell.zencompanion.util.FriendlyError
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserState(
    val snapshot: ZenSpaces.ZenSnapshot = ZenSpaces.ZenSnapshot.empty,
    val selectedIndex: Int = 0,
    val loading: Boolean = false,
    val loadErrorRes: Int? = null,
    val zeroSpaces: Boolean = false,
    val showShareTip: Boolean = false,
    val syncSetupHintDismissed: Boolean = false,
) {
    /**
     * Setup card while spaces synced but nothing in them: pinned/normal tabs
     * and essentials only sync when Zen's "Sync your sidebar across devices"
     * switch is on.
     */
    val showSyncSetupHint: Boolean
        get() = snapshot.spaces.isNotEmpty() &&
            !snapshot.hasSyncedTabs &&
            !syncSetupHintDismissed
}

/** Resolves a captured web login into the account snapshot to persist. */
interface LoginCompleter {
    suspend fun complete(login: FxAWebLogin): AccountStore.AccountSnapshot
}

/** FxA-backed default: fetch kB, hand back the snapshot for `completeSignIn`. */
internal class FxaLoginCompleter : LoginCompleter {
    override suspend fun complete(login: FxAWebLogin): AccountStore.AccountSnapshot = withContext(Dispatchers.IO) {
        val fxa = FxAClient(transport = AccountStore.authTransport)
        val result = fxa.completeWebLogin(
            email = login.email,
            uid = login.uid,
            sessionToken = login.sessionToken,
            keyFetchToken = login.keyFetchToken,
            unwrapBKeyHex = login.unwrapBKey,
        )
        AccountStore.AccountSnapshot(
            email = login.email,
            uid = result.uid,
            sessionTokenHex = result.session,
            kBHex = FxACrypto.hex(result.kB),
        )
    }
}

/**
 * Sync/browser data owner for the app shell: spaces refresh, tab writes,
 * the cached snapshot and the persisted "last space" id. UI code never talks
 * to [SpacesSyncService] directly.
 */
interface BrowserRepository {
    val cachedSnapshot: ZenSpaces.ZenSnapshot?
    fun lastSpaceId(): String?
    fun setLastSpaceId(id: String?)
    suspend fun refresh(): ZenSpaces.ZenSnapshot
    suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind = SaveKind.PINNED,
    ): SpacesSyncService.AddTabOutcome
    suspend fun deleteTab(id: String)
    fun isDemo(): Boolean
    /** Global pinned/normal choice for newly saved tabs. */
    fun saveKind(): SaveKind
}

internal class AndroidBrowserRepository(private val context: Context) : BrowserRepository {
    override val cachedSnapshot: ZenSpaces.ZenSnapshot?
        get() = SnapshotCache.cachedSnapshotShared

    override fun lastSpaceId(): String? = SnapshotCache.lastSpaceId(context)

    override fun setLastSpaceId(id: String?) = SnapshotCache.setLastSpaceId(context, id)

    override suspend fun refresh(): ZenSpaces.ZenSnapshot = SpacesSyncService.refresh(context)

    override suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): SpacesSyncService.AddTabOutcome =
        withContext(Dispatchers.IO) {
            SpacesSyncService.addTab(context, url, title, spaceId, folderId, kind)
        }

    override suspend fun deleteTab(id: String) = withContext(Dispatchers.IO) {
        SpacesSyncService.deleteTab(context, id)
    }

    override fun isDemo(): Boolean = AccountStore.isDemo(context)

    override fun saveKind(): SaveKind = BrowserSettings.getSaveKind(context)
}

/** User-facing preferences mirrored into the settings sheet. */
data class PreferencesState(
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    /** User-defined engines shown after the built-ins in the picker. */
    val customSearchEngines: List<SearchEngine> = emptyList(),
    val alwaysOpenExternally: Boolean = false,
    val essentialsGrouping: ZenSpaces.EssentialsGrouping = ZenSpaces.EssentialsGrouping.AUTOMATIC,
    /** Pinned (default) or normal tab for tabs saved from share/mini browser. */
    val saveKind: SaveKind = SaveKind.PINNED,
    /** Where the action bar sits: above the essentials grid (default) or below the switcher. */
    val toolbarPlacement: ToolbarPlacement = ToolbarPlacement.TOP,
)

/**
 * Preferences owner: search engine, external-browser toggle, essentials
 * grouping and the onboarding flags (sync-setup hint, share-extension tip).
 */
interface PreferencesRepository {
    fun load(): PreferencesState
    fun setSearchEngine(engine: SearchEngine)
    fun setCustomSearchEngines(engines: List<SearchEngine>)
    fun setAlwaysOpenExternally(enabled: Boolean)
    fun setEssentialsGrouping(grouping: ZenSpaces.EssentialsGrouping)
    fun setSaveKind(kind: SaveKind)
    fun setToolbarPlacement(placement: ToolbarPlacement)
    fun syncSetupHintDismissed(): Boolean
    fun setSyncSetupHintDismissed(dismissed: Boolean)
    fun didShowShareExtensionTip(): Boolean
    fun setDidShowShareExtensionTip(shown: Boolean)
    fun didRequestReview(): Boolean
    fun setDidRequestReview(requested: Boolean)
    fun didArmReviewPrompt(): Boolean
    fun setDidArmReviewPrompt(armed: Boolean)
}

internal class AndroidPreferencesRepository(private val context: Context) : PreferencesRepository {
    override fun load(): PreferencesState = PreferencesState(
        searchEngine = SearchEngines.resolve(context),
        customSearchEngines = SearchEngines.custom(context),
        alwaysOpenExternally = BrowserSettings.get(context),
        essentialsGrouping = ZenSpaces.EssentialsGrouping.fromStorage(
            BrowserSettings.getEssentialsGrouping(context),
        ),
        saveKind = BrowserSettings.getSaveKind(context),
        toolbarPlacement = BrowserSettings.getToolbarPlacement(context),
    )

    override fun setSearchEngine(engine: SearchEngine) = SearchEngines.set(context, engine)

    override fun setCustomSearchEngines(engines: List<SearchEngine>) =
        SearchEngines.setCustom(context, engines)

    override fun setAlwaysOpenExternally(enabled: Boolean) = BrowserSettings.set(context, enabled)

    override fun setEssentialsGrouping(grouping: ZenSpaces.EssentialsGrouping) =
        BrowserSettings.setEssentialsGrouping(context, grouping.storageValue)

    override fun setSaveKind(kind: SaveKind) = BrowserSettings.setSaveKind(context, kind)

    override fun setToolbarPlacement(placement: ToolbarPlacement) =
        BrowserSettings.setToolbarPlacement(context, placement)

    override fun syncSetupHintDismissed(): Boolean =
        onboardingPrefs().getBoolean(KEY_DISMISSED_SYNC_SETUP, false)

    override fun setSyncSetupHintDismissed(dismissed: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_DISMISSED_SYNC_SETUP, dismissed).apply()
    }

    override fun didShowShareExtensionTip(): Boolean =
        onboardingPrefs().getBoolean(KEY_DID_SHOW_SHARE_TIP, false)

    override fun setDidShowShareExtensionTip(shown: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_DID_SHOW_SHARE_TIP, shown).apply()
    }

    override fun didRequestReview(): Boolean =
        onboardingPrefs().getBoolean(KEY_DID_REQUEST_REVIEW, false)

    override fun setDidRequestReview(requested: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_DID_REQUEST_REVIEW, requested).apply()
    }

    override fun didArmReviewPrompt(): Boolean =
        onboardingPrefs().getBoolean(KEY_DID_ARM_REVIEW, false)

    override fun setDidArmReviewPrompt(armed: Boolean) {
        onboardingPrefs().edit().putBoolean(KEY_DID_ARM_REVIEW, armed).apply()
    }

    private fun onboardingPrefs() =
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_DISMISSED_SYNC_SETUP = "didDismissSyncSetupHint"
        const val KEY_DID_SHOW_SHARE_TIP = "didShowShareExtensionTip"
        const val KEY_DID_REQUEST_REVIEW = "didRequestReview"
        const val KEY_DID_ARM_REVIEW = "didArmReviewPrompt"
    }
}

/** Read-only synced browsing history owner. */
interface ActivityRepository {
    suspend fun load(): SyncedActivityService.Activity
}

internal class AndroidActivityRepository(private val context: Context) : ActivityRepository {
    override suspend fun load(): SyncedActivityService.Activity = SyncedActivityService.load(context)
}

/** Loading state of the synced-activity sheet. */
data class ActivityState(
    val activity: SyncedActivityService.Activity? = null,
    val loading: Boolean = false,
    @StringRes val errorRes: Int? = null,
)

/**
 * One app-scoped ViewModel for the signed-in session (unidirectional flow).
 */
class AppViewModel(
    private val appContext: Context,
    private val loginCompleter: LoginCompleter = FxaLoginCompleter(),
    internal val browserRepository: BrowserRepository = AndroidBrowserRepository(appContext),
    private val preferencesRepository: PreferencesRepository = AndroidPreferencesRepository(appContext),
    private val activityRepository: ActivityRepository = AndroidActivityRepository(appContext),
) : ViewModel() {
    // Loaded synchronously at creation so the first composed frame already
    // shows the right screen — no sign-in flash on cold start.
    private val _session = MutableStateFlow<AccountStore.AccountSnapshot?>(AccountStore.load(appContext))
    val session: StateFlow<AccountStore.AccountSnapshot?> = _session

    // Seeded synchronously from the disk cache so the first frame shows the
    // cached spaces instantly — the background refresh then updates in place.
    private val _browser = MutableStateFlow(BrowserState().withCachedSnapshot())
    val browser: StateFlow<BrowserState> = _browser

    private val _preferences = MutableStateFlow(preferencesRepository.load())
    val preferences: StateFlow<PreferencesState> = _preferences

    private val _activity = MutableStateFlow(ActivityState())
    val activity: StateFlow<ActivityState> = _activity

    private val _reviewRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reviewRequests: SharedFlow<Unit> = _reviewRequests

    private var reloadJob: Job? = null
    private var reviewJob: Job? = null
    private var reloading = false
    private var bootstrapped = false
    private var reviewArmedThisProcess = false
    private var reviewWaitElapsed = false

    private fun BrowserState.withCachedSnapshot(): BrowserState {
        if (_session.value == null) return this
        val cached = browserRepository.cachedSnapshot ?: return this
        if (cached.spaces.isEmpty()) return this
        val savedId = browserRepository.lastSpaceId()
        val restoredIndex = savedId?.let { id -> cached.spaces.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        android.util.Log.i(
            "SpacesSync",
            "restore: savedId=$savedId restoredIndex=$restoredIndex cachedSpaces=${cached.spaces.size}",
        )
        return copy(
            snapshot = cached,
            selectedIndex = (restoredIndex ?: selectedIndex)
                .coerceIn(0, (cached.spaces.size - 1).coerceAtLeast(0)),
        )
    }

    fun bootstrap() {
        // Idempotent: repeated calls from recomposition must not add duplicate
        // event collectors or kick a second initial load.
        if (bootstrapped) return
        bootstrapped = true

        FaviconLoader.initialize(appContext)
        _browser.value = _browser.value.copy(
            syncSetupHintDismissed = preferencesRepository.syncSetupHintDismissed(),
        )
        browserRepository.cachedSnapshot?.let { cached ->
            if (cached.spaces.isNotEmpty()) FaviconLoader.prefetch(cached)
        }
        if (_session.value != null) {
            considerReviewPrompt()
            initialLoad()
        }
        viewModelScope.launch {
            AppEvents.signedOut.collect { _session.value = null }
        }
        viewModelScope.launch {
            AppEvents.snapshotStale.collect { reload() }
        }
    }

    /**
     * Persists the session before entering the signed-in state. A storage
     * failure throws [SyncError.StorageUnavailable] so a real sign-in never
     * leaves a phantom in-memory session.
     */
    fun completeSignIn(snapshot: AccountStore.AccountSnapshot) {
        AccountStore.save(appContext, snapshot)
        SnapshotCache.invalidateMemory()
        _session.value = snapshot
        initialLoad()
    }

    /**
     * Finishes a captured web login through [LoginCompleter] and persists the
     * resulting session. Failures rethrow so the sign-in sheet can bubble the
     * error and stay open; a storage failure carries the friendly message.
     */
    suspend fun completeWebLogin(login: FxAWebLogin) {
        val snapshot = loginCompleter.complete(login)
        try {
            completeSignIn(snapshot)
        } catch (e: SyncError.StorageUnavailable) {
            throw IllegalStateException(appContext.getString(FriendlyError.messageRes(e)), e)
        }
    }

    fun enterDemo() {
        SnapshotCache.deleteCachedSnapshot(appContext)
        SnapshotCache.cache(DemoCatalog.snapshot)
        // Mirror iOS's explicit demo exception: the demo session stays usable
        // in memory when the secret cannot be persisted securely.
        runCatching { AccountStore.save(appContext, DemoCatalog.account) }
        _session.value = DemoCatalog.account
        _browser.value = BrowserState(
            snapshot = DemoCatalog.snapshot,
            selectedIndex = 0,
        )
        FaviconLoader.prefetch(DemoCatalog.snapshot)
        initialLoad()
    }

    fun signOut() {
        AccountStore.clear(appContext)
        SnapshotCache.deleteCachedSnapshot(appContext)
        SnapshotCache.invalidateMemory()
        preferencesRepository.setSyncSetupHintDismissed(false)
        _browser.value = BrowserState()
        AppEvents.emitSignedOut()
    }

    fun dismissSyncSetupHint() {
        preferencesRepository.setSyncSetupHintDismissed(true)
        _browser.value = _browser.value.copy(syncSetupHintDismissed = true)
    }

    fun selectSpace(index: Int) {
        val space = _browser.value.snapshot.spaces.getOrNull(index) ?: return
        _browser.value = _browser.value.copy(selectedIndex = index)
        browserRepository.setLastSpaceId(space.id)
    }

    fun initialLoad() {
        // Cached snapshot was already applied synchronously at construction;
        // just kick off the background refresh.
        reload()
    }

    /**
     * Manual refresh (pull-to-refresh) — shows the sync spinner. Always hits
     * the network: if a background reload is in flight, wait for it and fetch
     * again instead of silently doing nothing.
     */
    fun refresh() {
        viewModelScope.launch {
            var waited = 0L
            while (reloading && waited < 15_000L) {
                delay(100)
                waited += 100
            }
            reloadInternal(retryAttempted = false, manual = true)
        }
    }

    /** Automatic refresh (app start, stale snapshot) — silent when content is visible. */
    fun reload() {
        if (reloading) return
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch { reloadInternal(retryAttempted = false, manual = false) }
    }

    /**
     * Port of `reload(retryAttempted:)` including the 700ms cancellation retry.
     * The spinner only shows for manual refreshes, or when there is nothing to
     * show yet (no cached spaces) — a background sync with visible content
     * stays invisible instead of spinning in the pull-to-refresh indicator.
     */
    private suspend fun reloadInternal(retryAttempted: Boolean, manual: Boolean) {
        if (reloading) return
        reloading = true
        val showSpinner = manual || _browser.value.snapshot.spaces.isEmpty()
        try {
            _browser.value = _browser.value.copy(loading = showSpinner)
            val fresh = browserRepository.refresh()
            if (fresh.spaces.isEmpty()) {
                // Sync worked but the account has no spaces: Zen Sync is
                // likely not enabled on the desktop yet. Dedicated help state.
                _browser.value = _browser.value.copy(
                    loading = false,
                    loadErrorRes = null,
                    zeroSpaces = true,
                    snapshot = fresh,
                )
                return
            }
            // Keep the space the user is on. Fall back to the persisted id
            // (e.g. no cached snapshot yet) before clamping numerically.
            val currentId = _browser.value.snapshot.spaces
                .getOrNull(_browser.value.selectedIndex)?.id
                ?: browserRepository.lastSpaceId()
            val newIndex = currentId
                ?.let { id -> fresh.spaces.indexOfFirstOrNull { it.id == id } }
                ?: _browser.value.selectedIndex.coerceIn(0, fresh.spaces.size - 1)
            fresh.spaces.getOrNull(newIndex)?.id?.let {
                browserRepository.setLastSpaceId(it)
            }
            FaviconLoader.prefetch(fresh)
            _browser.value = _browser.value.copy(
                snapshot = fresh,
                selectedIndex = newIndex,
                loading = false,
                loadErrorRes = null,
                zeroSpaces = false,
            )
            considerReviewPrompt()
            maybeShowShareTip()
        } catch (e: Exception) {
            when {
                e is CancellationException && !retryAttempted -> {
                    _browser.value = _browser.value.copy(
                        loading = false,
                        loadErrorRes = de.kjell.zencompanion.R.string.error_interrupted,
                    )
                    delay(700)
                    reloadInternal(retryAttempted = true, manual = manual)
                    return
                }
                else -> {
                    _browser.value = _browser.value.copy(
                        loading = false,
                        loadErrorRes = FriendlyError.messageRes(e),
                    )
                }
            }
        } finally {
            reloading = false
        }
    }

    fun dismissShareTip() {
        _browser.value = _browser.value.copy(showShareTip = false)
        if (reviewWaitElapsed) requestReviewIfEligible()
    }

    /// One-time share-extension tip, further delayed so the user first takes
    /// in their spaces. Only once (SharedPreferences flag), only with spaces.
    private suspend fun maybeShowShareTip() {
        if (browserRepository.isDemo()) return
        if (preferencesRepository.didShowShareExtensionTip()) return
        if (_browser.value.snapshot.spaces.isEmpty()) return
        preferencesRepository.setDidShowShareExtensionTip(true)
        delay(4000)
        _browser.value = _browser.value.copy(showShareTip = true)
    }

    /**
     * Native Play In-App Review dialog. Google decides whether it actually
     * appears (quota-limited; debug/sideload installs usually skip it).
     * First look waits 12s after synced tabs; if the app is closed sooner,
     * the next launch asks after a short settle.
     */
    internal fun considerReviewPrompt() {
        if (browserRepository.isDemo()) return
        if (preferencesRepository.didRequestReview()) return
        if (!_browser.value.snapshot.hasSyncedTabs) return
        val alreadyArmed = preferencesRepository.didArmReviewPrompt()
        if (!alreadyArmed) {
            preferencesRepository.setDidArmReviewPrompt(true)
            reviewArmedThisProcess = true
            scheduleReviewPrompt(REVIEW_FIRST_LOOK_DELAY_MS)
            return
        }
        if (reviewArmedThisProcess) return
        reviewArmedThisProcess = true
        scheduleReviewPrompt(REVIEW_NEXT_OPEN_DELAY_MS)
    }

    private fun scheduleReviewPrompt(delayMs: Long) {
        if (preferencesRepository.didRequestReview()) return
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            delay(delayMs)
            reviewWaitElapsed = true
            requestReviewIfEligible()
        }
    }

    internal fun requestReviewIfEligible() {
        if (browserRepository.isDemo()) return
        if (preferencesRepository.didRequestReview()) return
        val state = _browser.value
        if (!state.snapshot.hasSyncedTabs) return
        if (state.showShareTip) return
        if (state.showSyncSetupHint) return
        if (state.loading) return
        preferencesRepository.setDidRequestReview(true)
        _reviewRequests.tryEmit(Unit)
    }

    fun deleteTab(id: String) {
        viewModelScope.launch {
            runCatching { browserRepository.deleteTab(id) }
        }
    }

    fun setSearchEngine(engine: SearchEngine) {
        preferencesRepository.setSearchEngine(engine)
        _preferences.value = _preferences.value.copy(searchEngine = engine)
    }

    /**
     * Validates and appends a custom engine, selecting it on success.
     * Returns null on success, otherwise why the draft was rejected.
     */
    fun addSearchEngine(name: String, template: String): SearchEngineValidation? {
        val error = SearchEngineTemplate.validate(name, template)
        if (error != null) return error

        val engine = SearchEngine(
            id = UUID.randomUUID().toString(),
            displayName = name.trim(),
            template = template.trim(),
            isBuiltIn = false,
        )
        val custom = _preferences.value.customSearchEngines + engine
        preferencesRepository.setCustomSearchEngines(custom)
        preferencesRepository.setSearchEngine(engine)
        _preferences.value = _preferences.value.copy(customSearchEngines = custom, searchEngine = engine)
        return null
    }

    /** Removes a custom engine; a selected one falls back to DuckDuckGo. */
    fun deleteSearchEngine(id: String) {
        val custom = _preferences.value.customSearchEngines.filterNot { it.id == id }
        preferencesRepository.setCustomSearchEngines(custom)

        var engine = _preferences.value.searchEngine
        if (engine.id == id) {
            engine = SearchEngine.DUCKDUCKGO
            preferencesRepository.setSearchEngine(engine)
        }
        _preferences.value = _preferences.value.copy(customSearchEngines = custom, searchEngine = engine)
    }

    fun setAlwaysOpenExternally(enabled: Boolean) {
        preferencesRepository.setAlwaysOpenExternally(enabled)
        _preferences.value = _preferences.value.copy(alwaysOpenExternally = enabled)
    }

    fun setEssentialsGrouping(grouping: ZenSpaces.EssentialsGrouping) {
        preferencesRepository.setEssentialsGrouping(grouping)
        _preferences.value = _preferences.value.copy(essentialsGrouping = grouping)
    }

    fun setSaveKind(kind: SaveKind) {
        preferencesRepository.setSaveKind(kind)
        _preferences.value = _preferences.value.copy(saveKind = kind)
    }

    fun setToolbarPlacement(placement: ToolbarPlacement) {
        preferencesRepository.setToolbarPlacement(placement)
        _preferences.value = _preferences.value.copy(toolbarPlacement = placement)
    }

    /** Loads (or retries) the synced browsing history into [activity]. */
    fun loadActivity() {
        viewModelScope.launch {
            val current = _activity.value
            _activity.value = current.copy(loading = current.activity == null, errorRes = null)
            try {
                val loaded = activityRepository.load()
                _activity.value = ActivityState(activity = loaded, loading = false, errorRes = null)
            } catch (e: Exception) {
                _activity.value = _activity.value.copy(
                    loading = false,
                    errorRes = FriendlyError.messageRes(e),
                )
            }
        }
    }

    private fun <T> List<T>.indexOfFirstOrNull(predicate: (T) -> Boolean): Int? =
        indexOfFirst(predicate).takeIf { it >= 0 }

    private companion object {
        const val REVIEW_FIRST_LOOK_DELAY_MS = 12_000L
        const val REVIEW_NEXT_OPEN_DELAY_MS = 1_500L
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(context.applicationContext) as T
    }
}
