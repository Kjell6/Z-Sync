package de.kjell.zencompanion.ui.browser

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.BrowserVisit
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.SearchSuggestions
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.screens.BrowserPageError
import de.kjell.zencompanion.ui.screens.formatBrowserInput
import de.kjell.zencompanion.util.FriendlyError
import de.kjell.zencompanion.util.PageTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.roundToInt

/// Page zoom steps for the mini browser, mirrored from `MiniBrowserTools` on
/// iOS: a fraction of 1.0, clamped to 0.5...2.0 in steps of 0.1.
object MiniBrowserZoom {
    const val min = 0.5f
    const val max = 2.0f
    const val step = 0.1f
    const val default = 1.0f

    fun clamped(value: Float): Float {
        val stepped = (value / step).roundToInt() * step
        return (min.coerceAtLeast(max.coerceAtMost(stepped)) * 100f).roundToInt() / 100f
    }

    fun percentLabel(value: Float): String = "${(clamped(value) * 100).roundToInt()}%"

    fun canZoomIn(value: Float): Boolean = clamped(value) < max - 0.001f
    fun canZoomOut(value: Float): Boolean = clamped(value) > min + 0.001f
    fun canReset(value: Float): Boolean = kotlin.math.abs(clamped(value) - default) > 0.001f
}

/**
 * One row of the address list: a page from the history, or a search phrase.
 * [id] is the row's own content, so a row that stays in the list keeps its
 * identity when the rows around it change.
 */
data class AddressSuggestion(
    val text: String,
    /** The page the row opens, or null for a search phrase. */
    val visit: BrowserVisit? = null,
) {
    val id: String get() = visit?.let { "history:${it.url}" } ?: "search:$text"

    companion object {
        /** A page prints its name, or its host when it has none. */
        fun page(visit: BrowserVisit) = AddressSuggestion(visit.title.ifEmpty { visit.host }, visit)
    }
}

/**
 * Chrome and deferred-pin state for the mini browser (port of the state
 * machine that used to live inline in `MiniBrowserScreen`).
 */
