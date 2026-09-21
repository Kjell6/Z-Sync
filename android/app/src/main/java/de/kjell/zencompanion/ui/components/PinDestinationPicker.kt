package de.kjell.zencompanion.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.kjell.zencompanion.sync.ZenSpaces

/**
 * Visual variants for [PinDestinationPicker].
 *
 * [BrowserBanner] is the compact inline row used inside the mini-browser pin
 * banner. [ShareCapsule] is the share screen's original appearance: an
 * animated capsule trigger with icon-led menu rows, dot fallbacks for spaces
 * without an icon and a checkmark on the selected destination.
 */
enum class PinDestinationPickerStyle { BrowserBanner, ShareCapsule }

/**
 * Destination selector dropdown (space + optional folder via "Pin to
 * Folder…" submenu) shared by the mini browser pin banner and the share
 * screen. Menu open state is hoisted so callers can coordinate their own
 * timers/back handling.
 */
@Composable
fun PinDestinationPicker(
    spaces: List<ZenSpaces.ZenSpace>,
    selected: PinDestination?,
    isSpaceMenuOpen: Boolean,
    isFolderMenuOpen: Boolean,
    onSelect: (PinDestination) -> Unit,
    onSpaceMenuOpenChange: (Boolean) -> Unit,
    onFolderMenuOpenChange: (Boolean) -> Unit,
    onMenuWillOpen: () -> Unit,
    onMenuDidDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    style: PinDestinationPickerStyle = PinDestinationPickerStyle.BrowserBanner,
    /** Normal saves always target the space root, so folder rows are hidden. */
    hideFolders: Boolean = false,
) {
    when (style) {
        PinDestinationPickerStyle.BrowserBanner -> BrowserBannerPicker(
            spaces = spaces,
            selected = selected,
            isSpaceMenuOpen = isSpaceMenuOpen,
            isFolderMenuOpen = isFolderMenuOpen,
            onSelect = onSelect,
            onSpaceMenuOpenChange = onSpaceMenuOpenChange,
            onFolderMenuOpenChange = onFolderMenuOpenChange,
            onMenuWillOpen = onMenuWillOpen,
            onMenuDidDismiss = onMenuDidDismiss,
            modifier = modifier,
            hideFolders = hideFolders,
        )

        PinDestinationPickerStyle.ShareCapsule -> ShareCapsulePicker(
            spaces = spaces,
            selected = selected,
            isSpaceMenuOpen = isSpaceMenuOpen,
            isFolderMenuOpen = isFolderMenuOpen,
            onSelect = onSelect,
            onSpaceMenuOpenChange = onSpaceMenuOpenChange,
            onFolderMenuOpenChange = onFolderMenuOpenChange,
            onMenuWillOpen = onMenuWillOpen,
            onMenuDidDismiss = onMenuDidDismiss,
            modifier = modifier,
            hideFolders = hideFolders,
        )
    }
}
