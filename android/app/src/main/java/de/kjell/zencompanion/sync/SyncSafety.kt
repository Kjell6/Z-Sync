package de.kjell.zencompanion.sync

import android.content.Context
import android.content.SharedPreferences
import de.kjell.zencompanion.data.AppContextHolder

/**
 * Local switch for conditional (conflict-safe) sync writes. This is not part
 * of the wire contract (SPEC §8): when off, the service falls back to the
 * legacy unconditional request sequence byte-for-byte.
 */
object SyncSafety {
    const val PREFS_NAME = "zen_prefs"
    const val KEY_SAFE_SYNC_ENABLED = "safe_sync_enabled"

    /** Test override; when non-null it wins over the persisted pref. */
    internal var overrideForTests: Boolean? = null

    /**
     * Read at call time so a settings change applies to the next write.
     * Defaults to on, and stays usable in pure-JVM tests where
     * [AppContextHolder] was never initialized.
     */
    var safeSyncEnabled: Boolean
        get() = overrideForTests ?: runCatching {
            prefs().getBoolean(KEY_SAFE_SYNC_ENABLED, true)
        }.getOrDefault(true)
        set(value) {
            runCatching { prefsOrNull()?.edit()?.putBoolean(KEY_SAFE_SYNC_ENABLED, value)?.apply() }
        }

    private fun prefs(): SharedPreferences {
        val context = AppContextHolder.appContext
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun prefsOrNull(): SharedPreferences? = runCatching {
        val context = AppContextHolder.appContext
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()
}
