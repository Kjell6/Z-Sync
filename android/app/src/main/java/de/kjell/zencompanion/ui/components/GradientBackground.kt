package de.kjell.zencompanion.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.theme.LocalZenColors

/**
 * Port of `ZenSpaceGradientBackground` (Shared/ZenTheme.swift): crossfade
 * between themes on space switch, plus the tiled noise overlay.
 *
 * The theme layer sits at [calcOpacity] over its paper — matching iOS — so
 * synced dot colours never render at full strength. Dark Mode additionally
 * darkens the dots (`darkenDots`, port of iOS' `colorForDot` dark branch),
 * which keeps bright desktop palettes from glaring on the dark paper.
 */
@Composable
fun ZenSpaceGradientBackground(
    theme: ZenSpaces.ZenSpaceTheme?,
    modifier: Modifier = Modifier,
    darkenDots: Boolean = false,
) {
    val colors = LocalZenColors.current
    val activeTexture = theme?.texture ?: 0.0

    // Every backdrop keeps the darkening it was shown with, so the outgoing
    // one does not jump when the next space flips between light and dark.
    val target = ThemeBackdrop(theme, darkenDots)
    val layers = remember { mutableStateListOf(BackdropLayer(target, Animatable(1f))) }

    LaunchedEffect(target) {
        if (layers.last().backdrop != target) {
            // The incoming backdrop fades in over the opaque outgoing one. A
            // switch that interrupts a fade leaves the layers below as they
            // are and fades in on top, so nothing jumps.
            val layer = BackdropLayer(target, Animatable(0f))
            layers.add(layer)
            layer.alpha.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            layers.removeRange(0, layers.indexOf(layer))
        }
    }

    Box(modifier.fillMaxSize()) {
        // Flattened backdrops (paper + theme) stacked bottom to top. The
        // lowest one is always opaque: fading both sides of a crossfade lets
        // the bare paper shine through mid-transition, which reads as a flash.
        for (layer in layers) {
            key(layer) {
                ThemeBackgroundSnapshot(
                    paper = colors.paper,
                    background = colors.background,
                    backdrop = layer.backdrop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = layer.alpha.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
                )
            }
        }

        // 4. Subtle paper noise texture (present on all spaces)
        Box(
            Modifier
                .fillMaxSize()
                .noise((0.022 + activeTexture.coerceIn(0.0, 1.0) * 0.04).toFloat()),
        )
    }
}

/** A space theme together with the darkening it is drawn with. */
private data class ThemeBackdrop(val theme: ZenSpaces.ZenSpaceTheme?, val darken: Boolean)

private class BackdropLayer(val backdrop: ThemeBackdrop, val alpha: Animatable<Float, AnimationVector1D>)

/**
 * One finished background: paper with its theme already mixed in (iOS
 * `backgroundSnapshot`), or the plain background for a space without a theme.
 */
@Composable
private fun ThemeBackgroundSnapshot(
    paper: Color,
    background: Color,
    backdrop: ThemeBackdrop,
    modifier: Modifier = Modifier,
) {
    val dotColors = remember(backdrop) { backdrop.theme?.toDotColors(backdrop.darken).orEmpty() }
    val gradientAlpha = remember(backdrop) { calcOpacity(backdrop.theme) }
    val themed = dotColors.isNotEmpty() && gradientAlpha > 0f
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(if (themed) paper else background))
        if (themed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(gradientAlpha)
                    .drawBehind {
                        for ((brush, blend) in gradientBrushesFor(dotColors, size.width, size.height)) {
                            drawRect(brush = brush, blendMode = blend)
                        }
                    },
            )
        }
    }
}

private fun Modifier.noise(opacity: Float): Modifier =
    zenNoise(opacity)

private fun ZenSpaces.ZenSpaceTheme?.toDotColors(darken: Boolean): List<ZenThemeDotColor> =
    this?.dots.orEmpty().map {
        ZenThemeDotColor(
            color = zenDotColor(it.color, darken),
            isCustom = it.isCustom,
            isPrimary = it.isPrimary,
        )
    }

/**
 * Port of `GradientDrawerView.colorForDot`'s dark branch: same hue, a hair
 * more saturated and darker so Dark Mode reads differently from Light Mode.
 * Android stops at 0.70 brightness (iOS 0.60) to stay a touch more vivid.
 */
internal fun zenDotColor(color: ZenSpaces.ZenColorValue, darken: Boolean): Color {
    val base = Color(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())
    if (!darken) return base
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.06f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 0.70f).coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/**
 * Port of `calcOpacity`. iOS clamps to 0.60..0.85; Android keeps a slightly
 * stronger 0.66..0.90 range so the palette reads a touch more saturated than
 * iOS without going back to full-strength greens.
 */
internal fun calcOpacity(theme: ZenSpaces.ZenSpaceTheme?): Float {
    if (theme == null || theme.dots.isEmpty()) return 0f
    val raw = theme.opacity ?: 0.85
    return ((raw * 0.86).coerceIn(0.66, 0.90)).toFloat()
}
