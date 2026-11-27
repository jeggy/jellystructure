package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.jellystructure.shared.tv.StillWatching
import kotlin.time.TimeSource

/**
 * DEB-5843 (FR-2) — the app-run state behind "Are you still watching?": [dev.jellystructure.shared.tv.StillWatching]'s
 * count and clock, on a monotonic clock, held by `RaviloApp` so a whole binge shares it across `replaceTop`. Plain fields,
 * not Compose state: nothing reads it while composing, an input is one write of an `Int` and a `Long`, and it is not saved
 * or sent anywhere.
 */
class StillWatchingTracker(tvClass: Boolean) {
    private val rule = StillWatching(tvClass)
    private val origin = TimeSource.Monotonic.markNow()
    private var advancingTo: String? = null

    private fun now(): Long = origin.elapsedNow().inWholeMilliseconds

    /** A viewer's input (a key, a tap or click, a remote command, a hand-started play): count 0, clock now. */
    fun onInput() = rule.onInput(now())

    /** The countdown ran out: true = ask (do not advance), false = the next episode starts by itself, counted. */
    fun decide(): Boolean = rule.decide(now())

    /** [advanceNext] is about to open [nextItemId]; its start is the app's, not a hand-start (FR-1). */
    fun willAdvanceTo(nextItemId: String) { advancingTo = nextItemId }

    /** A play of [itemId] starts. Anything the app did not advance to is a hand-start — Play, a rail pick, a cast or a
     *  hand-over from another device — and so is input. */
    fun onPlayStarted(itemId: String) {
        if (advancingTo == itemId) advancingTo = null else onInput()
    }

    /** For tests: the count since the last input. */
    internal val unattended: Int get() = rule.unattended
}

/** The app's tracker; the default (no `RaviloApp` around, a preview or a test) counts for a non-TV player. */
val LocalStillWatching = staticCompositionLocalOf { StillWatchingTracker(tvClass = false) }

/**
 * FR-2 — every key and every press or wheel turn on the player's root is input. Both hooks only observe: the key hook
 * returns `false` and the pointer hook watches the initial pass without consuming, so TV focus motion and the player's own
 * key handling are unchanged. A pointer *move* is deliberately not input (a bumped desk is not a viewer).
 */
fun Modifier.observeStillWatchingInput(tracker: StillWatchingTracker): Modifier =
    this
        .onPreviewKeyEvent { tracker.onInput(); false }
        .pointerInput(tracker) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press || e.type == PointerEventType.Scroll) tracker.onInput()
                }
            }
        }

/** The player root's input hook: [observeStillWatchingInput] on the app's tracker. Composable so `PlayerScreen`'s own body
 *  holds no new local (its register budget, DEB-2392). */
@androidx.compose.runtime.Composable
fun Modifier.stillWatchingInput(): Modifier = observeStillWatchingInput(LocalStillWatching.current)
