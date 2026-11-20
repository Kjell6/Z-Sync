package de.kjell.zencompanion.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.kjell.zencompanion.R

/**
 * One feature pill in the sheet. Copy stays in `strings.xml` because it is
 * UI text, and the list is replaced wholesale each release.
 */
data class WhatsNewItem(
    val id: String,
    @StringRes val titleRes: Int,
)

/**
 * The notes the What's New sheet shows. Keep this to genuinely user-visible
 * changes, in reading order. The version is the store release these notes
 * belong to, not the build currently running.
 */
object WhatsNewRelease {
    const val version: String = "1.6"

    @StringRes
    val headlineRes: Int = R.string.whats_new_headline

    @StringRes
    val subtitleRes: Int = R.string.whats_new_subtitle

    @StringRes
    val heroDescriptionRes: Int = R.string.whats_new_hero_description

    /**
     * The hero image, shown full width at its own aspect ratio, so its design
     * (and colours) live in the image itself.
     */
    @DrawableRes
    val heroImageRes: Int = R.drawable.whats_new_hero

    /** Small caption above the feature pills. */
    @StringRes
    val featuresHeadingRes: Int = R.string.whats_new_features_heading

    val items: List<WhatsNewItem> = listOf(
        WhatsNewItem("history", R.string.whats_new_item_history),
        WhatsNewItem("ad-blocking", R.string.whats_new_item_ad_blocking),
        WhatsNewItem("suggestions", R.string.whats_new_item_suggestions),
        WhatsNewItem("downloads", R.string.whats_new_item_downloads),
        WhatsNewItem("bottom-bar", R.string.whats_new_item_bottom_bar),
        WhatsNewItem("more", R.string.whats_new_item_more),
    )
}

/** Storage seam for the release version whose notes were already shown. */
interface WhatsNewRepository {
    fun shownVersion(): String?
    fun setShownVersion(version: String)

    /**
     * The version the reader started on, or [UPDATED_FROM_EARLIER_VERSION]
     * when the app was in use before this was recorded. Null: not recorded.
     */
    fun firstLaunchVersion(): String? = null

    companion object {
        const val UPDATED_FROM_EARLIER_VERSION = "earlier"
    }
}

/**
 * Once-per-release decision, kept free of Android types so it is testable on
 * the JVM. The notes only appear once the running app reaches their release
 * version, only until they have been shown for it, and only to a reader who
 * used an earlier version: a fresh install has nothing to compare against.
 */
class WhatsNewController(
    private val repository: WhatsNewRepository,
    private val currentVersion: String,
    private val releaseVersion: String = WhatsNewRelease.version,
) {
    fun shouldPresent(isDemo: Boolean): Boolean =
        !isDemo &&
            currentVersion == releaseVersion &&
            repository.shownVersion() != releaseVersion &&
            repository.firstLaunchVersion() != releaseVersion

    /** Call when the sheet is presented, not when it is dismissed. */
    fun markPresented() {
        repository.setShownVersion(releaseVersion)
    }
}

/**
 * What [AndroidWhatsNewRepository.recordFirstLaunch] stores: the running
 * version for a fresh install, or the "earlier" marker when an earlier
 * version had been used.
 */
internal fun firstLaunchValue(usedBefore: Boolean, currentVersion: String): String =
    if (usedBefore) WhatsNewRepository.UPDATED_FROM_EARLIER_VERSION else currentVersion

class AndroidWhatsNewRepository(private val context: Context) : WhatsNewRepository {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    override fun shownVersion(): String? = preferences.getString(KEY_SHOWN_VERSION, null)

    override fun setShownVersion(version: String) {
        preferences.edit().putString(KEY_SHOWN_VERSION, version).apply()
    }

    override fun firstLaunchVersion(): String? = preferences.getString(KEY_FIRST_LAUNCH_VERSION, null)

    /**
     * Remembers the version the reader started on, once. Called at app start,
     * before anything else writes preferences: on the first launch with this
     * code, an earlier version's footprints (its preferences, a stored
     * account) mark an update rather than a fresh install.
     */
    fun recordFirstLaunch(currentVersion: String) {
        if (preferences.contains(KEY_FIRST_LAUNCH_VERSION)) return
        val onboardingUsed = EARLIER_ONBOARDING_KEYS.any(preferences::contains)
        val shared = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val settingsUsed = EARLIER_SHARED_KEYS.any(shared::contains)
        val usedBefore = onboardingUsed || settingsUsed ||
            runCatching { AccountStore.load(context) != null }.getOrDefault(false)
        preferences.edit()
            .putString(KEY_FIRST_LAUNCH_VERSION, firstLaunchValue(usedBefore, currentVersion))
            .apply()
    }

    private companion object {
        const val KEY_SHOWN_VERSION = "whatsNewShownVersion"
        const val KEY_FIRST_LAUNCH_VERSION = "firstLaunchVersion"
        const val SHARED_PREFS_NAME = "zen_prefs"

        /** What an earlier version left in "onboarding" once it had been used. */
        val EARLIER_ONBOARDING_KEYS = listOf(
            KEY_SHOWN_VERSION,
            "didDismissSyncSetupHint",
            "didShowShareExtensionTip",
            "didRequestReview",
            "didArmReviewPrompt",
        )

        /** What an earlier version left in "zen_prefs" once it had been used. */
        val EARLIER_SHARED_KEYS = listOf(
            "always_open_links_externally",
            "essentials_grouping",
            "essentials_columns",
            "save_tab_kind",
            "toolbar_placement",
            "open_into_search",
            "zen_companion_search_engine",
            "selected_search_engine",
            "zen_companion_custom_search_engines",
            "lastSpaceId",
        )
    }
}
