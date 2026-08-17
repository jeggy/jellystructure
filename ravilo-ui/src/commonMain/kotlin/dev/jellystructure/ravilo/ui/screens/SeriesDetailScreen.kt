package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.AudioSubtitleFlagLine
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.CastCircle
import dev.jellystructure.ravilo.ui.components.CertBadge
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.EpisodeCard
import dev.jellystructure.ravilo.ui.components.DetailSynopsis
import dev.jellystructure.ravilo.ui.components.ImdbChip
import dev.jellystructure.ravilo.ui.components.MultiEpisodeCard
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.SeasonPicker
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
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
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
    // R190 §A — see MovieDetailScreen's identical parameter doc.
    onCastSelect: ((dev.jellystructure.shared.tv.Person, sourceTitle: String) -> Unit)? = null,
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
                onMarkEpisode = { epId, played -> store.setEpisodePlayed(epId, played) },
                onMarkFavorite = { favorite -> store.setFavorite(favorite) },
                onRelatedSelect = onRelatedSelect,
                onCastSelect = onCastSelect,
                displayName = displayName,
                onNavSelect = onNavSelect,
                onProfile = onProfile,
                onSearch = onSearch,
                discoverAvailable = discoverAvailable,
            )
        }
    }
}

/**
 * Phase R179 bug fix: when [ep] belongs to a multi-episode file group (`partCount > 1`), the player is
 * genuinely playing the whole shared file — its on-screen kicker/title used to show just this one
 * episode (e.g. "S1 · E1" / episode 1's own title) even though episodes 2 and 3 play right along with
 * it, reading as if only one episode were showing. Returns the group's (lowest, highest) episode
 * number when [ep] is grouped, else null for the ordinary single-episode case.
 */
private fun episodeGroupRange(ep: Episode, episodes: List<Episode>): Pair<Int, Int>? {
    if (ep.partCount <= 1 || ep.file.isBlank()) return null
    val nums = episodes.filter { it.file == ep.file }.map { it.episodeNumber }
    val lo = nums.minOrNull() ?: return null
    val hi = nums.maxOrNull() ?: return null
    return if (lo != hi) lo to hi else null
}

private fun episodeKicker(sNum: Int, ep: Episode, episodes: List<Episode>): String {
    val range = episodeGroupRange(ep, episodes)
    return if (range != null) "S$sNum · E${range.first}-${range.second}" else "S$sNum · E${ep.episodeNumber}"
}

private fun episodeDisplayTitle(ep: Episode, episodes: List<Episode>): String {
    val range = episodeGroupRange(ep, episodes)
    return if (range != null) "Episodes ${range.first}-${range.second}" else ep.title
}

/** Groups consecutive episodes sharing a physical `file` (a multi-episode release) into one unit;
 *  a lone episode is its own group of 1. Mirrors the episode rail's grouping (below) so the player's
 *  in-player episode picker never shows one entry per contained episode of the same file — bug fix:
 *  it used to map the flat episode list 1:1, so a 3-episode file produced 3 duplicate-looking picker
 *  entries all titled "Episodes 1-3", none of which reflected which file was actually playing. */
private fun episodeGroups(episodes: List<Episode>): List<List<Episode>> =
    // Bug fix (auto-play-next loop): grouping is by FILE, so two different files that both claim the same
    // episode (a library folder extracted twice, or a mislabelled release) used to become two rail slots
    // carrying the SAME id — the "next" group after episode 1 was episode 1 again, the credits card
    // announced it, and requesting a jump to the id already playing could only be a no-op. It also fed
    // duplicate keys to the rail's LazyRow. The backend now exposes one entry per (season, episode), but
    // an unscanned/older library can still hand us a collision, so the id is deduped here too: whatever
    // the metadata says, an id occupies exactly one group.
    episodes.distinctBy { it.id }
        .groupBy { if (it.file.isBlank()) "single:${it.id}" else it.file }
        .values.toList()

