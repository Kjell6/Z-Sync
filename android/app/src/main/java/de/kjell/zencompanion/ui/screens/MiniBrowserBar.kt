package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BrowserVisit
import de.kjell.zencompanion.data.SitePermission
import de.kjell.zencompanion.data.SitePermissionChoice
import de.kjell.zencompanion.data.SitePermissions
import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.blockingSiteKey
import de.kjell.zencompanion.ui.browser.AddressSuggestion
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.motion.rememberReducedMotion
import de.kjell.zencompanion.ui.sheets.icon
import de.kjell.zencompanion.ui.sheets.title
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenLoadingIndicator
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenMenuGroupGap
import de.kjell.zencompanion.ui.theme.ZenMenuItem
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.ui.theme.rowPressFeedback
import kotlinx.coroutines.flow.collectLatest

/**
 * The bar sits on the page's own color, nudged away from it so the plate is
 * visible: a touch darker on light colors, a touch lighter on dark ones.
 */
internal fun Color.nudgedForBar(amount: Float = 0.08f): Color {
    val target = if (luminance() < 0.5f) 1f else 0f
    return Color(
        red = red + (target - red) * amount,
        green = green + (target - green) * amount,
        blue = blue + (target - blue) * amount,
        alpha = alpha,
    )
}

// MARK: - Metrics

/** A solid surface, not glass: the platform has no Liquid Glass, so the bar is
 * a hard pane that sits on the page. */
private val DockShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(16.dp)

private val BarHeight = 44.dp
private val DockSide = 44.dp
private val CloseWidth = 92.dp
private val CloseHeight = 42.dp
private val TileHeight = 60.dp
private val RowGap = 8.dp

/** The bar turning into the composer, and the dock fading as it does. */
internal const val BarMorphMs = 160
/** How far the dock drops into the gesture area: the safe area is not touched,
 * only the slack above it is used up. */
private val BottomLift = 15.dp
/** Least air between the dock and the screen edge: enough to clear the gesture
 *  area, not so much that the bar floats. */
private val MinBottomGap = 16.dp

/** Air below the dock for a given navigation-bar inset. */
internal fun barBottomGap(navigationInset: Dp): Dp =
    (navigationInset - BottomLift).coerceAtLeast(MinBottomGap)
/** Air above the first row, so the bar background does not end flush with it.
 *  Small on purpose: the band belongs to the page's last line, not to the bar. */
private val TopGap = 6.dp
/** Pulls the outer dock buttons inwards, like the iOS chrome. */
private val DockInset = 7.dp

