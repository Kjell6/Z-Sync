package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.ui.browser.PageBlocker
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.ui.sheets.SiteSettingsSheet
import de.kjell.zencompanion.ui.browser.ReaderExtractor
import de.kjell.zencompanion.ui.components.PinDestinationPicker
import de.kjell.zencompanion.ui.components.SaveKindMenuButton
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenTheme
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener

/** Mutable scroll bookkeeping: read on every packet, never rendered directly. */
private class ScrollTracker {
    var lastY = 0
    var downRun = 0
}

/** One ViewModel per screen; a launch key re-arms it instead of accumulating. */
private const val MINI_BROWSER_VIEW_MODEL_KEY = "mini-browser"

@SuppressLint("SetJavaScriptEnabled")
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MiniBrowserScreen(
    initialUrl: String?,
    initialTitle: String? = null,
    launchKey: Int,
    currentSpace: ZenSpaces.ZenSpace,
    allSpaces: List<ZenSpaces.ZenSpace>,
    browserRepository: BrowserRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val viewModel: MiniBrowserViewModel = viewModel(
        key = MINI_BROWSER_VIEW_MODEL_KEY,
        factory = MiniBrowserViewModel.Factory(
            repository = browserRepository,
            initialUrl = initialUrl,
            initialTitle = initialTitle,
            spaces = allSpaces,
            currentSpaceId = currentSpace.id,
        ),
    )
    val state by viewModel.state.collectAsState()

    // Scroll-driven bar: tucks away on the way down, returns on the way up.
    var barHidden by remember { mutableStateOf(false) }
    // Written on every scroll packet; a state write would recompose the screen.
    val scroll = remember { ScrollTracker() }
    val onPageScroll: (Int) -> Unit = { scrollY ->
        val delta = scrollY - scroll.lastY
        scroll.lastY = scrollY
        when {
            // A short page, or the top of a long one, always keeps the bar.
            scrollY <= 40 -> {
                scroll.downRun = 0
                barHidden = false
            }
            delta > 0 -> {
                scroll.downRun += delta
                if (scroll.downRun > 24 && scrollY > 80) barHidden = true
            }
            delta < -2 -> {
                scroll.downRun = 0
                barHidden = false
            }
        }
    }

    LaunchedEffect(launchKey) {
        barHidden = false
        scroll.downRun = 0
        scroll.lastY = 0
        viewModel.startNewLaunch(
            launchKey = launchKey,
            initialUrl = initialUrl,
            initialTitle = initialTitle,
            spaces = allSpaces,
            currentSpaceId = currentSpace.id,
        )
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    val blockingSettings by ContentBlocking.settings.collectAsState()
    val blocker = remember {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        PageBlocker { count -> main.post { viewModel.onBlockedCountChange(count) } }
    }
    var blockingReload by remember { mutableIntStateOf(0) }
    val consentScript = remember(blockingSettings, state.showingOriginal) {
        blocker.consentScript(ContentBlocking.consentScript(context), blockingSettings)
    }
    /// Scroll offset to put back after a reload finishes.
    var reloadScroll by remember { mutableStateOf<Int?>(null) }
    // A crashed renderer takes its WebView with it; a new key builds a fresh one.
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    // A new document, a failed load or a notice all mean the chrome must come
    // back: the scroll-driven hide only ever belongs to a page that scrolls.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            barHidden = false
            scroll.downRun = 0
        }
    }

    SideEffect {
        viewModel.documentTitle = documentTitle@{
            val view = webView ?: return@documentTitle null
            suspendCancellableCoroutine { continuation ->
                view.post {
                    if (!continuation.isActive) return@post
                    view.evaluateJavascript(PageTitle.DOCUMENT_TITLE_SCRIPT) { raw ->
                        if (continuation.isActive) {
                            continuation.resume(PageTitle.pickJsPayload(raw, view.url))
                        }
                    }
                }
            }
        }

        viewModel.readerPayload = reader@{
            val view = webView ?: return@reader null
            suspendCancellableCoroutine { continuation ->
                view.post {
                    if (!continuation.isActive) return@post
                    view.evaluateJavascript(ReaderExtractor.jsonScript) { raw ->
                        if (!continuation.isActive) return@evaluateJavascript
                        val payload = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                        continuation.resume(payload)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // This WebView is destroyed with the composition, so a posted
            // document.title lookup would never call back and the deferred
            // save would never reach the repository. Withdraw the live lookup
            // first; the save falls back to the title captured when the
            // banner opened.
            viewModel.documentTitle = { null }
            viewModel.commitPendingPinSave()
        }
    }

    // Auto-focus URL bar if opened without initial URL
    LaunchedEffect(initialUrl) {
        // After a configuration change the page is already open: don't
        // jump into the address bar again.
        if (initialUrl.isNullOrEmpty() && viewModel.restoredUrl(launchKey) == null) {
            delay(200)
            viewModel.beginAddressEdit()
        }
    }

    // The composer is only composed while the address is being edited, so the
    // focus request has to wait for it: a request made before the field exists
    // is an unattached FocusRequester and leaves the bar dead under the finger.
    LaunchedEffect(state.isAddressFocused) {
        if (state.isAddressFocused) {
            focusRequester.requestFocus()
        }
    }

    // Back handling
    BackHandler {
        when {
            state.findVisible -> viewModel.closeFind()
            state.readerArticle != null -> viewModel.closeReader()
            state.menuExpanded -> viewModel.collapseMenu()
            else -> when (viewModel.onBackPressed(webView?.canGoBack() == true)) {
                MiniBrowserViewModel.BackAction.Handled -> Unit
                MiniBrowserViewModel.BackAction.ClearAddressFocus -> focusManager.clearFocus()
                MiniBrowserViewModel.BackAction.GoBack -> webView?.goBack()
                MiniBrowserViewModel.BackAction.Dismiss -> onDismiss()
            }
        }
    }

    val effectiveUrl = state.failedUrl.takeIf { state.pageError != null && it.isNotEmpty() }
        ?: webView?.url
        ?: state.currentUrl.ifEmpty { initialUrl ?: "" }

    /** One path for typed text, a tapped suggestion and a suggestion search. */
    fun openAddressTarget(target: String) {
        if (target.isEmpty()) return
        if (loadsInWebView(target)) {
            webView?.loadUrl(target)
        } else if (!openURLExternally(context, Uri.parse(target))) {
            // An address that names another app's scheme never reaches the
            // WebView.
            viewModel.onLinkUnavailable()
        }
    }

    fun finishAddressEdit() {
        focusManager.clearFocus()
        keyboardController?.hide()
        viewModel.endAddressEdit()
    }

    val defaultSurface = MaterialTheme.colorScheme.surface
    // The error panel sits on the surface color. The failed load leaves the
    // previous page's color behind, so the bar ignores it while it shows.
    val targetBg = state.siteThemeColor.takeIf { state.pageError == null } ?: defaultSurface
    val animatedBgColor by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(300),
        label = "browserThemeColor",
    )

    // The bar stays put while a sheet, a reader, the find bar or a banner is up.
    val barVisible = !barHidden ||
        state.isAddressFocused ||
        state.menuExpanded ||
        state.showPinBanner ||
        state.notice != null ||
        state.findVisible ||
        state.readerArticle != null ||
        state.pageError != null

    // The page's color for the backdrop, nudged for the bar's plate.
    val barColor = animatedBgColor.nudgedForBar()
    val barIsDark = barColor.luminance() < 0.5f

    // The status and navigation bars sit on the same color. Restored on close,
    // so the spaces screen keeps its own.
    val view = LocalView.current
    DisposableEffect(view, barIsDark) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatusBars = bars?.isAppearanceLightStatusBars
        val lightNavigationBars = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = !barIsDark
        bars?.isAppearanceLightNavigationBars = !barIsDark
        onDispose {
            if (bars != null && lightStatusBars != null && lightNavigationBars != null) {
                bars.isAppearanceLightStatusBars = lightStatusBars
                bars.isAppearanceLightNavigationBars = lightNavigationBars
            }
        }
    }

    val navigationBarInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val collapsedBarHeight = CollapsedBarHeightBase + barBottomGap(navigationBarInset)
    val reservedBottom by animateDpAsState(
        targetValue = if (barVisible) collapsedBarHeight else 0.dp,
        // When the bar tucks away the page must take the space in one step.
        // Animating it made the web view grow in slices, with a band of empty
        // bar background left over below it the whole time.
        animationSpec = if (barVisible) tween(durationMillis = 180) else snap(),
        label = "barReserve",
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = barColor,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // A page field keeps the chrome where it is (behind the keys) and
            // gets its own Done control above them, as iOS does.
            val imeVisible = WindowInsets.isImeVisible
            val imeHeight = with(LocalDensity.current) {
                WindowInsets.ime.getBottom(this).toDp()
            }
            Column(Modifier.fillMaxSize()) {
            // Page pane: the page ends where the collapsed bar begins. The open
            // menu draws over the page instead of pushing it up.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                key(webViewGeneration) {
                    MiniBrowserWebView(
                        // A recreated WebView (theme or font change) reopens the
                        // page the user was on, not the one the browser opened with.
                        // After a crash it waits for Reload instead.
                        initialUrl = if (state.pageError == BrowserPageError.CRASHED) {
                            null
                        } else {
                            viewModel.restoredUrl(launchKey) ?: initialUrl
                        },
                        isAddressFocused = state.isAddressFocused,
                        desktopSite = state.requestsDesktopSite,
                        textZoomPercent = (state.pageZoom * 100f).toInt(),
                        onWebViewCreated = { webView = it },
                        onLoadingChange = { loading ->
                            viewModel.onLoadingChange(loading)
                            if (!loading) {
                                reloadScroll?.let { offset ->
                                    reloadScroll = null
                                    // The new document can be shorter or taller;
                                    // clamp so the page still lands on content.
                                    // `scrollTo` clamps to the new page height
                                    // itself, so a shorter document is safe.
                                    webView?.post { webView?.scrollTo(0, offset) }
                                }
                            }
                        },
                        onSiteThemeColorChange = viewModel::onSiteThemeColorChange,
                        onCurrentUrlChange = viewModel::onCurrentUrlChange,
                        onCanGoBackChange = viewModel::onCanGoBackChange,
                        onCanGoForwardChange = viewModel::onCanGoForwardChange,
                        onTextInputChange = viewModel::onAddressTextChange,
                        onTitleChange = viewModel::onCurrentTitleChange,
                        onProgressChange = viewModel::onProgressChange,
                        onPageStarted = {
                            viewModel.onShowingOriginalChange(blocker.showingOriginal)
                            viewModel.clearPageError()
                            barHidden = false
                            scroll.downRun = 0
                            scroll.lastY = 0
                        },
                        onLoadFailed = viewModel::onPageLoadFailed,
                        onRendererGone = { url ->
                            viewModel.onPageLoadFailed(BrowserPageError.CRASHED, url ?: state.currentUrl)
                            viewModel.onLoadingChange(false)
                            webView = null
                            webViewGeneration++
                        },
                        onLinkUnavailable = viewModel::onLinkUnavailable,
                        onScrollChange = onPageScroll,
                        onFindResult = { active, total ->
                            viewModel.setFindProgress(if (total > 0) "$active/$total" else null)
                        },
                        blocker = blocker,
                        consentScript = consentScript,
                        blockingSettings = blockingSettings,
                        blockingReload = blockingReload,
                    )
                }

                if (state.readerArticle != null) {
                    MiniBrowserReader(
                        article = state.readerArticle!!,
                        onClose = viewModel::closeReader,
                    )
                }

                state.pageError?.let { error ->
                    BrowserErrorPanel(
                        error = error,
                        host = addressDisplay(state.failedUrl),
                        isLoading = state.isLoading,
                        onRetry = {
                            val target = state.failedUrl
                            if (error == BrowserPageError.CRASHED) viewModel.clearPageError()
                            openAddressTarget(target)
                        },
                        onSearch = { openAddressTarget(viewModel.searchForFailedHost()) },
                        onOpenExternally = { openURLExternally(context, state.failedUrl) },
                        onLeave = {
                            val web = webView
                            when (browserErrorExit(web?.url, state.failedUrl, web?.canGoBack() == true)) {
                                BrowserErrorExit.REVEAL -> viewModel.revealPage(web?.url.orEmpty())
                                BrowserErrorExit.GO_BACK -> web?.goBack()
                                BrowserErrorExit.DISMISS -> {
                                    viewModel.commitPendingPinSave()
                                    onDismiss()
                                }
                            }
                        },
                    )
                }

                if (state.findVisible) {
                    ZenTheme(darkTheme = barIsDark) {
                    MiniBrowserFindBar(
                        query = state.findQuery,
                        progress = state.findProgress,
                        barColor = barColor,
                        onQueryChange = viewModel::setFindQuery,
                        onNext = { webView?.findNext(true) },
                        onPrevious = { webView?.findNext(false) },
                        onClose = {
                            webView?.clearMatches()
                            viewModel.closeFind()
                        },
                    )
                    }
                }

                // Deferred Space Pinning Banner (floats above the bar). Fully
                // qualified: the ColumnScope overload would shadow this one.
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.showPinBanner,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 20.dp, end = 20.dp),
                ) {
                    PinBanner(
                        state = state,
                        allSpaces = allSpaces,
                        context = context,
                        viewModel = viewModel,
                    )
                }

                // Normal-save fallback notice (same banner styling, anchored
                // to the top so it never collides with the destination banner).
                // Keep the last notice while it animates out.
                var shownNotice by remember { mutableStateOf<MiniBrowserViewModel.Notice?>(null) }
                LaunchedEffect(state.notice) {
                    state.notice?.let { shownNotice = it }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.notice != null,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                ) {
                    NoticeBanner(notice = shownNotice)
                }
            }

                Spacer(Modifier.height(reservedBottom))
            }

            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Only the address composer rides the keyboard. A page
                    // field must not push the whole bar up into the page.
                    .then(if (state.isAddressFocused) Modifier.imePadding() else Modifier),
            ) {
                ZenTheme(darkTheme = barIsDark) {
                    MiniBrowserBar(
                        state = state,
                        viewModel = viewModel,
                        onClose = {
                            viewModel.commitPendingPinSave()
                            onDismiss()
                        },
                        effectiveUrl = effectiveUrl,
                        addressText = state.addressText,
                        onAddressTextChange = viewModel::onAddressTextChange,
                        focusRequester = focusRequester,
                        onAddressFocusedChange = viewModel::onAddressFocusChange,
                        onBeginAddressEdit = viewModel::beginAddressEdit,
                        onCancelAddressEdit = {
                            viewModel.endAddressEdit()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        onSubmitAddress = { input ->
                            openAddressTarget(viewModel.submitAddress(input))
                            finishAddressEdit()
                        },
                        onPickSuggestion = { suggestion ->
                            openAddressTarget(viewModel.selectSuggestion(suggestion))
                            finishAddressEdit()
                        },
                        // A page that failed to load has nothing worth pinning.
                        canPin = allSpaces.isNotEmpty() && state.pageError == null,
                        onBack = { webView?.goBack() },
                        onForward = { webView?.goForward() },
                        onReload = {
                            webView?.let { view ->
                                // Remember where the reader was: the reloaded
                                // page may otherwise land somewhere else.
                                reloadScroll = view.scrollY
                                view.reload()
                            }
                        },
                        onStop = { webView?.stopLoading() },
                        onCopyLink = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null && effectiveUrl.isNotEmpty()) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("URL", effectiveUrl))
                                Haptics.perform(context, Haptics.Kind.SELECTION)
                                viewModel.confirmCopied()
                            }
                        },
                        onFind = { viewModel.openFind() },
                        onShare = { shareUrl(context, effectiveUrl, state.currentTitle) },
                        onOpenExternal = { openURLExternally(context, effectiveUrl) },
                        onPin = {
                            Haptics.perform(context, Haptics.Kind.CONFIRM)
                            viewModel.triggerPinBanner()
                        },
                        blockingCard = BlockingCard.of(
                            settings = blockingSettings,
                            host = pageHost(effectiveUrl),
                            blockedCount = state.blockedCount,
                            showingOriginal = state.showingOriginal,
                        ),
                        onBlockingCard = {
                            when (
                                BlockingCard.of(blockingSettings, pageHost(effectiveUrl), state.blockedCount, state.showingOriginal)
                            ) {
                                is BlockingCard.Blocked -> {
                                    blocker.restoreOriginal(effectiveUrl)
                                    viewModel.onShowingOriginalChange(true)
                                    blockingReload++
                                }
                                BlockingCard.ShowingOriginal -> {
                                    blocker.blockAgain()
                                    viewModel.onShowingOriginalChange(false)
                                    viewModel.collapseMenu()
                                    blockingReload++
                                }
                                is BlockingCard.OffForSite -> viewModel.openSiteSettings()
                                BlockingCard.Hidden -> Unit
                            }
                        },
                        onSiteSettings = viewModel::openSiteSettings,
                        barColor = barColor,
                    )
                }
            }

            if (imeVisible && !state.isAddressFocused) {
                PageKeyboardDone(
                    bottomInset = imeHeight + 12.dp,
                    onClick = {
                        webView?.evaluateJavascript(
                            "document.activeElement && document.activeElement.blur()",
                            null,
                        )
                        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        keyboard?.hideSoftInputFromWindow(webView?.windowToken, 0)
                    },
                )
            }
        }
    }

    if (state.siteSettingsOpen) {
        pageHost(effectiveUrl)?.let { host ->
            SiteSettingsSheet(
                host = host,
                onDismiss = viewModel::closeSiteSettings,
                // The page reloads so the new switches apply to it.
                onChanged = { blockingReload++ },
            )
        } ?: viewModel.closeSiteSettings()
    }

    // Keep the find bar's matches in sync with the query.
    LaunchedEffect(state.findVisible, state.findQuery) {
        if (!state.findVisible) return@LaunchedEffect
        if (state.findQuery.isEmpty()) {
            webView?.clearMatches()
            viewModel.setFindProgress(null)
        } else {
            webView?.findAllAsync(state.findQuery)
        }
    }
}

