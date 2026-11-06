package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.kjell.zencompanion.R
import de.kjell.zencompanion.util.FriendlyError
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.PageSession
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.OpenPage
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.ui.components.PinDestinationPicker
import de.kjell.zencompanion.ui.components.SaveKindMenuButton
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenTheme
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mutable scroll bookkeeping: read on every packet, never rendered directly. */
private class ScrollTracker {
    var lastY = 0
    var downRun = 0
}

/** Space between two pages while they slide past each other. */
private val PageGap = 12.dp

/**
 * The browser: the page on screen with its chrome, and the overview of the open pages over it.
 * The pages themselves live in [pages]; closing this screen does not end them.
 */
@Composable
fun MiniBrowserScreen(
    pages: BrowserPages,
    /** Opened from the count on home: the overview first, no page yet. */
    startsInOverview: Boolean,
    /** Set when the app came back without its pages, after the process died. */
    launchUrl: String?,
    launchTitle: String?,
    currentSpace: ZenSpaces.ZenSpace,
    allSpaces: List<ZenSpaces.ZenSpace>,
    browserRepository: BrowserRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showOverview by rememberSaveable { mutableStateOf(startsInOverview) }
    // Still set after the process died: the page to come back to is then the
    // last one in use, not the one this browser was first opened with.
    var launched by rememberSaveable { mutableStateOf(false) }
    // Runs once, inside the first composition: everything below reads the page it opens.
    @Suppress("RememberReturnType")
    remember {
        pages.updateSpaces(allSpaces, currentSpace.id)
        if (pages.active == null && !startsInOverview) {
            val lastUsed = if (launched) pages.store.pages.value.maxByOrNull { it.lastUsedAt } else null
            if (lastUsed != null) pages.activate(lastUsed.id) else pages.open(launchUrl, launchTitle)
        }
        launched = true
    }
    val openPages by pages.store.pages.collectAsState()
    val session = pages.active
    val scope = rememberCoroutineScope()

    val overviewAlpha = remember { Animatable(0f) }
    /** The picture flying between the page (1) and its card (0). */
    val heroProgress = remember { Animatable(1f) }
    val heroAlpha = remember { Animatable(1f) }
    var hero by remember { mutableStateOf<OverviewHero?>(null) }
    /** Why pinning a page from the overview failed. */
    var pinErrorRes by remember { mutableStateOf<Int?>(null) }
    /** The card the picture stands in for; it shows once the picture lands. */
    var hiddenCardId by remember { mutableStateOf<String?>(null) }
    /** A page that must not join the grid while it fades out. */
    var excludedCardId by remember { mutableStateOf<String?>(null) }
    val cardFrames = remember { HashMap<String, Rect>() }
    var pageFrame by remember { mutableStateOf(Rect.Zero) }

    suspend fun awaitLayout() {
        repeat(3) { withFrameNanos { } }
    }

    /** The page shrinks into its card, once the grid has laid out and the card's place is known. */
    fun openOverview(page: PageSession) {
        scope.launch {
            pages.capture(page)
            val shot = page.shot
            hiddenCardId = page.id
            overviewAlpha.snapTo(0f)
            showOverview = true
            awaitLayout()
            val card = cardFrames[page.id]
            if (shot == null || card == null || pageFrame.isEmpty) {
                hiddenCardId = null
                overviewAlpha.animateTo(1f, tween(200))
                return@launch
            }
            heroAlpha.snapTo(1f)
            heroProgress.snapTo(1f)
            hero = OverviewHero(shot, card)
            launch { overviewAlpha.animateTo(1f, tween(HeroMillis)) }
            heroProgress.animateTo(0f, tween(HeroMillis, easing = FastOutSlowInEasing))
            hiddenCardId = null
            hero = null
        }
    }

    /** The chosen page grows out of its card and lands on the live page. */
    fun closeOverview(page: OpenPage) {
        val session = pages.session(page.id)
        val shot = session?.shot
        val card = cardFrames[page.id]
        pages.activate(page.id)
        scope.launch {
            if (shot == null || card == null) {
                overviewAlpha.animateTo(0f, tween(200))
                showOverview = false
                return@launch
            }
            hiddenCardId = page.id
            heroAlpha.snapTo(1f)
            heroProgress.snapTo(0f)
            hero = OverviewHero(shot, card)
            // From home the page has only just joined the screen.
            awaitLayout()
            launch { overviewAlpha.animateTo(0f, tween(HeroMillis)) }
            heroProgress.animateTo(1f, tween(HeroMillis, easing = FastOutSlowInEasing))
            showOverview = false
            hiddenCardId = null
            // A woken page may still be loading under the picture.
            heroAlpha.animateTo(0f, tween(140))
            hero = null
        }
    }

    /** From home the overview fades in over the spaces; a recreated screen just comes back. */
    LaunchedEffect(Unit) {
        if (showOverview && hero == null) overviewAlpha.animateTo(1f, tween(220))
    }

    // The overview stays up empty, like Safari's; its Done goes.
    LaunchedEffect(session == null, showOverview) {
        if (session == null && !showOverview) onDismiss()
    }

    Box(modifier.fillMaxSize()) {
        if (session != null) {
            BrowserPageScreen(
                modifier = if (showOverview) Modifier.clearAndSetSemantics { } else Modifier,
                session = session,
                pages = pages,
                pageCount = openPages.size,
                currentSpace = currentSpace,
                allSpaces = allSpaces,
                browserRepository = browserRepository,
                onPageFrame = { pageFrame = it },
                onOpenOverview = { openOverview(session) },
                onDismiss = {
                    pages.leaveBrowser()
                    onDismiss()
                },
            )
        }

        if (showOverview) {
            // Back leaves the overview, not the page underneath.
            BackHandler {
                pages.active?.let { active ->
                    pages.store.page(active.id)?.let(::closeOverview)
                } ?: scope.launch {
                    overviewAlpha.animateTo(0f, tween(200))
                    showOverview = false
                }
            }
            Box(Modifier.graphicsLayer { alpha = overviewAlpha.value }) {
                OpenPagesOverview(
                    pages = pages,
                    openPages = openPages.filter { it.id != excludedCardId },
                    activeId = pages.active?.id.takeIf { excludedCardId == null },
                    spaces = allSpaces,
                    saveKind = if (browserRepository.cachedSnapshot?.normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED) {
                        browserRepository.saveKind()
                    } else {
                        SaveKind.PINNED
                    },
                    hiddenCardId = hiddenCardId,
                    onCardFrame = { id, frame -> cardFrames[id] = frame },
                    onOpen = ::closeOverview,
                    onNewPage = {
                        // The grid stays as it was while it fades: no card joins it on the way out.
                        excludedCardId = pages.open(null).id
                        scope.launch {
                            overviewAlpha.animateTo(0f, tween(200))
                            showOverview = false
                            excludedCardId = null
                        }
                    },
                    onDone = {
                        val active = pages.active?.let { pages.store.page(it.id) }
                        if (active != null) {
                            closeOverview(active)
                        } else {
                            scope.launch {
                                overviewAlpha.animateTo(0f, tween(200))
                                showOverview = false
                            }
                        }
                    },
                    showsDone = session != null && excludedCardId == null,
                    onLeave = {
                        if (pages.active != null) {
                            pages.leaveBrowser()
                            onDismiss()
                        } else {
                            // Over home nothing is open behind it, only the overview goes.
                            scope.launch {
                                overviewAlpha.animateTo(0f, tween(200))
                                showOverview = false
                            }
                        }
                    },
                    onPin = { page, destination ->
                        scope.launch {
                            val kind = if (browserRepository.cachedSnapshot?.normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED) {
                                browserRepository.saveKind()
                            } else {
                                SaveKind.PINNED
                            }
                            try {
                                // Leaving the browser must not cut the write off.
                                withContext(NonCancellable) {
                                    browserRepository.addTab(
                                        url = page.url,
                                        title = PageTitle.headline(page.title, page.url),
                                        spaceId = destination.spaceId,
                                        folderId = if (kind == SaveKind.NORMAL) null else destination.folderId,
                                        kind = kind,
                                    )
                                }
                                Haptics.perform(context, Haptics.Kind.CONFIRM)
                                pages.close(page.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                pinErrorRes = FriendlyError.messageRes(e)
                            }
                        }
                    },
                    onShare = { page -> shareUrl(context, page.url, page.title) },
                )
            }
        }

        pinErrorRes?.let { message ->
            AlertDialog(
                onDismissRequest = { pinErrorRes = null },
                title = { Text(stringResource(R.string.browser_pin_failed)) },
                text = { Text(stringResource(message)) },
                confirmButton = {
                    TextButton(onClick = { pinErrorRes = null }) { Text(stringResource(R.string.common_done)) }
                },
            )
        }

        hero?.let { flying ->
            val density = LocalDensity.current
            val progress = heroProgress.value
            val frame = lerp(flying.card, pageFrame, progress)
            Image(
                bitmap = flying.image,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                    .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                    .clip(RoundedCornerShape(18.dp * (1f - progress)))
                    .graphicsLayer { alpha = heroAlpha.value },
            )
        }
    }
}

/** The picture standing in for a page between the page and its card. */
private class OverviewHero(val image: ImageBitmap, val card: Rect)

private const val HeroMillis = 340

@SuppressLint("SetJavaScriptEnabled")
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BrowserPageScreen(
    session: PageSession,
    pages: BrowserPages,
    pageCount: Int,
    currentSpace: ZenSpaces.ZenSpace,
    allSpaces: List<ZenSpaces.ZenSpace>,
    browserRepository: BrowserRepository,
    /** Where the page sits in the window, for the zoom into the overview. */
    onPageFrame: (Rect) -> Unit,
    onOpenOverview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val viewModel = session.viewModel
    val webView = session.webView
    val blocker = session.blocker
    val scope = rememberCoroutineScope()
    val pageSwipe = remember { Animatable(0f) }
    // At the end of a swipe the plate already shows the new page's color.
    var colorJumps by remember { mutableStateOf(false) }
    val shownSession by rememberUpdatedState(session)
    var paneWidth by remember { mutableIntStateOf(0) }
    val pageGapPx = with(LocalDensity.current) { PageGap.toPx() }
    /** The link a long press is asking about. */
    var linkMenu by remember { mutableStateOf<String?>(null) }
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

    // Another page came on screen: the chrome starts over for it.
    LaunchedEffect(session.id) {
        barHidden = false
        scroll.downRun = 0
        scroll.lastY = 0
    }

    val blockingSettings by ContentBlocking.settings.collectAsState()
    val consentBundle = remember { ContentBlocking.consentScript(context) }
    val focusRequester = remember { FocusRequester() }

    // A new document, a failed load or a notice all mean the chrome must come
    // back: the scroll-driven hide only ever belongs to a page that scrolls.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            barHidden = false
            scroll.downRun = 0
        }
    }

    DisposableEffect(session.id) {
        onDispose { viewModel.commitPendingPinSave() }
    }

    // A new page goes straight into the address field.
    LaunchedEffect(session.id) {
        if (session.opensWithSearch) {
            session.opensWithSearch = false
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

    /**
     * A new page nothing was typed into ends, and the page before it comes
     * back. The last one closes the browser.
     */
    fun closeIfStillEmpty() {
        if (state.currentUrl.isNotEmpty() || session.startUrl.isNotEmpty()) return
        when {
            // The way back runs the way it came: the page before slides in, live.
            pages.store.neighbours().first != null -> scope.launch {
                pages.prepareNeighbours()
                pageSwipe.animateTo(paneWidth + pageGapPx, tween(300, easing = FastOutSlowInEasing))
                colorJumps = true
                pages.close(session.id)
                pageSwipe.snapTo(0f)
                withFrameNanos { }
                withFrameNanos { }
                colorJumps = false
            }
            pageCount > 1 -> pages.close(session.id)
            else -> onDismiss()
        }
    }

    // Back handling
    BackHandler {
        when {
            state.findVisible -> viewModel.closeFind()
            state.readerArticle != null -> viewModel.closeReader()
            // The site's switches are a page inside the menu: back returns to
            // the menu's own page, not out of the browser.
            state.menuSiteSettings -> viewModel.closeSiteSettings()
            state.menuExpanded -> viewModel.collapseMenu()
            else -> when (viewModel.onBackPressed(webView?.canGoBack() == true)) {
                MiniBrowserViewModel.BackAction.Handled -> Unit
                MiniBrowserViewModel.BackAction.ClearAddressFocus -> {
                    focusManager.clearFocus()
                    closeIfStillEmpty()
                }
                MiniBrowserViewModel.BackAction.GoBack -> webView?.goBack()
                MiniBrowserViewModel.BackAction.Dismiss ->
                    // A page a link opened ends, and its opener comes back.
                    if (pages.canReturnToOpener(session.id)) pages.close(session.id) else onDismiss()
            }
        }
    }

    val effectiveUrl = state.failedUrl.takeIf { state.pageError != null && it.isNotEmpty() }
        ?: webView?.url
        ?: state.currentUrl.ifEmpty { session.startUrl }

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
        animationSpec = if (colorJumps) snap() else tween(300),
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

    // The page's color as-is, so the browser starts on the app's paper; the
    // plates that sit on it are nudged a touch for contrast.
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

    val openPages by pages.store.pages.collectAsState()
    val pageInfo = openPages.firstOrNull { it.id == session.id }
    val index = openPages.indexOfFirst { it.id == session.id }
    val previousPage = openPages.getOrNull(index - 1)
    val nextPage = openPages.getOrNull(index + 1)
    // Past the last page there is a new one, as in Safari. Not from a page
    // that is itself still empty.
    val offersNewPage = nextPage == null && effectiveUrl.isNotEmpty()
    // The page and the status bar are one plate here, so it blends from this
    // page's own color into the one the swipe is heading for. No nudge: the
    // background is the app's paper, as in Settings, not a beige version of it.
    val previousPageColor = previousPage?.let { pages.session(it.id) }?.let { peekPageColor(it, defaultSurface) }
    val nextPageColor = nextPage?.let { pages.session(it.id) }?.let { peekPageColor(it, defaultSurface) }
        ?: defaultSurface
    val livePageColor = {
        val moved = pageSwipe.value
        val span = paneWidth + pageGapPx
        val heading = if (moved < 0) nextPageColor else previousPageColor
        if (moved == 0f || span <= 0f || heading == null) {
            animatedBgColor
        } else {
            lerp(animatedBgColor, heading, (abs(moved) / span).coerceIn(0f, 1f))
        }
    }

    val swipe = PageSwipe(
        offset = { pageSwipe.value },
        span = { paneWidth + pageGapPx },
        previous = previousPage?.let { peekBar(pages, it) },
        next = nextPage?.let { peekBar(pages, it) } ?: if (offersNewPage) PeekBar(null, "") else null,
        onStart = { pages.prepareNeighbours() },
        onDrag = { amount ->
            scope.launch {
                val next = pageSwipe.value + amount
                val hasTarget = if (next < 0) nextPage != null || offersNewPage else previousPage != null
                // Page and finger move as one. With nothing that way the page
                // gives a little and comes back.
                pageSwipe.snapTo(if (hasTarget) next else pageSwipe.value + amount * 0.2f)
            }
        },
        onEnd = { velocity ->
            scope.launch {
                val span = paneWidth + pageGapPx
                val moved = pageSwipe.value
                val thrown = abs(velocity) > 1200f && (velocity < 0) == (moved < 0)
                val leaving = abs(moved) > span * 0.5f || thrown
                val target = if (moved < 0) nextPage else previousPage
                if (leaving && (target != null || (moved < 0 && offersNewPage))) {
                    Haptics.perform(context, Haptics.Kind.SELECTION)
                    pageSwipe.animateTo(
                        if (moved < 0) -span else span,
                        PageSpring,
                        initialVelocity = velocity,
                    )
                    colorJumps = true
                    val arriving = if (target != null) {
                        pages.activate(target.id)
                        target.id
                    } else {
                        pages.open(null).id
                    }
                    // The old page only comes off the swipe once the new one
                    // has taken over, its color included. Any earlier and the
                    // old page is back on screen for a moment.
                    while (shownSession.id != arriving) withFrameNanos { }
                    withFrameNanos { }
                    pageSwipe.snapTo(0f)
                    withFrameNanos { }
                    withFrameNanos { }
                    colorJumps = false
                } else {
                    pageSwipe.animateTo(0f, PageSpring, initialVelocity = velocity)
                }
            }
        },
    )

    Surface(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(livePageColor()) },
        color = Color.Transparent,
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
            // The composer goes back down on the keyboard it came up on.
            val keyboard = WindowInsets.ime
            val density = LocalDensity.current
            var keyboardCarriesBar by remember { mutableStateOf(false) }
            LaunchedEffect(state.isAddressFocused) {
                if (state.isAddressFocused) {
                    keyboardCarriesBar = true
                } else {
                    snapshotFlow { keyboard.getBottom(density) }.first { it == 0 }
                    keyboardCarriesBar = false
                }
            }
            val ridesKeyboard = state.isAddressFocused || keyboardCarriesBar
            Column(Modifier.fillMaxSize()) {
            // Page pane: the page ends where the collapsed bar begins. The open
            // menu draws over the page instead of pushing it up.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { paneWidth = it.width }
                    .onGloballyPositioned { onPageFrame(it.boundsInRoot()) }
                    .clipToBounds(),
            ) {
                PageStack(
                    pages = pages,
                    active = session,
                    offset = { pageSwipe.value },
                    gap = pageGapPx,
                    offersNewPage = offersNewPage,
                    blockingSettings = blockingSettings,
                    consentBundle = consentBundle,
                    onActiveScroll = onPageScroll,
                    onActivePageStarted = {
                        barHidden = false
                        scroll.downRun = 0
                        scroll.lastY = 0
                    },
                    onLinkLongPress = { linkMenu = it },
                )

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
                                    if (pages.canReturnToOpener(session.id)) pages.close(session.id) else onDismiss()
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
                    .then(if (ridesKeyboard) Modifier.imePadding() else Modifier),
            ) {
                ZenTheme(darkTheme = barIsDark) {
                    MiniBrowserBar(
                        state = state,
                        viewModel = viewModel,
                        onClose = onDismiss,
                        pageCount = pageCount,
                        onOpenPages = onOpenOverview,
                        canReturnToOpener = pages.canReturnToOpener(session.id),
                        onReturnToOpener = { pages.close(session.id) },
                        showsKeep = pages.keepsOnlyChosenPages(),
                        isKept = pageInfo?.isKept == true,
                        canKeep = pageInfo?.let { pages.store.canKeep(it, state.pageError != null) } == true,
                        onToggleKeep = { pages.toggleKeep(session.id) },
                        swipe = swipe,
                        effectiveUrl = effectiveUrl,
                        addressText = state.addressText,
                        onAddressTextChange = viewModel::onAddressTextChange,
                        focusRequester = focusRequester,
                        ridesKeyboard = ridesKeyboard,
                        onAddressFocusedChange = viewModel::onAddressFocusChange,
                        onBeginAddressEdit = viewModel::beginAddressEdit,
                        onCancelAddressEdit = {
                            viewModel.endAddressEdit()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            closeIfStillEmpty()
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
                                session.reloadScroll = view.scrollY
                                view.reload()
                            }
                        },
                        onStop = { webView?.stopLoading() },
                        onCopyLink = {
                            if (effectiveUrl.isNotEmpty()) {
                                copyLink(context, effectiveUrl)
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
                                    session.blockingReload++
                                }
                                BlockingCard.ShowingOriginal -> {
                                    blocker.blockAgain()
                                    viewModel.onShowingOriginalChange(false)
                                    viewModel.collapseMenu()
                                    session.blockingReload++
                                }
                                is BlockingCard.OffForSite -> viewModel.openSiteSettings()
                                BlockingCard.Hidden -> Unit
                            }
                        },
                        blockingSettings = blockingSettings,
                        blockingHost = pageHost(effectiveUrl),
                        onSiteBlockingChange = { category, on ->
                            pageHost(effectiveUrl)?.let { host ->
                                ContentBlocking.update(blockingSettings.withSite(category, host, on))
                                // The page reloads so the new switches apply.
                                session.blockingReload++
                            }
                        },
                        barColor = barColor,
                        liveBarColor = livePageColor,
                    )
                }
            }

            if (imeVisible && !ridesKeyboard) {
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

    linkMenu?.let { url ->
        LinkMenu(
            url = url,
            onOpenInNewPage = {
                linkMenu = null
                pages.capture(session)
                pages.open(url, openerId = session.id)
            },
            onCopy = {
                linkMenu = null
                copyLink(context, url)
            },
            onShare = {
                linkMenu = null
                shareUrl(context, url, "")
            },
            onDismiss = { linkMenu = null },
        )
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

// MARK: - Pages

/**
 * Pages settle on this. It ends once they stop visibly moving: the spring's
 * own threshold keeps it going for half a second after that, with the bar
 * waiting on it.
 */
private val PageSpring = spring<Float>(stiffness = 500f, visibilityThreshold = 3f)

/** The plate color a page brings with it. */
private fun peekColor(session: PageSession, surface: Color): Color {
    val state = session.viewModel.state.value
    return (state.siteThemeColor.takeIf { state.pageError == null } ?: surface).nudgedForBar()
}

/** The same color with no nudge, for the background the page sits on. */
private fun peekPageColor(session: PageSession, surface: Color): Color {
    val state = session.viewModel.state.value
    return state.siteThemeColor.takeIf { state.pageError == null } ?: surface
}

private fun peekBar(pages: BrowserPages, page: OpenPage): PeekBar {
    val state = pages.session(page.id)?.viewModel?.state?.value
    return PeekBar(
        url = page.url,
        title = page.title,
        canGoBack = state?.canGoBack == true || page.openerId != null,
        canGoForward = state?.canGoForward == true,
    )
}

/**
 * Every live page's WebView, one above the other. The page on screen sits in
 * place and its neighbours right beside it, so a swipe moves real pages. They
 * all stay in the same composition: finishing a swipe only changes who sits
 * where, so a page never reloads and nothing flashes.
 */
@Composable
private fun PageStack(
    pages: BrowserPages,
    active: PageSession,
    offset: () -> Float,
    gap: Float,
    offersNewPage: Boolean,
    blockingSettings: ContentBlockingSettings,
    consentBundle: String?,
    onActiveScroll: (Int) -> Unit,
    onActivePageStarted: () -> Unit,
    onLinkLongPress: (String) -> Unit,
) {
    val openPages by pages.store.pages.collectAsState()
    val index = openPages.indexOfFirst { it.id == active.id }
    val previousId = openPages.getOrNull(index - 1)?.id
    val nextId = openPages.getOrNull(index + 1)?.id
    val currentActive by rememberUpdatedState(active)
    // A blank page is the bare plate, in the app's paper: the same background
    // the Settings sheet uses, not a darker version of it.
    val surface = MaterialTheme.colorScheme.surface
    val pageCorners = RoundedCornerShape(28.dp)

    // What waits past the last page: not a page yet, so it does not pretend to
    // be one. A blank sheet grows up as the finger pulls it in.
    if (offersNewPage) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val step = size.width + gap
                    val progress = (-offset() / step).coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * progress
                    scaleY = scaleX
                    translationX = (1f - progress) * size.width * 0.3f
                    alpha = if (offset() < 0f) 0.45f + 0.55f * progress else 0f
                    shape = pageCorners
                    clip = true
                }
                .background(surface),
        )
    }

    for (session in pages.live) {
        key(session.id) {
            val viewModel = session.viewModel
            val state by viewModel.state.collectAsState()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val step = size.width + gap
                        translationX = when (session.id) {
                            active.id -> offset()
                            previousId -> offset() - step
                            nextId -> offset() + step
                            // Kept alive, out of sight.
                            else -> size.width * 3
                        }
                        // Rounded like the screen while it slides off towards a new page.
                        val leaving = offersNewPage && session.id == active.id && offset() < 0f
                        shape = if (leaving) pageCorners else RectangleShape
                        clip = leaving
                    },
            ) {
                if (session !== active) {
                    session.shot?.let { shot ->
                        Image(
                            bitmap = shot,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                key(session.webViewGeneration) {
                    MiniBrowserWebView(
                        // After a crash it waits for Reload instead.
                        initialUrl = if (state.pageError == BrowserPageError.CRASHED) {
                            null
                        } else {
                            state.currentUrl.ifEmpty { session.startUrl }.ifEmpty { null }
                        },
                        isAddressFocused = state.isAddressFocused,
                        desktopSite = state.requestsDesktopSite,
                        pageZoom = state.pageZoom,
                        savedHistory = { session.history.also { session.history = null } },
                        takePopup = { session.pendingPopup.also { session.pendingPopup = null } },
                        onWebViewCreated = { session.webView = it },
                        onWebViewReleased = { pages.webViewReleased(session, it) },
                        onOpenPopup = { message ->
                            pages.capture(session)
                            pages.open(null, openerId = session.id, popup = message)
                        },
                        onCloseWindow = { pages.close(session.id) },
                        onLinkLongPress = onLinkLongPress,
                        onLoadingChange = { loading ->
                            viewModel.onLoadingChange(loading)
                            if (!loading) {
                                session.reloadScroll?.let { position ->
                                    session.reloadScroll = null
                                    // `scrollTo` clamps to the new page height
                                    // itself, so a shorter document is safe.
                                    session.webView?.post { session.webView?.scrollTo(0, position) }
                                }
                                if (session === currentActive) pages.capture(session)
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
                            viewModel.onShowingOriginalChange(session.blocker.showingOriginal)
                            viewModel.clearPageError()
                            if (session === currentActive) onActivePageStarted()
                        },
                        onLoadFailed = viewModel::onPageLoadFailed,
                        onRendererGone = { url ->
                            viewModel.onPageLoadFailed(BrowserPageError.CRASHED, url ?: state.currentUrl)
                            viewModel.onLoadingChange(false)
                            session.webView = null
                            session.webViewGeneration++
                        },
                        onLinkUnavailable = viewModel::onLinkUnavailable,
                        onScrollChange = { if (session === currentActive) onActiveScroll(it) },
                        onFindResult = { current, total ->
                            viewModel.setFindProgress(if (total > 0) "$current/$total" else null)
                        },
                        blocker = session.blocker,
                        consentBundle = consentBundle,
                        blockingSettings = blockingSettings,
                        blockingReload = session.blockingReload,
                    )
                }
            }
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
            .background(zen.background)
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
                cursorBrush = SolidColor(zen.ink),
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

internal fun shareUrl(context: Context, url: String, title: String) {
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
