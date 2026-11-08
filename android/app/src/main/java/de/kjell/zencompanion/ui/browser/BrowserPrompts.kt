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
}

/** The site kinds behind a WebView request. Anything else stays refused. */
fun sitePermissionsFor(resources: Array<String>): List<SitePermission> = buildList {
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(SitePermission.CAMERA)
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(SitePermission.MICROPHONE)
}

/** The resources a grant may name: only what the browser asked about. */
fun grantableResources(resources: Array<String>): Array<String> = resources.filter {
    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
}.toTypedArray()
