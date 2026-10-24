package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.ravilo.ui.theme.RaviloMotion

/**
 * DEB-2349 (DEB-2204, FR-5) — a screen's content region fades in once when it replaces its loading view.
 *
 * - **A fade-in, not a crossfade.** The loading view and the content are the screen's own `when` branches, so they are
 *   never composed together. While [loading] the region is drawn at alpha 1 (the loading view as it is today); the
 *   first frame of whatever replaces it is drawn at alpha 0, then it tweens to 1 over
 *   [RaviloMotion.IMAGE_CROSSFADE_MS] on [RaviloMotion.EASE]. This covers content and an error state alike.
 * - **Draw-only.** The alpha is read inside a `graphicsLayer` block, never in composition, so layout, focus, focus
 *   requesters and restorers behave exactly as they do without it.
 * - **No fade on a return.** A region that first composes already loaded (R212's snapshot, `DetailStore`'s cache)
 *   starts at alpha 1 and stays there.
 * - **Reduced motion (FR-4).** Under [LocalReducedMotion] the alpha is 1 in the first frame.
 *
 * Make the state where the screen composes, outside the branch of its `when`, so a branch swap does not reset it.
 * A page that shows one of several lists keys it by the list ([rememberFadeInOnLoad]'s `key`), so a list it switches to
 * that is already loaded does not fade.
 */
@Stable
class FadeInOnLoadState internal constructor(startsLoading: Boolean) {
    /** 0 from the loading view until the replacement is first drawn, then 1. A region born loaded is at 1 already. */
    internal val progress = Animatable(if (startsLoading) 0f else 1f)
    internal var loading by mutableStateOf(startsLoading)
    internal var reduced by mutableStateOf(false)

    /** The region's alpha. Read it in the draw phase only (a `graphicsLayer` block). */
    val alpha: Float get() = if (loading || reduced) 1f else progress.value
}

/**
 * FR-5's state for one content region. [loading] is true while the screen shows its loading view (or nothing yet) and
 * false once it shows content or an error. A later [loading] again (a retry, a reload) arms the fade for the next
 * replacement. A changed [key] starts a fresh state, born as [loading] says it is then.
 */
@Composable
fun rememberFadeInOnLoad(loading: Boolean, key: Any? = null): FadeInOnLoadState {
    val reduced = LocalReducedMotion.current
    val fade = remember(key) { FadeInOnLoadState(startsLoading = loading) }
    // Written in composition and read in the draw phase, so the frame that first shows the replacement draws at 0.
    fade.loading = loading
    fade.reduced = reduced
    LaunchedEffect(fade, loading, reduced) {
        when {
            loading -> fade.progress.snapTo(0f)
            reduced -> fade.progress.snapTo(1f)
            fade.progress.value < 1f -> fade.progress.animateTo(1f, tween(RaviloMotion.IMAGE_CROSSFADE_MS, easing = RaviloMotion.EASE))
        }
    }
    return fade
}

/** Draws this region at [state]'s alpha (no change for a null [state]). Only the alpha moves, and only in the draw phase. */
fun Modifier.fadeInOnLoad(state: FadeInOnLoadState?): Modifier = if (state == null) this else graphicsLayer { alpha = state.alpha }

/** [rememberFadeInOnLoad] and [fadeInOnLoad] in one call, for a region that is a single container. */
@Composable
fun Modifier.fadeInOnLoad(loading: Boolean): Modifier = fadeInOnLoad(rememberFadeInOnLoad(loading))
