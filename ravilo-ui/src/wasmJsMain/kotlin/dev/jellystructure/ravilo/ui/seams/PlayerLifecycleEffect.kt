@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * R376 (FR-R376-4) — the background, as a browser has one. When the page goes hidden (another tab, the phone's home
 * screen, a locked screen) the player pauses and takes the same stop/progress path Android's `ON_STOP` takes
 * ([onBackground] — the resume record, the stop report with the position). It goes as NOT playing, so the return
 * ([onForeground] — a fresh start at the record's position) does not auto-resume. Not while the picture is in
 * picture-in-picture or on AirPlay: the viewer is still watching then.
 */
@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: () -> Unit,
) {
    val background by rememberUpdatedState(onBackground)
    val foreground by rememberUpdatedState(onForeground)
    DisposableEffect(player) {
        var away = false
        val handle = jsWatchVisibility(player.video) { hidden ->
            if (hidden && !away) {
                away = true
                player.pause()
                background(false)
            } else if (!hidden && away) {
                away = false
                foreground()
            }
        }
        onDispose { jsUnwatchVisibility(handle) }
    }
}

private fun jsWatchVisibility(video: org.w3c.dom.HTMLVideoElement, onChange: (Boolean) -> Unit): JsAny = js(
    """(function(){
        var f = function () {
            var hidden = document.visibilityState === 'hidden';
            if (hidden && (document.pictureInPictureElement === video || video.webkitCurrentPlaybackTargetIsWireless)) return;
            onChange(hidden);
        };
        document.addEventListener('visibilitychange', f);
        return f;
    })()"""
)

private fun jsUnwatchVisibility(handle: JsAny): Unit = js("document.removeEventListener('visibilitychange', handle)")
