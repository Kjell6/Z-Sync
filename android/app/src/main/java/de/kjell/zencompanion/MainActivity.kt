package de.kjell.zencompanion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.kjell.zencompanion.review.PlayInAppReview
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.AppViewModel
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.screens.MiniBrowserScreen
import de.kjell.zencompanion.ui.screens.MozillaSignInScreen
import de.kjell.zencompanion.ui.screens.SignInLandingView
import de.kjell.zencompanion.ui.screens.SpacesBrowserScreen
import de.kjell.zencompanion.ui.screens.incomingWebLink
import de.kjell.zencompanion.ui.sheets.SettingsSheet
import de.kjell.zencompanion.ui.sheets.SignInHelpSheet
import de.kjell.zencompanion.ui.sheets.SyncedActivitySheet
import de.kjell.zencompanion.ui.sheets.WhatsNewSheet
import de.kjell.zencompanion.ui.sheets.ContentBlockingSetupScreen
import de.kjell.zencompanion.data.ContentBlocking
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.kjell.zencompanion.ui.sheets.ZenSheet
import de.kjell.zencompanion.ui.theme.ZenTheme
import kotlinx.coroutines.delay

/** The browser being open. The pages live in [BrowserPages]; [url] reopens one after the process died. */
private data class BrowserLaunch(val url: String?, val title: String?, val overview: Boolean)

/** Keeps an open browser across configuration changes (theme, font size, locale). */
private val BrowserLaunchSaver = listSaver<BrowserLaunch?, Any?>(
    save = { launch -> if (launch == null) emptyList() else listOf(launch.url, launch.title, launch.overview) },
    restore = { values ->
        if (values.isEmpty()) null else BrowserLaunch(values[0] as String?, values[1] as String?, values[2] as Boolean)
    },
)

/**
 * Entry point: sign-in landing or the spaces browser (port of `HomeView`).
 */
