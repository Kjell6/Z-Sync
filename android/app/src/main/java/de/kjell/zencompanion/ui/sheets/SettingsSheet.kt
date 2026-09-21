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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SearchEngine
import de.kjell.zencompanion.data.SearchEngineValidation
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.PreferencesState
import de.kjell.zencompanion.ui.theme.LocalZenColors

import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Native Material 3 Settings Bottom Sheet.
 *
 * Features:
 * - Account section: Left card with user email & person icon (~56dp height),
 *   right compact square card button with destructive red sign-out icon (~56dp)
 *   that prompts for confirmation on click.
 * - About section: Material 3 card with rows for Privacy Policy and App Version.
 * - Footer: License attribution and subtle disclaimer caption.
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
    onSetEssentialsGrouping: (ZenSpaces.EssentialsGrouping) -> Unit,
    onSetSaveKind: (SaveKind) -> Unit,
    onSetToolbarPlacement: (ToolbarPlacement) -> Unit,
    onSignOut: () -> Unit,
    onDone: () -> Unit,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showAddEngine by remember { mutableStateOf(false) }

    BackHandler(enabled = showAdvanced || showAddEngine) {
        showAdvanced = false
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
        if (showAdvanced) {
            SettingsAdvancedScreen(
                preferences = preferences,
                onSetEssentialsGrouping = onSetEssentialsGrouping,
                onBack = { showAdvanced = false },
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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header
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

            // Account Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_account_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalZenColors.current.ink.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 4.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left Card: Email + Person icon (~56dp height)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (isDemo) 64.dp else 56.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
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
                    }

                    // Right Card: Square destructive sign-out button (~56dp height & width)
                    Card(
                        modifier = Modifier
                            .size(56.dp)
                            .clickable { showSignOutDialog = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                                contentDescription = stringResource(
                                    if (isDemo) R.string.demo_exit else R.string.home_sign_out,
                                ),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            // Preferences Section
            SettingsPreferencesSection(
                preferences = preferences,
                normalTabsCapability = normalTabsCapability,
                onSetSearchEngine = onSetSearchEngine,
                onOpenAddSearchEngine = { showAddEngine = true },
                onDeleteSearchEngine = onDeleteSearchEngine,
                onSetAlwaysOpenExternally = onSetAlwaysOpenExternally,
                onSetSaveKind = onSetSaveKind,
                onSetToolbarPlacement = onSetToolbarPlacement,
                onOpenAdvanced = { showAdvanced = true },
            )

            // Feedback Section
            SettingsAboutSection()
        }
    }
}
