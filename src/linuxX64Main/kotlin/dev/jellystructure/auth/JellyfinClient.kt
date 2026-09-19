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
import kotlinx.coroutines.CancellationException

// R219 (FR-R219-2) — page size for the "page to completion" fetches Continue Watching's canonical list
// needs. Not a cap: every paged fetch below loops until TotalRecordCount is reached.
private const val JF_PAGE_SIZE = 200

private const val DEVICE_ID = "jellystructure-server-v01"
// Phase 224 (FR-224-1) — the server's own identity carries its real version (was a literal "0.1.0").
private val AUTH_HEADER: String by lazy {
    """MediaBrowser Client="Jellystructure", Device="Server", DeviceId="$DEVICE_ID", Version="${headerSafe(dev.jellystructure.ServerVersion.current)}""""
}

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
//
// R183 splits the two moving parts out into [allowedVideoRangeTypes] (which Dolby Vision variants may
// direct-play) and [h264TargetConditions] (an honest, decodable transcode target) — see their docs.
// Phase 177 adds [videoBitrateConditions] (a per-codec direct-play REQUIREMENT — different from
// h264TargetConditions' declarative, non-required target, see that function's doc for why a blanket
// version of this was previously rejected), folds [capabilities.audioCodecs]/[maxAudioChannels] into
// AudioCodec/a new audio CodecProfile (previously always discarded), and replaces the fixed
// MaxStreamingBitrate with [maxStreamingBitrate] (device decode ceiling + link-derived cap).
internal fun deviceProfile(capabilities: ClientCapabilities): String {
    val audioCodecs = capabilities.audioCodecs.takeIf { it.isNotEmpty() }
        ?.joinToString(",") ?: "aac,ac3,eac3,mp3,flac,vorbis,opus,dts,truehd,pcm,mp2,alac"
    val codecProfiles = buildList {
        add("""{"Type":"Video","Codec":"hevc,h264,vp9,av1","Conditions":[{"Condition":"EqualsAny","Property":"VideoRangeType","Value":"${allowedVideoRangeTypes(capabilities).joinToString("|")}","IsRequired":true}]}""")
        add("""{"Type":"Video","Codec":"h264","Conditions":${h264TargetConditions(capabilities)}}""")
        addAll(videoBitrateConditions(capabilities))
        audioChannelCondition(capabilities)?.let { add(it) }
    }.joinToString(",")
    // 218/R245 amendment (2026-09-18) — `hls_only` and `containers` were declared by the Chromecast
    // receiver (FR-R245-13) and never read here: this list said "mkv direct-plays" for every client, so
    // the receiver was handed a raw MKV URL labelled as HLS and the TV's player process died on it (the
    // first real cast). A client that can only take HLS gets NO direct-play profile — Jellyfin then always
    // answers with a TranscodingUrl (a codec-copy remux when the codecs fit, a transcode otherwise). A
    // client that names its containers direct-plays only those. A client that says nothing keeps today's list.
    val directPlayProfiles = when {
        capabilities.hlsOnly -> ""
        capabilities.containers.isNotEmpty() ->
            """{"Container":"${capabilities.containers.joinToString(",")}","Type":"Video","VideoCodec":"h264,hevc,vp8,vp9,av1,mpeg4,mpeg2video,vc1","AudioCodec":"$audioCodecs"}"""
        else ->
            """{"Container":"mkv,mp4,webm,mov,avi,ts,m2ts,flv,3gp,mpegts","Type":"Video","VideoCodec":"h264,hevc,vp8,vp9,av1,mpeg4,mpeg2video,vc1","AudioCodec":"$audioCodecs"}"""
    }
    return """{"MaxStreamingBitrate":${maxStreamingBitrate(capabilities)},"DirectPlayProfiles":[$directPlayProfiles],"CodecProfiles":[$codecProfiles],"TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac,ac3,mp3","Protocol":"hls","Context":"Streaming"}],"SubtitleProfiles":[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"},{"Format":"subrip","Method":"External"},{"Format":"ass","Method":"External"},{"Format":"ssa","Method":"External"},{"Format":"vobsub","Method":"Embed"},{"Format":"dvdsub","Method":"Embed"},{"Format":"dvbsub","Method":"Embed"},{"Format":"pgssub","Method":"Encode"},{"Format":"pgs","Method":"Encode"}]}"""
}

/**
 * Phase 177 §FR-177-2 — a per-codec `VideoBitrate` **requirement**, unlike [h264TargetConditions]'
 * declarative `IsRequired:false` target: above the device's own reported decode ceiling, the device
 * cannot be trusted to decode, so this makes Jellyfin transcode instead of direct-playing into a wall
 * the investigation found (stue TV's decoders cap at 60 Mbps on both the Dolby Vision and plain-HEVC
 * paths — Jellyfin still keys a DV profile-8 file's Codec as "hevc", so one hevc condition covers both).
 *
 * Only ever emitted when [capabilities] actually reported a ceiling — a client that sends nothing
 * negotiates exactly as before (never invent a number, per `JellyfinClient.kt`'s existing live finding
 * against a *blanket* bitrate condition, see [h264TargetConditions]'s doc). [BITRATE_SAFETY_MARGIN]
 * leaves headroom for container/audio overhead `VideoBitrate` doesn't account for, and for short peaks a
 * rolling average hides (Offboarding averages 24 Mbps but peaks at 88.9 Mbps over 1s).
 */
private fun videoBitrateConditions(capabilities: ClientCapabilities): List<String> = buildList {
    capabilities.maxHevcBitrate.takeIf { it > 0 }?.let { ceiling ->
        val safe = (ceiling * BITRATE_SAFETY_MARGIN).toLong()
        add("""{"Type":"Video","Codec":"hevc","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"$safe","IsRequired":true}]}""")
    }
    capabilities.maxH264Bitrate.takeIf { it > 0 }?.let { ceiling ->
        val safe = (ceiling * BITRATE_SAFETY_MARGIN).toLong()
        add("""{"Type":"Video","Codec":"h264","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"$safe","IsRequired":true}]}""")
    }
}

/** Phase 177 §FR-177-3 — the second half of honouring [ClientCapabilities.maxAudioChannels] (the first
 *  half is the plain default of 8, already threaded through unconditionally today); only emitted when
 *  the client asked for something narrower, so a client that never set it keeps today's behaviour. */
private fun audioChannelCondition(capabilities: ClientCapabilities): String? =
    capabilities.maxAudioChannels.takeIf { it in 1 until 8 }?.let {
        """{"Type":"Audio","Conditions":[{"Condition":"LessThanEqual","Property":"AudioChannels","Value":"$it","IsRequired":true}]}"""
    }

/**
 * Phase 177 §FR-177-4 — `MaxStreamingBitrate` as the minimum of the fixed 120 Mbps ceiling, the device's
 * own decode ceiling (so this can never disagree with [videoBitrateConditions]), and a link-derived
 * allowance when the client reported one: Wi-Fi's PHY rate runs roughly double real achievable TCP
 * throughput under good conditions (worse under load), so it gets a conservative 50%; Ethernet is
 * deterministic and gets 90%. A client reporting no link info is unaffected — this only ever narrows the
 * cap, and doing so makes Jellyfin choose a transcode rather than a direct play the link can't feed,
 * which is strictly better than today's outcome (see the phase's own invariant).
 */
private fun maxStreamingBitrate(capabilities: ClientCapabilities): Long {
    var cap = 120_000_000L
    capabilities.maxVideoBitrate.takeIf { it > 0 }?.let { cap = minOf(cap, it.toLong()) }
    capabilities.linkMbps.takeIf { it > 0 }?.let { mbps ->
        val fraction = when (capabilities.linkKind) {
            "ethernet" -> 0.9
            "wifi" -> 0.5
            else -> null
        } ?: return@let
        cap = minOf(cap, (mbps.toLong() * 1_000_000L * fraction).toLong())
    }
    return cap
}

