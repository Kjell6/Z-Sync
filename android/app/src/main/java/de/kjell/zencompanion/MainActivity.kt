package de.kjell.zencompanion

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.review.PlayInAppReview
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.AppViewModel
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.screens.MiniBrowserScreen
import de.kjell.zencompanion.ui.screens.MozillaSignInScreen
import de.kjell.zencompanion.ui.screens.SignInLandingView
import de.kjell.zencompanion.ui.screens.SpacesBrowserScreen
import de.kjell.zencompanion.ui.sheets.SettingsSheet
import de.kjell.zencompanion.ui.sheets.SignInHelpSheet
import de.kjell.zencompanion.ui.sheets.SyncedActivitySheet
import de.kjell.zencompanion.ui.sheets.ZenSheet
import de.kjell.zencompanion.ui.theme.ZenTheme

/** One mini-browser launch; [id] keys the ViewModel so a new open starts clean. */
private data class BrowserLaunch(val url: String?, val title: String?, val id: Int)

/**
 * Entry point: sign-in landing or the spaces browser (port of `HomeView`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenTheme {
                val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory(applicationContext))
                LaunchedEffect(Unit) { viewModel.bootstrap() }

                HomeContent(viewModel)
            }
        }
    }
}

@Composable
private fun HomeContent(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsState()

    if (session != null) {
        SpacesBrowserRoot(viewModel = viewModel)
    } else {
        LandingRoot(viewModel = viewModel)
    }
}

@Composable
private fun LandingRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    var showSignIn by remember { mutableStateOf(false) }
    var showSignInHelp by remember { mutableStateOf(false) }

    SignInLandingView(
        onOpenSignIn = { showSignIn = true },
        onOpenHelp = { showSignInHelp = true },
        onEnterDemo = { viewModel.enterDemo() },
    )

    if (showSignIn) {
        // Modal sheet with #FBFBFC background matching the Mozilla sign-in page
        ZenSheet(
            onDismiss = { showSignIn = false },
            skipPartiallyExpanded = true,
            containerColor = Color(0xFFFBFBFC),
        ) {
            MozillaSignInScreen(
                onFinishLogin = { login ->
                    // Completes FxA + persists the session; failures rethrow so
                    // the sign-in sheet keeps its error bubble and stays open.
                    viewModel.completeWebLogin(login)
                    showSignIn = false
                },
                onCancel = { showSignIn = false },
            )
        }
    }

    if (showSignInHelp) {
        SignInHelpSheet(
            onOpenMozilla = {
                showSignInHelp = false
                openURLExternally(context, "https://accounts.firefox.com")
            },
            onDone = { showSignInHelp = false },
        )
    }
}

@Composable
private fun SpacesBrowserRoot(viewModel: AppViewModel) {
    val browser by viewModel.browser.collectAsState()
    val session by viewModel.session.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val activityState by viewModel.activity.collectAsState()
    val account = session ?: return

    var showSettings by remember { mutableStateOf(false) }
    var activeBrowserLaunch by remember { mutableStateOf<BrowserLaunch?>(null) }
    var browserLaunchCounter by remember { mutableIntStateOf(0) }
    var showActivity by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.reviewRequests.collect {
            (context as? Activity)?.let { PlayInAppReview.request(it) }
        }
    }

    val spaces = browser.snapshot.spaces
    val currentSpace = spaces.getOrNull(browser.selectedIndex) ?: spaces.firstOrNull() ?: ZenSpaces.ZenSpace(
        id = "",
        name = "",
        icon = null,
        containerGuid = null,
        theme = null,
        pinned = emptyList(),
    )

    fun openBrowser(url: String?, title: String?) {
        if (url != null && preferences.alwaysOpenExternally) {
            openURLExternally(context, url)
        } else {
            browserLaunchCounter += 1
            activeBrowserLaunch = BrowserLaunch(url, title, browserLaunchCounter)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SpacesBrowserScreen(
            isDemo = account.isDemo,
            state = browser,
            essentialsGrouping = preferences.essentialsGrouping,
            onSelectSpace = { viewModel.selectSpace(it) },
            onRefresh = { viewModel.refresh() },
            onDeleteTab = { id -> viewModel.deleteTab(id) },
            onOpenAccount = { showSettings = true },
            onOpenActivity = { showActivity = true },
            onOpenBrowser = { url, title -> openBrowser(url, title) },
            onDismissShareTip = { viewModel.dismissShareTip() },
            onDismissSyncSetupHint = { viewModel.dismissSyncSetupHint() },
        )

        val launch = activeBrowserLaunch
        if (launch != null) {
            MiniBrowserScreen(
                initialUrl = launch.url,
                initialTitle = launch.title,
                launchKey = launch.id,
                currentSpace = currentSpace,
                allSpaces = spaces,
                browserRepository = viewModel.browserRepository,
                onDismiss = { activeBrowserLaunch = null },
            )
        }
    }

    if (showActivity) {
        SyncedActivitySheet(
            state = activityState,
            onLoad = { viewModel.loadActivity() },
            onDismiss = { showActivity = false },
            onOpenUrl = { url, title ->
                showActivity = false
                openBrowser(url, title)
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            email = account.email,
            isDemo = account.isDemo,
            preferences = preferences,
            normalTabsCapability = browser.snapshot.normalTabsCapability,
            onSetSearchEngine = { viewModel.setSearchEngine(it) },
            onAddSearchEngine = { name, template -> viewModel.addSearchEngine(name, template) },
            onDeleteSearchEngine = { viewModel.deleteSearchEngine(it) },
            onSetAlwaysOpenExternally = { viewModel.setAlwaysOpenExternally(it) },
            onSetEssentialsGrouping = { viewModel.setEssentialsGrouping(it) },
            onSetSaveKind = { viewModel.setSaveKind(it) },
            onSignOut = {
                showSettings = false
                // Clears encrypted store + sync cache + wipes WebView data.
                de.kjell.zencompanion.ui.screens.FxAWeb.wipeWebSession()
                viewModel.signOut()
            },
            onDone = { showSettings = false },
        )
    }
}