/**
 * Bottom browser chrome (port of iOS `MiniBrowserChrome`), bottom to top:
 *
 *   [pages] [x] [⋯]   the open pages, the way out, the menu
 *   [navigation bar]  back, forward, favicon, title, reload
 *   [menu panel]      only while the menu is open: it grows above the bar
 *
 * Unlike iOS the surfaces are solid: no backdrop to blur at the page's edge
 * would read as glass anyway. The bar is wrapped in `ZenTheme(darkTheme =
 * isDark)` by the screen, so every color here follows the page rather than the
 * system theme.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MiniBrowserBar(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    /** Signed out there is no pin control and no save confirmation. */
    signedIn: Boolean,
    onClose: () -> Unit,
    /** Open pages on this phone; the dock's button shows the count. */
    pageCount: Int,
    onOpenPages: () -> Unit,
    /** The page has no history left, but a link opened it: Back returns there. */
    canReturnToOpener: Boolean,
    onReturnToOpener: () -> Unit,
    /** With "Keep only chosen pages" on, the menu offers Keep. */
    showsKeep: Boolean,
    isKept: Boolean,
    canKeep: Boolean,
    onToggleKeep: () -> Unit,
    swipe: PageSwipe,
    effectiveUrl: String,
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    /** The composer is on the keyboard, on its way up or down. */
    ridesKeyboard: Boolean,
    onAddressFocusedChange: (Boolean) -> Unit,
    onBeginAddressEdit: () -> Unit,
    onCancelAddressEdit: () -> Unit,
    onSubmitAddress: (String) -> Unit,
    /** False while a page error covers the page: there is nothing to pin. */
    canPin: Boolean = true,
    /** False while no page is showing, or the reader covers it. */
    canPrint: Boolean,
    onPrint: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onPickVisit: (BrowserVisit) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
    /** What the card at the top of the menu says. */
    blockingCard: BlockingCard,
    onBlockingCard: () -> Unit,
    /** The site's own switches, on the menu's second page. */
    blockingSettings: ContentBlockingSettings,
    blockingHost: String?,
    onSiteBlockingChange: (BlockCategory, Boolean) -> Unit,
    barColor: Color,
    /** The page's own color this frame: it follows a swipe between pages. */
    liveBarColor: () -> Color = { barColor },
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current

    val motion = MaterialTheme.motionScheme
    Column(modifier = modifier.fillMaxWidth()) {
        // The open menu is its own layer: it floats over the page, above the
        // bar, and leaves the bar and the page where they are.
        AnimatedVisibility(
            visible = state.menuExpanded,
            // Shares the column with the bar, which is measured first, so a
            // tall menu scrolls instead of pushing the bar off the screen.
            modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn(motion.fastEffectsSpec()) +
                scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f, transformOrigin = MenuGrowOrigin),
            exit = fadeOut(motion.fastEffectsSpec()) +
                scaleOut(motion.fastSpatialSpec(), targetScale = 0.92f, transformOrigin = MenuGrowOrigin),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = RowGap),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
                border = liftBorder(),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                        // One menu, two pages: the tools, and the site's own switches.
                        // The swap happens inside the same menu, so Site Settings never
                        // opens as a second sheet on top of it. Every page is a set of
                        // Expressive menu groups (MenuChrome), the same system the rest
                        // of the app's menus use.
                        AnimatedContent(
                            targetState = state.menuSiteSettings && blockingHost != null,
                            transitionSpec = {
                                fadeIn(tween(MenuPageFadeMs)) togetherWith fadeOut(tween(MenuPageFadeMs))
                            },
                            label = "menuPage",
                        ) { showSiteSettings ->
                            if (showSiteSettings && blockingHost != null) {
                                SiteSettingsMenuPage(
                                    host = blockingHost,
                                    settings = blockingSettings,
                                    onBack = viewModel::closeSiteSettings,
                                    onChange = onSiteBlockingChange,
                                )
                            } else {
                                ExpandedMenu(
                                    state = state,
                                    viewModel = viewModel,
                                    showsKeep = showsKeep,
                                    isKept = isKept,
                                    canKeep = canKeep,
                                    onToggleKeep = onToggleKeep,
                                    signedIn = signedIn,
                                    canPin = canPin,
                                    canPrint = canPrint,
                                    onPrint = onPrint,
                                    onCopyLink = onCopyLink,
                                    onFind = onFind,
                                    onShare = onShare,
                                    onOpenExternal = onOpenExternal,
                                    onPin = onPin,
                                    blockingCard = blockingCard,
                                    onBlockingCard = onBlockingCard,
                                )
                            }
                        }
                }
            }
        }

        // Part of the address editing state: the list floats over the page
        // above the composer, like the menu, and goes away with it. It keeps
        // each row's identity, so an answer that arrives only adds rows to
        // what is already on screen.
        val suggestionMs = if (rememberReducedMotion()) 0 else SuggestionMs
        AnimatedVisibility(
            visible = state.isAddressFocused && state.suggestionRows.isNotEmpty(),
            modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn(motion.fastEffectsSpec()) +
                scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f, transformOrigin = SuggestionGrowOrigin),
            exit = fadeOut(motion.fastEffectsSpec()) +
                scaleOut(motion.fastSpatialSpec(), targetScale = 0.92f, transformOrigin = SuggestionGrowOrigin),
        ) {
            SuggestionPanel(
                rows = state.suggestionRows,
                motionMs = suggestionMs,
                onSelectVisit = onPickVisit,
                onSelect = onPickSuggestion,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = RowGap),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The bar area has its own background already (the page ends above
                // it). Painting it here keeps the translucent tiles compositing
                // against that background instead of the page.
                .drawBehind { drawRect(liveBarColor()) }
                // The background starts above the address row: it should not end
                // hard against it.
                .padding(start = 16.dp, end = 16.dp, top = TopGap),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            // The bar turns into the composer in place.
            Crossfade(
                targetState = state.isAddressFocused,
                animationSpec = tween(BarMorphMs),
                label = "addressBar",
            ) { composing ->
                if (composing) {
                    ComposerBar(
                        addressText = addressText,
                        onAddressTextChange = onAddressTextChange,
                        focusRequester = focusRequester,
                        onAddressFocusedChange = onAddressFocusedChange,
                        onSubmitAddress = onSubmitAddress,
                        onCancel = onCancelAddressEdit,
                        barColor = barColor,
                    )
                } else {
                    SwipeableNavBar(swipe = swipe) {
                        NavBar(
                            canGoBack = state.canGoBack || canReturnToOpener,
                            canGoForward = state.canGoForward,
                            effectiveUrl = effectiveUrl,
                        pageFailed = state.pageError != null,
                            title = state.currentTitle,
                            isLoading = state.isLoading,
                            progress = state.progress,
                            onBack = if (state.canGoBack) onBack else onReturnToOpener,
                            onForward = onForward,
                            onReload = onReload,
                            onStop = onStop,
                            onBeginAddressEdit = onBeginAddressEdit,
                            barColor = barColor,
                        )
                    }
                }
            }

            val navigationInset = WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding()
            val keyboard = WindowInsets.ime
            val dockAlpha by animateFloatAsState(
                targetValue = if (state.isAddressFocused) 0f else 1f,
                animationSpec = tween(BarMorphMs),
                label = "dockAlpha",
            )
            // The dock's room stays when the composer takes over: the keyboard
            // uses it up as it rises, so the bar holds its place until the keys
            // reach it and goes up in one move, never down first. Once it rides
            // them, only a small gap keeps the row off the keys.
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val resting = (DockSide + RowGap + barBottomGap(navigationInset)).roundToPx()
                    val lifted = if (ridesKeyboard) keyboard.getBottom(this) else 0
                    val height = if (lifted > 0) maxOf(resting - lifted, 8.dp.roundToPx()) else resting
                    layout(constraints.maxWidth, height) { placeable.place(0, 0) }
                },
            ) {
                if (dockAlpha > 0f) {
                    Dock(
                        modifier = Modifier.graphicsLayer { alpha = dockAlpha },
                        menuOpen = state.menuExpanded,
                        ink = zen.ink,
                        pageCount = pageCount,
                        onOpenPages = onOpenPages,
                        onClose = onClose,
                        onToggleMenu = {
                            if (state.menuExpanded) viewModel.collapseMenu() else viewModel.toggleMenu()
                        },
                    )
                }
            }
        }
    }
}