private const val BITRATE_SAFETY_MARGIN = 0.9

/**
 * R183 — the `VideoRangeType`s this client may direct-play, mirroring how Jellyfin's own Android TV
 * client decides it (`util/profile/deviceProfile.kt`, which builds the inverse — an *unsupported* set —
 * from the same signals):
 * - **Dolby Vision profile 8** (`DOVIWith…`) is single-layer DV whose base layer *is* a conformant
 *   HDR10 / HDR10+ / HLG / SDR stream, with the DV metadata in RPU NAL units every decoder ignores.
 *   So it direct-plays correctly on any device that handles the base range — no DV decoder needed.
 *   This is the bug this phase fixes: `DOVIWithHDR10Plus` (13 of the library's DV titles, e.g.
 *   "Contact Week") was never in the allowed list, so every DV title was permanently forced into a
 *   transcode no matter how capable the client was.
 * - **Profile 5** (`DOVI`) is DV-only (IPT-PQ-C2) — garbage colours without a real DV decoder, so it
 *   stays gated behind [ClientCapabilities.supportsDolbyVision].
 * - **Profile 7** (`DOVIWithEL`, `DOVIWithELHDR10Plus`) is dual-layer; the enhancement layer needs DV
 *   *and* multi-instance HEVC decode → gated behind [ClientCapabilities.supportsDolbyVisionEl].
 * - `DOVIInvalid` is never allowed (the list is an allow-list, so it's excluded by construction).
 */
internal fun allowedVideoRangeTypes(capabilities: ClientCapabilities): List<String> = buildList {
    add("SDR")
    // DV profile 8.2 — SDR base layer, so plain SDR decode is enough (Jellyfin's Android TV client
    // likewise never marks this one unsupported).
    add("DOVIWithSDR")
    if (capabilities.supportsHdr10) {
        add("HDR10"); add("HDR10Plus")
        add("DOVIWithHDR10"); add("DOVIWithHDR10Plus")
    }
    if (capabilities.supportsHlg) {
        add("HLG")
        add("DOVIWithHLG") // DV profile 8.4 — HLG base layer.
    }
    if (capabilities.supportsDolbyVision) add("DOVI")
    if (capabilities.supportsDolbyVisionEl) {
        add("DOVIWithEL")
        if (capabilities.supportsHdr10) add("DOVIWithELHDR10Plus")
    }
}

/**
 * R183 — declares the H.264 **transcode target** so Jellyfin advertises an HLS variant the client can
 * actually decode. Jellyfin derives the master playlist's `CODECS`/`RESOLUTION` from what the profile
 * claims: with nothing declared it fell back to `avc1.424029` (Baseline, level 4.1) while still
 * targeting the source's native 4K, and ExoPlayer drops any variant whose declared profile/level can't
 * carry its resolution — with a single variant on offer, preparation failed before the first frame.
 *
 * Verified live (Jellyfin 10.11.11): these conditions turn the 3840x1606 `avc1.424029` variant into
 * `avc1.640033` (High, level 5.1) at 1920x803, and they also cap the PGS burn-in re-stream the same way.
 * `IsRequired=false` — a *declaration* of the encoder target, not a direct-play requirement.
 *
 * Deliberately **no `VideoBitrate` condition**: verified live that one blocks direct play of any source
 * above it (a high-bitrate H.264 remux would start transcoding for no reason).
 */
private fun h264TargetConditions(capabilities: ClientCapabilities): String {
    // 0 = the client didn't report its decoder ceiling (web, older builds) → 1080p High/L5.1, which
    // every H.264 decoder in the fleet handles.
    val maxWidth = capabilities.maxH264Width.takeIf { it > 0 } ?: 1920
    val maxHeight = capabilities.maxH264Height.takeIf { it > 0 } ?: 1080
    val maxLevel = capabilities.maxH264Level.takeIf { it > 0 } ?: 51
    return """[{"Condition":"EqualsAny","Property":"VideoProfile","Value":"high|main|baseline|constrained baseline","IsRequired":false},{"Condition":"LessThanEqual","Property":"VideoLevel","Value":"$maxLevel","IsRequired":false},{"Condition":"LessThanEqual","Property":"Width","Value":"$maxWidth","IsRequired":false},{"Condition":"LessThanEqual","Property":"Height","Value":"$maxHeight","IsRequired":false}]"""
}

/** Phase 187 (FR-187-3) — the three outcomes `POST /Users/Password` can produce, as distinguished by
 *  the caller: [OK], [WRONG_CURRENT] (403, verified live 2026-09-05 — not the 401 the spec first
 *  guessed), and [FAILED] (network/other — never guessed to be a wrong password). */
enum class PasswordChangeOutcome { OK, WRONG_CURRENT, FAILED }

/**
 * Phase 194 (FR-194-1) — the three outcomes a token check can have, which the old `Boolean` fused
 * into one. [REJECTED] is the *only* one that means the credential is bad; [UNKNOWN] means the
 * question was never answered — the call threw, or Jellyfin replied with something that is a
 * statement about the server's health, not about the token.
 *
 * The fusion was not academic: on 2026-09-06 a single connect-layer blip after a 20-minute idle
 * window was recorded as a rejected token, negative-cached for ten minutes, and took out playback on
 * every device belonging to that user while browsing carried on looking perfectly healthy. The
 * exception was swallowed by `getOrDefault(false)`, so the incident left no evidence behind at all.
 */
enum class TokenCheck { VALID, REJECTED, UNKNOWN }

/**
 * A [TokenCheck] plus the evidence for it, so the caller can log what actually happened instead of
 * asserting a status code nobody read (Phase 194, FR-194-4 — the old log line claimed "(401)" on a
 * code path that only ever saw a `Boolean`).
 */