// MARK: - Page keyboard

/** Above the keys while a page's own field is focused: the way out of the
 *  page keyboard, since Android has no input accessory of its own. */
@Composable
private fun BoxScope.PageKeyboardDone(
    bottomInset: Dp,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = bottomInset)
            .size(44.dp)
            .background(zen.lift, shape)
            .border(liftBorder(), shape)
            .barClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = stringResource(R.string.common_done),
            tint = zen.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - Pin banner

@Composable
private fun PinBanner(
    state: MiniBrowserViewModel.State,
    allSpaces: List<ZenSpaces.ZenSpace>,
    context: Context,
    viewModel: MiniBrowserViewModel,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (viewModel.allowsNormalTabs) {
                    // The same control as the share sheet: the round pin/tab
                    // menu opens the per-save choice.
                    SaveKindMenuButton(
                        selected = state.saveKind,
                        onSelect = viewModel::selectPinSaveKind,
                        onMenuWillOpen = viewModel::onMenuWillOpen,
                        onMenuDidDismiss = viewModel::onMenuDidDismiss,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(
                        if (state.saveKind == de.kjell.zencompanion.data.SaveKind.NORMAL) {
                            R.string.browser_normal
                        } else {
                            R.string.browser_pinned
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.width(12.dp))

            VerticalDivider(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            Spacer(Modifier.width(8.dp))

            PinDestinationPicker(
                spaces = allSpaces,
                selected = state.pinnedDestination,
                isSpaceMenuOpen = state.isSpaceMenuOpen,
                isFolderMenuOpen = state.isFolderMenuOpen,
                onSelect = { destination ->
                    Haptics.perform(context, Haptics.Kind.SELECTION)
                    viewModel.changePinDestination(destination)
                },
                onSpaceMenuOpenChange = viewModel::onSpaceMenuOpenChange,
                onFolderMenuOpenChange = viewModel::onFolderMenuOpenChange,
                onMenuWillOpen = viewModel::onMenuWillOpen,
                onMenuDidDismiss = viewModel::onMenuDidDismiss,
                hideFolders = state.hideFolders,
            )
        }
    }
}

@Composable
private fun NoticeBanner(notice: MiniBrowserViewModel.Notice?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = listOfNotNull(
                    notice?.message?.let { stringResource(it) },
                    notice?.detail?.let { stringResource(it) },
                ).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Find bar

@Composable
private fun MiniBrowserFindBar(
    query: String,
    progress: String?,
    barColor: Color,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val zen = LocalZenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(zen.paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .background(zen.lift, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = ZenType.rounded(15, FontWeight.Medium).copy(color = zen.ink),
                cursorBrush = SolidColor(zen.coral),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_find_on_page),
                                style = ZenType.rounded(15, FontWeight.Medium),
                                color = zen.ink.copy(alpha = 0.55f),
                            )
                        }
                        inner()
                    }
                },
            )
            if (progress != null) {
                Text(
                    text = progress,
                    style = ZenType.rounded(13, FontWeight.Medium),
                    color = zen.ink.copy(alpha = 0.6f),
                )
            }
        }

        FindStep(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.browser_find_previous), onPrevious)
        FindStep(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.browser_find_next), onNext)
        FindStep(Icons.Outlined.Close, stringResource(R.string.common_done), onClose)
    }
}

@Composable
private fun FindStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .barClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - Share

private fun shareUrl(context: Context, url: String, title: String) {
    if (url.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

/** The page's host for blocking, or null for a blank or non-web page. */
private fun pageHost(url: String): String? {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return uri.host?.takeIf { it.isNotEmpty() }
}
