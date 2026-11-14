package de.kjell.zencompanion.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

fun openURLExternally(context: Context, url: String) {
    openURLExternally(context, Uri.parse(url))
}

fun openURLExternally(context: Context, uri: Uri): Boolean {
    val intent = parseExternalIntent(uri) ?: return false
    return startExternally(context, intent)
}

private fun parseExternalIntent(uri: Uri): Intent? {
    if (!uri.scheme.equals("intent", ignoreCase = true)) {
        // Browsable is what marks a handler as reachable from a browser, so a
        // page link can never start an activity that never declared it.
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
        ?: return null
    return sanitizeWebIntent(intent)
}

/**
 * Restricts an `intent://` link from a web page to what Chrome allows: it may
 * only reach activities that declare themselves browsable, never a named
 * component or selector, and it can't grant access to content URIs.
 */
internal fun sanitizeWebIntent(intent: Intent): Intent {
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.flags = intent.flags and (
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ).inv()
    return intent
}

private fun startExternally(context: Context, intent: Intent): Boolean {
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        val fallback = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse) ?: return false
        // A fallback may only be a web page, never another intent or script.
        val scheme = fallback.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, fallback).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.isSuccess
    }
}
