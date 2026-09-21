package de.kjell.zencompanion.data

import android.content.Context

/**
 * How a tab captured by the share target or the mini browser is saved into
 * Zen: a pinned tab (default) or a normal (unpinned) tab.
 */
enum class SaveKind(val storageValue: String) {
    PINNED("pinned"),
    NORMAL("normal"),
    ;

    companion object {
        fun fromStorage(value: String?): SaveKind =
            entries.firstOrNull { it.storageValue == value } ?: PINNED
    }
}

/**
 * Where the spaces screen's action bar (history, search, settings) sits:
 * above the essentials grid (default) or below the space switcher.
 */
enum class ToolbarPlacement(val storageValue: String) {
    TOP("top"),
    BOTTOM("bottom"),
    ;

    companion object {
        fun fromStorage(value: String?): ToolbarPlacement =
            entries.firstOrNull { it.storageValue == value } ?: TOP
    }
}

/**
 * Whether tapping a tab opens the URL in the system default browser
 * instead of the in-app mini-browser, plus the essentials grouping override
 * and the pinned/normal save kind.
 */
object BrowserSettings {
    const val PREFS_NAME = "zen_prefs"
    const val KEY_ALWAYS_OPEN_EXTERNALLY = "always_open_links_externally"
    const val KEY_ESSENTIALS_GROUPING = "essentials_grouping"
    const val KEY_SAVE_KIND = "save_tab_kind"
    const val KEY_TOOLBAR_PLACEMENT = "toolbar_placement"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_OPEN_EXTERNALLY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALWAYS_OPEN_EXTERNALLY, enabled)
            .apply()
    }

    /** Raw storage value; null when the user never chose a grouping. */
    fun getEssentialsGrouping(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ESSENTIALS_GROUPING, null)

    fun setEssentialsGrouping(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ESSENTIALS_GROUPING, value)
            .apply()
    }

    /** How shared/mini-browser tabs are saved; pinned when never chosen. */
    fun getSaveKind(context: Context): SaveKind =
        SaveKind.fromStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVE_KIND, null),
        )

    fun setSaveKind(context: Context, kind: SaveKind) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVE_KIND, kind.storageValue)
            .apply()
    }

    /** Where the action bar sits; top when the user never chose. */
    fun getToolbarPlacement(context: Context): ToolbarPlacement =
        ToolbarPlacement.fromStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOOLBAR_PLACEMENT, null),
        )

    fun setToolbarPlacement(context: Context, placement: ToolbarPlacement) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOOLBAR_PLACEMENT, placement.storageValue)
            .apply()
    }
}