/** The menu grows out of its button, at the bar's trailing end. */
private val MenuGrowOrigin = TransformOrigin(0.9f, 1f)

/** The address list grows up out of the composer. */
private val SuggestionGrowOrigin = TransformOrigin(0.5f, 1f)

/** How long the suggestion list takes to resize or fade a row in. */
private const val SuggestionMs = 260

/**
 * A pane in the bar: same fill and hairline as the dock buttons, no shadow.
 */
@Composable
private fun Modifier.pane(shape: Shape, color: Color): Modifier = this
    .clip(shape)
    .background(color)
    .border(liftBorder(), shape)

/** Height of the chrome's rows, without the air below the dock. */
internal fun pageBottomBand(navigationInset: Dp): Dp =
    CollapsedBarHeightBase + barBottomGap(navigationInset)

internal val CollapsedBarHeightBase: Dp =
    TopGap + BarHeight + RowGap + DockSide

// MARK: - Swipe between pages

/** What the address bar needs for the swipe between pages. */
internal class PageSwipe(
    /** How far the page has moved, in pixels. */
    val offset: () -> Float,
    /** One page plus the gap: the distance a full swipe travels. */
    val span: () -> Float,
    val previous: PeekBar?,
    val next: PeekBar?,
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    /** The finger let go, at this speed in pixels per second. */
    val onEnd: (Float) -> Unit,
    /** A pull upwards on the bar: the page follows the finger from the first point on. */
    val onPullStart: () -> Unit = {},
    /** How far the finger has moved since the pull took over, in window pixels. */
    val onPullChange: (Offset) -> Unit = {},
    val onPullEnd: (PagePullEnd) -> Unit = {},
)

/** How a pull upwards on the address bar ended. */
internal class PagePullEnd(
    val translation: Offset,
    /** Pixels per second; negative is upwards. */
    val velocity: Float,
    /** The system took the finger away. */
    val cancelled: Boolean,
)

/** A neighbour's bar: what it shows when it is the real one. No address means the new page past the last one. */
internal data class PeekBar(
    val url: String?,
    val title: String,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/** The bars beside this one wait off the edges and follow the finger. */
@Composable
private fun SwipeableNavBar(swipe: PageSwipe, bar: @Composable () -> Unit) {
    val current by rememberUpdatedState(swipe)
    var barWidth by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { BarPeekGap.toPx() }
    fun barOffset(): Float {
        val span = current.span()
        return if (span > 0f) current.offset() * (barWidth + gap) / span else current.offset()
    }
    // The bar moves and shrinks under the finger during a pull, so the pull is
    // measured in window coordinates.
    val barCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { barWidth = it.width }
            .onGloballyPositioned { barCoordinates[0] = it }
            .pointerInput(Unit) {
                fun inWindow(position: Offset): Offset =
                    barCoordinates[0]?.takeIf { it.isAttached }?.localToRoot(position) ?: position
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = Offset.Zero
                    val first = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        moved = over
                        change.consume()
                    } ?: return@awaitEachGesture
                    val pulling = moved.y < 0f && abs(moved.y) > abs(moved.x)
                    if (!pulling && abs(moved.x) <= abs(moved.y) * 1.2f) return@awaitEachGesture
                    val velocity = VelocityTracker()
                    if (pulling) {
                        val origin = inWindow(first.position)
                        velocity.addPosition(first.uptimeMillis, origin)
                        current.onPullStart()
                        var last = Offset.Zero
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                current.onPullEnd(PagePullEnd(last, 0f, cancelled = true))
                                break
                            }
                            if (!change.pressed) {
                                current.onPullEnd(PagePullEnd(last, velocity.calculateVelocity().y, cancelled = false))
                                break
                            }
                            change.consume()
                            val here = inWindow(change.position)
                            velocity.addPosition(change.uptimeMillis, here)
                            last = here - origin
                            current.onPullChange(last)
                        }
                    } else {
                        velocity.addPosition(first.uptimeMillis, first.position)
                        current.onStart()
                        current.onDrag(moved.x * current.span() / (barWidth + gap).coerceAtLeast(1f))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                current.onEnd(0f)
                                break
                            }
                            if (!change.pressed) {
                                current.onEnd(velocity.calculateVelocity().x)
                                break
                            }
                            val amount = change.positionChange().x
                            change.consume()
                            velocity.addPosition(change.uptimeMillis, change.position)
                            current.onDrag(amount * current.span() / (barWidth + gap).coerceAtLeast(1f))
                        }
                    }
                }
            },
    ) {
        swipe.previous?.let { peek ->
            PeekNavBar(
                peek,
                Modifier.graphicsLayer {
                    translationX = barOffset() - (barWidth + gap)
                    alpha = if (current.offset() == 0f) 0f else 1f
                },
            )
        }
        swipe.next?.let { peek ->
            if (peek.url == null) {
                NewPagePill(
                    width = { (-barOffset() - gap).coerceAtLeast(0f) },
                    start = { barOffset() + barWidth + gap },
                )
            } else {
                PeekNavBar(
                    peek,
                    Modifier.graphicsLayer {
                        translationX = barOffset() + barWidth + gap
                        alpha = if (current.offset() == 0f) 0f else 1f
                    },
                )
            }
        }
        Box(Modifier.graphicsLayer { translationX = barOffset() }) { bar() }
    }
}

/**
 * Past the last page there is nothing to show an address for: a pill opens in
 * the space the bar leaves behind, as wide as the finger has pulled it.
 */
