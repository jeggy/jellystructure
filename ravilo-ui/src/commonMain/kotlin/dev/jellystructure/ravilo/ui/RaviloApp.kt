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
import dev.jellystructure.ravilo.ui.screens.DiscoverDetailScreen
import dev.jellystructure.ravilo.ui.screens.DiscoverDetailStore
import dev.jellystructure.ravilo.ui.screens.DiscoverScreen
import dev.jellystructure.ravilo.ui.screens.DiscoverStore
import dev.jellystructure.ravilo.ui.screens.HomeScreen
import dev.jellystructure.ravilo.ui.screens.HomeStore
import dev.jellystructure.ravilo.ui.screens.LocalSession
import dev.jellystructure.ravilo.ui.screens.MovieDetailScreen
import dev.jellystructure.ravilo.ui.screens.MovieDetailStore
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.PairingScreen
import dev.jellystructure.ravilo.ui.screens.PairingStore
import dev.jellystructure.ravilo.ui.screens.PlayerScreen
import dev.jellystructure.ravilo.ui.screens.PlayerStore
import dev.jellystructure.ravilo.ui.screens.ProfilePickerScreen
import dev.jellystructure.ravilo.ui.screens.ProfilePickerStore
import dev.jellystructure.ravilo.ui.screens.SearchScreen
import dev.jellystructure.ravilo.ui.screens.SearchStore
import dev.jellystructure.ravilo.ui.screens.SeriesDetailScreen
import dev.jellystructure.ravilo.ui.screens.SeriesDetailStore
import dev.jellystructure.ravilo.ui.screens.SettingsScreen
import dev.jellystructure.ravilo.ui.screens.SettingsStore
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import coil3.compose.LocalPlatformContext
import dev.jellystructure.ravilo.ui.components.ServerMessageHost
import dev.jellystructure.ravilo.ui.perf.FrameTrackerOverlay
import dev.jellystructure.ravilo.ui.seams.prefetchImage
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.rememberRaviloTheme
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.ServerMessageEnvelope
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.tileScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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

/** Tile-size multiplier from the active user's `RaviloConfig.uiDensity`; read by [dev.jellystructure.ravilo.ui.components.Tile]. */
val LocalTileScale = staticCompositionLocalOf { 1f }

/** R61 — server base URL (e.g. `http://192.168.1.100:8080`); used to resolve relative logo/image URLs. */
val LocalServerBaseUrl = staticCompositionLocalOf { "" }

/** R65 — active user's Jellyfin avatar URL; null if the user has no profile picture. */
val LocalUserAvatarUrl = staticCompositionLocalOf<String?> { null }

// ─── Navigation direction (drives AnimatedContent transitionSpec) ─────────────

private enum class NavDir { Forward, Back, Reset }

// ─── Navigation destinations ──────────────────────────────────────────────────

private sealed class Dest {
    data object Pairing : Dest()
    data object ProfilePicker : Dest()
    data class Home(val displayName: String) : Dest()
    data class ChannelView(val channel: Channel, val displayName: String) : Dest()
    data class Browse(val kind: BrowseKind, val displayName: String) : Dest()
    data class Search(val displayName: String) : Dest()
    data class Discover(val displayName: String) : Dest()
    data class DiscoverItem(val listId: String, val rank: Int, val displayName: String) : Dest()
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
    ) : Dest()
    data class Settings(val displayName: String) : Dest()

    // R80: each Dest maps to a hash route (web) or is ignored (android/TV).
    fun toRoute(): String = when (this) {
        is Pairing        -> "/pairing"
        is ProfilePicker  -> "/profiles"
        is Home           -> "/home"
        is ChannelView    -> "/channel/${channel.id}"
        is Browse         -> "/browse/${kind.name.lowercase()}"
        is Search         -> "/search"
        is Discover       -> "/discover"
        is DiscoverItem   -> "/discover/$listId/$rank"
        is MovieDetail    -> "/movie/$itemId"
        is SeriesDetail   -> "/series/$itemId"
        is Player         -> "/player/$itemId"
        is Settings       -> "/settings"
    }
}

