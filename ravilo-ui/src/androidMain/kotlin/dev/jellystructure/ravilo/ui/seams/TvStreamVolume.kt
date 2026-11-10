package dev.jellystructure.ravilo.ui.seams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.StreamVolume
import dev.jellystructure.shared.tv.VolumeReport
import kotlin.math.roundToInt

/**
 * DEB-2185 (FR-12.2; R357 FR-R357-5 as amended 2026-10-23) — the Android TV app's `STREAM_MUSIC` as a session's volume.
 *
 * - [report] is the stream's index as a percent of its maximum, and its mute; **null while [AudioManager.isVolumeFixed]**
 *   (the TV hands its volume to a soundbar or a receiver over HDMI-CEC), so the app sends no level and every remote
 *   shows *—* (FR-10). Never a guessed level.
 * - [set] sets the stream to the nearest index. A TV with 15 steps cannot hold 37 %: it lands on 40 % and reports that,
 *   which is exactly what the remote's 3 s rule then shows as *{place} kept 40 %*.
 * - [listen] registers for the platform's volume-changed broadcast and tells [RemoteControl] on every change of this
 *   stream, so the TV's own remote reaches every other app at once through the report-at-once path (FR-R357-4), not on
 *   the next heartbeat. The phone never installs this (R357 FR-R357-5 stands there): see [installTvStreamVolume].
 */
class TvStreamVolume(private val context: Context) : StreamVolume {
    private val audio: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val max: Int get() = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0).coerceAtLeast(1)

    /** The stream's level as a percent, or null when the platform reports it fixed or has no audio service. */
    fun percent(): Int? {
        val a = audio ?: return null
        if (runCatching { a.isVolumeFixed }.getOrDefault(false)) return null
        return (a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt().coerceIn(0, 100)
    }

    override fun report(): VolumeReport? {
        val p = percent() ?: return null
        val muted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && runCatching { audio?.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull() == true
        return VolumeReport(p, muted)
    }

    override fun set(percent: Int) {
        val a = audio ?: return
        val index = (percent.coerceIn(0, 100) * max / 100f).roundToInt().coerceIn(0, max)
        runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    override fun step(deltaPercent: Int) { percent()?.let { set(it + deltaPercent) } }

    override fun mute(muted: Boolean?) {
        val a = audio ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direction = when (muted) { true -> AudioManager.ADJUST_MUTE; false -> AudioManager.ADJUST_UNMUTE; null -> AudioManager.ADJUST_TOGGLE_MUTE }
        runCatching { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
    }

    /** Starts listening for a change of the stream; returns the stop. */
    fun listen(onChange: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != VOLUME_CHANGED_ACTION) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC) onChange()
            }
        }
        val ok = runCatching { context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION)) }.isSuccess
        return { if (ok) runCatching { context.unregisterReceiver(receiver) } }
    }

    companion object {
        /** The platform's broadcast on a stream volume change (`AudioManager.VOLUME_CHANGED_ACTION`, not in the public API). */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}

/**
 * DEB-2185 (FR-12.2) — on a television, the session's volume is the media stream from here on: installed once from the
 * app's context init, never on a phone. Returns false when nothing was installed.
 */
fun installTvStreamVolume(context: Context, television: Boolean): Boolean {
    if (!television || RemoteControl.streamVolume != null) return false
    val stream = TvStreamVolume(context.applicationContext)
    RemoteControl.streamVolume = stream
    stream.listen { RemoteControl.streamVolumeChanged() }
    return true
}