@Composable
private fun NewPagePill(width: () -> Float, start: () -> Float) {
    val zen = LocalZenColors.current
    val icon = with(LocalDensity.current) { 20.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .graphicsLayer { translationX = start() }
            .drawBehind {
                val radius = CornerRadius(20.dp.toPx())
                drawRoundRect(zen.lift, size = Size(width(), size.height), cornerRadius = radius)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = zen.ink.copy(alpha = 0.8f),
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    translationX = (width() - icon) / 2
                    alpha = (width() / (90.dp.toPx())).coerceIn(0f, 1f)
                },
        )
    }
}

/**
 * The whole bar of the page coming in, drawn by the same code as the real one:
 * when the swipe ends, the real bar takes over without a visible change.
 */
@Composable
private fun PeekNavBar(peek: PeekBar, modifier: Modifier) {
    val zen = LocalZenColors.current
    Box(modifier) {
        NavBar(
            canGoBack = peek.canGoBack,
            canGoForward = peek.canGoForward,
            effectiveUrl = peek.url.orEmpty(),
            title = peek.title,
            isLoading = false,
            progress = 0f,
            onBack = {},
            onForward = {},
            onReload = {},
            onStop = {},
            onBeginAddressEdit = {},
            barColor = zen.lift,
        )
    }
}

/** Space between two bars while they slide past each other. */
private val BarPeekGap = 12.dp

