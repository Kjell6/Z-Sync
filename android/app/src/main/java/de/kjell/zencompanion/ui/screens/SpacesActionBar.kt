package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.liftRim
import de.kjell.zencompanion.ui.theme.pressFeedback
import de.kjell.zencompanion.ui.theme.rememberTilePress

/**
 * Action bar: History | Search | Settings — all cards sharing the essentials
 * tile look. Rendered above the essentials grid or below the space switcher,
 * per the user's `ToolbarPlacement`.
 */
@Composable
internal fun ActionBar(
    onOpenBrowser: (url: String?, title: String?) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenAccount: () -> Unit,
    /** Pages open on this phone; the count shows from one up. */
    pageCount: Int = 0,
    onOpenPages: () -> Unit = {},
    /** Gap below the bar: toward the essentials grid at the top placement,
     * toward the screen edge when the bar is the last row. */
    bottomPadding: Dp = 6.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Left: History card. Press is the outermost modifier so the whole
        // tile, not only the icon, shrinks while the finger is down.
        ActionCard(
            description = "History",
            modifier = Modifier.size(44.dp),
            onClick = onOpenActivity,
        ) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Center: Search card (flexible width)
        ActionCard(
            description = "Search",
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            contentAlignment = Alignment.CenterStart,
            onClick = { onOpenBrowser(null, null) },
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = if (pageCount > 0) 52.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.browser_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pageCount > 0) {
                // Its own target: the rest of the field still starts a search.
                val description = pluralStringResource(R.plurals.browser_page_count, pageCount, pageCount)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickableNoIndication(onClick = onOpenPages)
                        .size(width = 48.dp, height = 44.dp)
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    PageCountBadge(count = pageCount, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
            }
        }

        // Right: Settings card
        ActionCard(
            description = "Settings",
            modifier = Modifier.size(44.dp),
            onClick = onOpenAccount,
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One card of the action bar: the same lift fill, hairline rim and 16dp
 * resting corner as before. While the finger is down the corner tightens and
 * the card scales down a touch, then springs back, on the theme's motion.
 */
@Composable
private fun ActionCard(
    description: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val zen = LocalZenColors.current
    // Same 16dp squircle corner as iOS Palette.liftCornerRadius.
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val press = rememberTilePress(source, shape)
    Box(
        modifier = Modifier
            .then(press.modifier)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .then(modifier)
            .clip(press.shape)
            .background(zen.lift)
            .liftRim(press.shape)
            .semantics { contentDescription = description },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Tap target without a Material ripple. Dims and shrinks while the finger is down. */
@Composable
internal fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressFeedback(source)
        .clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
        )
}