class MiniBrowserViewModel(
    private val repository: BrowserRepository,
    initialUrl: String?,
    initialTitle: String?,
    spaces: List<ZenSpaces.ZenSpace>,
    currentSpaceId: String,
    private val suggestionClient: SearchSuggestionClient = LiveSearchSuggestionClient(),
    private val suggestionDebounceMs: Long = SearchSuggestions.DEBOUNCE_MS,
    /** Where the reader parse runs; tests hand in their own dispatcher. */
    private val readerParseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Whether an account can take a save; asked each time, so a sign-out lands. */
    private val isSignedIn: () -> Boolean = { true },
) : ViewModel() {

    private var initialUrl: String? = initialUrl
    private var spaces: List<ZenSpaces.ZenSpace> = spaces
    private var currentSpaceId: String = currentSpaceId

    data class State(
        val currentUrl: String = "",
        val currentTitle: String = "",
        val isLoading: Boolean = false,
        val progress: Float = 0f,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val siteThemeColor: Color? = null,
        val isAddressFocused: Boolean = false,
        val addressText: TextFieldValue = TextFieldValue(""),
        val showPinBanner: Boolean = false,
        val pinnedDestination: PinDestination = PinDestination(spaceId = ""),
        val isSpaceMenuOpen: Boolean = false,
        val isFolderMenuOpen: Boolean = false,
        /** Pinned (default) or normal tab for this save. */
        val saveKind: SaveKind = SaveKind.PINNED,
        /** Brief banner after a save: a pinned fallback or a failed write. */
        val notice: Notice? = null,
        /** Set while a panel covers the page because it couldn't load. */
        val pageError: BrowserPageError? = null,
        /** The address that failed; the address bar and the panel act on it. */
        val failedUrl: String = "",
        /** Expanded page menu on the bottom bar. */
        val menuExpanded: Boolean = false,
        /** Reading view of the current page, or null for the page itself. */
        val readerArticle: ReaderArticle? = null,
        val readerLoading: Boolean = false,
        /** Session-only desktop request; applied to the WebView. */
        val requestsDesktopSite: Boolean = false,
        /** 1.0 = 100%. Always inside [MiniBrowserZoom] limits. */
        val pageZoom: Float = MiniBrowserZoom.default,
        /** Transient "Copied" confirmation on the copy row. */
        val copyConfirmed: Boolean = false,
        /** In-page find bar. */
        val findVisible: Boolean = false,
        val findQuery: String = "",
        val findProgress: String? = null,
        /** Suggestions for the text being typed. Empty hides the list. */
        /** Pages from the history for the same text, above the suggestions. */
        val historySuggestions: List<BrowserVisit> = emptyList(),
        val suggestions: List<String> = emptyList(),
        /** What the panel draws: the history first, then the phrases. */
        val suggestionRows: List<AddressSuggestion> = emptyList(),
        /** Ads and trackers blocked on the current page. */
        val blockedCount: Int = 0,
        /** The page was reloaded once with nothing blocked. */
        val showingOriginal: Boolean = false,
        /** The site's own blocking switches, as a page inside the open menu. */
        val menuSiteSettings: Boolean = false,
    ) {
        /** Normal saves always target the space root, so folder rows are hidden. */
        val hideFolders: Boolean get() = saveKind == SaveKind.NORMAL
    }

    /** A short message, optionally followed by a detail line (e.g. the error). */
    data class Notice(@StringRes val message: Int, @StringRes val detail: Int? = null)

    /** What the screen should do after the system back gesture/button. */
    enum class BackAction { Handled, ClearAddressFocus, GoBack, Dismiss }

    private val _state = MutableStateFlow(
        State(
            currentUrl = initialUrl.orEmpty(),
            currentTitle = initialTitle.orEmpty(),
            addressText = TextFieldValue(displayHost(initialUrl)),
            pinnedDestination = PinDestination(spaceId = currentSpaceId),
            saveKind = repository.saveKind(),
        ),
    )
    val state: StateFlow<State> = _state

    private var pinBannerJob: Job? = null
    private var noticeJob: Job? = null
    private var copyJob: Job? = null
    private var suggestionJob: Job? = null
    private var hasPendingPinSave = false
    /** Set after a pick or submit so the following text write does not suggest. */
    private var suggestionsSuspended = false
    /**
     * Numbers reader requests. A navigation or a close bumps it, so the parse
     * of a page that is gone can't publish over the one on screen.
     */
    private var readerRequest = 0

    /**
     * Live `document.title` from the WebView. The screen installs this.
     * WebView's own title callback is often the URL.
     */
    var documentTitle: suspend () -> String? = { null }

    /** Reader extraction payload from the page. Installed by the screen. */
    var readerPayload: suspend () -> String? = { null }

    /** Address and title for the open pages. Blank means no change. */
    var onPageChange: (url: String, title: String) -> Unit = { _, _ -> }

    /** A save started: this address goes into a space. */
    var onPinStarted: (url: String) -> Unit = {}
    var onPinFailed: () -> Unit = {}

    /** The save went through. */
    var onPinned: (url: String) -> Unit = {}

    /**
     * The page the user pinned (url to title), captured when the banner
     * opened, so a later navigation can't swap in another page.
     */
    private var pendingPin: Pair<String, String>? = null

    /** Whether this account can write normal tabs at all. Without it the save
     *  banner offers pinned only, like the share sheet. Signed out there is no
     *  account data to read at all. */
    val allowsNormalTabs: Boolean
        get() = isSignedIn() &&
            repository.cachedSnapshot?.normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED

    /** The spaces a pin can go to changed while the page was asleep. */
    fun updateSpaces(spaces: List<ZenSpaces.ZenSpace>, currentSpaceId: String) {
        this.spaces = spaces
        this.currentSpaceId = currentSpaceId
    }

    // MARK: - Chrome state

    fun onAddressTextChange(value: TextFieldValue) {
        _state.update { it.copy(addressText = value) }
        refreshSuggestions()
    }

    fun onAddressFocusChange(focused: Boolean) {
        if (focused == _state.value.isAddressFocused) return
        if (!focused) suggestionsSuspended = false
        val text = if (focused) effectiveUrl() else displayHost(effectiveUrl())
        _state.update {
            it.copy(
                isAddressFocused = focused,
                suggestions = if (focused) it.suggestions else emptyList(),
                historySuggestions = if (focused) it.historySuggestions else emptyList(),
                // Opening the field hands the whole address over, ready to be
                // replaced; a caret at index 0 would prepend into the scheme.
                addressText = TextFieldValue(
                    text = text,
                    selection = if (focused) TextRange(0, text.length) else TextRange(text.length),
                ),
            )
        }
        if (focused) {
            refreshSuggestions()
        } else {
            suggestionJob?.cancel()
            publishSuggestionRows()
        }
    }

    /**
     * Opens the address composer. The field itself is only composed while this
     * is on, so the screen focuses it afterwards; asking for focus here, before
     * the field exists, is an unattached `FocusRequester` and does nothing.
     */
    fun beginAddressEdit() {
        collapseMenu()
        onAddressFocusChange(true)
    }

    /** Closes the composer through the state, not only through the keyboard. */
    fun endAddressEdit() {
        onAddressFocusChange(false)
    }

    fun onLoadingChange(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    fun onProgressChange(progress: Float) {
        _state.update { it.copy(progress = progress) }
    }

    /**
     * A link the WebView must not load and nobody else can take. The page stays
     * as it is: the WebView's own error page would leave nothing to go back to.
     */
    fun onLinkUnavailable() {
        clearLoading()
        showNotice(Notice(R.string.browser_scheme_unavailable))
    }

    fun onDownloadStarted() = showNotice(Notice(R.string.browser_download_started))

    fun onDownloadFinished(saved: Boolean) = showNotice(
        Notice(if (saved) R.string.browser_download_finished else R.string.browser_download_failed),
    )

    /**
     * A load that never finishes must not leave the bar in its "stop" state:
     * a failed navigation gets no `onPageFinished` on some WebView versions.
     */
    private fun clearLoading() {
        _state.update { it.copy(isLoading = false, progress = 0f) }
    }

    fun onCanGoBackChange(canGoBack: Boolean) {
        _state.update { it.copy(canGoBack = canGoBack) }
    }

    fun onCanGoForwardChange(canGoForward: Boolean) {
        _state.update { it.copy(canGoForward = canGoForward) }
    }

    fun onCurrentUrlChange(url: String) {
        // A fresh document ends the reading view; a request still running for
        // the old page keeps its number and is dropped when it answers.
        if (!PageTitle.sameDocument(_state.value.currentUrl, url)) readerRequest++
        _state.update { state ->
            if (PageTitle.sameDocument(state.currentUrl, url)) {
                state.copy(currentUrl = url)
            } else {
                // A fresh document ends the reading view and the find session.
                state.copy(
                    currentUrl = url,
                    currentTitle = "",
                    readerArticle = null,
                    readerLoading = false,
                    findVisible = false,
                    findQuery = "",
                    findProgress = null,
                )
            }
        }
        onPageChange(url, "")
        // Leaving the pinned page saves it now instead of letting the banner
        // timer save whatever page is showing later.
        val pinned = pendingPin?.first
        if (hasPendingPinSave && pinned != null && !PageTitle.sameDocument(pinned, url)) {
            pinBannerJob?.cancel()
            commitPendingPinSave()
            _state.update { it.copy(showPinBanner = false) }
        }
    }

    fun onCurrentTitleChange(title: String) {
        // WebView's own error page has a title of its own.
        if (_state.value.pageError != null) return
        val usable = PageTitle.usable(title, effectiveUrl()) ?: return
        _state.update { it.copy(currentTitle = usable) }
        onPageChange(_state.value.currentUrl, usable)
    }

    fun onSiteThemeColorChange(color: Color?) {
        _state.update { it.copy(siteThemeColor = color) }
    }

    // MARK: - Error panel

    /** A main-frame load failed or the page crashed; the panel covers it. */
    fun onPageLoadFailed(error: BrowserPageError, url: String) {
        _state.update {
            it.copy(
                pageError = error,
                failedUrl = url,
                addressText = if (it.isAddressFocused) it.addressText else TextFieldValue(displayHost(url)),
            )
        }
    }

    /** A new load started, or the page underneath is shown again. */
    fun clearPageError() {
        if (_state.value.pageError == null) return
        _state.update { it.copy(pageError = null, failedUrl = "") }
    }

    /**
     * "Go back" when the failed load never replaced the page: the panel goes
     * and the address bar returns to [url], the page underneath.
     */
    fun revealPage(url: String) {
        clearPageError()
        onCurrentUrlChange(url)
        if (!_state.value.isAddressFocused) {
            _state.update { it.copy(addressText = TextFieldValue(displayHost(url))) }
        }
    }

    /** Search for the host that couldn't be found, usually a typo. */
    fun searchForFailedHost(): String {
        val failed = _state.value.failedUrl
        if (failed.isEmpty()) return ""
        val host = runCatching { URI(failed).host }.getOrNull()
        return SearchEngines.current.formatQuery(if (host.isNullOrEmpty()) failed else host)
    }

    /**
     * Resolves raw address input to a fully-qualified URL (or search URL).
     * The caller loads the returned value; empty means nothing to load.
     */
    fun submitAddress(input: String): String {
        suggestionsSuspended = true
        suggestionJob?.cancel()
        _state.update { it.copy(suggestions = emptyList(), historySuggestions = emptyList()) }
        publishSuggestionRows()
        return formatBrowserInput(input)
    }

    /**
     * A tapped suggestion searches; only an explicit http(s) address loads
     * directly (see [SearchSuggestions.target]).
     */
    fun selectSuggestion(suggestion: String): String {
        suggestionsSuspended = true
        suggestionJob?.cancel()
        _state.update {
            it.copy(
                suggestions = emptyList(),
                historySuggestions = emptyList(),
                addressText = TextFieldValue(suggestion),
            )
        }
        publishSuggestionRows()
        return SearchSuggestions.target(suggestion, SearchEngines.current)
    }

    /** A page from the history, picked from the list: its address to open. */
    fun selectVisit(visit: BrowserVisit): String {
        suggestionsSuspended = true
        suggestionJob?.cancel()
        _state.update { it.copy(suggestions = emptyList(), historySuggestions = emptyList()) }
        publishSuggestionRows()
        return visit.url
    }

    /**
     * Asks for suggestions while the field is focused and the text is a search
     * query. A URL, blank text, a custom engine, or a closed field sends nothing.
     * Errors and empty answers clear the list.
     */
    private fun refreshSuggestions() {
        suggestionJob?.cancel()
        if (suggestionsSuspended || !_state.value.isAddressFocused) {
            _state.update { it.copy(suggestions = emptyList(), historySuggestions = emptyList()) }
            publishSuggestionRows()
            return
        }
        val raw = _state.value.addressText.text
        // Local, so it answers right away and also for a typed address.
        _state.update { it.copy(historySuggestions = BrowserHistory.suggestions(raw, it.currentUrl)) }
        publishSuggestionRows()
        val url = SearchSuggestions.requestUrl(raw, SearchEngines.current)
        if (url == null) {
            _state.update { it.copy(suggestions = emptyList()) }
            publishSuggestionRows()
            return
        }
        suggestionJob = viewModelScope.launch {
            if (suggestionDebounceMs > 0) delay(suggestionDebounceMs)
            val body = try {
                suggestionClient.fetch(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (body == null) {
                _state.update { it.copy(suggestions = emptyList()) }
                publishSuggestionRows()
                return@launch
            }
            if (_state.value.addressText.text != raw || !_state.value.isAddressFocused) return@launch
            _state.update { it.copy(suggestions = SearchSuggestions.parse(body)) }
            publishSuggestionRows()
        }
    }

    /**
     * Rebuilds the one list the panel draws: the history first, then the
     * engine's phrases the history does not already name. The cap counts the
     * history rows first, so the phrases are what gets cut, never a page the
     * reader already visited, and an answer that arrives late can only add rows.
     */
    private fun publishSuggestionRows() {
        _state.update { state ->
            val rows = state.historySuggestions.map { AddressSuggestion.page(it) }.toMutableList()
            // The history row already prints the page's name and address, so a
            // phrase that repeats either one is the same row twice.
            val named = HashSet<String>()
            state.historySuggestions.forEach {
                named += it.title.lowercase()
                named += it.displayAddress.lowercase()
            }
            val unseen = state.suggestions.filter { named.add(it.lowercase()) }
            rows += SearchSuggestions.fitting(unseen, rows.size).map { AddressSuggestion(it) }
            state.copy(suggestionRows = rows)
        }
    }

    /** Back precedence: menus → address focus → web history → dismiss. */
    fun onBackPressed(webViewCanGoBack: Boolean): BackAction = when {
        _state.value.isFolderMenuOpen -> {
            _state.update { it.copy(isFolderMenuOpen = false) }
            startBannerDismissTimer(1000L)
            BackAction.Handled
        }
        _state.value.isSpaceMenuOpen -> {
            _state.update { it.copy(isSpaceMenuOpen = false) }
            startBannerDismissTimer(1000L)
            BackAction.Handled
        }
        _state.value.isAddressFocused -> {
            suggestionJob?.cancel()
            suggestionsSuspended = false
            _state.update {
                it.copy(
                    isAddressFocused = false,
                    suggestions = emptyList(),
                    addressText = TextFieldValue(displayHost(effectiveUrl())),
                )
            }
            BackAction.ClearAddressFocus
        }
        webViewCanGoBack -> BackAction.GoBack
        else -> {
            commitPendingPinSave()
            BackAction.Dismiss
        }
    }

    // MARK: - Deferred pin banner

    fun triggerPinBanner() {
        // Signed out there is no space and no account: never ask where to save.
        if (!isSignedIn()) return
        val urlToPin = effectiveUrl()
        // No spaces to attach the pin to: never open a banner that would save
        // an unattached record. A failed page is not worth pinning either.
        if (urlToPin.isEmpty() || spaces.isEmpty() || _state.value.pageError != null) return
        // The confirmation is a card over the page; an open menu would cover it.
        collapseMenu()

        if (_state.value.pinnedDestination.spaceId.isEmpty() ||
            spaces.none { it.id == _state.value.pinnedDestination.spaceId }
        ) {
            _state.update { it.copy(pinnedDestination = PinDestination(spaceId = currentSpaceId)) }
        } else if (_state.value.hideFolders) {
            // Normal saves always target the space root.
            _state.update {
                it.copy(pinnedDestination = it.pinnedDestination.copy(folderId = null))
            }
        }
        hasPendingPinSave = true
        pendingPin = urlToPin to _state.value.currentTitle
        // The saved setting is the default for this save; the banner can change it.
        val pendingKind = repository.saveKind().takeIf { allowsNormalTabs } ?: SaveKind.PINNED
        _state.update { it.copy(saveKind = pendingKind, showPinBanner = true) }
        startBannerDismissTimer(5000L) // 5.0 seconds initial time so user can interact
    }

    /**
     * Per-save pinned/normal choice. Nothing is persisted: the next save
     * starts from the setting again, as in the share sheet.
     */
    fun selectPinSaveKind(kind: SaveKind) {
        if (kind == _state.value.saveKind) return
        if (kind == SaveKind.NORMAL && !allowsNormalTabs) return
        _state.update {
            it.copy(
                saveKind = kind,
                // A normal save always lands at the space root.
                pinnedDestination = if (kind == SaveKind.NORMAL) {
                    it.pinnedDestination.copy(folderId = null)
                } else {
                    it.pinnedDestination
                },
            )
        }
        hasPendingPinSave = true
        startBannerDismissTimer(1200L)
    }

    fun changePinDestination(newDestination: PinDestination) {
        val forced = if (_state.value.hideFolders) newDestination.copy(folderId = null) else newDestination
        _state.update { it.copy(pinnedDestination = forced) }
        hasPendingPinSave = true
        startBannerDismissTimer(1200L) // 1.2s confirmation after explicit choice
    }

    fun onSpaceMenuOpenChange(open: Boolean) {
        _state.update { it.copy(isSpaceMenuOpen = open) }
    }

    fun onFolderMenuOpenChange(open: Boolean) {
        _state.update { it.copy(isFolderMenuOpen = open) }
    }

    /** Opening the menu cancels the banner timer so it stays alive while choosing. */
    fun onMenuWillOpen() {
        pinBannerJob?.cancel()
    }

    fun onMenuDidDismiss() {
        startBannerDismissTimer(1500L)
    }

    private fun startBannerDismissTimer(delayMillis: Long = 5000L) {
        pinBannerJob?.cancel()
        pinBannerJob = viewModelScope.launch {
            delay(delayMillis)
            commitPendingPinSave()
            _state.update { it.copy(showPinBanner = false) }
        }
    }

    /**
     * Deferred pin save: uploads the current page to the chosen destination.
     * Safe to call repeatedly (banner timer, back, close, dispose) — only the
     * pending flag decides whether anything is written.
     */
    fun commitPendingPinSave() {
        if (!hasPendingPinSave) return
        hasPendingPinSave = false
        // A save started before a sign-out never lands anywhere afterwards.
        if (!isSignedIn()) {
            pendingPin = null
            return
        }
        // No spaces to attach the pin to: refuse rather than write an
        // unattached record.
        if (spaces.isEmpty()) return
        // The kind chosen for this save, not the setting: the banner can change it.
        val kind = _state.value.saveKind
        val target = _state.value.pinnedDestination
        val (urlToPin, capturedTitle) = pendingPin ?: (effectiveUrl() to _state.value.currentTitle)
        pendingPin = null
        if (urlToPin.isEmpty()) return
        onPinStarted(urlToPin)
        // Still on the pinned page: the live title, or one that loaded after
        // the tap, wins over the one captured when the banner opened.
        val stillOnPage = PageTitle.sameDocument(urlToPin, effectiveUrl())

        viewModelScope.launch {
            val live = if (!stillOnPage) null else try {
                documentTitle()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val current = if (stillOnPage) _state.value.currentTitle else ""
            val titleToPin = PageTitle.headline(live ?: current.ifEmpty { capturedTitle }, urlToPin)
            try {
                val outcome = repository.addTab(
                    url = urlToPin,
                    title = titleToPin,
                    spaceId = target.spaceId,
                    // Normal tabs are never placed in a folder.
                    folderId = if (kind == SaveKind.NORMAL) null else target.folderId,
                    kind = kind,
                )
                if (outcome.fellBackToPinned) showNotice(Notice(R.string.save_fallback_normal_off))
                onPinned(urlToPin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onPinFailed()
                showNotice(Notice(R.string.browser_pin_failed, FriendlyError.messageRes(e)))
            }
        }
    }

    /** Brief banner after a save: a pinned fallback or a failed write. */
    private fun showNotice(notice: Notice) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = notice) }
        noticeJob = viewModelScope.launch {
            delay(6_000L)
            _state.update { it.copy(notice = null) }
        }
    }

    // MARK: - Page menu

    fun toggleMenu() {
        if (_state.value.menuExpanded) {
            collapseMenu()
        } else {
            _state.update { it.copy(menuExpanded = true) }
        }
    }

    fun collapseMenu() {
        _state.update { it.copy(menuExpanded = false, menuSiteSettings = false) }
    }

    // MARK: - Content blocking

    fun onBlockedCountChange(count: Int) {
        _state.update { it.copy(blockedCount = count) }
    }

    /** The screen reloads the page right after this; the card follows the page. */
    fun onShowingOriginalChange(showing: Boolean) {
        _state.update {
            it.copy(
                showingOriginal = showing,
                menuExpanded = if (showing) false else it.menuExpanded,
                menuSiteSettings = if (showing) false else it.menuSiteSettings,
            )
        }
    }

    /**
     * Opens the site's switches as a page inside the menu. The panel stays one
     * surface instead of stacking a sheet on top of it.
     */
    fun openSiteSettings() {
        _state.update { it.copy(menuExpanded = true, menuSiteSettings = true) }
    }

    /** Back to the menu's own page. The menu stays open. */
    fun closeSiteSettings() {
        _state.update { it.copy(menuSiteSettings = false) }
    }

    // MARK: - Page tools

    fun toggleDesktopSite() {
        val next = !_state.value.requestsDesktopSite
        _state.update { it.copy(requestsDesktopSite = next, menuExpanded = false) }
    }

    fun zoomIn() {
        val next = MiniBrowserZoom.clamped(_state.value.pageZoom + MiniBrowserZoom.step)
        _state.update { it.copy(pageZoom = next) }
    }

    fun zoomOut() {
        val next = MiniBrowserZoom.clamped(_state.value.pageZoom - MiniBrowserZoom.step)
        _state.update { it.copy(pageZoom = next) }
    }

    /** Back to 100%, the way a browser resets its zoom. */
    fun resetPageZoom() {
        _state.update { it.copy(pageZoom = MiniBrowserZoom.default) }
    }

    /** Transient confirmation on the copy row; the screen does the clipboard. */
    fun confirmCopied() {
        copyJob?.cancel()
        _state.update { it.copy(copyConfirmed = true) }
        copyJob = viewModelScope.launch {
            delay(1_600L)
            _state.update { it.copy(copyConfirmed = false) }
        }
    }

    // MARK: - Find on page

    fun openFind() {
        _state.update { it.copy(findVisible = true, menuExpanded = false) }
    }

    fun closeFind() {
        _state.update { it.copy(findVisible = false, findQuery = "", findProgress = null) }
    }

    fun setFindQuery(query: String) {
        _state.update { it.copy(findQuery = query) }
    }

    fun setFindProgress(progress: String?) {
        _state.update { it.copy(findProgress = progress) }
    }

    // MARK: - Reader

    /** Lifts the article out of the page, or drops back to the page. */
    fun toggleReader() {
        if (_state.value.readerArticle != null) {
            closeReader()
            return
        }
        loadReader()
    }

    fun closeReader() {
        // A parse still running for this page must not open the reader again.
        readerRequest++
        _state.update { it.copy(readerArticle = null, readerLoading = false) }
    }

    private fun loadReader() {
        if (_state.value.readerLoading) return
        val request = ++readerRequest
        _state.update { it.copy(readerLoading = true, menuExpanded = false) }
        viewModelScope.launch {
            val payload = try {
                readerPayload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // Long articles carry a payload of hundreds of kilobytes: the
            // parse belongs off the main thread.
            val article = withContext(readerParseDispatcher) { ReaderExtractor.parse(payload) }
            // The page changed or the reader was closed while this ran, so the
            // article and the "no article" notice both belong to a page that
            // is no longer showing.
            if (request != readerRequest) return@launch
            _state.update { it.copy(readerLoading = false, readerArticle = article) }
            if (article == null) showNotice(Notice(R.string.browser_reader_unavailable))
        }
    }

    private fun effectiveUrl(): String {
        val state = _state.value
        return state.failedUrl.takeIf { state.pageError != null && it.isNotEmpty() }
            ?: state.currentUrl.ifEmpty { initialUrl ?: "" }
    }

    companion object {
        private fun displayHost(url: String?): String {
            if (url.isNullOrEmpty()) return ""
            return runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url
        }
    }
}
