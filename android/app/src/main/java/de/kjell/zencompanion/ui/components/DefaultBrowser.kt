package de.kjell.zencompanion.ui.components

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The system's browser role: whether Z-Sync holds it and how to ask for it.
 * Android 10 and later has a role prompt; 8 and 9 only have a default-apps
 * settings page, where the default browser is the app that resolves web links.
 */
object DefaultBrowser {
    /** True while Z-Sync is the system's default browser. */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true) {
                return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        // Before the browser role existed (or where a device has no role
        // prompt), the default browser is the app the system resolves a web
        // link to.
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return resolvePackage(context, probe) == context.packageName
    }

    /**
     * The system screen that asks for the role: the role request on Android 10
     * and later, the default-apps page before it (or when the device has no
     * role prompt at all).
     */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
            }
        }
        return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    @Suppress("DEPRECATION")
    private fun resolvePackage(context: Context, intent: Intent): String? =
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
}