// ─── Root composable ──────────────────────────────────────────────────────────

@Composable
fun RaviloApp(apiClient: TvApiClient, initialDisplayName: String = "", onChangeServer: () -> Unit = {}) {
    var lang by remember { mutableStateOf("en") }
    var tileScale by remember { mutableStateOf(1f) }
    val themeState = rememberRaviloTheme()

    // Fetch the active user's config and apply server-owned interface prefs (language + skin)
    val configScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    fun refreshConfig() {
        configScope.launch {
            runCatching { apiClient.getConfig() }.getOrNull()?.let { cfg ->
                lang = cfg.uiLanguage
                themeState.skin = cfg.effectiveSkin()
                tileScale = cfg.uiDensity.tileScale()
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
    var activeUserId by remember { mutableStateOf(MultiTokenStore.getActive()?.userId) }
    var activeAvatarUrl by remember { mutableStateOf(MultiTokenStore.getActive()?.avatarUrl) }

    LaunchedEffect(Unit) { liveConfig.collect { refreshConfig() } }
    LaunchedEffect(activeUserId) {
        if (activeUserId == null) return@LaunchedEffect
        var backoff = 1000L
        while (true) {
            runCatching {
                apiClient.connectEvents(
                    onOpen = { backoff = 1000L; liveConfig.emit(0L) },
                    onEvent = { liveConfig.emit(it.rev) },
                    onAcquisition = { liveAcquisition.emit(it) },
                    onServerMessage = { liveServerMessages.emit(it) },
                )
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
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
                sessions.isEmpty() -> Dest.Pairing
                sessions.size == 1 -> {
                    MultiTokenStore.setActive(sessions.first().userId)
                    Dest.Home(sessions.first().displayName)
                }
                else -> Dest.ProfilePicker
            }
        }
        var stack by remember { mutableStateOf(listOf<Dest>(initialDest)) }
        // R92: direction that drives the AnimatedContent transitionSpec.
        var navDir by remember { mutableStateOf(NavDir.Forward) }
        var fpsOverlay by remember { mutableStateOf(false) }  // R94: toggle with F5
        // Top-level: track whether the Top 10 tab is available; set from HomeStore, propagated to all screens.
        var discoverAvailable by remember { mutableStateOf(false) }

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

        CompositionLocalProvider(LocalLiveConfig provides liveConfig, LocalLiveAcquisition provides liveAcquisition, LocalServerMessages provides liveServerMessages, LocalTileScale provides tileScale, LocalCompact provides compact, LocalServerBaseUrl provides apiClient.baseUrl, LocalUserAvatarUrl provides activeAvatarUrl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onKeyEvent { ev ->
                    when {
                        ev.type != KeyEventType.KeyDown -> false
                        ev.key == Key.F5 -> { fpsOverlay = !fpsOverlay; true }  // R94 debug toggle
                        (ev.key == Key.Back || ev.key == Key.Escape || ev.key == Key.Backspace) && stack.size > 1 ->
                            { pop(); true }
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
                        (slideInHorizontally(tween(RaviloMotion.ScreenEnterMs)) { it / 4 } +
                            fadeIn(tween(RaviloMotion.ScreenEnterMs))) togetherWith
                        (slideOutHorizontally(tween(RaviloMotion.ScreenExitMs)) { -it / 4 } +
                            fadeOut(tween(RaviloMotion.ScreenExitMs)))
                    NavDir.Back ->
                        (slideInHorizontally(tween(RaviloMotion.ScreenEnterMs)) { -it / 4 } +
                            fadeIn(tween(RaviloMotion.ScreenEnterMs))) togetherWith
                        (slideOutHorizontally(tween(RaviloMotion.ScreenExitMs)) { it / 4 } +
                            fadeOut(tween(RaviloMotion.ScreenExitMs)))
                    NavDir.Reset ->
                        fadeIn(tween(RaviloMotion.ScreenEnterMs)) togetherWith
                            fadeOut(tween(RaviloMotion.ScreenExitMs))
                }
            },
            contentKey = { it::class },
        ) { dest -> when (dest) {
            is Dest.ProfilePicker -> {
                val store = remember { ProfilePickerStore(apiClient) }
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

            is Dest.Pairing -> {
                val store = remember { PairingStore(apiClient) }
                PairingScreen(
                    store = store,
                    onPaired = {
                        val active = MultiTokenStore.getActive()
                        activeUserId = active?.userId
                        activeAvatarUrl = active?.avatarUrl
                        refreshConfig()
                        // Reset the stack so Back from Home doesn't return to pairing,
                        // and carry the freshly-paired user's display name.
                        val name = MultiTokenStore.getActive()?.displayName ?: ""
                        resetTo(Dest.Home(name))
                    },
                    onChangeServer = onChangeServer,
                )
            }

            is Dest.Home -> {
                val store = keptStore("home:${dest.displayName}") { HomeStore(apiClient) }
                val da by store.discoverAvailable.collectAsState()
                SideEffect { discoverAvailable = da }
                // R141: on every Home re-entry (including Back-returns), emit on liveConfig so the store
                // does a silent re-pull. HomeStore.refresh(silent=true) keeps the current content visible
                // and swaps in the new feed when it arrives — no Loading flash.
                LaunchedEffect(Unit) { liveConfig.emit(0L) }
                HomeScreen(
                    store = store,
                    displayName = dest.displayName,
                    onProfile = { push(Dest.ProfilePicker) },
                    onNavSelect = { idx ->
                        when (idx) {
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> push(Dest.Discover(dest.displayName)) // Top 10 (index 3); My List at 4
                            4 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {} // 0 = already home
                        }
                    },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onItemPlay = { card ->
                        when {
                            // A series needs episode-resolution (resume point + rail) that only the
                            // detail screen has, so Play opens it; a movie plays directly.
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.Player(card.id, card.title, displayName = dest.displayName))
                        }
                    },
                    onChannelSelect = { ch -> push(Dest.ChannelView(ch, dest.displayName)) },
                    onSeeAll = { push(Dest.Browse(BrowseKind.ALL, dest.displayName)) },
                )
            }

            is Dest.ChannelView -> {
                val store = keptStore("channel:${dest.displayName}:${dest.channel.id}") { ChannelStore(apiClient) }
                ChannelScreen(
                    channel = dest.channel,
                    store = store,
                    displayName = dest.displayName,
                    discoverAvailable = discoverAvailable,
                    onBack = { pop() },
                    onNavSelect = { idx ->   // R136: nav tabs on the channel page
                        when (idx) {
                            0 -> resetTo(Dest.Home(dest.displayName))
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> if (discoverAvailable) push(Dest.Discover(dest.displayName))
                                 else push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {}
                        }
                    },
                    onProfile = { push(Dest.ProfilePicker) },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                    onItemSelect = { openDetail(it, dest.displayName) },
                )
            }

            is Dest.Browse -> {
                val store = keptStore("browse:${dest.displayName}:${dest.kind}") { BrowseStore(apiClient) }
                BrowseScreen(
                    kind = dest.kind,
                    store = store,
                    displayName = dest.displayName,
                    discoverAvailable = discoverAvailable,
                    onBack = { pop() },
                    onNavSelect = { idx ->
                        when (idx) {
                            0 -> resetTo(Dest.Home(dest.displayName))
                            1 -> replaceTop(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> replaceTop(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> if (discoverAvailable) push(Dest.Discover(dest.displayName))
                                 else replaceTop(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> replaceTop(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {}
                        }
                    },
                    onItemSelect = { openDetail(it, dest.displayName) },
                    onProfile = { push(Dest.ProfilePicker) },
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

            is Dest.Discover -> {
                val store = keptStore("discover:${dest.displayName}") { DiscoverStore(apiClient) }
                DiscoverScreen(
                    store = store,
                    displayName = dest.displayName,
                    onNavSelect = { idx ->
                        when (idx) {
                            0 -> resetTo(Dest.Home(dest.displayName))
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            4 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {} // 3 = already on Top 10; 0 = go home handled above
                        }
                    },
                    onEntrySelect = { listId, rank -> push(Dest.DiscoverItem(listId, rank, dest.displayName)) },
                    onProfile = { push(Dest.ProfilePicker) },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.DiscoverItem -> {
                val store = remember(dest.listId, dest.rank) { DiscoverDetailStore(apiClient, dest.listId, dest.rank) }
                DiscoverDetailScreen(
                    store = store,
                    onWatchMovie = { itemId, title -> push(Dest.Player(itemId, title, displayName = dest.displayName)) },
                    onGoToSeries = { itemId -> push(Dest.SeriesDetail(itemId, dest.displayName)) },
                )
            }

            is Dest.MovieDetail -> {
                val store = keptStore("movie:${dest.displayName}:${dest.itemId}") { MovieDetailStore(apiClient) }
                MovieDetailScreen(
                    itemId = dest.itemId,
                    store = store,
                    onBack = { pop() },
                    onPlay = { card -> push(Dest.Player(card.id, card.title, displayName = dest.displayName)) },
                    onRelatedSelect = { openDetail(it, dest.displayName) },
                    displayName = dest.displayName,
                    discoverAvailable = discoverAvailable,
                    onNavSelect = { idx ->
                        when (idx) {
                            0 -> resetTo(Dest.Home(dest.displayName))
                            1 -> resetTo(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> resetTo(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> if (discoverAvailable) resetTo(Dest.Discover(dest.displayName))
                                 else resetTo(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> resetTo(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {}
                        }
                    },
                    onProfile = { push(Dest.ProfilePicker) },
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
                        ))
                    },
                    onRelatedSelect = { openDetail(it, dest.displayName) },
                    displayName = dest.displayName,
                    discoverAvailable = discoverAvailable,
                    onNavSelect = { idx ->
                        when (idx) {
                            0 -> resetTo(Dest.Home(dest.displayName))
                            1 -> resetTo(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> resetTo(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> if (discoverAvailable) resetTo(Dest.Discover(dest.displayName))
                                 else resetTo(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> resetTo(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            else -> {}
                        }
                    },
                    onProfile = { push(Dest.ProfilePicker) },
                    onSearch = { push(Dest.Search(dest.displayName)) },
                )
            }

            is Dest.Player -> {
                val store = remember(dest.itemId) { PlayerStore(apiClient) }
                PlayerScreen(
                    itemId           = dest.itemId,
                    itemTitle        = dest.title,
                    itemKicker       = dest.kicker,
                    nextEpisodeId    = dest.nextEpId,
                    nextEpisodeLabel = dest.nextEpLabel,
                    nextEpisodeTitle = dest.nextEpTitle,
                    episodes         = dest.episodes,
                    currentEpIndex   = dest.currentEpIndex,
                    store            = store,
                    onBack           = { pop() },
                    onNavigateToEpisode = { nextId ->
                        val eps = dest.episodes ?: return@PlayerScreen
                        val newIdx = eps.indexOfFirst { it.id == nextId }
                        if (newIdx < 0) return@PlayerScreen
                        val newEp = eps[newIdx]
                        val nextEp = eps.getOrNull(newIdx + 1)
                        replaceTop(Dest.Player(
                            itemId         = newEp.id,
                            title          = newEp.title,
                            kicker         = newEp.kicker,
                            nextEpId       = nextEp?.id,
                            nextEpLabel    = nextEp?.kicker,
                            nextEpTitle    = nextEp?.title,
                            displayName    = dest.displayName,
                            episodes       = eps,
                            currentEpIndex = newIdx,
                        ))
                    },
                )
            }

            is Dest.Settings -> {
                val store = remember { SettingsStore(apiClient) }
                SettingsScreen(
                    store = store,
                    displayName = dest.displayName,
                    onSkinChange = { themeState.skin = it },
                    onSignOut = { resetTo(Dest.Pairing) },
                    onBack = { pop() },
                )
            }
        } } // when / AnimatedContent
        FrameTrackerOverlay(fpsOverlay)  // R94: F5 toggles; no-op when false
        ServerMessageHost()  // R152: floats over every screen incl. the player (reads LocalServerMessages)
        } // Box (back-intercept)
        } // CompositionLocalProvider (live config)
    } // WithLocale
    } // RaviloTheme
}
