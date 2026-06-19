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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.FocusRow
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail

@Composable
fun MovieDetailScreen(
    itemId: String,
    store: MovieDetailStore,
    onBack: () -> Unit,
    onPlay: (MediaCard) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(itemId) { store.load(itemId) }
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is MovieDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = colors.textSecondary, fontSize = 16.sp)
            }
            is MovieDetailState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is MovieDetailState.Loaded -> MovieDetailLoaded(s.detail, onBack, onPlay, onRelatedSelect)
        }
    }
}

@Composable
private fun MovieDetailLoaded(
    detail: MovieDetail,
    onBack: () -> Unit,
    onPlay: (MediaCard) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    val scrollState = rememberScrollState()

    val playFR = remember { FocusRequester() }
    val myListFR = remember { FocusRequester() }
    val castFR = remember(detail.cast.size) { FocusRow(maxOf(detail.cast.size, 1)) }
    val relatedFR = remember(detail.related.size) { FocusRow(maxOf(detail.related.size, 1)) }

    LaunchedEffect(Unit) { playFR.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Hero band
        Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            val backdropUrl = detail.card.backdropUrl ?: detail.card.posterUrl
            if (backdropUrl != null) {
                RemoteImage(
                    url = backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(modifier = Modifier.matchParentSize().background(colors.surfaceVariant))
            }
            Box(
                modifier = Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to colors.background.copy(alpha = 0.5f),
                        1f to colors.background,
                    )
                )
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(40.dp, 40.dp),
            ) {
                Text(detail.card.title, color = colors.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val meta = listOfNotNull(
                    detail.card.year?.toString(),
                    if (detail.runtime > 0) "${detail.runtime} min" else null,
                    detail.card.genre,
                    detail.card.rating,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = colors.textSecondary, fontSize = 14.sp)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 40.dp)) {
            // Synopsis
            detail.synopsis?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = colors.textSecondary, fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(24.dp))
            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val isResume = detail.playback.positionMs > 0 && !detail.playback.watched
                val minsLeft = if (detail.playback.durationMs > 0)
                    ((detail.playback.durationMs - detail.playback.positionMs) / 60_000L).toInt() else 0
                val playLabel = if (isResume) "Resume · ${minsLeft} min left" else "Play"
                RaviloButton(
                    label = playLabel,
                    focusRequester = playFR,
                    style = ButtonStyle.PRIMARY,
                    onDown = { if (detail.cast.isNotEmpty()) castFR.requestFocus() else relatedFR.requestFocus() },
                    onRight = { myListFR.requestFocus() },
                    onSelect = { onPlay(detail.card) },
                )
                RaviloButton(
                    label = "+ My List",
                    focusRequester = myListFR,
                    style = ButtonStyle.GHOST,
                    onLeft = { playFR.requestFocus() },
                    onDown = { if (detail.cast.isNotEmpty()) castFR.requestFocus() else relatedFR.requestFocus() },
                )
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
                items(detail.cast.size) { i -> CastCircle(detail.cast[i]) }
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
                items(detail.related.size) { i ->
                    val card = detail.related[i]
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        focusRequester = relatedFR.requesters[i],
                        onFocused = { relatedFR.focused = i },
                        onLeft  = { relatedFR.moveLeft() },
                        onRight = { relatedFR.moveRight() },
                        onUp    = { playFR.requestFocus() },
                        onSelect = { onRelatedSelect(card) },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
