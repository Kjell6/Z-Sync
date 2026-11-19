package de.kjell.zencompanion.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.os.Build
import kotlin.math.min

/**
 * Portrait on a phone, any orientation on a large screen.
 *
 * The manifest locks the activities to portrait, so a phone never shows a
 * landscape frame before this runs. A tablet or an unfolded foldable (600dp
 * and more on the display's shortest side) is released here: Android 16
 * already ignores the lock there, and older versions would otherwise
 * letterbox the app in landscape. Folding and unfolding changes the answer,
 * so the main activity asks again on every configuration change.
 *
 * Measured on the display, not the window: while the lock is on, older
 * versions box the app into a portrait window that is phone-narrow, and the
 * window alone would keep the lock forever.
 */
object ScreenOrientation {
    /** The shortest side, in dp, from which a display counts as large. */
    private const val LargeScreenDp = 600

    fun isLargeScreen(activity: Activity): Boolean {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
        val density = activity.resources.displayMetrics.density
        return min(width, height) / density >= LargeScreenDp
    }

    fun requestedFor(activity: Activity): Int =
        if (isLargeScreen(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    /**
     * Applies the orientation for the display the activity is on. A
     * full-screen video holds its own (any orientation) and is left alone; it
     * asks for [requestedFor] again when it closes.
     */
    fun applyTo(activity: Activity) {
        val current = activity.requestedOrientation
        if (current != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
            current != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) {
            return
        }
        val wanted = requestedFor(activity)
        if (current != wanted) activity.requestedOrientation = wanted
    }
}
