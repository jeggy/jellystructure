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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.AudioSubtitleFlagLine
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.EpisodeCard
import dev.jellystructure.ravilo.ui.components.DetailSynopsis
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.SeasonPicker
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TitleLogoOrText
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import dev.jellystructure.shared.tv.CardPlayState
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
            is SeriesDetailState.Loading -> DetailLoadingShell()
            is SeriesDetailState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is SeriesDetailState.Loaded -> SeriesDetailLoaded(
                detail = s.detail,
                overlay = overlay,
                onBack = onBack,
                onPlay = onPlay,
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

private fun buildEpisodeContext(
    detail: SeriesDetail,
    seasonIdx: Int,
    episodes: List<Episode>,
    epId: String,
    overlay: Map<String, CardPlayState> = emptyMap(),
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
            // R84: prefer overlay playstate; fall back to 0f/false (catalog carries null from R83)
            val ps = overlay[e.id]
            PlayerEpisodeEntry(
                id            = e.id,
                n             = e.episodeNumber,
                title         = e.title,
                kicker        = "S$sNum · E${e.episodeNumber}",
                durationLabel = if (e.runtime > 0) "${e.runtime}m" else "",
                progressPct   = ps?.playedPct ?: e.playback?.pct ?: 0f,
                watched       = ps?.played ?: e.playback?.watched ?: false,
                stillUrl      = e.stillUrl,
            )
        },
        currentEpIndex = epIdx,
    )
}

