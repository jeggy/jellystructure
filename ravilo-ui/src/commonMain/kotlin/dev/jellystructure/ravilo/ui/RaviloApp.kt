package dev.jellystructure.ravilo.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import dev.jellystructure.ravilo.ui.screens.themeSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import dev.jellystructure.ravilo.ui.seams.windowDragArea
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.jellystructure.ravilo.ui.focus.arrowKeysMoveFocus
import dev.jellystructure.ravilo.ui.focus.keyboardMode
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
import dev.jellystructure.ravilo.ui.components.CastSheetHost
import dev.jellystructure.ravilo.ui.components.AirPlayNoticeBar
import dev.jellystructure.ravilo.ui.seams.platformAirPlay
import dev.jellystructure.ravilo.ui.components.reconnectsTo
import dev.jellystructure.ravilo.ui.screens.castMiniBarVisible
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.LocalCastHandoff
import dev.jellystructure.ravilo.ui.components.castArtFor
import dev.jellystructure.ravilo.ui.components.castEpisodes
import dev.jellystructure.ravilo.ui.seams.EventsCatchUp
import dev.jellystructure.ravilo.ui.seams.EventsSocketLog
import dev.jellystructure.ravilo.ui.seams.ReconnectBackoff
import dev.jellystructure.ravilo.ui.seams.acceptsRemoteCommand
import dev.jellystructure.ravilo.ui.seams.acceptsPlayerCommand
import dev.jellystructure.ravilo.ui.seams.MediaSocketHold
import dev.jellystructure.ravilo.ui.seams.rememberAppOnScreen
import dev.jellystructure.ravilo.ui.seams.rememberDeviceStateProbe
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
import dev.jellystructure.ravilo.ui.components.OnReselect
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
import dev.jellystructure.shared.tv.REMOTE_DECLARATION_APP
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.remoteCommandOf
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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

private var searchVisitCounter = 0L
/** R295 — see [Dest.Search.visit]. Main-thread only, like every navigation call. */
private fun nextSearchVisit(): Long = ++searchVisitCounter

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
         *  channel's own Continue row; forwarded to [SeededBrowseStore] unchanged. R318: also set for a
         *  channel's Recommended row. */
        val channelId: String? = null,
        /** R318 (FR-R318-2b) — the Recommended row's See all (see [SeededBrowseStore.recommendations]). */
        val recommendations: Boolean = false,
    ) : Dest()
    /** R277 — [focusInput] is set by tapping the bottom bar's Search item while Search is already
     *  showing, and is consumed by the screen (replaceTop with it cleared) so the next tap sets a
     *  fresh one. Same shape as [Discover.focusSegment], and for the same reason.
     *  R295 (FR-R295-1) — [visit] is new on every push and kept by copy(), so Search can tell "the
     *  viewer came Back to me" (same visit: land on the result they opened) from "the viewer came here
     *  afresh" (new visit: R277's text field and keyboard). */
    data class Search(val displayName: String, val focusInput: Boolean = false, val visit: Long = nextSearchVisit()) : Dest()
    // R170 — Coming Soon (the old Upcoming tab) and Request (the old Top-10/Discover tab) are now the
    // two segments of one merged Discover tab; `segment` decides which of UpcomingScreen/DiscoverScreen
    // actually renders (see DiscoverSegment/defaultDiscoverSegment in NavItems.kt).
    // R243 — [focusSegment] is set by a segment-bar switch (replaceTop), so the next screen keeps
    // focus on the chip that was pressed rather than parking it on the AppBar (FR-R243-7).
    // R310 (FR-R310-5) — `null` when no segment is available at all: Discover opens with no chips and
    // FR-R243-8's one sentence.
    data class Discover(val displayName: String, val segment: DiscoverSegment?, val focusSegment: Boolean = false) : Dest()
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
        /** R303 (FR-R303-2) — what the chrome shows top right: the film's or the SERIES' clearlogo (R214's
         *  versioned proxy URL, straight from the detail payload or the play push) and its ink (Phase 232);
         *  [seriesName] only for an episode, the text fallback when there is no logo. The player fetches nothing. */
        val logoUrl: String? = null,
        val logoInk: String? = null,
        val seriesName: String? = null,
        /** R343 (FR-R343-4) — this start is a finished series' Start over (only the first episode carries it). */
        val startOver: Boolean = false,
        /** R343 (FR-R343-5, dev review item 14) — the shuffle this entry belongs to, in play order, and its place
         *  in it. Null = an ordinary play. Not saved: after a low-memory restore the shuffle ends with the entry. */
        val shufflePlan: List<dev.jellystructure.ravilo.ui.screens.EpisodePlayContext>? = null,
        val shuffleAt: Int = 0,
        /** R343 — this entry is a shuffled one (from 0:00, no resume point left behind); true with a plan, and on
         *  an entry restored from the resume record without one. */
        val shuffled: Boolean = false,
    ) : Dest()
    data class Settings(val displayName: String) : Dest()
    // R304 (FR-R304-1) — the phone's Profile PAGE: the fifth bottom-bar item, on the stack like the other
    // four, so Back, the pill and scroll-to-top all work the way they do everywhere else. A re-tap of the
    // bar's item scrolls it to the top through the same [reselectTick] as the other four (FR-R304-1,
    // R267 FR-R267-9). Handset only — the TV keeps R170's dropdown.
    data class Profile(val displayName: String) : Dest()
    // R304 (FR-R304-4) — App language, pushed from Profile; the per-viewer setting R161/R162 shipped.
    data class AppLanguage(val displayName: String) : Dest()
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
    // R321 — music mode's pages (the phone only). The four tabs sit on the bar; the three details hide it (R278's rule).
    data class MusicListen(val displayName: String) : Dest()
    /** [focusInput] — a re-tap of Browse raises the keyboard (R277's rule), consumed like [Search.focusInput]. */
    data class MusicBrowse(val displayName: String, val chip: String = "artists", val focusInput: Boolean = false) : Dest()   // R339 — Artists on arrival
    data class MusicPlaying(val displayName: String) : Dest()
    data class MusicQueue(val displayName: String) : Dest()
    data class AlbumDetail(val id: String, val displayName: String) : Dest()
    data class ArtistDetail(val id: String, val displayName: String) : Dest()
    data class PlaylistDetail(val id: String, val name: String, val displayName: String) : Dest()
    // R323 — a book and its author (the phone only; the bar hides, the mini bar stays).
    data class AudiobookDetail(val id: String, val displayName: String) : Dest()
    data class AudiobookAuthor(val id: String, val displayName: String) : Dest()

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
        is Profile        -> "/profile"            // R304
        is AppLanguage    -> "/account/language"   // R304
        is LiveTv         -> "/livetv/$channelId"
        is LiveTvGuide    -> "/livetv-guide"
        is CastRemote     -> "/cast"
        is MusicListen    -> "/music"
        is MusicBrowse    -> "/music/browse"
        is MusicPlaying   -> "/music/playing"
        is MusicQueue     -> "/music/queue"
        is AlbumDetail    -> "/music/album/$id"
        is ArtistDetail   -> "/music/artist/$id"
        is PlaylistDetail -> "/music/playlist/$id"
        is AudiobookDetail -> "/music/audiobook/$id"
        is AudiobookAuthor -> "/music/audiobook-author/$id"
    }
}

// ─── Root composable ──────────────────────────────────────────────────────────

