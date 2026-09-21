package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.EssentialsGrid
import de.kjell.zencompanion.ui.motion.DampingSwitchSelect
import de.kjell.zencompanion.ui.motion.StiffnessSwitchSelect
import kotlin.math.abs

/** Horizontal paging of `SpacePageContainer` — one page is full width. */
@Composable
internal fun SpacesPager(
    spaces: List<ZenSpaces.ZenSpace>,
    essentialsBySpace: Map<String, List<ZenSpaces.ZenTab>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDeleteTab: (String) -> Unit,
    onOpenUrl: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        initialPageOffsetFraction = 0f,
        pageCount = { spaces.size },
    )

    // Keep the index and callback fresh inside the long-lived effect below:
    // it never restarts, so plain captures would read the FIRST composition's
    // values forever and compare against a stale index.
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    // Destination of a tap/restore scroll. While set, `settledPage` must not
    // write back: a jump of more than one space can briefly drop
    // `isScrollInProgress` (pre-jump or a no-op animation), which would
    // revert `selectedIndex`, cancel this effect, leave the pager on the old
    // page, and flash the destination theme over it.
    var programmaticTarget by remember { mutableStateOf<Int?>(null) }

    // Pager -> selection: report only the SETTLED page. `currentPage` also
    // changes while a scroll animation crosses an intermediate page (1 -> 3
    // passes 2); feeding that back rewrites `selectedIndex`, which cancels the
    // in-flight scroll below and freezes the pager half-way between two
    // spaces.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (shouldApplySettledPage(page, currentSelectedIndex, programmaticTarget)) {
                currentOnSelect(page)
            }
        }
    }
    // Selection -> pager: animate to external selections (dots, restore).
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex
        if (pagerState.pageCount == 0) return@LaunchedEffect
        val alreadyThere = pagerState.currentPage == target &&
            abs(pagerState.currentPageOffsetFraction) < 0.001f
        if (alreadyThere) {
            programmaticTarget = null
            return@LaunchedEffect
        }
        programmaticTarget = target
        try {
            pagerState.animateToSpace(target)
        } finally {
            if (programmaticTarget == target) programmaticTarget = null
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = modifier.fillMaxWidth(),
    ) { index ->
        val spaceEssentials = essentialsBySpace[spaces[index].id].orEmpty()
        val prevSharesEssentials = index > 0 &&
            essentialsGridsMatch(essentialsBySpace[spaces[index - 1].id].orEmpty(), spaceEssentials)
        val nextSharesEssentials = index < spaces.size - 1 &&
            essentialsGridsMatch(essentialsBySpace[spaces[index + 1].id].orEmpty(), spaceEssentials)
        SpacePageContainer(
            space = spaces[index],
            index = index,
            essentials = spaceEssentials,
            prevSharesEssentials = prevSharesEssentials,
            nextSharesEssentials = nextSharesEssentials,
            pagerState = pagerState,
            onDeleteTab = onDeleteTab,
            onOpenUrl = onOpenUrl,
        )
    }
}

/**
 * Two pages share one pinned essentials grid exactly when their effective tab
 * lists match — same-container spaces in container-specific mode, any two
 * spaces in shared mode.
 */
internal fun essentialsGridsMatch(
    a: List<ZenSpaces.ZenTab>,
    b: List<ZenSpaces.ZenTab>,
): Boolean = a.size == b.size && a.indices.all { a[it].id == b[it].id }

/**
 * During a tap/restore jump, ignore `settledPage` until the pager actually
 * lands on [programmaticTarget]. Otherwise a far-jump can settle on the
 * origin (or a pre-jump neighbor), overwrite the selection, and cancel the
 * animation — theme updates, pager does not.
 */
internal fun shouldApplySettledPage(
    settledPage: Int,
    selectedIndex: Int,
    programmaticTarget: Int?,
): Boolean {
    if (programmaticTarget != null && settledPage != programmaticTarget) return false
    return settledPage != selectedIndex
}

private suspend fun PagerState.animateToSpace(page: Int) {
    val spec = spring<Float>(
        dampingRatio = DampingSwitchSelect,
        stiffness = StiffnessSwitchSelect,
    )
    val size = layoutInfo.pageSize + layoutInfo.pageSpacing
    if (size > 0) {
        // Pixel delta so a skip of 2+ spaces is one continuous spring. The
        // built-in `animateScrollToPage` pre-jumps and can consume 0 px when
        // the destination is not yet composed (`beyondViewportPageCount` = 1).
        val delta = (page - currentPage - currentPageOffsetFraction) * size
        if (abs(delta) > 0.5f) animateScrollBy(delta, spec)
    } else {
        animateScrollToPage(page = page, animationSpec = spec)
    }
    if (currentPage != page || abs(currentPageOffsetFraction) > 0.01f) {
        scrollToPage(page)
    }
}

/**
 * One space page with interactive essentials pinning.
 */
@Composable
internal fun SpacePageContainer(
    space: ZenSpaces.ZenSpace,
    index: Int,
    essentials: List<ZenSpaces.ZenTab>,
    prevSharesEssentials: Boolean,
    nextSharesEssentials: Boolean,
    pagerState: PagerState,
    onDeleteTab: (String) -> Unit,
    onOpenUrl: (String, String?) -> Unit,
) {
    var pageWidthPx by remember { mutableStateOf(0f) }

    val offsetInPages = pagerState.getOffsetDistanceInPages(index)
    val minX = offsetInPages * pageWidthPx
    val isVisible = minX > -pageWidthPx + 0.5f && minX < pageWidthPx - 0.5f
    val isPinning = minX < -0.5f && minX > -pageWidthPx + 0.5f && nextSharesEssentials
    val hiddenByNeighbor = minX > 0.5f && prevSharesEssentials

    val xOffsetPx = if (isPinning) -minX else 0f
    val gridOpacity = if (isVisible && !hiddenByNeighbor) 1f else 0f

    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { pageWidthPx = it.width.toFloat() },
    ) {
        if (essentials.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 6.dp)
                    .offset { IntOffset(xOffsetPx.toInt(), 0) }
                    .alpha(gridOpacity),
            ) {
                EssentialsGrid(
                    tabs = essentials,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenUrl = onOpenUrl,
                )
            }
        }

        SpacePageView(space = space, onDeleteTab = onDeleteTab, onOpenUrl = onOpenUrl)
    }
}
