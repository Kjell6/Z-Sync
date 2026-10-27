package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LiftCard
import de.kjell.zencompanion.ui.theme.LocalZenColors

/** Small uppercase label above a card. */
@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalZenColors.current.ink.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * "Browsing": how the app picks a search engine, opens links and blocks
 * content. Content blocking is the only row here that leaves the sheet.
 */
@Composable
internal fun SettingsBrowsingSection(
    preferences: PreferencesState,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onOpenAddSearchEngine: () -> Unit,
    onSetOpenIntoSearch: (Boolean) -> Unit,
    onOpenContentBlocking: () -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_browsing_section))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SearchEngineRow(
                    selected = preferences.searchEngine,
                    engines = SearchEngines.builtIn + preferences.customSearchEngines,
                    onSelect = onSetSearchEngine,
                    onAdd = onOpenAddSearchEngine,
                )

                SettingsDivider()

                ToggleRow(
                    title = stringResource(R.string.settings_open_into_search),
                    subtitle = stringResource(R.string.settings_open_into_search_subtitle),
                    icon = Icons.Outlined.Keyboard,
                    checked = preferences.openIntoSearch,
                    onCheckedChange = onSetOpenIntoSearch,
                )

                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenContentBlocking)
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_content_blocking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                SettingsDivider()

                ToggleRow(
                    title = stringResource(R.string.settings_external_browser),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    checked = preferences.alwaysOpenExternally,
                    onCheckedChange = onSetAlwaysOpenExternally,
                )
            }
        }
    }
}

/**
 * The engine list as a menu on the row itself — the list is short enough that
 * a page would only add a tap. Custom engines are added from the same menu and
 * managed in their own card.
 */
@Composable
private fun SearchEngineRow(
    selected: SearchEngine,
    engines: List<SearchEngine>,
    onSelect: (SearchEngine) -> Unit,
    onAdd: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuExpanded = true }
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_search_engine),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = selected.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            engines.forEach { engine ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = engine.displayName,
                            fontWeight = if (engine == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (engine == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    trailingIcon = if (engine == selected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    onClick = {
                        onSelect(engine)
                        menuExpanded = false
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.settings_search_engine_add_action),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onAdd()
                },
            )
        }
    }
}

/**
 * Custom engines stay removable without opening the picker, so they get a card
 * of their own. Hidden until the user adds one.
 */
@Composable
internal fun SettingsCustomEnginesCard(
    engines: List<SearchEngine>,
    onDeleteSearchEngine: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_search_engine_custom_section))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                engines.forEachIndexed { index, engine ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = engine.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onDeleteSearchEngine(engine.id) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.settings_search_engine_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Layout": how the essentials grid and the action bar look. */
@Composable
internal fun SettingsLayoutSection(
    preferences: PreferencesState,
    onSetEssentialsColumns: (EssentialsColumns) -> Unit,
    onSetToolbarPlacement: (ToolbarPlacement) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_layout_section))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                var columnsExpanded by remember { mutableStateOf(false) }
                var toolbarExpanded by remember { mutableStateOf(false) }
                val columns = preferences.essentialsColumns
                val toolbar = preferences.toolbarPlacement

                Box(modifier = Modifier.fillMaxWidth()) {
                    MenuRow(
                        title = stringResource(R.string.settings_essentials_columns),
                        value = columns.label(),
                        icon = Icons.Outlined.GridView,
                        onClick = { columnsExpanded = true },
                    )
                    DropdownMenu(
                        expanded = columnsExpanded,
                        onDismissRequest = { columnsExpanded = false },
                    ) {
                        EssentialsColumns.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { MenuItemText(option.label(), option == columns) },
                                trailingIcon = if (option == columns) ({ MenuItemCheck() }) else null,
                                onClick = {
                                    onSetEssentialsColumns(option)
                                    columnsExpanded = false
                                },
                            )
                        }
                    }
                }

                SettingsDivider()

                Box(modifier = Modifier.fillMaxWidth()) {
                    MenuRow(
                        title = stringResource(R.string.settings_toolbar),
                        value = stringResource(toolbar.labelRes()),
                        icon = Icons.Outlined.SwapVert,
                        onClick = { toolbarExpanded = true },
                    )
                    DropdownMenu(
                        expanded = toolbarExpanded,
                        onDismissRequest = { toolbarExpanded = false },
                    ) {
                        ToolbarPlacement.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    MenuItemText(
                                        stringResource(option.labelRes()),
                                        option == toolbar,
                                    )
                                },
                                trailingIcon = if (option == toolbar) ({ MenuItemCheck() }) else null,
                                onClick = {
                                    onSetToolbarPlacement(option)
                                    toolbarExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Row that opens a menu: icon, title, current value, drop-down caret. */
@Composable
private fun MenuRow(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Row with a switch and an optional second line of explanation. */
@Composable
private fun ToggleRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun MenuItemText(text: String, selected: Boolean) {
    Text(
        text = text,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MenuItemCheck() {
    Icon(
        imageVector = Icons.Outlined.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
}

/** "Automatic", or the bare number of essentials per row. */
@Composable
private fun EssentialsColumns.label(): String = when (this) {
    EssentialsColumns.AUTOMATIC -> stringResource(R.string.settings_essentials_columns_automatic)
    else -> storageValue
}

private fun ToolbarPlacement.labelRes(): Int = when (this) {
    ToolbarPlacement.TOP -> R.string.settings_toolbar_top
    ToolbarPlacement.BOTTOM -> R.string.settings_toolbar_bottom
}
