package de.kjell.zencompanion.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import de.kjell.zencompanion.ui.motion.rememberReducedMotion

/**
 * One hairline weight for every lift surface, so a tile and a settings card
 * share the same edge.
 */
private val LiftRimWidth = 0.5.dp

/**
 * Ink on paper, paper on ink. Quieter than the iOS `glassRim` (0.08) because
 * the same alpha reads as a harder line on Android's crisper border draw.
 */
private const val LiftRimAlpha = 0.05f

/** Rim color shared by `liftRim` and `LiftCard`. */
@Composable
internal fun liftRimColor(): Color {
    val zen = LocalZenColors.current
    return (if (zen.isDark) Color.White else Color.Black).copy(alpha = LiftRimAlpha)
}

/** The shared rim as a `BorderStroke`, for `Card`/`Surface` call sites. */
@Composable
internal fun liftBorder(): BorderStroke = BorderStroke(LiftRimWidth, liftRimColor())

/** Flat hairline, just above the lift fill. */
@Composable
internal fun Modifier.liftRim(shape: Shape): Modifier =
    border(width = LiftRimWidth, color = liftRimColor(), shape = shape)

/**
 * The one card look: lift fill plus the hairline rim on the shared 16dp
 * corner. Use for every tappable card so buttons read the same.
 */
@Composable
internal fun Modifier.liftCard(shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val zen = LocalZenColors.current
    return this
        .clip(shape)
        .background(zen.lift, shape)
        .liftRim(shape)
}

/** Card-shaped version of [liftCard] for the sheet surfaces. */
@Composable
internal fun LiftCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val zen = LocalZenColors.current
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = zen.lift),
        border = liftBorder(),
        content = content,
    )
}

/**
 * Shrinks and dims while the finger is down. Pass the same source to the clickable.
 */
@Composable
internal fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.58f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "pressAlpha",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/** How far a tile tightens its corners while pressed. */
private const val PressedCornerScale = 0.5f

/** Scale and dim at rest, in the spirit of the Expressive button press. */
private const val TilePressedScale = 0.94f
private const val TilePressedAlpha = 0.58f

/** A list row only settles back a hair; the native ripple carries the rest. */
private const val RowPressedScale = 0.98f

/**
 * Press for a full-width list row (tab rows, folder rows): the row settles
 * back two percent on the theme's Expressive spatial motion while the native
 * ripple runs. With Reduce Motion on it snaps instead.
 */
@Composable
internal fun Modifier.rowPressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val spec: AnimationSpec<Float> = if (reduced) {
        snap()
    } else {
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) RowPressedScale else 1f,
        animationSpec = spec,
        label = "rowPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Everything a press-morphing lift tile needs: the shape to render this frame
 * and the modifier carrying the scale and dim. Read [shape] for the card and
 * [modifier] for its modifier chain.
 */
@Stable
internal class TilePress internal constructor(
    val shape: Shape,
    val modifier: Modifier,
)

/**
 * Expressive press for the app's own tiles: the corner tightens toward a
 * smaller radius while the finger is down and springs back on release, on the
 * theme's motion scheme (so [de.kjell.zencompanion.ui.theme.ZenTheme]'s
 * Expressive theme drives it). Resting shape, size and fill are untouched;
 * with Reduce Motion on, both states snap instead of springing.
 */
@Composable
internal fun rememberTilePress(
    source: MutableInteractionSource,
    resting: CornerBasedShape = RoundedCornerShape(16.dp),
): TilePress {
    val pressed by source.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val motionScheme = MaterialTheme.motionScheme
    val spatial = motionScheme.defaultSpatialSpec<Float>()
    val effects = motionScheme.defaultEffectsSpec<Float>()
    val spatialSpec: AnimationSpec<Float> = if (reduced) snap() else spatial
    val effectsSpec: AnimationSpec<Float> = if (reduced) snap() else effects

    val scale by animateFloatAsState(
        targetValue = if (pressed) TilePressedScale else 1f,
        animationSpec = spatialSpec,
        label = "tilePressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) TilePressedAlpha else 1f,
        animationSpec = effectsSpec,
        label = "tilePressAlpha",
    )
    val tightness by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spatialSpec,
        label = "tilePressCorner",
    )

    val density = LocalDensity.current
    return TilePress(
        shape = resting.tightened(tightness, density),
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
    )
}

/** Pulls every corner toward half its resting radius. Percent corners cannot
 *  be resolved without a size, so a shape like a capsule is left alone. */
private fun CornerBasedShape.tightened(progress: Float, density: Density): CornerBasedShape {
    if (progress <= 0f) return this
    val topStart = topStart.radius(density)
    val topEnd = topEnd.radius(density)
    val bottomEnd = bottomEnd.radius(density)
    val bottomStart = bottomStart.radius(density)
    if (topStart.value + topEnd.value + bottomEnd.value + bottomStart.value <= 0f) return this
    return RoundedCornerShape(
        topStart = lerp(topStart, topStart * PressedCornerScale, progress),
        topEnd = lerp(topEnd, topEnd * PressedCornerScale, progress),
        bottomEnd = lerp(bottomEnd, bottomEnd * PressedCornerScale, progress),
        bottomStart = lerp(bottomStart, bottomStart * PressedCornerScale, progress),
    )
}

private fun CornerSize.radius(density: Density): Dp {
    val px = toPx(shapeSize = Size.Zero, density = density)
    return with(density) { px.toDp() }
}
