package de.kjell.zencompanion.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Two eyes side by side, the private-page mark. Material has no such glyph;
 * this matches the eyes iOS uses: open outlines for a normal page, filled for
 * a private one.
 */
object EyesIcons {
    val Open: ImageVector by lazy { eyes(filled = false) }
    val Filled: ImageVector by lazy { eyes(filled = true) }

    private fun eyes(filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = if (filled) "EyesFilled" else "EyesOpen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            for (centerX in listOf(7.5f, 16.5f)) {
                if (filled) {
                    path(fill = SolidColor(Color.Black)) { oval(centerX, 12f, 4f, 7f) }
                } else {
                    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) { oval(centerX, 12f, 3.6f, 6.6f) }
                    path(fill = SolidColor(Color.Black)) { oval(centerX + 0.6f, 14f, 1.5f, 2.2f) }
                }
            }
        }.build()

    private fun PathBuilder.oval(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx - rx, cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + rx, y1 = cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - rx, y1 = cy)
        close()
    }
}
