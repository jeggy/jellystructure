package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LadderRules
import dev.jellystructure.shared.tv.PlaybackQoeReport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 308 (Tests §4, FR-308-1/-2/-5) + 309 (FR-309-2/-4/-5) — **the player-integration tests, the half of them this host
 * can prove.** 308's `VideoLadderTest` checks the composer's arithmetic; these play through the result.
 *
 * Everything on the server's side of the player is the real thing:
 *
 * - a real [PlaybackService] negotiates a real `PlaybackInfo` against a **Jellyfin stand-in over HTTP** that writes
 *   the `#EXT-X-STREAM-INF` line, the media playlist and the segments Jellyfin writes, and records which play
 *   session's job each read would have started;
 * - the master under test is the one [AudioRenditions] composed for that ticket, fetched exactly as
 *   `GET /api/tv/stream/{id}/master.m3u8` fetches it;
 * - every playlist and every segment the player reads is a real HTTP GET, with the relative segment URIs resolved
 *   against the variant playlist's own base, as a player resolves them;
 * - the step-down / climb bound is `shared`'s [LadderRules] — **the same object** Android's
 *   `ClimbingTrackSelection`, the web player and the receiver's `ReceiverAbr` call — and inside that bound the choice
 *   is Media3's own rule with `ClimbingTrackSelection`'s constants (estimate × 0.7, a decrease allowed up to 45 s
 *   buffered, an increase from 10 s, a 50 s buffer goal).
 *
 * One thing is **not** real, deliberately: the clock. A segment's transfer time is computed from the variant's
 * declared `BANDWIDTH` against the test's throttle on a virtual clock, and the bytes on the wire are a token — a real
 * 12 Mbps rung is 9 MB of segment and a 40 Mbps one 30 MB, and a wall-clock throttle on this shared host would make
 * the test both slow and flaky. So these tests prove the ladder, the manifests, the per-rung jobs and the rule;
 * **they do not prove ExoPlayer's or hls.js's own ABR code**, which needs an instrumented device and a browser with
 * H.264 and stays device work (on the TV-and-Cast QA task with 308's acceptance 1–2).
 */
class PlayerLadderIntegrationTest {
    // ── Media3's own numbers, from ClimbingTrackSelection / DefaultLoadControl ──────────────────────────────────
    private val bandwidthFraction = 0.7
    private val maxDecreaseMs = 45_000L
    private val minIncreaseMs = 10_000L
    private val maxBufferMs = 50_000L
    private val bufferForPlaybackMs = 2_500L
    private val segmentMs = 6_000L

    /** meidam's 2026-10-05 film: a 4K DV7 remux at 80.9 Mbps, which the ladder tops at 40 Mbps. */
    private val sourceBps = 80_889_815L

    /** The ladder's `BANDWIDTH` values as the stand-in writes them (`VideoBitrate` + the 256 kbps audio). */
    private val top = 40_256_000L
    private val r1080hi = 12_256_000L
    private val r1080 = 8_256_000L
    private val r720 = 4_256_000L
    private val r480 = 1_756_000L

    // ── The Jellyfin stand-in ──────────────────────────────────────────────────────────────────────────────────

    private class Fake {
        /** Every master GET, by its `PlaySessionId` (the backend composes the ladder from these). */
        val masters = mutableListOf<String>()
        /** Every variant playlist GET, by `PlaySessionId`. */
        val playlists = mutableListOf<String>()
        /** Every segment GET: the variant's play session and the segment index — a job starts at the first of these. */
        val segments = mutableListOf<Pair<String, Int>>()
    }

    private fun rig(name: String, block: suspend (PlaybackService, Fake, String) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = Fake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Item $id","RunTimeTicks":${120 * 60_000L * 10_000},
                        "UserData":{"PlaybackPositionTicks":0,"Played":false},
                        "MediaSources":[${source(id)}],
                        "MediaStreams":[${videoStream()}]}""".trimIndent(), ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"MediaSources":[${source(id)}],"PlaySessionId":"psid-$id"}""", ContentType.Application.Json)
                }
                // Fetching a master or a variant playlist starts no encode (R291, measured); a SEGMENT does.
                get("/videos/{id}/master.m3u8") {
                    val url = call.request.uri
                    fake.masters += queryParam(url, "PlaySessionId") ?: "?"
                    call.respondText(jellyfinMaster(url), ContentType.parse("application/vnd.apple.mpegurl"))
                }
                get("/videos/{id}/main.m3u8") {
                    val url = call.request.uri
                    fake.playlists += queryParam(url, "PlaySessionId") ?: "?"
                    call.respondText(variantPlaylist(url), ContentType.parse("application/vnd.apple.mpegurl"))
                }
                get("/videos/{id}/hls1/main/{seg}.ts") {
                    val url = call.request.uri
                    val seg = call.parameters["seg"]!!.toInt()
                    fake.segments += (queryParam(url, "PlaySessionId") ?: "?") to seg
                    // A token body: the transfer SIZE this test times is the variant's declared bitrate (see the KDoc).
                    call.respondText("segment $seg", ContentType.parse("video/mp2t"))
                }
                post("/Sessions/Playing") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Progress") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Stopped") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/UserItems/{id}/UserData") { call.receiveText(); call.respondText("{}", ContentType.Application.Json) }
                post("/Sessions/Capabilities/Full") { call.respond(HttpStatusCode.NoContent) }
                delete("/Videos/ActiveEncodings") { call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-308-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-308-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-308-$run-tags.json"), configStore)
        mediaStore.addOrUpdate(MediaItem(
            id = ITEM, title = ITEM, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/$ITEM.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), jellyfinId = ITEM,
        ))
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db),
            RaviloDeviceService(db), writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }))
        try {
            block(service, fake, base)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-308-$run.db")
            platform.posix.unlink("/tmp/jellystructure-test-308-$run.toml")
            platform.posix.unlink("/tmp/jellystructure-test-308-$run-tags.json")
        }
    }

    /** The media source Jellyfin negotiates for this device: transcode only, the picture re-encoded. */
    private fun source(id: String): String {
        val transcodingUrl = "/videos/$id/master.m3u8?DeviceId=dev&MediaSourceId=$id&VideoCodec=h264&AudioCodec=aac" +
            "&AudioStreamIndex=1&VideoBitrate=$sourceBps&AudioBitrate=256000&MaxWidth=1920&MaxHeight=1080" +
            "&PlaySessionId=psid-$id&ApiKey=secret&SegmentContainer=ts" +
            "&TranscodeReasons=VideoCodecNotSupported%2CAudioCodecNotSupported"
        return """{"Id":"$id","Container":"mkv","SupportsDirectPlay":false,"SupportsDirectStream":false,""" +
            """"MediaStreams":[${videoStream()}],"TranscodingUrl":"$transcodingUrl"}"""
    }

    private fun videoStream() =
        """{"Type":"Video","Codec":"hevc","BitRate":$sourceBps,"Width":3840,"Height":2160,"VideoRangeType":"DOVIWithEL","Index":0}"""

    /** Jellyfin's own master for one variant request: one `#EXT-X-STREAM-INF` written from that request. */
    private fun jellyfinMaster(url: String): String {
        val vb = queryParam(url, "VideoBitrate")!!.toLong()
        val h = queryParam(url, "MaxHeight")?.toInt() ?: 1080
        val w = queryParam(url, "MaxWidth")?.toInt() ?: 1920
        return """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=${vb + 256_000},AVERAGE-BANDWIDTH=${vb + 256_000},VIDEO-RANGE=SDR,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=${w}x$h,FRAME-RATE=23.976
            main.m3u8?${url.substringAfter('?')}
        """.trimIndent()
    }

    /** One variant's media playlist: the whole film in 6 s segments, its own query on every URI (as Jellyfin writes it). */
    private fun variantPlaylist(url: String): String {
        val query = url.substringAfter('?')
        val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        for (i in 0 until SEGMENT_COUNT) out.append("#EXTINF:6.000000,\nhls1/main/$i.ts?$query\n")
        return out.append("#EXT-X-ENDLIST\n").toString()
    }

    // ── The player ─────────────────────────────────────────────────────────────────────────────────────────────

    private data class Variant(val bandwidthBps: Long, val height: Int?, val url: String)

    /** The variants of a composed master, **in the order it lists them** (FR-309-2: the start rung is first). */
    private fun variantsOf(master: String): List<Variant> {
        val lines = master.lines()
        return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }.map { i ->
            val uri = lines.drop(i + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim()
            val inf = MasterVariant(lines[i], uri)
            Variant(inf.bandwidth, inf.height, uri)
        }
    }

    /**
     * A player of the composed master: it reads the real playlists and segments over HTTP, keeps a buffer and a
     * bandwidth estimate, and chooses its variant with [LadderRules] over Media3's own rule. [throttleAt] is the
     * path's capacity in bits/s at a point on the virtual clock.
     */
    private inner class LadderPlayer(
        val variants: List<Variant>,
        val fetch: suspend (String) -> String?,
        /** The ticket's `measured_bandwidth_bps`, which seeds Media3's and Shaka's meters (FR-309-2). */
        seedBps: Long?,
        /** hls.js and Safari/AVPlayer instead start on the variant the master lists first. */
        val startOnFirstListed: Boolean,
        val throttleAt: (Long) -> Long,
    ) {
        private val rungs = variants.map { it.bandwidthBps }
        private var estimate: Long? = seedBps
        private var current =
            if (startOnFirstListed || seedBps == null) 0
            else rungs.indices.filter { rungs[it] <= (seedBps * bandwidthFraction).roundToLong() }.maxByOrNull { rungs[it] }
                ?: rungs.indices.minByOrNull { rungs[it] }!!

        private var nowMs = 0L
        private var bufferMs = 0L
        private var lastBufferMs: Long? = null
        private var playing = false
        private val playlists = mutableMapOf<Int, List<String>>()

        val visited = mutableListOf(variants[current].bandwidthBps)
        var stepsDown = 0
            private set
        var stepsUp = 0
            private set
        var rebuffers = 0
            private set
        var rebufferMs = 0L
            private set
        var minBufferWhilePlayingMs = Long.MAX_VALUE
            private set
        val startedBps = variants[current].bandwidthBps
        val variantBps get() = variants[current].bandwidthBps
        val variantHeight get() = variants[current].height

        /** Plays [segments] segments from the start, or until the clock passes [untilMs]. */
        suspend fun play(segments: Int, untilMs: Long = Long.MAX_VALUE) {
            var seg = 0
            while (seg < segments && nowMs < untilMs) {
                val v = variants[current]
                val uris = playlists[current] ?: run {
                    val text = assertNotNull(fetch(v.url), "the variant playlist must be served: ${v.url}")
                    text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }.also { playlists[current] = it }
                }
                val segUrl = resolve(uris[seg], v.url)
                assertNotNull(fetch(segUrl), "the segment must be served: $segUrl")

                // The transfer: the variant's own bits over what the path carries now (see the class KDoc).
                val rate = throttleAt(nowMs)
                val transferMs = v.bandwidthBps * segmentMs / rate
                nowMs += transferMs
                if (playing) {
                    if (bufferMs < transferMs) { rebuffers++; rebufferMs += transferMs - bufferMs; bufferMs = 0 } else bufferMs -= transferMs
                }
                bufferMs += segmentMs
                if (!playing && bufferMs >= bufferForPlaybackMs) playing = true
                if (playing) minBufferWhilePlayingMs = minOf(minBufferWhilePlayingMs, bufferMs)
                // DefaultLoadControl's buffer goal: the player stops reading ahead at 50 s and the buffer drains.
                if (bufferMs > maxBufferMs) { nowMs += bufferMs - maxBufferMs; bufferMs = maxBufferMs }
                // The meter: each transfer is one sample, smoothed as Media3's sliding estimate is.
                estimate = estimate?.let { ((it * (1 - METER_ALPHA)) + rate * METER_ALPHA).roundToLong() } ?: rate
                seg++

                val next = chooseVariant()
                lastBufferMs = bufferMs
                if (next != current) {
                    if (rungs[next] < rungs[current]) stepsDown++ else stepsUp++
                    current = next
                    visited += rungs[next]
                }
            }
        }

        /** 309 (FR-309-4/-5) — [LadderRules] bounds the choice; inside the bound it is Media3's own. */
        private fun chooseVariant(): Int {
            val allowed = LadderRules.allowedMaxBps(rungs, rungs[current], bufferMs, lastBufferMs, oursEncoder = false)
            val effective = ((estimate ?: 0L) * bandwidthFraction).roundToLong()
            val cap = minOf(allowed ?: Long.MAX_VALUE, effective)
            val want = rungs.indices.filter { rungs[it] <= cap }.maxByOrNull { rungs[it] }
                ?: rungs.indices.minByOrNull { rungs[it] }!!
            // Media3's two timing knobs: with plenty buffered it does not bother stepping down, and a step up waits.
            if (rungs[want] < rungs[current] && bufferMs > maxDecreaseMs) return current
            if (rungs[want] > rungs[current] && bufferMs < minIncreaseMs) return current
            return want
        }

        private fun resolve(uri: String, playlistUrl: String) =
            if (uri.startsWith("http")) uri else playlistUrl.substringBefore('?').substringBeforeLast('/') + "/" + uri.removePrefix("/")
    }

    // ── The tests ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an adaptive player's ticket is a composed ladder - and both kinds of player start on the rung the server chose`() = rig("start") { service, fake, _ ->
        val device = device("phone")
        val ticket = service.startPlayback(device, ITEM, adaptiveCaps())
        assertFalse(ticket.directPlay, "a DV7 remux on this profile is a transcode")
        assertTrue(ticket.adaptive, "the transcode re-encodes the picture, so it gets a ladder (FR-308-1)")
        assertEquals("jellyfin", ticket.encoder, "this test exercises 308's per-rung Jellyfin ladder, not 313's own encoder")
        val master = assertNotNull(service.audioRenditions.master(streamIdOf(ticket)), "the composed master")
        val variants = variantsOf(master)

        // 308 (FR-308-1) — the whole table, each rung Jellyfin's own line, each under its own play session.
        assertEquals(listOf(r720, top, r1080hi, r1080, r480), variants.map { it.bandwidthBps }, master)
        assertEquals(listOf(720, 1080, 1080, 1080, 480), variants.map { it.height })
        assertEquals(listOf("psid-$ITEM" + "v3", "psid-$ITEM" + "v0", "psid-$ITEM" + "v1", "psid-$ITEM" + "v2", "psid-$ITEM" + "v4"),
            variants.map { queryParam(it.url, "PlaySessionId") })
        assertTrue(variants.all { it.url.startsWith("http://127.0.0.1") }, master)

        // 309 (FR-309-2) — with no stream record the ladder starts at the 4.5 Mbps budget, never the top: the rung
        // the server picked is listed first (for hls.js and AVPlayer) AND seeded into the ticket (for Media3/Shaka),
        // so both kinds of player begin on the same one.
        assertEquals(NO_RECORD_START_STREAM_BPS, ticket.startVariantBps)
        assertEquals((NO_RECORD_START_STREAM_BPS / THROUGHPUT_HEADROOM).toLong(), ticket.measuredBandwidthBps)
        val onFirstListed = player(variants, ticket.measuredBandwidthBps, startOnFirstListed = true) { 50_000_000 }
        val onSeed = player(variants, ticket.measuredBandwidthBps, startOnFirstListed = false) { 50_000_000 }
        assertEquals(r720, onFirstListed.startedBps)
        assertEquals(r720, onSeed.startedBps)

        // Composed once, from one master GET per rung — and not one segment of any rung has been read yet, so
        // Jellyfin has started no encode (the Build notes' mechanism, FR-308-1).
        assertEquals(5, fake.masters.size, fake.masters.toString())
        assertEquals(emptyList(), fake.segments)
    }

    @Test
    fun `under a 5 Mbps throttle the player steps down inside a segment and plays on without a rebuffer`() = rig("down") { service, fake, _ ->
        val device = device("phone-throttled")
        val ticket = service.startPlayback(device, ITEM, adaptiveCaps())
        val variants = variantsOf(assertNotNull(service.audioRenditions.master(streamIdOf(ticket))))
        val p = player(variants, ticket.measuredBandwidthBps, startOnFirstListed = false) { 5_000_000 }
        p.play(segments = 40)

        assertEquals(r720, p.startedBps, "it starts on the server's rung")
        assertEquals(r480, p.variantBps, "0.7 × 5 Mbps carries only the 1.5 Mbps rung, and that is where it settles")
        assertEquals(480, p.variantHeight)
        assertTrue(p.stepsDown >= 1, "it must step down: ${p.visited}")
        assertEquals(0, p.rebuffers, "the whole point of the phase: it steps down instead of stalling (${p.rebufferMs} ms)")
        assertTrue(p.minBufferWhilePlayingMs >= segmentMs, "the buffer never fell below the segment it started on: ${p.minBufferWhilePlayingMs} ms")
        // It stepped down on the first evaluation, before the buffer could run down (the 2026-10-06 Pixel finding).
        assertEquals(listOf(r720, r480), p.visited)

        // 308 (FR-308-1) — only the two rungs it actually read were asked of Jellyfin, each under its own play
        // session, and the rung it switched to was asked for at the segment it was at, not from the start.
        assertEquals(setOf("psid-$ITEM" + "v3", "psid-$ITEM" + "v4"), fake.segments.map { it.first }.toSet())
        assertEquals("psid-$ITEM" + "v3" to 0, fake.segments.first())
        assertEquals(1, fake.segments.first { it.first == "psid-$ITEM" + "v4" }.second,
            "the new rung is read at the segment the play had reached: ${fake.segments.take(4)}")
    }

    @Test
    fun `lifting the throttle climbs back one rung at a time - and only with the buffer in hand`() = rig("up") { service, _, _ ->
        val device = device("phone-freed")
        val ticket = service.startPlayback(device, ITEM, adaptiveCaps())
        val variants = variantsOf(assertNotNull(service.audioRenditions.master(streamIdOf(ticket))))
        // 5 Mbps for the first minute of the play, then the throttle comes off.
        val p = player(variants, ticket.measuredBandwidthBps, startOnFirstListed = false) { t -> if (t < 60_000) 5_000_000 else 50_000_000 }
        p.play(segments = 120)

        assertEquals(r1080hi, p.variantBps, "0.7 × 50 Mbps reaches the 12 Mbps rung, never the 40 Mbps top: ${p.visited}")
        assertEquals(1080, p.variantHeight)
        assertTrue(p.stepsUp >= 3, "it climbed back: ${p.visited}")
        assertEquals(0, p.rebuffers)
        // 309 (FR-309-4) — never more than one rung per switch, in either direction.
        val sorted = variants.map { it.bandwidthBps }.sorted()
        for ((from, to) in p.visited.zipWithNext()) {
            val gap = sorted.indexOf(to) - sorted.indexOf(from)
            assertTrue(gap == 1 || gap == -1, "a switch skipped a rung: $from → $to in ${p.visited}")
        }
    }

    @Test
    fun `the switches the player counted come back as QoE and become the variant Playing now shows`() = rig("qoe") { service, _, _ ->
        val device = device("phone-qoe")
        val ticket = service.startPlayback(device, ITEM, adaptiveCaps())
        val variants = variantsOf(assertNotNull(service.audioRenditions.master(streamIdOf(ticket))))
        val p = player(variants, ticket.measuredBandwidthBps, startOnFirstListed = false) { t -> if (t < 60_000) 5_000_000 else 50_000_000 }
        p.play(segments = 120)
        assertTrue(p.stepsDown >= 1 && p.stepsUp >= 1, "the report is only interesting with switches in it: ${p.visited}")

        // 308 (FR-308-5) — the player posts what it did; *Playing now* (304) reads the variant off it.
        service.recordQoe(device, PlaybackQoeReport(
            itemId = ITEM, variantSwitchesDown = p.stepsDown, variantSwitchesUp = p.stepsUp,
            variantBandwidthBps = p.variantBps, variantHeight = p.variantHeight, startVariantBps = p.startedBps,
            rebufferCount = p.rebuffers, rebufferMs = p.rebufferMs, directPlay = false,
        ))
        val now = assertNotNull(service.variantOf(device.deviceId, ITEM), "the admin's *Playing now* must have the variant")
        assertEquals(r1080hi, now.bandwidthBps)
        assertEquals(1080, now.height)
        assertEquals(p.stepsDown, now.stepsDown)
        assertEquals(p.stepsUp, now.stepsUp)
        assertEquals(r720, now.startBps, "309 (FR-309-11) — and the rung the play started on")
    }

    @Test
    fun `a player that does not adapt is given one stream and no ladder`() = rig("noadapt") { service, fake, _ ->
        val device = device("mpv")
        val ticket = service.startPlayback(device, ITEM, adaptiveCaps().copy(hlsAdaptive = false))
        assertFalse(ticket.adaptive, "FR-308-2: mpv cannot switch variants, so it keeps one stream")
        assertEquals(null, ticket.startVariantBps)
        assertTrue(ticket.hlsUrl!!.startsWith("http"), "its ticket keeps Jellyfin's own master: ${ticket.hlsUrl}")
        assertEquals(emptyList(), fake.masters, "and nothing composed a ladder behind its back")
    }

    // ── Plumbing ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    /** A player that adapts and takes no audio renditions (a Chromecast receiver, the web app): the ladder alone. */
    private fun adaptiveCaps() = ClientCapabilities(
        containers = listOf("mp4"), videoCodecs = listOf("h264"), audioCodecs = listOf("aac"),
        hlsOnly = true, hlsAdaptive = true, hlsAudioRenditions = false,
    )

    private fun streamIdOf(ticket: dev.jellystructure.shared.tv.StreamTicket) =
        assertNotNull(ticket.hlsUrl, "an adaptive ticket points at the composed master").removePrefix("/api/tv/stream/").removeSuffix("/master.m3u8")

    private fun player(variants: List<Variant>, seedBps: Long?, startOnFirstListed: Boolean, throttleAt: (Long) -> Long) =
        LadderPlayer(variants, { url -> JellyfinClient().fetchPlaylist(url) }, seedBps, startOnFirstListed, throttleAt)

    private companion object {
        const val ITEM = "ladder-film"
        const val SEGMENT_COUNT = 600          // a 60-minute film in 6 s segments
        /** The weight one transfer carries in the player's estimate (Media3 keeps a sliding percentile; this is the
         *  same shape with one number, and the tests never lean on its exact value). */
        const val METER_ALPHA = 0.5
    }
}