/** The page count in a rounded square, the way Safari shows open tabs. */
@Composable
internal fun PageCountBadge(count: Int, color: Color) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 23.dp, minHeight = 23.dp)
            .border(2.dp, color, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = ZenType.rounded(13, FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

// MARK: - Navigation bar

@Composable
private fun NavBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    effectiveUrl: String,
    /** An error page is shown: no connection to vouch for. */
    pageFailed: Boolean = false,
    title: String,
    isLoading: Boolean,
    progress: Float,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onBeginAddressEdit: () -> Unit,
    barColor: Color,
) {
    val zen = LocalZenColors.current
    // However the load ends, the fill first reaches the end of the bar and
    // only then fades away.
    val fillWidth = remember { Animatable(0f) }
    val fillAlpha = remember { Animatable(0f) }
    val currentProgress by rememberUpdatedState(progress)
    LaunchedEffect(isLoading) {
        if (isLoading) {
            fillWidth.snapTo(0f)
            fillAlpha.snapTo(1f)
            snapshotFlow { currentProgress }.collectLatest { next ->
                if (next > fillWidth.value) fillWidth.animateTo(next, tween(250))
            }
        } else if (fillAlpha.value > 0f) {
            fillWidth.animateTo(1f, tween(280))
            fillAlpha.animateTo(0f, tween(300))
            fillWidth.snapTo(0f)
        }
    }
    Box(modifier = Modifier.fillMaxWidth().pane(DockShape, zen.lift)) {
        // Loading fills the whole bar from the left, behind its controls.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = fillAlpha.value }
                .drawBehind {
                    drawRect(zen.ink.copy(alpha = 0.1f), size = size.copy(width = size.width * fillWidth.value))
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavGlyph(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.browser_back),
                enabled = canGoBack,
                onClick = onBack,
            )
            NavGlyph(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                description = stringResource(R.string.browser_forward),
                enabled = canGoForward,
                onClick = onForward,
            )

            Surface(
                onClick = onBeginAddressEdit,
                modifier = Modifier
                    .weight(1f)
                    .height(BarHeight - 12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteIcon(effectiveUrl = effectiveUrl, pageFailed = pageFailed)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // Same fallback as the iOS bar: a page without a title -
                        // still loading, or one that failed to load - is named by
                        // its address instead of an empty placeholder.
                        text = when {
                            title.isNotBlank() -> title
                            effectiveUrl.isNotEmpty() -> addressDisplay(effectiveUrl)
                            else -> stringResource(R.string.browser_search_placeholder)
                        },
                        style = ZenType.rounded(16, FontWeight.SemiBold),
                        color = zen.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            NavGlyph(
                icon = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                description = stringResource(
                    if (isLoading) R.string.browser_stop else R.string.browser_reload,
                ),
                enabled = true,
                onClick = if (isLoading) onStop else onReload,
            )
        }
    }
}

/** Icon-only glyph for the navigation bar: no chrome of its own. */
@Composable
private fun NavGlyph(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(10.dp)),
        modifier = Modifier.size(width = 34.dp, height = BarHeight - 12.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = zen.ink.copy(alpha = 0.8f),
            disabledContentColor = zen.ink.copy(alpha = 0.3f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Dock

@Composable
private fun Dock(
    modifier: Modifier = Modifier,
    menuOpen: Boolean,
    ink: Color,
    pageCount: Int,
    onOpenPages: () -> Unit,
    onClose: () -> Unit,
    onToggleMenu: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DockInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val zen = LocalZenColors.current
        val pagesDescription = pluralStringResource(R.plurals.browser_page_count, pageCount, pageCount)
        Surface(
            onClick = onOpenPages,
            modifier = Modifier
                .size(DockSide)
                .semantics { contentDescription = pagesDescription },
            shape = DockShape,
            color = zen.lift,
            border = liftBorder(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PageCountBadge(count = pageCount, color = ink.copy(alpha = 0.8f))
            }
        }

        Spacer(Modifier.weight(1f))

        LeaveBrowserButton(ink = ink, onClick = onClose)

        Spacer(Modifier.weight(1f))

        DockButton(
            icon = Icons.Outlined.MoreHoriz,
            description = stringResource(if (menuOpen) R.string.browser_close_menu else R.string.browser_more),
            tint = ink,
            onClick = onToggleMenu,
        )
    }
}

/** The way out of the browser. */
@Composable
private fun LeaveBrowserButton(ink: Color, onClick: () -> Unit) {
    DockButton(
        icon = Icons.Outlined.Close,
        description = stringResource(R.string.browser_close),
        tint = ink,
        width = CloseWidth,
        height = CloseHeight,
        onClick = onClick,
    )
}

/**
 * The overview's controls, drawn exactly where the browser's dock has its own.
 * A long press on the plus closes everything.
 */
@Composable
internal fun OverviewDock(
    onNewPage: () -> Unit,
    onOpenHistory: () -> Unit,
    onPlusLongPress: () -> Unit,
    onLeave: () -> Unit,
    plusMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current
    val navigationInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp + DockInset,
                end = 16.dp + DockInset,
                bottom = barBottomGap(navigationInset) + RowGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(
            icon = Icons.Outlined.History,
            description = stringResource(R.string.history_title),
            tint = zen.ink,
            onClick = onOpenHistory,
        )
        Spacer(Modifier.weight(1f))
        DockButton(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.browser_close),
            tint = zen.ink,
            width = CloseWidth,
            height = CloseHeight,
            onClick = onLeave,
        )
        Spacer(Modifier.weight(1f))
        Box {
            DockButton(
                icon = Icons.Outlined.Add,
                description = stringResource(R.string.kept_new_page),
                tint = zen.ink,
                onLongClick = onPlusLongPress,
                // The same label the custom action carries: a long press is
                // invisible to TalkBack, so the action has to be listed.
                onLongClickLabel = stringResource(R.string.kept_close_all_label),
                onClick = onNewPage,
            )
            plusMenu()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    width: Dp = DockSide,
    height: Dp = DockSide,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (onLongClick == null) {
        // A native Material surface: the theme's ripple and press motion.
        Surface(
            onClick = onClick,
            modifier = Modifier.size(width, height),
            shape = DockShape,
            color = zen.lift,
            border = liftBorder(),
            content = content,
        )
    } else {
        // The overview's plus also has a long press, which a Material surface
        // does not expose: the same look with the theme's press and ripple.
        val source = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(width, height)
                .rowPressFeedback(source)
                .clip(DockShape)
                .background(zen.lift)
                .border(liftBorder(), DockShape)
                .combinedClickable(
                    interactionSource = source,
                    indication = ripple(color = zen.ink),
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClickLabel,
                )
                // TalkBack only finds a long press by holding the finger
                // down. The same action as its own entry makes it reachable
                // from the actions list as well.
                .then(
                    if (onLongClickLabel != null) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(onLongClickLabel) {
                                    onLongClick()
                                    true
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// MARK: - Suggestions

/**
 * The address list, as a card floating over the page with the app's native
 * list rows, the same layer as the open menu.
 *
 * One list, keyed by what each row says: the model puts the pages from the
 * history first and appends the engine's phrases, so a row that stays is not
 * rebuilt when an answer changes the rows around it.
 */
@Composable
private fun SuggestionPanel(
    rows: List<AddressSuggestion>,
    motionMs: Int,
    onSelectVisit: (BrowserVisit) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // The card's own height follows the row count instead of jumping
            // when an answer brings a longer list.
            .animateContentSize(animationSpec = tween(motionMs)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = liftBorder(),
    ) {
        // The list can outgrow the space over the keyboard, so it scrolls
        // inside its own card instead of pushing the bar off the screen.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            rows.forEach { row ->
                key(row.id) {
                    SuggestionRowFade(tween(motionMs)) {
                        SuggestionRow(row = row, onSelectVisit = onSelectVisit, onSelect = onSelect)
                    }
                }
            }
        }
    }
}

/** A row fades in on its own, so a longer answer does not pop a row in whole. */
@Composable
private fun SuggestionRowFade(motion: FiniteAnimationSpec<Float>, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(visible = shown, enter = fadeIn(animationSpec = motion)) {
        content()
    }
}

/** A page prints its name over its address, a search phrase prints alone. */
@Composable
private fun SuggestionRow(
    row: AddressSuggestion,
    onSelectVisit: (BrowserVisit) -> Unit,
    onSelect: (String) -> Unit,
) {
    val visit = row.visit
    ZenListItem(
        headline = row.text,
        supportingText = visit?.displayAddress,
        singleLine = true,
        onClick = { if (visit != null) onSelectVisit(visit) else onSelect(row.text) },
        leadingContent = {
            Icon(
                imageVector = if (visit != null) Icons.Outlined.Schedule else Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

// MARK: - Composer

@Composable
private fun ComposerBar(
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onAddressFocusedChange: (Boolean) -> Unit,
    onSubmitAddress: (String) -> Unit,
    onCancel: () -> Unit,
    barColor: Color,
) {
    val zen = LocalZenColors.current
    // The composer is composed by the address state, so its field reports
    // `false` once before the screen's focus request lands. Only a loss after a
    // real gain ends the edit: tapping the page takes the keyboard away, and
    // the bar must close the editor the way Cancel does instead of staying an
    // editor without a keyboard.
    var hadFocus by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pane(DockShape, zen.lift)
            .padding(horizontal = 6.dp)
            .height(BarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: leave the field. Android browsers put the way back here, and
        // keep the trailing side for clearing the text.
        IconButton(
            onClick = onCancel,
            shapes = IconButtonDefaults.shapes(shape = CircleShape),
            modifier = Modifier.size(32.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor = zen.ink.copy(alpha = 0.8f),
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_cancel),
                modifier = Modifier.size(18.dp),
            )
        }
        BasicTextField(
            value = addressText,
            onValueChange = onAddressTextChange,
            singleLine = true,
            textStyle = ZenType.rounded(16, FontWeight.Medium).copy(color = zen.ink),
            cursorBrush = SolidColor(zen.ink),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmitAddress(addressText.text) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                // Only a focus *gain* is reported: the composer is composed by
                // the address state, so its first attach reports `false` and
                // would close the field again on the same frame. Closing is
                // explicit (Cancel, submit, back).
                .onFocusChanged { focus ->
                    if (focus.isFocused) {
                        hadFocus = true
                        onAddressFocusedChange(true)
                    } else if (hadFocus) {
                        // Losing the focus ends the edit; a submit that clears
                        // the focus itself has loaded the address already.
                        onAddressFocusedChange(false)
                    }
                },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (addressText.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browser_search_placeholder),
                            style = ZenType.rounded(16, FontWeight.Medium),
                            color = zen.ink.copy(alpha = 0.5f),
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (addressText.text.isNotEmpty()) {
            IconButton(
                onClick = { onAddressTextChange(TextFieldValue("", selection = TextRange.Zero)) },
                shapes = IconButtonDefaults.shapes(shape = CircleShape),
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = zen.ink.copy(alpha = 0.55f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cancel,
                    contentDescription = stringResource(R.string.browser_clear_search),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// MARK: - Menu

@Composable
private fun ExpandedMenu(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    signedIn: Boolean,
    showsKeep: Boolean,
    isKept: Boolean,
    canKeep: Boolean,
    onToggleKeep: () -> Unit,
    canPin: Boolean,
    canPrint: Boolean,
    onPrint: () -> Unit,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
    blockingCard: BlockingCard,
    onBlockingCard: () -> Unit,
) {
    val zen = LocalZenColors.current
    val showsBlocking = blockingCard != BlockingCard.Hidden
    // The groups round their own ends, so the count is the number of groups
    // actually drawn: the first and last read as the ends of the menu.
    val toolsGroup = if (showsBlocking) 1 else 0
    val siteGroup = toolsGroup + 1
    val groupCount = siteGroup + 1

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // At the top: the menu grows up from the bar, so this is the far end,
        // where Arc keeps the same status at the bottom of its sheet.
        if (showsBlocking) {
            ZenMenuGroup(index = 0, count = groupCount) {
                BlockingCardItem(card = blockingCard, onClick = onBlockingCard)
            }
            ZenMenuGroupGap()
        }

        // The page tools: one menu group, so the rows round off together and
        // morph under the finger like every other menu in the app.
        val toolCount = (if (showsKeep) 1 else 0) + 5
        val keepIndex = if (showsKeep) 0 else -1
        val copyIndex = if (showsKeep) 1 else 0
        val readerIndex = copyIndex + 1
        val printIndex = readerIndex + 1
        val desktopIndex = printIndex + 1
        val zoomIndex = desktopIndex + 1
        ZenMenuGroup(index = toolsGroup, count = groupCount) {
            if (showsKeep) {
                ZenMenuItem(
                    text = {
                        Text(stringResource(if (isKept) R.string.browser_kept else R.string.browser_keep_page))
                    },
                    onClick = onToggleKeep,
                    index = keepIndex,
                    count = toolCount,
                    leadingIcon = {
                        Icon(
                            imageVector = if (isKept) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                        )
                    },
                    enabled = canKeep,
                )
            }
            ZenMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (state.copyConfirmed) R.string.browser_copied else R.string.browser_copy_link,
                        ),
                    )
                },
                onClick = onCopyLink,
                index = copyIndex,
                count = toolCount,
                // Copied: the glyph turns into the checkmark the iOS menu
                // shows, beside the label that already says so.
                leadingIcon = {
                    Icon(
                        imageVector = if (state.copyConfirmed) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                },
            )
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_reader)) },
                onClick = viewModel::toggleReader,
                index = readerIndex,
                count = toolCount,
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                trailingContent = {
                    when {
                        state.readerLoading -> ZenLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        state.readerArticle != null -> Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                        )
                    }
                },
            )
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_print)) },
                onClick = onPrint,
                index = printIndex,
                count = toolCount,
                leadingIcon = { Icon(Icons.Outlined.Print, contentDescription = null) },
                // Nothing to print without a page, under the error panel or
                // with the reader lifted out of it.
                enabled = canPrint,
            )
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_desktop_site)) },
                onClick = viewModel::toggleDesktopSite,
                index = desktopIndex,
                count = toolCount,
                leadingIcon = { Icon(Icons.Outlined.Laptop, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = state.requestsDesktopSite,
                        onCheckedChange = { viewModel.toggleDesktopSite() },
                    )
                },
            )
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_page_zoom)) },
                // The readout is the reset; the whole row resets too, so the
                // row is a real menu item rather than an inert one.
                onClick = { if (MiniBrowserZoom.canReset(state.pageZoom)) viewModel.resetPageZoom() },
                index = zoomIndex,
                count = toolCount,
                leadingIcon = { Icon(Icons.Outlined.FormatSize, contentDescription = null) },
                trailingContent = {
                    ZoomControls(
                        zoom = state.pageZoom,
                        onZoomOut = viewModel::zoomOut,
                        onZoomIn = viewModel::zoomIn,
                        onReset = viewModel::resetPageZoom,
                    )
                },
            )
        }

        ZenMenuGroupGap()

        // Its own group: one destination, not a pair of tools.
        ZenMenuGroup(index = siteGroup, count = groupCount) {
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_site_settings)) },
                onClick = viewModel::openSiteSettings,
                index = 0,
                count = 1,
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RowGap),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MenuTile(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.browser_find_short),
                description = stringResource(R.string.browser_find_on_page),
                tint = zen.ink,
                onClick = onFind,
            )
            MenuTile(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.browser_share),
                description = stringResource(R.string.browser_share),
                tint = zen.ink,
                onClick = onShare,
            )
            // As the default browser, "open in the browser" would only come
            // back here, so the tile goes and the others share the width.
            val context = androidx.compose.ui.platform.LocalContext.current
            val isDefaultBrowser = remember(context) {
                de.kjell.zencompanion.ui.components.DefaultBrowser.isDefault(context)
            }
            if (!isDefaultBrowser) {
                MenuTile(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    title = stringResource(R.string.browser_open_short),
                    description = stringResource(R.string.browser_open_in_browser),
                    tint = zen.ink,
                    onClick = onOpenExternal,
                )
            }
            // Without the capability the save lands as a pin, whatever the
            // setting says. Signed out there is no space to save into, so the
            // tile is gone and the row's other tiles share the width.
            if (signedIn) {
                val effectiveKind = if (viewModel.allowsNormalTabs) state.saveKind else SaveKind.PINNED
                MenuTile(
                    icon = Icons.Outlined.PushPin,
                    title = stringResource(
                        if (effectiveKind == SaveKind.NORMAL) R.string.browser_save_short else R.string.browser_pin_short,
                    ),
                    description = stringResource(
                        if (effectiveKind == SaveKind.NORMAL) R.string.browser_save_to_space else R.string.browser_pin_to_space,
                    ),
                    tint = if (canPin) zen.ink else zen.ink.copy(alpha = 0.38f),
                    onClick = onPin,
                    enabled = canPin,
                )
            }
        }
    }
}

