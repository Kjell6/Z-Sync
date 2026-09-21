package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.ZenIconView
import de.kjell.zencompanion.ui.motion.DampingSwitcherExtra
import de.kjell.zencompanion.ui.motion.StiffnessSwitcherExtra

/**
 * Native Material 3 Space Switcher indicator dots / icons.
 *
 * Layout contract: never grows wider than the screen. Items shrink slightly
 * with many spaces, and the row scrolls horizontally (centered while it fits).
 */
@Composable
fun SpaceSwitcher(
    spaces: List<ZenSpaces.ZenSpace>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallbackName = stringResource(R.string.share_workspace)

    // Shrink touch targets slightly as the count grows so more spaces fit
    // without scrolling; beyond that the LazyRow scrolls.
    val itemWidth: Dp = when {
        spaces.size <= 5 -> 36.dp
        spaces.size <= 8 -> 32.dp
        else -> 30.dp
    }
    val spacing: Dp = when {
        spaces.size <= 5 -> 16.dp
        spaces.size <= 8 -> 10.dp
        else -> 8.dp
    }
    val iconSize: Dp = when {
        spaces.size <= 5 -> 22.dp
        spaces.size <= 8 -> 20.dp
        else -> 18.dp
    }

    val listState = rememberLazyListState()

    // Keep a restored selection (e.g. index 7 of 8) visible on first composition.
    LaunchedEffect(spaces.size) {
        if (selectedIndex in spaces.indices) {
            listState.scrollToItem(selectedIndex)
        }
    }
    // Follow taps / swipes and keep the selected item centered-ish.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in spaces.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LazyRow(
        state = listState,
        verticalAlignment = Alignment.CenterVertically,
        // Centered while the content fits, scrollable once it overflows.
        // fillMaxWidth constrains the row to the screen: it can never push
        // the parent Column wider, unlike a plain Row with many items.
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            count = spaces.size,
            key = { index -> spaces[index].id },
        ) { index ->
            val space = spaces[index]
            SwitcherItem(
                space = space,
                isSelected = index == selectedIndex,
                label = space.name.ifEmpty { fallbackName },
                itemWidth = itemWidth,
                iconSize = iconSize,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SwitcherItem(
    space: ZenSpaces.ZenSpace,
    isSelected: Boolean,
    label: String,
    itemWidth: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val opacity by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.35f,
        animationSpec = spring(
            dampingRatio = DampingSwitcherExtra,
            stiffness = StiffnessSwitcherExtra,
        ),
        label = "switchOpacity",
    )
    Box(
        Modifier
            .size(width = itemWidth, height = 44.dp)
            .alpha(opacity)
            .clickable(
                onClick = onClick,
                interactionSource = null,
                indication = androidx.compose.material3.ripple(bounded = false, radius = 20.dp),
            )
            .semantics {
                this.selected = isSelected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!space.icon.isNullOrEmpty()) {
            ZenIconView(icon = space.icon, size = iconSize, foreground = onSurface)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .background(onSurface, CircleShape),
            )
        }
    }
}
