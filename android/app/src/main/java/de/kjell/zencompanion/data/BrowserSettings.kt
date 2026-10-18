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
 * above the essentials grid or below the space switcher (default).
 */
enum class ToolbarPlacement(val storageValue: String) {
    TOP("top"),
    BOTTOM("bottom"),
    ;

    companion object {
        fun fromStorage(value: String?): ToolbarPlacement =
            entries.firstOrNull { it.storageValue == value } ?: BOTTOM
    }
}

/**
 * How many essentials sit on one row of the start-page grid.
 *
 * A local layout preference stored under [BrowserSettings.KEY_ESSENTIALS_COLUMNS]
 * and edited in the settings sheet (Advanced).
 */
enum class EssentialsColumns(val storageValue: String) {
    /** Adaptive default: 1–4 items fill one row, 5–6 use three columns, 7+ use four. */
    AUTOMATIC("automatic"),
    ONE("1"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    ;

    /**
     * Columns for a grid holding [count] essentials. A fixed choice is capped
     * at the item count so a short final row never leaves an empty column
     * behind — it stretches to fill the width instead. Never below one.
     */
    fun columnCountFor(count: Int): Int = when (this) {
        AUTOMATIC -> when {
            count <= 0 -> 1
            count <= 4 -> count
            count <= 6 -> 3
            else -> 4
        }
        ONE -> 1
        TWO -> count.coerceIn(1, 2)
        THREE -> count.coerceIn(1, 3)
        FOUR -> count.coerceIn(1, 4)
        FIVE -> count.coerceIn(1, 5)
    }

    companion object {
        fun fromStorage(value: String?): EssentialsColumns =
            entries.firstOrNull { it.storageValue == value } ?: AUTOMATIC
    }
}

/**
 * Whether tapping a tab opens the URL in the system default browser
 * instead of the in-app mini-browser, plus the essentials grouping override,
 * the essentials grid columns and the pinned/normal save kind.
 */
object BrowserSettings {
    const val PREFS_NAME = "zen_prefs"
    const val KEY_ALWAYS_OPEN_EXTERNALLY = "always_open_links_externally"
    const val KEY_ESSENTIALS_GROUPING = "essentials_grouping"
    const val KEY_ESSENTIALS_COLUMNS = "essentials_columns"
    const val KEY_SAVE_KIND = "save_tab_kind"
    const val KEY_TOOLBAR_PLACEMENT = "toolbar_placement"
    const val KEY_OPEN_INTO_SEARCH = "open_into_search"

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

    /** How many essentials share a grid row; adaptive when never chosen. */
    fun getEssentialsColumns(context: Context): EssentialsColumns =
        EssentialsColumns.fromStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ESSENTIALS_COLUMNS, null),
        )

    fun setEssentialsColumns(context: Context, columns: EssentialsColumns) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ESSENTIALS_COLUMNS, columns.storageValue)
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

    /** Where the action bar sits; bottom when the user never chose. */
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

    /** Whether the app opens into a new search tab; off when never chosen. */
    fun getOpenIntoSearch(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPEN_INTO_SEARCH, false)

    fun setOpenIntoSearch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPEN_INTO_SEARCH, enabled)
            .apply()
    }
}
