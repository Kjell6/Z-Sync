package de.kjell.zencompanion

import android.content.ContextWrapper
import android.content.SharedPreferences
import de.kjell.zencompanion.data.SitePermission
import de.kjell.zencompanion.data.SitePermissionChoice
import de.kjell.zencompanion.data.SitePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What the browser remembers per site, read back the way the next launch reads
 * it: the answers on disk are the whole state.
 */
class SitePermissionsStoreTests {

    @Before
    fun forgetSavedPermissions() {
        SitePermissions.clear()
    }

    @Test
    fun aHostWithTwoWwwPrefixesKeepsItsAnswerAcrossARestart() {
        val prefs = FakeSitePermissionStore()
        val context = FakePrefsContext(prefs)
        SitePermissions.start(context)
        SitePermissions.set(SitePermissionChoice.ALLOW, listOf(SitePermission.LOCATION), "www.www.example.com")

        // The next launch reads the same saved keys again.
        SitePermissions.start(context)

        // One leading `www.` was folded off, and the rest of the host stays.
        assertEquals(
            SitePermissionChoice.ALLOW,
            SitePermissions.choice(SitePermission.LOCATION, "www.www.example.com"),
        )
        assertEquals(
            mapOf("www.example.com|location" to SitePermissionChoice.ALLOW),
            SitePermissions.choices.value,
        )
        // It never became the bare host's answer.
        assertNull(SitePermissions.choice(SitePermission.LOCATION, "example.com"))
    }

    @Test
    fun anAnswerSavedUnderWWWStillMovesOverOnTheFirstLaunch() {
        val prefs = FakeSitePermissionStore()
        prefs.values["www.example.com|location"] = SitePermissionChoice.ALLOW.storageValue
        SitePermissions.start(FakePrefsContext(prefs))

        assertEquals(
            mapOf("example.com|location" to SitePermissionChoice.ALLOW),
            SitePermissions.choices.value,
        )
    }
}

/** A Context that only hands out the saved answers. */
private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

/** Saved answers in memory, kept across the simulated launches. */
private class FakeSitePermissionStore : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val changed = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var cleared = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null && value != null) changed[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) changed[key] = values
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) changed[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) changed[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) changed[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) changed[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) removed += key
        }

        override fun clear(): SharedPreferences.Editor = apply { cleared = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (cleared) values.clear()
            removed.forEach(values::remove)
            values.putAll(changed)
        }
    }
}
