package dev.jellystructure.ravilo.ui.seams

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberHandsetPlayerControls(): HandsetPlayerControls {
    val context = LocalContext.current
    return remember(context) {
        val activity = context as? Activity
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val window = activity?.window
        // FR-R244-5 — the window's own override, or the system level while no override is set. Setting
        // it writes ONLY the window attribute: the device default is never touched.
        val brightness: Float? = window?.let { w ->
            val override = w.attributes.screenBrightness
            if (override >= 0f) override
            else runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        }
        val setBrightness: ((Float) -> Unit)? = window?.let { w ->
            { level: Float ->
                val lp = w.attributes
                lp.screenBrightness = level.coerceIn(0.02f, 1f)
                w.attributes = lp
            }
        }
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.takeIf { it > 0 }
        val volume: Float? = if (audio != null && max != null) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else null
        val setVolume: ((Float) -> Unit)? = if (audio != null && max != null) {
            { level: Float -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).toInt(), 0) }
        } else null
        // FR-R244-7 — ACCELEROMETER_ROTATION == 0 is "the system has rotation locked"; with
        // SCREEN_ORIENTATION_FULL_USER (see PlayerImmersiveEffect) that means portrait for a phone.
        val locked = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0 }.getOrDefault(false)
        HandsetPlayerControls(
            brightness = brightness,
            setBrightness = setBrightness,
            volume = volume,
            setVolume = setVolume,
            systemRotationLocked = locked,
            setLandscape = { landscape ->
                activity?.requestedOrientation =
                    if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            },
        )
    }
}
