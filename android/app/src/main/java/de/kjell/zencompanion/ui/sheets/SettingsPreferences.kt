package de.kjell.zencompanion.ui.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Public
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.components.DefaultBrowser
import de.kjell.zencompanion.ui.components.rememberIsDefaultBrowser
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenListValue
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem
import de.kjell.zencompanion.ui.theme.ZenSwitchListItem

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

        ZenListGroup {
            SettingsNavRow(
                title = stringResource(R.string.settings_search),
                icon = Icons.Outlined.Search,
                onClick = onOpenSearch,
            )
            SettingsNavRow(
                title = stringResource(R.string.settings_pages_tabs),
                icon = Icons.Outlined.BookmarkBorder,
                onClick = onOpenPages,
            )
            SettingsNavRow(
                title = stringResource(R.string.settings_privacy_blocking),
                icon = Icons.Outlined.Shield,
                onClick = onOpenPrivacy,
            )
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

        ZenListGroup {
            engines.forEach { engine ->
                val isSelected = engine.id == preferences.searchEngine.id
                ZenListItem(
                    headline = engine.displayName,
                    onClick = { onSetSearchEngine(engine) },
                    modifier = Modifier.semantics { selected = isSelected },
                    leadingContent = {
                        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    trailingContent = if (engine.id in customIds) {
                        {
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
                        }
                    } else {
                        null
                    },
                )
            }

            ZenListItem(
                headline = stringResource(R.string.settings_search_engine_add_action),
                onClick = onOpenAddSearchEngine,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
        }
    }

    ZenListGroup {
        ZenSwitchListItem(
            headline = stringResource(R.string.settings_open_into_search),
            supportingText = stringResource(R.string.settings_open_into_search_subtitle),
            icon = Icons.Outlined.Keyboard,
            checked = preferences.openIntoSearch,
            onCheckedChange = onSetOpenIntoSearch,
        )
    }
}

/**
 * The Pages & tabs sub-screen: which pages survive, how long kept pages live,
 * whether tabs open in the default browser instead, and the system's browser
 * role.
 */
@Composable
internal fun SettingsPagesBody(
    preferences: PreferencesState,
    onSetKeepOnlyChosenPages: (Boolean) -> Unit,
    onSetKeptPageLifetime: (KeptPageLifetime) -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Asked in the role dialog or in the Settings app, so the rows update as
    // soon as the screen comes back.
    var isDefaultBrowser by rememberIsDefaultBrowser()
    val browserRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The system granted or kept the role; read it back once it is gone.
        isDefaultBrowser = DefaultBrowser.isDefault(context)
    }

    ZenListGroup {
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
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
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
        }
    }

    ZenListGroup {
        // As the default browser, "open in the default browser" would only
        // open Z-Sync again, so the row goes. The stored choice stays and
        // comes back with the row once another app takes the role.
        if (!isDefaultBrowser) {
            ZenSwitchListItem(
                headline = stringResource(R.string.settings_external_browser),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                checked = preferences.alwaysOpenExternally,
                onCheckedChange = onSetAlwaysOpenExternally,
            )
        }

        DefaultBrowserRow(
            isDefault = isDefaultBrowser,
            onRequestDefault = { browserRoleLauncher.launch(DefaultBrowser.requestIntent(context)) },
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

    SettingsContentBlockingBody()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_website_data_section))

        ZenListGroup {
            SettingsActionRow(
                title = stringResource(R.string.settings_clear_website_data),
                icon = Icons.Outlined.DeleteOutline,
                onClick = { confirmClearData = true },
            )
        }
    }
}

/** Row that opens a sub-screen: icon, title, chevron. */
@Composable
private fun SettingsNavRow(
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
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/** "Layout": how the essentials grid and the action bar look. */
@Composable
internal fun SettingsLayoutSection(
    signedIn: Boolean,
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

            // The grid only exists with an account; the toolbar placement
            // shapes the signed-out home too, so that row always stays.
            if (AccountOnlySettings.showsEssentialsColumns(signedIn)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    SettingsMenuRow(
                        title = stringResource(R.string.settings_essentials_columns),
                        value = columns.label(),
                        icon = Icons.Outlined.GridView,
                        onClick = { columnsExpanded = true },
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
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
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsMenuRow(
                    title = stringResource(R.string.settings_toolbar),
                    value = stringResource(toolbar.labelRes()),
                    icon = Icons.Outlined.SwapVert,
                    onClick = { toolbarExpanded = true },
                )
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
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

/** "Default browser": the system's browser role, or the way to ask for it. */
@Composable
private fun DefaultBrowserRow(isDefault: Boolean, onRequestDefault: () -> Unit) {
    ZenListItem(
        headline = stringResource(R.string.settings_default_browser),
        supportingText = stringResource(R.string.settings_default_browser_subtitle),
        // Once held, the system decides; the row is a statement, not a button.
        onClick = if (isDefault) null else onRequestDefault,
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = {
            if (isDefault) {
                ZenListValue(stringResource(R.string.settings_default_browser_value))
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
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
