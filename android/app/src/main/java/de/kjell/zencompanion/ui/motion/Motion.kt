package de.kjell.zencompanion.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlin.math.PI
import kotlin.math.pow

/**
 * SwiftUI `.spring(response: r, dampingFraction: d)` ≈ Compose
 * `spring(dampingRatio = d, stiffness = (2π / r)²)` — the mapping used for
 * every motion in this app. No `tween(300)` defaults, no Material tokens.
 */
fun springStiffness(responseSeconds: Double): Float =
    ((2.0 * PI / responseSeconds)).pow(2).toFloat()

// Motion catalog (see spec table):
val DampingTilePress = 0.75f      // response 0.25
val StiffnessTilePress = springStiffness(0.25)

val DampingSwitchSelect = 0.85f   // response 0.30
val StiffnessSwitchSelect = springStiffness(0.30)

val DampingFolderBody = 0.8f      // response 0.35
val StiffnessFolderBody = springStiffness(0.35)

val DampingFolderIcon = 0.8f      // response 0.30
val StiffnessFolderIcon = springStiffness(0.30)

val DampingSharePicker = 0.8f     // response 0.28
val StiffnessSharePicker = springStiffness(0.28)

val DampingShareResult = 0.8f     // response 0.30
val StiffnessShareResult = springStiffness(0.30)

val DampingSwitcherExtra = 0.8f   // response 0.25 (switcher opacity)
val StiffnessSwitcherExtra = springStiffness(0.25)

@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            Settings.Global.getFloat(
                resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** Spring that snaps instantly under Reduce Motion but keeps layout identical. */
@Composable
fun <T> zenSpring(dampingRatio: Float, stiffness: Float): AnimationSpec<T> {
    val reduced = rememberReducedMotion()
    return if (reduced) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

/**
 * Essential-tile press: scale 0.95 / opacity 0.85 with spring 0.25/0.75 —
 * a custom press effect layered under the native ripple.
 */
@Composable
fun Modifier.tilePressEffect(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (reduced) snap() else spring(0.75f, StiffnessTilePress),
        label = "tileScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = if (reduced) snap() else spring(0.75f, StiffnessTilePress),
        label = "tileAlpha",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
