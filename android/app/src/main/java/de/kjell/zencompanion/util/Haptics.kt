package de.kjell.zencompanion.util

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic helpers mapping the iOS feedback generators:
 *  - UISelectionFeedbackGenerator.selectionChanged → CLOCK_TICK
 *  - UINotificationFeedbackGenerator(.success)     → CONFIRM
 *  - UINotificationFeedbackGenerator(.error)       → REJECT
 */
object Haptics {
    enum class Kind { SELECTION, CONFIRM, REJECT }

    fun perform(context: Context, kind: Kind) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        runCatching {
            when (kind) {
                Kind.SELECTION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(8, 64))
                    }
                }
                Kind.CONFIRM -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(18, 160))
                    }
                }
                Kind.REJECT -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(34, 220))
                    }
                }
            }
        }
    }

    /** Selection ticks respect the system "touch feedback" volume on some devices. */
    fun isSilentMode(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }
}
