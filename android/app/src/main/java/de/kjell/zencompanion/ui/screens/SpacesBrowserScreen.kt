package de.kjell.zencompanion.ui.screens

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserState
import de.kjell.zencompanion.ui.components.ZenSpaceGradientBackground
import de.kjell.zencompanion.ui.sheets.SyncSetupSheet
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenTheme

/**
 * Native Material 3 Spaces Browser Screen.
 * Top bar: History card | Search card | Settings card (essentials look).
 * Bottom: space switcher + disclaimer. Refresh via pull-to-refresh.
 */
@Composable
fun SpacesBrowserScreen(
    isDemo: Boolean,
    state: BrowserState,
    essentialsGrouping: ZenSpaces.EssentialsGrouping,
    onSelectSpace: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDeleteTab: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBrowser: (url: String?, title: String?) -> Unit = { _, _ -> },
    onOpenActivity: () -> Unit = {},
    onDismissShareTip: () -> Unit = {},
    onDismissSyncSetupHint: () -> Unit = {},
) {
    val spaces = state.snapshot.spaces
    val currentTheme = spaces.getOrNull(state.selectedIndex)?.theme
    var showSyncSetup by remember { mutableStateOf(false) }
    val isDark = currentTheme?.isDarkTheme ?: isSystemInDarkTheme()
    // Resolved once per snapshot/grouping so adjacent pages are compared by
    // their effective essentials grid, not just by container guid.
    val essentialsBySpace = remember(state.snapshot, essentialsGrouping) {
        spaces.associate { space ->
            space.id to state.snapshot.essentialsFor(space, essentialsGrouping)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Gradient background follows the selected space and stays stable across theme toggles
        ZenSpaceGradientBackground(theme = currentTheme, darkenDots = isDark)

        ZenTheme(darkTheme = isDark) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (isDemo) {
                    Text(
                        text = stringResource(R.string.demo_banner),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = LocalZenColors.current.ink.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LocalZenColors.current.lift.copy(alpha = 0.65f))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (spaces.isEmpty()) {
                    TopBar(
                        onOpenBrowser = onOpenBrowser,
                        onOpenActivity = onOpenActivity,
                        onOpenAccount = onOpenAccount,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.zeroSpaces) {
                        ZeroSpacesHelp(
                            loading = state.loading,
                            onSetup = { showSyncSetup = true },
                            onRetry = onRefresh,
                        )
                    } else {
                        StateArea(
                            loading = state.loading,
                            errorRes = state.loadErrorRes,
                            onRetry = onRefresh,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    TopBar(
                        onOpenBrowser = onOpenBrowser,
                        onOpenActivity = onOpenActivity,
                        onOpenAccount = onOpenAccount,
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.showSyncSetupHint,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        SyncSetupHint(
                            onOpen = { showSyncSetup = true },
                            onDismiss = onDismissSyncSetupHint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 8.dp),
                        )
                    }

                    // Pull-to-refresh wraps the pager
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = onRefresh,
                        modifier = Modifier.weight(1f),
                    ) {
                        SpacesPager(
                            spaces = spaces,
                            essentialsBySpace = essentialsBySpace,
                            selectedIndex = state.selectedIndex,
                            onSelect = onSelectSpace,
                            onDeleteTab = onDeleteTab,
                            onOpenUrl = { url, title -> onOpenBrowser(url, title) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Bottom: Space Switcher + disclaimer
                if (spaces.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SpaceSwitcher(
                            spaces = spaces,
                            selectedIndex = state.selectedIndex,
                            onSelect = onSelectSpace,
                        )
                        Text(
                            text = stringResource(R.string.home_disclaimer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }


            // One-time share-extension tip, top overlay above everything.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.showShareTip && spaces.isNotEmpty(),
                enter = fadeIn() + androidx.compose.animation.slideInVertically(
                    initialOffsetY = { -it },
                ),
                exit = fadeOut() + androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { -it },
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            ) {
                ShareExtensionTip(onDismiss = onDismissShareTip)
            }
        }
    }

    // Step-by-step sidebar sync setup, opened from the hint or the zero state.
    if (showSyncSetup) {
        SyncSetupSheet(
            onDismiss = { showSyncSetup = false },
            onRefresh = {
                showSyncSetup = false
                onRefresh()
            },
        )
    }
}
