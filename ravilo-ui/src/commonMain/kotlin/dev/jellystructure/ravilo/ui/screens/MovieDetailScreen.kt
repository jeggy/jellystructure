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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.AudioSubtitleFlagLine
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.CertBadge
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.DetailSynopsis
import dev.jellystructure.ravilo.ui.components.ImdbChip
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TitleLogoOrText
import dev.jellystructure.ravilo.ui.components.TrailerOverlay
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail

@Composable
fun MovieDetailScreen(
    itemId: String,
    store: MovieDetailStore,
    onBack: () -> Unit,
    onPlay: (MediaCard) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
    displayName: String = "",
    onNavSelect: (Int) -> Unit = {},
    onProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    discoverAvailable: Boolean = false,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(itemId) { store.load(itemId) }
    val state by store.state.collectAsState()
    // R84: phase-2 overlay — empty map until /api/tv/playstate returns after the catalog paint
    val overlay by store.playstateOverlay.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is MovieDetailState.Loading -> DetailLoadingShell()
            is MovieDetailState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is MovieDetailState.Loaded -> MovieDetailLoaded(
                detail = s.detail,
                overlay = overlay,
                onBack = onBack,
                onPlay = onPlay,
                onMarkPlayed = { played -> store.setPlayed(played) },
                onRelatedSelect = onRelatedSelect,
                displayName = displayName,
                onNavSelect = onNavSelect,
                onProfile = onProfile,
                onSearch = onSearch,
                discoverAvailable = discoverAvailable,
            )
        }
    }
}

