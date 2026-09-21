package de.kjell.zencompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.kjell.zencompanion.favicon.FaviconDecoder
import de.kjell.zencompanion.favicon.FaviconResolver
import de.kjell.zencompanion.sync.ZenSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of FaviconTests.swift from ZenCompanionTests. */
class FaviconResolverTests {
    @Test
    fun directHTTPSURLWins() {
        assertEquals(
            "https://cdn.example.com/fav.png",
            FaviconResolver.url(pageURL = "https://example.com/path", directURL = "https://cdn.example.com/fav.png"),
        )
    }

    @Test
    fun directHTTPURLWins() {
        assertEquals(
            "http://example.com/favicon.ico",
            FaviconResolver.url(pageURL = "https://example.com", directURL = "http://example.com/favicon.ico"),
        )
    }

    @Test
    fun fallsBackToDuckDuckGoHost() {
        assertEquals(
            "https://icons.duckduckgo.com/ip3/news.ycombinator.com.ico",
            FaviconResolver.url(pageURL = "https://news.ycombinator.com/item?id=1", directURL = null),
        )
    }

    @Test
    fun ignoresNonHTTPDirectURL() {
        assertEquals(
            "https://icons.duckduckgo.com/ip3/example.com.ico",
            FaviconResolver.url(pageURL = "https://example.com", directURL = "chrome://branding/content/icon.png"),
        )
    }

    @Test
    fun nilWhenPageURLHasNoHost() {
        assertNull(FaviconResolver.url(pageURL = "not a url", directURL = null))
        assertNull(FaviconResolver.url(pageURL = "", directURL = null))
    }

    @Test
    fun snapshotCollectsUniqueRemoteIconsAndSkipsStatic() {
        val remote = ZenSpaces.ZenTab(id = "a", url = "https://example.com", title = "A")
        val duplicate = ZenSpaces.ZenTab(id = "b", url = "https://example.com/other", title = "B")
        val staticIcon = ZenSpaces.ZenTab(id = "c", url = "https://static.example", title = "C", icon = "📱", hasStaticIcon = true)
        val withDirect = ZenSpaces.ZenTab(id = "d", url = "https://direct.example", title = "D", iconURL = "https://cdn.example/d.png")
        val inFolder = ZenSpaces.ZenTab(id = "e", url = "https://folder.example", title = "E")
        val essential = ZenSpaces.ZenTab(id = "f", url = "https://essential.example", title = "F")

        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "Work",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = listOf(
                ZenSpaces.ZenItem.Tab(remote),
                ZenSpaces.ZenItem.Tab(duplicate),
                ZenSpaces.ZenItem.Tab(staticIcon),
                ZenSpaces.ZenItem.Tab(withDirect),
                ZenSpaces.ZenItem.Folder(ZenSpaces.ZenFolder(id = "f1", name = "Docs", icon = null, tabs = listOf(inFolder))),
            ),
        )
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(space),
            essentials = mapOf("default" to listOf(essential, remote)),
            fetchedAtMillis = 0,
        )

        val urls = FaviconResolver.urls(snapshot).toSet()
        assertEquals(
            setOf(
                "https://icons.duckduckgo.com/ip3/example.com.ico",
                "https://cdn.example/d.png",
                "https://icons.duckduckgo.com/ip3/folder.example.ico",
                "https://icons.duckduckgo.com/ip3/essential.example.ico",
            ),
            urls,
        )
        assertFalse(urls.any { it.contains("static.example") })
    }
}

class FaviconDecoderTests {
    private val onePxPNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun rejectsEmptyAndTinyPayloads() {
        assertFalse(FaviconDecoder.isPlausible(ByteArray(0)))
        assertFalse(FaviconDecoder.isPlausible("nope".toByteArray()))
        assertTrue(FaviconDecoder.isPlausible(onePxPNG))
    }

    @Test
    fun detectsPNGAndICOContainers() {
        assertTrue(FaviconDecoder.isPng(onePxPNG))
        assertTrue(FaviconDecoder.isIco(byteArrayOf(0, 0, 1, 0, 1, 0, 0, 0)))
        assertFalse(FaviconDecoder.isIco(onePxPNG))
    }

    /** Synthetic ICO with a 1×1 and a 3×3 PNG frame: the larger frame must win. */
    @Test
    fun picksLargestICOFrame() {
        val smallPNG = onePxPNG
        val largePNG = makePNG(3, 3)

        // header + two dir entries + payloads.
        val header = byteArrayOf(0, 0, 1, 0, 2, 0)
        val payloadStart = 6 + 16 * 2
        val dir1 = buildDir(w = 1, h = 1, size = smallPNG.size, offset = payloadStart)
        val dir2 = buildDir(w = 3, h = 3, size = largePNG.size, offset = payloadStart + smallPNG.size)
        val ico = header + dir1 + dir2 + smallPNG + largePNG

        val frame = FaviconDecoder.extractLargestFrameBytes(ico)
        assertNotNull(frame)
        assertTrue(frame!!.contentEquals(largePNG))
    }

    @Test
    fun malformedICOFrameBoundsYieldNull() {
        val truncated = byteArrayOf(0, 0, 1, 0, 1, 0) + buildDir(w = 4, h = 4, size = 9999, offset = 22)
        assertNull(FaviconDecoder.extractLargestFrameBytes(truncated))
    }

    private fun buildDir(w: Int, h: Int, size: Int, offset: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = w.toByte()
        out[1] = h.toByte()
        // color count 0, reserved 0 at [2],[3]
        var planes = 1
        out[4] = planes.toByte(); out[5] = 0
        var bitCount = 32
        out[6] = bitCount.toByte(); out[7] = (bitCount shr 8).toByte()
        writeInt(out, 8, size)
        writeInt(out, 12, offset)
        return out
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        target[at + 2] = ((value shr 16) and 0xFF).toByte()
        target[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** Minimal valid truecolor PNG built by hand (pure JVM, no android.graphics). */
    private fun makePNG(w: Int, h: Int): ByteArray {
        fun crc32(chunk: ByteArray): Long {
            val crc = java.util.zip.CRC32().apply { update(chunk) }
            return crc.value
        }

        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            val body = type.toByteArray(Charsets.US_ASCII) + data
            out.write(body)
            val c = crc32(body)
            out.write(
                byteArrayOf(
                    ((c ushr 24) and 0xFF).toByte(), ((c ushr 16) and 0xFF).toByte(),
                    ((c ushr 8) and 0xFF).toByte(), (c and 0xFF).toByte(),
                ),
            )
            return out.toByteArray()
        }

        val ihdr = java.io.ByteArrayOutputStream()
        ihdr.write(
            byteArrayOf(
                ((w ushr 24) and 0xFF).toByte(), ((w ushr 16) and 0xFF).toByte(),
                ((w ushr 8) and 0xFF).toByte(), w.toByte(),
                ((h ushr 24) and 0xFF).toByte(), ((h ushr 16) and 0xFF).toByte(),
                ((h ushr 8) and 0xFF).toByte(), h.toByte(),
                8, 2, 0, 0, 0, // bit depth 8, color type 2 (truecolor)
            ),
        )
        val rowBytes = h * (1 + w * 3)
        val raw = ByteArray(rowBytes)
        var idx = 0
        for (row in 0 until h) {
            raw[idx++] = 0 // filter none
            idx += w * 3
        }
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { def ->
            def.write(raw)
            def.finish()
        }
        val idat = compressed.toByteArray()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr.toByteArray()) +
            chunk("IDAT", idat) +
            chunk("IEND", ByteArray(0))
    }
}
