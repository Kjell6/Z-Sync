package de.kjell.zencompanion.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas

/** Simple wrapper pairing an ARGB color with the custom and primary flags from the wire format. */
data class ZenThemeDotColor(
    val color: androidx.compose.ui.graphics.Color,
    val isCustom: Boolean,
    val isPrimary: Boolean = false,
)

/**
 * Port of `GradientDrawerView` (Shared/ZenTheme.swift).
 */
internal fun gradientBrushesFor(
    dots: List<ZenThemeDotColor>,
    width: Float,
    height: Float,
): List<Pair<Brush, BlendMode>> {
    if (dots.isEmpty()) return emptyList()
    val orderedDots = if (dots.any { it.isPrimary } && !dots.first().isPrimary) {
        val primaryIndex = dots.indexOfFirst { it.isPrimary }
        listOf(dots[primaryIndex]) + dots.filterIndexed { i, _ -> i != primaryIndex }
    } else {
        dots
    }

    val maxDim = maxOf(width, height)
    return when (orderedDots.size) {
        1 -> listOf(
            Brush.linearGradient(listOf(orderedDots[0].color, orderedDots[0].color)) to BlendMode.SrcOver,
        )
        2 -> {
            val baseColor = orderedDots[0].color
            val accentColor = orderedDots[1].color
            listOf(
                Brush.linearGradient(listOf(baseColor, baseColor)) to BlendMode.SrcOver,
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accentColor.copy(alpha = 0.90f),
                        0.85f to accentColor.copy(alpha = 0.0f),
                    ),
                    center = Offset(width * 1.0f, height * 0.15f),
                    radius = maxDim * 0.95f,
                ) to BlendMode.SrcOver,
            )
        }
        else -> {
            val baseColor = orderedDots[0].color
            val accent1 = orderedDots[1].color
            val accent2 = orderedDots[2].color
            listOf(
                Brush.linearGradient(listOf(baseColor, baseColor)) to BlendMode.SrcOver,
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent1.copy(alpha = 0.90f),
                        0.80f to accent1.copy(alpha = 0.0f),
                    ),
                    center = Offset(width * 1.0f, height * 0.12f),
                    radius = maxDim * 0.85f,
                ) to BlendMode.SrcOver,
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent2.copy(alpha = 0.90f),
                        0.80f to accent2.copy(alpha = 0.0f),
                    ),
                    center = Offset(width * 0.85f, height * 0.88f),
                    radius = maxDim * 0.85f,
                ) to BlendMode.SrcOver,
            )
        }
    }
}

/**
 * Zero-cost tiled noise texture (port of `ZenNoiseImageCache`): 64x64 random
 * grayscale with per-pixel alpha `round(v * 0.25)`.
 */
object ZenNoiseTexture {
    @Volatile
    private var cached: Bitmap? = null

    fun bitmap(): Bitmap {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val size = 64
            val pixels = IntArray(size * size)
            val rng = java.util.Random()
            for (i in pixels.indices) {
                val v = rng.nextInt(256)
                val a = Math.round(v * 0.25).toInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
            }
            val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            cached = bmp
            return bmp
        }
    }
}

/**
 * Tiled overlay pass on top of content — port of `ZenNoiseOverlay`
 * (`Image(resizable:.tile).opacity(o).blendMode(.overlay)`).
 */
fun Modifier.zenNoise(opacity: Float): Modifier = drawWithCache {
    val shader = BitmapShader(ZenNoiseTexture.bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = shader
        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
    }
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    onDrawWithContent {
        drawContent()
        if (opacity > 0f && size.width > 0f) {
            drawContext.canvas.nativeCanvas.apply { drawRect(rect, paint) }
        }
    }
}