/** Picks the group's natural entry point: in-progress, else first unwatched, else the first episode. */
private fun groupEntryPoint(group: List<Episode>, overlay: Map<String, CardPlayState>): Episode =
    group.firstOrNull { ep -> overlay[ep.id].let { ps -> ps != null && !ps.played && ps.resumeMs > 0 } }
        ?: group.firstOrNull { ep -> overlay[ep.id]?.played != true }
        ?: group.first()

private fun buildEpisodeContext(
    detail: SeriesDetail,
    seasonIdx: Int,
    episodes: List<Episode>,
    epId: String,
    overlay: Map<String, CardPlayState> = emptyMap(),
): EpisodePlayContext {
    val season = detail.seasons.getOrNull(seasonIdx)
    val sNum = season?.index ?: (seasonIdx + 1)
    val groups = episodeGroups(episodes)
    val groupIdx = groups.indexOfFirst { g -> g.any { it.id == epId } }.coerceAtLeast(0)
    val group = groups.getOrElse(groupIdx) { groups.firstOrNull() ?: episodes.take(1) }
    val ep = group.firstOrNull { it.id == epId } ?: group.firstOrNull() ?: episodes.first()
    val nextGroup = groups.getOrNull(groupIdx + 1)
    val nextEp = nextGroup?.let { groupEntryPoint(it, overlay) }
    return EpisodePlayContext(
        episodeId    = epId,
        episodeTitle = episodeDisplayTitle(ep, episodes),
        kicker       = episodeKicker(sNum, ep, episodes),
        nextEpId     = nextEp?.id,
        nextEpLabel  = nextEp?.let { episodeKicker(sNum, it, episodes) },
        nextEpTitle  = nextEp?.let { episodeDisplayTitle(it, episodes) },
        episodes     = groups.map { g ->
            val rep = groupEntryPoint(g, overlay)
            // R84: prefer overlay playstate; fall back to 0f/false (catalog carries null from R83)
            val ps = overlay[rep.id]
            val totalRuntime = g.sumOf { it.runtime }
            val groupWatched = g.all { overlay[it.id]?.played ?: it.playback?.watched ?: false }
            PlayerEpisodeEntry(
                id            = rep.id,
                numberLabel   = if (g.size > 1) null else rep.episodeNumber.toString(),
                title         = episodeDisplayTitle(rep, episodes),
                kicker        = episodeKicker(sNum, rep, episodes),
                durationLabel = if (totalRuntime > 0) "${totalRuntime}m" else "",
                progressPct   = ps?.playedPct ?: rep.playback?.pct ?: 0f,
                watched       = groupWatched,
                stillUrls     = g.take(3).map { it.stillUrl },
                // Phase 150: a multi-episode-file group's credits belong to its LAST contained episode
                // (the shared file's own end) — g.last(), not the group's rep(resentative) entry point,
                // which can be any episode in the group. Detection itself currently skips these groups
                // entirely (PipelineStepOps.detectSegments), so this is empty/default in practice today —
                // forward-compatible for whenever that scope limitation is lifted.
                segments      = g.last().segments,
                seasonPosterUrl = season?.posterUrl,  // R194
            )
        },
        currentEpIndex = groupIdx,
        seriesId = detail.card.id,
        originalLanguage = detail.originalLanguage,
        segments = ep.segments,  // Phase 150 — the CURRENTLY PLAYING episode's own segments
        seriesPosterUrl = detail.card.posterUrl,  // R194
    )
}

