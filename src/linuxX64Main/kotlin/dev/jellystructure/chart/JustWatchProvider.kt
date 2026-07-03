package dev.jellystructure.chart

import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.shared.tv.ChartListSpec
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phase 59 — JustWatch unofficial GraphQL provider.
 * Covers Viaplay, Paramount+, SkyShowtime and any other service JustWatch tracks (4,500+).
 * Data is user-activity-based (60M monthly users), not platform-official viewership.
 *
 * The [urlSlug] is the last path segment from justwatch.com/xx/provider/<slug>:
 *   "viaplay"                → Viaplay
 *   "paramount-plus-premium" → Paramount+
 *   "sky-showtime"           → SkyShowtime
 *
 * On first fetch per country, GetProviders is called once to resolve the 3-letter shortName
 * needed for the popularTitles filter. Result is cached in memory for the process lifetime.
 */
class JustWatchProvider(
    override val id: String,           // "viaplay", "paramount", "skyshowtime"
    override val displayName: String,  // "Viaplay", "Paramount+", "SkyShowtime"
    private val urlSlug: String,       // e.g. "viaplay", "paramount-plus-premium", "sky-showtime"
) : ChartProvider {
    override val attribution = "JustWatch"

    private val gate = Semaphore(8)

    private suspend fun httpPost(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.post(url, block) }

    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    // shortName cache: country → 3-letter code (null = not available in that country)
    private val shortNameCache = mutableMapOf<String, String?>()

    override fun availableLists(region: String): List<ChartListSpec> = listOf(
        ChartListSpec("$id-mov-$region", id, "Top 10 Movies on $displayName", "country", "film", "rank", region),
        ChartListSpec("$id-tv-$region", id, "Top 10 Series on $displayName", "country", "series", "rank", region),
    )

    override suspend fun fetch(spec: ChartListSpec): ChartFetch {
        val region = spec.region ?: return ChartFetch("", emptyList())
        val country = region.uppercase()
        val week = weekKey()

        val shortName = resolveShortName(country) ?: run {
            Logger.info("chart: JustWatch slug '$urlSlug' not available in $country — skipping", "chart")
            return ChartFetch(week, emptyList())
        }

        val isMovie = spec.category == "film"
        val objectType = if (isMovie) "MOVIE" else "SHOW"

        val payload = buildJsonObject {
            put("query", POPULAR_TITLES_QUERY)
            putJsonObject("variables") {
                put("country", country)
                put("language", "en")
                put("first", 10)
                putJsonObject("popularTitlesFilter") {
                    putJsonArray("packages") { add(JsonPrimitive(shortName)) }
                    putJsonArray("objectTypes") { add(JsonPrimitive(objectType)) }
                }
                putJsonObject("filter") {
                    putJsonArray("monetizationTypes") { add(JsonPrimitive("FLATRATE")) }
                }
            }
        }

        val resp = gate.withPermit {
            runCatching {
                httpPost(ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }.body<JwPopularResponse>()
            }.getOrNull()
        } ?: return ChartFetch(week, emptyList())

        val edges = resp.data?.popularTitles?.edges ?: return ChartFetch(week, emptyList())
        val entries = edges.mapIndexedNotNull { idx, edge ->
            val title = edge.node?.content?.title?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            RawChartEntry(rank = idx + 1, title = title)
        }
        return ChartFetch(week, entries)
    }

    private suspend fun resolveShortName(country: String): String? {
        shortNameCache[country]?.let { return it }
        val code = gate.withPermit {
            runCatching {
                val payload = buildJsonObject {
                    put("query", PROVIDERS_QUERY)
                    putJsonObject("variables") {
                        put("country", country)
                        put("formatOfferIcon", "JPG")
                    }
                }
                val resp = httpPost(ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }.body<JwProvidersResponse>()
                resp.data?.packages?.find { it.slug == urlSlug }?.shortName
            }.getOrNull()
        }
        shortNameCache[country] = code
        return code
    }
}

// ---- GraphQL ----

private const val ENDPOINT = "https://apis.justwatch.com/graphql"

private const val PROVIDERS_QUERY = """
query GetProviders(${'$'}country: Country!, ${'$'}formatOfferIcon: ImageFormat) {
  packages(country: ${'$'}country, platform: WEB, includeAddons: true) {
    id shortName slug clearName monetizationTypes __typename
  }
  __typename
}
"""

private const val POPULAR_TITLES_QUERY = """
query GetPopularTitles(
  ${'$'}popularTitlesFilter: TitleFilter,
  ${'$'}country: Country!,
  ${'$'}language: Language!,
  ${'$'}first: Int!,
  ${'$'}filter: OfferFilter!
) {
  popularTitles(
    country: ${'$'}country,
    filter: ${'$'}popularTitlesFilter,
    first: ${'$'}first,
    sortBy: POPULAR,
    sortRandomSeed: 0
  ) {
    edges {
      node {
        objectType
        content(country: ${'$'}country, language: ${'$'}language) {
          title
          originalReleaseYear
        }
      }
    }
  }
}
"""

// ---- Response types ----

@Serializable
private data class JwProvidersResponse(val data: JwProvidersData? = null)

@Serializable
private data class JwProvidersData(val packages: List<JwPackage> = emptyList())

@Serializable
private data class JwPackage(
    val id: Int = 0,
    val shortName: String = "",
    val slug: String = "",
    val clearName: String = "",
)

@Serializable
private data class JwPopularResponse(val data: JwPopularData? = null)

@Serializable
private data class JwPopularData(val popularTitles: JwConnection? = null)

@Serializable
private data class JwConnection(val edges: List<JwEdge> = emptyList())

@Serializable
private data class JwEdge(val node: JwNode? = null)

@Serializable
private data class JwNode(
    val objectType: String = "",
    val content: JwContent? = null,
)

@Serializable
private data class JwContent(
    val title: String = "",
    @SerialName("originalReleaseYear") val originalReleaseYear: Int? = null,
)
