package de.kjell.zencompanion.data

import android.content.Context

/**
 * The first-run onboarding is for people who just installed the app: a
 * reader who updated from an earlier version already knows it, and the
 * What's New sheet covers what changed for them. Without a recorded first
 * launch nothing is known, so it stays quiet.
 */
internal fun shouldShowOnboarding(firstLaunchVersion: String?, completed: Boolean): Boolean =
    !completed &&
        firstLaunchVersion != null &&
        firstLaunchVersion != WhatsNewRepository.UPDATED_FROM_EARLIER_VERSION

/** Whether the onboarding still has to run; kept next to the first-launch record. */
class OnboardingStore(context: Context) {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun shouldShow(): Boolean = shouldShowOnboarding(
        firstLaunchVersion = preferences.getString(KEY_FIRST_LAUNCH_VERSION, null),
        completed = preferences.getBoolean(KEY_COMPLETED, false),
    )

    /** Call when the reader leaves it, by Skip or Get started. */
    fun markCompleted() {
        preferences.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    private companion object {
        const val KEY_FIRST_LAUNCH_VERSION = "firstLaunchVersion"
        const val KEY_COMPLETED = "didCompleteOnboarding"
    }
}
