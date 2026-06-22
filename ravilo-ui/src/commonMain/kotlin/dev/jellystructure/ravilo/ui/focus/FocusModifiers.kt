package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Physical remote / keyboard media-transport keys (R44). Distinct from D-pad/OK so the player can
 * react to them no matter which on-screen control is focused, even with the chrome hidden.
 */
enum class MediaKey { PLAY_PAUSE, PLAY, PAUSE, STOP, FAST_FORWARD, REWIND, NEXT, PREVIOUS }

/**
 * D-pad focus helper.
 *
 * Directional callbacks (`onLeft`/`onRight`/`onUp`/`onDown`) are **opt-in overrides**: when a
 * callback is supplied the matching key is *consumed*, which suppresses Compose's native focus
 * search. For items inside a lazy list/grid leave them `null` — returning `false` lets the
 * framework move focus (it composes off-screen items in the search direction and scrolls them
 * into view, which a per-item `requestFocus()` cannot do reliably). Reserve the callbacks for
 * genuine *content actions* (e.g. carousel paging) or jumps the spatial search can't make.
 *
 * `focusRequester` is optional — only needed for an explicit entry point or a non-spatial bridge,
 * never one-per-item across a lazy list.
 *
 * Pointer support: when `onSelect` is supplied a tap (mouse click / touch) also fires it and pulls
 * focus to the item, so the same surface works on pointer platforms (web/desktop) as on a D-pad.
 * Pointer taps and key events are distinct input sources, so this never double-fires `onSelect`.
 */
fun Modifier.dpadFocusable(
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onBlurred: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onMediaKey: ((MediaKey) -> Unit)? = null,
): Modifier = this
    .onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        // Media-transport keys are global content actions, not focus moves — handle them first and
        // independently of which child is focused (R44).
        if (onMediaKey != null) {
            val mk = when (ev.key) {
                Key.MediaPlayPause, Key.Spacebar -> MediaKey.PLAY_PAUSE
                Key.MediaPlay                    -> MediaKey.PLAY
                Key.MediaPause                   -> MediaKey.PAUSE
                Key.MediaStop                    -> MediaKey.STOP
                Key.MediaFastForward             -> MediaKey.FAST_FORWARD
                Key.MediaRewind                  -> MediaKey.REWIND
                Key.MediaNext                    -> MediaKey.NEXT
                Key.MediaPrevious                -> MediaKey.PREVIOUS
                else                             -> null
            }
            if (mk != null) { onMediaKey(mk); return@onKeyEvent true }
        }
        when (ev.key) {
            Key.DirectionLeft  -> onLeft?.invoke()?.let { true } ?: false
            Key.DirectionRight -> onRight?.invoke()?.let { true } ?: false
            Key.DirectionUp    -> onUp?.invoke()?.let { true } ?: false
            Key.DirectionDown  -> onDown?.invoke()?.let { true } ?: false
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> onSelect?.invoke()?.let { true } ?: false
            Key.Back, Key.Escape                            -> onBack?.invoke()?.let { true } ?: false
            else -> false
        }
    }
    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    .onFocusChanged { if (it.isFocused) onFocused() else onBlurred() }
    .focusable()
    .then(
        if (onSelect != null) Modifier.pointerInput(onSelect, focusRequester) {
            detectTapGestures(onTap = {
                // Pull focus so the focus ring follows the pointer and subsequent
                // D-pad/keyboard navigation continues from the tapped item.
                focusRequester?.let { runCatching { it.requestFocus() } }
                onSelect()
            })
        } else Modifier
    )