/** How long the menu's two pages take to cross-fade. */
private const val MenuPageFadeMs = 160

/**
 * The menu's second page: the site's own blocking switches, in the same
 * Expressive menu groups as the tools page. Its first row goes back, so the
 * sub-page reads as one step inside the menu instead of a sheet on top of it.
 * A switch that is off in Settings stays off here, with a note saying so.
 */
@Composable
private fun SiteSettingsMenuPage(
    host: String,
    settings: ContentBlockingSettings,
    onBack: () -> Unit,
    onChange: (BlockCategory, Boolean) -> Unit,
) {
    val zen = LocalZenColors.current
    val site = blockingSiteKey(host)
    // Header, the site's switches, the site's permissions.
    val groupCount = 3

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZenMenuGroup(index = 0, count = groupCount) {
            ZenMenuItem(
                text = { Text(stringResource(R.string.browser_site_settings)) },
                onClick = onBack,
                index = 0,
                count = 1,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back),
                    )
                },
                supportingText = { Text(site) },
            )
        }

        ZenMenuGroupGap()

        ZenMenuGroup(index = 1, count = groupCount) {
            BlockCategory.entries.forEachIndexed { index, category ->
                SiteBlockingItem(
                    category = category,
                    host = host,
                    settings = settings,
                    onChange = onChange,
                    index = index,
                    count = BlockCategory.entries.size,
                )
            }
        }

        ZenMenuGroupGap()

        ZenMenuGroup(index = 2, count = groupCount) {
            SitePermissionRows(host)
        }

        Text(
            text = stringResource(R.string.site_settings_footer, site),
            style = ZenType.rounded(13, FontWeight.Normal),
            color = zen.ink.copy(alpha = 0.6f),
            // Inset from the card's rounded bottom, so the last line clears the curve.
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = RowGap, bottom = 8.dp),
        )
    }
}

