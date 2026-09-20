package dev.jellystructure.ravilo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.focus.BackToTopRegistry
import dev.jellystructure.ravilo.ui.focus.LocalBackToTop
import dev.jellystructure.ravilo.i18n.resolveAndRememberLanguage
import dev.jellystructure.ravilo.ui.screens.installDeviceLanguageStore
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
import dev.jellystructure.ravilo.ui.screens.discoverSegments
import dev.jellystructure.ravilo.ui.screens.nextDiscoverSegment
import dev.jellystructure.ravilo.ui.screens.seedFacet
import dev.jellystructure.ravilo.ui.screens.CastRemoteScreen
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.CastConnectingBar
import dev.jellystructure.ravilo.ui.components.CastMiniBar
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.LocalCastHandoff
import dev.jellystructure.ravilo.ui.components.castArtFor
import dev.jellystructure.ravilo.ui.components.castEpisodes
import dev.jellystructure.ravilo.ui.seams.rememberCastSender
import dev.jellystructure.ravilo.ui.screens.TaxonomyStore
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
import dev.jellystructure.ravilo.ui.screens.UpcomingStore
import dev.jellystructure.ravilo.ui.screens.WatchedBus
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.ravilo.ui.i18n.str
import coil3.compose.LocalPlatformContext
import androidx.compose.foundation.layout.padding
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.components.BottomNavItem
import dev.jellystructure.ravilo.ui.components.LibraryTypePill
import dev.jellystructure.ravilo.ui.components.RaviloBottomNav
import dev.jellystructure.ravilo.ui.components.ProfileMenu
import dev.jellystructure.ravilo.ui.components.ServerMessageHost
import dev.jellystructure.ravilo.ui.components.UpdateToast
import dev.jellystructure.ravilo.ui.perf.FrameTrackerOverlay
import dev.jellystructure.ravilo.ui.seams.prefetchImage
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
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

/**
 * R237 (FR-R237-3) — what to do when the player reports that this TV must sign in again: revoke and
 * forget just this profile, then land on the picker or the login gate (the same exit R234's forced
 * sign-out uses). Null ⇒ the player offers Back alone.
 *
 * Deliberately a CompositionLocal rather than a `PlayerScreen` parameter. It was a parameter first,
 * and that shipped a release-build crash: `PlayerScreen` already took 15 arguments, and the 16th
 * pushed the Compose compiler's generated `$changed`/`$default` mask arrangement into a shape ART's
 * verifier rejects outright — `java.lang.VerifyError: Verifier rejected class …PlayerScreen…
 * register v2 has type Precise Reference: java.lang.String but expected Integer`. The player died
 * the instant it was opened. It reproduces ONLY in the R8-minified release build, so every compile
 * check and unit test passed; it was caught by opening an episode on the stue TV. Adding another
 * parameter to `PlayerScreen` will bring it back — route new inputs through here or a holder object.
 */
