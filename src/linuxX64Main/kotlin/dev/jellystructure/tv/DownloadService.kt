package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.JellyfinItemDetail
import dev.jellystructure.auth.JellyfinMediaSourceInfo
import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.auth.withJellyfinToken
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.MediaKind
import dev.jellystructure.ops.LockedMap
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.DownloadArtwork
import dev.jellystructure.shared.tv.DownloadOption
import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadSubtitle
import dev.jellystructure.shared.tv.DownloadTicket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.time.Clock

/** What a download route answers: a value, or the reason it has none. The routes map each reason to one status. */
sealed interface DownloadOutcome<out T> {
    data class Ok<T>(val value: T) : DownloadOutcome<T>
    /** The operator has switched this viewer's downloading off (FR-2): `403`. */
    data object NotAllowed : DownloadOutcome<Nothing>
    /** The title is not there, not visible to this device, or not a film or an episode: `404`. */
    data object NotFound : DownloadOutcome<Nothing>
    /** The request names something this server does not offer (an unknown version, a quality not built yet, an undecodable file): `400`. */
    data class Refused(val reason: String) : DownloadOutcome<Nothing>
    /** Jellyfin could not be asked right now: `503`. */
    data class Unavailable(val reason: String) : DownloadOutcome<Nothing>
    /** DEB-4062 (FR-6) — a smaller copy cannot begin now (a play is live, another phone holds the one download stream): `503` *waiting*; the phone asks again. */
    data class Waiting(val reason: String) : DownloadOutcome<Nothing>
}

/** The one status each reason answers with (the body, on success, is the answer itself). */
internal fun DownloadOutcome<*>.httpStatus(): io.ktor.http.HttpStatusCode = when (this) {
    is DownloadOutcome.Ok -> io.ktor.http.HttpStatusCode.OK
    DownloadOutcome.NotAllowed -> io.ktor.http.HttpStatusCode.Forbidden
    DownloadOutcome.NotFound -> io.ktor.http.HttpStatusCode.NotFound
    is DownloadOutcome.Refused -> io.ktor.http.HttpStatusCode.BadRequest
    is DownloadOutcome.Unavailable -> io.ktor.http.HttpStatusCode.ServiceUnavailable
    is DownloadOutcome.Waiting -> io.ktor.http.HttpStatusCode.ServiceUnavailable
}

private const val DOWNLOAD_TICKET_TTL_MS = 4 * 60 * 60 * 1000L   // a ticket lives 4 h, as a play's does
private const val POLICY_TTL_MS = 60_000L

/**
 * DEB-4061 (DEB-3995 FR-2, FR-4) — what the phone may download: the original file, and (DEB-4062) smaller copies of it.
 *
 * A `start` is never a play. It reports nothing to Jellyfin (no `/Sessions/Playing`), opens no R368 session, no tracker and no QoE row;
 * it only builds the URL a phone's downloader fetches and the metadata it saves beside the file. A smaller copy's URL is a download
 * stream of our encoder ([DownloadStreams]): one at a time, and it yields to every play.
 */
