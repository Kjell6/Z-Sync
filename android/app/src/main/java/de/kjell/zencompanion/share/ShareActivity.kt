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
import de.kjell.zencompanion.ui.theme.ZenTheme
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
            ZenTheme {
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
                    allowsNormalTabs = state.allowsNormalTabs,
                    onSelectDestination = viewModel::selectDestination,
                    onSelectSaveKind = viewModel::selectSaveKind,
                    onSave = viewModel::save,
                    onCancel = viewModel::cancel,
                )
            }
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
                sharedLink(text)?.let { foundUrl = it }
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

/**
 * The first http(s) link in shared text (iOS `ShareLink.link(in:)`). A text
 * without inner whitespace is tried as one address first, otherwise each
 * whitespace-separated word in turn.
 */
internal fun sharedLink(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.none { it.isWhitespace() }) {
        linkCandidate(trimmed)?.let { return it }
    }
    return trimmed.split(Regex("\\s+")).firstNotNullOfOrNull { linkCandidate(it) }
}

/** One word or the whole text as an address, or null when it is not a link. */
internal fun linkCandidate(text: String): String? {
    val bare = trimSurroundingMarks(text.trim())
    if (bare.isEmpty() || !looksLikeHttpUrl(bare)) return null
    return if (hostOf(bare).isNullOrEmpty()) null else bare
}

/**
 * The address's host, or null. `java.net.URI` only knows ASCII domain labels,
 * so a Unicode domain has no host until it is checked in the punycode form
 * `IDN` makes of it; the address itself is still returned as it was shared
 * (iOS's Foundation rewrites the domain to punycode on its own). The host and
 * port are split here because `URI` leaves a Unicode domain with a port
 * without a host; a port that is not digits is no address at all.
 */
private fun hostOf(text: String): String? {
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
    if (!uri.host.isNullOrEmpty()) return uri.host
    val authority = uri.rawAuthority ?: return null
    val hostAndPort = authority.substringAfterLast('@')
    if (':' in hostAndPort) {
        val port = hostAndPort.substringAfterLast(':')
        if (port.isNotEmpty() && (port.length > 5 || port.any { it !in '0'..'9' })) return null
    }
    val host = hostAndPort.substringBeforeLast(':')
    return runCatching { java.net.IDN.toASCII(host) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Marks a sentence puts before a link; they never belong to its address. */
private val leadingMarks = setOf('"', '\'', '“', '”', '‘', '’', '(', '[', '{', '<')

/** Marks a sentence puts right after a link. */
private val sentenceMarks = setOf('.', ',', ';', ':', '!', '?', '…')
private val quoteMarks = setOf('"', '\'', '“', '”', '‘', '’')

/** Closing bracket to the opening bracket it has to pair with. */
private val bracketPairs = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')

/**
 * Drops the leading quotes and brackets, then the sentence marks that close
 * the sentence around the link, then the quotes and unbalanced closing
 * brackets it is wrapped in (iOS `ShareLink.trimmingSurroundingMarks`). A mark
 * inside that wrapper stays, since an address may end in one: a quoted
 * `…?q=wow!` keeps its `!`. A closing bracket only goes while it is
 * unbalanced, so a linked `Function_(mathematics)` keeps its own.
 */
internal fun trimSurroundingMarks(text: String): String {
    val characters = text.dropWhile { it in leadingMarks }.toMutableList()
    while (characters.lastOrNull()?.let { it in sentenceMarks } == true) {
        characters.removeAt(characters.lastIndex)
    }
    // Each character is counted once, so a long run of closing brackets costs
    // one step per removed mark instead of a recount of the whole list.
    val counts = characters.groupingBy { it }.eachCount().toMutableMap()
    while (true) {
        val last = characters.lastOrNull() ?: break
        if (last in quoteMarks) {
            characters.removeAt(characters.lastIndex)
            continue
        }
        val opener = bracketPairs[last] ?: break
        val opens = counts[opener] ?: 0
        val closes = counts[last] ?: 0
        if (opens >= closes) break
        characters.removeAt(characters.lastIndex)
        counts[last] = closes - 1
    }
    return characters.joinToString("")
}
