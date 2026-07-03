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
    const val FOCUS_DAMPING = 0.8f
    const val FOCUS_STIFFNESS = Spring.StiffnessMedium
    const val SOFT_DAMPING = 0.65f
    const val SOFT_STIFFNESS = Spring.StiffnessMediumLow

    /** Snappy focus spring for content tiles / channel cards / cast circles (R43). */
    fun <T> focusSpring(): SpringSpec<T> = spring(dampingRatio = FOCUS_DAMPING, stiffness = FOCUS_STIFFNESS)

    /** Softer focus spring for buttons / season pills / episode cards. */
    fun <T> softSpring(): SpringSpec<T> = spring(dampingRatio = SOFT_DAMPING, stiffness = SOFT_STIFFNESS)

    // ── Focus scale constants (draw-only graphicsLayer) ─────────────────────────
    const val TILE_FOCUS_SCALE = 1.10f
    const val BUTTON_FOCUS_SCALE = 1.06f
    const val CARD_FOCUS_SCALE = 1.06f   // episode cards
    const val PILL_FOCUS_SCALE = 1.06f   // season picker
    const val CAST_FOCUS_SCALE = 1.12f

    // ── Image loading (R87) ─────────────────────────────────────────────────────
    // A short "settle," not the 600ms global "pop." Coil skips the crossfade for memory-cache hits,
    // so prefetched (R88) / revisited images appear instantly with no fade.
    const val IMAGE_CROSSFADE_MS = 220

    // ── Hero carousel (R58 / R91) ───────────────────────────────────────────────
    const val HERO_CROSSFADE_IN_MS = 600
    const val HERO_CROSSFADE_OUT_MS = 400
    const val HERO_DOT_TWEEN_MS = 300
    // R91 Ken Burns: gentle drift end-scale + travel time. Travel ≥ the auto-advance dwell so the
    // drift never visibly stalls before the slide changes.
    const val HERO_KEN_BURNS_SCALE = 1.05f
    const val HERO_KEN_BURNS_TRAVEL_MS = 9_000

    // ── Screen transitions (R92) ────────────────────────────────────────────────
    // Short directional slide + fade. Push slides in from the trailing edge; pop mirrors it.
    const val SCREEN_ENTER_MS = 220
    const val SCREEN_EXIT_MS = 160

    // ── Player chrome (R90) ─────────────────────────────────────────────────────
    const val CHROME_FADE_IN_MS = 300
    const val CHROME_FADE_OUT_MS = 200
    const val PAUSE_FLASH_IN_MS = 80
    const val PAUSE_FLASH_OUT_MS = 450
    const val PAUSE_FLASH_FROM_SCALE = 0.9f
    const val PAUSE_FLASH_TO_SCALE = 1.4f
    const val NEXT_UP_SLIDE_MS = 340

    // ── Server-message toast (R152) ─────────────────────────────────────────────
    const val TOAST_TRANSITION_MS = 340
}
