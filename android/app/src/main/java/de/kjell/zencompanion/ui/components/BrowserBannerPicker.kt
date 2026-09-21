package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces

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
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    onMenuWillOpen()
                    onSpaceMenuOpenChange(true)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
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

        DropdownMenu(
            expanded = isSpaceMenuOpen,
            onDismissRequest = {
                onSpaceMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            // Main level: spaces (tap = space root).
            spaces.forEach { space ->
                val isSelected = space.id == selected?.spaceId && selected?.hasFolder == false
                DropdownMenuItem(
                    text = {
                        Text(
                            text = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = {
                        if (!space.icon.isNullOrEmpty()) {
                            ZenIconView(icon = space.icon, size = 18.dp)
                        }
                    },
                    onClick = {
                        onSpaceMenuOpenChange(false)
                        onSelect(PinDestination(spaceId = space.id))
                    },
                )
            }

            if (!hideFolders) {
                HorizontalDivider()

                // "Pin to Folder…" submenu.
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.pin_folder_menu),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_folder_badge_plus),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        onSpaceMenuOpenChange(false)
                        onFolderMenuOpenChange(true)
                    },
                )
            }
        }

        // Folder submenu: per-space groups, space
        // name first (no checkmark), then folders as
        // indented text rows.
        DropdownMenu(
            expanded = isFolderMenuOpen,
            onDismissRequest = {
                onFolderMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            spaces.forEach { space ->
                val entries = PinDestinationModel.folderEntries(space)
                if (entries.isEmpty()) return@forEach

                DropdownMenuItem(
                    text = {
                        Text(
                            text = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        if (!space.icon.isNullOrEmpty()) {
                            ZenIconView(icon = space.icon, size = 18.dp)
                        }
                    },
                    onClick = {
                        onFolderMenuOpenChange(false)
                        onSelect(PinDestination(spaceId = space.id))
                    },
                )
                for (entry in entries) {
                    // Depth + 1: even top-level folders
                    // indent one level relative to the
                    // space header row.
                    val indent = "\u2003\u2003".repeat(entry.depth + 1)
                    val entryName = entry.name.ifEmpty { stringResource(R.string.tabs_folder) }
                    val isSelected = space.id == selected?.spaceId && selected?.folderId == entry.folderId
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = indent + entryName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Image(
                                    painter = painterResource(R.drawable.ic_checkmark),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        },
                        onClick = {
                            onFolderMenuOpenChange(false)
                            onSelect(PinDestination(spaceId = space.id, folderId = entry.folderId))
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
