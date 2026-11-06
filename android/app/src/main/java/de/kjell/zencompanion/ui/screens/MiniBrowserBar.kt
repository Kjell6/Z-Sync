package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.blockingSiteKey
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.ui.browser.MiniBrowserViewModel
import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.sheets.icon
import de.kjell.zencompanion.ui.sheets.title
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.ui.theme.pressFeedback

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

/**
 * The app's press feel: shrink and dim while the finger is down, no ripple. The
 * default ripple draws a dark rectangle over the control's square bounds, which
 * does not match the action bar and the tiles.
 */
@Composable
internal fun Modifier.barClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressFeedback(source)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

// MARK: - Metrics

/** A solid surface, not glass: the platform has no Liquid Glass, so the bar is
 * a hard pane that sits on the page. */
private val PanelShape = RoundedCornerShape(20.dp)
private val DockShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(16.dp)

private val BarHeight = 44.dp
private val DockSide = 44.dp
private val CloseWidth = 92.dp
private val CloseHeight = 42.dp
private val RowHeight = 44.dp
private val TileHeight = 60.dp
private val PanelInset = 14.dp
private val RowGap = 8.dp
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
    onClose: () -> Unit,
    /** Open pages on this phone; the dock's button shows the count. */
    pageCount: Int,
    onOpenPages: () -> Unit,
    /** The page has no history left, but a link opened it: Back returns there. */
    canReturnToOpener: Boolean,
    onReturnToOpener: () -> Unit,
    isKept: Boolean,
    canKeep: Boolean,
    onToggleKeep: () -> Unit,
    swipe: PageSwipe,
    effectiveUrl: String,
    addressText: TextFieldValue,
    onAddressTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onAddressFocusedChange: (Boolean) -> Unit,
    onBeginAddressEdit: () -> Unit,
    onCancelAddressEdit: () -> Unit,
    onSubmitAddress: (String) -> Unit,
    /** False while a page error covers the page: there is nothing to pin. */
    canPin: Boolean = true,
    onPickSuggestion: (String) -> Unit,
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
    /** The plate's color this frame: it follows a swipe between pages. */
    liveBarColor: () -> Color = { barColor },
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current

    Column(
        modifier = modifier
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
        if (state.menuExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Same surface as the dock buttons, so the open menu reads
                    // as part of the bar rather than as a second plate.
                    .pane(PanelShape, zen.lift)
                    .padding(PanelInset),
            ) {
                // One panel, two pages: the tools, and the site's own switches.
                // The swap happens inside the same surface, so Site Settings
                // never opens as a second sheet on top of the menu.
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
                            isKept = isKept,
                            canKeep = canKeep,
                            onToggleKeep = onToggleKeep,
                            canPin = canPin,
                            barColor = barColor,
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

        if (state.isAddressFocused) {
            // Part of the address editing state: the list sits in the space
            // above the composer, on the same plate, and goes away with it.
            if (state.suggestions.isNotEmpty()) {
                SuggestionPanel(
                    suggestions = state.suggestions,
                    onSelect = onPickSuggestion,
                )
            }
            // The bar rides on the keyboard while typing; the dock would only
            // sit under the keys.
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

            Dock(
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

        val navigationInset = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()
        // While the composer rides the keyboard, the gesture area is behind it:
        // only a small gap keeps the row off the keys.
        val imeVisible = WindowInsets.isImeVisible
        Spacer(Modifier.height(if (imeVisible) 8.dp else barBottomGap(navigationInset)))
    }
}

/**
 * A pane, not a floating sheet: same fill and hairline as the dock buttons, no
 * shadow. Nothing in this chrome hovers over the page.
 */
@Composable
private fun Modifier.pane(shape: Shape, color: Color): Modifier = this
    .clip(shape)
    .background(color)
    .border(liftBorder(), shape)

/** Height of the chrome's rows, without the air below the dock. */
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { barWidth = it.width }
            .pointerInput(Unit) {
                var velocity = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocity = VelocityTracker()
                        current.onStart()
                    },
                    onDragEnd = { current.onEnd(velocity.calculateVelocity().x) },
                    onDragCancel = { current.onEnd(0f) },
                ) { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    current.onDrag(amount * current.span() / (barWidth + gap).coerceAtLeast(1f))
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
            PeekNavBar(
                peek,
                Modifier.graphicsLayer {
                    translationX = barOffset() + barWidth + gap
                    alpha = if (current.offset() == 0f) 0f else 1f
                },
            )
        }
        Box(Modifier.graphicsLayer { translationX = barOffset() }) { bar() }
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
    Box(modifier = Modifier.fillMaxWidth().pane(DockShape, zen.lift)) {
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

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(BarHeight - 12.dp)
                    .barClickable(onClick = onBeginAddressEdit)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteIcon(effectiveUrl = effectiveUrl)
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

            NavGlyph(
                icon = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                description = stringResource(
                    if (isLoading) R.string.browser_stop else R.string.browser_reload,
                ),
                enabled = true,
                onClick = if (isLoading) onStop else onReload,
            )
        }

        if (isLoading && progress < 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(progress.coerceAtLeast(0.05f))
                    .height(2.dp)
                    .background(zen.ink),
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
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = BarHeight - 12.dp)
            .barClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = if (enabled) 0.8f else 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Dock

@Composable
private fun Dock(
    menuOpen: Boolean,
    ink: Color,
    pageCount: Int,
    onOpenPages: () -> Unit,
    onClose: () -> Unit,
    onToggleMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DockInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val zen = LocalZenColors.current
        val pagesDescription = pluralStringResource(R.plurals.browser_page_count, pageCount, pageCount)
        Box(
            modifier = Modifier
                .size(DockSide)
                .background(zen.lift, DockShape)
                .border(liftBorder(), DockShape)
                .barClickable(onClick = onOpenPages)
                .semantics { contentDescription = pagesDescription },
            contentAlignment = Alignment.Center,
        ) {
            PageCountBadge(count = pageCount, color = ink.copy(alpha = 0.8f))
        }

        Spacer(Modifier.weight(1f))

        // The only way out of the mini-browser.
        DockButton(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.browser_close),
            tint = ink,
            width = CloseWidth,
            height = CloseHeight,
            onClick = onClose,
        )

        Spacer(Modifier.weight(1f))

        DockButton(
            icon = Icons.Outlined.MoreHoriz,
            description = stringResource(if (menuOpen) R.string.browser_close_menu else R.string.browser_more),
            tint = ink,
            onClick = onToggleMenu,
        )
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    width: androidx.compose.ui.unit.Dp = DockSide,
    height: androidx.compose.ui.unit.Dp = DockSide,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(zen.lift, DockShape)
            .border(liftBorder(), DockShape)
            .barClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - Suggestions

/**
 * Same rows as the iOS list, on the hard surface this chrome uses. The panel
 * grows above the composer row, so the list reads as part of typing an
 * address rather than as a layer over the page.
 */
@Composable
private fun SuggestionPanel(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
) {
    val zen = LocalZenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pane(DockShape, zen.lift),
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RowHeight)
                    .barClickable { onSelect(suggestion) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    // Same quiet ink as the composer's glyph.
                    tint = zen.ink.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = suggestion,
                    style = ZenType.rounded(16, FontWeight.Medium),
                    color = zen.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (index < suggestions.lastIndex) RowDivider()
        }
    }
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
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.common_cancel),
            tint = zen.ink.copy(alpha = 0.8f),
            modifier = Modifier
                .size(32.dp)
                .barClickable(onClick = onCancel)
                .padding(8.dp),
        )
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
                .onFocusChanged { if (it.isFocused) onAddressFocusedChange(true) },
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
            Icon(
                imageVector = Icons.Outlined.Cancel,
                contentDescription = stringResource(R.string.browser_clear_search),
                tint = zen.ink.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(32.dp)
                    .barClickable { onAddressTextChange(TextFieldValue("", selection = TextRange.Zero)) }
                    .padding(8.dp),
            )
        }
    }
}

