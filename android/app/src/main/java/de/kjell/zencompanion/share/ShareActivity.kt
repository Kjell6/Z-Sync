package de.kjell.zencompanion.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.util.Haptics

/**
 * Android equivalent of the iOS Share Extension: ACTION_SEND text/plain /
 * text/uri-list. Extraction stays here; the state machine lives in
 * [ShareViewModel]. Same UX phases as `ShareSheetView`; success auto-closes.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val (sharedUrl, title) = extractShared()

        setContent {
            val context = LocalContext.current
            val viewModel: ShareViewModel = viewModel(
                factory = ShareViewModel.Factory(
                    repository = AndroidShareRepository(applicationContext),
                    initialUrl = sharedUrl,
                    initialPageTitle = title,
                ),
            )
            val state by viewModel.state.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    if (event is ShareViewModel.Event.Finished) finishFlow()
                }
            }

            // Port of the original save feedback: CONFIRM on success, REJECT
            // on failure (the 480ms auto-close lives in the ViewModel).
            LaunchedEffect(state.phase) {
                when (state.phase) {
                    SharePhase.saved -> Haptics.perform(context, Haptics.Kind.CONFIRM)
                    SharePhase.failed -> Haptics.perform(context, Haptics.Kind.REJECT)
                    else -> Unit
                }
            }

            ShareScreen(
                url = state.url,
                pageTitle = state.pageTitle,
                spaces = state.spaces,
                selected = state.destination,
                phase = state.phase,
                errorText = state.errorText,
                saveKind = state.saveKind,
                savedAsPinnedFallback = state.savedAsPinnedFallback,
                onSelectDestination = viewModel::selectDestination,
                onSave = viewModel::save,
                onCancel = viewModel::cancel,
            )
        }
    }

    private fun finishFlow() {
        runCatching { finishAffinity() }
    }

    /** Extracts the first http(s) URL + page title from the share intent. */
    private fun extractShared(): Pair<String?, String> {
        var foundUrl: String? = null

        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val candidate = text.trim().trim('"')
                if (looksLikeHttpUrl(candidate)) foundUrl = candidate
                else text.split(Regex("\\s+")).firstOrNull { looksLikeHttpUrl(it.trim()) }?.let { foundUrl = it.trim() }
            }
            if (foundUrl == null && intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { looksLikeHttpUrl(it) } == true) {
                foundUrl = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            }
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (foundUrl == null && stream != null && looksLikeHttpUrl(stream.toString())) {
                foundUrl = stream.toString()
            }
        }

        val rawTitle = intent?.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: ""
        return foundUrl to (rawTitle ?: "")
    }

}

/** True for http(s) only — file/javascript/custom schemes are never pinned. */
internal fun looksLikeHttpUrl(text: String): Boolean {
    if (text.isEmpty()) return false
    val scheme = runCatching { java.net.URI(text).scheme }.getOrNull()?.lowercase()
    return scheme == "http" || scheme == "https"
}
