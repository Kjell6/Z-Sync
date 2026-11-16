package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenMenuGroupGap
import de.kjell.zencompanion.ui.theme.ZenMenuItem
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem
import de.kjell.zencompanion.ui.theme.zenMenuItemColors

@Composable
internal fun BrowserBannerPicker(
    spaces: List<ZenSpaces.ZenSpace>,
    selected: PinDestination?,
    isSpaceMenuOpen: Boolean,
    isFolderMenuOpen: Boolean,
    onSelect: (PinDestination) -> Unit,
    onSpaceMenuOpenChange: (Boolean) -> Unit,
    onFolderMenuOpenChange: (Boolean) -> Unit,
    onMenuWillOpen: () -> Unit,
    onMenuDidDismiss: () -> Unit,
    modifier: Modifier,
    hideFolders: Boolean = false,
) {
    Box(modifier) {
        val selectedSpace = spaces.firstOrNull { it.id == selected?.spaceId }
        Surface(
            onClick = {
                onMenuWillOpen()
                onSpaceMenuOpenChange(true)
            },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val label = if (selectedSpace != null && selected != null) {
                    PinDestinationModel.displayName(destination = selected, space = selectedSpace)
                } else {
                    stringResource(R.string.share_workspace)
                }
                when {
                    selected?.hasFolder == true -> {
                        Image(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    !selectedSpace?.icon.isNullOrEmpty() -> {
                        ZenIconView(icon = selectedSpace?.icon, size = 18.dp)
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        ZenMenu(
            expanded = isSpaceMenuOpen,
            onDismissRequest = {
                onSpaceMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            // Main level: spaces (tap = space root), then "Pin to Folder…".
            val groupCount = if (hideFolders) 1 else 2
            ZenMenuGroup(index = 0, count = groupCount) {
                spaces.forEachIndexed { index, space ->
                    val isSelected = space.id == selected?.spaceId && selected?.hasFolder == false
                    ZenSelectableMenuItem(
                        selected = isSelected,
                        onClick = {
                            onSpaceMenuOpenChange(false)
                            onSelect(PinDestination(spaceId = space.id))
                        },
                        text = {
                            Text(
                                text = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        index = index,
                        count = spaces.size,
                        leadingIcon = space.icon?.takeIf { it.isNotEmpty() }?.let { icon ->
                            { ZenIconView(icon = icon, size = 18.dp) }
                        },
                        trailingContent = if (isSelected) ({ ZenMenuCheck() }) else null,
                    )
                }
            }

            if (!hideFolders) {
                ZenMenuGroupGap()
                ZenMenuGroup(index = 1, count = groupCount) {
                    // "Pin to Folder…" submenu.
                    ZenMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.pin_folder_menu),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = {
                            onSpaceMenuOpenChange(false)
                            onFolderMenuOpenChange(true)
                        },
                        index = 0,
                        count = 1,
                        leadingIcon = {
                            Image(
                                painter = painterResource(R.drawable.ic_folder_badge_plus),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = zenMenuItemColors(),
                    )
                }
            }
        }

        // Folder submenu: per-space groups, space name first (no checkmark),
        // then folders as indented text rows.
        ZenMenu(
            expanded = isFolderMenuOpen,
            onDismissRequest = {
                onFolderMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            val spaceGroups = spaces.mapNotNull { space ->
                PinDestinationModel.folderEntries(space)
                    .takeIf { it.isNotEmpty() }
                    ?.let { space to it }
            }
            spaceGroups.forEachIndexed { groupIndex, (space, entries) ->
                if (groupIndex > 0) ZenMenuGroupGap()
                ZenMenuGroup(index = groupIndex, count = spaceGroups.size) {
                    ZenSelectableMenuItem(
                        selected = false,
                        onClick = {
                            onFolderMenuOpenChange(false)
                            onSelect(PinDestination(spaceId = space.id))
                        },
                        text = {
                            Text(
                                text = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        index = 0,
                        count = entries.size + 1,
                        leadingIcon = space.icon?.takeIf { it.isNotEmpty() }?.let { icon ->
                            { ZenIconView(icon = icon, size = 18.dp) }
                        },
                    )
                    for ((index, entry) in entries.withIndex()) {
                        // Depth + 1: even top-level folders indent one level
                        // relative to the space header row.
                        val indent = "\u2003\u2003".repeat(entry.depth + 1)
                        val entryName = entry.name.ifEmpty { stringResource(R.string.tabs_folder) }
                        val isSelected = space.id == selected?.spaceId &&
                            selected?.folderId == entry.folderId
                        ZenSelectableMenuItem(
                            selected = isSelected,
                            onClick = {
                                onFolderMenuOpenChange(false)
                                onSelect(PinDestination(spaceId = space.id, folderId = entry.folderId))
                            },
                            text = {
                                Text(
                                    text = indent + entryName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            index = index + 1,
                            count = entries.size + 1,
                            trailingContent = if (isSelected) ({ ZenMenuCheck() }) else null,
                        )
                    }
                }
            }
        }
    }
}
