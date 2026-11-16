package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.KeptPageLifetime
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
 * "Browser": three rows, each opening its own sub-screen. No values on the
 * right: the choices are made on the page, not previewed here.
 */
@Composable
internal fun SettingsBrowserSection(
    onOpenSearch: () -> Unit,
    onOpenPages: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_browser_section))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NavRow(
                    title = stringResource(R.string.settings_search),
                    icon = Icons.Outlined.Search,
                    onClick = onOpenSearch,
                )
                SettingsDivider()
                NavRow(
                    title = stringResource(R.string.settings_pages_tabs),
                    icon = Icons.Outlined.BookmarkBorder,
                    onClick = onOpenPages,
                )
                SettingsDivider()
                NavRow(
                    title = stringResource(R.string.settings_privacy_blocking),
                    icon = Icons.Outlined.Shield,
                    onClick = onOpenPrivacy,
                )
            }
        }
    }
}

/**
 * The Search sub-screen: every engine in one list (custom ones with a delete
 * button), adding a new one, and whether the app opens into a search tab.
 */
@Composable
internal fun SettingsSearchBody(
    preferences: PreferencesState,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onDeleteSearchEngine: (String) -> Unit,
    onOpenAddSearchEngine: () -> Unit,
    onSetOpenIntoSearch: (Boolean) -> Unit,
) {
    val customIds = preferences.customSearchEngines.map { it.id }.toSet()
    val engines = SearchEngines.builtIn + preferences.customSearchEngines

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_search_engine))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                engines.forEach { engine ->
                    val selected = engine.id == preferences.searchEngine.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSetSearchEngine(engine) },
                            )
                            .heightIn(min = 56.dp)
                            .padding(start = 16.dp, end = if (engine.id in customIds) 8.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = engine.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (engine.id in customIds) {
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
                    SettingsDivider()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAddSearchEngine)
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_search_engine_add_action),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    LiftCard(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
            title = stringResource(R.string.settings_open_into_search),
            subtitle = stringResource(R.string.settings_open_into_search_subtitle),
            icon = Icons.Outlined.Keyboard,
            checked = preferences.openIntoSearch,
            onCheckedChange = onSetOpenIntoSearch,
        )
    }
}

/**
 * The Pages & tabs sub-screen: which pages survive, how long kept pages live,
 * and whether tabs open in the default browser instead.
 */
@Composable
internal fun SettingsPagesBody(
    preferences: PreferencesState,
    onSetKeepOnlyChosenPages: (Boolean) -> Unit,
    onSetKeptPageLifetime: (KeptPageLifetime) -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
) {
    LiftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ToggleRow(
                title = stringResource(R.string.settings_keep_only_chosen),
                subtitle = stringResource(R.string.settings_keep_only_chosen_subtitle),
                icon = Icons.Outlined.BookmarkBorder,
                checked = preferences.keepOnlyChosenPages,
                onCheckedChange = onSetKeepOnlyChosenPages,
            )

            SettingsDivider()

            var lifetimeExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                MenuRow(
                    title = stringResource(R.string.settings_kept_lifetime),
                    value = preferences.keptPageLifetime.label(),
                    icon = Icons.Outlined.Schedule,
                    onClick = { lifetimeExpanded = true },
                )
                DropdownMenu(
                    expanded = lifetimeExpanded,
                    onDismissRequest = { lifetimeExpanded = false },
                ) {
                    KeptPageLifetime.entries.forEach { option ->
                        val selected = option == preferences.keptPageLifetime
                        DropdownMenuItem(
                            text = { MenuItemText(option.label(), selected) },
                            trailingIcon = if (selected) ({ MenuItemCheck() }) else null,
                            onClick = {
                                onSetKeptPageLifetime(option)
                                lifetimeExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    LiftCard(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
            title = stringResource(R.string.settings_external_browser),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            checked = preferences.alwaysOpenExternally,
            onCheckedChange = onSetAlwaysOpenExternally,
        )
    }
}

/**
 * The Privacy & blocking sub-screen: the blocking switches for every site,
 * and clearing what websites stored.
 */
@Composable
internal fun SettingsPrivacyBody(onClearWebsiteData: () -> Unit) {
    var confirmClearData by remember { mutableStateOf(false) }
    if (confirmClearData) {
        AlertDialog(
            onDismissRequest = { confirmClearData = false },
            title = { Text(stringResource(R.string.settings_clear_website_data)) },
            text = { Text(stringResource(R.string.settings_clear_website_data_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearData = false
                    onClearWebsiteData()
                }) { Text(stringResource(R.string.settings_clear_website_data)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearData = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    SettingsContentBlockingBody()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_website_data_section))

        LiftCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { confirmClearData = true }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings_clear_website_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Row that opens a sub-screen: icon, title, chevron. */
@Composable
private fun NavRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
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
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
