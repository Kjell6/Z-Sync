package de.kjell.zencompanion.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

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
