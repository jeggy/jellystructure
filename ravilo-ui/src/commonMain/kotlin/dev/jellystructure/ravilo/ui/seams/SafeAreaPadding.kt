package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
 */
@Composable
expect fun Modifier.safeAreaPadding(): Modifier
