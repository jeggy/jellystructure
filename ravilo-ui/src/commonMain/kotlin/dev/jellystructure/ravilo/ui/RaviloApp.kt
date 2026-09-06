package dev.jellystructure.ravilo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.screens.BrowseKind
import dev.jellystructure.ravilo.ui.screens.BrowseScreen
import dev.jellystructure.ravilo.ui.screens.BrowseStore
import dev.jellystructure.ravilo.ui.screens.ChannelScreen
import dev.jellystructure.ravilo.ui.screens.ChannelStore
import dev.jellystructure.ravilo.ui.screens.SeededBrowseScreen
import dev.jellystructure.ravilo.ui.screens.SeededBrowseStore
import dev.jellystructure.ravilo.ui.screens.DiscoverDetailScreen
import dev.jellystructure.ravilo.ui.screens.DiscoverDetailStore
import dev.jellystructure.ravilo.ui.screens.DiscoverScreen
import dev.jellystructure.ravilo.ui.screens.DiscoverSegment
import dev.jellystructure.ravilo.ui.screens.DiscoverStore
import dev.jellystructure.ravilo.ui.screens.defaultDiscoverSegment
import dev.jellystructure.ravilo.ui.screens.HomeScreen
import dev.jellystructure.ravilo.ui.screens.HomeSnapshot
import dev.jellystructure.ravilo.ui.screens.HomeSnapshotCache
import dev.jellystructure.ravilo.ui.screens.HomeState
import dev.jellystructure.ravilo.ui.screens.HomeStore
import dev.jellystructure.ravilo.ui.screens.LiveTvGuideScreen
import dev.jellystructure.ravilo.ui.screens.LiveTvGuideStore
import dev.jellystructure.ravilo.ui.screens.LiveTvPlayerScreen
import dev.jellystructure.ravilo.ui.screens.LiveTvPlayerStore
import dev.jellystructure.ravilo.ui.screens.MovieDetailScreen
import dev.jellystructure.ravilo.ui.screens.MovieDetailStore
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.LoginScreen
import dev.jellystructure.ravilo.ui.screens.LoginStore
import dev.jellystructure.ravilo.ui.screens.PlayerScreen
import dev.jellystructure.ravilo.ui.screens.PlayerStore
import dev.jellystructure.ravilo.ui.screens.ProfilePickerScreen
import dev.jellystructure.ravilo.ui.screens.ProfilePickerStore
import dev.jellystructure.ravilo.ui.screens.RaviloNavTarget
import dev.jellystructure.ravilo.ui.screens.raviloNavTarget
import dev.jellystructure.ravilo.ui.screens.SearchScreen
import dev.jellystructure.ravilo.ui.screens.SearchStore
import dev.jellystructure.ravilo.ui.screens.SeerrSearchScreen
import dev.jellystructure.ravilo.ui.screens.SeerrSearchStore
import dev.jellystructure.ravilo.ui.screens.SeriesDetailScreen
import dev.jellystructure.ravilo.ui.screens.SeriesDetailStore
import dev.jellystructure.ravilo.ui.screens.SettingsScreen
import dev.jellystructure.ravilo.ui.screens.SettingsStore
import dev.jellystructure.ravilo.ui.screens.signOutActiveSession
import dev.jellystructure.ravilo.ui.screens.UpcomingDetailScreen
import dev.jellystructure.ravilo.ui.screens.UpcomingDetailStore
import dev.jellystructure.ravilo.ui.screens.UpcomingScreen
import dev.jellystructure.ravilo.ui.screens.UpcomingStore
import dev.jellystructure.ravilo.ui.screens.WatchedBus
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.ravilo.ui.i18n.str
import coil3.compose.LocalPlatformContext
import dev.jellystructure.ravilo.ui.components.ProfileMenu
import dev.jellystructure.ravilo.ui.components.ServerMessageHost
import dev.jellystructure.ravilo.ui.perf.FrameTrackerOverlay
import dev.jellystructure.ravilo.ui.seams.prefetchImage
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.rememberRaviloTheme
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.NavigateEnvelope
import dev.jellystructure.shared.tv.PlayItemEnvelope
import dev.jellystructure.shared.tv.PlaystateCommandEnvelope
import dev.jellystructure.shared.tv.ServerMessageEnvelope
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.tileScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * R33 live-config signal. The active session's WebSocket emits here on every `config_changed` (and on
 * (re)connect); the visible layout screen (Home/Channel/Settings) collects it for a silent refresh.
 */
val LocalLiveConfig = staticCompositionLocalOf<SharedFlow<Long>?> { null }

/** R49 — payload-bearing acquisition updates (Phase 56 `acquisition_changed`); Discover screens patch tiles live. */
val LocalLiveAcquisition = staticCompositionLocalOf<SharedFlow<AcquisitionRecord>?> { null }

/** R152 — Jellyfin dashboard "send message" events, relayed device-addressed via the Phase 110 session
 *  bridge; collected by [dev.jellystructure.ravilo.ui.components.ServerMessageHost] at the app root. */
val LocalServerMessages = staticCompositionLocalOf<SharedFlow<ServerMessageEnvelope>?> { null }

/** R159 — is the app's viewport currently taller than it is wide? Recomputed live on resize/rotation
 *  (TVs/desktop web: always false; a phone held upright: true; a resized browser window follows too).
 *  Drives portrait-only presentation overrides (starting with hero height) — the client only *selects*
 *  by its own viewport, both numbers are server-pushed, so this stays presentation selection, not
 *  derived state (same class as [LocalTileScale]'s uiDensity). */
val LocalPortrait = staticCompositionLocalOf { false }

/** R155 — remote playstate commands (stop/pause/unpause/seek) for whatever's playing on this device.
 *  Collected directly by PlayerScreen — a SharedFlow with no active collector just drops the value,
 *  which is exactly "ignore when no player is open" (FR-R155-2) with no extra check needed. */
val LocalPlaystateCommands = staticCompositionLocalOf<SharedFlow<PlaystateCommandEnvelope>?> { null }

/** Tile-size multiplier from the active user's `RaviloConfig.uiDensity`; read by [dev.jellystructure.ravilo.ui.components.Tile]. */
val LocalTileScale = staticCompositionLocalOf { 1f }

/** R174 — poster-grid items per row (all-movies/series browse + search + request search). Two values,
 *  server-pushed on the one config payload; the screen selects by [LocalPortrait] (same presentation-
 *  selection class as [LocalTileScale]). Defaults match the shared config: 6 landscape, 2 portrait. */
val LocalGridColumns = staticCompositionLocalOf { 6 }
val LocalPortraitGridColumns = staticCompositionLocalOf { 2 }

/** R61 — server base URL (e.g. `http://192.168.1.100:8080`); used to resolve relative logo/image URLs. */
val LocalServerBaseUrl = staticCompositionLocalOf { "" }

/** R65 — active user's Jellyfin avatar URL; null if the user has no profile picture. */
val LocalUserAvatarUrl = staticCompositionLocalOf<String?> { null }

// ─── Navigation direction (drives AnimatedContent transitionSpec) ─────────────

private enum class NavDir { Forward, Back, Reset }

// ─── Navigation destinations ──────────────────────────────────────────────────