// MARK: - Menu

@Composable
private fun ExpandedMenu(
    state: MiniBrowserViewModel.State,
    viewModel: MiniBrowserViewModel,
    isKept: Boolean,
    canKeep: Boolean,
    onToggleKeep: () -> Unit,
    canPin: Boolean,
    barColor: Color,
    onCopyLink: () -> Unit,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onPin: () -> Unit,
    blockingCard: BlockingCard,
    onBlockingCard: () -> Unit,
) {
    val zen = LocalZenColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        // At the top: the menu grows up from the bar, so this is the far end,
        // where Arc keeps the same status at the bottom of its sheet.
        if (blockingCard != BlockingCard.Hidden) {
            BlockingCardRow(card = blockingCard, onClick = onBlockingCard)
        }

        // The page tools, one block, divided inside. On Android the rows stay
        // straight on the panel: the dividers and the gap between the blocks
        // do the grouping, no second card under them.
        Column(Modifier.fillMaxWidth()) {
            MenuRow(
                icon = if (isKept) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                title = stringResource(if (isKept) R.string.browser_kept else R.string.browser_keep_page),
                tint = if (isKept) zen.ink else zen.ink,
                onClick = onToggleKeep.takeIf { canKeep },
            )
            RowDivider()
            MenuRow(
                icon = Icons.Outlined.ContentCopy,
                title = stringResource(
                    if (state.copyConfirmed) R.string.browser_copied else R.string.browser_copy_link,
                ),
                tint = if (state.copyConfirmed) zen.ink else zen.ink,
                onClick = onCopyLink,
            )
            RowDivider()
            MenuRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.browser_reader),
                tint = zen.ink,
                trailing = {
                    when {
                        state.readerLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = zen.ink,
                        )

                        state.readerArticle != null -> Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = zen.ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onClick = viewModel::toggleReader,
            )
            RowDivider()
            MenuRow(
                icon = Icons.Outlined.Laptop,
                title = stringResource(R.string.browser_desktop_site),
                tint = zen.ink,
                trailing = {
                    Switch(
                        checked = state.requestsDesktopSite,
                        onCheckedChange = { viewModel.toggleDesktopSite() },
                    )
                },
                onClick = viewModel::toggleDesktopSite,
            )
            RowDivider()
            MenuRow(
                icon = Icons.Outlined.FormatSize,
                title = stringResource(R.string.browser_page_zoom),
                tint = zen.ink,
                trailing = {
                    val resetLabel = stringResource(R.string.browser_zoom_reset)
                    val canReset = MiniBrowserZoom.canReset(state.pageZoom)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(barColor, CircleShape)
                            .padding(horizontal = 2.dp),
                    ) {
                        ZoomStep(
                            icon = Icons.Outlined.KeyboardArrowDown,
                            description = stringResource(R.string.browser_zoom_out),
                            enabled = MiniBrowserZoom.canZoomOut(state.pageZoom),
                            onClick = viewModel::zoomOut,
                        )
                        // The readout is the reset: same gesture as a browser's
                        // zoom percentage, and it keeps the row to one control.
                        Box(
                            modifier = Modifier
                                .size(width = 54.dp, height = 32.dp)
                                .barClickable(
                                    enabled = canReset,
                                    onClick = viewModel::resetPageZoom,
                                )
                                // At 100% the readout is plain text and announces
                                // itself; zoomed, it is the reset button.
                                .semantics {
                                    if (canReset) contentDescription = resetLabel
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = MiniBrowserZoom.percentLabel(state.pageZoom),
                                style = ZenType.rounded(15, FontWeight.SemiBold),
                                color = zen.ink,
                                textAlign = TextAlign.Center,
                            )
                        }
                        ZoomStep(
                            icon = Icons.Outlined.KeyboardArrowUp,
                            description = stringResource(R.string.browser_zoom_in),
                            enabled = MiniBrowserZoom.canZoomIn(state.pageZoom),
                            onClick = viewModel::zoomIn,
                        )
                    }
                },
            )
        }

        // Its own block: one destination, not a group.
        MenuRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.browser_site_settings),
            tint = zen.ink,
            trailing = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = zen.ink.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = viewModel::openSiteSettings,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MenuTile(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.browser_find_short),
                description = stringResource(R.string.browser_find_on_page),
                tint = zen.ink,
                background = barColor,
                onClick = onFind,
            )
            MenuTile(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.browser_share),
                description = stringResource(R.string.browser_share),
                tint = zen.ink,
                background = barColor,
                onClick = onShare,
            )
            MenuTile(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.browser_open_short),
                description = stringResource(R.string.browser_open_in_browser),
                tint = zen.ink,
                background = barColor,
                onClick = onOpenExternal,
            )
            // Without the capability the save lands as a pin, whatever the
            // setting says.
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
                background = barColor,
                onClick = onPin,
                enabled = canPin,
            )
        }
    }
}

