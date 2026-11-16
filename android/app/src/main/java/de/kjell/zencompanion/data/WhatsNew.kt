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

/** Drawable resources for the hero's three phone screenshots. */
data class HeroCards(
    @DrawableRes val leading: Int,
    @DrawableRes val center: Int,
    @DrawableRes val trailing: Int,
)

/**
 * The notes the What's New sheet shows. Keep this to genuinely user-visible
 * changes, in reading order. The version is the store release these notes
 * belong to, not the build currently running.
 */
object WhatsNewRelease {
    const val version: String = "1.6"

    @StringRes
    val eyebrowRes: Int = R.string.whats_new_eyebrow

    @StringRes
    val headlineRes: Int = R.string.whats_new_headline

    @StringRes
    val subtitleRes: Int = R.string.whats_new_subtitle

    @StringRes
    val heroDescriptionRes: Int = R.string.whats_new_hero_description

    /**
     * The three phone screenshots in the hero: left, upright centre, right.
     * The side ones lean outwards.
     */
    val heroCards: HeroCards = HeroCards(
        leading = R.drawable.whats_new_search,
        center = R.drawable.whats_new_deck,
        trailing = R.drawable.whats_new_page,
    )

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
}

/**
 * Once-per-release decision, kept free of Android types so it is testable on
 * the JVM. The notes only appear once the running app reaches their release
 * version, and then only until they have been shown for it. A fresh 1.6
 * install also presents them, which is safer than guessing whether the app
 * was updated.
 */
class WhatsNewController(
    private val repository: WhatsNewRepository,
    private val currentVersion: String,
    private val releaseVersion: String = WhatsNewRelease.version,
) {
    fun shouldPresent(isDemo: Boolean): Boolean =
        !isDemo &&
            currentVersion == releaseVersion &&
            repository.shownVersion() != releaseVersion

    /** Call when the sheet is presented, not when it is dismissed. */
    fun markPresented() {
        repository.setShownVersion(releaseVersion)
    }
}

class AndroidWhatsNewRepository(context: Context) : WhatsNewRepository {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    override fun shownVersion(): String? = preferences.getString(KEY_SHOWN_VERSION, null)

    override fun setShownVersion(version: String) {
        preferences.edit().putString(KEY_SHOWN_VERSION, version).apply()
    }

    private companion object {
        const val KEY_SHOWN_VERSION = "whatsNewShownVersion"
    }
}
