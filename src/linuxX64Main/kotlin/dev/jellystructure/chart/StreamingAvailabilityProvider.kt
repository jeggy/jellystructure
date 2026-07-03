package dev.jellystructure.chart

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.OutboundHttp
import dev.jellystructure.shared.tv.ChartListSpec
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Phase 59 — streaming-availability.p.rapidapi.com (movieofthenight.com) provider.
 * Covers Max, Disney+, Amazon Prime, Apple TV+ official top lists.
 * Free tier: 500 req/month; week-gated so we poll ≈ 40 req/month total across all services.
 *
 * Register multiple instances, one per [service] slug:
 *   max, disney, prime, apple, crunchyroll
 */
class StreamingAvailabilityProvider(
    private val configStore: ConfigStore,
    override val id: String,           // "max", "disney", "prime", "apple"
    override val displayName: String,  // "Max", "Disney+", "Amazon Prime", "Apple TV+"
) : ChartProvider {
    override val attribution = "movieofthenight.com"

    private val gate = Semaphore(8)

    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }

    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    override fun availableLists(region: String): List<ChartListSpec> = listOf(
        ChartListSpec("$id-mov-$region", id, "Top 10 Movies on $displayName", "country", "film", "rank", region),
        ChartListSpec("$id-tv-$region", id, "Top 10 Series on $displayName", "country", "series", "rank", region),
    )

    override suspend fun fetch(spec: ChartListSpec): ChartFetch {
        val key = configStore.current.apiKeys.streamingAvailabilityKey
        if (key.isBlank()) return ChartFetch("", emptyList())

        val region = spec.region ?: return ChartFetch("", emptyList())
        val showType = if (spec.category == "series") "series" else "movie"
        val week = weekKey()

        val shows = gate.withPermit {
            runCatching {
                httpGet("https://streaming-availability.p.rapidapi.com/shows/top") {
                    header("X-RapidAPI-Key", key)
                    header("X-RapidAPI-Host", "streaming-availability.p.rapidapi.com")
                    parameter("country", region.lowercase())
                    parameter("service", id)
                    parameter("show_type", showType)
                }.body<List<SaShow>>()
            }.getOrNull() ?: emptyList()
        }

        val entries = shows.mapIndexed { idx, show ->
            RawChartEntry(rank = idx + 1, title = show.title, weeksOnChart = 0, isNew = false)
        }
        return ChartFetch(week, entries)
    }
}

@Serializable
private data class SaShow(
    val title: String = "",
    @SerialName("tmdbId") val tmdbId: Int? = null,
    @SerialName("showType") val showType: String = "movie",
    @SerialName("releaseYear") val releaseYear: Int? = null,
)

@OptIn(ExperimentalForeignApi::class)
internal fun weekKey(): String = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    "W${ts.tv_sec / (7L * 24 * 3600)}"
}
