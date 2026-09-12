package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.FocusDetailUi
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.Skin

/** FR-R240-2 — the height L's foot strip reserves. Home's scroll surface adds this much bottom
 *  padding whenever the household's resolved direction is "line" — not merely while something is
 *  focused, or the page would shift the moment the first tile ever takes focus. */
val FOCUS_DETAIL_LINE_HEIGHT = 88.dp

/**
 * Phase R240 (FR-R240-2) — L, the always-present foot status line. [active] is
 * `HomeFeed.focusDetail == "line"` (the household's resolved direction); the strip — and its
 * reserved space — exists for as long as that's true. [ui] is null while nothing is eligible or
 * the dwell hasn't settled (FR-R240-6): the strip stays reserved but says nothing, never a stale
 * title's facts.
 *
 * FR-R240-5 — everything drawn here is inert: no `dpadFocusable`/`focusable` modifier anywhere in
 * this tree, not in the D-pad order, no action, no dismissal — a label, like the age badge.
 */
@Composable
fun FocusDetailLine(ui: FocusDetailUi?, active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val colors = RaviloTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FOCUS_DETAIL_LINE_HEIGHT)
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.96f)))),
    ) {
        if (ui != null && ui.mode == "line") {
            val facts = ui.facts
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ui.card.title,
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                FDDivider()
                facts.badge?.let { FDChip(it) }
                facts.year?.let { FDPlain(it.toString()) }
                focusDetailCountText(facts)?.let { FDPlain(it) }
                if (facts.ratingBadge != null) { CertBadge(facts.ratingBadge); Spacer(Modifier.width(12.dp)) }
                if (facts.imdbRating != null) { ImdbChip(facts.imdbRating); Spacer(Modifier.width(12.dp)) }

                val hasFlags = facts.audioLanguages.isNotEmpty() || facts.subtitleLanguages.isNotEmpty()
                if (hasFlags) {
                    FDDivider()
                    if (facts.audioLanguages.isNotEmpty()) AudioFlagStrip(facts.audioLanguages, label = str("fd.audio"))
                    if (facts.audioLanguages.isNotEmpty() && facts.subtitleLanguages.isNotEmpty()) Spacer(Modifier.width(14.dp))
                    if (facts.subtitleLanguages.isNotEmpty()) AudioFlagStrip(facts.subtitleLanguages, label = str("fd.subs"))
                }

                val resume = focusDetailResumeText(ui.card, facts)
                if (resume != null) {
                    FDDivider()
                    val skin = LocalRaviloSkin.current
                    val dotColor = if (skin == Skin.NOIR) colors.accent else colors.accentSecondary
                    Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(resume, color = dotColor, fontSize = 14.sp, fontFamily = Sora, fontWeight = FontWeight.Medium)
                }

                if (facts.genres.isNotEmpty()) {
                    Spacer(Modifier.width(28.dp))
                    Text(
                        text = facts.genres.joinToString(" · "),
                        color = colors.textDim,
                        fontSize = 13.sp,
                        fontFamily = Sora,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FDDivider() {
    val colors = RaviloTheme.colors
    Spacer(Modifier.width(16.dp))
    Box(modifier = Modifier.width(1.dp).height(22.dp).background(colors.textDim.copy(alpha = 0.3f)))
    Spacer(Modifier.width(16.dp))
}

@Composable
private fun FDPlain(text: String) {
    Text(text, color = RaviloTheme.colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.padding(end = 12.dp))
}

@Composable
private fun FDChip(text: String) {
    val colors = RaviloTheme.colors
    Box(modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Sora)
    }
    Spacer(Modifier.width(12.dp))
}
