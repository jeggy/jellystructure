package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

sealed class UpcomingDetailState {
    data object Loading : UpcomingDetailState()
    data class Loaded(val item: UpcomingItem) : UpcomingDetailState()
    data class Error(val message: String) : UpcomingDetailState()
}

/**
 * R160 §F — no dedicated single-item endpoint: the whole feed is cheap (server-cached,
 * UpcomingService) and this item's identity is just a lookup within it.
 */
class UpcomingDetailStore(private val apiClient: TvApiClient, private val id: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<UpcomingDetailState>(UpcomingDetailState.Loading)
    val state: StateFlow<UpcomingDetailState> = _state.asStateFlow()

    init {
        scope.launch {
            _state.value = runCatching {
                val feed = apiClient.getUpcoming()
                (feed.items + feed.missing).firstOrNull { it.id == id }
                    ?: error("Not found")
            }.fold(
                onSuccess = { UpcomingDetailState.Loaded(it) },
                onFailure = { UpcomingDetailState.Error(it.message ?: "Unknown error") },
            )
        }
    }
}

@Composable
fun UpcomingDetailScreen(store: UpcomingDetailStore) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is UpcomingDetailState.Loading -> {}
            is UpcomingDetailState.Error -> Text(s.message, color = colors.textSecondary, modifier = Modifier.padding(48.dp))
            is UpcomingDetailState.Loaded -> UpcomingDetailContent(s.item)
        }
    }
}

@Composable
private fun UpcomingDetailContent(item: UpcomingItem) {
    val colors = RaviloTheme.colors
    val scrim = remember(colors.background) {
        Brush.verticalGradient(0f to Color.Transparent, 0.55f to colors.background.copy(alpha = 0.85f), 1f to colors.background)
    }
    val scrollState = rememberScrollState()
    val gradient = remember(item.title) { gradientFor(item.title) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            if (item.posterUrl != null) RemoteImage(item.posterUrl, item.title, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(gradient))
            Box(Modifier.matchParentSize().background(scrim))
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = raviloHPad)
                .padding(top = 200.dp, bottom = 48.dp),
        ) {
            val kicker = kickerFor(item)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(kicker.second, androidx.compose.foundation.shape.CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(kicker.first, color = kicker.second, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(relativeChip(item), color = colors.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(item.title, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            val kindLabel = if (item.kind == MediaKind.MOVIE) str("up.movie") else str("up.episode")
            val epLine = if (item.kind == MediaKind.SERIES && item.season != null && item.episode != null) {
                "S${item.season}·E${item.episode}" + (item.episodeTitle?.let { " · $it" } ?: "")
            } else null
            Text(
                listOfNotNull(item.year?.toString(), kindLabel, item.genre, epLine).joinToString("  ·  "),
                color = colors.textSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(14.dp))
            item.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(0.66f))
                Spacer(Modifier.height(18.dp))
            }

            ScheduleGrid(item)
            Spacer(Modifier.height(18.dp))
            Text(
                if (item.status == UpcomingStatus.MISSING) str("up.foot_missing") else str("up.foot_upcoming"),
                color = colors.textSecondary.copy(alpha = 0.8f), fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ScheduleGrid(item: UpcomingItem) {
    val colors = RaviloTheme.colors
    val isSeries = item.kind == MediaKind.SERIES
    val rows = listOfNotNull(
        (if (isSeries) str("up.air_date") else str("up.release_date")) to item.date,
        item.time?.let { str("up.air_time") to it },
        item.releaseType?.let { str("up.release_type") to releaseTypeLabel(it) },
        item.network?.let { str("up.network") to it },
    )
    Column(
        Modifier
            .fillMaxWidth(0.5f)
            .background(colors.surface.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(str("up.schedule"), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = colors.textSecondary, fontSize = 13.sp)
                Text(value, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun kickerFor(item: UpcomingItem): Pair<String, Color> = when {
    item.status == UpcomingStatus.MISSING -> str("up.missing_kicker") to Color(0xFFE0393A)
    item.kind == MediaKind.SERIES && item.season == 1 && item.episode == 1 -> str("up.premiere") to Color(0xFF22A559)
    item.kind == MediaKind.SERIES -> str("up.new_episode") to Color(0xFF3B82F6)
    else -> str("up.premiere") to Color(0xFF22A559)
}

@Composable
private fun relativeChip(item: UpcomingItem): String {
    val date = runCatching { LocalDate.parse(item.date) }.getOrNull() ?: return item.date
    val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
    val diff = today.daysUntil(date)
    val isSeries = item.kind == MediaKind.SERIES
    return when {
        diff == 0 -> if (isSeries) str("up.airs_today") else str("up.releases_today")
        diff == 1 -> if (isSeries) str("up.airs_tomorrow") else str("up.releases_tomorrow")
        diff > 1 -> str(if (isSeries) "up.airs_in" else "up.releases_in", mapOf("n" to diff.toString()))
        diff == -1 -> if (isSeries) str("up.aired_yesterday") else str("up.released_yesterday")
        else -> str(if (isSeries) "up.aired_ago" else "up.released_ago", mapOf("n" to (-diff).toString()))
    }
}

@Composable
private fun releaseTypeLabel(releaseType: String): String = when (releaseType) {
    "digital" -> str("up.release_digital")
    "physical" -> str("up.release_physical")
    "cinema" -> str("up.release_cinema")
    else -> releaseType
}
