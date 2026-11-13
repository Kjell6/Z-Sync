package de.kjell.zencompanion.ui.components

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import de.kjell.zencompanion.MainActivity

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
    val target = withoutOwnBrowser(context, intent) ?: return false
    if (context !is Activity) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(target)
        true
    } catch (_: Exception) {
        val fallback = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse) ?: return false
        // A fallback may only be a web page, never another intent or script.
        val scheme = fallback.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val fallbackIntent = Intent(Intent.ACTION_VIEW, fallback).addCategory(Intent.CATEGORY_BROWSABLE)
        val fallbackTarget = withoutOwnBrowser(context, fallbackIntent) ?: return false
        if (context !is Activity) fallbackTarget.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallbackTarget) }.isSuccess
    }
}

/**
 * The intent to start for [intent], never Z-Sync's own browser. Once Z-Sync
 * holds the browser role, a `VIEW http(s)` intent resolves back to this app,
 * so "open externally" would do nothing at all. Web links therefore go to
 * another browser explicitly. Anything else is started as it came in.
 *
 * Null means there is no other app to take the link.
 */
private fun withoutOwnBrowser(context: Context, intent: Intent): Intent? {
    val scheme = intent.data?.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return intent

    val packageManager = context.packageManager
    val ownPackage = context.packageName
    val handlers = installedHandlers(packageManager, intent)
    // Nobody else: handing the link out would resolve to us, or to nothing.
    if (handlers.none { it != ownPackage }) return null

    val resolved = resolveHandler(packageManager, intent)
    // A null default with several handlers means the system asks the reader,
    // and we would be one of the answers. Either way the link must not stay here.
    val chooserWouldIncludeUs = resolved == null && ownPackage in handlers
    if (resolved != ownPackage && !chooserWouldIncludeUs) return intent

    // The browser probe lists every browser; keep those that handle this very
    // address, so http-only handlers are not sent an https link.
    val browserPackages = otherBrowserPackages(packageManager).filter { it in handlers }
    val otherBrowser = externalHandlerPackage(browserPackages, ownPackage)
    if (otherBrowser != null) return Intent(intent).setPackage(otherBrowser)

    // A handler the browser probe did not list: leave Z-Sync out of the
    // chooser instead of offering it as one of the answers.
    return Intent.createChooser(Intent(intent), null).apply {
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(context, MainActivity::class.java)),
        )
    }
}

/**
 * The app a web link may leave through, from the packages that handle it: the
 * first one that is not Z-Sync. Null means Z-Sync is the only handler, so the
 * caller reports that no app can open the link instead of handing it back.
 */
internal fun externalHandlerPackage(handlerPackages: List<String>, ownPackage: String): String? =
    handlerPackages.firstOrNull { it != ownPackage }

@Suppress("DEPRECATION")
private fun installedHandlers(packageManager: PackageManager, intent: Intent): List<String> =
    packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }

@Suppress("DEPRECATION")
private fun resolveHandler(packageManager: PackageManager, intent: Intent): String? =
    packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName

/** Browser apps the system offers for a web link, Z-Sync included. */
@Suppress("DEPRECATION")
private fun otherBrowserPackages(packageManager: PackageManager): List<String> {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }
}
