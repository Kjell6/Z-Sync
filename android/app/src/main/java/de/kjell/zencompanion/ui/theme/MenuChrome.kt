package de.kjell.zencompanion.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.SelectableMenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Expressive Material 3 menu, in the app's colors.
 *
 * A menu is a popup of one or more rounded groups ([ZenMenuGroup]); items
 * morph their corners as the finger moves between them. Items come in two
 * flavours: [ZenMenuItem] for actions and [ZenSelectableMenuItem] for a
 * single-choice list, where the current row shows a checkmark. Call sites pass
 * the item's [index] and the group's item count so the corners round off at
 * the top and bottom edges of the group.
 */

/** The popup itself. Put [ZenMenuGroup]s in it; separate them with [ZenMenuGroupGap]. */
@Composable
internal fun ZenMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        // Unlike DropdownMenu, the popup does not scroll by itself: a long
        // list (a space with many folders) must still reach its last row.
        // The scroll clips to its bounds, so it leaves the groups' shadows
        // room above and below instead of cutting them off square.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = MenuShadowRoom),
            content = content,
        )
    }
}

/** Room the scrolling menu leaves for its groups' shadows. */
private val MenuShadowRoom = 6.dp

/**
 * One rounded group inside [ZenMenu]. [index] of [count] decides whether the
 * group rounds off at both ends or joins the next group, the same way the
 * items inside it do.
 */
@Composable
internal fun ZenMenuGroup(
    index: Int,
    count: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(index, count),
        // The app's own surface container (never the baseline M3 purple-grey)
        // plus the hairline rim every other card in the app draws.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        border = liftBorder(),
        content = content,
    )
}

/** Gap between two [ZenMenuGroup]s: the Expressive segment gap. */
@Composable
internal fun ZenMenuGroupGap() {
    Spacer(Modifier.height(MenuDefaults.GroupSpacing))
}

/** An action row: icon, label, optional second line and trailing content. */
@Composable
internal fun ZenMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
) {
    DropdownMenuItem(
        onClick = onClick,
        text = text,
        shape = MenuDefaults.itemShape(index = index, count = count).shape,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingContent = trailingContent,
        supportingText = supportingText,
        enabled = enabled,
        colors = colors,
    )
}

/**
 * A row in a single-choice list. The selected row is tinted with the app's
 * primary color and shows a checkmark; when [selectedLeadingIcon] is given (and
 * there is no [leadingIcon]) the checkmark slides into the leading slot, which
 * is how the Expressive selectable item marks a choice.
 */
@Composable
internal fun ZenSelectableMenuItem(
    selected: Boolean,
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    selectedLeadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: SelectableMenuItemColors = zenSelectableMenuItemColors(),
) {
    SelectableDropdownMenuItem(
        selected = selected,
        onClick = onClick,
        text = text,
        shapes = MenuDefaults.itemShape(index = index, count = count),
        modifier = modifier,
        leadingIcon = leadingIcon,
        selectedLeadingIcon = selectedLeadingIcon,
        trailingContent = trailingContent,
        enabled = enabled,
        colors = colors,
    )
}

/** The checkmark a selected row in a [ZenSelectableMenuItem] list shows. */
@Composable
internal fun ZenMenuCheck() {
    Icon(
        imageVector = Icons.Outlined.Check,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * Colors for [ZenMenuItem]. Pass [textColor] and [iconColor] for a destructive
 * or accented row; the defaults are the app's ink and quiet ink.
 */
@Composable
internal fun zenMenuItemColors(
    textColor: Color = Color.Unspecified,
    iconColor: Color = Color.Unspecified,
): MenuItemColors =
    MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = iconColor,
        trailingIconColor = iconColor,
    )

/**
 * Selectable row colors in the app's palette. The library default tints a
 * selected row with `tertiaryContainer`, which the app's color scheme never
 * sets, so it would land on the baseline purple; this keeps the app's primary.
 */
@Composable
internal fun zenSelectableMenuItemColors(): SelectableMenuItemColors {
    val scheme = MaterialTheme.colorScheme
    val disabled = scheme.onSurface.copy(alpha = 0.38f)
    return MenuDefaults.selectableItemColors(
        textColor = scheme.onSurface,
        containerColor = Color.Transparent,
        leadingIconColor = scheme.onSurfaceVariant,
        trailingContentColor = scheme.onSurfaceVariant,
        selectedTextColor = scheme.primary,
        selectedContainerColor = scheme.primary.copy(alpha = 0.12f),
        selectedLeadingIconColor = scheme.primary,
        selectedTrailingContentColor = scheme.primary,
        disabledTextColor = disabled,
        disabledContainerColor = Color.Transparent,
        disabledLeadingIconColor = disabled,
        disabledTrailingContentColor = disabled,
    )
}
