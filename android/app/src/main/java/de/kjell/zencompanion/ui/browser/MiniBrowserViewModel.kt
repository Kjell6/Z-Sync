package de.kjell.zencompanion.ui.browser

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.data.SearchEngines
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
    private var hasPendingPinSave = false

    /**
     * Live `document.title` from the WebView. The screen installs this.
     * WebView's own title callback is often the URL.
     */
    var documentTitle: suspend () -> String? = { null }

    /**
     * The page the user pinned (url to title), captured when the banner
     * opened, so a later navigation can't swap in another page.
     */
    private var pendingPin: Pair<String, String>? = null

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
    }

    fun onAddressFocusChange(focused: Boolean) {
        if (focused == _state.value.isAddressFocused) return
        _state.update {
            it.copy(
                isAddressFocused = focused,
                addressText = TextFieldValue(
                    text = if (focused) effectiveUrl() else displayHost(effectiveUrl()),
                ),
            )
        }
    }

    fun onLoadingChange(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    fun onProgressChange(progress: Float) {
        _state.update { it.copy(progress = progress) }
    }

    fun onCanGoBackChange(canGoBack: Boolean) {
        _state.update { it.copy(canGoBack = canGoBack) }
    }

    fun onCanGoForwardChange(canGoForward: Boolean) {
        _state.update { it.copy(canGoForward = canGoForward) }
    }

    fun onCurrentUrlChange(url: String) {
        _state.update { state ->
            if (PageTitle.sameDocument(state.currentUrl, url)) state.copy(currentUrl = url)
            else state.copy(currentUrl = url, currentTitle = "")
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
    fun submitAddress(input: String): String = formatBrowserInput(input)

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
            _state.update {
                it.copy(
                    isAddressFocused = false,
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
        // an unattached record.
        if (urlToPin.isEmpty() || spaces.isEmpty()) return

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
        _state.update { it.copy(showPinBanner = true) }
        startBannerDismissTimer(5000L) // 5.0 seconds initial time so user can interact
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
        // Read fresh: the setting may have changed while the browser is open.
        val kind = repository.saveKind()
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
