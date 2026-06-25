package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.EpisodeCard
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.SeasonPicker
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.EdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SeriesDetail

@Composable
fun SeriesDetailScreen(
    itemId: String,
    store: SeriesDetailStore,
    onBack: () -> Unit,
    onPlay: (EpisodePlayContext) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(itemId) { store.load(itemId) }
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is SeriesDetailState.Loading -> DetailLoadingShell()
            is SeriesDetailState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is SeriesDetailState.Loaded -> SeriesDetailLoaded(s.detail, onBack, onPlay, onRelatedSelect)

        }
    }
}

private fun buildEpisodeContext(
    detail: SeriesDetail,
    seasonIdx: Int,
    episodes: List<Episode>,
    epId: String,
): EpisodePlayContext {
    val sNum = detail.seasons.getOrNull(seasonIdx)?.index ?: (seasonIdx + 1)
    val epIdx = episodes.indexOfFirst { it.id == epId }.coerceAtLeast(0)
    val ep    = episodes.getOrElse(epIdx) { episodes[0] }
    val nextEp = episodes.getOrNull(epIdx + 1)
    return EpisodePlayContext(
        episodeId    = epId,
        episodeTitle = ep.title,
        kicker       = "S$sNum · E${ep.episodeNumber}",
        nextEpId     = nextEp?.id,
        nextEpLabel  = nextEp?.let { "S$sNum · E${it.episodeNumber}" },
        nextEpTitle  = nextEp?.title,
        episodes     = episodes.mapIndexed { i, e ->
            PlayerEpisodeEntry(
                id            = e.id,
                n             = e.episodeNumber,
                title         = e.title,
                kicker        = "S$sNum · E${e.episodeNumber}",
                durationLabel = if (e.runtime > 0) "${e.runtime}m" else "",
                progressPct   = e.playback.pct,
                watched       = e.playback.watched,
                stillUrl      = e.stillUrl,
            )
        },
        currentEpIndex = epIdx,
    )
}