@Composable
private fun SeriesDetailLoaded(
    detail: SeriesDetail,
    overlay: Map<String, CardPlayState>,
    onBack: () -> Unit,
    onPlay: (EpisodePlayContext) -> Unit,
    onMarkEpisode: (String, Boolean) -> Unit,
    onMarkFavorite: (Boolean) -> Unit,
    onRelatedSelect: (MediaCard) -> Unit,
    onCastSelect: ((dev.jellystructure.shared.tv.Person, sourceTitle: String) -> Unit)?,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onProfile: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    discoverAvailable: Boolean,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk
    val sora = Sora
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
    var selectedSeasonIdx by remember(detail.card.id) { mutableIntStateOf(0) }
    // Auto-select the first incomplete season once the playstate overlay arrives (one-shot).
    var autoSeasonDone by remember(detail.card.id) { mutableStateOf(false) }
    LaunchedEffect(overlay, detail.card.id) {
        if (!autoSeasonDone && overlay.isNotEmpty()) {
            autoSeasonDone = true
            val activeIdx = detail.seasons.indexOfFirst { season ->
                season.episodes.any { ep -> overlay[ep.id]?.played != true }
            }.takeIf { it >= 0 } ?: (detail.seasons.size - 1)
            selectedSeasonIdx = activeIdx
        }
    }
    val currentSeason = detail.seasons.getOrNull(selectedSeasonIdx)
    val episodes: List<Episode> = currentSeason?.episodes ?: emptyList()

    // R84: derive all progress values from the phase-2 overlay (empty map = not yet loaded)
    val allEps = remember(detail) { detail.seasons.flatMap { it.episodes } }
    val overlayLoaded = overlay.isNotEmpty()
    // R107: memoize the O(N)-over-all-episodes scans so they don't re-run on every recomposition
    // (notably the phase-2 overlay re-emit) — only when the episode set or overlay actually changes.
    val watchedCount = remember(allEps, overlay) { allEps.count { ep -> overlay[ep.id]?.played == true } }
    // Set of season indices (season.index, not list position) where every episode is watched.
    val watchedSeasons: Set<Int> = remember(detail.seasons, overlay) {
        detail.seasons
            .filter { season -> season.episodes.isNotEmpty() && season.episodes.all { ep -> overlay[ep.id]?.played == true } }
            .map { it.index }
            .toSet()
    }
    // R151: per-season watched episode count, for the picker's partial (1..n-1 watched) w/N badge + sliver.
    val watchedCounts: Map<Int, Int> = remember(detail.seasons, overlay) {
        detail.seasons.associate { season -> season.index to season.episodes.count { ep -> overlay[ep.id]?.played == true } }
    }
    val resumeEpId: String? = remember(allEps, overlay) {
        allEps.firstOrNull { ep -> overlay[ep.id].let { ps -> ps != null && !ps.played && ps.resumeMs > 0 } }?.id
            ?: allEps.firstOrNull { ep -> overlay[ep.id]?.played != true }?.id
            // Bug fix: a fully-watched series used to fall through to `lastOrNull()` — the play button
            // showed the hard-coded "Play · E1" label (below) but actually launched the *last* episode
            // (reported: "Play E1" on a finished series played E40). Re-watching should restart from the
            // first episode, which is exactly what the label already promises.
            ?: allEps.firstOrNull()?.id
    }

    val playFR = remember { FocusRequester() }
    val synopsisFR = remember { FocusRequester() }   // R135
    val seasonFirstFR = remember { FocusRequester() }   // R138
    val navBarFR = remember { FocusRequester() }
    val trailerFR = remember { FocusRequester() }   // R163
    var showTrailer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { playFR.requestFocus() } }

    // Bug fix: pressing UP from the season picker / episode rail / cast / related rows relied entirely
    // on Compose's native spatial focus search reaching the hero above — but the hero is a full-height
    // `item(key = "hero")` in this same LazyColumn, so once scrolled a couple of rows past it, the hero
    // (and every FocusRequester inside it, including the one navBarFR itself bridges through) is
    // disposed. With nothing composed for native search to land on, the UP key silently did nothing —
    // reported live as focus "getting stuck" while navigating a series' season/episode area. Snap the
    // list back to the top (recomposing the hero) and retry focusing Play once it's attached
    // (`requestFocusRetrying` already exists for exactly this "target not composed yet" race).
    val upToHero: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { ev ->
        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
            scope.launch { runCatching { listState.scrollToItem(0) } }
            requestFocusRetrying(scope, playFR)
            true
        } else false
    }

    // R79: appBarHeight + 24dp top inset so season picker / episode rail title isn't hidden under the bar.
    val detailBivSpec = rememberEdgeBringIntoViewSpec(peekDp = 60.dp, topInsetDp = RaviloDimens.appBarHeight + 24.dp)
    // R109: boolean derivedStateOf (notifies only on threshold cross) — no per-scroll-frame recompose.
    val appBarScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    val navItems = raviloNavItems(discoverAvailable)

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
                        // R145: full width on a phone (TV 60% column wastes a narrow screen); TV unchanged.
                        .fillMaxWidth(if (LocalCompact.current) 1f else 0.6f)
                        .padding(start = raviloHPad, bottom = 44.dp, end = raviloHPad),
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
                    if (meta.isNotEmpty() || detail.ratingBadge != null || detail.imdbRating != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotEmpty()) Text(meta, color = colors.textSecondary, fontSize = 15.sp)
                            // Phase 106/R153: server-resolved age-rating badge.
                            if (detail.ratingBadge != null) {
                                if (meta.isNotEmpty()) Spacer(Modifier.width(10.dp))
                                CertBadge(detail.ratingBadge)
                            }
                            // R164: server-pushed IMDb rating chip (show-level), after the cert badge.
                            if (detail.imdbRating != null) {
                                if (meta.isNotEmpty() || detail.ratingBadge != null) Spacer(Modifier.width(10.dp))
                                ImdbChip(detail.imdbRating)
                            }
                        }
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
                    // R149: next-airing line — in the hero, beside the resume/up-next kicker above the
                    // fold (design's .dnext-row), not below the season picker. No source attribution.
                    detail.nextAiring?.let { na ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(colors.accent, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = buildString {
                                    append(str("sonarr.next_ep"))
                                    append(" · S${na.season.toString().padStart(2, '0')}E${na.episode.toString().padStart(2, '0')}")
                                    if (!na.title.isNullOrBlank()) append(" “${na.title}”")
                                    append(" · ")
                                    append(str("sonarr.airs"))
                                    append(" ${na.airDate}")
                                },
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                fontFamily = sora,
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        // R72: scroll(UserInput) wins over bring-into-view (Default priority) so
                        // focusing Play/Resume reliably reframes the full backdrop.
                        modifier = Modifier
                            // Bug fix: this Row lives inside the hero's 60%-width column; once Play +
                            // My List + Trailer's combined natural width exceeded that column, the Row
                            // got clamped to the column's maxWidth and the LAST button (Trailer) ended up
                            // squeezed into an unreadable sliver instead of the whole row simply being
                            // allowed to scroll. horizontalScroll removes the clamp — every button always
                            // renders at its full natural size; if there ever isn't room, the row scrolls
                            // (D-pad focus brings the target into view) instead of corrupting layout.
                            .horizontalScroll(rememberScrollState())
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
                                        }
                                        // R201: requestFocusRetrying (not a single runCatching) — the
                                        // picker's own self-scroll-to-selected (SeasonPicker.kt) may still
                                        // take a frame to attach the pill's FocusRequester.
                                        requestFocusRetrying(scope, seasonFirstFR)          // BIV reveals it below the AppBar
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
                                if (epId != null) {
                                    // resumeEpId is computed across all seasons, so it may not live in the
                                    // currently-shown season. Play it from *its own* season so the player's
                                    // episode index and the next-episode target (nextEpId) are correct —
                                    // otherwise buildEpisodeContext's indexOfFirst misses and silently plays
                                    // that season's first episode instead.
                                    val sIdx = detail.seasons.indexOfFirst { s -> s.episodes.any { it.id == epId } }
                                        .takeIf { it >= 0 } ?: selectedSeasonIdx
                                    val seasonEps = detail.seasons.getOrNull(sIdx)?.episodes ?: episodes
                                    onPlay(buildEpisodeContext(detail, sIdx, seasonEps, epId, overlay))
                                }
                            },
                        )
                        // Bug fix: this button had no `onSelect` at all -- pressing OK did nothing, and
                        // there was no backend call anywhere to add/remove a Jellyfin favorite (only a
                        // read path existed, for the My List browse grid's own filter). My List/Favorite
                        // is series-level, keyed by the series' own id (not any one episode).
                        val favorite = overlay[detail.card.id]?.favorite == true
                        RaviloButton(
                            label = if (favorite) "− ${str("nav.my_list")}" else "+ ${str("nav.my_list")}",
                            style = ButtonStyle.GHOST,
                            onSelect = { onMarkFavorite(!favorite) },
                        )
                        // R163: only when Phase 130 ingested a usable trailer — never a dead affordance.
                        if (detail.trailer != null) {
                            // Bug fix: with no minimum width this button intermittently measured to a
                            // ~0-width label (collapsing the whole pill into a tiny near-square sliver,
                            // completely unreadable) — same defensive fix already applied to Play above.
                            RaviloButton(
                                label = "▷ ${str("action.trailer")}",
                                focusRequester = trailerFR,
                                style = ButtonStyle.GHOST,
                                modifier = Modifier.widthIn(min = 130.dp),
                                onSelect = { showTrailer = true },
                            )
                        }
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
                        // Bug fix: picking a season before the playstate overlay finished its first
                        // load got silently reverted — LaunchedEffect(overlay, ...) below runs its
                        // ONE-SHOT auto-select the moment overlay first arrives non-empty, gated only on
                        // autoSeasonDone; if the viewer picked a season during that window, autoSeasonDone
                        // was still false and the auto-select stomped their choice right back. Marking it
                        // done here makes a manual pick permanently win, no matter when it happens.
                        onSelect = { selectedSeasonIdx = it; autoSeasonDone = true },
                        firstFocusRequester = seasonFirstFR,   // R138
                        watchedSeasons = watchedSeasons,
                        watchedCounts = watchedCounts,
                        modifier = Modifier.onKeyEvent(upToHero),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Episode rail — own lazy item.
            if (episodes.isNotEmpty()) item(key = "episodes") {
                // R142: season-scoped watched count (the loaded season's episodes). Marking watched is
                // episode-level only (the season-wide "Mark all" toggle was removed by request).
                val seasonWatched = episodes.count { overlay[it.id]?.played == true }
                Column {
                    if (detail.seasons.size <= 1) Spacer(Modifier.height(28.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = raviloHPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            str("detail.episodes"),
                            color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = spaceGrotesk, letterSpacing = (-0.5).sp,
                        )
                        if (overlayLoaded) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "$seasonWatched / ${episodes.size} ${str("action.watched").lowercase()}",
                                color = colors.textSecondary, fontSize = 13.sp,
                            )
                        }
                    }

                    // Phase R179: group consecutive episodes sharing a physical `file` (a multi-episode
                    // release, e.g. S01E01E02E03.mkv) into ONE rail slot — a lone episode is its own
                    // group of 1. Built from server-pushed `file` only; never inspects filenames. Shared
                    // with buildEpisodeContext so the in-player episode picker groups the same way.
                    val episodeGroups = remember(episodes) { episodeGroups(episodes) }
                    val epRowState = rememberLazyListState()
                    // Scroll to the GROUP containing the first unwatched episode whenever the selected
                    // season or overlay changes. Bug fix: this used to index into the flat `episodes`
                    // list, which no longer matches the rail's item count once episodes are grouped.
                    //
                    // Bug fix (live-tested on stue TV): the old `if (scrollTo > 0)` guard skipped the
                    // scroll whenever the TARGET was index 0 -- exactly the common case (a season that's
                    // either brand new/never-watched, or fully watched with no "first unwatched" episode
                    // at all, both resolve to 0). That left the rail showing whatever mid-season slice a
                    // PREVIOUSLY selected season had scrolled to -- confirmed live: switching from Season 5
                    // (scrolled to E15) to a completely unwatched Season 6 kept E15-E17 on screen instead
                    // of resetting to E1, and Down from the season picker landed focus on E17, not E1
                    // (focusRestorer() falls back to whatever's at the current scroll position once the
                    // old focused episode's composable is gone). Always scrolling -- including to 0 --
                    // fixes both: the rail visibly resets, and focus entering it lands on the right episode.
                    LaunchedEffect(selectedSeasonIdx, overlay, episodeGroups) {
                        if (overlay.isEmpty()) return@LaunchedEffect
                        val firstUnwatchedEp = episodes.firstOrNull { ep -> overlay[ep.id]?.played != true }
                        val scrollTo = firstUnwatchedEp
                            ?.let { target -> episodeGroups.indexOfFirst { g -> g.any { it.id == target.id } } }
                            ?.takeIf { it >= 0 } ?: 0
                        epRowState.scrollToItem(scrollTo)
                    }
                    Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
                    LazyRow(
                        state = epRowState,
                        // Bug fix: with a single season there's no season picker row above to catch native
                        // search, so this rail sits directly under the (often-unmounted-once-scrolled)
                        // hero — same "UP does nothing" gap as the season picker itself. With multiple
                        // seasons the picker is right above and stays composed, so native search already
                        // reaches it reliably; no bridge needed there.
                        modifier = if (detail.seasons.size <= 1) Modifier.focusRestorer().onKeyEvent(upToHero) else Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        items(episodeGroups.size, key = { i -> episodeGroups[i].first().id }) { i ->
                            val group = episodeGroups[i]
                            if (group.size > 1) {
                                // The whole card plays/toggles as one unit — target whichever contained
                                // episode is the natural entry point: in-progress, else first unwatched,
                                // else the group's first episode.
                                val targetEp = groupEntryPoint(group, overlay)
                                val groupWatched = group.all { overlay[it.id]?.played == true }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MultiEpisodeCard(
                                        episodes = group,
                                        isResumeGroup = overlayLoaded && group.any { it.id == resumeEpId },
                                        playstateOverlay = overlay,
                                        onSelect = { onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, targetEp.id, overlay)) },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    EpisodeWatchToggle(watched = groupWatched, onToggle = { onMarkEpisode(targetEp.id, !groupWatched) })
                                }
                            } else {
                                val ep = group.first()
                                val epWatched = overlay[ep.id]?.played == true
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    EpisodeCard(
                                        episode = ep,
                                        // R84: overlay-driven; no "UP NEXT" ribbon until playstate arrives
                                        isResumeEpisode = overlayLoaded && ep.id == resumeEpId,
                                        playstateOverride = overlay[ep.id],
                                        onSelect = { onPlay(buildEpisodeContext(detail, selectedSeasonIdx, episodes, ep.id, overlay)) },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    EpisodeWatchToggle(watched = epWatched, onToggle = { onMarkEpisode(ep.id, !epWatched) })
                                }
                            }
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
                        modifier = Modifier.padding(horizontal = raviloHPad))
                    Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        items(detail.cast.size, key = { i -> detail.cast[i].id }) { i ->
                            val person = detail.cast[i]
                            CastCircle(person = person, onSelect = onCastSelect?.let { { it(person, detail.card.title) } })
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
            // Bug fix: playFR.requestFocus() used to be called directly here — if the list had been
            // scrolled down into seasons/episodes/cast/related, the hero (lazy item 0) was disposed and
            // requestFocus() threw, silently swallowed, stranding focus in the nav bar (D-pad Down did
            // nothing). Same root cause + fix as HomeScreen's AppBar.onDown. Scroll to the top first so
            // the hero is back in composition before focusing it.
            onDown = { scope.launch { runCatching { listState.scrollToItem(0) }; runCatching { playFR.requestFocus() } } },
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

/**
 * R142 — per-episode played toggle: a second focusable below each EpisodeCard (the card stays the play
 * target; D-pad steps card → toggle → next card). SELECT marks the episode played/unplayed via the
 * server, and the store overlay re-emit re-renders the ✓ / count / season bar / up-next.
 */
@Composable
private fun EpisodeWatchToggle(watched: Boolean, onToggle: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val bg = if (watched) colors.badgeWatched.copy(alpha = 0.16f) else colors.surfaceVariant
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, shape) else Modifier)
            .dpadFocusable(
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (watched) "✓" else "○",
            color = if (watched) colors.badgeWatched else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (watched) str("action.watched") else str("action.mark_watched"),
            color = colors.text,
            fontSize = 12.sp,
        )
    }
}