val LocalReauthRequired = staticCompositionLocalOf<(() -> Unit)?> { null }

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
        /** R253 (FR-R253-2) — the row's own order (225's `Row.sort_by`/`sort_descending`), so the page OPENS
         *  in it. An initial value only; null = R187's default. The pin list never reaches the client. */
        val sortBy: String? = null,
        val sortDescending: Boolean? = null,
        /** R219 (FR-R219-6) — set only alongside [continueWatching] == true, when reached from a
         *  channel's own Continue row; forwarded to [SeededBrowseStore] unchanged. */
        val channelId: String? = null,
    ) : Dest()
    /** R277 — [focusInput] is set by tapping the bottom bar's Search item while Search is already
     *  showing, and is consumed by the screen (replaceTop with it cleared) so the next tap sets a
     *  fresh one. Same shape as [Discover.focusSegment], and for the same reason. */
    data class Search(val displayName: String, val focusInput: Boolean = false) : Dest()
    // R170 — Coming Soon (the old Upcoming tab) and Request (the old Top-10/Discover tab) are now the
    // two segments of one merged Discover tab; `segment` decides which of UpcomingScreen/DiscoverScreen
    // actually renders (see DiscoverSegment/defaultDiscoverSegment in NavItems.kt).
    // R243 — [focusSegment] is set by a segment-bar switch (replaceTop), so the next screen keeps
    // focus on the chip that was pressed rather than parking it on the AppBar (FR-R243-7).
    data class Discover(val displayName: String, val segment: DiscoverSegment, val focusSegment: Boolean = false) : Dest()
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
    // R245 (FR-R245-7) — the full-screen remote for a running cast. Reached from the mini bar, from a
    // detail screen's "Play on {device}", or by the hand-off from inside the local player.
    data class CastRemote(val displayName: String) : Dest()

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
        is CastRemote     -> "/cast"
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

    // R279 — the language ladder: the signed-in user's own setting (carried on the cached snapshot
    // at cold start, refreshed by refreshConfig() below), else whatever this device last drew in,
    // else English. The middle rung is what makes the login screen, the profile picker and the
    // pre-config frames of a cold start come up in the household's language instead of English.
    remember { installDeviceLanguageStore() }
    var lang by remember { mutableStateOf(resolveAndRememberLanguage(initialSnapshot?.uiLanguage)) }
    var tileScale by remember { mutableStateOf(initialSnapshot?.tileScale ?: 1f) }
    // R174 — grid columns, server-pushed on the config; portrait falls back to the built-in 2.
    var gridColumns by remember { mutableStateOf(initialSnapshot?.gridColumns ?: 6) }
    var portraitGridColumns by remember { mutableStateOf(initialSnapshot?.portraitGridColumns ?: 2) }
    val themeState = rememberRaviloTheme(initial = initialSnapshot?.skin ?: Skin.AURORA)

    // Fetch the active user's config and apply server-owned interface prefs (language + skin)
    val configScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // R245 / 218 (FR-218-3/11) — the cast capability rides the config snapshot: absent ⇒ no button.
    var castAppId by remember { mutableStateOf<String?>(null) }
    // R265 (dev review item 5) — same shape as castAppId: server-pushed, so the glyph can be present
    // for a household's very first screen (an empty device list alone can't answer "is this on at all").
    var screensEnabled by remember { mutableStateOf(false) }
    fun refreshConfig() {
        configScope.launch {
            runCatching { apiClient.getConfig() }.getOrNull()?.let { cfg ->
                castAppId = cfg.cast?.appId
                screensEnabled = cfg.screens?.enabled == true
                // Remembered as well as applied, so signing out of this profile does not take the
                // household's language with it.
                lang = resolveAndRememberLanguage(cfg.uiLanguage)
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
    // R248 (FR-R248-2) — the server folded a stop into this user's Home feed; collected below against
    // the retained Home/channel stores (not the screens), so a push that lands while the player is still
    // on top refreshes the feed the viewer is about to return to.
    val liveHome = remember { MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 16) }
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
                    onHomeChanged = { liveHome.emit(it) },
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
        // R248 (FR-R248-2/4) — a `home_changed` push refreshes every retained Home and channel store,
        // visible or not; the screens themselves never re-derive anything (FR-R248-5).
        LaunchedEffect(Unit) {
            liveHome.collect {
                storeRegistry.values.forEach { s ->
                    when (s) {
                        is HomeStore -> s.onHomeChanged()
                        is ChannelStore -> s.onHomeChanged()
                    }
                }
            }
        }

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

        // R243 (FR-R243-5/6) — OK on a wall tile opens the browse page seeded to that value through the
        // SAME path an R221 genre chip uses (a workbench condition on the seeded endpoint), so a genre
        // tile opens exactly the page a genre chip opens. A network seed carries kind SERIES because
        // Phase 216 counts networks for series only (FR-216-4); studios and genres count every kind.
        fun openTaxonomyBrowse(segment: DiscoverSegment, item: dev.jellystructure.shared.tv.FacetItem, crumb: String, displayName: String) {
            push(Dest.SeededBrowse(
                seedQuery = dev.jellystructure.shared.tv.ConditionGroup(children = listOf(
                    dev.jellystructure.shared.tv.Condition(facet = segment.seedFacet(), op = "is_any_of", values = listOf(item.name)),
                )),
                seedMediaKind = if (segment == DiscoverSegment.NETWORKS) "SERIES" else null,
                title = item.name,
                breadcrumb = crumb,
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
            is Dest.Player -> d.displayName; is Dest.Settings -> d.displayName; is Dest.CastRemote -> d.displayName
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

        // R267 (FR-R267-5/-7) — which bottom item this destination IS, or null when the bar is absent.
        //
        // Absent on every PUSHED destination — detail pages, the player, the cast remote, the profile
        // picker, account screens, seeded grids — each of which has its own Back and sits on a higher
        // layer. The bar belongs to the four pages and nowhere else, which is also what stops it
        // overlaying video.
        //
        // My List is a pushed Browse (it arrives from the profile menu), so it is deliberately NOT
        // Library: `Dest.Browse` alone is not enough to decide.
        // R278 (FR-R278-1) — whether there IS a bar, which is a different question from which item is
        // lit and must not be answered by bottomItemOf() again. Absent only where the picture is
        // playing, where the page is one title's detail, and before a profile has been chosen (that
        // last one is R275 FR-R275-2's reasoning: four pages that do not exist for a signed-out
        // viewer). Everything else keeps it, including every pushed list and the account screens —
        // superseding R267 FR-R267-7's "absent on anything pushed over a page".
        fun bottomBarShows(d: Dest): Boolean = when (d) {
            is Dest.Player, is Dest.LiveTv, is Dest.CastRemote -> false
            is Dest.MovieDetail, is Dest.SeriesDetail, is Dest.DiscoverItem, is Dest.UpcomingDetail -> false
            is Dest.Login, is Dest.ProfilePicker -> false
            else -> true
        }

        fun bottomItemOf(d: Dest): BottomNavItem? = when {
            d is Dest.Home -> BottomNavItem.HOME
            d is Dest.Browse && d.kind != BrowseKind.MY_LIST -> BottomNavItem.LIBRARY
            d is Dest.Search -> BottomNavItem.SEARCH
            d is Dest.Discover -> BottomNavItem.DISCOVER
            else -> null
        }

        // R145: detect a handset-width screen → tighter gutters + full-width detail content (phone target).
        val windowInfo = LocalWindowInfo.current
        val density = LocalDensity.current
        val compact = remember(windowInfo.containerSize.width, density) {
            val wPx = windowInfo.containerSize.width
            wPx > 0 && with(density) { wPx.toDp() } < 600.dp
        }
        // Orientation-stable counterpart to `compact` — see LocalHandset's doc comment for why
        // width-only breaks for a rotated phone (e.g. video playback, which is landscape-only).
        // R256 — never from dp alone: a TV is 960 x 540 dp, i.e. "handset-sized". See [isHandset].
        val handset = remember(windowInfo.containerSize.width, windowInfo.containerSize.height, density) {
            isHandset(isTvPlatform, windowInfo.containerSize.width, windowInfo.containerSize.height, density.density)
        }
        // R159 — orientation, not width: a resized browser window or a rotated phone flips this live.
        // Compact controls *sizing*; portrait controls *these overrides* — a portrait phone is usually
        // both, but they're independent signals (e.g. a narrow-but-landscape split-screen window).
        val portrait = remember(windowInfo.containerSize.width, windowInfo.containerSize.height) {
            windowInfo.containerSize.height > windowInfo.containerSize.width
        }

        // R245/R265 — one sender per app (composed: the platform Chromecast SDK where one exists, plus
        // the common ScreenSender always), one controller per server. Unlike R245 alone, the sender is
        // never null now (a screen needs no platform SDK) — presence of ANY button/sheet is instead
        // gated on castActive below, true when the server has EITHER capability (FR-R265-1).
        // R275 (FR-R275-5) — where a page declares its own top, for the one Back handler that a phone's
        // system Back actually reaches. Empty on the TV, which keeps the key-event path.
        val backToTop = remember { BackToTopRegistry() }
        val castSender = rememberCastSender(apiClient)
        val castController = remember(castSender, apiClient) { CastController(castSender, apiClient, apiClient.baseUrl) }
        LaunchedEffect(castController, castAppId) { castAppId?.let { castController.appId = it } }
        val castActive = if (castAppId != null || screensEnabled) castController else null
        val currentDisplayNameForCast = destDisplayName(dest)
        // R265 — this in-player quick hand-off is still Chromecast's own hand-off-code flow
        // (CastController.cast()); a screen has no hand-off code (FR-R265-6) and isn't offered this
        // shortcut yet — gated on castAppId specifically, not the broader castActive, so enabling
        // screens alone can't accidentally route through Chromecast's castHandoff() call.
        CompositionLocalProvider(LocalCast provides castActive, LocalCastHandoff provides (if (castActive != null && castAppId != null && dest is Dest.Player) { pos: Long ->
            val d = dest as Dest.Player
            castActive.cast(
                itemId = d.itemId, title = d.title, kicker = d.kicker,
                artUrl = resolveCastArt(apiClient.baseUrl, castArtFor(null, d.episodes?.getOrNull(d.currentEpIndex)?.stillUrls?.firstOrNull { it != null })),
                positionMs = pos, episodes = castEpisodes(d.episodes), currentIndex = d.currentEpIndex, lang = lang,
            )
            replaceTop(Dest.CastRemote(d.displayName))
        } else null),
            LocalBackToTop provides backToTop,
            LocalLiveConfig provides liveConfig, LocalLiveAcquisition provides liveAcquisition, LocalServerMessages provides liveServerMessages, LocalPlaystateCommands provides livePlaystateCommands, LocalTileScale provides tileScale, LocalGridColumns provides gridColumns, LocalPortraitGridColumns provides portraitGridColumns, LocalCompact provides compact, LocalHandset provides handset, LocalPortrait provides portrait, LocalServerBaseUrl provides apiClient.baseUrl, LocalUserAvatarUrl provides activeAvatarUrl,
            LocalReauthRequired provides {
                configScope.launch {
                    signOutActiveSession(apiClient)
                    resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                }
            }) {
        // Bug fix: the block below only catches Key.Back as a Compose KeyEvent, which a TV remote's
        // physical back key genuinely sends but Android's system back gesture/button does NOT under
        // gesture navigation — it's intercepted by OnBackPressedDispatcher before Compose ever sees a
        // KeyEvent. Confirmed live on the Pixel 9: back did nothing on any screen without its own
        // on-screen back affordance. This bridges the platform's real back action into the same pop().
        //
        // Bug fix: back at the true root (Home, nothing left to pop) used to fall through to the
        // platform's own default — measured (R260) to finish the Activity outright, not background it —
        // leaving the app closed in a way that reads as a crash rather than an intentional exit. Only
        // Home gets this treatment (not e.g. Login/ProfilePicker, which stay on the platform default) —
        // see rememberExitAction's doc comment.
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
        // R275 — the four bottom-bar pages all arrive by replaceTop, so the stack is size 1 on every one
        // of them: on Library, Search and Discover this handler was not even enabled, the platform
        // default ran, and Back closed the app (R260 measured that default as an outright Activity
        // finish). FR-R275-2 gives them Home instead, scoped by the same bottomItemOf() the bar is drawn
        // from so the two cannot drift — and NOT extended to Login/ProfilePicker, which have no Home to
        // go to and keep the platform default per rememberExitAction's doc comment.
        val backGoesHome = handset && bottomItemOf(dest) != null && dest !is Dest.Home
        PlatformBackHandler(enabled = !ownsItsOwnBack && (profileMenuOpen || stack.size > 1 || backGoesHome || atHomeRoot)) {
            // FR-R275-4 — one order, stated once, first match wins. backToTop sits above the stack: a
            // scrolled pushed screen goes to its top before it pops, exactly as it does on the TV.
            when {
                profileMenuOpen -> profileMenuOpen = false
                backToTop.consumeBack() -> Unit
                stack.size > 1 -> pop()
                backGoesHome -> resetTo(Dest.Home(destDisplayName(dest)))
                atHomeRoot -> exitApp()
            }
        }
        // R261 (FR-R261-3/5, dev review item 4) — safe-area padding used to be applied here, wrapping
        // every destination including the player. It now lives per-destination inside AnimatedContent's
        // content lambda below (none for the two playing destinations; safeAreaPadding(), not plain
        // safeDrawing, for everything else) plus explicitly on the profile menu and FPS overlays, which
        // are this root Box's other children.
        Box(
            modifier = Modifier
                .fillMaxSize()
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
            // R262 (FR-R262-3) — Home/Movies·Series/Discover are one section rail: switching between
            // them recomposes in place, same as the pre-existing Movies↔Series (`Dest.Browse`, same
            // class) treatment this generalises. Acceptance 1/5 require it — entering Discover from
            // Home, and Discover↔Movies, must never show two app bars mid-slide. The transition stays
            // for drilling into a detail page / the player / a seeded grid / See all, and for Back out
            // of those (still their own distinct class each).
            contentKey = { d -> if (d is Dest.Home || d is Dest.Browse || d is Dest.Discover) "section" else d::class },
        ) { dest ->
            // R261 (FR-R261-3/5, dev review item 4) — the two playing destinations render unpadded
            // (their picture owns the whole panel; the chrome pads itself). Dest.CastRemote is excluded
            // too: CastRemoteScreen.kt already self-pads with WindowInsets.safeDrawing at its own root,
            // predating this seam — wrapping it again here would double the inset. Everything else gets
            // safeAreaPadding() here (not on the root Box) so it composes with its final insets from the
            // first frame, instead of jumping once the bars finish animating back in (the measured 69 px
            // this phase fixes).
            val playingFullscreen = dest is Dest.Player || dest is Dest.LiveTv || dest is Dest.CastRemote
            // R267 (FR-R267-7/-12) — nothing scrolls under the bottom bar and nothing is clipped by
            // it. The height comes from RaviloDimens, never repeated as a literal: a phone value wrong
            // by one bar is exactly how a heading ends up underneath one, twice already (R257
            // FR-R257-5, R259 FR-R259-2). Applied here, where the per-destination insets already are,
            // so the four pages do not each have to remember it.
            val navBarInset = if (handset && bottomBarShows(dest)) RaviloDimens.bottomNavHeight else 0.dp
            Box(
                modifier = if (playingFullscreen) Modifier.fillMaxSize()
                // R274 (FR-R274-3) — the bar's height goes INTO the seam, not after it: the result is
                // max(ime, systemBars + bar), so a keyboard-up page ends at the keys rather than 68 dp
                // above them, and a keyboard-down page still clears the gesture inset AND the bar.
                else Modifier.fillMaxSize().safeAreaPadding(plusBottom = navBarInset),
            ) {
            when (dest) {
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
                // R141: on every Home re-entry (including Back-returns) the store does a silent re-pull.
                // HomeStore.refresh(silent=true) keeps the current content visible and swaps in the new
                // feed when it arrives — no Loading flash.
                // R248 (FR-R248-1) — the re-pull is the store's own `onReturn()` now (skipped when the
                // server's `home_changed` push already refreshed it while away), not a liveConfig emit —
                // which also re-pulled skin/lang on every return, work config_changed already covers.
                LaunchedEffect(Unit) { store.onReturn() }
                DisposableEffect(Unit) { onDispose { store.onLeave() } }
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
                        when (raviloNavTarget(idx)) {
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
                            sortBy = row.sortBy, sortDescending = row.sortDescending,   // R253
                        ))
                    },
                    onLiveTvChannelSelect = { ch -> push(Dest.LiveTv(ch.channelId, dest.displayName)) },
                    onOpenLiveTvGuide = { push(Dest.LiveTvGuide(dest.displayName)) },
                )
            }

            is Dest.ChannelView -> {
                val store = keptStore("channel:${dest.displayName}:${dest.channel.id}") { ChannelStore(apiClient) }
                // R248 (FR-R248-4) — the return re-pull itself is ChannelScreen's R40 `load()`; this
                // only tells the store when the page went away, for the one-refresh-per-return rule.
                DisposableEffect(Unit) { onDispose { store.onLeave() } }
                ChannelScreen(
                    channel = dest.channel,
                    store = store,
                    displayName = dest.displayName,
                    onBack = { pop() },
                    onNavSelect = { idx ->   // R136: nav tabs on the channel page
                        when (raviloNavTarget(idx)) {
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
                            sortBy = row.sortBy, sortDescending = row.sortDescending,   // R253
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
                    SeededBrowseStore(apiClient, dest.seedQuery, dest.seedMediaKind, dest.continueWatching, dest.personTmdbId, dest.channelId, initialSort = dev.jellystructure.ravilo.ui.screens.initialBrowseSort(dest.sortBy, dest.sortDescending))
                }
                SeededBrowseScreen(
                    store = store,
                    title = dest.title,
                    breadcrumb = dest.breadcrumb,
                    subtitle = dest.breadcrumb?.let { str("browse.from_row", mapOf("row" to it)) },
                    showTypeFacet = dest.seedMediaKind == null,
                    showFacetBar = !dest.continueWatching,
                    displayName = dest.displayName,
                    activeNav = dest.activeNav,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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
            // R267 (FR-R267-5b/-5c) — on a handset, Movies and Series are one page called **Library**,
            // and the type is a control in the top row rather than two nav items. This is a
            // presentation change, not a new screen: `Dest.Browse` is already one screen with a type
            // parameter, so Library is that screen with its parameter exposed. Its rows, grid, facets
            // and See-all behaviour are R187's, unchanged.
            //
            // My List keeps the old path — it arrives from the profile menu as a pushed destination,
            // it is not a Library type, and it is not expressible as a seed (see the R187 note below).
            is Dest.Browse -> if (handset && dest.kind != BrowseKind.MY_LIST) {
                val kind = dest.kind
                val seedKind = when (kind) {
                    BrowseKind.MOVIES -> "MOVIE"
                    BrowseKind.SERIES -> "SERIES"
                    BrowseKind.MUSIC -> "MUSIC_VIDEO"
                    else -> null   // ALL — no kind filter at all
                }
                val store = keptStore("library:${dest.displayName}:$kind") {
                    SeededBrowseStore(apiClient, seedQuery = null, seedMediaKind = seedKind, continueWatching = false)
                }
                // FR-R267-5c — all four counts from ONE call. `getFacets(kind = null)` answers with
                // `kind_counts` for every type (phase R267's backend half), so the dropdown never
                // needs four round trips and the client never sums anything itself.
                var kindCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    kindCounts = runCatching { apiClient.getFacets(kind = null).kindCounts }.getOrDefault(emptyMap())
                }
                SeededBrowseScreen(
                    store = store,
                    title = str("nav.library"),
                    breadcrumb = null,
                    subtitle = null,
                    showTypeFacet = false,
                    showFacetBar = true,
                    displayName = dest.displayName,
                    activeNav = -1,   // the phone's selection lives in the bottom bar, not this row
                    onBack = { pop() },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    handsetTopSlot = {
                        LibraryTypePill(
                            current = kind,
                            counts = kindCounts,
                            // The choice is a browse control, not a setting: it rides the destination
                            // and does not persist across app restarts.
                            onSelect = { replaceTop(Dest.Browse(it, dest.displayName)) },
                        )
                    },
                )
            } else if (dest.kind == BrowseKind.MOVIES || dest.kind == BrowseKind.SERIES) {
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
                    activeNav = if (dest.kind == BrowseKind.MOVIES) 1 else 2,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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
                    focusInputOnEntry = dest.focusInput,
                    onFocusInputConsumed = { replaceTop(dest.copy(focusInput = false)) },
                )
            }

            // R170 — the merged Discover tab: `segment` picks which of the two (formerly separate-tab)
            // screens renders. Each gets a switch-pill to flip to the other segment in place
            // (replaceTop, same Dest class ⇒ AnimatedContent's contentKey skips the slide transition —
            // same treatment as any other same-class tab switch, e.g. Browse→Browse).
            is Dest.Discover -> {
                // R243 (FR-R243-1) — the segment bar shows every available segment; a chip press swaps
                // the segment in place (replaceTop, same Dest class ⇒ AnimatedContent's contentKey skips
                // the slide transition) and keeps focus on that chip via focusSegment.
                val segs = discoverSegments(upcomingAvailable, discoverAvailable)
                val onSegment: (DiscoverSegment) -> Unit = { seg -> replaceTop(Dest.Discover(dest.displayName, seg, focusSegment = true)) }
                // The Discover nav button while already on Discover: step to the next segment rather than
                // no-op (the R170 fix, generalised — this screen's two-stage Back routinely parks focus on it).
                val onNav: (Int) -> Unit = { idx ->
                    when (raviloNavTarget(idx)) {
                        RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                        RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                        RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                        RaviloNavTarget.DISCOVER -> nextDiscoverSegment(segs, dest.segment)?.let { replaceTop(Dest.Discover(dest.displayName, it)) } ?: Unit
                    }
                }
                // R262 (FR-R262-7) — one frame, warmed on entry regardless of which segment shows first:
                // a store is constructed here (and only here) exactly when its segment is available, so
                // an ungated household never fetches a Coming Soon/Request feed nobody can see.
                val upcomingStore = if (DiscoverSegment.COMING_SOON in segs) keptStore("upcoming:${dest.displayName}") { UpcomingStore(apiClient) } else null
                val requestStore = if (DiscoverSegment.REQUEST in segs) keptStore("discover:${dest.displayName}") { DiscoverStore(apiClient) } else null
                // R243 — the three library walls share one store (one facets fetch feeds all three); never gated.
                val taxonomyStore = keptStore("taxonomy:${dest.displayName}") { TaxonomyStore(apiClient) }
                DiscoverScreen(
                    segment = dest.segment,
                    segments = segs,
                    onSegment = onSegment,
                    focusSegmentOnEntry = dest.focusSegment,
                    // R262 (dev review item 2) — consumes the press token on the stack entry itself, so a
                    // later Back-return from a seeded grid doesn't re-steal focus from a tile restore.
                    onFocusSegmentConsumed = { replaceTop(dest.copy(focusSegment = false)) },
                    displayName = dest.displayName,
                    onNavSelect = onNav,
                    onProfile = { profileMenuOpen = true },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    upcomingStore = upcomingStore,
                    onUpcomingItemSelect = { item ->
                        val itemId = item.itemId
                        when {
                            itemId != null && item.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(itemId, dest.displayName))
                            itemId != null -> push(Dest.MovieDetail(itemId, dest.displayName))
                            else -> push(Dest.UpcomingDetail(item.id, dest.displayName))
                        }
                    },
                    requestStore = requestStore,
                    onEntrySelect = { mediaType, tmdbId -> push(Dest.DiscoverItem(mediaType, tmdbId, dest.displayName)) },
                    onSearchSeerr = { push(Dest.SeerrSearch(dest.displayName)) },
                    taxonomyStore = taxonomyStore,
                    onTileSelect = { seg, item, crumb -> openTaxonomyBrowse(seg, item, crumb, dest.displayName) },
                )
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
                        // R245 (FR-R245-4) — while a cast session is connected, Play casts; the server
                        // resolves the resume position exactly as it does for a TV.
                        if (castActive?.connected == true) {
                            castActive.cast(
                                itemId = detail.card.id, title = detail.card.title, kicker = null,
                                artUrl = resolveCastArt(apiClient.baseUrl, detail.card.backdropUrl),
                                positionMs = null, lang = lang,
                            )
                            push(Dest.CastRemote(dest.displayName))
                        } else push(Dest.Player(
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
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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
                        if (castActive?.connected == true) {
                            // R245 (FR-R245-4/14) — the receiver gets the whole season so it can advance by itself.
                            castActive.cast(
                                itemId = ctx.episodeId, title = ctx.episodeTitle, kicker = ctx.kicker,
                                artUrl = resolveCastArt(apiClient.baseUrl, ctx.episodes.getOrNull(ctx.currentEpIndex)?.stillUrls?.firstOrNull { it != null }),
                                positionMs = null, episodes = castEpisodes(ctx.episodes), currentIndex = ctx.currentEpIndex, lang = lang,
                            )
                            push(Dest.CastRemote(dest.displayName))
                        } else push(Dest.Player(
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
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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

            is Dest.CastRemote -> {
                val cc = castActive
                if (cc == null) { LaunchedEffect(Unit) { pop() } }
                else CastRemoteScreen(
                    cast = cc,
                    onBack = { pop() },
                    onPlayAgain = { itemId ->
                        val st = cc.sender.status.value
                        cc.cast(itemId = itemId, title = st?.title ?: "", kicker = st?.kicker, artUrl = st?.artUrl, positionMs = 0L, lang = lang)
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
                    onBack = { pop() },
                    // Bug fix: this used to replaceTop the guide itself with the LiveTv player, which
                    // dropped the guide from the stack — Back from the player then skipped straight to
                    // whatever was below the guide (Home), not back to the guide the user actually came
                    // from. push() keeps the guide on the stack so Back unwinds one screen at a time,
                    // same as tuning from anywhere else (e.g. Home's On Now row already pushes).
                    onTuneChannel = { ch -> push(Dest.LiveTv(ch.channelId, dest.displayName)) },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
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
        } } } // Box (per-dest insets, R261) / when / AnimatedContent

        // R267 (FR-R267-5/-7/-10) — the phone's page navigation. Drawn HERE, as a child of the root
        // Box, deliberately outside AnimatedContent: a bar that animated in and out with every content
        // transition is exactly the two-bars-mid-slide problem R262 FR-R262-3 exists to prevent.
        //
        // No hide-on-scroll (FR-R267-10): it is the one affordance that says where you are, and a bar
        // that disappears while you read a row is a bar you have to go looking for.
        // R278 (FR-R278-2) — the page itself when it is one of the four, else the nearest one below it
        // on the stack, so a list opened from Discover keeps Discover lit. Null when nothing below it
        // answers either (My List, the account screens): the bar draws with no pill, which is honest —
        // it is none of the four.
        val pageItem = bottomItemOf(dest)
        val litItem = pageItem ?: stack.lastOrNull { bottomItemOf(it) != null }?.let { bottomItemOf(it) }
        if (handset && bottomBarShows(dest)) {
            // R274 (FR-R274-2) — includeIme = false: the bar is window furniture, so the keyboard is
            // drawn OVER it. Unioning the IME here is what made it climb onto the keyboard's top edge,
            // and (since union takes the larger side) swallowed its own navigation-bar inset on the way,
            // leaving the labels flush against the keys.
            Box(Modifier.fillMaxSize().safeAreaPadding(includeIme = false), contentAlignment = Alignment.BottomCenter) {
                RaviloBottomNav(
                    selected = litItem,
                    // Same derivation every other AppBar call site uses (ChannelScreen, HomeScreen, …).
                    userInitials = destDisplayName(dest).take(2).uppercase(),
                    onSelect = { item ->
                        // FR-R278-3 — "already here" is THIS DESTINATION being that page, never
                        // "this page belongs to that section": otherwise tapping Discover from a list
                        // opened out of Discover would count as a re-tap and do nothing.
                        val alreadyHere = item == pageItem
                        when (item) {
                            // FR-R267-5d — Profile is a menu, not a page, and never takes the pill:
                            // nothing about WHICH PAGE YOU ARE ON has changed when you open it.
                            // Re-tapping while it is open closes it, as tapping the scrim does.
                            BottomNavItem.PROFILE -> profileMenuOpen = !profileMenuOpen
                            BottomNavItem.HOME ->
                                if (!alreadyHere) resetTo(Dest.Home(destDisplayName(dest)))
                            // FR-R267-5b — Movies and Series are one page on a phone. This is a
                            // presentation change, not a new screen: Dest.Browse is already one screen
                            // with a type parameter, so Library is that screen with its parameter
                            // exposed as a control instead of as two nav items.
                            // FR-R278-3 — resetTo, not replaceTop: from a pushed page, replacing the
                            // top would leave the section it came from underneath ([Discover, Search]),
                            // and Back would then return there instead of following R275's ladder. On
                            // one of the four pages the stack is already size 1, so the two are the
                            // same act.
                            BottomNavItem.LIBRARY ->
                                if (!alreadyHere) resetTo(Dest.Browse(BrowseKind.ALL, destDisplayName(dest)))
                            // FR-R267-5a — search is a PAGE on a phone, not the TV's right-cluster
                            // magnifier: a phone has a keyboard and a thumb, so it is one of the
                            // places a viewer goes.
                            // R277 (FR-R277-2) — re-tapping Search while on Search raises the
                            // keyboard, which is the one thing the bar's own item could not do (this
                            // was `if (!alreadyHere)`, i.e. a no-op). The flag is carried on the Dest
                            // and consumed by the screen, so it works on every tap and not just the
                            // first.
                            BottomNavItem.SEARCH ->
                                resetTo(Dest.Search(destDisplayName(dest), focusInput = alreadyHere))
                            // FR-R267-9 — re-tapping Discover returns to the FIRST AVAILABLE segment,
                            // written that way (not "Networks") so this and R268's declared order
                            // cannot disagree. This replaces R170's step-to-the-next-segment on the
                            // phone only; R170 stands on the TV, where the nav item is reached by
                            // D-pad and stepping is the cheaper gesture.
                            BottomNavItem.DISCOVER ->
                                resetTo(Dest.Discover(
                                    destDisplayName(dest),
                                    defaultDiscoverSegment(upcomingAvailable, discoverAvailable),
                                ))
                        }
                    },
                )
            }
        }

        // R170 — the avatar's dropdown: My List/Settings/Switch profile/Unpair, replacing the old
        // straight-to-picker click. Rendered over whatever screen is current, same tier as the
        // debug/message overlays below.
        if (profileMenuOpen) {
            val currentDisplayName = destDisplayName(dest)
            // R261 — this root Box no longer pads itself; the menu is one of its two children (with the
            // FPS overlay below) that need it explicitly rather than through a per-destination wrapper.
            Box(Modifier.fillMaxSize().safeAreaPadding()) {
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
        }
        // R245 (FR-R245-3/6) — the connecting bar and the mini bar float over every screen except the
        // three that own the picture or ARE the remote. The mini bar never dismisses while a cast runs.
        if (castActive != null && dest !is Dest.Player && dest !is Dest.LiveTv && dest !is Dest.CastRemote) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter)) { CastConnectingBar() }
                // R267 (FR-R267-8) — the mini bar DOCKS directly above the nav bar; the pair moves as
                // one block and the mini bar keeps its own tap target and its rule (never dismissible
                // while a cast runs). On a pushed screen, where the nav bar is absent, it returns to
                // its own inset exactly as before. Same one-place height as the content padding.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        // R278 (FR-R278-4) — the same "is there a bar" answer the content pads by.
                        .padding(bottom = if (handset && bottomBarShows(dest)) RaviloDimens.bottomNavHeight else 0.dp),
                ) { CastMiniBar(onOpen = { push(Dest.CastRemote(currentDisplayNameForCast)) }) }
            }
        }
        // R261 — see the profile-menu comment above; the overlay itself has no inset awareness of its
        // own (a plain 6dp corner offset), so without this it could sit under a notch/status bar.
        Box(Modifier.fillMaxSize().safeAreaPadding()) { FrameTrackerOverlay(fpsOverlay) }  // R94: F5 toggles; no-op when false
        ServerMessageHost()  // R152: floats over every screen incl. the player (reads LocalServerMessages)
        UpdateToast()  // R263 (FR-R263-5): no-op off the web
        } // Box (back-intercept)
        } // CompositionLocalProvider (live config)
    } // WithLocale
    } // RaviloTheme
}

/** R245 — the Cast SDK hands art URLs straight to the receiver and the notification, so a relative
 *  `/api/tv/image/...` path must be absolute here (RemoteImage does this itself for on-screen art). */
private fun resolveCastArt(baseUrl: String, url: String?): String? =
    url?.let { if (it.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$it" else it }
