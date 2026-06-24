package dev.jellystructure.ravilo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.rememberRaviloTheme
import dev.jellystructure.shared.tv.AcquisitionRecord
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

/** Tile-size multiplier from the active user's `RaviloConfig.uiDensity`; read by [dev.jellystructure.ravilo.ui.components.Tile]. */
val LocalTileScale = staticCompositionLocalOf { 1f }

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
    var activeUserId by remember { mutableStateOf(MultiTokenStore.getActive()?.userId) }

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
                )
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
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

        fun push(dest: Dest) { stack = stack + dest }
        fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

        val dest = stack.last()

        CompositionLocalProvider(LocalLiveConfig provides liveConfig, LocalLiveAcquisition provides liveAcquisition, LocalTileScale provides tileScale) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.Back || ev.key == Key.Escape) &&
                        stack.size > 1
                    ) { pop(); true } else false
                }
        ) {
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
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
                        activeUserId = MultiTokenStore.getActive()?.userId
                        refreshConfig()
                        // Reset the stack so Back from Home doesn't return to pairing,
                        // and carry the freshly-paired user's display name.
                        val name = MultiTokenStore.getActive()?.displayName ?: ""
                        stack = listOf(Dest.Home(name))
                    },
                    onChangeServer = onChangeServer,
                )
            }

            is Dest.Home -> {
                val store = keptStore("home:${dest.displayName}") { HomeStore(apiClient) }
                HomeScreen(
                    store = store,
                    displayName = dest.displayName,
                    onProfile = { push(Dest.ProfilePicker) },
                    onNavSelect = { idx ->
                        when (idx) {
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> push(Dest.Search(dest.displayName))
                            5 -> push(Dest.Discover(dest.displayName)) // gated Top 10 tab (R49)
                            else -> {} // 0 = already home
                        }
                    },
                    onItemSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
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
                    onBack = { pop() },
                    onItemSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
                )
            }

            is Dest.Browse -> {
                val store = keptStore("browse:${dest.displayName}:${dest.kind}") { BrowseStore(apiClient) }
                BrowseScreen(
                    kind = dest.kind,
                    store = store,
                    onBack = { pop() },
                    onItemSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
                )
            }

            is Dest.Search -> {
                val store = keptStore("search:${dest.displayName}") { SearchStore(apiClient) }
                SearchScreen(
                    store = store,
                    onBack = { pop() },
                    onItemSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
                )
            }

            is Dest.Discover -> {
                val store = keptStore("discover:${dest.displayName}") { DiscoverStore(apiClient) }
                DiscoverScreen(
                    store = store,
                    displayName = dest.displayName,
                    onNavSelect = { idx ->
                        when (idx) {
                            0 -> { stack = listOf(Dest.Home(dest.displayName)) }
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> push(Dest.Search(dest.displayName))
                            else -> {} // 5 = already on Top 10
                        }
                    },
                    onEntrySelect = { listId, rank -> push(Dest.DiscoverItem(listId, rank, dest.displayName)) },
                    onProfile = { push(Dest.ProfilePicker) },
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
                    onRelatedSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
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
                    onRelatedSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
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
                        stack = stack.dropLast(1) + Dest.Player(
                            itemId         = newEp.id,
                            title          = newEp.title,
                            kicker         = newEp.kicker,
                            nextEpId       = nextEp?.id,
                            nextEpLabel    = nextEp?.kicker,
                            nextEpTitle    = nextEp?.title,
                            displayName    = dest.displayName,
                            episodes       = eps,
                            currentEpIndex = newIdx,
                        )
                    },
                )
            }

            is Dest.Settings -> {
                val store = remember { SettingsStore(apiClient) }
                SettingsScreen(
                    store = store,
                    displayName = dest.displayName,
                    onSkinChange = { themeState.skin = it },
                    onSignOut = { stack = listOf(Dest.Pairing) },
                    onBack = { pop() },
                )
            }
        } } // when / AnimatedContent
        } // Box (back-intercept)
        } // CompositionLocalProvider (live config)
    } // WithLocale
    } // RaviloTheme
}
