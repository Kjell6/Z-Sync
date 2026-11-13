package de.kjell.zencompanion.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SitePermission
import de.kjell.zencompanion.data.SitePermissionChoice
import de.kjell.zencompanion.data.SitePermissionDecision
import de.kjell.zencompanion.data.SitePermissions
import de.kjell.zencompanion.ui.browser.BrowserDownload
import de.kjell.zencompanion.ui.browser.BrowserPages
import de.kjell.zencompanion.ui.browser.BrowserPrompt
import de.kjell.zencompanion.ui.browser.PAGE_FILE_BRIDGE
import de.kjell.zencompanion.ui.browser.PageFileBridge
import de.kjell.zencompanion.ui.browser.PageSession
import de.kjell.zencompanion.ui.browser.announcedDownloadName
import de.kjell.zencompanion.ui.browser.enqueueWebDownload
import de.kjell.zencompanion.ui.browser.isPageLocalDownload
import de.kjell.zencompanion.ui.browser.isWebDownload
import de.kjell.zencompanion.ui.browser.pageAlreadyAsks
import de.kjell.zencompanion.ui.browser.pageFileScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a page's WebView needs from the screen around it. */
internal interface PageRequests {
    fun askPermission(kinds: List<SitePermission>, host: String, done: (Boolean) -> Unit)
    fun httpLogin(prompt: BrowserPrompt.HttpLogin)
    /** Saves the file at once: the reader picked it themselves. */
    fun download(view: WebView, download: BrowserDownload)
    /** Asks the reader first, the way a page's own download does. */
    fun askDownload(view: WebView, download: BrowserDownload)
    fun installPageFileBridge(view: WebView)
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideFullscreen()
}

/** The Activity under a Compose context, for the system's own screens. */
internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Either location permission will do: the approximate one is a valid answer. */
internal fun systemAccessGranted(kind: SitePermission, granted: (String) -> Boolean): Boolean =
    kind.systemPermissions.any(granted)

/**
 * What a request that waited behind an open dialog may be told once the dialog
 * closes, or null when it has to be asked: true when the system granted it all
 * by now, false when every kind it still misses was part of that question, and
 * null when one of them was never put to the reader.
 */
internal fun waitingAnswer(missing: List<SitePermission>, asked: List<SitePermission>): Boolean? = when {
    missing.isEmpty() -> true
    missing.all { it in asked } -> false
    else -> null
}

/**
 * The screen's side of page requests: the system's permission and file screens,
 * and the video that takes over the display. The system's question is only
 * raised from here, after the browser's own.
 */
