package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.Skin

private const val GENRE_CAP = 4

/**
 * R221 — every genre on a media detail, TMDB's own order, the first one marked primary. Same visual
 * pattern as [AudioFlagStrip] (dim caps label, then values) but each value is focusable and opens the
 * R187 browse page seeded to it (FR-RV-GEN1-4) — the same contract R190 gave a cast face.
 *
 * TV ([LocalCompact.current] == false): capped at [GENRE_CAP] visible plus a focusable `+N`, never
 * wrapping — `horizontalScroll` guards the same overflow the hero's actions row already needed
 * (long genre names in a 60%-width column). Phone: every genre renders, wraps freely via [FlowRow], no
 * cap and no `+N` (FR-RV-GEN1-3).
 *
 * No genres ⇒ this composable renders nothing at all — never a label with an empty row beside it
 * (FR-RV-GEN1-1), so callers can call it unconditionally.
 */
@Composable
fun GenreChipRow(
    genres: List<String>,
    onGenreSelect: ((List<String>) -> Unit)?,
    entryFocusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return
    val compact = LocalCompact.current
    val label = str(if (genres.size == 1) "detail.genre" else "detail.genres")

    if (compact) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GenreRowLabel(label)
            genres.forEachIndexed { i, g ->
                GenreChip(
                    text = g,
                    lead = i == 0,
                    onSelect = onGenreSelect?.let { { it(listOf(g)) } },
                )
            }
        }
        return
    }

    val shown = genres.take(GENRE_CAP)
    val rest = genres.drop(GENRE_CAP)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GenreRowLabel(label)
        shown.forEachIndexed { i, g ->
            GenreChip(
                text = g,
                lead = i == 0,
                focusRequester = if (i == 0) entryFocusRequester else null,
                onUp = if (i == 0) onUp else null,
                onDown = onDown,
                onSelect = onGenreSelect?.let { { it(listOf(g)) } },
            )
        }
        if (rest.isNotEmpty()) {
            GenreChip(
                text = "+${rest.size}",
                lead = false,
                onDown = onDown,
                onSelect = onGenreSelect?.let { { it(genres) } },
            )
        }
    }
}

@Composable
private fun GenreRowLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        fontFamily = Sora,
        modifier = Modifier.padding(end = 2.dp),
    )
}

@Composable
private fun GenreChip(
    text: String,
    lead: Boolean,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)?,
) {
    val colors = RaviloTheme.colors
    val skin = LocalRaviloSkin.current
    val sora = Sora
    var focused by remember { mutableStateOf(false) }
    val arrowAlpha by animateFloatAsState(if (focused && onSelect != null) 0.75f else 0f, label = "genreArrow")
    val shape = remember { RoundedCornerShape(50) }

    // R221 — Noir's accent IS amber, so a tinted lead chip reads as a warning there (the same collision
    // R222's copy later reused the fix for): keep the weight/ink signal, drop the colour.
    val noir = skin == Skin.NOIR
    val bg = when {
        focused -> colors.accent
        lead && !noir -> colors.accent.copy(alpha = 0.22f)
        lead -> colors.surfaceVariant
        else -> Color.White.copy(alpha = 0.06f)
    }
    val border = when {
        focused -> Color.Transparent
        lead && !noir -> colors.accent.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val ink = when {
        focused -> colors.onAccent
        lead -> colors.text
        else -> colors.textSecondary
    }
    val weight = if (lead) FontWeight.SemiBold else FontWeight.Medium

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onUp = onUp,
                onDown = onDown,
                onSelect = onSelect,
            )
            .clip(shape)
            .background(bg, shape)
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text = text, color = ink, fontSize = 14.sp, fontWeight = weight, fontFamily = sora)
        if (arrowAlpha > 0f) {
            Spacer(Modifier.width(6.dp))
            Text(text = "›", color = ink.copy(alpha = arrowAlpha), fontSize = 13.sp, fontFamily = sora)
        }
    }
}
