package de.kjell.zencompanion.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngineValidation
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LiftCard
import de.kjell.zencompanion.ui.theme.LocalZenColors

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
    onSetEssentialsColumns: (EssentialsColumns) -> Unit,
    onSetSaveKind: (SaveKind) -> Unit,
    onSetToolbarPlacement: (ToolbarPlacement) -> Unit,
    onSetOpenIntoSearch: (Boolean) -> Unit,
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
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }

    ZenSheet(
        onDismiss = onDone,
        skipPartiallyExpanded = true,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
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
                .padding(top = 28.dp, bottom = 28.dp),
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
                onOpenContentBlocking = { showContentBlocking = true },
                onSetAlwaysOpenExternally = onSetAlwaysOpenExternally,
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
        IconButton(onClick = onDone, modifier = Modifier.padding(end = 4.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Email and the destructive action in one card: signing out belongs to the
 * account and no longer needs a button of its own beside the address.
 */
@Composable
private fun SettingsAccountCard(
    email: String,
    isDemo: Boolean,
    onSignOut: () -> Unit,
) {
    LiftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isDemo) {
                            stringResource(R.string.demo_account_name)
                        } else {
                            email
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isDemo) {
                        Text(
                            text = stringResource(R.string.demo_account_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSignOut)
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(if (isDemo) R.string.demo_exit else R.string.home_sign_out),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
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
        LiftCard(modifier = Modifier.fillMaxWidth()) {
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
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_save_kind),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(displayed.labelRes()),
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
                    SaveKind.entries.forEach { kind ->
                        val enabled = kind != SaveKind.NORMAL ||
                            normalTabsCapability == ZenSpaces.NormalTabsCapability.ENABLED
                        DropdownMenuItem(
                            enabled = enabled,
                            text = {
                                Text(
                                    text = stringResource(kind.labelRes()),
                                    fontWeight = if (kind == displayed) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (kind == displayed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            trailingIcon = if (kind == displayed) {
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
                                onSetSaveKind(kind)
                                menuExpanded = false
                            },
                        )
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
            .padding(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                text = stringResource(R.string.settings_content_blocking),
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

        SettingsContentBlockingBody()
    }
}

private fun SaveKind.labelRes(): Int = when (this) {
    SaveKind.PINNED -> R.string.settings_save_kind_pinned
    SaveKind.NORMAL -> R.string.settings_save_kind_normal
}
