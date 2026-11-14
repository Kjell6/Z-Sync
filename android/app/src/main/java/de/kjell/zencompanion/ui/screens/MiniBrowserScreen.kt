package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.kjell.zencompanion.R
import de.kjell.zencompanion.util.FriendlyError
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserRepository
import de.kjell.zencompanion.ui.browser.BrowserDownload
import de.kjell.zencompanion.ui.browser.BrowserDownloadCentre
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.browser.DownloadFailure
import de.kjell.zencompanion.ui.browser.DownloadState
import de.kjell.zencompanion.ui.browser.DownloadSummary
import de.kjell.zencompanion.ui.browser.DownloadTask
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.PageSession
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.ui.browser.downloadPercent
import de.kjell.zencompanion.ui.browser.downloadReferrer
import de.kjell.zencompanion.ui.browser.downloadSummary
import de.kjell.zencompanion.data.BrowserVisit
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
    /** Signed out the browser is the same, without the pin path. */
    signedIn: Boolean,
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
    /** The browser's own history is up. It belongs to the browser, not to a
     *  page: the overview opened from home has no page behind it. */
    var historyVisible by rememberSaveable { mutableStateOf(false) }
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
    val density = LocalDensity.current
    val statusBars = WindowInsets.statusBars
    val bottomBand = with(density) {
        pageBottomBand(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).toPx()
    }
    var screen by remember { mutableStateOf(Size.Zero) }

    /** How far the deck has come in: 0 is only the page, 1 is the whole deck. */
    val overviewReveal = remember { Animatable(0f) }
    /** The deck's cards above the page's own, once it has landed. */
    val aheadReveal = remember { Animatable(1f) }
    /** The live page between full screen and its card. */
    val poseX = remember { Animatable(0f) }
    val poseY = remember { Animatable(0f) }
    val poseScale = remember { Animatable(1f) }
    /** How much of the strip under the page has been cut away. */
    val poseBandCut = remember { Animatable(0f) }
    val chromeAlpha = remember { Animatable(if (startsInOverview) 0f else 1f) }
    /** How far the tapped card has grown back into its page. */
    val cardExpansion = remember { Animatable(0f) }
    var overviewControls by remember { mutableStateOf(showOverview) }
    /** The page is above the deck, moving with the finger or landing on its card. */
    var pageFloats by remember { mutableStateOf(false) }
    /** The pull has let go for the overview; the bar takes no new one. */
    var committingPull by remember { mutableStateOf(false) }
    var pullPassedPoint by remember { mutableStateOf(false) }
    /** Why pinning a page from the overview failed. */
    var pinErrorRes by remember { mutableStateOf<Int?>(null) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var plusMenuOpen by remember { mutableStateOf(false) }
    /** The card the page stands in for; it shows once the page lands. */
    var hiddenCardId by remember { mutableStateOf<String?>(null) }
    var expandingCardId by remember { mutableStateOf<String?>(null) }
    /** A page that must not join the deck while it goes. */
    var excludedCardId by remember { mutableStateOf<String?>(null) }
    /** A new deck each time the page leaves for it, with the order fresh. */
    var deckGeneration by remember { mutableIntStateOf(0) }
    /** Every move of the overview replaces the one before; only the last one finishes. */
    val move = remember { arrayOfNulls<Job>(1) }

    fun transition(block: suspend CoroutineScope.() -> Unit) {
        move[0]?.cancel()
        move[0] = scope.launch(block = block)
    }

    val deckCardCorner = rememberDeckCardCornerPx()
    var floatCorner by remember { mutableStateOf(0f) }

    fun activeDeckIndex(): Int {
        val ordered = pages.store.deckOrder()
        return ordered.indexOfFirst { it.id == pages.active?.id }.takeIf { it >= 0 }
            ?: (ordered.size - 1).coerceAtLeast(0)
    }

    /** Where the page has to be to lie exactly on its card. */
    fun cardPose(): PageCardPose? {
        if (screen.width <= 0f || screen.height <= 0f) return null
        val deck = PageDeckGeometry(screen, pages.store.pages.value.size, bottomBand)
        val place = activeDeckIndex().toFloat()
        val card = deck.frame(place, place)
        val scale = card.width / screen.width
        return PageCardPose(
            x = card.center.x - screen.width / 2f,
            // The screen's bottom edge lies a scaled strip below the card's.
            y = card.bottom + bottomBand * scale - screen.height,
            scale = scale,
            corner = deckCardCorner / scale,
        )
    }

    fun settleFloatingPage() {
        scope.launch {
            poseX.snapTo(0f)
            poseY.snapTo(0f)
            poseScale.snapTo(1f)
            poseBandCut.snapTo(0f)
            overviewReveal.snapTo(1f)
            aheadReveal.snapTo(1f)
            chromeAlpha.snapTo(0f)
        }
        hiddenCardId = null
        pageFloats = false
        committingPull = false
    }

    /** The finger took hold of the page: the deck goes in behind it right away. */
    fun beginPagePull() {
        val page = pages.active ?: return
        if (committingPull) return
        move[0]?.cancel()
        pullPassedPoint = false
        // A card may still be growing into this page, and that deck has its
        // focus and order from before. A page coming back keeps its deck.
        if (!pageFloats) deckGeneration++
        expandingCardId = null
        excludedCardId = null
        scope.launch {
            cardExpansion.snapTo(0f)
            aheadReveal.snapTo(if (activeDeckIndex() < pages.store.pages.value.size - 1) 0f else 1f)
        }
        page.viewModel.collapseMenu()
        hiddenCardId = page.id
        cardPose()?.let { floatCorner = it.corner }
        pageFloats = true
        showOverview = true
        pages.capture(page)
    }

    /** The page shrinks under the finger, bottom edge with the finger, the deck coming in around it. */
    fun updatePagePull(translation: Offset) {
        if (!pageFloats || committingPull) return
        val card = cardPose() ?: return
        val travel = max(screen.height * 0.32f, 1f)
        val progress = (-translation.y / travel).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress).pow(1.7f)
        val appearing = ((progress - 0.03f) / 0.52f).coerceIn(0f, 1f)
        scope.launch {
            poseX.snapTo(translation.x * 0.6f)
            poseY.snapTo(min(0f, translation.y))
            poseScale.snapTo(1f - (1f - card.scale) * eased)
            poseBandCut.snapTo(bottomBand * eased)
            overviewReveal.snapTo(appearing * appearing * (3f - 2f * appearing))
            chromeAlpha.snapTo(1f - ((progress - 0.1f) / 0.3f).coerceIn(0f, 1f))
        }
        val passed = progress > PullCommitPoint
        if (passed != pullPassedPoint) {
            pullPassedPoint = passed
            if (passed) Haptics.perform(context, Haptics.Kind.SELECTION)
        }
    }

    /** The page lands on its card at the finger's speed; the card takes over once it is there. */
    fun landOnCard(velocity: Float) {
        val card = cardPose()
        if (card == null) {
            settleFloatingPage()
            showOverview = true
            overviewControls = true
            return
        }
        committingPull = true
        transition {
            launch {
                chromeAlpha.animateTo(0f, tween(120))
                overviewControls = true
            }
            val spec = spring<Float>(stiffness = 300f, visibilityThreshold = 0.5f)
            listOf(
                launch { poseX.animateTo(card.x, spec) },
                launch { poseY.animateTo(card.y, spec, initialVelocity = velocity) },
                launch { poseScale.animateTo(card.scale, spring(stiffness = 300f, visibilityThreshold = 0.001f)) },
                launch { poseBandCut.animateTo(bottomBand, spec) },
                launch { overviewReveal.animateTo(1f, spring(stiffness = 300f, visibilityThreshold = 0.002f)) },
            ).joinAll()
            hiddenCardId = null
            pageFloats = false
            committingPull = false
            poseX.snapTo(0f)
            poseY.snapTo(0f)
            poseScale.snapTo(1f)
            poseBandCut.snapTo(0f)
            aheadReveal.animateTo(1f, spring(stiffness = 400f, visibilityThreshold = 0.002f))
        }
    }

    /** The pull was too short, or came back down: the page takes the screen again. */
    fun returnPage() {
        committingPull = false
        overviewControls = false
        excludedCardId = null
        transition {
            val spec = spring<Float>(stiffness = 400f, visibilityThreshold = 0.5f)
            listOf(
                launch { poseX.animateTo(0f, spec) },
                launch { poseY.animateTo(0f, spec) },
                launch { poseScale.animateTo(1f, spring(stiffness = 400f, visibilityThreshold = 0.001f)) },
                launch { poseBandCut.animateTo(0f, spec) },
                launch { overviewReveal.animateTo(0f, spring(stiffness = 400f, visibilityThreshold = 0.002f)) },
                launch { chromeAlpha.animateTo(1f, tween(180)) },
            ).joinAll()
            showOverview = false
            pageFloats = false
            hiddenCardId = null
            aheadReveal.snapTo(1f)
        }
    }

    fun endPagePull(end: PagePullEnd) {
        if (!pageFloats || committingPull) return
        val travel = max(screen.height * 0.32f, 1f)
        val progress = -end.translation.y / travel
        val opens = !end.cancelled && end.velocity < 750f &&
            (progress > PullCommitPoint || end.velocity < -1050f)
        if (opens) landOnCard(end.velocity) else returnPage()
    }

    /** The dock's button takes the same way the finger does. */
    fun openOverview() {
        beginPagePull()
        scope.launch {
            // The deck has to be on screen for its way in to animate.
            withFrameNanos { }
            withFrameNanos { }
            if (pageFloats && !committingPull) landOnCard(0f)
        }
    }

    fun fadeOverviewOut() {
        overviewControls = false
        transition {
            overviewReveal.animateTo(0f, tween(200))
            showOverview = false
            chromeAlpha.snapTo(1f)
        }
    }

    /**
     * A page that was asleep has an empty WebView at first, and taking the card
     * away would flash it. The card stays until the page has loaded, briefly.
     */
    suspend fun awaitFirstPaint(page: PageSession) {
        var sawLoading = false
        repeat(80) {
            val state = page.viewModel.state.value
            if (state.isLoading) sawLoading = true
            if (state.progress >= 0.9f || (sawLoading && !state.isLoading)) return
            delay(50)
        }
    }

    /**
     * The tapped card grows to the whole screen inside the deck, so the cards
     * lying on it run off over it. The live page takes over under the finished
     * card: a cut, the card shows this very page.
     */
    fun closeOverview(page: OpenPage) {
        if (expandingCardId != null) return
        if (pageFloats) {
            // Tapped while the page was still landing.
            if (page.id == pages.active?.id) {
                returnPage()
                return
            }
            settleFloatingPage()
        }
        val target = pages.session(page.id)
        val wasAsleep = target?.webView == null
        // Chosen here, it is the current page at once. The deck's order still
        // holds for a moment, so going straight back to the deck — or swiping
        // from the page — finds the tab where it was, not already at the front.
        pages.beginSwitching()
        pages.activate(page.id)
        pages.finishSwitching(afterMs = BrowserPages.CHOSEN_SETTLE_MS)
        overviewControls = false
        expandingCardId = page.id
        transition {
            launch { overviewReveal.animateTo(0f, DeckOpenSpring) }
            cardExpansion.animateTo(1f, DeckOpenSpring)
            if (wasAsleep && target != null) awaitFirstPaint(target)
            showOverview = false
            expandingCardId = null
            cardExpansion.snapTo(0f)
            // The bar sits under the deck until now.
            chromeAlpha.animateTo(1f, tween(180))
        }
    }

    /**
     * A blank sheet comes in from the right and grows to the whole screen while
     * the deck parts around it. The address field follows it in.
     */
    fun openNewPage() {
        pages.releaseSwitchingOrder()
        val arriving = pages.open(null)
        // Not under the deck: the field and the keyboard come once it is nearly here.
        arriving.opensWithSearch = false
        // The deck stays as it was while it goes: no card joins it on the way out.
        excludedCardId = arriving.id
        overviewControls = false
        val count = pages.store.pages.value.size
        val last = (count - 1).coerceAtLeast(0).toFloat()
        val card = PageDeckGeometry(screen, count, bottomBand).frame(last, last - 1f)
        val scale = if (screen.width > 0f) card.width / screen.width else 1f
        floatCorner = deckCardCorner / scale
        pageFloats = true
        transition {
            poseX.snapTo(card.center.x - screen.width / 2f)
            poseY.snapTo(card.bottom + bottomBand * scale - screen.height)
            poseScale.snapTo(scale)
            poseBandCut.snapTo(bottomBand)
            chromeAlpha.snapTo(0f)
            // Building the WebView holds the main thread; the move starts after it.
            var waited = 0
            while (arriving.webView == null && waited++ < 40) withFrameNanos { }
            withFrameNanos { }
            val spec = spring<Float>(stiffness = 300f, visibilityThreshold = 0.5f)
            listOf(
                launch { poseX.animateTo(0f, spec) },
                launch { poseY.animateTo(0f, spec) },
                launch { poseScale.animateTo(1f, spring(stiffness = 300f, visibilityThreshold = 0.001f)) },
                launch { poseBandCut.animateTo(0f, spec) },
                launch { overviewReveal.animateTo(0f, spring(stiffness = 300f, visibilityThreshold = 0.002f)) },
            ).joinAll()
            pageFloats = false
            showOverview = false
            excludedCardId = null
            // Only now: the keyboard would stop the move dead in the middle.
            arriving.viewModel.beginAddressEdit()
            // The bar turns into the field first, out of sight.
            delay(BarMorphMs.toLong())
            chromeAlpha.animateTo(1f, tween(200))
        }
    }

    /** From home the overview fades in over the spaces; a recreated screen just comes back. */
    LaunchedEffect(Unit) {
        if (showOverview) overviewReveal.animateTo(1f, tween(220))
    }

    // The overview stays up empty, like Safari's; its Done goes.
    LaunchedEffect(session == null, showOverview) {
        if (session == null && !showOverview) onDismiss()
    }

    /**
     * A page from the browser's history: the page on screen takes it, or a new
     * one when the overview came up without a page.
     */
    fun openHistoryVisit(visit: BrowserVisit) {
        // A page whose renderer died has no WebView to load into.
        val active = pages.active
        val view = active?.webView
        val page = if (active != null && view != null) {
            view.loadUrl(visit.url)
            pages.store.page(active.id)
        } else {
            pages.store.page(pages.open(visit.url).id)
        }
        if (showOverview && page != null) closeOverview(page)
    }

    // Over the deck the status bar goes by the app's background, not the page's.
    val deckIsDark = LocalZenColors.current.background.luminance() < 0.5f
    val systemBarsView = LocalView.current
    DisposableEffect(systemBarsView, overviewControls, deckIsDark) {
        val window = (systemBarsView.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, systemBarsView) }
        val lightStatusBars = bars?.isAppearanceLightStatusBars
        val lightNavigationBars = bars?.isAppearanceLightNavigationBars
        if (overviewControls) {
            bars?.isAppearanceLightStatusBars = !deckIsDark
            bars?.isAppearanceLightNavigationBars = !deckIsDark
        }
        onDispose {
            if (overviewControls && bars != null && lightStatusBars != null && lightNavigationBars != null) {
                bars.isAppearanceLightStatusBars = lightStatusBars
                bars.isAppearanceLightNavigationBars = lightNavigationBars
            }
        }
    }

    val saveKind = if (signedIn &&
        browserRepository.cachedSnapshot?.normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED
    ) {
        browserRepository.saveKind()
    } else {
        SaveKind.PINNED
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) },
    ) {
        // Nothing of the screen behind the browser shows around a flying page:
        // the page's own color lies under it, as it does on iOS.
        if (pageFloats && session != null) {
            val floating by session.viewModel.state.collectAsState()
            val under = floating.siteThemeColor.takeIf { floating.pageError == null } ?: MaterialTheme.colorScheme.surface
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(-1f)
                    .background(under),
            )
        }

        if (session != null) {
            BrowserPageScreen(
                modifier = Modifier
                    .zIndex(if (pageFloats) 2f else 0f)
                    .graphicsLayer {
                        if (pageFloats) {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            scaleX = poseScale.value
                            scaleY = poseScale.value
                            translationX = poseX.value
                            translationY = poseY.value
                            shape = PageCardClip(floatCorner, poseBandCut.value)
                            clip = true
                            // The same shadow the card has, there from the
                            // moment the page leaves the screen's edges.
                            shadowElevation = 7.dp.toPx() / poseScale.value * overviewReveal.value
                            ambientShadowColor = Color.Black.copy(alpha = 0.3f)
                            spotShadowColor = Color.Black.copy(alpha = 0.3f)
                        }
                    }
                    .then(if (showOverview) Modifier.clearAndSetSemantics { } else Modifier),
                session = session,
                pages = pages,
                signedIn = signedIn,
                pageCount = openPages.size,
                currentSpace = currentSpace,
                allSpaces = allSpaces,
                browserRepository = browserRepository,
                chromeAlpha = { chromeAlpha.value },
                // A new page flies in on its own color, not on its way there.
                colorArrives = excludedCardId != null,
                onPullStart = ::beginPagePull,
                onPullChange = ::updatePagePull,
                onPullEnd = ::endPagePull,
                onOpenOverview = ::openOverview,
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
                } ?: fadeOverviewOut()
            }
            key(deckGeneration) {
                Box(Modifier.zIndex(1f)) {
                    OpenPagesOverview(
                        pages = pages,
                        openPages = openPages,
                        activeId = pages.active?.id.takeIf { excludedCardId == null },
                        signedIn = signedIn,
                        spaces = allSpaces,
                        saveKind = saveKind,
                        hiddenCardId = hiddenCardId,
                        expandingCardId = expandingCardId,
                        expansion = { cardExpansion.value },
                        excludedCardId = excludedCardId,
                        reveal = { overviewReveal.value },
                        aheadReveal = { aheadReveal.value },
                        topInsetPx = statusBars.getTop(density).toFloat(),
                        bottomBandPx = bottomBand,
                        onOpen = ::closeOverview,
                        onPin = { page, destination ->
                            scope.launch {
                                try {
                                    // Leaving the browser must not cut the write off.
                                    withContext(NonCancellable) {
                                        browserRepository.addTab(
                                            url = page.url,
                                            title = PageTitle.headline(page.title, page.url),
                                            spaceId = destination.spaceId,
                                            folderId = if (saveKind == SaveKind.NORMAL) null else destination.folderId,
                                            kind = saveKind,
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
        }

        // A tap on the page while it lands takes it back.
        if (committingPull) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2.5f)
                    .pointerInput(Unit) { detectTapGestures { returnPage() } },
            )
        }

        val dockAlpha by animateFloatAsState(
            targetValue = if (overviewControls) 1f else 0f,
            animationSpec = tween(200),
            label = "overviewDock",
        )
        if (showOverview && dockAlpha > 0f) {
            OverviewDock(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(3f)
                    .graphicsLayer { alpha = dockAlpha },
                onOpenHistory = { historyVisible = true },
                onNewPage = ::openNewPage,
                onPlusLongPress = { if (openPages.isNotEmpty()) plusMenuOpen = true },
                onLeave = {
                    if (pages.active != null) {
                        pages.leaveBrowser()
                        onDismiss()
                    } else {
                        // Over home nothing is open behind it, only the overview goes.
                        fadeOverviewOut()
                    }
                },
                plusMenu = {
                    DropdownMenu(expanded = plusMenuOpen, onDismissRequest = { plusMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kept_close_all), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                plusMenuOpen = false
                                confirmCloseAll = true
                            },
                        )
                    }
                },
            )
        }

        if (confirmCloseAll) {
            AlertDialog(
                onDismissRequest = { confirmCloseAll = false },
                title = { Text(stringResource(R.string.kept_close_all_title)) },
                text = { Text(stringResource(R.string.kept_close_all_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmCloseAll = false
                        pages.closeAll()
                    }) {
                        Text(stringResource(R.string.kept_close_all), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCloseAll = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
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
    }

    if (historyVisible) {
        BrowserHistoryScreen(
            onOpen = { visit ->
                historyVisible = false
                openHistoryVisit(visit)
            },
            onClose = { historyVisible = false },
        )
    }
}

/** The floating page's outline: rounded, and short of the strip under the page. */
private class PageCardClip(private val radius: Float, private val bottomCut: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(0f, 0f, size.width, size.height - bottomCut, CornerRadius(radius)),
        )
}

/** The live page between full screen and its card, scaled about the bottom edge. */
private class PageCardPose(val x: Float, val y: Float, val scale: Float, val corner: Float)

private const val PullCommitPoint = 0.16f

@SuppressLint("SetJavaScriptEnabled")
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BrowserPageScreen(
    session: PageSession,
    pages: BrowserPages,
    /** Signed out the bar has no pin control. */
    signedIn: Boolean,
    pageCount: Int,
    currentSpace: ZenSpaces.ZenSpace,
    allSpaces: List<ZenSpaces.ZenSpace>,
    browserRepository: BrowserRepository,
    /** The bar's opacity: it goes as the page becomes a card. */
    chromeAlpha: () -> Float,
    /** The page's color is there at once, with no blend from the page before. */
    colorArrives: Boolean,
    onPullStart: () -> Unit,
    onPullChange: (Offset) -> Unit,
    onPullEnd: (PagePullEnd) -> Unit,
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
    /** The link or image a long press is asking about. */
    var hitMenu by remember { mutableStateOf<BrowserHitMenu?>(null) }
    // One set for the screen: a page's file bridge and its download must match.
    val requests = rememberBrowserPageRequests(pages)
    // Keyed: a collector carried over to another page reads the old page's
    // state for its first frames, and the bar would show that page's colors.
    val state by key(session.id) { viewModel.state.collectAsState() }
    // The browser's downloads, not this page's: a popup that started a file
    // may be gone while the capsule still reports it here.
    val downloadTasks by BrowserDownloadCentre.tasks.collectAsState()

    // Scroll-driven bar: tucks away on the way down, returns on the way up.
    var barHidden by remember { mutableStateOf(false) }
    // Written on every scroll packet; a state write would recompose the screen.
    val scroll = remember { ScrollTracker() }
    val onPageScroll: (Int, Boolean) -> Unit = { scrollY, userDriven ->
        val step = barScrollStep(
            delta = scrollY - scroll.lastY,
            scrollY = scrollY,
            downRun = scroll.downRun,
            userDriven = userDriven,
        )
        scroll.lastY = scrollY
        scroll.downRun = step.downRun
        when (step.decision) {
            BarScrollDecision.KEEP -> Unit
            BarScrollDecision.SHOW -> barHidden = false
            BarScrollDecision.HIDE -> barHidden = true
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
                val before = pages.store.neighbours().first
                pages.prepareNeighbours()
                pageSwipe.animateTo(paneWidth + pageGapPx, tween(300, easing = FastOutSlowInEasing))
                colorJumps = true
                // The page that slid in stays; closing alone would pick by list.
                before?.let { pages.activate(it.id) }
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

    /**
     * Reload the page, remembering where the reader was: the reloaded document
     * may otherwise land somewhere else. The bar's button and a page pull share
     * this path, and a fresh load takes the error panel away with it.
     */
    fun reloadPage() {
        webView?.let { view ->
            session.reloadScroll = view.scrollY
            view.reload()
        }
    }

    val defaultSurface = MaterialTheme.colorScheme.surface
    // The error panel sits on the surface color. The failed load leaves the
    // previous page's color behind, so the bar ignores it while it shows.
    val targetBg = state.siteThemeColor.takeIf { state.pageError == null } ?: defaultSurface
    val animatedBgColor by animateColorAsState(
        targetValue = targetBg,
        animationSpec = if (colorJumps || colorArrives) snap() else tween(300),
        label = "browserThemeColor",
    )
    // The animation answers a frame late, and a page switch has no frame to
    // spare: the plate would go back to the old page's color for a moment.
    val shownBgColor = if (colorJumps || colorArrives) targetBg else animatedBgColor

    // The bar stays put while a sheet, a reader, the find bar or a banner is up.
    val barVisible = !barHidden ||
        state.isAddressFocused ||
        state.menuExpanded ||
        state.showPinBanner ||
        state.notice != null ||
        downloadTasks.isNotEmpty() ||
        state.findVisible ||
        state.readerArticle != null ||
        state.pageError != null

    // The page's color as-is, so the browser starts on the app's paper; the
    // plates that sit on it are nudged a touch for contrast.
    val barColor = shownBgColor.nudgedForBar()
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
    // The same neighbours the deck shows this page between.
    val switchingOrder by pages.store.switchingOrder.collectAsState()
    val (previousPage, nextPage) = remember(openPages, switchingOrder, session.id) { pages.store.neighbours() }
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
            shownBgColor
        } else {
            lerp(shownBgColor, heading, (abs(moved) / span).coerceIn(0f, 1f))
        }
    }

    val swipe = PageSwipe(
        offset = { pageSwipe.value },
        span = { paneWidth + pageGapPx },
        previous = previousPage?.let { peekBar(pages, it) },
        next = nextPage?.let { peekBar(pages, it) } ?: if (offersNewPage) PeekBar(null, "") else null,
        onStart = {
            pages.beginSwitching()
            pages.prepareNeighbours()
        },
        onPullStart = onPullStart,
        onPullChange = onPullChange,
        onPullEnd = onPullEnd,
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
            pages.finishSwitching()
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
                    if (target != null) {
                        // No frame between these: the page is already where it
                        // belongs, and with the swipe still applied both pages
                        // would sit off screen.
                        pages.activate(target.id)
                    } else {
                        // A new page has yet to be composed. The old one only
                        // comes off the swipe once it has taken over, its color
                        // included, or it is back on screen for a moment.
                        val arriving = pages.open(null).id
                        while (shownSession.id != arriving) withFrameNanos { }
                        withFrameNanos { }
                    }
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
                    .clipToBounds(),
            ) {
                PageStack(
                    pages = pages,
                    active = session,
                    requests = requests,
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
                    onHitLongPress = { hitMenu = it },
                    onPullRefresh = ::reloadPage,
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
                    ZenTheme(darkTheme = barIsDark, jumps = colorJumps) {
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

                // The browser's downloads, above the bar. The capsule rides
                // over the pin banner so the two never cover each other.
                val capsuleLift by animateDpAsState(
                    targetValue = if (state.showPinBanner) 64.dp else 0.dp,
                    label = "downloadCapsuleLift",
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = downloadTasks.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp + capsuleLift, start = 20.dp, end = 20.dp),
                ) {
                    DownloadCapsule(tasks = downloadTasks)
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
                    .graphicsLayer { alpha = chromeAlpha() }
                    // Only the address composer rides the keyboard. A page
                    // field must not push the whole bar up into the page.
                    .then(if (ridesKeyboard) Modifier.imePadding() else Modifier),
            ) {
                ZenTheme(darkTheme = barIsDark, jumps = colorJumps) {
                    MiniBrowserBar(
                        state = state,
                        viewModel = viewModel,
                        signedIn = signedIn,
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
                        onPickVisit = { visit ->
                            openAddressTarget(viewModel.selectVisit(visit))
                            finishAddressEdit()
                        },
                        // A page that failed to load has nothing worth pinning.
                        canPin = signedIn && allSpaces.isNotEmpty() && state.pageError == null,
                        // A page that is not showing has nothing to print.
                        canPrint = webView != null &&
                            effectiveUrl.isNotEmpty() &&
                            effectiveUrl != "about:blank" &&
                            state.pageError == null &&
                            state.readerArticle == null,
                        onPrint = {
                            val view = webView
                            if (view != null) {
                                // The menu goes for the system's own sheet.
                                viewModel.collapseMenu()
                                val jobName = state.currentTitle.takeIf { it.isNotBlank() }
                                    ?: addressDisplay(effectiveUrl)
                                context.activity()?.getSystemService(PrintManager::class.java)
                                    ?.print(jobName, view.createPrintDocumentAdapter(jobName), null)
                            }
                        },
                        onBack = { webView?.goBack() },
                        onForward = { webView?.goForward() },
                        onReload = ::reloadPage,
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

    hitMenu?.let { menu ->
        LinkMenu(
            menu = menu,
            onOpenInNewPage = {
                hitMenu = null
                menu.link?.let { link ->
                    pages.capture(session)
                    pages.open(link, openerId = session.id)
                }
            },
            onCopy = {
                hitMenu = null
                menu.link?.let { copyLink(context, it) }
            },
            onShare = {
                hitMenu = null
                menu.link?.let { shareUrl(context, it, "") }
            },
            onSaveImage = {
                hitMenu = null
                val image = menu.image
                val view = webView
                if (image != null && view != null) {
                    // Chosen by the reader, so it goes straight to Downloads with
                    // the same notices a page's own download gets.
                    requests.forSession(session).download(
                        view,
                        BrowserDownload(
                            url = image,
                            userAgent = view.settings.userAgentString,
                            contentDisposition = null,
                            mimeType = null,
                            referer = downloadReferrer(effectiveUrl, image),
                        ),
                    )
                }
            },
            onOpenImageInNewPage = {
                hitMenu = null
                menu.image?.let { image ->
                    pages.capture(session)
                    pages.open(image, openerId = session.id)
                }
            },
            onCopyImage = {
                hitMenu = null
                menu.image?.let { copyLink(context, it) }
            },
            onDismiss = { hitMenu = null },
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
    /** The screen's page requests, shared with the long-press menu outside the stack. */
    requests: BrowserPageRequests,
    offset: () -> Float,
    gap: Float,
    offersNewPage: Boolean,
    blockingSettings: ContentBlockingSettings,
    consentBundle: String?,
    onActiveScroll: (scrollY: Int, userDriven: Boolean) -> Unit,
    onActivePageStarted: () -> Unit,
    onHitLongPress: (BrowserHitMenu) -> Unit,
    onPullRefresh: () -> Unit,
) {
    val openPages by pages.store.pages.collectAsState()
    val switchingOrder by pages.store.switchingOrder.collectAsState()
    val (previous, next) = remember(openPages, switchingOrder, active.id) { pages.store.neighbours() }
    val previousId = previous?.id
    val nextId = next?.id
    val currentActive by rememberUpdatedState(active)
    BrowserPromptDialogs(active, requests)
    // A video on the whole display takes Back first. Composed here, inside the
    // screen's later part, so it outranks the browser's own back handling.
    BackHandler(enabled = requests.isFullscreen) { requests.exitFullscreen() }
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
                        pageThemeColor = state.siteThemeColor,
                        savedHistory = { pages.takeHistory(session) },
                        takePopup = { session.pendingPopup.also { session.pendingPopup = null } },
                        onWebViewCreated = { session.webView = it },
                        onWebViewReleased = { pages.webViewReleased(session, it) },
                        onOpenPopup = { message ->
                            pages.capture(session)
                            pages.open(null, openerId = session.id, popup = message)
                        },
                        onCloseWindow = { pages.close(session.id) },
                        onHitLongPress = onHitLongPress,
                        onPageLoaded = { pages.pageLoaded(session) },
                        onPullRefresh = onPullRefresh,
                        // A sheet, the reader, find or an error panel owns the
                        // gestures over the page, and so does a full-screen video.
                        pullToRefreshEnabled = !state.isAddressFocused &&
                            !state.findVisible &&
                            state.readerArticle == null &&
                            state.pageError == null &&
                            !requests.isFullscreen,
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
                        onCurrentUrlChange = { url ->
                            viewModel.onCurrentUrlChange(url)
                            pages.pageAddressChanged(session)
                        },
                        onCanGoBackChange = viewModel::onCanGoBackChange,
                        onCanGoForwardChange = viewModel::onCanGoForwardChange,
                        onTextInputChange = viewModel::onAddressTextChange,
                        onTitleChange = { title ->
                            viewModel.onCurrentTitleChange(title)
                            pages.pageTitleChanged(session)
                        },
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
                        requests = remember(session.id) { requests.forSession(session) },
                        onScrollChange = { scrollY, userDriven ->
                            if (session === currentActive) onActiveScroll(scrollY, userDriven)
                        },
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

/**
 * The browser's downloads: a file still arriving with its bar and its cancel,
 * one waiting for the save screen, or what came of it. It shows on whichever
 * page is in front, wherever the file came from.
 */
@Composable
private fun DownloadCapsule(tasks: List<DownloadTask>) {
    val summary = downloadSummary(tasks) ?: return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = downloadTitle(summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    val fraction = summary.fraction
                    if (summary.state == DownloadState.Downloading && fraction != null) {
                        Text(
                            text = stringResource(
                                R.string.browser_download_percent,
                                downloadPercent(fraction) ?: 0,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (summary.state) {
                    DownloadState.Downloading -> {
                        val fraction = summary.fraction
                        if (fraction == null) {
                            // The server named no size: a bar would be a guess.
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            DownloadBar(fraction)
                        }
                    }
                    is DownloadState.Failed -> Text(
                        text = downloadFailureText(summary.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    else -> Unit
                }
            }
            if (summary.state == DownloadState.Downloading) {
                IconButton(
                    onClick = { summary.ids.forEach(BrowserDownloadCentre::cancel) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.browser_download_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** A thin bar that fills as the bytes arrive. */
@Composable
private fun DownloadBar(fraction: Float) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun downloadTitle(summary: DownloadSummary): String {
    val single = summary.count == 1
    return when (summary.state) {
        DownloadState.Downloading -> if (single) {
            stringResource(R.string.browser_download_started, summary.name)
        } else {
            stringResource(R.string.browser_download_started_many, summary.count)
        }
        DownloadState.ChoosingLocation -> if (single) {
            stringResource(R.string.browser_download_choose, summary.name)
        } else {
            stringResource(R.string.browser_download_choose_many, summary.count)
        }
        DownloadState.Saved -> if (single) {
            stringResource(R.string.browser_download_finished, summary.name)
        } else {
            stringResource(R.string.browser_download_finished_many, summary.count)
        }
        is DownloadState.Failed -> if (single) {
            stringResource(R.string.browser_download_failed, summary.name)
        } else {
            stringResource(R.string.browser_download_failed_many, summary.count)
        }
    }
}

@Composable
private fun downloadFailureText(reason: DownloadFailure?): String = when (reason) {
    DownloadFailure.NETWORK -> stringResource(R.string.browser_download_reason_network)
    DownloadFailure.STORAGE -> stringResource(R.string.browser_download_reason_storage)
    DownloadFailure.SAVE, null -> stringResource(R.string.browser_download_reason_save)
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
            // The step buttons take the platform's 48 dp; half the air above
            // and below them keeps the strip at the height it had at 36 dp.
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
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
    // The platform's least touch target; the 20 dp glyph stays as it is.
    Box(
        modifier = Modifier
            .size(48.dp)
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