data class TokenCheckResult(
    val outcome: TokenCheck,
    val httpStatus: Int? = null,
    val error: String? = null,
)

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
        val authHeader = jellyfinIdentityHeader(identity, versionRequired = true)
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

    /**
     * Phase 187 (FR-187-6/7) — set the caller's own Jellyfin user image. [imageBytes] must already be
     * server-side validated + centre-cropped + bounded ([FfmpegRunner.centerCropSquareJpeg]) before this
     * is called; this method only speaks the wire format. [contentType] should be `image/jpeg`.
     *
     * FR-187-1's probe found the documented request shape (a raw `image` MIME binary body) is **wrong** — it
     * 500s. Jellyfin actually wants **base64 text of the image bytes**, with the real MIME type still in
     * `Content-Type` (verified live 2026-09-05: a raw-binary POST 500'd, the identical bytes base64-
     * encoded returned 204 and moved `PrimaryImageTag`). Returns the new `PrimaryImageTag`
     * (`GET /Users/{userId}` re-fetched after a successful write) so the caller can build a change-keyed
     * avatar URL (FR-187-7) without a second round trip elsewhere in the call chain.
     */
    suspend fun setUserImage(baseUrl: String, token: String, userId: String, imageBytes: ByteArray, contentType: String = "image/jpeg"): String? = runCatching {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val b64 = kotlin.io.encoding.Base64.Default.encode(imageBytes)
        val url = baseUrl.trimEnd('/') + "/UserImage?userId=${userId.encodeURLParameter()}"
        val resp = httpPost(url) { jellyfinAuth(token); header("Content-Type", contentType); setBody(b64) }
        if (!resp.status.isSuccess()) { Logger.warn("Jellyfin setUserImage failed: ${resp.status.value}", "auth"); return@runCatching null }
        getUserImageTag(baseUrl, token, userId)
    }.getOrNull()

    /** Phase 187 (FR-187-3) — remove the caller's own Jellyfin user image, back to initials. */
    suspend fun deleteUserImage(baseUrl: String, token: String, userId: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/UserImage?userId=${userId.encodeURLParameter()}"
        httpDelete(url) { jellyfinAuth(token) }.status.isSuccess()
    }.getOrDefault(false)

    private suspend fun getUserImageTag(baseUrl: String, token: String, userId: String): String? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/Users/$userId") { jellyfinAuth(token) }
            .bodyOrNull<JellyfinUser>("getUserImageTag")?.primaryImageTag
    }.getOrNull()

    /**
     * Phase 187 (FR-187-3/8) — change the caller's own Jellyfin password. Jellyfin validates
     * [currentPw]; jellystructure never does (never compares/hashes/stores it). [tokenSurvives] is
     * measured, not assumed: probed live 2026-09-05 that on this house's Jellyfin (10.11.11) a token
     * minted before the change still authorises afterwards — only `AuthenticateByName` is affected —
     * but that isn't a documented guarantee, so the caller re-validates with [JellyfinClient.isTokenValid]
     * using the very token this call was made with, rather than the route hard-coding "always survives".
     */
    suspend fun updateUserPassword(baseUrl: String, token: String, userId: String, currentPw: String, newPw: String): PasswordChangeOutcome = runCatching {
        val url = baseUrl.trimEnd('/') + "/Users/Password?userId=${userId.encodeURLParameter()}"
        val resp = httpPost(url) {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"CurrentPw":${currentPw.jsonEscape()},"NewPw":${newPw.jsonEscape()}}""")
        }
        when {
            resp.status.isSuccess() -> PasswordChangeOutcome.OK
            // FR-187-1's probe: a wrong CurrentPw is 403 on this Jellyfin version, not the 401 an
            // earlier pass at the spec guessed from the declared response list.
            resp.status == HttpStatusCode.Forbidden -> PasswordChangeOutcome.WRONG_CURRENT
            else -> { Logger.warn("Jellyfin updateUserPassword failed: ${resp.status.value}", "auth"); PasswordChangeOutcome.FAILED }
        }
    }.getOrElse { PasswordChangeOutcome.FAILED }

    suspend fun testConnection(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/System/Info/Public"
        val response = httpGet(url) { jellyfinAuth(token) }
        response.status.value in 200..299
    }.getOrDefault(false)

    /**
     * Can [token] still act as [userId]? Used to detect a stale **paired** TV user token so the TV
     * can fall back to the long-lived server token. `/Users/{userId}` is an authenticated endpoint —
     * public `/System/Info/Public` would 200 even for an invalid token, so it can't be used here.
     *
     * Phase 194 (FR-194-1): this used to be `runCatching { … }.getOrDefault(false)`, which reported
     * "the token is bad" for a 5xx, a connect timeout, a TLS failure and a stale pooled connection
     * alike — and threw the exception away unlogged. Only 401/403 is a rejection now; everything
     * else is [TokenCheck.UNKNOWN] and carries its own evidence, which the caller must log.
     */
    suspend fun checkToken(baseUrl: String, token: String, userId: String): TokenCheckResult {
        if (token.isBlank()) return TokenCheckResult(TokenCheck.REJECTED, error = "no token stored for this device")
        return try {
            val status = httpGet(baseUrl.trimEnd('/') + "/Users/$userId") { jellyfinAuth(token) }.status.value
            when {
                status in 200..299 -> TokenCheckResult(TokenCheck.VALID, httpStatus = status)
                // The only answer that is about the credential rather than the server.
                status == 401 || status == 403 -> TokenCheckResult(TokenCheck.REJECTED, httpStatus = status)
                // 5xx, 429, an unexpected 4xx — Jellyfin declined to answer the question we asked.
                else -> TokenCheckResult(TokenCheck.UNKNOWN, httpStatus = status)
            }
        } catch (e: CancellationException) {
            // Phase 205 (FR-205-5) — a bare `catch (e: Throwable)` here swallowed cancellation the same
            // way runCatching does elsewhere in this file: the caller's own withTimeoutOrNull cancels
            // this call, and catching+returning UNKNOWN instead of rethrowing means the coroutine keeps
            // running past its deadline rather than actually stopping.
            throw e
        } catch (e: Throwable) {
            TokenCheckResult(TokenCheck.UNKNOWN, error = e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

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
            "/Items?IncludeItemTypes=Movie,Series,MusicVideo&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItems")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" || it.type == "MusicVideo" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItems failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItemsByParent(baseUrl: String, token: String, parentId: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?ParentId=$parentId&IncludeItemTypes=Movie,Series,MusicVideo&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItemsByParent")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" || it.type == "MusicVideo" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemsByParent failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    /**
     * Phase 181 (FR-181-1) — every Movie/Series/Episode id Jellyfin holds, for the set-difference sweep
     * that converges jellystructure's catalog onto Jellyfin's actual state instead of predicting what
     * needs attention. Deliberately **not** filtered by `MinDateCreated`/`MinDateLastSaved` — both were
     * verified live to be silently ignored (return the full unfiltered library, no error) — and
     * deliberately not sorted or windowed by `DateCreated`, which is the file's on-disk mtime, not
     * Jellyfin's ingest time, and unusable as a watermark on this library (spec §2.4: 50% of files carry
     * an mtime unrelated to when they were actually added). A full, unfiltered id enumeration is the only
     * sound basis here. Paged to `TotalRecordCount` per this codebase's own rule (see
     * `getResumeItemsAll`/`getRecentlyPlayedAll` — a bare `Limit` is never trusted as a cap), even though
     * this server returns the whole 8k-item library in one unpaged response today.
     */
    suspend fun getAllLibraryItemIds(baseUrl: String, token: String): List<JellyfinItem> = runCatching {
        val acc = mutableListOf<JellyfinItem>()
        var startIndex = 0
        while (true) {
            val url = baseUrl.trimEnd('/') +
                "/Items?Recursive=true&IncludeItemTypes=Movie,Series,Episode&EnableImages=false&EnableUserData=false" +
                "&Fields=Path,SeriesId&Limit=$JF_PAGE_SIZE&StartIndex=$startIndex"
            val resp = httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("getAllLibraryItemIds") ?: break
            if (resp.items.isEmpty()) break
            acc += resp.items
            startIndex += resp.items.size
            if (startIndex >= resp.totalRecordCount) break
        }
        acc
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getAllLibraryItemIds failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItem(baseUrl: String, token: String, jellyfinId: String): JellyfinItem? = runCatching {
        // Fetch via the same list-endpoint shape getItems uses, filtered to one id.
        // Phase 207/208 correction (2026-09-13, verified live against this server's Jellyfin): the
        // non-user-scoped single-item route `/Items/{id}` 400s not because of the server token but
        // because it has no user context — `GET /Items/{id}?userId={uid}` returns 200 with this exact
        // admin token. `/Users/{userId}/Items/{id}` is NOT what it "expects" either: it isn't declared
        // in this server's own OpenAPI document (Phase 208's undocumented-routes finding) — it merely
        // also happens to work. The `Ids=` filter used here needs no user context at all and is the
        // pattern this file now also uses in getItemMediaStreams for the same reason.
        val url = baseUrl.trimEnd('/') +
            "/Items?Ids=$jellyfinId&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName,SeriesId"
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
            "/Items?Path=${path.encodeURLParameter()}&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName,SeriesId"
        httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("getItemByPath")?.items?.firstOrNull()
    }.getOrElse { Logger.warn("Jellyfin getItemByPath failed: ${it.message}"); null }

    /** Phase 163 (step 6) — Jellyfin's own MediaSegments API (`GetItemSegments`), confirmed live against
     *  this server's OpenAPI: no provider field, `Type` one of Unknown/Intro/Outro/Recap/Preview/
     *  Commercial. Read-only — the caller offers these as `source=jellyfin` candidates, never applies
     *  them automatically. Expect this to come back empty on a server with no segment-provider plugin
     *  installed (confirmed live 2026-08); that's normal, not an error. */
    suspend fun getMediaSegments(baseUrl: String, token: String, jellyfinId: String): List<JellyfinMediaSegment> = runCatching {
        val url = baseUrl.trimEnd('/') + "/MediaSegments/$jellyfinId"
        httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinMediaSegmentsResponse>("getMediaSegments")?.items ?: emptyList()
    }.getOrElse { Logger.warn("Jellyfin getMediaSegments failed: ${it.message}"); emptyList() }

    // ── Phase 165 — plugin management for the Webhook-plugin ingest path ───────

    /** Every INSTALLED plugin (`GET /Plugins`), used to check whether the Webhook plugin is present and
     *  which version, and to find its live `Id` (needed for the Configuration calls below — never
     *  assume it equals the catalog GUID; Jellyfin mints its own plugin instance id). Phase 212
     *  (FR-212-7) also reads each entry's `status` for a pending-restart finding. */
    suspend fun getPlugins(baseUrl: String, token: String): List<JellyfinPluginInfo>? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/Plugins") { jellyfinAuth(token) }.bodyOrNull<List<JellyfinPluginInfo>>("getPlugins")
    }.getOrElse { Logger.warn("Jellyfin getPlugins failed: ${it.message}"); null }

    // ── Phase 212 — Jellyfin settings advisor ───────────────────────────────────

    /** `GET /System/Configuration/encoding` — the source for FR-212-5's server-wide findings. */
    suspend fun getEncodingConfiguration(baseUrl: String, token: String): JellyfinEncodingConfig? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/System/Configuration/encoding") { jellyfinAuth(token) }
            .bodyOrNull<JellyfinEncodingConfig>("getEncodingConfiguration")
    }.getOrElse { Logger.warn("Jellyfin getEncodingConfiguration failed: ${it.message}"); null }

    /** `GET /System/Info` (authenticated — distinct from [testConnection]'s public probe, which doesn't
     *  carry `HasPendingRestart`). Source for FR-212-7. */
    suspend fun getSystemInfoAuth(baseUrl: String, token: String): JellyfinSystemInfoAuth? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/System/Info") { jellyfinAuth(token) }
            .bodyOrNull<JellyfinSystemInfoAuth>("getSystemInfoAuth")
    }.getOrElse { Logger.warn("Jellyfin getSystemInfoAuth failed: ${it.message}"); null }

    // ── Phase 244 — exposure advisor (read-only; FR-244-7 forbids any lifecycle route) ──────────

    /** `GET /System/Configuration/network` — `KnownProxies` and `EnableRemoteAccess`, the two facts
     *  FR-244-1 and FR-244-2 read. */
    suspend fun getNetworkConfiguration(baseUrl: String, token: String): JellyfinNetworkConfig? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/System/Configuration/network") { jellyfinAuth(token) }
            .bodyOrNull<JellyfinNetworkConfig>("getNetworkConfiguration")
    }.getOrElse { Logger.warn("Jellyfin getNetworkConfiguration failed: ${it.message}"); null }

    /** FR-244-1's capability probe: ask Jellyfin how it would classify a caller presenting a public
     *  address, rather than reading a setting and reasoning about what it implies.
     *
     *  The address is **mandatorily** from `203.0.113.0/24` (RFC 5737 TEST-NET-3). A real address such
     *  as `8.8.8.8` must never be used here, because the probe would then be asserting something about
     *  a third party's network.
     *
     *  Read-only, and deliberately so: the audit that produced phase 244 caused roughly a minute of
     *  household downtime by probing `POST /System/Restart` expecting a 401, so FR-244-7 puts lifecycle
     *  routes on a deny-list and this phase reads a classification instead of testing a restart by
     *  performing one. */
    suspend fun probeEndpointClassification(baseUrl: String, token: String): JellyfinEndpointInfo? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/System/Endpoint") {
            jellyfinAuth(token)
            header("X-Forwarded-For", EXPOSURE_PROBE_ADDRESS)
        }.bodyOrNull<JellyfinEndpointInfo>("probeEndpointClassification")
    }.getOrElse { Logger.warn("Jellyfin probeEndpointClassification failed: ${it.message}"); null }

    /** The default repository's plugin catalog (`GET /Packages`) — used to find the Webhook plugin's
     *  latest installable version when it isn't installed yet. */
    suspend fun getAvailablePackages(baseUrl: String, token: String): List<JellyfinPackageInfo>? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/Packages") { jellyfinAuth(token) }.bodyOrNull<List<JellyfinPackageInfo>>("getAvailablePackages")
    }.getOrElse { Logger.warn("Jellyfin getAvailablePackages failed: ${it.message}"); null }

    /** `POST /Packages/Installed/{name}` — installs a plugin from the catalog by name + assembly GUID.
     *  Takes effect after Jellyfin's next restart (never triggered automatically — see [restartServer]'s
     *  own doc on why this stays a separate, explicit, user-initiated action). */
    suspend fun installPlugin(baseUrl: String, token: String, name: String, assemblyGuid: String, version: String? = null): Boolean = runCatching {
        val url = buildString {
            append(baseUrl.trimEnd('/')); append("/Packages/Installed/"); append(name.encodeURLParameter())
            append("?assemblyGuid=").append(assemblyGuid)
            if (version != null) append("&version=").append(version.encodeURLParameter())
        }
        httpPost(url) { jellyfinAuth(token) }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin installPlugin failed: ${it.message}"); false }

    /** `POST /System/Restart` — restarts the whole Jellyfin server (every household stream drops).
     *  Callers must treat this as its own explicit, separately-confirmed action, never a side effect of
     *  installing/configuring a plugin (Phase 165 spec FR-165-3). */
    suspend fun restartServer(baseUrl: String, token: String): Boolean = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/System/Restart") { jellyfinAuth(token) }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin restartServer failed: ${it.message}"); false }

    /** Raw JSON passthrough (`GET /Plugins/{pluginId}/Configuration`) — deliberately NOT a typed DTO.
     *  The Webhook plugin's configuration shape is confirmed from its C# source (`ServerUrl`,
     *  `GenericOptions[]` among 9 destination-type arrays, each `WebhookName`/`WebhookUri`/
     *  `NotificationTypes[]`/`EnableMovies`/`EnableEpisodes`/`EnableSeries`/`Template`/...) but its exact
     *  runtime JSON casing has NOT been verified live (the plugin isn't installed on this deployment as
     *  of this writing) — a generic [kotlinx.serialization.json.JsonObject] read-modify-write survives a
     *  casing/shape mismatch far better than a strict typed decode would (which could silently drop
     *  fields this code doesn't know about on re-serialize). Callers MUST treat any parse/shape failure
     *  as "fall back to manual setup instructions," never as a reason to guess. */
    suspend fun getPluginConfiguration(baseUrl: String, token: String, pluginId: String): kotlinx.serialization.json.JsonObject? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/Plugins/$pluginId/Configuration") { jellyfinAuth(token) }
            .bodyOrNull<kotlinx.serialization.json.JsonObject>("getPluginConfiguration")
    }.getOrElse { Logger.warn("Jellyfin getPluginConfiguration failed: ${it.message}"); null }

    /** `POST /Plugins/{pluginId}/Configuration` — writes back the WHOLE configuration document. Callers
     *  must read-modify-write (never construct one from scratch) so an admin's other destinations
     *  (Discord, Slack, ...) survive untouched. */
    suspend fun updatePluginConfiguration(baseUrl: String, token: String, pluginId: String, config: kotlinx.serialization.json.JsonObject): Boolean = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/Plugins/$pluginId/Configuration") {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody(config)
        }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin updatePluginConfiguration failed: ${it.message}"); false }

    // Phase 165 amendment (2026-08-14, FR-165-8) — `GET /ScheduledTasks` + `POST
    // /ScheduledTasks/Running/{id}`, used by the live delivery probe to trigger the Webhook plugin's own
    // "Webhook Item Added Notifier" task on demand (a cheap, harmless task that always completes).

    suspend fun getScheduledTasks(baseUrl: String, token: String): List<JellyfinTaskInfo>? = runCatching {
        httpGet(baseUrl.trimEnd('/') + "/ScheduledTasks") { jellyfinAuth(token) }.bodyOrNull<List<JellyfinTaskInfo>>("getScheduledTasks")
    }.getOrElse { Logger.warn("Jellyfin getScheduledTasks failed: ${it.message}"); null }

    suspend fun runScheduledTask(baseUrl: String, token: String, taskId: String): Boolean = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/ScheduledTasks/Running/$taskId") { jellyfinAuth(token) }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin runScheduledTask failed: ${it.message}"); false }

    /** Phase 145 — reliable path→item resolution. This Jellyfin **ignores** the `?Path=` filter
     *  ([getItemByPath] then returns an arbitrary item), so instead pull the most-recently-added
     *  Movie/Episode items and match the path **ourselves**. Bounded (last [limit] additions) and safe to
     *  poll after a `notifyLibraryMediaUpdated` nudge. Returns the exact-path match, or null if not there yet. */
    suspend fun findRecentItemByPath(baseUrl: String, token: String, path: String, limit: Int = 200): JellyfinItem? = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?Recursive=true&IncludeItemTypes=Movie,Episode&SortBy=DateCreated&SortOrder=Descending&Limit=$limit" +
            "&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName,SeriesId"
        httpGet(url) { jellyfinAuth(token) }.bodyOrNull<JellyfinItemsResponse>("findRecentItemByPath")
            ?.items?.firstOrNull { it.path == path }
    }.getOrElse { Logger.warn("Jellyfin findRecentItemByPath failed: ${it.message}"); null }

    /** Phase 114 — batch form of [getItem] for the LibraryChanged listener (chunked by the caller to
     *  keep URLs reasonable; Jellyfin has no documented Ids= count limit but a few hundred is prudent). */
    suspend fun getItemsByIds(baseUrl: String, token: String, jellyfinIds: List<String>): List<JellyfinItem> {
        if (jellyfinIds.isEmpty()) return emptyList()
        val url = baseUrl.trimEnd('/') +
            "/Items?Ids=${jellyfinIds.joinToString(",")}&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName,SeriesId"
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

    /** R186: [limit] is the CANDIDATE pool the Continue row filters from (per-channel membership, dedup),
     *  not how many cards are shown — keep it well above a realistic in-flight count or a channel row
     *  silently truncates to "whichever of the global top-N happen to be in it". `IsPlayed=false` excludes
     *  finished items server-side, so an R185-style played/position desync can't consume the window. */
    suspend fun getResumeItems(
        baseUrl: String,
        userToken: String,
        userId: String,
        limit: Int = 200,
    ): List<JellyfinPlayItem> = runCatching {
        // Phase 208 — migrated off /Users/{userId}/Items (undocumented in 10.11.11, [Obsolete("Kept for
        // backwards compatibility")] in Jellyfin's own source) to the documented /Items?userId= form.
        // Verified live 2026-09-13: byte-identical response, same filter, on this exact query shape.
        val url = baseUrl.trimEnd('/') +
            "/Items?userId=$userId&Filters=IsResumable&Recursive=true&IsPlayed=false" +
            "&IncludeItemTypes=Movie,Episode&Limit=$limit" +
            "&SortBy=DatePlayed&SortOrder=Descending" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getResumeItems")?.items.orEmpty()
    }.warnOnFailureOrDefault("getResumeItems", emptyList())

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
        // Phase 208 — see getResumeItems' comment; same migration, verified the same way.
        val url = baseUrl.trimEnd('/') +
            "/Items?userId=$userId&Filters=IsPlayed&Recursive=true" +
            "&IncludeItemTypes=Movie,Episode&Limit=$limit&StartIndex=$startIndex" +
            "&SortBy=DatePlayed&SortOrder=Descending" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        httpGet(url) { jellyfinAuth(adminToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getRecentlyPlayed")?.items.orEmpty()
    }.warnOnFailureOrDefault("getRecentlyPlayed", emptyList())

    /**
     * R219 (FR-R219-2) — [getResumeItems] in FULL, not the bounded preview Phase 143's device-history
     * card uses. Continue Watching's canonical list must never truncate this pre-merge (see the rule at
     * the top of phase-R219's spec) — pages until the collected count reaches `TotalRecordCount`.
     * `EnableImages=false` halves the payload; these images are never shown (jellystructure's own
     * catalog artwork is used instead).
     */
    suspend fun getResumeItemsAll(baseUrl: String, userToken: String, userId: String): List<JellyfinPlayItem> = runCatching {
        val acc = mutableListOf<JellyfinPlayItem>()
        var startIndex = 0
        while (true) {
            // Phase 208 — migrated off /Users/{userId}/Items; see getResumeItems' comment.
            val url = baseUrl.trimEnd('/') +
                "/Items?userId=$userId&Filters=IsResumable&Recursive=true&IsPlayed=false" +
                "&IncludeItemTypes=Movie,Episode&Limit=$JF_PAGE_SIZE&StartIndex=$startIndex" +
                "&SortBy=DatePlayed&SortOrder=Descending&EnableImages=false" +
                "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
            val resp = httpGet(url) { jellyfinAuth(userToken) }.bodyOrNull<JellyfinPlayItemsResponse>("getResumeItemsAll") ?: break
            if (resp.items.isEmpty()) break
            acc += resp.items
            startIndex += resp.items.size
            if (startIndex >= resp.totalRecordCount) break
        }
        acc
    }.warnOnFailureOrDefault("getResumeItemsAll", emptyList())

    /**
     * R219 (FR-R219-2) — [getRecentlyPlayed] in FULL, not one bounded history page. Continue Watching's
     * conflict rule (§3, "most recent activity wins") needs the true last-finished instant for every
     * series, not just the ones within an arbitrary pool — see the rule at the top of phase-R219's spec
     * (this replaced the `CONTINUE_RECENCY_POOL` constant that violated it). `EnableImages=false` halves
     * the payload (measured live: 1.89 MB → 0.97 MB for 1104 episodes).
     */
    suspend fun getRecentlyPlayedAll(baseUrl: String, userToken: String, userId: String): List<JellyfinPlayItem> = runCatching {
        val acc = mutableListOf<JellyfinPlayItem>()
        var startIndex = 0
        while (true) {
            // Phase 208 — migrated off /Users/{userId}/Items; see getResumeItems' comment.
            val url = baseUrl.trimEnd('/') +
                "/Items?userId=$userId&Filters=IsPlayed&Recursive=true" +
                "&IncludeItemTypes=Movie,Episode&Limit=$JF_PAGE_SIZE&StartIndex=$startIndex" +
                "&SortBy=DatePlayed&SortOrder=Descending&EnableImages=false" +
                "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
            val resp = httpGet(url) { jellyfinAuth(userToken) }.bodyOrNull<JellyfinPlayItemsResponse>("getRecentlyPlayedAll") ?: break
            if (resp.items.isEmpty()) break
            acc += resp.items
            startIndex += resp.items.size
            if (startIndex >= resp.totalRecordCount) break
        }
        acc
    }.warnOnFailureOrDefault("getRecentlyPlayedAll", emptyList())

    /**
     * R219 (FR-R219-3) — membership rule §2(c): an item touched (played at least once) within
     * [sinceEpochSeconds], even with no saved position and no finish. No Jellyfin filter expresses "has
     * been played" directly (verified against the live OpenAPI: `minDateLastSaved` exists,
     * `minDateLastPlayed` does not), so this reads `SortBy=DatePlayed&SortOrder=Descending` with NO
     * played filter and stops as soon as a page's DatePlayed crosses the window edge (or is null/absent,
     * meaning everything with real playback data has already been seen) — bounded by the window, not by
     * a row-count guess, per the rule at the top of phase-R219's spec.
     */
    suspend fun getRecentlyTouched(baseUrl: String, userToken: String, userId: String, sinceEpochSeconds: Long): List<JellyfinPlayItem> = runCatching {
        val acc = mutableListOf<JellyfinPlayItem>()
        var startIndex = 0
        outer@ while (true) {
            // Phase 208 — migrated off /Users/{userId}/Items; see getResumeItems' comment.
            val url = baseUrl.trimEnd('/') +
                "/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Episode" +
                "&Limit=$JF_PAGE_SIZE&StartIndex=$startIndex&SortBy=DatePlayed&SortOrder=Descending" +
                "&EnableImages=false&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
            val resp = httpGet(url) { jellyfinAuth(userToken) }.bodyOrNull<JellyfinPlayItemsResponse>("getRecentlyTouched") ?: break
            if (resp.items.isEmpty()) break
            for (item in resp.items) {
                val ts = item.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) }
                if (ts == null || ts < sinceEpochSeconds) break@outer
                acc += item
            }
            startIndex += resp.items.size
            if (startIndex >= resp.totalRecordCount) break
        }
        acc
    }.warnOnFailureOrDefault("getRecentlyTouched", emptyList())

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
    }.let {
        // Phase 219 (FR-219-1) — 210's rule: never swallow the caller's own cancellation as a "failure".
        it.exceptionOrNull()?.let { e -> if (e is kotlinx.coroutines.CancellationException) throw e }
        if (it.isFailure) Logger.warn("Jellyfin reportPlaybackProgress failed: ${it.exceptionOrNull()?.message}")
    }

    /** Phase 219 (FR-219-2) — the acknowledging form the PlaybackWriter retries on: true on a 2xx, false
     *  on any other answer, and every exception (including cancellation) propagates to the writer. */
    suspend fun postPlaybackProgress(
        baseUrl: String, userToken: String, jellyfinId: String, positionTicks: Long, isPaused: Boolean,
        mediaSourceId: String, identity: JellyfinDeviceIdentity? = null, playSessionId: String? = null,
    ): Boolean {
        val r = httpPost(baseUrl.trimEnd('/') + "/Sessions/Playing/Progress") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            val psid = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"IsPaused":$isPaused,"MediaSourceId":"$mediaSourceId","EventName":"timeupdate"$psid}""")
        }
        if (r.status.value !in 200..299) throw IllegalStateException("Jellyfin answered ${r.status.value} to a progress report")
        return true
    }

    /** Phase 219 (FR-219-2) — see [postPlaybackProgress]; the stop is the write that must land. */
    suspend fun postPlaybackStopped(
        baseUrl: String, userToken: String, jellyfinId: String, positionTicks: Long,
        mediaSourceId: String, identity: JellyfinDeviceIdentity? = null, playSessionId: String? = null,
    ): Boolean {
        val r = httpPost(baseUrl.trimEnd('/') + "/Sessions/Playing/Stopped") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            val psid = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"MediaSourceId":"$mediaSourceId"$psid}""")
        }
        if (r.status.value !in 200..299) throw IllegalStateException("Jellyfin answered ${r.status.value} to a stop report")
        return true
    }

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
    }.let {
        it.exceptionOrNull()?.let { e -> if (e is kotlinx.coroutines.CancellationException) throw e }   // Phase 219 (FR-219-1)
        if (it.isFailure) Logger.warn("Jellyfin stopPlaybackSession failed: ${it.exceptionOrNull()?.message}")
    }

    /**
     * Phase 180 (FR-180-2) — releases an in-flight transcode for [playSessionId], which must be
     * Jellyfin's OWN play-session id (the top-level `PlaySessionId` [getPlaybackInfo] returns,
     * carried through from [JellyfinPlaybackInfoResponse.playSessionId]) — NOT jellystructure's
     * deterministic bookkeeping id ([dev.jellystructure.tv.playSessionIdFor]), which is a different
     * namespace Jellyfin's transcode manager never sees. [deviceId] must likewise be the
     * `identity.deviceId` used for the stream/PlaybackInfo request that produced this play session, not
     * the raw jellystructure device id.
     *
     * Confirmed against the live 10.11.11 OpenAPI document (2026-08-28): `DELETE
     * /Videos/ActiveEncodings` (operationId `StopEncodingProcess`) takes exactly these two query params,
     * both required, and returns 204 with no body — including, per its own C# implementation, when no
     * matching encode is found (a direct-play session, or one already torn down), so this is safe to call
     * unconditionally and needs no "was this actually transcoding" check upstream. Failure is logged and
     * swallowed, never surfaced to the caller — see this function's callers for why (a release must never
     * block or fail the user-visible stop).
     *
     * Takes [identity] rather than a bare device-id string: the query param must be byte-identical to
     * the `identity.deviceId` the original stream/PlaybackInfo request used, so deriving it here (instead
     * of trusting a second caller-supplied copy) removes an entire class of mismatch bug.
     */
    suspend fun stopActiveEncoding(
        baseUrl: String,
        userToken: String,
        identity: JellyfinDeviceIdentity,
        playSessionId: String,
    ) = runCatching {
        httpDelete(
            baseUrl.trimEnd('/') +
                "/Videos/ActiveEncodings?deviceId=${identity.deviceId.encodeURLParameter()}" +
                "&playSessionId=${playSessionId.encodeURLParameter()}",
        ) {
            jellyfinAuth(userToken, identity)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin stopActiveEncoding failed: ${it.exceptionOrNull()?.message}") }

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

    // Phase 208 (FR-208-3) — migrated off /Users/{userId}/PlayedItems/{itemId}. Jellyfin's own source
    // (Jellyfin.Api/Controllers/PlaystateController.cs) shows the legacy route's handler
    // (`MarkPlayedItemLegacy`) does nothing but call this exact modern handler with the same
    // parameters — same internal code path, not just a similar one — which is why this is trusted
    // without a live write test: firing a live write against the household to "verify" it would mutate
    // real watch-history data for a route this source reading already proves is identical, and no
    // jellyfin-demo credentials were available this session to test it there instead (FR-208-3's own
    // preferred target). Whoever verifies this live should use jellyfin-demo, never the household.
    suspend fun markPlayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/UserPlayedItems/$jellyfinId?userId=$userId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markPlayed failed: ${it.exceptionOrNull()?.message}") }

    suspend fun markUnplayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpDelete(baseUrl.trimEnd('/') + "/UserPlayedItems/$jellyfinId?userId=$userId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markUnplayed failed: ${it.exceptionOrNull()?.message}") }

    // Bug fix — Ravilo's "My List": mirrors markPlayed/markUnplayed exactly, same REST shape
    // (`/Users/{id}/FavoriteItems/{itemId}`), for a feature whose write-through call never existed.
    // Phase 208 (FR-208-3) — migrated off /Users/{userId}/FavoriteItems/{itemId}; same reasoning and same
    // same-handler proof as markPlayed/markUnplayed above (UserLibraryController.cs's
    // `MarkFavoriteItemLegacy`/`UnmarkFavoriteItemLegacy` call the modern handlers directly).
    suspend fun markFavorite(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/UserFavoriteItems/$jellyfinId?userId=$userId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markFavorite failed: ${it.exceptionOrNull()?.message}") }

    suspend fun unmarkFavorite(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        httpDelete(baseUrl.trimEnd('/') + "/UserFavoriteItems/$jellyfinId?userId=$userId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin unmarkFavorite failed: ${it.exceptionOrNull()?.message}") }

    suspend fun getItemDetail(
        baseUrl: String,
        userToken: String,
        userId: String,
        jellyfinId: String,
    ): JellyfinItemDetail? = runCatching {
        // Phase 208 — migrated off /Users/{userId}/Items/{itemId}; verified live 2026-09-13,
        // byte-identical response on this exact query shape.
        val url = baseUrl.trimEnd('/') +
            "/Items/$jellyfinId?userId=$userId&Fields=UserData,RunTimeTicks,MediaStreams"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinItemDetail>("getItemDetail")
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemDetail failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    /**
     * Phase 179 (FR-179-1) — like [getItemDetail], but no `/Users/{userId}` context: only `MediaStreams`
     * is needed to find text-subtitle streams to pre-warm, and the pipeline has no per-device user to
     * scope the call to (it runs once for the library, not once per viewer).
     *
     * Phase 207 correction: this used to call `/Items/{id}?Fields=MediaStreams` with the admin token,
     * which 400s — a route needing a user context answers 400, not 401/403, when none is named (see
     * [getItem]'s comment). It ran unnoticed since Phase 179 shipped: 285 failing calls/run, all logged,
     * none read as a bug because "0 subtitle stream(s) warmed" looks identical to "nothing needed
     * warming." Now uses the same `Ids=` list-endpoint shape [getItem] uses, which needs no user
     * context at all — verified live, 2026-09-13.
     */
    suspend fun getItemMediaStreams(
        baseUrl: String,
        token: String,
        jellyfinId: String,
    ): JellyfinItemDetail? = runCatching {
        val url = baseUrl.trimEnd('/') + "/Items?Ids=$jellyfinId&Fields=MediaStreams"
        httpGet(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemDetailsResponse>("getItemMediaStreams")?.items?.firstOrNull()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemMediaStreams failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    /**
     * Phase 207 (FR-207-2) — the per-series sibling of [getItemMediaStreams]: one request returns
     * `MediaStreams` for every episode Jellyfin knows about, rather than one sequential call per episode
     * (285 calls / 14s for one series, observed in production before this phase). No user context
     * needed, same reasoning as [getSeriesEpisodesMeta]'s `/Shows/{seriesId}/Episodes` shape. Returns
     * `null` only when the Jellyfin call itself failed — distinct from a successful call whose series
     * genuinely has zero episodes — so the caller can tell "nothing to warm" from "could not tell"
     * (FR-207-3), which is the exact distinction this phase exists to draw.
     */
    suspend fun getSeriesEpisodesMediaStreams(
        baseUrl: String,
        token: String,
        seriesId: String,
    ): List<JellyfinEpisodeItem>? {
        val url = baseUrl.trimEnd('/') + "/Shows/$seriesId/Episodes?Fields=MediaStreams"
        val response = runCatching { httpGet(url) { jellyfinAuth(token) } }.getOrElse {
            Logger.warn("Jellyfin getSeriesEpisodesMediaStreams failed: ${it.message}")
            return null
        }
        return response.bodyOrNull<JellyfinEpisodesResponse>("getSeriesEpisodesMediaStreams")?.items
    }

    /** Phase 213 (FR-213-4) — [warmSubtitleExtraction]'s outcome, split so a caller can tell "Jellyfin
     *  is still busy on this file" (abandon the rest of this item's stream list — retrying immediately
     *  just queues another request behind the one still running) from an ordinary per-stream failure
     *  (safe to move on to the next stream). Collapsing both into one `Boolean` is the exact shape of
     *  the 2026-09-15 incident's §2.2 defect: a timeout advanced to the next index instead of stopping. */
    sealed interface WarmResult {
        data object Success : WarmResult
        data object TimedOut : WarmResult
        data class Failed(val reason: String?) : WarmResult
    }

    /**
     * Phase 179 (FR-179-1) — hits the exact URL [dev.jellystructure.tv.PlaybackService.buildSubtracks]
     * builds for a real sideloaded text-subtitle track (`.../Subtitles/{index}/0/Stream.vtt`), ahead of
     * any real playback, so Jellyfin's own ffmpeg extraction (R183: measured 4m37s cold on a 26 GB file)
     * has already run and cached by the time a client actually asks — see the phase's Root cause §4 for
     * why a live client request can lose the race against Jellyfin extracting the same file a real
     * transcode is concurrently reading. The response body is discarded; only the side effect (Jellyfin's
     * own cache getting warmed) matters here.
     *
     * Phase 210 (FR-210-1) — returns whether the request actually succeeded, and does not catch
     * [CancellationException]: this call runs inside [PipelineStepPool]'s per-item [kotlinx.coroutines.withTimeout],
     * and a plain `runCatching` here used to swallow that timeout's cancellation exactly like the bug
     * [PipelineStepPool] itself already documents fixing at its own layer (Phase 182, FR-182-5) — silently
     * absorbing the cancellation instead of letting the deadline actually abandon the item, while every
     * remaining loop iteration in [PipelineStepOps.warmedCountOf] threw and was logged as an independent
     * "timeout" the instant it was reached.
     *
     * Phase 213 (FR-213-4) — [WarmResult.TimedOut] is reported distinctly from an ordinary
     * [WarmResult.Failed]: `OutboundHttp`'s shared client times out this call at 120 s
     * ([io.ktor.client.plugins.HttpRequestTimeoutException]), and a timeout means Jellyfin's own ffmpeg
     * is still running against this same file — the caller must stop issuing more requests against it,
     * not move on to the next stream index (the behaviour that built the 2026-09-15 backlog).
     */
    suspend fun warmSubtitleExtraction(baseUrl: String, token: String, jellyfinId: String, streamIndex: Int): WarmResult {
        val url = baseUrl.trimEnd('/') + "/Videos/$jellyfinId/$jellyfinId/Subtitles/$streamIndex/0/Stream.vtt?api_key=$token"
        return try {
            httpGet(url)
            WarmResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            Logger.warn("Jellyfin subtitle pre-warm timed out (item=$jellyfinId index=$streamIndex) — Jellyfin is still busy on this file")
            WarmResult.TimedOut
        } catch (e: Throwable) {
            Logger.warn("Jellyfin subtitle pre-warm failed (item=$jellyfinId index=$streamIndex): ${e.message}")
            WarmResult.Failed(e.message)
        }
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
            "/Shows/$seriesId/Episodes?Fields=RunTimeTicks,SeasonName,DateCreated,Path"
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
        // Phase 208 — migrated off /Users/{userId}/Items; see getResumeItems' comment.
        val url = baseUrl.trimEnd('/') +
            "/Items?userId=$userId&Ids=$ids&Fields=UserData,RecursiveItemCount&Limit=${jellyfinIds.size}"
        httpGet(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinUserDataItemsResponse>("getUserDataBulk")?.items.orEmpty()
    }.warnOnFailureOrDefault("getUserDataBulk", emptyList())

    suspend fun getFavoriteItemIds(
        baseUrl: String,
        userToken: String,
        userId: String,
    ): Set<String> = runCatching {
        // Phase 208 — migrated off /Users/{userId}/Items; see getResumeItems' comment.
        val url = baseUrl.trimEnd('/') +
            "/Items?userId=$userId&Filters=IsFavorite&Recursive=true" +
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
        itemId: String,
        playSessionId: String? = null,
        capabilities: ClientCapabilities = ClientCapabilities(),
        identity: JellyfinDeviceIdentity? = null,
    ): JellyfinLiveStreamOpenResponse? = runCatching {
        // Bug fix: this is a MediaInfoController route, NOT under /LiveTv/ — Jellyfin 404s
        // "/LiveTv/LiveStreams/Open" (verified against server source). Real path is "/LiveStreams/Open".
        //
        // Bug fix: the server's OpenLiveStream action defaults ItemId to Guid.Empty when it's absent
        // from both the query string and this body — without a real channel id it can't resolve the
        // tuner/provider source and throws server-side, surfacing here as a generic
        // "400 Error processing request." (verified against server source: `ItemId = itemId ??
        // openLiveStreamDto?.ItemId ?? Guid.Empty`). PlaySessionId is carried over from the PlaybackInfo
        // call it followed, matching official clients' behavior.
        val psidBody = playSessionId?.let { ""","PlaySessionId":"$it"""" } ?: ""
        httpPost(baseUrl.trimEnd('/') + "/LiveStreams/Open") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody("""{"OpenToken":"$openToken","UserId":"$userId","ItemId":"$itemId"$psidBody,"DeviceProfile":${deviceProfile(capabilities)}}""")
        }.bodyOrNull<JellyfinLiveStreamOpenResponse>("openLiveStream")
    }.getOrElse { Logger.warn("Jellyfin openLiveStream failed: ${it.message}"); null }

    suspend fun closeLiveStream(baseUrl: String, userToken: String, liveStreamId: String, identity: JellyfinDeviceIdentity? = null): Boolean = runCatching {
        httpPost(baseUrl.trimEnd('/') + "/LiveStreams/Close") {
            jellyfinAuth(userToken, identity)
            contentType(ContentType.Application.Json)
            setBody("""{"LiveStreamId":"$liveStreamId"}""")
        }.status.isSuccess()
    }.getOrElse { Logger.warn("Jellyfin closeLiveStream failed: ${it.message}"); false }

    /** R219 (FR-R219-2, was R186): pages to `TotalRecordCount` — a bounded `Limit` here is a pre-merge
     *  truncation (see the rule at the top of phase-R219's spec). Live example that motivated the
     *  original R186 fix: this user's NextUp TotalRecordCount was 54 while a `Limit=20` fetch returned
     *  20, so a series at position 41 was invisible on Home and in every channel row.
     *  `EnableImages=false` halves the payload; these images are never shown. */
    suspend fun getNextUp(baseUrl: String, userToken: String, userId: String): List<JellyfinPlayItem> = runCatching {
        val acc = mutableListOf<JellyfinPlayItem>()
        var startIndex = 0
        while (true) {
            val url = baseUrl.trimEnd('/') +
                "/Shows/NextUp?UserId=$userId&Limit=$JF_PAGE_SIZE&StartIndex=$startIndex&EnableImages=false" +
                "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
            val resp = httpGet(url) { jellyfinAuth(userToken) }.bodyOrNull<JellyfinPlayItemsResponse>("getNextUp") ?: break
            if (resp.items.isEmpty()) break
            acc += resp.items
            startIndex += resp.items.size
            if (startIndex >= resp.totalRecordCount) break
        }
        acc
    }.warnOnFailureOrDefault("getNextUp", emptyList())
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
data class JellyfinDeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    // Phase 224 (FR-224-3): the build the device last reported (R252). Null ⇒ the header carries no
    // Version at all, and Jellyfin keeps whatever it last learned for that device.
    val appVersion: String? = null,
) {
    companion object {
        fun forDevice(device: DeviceData): JellyfinDeviceIdentity =
            JellyfinDeviceIdentity("ravilo-${device.deviceId}-${device.jellyfinUserId}", device.displayName.ifBlank { "Ravilo TV" }, device.appVersion)
    }
}

/** What Jellyfin is told when a Ravilo client signs in without saying which build it is. */
internal const val UNKNOWN_CLIENT_VERSION = "0.0.0"

internal fun headerSafe(s: String): String = s.replace("\"", "'").replace("\n", " ").take(64)

/**
 * Phase 224 (FR-224-3) — the one place a Jellyfin identity becomes header text. A device identity emits
 * `Version` only when the device has reported one (10.11.11 `AuthorizationContext.cs:167-176`: a blank
 * Version leaves the stored value alone, a differing one updates it); no identity ⇒ the server's own.
 */
internal fun jellyfinIdentityHeader(identity: JellyfinDeviceIdentity?, versionRequired: Boolean = false): String {
    if (identity == null) return AUTH_HEADER
    // Phase 224 amendment (2026-09-17) — `AuthenticateByName` is the one call where Version is NOT
    // optional: it opens a NEW session, and 10.11.11 answers **400** to a header without one. A client
    // that sends no X-Ravilo-Version (every build before R252, any script) therefore could not sign in
    // at all, and the route reported the 400 as "Could not reach Jellyfin" (503). Found on production by
    // a dummy-credential probe. An authenticated call may still omit it (FR-224-3 stands).
    val reported = identity.appVersion?.trim()?.ifBlank { null } ?: if (versionRequired) UNKNOWN_CLIENT_VERSION else null
    val version = reported?.let { """, Version="${headerSafe(it)}"""" } ?: ""
    return """MediaBrowser Client="Ravilo", Device="${headerSafe(identity.deviceName)}", DeviceId="${headerSafe(identity.deviceId)}"$version"""
}

/** Canonical authenticated Jellyfin header — one place so no call site can drift (Phase 50). Phase 110:
 *  an [identity] swaps in a per-device Client/Device/DeviceId instead of the shared server identity.
 *  Phase 224 (FR-224-4): a call site that passes no identity still gets the device's own when the token
 *  is a device's — [DeviceIdentityRegistry] — so a device token never travels under `Device="Server"`. */
/** Phase 244 FR-244-1 — RFC 5737 TEST-NET-3. Documentation-only by standard, so the probe cannot be
 *  making a claim about anyone's real network. */
internal const val EXPOSURE_PROBE_ADDRESS = "203.0.113.9"

private fun HttpRequestBuilder.jellyfinAuth(token: String, identity: JellyfinDeviceIdentity? = null) {
    val resolved = identity ?: DeviceIdentityRegistry.identityFor(token)
    header("Authorization", """${jellyfinIdentityHeader(resolved)}, Token="$token"""")
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

/**
 * Phase 205 (FR-205-5) — the `runCatching { … }.let { if (isFailure) Logger.warn(…); getOrDefault(…) }`
 * idiom used across this file catches `CancellationException` the same as any other `Throwable` and
 * defaults instead of rethrowing it. That is not just a misleading log line: when an enclosing
 * `withTimeoutOrNull` cancels the caller (e.g. `buildCanonicalContinueList`'s shared 6s budget across
 * four concurrent fetches), a call using this idiom logged itself as a plain failure — "39 getNextUp
 * failures" in production for a call that measures 1.0s standalone — because the *other* three fetches
 * were slow and the group deadline fired. Swallowing cancellation also means this coroutine keeps
 * running to completion instead of actually stopping, which is what let a "6s budget" cost more than 6s
 * in practice whenever a sibling ignored its own cancellation this way.
 *
 * Scoped here to the four methods [buildCanonicalContinueList] uses concurrently — the ones the
 * investigation actually named — not applied file-wide; the other ~20 methods sharing this same idiom
 * have the identical latent issue and are a generalization candidate for whichever phase next touches
 * this file's error handling (Phase 208's route migration is the nearest candidate).
 */
private suspend fun <T> Result<T>.warnOnFailureOrDefault(context: String, default: T): T {
    exceptionOrNull()?.let { if (it is CancellationException) throw it }
    if (isFailure) Logger.warn("Jellyfin $context failed: ${exceptionOrNull()?.message}")
    return getOrDefault(default)
}

private fun String.jsonEscape(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