private sealed class Dest {
    data object Login : Dest()
    data object ProfilePicker : Dest()
    data class Home(val displayName: String) : Dest()
    data class ChannelView(val channel: Channel, val displayName: String) : Dest()
    data class Browse(val kind: BrowseKind, val displayName: String) : Dest()
    // R187 — the "→ See all" seeded browse page: [seedQuery]/[seedMediaKind] mirror Row's own fields
    // (null seedQuery = no additional filter, e.g. a Newly-Added-style kind-only seed). [continueWatching]
    // routes to the dedicated GET /tv/continue/all path instead (Continue Watching isn't seed-representable
    // — see the R187 spec's §G-4) and hides the facet bar (FR-RV-BROWSE1-1's "full in-progress list", not
    // a filterable catalog view). Not deep-linkable (the seed has no URL-safe encoding) — see toRoute().
    data class SeededBrowse(
        val seedQuery: dev.jellystructure.shared.tv.ConditionGroup?,
        val seedMediaKind: String?,
        val title: String,
        val breadcrumb: String?,
        val continueWatching: Boolean,
        val displayName: String,
        /** R187 fix — which section tab (if any) this seeded page IS, so the AppBar highlights it;
         *  -1 for a drill-in ("→ See all") page that isn't itself a tab. */
        val activeNav: Int = -1,
        /** R190 §B/§C — set only for a person seed: [personRoleLine] renders as the meta line under the
         *  person's name (FR-RV-PPL1-3), [personTmdbId] drives the Seerr overflow row fetch (§C). Both
         *  null for every other seed source (row/kind/channel). */
        val personRoleLine: String? = null,
        val personTmdbId: Int? = null,
        /** R219 (FR-R219-6) — set only alongside [continueWatching] == true, when reached from a
         *  channel's own Continue row; forwarded to [SeededBrowseStore] unchanged. */
        val channelId: String? = null,
    ) : Dest()
    data class Search(val displayName: String) : Dest()
    // R170 — Coming Soon (the old Upcoming tab) and Request (the old Top-10/Discover tab) are now the
    // two segments of one merged Discover tab; `segment` decides which of UpcomingScreen/DiscoverScreen
    // actually renders (see DiscoverSegment/defaultDiscoverSegment in NavItems.kt).
    data class Discover(val displayName: String, val segment: DiscoverSegment) : Dest()
    // R171 — addressed by mediaType ("movie"|"tv") + tmdbId; Request rows have no rank concept.
    data class DiscoverItem(val mediaType: String, val tmdbId: Int, val displayName: String) : Dest()
    // R171 — the Request tab's Seerr-scoped search (FR-R171-3), a separate destination from the
    // library `Dest.Search` above (different result type/action: request tiles, not play tiles).
    data class SeerrSearch(val displayName: String) : Dest()
    data class UpcomingDetail(val id: String, val displayName: String) : Dest()
    data class MovieDetail(val itemId: String, val displayName: String) : Dest()
    data class SeriesDetail(val itemId: String, val displayName: String) : Dest()
    data class Player(
        val itemId: String,
        val title: String,
        val kicker: String? = null,
        val nextEpId: String? = null,
        val nextEpLabel: String? = null,
        val nextEpTitle: String? = null,
        val displayName: String,
        val episodes: List<dev.jellystructure.ravilo.ui.screens.PlayerEpisodeEntry>? = null,
        val currentEpIndex: Int = 0,
        /** R181 — the series' own item id (or, for a movie, the movie's own id — it's its own bucket)
         *  for per-series remembered audio/subtitle choices. */
        val seriesId: String? = null,
        /** R181/R180 — the title's original-audio language, for the player's "Dubbed" audio badge. */
        val originalLanguage: String? = null,
        /** R192 — poster fallback for the OS media session's artwork (movies; series derive a still
         *  from [episodes] instead, so this is left null on that path). */
        val posterUrl: String? = null,
        /** Phase 150 — this title's own intro/credits segments (R182 Skip Intro / Skip Credits). */
        val segments: dev.jellystructure.shared.tv.TvSegmentMarkers = dev.jellystructure.shared.tv.TvSegmentMarkers(),
    ) : Dest()
    data class Settings(val displayName: String) : Dest()
    // R234 (FR-R234-1) — phone/web only; the caller gates the ProfileMenu row that reaches this on
    // !isTvPlatform, but the destination itself is reachable by any platform that pushes it.
    data class YourProfile(val displayName: String) : Dest()
    // R234 (FR-R234-4) — every platform, reached from Settings' Account section.
    data class ChangePassword(val displayName: String) : Dest()
    // Phase R177 — a deliberate sibling to Player (see LiveTvPlayerStore's doc comment): live channels
    // have no resume position and need Jellyfin's explicit open/close handshake, so this is its own
    // destination rather than a branch of Dest.Player. Never reached from raviloNavItems (no top-nav
    // tab) — only from the Home "On now" row or the guide below.
    data class LiveTv(val channelId: String, val displayName: String) : Dest()
    data class LiveTvGuide(val displayName: String) : Dest()

    // R80: each Dest maps to a hash route (web) or is ignored (android/TV).
    fun toRoute(): String = when (this) {
        is Login          -> "/login"
        is ProfilePicker  -> "/profiles"
        is Home           -> "/home"
        is ChannelView    -> "/channel/${channel.id}"
        is Browse         -> "/browse/${kind.name.lowercase()}"
        is SeededBrowse   -> "/browse-seed" // not deep-linkable — the seed has no URL-safe encoding
        is Search         -> "/search"
        is Discover       -> "/discover"
        is DiscoverItem   -> "/discover/$mediaType/$tmdbId"
        is SeerrSearch    -> "/discover/search"
        is UpcomingDetail -> "/upcoming/$id"
        is MovieDetail    -> "/movie/$itemId"
        is SeriesDetail   -> "/series/$itemId"
        is Player         -> "/player/$itemId"
        is Settings       -> "/settings"
        is YourProfile    -> "/account/profile"
        is ChangePassword -> "/account/password"
        is LiveTv         -> "/livetv/$channelId"
        is LiveTvGuide    -> "/livetv-guide"
    }
}

// ─── Root composable ──────────────────────────────────────────────────────────

