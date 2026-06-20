package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.EpisodeCard
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.SeasonPicker
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.FocusRow
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SeriesDetail

@Composable
fun SeriesDetailScreen(
    itemId: String,
    store: SeriesDetailStore,
    onBack: () -> Unit,
    onPlay: (episodeId: String) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(itemId) { store.load(itemId) }
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is SeriesDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = colors.textSecondary, fontSize = 16.sp)
            }
            is SeriesDetailState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is SeriesDetailState.Loaded -> SeriesDetailLoaded(s.detail, onBack, onPlay, onRelatedSelect)
        }
    }
}

@Composable
private fun SeriesDetailLoaded(
    detail: SeriesDetail,
    onBack: () -> Unit,
    onPlay: (episodeId: String) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    val backdropGradient = remember(colors.background) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to colors.background.copy(alpha = 0.5f),
            1f to colors.background,
        )
    }
    val scrollState = rememberScrollState()

    var selectedSeasonIdx by remember { mutableIntStateOf(0) }
    val currentSeason = detail.seasons.getOrNull(selectedSeasonIdx)
    val episodes: List<Episode> = currentSeason?.episodes ?: emptyList()

    val playFR = remember { FocusRequester() }
    val myListFR = remember { FocusRequester() }

    val seasonFRs = remember(detail.seasons.size) {
        List(detail.seasons.size) { FocusRequester() }
    }
    var focusedSeasonIdx by remember { mutableIntStateOf(0) }

    val castFR = remember(detail.cast.size) { FocusRow(maxOf(detail.cast.size, 1)) }
    val episodeFR = remember(episodes.size) { FocusRow(maxOf(episodes.size, 1)) }
    val relatedFR = remember(detail.related.size) { FocusRow(maxOf(detail.related.size, 1)) }

    // Resume episode index in current season
    val resumeEpIdx = episodes.indexOfFirst { it.id == detail.progress.resumeEpisodeId }
        .takeIf { it >= 0 } ?: 0

    LaunchedEffect(Unit) { playFR.requestFocus() }

    // When season changes, reset episode focus
    LaunchedEffect(selectedSeasonIdx) { episodeFR.focused = 0 }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Hero band
        Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            val backdropUrl = detail.card.backdropUrl ?: detail.card.posterUrl
            if (backdropUrl != null) {
                RemoteImage(url = backdropUrl, contentDescription = null, modifier = Modifier.matchParentSize())
            } else {
                Box(modifier = Modifier.matchParentSize().background(colors.surfaceVariant))
            }
            Box(modifier = Modifier.matchParentSize().background(backdropGradient))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(40.dp, 40.dp)) {
                Text(detail.card.title, color = colors.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val meta = remember(detail.card.year, detail.card.genre) {
                    listOfNotNull(detail.card.year?.toString(), detail.card.genre).joinToString(" · ")
                }
                if (meta.isNotEmpty()) Text(meta, color = colors.textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                // Watched overview
                val p = detail.progress
                if (p.totalCount > 0) {
                    Text(
                        "${p.watchedCount} of ${p.totalCount} episodes watched",
                        color = colors.textSecondary, fontSize = 13.sp,
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 40.dp)) {
            // Synopsis
            detail.synopsis?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = colors.textSecondary, fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            // Up Next label
            detail.progress.resumeLabel?.let {
                Text(it, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val resumeEpId = detail.progress.resumeEpisodeId
                val playLabel = if (resumeEpId != null && detail.progress.watchedCount < detail.progress.totalCount)
                    "Resume · ${detail.progress.resumeLabel ?: "E${resumeEpIdx + 1}"}"
                else "Play · E1"
                RaviloButton(
                    label = playLabel,
                    focusRequester = playFR,
                    style = ButtonStyle.PRIMARY,
                    onRight = { myListFR.requestFocus() },
                    onDown = {
                        when {
                            detail.seasons.size > 1 -> seasonFRs[focusedSeasonIdx].requestFocus()
                            episodes.isNotEmpty()   -> episodeFR.requestFocus()
                            detail.related.isNotEmpty() -> relatedFR.requestFocus()
                        }
                    },
                    onSelect = {
                        val epId = resumeEpId ?: episodes.firstOrNull()?.id
                        if (epId != null) onPlay(epId)
                    },
                )
                RaviloButton(
                    label = "+ My List",
                    focusRequester = myListFR,
                    style = ButtonStyle.GHOST,
                    onLeft = { playFR.requestFocus() },
                    onDown = {
                        when {
                            detail.seasons.size > 1 -> seasonFRs[focusedSeasonIdx].requestFocus()
                            episodes.isNotEmpty()   -> episodeFR.requestFocus()
                            detail.related.isNotEmpty() -> relatedFR.requestFocus()
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Season picker
        if (detail.seasons.size > 1) {
            SeasonPicker(
                seasons = detail.seasons,
                selectedIndex = selectedSeasonIdx,
                focusedIndex = focusedSeasonIdx,
                focusRequesters = seasonFRs,
                onSelect = { selectedSeasonIdx = it },
                onLeft  = { i -> if (i > 0) { focusedSeasonIdx = i - 1; seasonFRs[i - 1].requestFocus() } },
                onRight = { i -> if (i < detail.seasons.lastIndex) { focusedSeasonIdx = i + 1; seasonFRs[i + 1].requestFocus() } },
                onDown  = { episodeFR.requestFocus() },
            )
            Spacer(Modifier.height(16.dp))
        }

        // Episode rail
        if (episodes.isNotEmpty()) {
            Text(
                "Episodes",
                color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(episodes.size, key = { i -> episodes[i].id }) { i ->
                    val ep = episodes[i]
                    val isResumeEp = ep.id == detail.progress.resumeEpisodeId
                    EpisodeCard(
                        episode = ep,
                        focusRequester = episodeFR.requesters[i],
                        onFocused = { episodeFR.focused = i },
                        onLeft  = { episodeFR.moveLeft() },
                        onRight = { episodeFR.moveRight() },
                        onUp    = {
                            if (detail.seasons.size > 1) seasonFRs[focusedSeasonIdx].requestFocus()
                            else playFR.requestFocus()
                        },
                        onDown  = {
                            when {
                                detail.cast.isNotEmpty()    -> castFR.requestFocus()
                                detail.related.isNotEmpty() -> relatedFR.requestFocus()
                            }
                        },
                        onSelect = { onPlay(ep.id) },
                    )
                }
            }
        }

        // Cast row
        if (detail.cast.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("Cast", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp))
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(detail.cast.size, key = { i -> detail.cast[i].id }) { i ->
                    CastCircle(
                        person = detail.cast[i],
                        focusRequester = castFR.requesters[i],
                        onFocused = { castFR.focused = i },
                        onLeft  = { castFR.moveLeft() },
                        onRight = { castFR.moveRight() },
                        onUp    = {
                            if (episodes.isNotEmpty()) episodeFR.requestFocus()
                            else playFR.requestFocus()
                        },
                        onDown  = { if (detail.related.isNotEmpty()) relatedFR.requestFocus() },
                    )
                }
            }
        }

        // More Like This
        if (detail.related.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("More Like This", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp))
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(detail.related.size, key = { i -> detail.related[i].id }) { i ->
                    val card = detail.related[i]
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        focusRequester = relatedFR.requesters[i],
                        onFocused = { relatedFR.focused = i },
                        onLeft  = { relatedFR.moveLeft() },
                        onRight = { relatedFR.moveRight() },
                        onUp    = {
                            when {
                                detail.cast.isNotEmpty()  -> castFR.requestFocus()
                                episodes.isNotEmpty()     -> episodeFR.requestFocus()
                                else                      -> playFR.requestFocus()
                            }
                        },
                        onSelect = { onRelatedSelect(card) },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
