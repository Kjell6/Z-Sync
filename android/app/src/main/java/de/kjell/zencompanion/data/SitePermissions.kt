package de.kjell.zencompanion.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a site can ask for. The browser asks first; the system's own question
 * only follows once the reader has allowed the site.
 */
enum class SitePermission(val storageValue: String, val systemPermissions: List<String>) {
    CAMERA("camera", listOf(Manifest.permission.CAMERA)),
    MICROPHONE("microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION(
        "location",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
}

/** A remembered answer for one site. No entry means the browser asks. */
enum class SitePermissionChoice(val storageValue: String) {
    ALLOW("allow"),
    BLOCK("block"),
}

enum class SitePermissionDecision { GRANT, DENY, ASK }

/**
 * One blocked kind blocks the whole request, and every kind must be allowed for
 * it to pass without asking.
 */
fun sitePermissionDecision(
    kinds: List<SitePermission>,
    choiceOf: (SitePermission) -> SitePermissionChoice?,
): SitePermissionDecision {
    val answers = kinds.map(choiceOf)
    return when {
        SitePermissionChoice.BLOCK in answers -> SitePermissionDecision.DENY
        answers.isNotEmpty() && answers.all { it == SitePermissionChoice.ALLOW } -> SitePermissionDecision.GRANT
        else -> SitePermissionDecision.ASK
    }
}

/**
 * The browser's answers per site, on this phone only: never synced and left
 * out of device backups.
 *
 * iOS port: `SitePermissionStore` in `Browser/SitePermissions.swift`.
 */
object SitePermissions {
    private const val PREFS = "site_permissions"
    private var prefs: SharedPreferences? = null

    private val _choices = MutableStateFlow<Map<String, SitePermissionChoice>>(emptyMap())
    val choices: StateFlow<Map<String, SitePermissionChoice>> = _choices.asStateFlow()

    fun start(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = stored
        _choices.value = stored.all.entries.mapNotNull { (key, value) ->
            SitePermissionChoice.entries.firstOrNull { it.storageValue == value }?.let { key to it }
        }.toMap()
    }

    fun decision(kinds: List<SitePermission>, host: String): SitePermissionDecision =
        sitePermissionDecision(kinds) { choice(it, host) }

    fun choice(kind: SitePermission, host: String): SitePermissionChoice? = _choices.value[key(kind, host)]

    fun set(choice: SitePermissionChoice?, kinds: List<SitePermission>, host: String) {
        val updated = _choices.value.toMutableMap()
        val editor = prefs?.edit()
        kinds.forEach { kind ->
            val key = key(kind, host)
            if (choice == null) {
                updated.remove(key)
                editor?.remove(key)
            } else {
                updated[key] = choice
                editor?.putString(key, choice.storageValue)
            }
        }
        editor?.apply()
        _choices.value = updated
    }

    fun clear() {
        _choices.value = emptyMap()
        prefs?.edit()?.clear()?.apply()
    }

    private fun key(kind: SitePermission, host: String) = "${host.lowercase()}|${kind.storageValue}"
}
