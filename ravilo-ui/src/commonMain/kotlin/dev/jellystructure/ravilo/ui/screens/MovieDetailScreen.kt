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
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
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
    val spaceGrotesk = SpaceGrotesk
    val backdropGradient = remember(colors.background) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to colors.background.copy(alpha = 0.55f),
            1f to colors.background,
        )
    }
    val scrollState = rememberScrollState()

    val playFR = remember { FocusRequester() }
    val myListFR = remember { FocusRequester() }
    val castFR = remember(detail.cast.size) { FocusRow(maxOf(detail.cast.size, 1)) }
    val relatedFR = remember(detail.related.size) { FocusRow(maxOf(detail.related.size, 1)) }

    LaunchedEffect(Unit) { playFR.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Hero band
        Box(modifier = Modifier.fillMaxWidth().height(620.dp)) {
            val backdropUrl = detail.card.backdropUrl ?: detail.card.posterUrl
            if (backdropUrl != null) {
                RemoteImage(
                    url = backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    alignment = Alignment.TopCenter,
                )
            } else {
                Box(modifier = Modifier.matchParentSize().background(colors.surfaceVariant))
            }
            Box(modifier = Modifier.matchParentSize().background(backdropGradient))
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RaviloDimens.heroBodyStart, bottom = RaviloDimens.detailBodyBot, end = 40.dp),
            ) {
                Text(
                    text = detail.card.title,
                    color = colors.text,
                    fontSize = 72.sp,
                    lineHeight = 84.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-2).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                val meta = remember(detail.card.year, detail.runtime, detail.card.genre, detail.card.rating) {
                    listOfNotNull(
                        detail.card.year?.toString(),
                        if (detail.runtime > 0) "${detail.runtime} min" else null,
                        detail.card.genre,
                        detail.card.rating,
                    ).joinToString(" · ")
                }
                if (meta.isNotEmpty()) {
                    Text(meta, color = colors.textSecondary, fontSize = 18.sp)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = RaviloDimens.screenPadH)) {
            // Synopsis
            detail.synopsis?.let {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = it,
                    color = colors.textSecondary,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(28.dp))
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
                    onDown = {
                        when {
                            detail.cast.isNotEmpty()    -> castFR.requestFocus()
                            detail.related.isNotEmpty() -> relatedFR.requestFocus()
                        }
                    },
                    onRight = { myListFR.requestFocus() },
                    onSelect = { onPlay(detail.card) },
                )
                RaviloButton(
                    label = "+ My List",
                    focusRequester = myListFR,
                    style = ButtonStyle.GHOST,
                    onLeft = { playFR.requestFocus() },
                    onDown = {
                        when {
                            detail.cast.isNotEmpty()    -> castFR.requestFocus()
                            detail.related.isNotEmpty() -> relatedFR.requestFocus()
                        }
                    },
                )
            }
        }

        // Cast row
        if (detail.cast.isNotEmpty()) {
            Spacer(Modifier.height(RaviloDimens.rowGap))
            Text("Cast", color = colors.text, fontSize = 29.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH))
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
            LazyRow(
                contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
            ) {
                items(detail.cast.size, key = { i -> detail.cast[i].id }) { i ->
                    CastCircle(
                        person = detail.cast[i],
                        focusRequester = castFR.requesters[i],
                        onFocused = { castFR.focused = i },
                        onLeft  = { castFR.moveLeft() },
                        onRight = { castFR.moveRight() },
                        onUp    = { playFR.requestFocus() },
                        onDown  = { if (detail.related.isNotEmpty()) relatedFR.requestFocus() },
                    )
                }
            }
        }

        // More Like This
        if (detail.related.isNotEmpty()) {
            Spacer(Modifier.height(RaviloDimens.rowGap))
            Text("More Like This", color = colors.text, fontSize = 29.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH))
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
            LazyRow(
                contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
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
                        onUp    = { if (detail.cast.isNotEmpty()) castFR.requestFocus() else playFR.requestFocus() },
                        onSelect = { onRelatedSelect(card) },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