@Composable
private fun SeriesDetailLoaded(
    detail: SeriesDetail,
    overlay: Map<String, CardPlayState>,
    onBack: () -> Unit,
    onPlay: (EpisodePlayContext) -> Unit,
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
    // R109: LazyColumn so below-hero rails (season picker, episodes, cast, related) compose only when
    // scrolled into view — first paint is hero-only (supersedes R107's timed defer).
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    val heroHeight = if (containerH > 0) with(density) { containerH.toDp() } else 540.dp

    // R84: key on series id (not whole detail object) so overlay hydration never resets the season picker
    val initialSeasonIdx = remember(detail.card.id) { 0 }
    var selectedSeasonIdx by remember(detail.card.id) { mutableIntStateOf(initialSeasonIdx) }
    val currentSeason = detail.seasons.getOrNull(selectedSeasonIdx)
    val episodes: List<Episode> = currentSeason?.episodes ?: emptyList()

    // R84: derive all progress values from the phase-2 overlay (empty map = not yet loaded)
    val allEps = remember(detail) { detail.seasons.flatMap { it.episodes } }
    val overlayLoaded = overlay.isNotEmpty()
    // R107: memoize the O(N)-over-all-episodes scans so they don't re-run on every recomposition
    // (notably the phase-2 overlay re-emit) — only when the episode set or overlay actually changes.
    val watchedCount = remember(allEps, overlay) { allEps.count { ep -> overlay[ep.id]?.played == true } }
    val resumeEpId: String? = remember(allEps, overlay) {
        allEps.firstOrNull { ep -> overlay[ep.id].let { ps -> ps != null && !ps.played && ps.resumeMs > 0 } }?.id
            ?: allEps.firstOrNull { ep -> overlay[ep.id]?.played != true }?.id
            ?: allEps.lastOrNull()?.id
    }

    val playFR = remember { FocusRequester() }
    val synopsisFR = remember { FocusRequester() }   // R135
    val seasonFirstFR = remember { FocusRequester() }   // R138
    val navBarFR = remember { FocusRequester() }

    val resumeEpIdx = episodes.indexOfFirst { it.id == resumeEpId }.takeIf { it >= 0 } ?: 0

    LaunchedEffect(Unit) { runCatching { playFR.requestFocus() } }

    // R79: appBarHeight + 24dp top inset so season picker / episode rail title isn't hidden under the bar.
    val detailBivSpec = rememberEdgeBringIntoViewSpec(peekDp = 60.dp, topInsetDp = RaviloDimens.appBarHeight + 24.dp)
    // R109: boolean derivedStateOf (notifies only on threshold cross) — no per-scroll-frame recompose.
    val appBarScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    val navItems = buildList {
        add(str("nav.home")); add(str("nav.movies")); add(str("nav.series"))
        if (discoverAvailable) add("Top 10")
        add(str("nav.my_list"))
    }

    Box(Modifier.fillMaxSize()) {
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides detailBivSpec) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // Full-bleed hero: title · meta · progress · synopsis · resume · actions overlaid in the lower third.
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
                        .fillMaxWidth(0.6f)
                        .padding(start = RaviloDimens.heroBodyStart, bottom = 44.dp, end = 24.dp),
                ) {
                    // R130: clearlogo when it loads, else the title as readable text.
                    TitleLogoOrText(
                        logoUrl = detail.logoUrl,
                        title = detail.card.title,
                        logoModifier = Modifier.height(80.dp).widthIn(max = 360.dp),
                    )
                    val meta = remember(detail.card.year, detail.card.genre) {
                        listOfNotNull(detail.card.year?.toString(), detail.card.genre).joinToString(" · ")
                    }
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(meta, color = colors.textSecondary, fontSize = 15.sp)
                    }
                    if (detail.audioLanguages.isNotEmpty() || detail.subtitleLanguages.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        AudioSubtitleFlagLine(detail.audioLanguages, detail.subtitleLanguages)  // R134: one line
                    }
                    // R84: reserve the watched-count line from first paint; fade in when overlay lands
                    // (no-flicker rule: the text line occupies space even before overlay arrives).
                    val progressAlpha by animateFloatAsState(
                        targetValue = if (overlayLoaded && allEps.isNotEmpty()) 1f else 0f,
                        label = "watchedCountAlpha",
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$watchedCount of ${allEps.size} episodes watched",
                        color = colors.textDim,
                        fontSize = 13.sp,
                        modifier = Modifier.alpha(progressAlpha),
                    )
                    detail.synopsis?.let {
                        Spacer(Modifier.height(10.dp))
                        DetailSynopsis(   // R135: focusable; SELECT expands the full text inline
                            text = it,
                            collapsedMaxLines = 2,
                            focusRequester = synopsisFR,
                            onUp = { navBarFR.requestFocus() },
                            onDown = { runCatching { playFR.requestFocus() } },
                        )
                    }
                    // Resume kicker: derived from overlay; always reserves a line so synopsis doesn't shift
                    val resumeEpEntry = if (overlayLoaded) allEps.firstOrNull { ep ->
                        overlay[ep.id].let { ps -> ps != null && !ps.played && ps.resumeMs > 0 }
                    } else null
                    val resumeKicker = resumeEpEntry?.let { ep ->
                        val sIdx = detail.seasons.indexOfFirst { s -> s.episodes.any { it.id == ep.id } }
                        val sNum = detail.seasons.getOrNull(sIdx)?.index
                        if (sNum != null) "S${sNum}E${ep.episodeNumber} · ${ep.title}" else ep.title
                    }
                    val kickerAlpha by animateFloatAsState(
                        targetValue = if (resumeKicker != null) 1f else 0f,
                        label = "resumeKickerAlpha",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = resumeKicker ?: "",
                        color = colors.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.alpha(kickerAlpha),
                    )
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
                            // R79/R135/R138: UP → synopsis (or AppBar); DOWN → smoothly scroll the season
                            // picker into view (composing the lazy item) and land on the selected season, so
                            // native traversal can't skip the not-yet-composed picker and jump to episodes.
                            .onKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (ev.key) {
                                    Key.DirectionUp -> {
                                        if (detail.synopsis != null) runCatching { synopsisFR.requestFocus() }
                                        else navBarFR.requestFocus()
                                        true
                                    }
                                    Key.DirectionDown -> if (detail.seasons.size > 1) {
                                        scope.launch {
                                            runCatching { listState.animateScrollToItem(1) }   // hero=0, seasons=1
                                            runCatching { seasonFirstFR.requestFocus() }        // BIV reveals it below the AppBar
                                        }
                                        true
                                    } else false
                                    else -> false
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // R84: overlay-derived resume; neutral "Play · E1" until playstate lands
                        val resumeShort = resumeEpId?.let { rid ->
                            val sIdx = detail.seasons.indexOfFirst { s -> s.episodes.any { it.id == rid } }
                            val ep = detail.seasons.getOrNull(sIdx)?.episodes?.firstOrNull { it.id == rid }
                            if (sIdx >= 0 && ep != null) "S${detail.seasons[sIdx].index}E${ep.episodeNumber}"
                            else ep?.let { "E${it.episodeNumber}" }
                        }
                        val hasResume = overlayLoaded && resumeEpId != null && watchedCount < allEps.size
                        val playLabel = if (hasResume && resumeShort != null)
                            "${str("action.resume")} · $resumeShort"
                        else "${str("action.play")} · E1"
                        // Fixed min-width: sized for the longest "Resume · SNNEN" label so swapping
                        // Play→Resume never shifts the "My List" button (no-flicker rule).
                        RaviloButton(
                            label = playLabel,
                            focusRequester = playFR,
                            style = ButtonStyle.PRIMARY,
                            modifier = Modifier.widthIn(min = 220.dp),
                            onSelect = {
                                val epId = resumeEpId ?: episodes.firstOrNull()?.id
                                if (epId != null) onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, epId, overlay))
                            },
                        )
                        RaviloButton(
                            label = "+ ${str("nav.my_list")}",
                            style = ButtonStyle.GHOST,
                        )
                    }
                }
            }
            } // item: hero

            // Season picker — own lazy item (R109: composes when scrolled into view).
            if (detail.seasons.size > 1) item(key = "seasons") {
                Column {
                    Spacer(Modifier.height(28.dp))
                    SeasonPicker(
                        seasons = detail.seasons,
                        selectedIndex = selectedSeasonIdx,
                        onSelect = { selectedSeasonIdx = it },
                        firstFocusRequester = seasonFirstFR,   // R138
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Episode rail — own lazy item.
            if (episodes.isNotEmpty()) item(key = "episodes") {
                Column {
                    if (detail.seasons.size <= 1) Spacer(Modifier.height(28.dp))
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
                                // R84: overlay-driven; no "UP NEXT" ribbon until playstate arrives
                                isResumeEpisode = overlayLoaded && ep.id == resumeEpId,
                                playstateOverride = overlay[ep.id],
                                onSelect = { onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, ep.id, overlay)) },
                            )
                        }
                    }
                }
            }

            // Cast row — own lazy item.
            if (detail.cast.isNotEmpty()) item(key = "cast") {
                Column {
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
            }

            // More Like This — own lazy item.
            if (detail.related.isNotEmpty()) item(key = "related") {
                Column {
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
    }
}