@Composable
private fun MovieDetailLoaded(
    detail: MovieDetail,
    overlay: Map<String, CardPlayState>,
    onBack: () -> Unit,
    onPlay: (MediaCard) -> Unit,
    onMarkPlayed: (Boolean) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onProfile: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    discoverAvailable: Boolean,
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
    // R109: LazyColumn so the below-hero rails (cast, related) compose only when scrolled into view —
    // the full-bleed hero is item 0 and fills the viewport, so on open nothing below it composes
    // (supersedes R107's timed defer; the eager off-screen composition was the detail-open hitch).
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    val heroHeight = if (containerH > 0) with(density) { containerH.toDp() } else 540.dp

    val playFR = remember { FocusRequester() }
    val synopsisFR = remember { FocusRequester() }   // R135
    val navBarFR = remember { FocusRequester() }
    val trailerFR = remember { FocusRequester() }   // R163
    var showTrailer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { playFR.requestFocus() } }

    // R79: appBarHeight + 24dp top inset so cast/related rows aren't hidden under the overlay bar.
    val detailBivSpec = rememberEdgeBringIntoViewSpec(peekDp = 60.dp, topInsetDp = RaviloDimens.appBarHeight + 24.dp)
    // R109: boolean derivedStateOf (notifies only on threshold cross) — avoids recomposing on every
    // scroll frame, unlike the prior `scrollState.value > 0` read at composition scope.
    val appBarScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    val navItems = raviloNavItems(discoverAvailable)

    Box(Modifier.fillMaxSize()) {
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides detailBivSpec) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // Full-bleed hero: title · meta · synopsis · actions overlaid in the lower third.
            item(key = "hero") {
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
                        // R145: full width on a phone (the TV 60% column wastes a narrow screen and
                        // wraps/cuts the title, flags, synopsis and buttons); TV keeps the 60% column.
                        .fillMaxWidth(if (LocalCompact.current) 1f else 0.6f)
                        .padding(start = raviloHPad, bottom = 44.dp, end = raviloHPad),
                ) {
                    // R130: clearlogo when it loads, else the title as readable text.
                    TitleLogoOrText(
                        logoUrl = detail.logoUrl,
                        title = detail.card.title,
                        logoModifier = Modifier.height(80.dp).widthIn(max = 360.dp),
                    )
                    val meta = remember(detail.card.year, detail.runtime, detail.card.genre) {
                        listOfNotNull(
                            detail.card.year?.toString(),
                            if (detail.runtime > 0) "${detail.runtime} min" else null,
                            detail.card.genre,
                        ).joinToString(" · ")
                    }
                    if (meta.isNotEmpty() || detail.ratingBadge != null || detail.imdbRating != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotEmpty()) Text(meta, color = colors.textSecondary, fontSize = 15.sp)
                            // Phase 106/R153: server-resolved age-rating badge.
                            if (detail.ratingBadge != null) {
                                if (meta.isNotEmpty()) Spacer(Modifier.width(10.dp))
                                CertBadge(detail.ratingBadge)
                            }
                            // R164: server-pushed IMDb rating chip, after the certification badge.
                            if (detail.imdbRating != null) {
                                if (meta.isNotEmpty() || detail.ratingBadge != null) Spacer(Modifier.width(10.dp))
                                ImdbChip(detail.imdbRating)
                            }
                            // R142: ✓ Watched chip when the movie is played.
                            if (overlay[detail.card.id]?.played == true) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "✓ ${str("action.watched")}",
                                    color = colors.badgeWatched,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    if (detail.audioLanguages.isNotEmpty() || detail.subtitleLanguages.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        AudioSubtitleFlagLine(detail.audioLanguages, detail.subtitleLanguages)  // R134: one line
                    }
                    detail.synopsis?.let {
                        Spacer(Modifier.height(10.dp))
                        DetailSynopsis(   // R135: focusable; SELECT expands the full text inline
                            text = it,
                            collapsedMaxLines = 3,
                            focusRequester = synopsisFR,
                            onUp = { navBarFR.requestFocus() },
                            onDown = { runCatching { playFR.requestFocus() } },
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        // R72: scroll(UserInput) wins over bring-into-view (Default priority) so
                        // focusing Play/Resume reliably reframes the full backdrop.
                        modifier = Modifier
                            .onFocusChanged {
                                // R72: focusing Play/Resume reframes the full backdrop. R115: only when the
                                // hero is actually scrolled — on open the list is already at the top (offset 0),
                                // so skip the competing scroll(UserInput) that otherwise fights bring-into-view
                                // mid-transition (the open transition was the jankiest pass on-device).
                                if (it.hasFocus && listState.firstVisibleItemScrollOffset > 0) scope.launch {
                                    listState.scroll(MutatePriority.UserInput) {
                                        scrollBy(-listState.firstVisibleItemScrollOffset.toFloat())
                                    }
                                }
                            }
                            // R79/R135: UP from actions → the focusable synopsis if present, else the AppBar.
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                    if (detail.synopsis != null) runCatching { synopsisFR.requestFocus() }
                                    else navBarFR.requestFocus()
                                    true
                                } else false
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // R84: phase-2 overlay drives Play/Resume label; safe to press before it arrives
                        val ps = overlay[detail.card.id]
                        val played = ps?.played == true
                        val isResume = ps != null && ps.resumeMs > 0 && !ps.played
                        val runtimeMs = detail.runtime.toLong() * 60_000L
                        val minsLeft = if (isResume && ps != null && runtimeMs > 0)
                            ((runtimeMs - ps.resumeMs) / 60_000L).toInt() else 0
                        val playLabel = when {
                            played -> str("action.play_again")          // R142
                            // Bug fix: detail.runtime is the item's metadata runtime (whole minutes,
                            // e.g. from TMDB/NFO) which can understate the actual media file's duration
                            // — when it does, minsLeft goes negative ("Resume · -1 min left"). The
                            // metadata is untrustworthy here, not the resume position, so drop the
                            // estimate rather than show a nonsensical or fabricated number.
                            isResume && minsLeft > 0 -> "${str("action.resume")} · $minsLeft min left"
                            isResume -> str("action.resume")
                            else -> str("action.play")
                        }
                        // Fixed min-width: sized for the longest "Resume · NN min left" label so
                        // swapping Play→Resume never shifts the adjacent buttons (no-flicker rule).
                        RaviloButton(
                            label = playLabel,
                            focusRequester = playFR,
                            style = ButtonStyle.PRIMARY,
                            modifier = Modifier.widthIn(min = 200.dp),
                            onSelect = { onPlay(detail.card) },
                        )
                        // R142: mark played / unplayed write-through. Re-renders from the server-returned state.
                        RaviloButton(
                            label = if (played) "✓ ${str("action.watched")}" else str("action.mark_watched"),
                            style = ButtonStyle.GHOST,
                            onSelect = { onMarkPlayed(!played) },
                        )
                        RaviloButton(
                            label = "+ ${str("nav.my_list")}",
                            style = ButtonStyle.GHOST,
                        )
                        // R163: only when Phase 130 ingested a usable trailer — never a dead affordance.
                        if (detail.trailer != null) {
                            RaviloButton(
                                label = "▷ ${str("action.trailer")}",
                                focusRequester = trailerFR,
                                style = ButtonStyle.GHOST,
                                onSelect = { showTrailer = true },
                            )
                        }
                    }
                }
            }
            } // item: hero

            // Cast row — its own lazy item, so it composes only when scrolled into view (R109).
            if (detail.cast.isNotEmpty()) item(key = "cast") {
                Column {
                    Spacer(Modifier.height(RaviloDimens.rowGap))
                    Text(str("detail.cast"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = spaceGrotesk, letterSpacing = (-0.3).sp,
                        modifier = Modifier.padding(horizontal = raviloHPad))
                    Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        items(detail.cast.size, key = { i -> detail.cast[i].id }) { i ->
                            CastCircle(person = detail.cast[i])
                        }
                    }
                }
            }

            // More Like This — own lazy item.
            if (detail.related.isNotEmpty()) item(key = "related") {
                Column {
                    Spacer(Modifier.height(RaviloDimens.rowGap))
                    Text(str("section.related"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = spaceGrotesk, letterSpacing = (-0.3).sp,
                        modifier = Modifier.padding(horizontal = raviloHPad))
                    Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        items(detail.related.size, key = { i -> detail.related[i].id }) { i ->
                            val card = detail.related[i]
                            Tile(
                                title = card.title,
                                posterUrl = card.posterUrl,
                                watched = card.watched,
                                upcomingLabel = card.upcomingEpisode,
                                onSelect = { onRelatedSelect(card) },
                            )
                        }
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(48.dp)) }
        }
        } // CompositionLocalProvider

        // R79: AppBar overlay — last child of the Box so it renders over the scroll content.
        AppBar(
            navItems = navItems,
            activeNav = -1,
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            onDown = { runCatching { playFR.requestFocus() } },
            userInitials = displayName.take(2).uppercase(),
            onProfile = onProfile,
            onSearch = onSearch,
            scrolled = appBarScrolled,
        )

        // R163: fullscreen embedded trailer, last child so it paints over the AppBar too.
        // Cross-module `val` properties (detail.trailer is declared in :shared) aren't smart-cast —
        // bind to a local val first.
        val trailer = detail.trailer
        if (showTrailer && trailer != null) {
            TrailerOverlay(
                trailer = trailer,
                title = detail.card.title,
                onClose = { showTrailer = false; runCatching { trailerFR.requestFocus() } },
            )
        }
    }
}
