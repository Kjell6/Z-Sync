package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.liftRim
import de.kjell.zencompanion.ui.theme.pressFeedback

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
) {
    val zen = LocalZenColors.current
    // Same 16dp squircle corner as iOS Palette.liftCornerRadius.
    val cardShape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Left: History card. Press is the outermost modifier so the whole
        // tile, not only the icon, shrinks while the finger is down.
        Box(
            modifier = Modifier
                .clickableNoIndication(onClick = onOpenActivity)
                .size(44.dp)
                .clip(cardShape)
                .background(zen.lift)
                .liftRim(cardShape)
                .semantics { contentDescription = "History" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Center: Search card (flexible width)
        Box(
            modifier = Modifier
                .weight(1f)
                .clickableNoIndication(onClick = { onOpenBrowser(null, null) })
                .height(44.dp)
                .clip(cardShape)
                .background(zen.lift)
                .liftRim(cardShape)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 12.dp),
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
        }

        // Right: Settings card
        Box(
            modifier = Modifier
                .clickableNoIndication(onClick = onOpenAccount)
                .size(44.dp)
                .clip(cardShape)
                .background(zen.lift)
                .liftRim(cardShape)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
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
