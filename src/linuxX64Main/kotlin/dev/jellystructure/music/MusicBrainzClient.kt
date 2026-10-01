package dev.jellystructure.music

import dev.jellystructure.OutboundHttp
import dev.jellystructure.ServerVersion
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─── MusicBrainz web service v2 (JSON), only the fields jellystructure reads ─────────────────────────────────
// Shapes measured against musicbrainz.org on 2026-09-27/28 (research §3.2, the 276 dry run).

@Serializable data class MbArtistRef(val id: String = "", val name: String = "", @SerialName("sort-name") val sortName: String? = null)
@Serializable data class MbArtistCredit(val name: String = "", val joinphrase: String = "", val artist: MbArtistRef? = null)
@Serializable data class MbGenre(val name: String = "", val count: Int = 0)
@Serializable data class MbUrl(val resource: String = "")
@Serializable data class MbRelation(val type: String = "", val url: MbUrl? = null)
@Serializable data class MbLifeSpan(val begin: String? = null, val end: String? = null, val ended: Boolean? = null)
@Serializable data class MbAlias(val name: String = "")
@Serializable data class MbLabel(val name: String = "")
@Serializable data class MbLabelInfo(val label: MbLabel? = null)
@Serializable data class MbCaa(val front: Boolean = false, val count: Int = 0)

