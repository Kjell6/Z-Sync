package de.kjell.zencompanion.favicon

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG

/**
 * Rasterizes an SVG favicon to a bitmap. Many sites ship only an SVG icon, and
 * the platform cannot decode SVG (Android above API 30 only, via `ImageDecoder`).
 *
 * Uses AndroidSVG, a small pure-Java renderer, so it works on every supported
 * API level and needs no WebView. The result goes into the loader's normal
 * in-memory cache, so this runs at most once per icon per launch.
 */
internal object SvgRasterizer {
    /** Rasterize [svg] into a [size]×[size] bitmap. Null on parse/failure. */
    fun rasterize(svg: String, size: Int, cacheKey: String): Bitmap? = runCatching {
        val parsed = SVG.getFromString(svg)
        // AndroidSVG 1.4 lets a document's own width/height override the rect
        // passed to renderToCanvas(): an icon that declares width="180" is drawn
        // 1:1 instead of being scaled into the target, so it comes out ~40% too
        // large and clipped at the right and bottom edge (~15% of the icon cut
        // off, which is how chatgpt.com's 180px mark showed up). The converse
        // breaks too: a 32x32 octicon shrinks into the top-left corner. Pinning
        // the document size to the target makes AndroidSVG fit the viewBox into
        // it. iOS gets this for free: its WebView renderer is handed an explicit
        // width/height on the <svg> element before drawing.
        parsed.setDocumentWidth(size.toFloat())
        parsed.setDocumentHeight(size.toFloat())
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        parsed.renderToCanvas(canvas)
        if (bitmap.isBlank()) {
            bitmap.recycle()
            null
        } else {
            bitmap
        }
    }.getOrNull()

    private fun Bitmap.isBlank(): Boolean {
        var x = 0
        while (x < width) {
            var y = 0
            while (y < height) {
                if (getPixel(x, y) != 0) return false
                y += 4
            }
            x += 4
        }
        return true
    }
}
