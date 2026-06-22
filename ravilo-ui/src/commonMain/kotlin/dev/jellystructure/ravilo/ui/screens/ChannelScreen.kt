package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.components.toTileVariant
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import androidx.compose.runtime.collectAsState
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null

    fun load(channelId: String) {
        // R40: re-entry with the same channel keeps the cached feed and refreshes silently (no flash).
        if (currentId == channelId && _state.value is HomeState.Loaded) { refresh(silent = true); return }
        currentId = channelId
        loadJob?.cancel()
        _state.value = HomeState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { HomeState.Loaded(apiClient.getChannel(channelId)) }
                .getOrElse { HomeState.Error(it.message ?: "Unknown error") }
        }
    }

    /** R33 live refresh: re-pull the channel feed in place (no Loading flash). */
    fun refresh(silent: Boolean = false) {
        val id = currentId ?: return
        if (!silent) { load(id); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getChannel(id) }.getOrNull()?.let { _state.value = HomeState.Loaded(it) }
        }
    }
}

@Composable
fun ChannelScreen(
    channel: Channel,
    store: ChannelStore,
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors

    LaunchedEffect(channel.id) { store.load(channel.id) }

    // R33: silently re-pull this channel when the user's layout changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    val storeState by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Channel header
            Column(modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp)) {
                Text("‹ ${str("nav.home")}", color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(channel.name, color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }

            when (val s = storeState) {
                is HomeState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is HomeState.Error -> Box(Modifier.weight(1f).padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
                }
                is HomeState.Loaded -> {
                    val nonEmpty = s.feed.rows.filter { it.items.isNotEmpty() }
                    if (nonEmpty.isEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(str("browse.empty_channel"), color = colors.textSecondary, fontSize = 16.sp)
                        }
                    } else {
                        ChannelRows(rows = nonEmpty, tileShape = s.feed.tileShape, onItemSelect = onItemSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRows(rows: List<Row>, tileShape: dev.jellystructure.shared.tv.TileShape, onItemSelect: (MediaCard) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        items(rows.size, key = { ri -> rows[ri].id }) { ri ->
            val row = rows[ri]
            Spacer(Modifier.height(28.dp))
            StaticContentRow(
                title = row.title,
                items = row.items,
                itemKey = { card -> card.id },
            ) { _, card ->
                val variant = if (row.kind == RowKind.CONTINUE) TileVariant.LANDSCAPE else tileShape.toTileVariant()
                Tile(
                    title = card.title,
                    posterUrl = if (variant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                    variant = variant,
                    progressPct = card.progressPct ?: 0f,
                    onSelect = { onItemSelect(card) },
                )
            }
        }
    }
}