/** How long the menu's two pages take to cross-fade. */
private const val MenuPageFadeMs = 160

/**
 * The menu's second page: the site's own blocking switches, in the panel's row
 * style. Its first row goes back, so the sub-page reads as one step inside the
 * menu instead of a sheet on top of it. A switch that is off in Settings stays
 * off here, with a note saying so.
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .barClickable(onClick = onBack)
                .heightIn(min = RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron sits in the space the menu rows use for their glyphs,
            // so the title lines up with the rows on the other page.
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.browser_back),
                tint = zen.ink.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.browser_site_settings),
                    style = ZenType.rounded(16, FontWeight.Medium),
                    color = zen.ink,
                )
                Text(
                    text = site,
                    style = ZenType.rounded(13, FontWeight.Normal),
                    color = zen.ink.copy(alpha = 0.6f),
                )
            }
        }
        // One block, divided inside, like the page tools on the menu's first
        // page: no card under them, the gap separates the block from the header.
        Column(Modifier.fillMaxWidth()) {
            BlockCategory.entries.forEachIndexed { index, category ->
                if (index > 0) RowDivider()
                SiteBlockingRow(
                    category = category,
                    host = host,
                    settings = settings,
                    onChange = onChange,
                )
            }
        }
        Text(
            text = stringResource(R.string.site_settings_footer, site),
            style = ZenType.rounded(13, FontWeight.Normal),
            color = zen.ink.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
}

/** One site switch as a plain menu row: like the menu's tools, the rows sit
 *  straight on the panel and the dividers do the grouping. */
