package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces

/** Adaptive columns: 1-4 single row, 5-6 -> 3 cols, 7+ -> 4 cols. */
internal fun columnCountFor(count: Int): Int = when (count) {
    1 -> 1
    2 -> 2
    3 -> 3
    4 -> 4
    5, 6 -> 3
    else -> 4
}

/**
 * Native Material 3 Essentials Grid.
 * Displays pinned essential tabs in a responsive grid of Material 3 cards with native ripples.
 */
@Composable
fun EssentialsGrid(
    tabs: List<ZenSpaces.ZenTab>,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String, String?) -> Unit)? = null,
    onRenameTab: (String, String) -> Unit = { _, _ -> },
) {
    val columns = columnCountFor(tabs.size)
    val rows = tabs.chunked(columns)

    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tab in row) {
                    Box(Modifier.weight(1f)) {
                        EssentialTile(
                            tab = tab,
                            onOpenUrl = onOpenUrl,
                            onRename = { label -> onRenameTab(tab.id, label) },
                        )
                    }
                }
                // Pad the final row so tiles maintain width
                repeat(columns - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EssentialTile(
    tab: ZenSpaces.ZenTab,
    onOpenUrl: ((String, String?) -> Unit)? = null,
    onRename: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val label = tab.displayTitle
    val renameText = stringResource(R.string.tabs_rename)
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {
                    if (onOpenUrl != null) {
                        onOpenUrl(tab.url, tab.displayTitle)
                    } else {
                        openURLExternally(context, tab.url)
                    }
                },
                onLongClick = { menuOpen = true },
            )
            .semantics {
                contentDescription = label
                customActions = listOf(CustomAccessibilityAction(renameText) { showRename = true; true })
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            // Same lift color as the top-bar cards (iOS: Palette.lift) so all
            // tile backgrounds match across the screen.
            containerColor = de.kjell.zencompanion.ui.theme.LocalZenColors.current.lift,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ZenTabIcon(tab = tab, size = 26.dp)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            renameText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        showRename = true
                    },
                )
            }
        }
    }

    if (showRename) {
        RenameTabDialog(
            tab = tab,
            onDismiss = { showRename = false },
            onConfirm = { value ->
                showRename = false
                onRename(value)
            },
        )
    }
}
