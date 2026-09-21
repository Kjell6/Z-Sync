package de.kjell.zencompanion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Android system font (Roboto / device default) everywhere — the user-facing
 * choice over the bundled rounded face. Weights still mirror the iOS styles
 * (semibold titles, bold folder names, medium tab rows).
 */
val SystemFont: FontFamily = FontFamily.Default

@Immutable
object ZenType {
    /** App name: 32 semibold, tracking -0.5. */
    val appName = TextStyle(
        fontFamily = SystemFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp,
    )

    /** Space title: 20 bold, tracking -0.3. */
    val spaceTitle = TextStyle(
        fontFamily = SystemFont,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
    )

    /** Tab rows: 17 medium. */
    val tabTitle = TextStyle(
        fontFamily = SystemFont,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
    )

    /** Folder names: 17 bold. */
    val folderName = TextStyle(
        fontFamily = SystemFont,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
    )

    fun rounded(size: Int, weight: FontWeight): TextStyle = TextStyle(
        fontFamily = SystemFont,
        fontWeight = weight,
        fontSize = size.sp,
    )

    /** Device headers: 11 semibold uppercase tracking +0.6. */
    val deviceHeader = TextStyle(
        fontFamily = SystemFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
    )
}
