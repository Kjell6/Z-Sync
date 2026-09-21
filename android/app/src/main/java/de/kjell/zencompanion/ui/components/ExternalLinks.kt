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
        return Intent(Intent.ACTION_VIEW, uri)
    }
    return runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
}

private fun startExternally(context: Context, intent: Intent): Boolean {
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        val fallbackUrl = intent.getStringExtra("browser_fallback_url") ?: return false
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
        }.isSuccess
    }
}