class MainActivity : ComponentActivity() {
    /** One instance for the activity's lifetime, shared with the composition. */
    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appViewModel = ViewModelProvider(this, AppViewModel.Factory(applicationContext))
            .get(AppViewModel::class.java)
        enableEdgeToEdge()
        // A recreation keeps the pending link in the ViewModel instead of
        // opening it a second time.
        if (savedInstanceState == null) openIncomingLink(intent)
        setContent {
            ZenTheme {
                val viewModel = appViewModel
                LaunchedEffect(Unit) { viewModel.bootstrap() }

                HomeContent(viewModel)
            }
        }
    }

    /**
     * A link from another app while this one is already running. `singleTask`
     * delivers it here instead of building a second copy inside the caller.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIncomingLink(intent)
    }

    override fun onDestroy() {
        // Leaving the app before signing in drops a link that was kept for it.
        if (isFinishing) appViewModel.discardIncomingLink()
        super.onDestroy()
    }

    /** Only `VIEW` intents with an `http`/`https` data URI carry a web link. */
    private fun openIncomingLink(intent: Intent?) {
        val address = incomingWebLink(intent?.action, intent?.scheme, intent?.dataString) ?: return
        appViewModel.openLinkFromAnotherApp(address)
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
    val sessionNotice by viewModel.sessionNotice.collectAsState()

    SignInLandingView(
        onOpenSignIn = { showSignIn = true },
        onOpenHelp = { showSignInHelp = true },
        onEnterDemo = { viewModel.enterDemo() },
        sessionNotice = sessionNotice,
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
    val whatsNewVisible by viewModel.whatsNewVisible.collectAsState()
    val blockingAsked by ContentBlocking.asked.collectAsState()
    val account = session ?: return

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var activeBrowserLaunch by rememberSaveable(stateSaver = BrowserLaunchSaver) { mutableStateOf<BrowserLaunch?>(null) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val pages = remember { BrowserPages.shared(context, viewModel.browserRepository) }
    val openPages by pages.store.pages.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.reviewRequests.collect {
            (context as? Activity)?.let { PlayInAppReview.request(it) }
        }
    }

    // Once per app version, after the first frame has had a moment to settle.
    // Only the main activity reaches this: sharing from another app opens
    // ShareActivity, which never builds AppViewModel, so a share cannot
    // trigger the sheet.
    // The content blocking question comes first; the notes wait for its answer.
    LaunchedEffect(blockingAsked) {
        if (!blockingAsked) return@LaunchedEffect
        delay(450)
        viewModel.presentWhatsNewIfNeeded()
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

    fun openBrowser(
        url: String?,
        title: String?,
        openedFromSpace: Boolean = false,
        fromAnotherApp: Boolean = false,
    ) {
        // A link another app handed over always opens here: sending it out
        // again would hand it straight back once Z-Sync is the default browser.
        if (!fromAnotherApp && url != null && preferences.alwaysOpenExternally) {
            openURLExternally(context, url)
        } else {
            pages.updateSpaces(spaces, currentSpace.id)
            pages.open(url, title, openedFromSpace)
            activeBrowserLaunch = BrowserLaunch(url, title, overview = false)
        }
    }

    // A new observer replays ON_START, so this also covers launch. Returning
    // to the foreground (e.g. after sharing from another app) refreshes what's
    // on screen, like iOS. Stops for a configuration change (dark mode, window
    // resize) are not a trip away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.reload()
                    viewModel.appCameToForeground()
                    pages.closeExpired()
                }
                Lifecycle.Event.ON_STOP ->
                    if ((context as? Activity)?.isChangingConfigurations != true) {
                        viewModel.appWentToBackground()
                        pages.appWentToBackground()
                    }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val incomingLink by viewModel.incomingLink.collectAsState()
    val searchOnOpenPending by viewModel.searchOnOpenPending.collectAsState()
    LaunchedEffect(searchOnOpenPending) {
        if (!searchOnOpenPending) return@LaunchedEffect
        // Leave an open browser, settings or activity sheet where the user left
        // it. A link from another app is the tab this launch is for.
        if (incomingLink == null && activeBrowserLaunch == null && !showSettings && !showActivity) {
            openBrowser(null, null)
        }
        viewModel.consumeSearchOnOpen()
    }

    // A web link from another app: the reader chose Z-Sync for it, so it opens
    // on top of the spaces screen. Consumed first, so recomposition cannot
    // open it twice; before sign-in it waits in the ViewModel.
    LaunchedEffect(incomingLink) {
        val url = incomingLink ?: return@LaunchedEffect
        viewModel.consumeIncomingLink()
        // A sheet would cover the page; close it like tapping a link does.
        showSettings = false
        showActivity = false
        openBrowser(url, null, fromAnotherApp = true)
    }

    Box(Modifier.fillMaxSize()) {
        SpacesBrowserScreen(
            isDemo = account.isDemo,
            state = browser,
            essentialsColumns = preferences.essentialsColumns,
            toolbarPlacement = preferences.toolbarPlacement,
            onSelectSpace = { viewModel.selectSpace(it) },
            onRefresh = { viewModel.refresh() },
            onDeleteTab = { id -> viewModel.deleteTab(id) },
            onRenameTab = { id, label -> viewModel.renameTab(id, label) },
            onMoveTab = { id, spaceId, folderId -> viewModel.moveTab(id, spaceId, folderId) },
            onPinTab = { id, spaceId -> viewModel.pinTab(id, spaceId) },
            onOpenAccount = { showSettings = true },
            onOpenActivity = { showActivity = true },
            onOpenBrowser = { url, title -> openBrowser(url, title, openedFromSpace = url != null) },
            pageCount = openPages.size,
            onOpenPages = { activeBrowserLaunch = BrowserLaunch(null, null, overview = true) },
            onDismissShareTip = { viewModel.dismissShareTip() },
            onDismissSyncSetupHint = { viewModel.dismissSyncSetupHint() },
        )

        val launch = activeBrowserLaunch
        if (launch != null) {
            MiniBrowserScreen(
                pages = pages,
                startsInOverview = launch.overview,
                launchUrl = launch.url,
                launchTitle = launch.title,
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
            onSetEssentialsColumns = { viewModel.setEssentialsColumns(it) },
            onSetSaveKind = { viewModel.setSaveKind(it) },
            onSetToolbarPlacement = { viewModel.setToolbarPlacement(it) },
            onSetOpenIntoSearch = { viewModel.setOpenIntoSearch(it) },
            onSetKeepOnlyChosenPages = { viewModel.setKeepOnlyChosenPages(it) },
            onSetKeptPageLifetime = { viewModel.setKeptPageLifetime(it) },
            onClearWebsiteData = { de.kjell.zencompanion.ui.browser.clearBrowserWebsiteData(context) },
            onSignOut = {
                showSettings = false
                // Clears encrypted store + sync cache + wipes WebView data.
                de.kjell.zencompanion.ui.screens.FxAWeb.wipeWebSession()
                pages.closeAll()
                viewModel.signOut()
            },
            onOpenWhatsNew = {
                showSettings = false
                viewModel.openWhatsNew()
            },
            onDone = { showSettings = false },
        )
    }

    if (whatsNewVisible) {
        WhatsNewSheet(onDismiss = { viewModel.dismissWhatsNew() })
    }

    // Asked once, right after sign-in or on the first launch that has it.
    // Back does nothing: Continue is the answer.
    if (!blockingAsked) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            ContentBlockingSetupScreen()
        }
    }
}
