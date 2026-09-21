package de.kjell.zencompanion.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.motion.DampingSharePicker
import de.kjell.zencompanion.ui.motion.StiffnessSharePicker
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType

/** Capsule (fully-rounded pill) used by the share-style trigger. */
private val Capsule = RoundedCornerShape(50)

/**
 * Pre-unification share-screen appearance (port of the iOS
 * PinDestinationMenuButton): a capsule trigger showing the current
 * destination, spaces on top, divider, then a "Pin to Folder…" item that
 * opens a nested menu with per-space groups where folders appear as
 * indented plain-text rows.
 */
@Composable
internal fun ShareCapsulePicker(
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
    val colors = LocalZenColors.current
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 28.dp)
                .background(colors.lift, Capsule)
                .clickable {
                    onMenuWillOpen()
                    onSpaceMenuOpenChange(true)
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            // Changing destinations animates with spring (+ CLOCK_TICK haptic).
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val spec = androidx.compose.animation.core.spring<Float>(
                        dampingRatio = DampingSharePicker,
                        stiffness = StiffnessSharePicker,
                    )
                    (fadeIn(spec) + scaleIn(initialScale = 0.96f, animationSpec = spec))
                        .togetherWith(fadeOut(spec))
                },
                label = "pickerContent",
            ) { target ->
                val current = spaces.firstOrNull { it.id == target?.spaceId }
                val label = current?.let { PinDestinationModel.displayName(destination = target!!, space = it) }
                    ?: stringResource(R.string.share_workspace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        target?.hasFolder == true -> {
                            Box(
                                Modifier.size(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_folder),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.ink),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        !current?.icon.isNullOrEmpty() -> {
                            ZenIconView(icon = current?.icon, size = 18.dp)
                        }
                        else -> {
                            Box(
                                Modifier
                                    .size(6.5.dp)
                                    .background(colors.ink85, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = label,
                        style = ZenType.rounded(17, FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(9.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_chevron_up_down),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colors.ink.copy(alpha = 0.5f)),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = isSpaceMenuOpen,
            onDismissRequest = {
                onSpaceMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            // Main level: the spaces (tap = pin to the space root).
            for (space in spaces) {
                val isSelected = space.id == selected?.spaceId && selected?.hasFolder != true
                DropdownItemWithIcon(
                    label = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                    icon = space.icon,
                    checked = isSelected,
                    onClick = {
                        onSpaceMenuOpenChange(false)
                        onSelect(PinDestination(spaceId = space.id))
                    },
                )
            }

            if (!hideFolders) {
                // Divider, then the folder submenu entry.
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.pin_folder_menu),
                            style = ZenType.rounded(15, FontWeight.Medium),
                        )
                    },
                    leadingIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_folder_badge_plus),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colors.ink),
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        // Close the main menu and open the folder menu as a
                        // SIBLING popup (same trigger anchor). A popup nested
                        // inside another popup's content positions itself at
                        // the window corner and gets clipped.
                        onSpaceMenuOpenChange(false)
                        onFolderMenuOpenChange(true)
                    },
                )
            }
        }

        // Folder submenu — sibling of the main menu, anchored to the same
        // trigger capsule. NOT nested inside the main menu's popup.
        DropdownMenu(
            expanded = isFolderMenuOpen,
            onDismissRequest = {
                onFolderMenuOpenChange(false)
                onMenuDidDismiss()
            },
        ) {
            for (space in spaces) {
                val entries = PinDestinationModel.folderEntries(space)
                if (entries.isEmpty()) continue
                // Space header (no checkmark here — the selection
                // marker lives only in the main menu).
                DropdownItemWithIcon(
                    label = space.name.ifEmpty { stringResource(R.string.share_workspace) },
                    icon = space.icon,
                    checked = false,
                    onClick = {
                        onFolderMenuOpenChange(false)
                        onSelect(PinDestination(spaceId = space.id))
                    },
                )
                // Folders as indented plain-text rows, outer folders
                // before their nested children. Depth + 1 because even
                // top-level folders indent one level relative to the
                // space header row.
                for (entry in entries) {
                    val indent = "\u2003\u2003".repeat(entry.depth + 1)
                    val entryName = entry.name.ifEmpty { stringResource(R.string.tabs_folder) }
                    val isSelected = space.id == selected?.spaceId && selected?.folderId == entry.folderId
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = indent + entryName,
                                style = ZenType.rounded(15, FontWeight.Normal),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Image(
                                    painter = painterResource(R.drawable.ic_checkmark),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.ink),
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
            }
        }
    }
}

/** Menu row: icon + label + optional trailing checkmark. */
@Composable
private fun DropdownItemWithIcon(
    label: String,
    icon: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalZenColors.current
    DropdownMenuItem(
        text = {
            Text(label, style = ZenType.rounded(15, FontWeight.Medium))
        },
        leadingIcon = {
            if (!icon.isNullOrEmpty()) {
                ZenIconView(icon = icon, size = 24.dp)
            } else {
                Box(
                    Modifier
                        .size(6.5.dp)
                        .background(colors.ink85, CircleShape),
                )
            }
        },
        trailingIcon = {
            if (checked) {
                Image(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.ink),
                    modifier = Modifier.size(14.dp),
                )
            }
        },
        onClick = onClick,
    )
}
