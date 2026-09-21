package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.FolderBlock
import de.kjell.zencompanion.ui.components.SplitRow
import de.kjell.zencompanion.ui.components.TabRow
import de.kjell.zencompanion.ui.components.ZenIconView
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType

/** One space page: fixed title row + vertically scrolling tab list (pinned &
 *  normal), with conditional top & bottom strokes when the list is scrollable. */
@Composable
internal fun SpacePageView(
    space: ZenSpaces.ZenSpace,
    onDeleteTab: (String) -> Unit,
    onOpenUrl: (String, String?) -> Unit,
) {
    val fallbackName = stringResource(R.string.share_workspace)
    val listState = rememberLazyListState()

    val showTopStroke by remember {
        derivedStateOf { listState.canScrollBackward }
    }
    val showBottomStroke by remember {
        derivedStateOf { listState.canScrollForward }
    }

    Column(Modifier.fillMaxSize()) {
        // Space title stays fixed; only the tab list below scrolls.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 8.dp),
        ) {
            if (!space.icon.isNullOrEmpty()) {
                // Same 28dp slot + 12dp gap as a tab row's favicon, so the
                // header and the tab titles share one text column.
                Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) {
                    ZenIconView(icon = space.icon, size = 24.dp)
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = space.name.ifEmpty { fallbackName },
                style = ZenType.spaceTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            for (item in space.pinned) {
                when (item) {
                    is ZenSpaces.ZenItem.Tab -> {
                        item(key = item.id) {
                            TabRow(
                                tab = item.tab,
                                deletable = true,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                onDelete = { onDeleteTab(item.id) },
                                onOpenUrl = onOpenUrl,
                            )
                        }
                    }

                    is ZenSpaces.ZenItem.Folder -> {
                        item(key = item.id) {
                            FolderBlock(
                                folder = item.folder,
                                onDeleteTab = onDeleteTab,
                                onOpenUrl = onOpenUrl,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }

                    is ZenSpaces.ZenItem.Split -> {
                        item(key = item.id) {
                            SplitRow(
                                split = item.split,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                onDelete = { onDeleteTab(item.id) },
                                onOpenUrl = onOpenUrl,
                            )
                        }
                    }
                }
            }

            // Normal (unpinned) tabs synced via the desktop opt-in
            // `zen.spaces-sync.normal-tabs`, separated from pinned by a divider.
            if (space.tabs.isNotEmpty()) {
                if (space.pinned.isNotEmpty()) {
                    item(key = "normal-divider") {
                        // Ink-based (not `outlineVariant`): adapts to light/dark
                        // spaces and stays visible on dark gradients. 1dp at 35%
                        // ink so the pinned / normal section break reads.
                        HorizontalDivider(
                            color = LocalZenColors.current.ink.copy(alpha = 0.35f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
                for (item in space.tabs) {
                    when (item) {
                        is ZenSpaces.ZenItem.Tab -> {
                            item(key = item.id) {
                                TabRow(
                                    tab = item.tab,
                                    deletable = true,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    onDelete = { onDeleteTab(item.id) },
                                    onOpenUrl = onOpenUrl,
                                )
                            }
                        }

                        is ZenSpaces.ZenItem.Split -> {
                            item(key = item.id) {
                                SplitRow(
                                    split = item.split,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    onDelete = { onDeleteTab(item.id) },
                                    onOpenUrl = onOpenUrl,
                                )
                            }
                        }

                        is ZenSpaces.ZenItem.Folder -> {
                            item(key = item.id) {
                                FolderBlock(
                                    folder = item.folder,
                                    onDeleteTab = onDeleteTab,
                                    onOpenUrl = onOpenUrl,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (space.pinned.isEmpty() && space.tabs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.tabs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = 12.dp),
                    )
                }
            }
        }

        // Top Stroke (Visible only when content is scrolled past top edge)
        androidx.compose.animation.AnimatedVisibility(
            visible = showTopStroke,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            HorizontalDivider(
                color = LocalZenColors.current.ink.copy(alpha = 0.22f),
            )
        }

        // Bottom Stroke (Visible only when remaining content extends below bottom edge)
        androidx.compose.animation.AnimatedVisibility(
            visible = showBottomStroke,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            HorizontalDivider(
                color = LocalZenColors.current.ink.copy(alpha = 0.22f),
            )
        }
        }
    }
}
