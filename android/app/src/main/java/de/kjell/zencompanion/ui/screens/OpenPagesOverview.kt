package de.kjell.zencompanion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.OpenPage
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.browser.PageSession
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** A tapped card growing back into its page, and the deck parting around it. */
internal val DeckOpenSpring = spring<Float>(stiffness = 220f, visibilityThreshold = 0.002f)

/**
 * Where the iPhone's app switcher puts its cards, as one pure calculation, so
 * the page can land exactly on its card without measuring anything (port of
 * iOS `PageDeckGeometry`). A card is the page down to where the bar starts. The
 * focused card sits right of the middle; each card further right lies on top
 * and runs away quickly, each further left tucks in behind.
 */
internal class PageDeckGeometry(
    val screen: Size,
    val count: Int,
    /** The strip under the page, behind the bar: no part of a card. */
    private val bottomBand: Float,
) {
    private val pageHeight = max(0f, screen.height - bottomBand)
    val cardSize = Size(screen.width * FocusScale, pageHeight * FocusScale)

    /** Where a page sits at full size. */
    val pageFrame = Rect(0f, 0f, screen.width, pageHeight)

    /** [cards] is fractional while one leaves, so the deck's end moves with it. */
    fun frame(index: Float, focus: Float, cards: Float = count.toFloat()): Rect {
        val offset = index - focus
        val scale = FocusScale + ScaleStep * offset.coerceIn(-4f, 2f)
        val width = screen.width * scale
        val height = pageHeight * scale
        val left = screen.width * left(min(3f, offset)) + endShift(focus, cards)
        val top = screen.height * Middle - height / 2
        return Rect(left, top, left + width, top + height)
    }

    /** With nothing on top of it the last card moves to the true middle. */
    private fun endShift(focus: Float, cards: Float): Float {
        val last = max(0f, cards - 1f)
        val near = (1f - (last - focus)).coerceIn(0f, 1f)
        val eased = near * near * (3f - 2f * near)
        val centered = (screen.width - cardSize.width) / 2f
        return (centered - screen.width * RestingLeft) * eased
    }

    private fun left(offset: Float): Float = if (offset < 0f) {
        RestingLeft + BehindSpread * (BehindGrowth.pow(offset) - 1f)
    } else {
        RestingLeft + AheadSpread * (AheadGrowth.pow(offset) - 1f)
    }

    companion object {
        const val FocusScale = 0.6875f
        private const val ScaleStep = 0.0105f
        private const val RestingLeft = 0.29f
        private const val BehindSpread = 0.369f
        private const val BehindGrowth = 2.39f
        private const val AheadSpread = 0.3876f
        private const val AheadGrowth = 2.29f
        private const val Middle = 0.49f
    }
}

/** How round this display's corners are, where the system says. */
@Composable
internal fun rememberDisplayCornerPx(): Float {
    val view = LocalView.current
    return remember(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
        } else {
            0f
        }
    }
}

/** The display's own curve, scaled down with the card. */
@Composable
internal fun rememberDeckCardCornerPx(): Float {
    val display = rememberDisplayCornerPx()
    val least = with(LocalDensity.current) { 20.dp.toPx() }
    return max(least, display * PageDeckGeometry.FocusScale)
}

private enum class DeckDrag { SIDEWAYS, UP, IGNORED }

private enum class DeckTouch { TAP, DRAG, LIFTED }

/**
 * Every open page as a card, the way the iPhone shows its running apps. The
 * deck slides sideways under the finger and comes to rest on a card. A card
 * opens with a tap and closes when it is pulled up; long press has the rest.
 */
