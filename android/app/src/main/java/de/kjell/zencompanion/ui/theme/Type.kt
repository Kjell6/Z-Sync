package de.kjell.zencompanion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
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

/**
 * Title lines for tab, folder and space rows: one by design, two once the
 * system font scale would otherwise truncate nearly every title (iOS parity
 * with accessibility text sizes).
 */
@Composable
@ReadOnlyComposable
fun rowTitleMaxLines(): Int = if (LocalDensity.current.fontScale >= 1.5f) 2 else 1

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

/**
 * The Material type scale, kept from the library defaults (sizes, line
 * heights, platform text metrics) but drawn in the app's system face and with
 * the weights the rest of the app already uses: semibold headings, medium
 * titles and labels, regular body. This is what `MaterialTheme.typography`
 * resolves to, so native components (list items, menus, buttons) share one
 * voice with [ZenType].
 */
val ZenTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displayMedium = displayMedium.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold),
        displaySmall = displaySmall.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineMedium = headlineMedium.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = SystemFont, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = SystemFont, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = SystemFont, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = SystemFont),
        bodyMedium = bodyMedium.copy(fontFamily = SystemFont),
        bodySmall = bodySmall.copy(fontFamily = SystemFont),
        labelLarge = labelLarge.copy(fontFamily = SystemFont, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = SystemFont, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = SystemFont, fontWeight = FontWeight.Medium),
    )
}
