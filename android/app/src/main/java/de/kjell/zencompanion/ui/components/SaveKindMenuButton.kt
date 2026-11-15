package de.kjell.zencompanion.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.util.Haptics

/**
 * Round per-save kind menu beside the destination. It keeps the pin/tab-stack
 * look and opens the pinned/normal choice on tap.
 *
 * The same control in the share sheet and in the mini-browser's save banner,
 * so a save looks and behaves the same wherever it starts. Only shown when the
 * synced browser can write normal tabs; leaving it untouched keeps the
 * persisted setting.
 */
@Composable
internal fun SaveKindMenuButton(
    selected: SaveKind,
    onSelect: (SaveKind) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onMenuWillOpen: () -> Unit = {},
    onMenuDidDismiss: () -> Unit = {},
) {
    val colors = LocalZenColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val saveAs = stringResource(R.string.share_save_as)
    val kindLabel = stringResource(
        if (selected == SaveKind.PINNED) {
            R.string.share_save_kind_pinned
        } else {
            R.string.share_save_kind_normal
        },
    )
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Surface(
                onClick = {
                    onMenuWillOpen()
                    menuOpen = true
                },
                enabled = enabled,
                shape = CircleShape,
                color = colors.lift,
                border = liftBorder(),
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (enabled) 1f else 0.55f)
                    .semantics {
                        contentDescription = saveAs
                        stateDescription = kindLabel
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SaveKindIcon(kind = selected, size = 16.dp, tint = colors.ink)
                }
            }
        }

        ZenMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                onMenuDidDismiss()
            },
        ) {
            ZenMenuGroup(index = 0, count = 1) {
                SaveKind.entries.forEachIndexed { index, kind ->
                    val isSelected = selected == kind
                    ZenSelectableMenuItem(
                        selected = isSelected,
                        onClick = {
                            menuOpen = false
                            Haptics.perform(context, Haptics.Kind.SELECTION)
                            // Picking the kind that is already set changes nothing, so
                            // nothing restarts what opening the menu paused: the caller
                            // still has to be told the menu is gone.
                            if (kind == selected) onMenuDidDismiss()
                            onSelect(kind)
                        },
                        text = {
                            Text(
                                text = stringResource(
                                    if (kind == SaveKind.PINNED) {
                                        R.string.share_save_kind_pinned_menu
                                    } else {
                                        R.string.share_save_kind_normal_menu
                                    },
                                ),
                                style = ZenType.rounded(15, FontWeight.Medium),
                            )
                        },
                        index = index,
                        count = SaveKind.entries.size,
                        leadingIcon = {
                            SaveKindIcon(kind = kind, size = 18.dp, tint = colors.ink)
                        },
                        trailingContent = if (isSelected) ({ ZenMenuCheck() }) else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SaveKindIcon(kind: SaveKind, size: Dp, tint: Color) {
    if (kind == SaveKind.PINNED) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_tab_stack),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
