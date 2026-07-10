package dev.jellystructure.auth

import dev.jellystructure.OutboundHttp
import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ClientCapabilities
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

private const val DEVICE_ID = "jellystructure-server-v01"
private const val AUTH_HEADER =
    """MediaBrowser Client="Jellystructure", Device="Server", DeviceId="$DEVICE_ID", Version="0.1.0""""

// R56 — DeviceProfile sent to Jellyfin PlaybackInfo. Declares broad direct-play support (so most
// items stream the raw container) and per-format subtitle delivery: text subs External, image subs
// Embed where the player can render them, PGS via Encode (server burn-in). Permissive on purpose —
// the Ravilo player + :ravilo-player FFmpeg decoder handle the codecs the library actually holds.
//
// Bug fix: this used to be a single hardcoded constant with no CodecProfiles entry at all, so it
// never told Jellyfin anything about HDR — an HDR10/HDR10+/HLG (PQ) source always matched the plain
// DirectPlayProfile and streamed byte-for-byte, whatever the actual device's display could handle.
// Verified live against a real HDR10+ file ("Overcast"): with no CodecProfiles, PlaybackInfo reports
// SupportsDirectPlay=true; adding a CodecProfile with a VideoRangeType condition correctly flips it to
// SupportsDirectPlay=false / TranscodeReasons=VideoRangeTypeNotSupported, and Jellyfin's transcode
// correctly tone-maps to SDR (h264-rangetype=SDR in the resulting TranscodingUrl). [capabilities]'s
// supportsHdr10/supportsHlg (default false — conservative) widen the allowed VideoRangeType list only
// when the client has verified real HDR display/decode support.
private fun deviceProfile(capabilities: ClientCapabilities): String {
    val allowedRanges = buildList {
        add("SDR")
        if (capabilities.supportsHdr10) { add("HDR10"); add("HDR10Plus") }
        if (capabilities.supportsHlg) add("HLG")
    }.joinToString("|")
    return """{"MaxStreamingBitrate":120000000,"DirectPlayProfiles":[{"Container":"mkv,mp4,webm,mov,avi,ts,m2ts,flv,3gp,mpegts","Type":"Video","VideoCodec":"h264,hevc,vp8,vp9,av1,mpeg4,mpeg2video,vc1","AudioCodec":"aac,ac3,eac3,mp3,flac,vorbis,opus,dts,truehd,pcm,mp2,alac"}],"CodecProfiles":[{"Type":"Video","Codec":"hevc,h264,vp9,av1","Conditions":[{"Condition":"EqualsAny","Property":"VideoRangeType","Value":"$allowedRanges","IsRequired":true}]}],"TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac,ac3,mp3","Protocol":"hls","Context":"Streaming"}],"SubtitleProfiles":[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"},{"Format":"subrip","Method":"External"},{"Format":"ass","Method":"External"},{"Format":"ssa","Method":"External"},{"Format":"vobsub","Method":"Embed"},{"Format":"dvdsub","Method":"Embed"},{"Format":"dvbsub","Method":"Embed"},{"Format":"pgssub","Method":"Encode"},{"Format":"pgs","Method":"Encode"}]}"""
}

