package de.kjell.zencompanion.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the quick browser can block. Each has its own switch. */
enum class BlockCategory(internal val storageKey: String) {
    ADS("ads"),
    TRACKERS("trackers"),
    COOKIE_BANNERS("cookie_banners"),
}

/** The key a site's own switches are stored under: the host without `www.`. */
fun blockingSiteKey(host: String): String = host.lowercase().trimEnd('.').removePrefix("www.")

/**
 * The three switches in Settings, and the sites where one of them is turned
 * off from the browser menu. A site can only turn off what Settings has on.
 */
data class ContentBlockingSettings(
    val ads: Boolean = true,
    val trackers: Boolean = true,
    val cookieBanners: Boolean = true,
    val offForSites: Map<BlockCategory, Set<String>> = emptyMap(),
) {
    fun isOn(category: BlockCategory): Boolean = when (category) {
        BlockCategory.ADS -> ads
        BlockCategory.TRACKERS -> trackers
        BlockCategory.COOKIE_BANNERS -> cookieBanners
    }

    /** On in Settings and not turned off for the site of [host]. */
    fun isOn(category: BlockCategory, host: String?): Boolean =
        isOn(category) && (host == null || blockingSiteKey(host) !in offForSites[category].orEmpty())

    fun with(category: BlockCategory, on: Boolean): ContentBlockingSettings = when (category) {
        BlockCategory.ADS -> copy(ads = on)
        BlockCategory.TRACKERS -> copy(trackers = on)
        BlockCategory.COOKIE_BANNERS -> copy(cookieBanners = on)
    }

    fun withSite(category: BlockCategory, host: String, on: Boolean): ContentBlockingSettings {
        val site = blockingSiteKey(host)
        val sites = offForSites[category].orEmpty()
        val updated = if (on) sites - site else sites + site
        return copy(offForSites = offForSites + (category to updated))
    }

    /** Every switch that is on in Settings is off for this site. */
    fun allOffFor(host: String): Boolean {
        val active = BlockCategory.entries.filter { isOn(it) }
        return active.isNotEmpty() && active.none { isOn(it, host) }
    }

    /** The sites where cookie banners are left alone. */
    fun cookieBannerSitesOff(): Set<String> = offForSites[BlockCategory.COOKIE_BANNERS].orEmpty()
}

/** Reads and writes the switches and the update bookkeeping. */
internal class ContentBlockingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ContentBlockingSettings = ContentBlockingSettings(
        ads = prefs.getBoolean(key(BlockCategory.ADS), true),
        trackers = prefs.getBoolean(key(BlockCategory.TRACKERS), true),
        cookieBanners = prefs.getBoolean(key(BlockCategory.COOKIE_BANNERS), true),
        offForSites = BlockCategory.entries.associateWith { category ->
            prefs.getStringSet(siteKey(category), emptySet()).orEmpty().toSet()
        },
    )

    fun save(settings: ContentBlockingSettings) {
        prefs.edit().apply {
            BlockCategory.entries.forEach { category ->
                putBoolean(key(category), settings.isOn(category))
                putStringSet(siteKey(category), settings.offForSites[category].orEmpty())
            }
        }.apply()
    }

    /** Whether the setup question was answered. */
    var asked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED, value).apply()

    /** When both lists last downloaded, in milliseconds since the epoch. */
    var listsUpdatedAt: Long
        get() = prefs.getLong(KEY_UPDATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATED_AT, value).apply()

    private fun key(category: BlockCategory) = "block_${category.storageKey}"
    private fun siteKey(category: BlockCategory) = "off_for_sites_${category.storageKey}"

    private companion object {
        const val PREFS = "content_blocking"
        const val KEY_ASKED = "setup_asked"
        const val KEY_UPDATED_AT = "lists_updated_at"
    }
}

/** One of the two filter lists, as shipped and as downloaded. */
internal enum class FilterList(val fileName: String, val url: String, val category: BlockCategory) {
    EASYLIST("easylist.txt", "https://easylist.to/easylist/easylist.txt", BlockCategory.ADS),
    EASYPRIVACY("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt", BlockCategory.TRACKERS),
}

/** When to download fresh lists, and which downloads to trust. */
internal object FilterListUpdates {
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    /** Well above the real lists, so a broken server can't fill the phone. */
    const val MAX_BYTES = 16 * 1024 * 1024
    /** Both real lists have tens of thousands; far fewer means a bad download. */
    const val MIN_RULES = 1_000

