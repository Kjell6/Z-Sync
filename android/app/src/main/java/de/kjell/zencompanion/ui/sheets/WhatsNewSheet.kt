package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.WhatsNewIcon
import de.kjell.zencompanion.data.WhatsNewItem
import de.kjell.zencompanion.data.WhatsNewRelease
import de.kjell.zencompanion.ui.theme.zenListItemColors

/**
 * Material 3 What's New sheet: a single scrollable list of release highlights
 * with a pinned Done button. All type comes from [MaterialTheme.typography]
 * so font scaling is respected; the list scrolls instead of clipping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    ZenSheet(
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CloseButton(onDismiss)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.whats_new_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.whats_new_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))

                // The rows are native list items with their own vertical
                // padding, so they need only a small gap between them.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhatsNewRelease.items.forEach { item ->
                        WhatsNewRow(item)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 20.dp)
                    .heightIn(min = 52.dp),
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(14.dp)),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.whats_new_done),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WhatsNewRow(item: WhatsNewItem) {
    ListItem(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = stringResource(item.detailRes),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        colors = zenListItemColors(),
    ) {
        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val WhatsNewIcon.imageVector: ImageVector
    get() = when (this) {
        WhatsNewIcon.SHARE -> Icons.Outlined.Share
        WhatsNewIcon.TABS -> Icons.Outlined.Tab
        WhatsNewIcon.SEARCH -> Icons.Outlined.Search
        WhatsNewIcon.MORE -> Icons.Outlined.AutoAwesome
    }
