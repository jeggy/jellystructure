package dev.jellystructure.ravilo.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Centralized motion tokens (R89). The single source of truth for the app's motion language —
 * mirrors [RaviloColors] / [RaviloDimens]. Before this, every component hardcoded its own spring /
 * tween inline, so the feel drifted between surfaces and couldn't be tuned in one place.
 *
 * Direction (restrained, Netflix-style): **fast, draw-only, subtle**. Responsiveness and zero jank
 * over flashiness. The focus springs preserve the R42/R43-tuned snappy feel verbatim; transitions
 * are short; the image crossfade is a "settle," not a "pop."
 *
 * Focus animations are still expected to run draw-only (scale + shadow in a `graphicsLayer`, ring in
 * `drawWithCache`) at every call site so the lazy list's focused-bounds never chases the scale.
 */
object RaviloMotion {
    // ── Focus springs ──────────────────────────────────────────────────────────
    // Snappier surfaces (tiles, channel cards, cast circles): StiffnessMedium settles fast.
    // Softer UI (buttons, season pills, episode cards): StiffnessMediumLow reads gentler.
    const val FocusDamping = 0.8f
    val FocusStiffness = Spring.StiffnessMedium
    const val SoftDamping = 0.65f
    val SoftStiffness = Spring.StiffnessMediumLow

    /** Snappy focus spring for content tiles / channel cards / cast circles (R43). */
    fun <T> focusSpring(): SpringSpec<T> = spring(dampingRatio = FocusDamping, stiffness = FocusStiffness)

    /** Softer focus spring for buttons / season pills / episode cards. */
    fun <T> softSpring(): SpringSpec<T> = spring(dampingRatio = SoftDamping, stiffness = SoftStiffness)

    // ── Focus scale constants (draw-only graphicsLayer) ─────────────────────────
    const val TileFocusScale = 1.10f
    const val ButtonFocusScale = 1.06f
    const val CardFocusScale = 1.06f   // episode cards
    const val PillFocusScale = 1.06f   // season picker
    const val CastFocusScale = 1.12f

    // ── Image loading (R87) ─────────────────────────────────────────────────────
    // A short "settle," not the 600ms global "pop." Coil skips the crossfade for memory-cache hits,
    // so prefetched (R88) / revisited images appear instantly with no fade.
    const val ImageCrossfadeMs = 220

    // ── Hero carousel (R58 / R91) ───────────────────────────────────────────────
    const val HeroCrossfadeInMs = 600
    const val HeroCrossfadeOutMs = 400
    const val HeroDotTweenMs = 300
    // R91 Ken Burns: gentle drift end-scale + travel time. Travel ≥ the auto-advance dwell so the
    // drift never visibly stalls before the slide changes.
    const val HeroKenBurnsScale = 1.05f
    const val HeroKenBurnsTravelMs = 9_000
    // R91 parallax: hero drifts at this fraction of content scroll speed as it leaves the viewport.
    const val HeroParallaxFactor = 0.5f

    // ── Screen transitions (R92) ────────────────────────────────────────────────
    // Short directional slide + fade. Push slides in from the trailing edge; pop mirrors it.
    const val ScreenEnterMs = 220
    const val ScreenExitMs = 160

    // ── Player chrome (R90) ─────────────────────────────────────────────────────
    const val ChromeFadeInMs = 300
    const val ChromeFadeOutMs = 200
    const val PauseFlashInMs = 80
    const val PauseFlashOutMs = 450
    const val PauseFlashFromScale = 0.9f
    const val PauseFlashToScale = 1.4f
    const val NextUpSlideMs = 340
}
