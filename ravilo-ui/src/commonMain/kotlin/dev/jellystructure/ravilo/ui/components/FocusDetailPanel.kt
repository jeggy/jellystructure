package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.FocusDetailUi
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.Skin

private val PANEL_CONTENT_WIDTH = 620.dp
private const val PANEL_GENRE_CAP = 4

/**
 * The panel's own total laid-out width — `Modifier.width(PANEL_CONTENT_WIDTH)` fixes the node's
 * measured width, and the `padding` after it insets the content *within* that, so the node is exactly
 * [PANEL_CONTENT_WIDTH] wide.
 *
 * Exported because `StaticContentRow` must know how much room the panel needs **before it has ever
 * been laid out**. That isn't an optimisation — it's the only way the calculation can work at all: the
 * panel is inserted to the RIGHT of a tile that is frequently already at the right edge of the
 * viewport, so the panel's own slot lands off-screen, and a `LazyRow` never *places* an off-screen
 * item. `onGloballyPositioned` therefore never fires for it, so "measure the panel, then scroll it
 * into view" is circular — it can't be measured until it's scrolled in, and it can't be scrolled in
 * until it's measured. Knowing the width up front breaks that loop.
 */
val FOCUS_DETAIL_PANEL_WIDTH: Dp = PANEL_CONTENT_WIDTH

/**
 * FR-R240-10 (built 2026-09-12) — a lateral hop from one already-open tile straight to another IN THE
 * SAME ROW now gets a real two-panel crossfade: `ContentRowItem` (HomeScreen.kt) moves the tile that
 * WAS open into a second, independent `closingAfterKey`/`closingPanel` slot on `StaticContentRow`
 * (always rendered with `visible = false`, so its own exit tween plays out undisturbed) while the new
 * tile opens in the original `openAfterKey` slot — two `FocusDetailPanel` instances genuinely mid-tween
 * at once, one shrinking, one expanding, rather than one shared node being re-anchored with no exit
 * animation for the tile that just lost focus. (Compose's `LazyRow` reflows both width changes on its
 * own via its per-item-key anchor tracking, so there is no separate manual `FD.hShift`-style scroll
 * subtraction to build here the way the original HTML mockup needed — that concern was specific to the
 * mockup's own scroll-offset bookkeeping.) FR-R240-9 (the row's one-shot vertical scroll target) is
 * built too, in `FocusDetailScroll.kt`/`ContentRowItem` — a separate concern from this horizontal one.
 *
 * Phase R240 (FR-R240-3/7) — J's inert panel, inserted into the row right after the tile that opened
 * it (see `StaticContentRow`'s `openAfterKey`/`openPanel`). [visible] drives Compose's own
 * `expandHorizontally`/`shrinkHorizontally`, which is exactly FR-R240-7's required shape for free: the
 * child is laid out at its full, fixed [PANEL_CONTENT_WIDTH] the whole time (so text never reflows
 * mid-tween) while only a clip animates 0→full width, and — because `AnimatedVisibility` keeps a
 * child composed through its exit transition and only then removes it — the panel "leaves the tree
 * once it has closed" without any bookkeeping of our own.
 *
 * FR-R240-5 — inert top to bottom: nothing in this subtree calls `dpadFocusable`/`focusable`, holds a
 * `FocusRequester`, or can be found by `focusRestorer()`'s row-level bridge search.
 */
@Composable
fun FocusDetailPanel(ui: FocusDetailUi, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(tween(dev.jellystructure.ravilo.ui.theme.RaviloMotion.ROW_OPEN_TWEEN_MS)) +
            fadeIn(tween(140)),
        exit = shrinkHorizontally(tween(dev.jellystructure.ravilo.ui.theme.RaviloMotion.ROW_OPEN_TWEEN_MS)) +
            fadeOut(tween(90)),
        modifier = modifier,
    ) {
        val facts = ui.facts
        val colors = RaviloTheme.colors
        Row(modifier = Modifier.width(PANEL_CONTENT_WIDTH).fillMaxHeight().padding(start = 20.dp, top = 4.dp)) {
            Column {
                Text(
                    text = ui.card.title,
                    color = colors.text,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    facts.badge?.let {
                        Box(modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(it, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Sora)
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    facts.year?.let { Text(it.toString(), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.padding(end = 10.dp)) }
                    focusDetailCountText(facts)?.let { Text(it, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.padding(end = 10.dp)) }
                    if (facts.ratingBadge != null) { CertBadge(facts.ratingBadge); Spacer(Modifier.width(10.dp)) }
                    if (facts.imdbRating != null) ImdbChip(facts.imdbRating)
                }
                if (facts.genres.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    InertGenreChips(facts.genres)
                }
                Spacer(Modifier.height(12.dp))
                val overview = facts.overview
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontFamily = Sora,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(str("fd.nodesc"), color = colors.textDim, fontSize = 15.sp, fontStyle = FontStyle.Italic, fontFamily = Sora)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasFlags = facts.audioLanguages.isNotEmpty() || facts.subtitleLanguages.isNotEmpty()
                    if (facts.audioLanguages.isNotEmpty()) AudioFlagStrip(facts.audioLanguages, label = str("fd.audio"))
                    if (facts.audioLanguages.isNotEmpty() && facts.subtitleLanguages.isNotEmpty()) Spacer(Modifier.width(14.dp))
                    if (facts.subtitleLanguages.isNotEmpty()) AudioFlagStrip(facts.subtitleLanguages, label = str("fd.subs"))
                    val resume = focusDetailResumeText(ui.card, facts)
                    if (resume != null) {
                        if (hasFlags) Spacer(Modifier.width(14.dp))
                        Text(resume, color = colors.accentSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = Sora)
                    }
                }
            }
        }
    }
}

/** A non-interactive twin of [GenreChipRow]'s chip: same color/weight rule (incl. Noir's FR-R240-12
 *  ink-not-tint override for the primary chip), but nothing here is `dpadFocusable` — J's panel adds
 *  no focus stop (FR-R240-5), so the real (selectable) chip row can't be reused as-is. */
@Composable
private fun InertGenreChips(genres: List<String>) {
    val colors = RaviloTheme.colors
    val skin = LocalRaviloSkin.current
    val noir = skin == Skin.NOIR
    val shape = RoundedCornerShape(50)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.take(PANEL_GENRE_CAP).forEachIndexed { i, g ->
            val lead = i == 0
            val bg = when { lead && !noir -> colors.accent.copy(alpha = 0.22f); lead -> colors.surfaceVariant; else -> Color.White.copy(alpha = 0.06f) }
            val ink = if (lead) colors.text else colors.textSecondary
            val weight = if (lead) FontWeight.SemiBold else FontWeight.Medium
            Box(modifier = Modifier.background(bg, shape).padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(g, color = ink, fontSize = 14.sp, fontWeight = weight, fontFamily = Sora)
            }
        }
    }
}
