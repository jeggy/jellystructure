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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
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
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val pillShape = remember { RoundedCornerShape(24.dp) }
    val focusSpec = remember { RaviloMotion.softSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.softSpring<Dp>() }

    // R201 — a series whose auto-selected season (e.g. the last season, for a fully-watched show) sits
    // outside this row's initial composition window left `firstFocusRequester` attached to nothing: a
    // LazyRow only composes items near its current scroll position, and this row never scrolled itself
    // to the selected pill. Fires on first composition too (not just later changes), so the selected
    // pill is always at least brought into composition before anything tries to focus it.
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { runCatching { listState.scrollToItem(selectedIndex) } }

    // Bug fix (live-tested on stue TV, Fjollerne's 11-season row): interior pills used to leave
    // onLeft/onRight null so Compose's native spatial focus search would move between them (the
    // documented dpadFocusable pattern for lazy-list items). That search composes the next off-screen
    // item and scrolls it into view, which takes a frame or two — under a fast D-pad burst (real remote
    // repeat-rate, not a single deliberate press) the next pill wasn't composed/on-screen yet when the
    // key landed, and native search picked the nearest ALREADY-composed focusable candidate instead:
    // the AppBar's profile avatar in the top-right corner, several rows above. Focus silently jumped
    // there mid-burst with no visual cue at the season row itself. Explicit per-pill FocusRequesters
    // sidestep the timing dependency entirely — Left/Right always targets a specific known pill.
    val pillFocusRequesters = remember(seasons) { List(seasons.size) { FocusRequester() } }

    // R250 (FR-R250-6) — a focused pill stays inside the safe area: bring-into-view keeps `raviloHPad`
    // as its margin on both sides, so the row never parks a focused pill against the screen edge.
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberGutterBringIntoViewSpec(raviloHPad)) {
        LazyRow(
            state = listState,
            modifier = modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = raviloHPad),
        ) {
            items(seasons.size, key = { i -> seasons[i].index }) { i ->
                val isSelected = i == selectedIndex
                var focused by rememberFocusVisual()
                val scale        by animateFloatAsState(if (focused) RaviloMotion.PILL_FOCUS_SCALE else 1f, focusSpec, label = "pillScale$i")
                // R223 FR-1: a focused pill is always visibly focused, selected or not. `focusRing` sits
                // deliberately close to `accent` in hue/lightness in every skin, so a focusRing border drawn
                // over the selected pill's accent fill would itself be near-invisible — use `onAccent` there
                // instead (the same token the pill's own label/badge text already switch to for this exact
                // contrast problem, see `badgeText` below).
                val borderWidth  by animateDpAsState(if (focused) 2.dp else 0.dp, dpSpec, label = "pillBorder$i")
                val borderColor  = if (isSelected) colors.onAccent else colors.focusRing
                val glowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "pillShadow$i")

                // Focusable outer keeps a constant layout size; the scale + glow run draw-only on the inner
                // layer so the season picker never chases the focus animation → no viewport jump (R42/R43).
                Box(
                    modifier = Modifier
                        .then(
                            if (i == selectedIndex && firstFocusRequester != null)
                                Modifier.focusRequester(firstFocusRequester)
                            else Modifier
                        )
                        .dpadFocusable(
                            focusRequester = pillFocusRequesters[i],
                            onFocused = { focused = true },
                            onBlurred = { focused = false },
                            onSelect = { onSelect(i) },
                            // Bug fix — see pillFocusRequesters' doc above: explicit targets for every pill,
                            // not just the two edges, so a fast D-pad burst can never outrun native search
                            // and escape to the AppBar avatar. No-op at both true ends of the row.
                            onLeft = if (i == 0) {{ }} else {{ runCatching { pillFocusRequesters[i - 1].requestFocus() } }},
                            onRight = if (i == seasons.lastIndex) {{ }} else {{ runCatching { pillFocusRequesters[i + 1].requestFocus() } }},
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
                        .background(
                            if (isSelected) colors.accent else colors.surfaceVariant,
                            pillShape,
                        )
                        .border(borderWidth, borderColor, pillShape)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // R150/R151: complete → ✓ badge; partial (1..n-1 watched) → w/N count badge + sliver;
                    // none watched → plain pill. Empty overlay (watchedSeasons/watchedCounts both empty) shows nothing.
                    val isComplete = seasons[i].index in watchedSeasons
                    val watchedCount = watchedCounts[seasons[i].index] ?: 0
                    val total = seasons[i].episodes.size
                    val isPartial = !isComplete && watchedCount > 0 && total > 0
                    // On the selected (accent-filled) pill the badge/sliver invert to stay legible, mirroring
                    // the design's focused-pill inversion (this app's "selected" state is the highlighted one).
                    val badgeBg    = if (isSelected) colors.onAccent.copy(alpha = 0.18f) else colors.progressBg
                    val badgeText  = if (isSelected) colors.onAccent else colors.text
                    val sliverColor = if (isSelected) colors.onAccent else colors.progressFill

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = seasons[i].name,
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
                                Text(
                                    text = "✓",
                                    color = colors.background,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = sora,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
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
        }
    }
}
