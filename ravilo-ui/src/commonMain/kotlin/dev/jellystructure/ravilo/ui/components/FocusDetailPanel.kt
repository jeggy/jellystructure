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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalTileScale
import dev.jellystructure.ravilo.ui.focus.FocusDetailUi
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.Skin

private const val PANEL_GENRE_CAP = 4

/** Below this the panel stops being worth showing at all — a synopsis in a column this narrow reads
 *  as a ransom note. Only reachable on a viewport far smaller than any real TV. */
private val PANEL_MIN_WIDTH = 280.dp

/**
 * How wide J's panel may actually be, given the row it opens in — **the remaining width after a
 * grown tile of [variant] has taken its share**, never a fixed number.
 *
 * A fixed 620dp was the original shape and it was wrong: at this house's 960dp-wide TV a grown
 * LANDSCAPE tile (256dp → 366dp under FR-R240-7's `ROW_OPEN_WIDTH_SCALE`) leaves only 482dp, so the
 * panel overflowed the screen by 138dp and its synopsis was cut off mid-word on the right. A grown
 * POSTER tile (155dp → 221dp) leaves 627dp, which cleared 620dp by 7dp — which is exactly why the
 * clipping showed up in Continue Watching but not in the poster rows, and why it looked like an
 * intermittent bug rather than a straightforward arithmetic one.
 *
 * Deriving it instead means the panel always ends exactly at the row's own end gutter: nothing is
 * ever clipped, on any tile shape or screen size, and the row's scroll target reduces to the simple
 * "bring the opening tile to the row's content start" (see `StaticContentRow`).
 */
@Composable
fun focusDetailPanelWidthFor(variant: TileVariant): Dp {
    val screenWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val grownTile = tileBaseWidth(variant) * LocalTileScale.current * RaviloMotion.ROW_OPEN_WIDTH_SCALE
    val gutters = raviloHPad * 2
    return (screenWidth - gutters - grownTile - RaviloDimens.itemSpacing).coerceAtLeast(PANEL_MIN_WIDTH)
}

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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusDetailPanel(ui: FocusDetailUi, visible: Boolean, width: Dp, modifier: Modifier = Modifier) {
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
        // R250 (FR-R250-1) — a panel-local scrim under the text column, layered over R242's full-screen
        // wash: transparent at the tile-facing edge, near-opaque behind the text. The number is not
        // taste: `textSecondary` (Aurora #AEB4CB, L≈0.46) needs the blended ground at L≤0.06 to clear
        // 4.5:1 against a pure-white worst-case region, i.e. ≥0.94 of `background` (L≈0.003); the title
        // clears 3:1 from ~0.73. Noir goes deeper still (R242 FR-R242-7's precedent). The picture stays
        // untouched outside the panel — R242's point.
        val noir = LocalRaviloSkin.current == Skin.NOIR
        // R255 (owner decision 2026-09-17) — NO panel-local scrim: R250's opaque box and this phase's
        // first feathered gradient are both gone. The whole backdrop carries one flat wash strong enough
        // for this text anywhere on screen (FocusDetailScrims).
        Box {
        Row(modifier = Modifier.width(width).fillMaxHeight().padding(start = 20.dp, top = 4.dp, end = 12.dp)) {
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
                // FlowRow, not Row: the panel's width is now derived from whatever a grown tile leaves
                // (see [focusDetailPanelWidthFor]), so on a LANDSCAPE row this line has ~480dp rather
                // than the 620dp it was laid out against. A plain Row would silently run its last
                // badges off the panel's right edge — the same clipping this whole pass is removing.
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
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
                // Same reasoning as the meta line above — two flag strips plus a resume label is the
                // widest thing in the panel, and a title with both audio and subtitle flags in a
                // narrow (LANDSCAPE-row) panel would otherwise push the resume text off the edge.
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
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
        }  // R255 — the scrim+content Box
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