    /** A clock set back (or a value from the future) counts as due. */
    fun isDue(lastUpdate: Long, now: Long): Boolean = lastUpdate <= 0 || now - lastUpdate >= INTERVAL_MS || lastUpdate > now

    /** The parsed list, or null when the download isn't a usable filter list. */
    fun accept(text: String): BlockList? {
        if (!text.startsWith("[Adblock")) return null
        return BlockList.parse(text).takeIf { it.ruleCount >= MIN_RULES }
    }
}

/**
 * The app's content blocker: the switches, and the two lists once they are
 * loaded. Lists load in the background at app start; until then nothing is
 * blocked, which only matters for a page opened in the first moment.
 */
object ContentBlocking {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: ContentBlockingStore? = null

    private val _settings = MutableStateFlow(ContentBlockingSettings())
    val settings: StateFlow<ContentBlockingSettings> = _settings.asStateFlow()

    private val _asked = MutableStateFlow(true)
    /** False until the setup question was answered. */
    val asked: StateFlow<Boolean> = _asked.asStateFlow()

    private val _lastModified = MutableStateFlow<String?>(null)
    /** The ad list's own date, for the caption in Settings. */
    val lastModified: StateFlow<String?> = _lastModified.asStateFlow()

    @Volatile
    private var lists: Map<BlockCategory, BlockList> = emptyMap()

    @Volatile
    private var consentScript: String? = null

    fun start(context: Context) {
        val app = context.applicationContext
        val store = ContentBlockingStore(app).also { store = it }
        _settings.value = store.load()
        _asked.value = store.asked
        scope.launch {
            loadLists(app)
            // Nothing to keep current while both list switches are off.
            val listsInUse = _settings.value.ads || _settings.value.trackers
            if (listsInUse && FilterListUpdates.isDue(store.listsUpdatedAt, System.currentTimeMillis())) {
                if (downloadLists(app)) {
                    store.listsUpdatedAt = System.currentTimeMillis()
                    loadLists(app)
                }
            }
        }
    }

    fun update(settings: ContentBlockingSettings) {
        _settings.value = settings
        store?.save(settings)
    }

    /** The answer to the setup question: the three switches, saved at once. */
    fun answerSetup(settings: ContentBlockingSettings) {
        update(settings)
        store?.asked = true
        _asked.value = true
    }

    /** The category that blocks [requestHost] on a page from [pageHost], if any. */
    fun blockedCategory(requestHost: String, pageHost: String?, settings: ContentBlockingSettings): BlockCategory? {
        val loaded = lists
        for ((category, list) in loaded) {
            if (settings.isOn(category, pageHost) && list.blocks(requestHost, pageHost)) return category
        }
        return null
    }

    /** The bundled autoconsent script, read once. */
    fun consentScript(context: Context): String? {
        consentScript?.let { return it }
        return runCatching {
            context.assets.open("$ASSET_DIR/autoconsent.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { consentScript = it }
    }

    private fun loadLists(context: Context) {
        val loaded = FilterList.entries.mapNotNull { list ->
            val parsed = downloadedFile(context, list).takeIf { it.exists() }
                ?.let { runCatching { FilterListUpdates.accept(it.readText()) }.getOrNull() }
                ?: runCatching {
                    context.assets.open("$ASSET_DIR/${list.fileName}").bufferedReader().use { BlockList.parse(it.readText()) }
                }.getOrNull()
            parsed?.let { list.category to it }
        }.toMap()
        lists = loaded
        _lastModified.value = loaded[BlockCategory.ADS]?.lastModified
    }

    /** Fetches both lists; keeps the old files unless both downloads are good. */
    private fun downloadLists(context: Context): Boolean {
        val fetched = FilterList.entries.associateWith { list ->
            val text = runCatching { fetch(list.url) }
                .onFailure { Log.i(TAG, "List download failed: ${it.message}") }
                .getOrNull() ?: return false
            if (FilterListUpdates.accept(text) == null) return false
            text
        }
        fetched.forEach { (list, text) ->
            val target = downloadedFile(context, list)
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${list.fileName}.part")
            partial.writeText(text)
            if (!partial.renameTo(target)) partial.delete()
        }
        return true
    }

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > FilterListUpdates.MAX_BYTES) throw IOException("list too large")
                }
                buffer.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadedFile(context: Context, list: FilterList) =
        File(File(context.filesDir, ASSET_DIR), list.fileName)

    private const val ASSET_DIR = "content-blocking"
    private const val TAG = "ContentBlocking"
}
