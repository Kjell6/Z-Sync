package de.kjell.zencompanion.favicon

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import de.kjell.zencompanion.sync.ZenSpaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Port of `FaviconLoader` (Shared/ZenTheme.swift): in-memory decoded-image
 * cache, a small disk cache, and coalesced in-flight fetches. AsyncImage-style
 * loaders are not used: they cancel in lazy pager pages and do not share
 * results across the duplicate Essentials grids of same-container spaces.
 */
object FaviconLoader {
    private const val DISK_DIR = "zen-favicons"
    private const val TIMEOUT_MS = 12_000
    private const val MAX_CONCURRENT = 6
    private const val MAX_FAVICON_BYTES = 512 * 1024

    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT) { r ->
        Thread(r, "favicon-loader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }

    @Volatile
    private var diskDir: File? = null

    fun initialize(context: Context) {
        if (diskDir == null) {
            synchronized(this) {
                if (diskDir == null) {
                    diskDir = File(context.cacheDir, DISK_DIR).apply { mkdirs() }
                }
            }
        }
    }

    /** Count-bounded like the Swift NSCache (countLimit 400); ~20MB cost guard via byteCount. */
    private val memoryCache = object : LruCache<String, Bitmap>(400) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
        private var approxBytes = 0
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (oldValue != null) approxBytes -= oldValue.byteCount
            if (newValue != null) approxBytes += newValue.byteCount
        }
    }

    private val inflight = ConcurrentHashMap<String, Future<Bitmap?>>()

    fun cached(url: String): Bitmap? = memoryCache.get(url)

    /** Blocking fetch used from IO contexts; coalesces concurrent callers. */
    fun imageBlocking(url: String): Bitmap? {
        cached(url)?.let { return it }
        val future = inflight[url] ?: synchronized(this) {
            inflight.getOrPut(url) {
                executor.submit<Bitmap?> {
                    try {
                        val fetched = fetch(url)
                        if (fetched != null) memoryCache.put(url, fetched)
                        fetched
                    } finally {
                        synchronized(this) { inflight.remove(url) }
                    }
                }
            }
        }
        return runCatching { future.get() }.getOrNull()
    }

    suspend fun image(url: String?): Bitmap? {
        if (url == null) return null
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) { imageBlocking(url) }
    }

    /** Prefetch up to 6 concurrent loads for all icons referenced by the snapshot. */
    fun prefetch(snapshot: ZenSpaces.ZenSnapshot) {
        val urls = FaviconResolver.urls(snapshot)
        if (urls.isEmpty()) return
        executor.submit {
            kotlinx.coroutines.runBlocking {
                coroutineScope {
                    val semaphore = java.util.concurrent.Semaphore(MAX_CONCURRENT)
                    urls.map { url ->
                        async(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                imageBlocking(url)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun fetch(urlString: String): Bitmap? {
        // Snapshot URLs are hostile input: only https is ever fetched.
        val url = runCatching { URL(urlString) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        // Disk cache first (returnCacheDataElseLoad semantics).
        val dir = diskDir ?: return null
        val key = diskKey(urlString)
        val cacheFile = File(dir, key)
        if (cacheFile.exists()) {
            val bytes = runCatching { cacheFile.readBytes() }.getOrNull()
            if (bytes != null) {
                FaviconDecoder.image(bytes)?.let { return it }
                cacheFile.delete()
            }
        }
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            try {
                val code = connection.responseCode
                if (code !in 200..299) return null
                // Capped read: a hostile server must not exhaust memory.
                val data = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_FAVICON_BYTES) return null
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                FaviconDecoder.image(data)?.also {
                    runCatching { cacheFile.writeBytes(data) }
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun diskKey(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".bin"
}