internal class BrowserPageRequests(
    private val context: Context,
    private val pages: BrowserPages,
    private val permissions: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    private val files: ManagedActivityResultLauncher<Intent, ActivityResult>,
) {
    /** Not tied to the screen: a started download outlasts it. */
    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Kinds the system refuses for good, for the dialog that explains it. */
    var systemAccessOff by mutableStateOf<List<SitePermission>?>(null)
    private var fullscreen by mutableStateOf<Fullscreen?>(null)
    val isFullscreen: Boolean get() = fullscreen != null

    private var permissionAnswer: ((Map<String, Boolean>) -> Unit)? = null
    /** The kinds of site permission the open system dialog asks about. */
    private var permissionKinds: List<SitePermission> = emptyList()
    private var fileAnswer: ((ActivityResult) -> Unit)? = null

    /** Callers waiting while a system request is open, answered when it closes. */
    private val permissionWaiters = mutableListOf<Pair<List<SitePermission>, (Boolean) -> Unit>>()

    fun onPermissionResult(result: Map<String, Boolean>) {
        val answer = permissionAnswer
        val asked = permissionKinds
        permissionAnswer = null
        permissionKinds = emptyList()
        answer?.invoke(result)
        // Everyone who asked while the dialog was open is answered now, but only
        // about what that dialog asked: a kind the reader was not shown goes to
        // the system as its own question instead of being refused unanswered.
        val waiting = permissionWaiters.toList()
        permissionWaiters.clear()
        waiting.forEach { (kinds, done) ->
            val missing = kinds.filter { kind -> !systemAccessGranted(kind) { context.has(it) } }
            val allowed = waitingAnswer(missing, asked)
            // A waiter answered again is handed back to the system, which either
            // starts the next dialog or lets it wait behind one: it is never
            // resubmitted a second time for the same closed dialog.
            if (allowed != null) done(allowed) else requestSystem(kinds, done)
        }
    }

    fun onFileResult(result: ActivityResult) {
        val answer = fileAnswer
        fileAnswer = null
        answer?.invoke(result)
    }

    private fun requestSystem(kinds: List<SitePermission>, done: (Boolean) -> Unit) {
        val missing = kinds.filter { kind -> !systemAccessGranted(kind) { context.has(it) } }
        if (missing.isEmpty()) {
            done(true)
            return
        }
        if (permissionAnswer != null) {
            // One system dialog at a time: wait for the open one instead of
            // denying a request the reader has already been asked about.
            permissionWaiters += kinds to done
            return
        }
        permissionKinds = kinds
        permissionAnswer = { _ ->
            val refused = kinds.filter { kind -> !systemAccessGranted(kind) { context.has(it) } }
            // After a final no the system shows nothing at all, so say where it changes.
            val activity = context.activity()
            if (refused.isNotEmpty() && activity != null && refused.none { kind ->
                    kind.systemPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                }
            ) {
                systemAccessOff = refused
            }
            done(refused.isEmpty())
        }
        permissions.launch(missing.flatMap { it.systemPermissions }.toTypedArray())
    }

    /**
     * One page's requests. Kept per page: the page-local file bridge installed
     * on its WebView must be the one that later hands out the file's token.
     */
    private val sessionRequests = HashMap<String, PageRequests>()

    fun forSession(session: PageSession): PageRequests {
        // A closed page takes its requests with it, and its file bridge too, so
        // this stays as small as the pages that are open.
        val open = pages.store.pages.value.mapTo(HashSet()) { it.id }
        sessionRequests.keys.retainAll(open)
        return sessionRequests.getOrPut(session.id) { requestsFor(session) }
    }

    private fun requestsFor(session: PageSession): PageRequests = object : PageRequests {
        private val pageFiles = PageFileBridge(context) { saved ->
            session.webView?.post {
                session.viewModel.onDownloadFinished(saved)
            }
        }

        override fun askPermission(kinds: List<SitePermission>, host: String, done: (Boolean) -> Unit) {
            when (SitePermissions.decision(kinds, host)) {
                SitePermissionDecision.GRANT -> requestSystem(kinds, done)
                SitePermissionDecision.DENY -> done(false)
                SitePermissionDecision.ASK -> session.prompts += BrowserPrompt.Permission(host, kinds, onDismiss = { done(false) }) { allowed ->
                    SitePermissions.set(
                        if (allowed) SitePermissionChoice.ALLOW else SitePermissionChoice.BLOCK,
                        kinds,
                        host,
                    )
                    if (allowed) requestSystem(kinds, done) else done(false)
                    // The page may have asked again before this answer. Those
                    // questions have their answer now too, instead of showing
                    // the same dialog once more. Only for kinds just answered:
                    // nothing is remembered that the reader was not asked.
                    val repeats = session.prompts
                        .filterIsInstance<BrowserPrompt.Permission>()
                        .filter { it.host.equals(host, ignoreCase = true) && kinds.containsAll(it.kinds) }
                    // Taken out first: each answer looks for the next question
                    // itself, and a list still holding them would nest one call
                    // per duplicate.
                    session.prompts.removeAll(repeats)
                    repeats.forEach { it.send(allowed) }
                }
            }
        }

        override fun httpLogin(prompt: BrowserPrompt.HttpLogin) {
            session.prompts += prompt
        }

        override fun installPageFileBridge(view: WebView) {
            view.addJavascriptInterface(pageFiles, PAGE_FILE_BRIDGE)
        }

        override fun download(view: WebView, download: BrowserDownload) {
            // Android 8 and 9 still need the storage permission for Downloads.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                !context.has(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                if (permissionAnswer != null) return
                // Storage is no site permission: a request waiting behind this
                // dialog has not been asked about its own kinds.
                permissionKinds = emptyList()
                permissionAnswer = { result ->
                    if (result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
                        save(view, download)
                    } else {
                        session.viewModel.onDownloadFinished(false)
                    }
                }
                permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                return
            }
            save(view, download)
        }

        override fun askDownload(view: WebView, download: BrowserDownload) {
            // The same file twice while the first question is open asks once.
            if (pageAlreadyAsks(session.prompts, download.url)) return
            session.prompts += BrowserPrompt.Download(
                url = download.url,
                fileName = announcedDownloadName(download),
                host = pageHost(view.url.orEmpty()).orEmpty(),
                // The page is going: the file is dropped along with it.
                onDismiss = {},
            ) { confirmed ->
                if (confirmed) download(view, download) else cancelDownload(view, download)
            }
        }

        private fun save(view: WebView, download: BrowserDownload) {
            when {
                isWebDownload(download.url) -> downloads.launch {
                    val queued = withContext(Dispatchers.IO) { enqueueWebDownload(context, download) }
                    if (queued) session.viewModel.onDownloadStarted() else session.viewModel.onDownloadFinished(false)
                }
                isPageLocalDownload(download.url) ->
                    view.evaluateJavascript(pageFileScript(download.url, pageFiles.expect()), null)
                else -> return
            }
            closeFileWindow(view, download)
        }

        /** The reader said no: nothing is written, and a window opened only for its file still goes. */
        private fun cancelDownload(view: WebView, download: BrowserDownload) {
            closeFileWindow(view, download)
        }

        /** A link that opened a window only for its file leaves nothing to show. */
        private fun closeFileWindow(view: WebView, download: BrowserDownload) {
            val current = view.url
            val blank = current.isNullOrEmpty() || current == "about:blank"
            if (blank && !view.canGoBack() && pages.canReturnToOpener(session.id) && isWebDownload(download.url)) {
                pages.close(session.id)
            }
        }

        override fun chooseFiles(
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            if (fileAnswer != null) return false
            val started = runCatching { files.launch(params.createIntent()) }.isSuccess
            if (!started) return false
            fileAnswer = { result ->
                callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
            }
            return true
        }

        override fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
            val activity = context.activity()
            if (activity == null || fullscreen != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreen = Fullscreen(activity, view, callback)
        }

        override fun hideFullscreen() = endFullscreen()
    }

    /** Back on a full-screen video ends the video's full screen, nothing else. */
    fun exitFullscreen() {
        val current = fullscreen ?: return
        current.callback.onCustomViewHidden()
        endFullscreen()
    }

    fun endFullscreen() {
        fullscreen?.close()
        fullscreen = null
    }
}