@Composable
internal fun OpenPagesOverview(
    pages: BrowserPages,
    openPages: List<OpenPage>,
    activeId: String?,
    spaces: List<ZenSpaces.ZenSpace>,
    /** Folders are offered only when the save lands as a pinned tab. */
    saveKind: SaveKind,
    /** The card the page itself stands in for right now. */
    hiddenCardId: String?,
    /** The card growing back into its page, and how far it has got. */
    expandingCardId: String?,
    expansion: () -> Float,
    /** A page that is not part of the deck yet. */
    excludedCardId: String?,
    /** 0 with only the page on screen, 1 with the deck fully there. */
    reveal: () -> Float,
    /**
     * Cards that lie on top of the page's own card wait until it has landed:
     * the page flies above the deck and could not pass under them.
     */
    aheadReveal: () -> Float,
    /** Status bar height: the part of a card above the page's picture. */
    topInsetPx: Float,
    /** The strip under the page, behind the bar: no part of a card. */
    bottomBandPx: Float,
    onOpen: (OpenPage) -> Unit,
    onPin: (OpenPage, PinDestination) -> Unit,
    onShare: (OpenPage) -> Unit,
) {
    val zen = LocalZenColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Fixed when the deck comes up: like the running apps, the deck reorders
    // only the next time it opens, never under the finger or a tapped card.
    val order = remember { pages.store.deckOrder().filter { it.id != excludedCardId }.map { it.id } }
    // Gone from the deck in the very frame the others have closed the gap.
    val closed = remember { mutableStateListOf<String>() }
    val focus = remember {
        Animatable(
            (order.indexOf(activeId).takeIf { it >= 0 } ?: (order.size - 1).coerceAtLeast(0)).toFloat(),
        )
    }
    var liftedId by remember { mutableStateOf<String?>(null) }
    val lift = remember { Animatable(0f) }
    /** How far the other cards have closed the gap the lifted one will leave. */
    val gap = remember { Animatable(0f) }
    var menuCardId by remember { mutableStateOf<String?>(null) }

    val open = openPages.filter { it.id != excludedCardId && it.id !in closed }
    val known = order.mapNotNull { id -> open.firstOrNull { it.id == id } }
    // A popup can arrive while the deck is up; it is the newest.
    val cards = known + open.filter { it.id !in order }
    val lifted = cards.indexOfFirst { it.id == liftedId }.takeIf { it >= 0 }

    LaunchedEffect(cards.size) {
        val last = (cards.size - 1).coerceAtLeast(0).toFloat()
        if (focus.value > last) focus.snapTo(last)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true },
    ) {
        val deck = PageDeckGeometry(
            Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
            cards.size,
            bottomBandPx,
        )
        val density = LocalDensity.current
        val cardWidth = with(density) { deck.cardSize.width.toDp() }
        val cardHeight = with(density) { deck.cardSize.height.toDp() }
        val cardTopInset = with(density) { (topInsetPx * PageDeckGeometry.FocusScale).toDp() }
        val current by rememberUpdatedState(cards)
        val currentLifted by rememberUpdatedState(lifted)
        val interactive = expandingCardId == null

        fun focusAfterClosing(index: Int, count: Int): Float {
            var target = kotlin.math.round(focus.value)
            if (index < target) target -= 1f
            return target.coerceIn(0f, max(0, count - 2).toFloat())
        }

        /** The card leaves over the top and the others take its place, in one move. */
        fun throwAway(page: OpenPage, velocity: Float = 0f) {
            val list = current
            val index = list.indexOfFirst { it.id == page.id }
            if (index < 0) return
            liftedId = page.id
            scope.launch {
                launch { gap.animateTo(1f, spring(stiffness = 500f, visibilityThreshold = 0.002f)) }
                lift.animateTo(
                    -deck.screen.height,
                    spring(stiffness = 500f, visibilityThreshold = 4f),
                    initialVelocity = velocity,
                )
                val target = focusAfterClosing(index, list.size)
                closed.add(page.id)
                liftedId = null
                lift.snapTo(0f)
                gap.snapTo(0f)
                focus.snapTo(target)
                pages.close(page.id)
            }
        }

        val heldSpan = max(deck.screen.width * 0.5f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal() }
                .background(zen.background),
        )

        if (openPages.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp)
                    .graphicsLayer { alpha = reveal() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.kept_empty_title),
                    style = ZenType.rounded(20, FontWeight.Medium),
                    color = zen.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.kept_empty_message),
                    style = ZenType.rounded(15, FontWeight.Normal),
                    color = zen.ink.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
            }
        }

        // One finger, one decision: sideways moves the deck, up lifts the card
        // under the finger. Both follow the finger and hand its speed on. While
        // a card grows into its page nothing is caught here.
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (interactive) {
                        Modifier.pointerInput(deck.screen, deck.count) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (reveal() < 0.99f) return@awaitEachGesture
                                val list = current
                                val touchedIndex = list.indices.reversed().firstOrNull { index ->
                                    deck.frame(index.toFloat(), focus.value).contains(down.position)
                                }
                                val velocity = VelocityTracker()
                                velocity.addPosition(down.uptimeMillis, down.position)
                                var travelled = Offset.Zero
                                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var result = DeckTouch.TAP
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        if (change == null) {
                                            result = DeckTouch.LIFTED
                                            break
                                        }
                                        velocity.addPosition(change.uptimeMillis, change.position)
                                        travelled = change.position - down.position
                                        if (!change.pressed) {
                                            result = DeckTouch.TAP
                                            break
                                        }
                                        if (travelled.getDistance() > viewConfiguration.touchSlop) {
                                            change.consume()
                                            result = DeckTouch.DRAG
                                            break
                                        }
                                    }
                                    result
                                }
                                if (outcome == null) {
                                    // Held still: the card's menu.
                                    touchedIndex?.let {
                                        Haptics.perform(context, Haptics.Kind.SELECTION)
                                        menuCardId = list[it].id
                                    }
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                    }
                                    return@awaitEachGesture
                                }
                                if (outcome == DeckTouch.TAP) {
                                    touchedIndex?.let { index ->
                                        scope.launch { focus.animateTo(index.toFloat(), DeckOpenSpring) }
                                        onOpen(list[index])
                                    }
                                    return@awaitEachGesture
                                }
                                if (outcome != DeckTouch.DRAG) return@awaitEachGesture

                                val axis = when {
                                    abs(travelled.x) > abs(travelled.y) -> DeckDrag.SIDEWAYS
                                    touchedIndex != null -> DeckDrag.UP
                                    else -> DeckDrag.IGNORED
                                }
                                val startFocus = focus.value
                                if (axis == DeckDrag.UP) liftedId = list[touchedIndex!!].id
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    velocity.addPosition(change.uptimeMillis, change.position)
                                    val moved = change.position - down.position
                                    when (axis) {
                                        DeckDrag.SIDEWAYS -> {
                                            val last = (list.size - 1).coerceAtLeast(0).toFloat()
                                            val raw = startFocus - moved.x / heldSpan
                                            val banded = when {
                                                raw < 0f -> raw * 0.3f
                                                raw > last -> last + (raw - last) * 0.3f
                                                else -> raw
                                            }
                                            scope.launch { focus.snapTo(banded) }
                                        }
                                        DeckDrag.UP -> {
                                            // Up is one to one. Down gives a little and comes back.
                                            val height = moved.y
                                            val shown = if (height < 0f) {
                                                height
                                            } else {
                                                with(density) { 70.dp.toPx() } *
                                                    (1f - exp(-height / with(density) { 140.dp.toPx() }))
                                            }
                                            val closing = (max(0f, -shown) / max(deck.cardSize.height * 0.75f, 1f)).coerceAtMost(0.4f)
                                            scope.launch {
                                                lift.snapTo(shown)
                                                gap.snapTo(closing)
                                            }
                                        }
                                        DeckDrag.IGNORED -> Unit
                                    }
                                }
                                val speed = velocity.calculateVelocity()
                                when (axis) {
                                    DeckDrag.SIDEWAYS -> {
                                        val last = (list.size - 1).coerceAtLeast(0).toFloat()
                                        val cardsPerSecond = -speed.x / heldSpan
                                        val settled = kotlin.math.round(focus.value)
                                        val coasted = kotlin.math.round(focus.value + cardsPerSecond * 0.18f)
                                        val target = coasted.coerceIn(settled - 4f, settled + 4f).coerceIn(0f, last)
                                        scope.launch {
                                            focus.animateTo(
                                                target,
                                                spring(stiffness = 220f, visibilityThreshold = 0.002f),
                                                initialVelocity = cardsPerSecond,
                                            )
                                        }
                                    }
                                    DeckDrag.UP -> {
                                        val page = list[touchedIndex!!]
                                        val thrown = speed.y < -2200f
                                        if (lift.value < 0f && (thrown || lift.value < -deck.cardSize.height * 0.22f)) {
                                            Haptics.perform(context, Haptics.Kind.SELECTION)
                                            throwAway(page, speed.y)
                                        } else {
                                            scope.launch {
                                                launch { gap.animateTo(0f, spring(stiffness = 400f, visibilityThreshold = 0.002f)) }
                                                lift.animateTo(
                                                    0f,
                                                    spring(dampingRatio = 0.62f, stiffness = 400f, visibilityThreshold = 1f),
                                                    initialVelocity = speed.y,
                                                )
                                                liftedId = null
                                            }
                                        }
                                    }
                                    DeckDrag.IGNORED -> Unit
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            cards.forEachIndexed { index, page ->
                androidx.compose.runtime.key(page.id) {
                    val closing = lifted != null && index != lifted
                    val isExpanding = page.id == expandingCardId
                    fun place(): Float = if (closing && index > lifted!!) index - gap.value else index.toFloat()
                    fun deckFocus(): Float {
                        if (!closing) return focus.value
                        val closedFocus = focusAfterClosing(lifted!!, cards.size)
                        return focus.value + (closedFocus - focus.value) * gap.value
                    }
                    OpenPageCard(
                        page = page,
                        pages = pages,
                        isCurrent = page.id == activeId,
                        hidesPicture = page.id == hiddenCardId,
                        isExpanding = isExpanding,
                        expansion = expansion,
                        isFrontCard = index == cards.size - 1,
                        width = cardWidth,
                        height = cardHeight,
                        topInset = cardTopInset,
                        offset = { place() - deckFocus() },
                        reveal = reveal,
                        menuOpen = menuCardId == page.id,
                        onMenuOpen = { menuCardId = page.id },
                        onMenuDismiss = { menuCardId = null },
                        onOpen = {
                            scope.launch { focus.animateTo(index.toFloat(), DeckOpenSpring) }
                            onOpen(page)
                        },
                        spaces = spaces,
                        pinsIntoFolders = saveKind == SaveKind.PINNED,
                        onClose = {
                            menuCardId = null
                            throwAway(page)
                        },
                        onPin = { onPin(page, it) },
                        onShare = { onShare(page) },
                        modifier = Modifier
                            .zIndex(index.toFloat())
                            .graphicsLayer {
                                val focused = deckFocus()
                                val spot = place()
                                // A lifted card already counts as gone for the deck's end,
                                // so the others settle once instead of moving again.
                                val resting = deck.frame(spot, focused, max(1f, deck.count - gap.value))
                                val grown = if (isExpanding) expansion() else 0f
                                val left = resting.left * (1f - grown)
                                val top = resting.top * (1f - grown)
                                val frameWidth = resting.width + (deck.screen.width - resting.width) * grown
                                val frameHeight = resting.height + (deck.pageFrame.height - resting.height) * grown
                                val shown = if (spot > focused) min(reveal(), aheadReveal()) else reveal()
                                val away = spot - focused
                                val aside = if (abs(away) < 0.01f || grown > 0f) {
                                    0f
                                } else {
                                    (if (away < 0f) -1f else 1f) * deck.screen.width * 0.75f * (1f - shown)
                                }
                                val scale = frameWidth / max(deck.cardSize.width, 1f)
                                scaleX = scale
                                scaleY = scale
                                translationX = left + frameWidth / 2f - deck.cardSize.width / 2f + aside
                                translationY = top + frameHeight / 2f - deck.cardSize.height / 2f +
                                    (if (page.id == liftedId) lift.value else 0f)
                            },
                    )
                }
            }
        }

        if (openPages.isNotEmpty()) {
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = overviewDockClearance(navigationInset) - 8.dp)
                    .graphicsLayer { alpha = reveal() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = zen.ink.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(lifetimeHint(pages.lifetime())),
                    style = ZenType.rounded(12, FontWeight.Normal),
                    color = zen.ink.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One page: the whole screen in small, its icon and name above it. */
@Composable
private fun OpenPageCard(
    page: OpenPage,
    pages: BrowserPages,
    isCurrent: Boolean,
    hidesPicture: Boolean,
    /** Growing to the whole screen: it casts nothing. */
    isExpanding: Boolean,
    expansion: () -> Float,
    /** Nothing lies on top of this card, so its name has the whole width. */
    isFrontCard: Boolean,
    width: Dp,
    height: Dp,
    topInset: Dp,
    offset: () -> Float,
    reveal: () -> Float,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onOpen: () -> Unit,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onClose: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current
    val context = LocalContext.current
    val title = if (page.url.isEmpty()) stringResource(R.string.kept_new_page) else PageTitle.headline(page.title, page.url)
    val currentLabel = stringResource(R.string.kept_current_page)
    val closeLabel = stringResource(R.string.kept_close_page)
    val session = remember(page.id) { pages.session(page.id) }
    val cornerPx = rememberDeckCardCornerPx()
    val shape = remember(cornerPx) { RoundedCornerShape(cornerPx) }
    val surface = MaterialTheme.colorScheme.surface
    val iconSide = 22.dp
    val headerInset = 20.dp
    val iconGap = 8.dp

    LaunchedEffect(page.id) { session?.let(pages::loadShot) }

    Box(modifier.requiredSize(width, height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (hidesPicture) 0f else 1f }
                .shadow(
                    elevation = if (isExpanding) 0.dp else 7.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.3f),
                    spotColor = Color.Black.copy(alpha = 0.3f),
                )
                .graphicsLayer {
                    // Grown to the page, its bottom edge meets the bar's plate.
                    val bottom = cornerPx * (1f - if (isExpanding) expansion() else 0f)
                    this.shape = RoundedCornerShape(cornerPx, cornerPx, bottom, bottom)
                    clip = true
                }
                // The deck's gestures sit on its parent; TalkBack needs them here.
                .semantics {
                    contentDescription = if (isCurrent) "$title, $currentLabel" else title
                    onClick {
                        onOpen()
                        true
                    }
                    onLongClick {
                        onMenuOpen()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(closeLabel) {
                            onClose()
                            true
                        },
                    )
                },
        ) {
            OpenPageShot(page = page, session = session, topInset = topInset, surface = surface)
            // Cards the deck is not resting on sit a little in the dark, the
            // ones behind more than the one lying on top.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val away = offset()
                        val dim = if (away < 0f) min(0.3f, -away * 0.2f) else min(0.16f, away * 0.14f)
                        drawRect(Color.Black, alpha = dim)
                    },
            )
        }
        Row(
            modifier = Modifier
                .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                .offset(x = headerInset, y = -(iconSide + iconGap))
                .graphicsLayer {
                    alpha = reveal() * (1f - (max(0f, -offset() - 1.5f) * 2f).coerceAtMost(1f))
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(iconGap),
        ) {
            if (page.url.isNotEmpty()) {
                Favicon(urlString = page.url, size = iconSide)
            } else {
                Spacer(Modifier.size(iconSide))
            }
            val room = if (isFrontCard) width - headerInset * 2 else width * 0.727f - 12.dp
            Text(
                text = title,
                style = ZenType.rounded(14, FontWeight.SemiBold),
                color = zen.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = (room - iconSide - iconGap).coerceAtLeast(0.dp))
                    .graphicsLayer {
                        alpha = 1f - (abs(offset()) * 2.5f).coerceAtMost(1f)
                    },
            )
        }
        val pageFailed = session?.viewModel?.state?.collectAsState()?.value?.pageError != null
        PageCardMenu(
            expanded = menuOpen,
            page = page,
            canKeep = pages.keepsOnlyChosenPages() && pages.store.canKeep(page, pageFailed),
            spaces = spaces,
            pinsIntoFolders = pinsIntoFolders,
            onDismiss = onMenuDismiss,
            onToggleKeep = {
                onMenuDismiss()
                session?.let { pages.toggleKeep(it.id) }
            },
            onPin = { destination ->
                onMenuDismiss()
                onPin(destination)
            },
            onCopy = {
                onMenuDismiss()
                copyLink(context, page.url)
            },
            onShare = {
                onMenuDismiss()
                onShare()
            },
            onClose = onClose,
        )
    }
}

