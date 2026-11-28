package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ArrowRow
import dev.jellystructure.ravilo.ui.components.DetailHeroScrims
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TitleLogoOrText
import dev.jellystructure.ravilo.ui.downloads.isDownloaded
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.raviloItemSpacing
import dev.jellystructure.ravilo.ui.theme.raviloTrackPadV
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.withTimeoutOrNull

/** DEB-5841 (FR-3) — how long the page waits for the film's list before it gives up and closes. */
internal const val POST_FILM_FETCH_TIMEOUT_MS = 3_000L

/**
 * DEB-5841 (FR-1..FR-5) — what opens when a finished film has something to offer. It holds no stream and no player, starts no play and
 * counts down nothing (FR-2). [related] is the film page's own list when the play began there; null means [loadRelated] makes the one
 * `GET /api/tv/movie/{id}`, after the player has closed. No picks — an empty list, all watched, a failed fetch or no answer within
 * [POST_FILM_FETCH_TIMEOUT_MS] — calls [onClose] and the page never shows (FR-3). A tile calls [onSelect]: it opens that title's page, it
 * does not play it (FR-4). Back is the host's own (FR-5).
 */
@Composable
internal fun PostFilmScreen(
    playedId: String,
    title: String,
    logoUrl: String?,
    logoInk: String?,
    backdropUrl: String?,
    related: List<MediaCard>?,
    loadRelated: suspend () -> List<MediaCard>,
    onSelect: (MediaCard) -> Unit,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var picks by remember(playedId) { mutableStateOf(related?.let { postFilmPicks(it, playedId) }) }
    LaunchedEffect(playedId) {
        val known = picks ?: withTimeoutOrNull(POST_FILM_FETCH_TIMEOUT_MS) {
            runCatching { loadRelated() }.getOrNull()?.let { postFilmPicks(it, playedId) }
        }
        if (known.isNullOrEmpty()) onClose() else picks = known
    }
    val firstTile = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(picks) { if (!picks.isNullOrEmpty()) requestFocusRetrying(scope, firstTile) }
    val handset = LocalHandset.current
    val backLabel = str("action.back")

    Box(Modifier.fillMaxSize().background(colors.surfaceVariant).testTag("post-film")) {
        if (backdropUrl != null) RemoteImage(
            url = backdropUrl, contentDescription = null, modifier = Modifier.matchParentSize(),
            alignment = RaviloDimens.heroBackdropAlignment,
        )
        DetailHeroScrims()
        val shown = picks
        if (!shown.isNullOrEmpty()) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 44.dp)) {
            Column(Modifier.padding(horizontal = raviloHPad)) {
                Text(
                    "${str("player.end_of_movie")} · $title".uppercase(), color = colors.textSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1,
                    modifier = Modifier.testTag("post-film-kicker"),
                )
                Spacer(Modifier.height(8.dp))
                TitleLogoOrText(
                    logoUrl = logoUrl, logoInk = logoInk, title = title,
                    logoModifier = Modifier.height(80.dp).widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(str("section.related"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            ArrowRow(
                contentPadding = PaddingValues(horizontal = raviloHPad, vertical = raviloTrackPadV),
                horizontalArrangement = Arrangement.spacedBy(raviloItemSpacing),
            ) {
                items(shown.size, key = { i -> shown[i].id }) { i ->
                    val card = shown[i]
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        watched = card.watched,
                        upcomingLabel = card.upcomingEpisode,
                        upcomingAirUtc = card.upcomingAirUtc,
                        qualityBadge = card.qualityBadge,
                        downloaded = isDownloaded(card.id),
                        focusRequester = if (i == 0) firstTile else null,
                        onSelect = { onSelect(card) },
                    )
                }
            }
        }
        // The phone has no Back key: a visible back circle. The TV draws none, and a desktop window has its own header (owner, 2026-11-27).
        if (handset && onBack != null) Box(
            Modifier.align(Alignment.TopStart).padding(16.dp).size(44.dp)
                .background(colors.surfaceVariant, CircleShape)
                .semantics { contentDescription = backLabel }
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = colors.text, fontSize = 26.sp) }
    }
}
