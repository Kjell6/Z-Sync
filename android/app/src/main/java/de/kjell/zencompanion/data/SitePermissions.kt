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
 * A leading `www.` is the same site as the bare host: one answer covers both.
 * Only that one prefix is folded, so maps.google.com keeps its own.
 */
internal fun foldedSiteHost(host: String): String {
    val lower = host.lowercase()
    return if (lower.startsWith("www.")) lower.removePrefix("www.") else lower
}

/**
 * Keys saved before `www.` folded into the bare host keep working: an answer
 * already stored for the bare host wins over the old `www.` one.
 */
internal fun foldedSitePermissionKeys(
    saved: Map<String, SitePermissionChoice>,
): Map<String, SitePermissionChoice> {
    val folded = mutableMapOf<String, SitePermissionChoice>()
    saved.forEach { (key, choice) ->
        val bare = if (key.startsWith("www.")) key.removePrefix("www.") else key
        if (folded[bare] == null || bare == key) folded[bare] = choice
    }
    return folded
}

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

    /** Kinds whose question went unanswered this run: no until the app restarts. */
    private val unanswered = mutableSetOf<String>()

    /** Sites whose closed system access was already explained this run. */
    private val explainedSystemAccess = mutableSetOf<String>()

    fun start(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = stored
        val saved = stored.all.entries.mapNotNull { (key, value) ->
            SitePermissionChoice.entries.firstOrNull { it.storageValue == value }?.let { key to it }
        }.toMap()
        val folded = foldedSitePermissionKeys(saved)
        if (folded != saved) {
            // Answers saved under a leading `www.` move over once, so a later
            // change and a read agree on the same key.
            val editor = stored.edit().clear()
            folded.forEach { (key, choice) -> editor.putString(key, choice.storageValue) }
            editor.apply()
        }
        _choices.value = folded
    }

    fun decision(kinds: List<SitePermission>, host: String): SitePermissionDecision =
        sitePermissionDecision(kinds) { kind ->
            // A question that went away unanswered is a no for the rest of this
            // run, so the page hears the refusal instead of asking again.
            if (key(kind, host) in unanswered) SitePermissionChoice.BLOCK else choice(kind, host)
        }

    fun choice(kind: SitePermission, host: String): SitePermissionChoice? = _choices.value[key(kind, host)]

    /**
     * Nobody answered the question: that is a no for the rest of this run, and
     * only in memory, so the next launch asks again.
     */
    fun markUnanswered(kinds: List<SitePermission>, host: String) {
        kinds.forEach { unanswered += key(it, host) }
    }

    /**
     * True only the first time this run explains that the system refuses a
     * site, so a page asking again just hears the refusal.
     */
    fun shouldExplainSystemAccess(host: String): Boolean = explainedSystemAccess.add(foldedSiteHost(host))

    fun set(choice: SitePermissionChoice?, kinds: List<SitePermission>, host: String) {
        val updated = _choices.value.toMutableMap()
        val editor = prefs?.edit()
        kinds.forEach { kind ->
            val key = key(kind, host)
            // A real answer replaces what an unanswered question left behind.
            unanswered -= key
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
        unanswered.clear()
        explainedSystemAccess.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun key(kind: SitePermission, host: String) = "${foldedSiteHost(host)}|${kind.storageValue}"
}
