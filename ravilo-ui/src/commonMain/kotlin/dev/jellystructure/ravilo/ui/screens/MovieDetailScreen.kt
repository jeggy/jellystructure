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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.i18n.str
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
            is MovieDetailState.Loading -> DetailLoadingShell()
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
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    // Full-bleed hero fills the first screenful so the first focusable (Play) is already on-screen —
    // the page opens at the top with no auto-scroll; Down from the actions scrolls into cast/related.
    val heroHeight = if (containerH > 0) with(density) { containerH.toDp() } else 540.dp

    val playFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFR.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Full-bleed hero: title · meta · synopsis · actions overlaid in the lower third.
        Box(modifier = Modifier.fillMaxWidth().height(heroHeight)) {
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
                val meta = remember(detail.card.year, detail.runtime, detail.card.genre, detail.card.rating) {
                    listOfNotNull(
                        detail.card.year?.toString(),
                        if (detail.runtime > 0) "${detail.runtime} min" else null,
                        detail.card.genre,
                        detail.card.rating,
                    ).joinToString(" · ")
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta, color = colors.textSecondary, fontSize = 15.sp)
                }
                detail.synopsis?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val isResume = detail.playback.positionMs > 0 && !detail.playback.watched
                    val minsLeft = if (detail.playback.durationMs > 0)
                        ((detail.playback.durationMs - detail.playback.positionMs) / 60_000L).toInt() else 0
                    val playLabel = if (isResume) "${str("action.resume")} · ${minsLeft} min left" else str("action.play")
                    RaviloButton(
                        label = playLabel,
                        focusRequester = playFR,
                        style = ButtonStyle.PRIMARY,
                        onSelect = { onPlay(detail.card) },
                    )
                    RaviloButton(
                        label = "+ ${str("nav.my_list")}",
                        style = ButtonStyle.GHOST,
                    )
                }
            }
        }

        // Cast row
        if (detail.cast.isNotEmpty()) {
            Spacer(Modifier.height(RaviloDimens.rowGap))
            Text(str("detail.cast"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = spaceGrotesk, letterSpacing = (-0.3).sp,
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
                fontFamily = spaceGrotesk, letterSpacing = (-0.3).sp,
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
                        onSelect = { onRelatedSelect(card) },
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
