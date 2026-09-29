package dev.jellystructure.ravilo.ui.music

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.desktop.PrefsFile

actual object MusicDeviceStore {
    private val file by lazy { PrefsFile("ravilo_music") }
    actual fun get(key: String): String? = file.get(key)
    actual fun put(key: String, value: String?) = file.put(key, value)
}

/** A desktop has no notification permission to ask for: Now Playing needs none (R329 FR-R329-10). */
@Composable
actual fun rememberNotificationAsk(): () -> Unit = {}

/** R329 — AVPlayer has no skip-silence, so the Mac shows no such setting. */
actual val playerSkipsSilence: Boolean = false
