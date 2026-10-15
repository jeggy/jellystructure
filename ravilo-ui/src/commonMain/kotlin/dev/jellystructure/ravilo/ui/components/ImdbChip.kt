package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.TvImdbRating
import dev.jellystructure.shared.tv.imdbTitleUrl
import kotlin.math.roundToLong

private val IMDB_GOLD = Color(0xFFF5C518)
private val IMDB_INK = Color(0xFF0A0C13)

/** `24800 → "24.8K"`, `1_200_000 → "1.2M"`, otherwise the raw count. */
private fun fmtVotes(n: Long): String = when {
    n >= 1_000_000 -> "${trimTrailingZero(n / 1_000_000.0)}M"
    n >= 1_000     -> "${trimTrailingZero(n / 1_000.0)}K"
    else           -> n.toString()
}

private fun trimTrailingZero(v: Double): String {
    val rounded = (v * 10).roundToLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/**
 * R164 — server-pushed IMDb rating chip for the Movie/Series detail hero meta row (after the
 * certification badge). Render-only: [rating] is exactly what Phase 131 stored + synced — Ravilo
 * never calls imdbapi.dev. Null renders nothing (no reserved space).
 *
 * R387 — with [openable] (the detail hero only; the browse focus panel and line leave it off), a tap
 * or click anywhere on the chip opens the title on IMDb. Pointer only: a tap detector, never
 * `clickable`/`focusable`, so the chip is not in the D-pad / arrow / Tab focus graph and nothing about
 * how focus moves changes. Inert on a TV (FR-R387-6) and when the server sent no id (FR-R387-3).
 */
@Composable
fun ImdbChip(rating: TvImdbRating?, modifier: Modifier = Modifier, openable: Boolean = false) {
    if (rating == null) return
    val colors = RaviloTheme.colors
    val sora = Sora
    val ratingStr = trimTrailingZero(rating.aggregateRating).let { if ('.' in it) it else "$it.0" }
    // R279 — read aloud, never drawn, and it was the one string a sweep of Text() would miss.
    val a11y = str("imdb.a11y", mapOf("rating" to ratingStr, "votes" to fmtVotes(rating.voteCount)))
    val url = if (openable && !isTvPlatform) imdbTitleUrl(rating.imdbId) else null
    val uri = LocalUriHandler.current
    val openLabel = str("imdb.open")   // R387 (FR-R387-7) — read aloud as the chip's action; never drawn
    val open: () -> Unit = { if (url != null) runCatching { uri.openUri(url) } }
    Row(
        modifier = modifier
            .then(
                if (url != null) Modifier.handCursor().pointerInput(url) { detectTapGestures(onTap = { open() }) }
                else Modifier
            )
            .semantics {
                contentDescription = a11y
                if (url != null) {
                    // Compose 1.9 has no Role.Link; Button is the nearest, and the label says what it does.
                    role = Role.Button
                    onClick(label = openLabel) { open(); true }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(IMDB_GOLD, RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StarGlyph(IMDB_INK, 11.dp)   // R315 — drawn, not typed
            Text(
                " IMDb", color = IMDB_INK, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontFamily = sora,
            )
        }
        Text(
            "  $ratingStr", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = sora,
        )
        Text(
            " ${fmtVotes(rating.voteCount)}", color = colors.textDim, fontSize = 13.sp, fontFamily = sora,
        )
    }
}
