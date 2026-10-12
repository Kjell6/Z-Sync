package de.kjell.zencompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.kjell.zencompanion.favicon.FaviconDecoder
import de.kjell.zencompanion.favicon.FaviconDiscovery
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
    fun syncedURLDoesNotUseAnIconService() {
        assertNull(FaviconResolver.url(pageURL = "https://news.ycombinator.com/item?id=1", directURL = null))
        val document = FaviconDiscovery.documentURL("https://user:secret@news.ycombinator.com/item?id=1#c")
        assertEquals("https://news.ycombinator.com/item", document.toString())
        val urls = FaviconDiscovery.candidates(null, null, document!!)
        assertEquals(listOf("https://news.ycombinator.com/favicon.ico"), urls)
        assertTrue(urls.none { it.contains("duckduckgo") || it.contains("google.com/s2") })
    }

    @Test
    fun ignoresNonHTTPDirectURL() {
        assertNull(
            FaviconResolver.url(pageURL = "https://example.com", directURL = "chrome://branding/content/icon.png"),
        )
        assertEquals(
            "site:https://example.com",
            FaviconResolver.cacheKey(pageURL = "https://example.com", directURL = "chrome://branding/content/icon.png"),
        )
    }

    @Test
    fun nilWhenPageURLHasNoHost() {
        assertNull(FaviconResolver.url(pageURL = "not a url", directURL = null))
        assertNull(FaviconResolver.url(pageURL = "", directURL = null))
    }

    @Test
    fun inlineDataIconOverridesRemoteLookup() {
        val data =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        // The synced inline icon wins even when a direct URL is also present.
        assertEquals(
            "data:https://www.figma.com/design/x",
            FaviconResolver.cacheKey(
                pageURL = "https://www.figma.com/design/x",
                directURL = "https://cdn.example/d.png",
                dataURL = data,
            ),
        )
        // Non-image static icons (emoji, chrome: refs) are not data icons.
        assertEquals(
            "site:https://static.example",
            FaviconResolver.cacheKey(pageURL = "https://static.example", directURL = null, dataURL = "📱"),
        )
    }

    @Test
    fun dataIconTabIsFetchedEvenWhenMarkedStatic() {
        val data =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val dataTab = ZenSpaces.ZenTab(
            id = "a",
            url = "http://localhost:3456/?cols=5",
            title = "Board",
            icon = data,
            hasStaticIcon = false,
        )
        val emojiTab = ZenSpaces.ZenTab(
            id = "b",
            url = "https://emoji.example",
            title = "Emoji",
            icon = "📱",
            hasStaticIcon = true,
        )
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "s1",
                    name = "Work",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = listOf(ZenSpaces.ZenItem.Tab(dataTab), ZenSpaces.ZenItem.Tab(emojiTab)),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 0,
        )
        val requests = FaviconResolver.requests(snapshot)
        assertEquals(listOf("data:http://localhost:3456/?cols=5"), requests.map { it.cacheKey })
        assertEquals(data, requests.first().dataURL)
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

        val keys = FaviconResolver.requests(snapshot).map { it.cacheKey }.toSet()
        assertEquals(
            setOf(
                "site:https://example.com",
                "direct:https://cdn.example/d.png",
                "site:https://folder.example",
                "site:https://essential.example",
            ),
            keys,
        )
        assertFalse(keys.any { it.contains("static.example") })
    }
}

class FaviconDiscoveryTests {
    @Test
    fun declaredIconsPreferTouchPNGAndKeepSVGLast() {
        val html = """
            <head>
            <base href="https://cdn.example/app/">
            <link rel="mask-icon" href="/mask.svg" color="#000">
            <link rel="icon" type="image/svg+xml" href="icon.svg">
            <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
            <link rel="shortcut icon" href="//static.example/fav.ico">
            <link rel="icon" href="plain.png" sizes="32x32">
            </head>
        """.trimIndent()
        val page = java.net.URI("https://ticktick.com/home")
        val urls = FaviconDiscovery.candidates(html, page, page)
        assertEquals(
            listOf(
                "https://cdn.example/touch.png",
                "https://cdn.example/app/plain.png",
                "https://static.example/fav.ico",
                "https://cdn.example/app/icon.svg",
                "https://ticktick.com/favicon.ico",
            ),
            urls,
        )
    }

    @Test
    fun svgIconDetection() {
        assertTrue(FaviconDiscovery.isSvgIcon("https://variant.com/favicon-light.svg"))
        assertTrue(FaviconDiscovery.isSvgIcon("https://x.com/favicon.svg?v=2"))
        assertFalse(FaviconDiscovery.isSvgIcon("https://x.com/favicon.ico"))
        assertFalse(FaviconDiscovery.isSvgIcon("https://cdn.example/app/plain.png"))
    }

    @Test
    fun loginPageIconsAreIgnoredForCalendar() {
        val html = "<head><link rel=\"icon\" href=\"https://www.google.com/favicon.ico\"></head>"
        val page = java.net.URI("https://calendar.google.com/calendar/u/0/r")
        val finalURL = java.net.URI("https://accounts.google.com/v3/signin/identifier")
        val urls = FaviconDiscovery.candidates(html, finalURL, page)
        assertEquals(
            "https://calendar.google.com/googlecalendar/images/favicons_2020q4/calendar_31.ico",
            urls.first(),
        )
        assertFalse(urls.contains("https://www.google.com/favicon.ico"))
        assertTrue(urls.contains("https://calendar.google.com/favicon.ico"))
    }

    @Test
    fun docsProductsUseTheirOwnIcon() {
        val sheets = java.net.URI("https://docs.google.com/spreadsheets/d/abc")
        val docs = java.net.URI("https://docs.google.com/document/d/abc")
        assertEquals(
            "https://ssl.gstatic.com/docs/spreadsheets/favicon3.ico",
            FaviconDiscovery.candidates(null, null, sheets).first(),
        )
        assertEquals(
            "https://ssl.gstatic.com/docs/documents/images/kix-favicon7.ico",
            FaviconDiscovery.candidates(null, null, docs).first(),
        )
        assertEquals(
            "site:https://docs.google.com/spreadsheets",
            FaviconResolver.cacheKey("https://docs.google.com/spreadsheets/d/abc?usp=share", null),
        )
    }

    @Test
    fun loopbackIsNotFetched() {
        assertTrue(FaviconDiscovery.isLoopback(java.net.URI("http://localhost:3000/board")))
        assertFalse(FaviconDiscovery.isLoopback(java.net.URI("https://example.com")))
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