/**
 * A video on the whole display. It sits on the window, above Compose, and turns
 * with the phone: the app itself stays upright.
 */
private class Fullscreen(
    private val activity: Activity,
    view: View,
    val callback: WebChromeClient.CustomViewCallback,
) {
    private val decor = activity.window.decorView as ViewGroup
    private val orientation = activity.requestedOrientation
    private val bars = WindowCompat.getInsetsController(activity.window, decor)
    private val frame = FrameLayout(activity).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    init {
        decor.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    fun close() {
        decor.removeView(frame)
        frame.removeAllViews()
        bars.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = orientation
    }
}

@Composable
internal fun rememberBrowserPageRequests(pages: BrowserPages): BrowserPageRequests {
    val context = LocalContext.current
    var holder by remember { mutableStateOf<BrowserPageRequests?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        holder?.onPermissionResult(it)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        holder?.onFileResult(it)
    }
    val requests = remember(pages) {
        BrowserPageRequests(context, pages, permissions, files).also { holder = it }
    }
    DisposableEffect(requests) {
        onDispose { requests.endFullscreen() }
    }
    return requests
}

/** The active page's open question, then the note about system access. */
@Composable
internal fun BrowserPromptDialogs(session: PageSession?, requests: BrowserPageRequests) {
    val prompt = session?.prompts?.firstOrNull()
    when (prompt) {
        is BrowserPrompt.Permission -> PermissionDialog(prompt) { session.prompts.remove(prompt) }
        is BrowserPrompt.HttpLogin -> HttpLoginDialog(prompt) { session.prompts.remove(prompt) }
        is BrowserPrompt.Download -> DownloadDialog(prompt) { session.prompts.remove(prompt) }
        null -> requests.systemAccessOff?.let { kinds ->
            SystemAccessOffDialog(kinds) { requests.systemAccessOff = null }
        }
    }
}

/** A file a page wants to save: the reader names the file before it starts. */
@Composable
private fun DownloadDialog(prompt: BrowserPrompt.Download, onClose: () -> Unit) {
    val site = prompt.host.ifEmpty { stringResource(R.string.browser_dialog_this_page) }
    AlertDialog(
        // Tapping outside is no answer: the file waits for a real one.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.browser_download_question, prompt.fileName)) },
        text = { Text(stringResource(R.string.browser_download_from, site)) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                prompt.send(true)
            }) { Text(stringResource(R.string.browser_download_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onClose()
                prompt.send(false)
            }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun PermissionDialog(prompt: BrowserPrompt.Permission, onClose: () -> Unit) {
    val site = prompt.host.ifEmpty { stringResource(R.string.browser_dialog_this_page) }
    val title = when {
        SitePermission.CAMERA in prompt.kinds && SitePermission.MICROPHONE in prompt.kinds ->
            stringResource(R.string.browser_permission_camera_microphone, site)
        SitePermission.CAMERA in prompt.kinds -> stringResource(R.string.browser_permission_camera, site)
        SitePermission.MICROPHONE in prompt.kinds -> stringResource(R.string.browser_permission_microphone, site)
        else -> stringResource(R.string.browser_permission_location, site)
    }
    AlertDialog(
        // Tapping outside is no answer: the page waits for a real one.
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text(stringResource(R.string.browser_permission_message)) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                prompt.send(true)
            }) { Text(stringResource(R.string.browser_permission_allow)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onClose()
                prompt.send(false)
            }) { Text(stringResource(R.string.browser_permission_deny)) }
        },
    )
}

@Composable
private fun HttpLoginDialog(prompt: BrowserPrompt.HttpLogin, onClose: () -> Unit) {
    var user by remember(prompt) { mutableStateOf("") }
    var password by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.browser_dialog_login_title, prompt.host)) },
        text = {
            Column {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.browser_dialog_user)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.browser_dialog_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                prompt.signIn(user, password)
            }) { Text(stringResource(R.string.browser_dialog_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onClose()
                prompt.dismiss()
            }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SystemAccessOffDialog(kinds: List<SitePermission>, onClose: () -> Unit) {
    val context = LocalContext.current
    val title = when (kinds.firstOrNull()) {
        SitePermission.CAMERA -> R.string.browser_permission_camera_off
        SitePermission.MICROPHONE -> R.string.browser_permission_microphone_off
        else -> R.string.browser_permission_location_off
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(R.string.browser_permission_off_message)) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text(stringResource(R.string.browser_permission_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
