package de.kjell.zencompanion.ui.browser

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.R
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private var initialUrl: String? = initialUrl
    private var spaces: List<ZenSpaces.ZenSpace> = spaces
    private var currentSpaceId: String = currentSpaceId
    private var launchKey: Int? = null

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
        val suggestions: List<String> = emptyList(),
        /** Ads and trackers blocked on the current page. */
        val blockedCount: Int = 0,
        /** The page was reloaded once with nothing blocked. */
        val showingOriginal: Boolean = false,
        /** The site's own blocking switches, opened from the menu. */
        val siteSettingsOpen: Boolean = false,
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
     * Live `document.title` from the WebView. The screen installs this.
     * WebView's own title callback is often the URL.
     */
    var documentTitle: suspend () -> String? = { null }

    /** Reader extraction payload from the page. Installed by the screen. */
    var readerPayload: suspend () -> String? = { null }

    /**
     * The page the user pinned (url to title), captured when the banner
     * opened, so a later navigation can't swap in another page.
     */
    private var pendingPin: Pair<String, String>? = null

    /** Whether this account can write normal tabs at all. Without it the save
     *  banner offers pinned only, like the share sheet. */
    val allowsNormalTabs: Boolean
        get() = repository.cachedSnapshot?.normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED

    /**
     * Re-arms the single per-screen instance for a new browser launch. The
     * screen keeps one ViewModel key (so launches no longer accumulate
     * ViewModels); a repeated launch key is a no-op so configuration changes
     * preserve live state.
     */
    fun startNewLaunch(
        launchKey: Int,
        initialUrl: String?,
        initialTitle: String?,
        spaces: List<ZenSpaces.ZenSpace>,
        currentSpaceId: String,
    ) {
        if (this.launchKey == launchKey) return
        this.launchKey = launchKey
        this.initialUrl = initialUrl
        this.spaces = spaces
        this.currentSpaceId = currentSpaceId
        pinBannerJob?.cancel()
        noticeJob?.cancel()
        suggestionJob?.cancel()
        suggestionsSuspended = false
        hasPendingPinSave = false
        pendingPin = null
        _state.value = State(
            currentUrl = initialUrl.orEmpty(),
            currentTitle = initialTitle.orEmpty(),
            addressText = TextFieldValue(displayHost(initialUrl)),
            pinnedDestination = PinDestination(spaceId = currentSpaceId),
            saveKind = repository.saveKind(),
        )
    }

    /**
     * The page to reopen when the screen is recreated for the same launch
     * (theme or font change), or null for a new launch. A new launch must not
     * see the previous launch's page before [startNewLaunch] runs.
     */
    fun restoredUrl(launchKey: Int): String? =
        if (this.launchKey == launchKey) _state.value.currentUrl.ifEmpty { null } else null

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
                // Opening the field hands the whole address over, ready to be
                // replaced; a caret at index 0 would prepend into the scheme.
                addressText = TextFieldValue(
                    text = text,
                    selection = if (focused) TextRange(0, text.length) else TextRange(text.length),
                ),
            )
        }
        if (focused) refreshSuggestions() else suggestionJob?.cancel()
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
        _state.update { it.copy(suggestions = emptyList()) }
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
                addressText = TextFieldValue(suggestion),
            )
        }
        return SearchSuggestions.target(suggestion, SearchEngines.current)
    }

    /**
     * Asks for suggestions while the field is focused and the text is a search
     * query. A URL, blank text, a custom engine, or a closed field sends nothing.
     * Errors and empty answers clear the list.
     */
    private fun refreshSuggestions() {
        suggestionJob?.cancel()
        if (suggestionsSuspended || !_state.value.isAddressFocused) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        val raw = _state.value.addressText.text
        val url = SearchSuggestions.requestUrl(raw, SearchEngines.current)
        if (url == null) {
            _state.update { it.copy(suggestions = emptyList()) }
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
                return@launch
            }
            if (_state.value.addressText.text != raw || !_state.value.isAddressFocused) return@launch
            _state.update { it.copy(suggestions = SearchSuggestions.parse(body)) }
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
        // No spaces to attach the pin to: refuse rather than write an
        // unattached record.
        if (spaces.isEmpty()) return
        // The kind chosen for this save, not the setting: the banner can change it.
        val kind = _state.value.saveKind
        val target = _state.value.pinnedDestination
        val (urlToPin, capturedTitle) = pendingPin ?: (effectiveUrl() to _state.value.currentTitle)
        pendingPin = null
        if (urlToPin.isEmpty()) return
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        _state.update { it.copy(menuExpanded = !it.menuExpanded) }
    }

    fun collapseMenu() {
        _state.update { it.copy(menuExpanded = false) }
    }

    // MARK: - Content blocking

    fun onBlockedCountChange(count: Int) {
        _state.update { it.copy(blockedCount = count) }
    }

    /** The screen reloads the page right after this; the card follows the page. */
    fun onShowingOriginalChange(showing: Boolean) {
        _state.update { it.copy(showingOriginal = showing, menuExpanded = if (showing) false else it.menuExpanded) }
    }

    fun openSiteSettings() {
        _state.update { it.copy(siteSettingsOpen = true, menuExpanded = false) }
    }

    fun closeSiteSettings() {
        _state.update { it.copy(siteSettingsOpen = false) }
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
        _state.update { it.copy(readerArticle = null, readerLoading = false) }
    }

    private fun loadReader() {
        if (_state.value.readerLoading) return
        _state.update { it.copy(readerLoading = true, menuExpanded = false) }
        viewModelScope.launch {
            val payload = try {
                readerPayload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val article = ReaderExtractor.parse(payload)
            _state.update { it.copy(readerLoading = false, readerArticle = article) }
            if (article == null) showNotice(Notice(R.string.browser_reader_unavailable))
        }
    }

    private fun effectiveUrl(): String = _state.value.currentUrl.ifEmpty { initialUrl ?: "" }

    class Factory(
        private val repository: BrowserRepository,
        private val initialUrl: String?,
        private val initialTitle: String?,
        private val spaces: List<ZenSpaces.ZenSpace>,
        private val currentSpaceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MiniBrowserViewModel(repository, initialUrl, initialTitle, spaces, currentSpaceId) as T
    }

    companion object {
        private fun displayHost(url: String?): String {
            if (url.isNullOrEmpty()) return ""
            return runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url
        }
    }
}