class DownloadService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val detailService: DetailService,
    private val playbackService: PlaybackService,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Seen(val allowed: Boolean, val at: Long)
    private val policy = LockedMap<String, Seen>()

    /** The same encoding the page routes answer with, so a saved `item` is byte-for-byte what the page route sent. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * FR-2 — the operator's *allow media downloading* switch for this viewer, read live from Jellyfin and kept a minute. A
     * failed read falls back to the last answer, and to `null` (unknown) before there is one: never a guess in either direction.
     */
    suspend fun allowed(device: DeviceData): Boolean? {
        val key = device.jellyfinUserId
        val seen = policy[key]
        if (seen != null && clock() - seen.at < POLICY_TTL_MS) return seen.allowed
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(base, device)
        val fresh = token?.let { jellyfinClient.getUserOrNull(base, it, device.jellyfinUserId) }?.policy?.enableContentDownloading
        if (fresh == null) return seen?.allowed
        policy[key] = Seen(fresh, clock())
        return fresh
    }

    /** FR-4 — `GET /api/tv/download/options`. [capabilities] is what the phone declared with the request, if it did. */
    suspend fun options(device: DeviceData, itemId: String, mediaSourceId: String?, capabilities: ClientCapabilities?): DownloadOutcome<DownloadOptions> {
        val title = resolve(device, itemId, mediaSourceId, capabilities) { return it }
        val original = title.original()
        val copies = playbackService.downloadCopies(device, title.detail, title.source?.id, itemId, title.capabilities ?: ClientCapabilities(), title.source?.size?.takeIf { it > 0 })
        return DownloadOutcome.Ok(DownloadOptions(listOfNotNull(original) + copies))
    }

    /** FR-4 — `POST /api/tv/download/start`: a fresh ticket for the original, or a download stream for a smaller copy. Asking again mints another. */
    suspend fun start(device: DeviceData, req: DownloadStartRequest): DownloadOutcome<DownloadTicket> {
        val copy = DOWNLOAD_COPIES.firstOrNull { it.first.equals(req.quality, ignoreCase = true) }
        if (copy == null && !req.quality.equals(ORIGINAL, ignoreCase = true)) return DownloadOutcome.Refused("quality ${req.quality} is not offered")
        val title = resolve(device, req.itemId, req.mediaSourceId, req.capabilities) { return it }
        if (copy != null) return startCopy(device, req, title, copy.first, copy.second)
        val original = title.original() ?: return DownloadOutcome.Refused("the original is not offered for this title")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val sourceId = title.source?.id ?: req.itemId
        // The direct-play URL a start builds (PlaybackService.streamUrlFor), plus a per-ticket value: each call is its own URL, so a
        // resumed download never reuses one a downloader or a cache already holds. Jellyfin ignores a query key it does not know.
        val url = withJellyfinToken(
            "${title.base}/Videos/${req.itemId}/stream?Static=true&MediaSourceId=$sourceId&DeviceId=${identity.deviceId}&ticket=${Random.nextLong().toULong().toString(16)}",
            title.token,
        )
        return DownloadOutcome.Ok(ticket(title, req, kind = "file", quality = ORIGINAL, url = url, bytes = original.bytes, expiresAt = clock() + DOWNLOAD_TICKET_TTL_MS))
    }

    /**
     * DEB-4062 (FR-4, FR-6) — a smaller copy: the plan is made as a play's is (one rung, one audio) and registered as the one download
     * stream; the ticket's URL is its master. Waiting (503) when a play is live or another phone holds the stream, or when no card has a slot.
     */
    private suspend fun startCopy(device: DeviceData, req: DownloadStartRequest, title: Title, quality: String, box: Int): DownloadOutcome<DownloadTicket> {
        val caps = title.capabilities ?: ClientCapabilities()
        val offered = playbackService.downloadCopies(device, title.detail, title.source?.id, req.itemId, caps, title.source?.size?.takeIf { it > 0 })
            .firstOrNull { it.quality == quality } ?: return DownloadOutcome.Refused("quality $quality is not offered for this title")
        // Before any planning: a play is live, so nothing is started (and the phone shows *Waiting for the server*).
        if (playbackService.downloads.playLive()) return DownloadOutcome.Waiting("a play is using the server")
        val (plan, why) = playbackService.downloadPlanFor(device, title.detail, title.source?.id, req.itemId, caps, box, req.audioLanguage, req.audioVariant)
        if (plan == null) return if (why.startsWith("no encoder slot free")) DownloadOutcome.Waiting(why) else DownloadOutcome.Unavailable(why)
        val expiresAt = clock() + DOWNLOAD_TICKET_TTL_MS
        val id = when (val opened = playbackService.downloads.open(plan, device.deviceId, req.itemId, expiresAt, (req.fromSegment ?: 0).coerceAtLeast(0))) {
            is DownloadStreams.Opened.Ok -> opened.id
            is DownloadStreams.Opened.Waiting -> return DownloadOutcome.Waiting(opened.reason)
        }
        return DownloadOutcome.Ok(ticket(title, req, kind = "hls", quality = quality, url = "/api/tv/stream/$id/master.m3u8", bytes = offered.bytes, expiresAt = expiresAt))
    }

    private suspend fun ticket(title: Title, req: DownloadStartRequest, kind: String, quality: String, url: String, bytes: Long, expiresAt: Long): DownloadTicket {
        val subtitles = playbackService.textSubtitleTracks(title.detail, req.itemId, title.base, title.token).map {
            DownloadSubtitle(index = it.index, language = it.language, label = it.label, forced = it.forced, isDefault = it.isDefault, url = it.url!!)
        }
        return DownloadTicket(
            kind = kind, quality = quality, url = url, bytes = bytes, expiresAt = expiresAt,
            mediaSourceId = title.source?.id, subtitles = subtitles, artwork = title.artwork,
            itemKind = title.kind, seriesId = title.seriesJellyfinId, item = title.page,
        )
    }

    private class Title(
        val base: String,
        val token: String,
        val kind: String,
        val seriesJellyfinId: String?,
        val detail: JellyfinItemDetail,
        val source: JellyfinMediaSourceInfo?,
        val capabilities: ClientCapabilities?,
        val artwork: DownloadArtwork,
        val page: JsonElement,
    ) {
        private val video: JellyfinMediaStream? =
            (source?.mediaStreams?.takeIf { it.isNotEmpty() } ?: detail.mediaStreams).firstOrNull { it.type.equals("Video", ignoreCase = true) }

        /** The file itself at its exact size, unless the phone cannot decode its picture or Jellyfin does not say how big it is. */
        fun original(): DownloadOption? {
            val bytes = source?.size?.takeIf { it > 0 } ?: return null
            if (!decodes(capabilities, video?.codec)) return null
            return DownloadOption(quality = ORIGINAL, height = video?.height, bytes = bytes, exact = true)
        }
    }

    /**
     * Everything both routes need, or the reason there is none (handed to [bail], which returns from the caller). The gate order is
     * the answer order: the switch first, then the title, then the version, then Jellyfin.
     */
    private suspend inline fun resolve(
        device: DeviceData, itemId: String, mediaSourceId: String?, declared: ClientCapabilities?,
        bail: (DownloadOutcome<Nothing>) -> Nothing,
    ): Title {
        when (allowed(device)) {
            true -> {}
            false -> bail(DownloadOutcome.NotAllowed)
            null -> bail(DownloadOutcome.Unavailable("the download setting could not be read"))
        }
        // A film is a movie item; an episode is found inside its series, which is the page that lists it. A series itself is not a download.
        val movie = mediaStore.resolveByJellyfinId(itemId)
        val series = if (movie != null) null else mediaStore.allItems().firstOrNull { s -> s.episodes.any { it.jellyfinId == itemId } }
        val kind = when {
            movie != null && movie.kind == MediaKind.MOVIE && movie.episodes.isEmpty() && movie.visibleTo(device) -> "movie"
            series != null && series.visibleTo(device) -> "episode"
            else -> bail(DownloadOutcome.NotFound)
        }
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(base, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val detail = jellyfinClient.getItemDetail(base, token, device.jellyfinUserId, itemId)
            ?: bail(DownloadOutcome.Unavailable("the title could not be read"))
        val caps = declared ?: playbackService.capabilitiesOf(device)
        val sources = detail.mediaSources
        val source = if (mediaSourceId != null) sources.firstOrNull { it.id == mediaSourceId } ?: bail(DownloadOutcome.Refused("no such version"))
            else chooseMediaSource(sources, null, caps != null && caps.supportsDolbyVision && caps.supportsDolbyVisionEl)?.let { id -> sources.firstOrNull { it.id == id } } ?: sources.firstOrNull()
        val seriesId = series?.jellyfinId
        val page: JsonElement
        val artwork: DownloadArtwork
        if (kind == "movie") {
            val d = detailService.getMovieDetail(device, itemId) ?: bail(DownloadOutcome.NotFound)
            page = json.encodeToJsonElement(dev.jellystructure.shared.tv.MovieDetail.serializer(), d)
            artwork = DownloadArtwork(posterUrl = d.card.posterUrl, backdropUrl = d.card.backdropUrl)
        } else {
            val d = detailService.getSeriesDetail(device, seriesId ?: bail(DownloadOutcome.NotFound)) ?: bail(DownloadOutcome.NotFound)
            page = json.encodeToJsonElement(dev.jellystructure.shared.tv.SeriesDetail.serializer(), d)
            // The pictures the page draws from: the series' poster and backdrop, and this episode's own still.
            artwork = DownloadArtwork(
                posterUrl = d.card.posterUrl, backdropUrl = d.card.backdropUrl,
                stillUrl = d.seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == itemId }?.stillUrl },
            )
        }
        return Title(base, token, kind, seriesId, detail, source, caps, artwork, page)
    }

    companion object {
        const val ORIGINAL = "original"

        private fun canonical(codec: String): String = when (val c = codec.trim().lowercase()) {
            "h265", "hev1", "hvc1" -> "hevc"
            "avc", "avc1", "h.264" -> "h264"
            else -> c
        }

        /**
         * FR-4 — whether a phone that declared [capabilities] decodes a picture coded [codec]. A phone that declared no decoders, or a
         * file whose codec Jellyfin did not name, is not refused: nothing is known to rule the original out.
         */
        internal fun decodes(capabilities: ClientCapabilities?, codec: String?): Boolean {
            val declared = capabilities?.videoCodecs?.takeIf { it.isNotEmpty() } ?: return true
            val file = codec?.takeIf { it.isNotBlank() } ?: return true
            return canonical(file) in declared.map { canonical(it) }
        }
    }
}
