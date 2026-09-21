package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngines
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LocalZenColors

@Composable
internal fun SettingsPreferencesSection(
    preferences: PreferencesState,
    normalTabsCapability: ZenSpaces.NormalTabsCapability,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onOpenAddSearchEngine: () -> Unit,
    onDeleteSearchEngine: (String) -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
    onSetSaveKind: (SaveKind) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    // Preferences Section
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_preferences_section),
            style = MaterialTheme.typography.labelLarge,
            color = LocalZenColors.current.ink.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 4.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            var menuExpanded by remember { mutableStateOf(false) }
            val selectedEngine = preferences.searchEngine
            val alwaysOpenExternally = preferences.alwaysOpenExternally
            val engines = SearchEngines.builtIn + preferences.customSearchEngines

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
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
                        text = selectedEngine.displayName,
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
                                    fontWeight = if (engine == selectedEngine) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (engine == selectedEngine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = if (engine == selectedEngine) {
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
                                onSetSearchEngine(engine)
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
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenAddSearchEngine()
                        },
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetAlwaysOpenExternally(!alwaysOpenExternally) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings_external_browser),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(
                    checked = alwaysOpenExternally,
                    onCheckedChange = { onSetAlwaysOpenExternally(it) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Save kind (pinned vs normal). Hidden when the browser version
            // never proved normal-tab support (`absent`).
            if (normalTabsCapability != ZenSpaces.NormalTabsCapability.ABSENT) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_save_kind),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    SaveKindOption(
                        label = stringResource(R.string.settings_save_kind_pinned),
                        selected = preferences.saveKind == SaveKind.PINNED,
                        enabled = true,
                        onSelect = { onSetSaveKind(SaveKind.PINNED) },
                    )
                    SaveKindOption(
                        label = stringResource(R.string.settings_save_kind_normal),
                        selected = preferences.saveKind == SaveKind.NORMAL,
                        enabled = normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED,
                        onSelect = { onSetSaveKind(SaveKind.NORMAL) },
                    )
                    if (normalTabsCapability == ZenSpaces.NormalTabsCapability.DISABLED) {
                        Text(
                            text = stringResource(R.string.settings_save_kind_normal_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalZenColors.current.ink.copy(alpha = 0.45f),
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }
            }
        }

        // Custom search engines: listed separately so they can be deleted
        // without opening the picker. Hidden until the user adds one.
        if (preferences.customSearchEngines.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_search_engine_custom_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalZenColors.current.ink.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    preferences.customSearchEngines.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
                    Spacer(modifier = Modifier.size(4.dp))
                }
            }
        }

        // Own card: Advanced is unrelated to the preferences above.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAdvanced() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings_advanced),
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
    }
}

/** The Advanced sub-screen replaces the sheet content until Back or Done. */
@Composable
internal fun SettingsAdvancedScreen(
    preferences: PreferencesState,
    onSetEssentialsGrouping: (ZenSpaces.EssentialsGrouping) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var groupingMenuExpanded by remember { mutableStateOf(false) }
    val essentialsGrouping = preferences.essentialsGrouping

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDone, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.common_done),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupingMenuExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_essentials_grouping),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(essentialsGrouping.labelRes()),
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
                        expanded = groupingMenuExpanded,
                        onDismissRequest = { groupingMenuExpanded = false },
                    ) {
                        ZenSpaces.EssentialsGrouping.entries.forEach { grouping ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(grouping.labelRes()),
                                        fontWeight = if (grouping == essentialsGrouping) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (grouping == essentialsGrouping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                trailingIcon = if (grouping == essentialsGrouping) {
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
                                    onSetEssentialsGrouping(grouping)
                                    groupingMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_essentials_grouping_caption),
                style = MaterialTheme.typography.labelSmall,
                color = LocalZenColors.current.ink.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** One radio row of the save-kind choice; disabled rows stay visible. */
@Composable
private fun SaveKindOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

private fun ZenSpaces.EssentialsGrouping.labelRes(): Int = when (this) {
    ZenSpaces.EssentialsGrouping.AUTOMATIC -> R.string.settings_essentials_grouping_automatic
    ZenSpaces.EssentialsGrouping.CONTAINER_SPECIFIC -> R.string.settings_essentials_grouping_container_specific
    ZenSpaces.EssentialsGrouping.SHARED -> R.string.settings_essentials_grouping_shared
}