@Composable
fun RaviloApp(apiClient: TvApiClient, initialDisplayName: String = "", onChangeServer: () -> Unit = {}) {
    // R212 — the last-known Home feed + display settings for this device's single cached session
    // (if any), read once at cold start. Mirrors initialDest's own bare `remember{}` below: it only
    // ever matters for the single-session fast path — a profile switch mid-session is already
    // handled live by refreshConfig(), independent of this seed.
    val initialSnapshot = remember { MultiTokenStore.getActive()?.userId?.let { HomeSnapshotCache.load(it) } }

    var lang by remember { mutableStateOf(initialSnapshot?.uiLanguage ?: "en") }
    var tileScale by remember { mutableStateOf(initialSnapshot?.tileScale ?: 1f) }
    // R174 — grid columns, server-pushed on the config; portrait falls back to the built-in 2.
    var gridColumns by remember { mutableStateOf(initialSnapshot?.gridColumns ?: 6) }
    var portraitGridColumns by remember { mutableStateOf(initialSnapshot?.portraitGridColumns ?: 2) }
    val themeState = rememberRaviloTheme(initial = initialSnapshot?.skin ?: Skin.AURORA)

    // Fetch the active user's config and apply server-owned interface prefs (language + skin)
    val configScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    fun refreshConfig() {
        configScope.launch {
            runCatching { apiClient.getConfig() }.getOrNull()?.let { cfg ->
                lang = cfg.uiLanguage
                themeState.skin = cfg.effectiveSkin()
                tileScale = cfg.uiDensity.tileScale()
                gridColumns = cfg.gridColumns
                portraitGridColumns = cfg.portrait?.gridColumns ?: 2
            }
        }
    }

    // R33 — live config push. One WebSocket per active user; on connect (and on every change) we
    // re-pull config (skin/lang) and signal the visible screen to silently refresh. Reconnect with
    // backoff; the (re)connect emit catches anything missed while disconnected. The collect + the
    // socket run on background scopes, so navigation is never blocked.
    val liveConfig = remember { MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 16) }
    val liveAcquisition = remember { MutableSharedFlow<AcquisitionRecord>(replay = 0, extraBufferCapacity = 32) }
    val liveServerMessages = remember { MutableSharedFlow<ServerMessageEnvelope>(replay = 0, extraBufferCapacity = 8) }
    // R155 — remote-control commands (Phase 111 / Home Assistant + the Jellyfin dashboard cast menu).
    // play_item/navigate need push()/resetTo(), which aren't declared yet at this point in the
    // composable — collected further down, after those are in scope. playstate_command is exposed via
    // LocalPlaystateCommands for PlayerScreen to collect directly (naturally a no-op if no player is open).
    val livePlayItem = remember { MutableSharedFlow<PlayItemEnvelope>(replay = 0, extraBufferCapacity = 8) }
    val livePlaystateCommands = remember { MutableSharedFlow<PlaystateCommandEnvelope>(replay = 0, extraBufferCapacity = 8) }
    val liveNavigate = remember { MutableSharedFlow<NavigateEnvelope>(replay = 0, extraBufferCapacity = 8) }
    var activeUserId by remember { mutableStateOf(MultiTokenStore.getActive()?.userId) }
    var activeAvatarUrl by remember { mutableStateOf(MultiTokenStore.getActive()?.avatarUrl) }

    LaunchedEffect(Unit) { liveConfig.collect { refreshConfig() } }
    LaunchedEffect(activeUserId) {
        if (activeUserId == null) return@LaunchedEffect
        var backoff = 1000L
        while (true) {
            // Bug fix: a device whose token no longer matches any ravilo_device row (stale local
            // storage after a re-pair/DB reset) still completes the WS *upgrade* — the server only
            // rejects the token afterward, inside the handler — so onOpen() fires and used to reset
            // backoff to 1000ms on every single doomed attempt. That defeated the backoff entirely and
            // reconnected roughly once a second forever instead of backing off to the 15s cap. Only
            // treat the attempt as healthy (and reset backoff) if the socket stayed open a meaningful
            // duration; a near-instant open-then-close is treated like any other failed attempt.
            var openedAt: kotlin.time.Instant? = null
            runCatching {
                apiClient.connectEvents(
                    onOpen = { openedAt = Clock.System.now(); liveConfig.emit(0L) },
                    onEvent = { liveConfig.emit(it.rev) },
                    onAcquisition = { liveAcquisition.emit(it) },
                    onServerMessage = { liveServerMessages.emit(it) },
                    onPlayItem = { livePlayItem.emit(it) },
                    onPlaystateCommand = { livePlaystateCommands.emit(it) },
                    onNavigate = { liveNavigate.emit(it) },
                    // Home-feed playstate cache/concurrency fix — a live Jellyfin fetch made to satisfy
                    // this or another device's own /api/tv/home request lands here; reuse the existing
                    // R147 patch-in-place path (WatchedBus) instead of forcing a re-fetch.
                    onPlaystateChanged = { patch -> WatchedBus.publish(patch) },
                )
            }
            val heldOpenMs = openedAt?.let { (Clock.System.now() - it).inWholeMilliseconds } ?: 0L
            backoff = if (heldOpenMs >= 2_000L) 1_000L else (backoff * 2).coerceAtMost(15_000L)
            delay(backoff)
        }
    }
    // R141: degrade-to-poll fallback — safety net for when the WS is down or a single event is missed.
    // Polls /api/tv/config/rev every 15 s; if the rev has advanced since last seen, emits on liveConfig
    // so the visible screen does its existing silent refresh (same path as WS events — no duplication risk).
    LaunchedEffect("r141-poll:$activeUserId") {
        if (activeUserId == null) return@LaunchedEffect
        var seenRev = 0L
        while (true) {
            delay(15_000L)
            val rev = runCatching { apiClient.getConfigRev() }.getOrNull() ?: continue
            if (rev != seenRev) { seenRev = rev; liveConfig.emit(rev) }
        }
    }

    RaviloTheme(state = themeState) {
    WithLocale(lang) {
        // Determine starting screen based on cached sessions
        val initialDest = remember {
            val sessions = MultiTokenStore.getAll()
            when {
                sessions.isEmpty() -> Dest.Login
                sessions.size == 1 -> {
                    MultiTokenStore.setActive(sessions.first().userId)
                    Dest.Home(sessions.first().displayName)
                }
                else -> Dest.ProfilePicker
            }
        }
        var stack by remember { mutableStateOf(listOf(initialDest)) }
        // R92: direction that drives the AnimatedContent transitionSpec.
        var navDir by remember { mutableStateOf(NavDir.Forward) }
        var fpsOverlay by remember { mutableStateOf(false) }  // R94: toggle with F5
        // Top-level: track whether the Request (Seerr) segment is available; set from HomeStore, propagated to all screens.
        var discoverAvailable by remember { mutableStateOf(false) }
        // R160: same pattern for the Coming Soon segment (server-gated on [sonarr]/[radarr] presence).
        var upcomingAvailable by remember { mutableStateOf(false) }
        // R170 — the avatar opens this dropdown (My List/Settings/Switch profile/Unpair) instead of
        // pushing straight to the profile picker.
        var profileMenuOpen by remember { mutableStateOf(false) }

        // R58: first-ever launch — initialDest called setActive() after activeUserId was already
        // initialized to null; sync the value so the WS LaunchedEffect fires and self-heals.
        LaunchedEffect(initialDest) {
            if (activeUserId == null) activeUserId = MultiTokenStore.getActive()?.userId
        }

        // R40: retain screen stores across navigation so Back renders the cached screen instantly
        // (no Loading flash); each store refreshes silently on re-entry. Keyed by destination identity.
        val storeRegistry = remember { mutableMapOf<String, Any>() }
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> keptStore(key: String, create: () -> T): T =
            storeRegistry.getOrPut(key) { create() } as T

        // Load config when already on Home (single-session fast path)
        if (initialDest is Dest.Home) {
            androidx.compose.runtime.LaunchedEffect(Unit) { refreshConfig() }
        }

        // R80: stable holder for the "this mutation originated from the browser" flag.
        // Prevents push/pop from issuing a redundant history.push/replace when we're already
        // reacting to a browser-initiated navigation (hashchange).
        val fromHistory = remember { object { var flag = false } }

        // R100: captured at composition so the detail-open click handlers can pre-warm a backdrop.
        val imageCtx = LocalPlatformContext.current

        fun push(dest: Dest) {
            navDir = NavDir.Forward
            stack = stack + dest
            if (!fromHistory.flag) pushRoute(dest.toRoute())
        }
        fun pop() {
            if (stack.size > 1) {
                navDir = NavDir.Back
                stack = stack.dropLast(1)
                // On browser, hashchange already moved the URL — don't push another entry.
                // On Android, replaceRoute is a no-op, so calling it is harmless.
                if (!fromHistory.flag) replaceRoute(stack.last().toRoute())
            }
        }
        // Replace the whole stack (tab resets, sign-out) and sync the browser URL.
        fun resetTo(dest: Dest) {
            navDir = NavDir.Reset
            stack = listOf(dest)
            if (!fromHistory.flag) replaceRoute(dest.toRoute())
        }
        // Replace the top of the stack in-place (episode navigation) and sync the browser URL.
        fun replaceTop(dest: Dest) {
            navDir = NavDir.Forward
            stack = if (stack.isEmpty()) listOf(dest) else stack.dropLast(1) + dest
            if (!fromHistory.flag) replaceRoute(dest.toRoute())
        }
        // R100: single funnel for opening a movie/series detail — pre-warm the hero backdrop into
        // Coil so it's a cache hit (no late fade) when the detail composes, then push the right Dest.
        fun openDetail(card: MediaCard, displayName: String) {
            prefetchImage(imageCtx, apiClient.baseUrl, card.backdropUrl)
            if (card.kind == MediaKind.SERIES) push(Dest.SeriesDetail(card.id, displayName))
            else push(Dest.MovieDetail(card.id, displayName))
        }

        // R190 §A — OK on a cast/crew face opens the browse page seeded to that person's filmography
        // (FR-RV-PPL1-1/2). [person.id] is already the tmdbId stringified (see Person's doc comment /
        // DetailService.castFrom) so it round-trips straight into the cast_crew condition unchanged.
        fun openPersonBrowse(person: dev.jellystructure.shared.tv.Person, sourceTitle: String, displayName: String) {
            val personTmdbId = person.id.toIntOrNull()
            push(Dest.SeededBrowse(
                seedQuery = dev.jellystructure.shared.tv.ConditionGroup(children = listOf(
                    dev.jellystructure.shared.tv.Condition(facet = "cast_crew", op = "is_any_of", values = listOf(person.id)),
                )),
                seedMediaKind = null,
                title = person.name,
                breadcrumb = sourceTitle,
                continueWatching = false,
                displayName = displayName,
                personRoleLine = person.role,
                personTmdbId = personTmdbId,
            ))
        }

        // R221 §B — OK on a genre chip opens the browse page seeded to that genre, the same contract
        // as openPersonBrowse above. A single chip seeds [genreNames] with one value; the `+N` chip
        // seeds the title's full genre set ("everything like this one" — FR-RV-GEN1-4).
        // Open question (spec §"Open questions" #3): whether this should inherit the detail page's own
        // channel scope rather than the whole library is left undecided — always global for now.
        fun openGenreBrowse(genreNames: List<String>, sourceTitle: String, displayName: String) {
            push(Dest.SeededBrowse(
                seedQuery = dev.jellystructure.shared.tv.ConditionGroup(children = listOf(
                    dev.jellystructure.shared.tv.Condition(facet = "genre", op = "is_any_of", values = genreNames),
                )),
                seedMediaKind = null,
                title = genreNames.joinToString(", "),
                breadcrumb = sourceTitle,
                continueWatching = false,
                displayName = displayName,
            ))
        }

        // Shared by the remote-play collector and the R170 ProfileMenu (both need "whatever name the
        // currently visible screen is carrying," without an exhaustive `when` at every call site).
        fun destDisplayName(d: Dest?): String = when (d) {
            is Dest.Home -> d.displayName; is Dest.ChannelView -> d.displayName
            is Dest.Browse -> d.displayName; is Dest.SeededBrowse -> d.displayName; is Dest.Search -> d.displayName
            is Dest.Discover -> d.displayName; is Dest.DiscoverItem -> d.displayName
            is Dest.SeerrSearch -> d.displayName
            is Dest.UpcomingDetail -> d.displayName
            is Dest.MovieDetail -> d.displayName; is Dest.SeriesDetail -> d.displayName
            is Dest.Player -> d.displayName; is Dest.Settings -> d.displayName
            is Dest.YourProfile -> d.displayName; is Dest.ChangePassword -> d.displayName
            else -> null
        } ?: MultiTokenStore.getActive()?.displayName.orEmpty()

        // R155 — remote-control play (Phase 111 / Home Assistant, or the Jellyfin dashboard cast menu
        // via the Phase 110 bridge). Profile guard: only act while a profile is active — a command
        // addressed to a (user, device) that arrives while the picker is up or mid-switch is dropped,
        // never queued (FR-R155-1.2/.3).
        LaunchedEffect(Unit) {
            livePlayItem.collect { env ->
                if (activeUserId == null || stack.lastOrNull() is Dest.ProfilePicker || stack.lastOrNull() is Dest.Login) {
                    return@collect
                }
                val displayName = destDisplayName(stack.lastOrNull())
                when (env.kind) {
                    "series" -> push(Dest.SeriesDetail(env.jellyfinId, displayName))
                    else -> push(Dest.Player(
                        itemId = env.jellyfinId,
                        title = env.title.orEmpty(),
                        displayName = displayName,
                        seriesId = env.jellyfinId,   // R181 — a movie is its own remembered bucket
                    ))
                }
            }
        }
        LaunchedEffect(Unit) {
            liveNavigate.collect { env ->
                if (activeUserId == null) return@collect
                if (env.destination == "home") {
                    resetTo(Dest.Home(MultiTokenStore.getActive()?.displayName.orEmpty()))
                }
            }
        }

        // R80: write the initial URL on first composition, then listen for browser Back/Forward.
        LaunchedEffect(Unit) {
            replaceRoute(stack.last().toRoute())
            installHashListener { _ ->
                fromHistory.flag = true
                navDir = NavDir.Back  // R92: browser Back fires the reverse slide
                pop()
                fromHistory.flag = false
            }
        }

        val dest = stack.last()

        // R145: detect a handset-width screen → tighter gutters + full-width detail content (phone target).
        val windowInfo = LocalWindowInfo.current
        val density = LocalDensity.current
        val compact = remember(windowInfo.containerSize.width, density) {
            val wPx = windowInfo.containerSize.width
            wPx > 0 && with(density) { wPx.toDp() } < 600.dp
        }
        // Orientation-stable counterpart to `compact` — see LocalHandset's doc comment for why
        // width-only breaks for a rotated phone (e.g. video playback, which is landscape-only).
        val handset = remember(windowInfo.containerSize.width, windowInfo.containerSize.height, density) {
            val wPx = windowInfo.containerSize.width; val hPx = windowInfo.containerSize.height
            val shortPx = minOf(wPx, hPx)
            shortPx > 0 && with(density) { shortPx.toDp() } < 600.dp
        }
        // R159 — orientation, not width: a resized browser window or a rotated phone flips this live.
        // Compact controls *sizing*; portrait controls *these overrides* — a portrait phone is usually
        // both, but they're independent signals (e.g. a narrow-but-landscape split-screen window).
        val portrait = remember(windowInfo.containerSize.width, windowInfo.containerSize.height) {
            windowInfo.containerSize.height > windowInfo.containerSize.width
        }

        CompositionLocalProvider(LocalLiveConfig provides liveConfig, LocalLiveAcquisition provides liveAcquisition, LocalServerMessages provides liveServerMessages, LocalPlaystateCommands provides livePlaystateCommands, LocalTileScale provides tileScale, LocalGridColumns provides gridColumns, LocalPortraitGridColumns provides portraitGridColumns, LocalCompact provides compact, LocalHandset provides handset, LocalPortrait provides portrait, LocalServerBaseUrl provides apiClient.baseUrl, LocalUserAvatarUrl provides activeAvatarUrl) {
        // Bug fix: the block below only catches Key.Back as a Compose KeyEvent, which a TV remote's
        // physical back key genuinely sends but Android's system back gesture/button does NOT under
        // gesture navigation — it's intercepted by OnBackPressedDispatcher before Compose ever sees a
        // KeyEvent. Confirmed live on the Pixel 9: back did nothing on any screen without its own
        // on-screen back affordance. This bridges the platform's real back action into the same pop().
        //
        // Bug fix: back at the true root (Home, nothing left to pop) used to fall through to the
        // platform's own default — on Android that's `moveTaskToBack`, leaving the app running in the
        // background instead of closing it. Only Home gets this treatment (not e.g. Login/ProfilePicker,
        // which stay on the platform default) — see rememberExitAction's doc comment.
        val exitApp = rememberExitAction()
        val atHomeRoot = stack.size == 1 && stack.last() is Dest.Home
        // Bug fix (live-tested on soveværelse TV): the Player/LiveTv screens own a deliberate two-step
        // Back (chrome visible -> hide it; chrome already hidden -> exit, PlayerScreen.kt's onBack doc
        // comment at R112). Both PlatformBackHandler here AND this root onKeyEvent block used to run
        // UNCONDITIONALLY regardless of which screen was on top, racing the player's own onBack for the
        // exact same physical Back press. Confirmed live: with the player's chrome visibly on screen, one
        // Back press exited straight to the previous screen instead of just hiding the chrome first --
        // this root-level pop() was winning the race and skipping the player's hide-first step entirely.
        // Excluding Player/LiveTv here lets their own dpadFocusable onBack be the single source of truth
        // for what Back does while one of them is on screen.
        val ownsItsOwnBack = dest is Dest.Player || dest is Dest.LiveTv
        // Bug fix: profileMenuOpen is local overlay state, not part of `stack`, so it was invisible to
        // both this handler and the raw KeyEvent block below. On TV the physical remote's Back key
        // reaches ProfileMenu's own dpadFocusable onKeyEvent first (topmost focusable wins) and closes
        // it — but Android's system back gesture/button never surfaces as a Compose KeyEvent (same gap
        // documented above for screens without their own back affordance) and instead skips straight to
        // this dispatcher-level handler, which had no idea the menu was open and either popped the
        // screen underneath it or exited the app while the menu stayed on screen. Reported live on
        // mobile as "can't be closed." Checking profileMenuOpen first here — and consuming it — fixes
        // both the gesture-back and physical-back-with-a-non-KeyEvent-dispatch cases in one place.
        PlatformBackHandler(enabled = !ownsItsOwnBack && (profileMenuOpen || stack.size > 1 || atHomeRoot)) {
            if (profileMenuOpen) profileMenuOpen = false
            else if (stack.size > 1) pop() else exitApp()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onKeyEvent { ev ->
                    when {
                        ev.type != KeyEventType.KeyDown -> false
                        ev.key == Key.F5 -> { fpsOverlay = !fpsOverlay; true }  // R94 debug toggle
                        ownsItsOwnBack -> false
                        (ev.key == Key.Back || ev.key == Key.Escape || ev.key == Key.Backspace) && stack.size > 1 ->
                            { pop(); true }
                        // Bug fix: same root-exit treatment as PlatformBackHandler above, for the TV
                        // remote's physical Back key (a real Compose KeyEvent, handled here rather than
                        // through PlatformBackHandler's system-gesture bridge).
                        (ev.key == Key.Back || ev.key == Key.Escape || ev.key == Key.Backspace) && atHomeRoot ->
                            { exitApp(); true }
                        else -> false
                    }
                }
        ) {
        // R92: direction-aware transitions — push slides left, pop slides right, resets fade.
        // Slide is 25% of screen width (subtle) at ScreenEnterMs/ScreenExitMs durations.
        // contentKey = class means same-class tab switches (Browse→Browse) skip the transition.
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                when (navDir) {
                    NavDir.Forward ->
                        (slideInHorizontally(tween(RaviloMotion.SCREEN_ENTER_MS)) { it / 4 } +
                            fadeIn(tween(RaviloMotion.SCREEN_ENTER_MS))) togetherWith
                        (slideOutHorizontally(tween(RaviloMotion.SCREEN_EXIT_MS)) { -it / 4 } +
                            fadeOut(tween(RaviloMotion.SCREEN_EXIT_MS)))
                    NavDir.Back ->
                        (slideInHorizontally(tween(RaviloMotion.SCREEN_ENTER_MS)) { -it / 4 } +
                            fadeIn(tween(RaviloMotion.SCREEN_ENTER_MS))) togetherWith
                        (slideOutHorizontally(tween(RaviloMotion.SCREEN_EXIT_MS)) { it / 4 } +
                            fadeOut(tween(RaviloMotion.SCREEN_EXIT_MS)))
                    NavDir.Reset ->
                        fadeIn(tween(RaviloMotion.SCREEN_ENTER_MS)) togetherWith
                            fadeOut(tween(RaviloMotion.SCREEN_EXIT_MS))
                }
            },
            contentKey = { it::class },
        ) { dest -> when (dest) {
            is Dest.ProfilePicker -> {
                val store = remember { ProfilePickerStore() }
                ProfilePickerScreen(
                    store = store,
                    apiClient = apiClient,
                    onProfileSelected = { session ->
                        activeUserId = session.userId
                        activeAvatarUrl = session.avatarUrl
                        refreshConfig()
                        push(Dest.Home(session.displayName))
                    },
                    onSettings = {
                        push(Dest.Settings(MultiTokenStore.getActive()?.displayName ?: ""))
                    },
                )
            }

            is Dest.Login -> {
                val store = remember { LoginStore(apiClient) }
                LoginScreen(
                    store = store,
                    onSignedIn = {
                        val active = MultiTokenStore.getActive()
                        activeUserId = active?.userId
                        activeAvatarUrl = active?.avatarUrl
                        refreshConfig()
                        // Reset the stack so Back from Home doesn't return to the login screen,
                        // and carry the freshly-signed-in user's display name.
                        val name = MultiTokenStore.getActive()?.displayName ?: ""
                        resetTo(Dest.Home(name))
                    },
                    onChangeServer = onChangeServer,
                )
            }

            is Dest.Home -> {
                // R187 — str() is @Composable; hoisted here since it's read inside the onSeeAll callback
                // below, which isn't composable context.
                val homeLabel = str("nav.home")
                val store = keptStore("home:${dest.displayName}") { HomeStore(apiClient, seedFeed = initialSnapshot?.feed) }
                val da by store.discoverAvailable.collectAsState()
                SideEffect { discoverAvailable = da }
                val ua by store.upcomingAvailable.collectAsState()
                SideEffect { upcomingAvailable = ua }
                // R141: on every Home re-entry (including Back-returns), emit on liveConfig so the store
                // does a silent re-pull. HomeStore.refresh(silent=true) keeps the current content visible
                // and swaps in the new feed when it arrives — no Loading flash.
                LaunchedEffect(Unit) { liveConfig.emit(0L) }
                // R212 — write through the combined feed + display-settings snapshot whenever Home has
                // fresh content, so the next cold start can seed instantly instead of a bare shimmer.
                // Always an exact copy of what's already on screen — never computed/derived.
                val homeState by store.state.collectAsState()
                LaunchedEffect(homeState, lang, themeState.skin, tileScale, gridColumns, portraitGridColumns) {
                    val loaded = homeState as? HomeState.Loaded ?: return@LaunchedEffect
                    val uid = activeUserId ?: return@LaunchedEffect
                    HomeSnapshotCache.save(uid, HomeSnapshot(
                        feed = loaded.feed,
                        uiLanguage = lang,
                        skin = themeState.skin,
                        tileScale = tileScale,
                        gridColumns = gridColumns,
                        portraitGridColumns = portraitGridColumns,
                        savedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                    ))
                }
                HomeScreen(
                    store = store,
                    apiClient = apiClient,
                    displayName = dest.displayName,
                    onProfile = { profileMenuOpen = true },
                    onSignOut = { resetTo(Dest.Login) },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> {} // already home
                            RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onItemPlay = { card ->
                        when {
                            // A series needs episode-resolution (resume point + rail) that only the
                            // detail screen has, so Play opens it; a movie plays directly.
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.Player(card.id, card.title, displayName = dest.displayName, seriesId = card.id, posterUrl = card.posterUrl))  // R192
                        }
                    },
                    onChannelSelect = { ch -> push(Dest.ChannelView(ch, dest.displayName)) },
                    // R187 (FR-RV-BROWSE1-1) — seeded to the row that was actually pressed, not the
                    // whole library; ContentRowItem only offers the tile when the row has a resolvable
                    // seed (see its canSeeAll check).
                    onSeeAll = { row ->
                        push(Dest.SeededBrowse(
                            seedQuery = row.seedQuery, seedMediaKind = row.seedMediaKind,
                            title = row.title, breadcrumb = homeLabel,
                            continueWatching = row.kind == RowKind.CONTINUE, displayName = dest.displayName,
                        ))
                    },
                    onLiveTvChannelSelect = { ch -> push(Dest.LiveTv(ch.channelId, dest.displayName)) },
                    onOpenLiveTvGuide = { push(Dest.LiveTvGuide(dest.displayName)) },
                )
            }

            is Dest.ChannelView -> {
                val store = keptStore("channel:${dest.displayName}:${dest.channel.id}") { ChannelStore(apiClient) }
                ChannelScreen(
                    channel = dest.channel,
                    store = store,
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    onBack = { pop() },
                    onNavSelect = { idx ->   // R136: nav tabs on the channel page
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onSeeAll = { row ->
                        push(Dest.SeededBrowse(
                            seedQuery = row.seedQuery, seedMediaKind = row.seedMediaKind,
                            title = row.title, breadcrumb = dest.channel.name,
                            continueWatching = row.kind == RowKind.CONTINUE, displayName = dest.displayName,
                            // R219 (FR-R219-6) — dest.channel was already in scope here and simply
                            // unused for this case; only meaningful for the Continue row (a non-Continue
                            // row's seedQuery is already channel-aware via withChannelSeed server-side).
                            channelId = if (row.kind == RowKind.CONTINUE) dest.channel.id else null,
                        ))
                    },
                )
            }

            is Dest.SeededBrowse -> {
                // R219 (FR-R219-6): channelId is part of the key too — Home's and a channel's Continue
                // See-all otherwise share the same title ("Continue Watching") and would wrongly reuse
                // each other's cached store/result.
                val storeKey = "seededBrowse:${dest.displayName}:${dest.title}:${dest.continueWatching}:${dest.channelId}"
                val store = keptStore(storeKey) {
                    SeededBrowseStore(apiClient, dest.seedQuery, dest.seedMediaKind, dest.continueWatching, dest.personTmdbId, dest.channelId)
                }
                SeededBrowseScreen(
                    store = store,
                    title = dest.title,
                    breadcrumb = dest.breadcrumb,
                    subtitle = dest.breadcrumb?.let { str("browse.from_row", mapOf("row" to it)) },
                    showTypeFacet = dest.seedMediaKind == null,
                    showFacetBar = !dest.continueWatching,
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    activeNav = dest.activeNav,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    personRoleLine = dest.personRoleLine,
                    onRequestSelect = dest.personTmdbId?.let { { e: dev.jellystructure.shared.tv.DiscoverEntry ->
                        push(Dest.DiscoverItem(if (e.entry.mediaKind == MediaKind.SERIES) "tv" else "movie", e.entry.tmdbId, dest.displayName))
                    } },
                )
            }

            // R187 fix (issue #1) — Movies/Series are no longer their own bespoke grid: they're
            // SeededBrowseScreen with a fixed seedMediaKind and no other seed, the SAME shared component
            // as a row's "→ See all" drill-in — full facet bar, sort, everything. My List keeps the old
            // BrowseStore/BrowseScreen: it isn't expressible as a ConditionGroup seed (it's per-user saved-
            // list membership, a different backend query shape entirely), so unifying it isn't a same-day change.
            is Dest.Browse -> if (dest.kind == BrowseKind.MOVIES || dest.kind == BrowseKind.SERIES) {
                val storeKey = "seededTab:${dest.displayName}:${dest.kind}"
                // /tv/browse/seeded's mediaKind matches MediaKind's enum name ("MOVIE"/"SERIES", see
                // BrowseService.browseByQuery) — NOT BrowseKind.apiKey's "movie"/"series", which is the
                // OLD plain /tv/browse endpoint's own (different) convention.
                val seedKind = if (dest.kind == BrowseKind.MOVIES) "MOVIE" else "SERIES"
                val store = keptStore(storeKey) {
                    SeededBrowseStore(apiClient, seedQuery = null, seedMediaKind = seedKind, continueWatching = false)
                }
                val tabTitle = if (dest.kind == BrowseKind.MOVIES) str("nav.movies") else str("nav.series")
                SeededBrowseScreen(
                    store = store,
                    title = tabTitle,
                    breadcrumb = null,
                    subtitle = null,
                    showTypeFacet = false,
                    showFacetBar = true,
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    activeNav = if (dest.kind == BrowseKind.MOVIES) 1 else 2,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> replaceTop(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> replaceTop(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                )
            } else {
                val store = keptStore("browse:${dest.displayName}:${dest.kind}") { BrowseStore(apiClient) }
                BrowseScreen(
                    kind = dest.kind,
                    store = store,
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> replaceTop(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> replaceTop(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Search -> {
                val store = keptStore("search:${dest.displayName}") { SearchStore(apiClient) }
                SearchScreen(
                    store = store,
                    onBack = { pop() },
                    onItemSelect = { openDetail(it, dest.displayName) },
                )
            }

            // R170 — the merged Discover tab: `segment` picks which of the two (formerly separate-tab)
            // screens renders. Each gets a switch-pill to flip to the other segment in place
            // (replaceTop, same Dest class ⇒ AnimatedContent's contentKey skips the slide transition —
            // same treatment as any other same-class tab switch, e.g. Browse→Browse).
            is Dest.Discover -> when (dest.segment) {
                DiscoverSegment.COMING_SOON -> {
                    val store = keptStore("upcoming:${dest.displayName}") { UpcomingStore(apiClient) }
                    UpcomingScreen(
                        store = store,
                        displayName = dest.displayName,
                        onNavSelect = { idx ->
                            when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                                RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                                RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                                RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                                // Bug fix: this used to be a hard no-op ("already on Discover"), but since
                                // R170 merged two separately-navigable tabs (Upcoming, Discover/Top10) into
                                // one shared nav slot, that no-op now fires for BOTH segments — and this
                                // screen's own two-stage-Back (`atTop = { navBarFocused }`) + entry-focus
                                // routinely park D-pad focus directly on this now-inert button, unlike
                                // Home/Browse/Channel which refocus real content instead. Reuse the same
                                // switch already wired to the in-screen pill (onSwitchToRequest below) so
                                // the nav button does the pre-R170-equivalent thing: flip segments when the
                                // other one exists, instead of nothing.
                                RaviloNavTarget.DISCOVER -> if (discoverAvailable) replaceTop(Dest.Discover(dest.displayName, DiscoverSegment.REQUEST)) else Unit
                            }
                        },
                        onProfile = { profileMenuOpen = true },
                        onSearch = { push(Dest.Search(dest.displayName)) },
                        onItemSelect = { item ->
                            val itemId = item.itemId
                            when {
                                itemId != null && item.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(itemId, dest.displayName))
                                itemId != null -> push(Dest.MovieDetail(itemId, dest.displayName))
                                else -> push(Dest.UpcomingDetail(item.id, dest.displayName))
                            }
                        },
                        onSwitchToRequest = if (discoverAvailable) {
                            { replaceTop(Dest.Discover(dest.displayName, DiscoverSegment.REQUEST)) }
                        } else null,
                    )
                }
                DiscoverSegment.REQUEST -> {
                    val store = keptStore("discover:${dest.displayName}") { DiscoverStore(apiClient) }
                    DiscoverScreen(
                        store = store,
                        displayName = dest.displayName,
                        onNavSelect = { idx ->
                            when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                                RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                                RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                                RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                                // Same fix as the COMING_SOON branch above, mirrored: switch to the other
                                // segment instead of no-op'ing when it exists.
                                RaviloNavTarget.DISCOVER -> if (upcomingAvailable) replaceTop(Dest.Discover(dest.displayName, DiscoverSegment.COMING_SOON)) else Unit
                            }
                        },
                        onEntrySelect = { mediaType, tmdbId -> push(Dest.DiscoverItem(mediaType, tmdbId, dest.displayName)) },
                        onProfile = { profileMenuOpen = true },
                        onSearch = { push(Dest.Search(dest.displayName)) },
                        onSearchSeerr = { push(Dest.SeerrSearch(dest.displayName)) },
                        upcomingAvailable = upcomingAvailable,
                        onSwitchToComingSoon = if (upcomingAvailable) {
                            { replaceTop(Dest.Discover(dest.displayName, DiscoverSegment.COMING_SOON)) }
                        } else null,
                    )
                }
            }

            is Dest.DiscoverItem -> {
                val store = remember(dest.mediaType, dest.tmdbId) {
                    DiscoverDetailStore(apiClient, dest.mediaType, dest.tmdbId, onLocalAcquisition = { liveAcquisition.tryEmit(it) })
                }
                DiscoverDetailScreen(
                    store = store,
                    onWatchMovie = { itemId, title -> push(Dest.Player(itemId, title, displayName = dest.displayName, seriesId = itemId)) },
                    onGoToSeries = { itemId -> push(Dest.SeriesDetail(itemId, dest.displayName)) },
                )
            }

            is Dest.SeerrSearch -> {
                val store = keptStore("seerrsearch:${dest.displayName}") { SeerrSearchStore(apiClient) }
                SeerrSearchScreen(
                    store = store,
                    onBack = { pop() },
                    onEntrySelect = { mediaType, tmdbId -> push(Dest.DiscoverItem(mediaType, tmdbId, dest.displayName)) },
                )
            }

            is Dest.UpcomingDetail -> {
                val store = remember(dest.id) { UpcomingDetailStore(apiClient, dest.id) }
                UpcomingDetailScreen(store = store)
            }

            is Dest.MovieDetail -> {
                val store = keptStore("movie:${dest.displayName}:${dest.itemId}") { MovieDetailStore(apiClient) }
                MovieDetailScreen(
                    itemId = dest.itemId,
                    store = store,
                    onBack = { pop() },
                    onPlay = { detail ->
                        push(Dest.Player(
                            detail.card.id,
                            detail.card.title,
                            displayName = dest.displayName,
                            seriesId = detail.card.id,   // R181 — a movie is its own remembered bucket
                            originalLanguage = detail.originalLanguage,
                            segments = detail.segments,  // Phase 150
                            posterUrl = detail.card.posterUrl,  // R192
                        ))
                    },
                    onRelatedSelect = { openDetail(it, dest.displayName) },
                    onCastSelect = { person, sourceTitle -> openPersonBrowse(person, sourceTitle, dest.displayName) },
                    onGenreSelect = { genres, sourceTitle -> openGenreBrowse(genres, sourceTitle, dest.displayName) },
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> resetTo(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> resetTo(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> resetTo(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.SeriesDetail -> {
                val store = keptStore("series:${dest.displayName}:${dest.itemId}") { SeriesDetailStore(apiClient) }
                SeriesDetailScreen(
                    itemId = dest.itemId,
                    store = store,
                    onBack = { pop() },
                    onPlay = { ctx ->
                        push(Dest.Player(
                            itemId        = ctx.episodeId,
                            title         = ctx.episodeTitle,
                            kicker        = ctx.kicker,
                            nextEpId      = ctx.nextEpId,
                            nextEpLabel   = ctx.nextEpLabel,
                            nextEpTitle   = ctx.nextEpTitle,
                            displayName   = dest.displayName,
                            episodes      = ctx.episodes,
                            currentEpIndex = ctx.currentEpIndex,
                            seriesId      = ctx.seriesId,
                            originalLanguage = ctx.originalLanguage,
                            segments      = ctx.segments,  // Phase 150
                            posterUrl     = ctx.seriesPosterUrl,  // R194
                        ))
                    },
                    onRelatedSelect = { openDetail(it, dest.displayName) },
                    onCastSelect = { person, sourceTitle -> openPersonBrowse(person, sourceTitle, dest.displayName) },
                    onGenreSelect = { genres, sourceTitle -> openGenreBrowse(genres, sourceTitle, dest.displayName) },
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> resetTo(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> resetTo(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> resetTo(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Player -> {
                // remember(dest.itemId) — an auto-advance/next-episode does replaceTop(Dest.Player(…)),
                // which keeps the SAME PlayerScreen composed and only swaps dest.itemId, so a new store
                // is built per episode. Bug fix: the outgoing one used to be silently dropped and kept
                // running — its 10s progress heartbeat went on POSTing for the finished episode's id with
                // the CURRENT episode's playhead (N episodes ⇒ N phantom "Now Playing" sessions on the
                // Jellyfin dashboard, and trashed resume positions ⇒ wrong Continue Watching). Closing it
                // on dispose cancels that heartbeat and reports a final stop for the episode we left.
                val store = remember(dest.itemId) { PlayerStore(apiClient) }
                DisposableEffect(store) { onDispose { store.close() } }
                PlayerScreen(
                    itemId           = dest.itemId,
                    itemTitle        = dest.title,
                    itemKicker       = dest.kicker,
                    nextEpisodeId    = dest.nextEpId,
                    nextEpisodeLabel = dest.nextEpLabel,
                    nextEpisodeTitle = dest.nextEpTitle,
                    episodes         = dest.episodes,
                    currentEpIndex   = dest.currentEpIndex,
                    seriesId         = dest.seriesId,
                    originalLanguage = dest.originalLanguage,
                    segments         = dest.segments,  // Phase 150
                    posterUrl        = dest.posterUrl,  // R192
                    store            = store,
                    onBack           = { pop() },
                    // R237 (FR-R237-3) — the player told the viewer this TV needs to be signed in
                    // again; this is the only control that can act on that. Same shape as R234's
                    // onForceSignOut: revoke and forget just this profile, then land on the picker if
                    // another cached profile remains, else the login gate.
                    onReauthRequired = {
                        configScope.launch {
                            signOutActiveSession(apiClient)
                            resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                        }
                    },
                    onNavigateToEpisode = { nextId ->
                        // Bug fix: this used to bail out silently whenever `dest.episodes` was absent or
                        // `nextId` wasn't in it — the player had no way to know the navigation never
                        // happened, so its credits card re-armed on the next poll tick and re-requested
                        // the same advance over and over ("the auto play next episode doesn't work and
                        // ends in a forever loop"). Now the target id ALWAYS plays: an id we can't place
                        // in the list falls back to the outgoing card's own next-episode labels and
                        // simply carries no further next-episode target (so the binge stops cleanly at
                        // that episode) instead of trapping playback in a retry loop.
                        val eps = dest.episodes
                        val newIdx = eps?.indexOfFirst { it.id == nextId } ?: -1
                        val newEp = if (newIdx >= 0) eps?.get(newIdx) else null
                        val nextEp = if (newIdx >= 0) eps?.getOrNull(newIdx + 1) else null
                        if (nextId != dest.itemId) replaceTop(Dest.Player(
                            itemId         = nextId,
                            title          = newEp?.title ?: dest.nextEpTitle ?: dest.title,
                            kicker         = newEp?.kicker ?: dest.nextEpLabel,
                            nextEpId       = nextEp?.id,
                            nextEpLabel    = nextEp?.kicker,
                            nextEpTitle    = nextEp?.title,
                            displayName    = dest.displayName,
                            episodes       = eps,
                            currentEpIndex = newIdx.coerceAtLeast(0),
                            // R181 — same series, same original language, for the whole binge.
                            seriesId         = dest.seriesId,
                            originalLanguage = dest.originalLanguage,
                            // Phase 150 — the NEW episode's own segments; an unplaceable id has none, and
                            // must NOT inherit the outgoing episode's (a stale credits marker would fire
                            // the next-up countdown from the start of the new stream).
                            segments         = newEp?.segments ?: dev.jellystructure.shared.tv.TvSegmentMarkers(),
                            posterUrl        = dest.posterUrl,  // R194 — same series poster fallback for the whole binge
                        ))
                    },
                )
            }

            is Dest.LiveTv -> {
                // remember(dest.channelId) — a zap/number-entry re-tune inside the player mutates the
                // SAME store instance in place (LiveTvPlayerStore.tune), it does not push a new Dest;
                // this key only matters if the caller navigates to a genuinely different channel Dest.
                val store = remember(dest.channelId) { LiveTvPlayerStore(apiClient) }
                LiveTvPlayerScreen(
                    channelId = dest.channelId,
                    store = store,
                    onBack = { pop() },
                )
            }

            is Dest.LiveTvGuide -> {
                val store = remember { LiveTvGuideStore(apiClient) }
                LiveTvGuideScreen(
                    store = store,
                    displayName = dest.displayName,
                    discoverAvailable = upcomingAvailable || discoverAvailable,
                    onBack = { pop() },
                    // Bug fix: this used to replaceTop the guide itself with the LiveTv player, which
                    // dropped the guide from the stack — Back from the player then skipped straight to
                    // whatever was below the guide (Home), not back to the guide the user actually came
                    // from. push() keeps the guide on the stack so Back unwinds one screen at a time,
                    // same as tuning from anywhere else (e.g. Home's On Now row already pushes).
                    onTuneChannel = { ch -> push(Dest.LiveTv(ch.channelId, dest.displayName)) },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx, upcomingAvailable || discoverAvailable)) {
                            RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                            RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable)))
                        }
                    },
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Settings -> {
                val store = remember { SettingsStore(apiClient) }
                SettingsScreen(
                    store = store,
                    displayName = dest.displayName,
                    onSkinChange = { themeState.skin = it },
                    // R191 — SettingsScreen already revoked/forgot just the active profile
                    // (store.signOutActiveSession()) before this fires; route to the profile picker
                    // if another cached profile remains, else all the way back to Login.
                    onSignOut = {
                        resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                    },
                    onBack = { pop() },
                    // R161: unpair revokes every session this device holds (store.unpairDevice() has
                    // already cleared MultiTokenStore by the time this fires) — always lands on the
                    // login gate, matching the "no sessions" boot state.
                    onUnpair = { resetTo(Dest.Login) },
                    onChangePassword = { push(Dest.ChangePassword(dest.displayName)) },
                )
            }

            is Dest.YourProfile -> dev.jellystructure.ravilo.ui.screens.YourProfileScreen(
                apiClient = apiClient,
                displayName = dest.displayName,
                initialAvatarUrl = activeAvatarUrl,
                onBack = { pop() },
                onAvatarChanged = { activeAvatarUrl = it },
            )

            is Dest.ChangePassword -> dev.jellystructure.ravilo.ui.screens.ChangePasswordScreen(
                apiClient = apiClient,
                onBack = { pop() },
                // FR-R234-7 — this Jellyfin version never actually takes this branch (probed: tokens
                // survive a password change), but the client must still render the truth it's told,
                // not a hard-coded assumption — see ChangePasswordScreen's own doc.
                onForceSignOut = {
                    configScope.launch {
                        signOutActiveSession(apiClient)
                        resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                    }
                },
            )
        } } // when / AnimatedContent
        // R170 — the avatar's dropdown: My List/Settings/Switch profile/Unpair, replacing the old
        // straight-to-picker click. Rendered over whatever screen is current, same tier as the
        // debug/message overlays below.
        if (profileMenuOpen) {
            val currentDisplayName = destDisplayName(dest)
            ProfileMenu(
                apiClient = apiClient,
                onClose = { profileMenuOpen = false },
                onMyList = { profileMenuOpen = false; push(Dest.Browse(BrowseKind.MY_LIST, currentDisplayName)) },
                onSettings = { profileMenuOpen = false; push(Dest.Settings(currentDisplayName)) },
                onSwitchProfile = { profileMenuOpen = false; push(Dest.ProfilePicker) },
                onYourProfile = { profileMenuOpen = false; push(Dest.YourProfile(currentDisplayName)) },
                // R175 — "Add user" opens the same LoginScreen; a successful sign-in resets the stack
                // to the new profile's Home (see the Dest.Login branch above).
                onAddUser = { profileMenuOpen = false; push(Dest.Login) },
                // R191 — mirrors onUnpair's shape but only one profile was revoked/forgotten; go to
                // the picker if another cached profile remains, else all the way to Login.
                onSignedOut = {
                    profileMenuOpen = false
                    resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                },
                onUnpaired = { profileMenuOpen = false; resetTo(Dest.Login) },
            )
        }
        FrameTrackerOverlay(fpsOverlay)  // R94: F5 toggles; no-op when false
        ServerMessageHost()  // R152: floats over every screen incl. the player (reads LocalServerMessages)
        } // Box (back-intercept)
        } // CompositionLocalProvider (live config)
    } // WithLocale
    } // RaviloTheme
}
