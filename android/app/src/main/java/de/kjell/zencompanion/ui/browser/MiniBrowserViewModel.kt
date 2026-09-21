package de.kjell.zencompanion.ui.browser

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.screens.formatBrowserInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        /** True while a requested normal save fell back to a pinned record. */
        val fallbackNotice: Boolean = false,
    ) {
        /** Normal saves always target the space root, so folder rows are hidden. */
        val hideFolders: Boolean get() = saveKind == SaveKind.NORMAL
    }

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
    private var fallbackNoticeJob: Job? = null
    private var hasPendingPinSave = false

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
        fallbackNoticeJob?.cancel()
        hasPendingPinSave = false
        _state.value = State(
            currentUrl = initialUrl.orEmpty(),
            currentTitle = initialTitle.orEmpty(),
            addressText = TextFieldValue(displayHost(initialUrl)),
            pinnedDestination = PinDestination(spaceId = currentSpaceId),
            saveKind = repository.saveKind(),
        )
    }

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
        _state.update { it.copy(currentUrl = url) }
    }

    fun onCurrentTitleChange(title: String) {
        _state.update { it.copy(currentTitle = title) }
    }

    fun onSiteThemeColorChange(color: Color?) {
        _state.update { it.copy(siteThemeColor = color) }
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
        val urlToPin = effectiveUrl()
        if (urlToPin.isEmpty()) return

        val titleToPin = _state.value.currentTitle.ifEmpty {
            runCatching { Uri.parse(urlToPin).host }.getOrNull() ?: urlToPin
        }
        viewModelScope.launch {
            runCatching {
                val outcome = repository.addTab(
                    url = urlToPin,
                    title = titleToPin,
                    spaceId = target.spaceId,
                    // Normal tabs are never placed in a folder.
                    folderId = if (kind == SaveKind.NORMAL) null else target.folderId,
                    kind = kind,
                )
                if (outcome.fellBackToPinned) showFallbackNotice()
            }
        }
    }

    /** Brief banner telling the user a normal save was pinned instead. */
    private fun showFallbackNotice() {
        fallbackNoticeJob?.cancel()
        _state.update { it.copy(fallbackNotice = true) }
        fallbackNoticeJob = viewModelScope.launch {
            delay(6_000L)
            _state.update { it.copy(fallbackNotice = false) }
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
