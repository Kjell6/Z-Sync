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
 * How a web link leaves Z-Sync without coming straight back: "open
 * externally" must never hand the link to this app again.
 */
internal sealed interface ExternalLinkRoute {
    /** Start the intent as it came in: Z-Sync cannot take this link itself. */
    data object StartAsIs : ExternalLinkRoute

    /** Start it for exactly this package, so the system cannot pick Z-Sync. */
    data class StartWithPackage(val packageName: String) : ExternalLinkRoute

    /** Ask the reader, with Z-Sync left out of the answers. */
    data object ChooserWithoutUs : ExternalLinkRoute

    /** Nothing else on the device can open the link. */
    data object NoOtherApp : ExternalLinkRoute
}

/**
 * The route for a web link, from what the system knows about it: the packages
 * that handle the intent, the package the system resolves it to, Z-Sync's
 * package, and the browser apps the system lists.
 *
 * Z-Sync must never be an answer, because the link would land back in this
 * app. That is decided by the handler set, not by the resolver: when several
 * apps handle a link and none is the default, `resolveActivity` reports the
 * system's chooser activity (`com.android.internal.app.ResolverActivity`,
 * package `android`, or another system package on some devices), not null.
 */
internal fun externalLinkRoute(
    handlerPackages: List<String>,
    resolvedPackage: String?,
    ownPackage: String,
    browserPackages: List<String>,
): ExternalLinkRoute {
    // Z-Sync cannot take the link at all: nothing to keep it away from.
    if (ownPackage !in handlerPackages) return ExternalLinkRoute.StartAsIs
    val others = handlerPackages.filter { it != ownPackage }.distinct()
    // The only handler left: handing it out would resolve to us or to nothing.
    if (others.isEmpty()) return ExternalLinkRoute.NoOtherApp

    // A real default that is not us may take the link as it is. A chooser
    // activity is not in the handler set, so it never counts as one.
    if (resolvedPackage != null &&
        resolvedPackage != ownPackage &&
        resolvedPackage in handlerPackages
    ) {
        return ExternalLinkRoute.StartWithPackage(resolvedPackage)
    }

    // No usable default: the first browser that handles this very address, so
    // an http-only browser is not sent an https link.
    browserPackages.firstOrNull { it in others }?.let {
        return ExternalLinkRoute.StartWithPackage(it)
    }

    // A handler the browser probe did not list: leave Z-Sync out of the
    // chooser instead of offering it as one of the answers.
    return ExternalLinkRoute.ChooserWithoutUs
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
    return when (
        val route = externalLinkRoute(
            handlerPackages = installedHandlers(packageManager, intent),
            resolvedPackage = resolveHandler(packageManager, intent),
            ownPackage = context.packageName,
            browserPackages = browserPackages(packageManager),
        )
    ) {
        ExternalLinkRoute.StartAsIs -> intent
        is ExternalLinkRoute.StartWithPackage -> Intent(intent).setPackage(route.packageName)
        ExternalLinkRoute.ChooserWithoutUs -> Intent.createChooser(Intent(intent), null).apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, MainActivity::class.java)),
            )
        }
        ExternalLinkRoute.NoOtherApp -> null
    }
}

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
private fun browserPackages(packageManager: PackageManager): List<String> {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }
}