/** The page as it looked when it was last on screen, on its own color; its favicon until then. */
@Composable
private fun OpenPageShot(page: OpenPage, session: PageSession?, topInset: Dp, surface: Color) {
    val state = session?.viewModel?.state?.collectAsState()?.value
    val color = state?.siteThemeColor.takeIf { state?.pageError == null } ?: surface
    val shot = session?.shot
    Box(
        Modifier
            .fillMaxSize()
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (shot != null) {
            Image(
                bitmap = shot,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset),
            )
        } else if (page.url.isNotEmpty()) {
            Favicon(urlString = page.url, size = 36.dp)
        }
    }
}

/** Long press. Pin to space offers each space and, for a pin, its folders. */
@Composable
private fun PageCardMenu(
    expanded: Boolean,
    page: OpenPage,
    canKeep: Boolean,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onDismiss: () -> Unit,
    onToggleKeep: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    var pinning by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (pinning) {
            spaces.forEach { space ->
                DropdownMenuItem(
                    text = { Text(space.name) },
                    onClick = { onPin(PinDestination(spaceId = space.id)) },
                )
                if (pinsIntoFolders) {
                    PinDestinationModel.folderEntries(space).forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.name) },
                            modifier = Modifier.padding(start = 16.dp + 12.dp * folder.depth),
                            onClick = { onPin(PinDestination(spaceId = space.id, folderId = folder.folderId)) },
                        )
                    }
                }
            }
            return@DropdownMenu
        }
        if (canKeep) {
            DropdownMenuItem(
                text = { Text(stringResource(if (page.isKept) R.string.browser_kept else R.string.browser_keep_page)) },
                leadingIcon = {
                    Icon(if (page.isKept) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null)
                },
                onClick = onToggleKeep,
            )
        }
        if (page.url.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kept_pin_to_space)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                enabled = spaces.isNotEmpty(),
                onClick = { pinning = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kept_copy_link)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = onCopy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = onShare,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.kept_close_page), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onClose,
        )
    }
}

@StringRes
private fun lifetimeHint(lifetime: KeptPageLifetime): Int = when (lifetime) {
    KeptPageLifetime.NEVER -> R.string.kept_hint_never
    KeptPageLifetime.DAY -> R.string.kept_hint_day
    KeptPageLifetime.WEEK -> R.string.kept_hint_week
    KeptPageLifetime.MONTH -> R.string.kept_hint_month
}

/** Long press on a link in a page. */
@Composable
internal fun LinkMenu(
    url: String,
    onOpenInNewPage: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenInNewPage, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.browser_open_in_new_page), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.kept_copy_link), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.browser_share), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}

internal fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Haptics.perform(context, Haptics.Kind.SELECTION)
}