/** What the site may ask for. Ask lets the site's question come back. */
@Composable
private fun SitePermissionRows(host: String) {
    val choices by SitePermissions.choices.collectAsState()
    val count = SitePermission.entries.size
    SitePermission.entries.forEachIndexed { index, kind ->
        // Read through the collected map, so a change redraws the row.
        val current = choices.let { SitePermissions.choice(kind, host) }
        var expanded by remember { mutableStateOf(false) }
        ZenMenuItem(
            text = { Text(stringResource(kind.title)) },
            onClick = { expanded = true },
            index = index,
            count = count,
            leadingIcon = { Icon(kind.icon, contentDescription = null) },
            trailingContent = {
                // Anchored to the value, the way the chooser sits on its row.
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(choiceTitle(current)))
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    ZenMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        val options = listOf(null, SitePermissionChoice.ALLOW, SitePermissionChoice.BLOCK)
                        ZenMenuGroup(index = 0, count = 1) {
                            options.forEachIndexed { optionIndex, choice ->
                                ZenSelectableMenuItem(
                                    selected = choice == current,
                                    onClick = {
                                        expanded = false
                                        SitePermissions.set(choice, listOf(kind), host)
                                    },
                                    text = { Text(stringResource(choiceTitle(choice))) },
                                    index = optionIndex,
                                    count = options.size,
                                    selectedLeadingIcon = { ZenMenuCheck() },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

private fun choiceTitle(choice: SitePermissionChoice?): Int = when (choice) {
    SitePermissionChoice.ALLOW -> R.string.site_permission_allow
    SitePermissionChoice.BLOCK -> R.string.site_permission_block
    null -> R.string.site_permission_ask
}

private val SitePermission.title: Int
    get() = when (this) {
        SitePermission.CAMERA -> R.string.site_permission_camera
        SitePermission.MICROPHONE -> R.string.site_permission_microphone
        SitePermission.LOCATION -> R.string.site_permission_location
    }

private val SitePermission.icon: ImageVector
    get() = when (this) {
        SitePermission.CAMERA -> Icons.Outlined.PhotoCamera
        SitePermission.MICROPHONE -> Icons.Outlined.Mic
        SitePermission.LOCATION -> Icons.Outlined.LocationOn
    }

/** One site switch, as a menu item with the native switch on its right. */
@Composable
private fun SiteBlockingItem(
    category: BlockCategory,
    host: String,
    settings: ContentBlockingSettings,
    onChange: (BlockCategory, Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    val enabled = settings.isOn(category)
    val on = settings.isOn(category, host)
    ZenMenuItem(
        text = { Text(stringResource(category.title)) },
        onClick = { onChange(category, !on) },
        index = index,
        count = count,
        leadingIcon = { Icon(category.icon, contentDescription = null) },
        supportingText = if (!enabled) {
            ({ Text(stringResource(R.string.blocking_off_in_settings)) })
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = on,
                onCheckedChange = { onChange(category, it) },
                enabled = enabled,
            )
        },
        enabled = enabled,
    )
}

/** The blocked count, or what blocking does on this page, with what a tap does. */
@Composable
private fun BlockingCardItem(card: BlockingCard, onClick: () -> Unit) {
    val (icon, title, detail) = when (card) {
        is BlockingCard.Blocked -> {
            val text = pluralStringResource(R.plurals.blocking_card_blocked, card.count, card.count)
            val number = card.count.toString()
            val start = text.indexOf(number)
            Triple(
                Icons.Outlined.Shield,
                buildAnnotatedString {
                    if (start < 0) {
                        append(text)
                    } else {
                        append(text.substring(0, start))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(number) }
                        append(text.substring(start + number.length))
                    }
                },
                stringResource(R.string.blocking_card_restore),
            )
        }
        BlockingCard.ShowingOriginal -> Triple(
            Icons.Outlined.Restore,
            buildAnnotatedString { append(stringResource(R.string.blocking_card_original)) },
            stringResource(R.string.blocking_card_block_again),
        )
        is BlockingCard.OffForSite -> Triple(
            Icons.Outlined.Tune,
            buildAnnotatedString { append(stringResource(R.string.blocking_card_off_for_site, card.site)) },
            stringResource(R.string.blocking_card_open_site_settings),
        )
        BlockingCard.Hidden -> return
    }
    ZenMenuItem(
        text = { Text(title) },
        onClick = onClick,
        index = 0,
        count = 1,
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        supportingText = { Text(detail) },
    )
}

/** Minus, the readout that resets, plus: the menu's one inline control. */
@Composable
private fun ZoomControls(
    zoom: Float,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
) {
    val zen = LocalZenColors.current
    val resetLabel = stringResource(R.string.browser_zoom_reset)
    val canReset = MiniBrowserZoom.canReset(zoom)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZoomStep(
            icon = Icons.Outlined.Remove,
            description = stringResource(R.string.browser_zoom_out),
            enabled = MiniBrowserZoom.canZoomOut(zoom),
            onClick = onZoomOut,
        )
        // The readout is the reset: same gesture as a browser's zoom
        // percentage, and it keeps the row to one control.
        Surface(
            onClick = onReset,
            enabled = canReset,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier
                .width(56.dp)
                .height(40.dp)
                // At 100% the readout is plain text and announces itself;
                // zoomed, it is the reset button.
                .semantics {
                    if (canReset) contentDescription = resetLabel
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = MiniBrowserZoom.percentLabel(zoom),
                    style = ZenType.rounded(15, FontWeight.SemiBold),
                    color = zen.ink,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                )
            }
        }
        ZoomStep(
            icon = Icons.Outlined.Add,
            description = stringResource(R.string.browser_zoom_in),
            enabled = MiniBrowserZoom.canZoomIn(zoom),
            onClick = onZoomIn,
        )
    }
}

/** One zoom step: a tonal Material icon button. */
@Composable
private fun ZoomStep(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = zen.ink.copy(alpha = 0.9f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = zen.ink.copy(alpha = 0.3f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RowScope.MenuTile(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(TileHeight),
        shape = TileShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = liftBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = ZenType.rounded(11, FontWeight.SemiBold),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SiteIcon(effectiveUrl: String, pageFailed: Boolean = false) {
    val zen = LocalZenColors.current
    val icon = when {
        effectiveUrl.isEmpty() -> Icons.Outlined.Search
        // An insecure page must not wear the padlock a trusted one does.
        effectiveUrl.startsWith("http://") -> Icons.Outlined.LockOpen
        // A failed load (a bad certificate among them) made no trusted
        // connection, so it gets no closed padlock either.
        effectiveUrl.startsWith("https://") && !pageFailed -> Icons.Outlined.Lock
        else -> Icons.Outlined.Language
    }
    // The padlock is the only sign a page is encrypted, so it has to say so:
    // the clickable row around it merges its children, and the description is
    // read with the page's name. A search or a plain address says nothing
    // about a connection and stays silent.
    val description = when {
        pageFailed -> null
        effectiveUrl.startsWith("https://") -> stringResource(R.string.browser_connection_secure)
        effectiveUrl.startsWith("http://") -> stringResource(R.string.browser_connection_insecure)
        else -> null
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = zen.ink.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp),
    )
}
