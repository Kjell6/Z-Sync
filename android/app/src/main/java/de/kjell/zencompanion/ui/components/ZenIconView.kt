package de.kjell.zencompanion.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.util.Base64

/**
 * Port of `ZenIconView` (Shared/ZenTheme.swift): renders Zen vector assets,
 * synced data-URL icons (emoji-as-SVG or raster), fallback emojis/text, or a
 * small dot.
 */
@Composable
fun ZenIconView(
    icon: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    foreground: Color? = null,
) {
    val clean = icon?.trim()
    if (clean.isNullOrEmpty()) return

    val context = LocalContext.current
    val tint = foreground ?: LocalContentColor.current

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .wrapContentSize(Alignment.Center, unbounded = true),
        contentAlignment = Alignment.Center,
    ) {
        val emoji = remember(clean) { emojiFromDataUrl(clean) }
        val assetName = remember(clean) { assetName(clean) }
        val resId = remember(assetName) {
            if (assetName != null) {
                context.resources.getIdentifier(assetName, "drawable", context.packageName)
            } else 0
        }

        when {
            emoji != null -> {
                // Emoji static icons arrive as `<text>` inside an SVG data URL.
                Text(
                    text = emoji,
                    fontSize = (size.value * 0.90f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            clean.startsWith("data:") -> {
                // Non-emoji data payloads (favicon raster bytes, or an SVG that
                // only wraps one). Never fall through to the text branch: a
                // base64 blob must not render as text.
                val bitmap = remember(clean) {
                    val bytes = dataUrlBytes(clean) ?: return@remember null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: embeddedRasterBytes(bytes)?.let { nested ->
                            BitmapFactory.decodeByteArray(nested, 0, nested.size)
                        }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(size * 0.88f),
                    )
                }
            }
            resId != 0 -> {
                Image(
                    painter = painterResource(resId),
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.88f),
                    colorFilter = ColorFilter.tint(tint),
                )
            }
            !clean.startsWith("http") && !clean.startsWith("chrome:") && !clean.startsWith("file:") -> {
                // Original visual size, unbounded container prevents edge clipping
                Text(
                    text = clean,
                    fontSize = (size.value * 0.90f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            else -> {
                Box(
                    Modifier
                        .size(size * 0.38f)
                        .background(tint, CircleShape),
                )
            }
        }
    }
}

/** Port of `ZenIconView.assetName(for:)`. */
fun assetName(raw: String): String? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    // Handles full URIs like chrome://browser/skin/zen-icons/selectable/baseball.svg
    val lastSegment = clean.split("/").lastOrNull() ?: clean
    val base = lastSegment
        .replace(".svg", "")
        .replace(".png", "")
        .trim()
    if (base.isEmpty()) return null
    return "zen_${base.replace('-', '_')}"
}

private val TEXT_NODE = Regex("<text[^>]*>(.*?)</text", RegexOption.DOT_MATCHES_ALL)

private val XML_ESCAPES = listOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&amp;" to "&",
)

private fun unescapeXml(text: String): String =
    XML_ESCAPES.fold(text) { acc, (escaped, raw) -> acc.replace(escaped, raw) }

/**
 * SVG source of a `data:image/svg+xml` URL.
 *
 * The picker writes `data:image/svg+xml;base64,…`. Live sidebar icons also
 * arrive as `charset=utf-8` base64 and as percent-encoded SVG, both with the
 * same `<text>` node (`viewBox="0 0 32 32"` or `0 0 100 100`).
 */
internal fun svgDocument(icon: String): String? {
    if (!icon.trim().lowercase().startsWith("data:image/svg+xml")) return null
    val bytes = dataUrlBytes(icon) ?: return null
    val svg = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    return svg.takeIf { it.contains("<svg", ignoreCase = true) }
}

/** The emoji inside Zen's emoji-as-SVG data URL, null for every other icon. */
internal fun emojiFromDataUrl(icon: String): String? {
    val svg = svgDocument(icon) ?: return null
    val match = TEXT_NODE.find(svg) ?: return null
    return unescapeXml(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
}

private val IMAGE_HREF = Regex(
    """(?:xlink:)?href\s*=\s*["'](data:image/[^"']+)["']""",
    RegexOption.IGNORE_CASE,
)

/** Bitmap bytes nested in an SVG `<image href="data:image/…">`. */
internal fun embeddedRasterBytes(svgOrBytes: ByteArray): ByteArray? {
    val svg = runCatching { String(svgOrBytes, Charsets.UTF_8) }.getOrNull() ?: return null
    val href = IMAGE_HREF.find(svg)?.groupValues?.get(1) ?: return null
    return dataUrlBytes(unescapeXml(href))
}

/**
 * Icon drawn inside the folder flap. Empty and the plain folder emojis
 * (raw or wrapped in Zen's emoji SVG) stay null: the silhouette is enough.
 */
internal fun folderFlapIcon(icon: String?): String? {
    val clean = icon?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val glyph = emojiFromDataUrl(clean) ?: clean
    return if (isPlainFolderEmoji(glyph)) null else clean
}

private fun isPlainFolderEmoji(value: String): Boolean {
    val stripped = value.replace("\uFE0F", "")
    return stripped == "📁" || stripped == "📂"
}

/** Raw bytes of any `data:` URL, base64 or percent-encoded. */
internal fun dataUrlBytes(icon: String): ByteArray? {
    if (!icon.startsWith("data:")) return null
    val comma = icon.indexOf(',')
    if (comma < 0) return null
    val meta = icon.substring(0, comma).lowercase()
    val payload = icon.substring(comma + 1)
    return if (meta.contains(";base64")) {
        runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
    } else {
        runCatching { java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8) }
            .getOrNull()
    }
}
