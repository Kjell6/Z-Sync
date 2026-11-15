package de.kjell.zencompanion.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.KeptPageLifetime
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngineValidation
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenListValue
import de.kjell.zencompanion.ui.theme.ZenMenu
import de.kjell.zencompanion.ui.theme.ZenMenuCheck
import de.kjell.zencompanion.ui.theme.ZenMenuGroup
import de.kjell.zencompanion.ui.theme.ZenSelectableMenuItem

/**
 * Native Material 3 Settings Bottom Sheet.
 *
 * Rows are grouped (account, save kind, browsing, layout, feedback, about) and
 * exactly one group leads to a sub-screen — the content blocking switches.
 * Everything else changes in place, so the sheet stays one scroll.
 *
 * @param onDone dismisses the whole sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    email: String,
    isDemo: Boolean,
    preferences: PreferencesState,
    normalTabsCapability: ZenSpaces.NormalTabsCapability,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onAddSearchEngine: (String, String) -> SearchEngineValidation?,
    onDeleteSearchEngine: (String) -> Unit,
    onSetAlwaysOpenExternally: (Boolean) -> Unit,
    onClearWebsiteData: () -> Unit,
    onSetEssentialsColumns: (EssentialsColumns) -> Unit,
    onSetSaveKind: (SaveKind) -> Unit,
    onSetToolbarPlacement: (ToolbarPlacement) -> Unit,
    onSetOpenIntoSearch: (Boolean) -> Unit,
    onSetKeepOnlyChosenPages: (Boolean) -> Unit,
    onSetKeptPageLifetime: (KeptPageLifetime) -> Unit,
    onSignOut: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onDone: () -> Unit,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showContentBlocking by remember { mutableStateOf(false) }
    var showAddEngine by remember { mutableStateOf(false) }

    BackHandler(enabled = showContentBlocking || showAddEngine) {
        showContentBlocking = false
        showAddEngine = false
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    text = stringResource(
                        if (isDemo) R.string.demo_exit else R.string.settings_sign_out_dialog_title,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = if (isDemo) {
                null
            } else {
                {
                    Text(
                        text = stringResource(R.string.settings_sign_out_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOut()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        text = stringResource(
                            if (isDemo) R.string.demo_exit else R.string.settings_sign_out_confirm,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }

    ZenSheet(
        onDismiss = onDone,
        skipPartiallyExpanded = true,
    ) {
        if (showContentBlocking) {
            SettingsContentBlockingScreen(
                onBack = { showContentBlocking = false },
                onDone = onDone,
            )
            return@ZenSheet
        }

        if (showAddEngine) {
            SettingsAddSearchEngineScreen(
                onSubmit = onAddSearchEngine,
                onBack = { showAddEngine = false },
                onDone = onDone,
            )
            return@ZenSheet
        }

        Column(
            Modifier
                .settingsSheetPage()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(
                    top = SheetContentTopSpacing,
                    bottom = SheetContentBottomSpacing,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSheetHeader(onDone = onDone)

            SettingsAccountCard(
                email = email,
                isDemo = isDemo,
                onSignOut = { showSignOutDialog = true },
            )

            if (normalTabsCapability != ZenSpaces.NormalTabsCapability.ABSENT) {
                SettingsSaveKindCard(
                    saveKind = preferences.saveKind,
                    normalTabsCapability = normalTabsCapability,
                    onSetSaveKind = onSetSaveKind,
                )
            }

            SettingsBrowsingSection(
                preferences = preferences,
                onSetSearchEngine = onSetSearchEngine,
                onOpenAddSearchEngine = { showAddEngine = true },
                onSetOpenIntoSearch = onSetOpenIntoSearch,
                onSetKeepOnlyChosenPages = onSetKeepOnlyChosenPages,
                onSetKeptPageLifetime = onSetKeptPageLifetime,
                onOpenContentBlocking = { showContentBlocking = true },
                onSetAlwaysOpenExternally = onSetAlwaysOpenExternally,
                onClearWebsiteData = onClearWebsiteData,
            )

            if (preferences.customSearchEngines.isNotEmpty()) {
                SettingsCustomEnginesCard(
                    engines = preferences.customSearchEngines,
                    onDeleteSearchEngine = onDeleteSearchEngine,
                )
            }

            SettingsLayoutSection(
                preferences = preferences,
                onSetEssentialsColumns = onSetEssentialsColumns,
                onSetToolbarPlacement = onSetToolbarPlacement,
            )

            SettingsFeedbackSection()

            SettingsAboutSection(onOpenWhatsNew = onOpenWhatsNew)

            SettingsFooter()
        }
    }
}

@Composable
private fun SettingsSheetHeader(onDone: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDone,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Email on the left, the destructive action as a compact icon button beside it.
 */
@Composable
private fun SettingsAccountCard(
    email: String,
    isDemo: Boolean,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZenListGroup(modifier = Modifier.weight(1f)) {
            ZenListItem(
                headline = if (isDemo) {
                    stringResource(R.string.demo_account_name)
                } else {
                    email
                },
                supportingText = if (isDemo) {
                    stringResource(R.string.demo_account_caption)
                } else {
                    null
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
        }

        FilledTonalIconButton(
            onClick = onSignOut,
            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                contentDescription = stringResource(
                    if (isDemo) R.string.demo_exit else R.string.home_sign_out,
                ),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Pinned or normal tabs for links shared into the app. Stands on its own above
 * the groups: it is the only choice about what happens to a shared link.
 */
@Composable
private fun SettingsSaveKindCard(
    saveKind: SaveKind,
    normalTabsCapability: ZenSpaces.NormalTabsCapability,
    onSetSaveKind: (SaveKind) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val displayed = if (normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED) {
        saveKind
    } else {
        SaveKind.PINNED
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZenListGroup {
            Box(modifier = Modifier.fillMaxWidth()) {
                ZenListItem(
                    headline = stringResource(R.string.settings_save_kind),
                    onClick = { menuExpanded = true },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ZenListValue(stringResource(displayed.labelRes()))
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
                    ZenMenuGroup(index = 0, count = 1) {
                        SaveKind.entries.forEachIndexed { index, kind ->
                            val enabled = kind != SaveKind.NORMAL ||
                                normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED
                            ZenSelectableMenuItem(
                                selected = kind == displayed,
                                onClick = {
                                    onSetSaveKind(kind)
                                    menuExpanded = false
                                },
                                text = { Text(stringResource(kind.labelRes())) },
                                index = index,
                                count = SaveKind.entries.size,
                                enabled = enabled,
                                selectedLeadingIcon = { ZenMenuCheck() },
                            )
                        }
                    }
                }
            }
        }

        if (normalTabsCapability == ZenSpaces.NormalTabsCapability.DISABLED) {
            Text(
                text = stringResource(R.string.settings_save_kind_normal_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalZenColors.current.ink.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * The content blocking page: the three switches for every site and the note on
 * what feeds them. Nothing else lives here.
 */
@Composable
private fun SettingsContentBlockingScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .settingsSheetPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(
                top = SheetContentTopSpacing,
                bottom = SheetContentBottomSpacing,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
        ) {
            IconButton(
                onClick = onBack,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_content_blocking),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDone,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.common_done),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsContentBlockingBody()
    }
}

private fun SaveKind.labelRes(): Int = when (this) {
    SaveKind.PINNED -> R.string.settings_save_kind_pinned
    SaveKind.NORMAL -> R.string.settings_save_kind_normal
}
