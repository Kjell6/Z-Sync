package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.ReaderExtractor
import de.kjell.zencompanion.ui.components.PinDestinationPicker
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenTheme
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener

/** One ViewModel per screen; a launch key re-arms it instead of accumulating. */
private const val MINI_BROWSER_VIEW_MODEL_KEY = "mini-browser"

/** Chrome-like desktop user agent for the "Request Desktop Site" switch. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
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
    var lastScrollY by remember { mutableStateOf(0) }
    var scrollDownRun by remember { mutableStateOf(0) }
    val onPageScroll: (Int) -> Unit = { scrollY ->
        val delta = scrollY - lastScrollY
        lastScrollY = scrollY
        when {
            // A short page, or the top of a long one, always keeps the bar.
            scrollY <= 40 -> {
                scrollDownRun = 0
                barHidden = false
            }
            delta > 0 -> {
                scrollDownRun += delta
                if (scrollDownRun > 24 && scrollY > 80) barHidden = true
            }
            delta < -2 -> {
                scrollDownRun = 0
                barHidden = false
            }
        }
    }

    LaunchedEffect(launchKey) {
        barHidden = false
        scrollDownRun = 0
        lastScrollY = 0
        viewModel.startNewLaunch(
            launchKey = launchKey,
            initialUrl = initialUrl,
            initialTitle = initialTitle,
            spaces = allSpaces,
            currentSpaceId = currentSpace.id,
        )
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }

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
            viewModel.commitPendingPinSave()
        }
    }

    // Auto-focus URL bar if opened without initial URL
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNullOrEmpty()) {
            delay(200)
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

    val effectiveUrl = webView?.url ?: state.currentUrl.ifEmpty { initialUrl ?: "" }
    val defaultSurface = MaterialTheme.colorScheme.surface
    val targetBg = state.siteThemeColor ?: defaultSurface
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
        state.noticeRes != null ||
        state.findVisible ||
        state.readerArticle != null

    // The page's color for the backdrop, nudged for the bar's plate.
    val barColor = animatedBgColor.nudgedForBar()
    val barIsDark = barColor.luminance() < 0.5f

    Surface(
        modifier = modifier.fillMaxSize(),
        color = animatedBgColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            // Page pane: the page ends where the bar begins.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MiniBrowserWebView(
                    initialUrl = initialUrl,
                    isAddressFocused = state.isAddressFocused,
                    desktopSite = state.requestsDesktopSite,
                    textZoomPercent = (state.pageZoom * 100f).toInt(),
                    onWebViewCreated = { webView = it },
                    onLoadingChange = viewModel::onLoadingChange,
                    onSiteThemeColorChange = viewModel::onSiteThemeColorChange,
                    onCurrentUrlChange = viewModel::onCurrentUrlChange,
                    onCanGoBackChange = viewModel::onCanGoBackChange,
                    onCanGoForwardChange = viewModel::onCanGoForwardChange,
                    onTextInputChange = viewModel::onAddressTextChange,
                    onTitleChange = viewModel::onCurrentTitleChange,
                    onProgressChange = viewModel::onProgressChange,
                    onScrollChange = onPageScroll,
                    onFindResult = { active, total ->
                        viewModel.setFindProgress(if (total > 0) "$active/$total" else null)
                    },
                )

                if (state.readerArticle != null) {
                    MiniBrowserReader(
                        article = state.readerArticle!!,
                        onClose = viewModel::closeReader,
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

                state.noticeRes?.let { res ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                    ) {
                        NoticeBanner(text = stringResource(res))
                    }
                }
            }

            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically(initialOffsetY = { it }) +
                    expandVertically(expandFrom = Alignment.Top),
                exit = slideOutVertically(targetOffsetY = { it }) +
                    shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                ZenTheme(darkTheme = barIsDark) {
                MiniBrowserBar(
                    state = state,
                    viewModel = viewModel,
                    effectiveUrl = effectiveUrl,
                    addressText = state.addressText,
                    onAddressTextChange = viewModel::onAddressTextChange,
                    focusRequester = focusRequester,
                    onAddressFocusedChange = viewModel::onAddressFocusChange,
                    onBeginAddressEdit = {
                        viewModel.collapseMenu()
                        focusRequester.requestFocus()
                    },
                    onCancelAddressEdit = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                    onSubmitAddress = { input ->
                        val target = viewModel.submitAddress(input)
                        if (target.isNotEmpty()) {
                            webView?.loadUrl(target)
                        }
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                    onBack = { webView?.goBack() },
                    onForward = { webView?.goForward() },
                    onReload = { webView?.reload() },
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
                    onClose = {
                        viewModel.commitPendingPinSave()
                        onDismiss()
                    },
                    barColor = barColor,
                )
                }
            }
        }
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
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
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
private fun NoticeBanner(text: String) {
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
                text = text,
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
            .clickable(onClick = onClick),
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