class JellyfinClient {
    private suspend fun httpGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }
    private suspend fun httpPost(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        OutboundHttp.withPermit { http.post(url, block) }
    private suspend fun httpDelete(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        OutboundHttp.withPermit { http.delete(url, block) }

    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    suspend fun authenticateByName(
        baseUrl: String,
        username: String,
        password: String,
        // Phase 110: TV pairing mints the token under the TV's own DeviceId (see JellyfinDeviceIdentity)
        // so the resulting Jellyfin session is per-device; admin login (no identity) keeps the server one.
        identity: JellyfinDeviceIdentity? = null,
    ): JellyfinAuthResponse {
        val url = baseUrl.trimEnd('/') + "/Users/AuthenticateByName"
        val authHeader = if (identity != null) {
            """MediaBrowser Client="Ravilo", Device="${headerSafe(identity.deviceName)}", DeviceId="${headerSafe(identity.deviceId)}", Version="0.1.0""""
        } else AUTH_HEADER
        val response = httpPost(url) {
            header("Authorization", authHeader)
            contentType(ContentType.Application.Json)
            setBody("""{"Username":${username.jsonEscape()},"Pw":${password.jsonEscape()}}""")
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalArgumentException("Invalid Jellyfin credentials")
        }
        if (!response.status.value.toString().startsWith("2")) {
            throw IllegalStateException("Jellyfin returned ${response.status.value}")
        }
        return response.body()
    }

    suspend fun testConnection(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/System/Info/Public"
        val response = httpGet(url) { jellyfinAuth(token) }
        response.status.value in 200..299
    }.getOrDefault(false)

    /**
     * True if [token] is a Jellyfin access token that can still act as [userId]. Used to detect a
     * stale **paired** TV user token (Jellyfin 401s it) so the TV can fall back to the long-lived
     * server token. `/Users/{userId}` is an authenticated endpoint — public `/System/Info/Public`
     * would 200 even for an invalid token, so it can't be used here.
     */
    suspend fun isTokenValid(baseUrl: String, token: String, userId: String): Boolean = runCatching {
        if (token.isBlank()) return false
        httpGet(baseUrl.trimEnd('/') + "/Users/$userId") { jellyfinAuth(token) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun getUsers(baseUrl: String, token: String): List<JellyfinUser> = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/Users") { jellyfinAuth(token) }
            .bodyOrNull<List<JellyfinUser>>("getUsers").orEmpty()
    }.getOrDefault(emptyList())

    suspend fun getLibraries(baseUrl: String, token: String): List<JellyfinLibrary> = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/VirtualFolders"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<List<JellyfinLibrary>>("getLibraries").orEmpty()
    }.getOrDefault(emptyList())

    suspend fun getItems(baseUrl: String, token: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?IncludeItemTypes=Movie,Series&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItems")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItems failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItemsByParent(baseUrl: String, token: String, parentId: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?ParentId=$parentId&IncludeItemTypes=Movie,Series&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItemsByParent")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemsByParent failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItem(baseUrl: String, token: String, jellyfinId: String): JellyfinItem? = runCatching {
        // Fetch via the same list-endpoint shape getItems uses, filtered to one id. The
        // non-user-scoped single-item route `/Items/{id}` 400s with a server token across Jellyfin
        // versions (it expects `/Users/{userId}/Items/{id}`); the `Ids=` filter on the list endpoint
        // is accepted with the same token + Fields (incl. LockData/LockedFields for Phase 22).
        val url = baseUrl.trimEnd('/') +
            "/Items?Ids=$jellyfinId&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SeriesId"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItem")?.items?.firstOrNull()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItem failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    /** Phase 114 (FR A.3) — the webhook fallback poll: has Jellyfin picked up this exact path yet?
     *  Cheap compared to fetching every item — `Path=` is an exact-match filter Jellyfin's `/Items`
     *  endpoint supports alongside `Ids=`. */
    suspend fun getItemByPath(baseUrl: String, token: String, path: String): JellyfinItem? = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?Path=${path.encodeURLParameter()}&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SeriesId"
        httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("getItemByPath")?.items?.firstOrNull()
    }.getOrElse { Logger.warn("Jellyfin getItemByPath failed: ${it.message}"); null }

    /** Phase 145 — reliable path→item resolution. This Jellyfin **ignores** the `?Path=` filter
     *  ([getItemByPath] then returns an arbitrary item), so instead pull the most-recently-added
     *  Movie/Episode items and match the path **ourselves**. Bounded (last [limit] additions) and safe to
     *  poll after a `notifyLibraryMediaUpdated` nudge. Returns the exact-path match, or null if not there yet. */
    suspend fun findRecentItemByPath(baseUrl: String, token: String, path: String, limit: Int = 200): JellyfinItem? = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?Recursive=true&IncludeItemTypes=Movie,Episode&SortBy=DateCreated&SortOrder=Descending&Limit=$limit" +
            "&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SeriesId"
        httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("findRecentItemByPath")
            ?.items?.firstOrNull { it.path == path }
    }.getOrElse { Logger.warn("Jellyfin findRecentItemByPath failed: ${it.message}"); null }

    /** Phase 114 — batch form of [getItem] for the LibraryChanged listener (chunked by the caller to
     *  keep URLs reasonable; Jellyfin has no documented Ids= count limit but a few hundred is prudent). */
    suspend fun getItemsByIds(baseUrl: String, token: String, jellyfinIds: List<String>): List<JellyfinItem> {
        if (jellyfinIds.isEmpty()) return emptyList()
        val url = baseUrl.trimEnd('/') +
            "/Items?Ids=${jellyfinIds.joinToString(",")}&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SeriesId"
        return runCatching {
            httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("getItemsByIds")?.items ?: emptyList()
        }.getOrElse { Logger.warn("Jellyfin getItemsByIds failed: ${it.message}"); emptyList() }
    }

    /** Phase 114 (FR A.2) — asks Jellyfin to examine one path right now, instead of waiting for its own
     *  library monitor (unreliable on network mounts — the usual reason ingest "takes forever"). */
    suspend fun notifyLibraryMediaUpdated(baseUrl: String, token: String, path: String): Boolean = runCatching {
        val body = """{"Updates":[{"Path":${path.jsonEscape()},"UpdateType":"Created"}]}"""
        val r = httpPost(baseUrl.trimEnd('/') + "/Library/Media/Updated") {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        r.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin notifyLibraryMediaUpdated failed: ${it.message}"); false }

    suspend fun refreshItem(baseUrl: String, token: String, jellyfinId: String, full: Boolean = false): Boolean = runCatching {
        // FullRefresh forces Jellyfin to actually re-read all providers (including our NFO) regardless
        // of DateLastRefreshed. Default mode skips the re-read if the item was recently refreshed.
        // ReplaceAllMetadata=true replaces all Jellyfin-cached fields with what providers return so our
        // NFO values win over any stale TMDB values Jellyfin had cached.
        // Recursive=false (not added) — we only want to refresh this one item.
        // Important: Jellyfin's NFO Metadata Saver must be OFF for this library, otherwise Jellyfin
        // writes its own NFO back after reading ours (overwriting our metadata). The /health/full
        // endpoint checks and warns about this.
        val mode = if (full) "FullRefresh" else "ValidationOnly"
        val extra = if (full) "&ReplaceAllMetadata=true" else ""
        val url = baseUrl.trimEnd('/') +
            "/Items/$jellyfinId/Refresh?MetadataRefreshMode=$mode&ImageRefreshMode=$mode$extra"
        val response = httpPost(url) { jellyfinAuth(token) }
        Logger.info("Jellyfin item refresh $jellyfinId (${if (full) "full/nfo-sync" else "validation"}): ${response.status.value}")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun triggerLibraryRefresh(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/Refresh"
        val response = httpPost(url) { jellyfinAuth(token) }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun getResumeItems(
        baseUrl: String,
        userToken: String,
        userId: String,
        limit: Int = 20,
    ): List<JellyfinPlayItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Filters=IsResumable&Recursive=true" +
            "&IncludeItemTypes=Movie,Episode&Limit=$limit" +
            "&SortBy=DatePlayed&SortOrder=Descending" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getResumeItems")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getResumeItems failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    /**
     * Phase 143 — the target user's fully-watched items, newest-played first, for the Users & Devices
     * "Recently watched" history. Scope note: only `Filters=IsPlayed` (finished) items — an in-progress
     * ("stopped at N%") item would need a second, differently-filtered call; skipped to keep this to one
     * request. [adminToken] (not the target user's own token) — the same elevated token [getUsers] uses.
     */
    suspend fun getRecentlyPlayed(
        baseUrl: String,
        adminToken: String,
        userId: String,
        limit: Int = 20,
        startIndex: Int = 0,
    ): List<JellyfinPlayItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Filters=IsPlayed&Recursive=true" +
            "&IncludeItemTypes=Movie,Episode&Limit=$limit&StartIndex=$startIndex" +
            "&SortBy=DatePlayed&SortOrder=Descending" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        httpGet(url) { jellyfinAuth(adminToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getRecentlyPlayed")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getRecentlyPlayed failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun startPlaybackSession(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        mediaSourceId: String,
        identity: JellyfinDeviceIdentity? = null,
        playSessionId: String? = null,
    ) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Sessions/Playing") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            val psid = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
            setBody("""{"ItemId":"$jellyfinId","StartPositionTicks":$positionTicks,"MediaSourceId":"$mediaSourceId","CanSeek":true$psid}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin startPlaybackSession failed: ${it.exceptionOrNull()?.message}") }

    /**
     * R56 — negotiate delivery with Jellyfin. POSTs a [deviceProfile] to PlaybackInfo; Jellyfin replies
     * per MediaSource whether it can direct-play, else returns a TranscodingUrl (e.g. for a burned-in
     * image subtitle). [subtitleStreamIndex] asks Jellyfin to Encode-burn that sub into the video.
     * [mediaSourceId] defaults to [itemId] (correct for VOD, where the item's own id is its default
     * media source) — pass null for Live TV channels, whose tuner/provider mints its own MediaSource
     * ids+OpenTokens that never match the channel id (R177: sending it made Jellyfin return no sources).
     */
    suspend fun getPlaybackInfo(
        baseUrl: String,
        userToken: String,
        userId: String,
        itemId: String,
        capabilities: ClientCapabilities = ClientCapabilities(),
        subtitleStreamIndex: Int? = null,
        identity: JellyfinDeviceIdentity? = null,
        mediaSourceId: String? = itemId,
    ): JellyfinPlaybackInfoResponse? = runCatching {
        val subBody = subtitleStreamIndex?.let { ""","SubtitleStreamIndex":$it""" } ?: ""
        val mediaSourceBody = mediaSourceId?.let { ""","MediaSourceId":"$it"""" } ?: ""
        httpPost(baseUrl.trimEnd('/') + "/Items/$itemId/PlaybackInfo?UserId=$userId") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody("""{"DeviceProfile":${deviceProfile(capabilities)}$mediaSourceBody$subBody}""")
        }.bodyOrNull<JellyfinPlaybackInfoResponse>("getPlaybackInfo")
    }.getOrElse { Logger.warn("Jellyfin getPlaybackInfo failed: ${it.message}"); null }

    suspend fun reportPlaybackProgress(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        isPaused: Boolean,
        mediaSourceId: String,
        identity: JellyfinDeviceIdentity? = null,
        playSessionId: String? = null,
    ) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Sessions/Playing/Progress") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            val psid = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"IsPaused":$isPaused,"MediaSourceId":"$mediaSourceId","EventName":"timeupdate"$psid}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin reportPlaybackProgress failed: ${it.exceptionOrNull()?.message}") }

    suspend fun stopPlaybackSession(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        mediaSourceId: String,
        identity: JellyfinDeviceIdentity? = null,
        playSessionId: String? = null,
    ) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Sessions/Playing/Stopped") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            val psid = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"MediaSourceId":"$mediaSourceId"$psid}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin stopPlaybackSession failed: ${it.exceptionOrNull()?.message}") }

    /** Phase 110 (FR C.2) — registers this device as a remote-control target: makes the dashboard
     *  message button and cast/remote-control menu appear for its session, and Home Assistant's
     *  Jellyfin integration list it as a controllable media_player. Only takes effect while paired
     *  with an open session WebSocket (SupportsMediaControl + a live socket = an addressable session). */
    suspend fun postCapabilities(baseUrl: String, userToken: String, identity: JellyfinDeviceIdentity) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Sessions/Capabilities/Full") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody(
                """{"PlayableMediaTypes":["Video"],"SupportedCommands":["DisplayMessage","Play","Playstate"],"SupportsMediaControl":true}"""
            )
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin postCapabilities failed: ${it.exceptionOrNull()?.message}") }

    suspend fun markPlayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Users/$userId/PlayedItems/$jellyfinId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markPlayed failed: ${it.exceptionOrNull()?.message}") }

    suspend fun markUnplayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpDelete(baseUrl.trimEnd('/') + "/Users/$userId/PlayedItems/$jellyfinId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markUnplayed failed: ${it.exceptionOrNull()?.message}") }

    suspend fun getItemDetail(
        baseUrl: String,
        userToken: String,
        userId: String,
        jellyfinId: String,
    ): JellyfinItemDetail? = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items/$jellyfinId?Fields=UserData,RunTimeTicks,MediaStreams"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinItemDetail>("getItemDetail")
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemDetail failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    /**
     * R82: Fetch per-episode static metadata (id, runtime, season name) without a user context —
     * uses the admin token so this can be called at scan time without a paired user session.
     */
    suspend fun getSeriesEpisodesMeta(
        baseUrl: String,
        token: String,
        seriesId: String,
    ): List<JellyfinEpisodeItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Shows/$seriesId/Episodes?Fields=RunTimeTicks,SeasonName,DateCreated"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinEpisodesResponse>("getSeriesEpisodesMeta")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getSeriesEpisodesMeta failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    /**
     * R83: Fetch per-user UserData for a batch of Jellyfin IDs in a single round-trip.
     * Caller must chunk large id lists (≤100) before calling; chunking is the caller's responsibility
     * so the Semaphore lives at the call site alongside other fan-out controls.
     */
    suspend fun getUserDataBulk(
        baseUrl: String,
        userToken: String,
        userId: String,
        jellyfinIds: List<String>,
    ): List<JellyfinUserDataItem> = runCatching {
        if (jellyfinIds.isEmpty()) return@runCatching emptyList()
        val ids = jellyfinIds.joinToString(",")
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Ids=$ids&Fields=UserData,RecursiveItemCount&Limit=${jellyfinIds.size}"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinUserDataItemsResponse>("getUserDataBulk")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getUserDataBulk failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getFavoriteItemIds(
        baseUrl: String,
        userToken: String,
        userId: String,
    ): Set<String> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Filters=IsFavorite&Recursive=true" +
            "&IncludeItemTypes=Movie,Series&Fields=Id&Limit=500"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinItemsResponse>("getFavoriteItemIds")?.items?.map { it.id }?.toSet().orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getFavoriteItemIds failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptySet())
    }

    // ── Phase 147 — Live TV ────────────────────────────────────────────────────────

    /** Dev-review addendum A: gate "Live TV available" on channel count > 0, NOT on this — an
     *  M3U/HLS-provider setup reports `Tuners:[]` yet serves channels fine. Kept for the raw
     *  IsEnabled flag only (Jellyfin's own Live TV kill switch). */
    suspend fun getLiveTvInfo(baseUrl: String, token: String): JellyfinLiveTvInfo? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/LiveTv/Info") { jellyfinAuth(token) }
            .bodyOrNull<JellyfinLiveTvInfo>("getLiveTvInfo")
    }.getOrElse { Logger.warn("Jellyfin getLiveTvInfo failed: ${it.message}"); null }

    suspend fun getLiveTvChannels(baseUrl: String, token: String): List<JellyfinLiveTvChannel> = runCatching {
        val url = baseUrl.trimEnd('/') + "/LiveTv/Channels?EnableImages=true"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinLiveTvChannelsResponse>("getLiveTvChannels")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getLiveTvChannels failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    /** Full-schedule guide fetch for the R177 EPG grid (addendum C — "On now"/channel bar don't need
     *  this; only the full guide grid does). [minStartUtc]/[maxStartUtc] are Jellyfin's expected
     *  ISO-8601 UTC bounds (e.g. `2026-07-10T00:00:00.000Z`). */
    suspend fun getLiveTvPrograms(
        baseUrl: String,
        token: String,
        channelIds: List<String>,
        minStartUtc: String,
        maxStartUtc: String,
    ): List<JellyfinLiveTvProgram> = runCatching {
        if (channelIds.isEmpty()) return@runCatching emptyList()
        val url = baseUrl.trimEnd('/') +
            "/LiveTv/Programs?ChannelIds=${channelIds.joinToString(",")}" +
            "&MinStartDate=${minStartUtc.encodeURLParameter()}&MaxStartDate=${maxStartUtc.encodeURLParameter()}"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinLiveTvProgramsResponse>("getLiveTvPrograms")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getLiveTvPrograms failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    /**
     * Dev-review addendum D — the live tuning handshake, a SIBLING to [getPlaybackInfo]/startPlayback,
     * not a branch of it: negotiate via the same `/Items/{id}/PlaybackInfo` (Live TV channels are
     * Items too) to get an `OpenToken`, then activate the tuner/provider stream via
     * `POST /LiveStreams/Open` — a MediaInfoController route, NOT under `/LiveTv/`. The returned
     * MediaSource's `Path` is the definitive playable
     * URL; its `LiveStreamId` (or the response's top-level `Id`) must be passed to [closeLiveStream].
     */
    suspend fun openLiveStream(
        baseUrl: String,
        userToken: String,
        userId: String,
        openToken: String,
        capabilities: ClientCapabilities = ClientCapabilities(),
        identity: JellyfinDeviceIdentity? = null,
    ): JellyfinLiveStreamOpenResponse? = runCatching {
        // Bug fix: this is a MediaInfoController route, NOT under /LiveTv/ — Jellyfin 404s
        // "/LiveTv/LiveStreams/Open" (verified against server source). Real path is "/LiveStreams/Open".
        httpPost(baseUrl.trimEnd('/') + "/LiveStreams/Open") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody("""{"OpenToken":"$openToken","UserId":"$userId","DeviceProfile":${deviceProfile(capabilities)}}""")
        }.bodyOrNull<JellyfinLiveStreamOpenResponse>("openLiveStream")
    }.getOrElse { Logger.warn("Jellyfin openLiveStream failed: ${it.message}"); null }

    suspend fun closeLiveStream(baseUrl: String, userToken: String, liveStreamId: String, identity: JellyfinDeviceIdentity? = null): Boolean = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/LiveStreams/Close") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody("""{"LiveStreamId":"$liveStreamId"}""")
        }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin closeLiveStream failed: ${it.message}"); false }

    suspend fun getNextUp(
        baseUrl: String,
        userToken: String,
        userId: String,
        limit: Int = 20,
    ): List<JellyfinPlayItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Shows/NextUp?UserId=$userId&Limit=$limit" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getNextUp")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getNextUp failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }
}

/**
 * Phase 110 — a per-device identity for the Jellyfin session bridge. Every Ravilo TV authenticating
 * under its OWN DeviceId (instead of the one shared `jellystructure-server-v01` every TV + the admin
 * server used before) is the root-cause fix for the paired-token 401 storm: Jellyfin prunes a
 * DeviceId's older tokens on every new login under that same id, so with one shared id, pairing a
 * second TV could invalidate the first TV's token. It also makes each TV show up as its own named
 * session in the Jellyfin dashboard instead of one anonymous "Jellystructure" session for all of them.
 *
 * Phase 141: [forDevice] folds in [DeviceData.jellyfinUserId] as well, so the identity is unique per
 * **(device, user)** rather than per physical device. Without this, two profiles signed in on the same
 * shared TV (R175 multi-user) would share one DeviceId, and the same pruning behaviour that motivated
 * this class in the first place would invalidate the first profile's token the moment the second one
 * signs in. The initial `POST /api/tv/login` auth call (before a `DeviceData` row exists) builds an
 * equivalent identity directly from `deviceId` + `username`.
 */
data class JellyfinDeviceIdentity(val deviceId: String, val deviceName: String) {
    companion object {
        fun forDevice(device: DeviceData): JellyfinDeviceIdentity =
            JellyfinDeviceIdentity("ravilo-${device.deviceId}-${device.jellyfinUserId}", device.displayName.ifBlank { "Ravilo TV" })
    }
}

private fun headerSafe(s: String): String = s.replace("\"", "'").replace("\n", " ").take(64)

/** Canonical authenticated Jellyfin header — one place so no call site can drift (Phase 50). Phase 110:
 *  an [identity] swaps in a per-device Client/Device/DeviceId instead of the shared server identity. */
private fun HttpRequestBuilder.jellyfinAuth(token: String, identity: JellyfinDeviceIdentity? = null) {
    val header = if (identity != null) {
        """MediaBrowser Client="Ravilo", Device="${headerSafe(identity.deviceName)}", DeviceId="${headerSafe(identity.deviceId)}", Version="0.1.0""""
    } else AUTH_HEADER
    header("Authorization", """$header, Token="$token"""")
}

/**
 * Deserialize the body only on a 2xx response. A non-2xx Jellyfin reply is often `text/plain`, which
 * `.body<T>()` can't parse as JSON — it throws `NoTransformationFoundException` (Phase 50). Log the
 * status + a snippet of the error body and return null so callers degrade gracefully instead.
 */
private suspend inline fun <reified T> HttpResponse.bodyOrNull(context: String): T? {
    if (!status.isSuccess()) {
        val detail = runCatching { bodyAsText() }.getOrDefault("")
        Logger.warn("Jellyfin $context failed: ${status.value} ${detail.take(180)}")
        return null
    }
    return body()
}

private fun String.jsonEscape(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
