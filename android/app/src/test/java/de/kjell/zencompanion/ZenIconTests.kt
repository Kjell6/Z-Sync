package de.kjell.zencompanion

import de.kjell.zencompanion.ui.components.assetName
import de.kjell.zencompanion.ui.components.dataUrlBytes
import de.kjell.zencompanion.ui.components.embeddedRasterBytes
import de.kjell.zencompanion.ui.components.emojiFromDataUrl
import de.kjell.zencompanion.ui.components.folderFlapIcon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Covers the icon payloads Zen syncs for tabs and folders
 * (`ZenEmojiPicker.mjs`, `ZenSpacesSyncModel.sys.mjs`). Ports ZenIconTests.swift.
 */
class ZenIconDecoderTests {
    /** Byte-for-byte the SVG `ZenEmojiPicker.#selectEmoji(emojiAsSVG:)` builds. */
    private fun emojiDataUrl(emoji: String): String {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\" font-size=\"28\" x=\"0\">$emoji</text></svg>"
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun extractsEmojiFromZenSVGDataUrl() {
        assertEquals("📁", emojiFromDataUrl(emojiDataUrl("📁")))
        assertEquals("🚀", emojiFromDataUrl(emojiDataUrl("🚀")))
        // Multi-codepoint emoji must survive too (ZWJ sequence).
        assertEquals("👨‍👩‍👧‍👦", emojiFromDataUrl(emojiDataUrl("👨‍👩‍👧‍👦")))
    }

    @Test
    fun unescapesXmlText() {
        assertEquals("a&b", emojiFromDataUrl(emojiDataUrl("a&amp;b")))
        assertEquals("It's", emojiFromDataUrl(emojiDataUrl("It&#39;s")))
    }

    @Test
    fun ignoresNonEmojiIcons() {
        assertNull(emojiFromDataUrl("chrome://browser/skin/zen-icons/selectable/baseball.svg"))
        assertNull(emojiFromDataUrl("📁"))
        assertNull(emojiFromDataUrl("data:image/png;base64,iVBORw0KGgo="))
        assertNull(emojiFromDataUrl(""))
    }

    /** Live Zen sidebar payload: `viewBox="0 0 100 100"` and `y=".9em"`, not the picker SVG. */
    @Test
    fun extractsEmojiFromLiveSidebarSvg() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><text y=\".9em\" font-size=\"90\">✨</text></svg>"
        val url = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        assertEquals("✨", emojiFromDataUrl(url))
    }

    @Test
    fun extractsEmojiWhenCharsetPrecedesBase64() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\" font-size=\"28\">🚀</text></svg>"
        val url = "data:image/svg+xml;charset=utf-8;base64," + Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        assertEquals("🚀", emojiFromDataUrl(url))
    }

    @Test
    fun extractsEmojiFromPercentEncodedSvg() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\">🚀</text></svg>"
        val encoded = java.net.URLEncoder.encode(svg, "UTF-8").replace("+", "%20")
        assertEquals("🚀", emojiFromDataUrl("data:image/svg+xml,$encoded"))
    }

    @Test
    fun extractsRasterNestedInSvg() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><image xlink:href=\"data:image/png;base64,${Base64.getEncoder().encodeToString(png)}\"/></svg>"
        assertArrayEquals(png, embeddedRasterBytes(svg.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun folderFlapSkipsPlainFolderEmoji() {
        assertNull(folderFlapIcon(null))
        assertNull(folderFlapIcon(""))
        assertNull(folderFlapIcon("   "))
        assertNull(folderFlapIcon("📁"))
        assertNull(folderFlapIcon("📂"))
        assertNull(folderFlapIcon(emojiDataUrl("📁")))
        assertNull(folderFlapIcon(emojiDataUrl("📂")))
        val rocket = "chrome://browser/skin/zen-icons/selectable/rocket.svg"
        assertEquals(rocket, folderFlapIcon(rocket))
        assertEquals(emojiDataUrl("🚀"), folderFlapIcon(emojiDataUrl("🚀")))
        assertEquals("ticket", folderFlapIcon("ticket"))
    }

    @Test
    fun extractsDataUrlBytes() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        val base64Url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        assertArrayEquals(png, dataUrlBytes(base64Url))
        assertNotNull(dataUrlBytes("data:image/svg+xml,%3Csvg%3E%3C/svg%3E"))
        assertNull(dataUrlBytes("chrome://browser/skin/zen-icons/selectable/baseball.svg"))
        assertNull(dataUrlBytes("data:image/png;base64"))
    }
}

class ZenIconAssetTests {
    @Test
    fun assetNameForSelectableChromeUrl() {
        assertEquals("zen_baseball", assetName("chrome://browser/skin/zen-icons/selectable/baseball.svg"))
        assertEquals("zen_globe_1", assetName("chrome://browser/skin/zen-icons/selectable/globe-1.svg"))
    }

    @Test
    fun assetNameForBareNamesAndFiles() {
        assertEquals("zen_baseball", assetName("baseball"))
        assertEquals("zen_baseball", assetName("baseball.svg"))
        assertEquals("zen_baseball", assetName("baseball.png"))
        assertNull(assetName("   "))
    }
}
