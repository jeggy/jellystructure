package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode

/**
 * Phase R179 — a group of episodes sharing one physical file (`Episode.file`, e.g. a
 * `S01E01E02E03.mkv` release) renders as ONE combined card instead of [EpisodeCard] × N, so nothing
 * in the episode rail reads as missing. Visual/data layer only for this pass: the triptych, range
 * label, contained-episode list, and watched-state aggregate are all here; [onSelect] plays whichever
 * episode the caller resolved as the group's target (same shape as [EpisodeCard]'s onSelect) via the
 * existing per-episode playback path unchanged.
 *
 * Deferred (tracked as follow-up, not silently dropped — the real content driving this phase has no
 * chapter markers, so these don't block real usage yet): chapter-precise resume/seek within the file,
 * a dedicated "mark all N episodes watched" action (today's toggle marks the group's target episode
 * only, same as a normal card), and position-within-file → specific-episode resolution during playback.
 */
@Composable
fun MultiEpisodeCard(
    episodes: List<Episode>,
    focusRequester: FocusRequester? = null,
    isResumeGroup: Boolean = false,
    onSelect: (() -> Unit)? = null,
    playstateOverlay: Map<String, CardPlayState> = emptyMap(),
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { RaviloMotion.softSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.softSpring<Dp>() }
    val scale         by animateFloatAsState(if (focused) RaviloMotion.CARD_FOCUS_SCALE else 1f, focusSpec, label = "mepScale")
    val borderWidth   by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "mepBorder")
    val glowElevation by animateDpAsState(if (focused) 20.dp else 0.dp, dpSpec, label = "mepShadow")
    val cardShape = remember { RoundedCornerShape(12.dp) }

    val ordered = remember(episodes) { episodes.sortedBy { it.partIndex } }
    val first = ordered.first()
    val last = ordered.last()
    val totalRuntime = ordered.sumOf { it.runtime }

    val watchedFlags = ordered.map { ep -> playstateOverlay[ep.id]?.played ?: ep.playback?.watched ?: false }
    val allWatched = watchedFlags.isNotEmpty() && watchedFlags.all { it }
    val watchedCount = watchedFlags.count { it }
    val groupPct = if (ordered.isNotEmpty()) watchedCount.toFloat() / ordered.size else 0f
    val pct by animateFloatAsState(groupPct, label = "mepProgressBar")

    val upNextGradient = remember(colors.accent, colors.accentSecondary) { colors.accentGradient }

    Box(
        modifier = Modifier
            .width(320.dp)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                shadowElevation = glowElevation.toPx()
                shape = cardShape
                clip = true
                ambientShadowColor = colors.focusGlow
                spotShadowColor = colors.focusGlow
            }
            .background(colors.card)
            .border(borderWidth, colors.focusRing, cardShape)
            .alpha(if (allWatched) 0.62f else 1f),
    ) {
        // Triptych still — full-width 16:9, up to 3 panels from the group's own episode stills.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.surface),
        ) {
            val panels = ordered.take(3)
            Row(modifier = Modifier.matchParentSize()) {
                panels.forEachIndexed { i, ep ->
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val stillUrl = ep.stillUrl
                        if (stillUrl != null) {
                            RemoteImage(url = stillUrl, contentDescription = ep.title, modifier = Modifier.matchParentSize())
                        }
                        Text(
                            text = "${ep.episodeNumber}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = spaceGrotesk,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        )
                    }
                    // Skewed seam between panels (matches the admin mockup's ~8° tilt).
                    if (i < panels.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxWidth()
                                .rotate(8f)
                                .background(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }
            }

            // Duration badge (top-end) — combined runtime.
            if (totalRuntime > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("${totalRuntime}m", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = sora)
                }
            }

            if (isResumeGroup) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(upNextGradient, RoundedCornerShape(topEnd = 8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("UP NEXT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = sora, letterSpacing = 0.5.sp)
                }
            }

            if (allWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.badgeWatched)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else if (groupPct > 0f) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.progressBg)) {
                    Box(modifier = Modifier.fillMaxWidth(pct).height(4.dp).background(colors.progressFill))
                }
            }
        }

        // Text area — range label, "N episodes · 1 file · Xm" line, then a compact per-episode list.
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = str("up.episodes_range", mapOf("a" to "${first.episodeNumber}", "b" to "${last.episodeNumber}")),
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = str("up.episodes_one_file", mapOf("n" to "${ordered.size}", "mins" to "$totalRuntime")),
                color = colors.textDim,
                fontSize = 13.sp,
                fontFamily = sora,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            ordered.take(3).forEach { ep ->
                Text(
                    text = "E${ep.episodeNumber} · ${ep.title}",
                    color = colors.textDim,
                    fontSize = 12.sp,
                    fontFamily = sora,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}
