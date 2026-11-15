package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenListValue
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenMenuGroupGap
import de.kjell.zencompanion.ui.theme.ZenMenuItem
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem
import de.kjell.zencompanion.ui.theme.ZenSwitchListItem
import de.kjell.zencompanion.ui.theme.zenMenuItemColors

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
    onSetKeepOnlyChosenPages: (Boolean) -> Unit,
    onSetKeptPageLifetime: (KeptPageLifetime) -> Unit,
    onOpenContentBlocking: () -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
    onClearWebsiteData: () -> Unit,
) {
    var confirmClearData by remember { mutableStateOf(false) }
    if (confirmClearData) {
        AlertDialog(
            onDismissRequest = { confirmClearData = false },
            title = { Text(stringResource(R.string.settings_clear_website_data)) },
            text = { Text(stringResource(R.string.settings_clear_website_data_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearData = false
                        onClearWebsiteData()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.settings_clear_website_data)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClearData = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_browsing_section))

        ZenListGroup {
            SearchEngineRow(
                selected = preferences.searchEngine,
                engines = SearchEngines.builtIn + preferences.customSearchEngines,
                onSelect = onSetSearchEngine,
                onAdd = onOpenAddSearchEngine,
            )

            ZenSwitchListItem(
                headline = stringResource(R.string.settings_open_into_search),
                supportingText = stringResource(R.string.settings_open_into_search_subtitle),
                icon = Icons.Outlined.Keyboard,
                checked = preferences.openIntoSearch,
                onCheckedChange = onSetOpenIntoSearch,
            )

            ZenSwitchListItem(
                headline = stringResource(R.string.settings_keep_only_chosen),
                supportingText = stringResource(R.string.settings_keep_only_chosen_subtitle),
                icon = Icons.Outlined.BookmarkBorder,
                checked = preferences.keepOnlyChosenPages,
                onCheckedChange = onSetKeepOnlyChosenPages,
            )

            var lifetimeExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsMenuRow(
                    title = stringResource(R.string.settings_kept_lifetime),
                    value = preferences.keptPageLifetime.label(),
                    icon = Icons.Outlined.Schedule,
                    onClick = { lifetimeExpanded = true },
                )
                ZenMenu(
                    expanded = lifetimeExpanded,
                    onDismissRequest = { lifetimeExpanded = false },
                ) {
                    val options = KeptPageLifetime.entries
                    ZenMenuGroup(index = 0, count = 1) {
                        options.forEachIndexed { index, option ->
                            ZenSelectableMenuItem(
                                selected = option == preferences.keptPageLifetime,
                                onClick = {
                                    onSetKeptPageLifetime(option)
                                    lifetimeExpanded = false
                                },
                                text = { Text(option.label()) },
                                index = index,
                                count = options.size,
                                selectedLeadingIcon = { ZenMenuCheck() },
                            )
                        }
                    }
                }
            }

            ZenListItem(
                headline = stringResource(R.string.settings_content_blocking),
                onClick = onOpenContentBlocking,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            ZenSwitchListItem(
                headline = stringResource(R.string.settings_external_browser),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                checked = preferences.alwaysOpenExternally,
                onCheckedChange = onSetAlwaysOpenExternally,
            )

            SettingsActionRow(
                title = stringResource(R.string.settings_clear_website_data),
                icon = Icons.Outlined.DeleteOutline,
                onClick = { confirmClearData = true },
            )
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
        ZenListItem(
            headline = stringResource(R.string.settings_search_engine),
            onClick = { menuExpanded = true },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZenListValue(selected.displayName)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        ZenMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            ZenMenuGroup(index = 0, count = 2) {
                engines.forEachIndexed { index, engine ->
                    ZenSelectableMenuItem(
                        selected = engine == selected,
                        onClick = {
                            onSelect(engine)
                            menuExpanded = false
                        },
                        text = { Text(engine.displayName) },
                        index = index,
                        count = engines.size,
                        selectedLeadingIcon = { ZenMenuCheck() },
                    )
                }
            }
            ZenMenuGroupGap()
            ZenMenuGroup(index = 1, count = 2) {
                ZenMenuItem(
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
                    index = 0,
                    count = 1,
                    colors = zenMenuItemColors(textColor = MaterialTheme.colorScheme.primary),
                )
            }
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

        ZenListGroup {
            engines.forEach { engine ->
                ZenListItem(
                    headline = engine.displayName,
                    trailingContent = {
                        IconButton(
                            onClick = { onDeleteSearchEngine(engine.id) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.settings_search_engine_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
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

        ZenListGroup {
            var columnsExpanded by remember { mutableStateOf(false) }
            var toolbarExpanded by remember { mutableStateOf(false) }
            val columns = preferences.essentialsColumns
            val toolbar = preferences.toolbarPlacement

            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsMenuRow(
                    title = stringResource(R.string.settings_essentials_columns),
                    value = columns.label(),
                    icon = Icons.Outlined.GridView,
                    onClick = { columnsExpanded = true },
                )
                ZenMenu(
                    expanded = columnsExpanded,
                    onDismissRequest = { columnsExpanded = false },
                ) {
                    val options = EssentialsColumns.entries
                    ZenMenuGroup(index = 0, count = 1) {
                        options.forEachIndexed { index, option ->
                            ZenSelectableMenuItem(
                                selected = option == columns,
                                onClick = {
                                    onSetEssentialsColumns(option)
                                    columnsExpanded = false
                                },
                                text = { Text(option.label()) },
                                index = index,
                                count = options.size,
                                selectedLeadingIcon = { ZenMenuCheck() },
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsMenuRow(
                    title = stringResource(R.string.settings_toolbar),
                    value = stringResource(toolbar.labelRes()),
                    icon = Icons.Outlined.SwapVert,
                    onClick = { toolbarExpanded = true },
                )
                ZenMenu(
                    expanded = toolbarExpanded,
                    onDismissRequest = { toolbarExpanded = false },
                ) {
                    val options = ToolbarPlacement.entries
                    ZenMenuGroup(index = 0, count = 1) {
                        options.forEachIndexed { index, option ->
                            ZenSelectableMenuItem(
                                selected = option == toolbar,
                                onClick = {
                                    onSetToolbarPlacement(option)
                                    toolbarExpanded = false
                                },
                                text = { Text(stringResource(option.labelRes())) },
                                index = index,
                                count = options.size,
                                selectedLeadingIcon = { ZenMenuCheck() },
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
private fun SettingsMenuRow(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ZenListItem(
        headline = title,
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZenListValue(value)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/** Row that acts at once: icon, title, no value. */
@Composable
private fun SettingsActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ZenListItem(
        headline = title,
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
    )
}

/** "Automatic", or the bare number of essentials per row. */
@Composable
private fun KeptPageLifetime.label(): String = stringResource(
    when (this) {
        KeptPageLifetime.NEVER -> R.string.settings_kept_lifetime_never
        KeptPageLifetime.DAY -> R.string.settings_kept_lifetime_day
        KeptPageLifetime.WEEK -> R.string.settings_kept_lifetime_week
        KeptPageLifetime.MONTH -> R.string.settings_kept_lifetime_month
    },
)

@Composable
private fun EssentialsColumns.label(): String = when (this) {
    EssentialsColumns.AUTOMATIC -> stringResource(R.string.settings_essentials_columns_automatic)
    else -> storageValue
}

private fun ToolbarPlacement.labelRes(): Int = when (this) {
    ToolbarPlacement.TOP -> R.string.settings_toolbar_top
    ToolbarPlacement.BOTTOM -> R.string.settings_toolbar_bottom
}