@Composable
private fun SiteBlockingRow(
    category: BlockCategory,
    host: String,
    settings: ContentBlockingSettings,
    onChange: (BlockCategory, Boolean) -> Unit,
) {
    val zen = LocalZenColors.current
    val enabled = settings.isOn(category)
    val on = settings.isOn(category, host)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .barClickable(enabled = enabled) { onChange(category, !on) }
            .heightIn(min = RowHeight)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = zen.ink.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(category.title),
                style = ZenType.rounded(16, FontWeight.Medium),
                color = zen.ink,
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.blocking_off_in_settings),
                    style = ZenType.rounded(13, FontWeight.Normal),
                    color = zen.ink.copy(alpha = 0.6f),
                )
            }
        }
        Switch(
            checked = on,
            onCheckedChange = { onChange(category, it) },
            enabled = enabled,
        )
    }
}

/** The blocked count, or what blocking does on this page, with what a tap does. */
@Composable
private fun BlockingCardRow(card: BlockingCard, onClick: () -> Unit) {
    val zen = LocalZenColors.current
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
    // True white or black rather than the cream ink: this row sits on the
    // page's own colour through the glass, where cream loses contrast.
    val strong = if (zen.isDark) Color.White else Color.Black
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .barClickable(onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = strong,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = ZenType.rounded(16, FontWeight.Medium),
                color = strong,
            )
            Text(
                text = detail,
                style = ZenType.rounded(13, FontWeight.Normal),
                color = strong.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(onClick?.let { action -> Modifier.barClickable(onClick = action) } ?: Modifier)
            .height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = ZenType.rounded(16, FontWeight.Medium),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        trailing?.invoke()
    }
}

@Composable
private fun RowDivider() {
    val zen = LocalZenColors.current
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 34.dp)
            .height(0.5.dp)
            .background(zen.ink.copy(alpha = 0.08f)),
    )
}

@Composable
private fun ZoomStep(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zen = LocalZenColors.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .barClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = zen.ink.copy(alpha = if (enabled) 0.9f else 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RowScope.MenuTile(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val zen = LocalZenColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .height(TileHeight)
            .background(background, TileShape)
            .border(liftBorder(), TileShape)
            .barClickable(enabled = enabled, onClick = onClick),
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

@Composable
private fun SiteIcon(effectiveUrl: String) {
    val zen = LocalZenColors.current
    val icon = when {
        effectiveUrl.isEmpty() -> Icons.Outlined.Search
        effectiveUrl.startsWith("http://") -> Icons.Outlined.Lock
        effectiveUrl.startsWith("https://") -> Icons.Outlined.Lock
        else -> Icons.Outlined.Language
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (effectiveUrl.startsWith("http://")) zen.ink else zen.ink.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp),
    )
}
