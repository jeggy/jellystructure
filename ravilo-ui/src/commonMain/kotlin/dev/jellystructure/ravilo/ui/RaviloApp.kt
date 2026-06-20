package dev.jellystructure.ravilo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ─── Navigation destinations ──────────────────────────────────────────────────

private sealed class Dest {
    data object Pairing : Dest()
    data object ProfilePicker : Dest()
    data class Home(val displayName: String) : Dest()
    data class ChannelView(val channel: Channel, val displayName: String) : Dest()
    data class Browse(val kind: BrowseKind, val displayName: String) : Dest()
    data class Search(val displayName: String) : Dest()
    data class MovieDetail(val itemId: String, val displayName: String) : Dest()
    data class SeriesDetail(val itemId: String, val displayName: String) : Dest()
    data class Player(
        val itemId: String,
        val title: String,
        val kicker: String? = null,
        val nextEpId: String? = null,
        val nextEpLabel: String? = null,
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

    // Fetch the active user's language setting from their config
    val langScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    fun refreshLang() {
        langScope.launch {
            lang = runCatching { apiClient.getConfig().uiLanguage }.getOrDefault("en")
        }
    }

    RaviloTheme {
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

        // Load lang when already on Home (single-session fast path)
        if (initialDest is Dest.Home) {
            androidx.compose.runtime.LaunchedEffect(Unit) { refreshLang() }
        }

        fun push(dest: Dest) { stack = stack + dest }
        fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

        val dest = stack.last()

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
                        refreshLang()
                        push(Dest.Home(session.displayName))
                    },
                )
            }

            is Dest.Pairing -> {
                val store = remember { PairingStore(apiClient) }
                PairingScreen(
                    store = store,
                    onPaired = {
                        refreshLang()
                        push(Dest.Home(displayName = ""))
                    },
                    onChangeServer = onChangeServer,
                )
            }

            is Dest.Home -> {
                val store = remember { HomeStore(apiClient) }
                HomeScreen(
                    store = store,
                    onNavSelect = { idx ->
                        when (idx) {
                            1 -> push(Dest.Browse(BrowseKind.MOVIES, dest.displayName))
                            2 -> push(Dest.Browse(BrowseKind.SERIES, dest.displayName))
                            3 -> push(Dest.Browse(BrowseKind.MY_LIST, dest.displayName))
                            4 -> push(Dest.Search(dest.displayName))
                            else -> {} // 0 = already home
                        }
                    },
                    onItemSelect = { card ->
                        when {
                            card.kind == MediaKind.SERIES -> push(Dest.SeriesDetail(card.id, dest.displayName))
                            else -> push(Dest.MovieDetail(card.id, dest.displayName))
                        }
                    },
                    onChannelSelect = { ch -> push(Dest.ChannelView(ch, dest.displayName)) },
                    onSeeAll = { push(Dest.Browse(BrowseKind.ALL, dest.displayName)) },
                )
            }

            is Dest.ChannelView -> {
                val store = remember(dest.channel.id) { ChannelStore(apiClient) }
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
                val store = remember(dest.kind) { BrowseStore(apiClient) }
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
                val store = remember { SearchStore(apiClient) }
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

            is Dest.MovieDetail -> {
                val store = remember(dest.itemId) { MovieDetailStore(apiClient) }
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
                val store = remember(dest.itemId) { SeriesDetailStore(apiClient) }
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
                            nextEpLabel    = nextEp?.let { "${it.kicker} · ${it.title}" },
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
                    onSignOut = { stack = listOf(Dest.Pairing) },
                    onBack = { pop() },
                )
            }
        } } // when / AnimatedContent
        } // Box (back-intercept)
    } // WithLocale
    } // RaviloTheme
}
