package de.kjell.zencompanion.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
 * Settings rows that only make sense with a Mozilla account. Signed out they
 * are gone rather than disabled: they would have nothing to change.
 */
internal object AccountOnlySettings {
    /** Saves a shared link as pinned or normal: needs a space to save into. */
    fun showsSaveKind(signedIn: Boolean): Boolean = signedIn

    /** Essentials per row shapes the grid only an account fills. */
    fun showsEssentialsColumns(signedIn: Boolean): Boolean = signedIn
}

/**
 * Native Material 3 Settings Bottom Sheet.
 *
 * Rows are grouped (account, save kind, browser, layout, feedback, about) and
 * change in place, except the Browser group: its three rows (Search, Pages &
 * tabs, Privacy & blocking) each open a sub-screen, so the browser's many
 * options stay one tap away without crowding the main sheet.
 *
 * @param onDone dismisses the whole sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    /** Signed out the account card offers sign-in instead of email and sign-out. */
    signedIn: Boolean,
    email: String?,
    isDemo: Boolean,
    preferences: PreferencesState,
    normalTabsCapability: ZenSpaces.NormalTabsCapability,
    onSignIn: () -> Unit,
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
    var page by remember { mutableStateOf(SettingsPage.MAIN) }

    BackHandler(enabled = page != SettingsPage.MAIN) {
        page = page.parent
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
        when (page) {
            SettingsPage.MAIN -> Unit
            SettingsPage.SEARCH -> {
                SettingsSubpage(
                    title = stringResource(R.string.settings_search),
                    onBack = { page = page.parent },
                    onDone = onDone,
                ) {
                    SettingsSearchBody(
                        preferences = preferences,
                        onSetSearchEngine = onSetSearchEngine,
                        onDeleteSearchEngine = onDeleteSearchEngine,
                        onOpenAddSearchEngine = { page = SettingsPage.ADD_ENGINE },
                        onSetOpenIntoSearch = onSetOpenIntoSearch,
                    )
                }
                return@ZenSheet
            }
            SettingsPage.PAGES -> {
                SettingsSubpage(
                    title = stringResource(R.string.settings_pages_tabs),
                    onBack = { page = page.parent },
                    onDone = onDone,
                ) {
                    SettingsPagesBody(
                        preferences = preferences,
                        onSetKeepOnlyChosenPages = onSetKeepOnlyChosenPages,
                        onSetKeptPageLifetime = onSetKeptPageLifetime,
                        onSetAlwaysOpenExternally = onSetAlwaysOpenExternally,
                    )
                }
                return@ZenSheet
            }
            SettingsPage.PRIVACY -> {
                SettingsSubpage(
                    title = stringResource(R.string.settings_privacy_blocking),
                    onBack = { page = page.parent },
                    onDone = onDone,
                ) {
                    SettingsPrivacyBody(onClearWebsiteData = onClearWebsiteData)
                }
                return@ZenSheet
            }
            SettingsPage.ADD_ENGINE -> {
                // On success the screen calls onBack, which leads to Search.
                SettingsAddSearchEngineScreen(
                    onSubmit = onAddSearchEngine,
                    onBack = { page = page.parent },
                    onDone = onDone,
                )
                return@ZenSheet
            }
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
                signedIn = signedIn,
                email = email,
                isDemo = isDemo,
                onSignIn = onSignIn,
                onSignOut = { showSignOutDialog = true },
            )

            if (AccountOnlySettings.showsSaveKind(signedIn) &&
                normalTabsCapability != ZenSpaces.NormalTabsCapability.ABSENT
            ) {
                SettingsSaveKindCard(
                    saveKind = preferences.saveKind,
                    normalTabsCapability = normalTabsCapability,
                    onSetSaveKind = onSetSaveKind,
                )
            }

            SettingsBrowserSection(
                onOpenSearch = { page = SettingsPage.SEARCH },
                onOpenPages = { page = SettingsPage.PAGES },
                onOpenPrivacy = { page = SettingsPage.PRIVACY },
            )

            SettingsLayoutSection(
                signedIn = signedIn,
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
 * Signed out, one row that starts the sign-in flow in their place.
 */
@Composable
private fun SettingsAccountCard(
    signedIn: Boolean,
    email: String?,
    isDemo: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (!signedIn) {
        ZenListGroup(modifier = Modifier.fillMaxWidth()) {
            ZenListItem(
                headline = stringResource(R.string.home_sign_in),
                onClick = onSignIn,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
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
        return
    }

    // The button takes the card's height, whatever the card's lines make it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZenListGroup(modifier = Modifier.weight(1f)) {
            ZenListItem(
                headline = if (isDemo) {
                    stringResource(R.string.demo_account_name)
                } else {
                    email.orEmpty()
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
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight(),
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

                // The value and its arrow sit at the row's end: the menu opens there.
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
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

/** Which screen the sheet shows; [parent] is where Back leads. */
private enum class SettingsPage {
    MAIN, SEARCH, PAGES, PRIVACY, ADD_ENGINE;

    val parent: SettingsPage
        get() = if (this == ADD_ENGINE) SEARCH else MAIN
}

/**
 * The frame of a Browser sub-screen: back, title, done, then the page's own
 * groups in one scroll.
 */
@Composable
private fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    content: @Composable () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
                text = title,
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

        content()
    }
}

private fun SaveKind.labelRes(): Int = when (this) {
    SaveKind.PINNED -> R.string.settings_save_kind_pinned
    SaveKind.NORMAL -> R.string.settings_save_kind_normal
}
