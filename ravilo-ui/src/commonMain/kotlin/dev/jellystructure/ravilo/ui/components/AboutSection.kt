package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import dev.jellystructure.ravilo.ui.theme.raviloRowHeadPadB
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.countryName
import dev.jellystructure.ravilo.ui.seams.endonymOf
import dev.jellystructure.ravilo.ui.seams.languageName
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.AboutFacts

/**
 * R325 (FR-R325-1) — the About section under a film's or series' synopsis (after Episodes on a series): a grid of
 * label · value pairs, two columns where the width allows. A row is drawn only when the server sent its value;
 * nothing is invented and nothing says *Unknown*. Not focusable: it is read, never acted on (same as the flag line).
 */
@Composable
fun AboutSection(about: AboutFacts, series: Boolean, twoColumns: Boolean, modifier: Modifier = Modifier) {
    val rows = aboutRows(about, series)
    if (rows.isEmpty()) return
    val colors = RaviloTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
        Spacer(Modifier.height(raviloRowGap))
        Text(str("about.title"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = SpaceGrotesk, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(raviloRowHeadPadB))
        val perRow = if (twoColumns) 2 else 1
        rows.chunked(perRow).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                chunk.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                        Text(label.uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(value, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                    }
                }
                if (chunk.size < perRow) Spacer(Modifier.weight((perRow - chunk.size).toFloat()))
            }
        }
    }
}

/** The rows in the spec's order, label already translated, each absent when its fact is. */
@Composable
internal fun aboutRows(a: AboutFacts, series: Boolean): List<Pair<String, String>> = buildList {
    a.runtimeMin?.takeIf { it > 0 }?.let {
        add(str("about.runtime") to (if (series) str("about.per_episode", mapOf("min" to it.toString())) else str("about.minutes", mapOf("min" to it.toString()))))
    }
    a.released?.takeIf { it.isNotBlank() }?.let { add((if (series) str("about.first_aired") else str("about.released")) to aboutDate(it)) }
    a.director?.takeIf { it.isNotBlank() }?.let { add(str("about.director") to it) }
        ?: a.creator?.takeIf { it.isNotBlank() }?.let { add(str("about.creator") to it) }
    a.studioOrNetwork?.takeIf { it.isNotBlank() }?.let { add((if (series) str("about.network") else str("about.studio")) to it) }
    a.country?.takeIf { it.isNotBlank() }?.let { add(str("about.country") to (countryName(it) ?: it)) }
    a.originalLanguage?.takeIf { it.isNotBlank() }?.let { add(str("about.original_language") to (endonymOf(it) ?: languageName(it) ?: it)) }
    if (series && a.seasons != null && a.episodes != null) {
        add(str("about.seasons") to str("about.seasons_value", mapOf("seasons" to a.seasons.toString(), "episodes" to a.episodes.toString())))
    }
    a.added?.takeIf { it > 0 }?.let { add(str("about.in_library_since") to aboutDate(epochDayIso(it))) }
}

/** `2024-03-08` → `8 March 2024` in the viewer's language (`about.month.N`); a partial date is shown as it is. */
@Composable
internal fun aboutDate(iso: String): String {
    val parts = iso.split('-')
    val y = parts.getOrNull(0)?.toIntOrNull() ?: return iso
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return iso
    val d = parts.getOrNull(2)?.toIntOrNull() ?: return "${str("about.month.$m")} $y"
    return str("about.date", mapOf("day" to d.toString(), "month" to str("about.month.$m"), "year" to y.toString()))
}

/** Epoch seconds → `YYYY-MM-DD` (UTC), without a platform date library. */
internal fun epochDayIso(epochSeconds: Long): String {
    var days = epochSeconds / 86_400
    var year = 1970
    while (true) {
        val len = if (isLeap(year)) 366 else 365
        if (days < len) break
        days -= len; year++
    }
    val months = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 1
    for (len in months) { if (days < len) break; days -= len; month++ }
    return "$year-${month.toString().padStart(2, '0')}-${(days + 1).toString().padStart(2, '0')}"
}

private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
