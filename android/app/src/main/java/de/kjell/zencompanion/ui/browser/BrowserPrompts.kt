package de.kjell.zencompanion.ui.browser

import android.webkit.PermissionRequest
import de.kjell.zencompanion.data.SitePermission

/**
 * A page's question, answered exactly once. The WebView keeps a request open
 * until it hears back, so [dismiss] answers for a page that goes away.
 */
sealed class BrowserPrompt {
    abstract fun dismiss()

    /** [onDismiss] runs when the page went away: nobody refused, so nothing is remembered. */
    class Permission(
        val host: String,
        val kinds: List<SitePermission>,
        private val onDismiss: () -> Unit,
        private val answer: (Boolean) -> Unit,
    ) : BrowserPrompt() {
        private var answered = false

        fun send(allowed: Boolean) {
            if (answered) return
            answered = true
            answer(allowed)
        }

        override fun dismiss() {
            if (answered) return
            answered = true
            onDismiss()
        }
    }

    class HttpLogin(
        val host: String,
        private val proceed: (String, String) -> Unit,
        private val cancel: () -> Unit,
    ) : BrowserPrompt() {
        private var answered = false

        fun signIn(user: String, password: String) {
            if (answered) return
            answered = true
            proceed(user, password)
        }

        override fun dismiss() {
            if (answered) return
            answered = true
            cancel()
        }
    }

    /**
     * A download a page started: the reader hears what the file is before it
     * begins. An address already being asked about asks nothing more.
     */
    class Download(
        /** The file's address, so the same file is not asked about twice. */
        val url: String,
        /** The name the file would be saved under. */
        val fileName: String,
        /** The host of the page that started it, or empty when it has none. */
        val host: String,
        /** The page went away: the file is dropped and nothing is left to close. */
        private val onDismiss: () -> Unit,
        private val answer: (Boolean) -> Unit,
    ) : BrowserPrompt() {
        private var answered = false

        fun send(confirmed: Boolean) {
            if (answered) return
            answered = true
            answer(confirmed)
        }

        override fun dismiss() {
            if (answered) return
            answered = true
            onDismiss()
        }
    }
}

/** True when this page is still asking about this very file. */
internal fun pageAlreadyAsks(prompts: List<BrowserPrompt>, url: String): Boolean =
    prompts.any { it is BrowserPrompt.Download && it.url == url }

/** The site kinds behind a WebView request. Anything else stays refused. */
fun sitePermissionsFor(resources: Array<String>): List<SitePermission> = buildList {
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(SitePermission.CAMERA)
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(SitePermission.MICROPHONE)
}

/** The resources a grant may name: only what the browser asked about. */
fun grantableResources(resources: Array<String>): Array<String> = resources.filter {
    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
}.toTypedArray()
