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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.focus.FocusRow
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

    fun load(channelId: String) {
        loadJob?.cancel()
        _state.value = HomeState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { HomeState.Loaded(apiClient.getChannel(channelId)) }
                .getOrElse { HomeState.Error(it.message ?: "Unknown error") }
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

    val storeState by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Channel header
            Column(modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp)) {
                Text("‹ Home", color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(channel.name, color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }

            when (val s = storeState) {
                is HomeState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = colors.textSecondary, fontSize = 16.sp)
                }
                is HomeState.Error -> Box(Modifier.weight(1f).padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
                }
                is HomeState.Loaded -> {
                    val nonEmpty = s.feed.rows.filter { it.items.isNotEmpty() }
                    if (nonEmpty.isEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("Nothing in this channel yet.", color = colors.textSecondary, fontSize = 16.sp)
                        }
                    } else {
                        ChannelRows(rows = nonEmpty, onItemSelect = onItemSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRows(rows: List<Row>, onItemSelect: (MediaCard) -> Unit) {
    val rowFocusStates = remember(rows.size) { rows.map { FocusRow(maxOf(it.items.size, 1)) } }
    var focusedRowIdx by remember { mutableIntStateOf(0) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        items(rows.size, key = { ri -> rows[ri].id }) { ri ->
            val row = rows[ri]
            val rowFocus = rowFocusStates[ri]
            Spacer(Modifier.height(28.dp))
            StaticContentRow(
                title = row.title,
                items = row.items,
                focusedIndex = rowFocus.focused,
                itemKey = { card -> card.id },
            ) { ci, card ->
                val isLandscape = row.kind == RowKind.CONTINUE
                Tile(
                    title = card.title,
                    posterUrl = if (isLandscape) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                    focusRequester = rowFocus.requesters[ci],
                    variant = if (isLandscape) TileVariant.LANDSCAPE else TileVariant.POSTER,
                    progressPct = card.progressPct ?: 0f,
                    onFocused = { rowFocus.focused = ci; focusedRowIdx = ri },
                    onLeft  = { rowFocus.moveLeft() },
                    onRight = { rowFocus.moveRight() },
                    onUp    = { if (ri > 0) { focusedRowIdx = ri - 1; rowFocusStates[ri - 1].requestFocus() } },
                    onDown  = { if (ri < rowFocusStates.lastIndex) { focusedRowIdx = ri + 1; rowFocusStates[ri + 1].requestFocus() } },
                    onSelect = { onItemSelect(card) },
                )
            }
        }
    }
}

