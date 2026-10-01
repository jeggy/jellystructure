package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import dev.jellystructure.ravilo.ui.focus.rememberGutterBringIntoViewSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.Season
import androidx.compose.ui.focus.FocusRequester

/** R343 (FR-R343-9) — test tags the series page's focus-path test walks by. */
object SeasonPickerTags {
    fun pill(seasonIndex: Int) = "season-pill-$seasonIndex"
    const val SHUFFLE = "season-shuffle"
}

/**
 * The season pills, plus (R343, FR-R343-5) a thin divider and a **Shuffle** pill as the row's last item.
 *
 * R343 (FR-R343-9, dev review item 13) — a plain, always-composed `Row` with `horizontalScroll`, not a
 * `LazyRow`. A series has few seasons, and the lazy row put the selected pill's [firstFocusRequester] on a
 * lazy item: the exact R201 failure (a requester attached to nothing when the selected pill sat outside the
 * composed window), kept alive by a `scrollToItem` workaround. Every pill is composed now, so the requester
 * is always attached and the workaround is gone; focusing a pill off-screen brings it into view through the
 * scroll's own bring-into-view (with R250's gutter spec).
 */
@Composable
fun SeasonPicker(
    seasons: List<Season>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    firstFocusRequester: FocusRequester? = null,   // R138: lets the hero hand DOWN-focus straight to the selected season
    watchedSeasons: Set<Int> = emptySet(),
    // R151: season.index -> watched episode count, for the partial (1..n-1 watched) w/N badge + sliver.
    // A season complete per [watchedSeasons] takes the ✓ badge instead, regardless of what's here.
    watchedCounts: Map<Int, Int> = emptyMap(),
    // R343 (FR-R343-5) — the Shuffle pill's label and action; null draws no Shuffle.
    shuffleLabel: String? = null,
    onShuffle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val pillShape = remember { RoundedCornerShape(24.dp) }
    val focusSpec = remember { RaviloMotion.softSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.softSpring<Dp>() }

    // Bug fix (live-tested on stue TV, Fjollerne's 11-season row): every pill has an explicit Left/Right
    // target, so a fast D-pad burst can never outrun native search and escape to the AppBar's profile
    // avatar (a row just under the bar). No-op at both true ends of the row.
    val pillFocusRequesters = remember(seasons) { List(seasons.size) { FocusRequester() } }
    val hasShuffle = shuffleLabel != null && onShuffle != null
    val shuffleFR = remember { FocusRequester() }

    // R250 (FR-R250-6) — a focused pill stays inside the safe area: bring-into-view keeps `raviloHPad`
    // as its margin on both sides, so the row never parks a focused pill against the screen edge.
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberGutterBringIntoViewSpec(raviloHPad)) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .focusRestorer()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = raviloHPad),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            seasons.forEachIndexed { i, season ->
                SeasonPill(
                    season = season,
                    isSelected = i == selectedIndex,
                    requester = pillFocusRequesters[i],
                    entryRequester = firstFocusRequester.takeIf { i == selectedIndex },
                    isComplete = season.index in watchedSeasons,
                    watchedCount = watchedCounts[season.index] ?: 0,
                    onSelect = { onSelect(i) },
                    onLeft = if (i == 0) {{ }} else {{ runCatching { pillFocusRequesters[i - 1].requestFocus() } }},
                    onRight = when {
                        i < seasons.lastIndex -> {{ runCatching { pillFocusRequesters[i + 1].requestFocus() } }}
                        hasShuffle -> {{ runCatching { shuffleFR.requestFocus() } }}
                        else -> {{ }}
                    },
                    pillShape = pillShape, focusSpec = focusSpec, dpSpec = dpSpec, sora = sora,
                )
            }
            if (hasShuffle) {
                // FR-R343-5 — a thin divider, then Shuffle, focusable like a season pill.
                Box(Modifier.width(1.dp).height(24.dp).background(colors.textSecondary.copy(alpha = 0.35f)))
                ShufflePill(
                    label = shuffleLabel!!,
                    requester = shuffleFR,
                    onSelect = onShuffle!!,
                    onLeft = { pillFocusRequesters.lastOrNull()?.let { runCatching { it.requestFocus() } } },
                    pillShape = pillShape, focusSpec = focusSpec, dpSpec = dpSpec, sora = sora,
                )
            }
        }
    }
}

