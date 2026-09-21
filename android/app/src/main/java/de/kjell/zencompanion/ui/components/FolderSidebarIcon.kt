package de.kjell.zencompanion.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.ui.motion.DampingFolderIcon
import de.kjell.zencompanion.ui.motion.StiffnessFolderIcon
import de.kjell.zencompanion.ui.theme.LocalZenColors
import kotlin.math.PI
import kotlin.math.tan

/**
 * Port of `ZenFolderSidebarIcon` (Shared/ZenTheme.swift): two 28x28 shapes,
 * scaleEffect 1.28, folding open with scale/skew/translate driven by progress.
 *
 * [userIcon] is Zen's folder user icon (`chrome://…/selectable/….svg`, emoji
 * or data URL). Zen draws it as an 11x11 image inside the folder flap and
 * transforms it together with the flap — never instead of the folder shape.
 */
@Composable
fun ZenFolderSidebarIcon(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    userIcon: String? = null,
) {
    val colors = LocalZenColors.current
    val strokeColor = colors.ink85
    val backFill = if (colors.isDark) Color(0.16f, 0.16f, 0.18f) else Color(228f / 255f, 222f / 255f, 216f / 255f)
    val frontFill = if (colors.isDark) Color(0.22f, 0.22f, 0.25f) else Color(243f / 255f, 236f / 255f, 230f / 255f)

    // Animate progress with spring 0.3/0.8 (Swift .spring(response:0.3,dampingFraction:0.8)).
    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(dampingRatio = DampingFolderIcon, stiffness = StiffnessFolderIcon),
        label = "folderFold",
    )

    Box(modifier.size(FolderIconBox)) {
        // Swift paths live in a 28x28 *point* space; Compose Canvas works in
        // pixels, so rescale by density or the icon renders tiny in the corner
        // on high-density devices.
        val density = LocalDensity.current
        val densityScale = density.density
        val pxStroke = with(density) { StrokeWidth.dp.toPx() }
        Canvas(
            Modifier
                .size(FolderIconBox)
                .scale(1.28f),
        ) {
            // Fresh paths per frame; transform() mutates in place.
            val back = backPath()
            val front = frontPath()
            back.transform(Matrix(folderMatrixValues(progress, isFront = false)))
            front.transform(Matrix(folderMatrixValues(progress, isFront = true)))

            scale(densityScale, densityScale, pivot = Offset.Zero) {
                // 1. Back folder.
                drawPath(back, backFill)
                drawPath(back, strokeColor, style = Stroke(width = pxStroke / densityScale))
                // 2. Front folder flap.
                drawPath(front, frontFill)
                drawPath(front, strokeColor, style = Stroke(width = pxStroke / densityScale))
            }
        }

        if (!userIcon.isNullOrEmpty()) {
            // Same transform matrix as the front flap, but in pixel space:
            // scale/skew are unitless, translations scale with density.
            Box(
                modifier = Modifier
                    .size(FolderIconBox)
                    .scale(1.28f)
                    .drawWithContent {
                        val matrix = folderMatrixValues(progress, isFront = true)
                        matrix[12] *= densityScale
                        matrix[13] *= densityScale
                        withTransform({ transform(Matrix(matrix)) }) {
                            this@drawWithContent.drawContent()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                ZenIconView(
                    icon = userIcon,
                    size = FolderUserIconSize,
                    foreground = strokeColor,
                    // Zen centers the image on the front rect (y 9.625…22.375
                    // -> center 16), two points below the 28pt frame center.
                    modifier = Modifier.offset(y = 2.dp),
                )
            }
        }
    }
}

val FolderIconBox: Dp = 28.dp
private val FolderUserIconSize: Dp = 11.dp
private const val StrokeWidth = 1.4f

/**
 * Row-vector chain `scale . translate . skew` from the Swift GeometryEffect:
 *   x' = sx*px + k*sy*py + (tx + k*ty);  y' = sy*py + ty
 * encoded directly as a 4x4 column-major matrix (compose/android layout).
 */
internal fun folderMatrixValues(progress: Float, isFront: Boolean): FloatArray {
    val s = 1f - 0.15f * progress
    val k = tan(((if (isFront) -16.0 else 16.0) * progress) * PI / 180.0).toFloat()
    val tx = ((if (isFront) 8.0 else -5.2) * progress).toFloat()
    val ty = (2.0 * progress).toFloat()

    return floatArrayOf(
        s,          0f,   0f, 0f, // column 0: x-axis
        k * s,      s,    0f, 0f, // column 1: y-axis sheared
        0f,         0f,   1f, 0f, // column 2: z
        tx + k * ty, ty,  0f, 1f, // column 3: translation
    )
}

/** Back path control points copied verbatim from `BackFolderShape`. */
internal fun backPath(): Path = Path().apply {
    moveTo(8f, 5.625f)
    lineTo(11.9473f, 5.625f)
    cubicTo(12.4866f, 5.625f, 13.0105f, 5.80861f, 13.4316f, 6.14551f)
    lineTo(14.2881f, 6.83105f)
    cubicTo(14.9308f, 7.34508f, 15.7298f, 7.625f, 16.5527f, 7.625f)
    lineTo(20f, 7.625f)
    cubicTo(21.3117f, 7.625f, 22.375f, 8.68832f, 22.375f, 10f)
    lineTo(22.375f, 20f)
    cubicTo(22.375f, 21.3117f, 21.3117f, 22.375f, 20f, 22.375f)
    lineTo(8f, 22.375f)
    cubicTo(6.68832f, 22.375f, 5.625f, 21.3117f, 5.625f, 20f)
    lineTo(5.625f, 8f)
    cubicTo(5.625f, 6.68832f, 6.68832f, 5.625f, 8f, 5.625f)
    close()
}

internal fun frontPath(): Path = Path().apply {
    // Front rect: x 5.625 y 9.625 w 16.75 h 12.75 corner 2.375.
    addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            left = 5.625f,
            top = 9.625f,
            right = 5.625f + 16.75f,
            bottom = 9.625f + 12.75f,
            cornerRadius = CornerRadius(2.375f),
        ),
    )
}
