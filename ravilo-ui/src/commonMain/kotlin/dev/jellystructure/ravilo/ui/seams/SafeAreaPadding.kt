package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * R261 (dev review item 5) — the safe-area padding every *non-playing* screen needs, shared because
 * FR-R261-5 needs it to be visibility-independent (Android: `systemBarsIgnoringVisibility ∪
 * displayCutout ∪ ime`, never plain `safeDrawing`, which tracks live bar visibility and produces a
 * layout jump once the bars finish animating back in — the measured 69 px). Also the seam R263
 * FR-R263-6 needs for the same values on wasm, where Compose's own `WindowInsets.safeDrawing` has no
 * real source; whichever phase ships first introduces it, and this is that phase.
 *
 * Deliberately not yet wired into the player's own chrome (`PlayerScreen.kt`, `PlayerHandsetChrome.kt`)
 * or `CastRemoteScreen.kt`/`components/Cast.kt`'s existing `WindowInsets.safeDrawing` call sites: those
 * screens are either always-immersive while composed (no visibility jump possible) or already
 * self-pad in a way replacing here would double-pad. This only replaces the *root*-level padding this
 * phase moves off `RaviloApp.kt`'s outer `Box` and into its per-destination content.
 *
 * ## R274 — the two parameters, and why the order they apply in matters
 *
 * @param includeIme whether the on-screen keyboard participates. True for content, which must never
 *   end up under a keyboard. **False for window furniture** — the bottom nav bar and the cast mini
 *   bar docked above it (FR-R274-2/-4): a platform bottom bar is drawn *under* the keyboard, and
 *   unioning the IME instead lifts it to sit on top of one, which also subsumes its own
 *   navigation-bar inset (`union` takes the larger side, and a keyboard is much taller than a
 *   gesture bar) and leaves its labels flush against the keys.
 * @param plusBottom a bottom inset **added** to the safe area before the IME is unioned in — the
 *   height of something drawn over the bottom of the window, i.e. the nav bar. Added, not unioned,
 *   because the bar occupies the band *above* the navigation-bar inset, so content has to clear
 *   both. The IME is then unioned over that sum, so content's bottom inset is
 *   `max(ime, systemBars + plusBottom)`: with a keyboard up the bar is behind it and reserving for
 *   it as well would leave a dead band above the keys (FR-R274-3).
 */
@Composable
expect fun Modifier.safeAreaPadding(
    includeIme: Boolean = true,
    plusBottom: Dp = 0.dp,
): Modifier