@Composable
fun RaviloApp(
    apiClient: TvApiClient,
    initialDisplayName: String = "",
    onChangeServer: () -> Unit = {},
    // R340 (FR-R340-3) — "Everyone on this TV": every session is already revoked; forget the server and say so.
    onSignedOutEveryone: () -> Unit = onChangeServer,
) {
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
    val themeState = rememberRaviloTheme(
        initial = initialSnapshot?.skin ?: Skin.AURORA,
        initialSettings = initialSnapshot?.themeSettings(),   // R338 — the theme settings the last session drew
    )

    // Fetch the active user's config and apply server-owned interface prefs (language + skin)
    val configScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // R245 / 218 (FR-218-3/11) — the cast capability rides the config snapshot: absent ⇒ no button.
    var castAppId by remember { mutableStateOf<String?>(null) }
    // R265 (dev review item 5) — same shape as castAppId: server-pushed, so the glyph can be present
    // for a household's very first screen (an empty device list alone can't answer "is this on at all").
    var screensEnabled by remember { mutableStateOf(false) }
    // R324 (dev review 12) — `RaviloConfig.cast.music`: the receiver on this server plays music queues (286).
    var castMusic by remember { mutableStateOf(false) }
    fun refreshConfig() {
        configScope.launch {
            runCatching { apiClient.getConfig() }.getOrNull()?.let { cfg ->
                castAppId = cfg.cast?.appId
                castMusic = cfg.cast?.music == true   // R324 (dev review 12)
                screensEnabled = cfg.screens?.enabled == true
                // Remembered as well as applied, so signing out of this profile does not take the
                // household's language with it.
                lang = resolveAndRememberLanguage(cfg.uiLanguage)
                themeState.apply(cfg)   // R338 — the viewer's theme settings (or, from an older server, the skin)
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
    // composable — collected further down, after those are in scope. R354 (FR-R354-2/-3) — playstate_command and
    // player_command are read once into a RemoteCommand and collected below on the main thread (the music engine is
    // main-thread only) by RemoteControl, which hands each to the open film player or else the music player.
    val livePlayItem = remember { MutableSharedFlow<PlayItemEnvelope>(replay = 0, extraBufferCapacity = 8) }
    val liveRemote = remember { MutableSharedFlow<RemoteCommand>(replay = 0, extraBufferCapacity = 16) }
    val liveNavigate = remember { MutableSharedFlow<NavigateEnvelope>(replay = 0, extraBufferCapacity = 8) }
    // R248 (FR-R248-2) — the server folded a stop into this user's Home feed; collected below against
    // the retained Home/channel stores (not the screens), so a push that lands while the player is still
    // on top refreshes the feed the viewer is about to return to.
    val liveHome = remember { MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 16) }
    // R345 (FR-R345-3) — the events socket opened: the server answers again. An unknown music answer is asked again then.
    // A count, not an event: an open that lands while the first question is still out is not lost.
    val serverOpens = remember { MutableStateFlow(0) }
    var activeUserId by remember { mutableStateOf(MultiTokenStore.getActive()?.userId) }
    var activeAvatarUrl by remember { mutableStateOf(MultiTokenStore.getActive()?.avatarUrl) }

    // R293 (FR-R293-1/2) — an app that is off screen holds no connection open: the events socket, R141's
    // poll and Home's Live TV poll are all keyed on this, so leaving the foreground cancels their effects
    // (loop, backoff delay and all) and coming back restarts them. The probe and the log are FR-R293-6's
    // "next time, we know why"; the catch-up rule is FR-R293-3's "coming back catches up once".
    val appOnScreen = rememberAppOnScreen()
    val onScreenNow by rememberUpdatedState(appOnScreen)
    // The socket loop reads this flow rather than being cancelled: leaving the screen must send a proper
    // close frame (`1000 background`), and only a live session can send one.
    val onScreenFlow = remember { MutableStateFlow(true) }
    LaunchedEffect(appOnScreen) { onScreenFlow.value = appOnScreen }
    // R354 (FR-R354-5, amends R293 FR-R293-1) — the events socket is also wanted off screen while the music player
    // plays, and for 10 minutes after it pauses, so the Jellyfin dashboard can pause and resume it. Nothing else holds it.
    val socketWanted = remember { MutableStateFlow(true) }
    LaunchedEffect(Unit) {
        val hold = MediaSocketHold()
        val ticks = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); delay(30_000L) } }
        kotlinx.coroutines.flow.combine(onScreenFlow, dev.jellystructure.ravilo.ui.music.MusicEngine.state, ticks) { on, st, _ ->
            val now = Clock.System.now().toEpochMilliseconds()
            hold.update(active = st.active, playing = st.playing, nowMs = now)
            on || hold.holds(now)
        }.collect { socketWanted.value = it }
    }
    LaunchedEffect(Unit) { liveRemote.collect { cmd -> RemoteControl.dispatch(cmd) } }
    val deviceStateProbe = rememberDeviceStateProbe()
    val eventsCatchUp = remember { EventsCatchUp() }
    val eventsSocketLog = remember { EventsSocketLog() }

    LaunchedEffect(Unit) { liveConfig.collect { refreshConfig() } }
    LaunchedEffect(activeUserId) {
        if (activeUserId == null) return@LaunchedEffect
        val backoff = ReconnectBackoff()
        while (true) {
            // FR-R293-1 — off screen there is no socket, no reconnect and no timer: the loop parks here
            // (suspended on the flow, not on a delay) until the app is back.
            socketWanted.first { it }
            // Bug fix (kept from before R293): a device whose token no longer matches any ravilo_device
            // row still completes the WS *upgrade* — the server rejects the token afterward, inside the
            // handler — so onOpen() fires for a doomed attempt. Only a socket held open a meaningful
            // time counts as healthy; ReconnectBackoff decides what "meaningful" is (5 minutes now, not
            // 2 seconds: FR-R293-4's rule, so a socket that dies every minute backs off to 60 s).
            var openedAt: kotlin.time.Instant? = null
            var how = "eof"
            try {
                how = apiClient.connectEvents(
                    previousSockets = eventsSocketLog.headerValue(),
                    closeWhen = { socketWanted.first { !it }; "background" },
                    remote = REMOTE_DECLARATION_APP,   // R354 (FR-R354-1)
                    onOpen = {
                        openedAt = Clock.System.now()
                        serverOpens.value = serverOpens.value + 1
                        // FR-R293-3 (dev review item 2) — an open is a config-rev check, not a refresh:
                        // the config re-pulls only when the rev moved, and Home only when the app was
                        // away longer than the server's push stream can be assumed to have covered.
                        val rev = runCatching { apiClient.getConfigRev() }.getOrNull()
                        val d = eventsCatchUp.onOpen(rev, Clock.System.now().toEpochMilliseconds())
                        if (d.refreshConfig) liveConfig.emit(rev ?: 0L)
                        if (d.refreshHome) liveHome.emit(0L)
                    },
                    onEvent = { liveConfig.emit(it.rev) },
                    onAcquisition = { liveAcquisition.emit(it) },
                    onServerMessage = { liveServerMessages.emit(it) },
                    // FR-R293-5 — a command that lands in the gap between ON_STOP and the socket's close is
                    // dropped and logged, never applied: nothing may act while nobody is looking.
                    onPlayItem = { if (acceptsRemoteCommand(onScreenNow, true)) livePlayItem.emit(it) else println("R293: dropped play_item while off screen") },
                    // R354 (FR-R354-3/-5) — a playback command is accepted while the socket is open for the media too.
                    onPlaystateCommand = { env ->
                        val cmd = remoteCommandOf(env)
                        if (cmd != null && acceptsPlayerCommand(onScreenNow, socketWanted.value)) liveRemote.emit(cmd) else if (cmd != null) println("R293: dropped playstate_command while off screen")
                    },
                    onNavigate = { if (acceptsRemoteCommand(onScreenNow, true)) liveNavigate.emit(it) else println("R293: dropped navigate while off screen") },
                    onPlayerCommand = { env ->
                        val cmd = remoteCommandOf(env)
                        if (cmd != null && acceptsPlayerCommand(onScreenNow, socketWanted.value)) liveRemote.emit(cmd) else if (cmd != null) println("R293: dropped player_command while off screen")
                    },
                    onHomeChanged = { liveHome.emit(it) },
                    // Home-feed playstate cache/concurrency fix — a live Jellyfin fetch made to satisfy
                    // this or another device's own /api/tv/home request lands here; reuse the existing
                    // R147 patch-in-place path (WatchedBus) instead of forcing a re-fetch.
                    onPlaystateChanged = { patch -> WatchedBus.publish(patch) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The effect was cancelled by a profile switch (leaving the screen is a close, not a
                // cancellation). Recorded, then rethrown — structured concurrency owns the rest.
                how = "us:user-switch"
                throw e
            } catch (e: Throwable) {
                how = "err:" + (e::class.simpleName ?: "Throwable")
            } finally {
                val now = Clock.System.now()
                val heldOpenMs = openedAt?.let { (now - it).inWholeMilliseconds } ?: 0L
                eventsSocketLog.record(heldOpenMs, how, deviceStateProbe())
                eventsCatchUp.onClosed(now.toEpochMilliseconds())
            }
            val heldOpenMs = openedAt?.let { (Clock.System.now() - it).inWholeMilliseconds } ?: 0L
            val wait = backoff.next(heldOpenMs)
            // A close we asked for is not a failure: no delay, the loop parks on the flow above instead.
            if (socketWanted.value) delay(wait)
        }
    }
    // R141: degrade-to-poll fallback — safety net for when the WS is down or a single event is missed.
    // Polls /api/tv/config/rev every 15 s; if the rev has advanced since last seen, emits on liveConfig
    // so the visible screen does its existing silent refresh (same path as WS events — no duplication risk).
    // R293 (FR-R293-2) — only while the app is on screen; the seen rev is shared with the socket's own check.
    LaunchedEffect("r141-poll:$activeUserId", appOnScreen) {
        if (activeUserId == null || !appOnScreen) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            val rev = runCatching { apiClient.getConfigRev() }.getOrNull() ?: continue
            if (eventsCatchUp.onPollRev(rev)) liveConfig.emit(rev)
        }
    }

    // R338 (FR-R338-4, D6) — the device's own light/dark reaches the theme only where the layout has one: a phone
    // layout (a phone, the iPhone web app, a desktop window under 600 dp) and the desktop. The TV layout — a TV, the web
    // app in a desktop browser — is dark-only and shows the dark pick. Written here, before RaviloTheme reads it, so a
    // cold start on a light phone never draws one dark frame first.
    val appearanceWindow = LocalWindowInfo.current
    val appearanceDensity = LocalDensity.current
    val hasAppearance = dev.jellystructure.ravilo.ui.isDesktopPlatform ||
        isHandset(isTvPlatform, appearanceWindow.containerSize.width, appearanceWindow.containerSize.height, appearanceDensity.density)
    val systemDark = dev.jellystructure.ravilo.ui.seams.systemDarkAppearance()
    themeState.deviceDark = if (hasAppearance) (systemDark ?: true) else null
    dev.jellystructure.ravilo.ui.seams.SystemBarsAppearance(light = themeState.theme.isLight)

    RaviloTheme(state = themeState) {
    WithLocale(lang) {
        // R292 (FR-R292-6, dev review item 4) — the ONE piece of saved state: the player's resume record as a
        // string. A low-memory destroy, a configuration recreation or a recents restore brings the viewer
        // back into the player at their position, never to Home. Nothing else on the stack is saved.
        val resumeJson = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        val playerResume = remember { dev.jellystructure.ravilo.ui.screens.PlayerResumeStore(resumeJson.value) { resumeJson.value = it } }
        // Determine starting screen based on cached sessions
        val initialStack: List<Dest> = remember {
            val sessions = MultiTokenStore.getAll()
            val base = when {
                sessions.isEmpty() -> Dest.Login
                sessions.size == 1 -> {
                    MultiTokenStore.setActive(sessions.first().userId)
                    // R321 (FR-R321-1) — the mode is remembered across launches; a lost grant falls back below.
                    if (!isTvPlatform && dev.jellystructure.ravilo.ui.music.MusicEngine.supported && dev.jellystructure.ravilo.ui.music.ListeningMode.read() == dev.jellystructure.ravilo.ui.music.ListeningMode.MUSIC)
                        Dest.MusicListen(sessions.first().displayName)
                    else Dest.Home(sessions.first().displayName)
                }
                else -> Dest.ProfilePicker
            }
            val record = playerResume.record
            val active = MultiTokenStore.getActive()
            if (base is Dest.Home && record != null && active != null &&
                dev.jellystructure.ravilo.ui.screens.resumeRestorable(record, kotlin.time.Clock.System.now().toEpochMilliseconds())
            ) {
                listOf(base, Dest.Player(
                    itemId = record.itemId, title = record.title, kicker = record.kicker,
                    nextEpId = record.nextEpId, nextEpLabel = record.nextEpLabel, nextEpTitle = record.nextEpTitle,
                    displayName = record.displayName.ifBlank { active.displayName },
                    seriesId = record.seriesId, originalLanguage = record.originalLanguage, posterUrl = record.posterUrl,
                    logoUrl = record.logoUrl, logoInk = record.logoInk, seriesName = record.seriesName,
                    // R343 (dev review items 7 and 14) — the session restarts as it was: Start over still clears
                    // at 5 %, a shuffled entry still leaves no resume point. The plan itself is not saved.
                    startOver = record.startOver, shuffled = record.shuffle,
                ))
            } else {
                playerResume.clear()   // a record with nowhere to go (signed out, too old) is dropped, not kept
                listOf(base)
            }
        }
        var stack by remember { mutableStateOf(initialStack) }
        // R92: direction that drives the AnimatedContent transitionSpec.
        var navDir by remember { mutableStateOf(NavDir.Forward) }
        var fpsOverlay by remember { mutableStateOf(false) }  // R94: toggle with F5
        // Top-level: track whether the Request (Seerr) segment is available; set from HomeStore, propagated to all screens.
        var discoverAvailable by remember { mutableStateOf(false) }
        // R160: same pattern for the Coming Soon segment (server-gated on [sonarr]/[radarr] presence).
        var upcomingAvailable by remember { mutableStateOf(false) }
        // R310 (FR-R310-3) — which library walls hold anything for this viewer; null = no answer (show all).
        var taxonomyWalls by remember { mutableStateOf<Set<DiscoverSegment>?>(null) }
        // R170 — the avatar opens this dropdown (My List/Settings/Switch profile/Unpair) instead of
        // pushing straight to the profile picker.
        var profileMenuOpen by remember { mutableStateOf(false) }
        // R267 (FR-R267-9) — bumped when the bottom bar's item for the page already on screen is tapped
        // again. One counter for all five pages: each reads it through OnReselect, which ignores the
        // value a page was composed with, so a page reached by Back never mistakes an old re-tap for a
        // new one. Never changes on a TV (no bottom bar there).
        var reselectTick by remember { mutableIntStateOf(0) }

        // R58: first-ever launch — initialDest called setActive() after activeUserId was already
        // initialized to null; sync the value so the WS LaunchedEffect fires and self-heals.
        LaunchedEffect(Unit) {
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
                // R351 (FR-R351-7) / R343 (FR-R343-12) — a title's page in the stack (on screen, or under the remote or
                // the player) reads its playstate again; retained pages no longer in the stack are left alone.
                stack.forEach { d ->
                    when (d) {
                        is Dest.SeriesDetail -> (storeRegistry["series:${d.displayName}:${d.itemId}"] as? SeriesDetailStore)?.onHomeChanged()
                        is Dest.MovieDetail -> (storeRegistry["movie:${d.displayName}:${d.itemId}"] as? MovieDetailStore)?.onHomeChanged()
                        else -> {}
                    }
                }
            }
        }
        // R352 (FR-R352-8) — a `playstate_changed` patch reaches a series page in the stack too, not only the tiles.
        LaunchedEffect(Unit) {
            WatchedBus.patches.collect { patch ->
                stack.forEach { d -> if (d is Dest.SeriesDetail) (storeRegistry["series:${d.displayName}:${d.itemId}"] as? SeriesDetailStore)?.onPlaystatePatch(patch) }
            }
        }
        // R293 (FR-R293-2, dev review item 1) — the Live TV "On now" poll is a job on a retained store, not
        // an effect, so it is told explicitly; every retained Home store hears the same answer.
        LaunchedEffect(appOnScreen) {
            storeRegistry.values.forEach { s -> if (s is HomeStore) s.setOnScreen(appOnScreen) }
        }

        // Load config when already on Home (single-session fast path)
        if (initialStack.first() is Dest.Home) {
            androidx.compose.runtime.LaunchedEffect(Unit) { refreshConfig() }
        }

        // R80: stable holder for the "this mutation originated from the browser" flag.
        // Prevents push/pop from issuing a redundant history.push/replace when we're already
        // reacting to a browser-initiated navigation (hashchange).
        val fromHistory = remember { object { var flag = false } }

        // R100: captured at composition so the detail-open click handlers can pre-warm a backdrop.
        val imageCtx = LocalPlatformContext.current

        // R337 — the desktop toolbar's Forward: the pages Back left, until the viewer goes somewhere new.
        var forwardStack by remember { mutableStateOf<List<Dest>>(emptyList()) }
        // R337 — on a computer Settings is a window of its own beside the app's (the mockup's T·e, ⌘, on the Mac), so
        // every way to Settings opens that window and the app's own page stays where it is.
        var deskSettingsOpen by remember { mutableStateOf(false) }
        fun push(dest: Dest) {
            if (dest is Dest.Settings && isDesktopPlatform) { deskSettingsOpen = true; return }
            navDir = NavDir.Forward
            forwardStack = emptyList()
            stack = stack + dest
            if (!fromHistory.flag) pushRoute(dest.toRoute())
        }
        fun pop() {
            if (stack.size > 1) {
                navDir = NavDir.Back
                // A player is not a page to go forward to: leaving it is the end of that play.
                val left = stack.last()
                forwardStack = if (left is Dest.Player || left is Dest.LiveTv || left is Dest.CastRemote) emptyList() else forwardStack + left
                stack = stack.dropLast(1)
                // On browser, hashchange already moved the URL — don't push another entry.
                // On Android, replaceRoute is a no-op, so calling it is harmless.
                if (!fromHistory.flag) replaceRoute(stack.last().toRoute())
            }
        }
        // Replace the whole stack (tab resets, sign-out) and sync the browser URL.
        fun goForward() {
            val next = forwardStack.lastOrNull() ?: return
            navDir = NavDir.Forward
            forwardStack = forwardStack.dropLast(1)
            stack = stack + next
            if (!fromHistory.flag) pushRoute(next.toRoute())
        }
        fun resetTo(dest: Dest) {
            navDir = NavDir.Reset
            forwardStack = emptyList()
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
            is Dest.Profile -> d.displayName; is Dest.AppLanguage -> d.displayName   // R304
            is Dest.MusicListen -> d.displayName; is Dest.MusicBrowse -> d.displayName; is Dest.MusicPlaying -> d.displayName   // R321
            is Dest.MusicQueue -> d.displayName; is Dest.AlbumDetail -> d.displayName; is Dest.ArtistDetail -> d.displayName
            is Dest.PlaylistDetail -> d.displayName
            is Dest.AudiobookDetail -> d.displayName; is Dest.AudiobookAuthor -> d.displayName   // R323
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
                // R293 (FR-R293-5, dev review item 4) — never while the app is off screen.
                if (!acceptsRemoteCommand(onScreenNow, true)) { println("R293: dropped play_item while off screen"); return@collect }
                val displayName = destDisplayName(stack.lastOrNull())
                when (env.kind) {
                    "series" -> push(Dest.SeriesDetail(env.jellyfinId, displayName))
                    else -> push(Dest.Player(
                        itemId = env.jellyfinId,
                        title = env.title.orEmpty(),
                        kicker = env.kicker,          // R303 — the push now carries the S·E kicker for an episode
                        displayName = displayName,
                        seriesId = env.jellyfinId,   // R181 — a movie is its own remembered bucket
                        logoUrl = env.logoUrl, logoInk = env.logoInk, seriesName = env.seriesName,   // R303 (FR-R303-2)
                    ))
                }
            }
        }
        LaunchedEffect(Unit) {
            liveNavigate.collect { env ->
                if (activeUserId == null) return@collect
                if (!acceptsRemoteCommand(onScreenNow, true)) { println("R293: dropped navigate while off screen"); return@collect }   // R293 (FR-R293-5)
                if (env.destination == "home") {
                    resetTo(Dest.Home(MultiTokenStore.getActive()?.displayName.orEmpty()))
                }
            }
        }

        // R328 (FR-R328-5) — the Mac's menu: *Settings…* (⌘,) opens Settings over what is on screen once a viewer is
        // signed in. Never over a film or live TV: opening a page there would end the playback.
        LaunchedEffect(Unit) {
            dev.jellystructure.ravilo.ui.components.AppCommands.requests.collect { cmd ->
                when (cmd) {
                    dev.jellystructure.ravilo.ui.components.AppCommand.OPEN_SETTINGS -> {
                        val top = stack.lastOrNull()
                        if (activeUserId != null && top !is Dest.Settings && top !is Dest.Player && top !is Dest.LiveTv) {
                            push(Dest.Settings(MultiTokenStore.getActive()?.displayName.orEmpty()))
                        }
                    }
                    else -> Unit   // R337 — the desktop's other commands are handled with the frame, below
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
            // R321 (FR-R321-4) — one title's detail hides the bar; the mini bar stays.
            is Dest.AlbumDetail, is Dest.ArtistDetail, is Dest.PlaylistDetail -> false
            is Dest.AudiobookDetail, is Dest.AudiobookAuthor -> false   // R323 (FR-R323-3)
            else -> true
        }

        fun bottomItemOf(d: Dest): BottomNavItem? = when {
            d is Dest.Home -> BottomNavItem.HOME
            d is Dest.Browse && d.kind != BrowseKind.MY_LIST -> BottomNavItem.LIBRARY
            d is Dest.Search -> BottomNavItem.SEARCH
            d is Dest.Discover -> BottomNavItem.DISCOVER
            d is Dest.Profile -> BottomNavItem.PROFILE   // R304 (FR-R304-1) — a page, so it takes the pill
            d is Dest.MusicListen -> BottomNavItem.LISTEN   // R321 (FR-R321-4)
            d is Dest.MusicBrowse -> BottomNavItem.BROWSE
            d is Dest.MusicPlaying -> BottomNavItem.PLAYING
            d is Dest.MusicQueue -> BottomNavItem.QUEUE
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
        // R337 (FR-R337-1) — the view family. A desktop window under 600 dp is handset by the same test (its minimum
        // height is 600, so "the shorter side is under 600" is "the width is under 600"): the phone's screens, unchanged.
        val family = when {
            handset -> dev.jellystructure.ravilo.ui.theme.LayoutFamily.PHONE
            isDesktopPlatform -> dev.jellystructure.ravilo.ui.theme.LayoutFamily.DESKTOP
            else -> dev.jellystructure.ravilo.ui.theme.LayoutFamily.TV
        }
        val desktop = family == dev.jellystructure.ravilo.ui.theme.LayoutFamily.DESKTOP
        val windowWidth = with(density) { windowInfo.containerSize.width.toDp() }
        // R304 (FR-R304-1/5) — the avatar opens a PAGE on a phone and the dropdown on a TV. One place, so
        // the nine AppBar call sites cannot disagree; the phone's dropdown is gone by construction.
        fun openProfile() {
            // R337 (dev review 4, Q12) — a computer holds one viewer, as a phone does: Profile is its page there too.
            if (handset || desktop) {
                val top = stack.lastOrNull()
                if (top !is Dest.Profile) resetTo(Dest.Profile(destDisplayName(top)))
            } else profileMenuOpen = true
        }
        // R159 — orientation, not width: a resized browser window or a rotated phone flips this live.
        // Compact controls *sizing*; portrait controls *these overrides* — a portrait phone is usually
        // both, but they're independent signals (e.g. a narrow-but-landscape split-screen window).
        val portrait = remember(windowInfo.containerSize.width, windowInfo.containerSize.height) {
            windowInfo.containerSize.height > windowInfo.containerSize.width
        }

        // ─── R321/R322 — music mode ───────────────────────────────────────────────
        // FR-R321-1 — a per-device flag, read again whenever the viewer changes (a sign-out clears it).
        var musicMode by remember { mutableStateOf(dev.jellystructure.ravilo.ui.music.ListeningMode.read() == dev.jellystructure.ravilo.ui.music.ListeningMode.MUSIC) }
        // FR-R321-2 — absent, never greyed: only for a viewer with the music library, and only where it can play.
        var musicAvailable by remember { mutableStateOf<Boolean?>(null) }
        var booksAvailable by remember { mutableStateOf(false) }
        LaunchedEffect(apiClient) { dev.jellystructure.ravilo.ui.music.MusicEngine.attach(apiClient) }
        // R337 (dev review 3a) — the listening mode on a phone AND on the desktop, at every width.
        val listeningLayout = handset || desktop
        // R345 — asks the server. A definite no stores films (FR-R345-2), so the next launch opens there with no flip;
        // a failure is unknown (null): Ravilo stays in the stored mode and asks again when the server next answers.
        suspend fun askMusicNow() {
            val (books, answer) = dev.jellystructure.ravilo.ui.music.askMusicAvailability(apiClient)
            // R323 — the listening mode is there for music or for audiobooks (either is enough).
            booksAvailable = books == dev.jellystructure.ravilo.ui.music.MusicAnswer.SOMETHING
            musicAvailable = answer
            if (answer == false) {
                dev.jellystructure.ravilo.ui.music.ListeningMode.write(dev.jellystructure.ravilo.ui.music.ListeningMode.VIDEO)
                musicMode = false
            }
        }
        LaunchedEffect(activeUserId, listeningLayout) {
            musicMode = dev.jellystructure.ravilo.ui.music.ListeningMode.read() == dev.jellystructure.ravilo.ui.music.ListeningMode.MUSIC
            if (activeUserId == null || !listeningLayout || !dev.jellystructure.ravilo.ui.music.MusicEngine.supported) {
                booksAvailable = false
                musicAvailable = false
                return@LaunchedEffect
            }
            musicAvailable = null
            var seen = serverOpens.value
            askMusicNow()
            // FR-R345-3 — while the answer is unknown, the next socket open asks again (no timer, not every request).
            serverOpens.collect { n ->
                if (n != seen) {
                    seen = n
                    if (musicAvailable == null) askMusicNow()
                }
            }
        }
        val inMusic = listeningLayout && musicMode && musicAvailable != false && dev.jellystructure.ravilo.ui.music.MusicEngine.supported
        // R342 — the Mac's running Dock icon follows what the screen shows (a no-op everywhere else).
        LaunchedEffect(inMusic) { dev.jellystructure.ravilo.ui.seams.reportListeningMode(inMusic) }
        fun homeDest(name: String): Dest = if (inMusic) Dest.MusicListen(name) else Dest.Home(name)
        // FR-R321-2 — a mode stored for a viewer who lost the grant falls back to video, silently.
        LaunchedEffect(musicAvailable) {
            if (musicAvailable == false && stack.any { it is Dest.MusicListen || it is Dest.MusicBrowse || it is Dest.MusicPlaying || it is Dest.MusicQueue || it is Dest.AudiobookDetail || it is Dest.AudiobookAuthor }) {
                resetTo(Dest.Home(destDisplayName(stack.lastOrNull())))
            }
        }
        val musicState by dev.jellystructure.ravilo.ui.music.MusicPlayback.state.collectAsState()
        val musicBarHidden by dev.jellystructure.ravilo.ui.music.MusicCast.barHidden.collectAsState()
        // R323 — the viewer's shelf and book pages, kept across navigation; commands to the engine run on the main thread.
        val bookStore = remember(activeUserId) { dev.jellystructure.ravilo.ui.music.AudiobookStore(apiClient) }
        val uiScope = androidx.compose.runtime.rememberCoroutineScope()
        // FR-R322-12 — a video taking the screen stops the song (the queue stays, paused where it was).
        LaunchedEffect(stack.lastOrNull()) {
            val top = stack.lastOrNull()
            if (top is Dest.Player || top is Dest.LiveTv || top is Dest.CastRemote) dev.jellystructure.ravilo.ui.music.MusicEngine.stopForVideo()
        }
        // Dev review 12 — the notification permission is asked on the first play, never at launch.
        val askNotifications = dev.jellystructure.ravilo.ui.music.rememberNotificationAsk()
        LaunchedEffect(musicState.playing) { if (musicState.playing) askNotifications() }
        // FR-R337-6 / Q2 + Q10 — the page a pause happened on, while music is paused in films mode (see deskBarShows).
        var deskBarKeptOn by remember { mutableStateOf<Dest?>(null) }
        LaunchedEffect(musicState.playing, stack.lastOrNull()) {
            if (musicState.playing) deskBarKeptOn = stack.lastOrNull()
            else if (deskBarKeptOn != stack.lastOrNull()) deskBarKeptOn = null
        }
        // FR-R322-10 — the music mini bar: wherever a song is loaded, except the Playing tab and anywhere a picture plays.
        // R352 (FR-R352-6) — on a computer the phone-layout window (< 600 dp) keeps the computer's films-mode rule
        // (Q2 + Q10): paused music stays only on the page it was paused on. A real phone keeps FR-R322-10.
        fun musicMiniOver(d: Dest) = handset && musicState.active && !musicBarHidden && d !is Dest.MusicPlaying && d !is Dest.Player && d !is Dest.LiveTv &&
            d !is Dest.CastRemote && d !is Dest.Login && d !is Dest.ProfilePicker &&
            (!isDesktopPlatform || inMusic || musicState.playing || deskBarKeptOn == d)
        // FR-R324-7 — opening Playing brings a hidden bar back.
        LaunchedEffect(stack.lastOrNull()) { if (stack.lastOrNull() is Dest.MusicPlaying) dev.jellystructure.ravilo.ui.music.MusicCast.barHidden.value = false }
        // FR-R322-6 — landscape Playing is full-screen, not a tab.
        // R326 (FR-R326-2) — in music mode the bar stays on an album, an artist, a playlist, a book and its author
        // (R321/R322's "one title's detail hides the bar" holds for video mode only — R278 stands there).
        fun musicDetail(d: Dest) = d is Dest.AlbumDetail || d is Dest.ArtistDetail || d is Dest.PlaylistDetail || d is Dest.AudiobookDetail || d is Dest.AudiobookAuthor
        fun barShows(d: Dest) = (bottomBarShows(d) || (inMusic && musicDetail(d))) && !(d is Dest.MusicPlaying && !portrait)
        var trackSheet by remember { mutableStateOf<dev.jellystructure.ravilo.ui.music.TrackSheetRequest?>(null) }

        // R337 — on a computer the focus ring follows the keyboard: it shows once Tab, an arrow, Enter or Esc is pressed and goes
        // when the pointer is used again (FR-R350-17: really moved or pressed), as the platforms' own focus rings do. (R298's rule for a phone: never.)
        val deskKeyboard = remember { dev.jellystructure.ravilo.ui.focus.KeyboardMode() }
        val deskKeyboardNav = deskKeyboard.on
        // ─── R337 — the desktop's frame: sidebar or rail, the player bar, the queue panel ────────────────────────
        var queueOpen by remember { mutableStateOf(false) }
        var sidebarHidden by remember { mutableStateOf(runCatching { dev.jellystructure.ravilo.ui.music.MusicDeviceStore.get("desk_sidebar_hidden") }.getOrNull() == "1") }
        var deskMenuOpen by remember { mutableStateOf(false) }
        var shortcutsOpen by remember { mutableStateOf(false) }
        var deskSignOutAsk by remember { mutableStateOf(false) }
        val deskMedium = windowWidth < dev.jellystructure.ravilo.ui.theme.WindowWidths.EXPANDED
        val deskLarge = windowWidth >= dev.jellystructure.ravilo.ui.theme.WindowWidths.LARGE
        fun deskFramed(d: Dest) = desktop && activeUserId != null && d !is Dest.Player && d !is Dest.LiveTv && d !is Dest.CastRemote &&
            d !is Dest.Login && d !is Dest.ProfilePicker
        // FR-R337-9 — a film hides the sidebar, the rail and the bar; ⌃⌘S hides the sidebar at will (remembered).
        fun deskNavShows(d: Dest) = deskFramed(d) && !sidebarHidden
        fun deskPageOf(d: Dest): dev.jellystructure.ravilo.ui.components.DesktopPage? = when {
            d is Dest.Home -> dev.jellystructure.ravilo.ui.components.DesktopPage.HOME
            d is Dest.Discover -> dev.jellystructure.ravilo.ui.components.DesktopPage.DISCOVER
            d is Dest.Browse && d.kind == BrowseKind.MY_LIST -> dev.jellystructure.ravilo.ui.components.DesktopPage.MY_LIST
            d is Dest.Browse && d.kind == BrowseKind.MOVIES -> dev.jellystructure.ravilo.ui.components.DesktopPage.FILMS
            d is Dest.Browse && d.kind == BrowseKind.SERIES -> dev.jellystructure.ravilo.ui.components.DesktopPage.SERIES
            d is Dest.Search -> dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH
            d is Dest.MusicListen -> dev.jellystructure.ravilo.ui.components.DesktopPage.LISTEN
            d is Dest.MusicPlaying -> dev.jellystructure.ravilo.ui.components.DesktopPage.PLAYING
            d is Dest.MusicBrowse && d.focusInput -> dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH
            d is Dest.MusicBrowse -> when (d.chip) {
                "artists" -> dev.jellystructure.ravilo.ui.components.DesktopPage.ARTISTS
                "albums" -> dev.jellystructure.ravilo.ui.components.DesktopPage.ALBUMS
                "songs" -> dev.jellystructure.ravilo.ui.components.DesktopPage.SONGS
                "genres" -> dev.jellystructure.ravilo.ui.components.DesktopPage.GENRES
                "playlists" -> dev.jellystructure.ravilo.ui.components.DesktopPage.PLAYLISTS
                "audiobooks" -> dev.jellystructure.ravilo.ui.components.DesktopPage.AUDIOBOOKS
                else -> null
            }
            else -> null
        }
        // Dev review 6 — the lit row is worked out from the stack, never stored: the page itself, else the nearest page
        // below it (a detail opened from Albums keeps Albums lit). The phone's Browse(ALL) lights neither Films nor Series.
        // The sidebar's search: what is typed in its field. Music's results take the Browse page's place, films' the
        // Search page's; any page picked from the sidebar, and a change of mode, empties it.
        var deskQuery by remember { mutableStateOf("") }
        val deskSearchFocus = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        // The rail has no field: its Search item opens the mode's page with the page's own field.
        var deskRailSearch by remember { mutableStateOf(false) }
        val deskSidebarShown = desktop && !deskMedium && !sidebarHidden
        fun deskLit(): dev.jellystructure.ravilo.ui.components.DesktopPage? = when {
            deskRailSearch && deskMedium -> dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH
            deskQuery.isNotBlank() && deskSidebarShown && stack.lastOrNull() is Dest.MusicBrowse -> null
            else -> stack.asReversed().firstNotNullOfOrNull { deskPageOf(it) }
        }
        fun deskSearch(q: String) {
            deskQuery = q
            if (q.isBlank()) return
            val name = destDisplayName(stack.lastOrNull())
            val cur = stack.lastOrNull()
            if (inMusic) { if (cur !is Dest.MusicBrowse) resetTo(Dest.MusicBrowse(name)) }
            else if (cur !is Dest.Search) resetTo(Dest.Search(name))
        }
        fun openDeskPage(p: dev.jellystructure.ravilo.ui.components.DesktopPage) {
            val name = destDisplayName(stack.lastOrNull())
            val cur = stack.lastOrNull()
            if (p != dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH && p != dev.jellystructure.ravilo.ui.components.DesktopPage.QUEUE) { deskQuery = ""; deskRailSearch = false }
            when (p) {
                dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH ->
                    // The sidebar's own field takes ⌘F; the rail opens the page with its field.
                    if (deskSidebarShown) deskSearchFocus.tryEmit(Unit)
                    else if (inMusic) { deskRailSearch = true; resetTo(Dest.MusicBrowse(name, chip = (cur as? Dest.MusicBrowse)?.chip ?: "artists", focusInput = true)) }
                    else { deskRailSearch = true; resetTo(Dest.Search(name, focusInput = true)) }
                dev.jellystructure.ravilo.ui.components.DesktopPage.HOME -> resetTo(Dest.Home(name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.DISCOVER -> resetTo(Dest.Discover(name, null))
                dev.jellystructure.ravilo.ui.components.DesktopPage.MY_LIST -> resetTo(Dest.Browse(BrowseKind.MY_LIST, name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.FILMS -> resetTo(Dest.Browse(BrowseKind.MOVIES, name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.SERIES -> resetTo(Dest.Browse(BrowseKind.SERIES, name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.LISTEN -> resetTo(Dest.MusicListen(name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.PLAYING -> resetTo(Dest.MusicPlaying(name))
                dev.jellystructure.ravilo.ui.components.DesktopPage.QUEUE -> queueOpen = !queueOpen
                dev.jellystructure.ravilo.ui.components.DesktopPage.ARTISTS -> resetTo(Dest.MusicBrowse(name, "artists"))
                dev.jellystructure.ravilo.ui.components.DesktopPage.ALBUMS -> resetTo(Dest.MusicBrowse(name, "albums"))
                dev.jellystructure.ravilo.ui.components.DesktopPage.SONGS -> resetTo(Dest.MusicBrowse(name, "songs"))
                dev.jellystructure.ravilo.ui.components.DesktopPage.GENRES -> resetTo(Dest.MusicBrowse(name, "genres"))
                dev.jellystructure.ravilo.ui.components.DesktopPage.PLAYLISTS -> resetTo(Dest.MusicBrowse(name, "playlists"))
                dev.jellystructure.ravilo.ui.components.DesktopPage.AUDIOBOOKS -> resetTo(Dest.MusicBrowse(name, "audiobooks"))
            }
        }
        // FR-R337-3 — the switch changes the sidebar and the content together; the mode's first page.
        fun switchMode(music: Boolean) {
            if (music == musicMode) return
            deskQuery = ""; deskRailSearch = false
            musicMode = music
            dev.jellystructure.ravilo.ui.music.ListeningMode.write(if (music) dev.jellystructure.ravilo.ui.music.ListeningMode.MUSIC else dev.jellystructure.ravilo.ui.music.ListeningMode.VIDEO)
            val name = destDisplayName(stack.lastOrNull())
            if (desktop) resetTo(if (music) Dest.MusicListen(name) else Dest.Home(name))
        }
        // FR-R337-6 / Q2 + Q10 — in music mode the bar is always there; in films mode only while music plays, and a pause
        // from the bar keeps it until the viewer leaves the page. (`deskBarKeptOn` is declared beside the mini bar's rule.)
        fun deskBarShows(d: Dest) = deskFramed(d) && musicState.active && d !is Dest.MusicPlaying &&
            (inMusic || musicState.playing || deskBarKeptOn == d)
        // The queue panel goes where the bar goes (and stays on Playing, which hides the bar): in films mode with nothing
        // playing there is no bar, and a queue beside a film's page would be a panel about nothing on screen.
        fun deskQueueShows(d: Dest) = deskFramed(d) && musicState.active && (deskBarShows(d) || d is Dest.MusicPlaying)
        // FR-R322-3 — nothing playing: the last queue this device kept (where it was), else the viewer's last-played song,
        // loaded paused and not started. The phone does it on the Playing tab; the desktop on entering music mode, where
        // the player bar is always there (FR-R337-6).
        suspend fun restoreLastListening() {
            if (musicState.active || dev.jellystructure.ravilo.ui.music.MusicPlayback.state.value.active) return
            val uid = MultiTokenStore.getActive()?.userId
            // R323 — the last thing listened to was a book: load it paused where this viewer is.
            val lastBook = dev.jellystructure.ravilo.ui.music.BookLastStore.load()?.takeIf { it.first == uid && dev.jellystructure.ravilo.ui.music.MusicQueueStore.load() == null }
            val book = lastBook?.let { runCatching { apiClient.getAudiobook(it.second) }.getOrNull() }
            if (book != null) { dev.jellystructure.ravilo.ui.music.MusicEngine.playBook(book, book.position?.part ?: 0, book.position?.positionMs ?: 0L, play = false); return }
            val snap = dev.jellystructure.ravilo.ui.music.MusicQueueStore.load()?.takeIf { it.userId == uid && it.tracks.isNotEmpty() }
            if (snap != null) dev.jellystructure.ravilo.ui.music.MusicEngine.loadPaused(snap.tracks, snap.index, snap.positionMs, snap.context)
            else runCatching { apiClient.lastPlayedMusic() }.getOrNull()?.let { lp ->
                dev.jellystructure.ravilo.ui.music.MusicEngine.loadPaused(listOf(lp.track), 0, 0L,
                    dev.jellystructure.ravilo.ui.music.MusicContext("album", lp.album?.title ?: lp.track.album.orEmpty(), lp.album?.id))
            }
        }
        LaunchedEffect(desktop, inMusic, activeUserId) { if (desktop && inMusic && activeUserId != null) restoreLastListening() }
        // FR-R337-3 — the counts beside Films, Series and the music Library, once per session.
        var deskCounts by remember { mutableStateOf<Map<dev.jellystructure.ravilo.ui.components.DesktopPage, Int>>(emptyMap()) }
        LaunchedEffect(activeUserId, desktop) {
            if (!desktop || activeUserId == null) return@LaunchedEffect
            val m = HashMap<dev.jellystructure.ravilo.ui.components.DesktopPage, Int>()
            runCatching { apiClient.getFacets(null).kindCounts }.getOrNull()?.let { k ->
                k["movie"]?.let { m[dev.jellystructure.ravilo.ui.components.DesktopPage.FILMS] = it }
                k["series"]?.let { m[dev.jellystructure.ravilo.ui.components.DesktopPage.SERIES] = it }
            }
            if (musicAvailable == true) {
                runCatching { apiClient.browseMusic("artists").total }.getOrNull()?.let { m[dev.jellystructure.ravilo.ui.components.DesktopPage.ARTISTS] = it }
                runCatching { apiClient.browseMusic("albums").total }.getOrNull()?.let { m[dev.jellystructure.ravilo.ui.components.DesktopPage.ALBUMS] = it }
                runCatching { apiClient.browseMusic("tracks").total }.getOrNull()?.let { m[dev.jellystructure.ravilo.ui.components.DesktopPage.SONGS] = it }
            }
            deskCounts = m
        }
        // FR-R337-10 — the View menu and the keys, from the desktop app (AppCommands).
        LaunchedEffect(Unit) {
            dev.jellystructure.ravilo.ui.components.AppCommands.requests.collect { cmd ->
                val top = stack.lastOrNull()
                when (cmd) {
                    dev.jellystructure.ravilo.ui.components.AppCommand.MODE_VIDEO -> if (activeUserId != null) switchMode(false)
                    dev.jellystructure.ravilo.ui.components.AppCommand.MODE_MUSIC -> if (activeUserId != null && musicAvailable == true) switchMode(true)
                    dev.jellystructure.ravilo.ui.components.AppCommand.SEARCH -> if (activeUserId != null && top !is Dest.Player) openDeskPage(dev.jellystructure.ravilo.ui.components.DesktopPage.SEARCH)
                    dev.jellystructure.ravilo.ui.components.AppCommand.TOGGLE_SIDEBAR -> {
                        sidebarHidden = !sidebarHidden
                        runCatching { dev.jellystructure.ravilo.ui.music.MusicDeviceStore.put("desk_sidebar_hidden", if (sidebarHidden) "1" else "0") }
                    }
                    dev.jellystructure.ravilo.ui.components.AppCommand.TOGGLE_QUEUE -> if (musicState.active) {
                        if (handset) { if (top !is Dest.MusicQueue) push(Dest.MusicQueue(destDisplayName(top))) } else queueOpen = !queueOpen
                    }
                    dev.jellystructure.ravilo.ui.components.AppCommand.SHOW_LYRICS -> if (musicState.active && top !is Dest.MusicPlaying) push(Dest.MusicPlaying(destDisplayName(top)))
                    dev.jellystructure.ravilo.ui.components.AppCommand.SHORTCUTS -> shortcutsOpen = true
                    dev.jellystructure.ravilo.ui.components.AppCommand.NEXT_SONG -> if (musicState.active) dev.jellystructure.ravilo.ui.music.MusicPlayback.next()
                    dev.jellystructure.ravilo.ui.components.AppCommand.PREVIOUS_SONG -> if (musicState.active) dev.jellystructure.ravilo.ui.music.MusicPlayback.previous()
                    dev.jellystructure.ravilo.ui.components.AppCommand.VOLUME_UP -> dev.jellystructure.ravilo.ui.music.MusicVolume.nudge(0.1f)
                    dev.jellystructure.ravilo.ui.components.AppCommand.VOLUME_DOWN -> dev.jellystructure.ravilo.ui.music.MusicVolume.nudge(-0.1f)
                    dev.jellystructure.ravilo.ui.components.AppCommand.SIGN_OUT -> if (activeUserId != null) deskSignOutAsk = true
                    dev.jellystructure.ravilo.ui.components.AppCommand.OPEN_SETTINGS -> Unit   // handled above, where it always was
                }
            }
        }
        val musicFavoriteAdded = str("music.my_list_added")
        val musicFavoriteRemoved = str("music.my_list_removed")
        fun setMusicFavorite(t: dev.jellystructure.shared.tv.MusicTrackItem, fav: Boolean) {
            dev.jellystructure.ravilo.ui.music.MusicFavorites.set(t.id, fav)
            dev.jellystructure.ravilo.ui.music.MusicToasts.show(if (fav) musicFavoriteAdded else musicFavoriteRemoved)
            configScope.launch { runCatching { apiClient.setMusicFavorite(t.id, fav) } }
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
        SideEffect { castController.screensEnabled = screensEnabled; castController.userId = activeUserId; castController.musicEnabled = castMusic }
        // R324 — the music bridge reads the one controller; the screens read MusicPlayback, which follows the link.
        LaunchedEffect(castController) { dev.jellystructure.ravilo.ui.music.MusicCast.bind(castController) }
        // R356 (FR-R356-6) — back on screen with a cast connected: the sender asks the receiver where it is, and rejoins
        // it if nothing answers (a frozen app's Cast connection may have been dropped by Play services).
        LaunchedEffect(castController, appOnScreen) { if (appOnScreen) castController.sender.onAppForeground() }
        // R265 (FR-R265-1) — present when the user has a TV to send to OR AirPlay is available here.
        val airplayAvailable by (platformAirPlay?.available ?: remember { MutableStateFlow(false) }).collectAsState()
        val castActive = if (castAppId != null || screensEnabled || airplayAvailable) castController else null
        // R265 (FR-R265-7) — reconnect is a list, not a session: on app start and on every return to the
        // screen, the server's device list decides. A screen playing something THIS viewer started ⇒
        // link to it, and the mini bar shows its live position; none ⇒ nothing at all (R245's silent
        // outcome). Never while a Chromecast or a screen is already linked — that one stands.
        LaunchedEffect(castActive, screensEnabled, activeUserId, appOnScreen) {
            val c = castActive ?: return@LaunchedEffect
            val me = activeUserId ?: return@LaunchedEffect
            if (!screensEnabled || !appOnScreen || c.sender.link.value != dev.jellystructure.ravilo.ui.seams.CastLinkState.NONE) return@LaunchedEffect
            c.screenDevices().firstOrNull { d -> reconnectsTo(d, me) }?.let { d -> c.joinScreen(d) }
        }
        // FR-R267-8 — whether the cast mini bar is floating over [d] right now: the mini bar's own
        // visibility rule, on the same screens it is drawn over (the overlay below).
        val castLinkNow by castController.sender.link.collectAsState()
        val castStatusNow by castController.sender.status.collectAsState()
        fun miniBarOver(d: Dest) = castActive != null && d !is Dest.Player && d !is Dest.LiveTv && d !is Dest.CastRemote &&
            castMiniBarVisible(castLinkNow, castStatusNow)
        val currentDisplayNameForCast = destDisplayName(dest)
        // R245 (FR-R245-4) / R265 (FR-R265-6) — the in-player hand-off, for a screen exactly as for a
        // Chromecast: CastController.cast() posts straight to a linked screen (no hand-off code) and only
        // mints one when the Chromecast is the side that connected, so the hand-off needs no gate beyond
        // "something can be cast to" (R265's first build gated it on castAppId, leaving a screen-only
        // household with no way to move a playing title to the TV).
        CompositionLocalProvider(dev.jellystructure.ravilo.ui.components.LocalMusicMode provides inMusic, LocalCast provides castActive, LocalCastHandoff provides (if (castActive != null && dest is Dest.Player) { pos: Long ->
            val d = dest as Dest.Player
            // R343 (FR-R343-8, dev review item 14) — a shuffled player hands over the REST of its plan (this entry
            // first), not the season rail; Start over rides along while this session carries it.
            val rest = d.shufflePlan?.drop(d.shuffleAt)
            castActive.cast(
                itemId = d.itemId, title = d.title, kicker = d.kicker,
                artUrl = resolveCastArt(apiClient.baseUrl, castArtFor(null, d.episodes?.getOrNull(d.currentEpIndex)?.stillUrls?.firstOrNull { it != null })),
                positionMs = pos,
                episodes = if (rest != null) castShuffle(rest) else castEpisodes(d.episodes),
                currentIndex = if (rest != null) 0 else d.currentEpIndex, lang = lang,
                episodesShuffled = rest != null, startOver = d.startOver,
            )
            replaceTop(Dest.CastRemote(d.displayName))
        } else null),
            LocalBackToTop provides backToTop,
            LocalLiveConfig provides liveConfig, LocalLiveAcquisition provides liveAcquisition, LocalServerMessages provides liveServerMessages, LocalTileScale provides tileScale, LocalGridColumns provides gridColumns, LocalPortraitGridColumns provides portraitGridColumns, LocalCompact provides compact, LocalHandset provides handset, dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily provides family, dev.jellystructure.ravilo.ui.theme.LocalWindowWidth provides windowWidth, dev.jellystructure.ravilo.ui.focus.LocalFocusVisible provides (if (isDesktopPlatform) deskKeyboardNav else !handset), LocalPortrait provides portrait, LocalServerBaseUrl provides apiClient.baseUrl, LocalUserAvatarUrl provides activeAvatarUrl,
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
        val atHomeRoot = stack.size == 1 && (stack.last() is Dest.Home || stack.last() is Dest.MusicListen)
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
        val backGoesHome = handset && bottomItemOf(dest) != null && dest !is Dest.Home && dest !is Dest.MusicListen
        PlatformBackHandler(enabled = !ownsItsOwnBack && (profileMenuOpen || trackSheet != null || stack.size > 1 || backGoesHome || atHomeRoot)) {
            // FR-R275-4 — one order, stated once, first match wins. backToTop sits above the stack: a
            // scrolled pushed screen goes to its top before it pops, exactly as it does on the TV.
            when {
                profileMenuOpen -> profileMenuOpen = false
                trackSheet != null -> trackSheet = null   // R322 — Back with the ⋯ sheet up closes the sheet
                backToTop.consumeBack() -> Unit
                stack.size > 1 -> pop()
                backGoesHome -> resetTo(homeDest(destDisplayName(dest)))   // R321 — Listen is music mode's Home
                atHomeRoot -> exitApp()
            }
        }
        // R261 (FR-R261-3/5, dev review item 4) — safe-area padding used to be applied here, wrapping
        // every destination including the player. It now lives per-destination inside AnimatedContent's
        // content lambda below (none for the two playing destinations; safeAreaPadding(), not plain
        // safeDrawing, for everything else) plus explicitly on the profile menu and FPS overlays, which
        // are this root Box's other children.
        // R337 (FR-R337-5) — the Mac's traffic lights go where the layout has room for them: inside the sidebar's or the
        // rail's glass, in the phone layout's 32 dp strip, and in the toolbar's own band where there is no sidebar.
        if (isMacPlatform) {
            val lights = when {
                handset -> 18f to 16f
                deskNavShows(dest) && deskMedium -> 26f to 29f
                deskNavShows(dest) -> 29f to 29f
                dest is Dest.Player || dest is Dest.LiveTv -> 24f to 34f   // beside the player's Back, in its 68 dp bar
                else -> 24f to 26f
            }
            LaunchedEffect(lights) { dev.jellystructure.ravilo.ui.seams.placeWindowControls(lights.first, lights.second) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // R337 — a computer's window is the page's colour edge to edge: the sidebar's glass and the toolbar sit on it.
                .then(if (isDesktopPlatform) Modifier.background(RaviloTheme.colors.background)
                    // R350 (FR-R350-17) — keys turn the ring on; a pointer that really moves or presses turns it off.
                    .keyboardMode(deskKeyboard) else Modifier)
                // R350 (FR-R350-11) — an arrow no focused element consumed moves focus, as the D-pad does on a TV.
                .arrowKeysMoveFocus(isDesktopPlatform)
                .onKeyEvent { ev ->
                    when {
                        ev.type != KeyEventType.KeyDown -> false
                        ev.key == Key.F5 -> { fpsOverlay = !fpsOverlay; true }  // R94 debug toggle
                        ownsItsOwnBack -> false
                        // FR-R337-8 — Esc closes the queue while it lies over the content.
                        ev.key == Key.Escape && desktop && queueOpen && !deskLarge -> { queueOpen = false; true }
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
        // R337 (FR-R337-3/4) — on the desktop, the sidebar (≥ 840 dp) or the rail (600–839 dp) beside the page; the page
        // itself is the same AnimatedContent every layout uses.
        Row(Modifier.fillMaxSize()) {
        if (deskNavShows(dest)) dev.jellystructure.ravilo.ui.components.DesktopNav(
            rail = deskMedium,
            inMusic = inMusic,
            musicAvailable = musicAvailable == true,
            booksAvailable = booksAvailable,
            lit = deskLit(),
            queueOpen = queueOpen,
            musicPlaying = musicState.playing,
            counts = deskCounts,
            viewerName = MultiTokenStore.getActive()?.displayName ?: destDisplayName(dest),
            onPage = ::openDeskPage,
            onMode = ::switchMode,
            onViewer = { openProfile() },
            onSettings = { push(Dest.Settings(destDisplayName(dest))) },
            onSignOut = { deskSignOutAsk = true },
            onMenu = { deskMenuOpen = !deskMenuOpen },
            searchQuery = deskQuery,
            onSearchQuery = ::deskSearch,
            searchFocus = deskSearchFocus,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
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
            val navBarInset = (if (handset && barShows(dest)) RaviloDimens.bottomNavHeight else 0.dp) +
                // R337 (FR-R337-6) — and by the desktop's player bar while it shows.
                (if (deskBarShows(dest)) dev.jellystructure.ravilo.ui.music.desktopMusicBarInset() else 0.dp) +
                // FR-R267-8 — "the content's bottom padding is the sum of the two": while the cast mini
                // bar floats over this page, the page pads by it as well, or its last row sits under it.
                (if (miniBarOver(dest)) RaviloDimens.castMiniBarHeight else 0.dp) +
                // R322 (dev review 10) — and by the music mini bar too, when it shows.
                (if (musicMiniOver(dest)) RaviloDimens.musicMiniBarHeight else 0.dp)
            Box(
                modifier = if (playingFullscreen) Modifier.fillMaxSize()
                // R274 (FR-R274-3) — the bar's height goes INTO the seam, not after it: the result is
                // max(ime, systemBars + bar), so a keyboard-up page ends at the keys rather than 68 dp
                // above them, and a keyboard-down page still clears the gesture inset AND the bar.
                else Modifier.fillMaxSize().safeAreaPadding(plusBottom = navBarInset)
                    // R337 (FR-R337-2) — the phone layout on a computer: a 32 dp strip at the top for the window's controls.
                    .padding(top = if (handset && isDesktopPlatform) 32.dp else 0.dp),
            ) {
            // R337 — what the desktop's toolbar needs from the frame: a Back to offer, and room for the traffic lights.
            androidx.compose.runtime.CompositionLocalProvider(
                dev.jellystructure.ravilo.ui.components.LocalDesktopBack provides (if (stack.size > 1) ({ pop() }) else null),
                dev.jellystructure.ravilo.ui.components.LocalDesktopForward provides (if (forwardStack.isNotEmpty()) ({ goForward() }) else null),
                dev.jellystructure.ravilo.ui.components.LocalDesktopChromeStart provides (when {
                    deskNavShows(dest) -> 0.dp
                    isMacPlatform -> 78.dp
                    // GNOME with its buttons on the left and no sidebar to hold them: the toolbar starts after them.
                    else -> dev.jellystructure.ravilo.ui.seams.windowControlsWidth(true).let { if (it > 0.dp) it + 14.dp else 0.dp }
                }),
                dev.jellystructure.ravilo.ui.components.LocalDesktopTitle provides deskPageOf(dest)?.let { dev.jellystructure.ravilo.ui.components.desktopPageLabel(it) },
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
                val tw by store.taxonomyWalls.collectAsState()
                SideEffect { taxonomyWalls = tw }
                // R141: on every Home re-entry (including Back-returns) the store does a silent re-pull.
                // HomeStore.refresh(silent=true) keeps the current content visible and swaps in the new
                // feed when it arrives — no Loading flash.
                // R248 (FR-R248-1) — the re-pull is the store's own `onReturn()` now (skipped when the
                // server's `home_changed` push already refreshed it while away), not a liveConfig emit —
                // which also re-pulled skin/lang on every return, work config_changed already covers.
                LaunchedEffect(Unit) { store.onReturn() }
                DisposableEffect(Unit) { onDispose { store.onLeave() } }
                // R267 (FR-R267-9) — re-tapping Home scrolls the page to its top. The list state is the
                // store's own (R137), so this needs nothing from HomeScreen.
                OnReselect(reselectTick) { runCatching { store.listState.animateScrollToItem(0) } }
                // R212 — write through the combined feed + display-settings snapshot whenever Home has
                // fresh content, so the next cold start can seed instantly instead of a bare shimmer.
                // Always an exact copy of what's already on screen — never computed/derived.
                val homeState by store.state.collectAsState()
                LaunchedEffect(homeState, lang, themeState.skin, themeState.settings, tileScale, gridColumns, portraitGridColumns) {
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
                        theme = themeState.settings?.single,
                        themeFollow = themeState.settings?.follow,
                        themeLight = themeState.settings?.light,
                        themeDark = themeState.settings?.dark,
                    ))
                }
                HomeScreen(
                    store = store,
                    apiClient = apiClient,
                    displayName = dest.displayName,
                    onProfile = { openProfile() },
                    onSignOut = { resetTo(Dest.Login) },
                    onNavSelect = { idx ->
                        when (raviloNavTarget(idx)) {
                            RaviloNavTarget.HOME -> {} // already home
                            RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
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
                            recommendations = row.recommendations,   // R318
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
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
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
                            channelId = if (row.kind == RowKind.CONTINUE || row.recommendations) dest.channel.id else null,
                            recommendations = row.recommendations,   // R318
                        ))
                    },
                )
            }

            is Dest.SeededBrowse -> {
                // R219 (FR-R219-6): channelId is part of the key too — Home's and a channel's Continue
                // See-all otherwise share the same title ("Continue Watching") and would wrongly reuse
                // each other's cached store/result.
                val storeKey = "seededBrowse:${dest.displayName}:${dest.title}:${dest.continueWatching}:${dest.channelId}:${dest.recommendations}"
                val store = keptStore(storeKey) {
                    SeededBrowseStore(apiClient, dest.seedQuery, dest.seedMediaKind, dest.continueWatching, dest.personTmdbId, dest.channelId,
                        initialSort = if (dest.recommendations) dev.jellystructure.ravilo.ui.screens.SortField.SOURCE to dev.jellystructure.ravilo.ui.screens.SortDir.DESC
                            else dev.jellystructure.ravilo.ui.screens.initialBrowseSort(dest.sortBy, dest.sortDescending),
                        recommendations = dest.recommendations, sourceLabel = dest.title)
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
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
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
                // R267 (FR-R267-9) — re-tapping Library scrolls the grid to its top (the pill below
                // closes its menu on the same tick); the type itself is kept.
                OnReselect(reselectTick) { runCatching { store.gridState.animateScrollToItem(0) } }
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
                            closeTick = reselectTick,
                            // The choice is a browse control, not a setting: it rides the destination
                            // and does not persist across app restarts.
                            // R187 (FR-RV-BROWSE1-10) — a type picked is a new list, opened at its top.
                            // Each type keeps its own store (and so its own scroll), which Back needs.
                            onSelect = {
                                (storeRegistry["library:${dest.displayName}:$it"] as? SeededBrowseStore)
                                    ?.gridState?.requestScrollToItem(0)
                                replaceTop(Dest.Browse(it, dest.displayName))
                            },
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
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
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
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onProfile = { openProfile() },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Search -> {
                val store = keptStore("search:${dest.displayName}") { SearchStore(apiClient) }
                val searchTitle = str("nav.search")
                SearchScreen(
                    store = store,
                    visit = dest.visit,
                    onBack = { pop() },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    focusInputOnEntry = dest.focusInput,
                    onFocusInputConsumed = { replaceTop(dest.copy(focusInput = false)) },
                    externalQuery = if (deskSidebarShown) deskQuery else null,   // R337 — the sidebar's field
                    // R325 (FR-R325-5) — a genre chip opens Browse seeded to it, R221's contract.
                    onOpenGenre = { hit -> openGenreBrowse(listOf(hit.label), searchTitle, dest.displayName) },
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
                val segs = discoverSegments(upcomingAvailable, discoverAvailable, taxonomyWalls)
                val onSegment: (DiscoverSegment) -> Unit = { seg -> replaceTop(Dest.Discover(dest.displayName, seg, focusSegment = true)) }
                // R310 (FR-R310-4) — the segment on screen lost its chip (a refresh said its wall is now empty,
                // or the integration went away): move to the first chip left, in place, with focus on it. A
                // wall that gains values just appears in its declared place.
                val landing = if (dest.segment != null && dest.segment in segs) dest.segment else segs.firstOrNull()
                if (landing != dest.segment) LaunchedEffect(segs, dest.segment) { replaceTop(Dest.Discover(dest.displayName, landing, focusSegment = landing != null)) }
                // The Discover nav button while already on Discover: step to the next segment rather than
                // no-op (the R170 fix, generalised — this screen's two-stage Back routinely parks focus on it).
                val onNav: (Int) -> Unit = { idx ->
                    when (raviloNavTarget(idx)) {
                        RaviloNavTarget.HOME -> resetTo(Dest.Home(dest.displayName))
                        RaviloNavTarget.MOVIES -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                        RaviloNavTarget.SERIES -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                        RaviloNavTarget.DISCOVER -> dest.segment?.let { cur -> nextDiscoverSegment(segs, cur) }?.let { replaceTop(Dest.Discover(dest.displayName, it)) } ?: Unit
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
                    onProfile = { openProfile() },
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
                    reselectTick = reselectTick,
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
                    onBack = { pop() },
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
                UpcomingDetailScreen(store = store, onBack = { pop() })
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
                            logoUrl = detail.logoUrl, logoInk = detail.logoInk,  // R303 — a film shows its own logo, no name fallback
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
                            RaviloNavTarget.DISCOVER -> resetTo(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
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
                                // R343 (FR-R343-8) — Start over rides the cast: from 0:00, and the clear happens on the TV's play.
                                positionMs = if (ctx.startOver) 0L else null, episodes = castEpisodes(ctx.episodes), currentIndex = ctx.currentEpIndex, lang = lang,
                                startOver = ctx.startOver,
                            )
                            push(Dest.CastRemote(dest.displayName))
                        } else push(playerDestFor(ctx, dest.displayName))
                    },
                    // R343 (FR-R343-5/8) — Shuffle: the whole order, here or on the connected TV (which plays the same order).
                    onShuffle = { plan ->
                        val first = plan.first()
                        if (castActive?.connected == true) {
                            castActive.cast(
                                itemId = first.episodeId, title = first.episodeTitle, kicker = first.kicker,
                                artUrl = resolveCastArt(apiClient.baseUrl, first.episodes.getOrNull(first.currentEpIndex)?.stillUrls?.firstOrNull { it != null }),
                                positionMs = 0L, episodes = castShuffle(plan), currentIndex = 0, lang = lang, episodesShuffled = true,
                            )
                            push(Dest.CastRemote(dest.displayName))
                        } else push(playerDestFor(first, dest.displayName, plan, 0))
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
                            RaviloNavTarget.DISCOVER -> resetTo(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Player -> dev.jellystructure.ravilo.ui.theme.KeepDark {   // R338 (FR-R338-2) — the film player stays dark
                // remember(dest.itemId) — an auto-advance/next-episode does replaceTop(Dest.Player(…)),
                // which keeps the SAME PlayerScreen composed and only swaps dest.itemId, so a new store
                // is built per episode. Bug fix: the outgoing one used to be silently dropped and kept
                // running — its 10s progress heartbeat went on POSTing for the finished episode's id with
                // the CURRENT episode's playhead (N episodes ⇒ N phantom "Now Playing" sessions on the
                // Jellyfin dashboard, and trashed resume positions ⇒ wrong Continue Watching). Closing it
                // on dispose cancels that heartbeat and reports a final stop for the episode we left.
                // R347 — the stop's "finished" rule needs this episode's credits marker; R343 — Start over and a
                // shuffled entry are this store's start flags (from 0:00; the server clears / restores).
                val store = remember(dest.itemId) {
                    PlayerStore(apiClient, creditsStartMs = dest.segments.creditsStartMs, startOver = dest.startOver, shuffle = dest.shuffled)
                }
                DisposableEffect(store) { onDispose { store.close() } }
                // R343 (FR-R343-5) — the next-up card says *UP NEXT · SHUFFLED* while a shuffle has a next entry.
                CompositionLocalProvider(dev.jellystructure.ravilo.ui.screens.LocalShuffledNextUp provides (dest.shufflePlan != null)) {
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
                    logoUrl          = dest.logoUrl, logoInk = dest.logoInk, seriesName = dest.seriesName,  // R303
                    resume           = playerResume,  // R292
                    store            = store,
                    // R292 — leaving the player on purpose drops the record: the next launch must not restore it.
                    onBack           = { playerResume.clear(); pop() },
                    onNavigateToEpisode = navigate@{ nextId ->
                        // R343 (dev review item 14) — in a shuffle, the plan's next entry continues it (Next,
                        // auto-advance, the card's Play). Any other id — a rail pick — leaves the shuffle and plays
                        // on in that season's order, as below.
                        val plan = dest.shufflePlan
                        val planNext = plan?.getOrNull(dest.shuffleAt + 1)
                        if (plan != null && planNext != null && planNext.episodeId == nextId) {
                            replaceTop(playerDestFor(planNext, dest.displayName, plan, dest.shuffleAt + 1))
                            return@navigate
                        }
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
                            // R303 — same series, same logo and name for the whole binge.
                            logoUrl = dest.logoUrl, logoInk = dest.logoInk, seriesName = dest.seriesName,
                        ))
                    },
                )
                } // CompositionLocalProvider (R343)
            }

            is Dest.CastRemote -> {
                val cc = castActive
                if (cc == null) { LaunchedEffect(Unit) { pop() } }
                // R338 — the remote draws the film player's own picker sheets, so it stays dark like the player.
                else dev.jellystructure.ravilo.ui.theme.KeepDark { CastRemoteScreen(
                    cast = cc,
                    onBack = { pop() },
                    onPlayAgain = { itemId ->
                        val st = cc.sender.status.value
                        cc.cast(itemId = itemId, title = st?.title ?: "", kicker = st?.kicker, artUrl = st?.artUrl, positionMs = 0L, lang = lang)
                    },
                    // R299 (FR-R299-2) — the receiver could not play it; end the cast (or Play would cast
                    // again, FR-R245-4) and open this phone's own player at the start.
                    onPlayHere = { itemId, title, kicker ->
                        cc.sender.stop()
                        pop()
                        push(Dest.Player(itemId = itemId, title = title, kicker = kicker, displayName = dest.displayName))
                    },
                ) }
            }

            is Dest.LiveTv -> {
                // remember(dest.channelId) — a zap/number-entry re-tune inside the player mutates the
                // SAME store instance in place (LiveTvPlayerStore.tune), it does not push a new Dest;
                // this key only matters if the caller navigates to a genuinely different channel Dest.
                val store = remember(dest.channelId) { LiveTvPlayerStore(apiClient) }
                dev.jellystructure.ravilo.ui.theme.KeepDark {   // R338 (FR-R338-2) — a player stays dark
                    LiveTvPlayerScreen(
                        channelId = dest.channelId,
                        store = store,
                        onBack = { pop() },
                    )
                }
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
                            RaviloNavTarget.DISCOVER -> push(Dest.Discover(dest.displayName, defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls)))
                        }
                    },
                    onProfile = { openProfile() },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            // R304 — the phone's Profile page and its App language screen.
            is Dest.Profile -> {
                val active = MultiTokenStore.getActive()
                dev.jellystructure.ravilo.ui.screens.ProfileScreen(
                    apiClient = apiClient,
                    displayName = dest.displayName,
                    isAdmin = active?.isAdmin == true,
                    avatarUrl = activeAvatarUrl,
                    serverHost = apiClient.baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    onPhoto = { push(Dest.YourProfile(dest.displayName)) },
                    onMyListSeeAll = { push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onAppLanguage = { push(Dest.AppLanguage(dest.displayName)) },
                    onChangePassword = { push(Dest.ChangePassword(dest.displayName)) },
                    onSettings = { push(Dest.Settings(dest.displayName)) },
                    // R191's shape: this one profile is revoked/forgotten; another cached profile goes to
                    // the picker, none goes to Login.
                    onSignedOut = { resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker) },
                    scrollToTopTick = reselectTick,
                    // R321 (FR-R321-3) — the mode card; R326 (FR-R326-3): a tap switches the mode and stays on Profile.
                    modeCard = if (handset && musicAvailable == true && dev.jellystructure.ravilo.ui.music.MusicEngine.supported) ({
                        dev.jellystructure.ravilo.ui.music.ListeningModeCard(musicMode, withBooks = booksAvailable) { on ->
                            musicMode = on
                            dev.jellystructure.ravilo.ui.music.ListeningMode.write(if (on) dev.jellystructure.ravilo.ui.music.ListeningMode.MUSIC else dev.jellystructure.ravilo.ui.music.ListeningMode.VIDEO)
                            // R326 (FR-R326-3) — switching stays on Profile: only the bar changes (the card toasts the mode).
                        }
                    }) else null,
                )
            }

            // ─── R321/R322 — music mode ───
            is Dest.MusicListen -> {
                val loader = keptStore("mlisten:${dest.displayName}") { dev.jellystructure.ravilo.ui.music.MusicLoader { apiClient.getMusicHome().also { dev.jellystructure.ravilo.ui.music.MusicVersions.learn(it.versionTypes) } } }
                // R345 (acceptance 2) — the server answers again: a Listen page that failed loads without a relaunch.
                LaunchedEffect(loader) {
                    serverOpens.drop(1).collect { if (loader.state.value is dev.jellystructure.ravilo.ui.music.Load.Failed) loader.load() }
                }
                dev.jellystructure.ravilo.ui.music.MusicListenScreen(
                    loader = loader,
                    onProfile = { openProfile() },
                    onOpenAlbum = { id -> push(Dest.AlbumDetail(id, dest.displayName)) },
                    onOpenArtist = { id -> push(Dest.ArtistDetail(id, dest.displayName)) },
                    onSeeAllPlayed = {
                        keptStore("mbrowse:${dest.displayName}") { dev.jellystructure.ravilo.ui.music.MusicBrowseStore(apiClient) }.setSort("songs", "played")
                        resetTo(Dest.MusicBrowse(dest.displayName, chip = "songs"))
                    },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                    scrollToTopTick = reselectTick,
                    books = if (booksAvailable) bookStore else null,
                    onResumeBook = { id -> uiScope.launch { dev.jellystructure.ravilo.ui.music.resumeBook(apiClient, id); bookStore.refresh(id) } },
                )
            }
            is Dest.MusicBrowse -> {
                val store = keptStore("mbrowse:${dest.displayName}") { dev.jellystructure.ravilo.ui.music.MusicBrowseStore(apiClient) }
                dev.jellystructure.ravilo.ui.music.MusicBrowseScreen(
                    store = store,
                    chip = dest.chip,
                    onChip = { c -> replaceTop(dest.copy(chip = c, focusInput = false)) },
                    focusInput = dest.focusInput,
                    onFocusInputConsumed = { replaceTop(dest.copy(focusInput = false)) },
                    externalQuery = if (deskSidebarShown) deskQuery else null,   // R337 — the sidebar's field
                    ownField = deskRailSearch,
                    onProfile = { openProfile() },
                    onOpenAlbum = { id -> push(Dest.AlbumDetail(id, dest.displayName)) },
                    onOpenArtist = { id -> push(Dest.ArtistDetail(id, dest.displayName)) },
                    onOpenPlaylist = { p -> push(Dest.PlaylistDetail(p.id, p.name, dest.displayName)) },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                    scrollToTopTick = reselectTick,
                    books = if (booksAvailable) bookStore else null,
                    onResumeBook = { id -> uiScope.launch { dev.jellystructure.ravilo.ui.music.resumeBook(apiClient, id); bookStore.refresh(id) } },
                    onOpenBook = { id -> push(Dest.AudiobookDetail(id, dest.displayName)) },
                    onOpenBookAuthor = { id -> push(Dest.AudiobookAuthor(id, dest.displayName)) },
                )
            }
            is Dest.MusicPlaying -> {
                // FR-R322-3 — nothing playing: the last queue this phone kept (where it was), else the viewer's last-played
                // song, loaded paused and not started.
                LaunchedEffect(Unit) { restoreLastListening() }
                dev.jellystructure.ravilo.ui.music.MusicPlayingScreen(
                    api = apiClient,
                    onClose = { if (stack.size > 1) pop() else resetTo(homeDest(dest.displayName)) },
                    onOpenAlbum = { id -> push(Dest.AlbumDetail(id, dest.displayName)) },
                    onOpenArtist = { id -> push(Dest.ArtistDetail(id, dest.displayName)) },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                    onFavorite = { t, fav -> setMusicFavorite(t, fav) },
                    books = bookStore,
                    onOpenBook = { id -> push(Dest.AudiobookDetail(id, dest.displayName)) },
                    onOpenBookAuthor = { id -> push(Dest.AudiobookAuthor(id, dest.displayName)) },
                )
            }
            is Dest.MusicQueue -> dev.jellystructure.ravilo.ui.music.MusicQueueScreen(
                onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                onProfile = { openProfile() },
            )
            is Dest.AlbumDetail -> {
                val loader = keptStore("malbum:${dest.id}") { dev.jellystructure.ravilo.ui.music.MusicLoader { apiClient.getMusicAlbum(dest.id) } }
                dev.jellystructure.ravilo.ui.music.MusicAlbumScreen(
                    loader = loader,
                    onBack = { pop() },
                    onOpenArtist = { id -> push(Dest.ArtistDetail(id, dest.displayName)) },
                    onOpenAlbum = { id -> push(Dest.AlbumDetail(id, dest.displayName)) },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                )
            }
            is Dest.ArtistDetail -> {
                val loader = keptStore("martist:${dest.id}:$lang") { dev.jellystructure.ravilo.ui.music.MusicLoader { apiClient.getMusicArtist(dest.id, lang) } }
                dev.jellystructure.ravilo.ui.music.MusicArtistScreen(
                    loader = loader,
                    onBack = { pop() },
                    onOpenAlbum = { id -> push(Dest.AlbumDetail(id, dest.displayName)) },
                    // FR-R321-11 — a music video is a video: the film player (which stops the music, FR-R322-12).
                    onPlayVideo = { v -> push(Dest.Player(itemId = v.id, title = v.title, displayName = dest.displayName, seriesId = v.id, posterUrl = v.imageUrl)) },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                )
            }
            is Dest.AudiobookDetail -> dev.jellystructure.ravilo.ui.music.AudiobookDetailScreen(
                store = bookStore, api = apiClient, id = dest.id,
                onBack = { pop() },
                onOpenAuthor = { id -> push(Dest.AudiobookAuthor(id, dest.displayName)) },
                onOpenPlaying = { resetTo(Dest.MusicPlaying(dest.displayName)) },
            )
            is Dest.AudiobookAuthor -> dev.jellystructure.ravilo.ui.music.AudiobookAuthorScreen(
                store = bookStore, id = dest.id,
                onBack = { pop() },
                onOpenBook = { id -> push(Dest.AudiobookDetail(id, dest.displayName)) },
            )
            is Dest.PlaylistDetail -> {
                val loader = keptStore("mplaylist:${dest.id}") { dev.jellystructure.ravilo.ui.music.MusicLoader { apiClient.getMusicPlaylist(dest.id) } }
                dev.jellystructure.ravilo.ui.music.MusicPlaylistScreen(
                    name = dest.name,
                    loader = loader,
                    onBack = { pop() },
                    onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) },
                )
            }

            is Dest.AppLanguage -> dev.jellystructure.ravilo.ui.screens.AppLanguageScreen(
                apiClient = apiClient,
                onBack = { pop() },
            )

            is Dest.Settings -> {
                val store = remember { SettingsStore(apiClient) }
                SettingsScreen(
                    store = store,
                    displayName = dest.displayName,
                    onSkinChange = { themeState.skin = it },
                    onThemeChange = { themeState.apply(it) },   // R338
                    // R191 — SettingsScreen already revoked/forgot just the active profile
                    // (store.signOutActiveSession()) before this fires; route to the profile picker
                    // if another cached profile remains, else all the way back to Login.
                    onSignOut = {
                        resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                    },
                    onBack = { pop() },
                    // R340 — "Everyone on this TV" (store.unpairDevice() has already cleared MultiTokenStore):
                    // the server is forgotten too, so the TV starts again at server setup.
                    onSignedOutEveryone = onSignedOutEveryone,
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
        } } } } // CompositionLocalProvider (R337) / Box (per-dest insets, R261) / when / AnimatedContent
        // R337 (FR-R337-6) — the desktop's player bar at the content's foot.
        if (deskBarShows(dest)) dev.jellystructure.ravilo.ui.music.DesktopMusicBar(
            medium = deskMedium,
            queueOpen = queueOpen,
            onOpenPlaying = { if (inMusic) resetTo(Dest.MusicPlaying(destDisplayName(dest))) else push(Dest.MusicPlaying(destDisplayName(dest))) },
            onQueue = { queueOpen = !queueOpen },
            modifier = Modifier.align(Alignment.BottomCenter),
            queueOverlay = queueOpen && !deskLarge && deskQueueShows(dest),
        )
        // R337 (FR-R337-8) — 600–1199 dp: the queue over the content, from the right.
        if (desktop && queueOpen && !deskLarge && deskQueueShows(dest)) {
            // A click beside the panel closes it (the capsule's queue button and Esc do too).
            Box(Modifier.fillMaxSize().padding(bottom = dev.jellystructure.ravilo.ui.music.desktopMusicBarInset())
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { queueOpen = false })
            dev.jellystructure.ravilo.ui.music.DesktopQueuePanel(overlay = true, onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) }, modifier = Modifier.align(Alignment.TopEnd))
        }
        // FR-R337-2/5 — the window's own controls where Ravilo draws the frame (GNOME), and the drag area.
        // The start buttons (a desktop that keeps them on the left) are the sidebar's or the rail's while that shows; the
        // page's own toolbar moves the window on a framed page, so the strip only adds its drag area where there is none.
        if (desktop || (handset && isDesktopPlatform)) dev.jellystructure.ravilo.ui.seams.DesktopTitleStrip(
            Modifier.align(Alignment.TopCenter), startControls = !deskNavShows(dest), drag = !deskFramed(dest),
        )
        // FR-R337-2 — the phone layout on a Mac: its 32 dp strip holds the traffic lights and moves the window.
        if (handset && isMacPlatform) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(32.dp).windowDragArea())
        } // Box (the page)
        // R337 (FR-R337-8) — 1200 dp and wider: the queue as a side panel that pushes the content.
        if (desktop && queueOpen && deskLarge && deskQueueShows(dest)) {
            dev.jellystructure.ravilo.ui.music.DesktopQueuePanel(overlay = false, onTrackMore = { trackSheet = dev.jellystructure.ravilo.ui.music.TrackSheetRequest(it) })
        }
        } // Row (R337)
        // R337 — GNOME's primary menu ☰ (FR-R337-10), the keyboard shortcuts window, and Sign out (R304's one-profile ask).
        if (deskMenuOpen) dev.jellystructure.ravilo.ui.components.DesktopPrimaryMenu(
            viewerName = MultiTokenStore.getActive()?.displayName ?: "",
            musicAvailable = musicAvailable == true,
            onDismiss = { deskMenuOpen = false },
            onMode = { deskMenuOpen = false; switchMode(it) },
            onSettings = { deskMenuOpen = false; push(Dest.Settings(destDisplayName(dest))) },
            onShortcuts = { deskMenuOpen = false; shortcutsOpen = true },
            onAbout = { deskMenuOpen = false; dev.jellystructure.ravilo.ui.seams.showAboutWindow() },
            onSignOut = { deskMenuOpen = false; deskSignOutAsk = true },
        )
        if (shortcutsOpen) dev.jellystructure.ravilo.ui.components.DesktopShortcutsOverlay(onDismiss = { shortcutsOpen = false })
        // R337 — the Settings window. Change password and Sign out are the app's own page and question: the window
        // closes and the app's window takes over.
        LaunchedEffect(activeUserId) { if (activeUserId == null) deskSettingsOpen = false }
        if (deskSettingsOpen && activeUserId != null) {
            val settingsName = MultiTokenStore.getActive()?.displayName.orEmpty()
            val settingsStore = remember(activeUserId) { SettingsStore(apiClient) }
            dev.jellystructure.ravilo.ui.seams.DesktopSettingsWindow(title = str(if (isMacPlatform) "nav.settings" else "desk.preferences"), onClose = { deskSettingsOpen = false }) {
                dev.jellystructure.ravilo.ui.screens.DesktopSettingsPanel(
                    store = settingsStore,
                    displayName = settingsName,
                    onSkinChange = { themeState.skin = it },
                    onThemeChange = { themeState.apply(it) },
                    onChangePassword = { deskSettingsOpen = false; push(Dest.ChangePassword(settingsName)) },
                    onSignOut = { deskSettingsOpen = false; deskSignOutAsk = true },
                    onClose = { deskSettingsOpen = false },
                )
            }
        }
        if (deskSignOutAsk) dev.jellystructure.ravilo.ui.screens.ConfirmOverlay(
            title = str("profile.signout_title_desk"),
            description = str("profile.signout_body"),
            confirmLabel = str("profile.signout_confirm"),
            onCancel = { deskSignOutAsk = false },
            onConfirm = {
                deskSignOutAsk = false
                uiScope.launch {
                    dev.jellystructure.ravilo.ui.screens.signOutActiveSession(apiClient)
                    resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                }
            },
        )

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
        if (handset && barShows(dest)) {
            // R274 (FR-R274-2) — includeIme = false: the bar is window furniture, so the keyboard is
            // drawn OVER it. Unioning the IME here is what made it climb onto the keyboard's top edge,
            // and (since union takes the larger side) swallowed its own navigation-bar inset on the way,
            // leaving the labels flush against the keys.
            Box(Modifier.fillMaxSize().safeAreaPadding(includeIme = false), contentAlignment = Alignment.BottomCenter) {
                RaviloBottomNav(
                    // R321 (FR-R321-4) — music mode's bar: Listen · Browse · Playing · Queue · Profile.
                    items = if (inMusic) dev.jellystructure.ravilo.ui.components.MUSIC_BAR else dev.jellystructure.ravilo.ui.components.VIDEO_BAR,
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
                            // R304 (FR-R304-1) — Profile is a PAGE on a phone (R267 FR-R267-5d superseded):
                            // it takes the pill, and a re-tap scrolls it to the top like the other four.
                            BottomNavItem.PROFILE ->
                                if (alreadyHere) reselectTick++
                                else resetTo(Dest.Profile(destDisplayName(dest)))
                            // FR-R267-9 — a re-tap scrolls the page to its top (OnReselect in each
                            // page's branch below); it never rebuilds the page or reloads it.
                            BottomNavItem.HOME ->
                                if (alreadyHere) reselectTick++
                                else resetTo(Dest.Home(destDisplayName(dest)))
                            // R321 (FR-R321-4) — music mode's four. Listen scrolls to top on a re-tap; Browse raises
                            // the keyboard on a re-tap (never on arrival); Playing and Queue do nothing.
                            BottomNavItem.LISTEN ->
                                if (alreadyHere) reselectTick++
                                else resetTo(Dest.MusicListen(destDisplayName(dest)))
                            BottomNavItem.BROWSE ->
                                if (alreadyHere && dest is Dest.MusicBrowse) replaceTop(dest.copy(focusInput = true))
                                else resetTo(Dest.MusicBrowse(destDisplayName(dest)))
                            BottomNavItem.PLAYING -> if (!alreadyHere) resetTo(Dest.MusicPlaying(destDisplayName(dest)))
                            BottomNavItem.QUEUE -> if (!alreadyHere) resetTo(Dest.MusicQueue(destDisplayName(dest)))
                            // FR-R267-5b — Movies and Series are one page on a phone. This is a
                            // presentation change, not a new screen: Dest.Browse is already one screen
                            // with a type parameter, so Library is that screen with its parameter
                            // exposed as a control instead of as two nav items.
                            // FR-R278-3 — resetTo, not replaceTop: from a pushed page, replacing the
                            // top would leave the section it came from underneath ([Discover, Search]),
                            // and Back would then return there instead of following R275's ladder. On
                            // one of the four pages the stack is already size 1, so the two are the
                            // same act.
                            // FR-R267-9 — a re-tap keeps the type: a viewer who filtered to Series and
                            // scrolled down wants the top of Series.
                            BottomNavItem.LIBRARY ->
                                if (alreadyHere) reselectTick++
                                else resetTo(Dest.Browse(BrowseKind.ALL, destDisplayName(dest)))
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
                            // The tick also scrolls the segment's content to its top: when the viewer
                            // is already on the first segment the destination does not change at all.
                            BottomNavItem.DISCOVER -> {
                                if (alreadyHere) reselectTick++
                                resetTo(Dest.Discover(
                                    destDisplayName(dest),
                                    defaultDiscoverSegment(upcomingAvailable, discoverAvailable, taxonomyWalls),
                                ))
                            }
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
                // R191 — mirrors onUnpair's shape but only one profile was revoked/forgotten; go to
                // the picker if another cached profile remains, else all the way to Login.
                onSignedOut = {
                    profileMenuOpen = false
                    resetTo(if (MultiTokenStore.getAll().isEmpty()) Dest.Login else Dest.ProfilePicker)
                },
                onSignedOutEveryone = { profileMenuOpen = false; onSignedOutEveryone() },
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
                        .padding(bottom = (if (handset && barShows(dest)) RaviloDimens.bottomNavHeight else 0.dp) +
                            // R322 (FR-R322-10) — with a film casting too the bars stack: the cast bar on top, music under it.
                            (if (musicMiniOver(dest)) RaviloDimens.musicMiniBarHeight else 0.dp)),
                ) { CastMiniBar(onOpen = { push(Dest.CastRemote(currentDisplayNameForCast)) }) }
            }
        }
        // R322 (FR-R322-10) — the music mini bar docks on top of the bottom bar, where the cast mini bar docks; on a
        // detail page (no bar) it sits at the bottom inset. Tap → the Playing tab.
        if (musicMiniOver(dest)) {
            Box(Modifier.fillMaxSize().safeAreaPadding(includeIme = false), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = if (handset && barShows(dest)) RaviloDimens.bottomNavHeight else 0.dp)) {
                    dev.jellystructure.ravilo.ui.music.MusicMiniBar(onOpen = {
                        if (inMusic) resetTo(Dest.MusicPlaying(destDisplayName(dest))) else push(Dest.MusicPlaying(destDisplayName(dest)))
                    })
                }
            }
        }
        if (handset) {
            Box(Modifier.fillMaxSize().safeAreaPadding(includeIme = false)) {
                dev.jellystructure.ravilo.ui.music.MusicToastHost(
                    (if (barShows(dest)) RaviloDimens.bottomNavHeight else 0.dp) + (if (musicMiniOver(dest)) RaviloDimens.musicMiniBarHeight else 0.dp),
                )
            }
            // FR-R322-11 — ⋯ on a song, over every music page.
            dev.jellystructure.ravilo.ui.music.TrackActionsSheet(
                request = trackSheet,
                onDismiss = { trackSheet = null },
                onGoAlbum = { id -> push(Dest.AlbumDetail(id, destDisplayName(dest))) },
                onGoArtist = { id -> push(Dest.ArtistDetail(id, destDisplayName(dest))) },
                onFavorite = { t, fav -> setMusicFavorite(t, fav) },
            )
        }
        // R265 — the "Play on a TV" sheet, drawn once over every screen (incl. the player), above the bottom
        // bar AND the cast mini bar (drawn before it here, the mini bar sat on top of the open sheet — seen
        // on the Pixel 9). Every cast glyph only asks for it (CastController.openSheet).
        castActive?.let { CastSheetHost(it) }
        // R270 (FR-R270-2) — the AirPlay caveat, once, when the picture moves to the TV (web only).
        AirPlayNoticeBar()
        // R261 — see the profile-menu comment above; the overlay itself has no inset awareness of its
        // own (a plain 6dp corner offset), so without this it could sit under a notch/status bar.
        Box(Modifier.fillMaxSize().safeAreaPadding()) { FrameTrackerOverlay(fpsOverlay) }  // R94: F5 toggles; no-op when false
        ServerMessageHost()  // R152: floats over every screen incl. the player (reads LocalServerMessages)
        UpdateToast()  // R263 (FR-R263-5): no-op off the web
        dev.jellystructure.ravilo.ui.seams.DesktopWindowFrame()  // R337 — GNOME's undecorated window: resize edges and a border
        } // Box (back-intercept)
        } // CompositionLocalProvider (live config)
    } // WithLocale
    } // RaviloTheme
}

/** R245 — the Cast SDK hands art URLs straight to the receiver and the notification, so a relative
 *  `/api/tv/image/...` path must be absolute here (RemoteImage does this itself for on-screen art). */
private fun resolveCastArt(baseUrl: String, url: String?): String? =
    url?.let { if (it.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$it" else it }

/**
 * The player destination for one series episode — the page's Play, a rail pick, Start over (R343, the context
 * carries `startOver`) or one entry of a shuffle ([plan] in play order, [at] this entry's place in it).
 */
private fun playerDestFor(
    ctx: dev.jellystructure.ravilo.ui.screens.EpisodePlayContext,
    displayName: String,
    plan: List<dev.jellystructure.ravilo.ui.screens.EpisodePlayContext>? = null,
    at: Int = 0,
): Dest.Player = Dest.Player(
    itemId        = ctx.episodeId,
    title         = ctx.episodeTitle,
    kicker        = ctx.kicker,
    nextEpId      = ctx.nextEpId,
    nextEpLabel   = ctx.nextEpLabel,
    nextEpTitle   = ctx.nextEpTitle,
    displayName   = displayName,
    episodes      = ctx.episodes,
    currentEpIndex = ctx.currentEpIndex,
    seriesId      = ctx.seriesId,
    logoUrl = ctx.logoUrl, logoInk = ctx.logoInk, seriesName = ctx.seriesName,  // R303 — the SERIES' logo, never an episode's
    originalLanguage = ctx.originalLanguage,
    segments      = ctx.segments,  // Phase 150
    posterUrl     = ctx.seriesPosterUrl,  // R194
    startOver     = ctx.startOver,   // R343
    shufflePlan   = plan, shuffleAt = at, shuffled = plan != null,   // R343
)

/** R343 (FR-R343-8) — a shuffle as the Chromecast's episode list, in play order: each entry its own id, title,
 *  `Shuffle · S02E07` kicker, still and markers. */
private fun castShuffle(plan: List<dev.jellystructure.ravilo.ui.screens.EpisodePlayContext>): List<dev.jellystructure.shared.tv.CastEpisode> =
    plan.map { c ->
        dev.jellystructure.shared.tv.CastEpisode(
            id = c.episodeId, title = c.episodeTitle, kicker = c.kicker,
            stillUrl = c.episodes.getOrNull(c.currentEpIndex)?.stillUrls?.firstOrNull { it != null },
            introStartMs = c.segments.introStartMs, introEndMs = c.segments.introEndMs, creditsStartMs = c.segments.creditsStartMs,
        )
    }
