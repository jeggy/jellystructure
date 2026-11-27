package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.FocusDetailFacts
import dev.jellystructure.shared.tv.MediaCard
import kotlin.math.max
import kotlin.math.round

/**
 * Phase R240 (FR-R240-11) — assembles the client's own strings against [FocusDetailFacts]' raw
 * numbers; formatting is not computing (the numbers themselves are 100% server-resolved, FR-202-5),
 * but presentation of them stays with Ravilo the same way `detail.runtime`'s "127 min" already does
 * on the movie/series detail screens (MovieDetailScreen.kt) — those aren't routed through Strings.kt
 * either, so this follows the same established convention rather than inventing a second one. Only
 * the four strings the spec actually calls out (`fd.audio`/`fd.subs`/`fd.nodesc`/`fd.min_left`) are
 * real i18n keys.
 */
@Composable
internal fun focusDetailCountText(facts: FocusDetailFacts): String? {
    val lang = LocalLang.current
    return focusDetailCountText(facts) { key, vars -> t(key, lang, vars) }
}

/** The same line against any translator, so a test can pass a fake (DEB-5836, FR-5). */
internal fun focusDetailCountText(facts: FocusDetailFacts, tr: (key: String, vars: Map<String, String>) -> String): String? {
    val seasons = facts.seasons
    val episodes = facts.episodes
    return when {
        seasons != null && seasons > 0 -> {
            val base = if (seasons == 1) tr("fd.season_one", emptyMap()) else tr("fd.seasons", mapOf("n" to seasons.toString()))
            if (episodes != null && episodes > 0) {
                val eps = if (episodes == 1) tr("fd.episode_one", emptyMap()) else tr("fd.episodes", mapOf("n" to episodes.toString()))
                "$base · $eps"
            } else {
                base
            }
        }
        else -> {
            val runtime = facts.runtimeMinutes
            if (runtime != null && runtime > 0) tr("up.runtime_min", mapOf("n" to runtime.toString())) else null
        }
    }
}

/** "Next Episode" / "Resume · 12 min left" — mirrors `fieldsFor()`'s `resume` field in the mockup.
 *  [MediaCard.nextUpLabel] already carries a finished label when the server has one; otherwise this
 *  derives minutes-left from the movie's own runtime and progress, matching the mockup's `fd_min_left`. */
@Composable
internal fun focusDetailResumeText(card: MediaCard, facts: FocusDetailFacts): String? {
    card.nextUpLabel?.let { return it }
    val pct = card.progressPct ?: return null
    if (pct <= 0f || pct >= 1f) return null
    val runtime = facts.runtimeMinutes ?: return str("action.resume")
    return str("fd.min_left", mapOf("n" to minutesLeft(runtime, pct).toString()))
}

/** [pct] is [MediaCard.progressPct], 0..1 (DEB-5836, FR-1). */
internal fun minutesLeft(runtime: Int, pct: Float): Int =
    max(1, round(runtime * (1 - pct)).toInt())