@Composable
private fun SeriesDetailLoaded(
    detail: SeriesDetail,
    onBack: () -> Unit,
    onPlay: (EpisodePlayContext) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk
    val backdropGradient = remember(colors.background) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to colors.background.copy(alpha = 0.55f),
            1f to colors.background,
        )
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    // Full-bleed hero fills the first screenful. Whenever the actions row holds focus — on entry and
    // when focus returns up from the season picker / episode rail — snap the page to the top so the
    // full hero re-frames instead of stranding at the Resume button (R45).
    val heroHeight = if (containerH > 0) with(density) { containerH.toDp() } else 540.dp

    // Default to the season that holds the resume episode, so Resume plays with the
    // correct title/episode-rail context (not always season 0).
    val initialSeasonIdx = remember(detail) {
        val rid = detail.progress.resumeEpisodeId
        if (rid != null)
            detail.seasons.indexOfFirst { s -> s.episodes.any { it.id == rid } }.takeIf { it >= 0 } ?: 0
        else 0
    }
    var selectedSeasonIdx by remember(detail) { mutableIntStateOf(initialSeasonIdx) }
    val currentSeason = detail.seasons.getOrNull(selectedSeasonIdx)
    val episodes: List<Episode> = currentSeason?.episodes ?: emptyList()

    // Entry focus only; movement between buttons, season picker, episode rail, cast and related
    // is native spatial traversal within the non-lazy verticalScroll column.
    val playFR = remember { FocusRequester() }

    // Resume episode index in current season
    val resumeEpIdx = episodes.indexOfFirst { it.id == detail.progress.resumeEpisodeId }
        .takeIf { it >= 0 } ?: 0

    LaunchedEffect(Unit) { runCatching { playFR.requestFocus() } }

    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeBringIntoViewSpec) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Full-bleed hero: title · meta · progress · synopsis · resume · actions overlaid in the lower third.
        Box(modifier = Modifier.fillMaxWidth().height(heroHeight)) {
            val backdropUrl = detail.card.backdropUrl ?: detail.card.posterUrl
            if (backdropUrl != null) {
                RemoteImage(
                    url = backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    alignment = RaviloDimens.heroBackdropAlignment,
                )
            } else {
                Box(modifier = Modifier.matchParentSize().background(colors.surfaceVariant))
            }
            Box(modifier = Modifier.matchParentSize().background(backdropGradient))
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.6f)
                    .padding(start = RaviloDimens.heroBodyStart, bottom = 44.dp, end = 24.dp),
            ) {
                Text(
                    text = detail.card.title,
                    color = colors.text,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-0.5).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = remember(detail.card.year, detail.card.genre) {
                    listOfNotNull(detail.card.year?.toString(), detail.card.genre).joinToString(" · ")
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta, color = colors.textSecondary, fontSize = 15.sp)
                }
                val p = detail.progress
                if (p.totalCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${p.watchedCount} of ${p.totalCount} episodes watched",
                        color = colors.textDim, fontSize = 13.sp,
                    )
                }
                detail.synopsis?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                detail.progress.resumeLabel?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) scope.launch { scrollState.animateScrollTo(0) }
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val resumeEpId = detail.progress.resumeEpisodeId
                    // Season-aware "SxEy" for the button (no episode title — that shows above already).
                    // Resolved from the full season list so it's correct regardless of the selected season.
                    val resumeShort = resumeEpId?.let { rid ->
                        val sIdx = detail.seasons.indexOfFirst { s -> s.episodes.any { it.id == rid } }
                        val ep = detail.seasons.getOrNull(sIdx)?.episodes?.firstOrNull { it.id == rid }
                        if (sIdx >= 0 && ep != null) "S${detail.seasons[sIdx].index}E${ep.episodeNumber}"
                        else ep?.let { "E${it.episodeNumber}" }
                    } ?: "E${resumeEpIdx + 1}"
                    val playLabel = if (resumeEpId != null && detail.progress.watchedCount < detail.progress.totalCount)
                        "${str("action.resume")} · $resumeShort"
                    else "${str("action.play")} · E1"
                    RaviloButton(
                        label = playLabel,
                        focusRequester = playFR,
                        style = ButtonStyle.PRIMARY,
                        onSelect = {
                            val epId = resumeEpId ?: episodes.firstOrNull()?.id
                            if (epId != null) onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, epId))
                        },
                    )
                    RaviloButton(
                        label = "+ ${str("nav.my_list")}",
                        style = ButtonStyle.GHOST,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // Season picker
        if (detail.seasons.size > 1) {
            SeasonPicker(
                seasons = detail.seasons,
                selectedIndex = selectedSeasonIdx,
                onSelect = { selectedSeasonIdx = it },
            )
            Spacer(Modifier.height(16.dp))
        }

        // Episode rail
        if (episodes.isNotEmpty()) {
            Text(
                str("detail.episodes"),
                color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH),
            )
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
            ) {
                items(episodes.size, key = { i -> episodes[i].id }) { i ->
                    val ep = episodes[i]
                    EpisodeCard(
                        episode = ep,
                        isResumeEpisode = ep.id == detail.progress.resumeEpisodeId,
                        onSelect = { onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, ep.id)) },
                    )
                }
            }
        }

        // Cast row
        if (detail.cast.isNotEmpty()) {
            Spacer(Modifier.height(RaviloDimens.rowGap))
            Text(str("detail.cast"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH))
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
            ) {
                items(detail.cast.size, key = { i -> detail.cast[i].id }) { i ->
                    CastCircle(person = detail.cast[i])
                }
            }
        }

        // More Like This
        if (detail.related.isNotEmpty()) {
            Spacer(Modifier.height(RaviloDimens.rowGap))
            Text(str("section.related"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH))
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
            ) {
                items(detail.related.size, key = { i -> detail.related[i].id }) { i ->
                    val card = detail.related[i]
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        watched = card.watched,
                        onSelect = { onRelatedSelect(card) },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
    }
}
