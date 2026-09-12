package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.str
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
internal fun focusDetailCountText(facts: FocusDetailFacts): String? {
    val seasons = facts.seasons
    val episodes = facts.episodes
    return when {
        seasons != null && seasons > 0 -> {
            val base = if (seasons == 1) "1 Season" else "$seasons Seasons"
            if (episodes != null && episodes > 0) "$base · $episodes episodes" else base
        }
        else -> {
            val runtime = facts.runtimeMinutes
            if (runtime != null && runtime > 0) "$runtime min" else null
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
    if (pct <= 0f || pct >= 100f) return null
    val runtime = facts.runtimeMinutes ?: return str("action.resume")
    val minsLeft = max(1, round(runtime * (1 - pct / 100f)).toInt())
    return str("fd.min_left", mapOf("n" to minsLeft.toString()))
}
