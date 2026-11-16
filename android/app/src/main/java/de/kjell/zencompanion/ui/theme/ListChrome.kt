package de.kjell.zencompanion.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The app's grouped list: one rounded card per section holding native
 * Material 3 list items. The card keeps the app's lift fill and hairline rim;
 * the items are transparent, so the native press morph and ripple read on top
 * of that fill.
 */
@Composable
internal fun ZenListGroup(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val zen = LocalZenColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = zen.lift,
        border = liftBorder(),
    ) {
        Column(content = content)
    }
}

/**
 * Transparent list item colors so a row sits on its [ZenListGroup] instead of
 * painting a second surface over it. Headlines are ink, everything else is the
 * quieter ink the rest of the app uses for icons and supporting lines.
 */
@Composable
internal fun zenListItemColors(containerColor: Color = Color.Transparent): ListItemColors =
    ListItemDefaults.colors(
        containerColor = containerColor,
        headlineColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * One native Material 3 list item row: leading icon or avatar, a headline, an
 * optional second line and optional trailing content (a value, a chevron, a
 * switch, a button). With [onClick] the row gets Material's press morph and
 * ripple; without it the row is read-only.
 */
@Composable
internal fun ZenListItem(
    headline: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = zenListItemColors(),
    /** Page titles and addresses: one line each, cut with an ellipsis, so a
     * row keeps its height and its leading icon stays centred. */
    singleLine: Boolean = false,
) {
    val maxLines = if (singleLine) 1 else Int.MAX_VALUE
    val overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip
    val label: @Composable () -> Unit = {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
    val supporting: (@Composable () -> Unit)? = supportingText?.let { line ->
        {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = overflow,
            )
        }
    }
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supporting,
            colors = colors,
            content = label,
        )
    } else {
        ListItem(
            modifier = modifier,
            enabled = enabled,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supporting,
            colors = colors,
            content = label,
        )
    }
}

/**
 * A [ZenListItem] that flips a setting: the whole row is the target and the
 * native switch rides along on the right, with a checkmark in its thumb while
 * the setting is on.
 */
@Composable
internal fun ZenSwitchListItem(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    supportingText: String? = null,
) {
    ZenListItem(
        headline = headline,
        // One target for TalkBack: the row carries the label, the state and
        // the switch role; the switch itself only shows it.
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        leadingContent = icon?.let { vector ->
            { Icon(imageVector = vector, contentDescription = null, modifier = Modifier.size(22.dp)) }
        },
        supportingText = supportingText,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        },
    )
}

/** The quiet trailing value of a row that opens a chooser. */
@Composable
internal fun ZenListValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
