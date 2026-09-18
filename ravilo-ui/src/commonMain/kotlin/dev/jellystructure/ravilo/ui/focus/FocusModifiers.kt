package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import dev.jellystructure.ravilo.ui.PlatformBackHandler
import dev.jellystructure.ravilo.ui.isTvPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Physical remote / keyboard media-transport keys (R44). Distinct from D-pad/OK so the player can
 * react to them no matter which on-screen control is focused, even with the chrome hidden.
 */
enum class MediaKey { PLAY_PAUSE, PLAY, PAUSE, STOP, FAST_FORWARD, REWIND, NEXT, PREVIOUS }

/**
 * Bug fix: cross-screen focus bridges (hero↔nav-bar↔first-row jumps in HomeScreen) sat behind a bare
 * `runCatching { fr.requestFocus() }` — if the target was mid-recomposition at the exact moment the
 * key press fired (a lazy-list item just got replaced/rekeyed, or scrolled back into range),
 * `requestFocus()` throws because the requester isn't attached to any focus target yet, and the
 * swallowed exception left the user stuck with the key press silently doing nothing.
 *
 * R200: a single next-frame retry isn't always enough — reported live as a *permanent* stuck-on-hero/
 * nav-bar state (confirmed via adb: the same bridge failed on every subsequent Down press across
 * seconds, only an app restart recovered it), traced to a target composable that took more than one
 * frame to (re)attach after a live feed refresh reordered rows. Retries once per frame for up to
 * [maxFrames] frames, covering any recomposition slower than a single frame without adding visible
 * delay on the overwhelmingly common immediate-success path (the loop exits the instant a retry
 * succeeds).
 */
fun requestFocusRetrying(scope: CoroutineScope, focusRequester: FocusRequester, maxFrames: Int = 30) {
    if (runCatching { focusRequester.requestFocus() }.isSuccess) return
    scope.launch {
        repeat(maxFrames) {
            withFrameNanos {}
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@launch
        }
    }
}

/**
 * R236 (FR-R236-2) — the same retry loop as [requestFocusRetrying], but a cross-screen bridge press
 * (Down from the hero/app-bar) must never end as a completely dead key: when every retry still fails
 * (the bridge target genuinely isn't reachable, not just slow to attach), fall through to Compose's own
 * native focus search in [direction] instead of giving up silently. `dpadFocusable`'s `onDown`/`onUp`
 * callbacks always consume the key once supplied (see its own doc), so "let native search happen"
 * has to be done explicitly from inside the callback, as this function does, rather than by declining
 * to handle the key.
 */
fun requestFocusRetryingOrMoveNative(
    scope: CoroutineScope,
    focusRequester: FocusRequester,
    focusManager: FocusManager,
    direction: FocusDirection,
    maxFrames: Int = 30,
) {
    if (runCatching { focusRequester.requestFocus() }.isSuccess) return
    scope.launch {
        repeat(maxFrames) {
            withFrameNanos {}
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@launch
        }
        focusManager.moveFocus(direction)
    }
}

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
 *
 * R157: `onTap` lets a tap diverge from Enter-key (defaults to `onSelect`, so existing call sites
 * are unaffected); `moveFocusOnHover` (opt-in) shifts focus to this item on pointer hover, so the
 * visual focus state follows the mouse and a click/Enter agree on the target.
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
    // R157 — a tap/click can mean something different from Enter-key (e.g. the player root: Enter
    // activates the focused control, but a web click on empty space toggles chrome instead). Defaults
    // to `onSelect` everywhere this divergence doesn't apply — existing call sites are unaffected.
    onTap: (() -> Unit)? = onSelect,
    // R157 — mouse hover moves focus here, so the visual focus ring follows the cursor and a
    // subsequent click/Enter agree on the target. Opt-in (default off): most `dpadFocusable` call
    // sites are lazy-list items where untested hover-focus churn isn't worth the risk; the player's
    // transport controls turn it on explicitly.
    moveFocusOnHover: Boolean = false,
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
        if (onTap != null) Modifier.pointerInput(onTap, focusRequester) {
            detectTapGestures(onTap = {
                // Pull focus so the focus ring follows the pointer and subsequent
                // D-pad/keyboard navigation continues from the tapped item.
                focusRequester?.let { runCatching { it.requestFocus() } }
                onTap()
            })
        } else Modifier
    )
    .then(
        // hoverable()/HoverInteraction is cross-platform commonMain (unlike the lower-level
        // onPointerEvent, which is skiko-only — desktop/web — and unavailable on Android); composed{}
        // gives this non-@Composable modifier factory the composable scope remember/LaunchedEffect need.
        if (moveFocusOnHover && focusRequester != null) Modifier.composed {
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    if (interaction is HoverInteraction.Enter) runCatching { focusRequester.requestFocus() }
                }
            }
            Modifier.hoverable(interactionSource)
        } else Modifier
    )

/**
 * R260 (FR-R260-1) — the player's Back decision (picker → rail → next-up → scrub → chrome → leave, or
 * Live TV's guide → number entry → leave) needs a **second** entrance: Android's system back
 * gesture/button never arrives as a `Key.Back` [KeyEvent] under gesture navigation (it's intercepted by
 * `OnBackPressedDispatcher` before Compose sees it), which left the phone's Back finishing the whole
 * Activity instead of running [onBack] at all. `dpadFocusable`'s `onBack` above stays the TV
 * remote/keyboard entrance; this is the platform-dispatcher entrance for the same decision.
 *
 * `isTvPlatform` keeps the TV byte-for-byte on the key-only path — the dispatcher entrance never turns
 * on there, so the bedroom-TV race that made the root step aside for the player (see `RaviloApp.kt`'s
 * `ownsItsOwnBack`) cannot return. Off the TV, a device where one physical Back reaches both entrances
 * (a Bluetooth keyboard's Escape, a gamepad's B) would otherwise run [onBack] twice for one press —
 * chrome visible → hidden, then hidden → leave, in one motion. `suppressDispatcher` is set on the key
 * entrance's `KeyDown` and cleared on its `KeyUp`, so the dispatcher entrance is a no-op for the exact
 * press the key entrance already handled; a gesture or nav-bar tap never sets the flag and reaches the
 * dispatcher normally.
 *
 * Kept as its own modifier rather than inlined into the player screens' bodies: `PlayerScreen` has
 * already tripped ART's release-build register-count verifier once (R258), and phase 231 gates every
 * release APK on that verifier passing.
 */
fun Modifier.playerBackGesture(onBack: () -> Unit): Modifier =
    if (isTvPlatform) this
    else this.then(
        Modifier.composed {
            var suppressDispatcher by remember { mutableStateOf(false) }
            PlatformBackHandler(enabled = true) { if (!suppressDispatcher) onBack() }
            Modifier.onKeyEvent { ev ->
                if (ev.key == Key.Back || ev.key == Key.Escape) {
                    suppressDispatcher = ev.type == KeyEventType.KeyDown
                }
                false
            }
        }
    )
