package de.kjell.zencompanion.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.theme.LocalZenColors

/**
 * Port of `ZenSpaceGradientBackground` (Shared/ZenTheme.swift): base paper,
 * crossfade between themes on space switch, plus the tiled noise overlay.
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

    var currentTheme by remember { mutableStateOf(theme) }
    var previousTheme by remember { mutableStateOf<ZenSpaces.ZenSpaceTheme?>(null) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(theme) {
        if (currentTheme != theme) {
            previousTheme = currentTheme
            currentTheme = theme
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            previousTheme = null
        }
    }

    val prev = previousTheme
    val prevDotColors = remember(prev, darkenDots) { prev?.toDotColors(darkenDots).orEmpty() }
    val prevGradientAlpha = remember(prev) { calcOpacity(prev) }
    val curr = currentTheme
    val currDotColors = remember(curr, darkenDots) { curr?.toDotColors(darkenDots).orEmpty() }
    val currGradientAlpha = remember(curr) { calcOpacity(curr) }

    Box(modifier.fillMaxSize()) {
        // 1. Base paper canvas (for spaces without a theme or when fading to paper)
        Box(Modifier.fillMaxSize().background(colors.paper))

        // 2. Outgoing snapshot (paper + its theme), fading out...
        if (prev != null && prevDotColors.isNotEmpty()) {
            val snapshotAlpha = 1f - progress.value
            if (snapshotAlpha > 0f) {
                ThemeBackgroundSnapshot(
                    paper = colors.paper,
                    dotColors = prevDotColors,
                    gradientAlpha = prevGradientAlpha,
                    modifier = Modifier.fillMaxSize().alpha(snapshotAlpha),
                )
            }
        }

        // 3. ...over the incoming snapshot, fading in. Snapshots are flattened
        // (paper + theme) like iOS, never translucent gradients over one shared
        // paper — that filtered the light twice and dipped dark mid-transition.
        if (curr != null && currDotColors.isNotEmpty()) {
            val snapshotAlpha = progress.value
            if (snapshotAlpha > 0f) {
                ThemeBackgroundSnapshot(
                    paper = colors.paper,
                    dotColors = currDotColors,
                    gradientAlpha = currGradientAlpha,
                    modifier = Modifier.fillMaxSize().alpha(snapshotAlpha),
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

/** One finished background: paper with its theme already mixed in (iOS `backgroundSnapshot`). */
@Composable
private fun ThemeBackgroundSnapshot(
    paper: Color,
    dotColors: List<ZenThemeDotColor>,
    gradientAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(paper))
        if (gradientAlpha > 0f) {
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
