package de.kjell.zencompanion.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * Port of `Shared/ZenTheme.swift` `Palette` — paper/ink/coral, never the
 * Material 3 default look. No dynamic color.
 */

val Coral = Color(0xFF264540)
val CoralDark = Color(0xFFA3CCC6)
val PaperLight = Color(0xFFF3ECE6)
val PaperDark = Color(0xFF1F1F1F)
val InkLight = Color(0xFF2E2E2E)
val InkDark = Color(0xFFF3ECE6)

/** Placeholder gray for missing favicons (iOS `systemGray5`). */
val FaviconPlaceholder = Color(0xFFE4E4E9)
val FaviconPlaceholderDark = Color(0xFF2C2C2E)

class ZenColors(
    val paper: Color,
    val ink: Color,
    val lift: Color,
    val coral: Color,
    val isDark: Boolean,
) {
    /** iOS `.opacity()` variants used across the app. */
    val ink60: Color get() = ink.copy(alpha = 0.6f)
    val ink48: Color get() = ink.copy(alpha = 0.48f)
    val ink45: Color get() = ink.copy(alpha = 0.45f)
    val ink40: Color get() = ink.copy(alpha = 0.4f)
    val ink38: Color get() = ink.copy(alpha = 0.38f)
    val ink35: Color get() = ink.copy(alpha = 0.35f)
    val ink28: Color get() = ink.copy(alpha = 0.28f)
    val ink6: Color get() = ink.copy(alpha = 0.06f)
    val ink85: Color get() = ink.copy(alpha = 0.85f)
    val ink50: Color get() = ink.copy(alpha = 0.5f)
    val ink62: Color get() = ink.copy(alpha = 0.62f)
    val ink55: Color get() = ink.copy(alpha = 0.55f)
    val ink70: Color get() = ink.copy(alpha = 0.7f)
}

fun zenColors(isDark: Boolean): ZenColors = if (isDark) {
    ZenColors(
        paper = PaperDark,
        ink = InkDark,
        lift = Color.White.copy(alpha = 0.07f),
        coral = CoralDark,
        isDark = true,
    )
} else {
    ZenColors(
        paper = PaperLight,
        ink = InkLight,
        lift = Color.Black.copy(alpha = 0.06f),
        coral = Coral,
        isDark = false,
    )
}

val LocalZenColors = compositionLocalOf { zenColors(false) }

@Composable
fun ZenTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val targetZen = zenColors(darkTheme)
    val animSpec = tween<Color>(durationMillis = 280, easing = FastOutSlowInEasing)

    val animatedPaper by animateColorAsState(targetZen.paper, animSpec, label = "paperColor")
    val animatedInk by animateColorAsState(targetZen.ink, animSpec, label = "inkColor")
    val animatedLift by animateColorAsState(targetZen.lift, animSpec, label = "liftColor")
    val animatedCoral by animateColorAsState(targetZen.coral, animSpec, label = "coralColor")

    val zen = ZenColors(
        paper = animatedPaper,
        ink = animatedInk,
        lift = animatedLift,
        coral = animatedCoral,
        isDark = darkTheme,
    )

    val targetColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = CoralDark,
            onPrimary = PaperDark,
            background = PaperDark,
            onBackground = InkDark,
            surface = PaperDark,
            onSurface = InkDark,
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = InkDark.copy(alpha = 0.7f),
            surfaceContainer = Color(0xFF282828),
            surfaceContainerHigh = Color(0xFF323232),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColorScheme(
            primary = Coral,
            onPrimary = Color.White,
            background = PaperLight,
            onBackground = InkLight,
            surface = PaperLight,
            onSurface = InkLight,
            surfaceVariant = Color(0xFFE4DED8),
            onSurfaceVariant = InkLight.copy(alpha = 0.7f),
            surfaceContainer = Color(0xFFECE5DF),
            surfaceContainerHigh = Color(0xFFDDD7D1),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
        )
    }

    val animatedPrimary by animateColorAsState(targetColorScheme.primary, animSpec, label = "primary")
    val animatedOnPrimary by animateColorAsState(targetColorScheme.onPrimary, animSpec, label = "onPrimary")
    val animatedBackground by animateColorAsState(targetColorScheme.background, animSpec, label = "background")
    val animatedOnBackground by animateColorAsState(targetColorScheme.onBackground, animSpec, label = "onBackground")
    val animatedSurface by animateColorAsState(targetColorScheme.surface, animSpec, label = "surface")
    val animatedOnSurface by animateColorAsState(targetColorScheme.onSurface, animSpec, label = "onSurface")
    val animatedSurfaceVariant by animateColorAsState(targetColorScheme.surfaceVariant, animSpec, label = "surfaceVariant")
    val animatedOnSurfaceVariant by animateColorAsState(targetColorScheme.onSurfaceVariant, animSpec, label = "onSurfaceVariant")
    val animatedSurfaceContainer by animateColorAsState(targetColorScheme.surfaceContainer, animSpec, label = "surfaceContainer")
    val animatedSurfaceContainerHigh by animateColorAsState(targetColorScheme.surfaceContainerHigh, animSpec, label = "surfaceContainerHigh")

    val colorScheme = targetColorScheme.copy(
        primary = animatedPrimary,
        onPrimary = animatedOnPrimary,
        background = animatedBackground,
        onBackground = animatedOnBackground,
        surface = animatedSurface,
        onSurface = animatedOnSurface,
        surfaceVariant = animatedSurfaceVariant,
        onSurfaceVariant = animatedOnSurfaceVariant,
        surfaceContainer = animatedSurfaceContainer,
        surfaceContainerHigh = animatedSurfaceContainerHigh,
    )

    CompositionLocalProvider(LocalZenColors provides zen) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