@Serializable
data class MbRecording(
    val id: String = "",
    val title: String = "",
    val length: Long? = null,
    val score: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
data class MbTrack(
    val id: String = "",
    val position: Int = 0,
    val title: String = "",
    val length: Long? = null,
    val recording: MbRecording? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@Serializable
data class MbMedium(
    val position: Int = 1,
    val format: String? = null,
    @SerialName("track-count") val trackCount: Int = 0,
    val tracks: List<MbTrack> = emptyList(),
)

@Serializable
data class MbReleaseGroup(
    val id: String = "",
    val title: String = "",
    val score: Int? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val genres: List<MbGenre> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
data class MbRelease(
    val id: String = "",
    val title: String = "",
    val country: String? = null,
    val date: String? = null,
    val status: String? = null,
    val disambiguation: String? = null,
    @SerialName("label-info") val labelInfo: List<MbLabelInfo> = emptyList(),
    val media: List<MbMedium> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
    @SerialName("cover-art-archive") val coverArtArchive: MbCaa? = null,
)

@Serializable
data class MbArtist(
    val id: String = "",
    val name: String = "",
    @SerialName("sort-name") val sortName: String? = null,
    val type: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    @SerialName("life-span") val lifeSpan: MbLifeSpan? = null,
    val aliases: List<MbAlias> = emptyList(),
    val genres: List<MbGenre> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
)

@Serializable private data class MbReleaseGroupSearch(@SerialName("release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList())
@Serializable private data class MbReleaseBrowse(val releases: List<MbRelease> = emptyList())
@Serializable private data class MbRecordingSearch(val recordings: List<MbRecording> = emptyList())

/**
 * Phase 276 (FR-276-2) — the MusicBrainz client. **One request a second** (MusicBrainz's published rule, 503 beyond
 * it), a `User-Agent` naming jellystructure, its version and the configured contact, a 503/429 backed off and
 * retried, and every answer cached for the process's life in a small LRU (a run asks for the same release-group
 * and artist many times). Every call returns null/empty on failure — a failed lookup never blanks a good match.
 */
open class MusicBrainzClient(
    private val configStore: ConfigStore,
    val limiter: FixedRateLimiter = FixedRateLimiter(1.0),
    private val base: String = "https://musicbrainz.org/ws/2",
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, String>()
    private val http get() = OutboundHttp.client

    val userAgent: String get() = "jellystructure/${ServerVersion.current.ifBlank { "dev" }} ( ${configStore.current.musicbrainz.effectiveContact} )"

    /** The last call's outcome, for the providers card's status line. */
    @kotlin.concurrent.Volatile var lastOutcome: String? = null
        private set

    private suspend fun getText(pathAndQuery: String): String? {
        val url = "$base$pathAndQuery${if ('?' in pathAndQuery) "&" else "?"}fmt=json"
        cacheLock.withLock { cache[url]?.let { hit -> cache.remove(url); cache[url] = hit; return hit } }
        var backoff = 2_000L
        repeat(4) {
            limiter.acquire()
            val result = runCatching {
                OutboundHttp.withPermit {
                    val resp = http.get(url) { header(HttpHeaders.UserAgent, userAgent); header(HttpHeaders.Accept, "application/json") }
                    resp.status to resp.bodyAsText()
                }
            }
            result.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                lastOutcome = "failed — ${e.message?.take(80)}"
                Logger.warn("MusicBrainz $pathAndQuery failed: ${e.message}", "music")
                delay(backoff); backoff *= 2
                return@repeat
            }
            val (status, body) = result.getOrThrow()
            when {
                status == HttpStatusCode.OK -> {
                    lastOutcome = "ok"
                    cacheLock.withLock { cache[url] = body; while (cache.size > 600) cache.remove(cache.keys.first()) }
                    return body
                }
                status == HttpStatusCode.ServiceUnavailable || status == HttpStatusCode.TooManyRequests -> {
                    limiter.onRefused()
                    lastOutcome = "slowed down by MusicBrainz (${status.value})"
                    delay(backoff); backoff *= 2
                }
                status == HttpStatusCode.NotFound || status == HttpStatusCode.BadRequest -> { lastOutcome = "ok"; return null }
                else -> { lastOutcome = "answered ${status.value}"; return null }
            }
        }
        return null
    }

    private suspend fun <T> get(pathAndQuery: String, serializer: KSerializer<T>): T? =
        getText(pathAndQuery)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    // Lists: null = MusicBrainz did not answer (keep whatever was known), empty = it answered and found nothing.

    /** Rung 2 — release-groups by album title and album artist (Lucene, both quoted). */
    open suspend fun searchReleaseGroups(artist: String, album: String, limit: Int = 5): List<MbReleaseGroup>? {
        val q = buildString {
            append("releasegroup:\"").append(lucene(album)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(lucene(artist)).append('"')
        }
        return get("/release-group?limit=$limit&query=${q.encodeURLParameter()}", MbReleaseGroupSearch.serializer())?.releaseGroups
    }

    /** The admin's own query in Find match… — free text, MusicBrainz's own relevance. */
    open suspend fun searchReleaseGroupsFree(query: String, limit: Int = 8): List<MbReleaseGroup>? =
        get("/release-group?limit=$limit&query=${query.encodeURLParameter()}", MbReleaseGroupSearch.serializer())?.releaseGroups

    open suspend fun releaseGroup(mbid: String): MbReleaseGroup? =
        get("/release-group/$mbid?inc=genres+artist-credits+url-rels", MbReleaseGroup.serializer())

    /** Up to 25 pressings of a release-group, each with its track list — one request. */
    open suspend fun releasesOf(releaseGroupMbid: String): List<MbRelease>? =
        get("/release?release-group=$releaseGroupMbid&inc=recordings+media+labels+artist-credits&limit=25", MbReleaseBrowse.serializer())?.releases

    open suspend fun release(mbid: String): MbRelease? =
        get("/release/$mbid?inc=recordings+media+labels+artist-credits+release-groups", MbRelease.serializer())

    /** FR-276-5 — an artist's recordings of a title, for *Match this track…*. */
    open suspend fun searchRecordings(artist: String, title: String, limit: Int = 10): List<MbRecording>? {
        val q = "recording:\"${lucene(title)}\"" + if (artist.isNotBlank()) " AND artist:\"${lucene(artist)}\"" else ""
        return get("/recording?limit=$limit&query=${q.encodeURLParameter()}", MbRecordingSearch.serializer())?.recordings
    }

    /** AcoustID answers recordings; this says which release-groups they are on. */
    open suspend fun recording(mbid: String): MbRecording? =
        get("/recording/$mbid?inc=releases+release-groups+artist-credits", MbRecording.serializer())

    open suspend fun artist(mbid: String): MbArtist? =
        get("/artist/$mbid?inc=genres+url-rels+aliases", MbArtist.serializer())

    companion object {
        /** Lucene-escape a phrase for MusicBrainz's search syntax. */
        fun lucene(s: String): String = buildString {
            for (c in s) { if (c in "+-&|!(){}[]^\"~*?:\\/") append('\\'); append(c) }
        }

        private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** A pasted `musicbrainz.org/release-group/…` or `/release/…` URL (open question 2: a release resolves to its group). */
        fun parseUrl(s: String): Pair<String, String>? {
            val id = MBID.find(s)?.value ?: return null
            return when {
                "/release-group/" in s -> "release-group" to id
                "/release/" in s -> "release" to id
                else -> null
            }
        }
    }
}