@Composable
private fun SeasonPill(
    season: Season,
    isSelected: Boolean,
    requester: FocusRequester,
    entryRequester: FocusRequester?,
    isComplete: Boolean,
    watchedCount: Int,
    onSelect: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    pillShape: RoundedCornerShape,
    focusSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    dpSpec: androidx.compose.animation.core.AnimationSpec<Dp>,
    sora: androidx.compose.ui.text.font.FontFamily,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val scale        by animateFloatAsState(if (focused) RaviloMotion.PILL_FOCUS_SCALE else 1f, focusSpec, label = "pillScale")
    // R223 FR-1: a focused pill is always visibly focused, selected or not. `focusRing` sits deliberately
    // close to `accent` in every skin, so over the selected pill's accent fill use `onAccent` instead.
    val borderWidth  by animateDpAsState(if (focused) 2.dp else 0.dp, dpSpec, label = "pillBorder")
    val borderColor  = if (isSelected) colors.onAccent else colors.focusRing
    val glowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "pillShadow")

    // Focusable outer keeps a constant layout size; the scale + glow run draw-only on the inner layer so
    // the season picker never chases the focus animation → no viewport jump (R42/R43).
    Box(
        modifier = Modifier
            .testTag(SeasonPickerTags.pill(season.index))
            .then(if (entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier)
            .dpadFocusable(
                focusRequester = requester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
                onLeft = onLeft,
                onRight = onRight,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = glowElevation.toPx()
                    shape = pillShape
                    clip = false
                    ambientShadowColor = colors.focusGlow
                    spotShadowColor = colors.focusGlow
                }
                .background(if (isSelected) colors.accent else colors.surfaceVariant, pillShape)
                .border(borderWidth, borderColor, pillShape)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // R150/R151: complete → ✓ badge; partial (1..n-1 watched) → w/N count badge + sliver;
            // none watched → plain pill. Empty overlay (watchedSeasons/watchedCounts both empty) shows nothing.
            val total = season.episodes.size
            val isPartial = !isComplete && watchedCount > 0 && total > 0
            // On the selected (accent-filled) pill the badge/sliver invert to stay legible.
            val badgeBg    = if (isSelected) colors.onAccent.copy(alpha = 0.18f) else colors.progressBg
            val badgeText  = if (isSelected) colors.onAccent else colors.text
            val sliverColor = if (isSelected) colors.onAccent else colors.progressFill

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = season.name,
                    color = if (isSelected) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                )
                if (isComplete) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(colors.badgeWatched, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CheckGlyph(colors.background, 12.dp, Modifier.padding(horizontal = 6.dp, vertical = 2.dp))   // R315
                    }
                } else if (isPartial) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(badgeBg, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$watchedCount/$total",
                            color = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = sora,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (isPartial) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp)) {
                    Box(Modifier.weight(watchedCount.toFloat()).height(3.dp)
                        .background(sliverColor, RoundedCornerShape(3.dp)))
                    Box(Modifier.weight((total - watchedCount).toFloat()))
                }
            }
        }
    }
}

/** R343 (FR-R343-5) — the Shuffle pill: the shuffle glyph and its label, focusable like a season pill. */
@Composable
private fun ShufflePill(
    label: String,
    requester: FocusRequester,
    onSelect: () -> Unit,
    onLeft: () -> Unit,
    pillShape: RoundedCornerShape,
    focusSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    dpSpec: androidx.compose.animation.core.AnimationSpec<Dp>,
    sora: androidx.compose.ui.text.font.FontFamily,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val scale by animateFloatAsState(if (focused) RaviloMotion.PILL_FOCUS_SCALE else 1f, focusSpec, label = "shuffleScale")
    val borderWidth by animateDpAsState(if (focused) 2.dp else 0.dp, dpSpec, label = "shuffleBorder")
    val glowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "shuffleShadow")
    Box(
        modifier = Modifier
            .testTag(SeasonPickerTags.SHUFFLE)
            .dpadFocusable(
                focusRequester = requester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
                onLeft = onLeft,
                onRight = { },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = glowElevation.toPx()
                    shape = pillShape
                    clip = false
                    ambientShadowColor = colors.focusGlow
                    spotShadowColor = colors.focusGlow
                }
                .background(colors.surfaceVariant, pillShape)
                .border(borderWidth, colors.focusRing, pillShape)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShuffleGlyph(if (focused) colors.text else colors.textSecondary, 15.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = sora,
            )
        }
    }
}
