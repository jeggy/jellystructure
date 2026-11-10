package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4062 / DEB-4256 (DEB-3995 FR-4, FR-6) — a smaller copy is a download stream of our encoder: its options per source size and codec
 * family, one rung and one audio in its master, no play session behind it, and reaped when nobody fetches a segment of it. Since DEB-4256
 * it has a play's priority: admitted by a play's budget on the card plays use last, one per phone with no server-wide limit, ended by no
 * play; it waits only when every card is full (`encoder_full`, with the count of streams) or the copy cannot be made (`unavailable`).
 */
class DownloadStreamTest {
    private val hevcPhone = ClientCapabilities(hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, maxAudioChannels = 6)
    private val h264Phone = ClientCapabilities(videoCodecs = listOf("h264"), maxAudioChannels = 6)
    private val cards = listOf(EncoderCard(0, "Quadro P4000", null, 0))
    /** Two cards in a play's order: a play goes to card 0 first, a download stream to card 1 first. */
    private val twoCards = listOf(EncoderCard(0, "Quadro P4000", null, 0), EncoderCard(1, "Quadro P2000", null, 0))
    /** A GeForce with every NVENC session taken: no card can take one more rung. */
    private val fullCards = listOf(EncoderCard(0, "GeForce GTX 1070", 8, 8))

    private fun track(codec: String, w: Int, h: Int, bps: Int) =
        Track(0, "v:0", TrackKind.VIDEO, codec, null, null, true, false, width = w, height = h, videoRange = "SDR", videoBitrate = bps, durationMs = 6_000_000)
    private fun tracksOf(video: Track) = listOf(video, Track(1, "a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false))
    private val audio = listOf(AudioTrack(1, "eng", "English", "eac3", 6, true))

    // ── the policy, against the encoder directly (no ffmpeg runs) ────────────────────────────────────────────────

    private var now = 1_000_000L
    private fun encoder(cards: List<EncoderCard> = this.cards) = Encoder({ EncoderConfig(enabled = true, workDir = "/tmp/js-test-4062-${getpid()}", ffmpegDir = "/nonexistent") },
        cardsOverride = { cards }, ffmpegOverride = { true }, clock = { now })

    /** A download stream's plan, as [PlaybackService.downloadPlanFor] asks for it: one rung in [box], the cards tried last first. */
    private suspend fun downloadPlan(enc: Encoder, box: Int = 720): Pair<EncoderPlan?, String> =
        enc.planFor(downloadCaps(hevcPhone), "phone", "/m/film.mkv", 6_000_000, tracksOf(track("hevc", 3840, 2160, 40_000_000)), audio, 1,
            takeBps = null, noRecord = false, sourceVideoRange = null, boxLimit = box, cardsLastFirst = true)

    private suspend fun planOf(enc: Encoder, box: Int = 720): EncoderPlan = downloadPlan(enc, box).first!!

    @Test fun `a copy's plan is one rung of its box with one audio and no subtitles and its master says so`() = runBlocking {
        val enc = encoder()
        val plan = planOf(enc, 720)
        assertEquals(1, plan.rungs.size)
        assertEquals(720, plan.rungs[0].boxHeight)
        assertEquals(1, plan.audio.size)
        assertTrue(plan.subtitles.isEmpty())
        val rung = downloadRung(hevcPhone, plan.source, 720)!!.second
        assertEquals(rung, plan.rungs[0], "the options estimate the rung start encodes")
        val id = enc.registerDownload(plan, "phone-a", "film", now + 4 * 3_600_000L)
        assertTrue(id.startsWith("dl-"))
        val master = enc.master(id)!!
        assertEquals(1, master.lines().count { it.startsWith("#EXT-X-STREAM-INF:") })
        assertEquals(1, master.lines().count { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") })
        assertTrue("RESOLUTION=1280x720" in master)
        assertFalse("EXT-X-START" in master, "a download saves the whole file")
        assertEquals(setOf(720), setOf(plan.rungs[0].boxHeight))
    }

    @Test fun `the rung of each copy per source size and codec family`() {
        fun src(w: Int, h: Int, bps: Long) = EncoderSource("/m/f.mkv", 6_000_000, "hevc", w, h, bps, hdr = false)
        // A 4K file: all three, at the family's rung (HEVC 8/4/1.5 Mbps, H.264 12/6/2).
        assertEquals(listOf(8_000_000L, 4_000_000L, 1_500_000L), DOWNLOAD_COPIES.map { downloadRung(hevcPhone, src(3840, 2160, 40_000_000), it.second)!!.second.videoBps })
        assertEquals(listOf(12_000_000L, 6_000_000L, 2_000_000L), DOWNLOAD_COPIES.map { downloadRung(h264Phone, src(3840, 2160, 40_000_000), it.second)!!.second.videoBps })
        assertEquals(EncoderCodec.HEVC, downloadRung(hevcPhone, src(3840, 2160, 40_000_000), 1080)!!.first)
        assertEquals(EncoderCodec.H264, downloadRung(h264Phone, src(3840, 2160, 40_000_000), 1080)!!.first)
        // A 720p file is not tall enough for a 1080p copy; a 480p one for a 720p copy.
        assertNull(downloadRung(hevcPhone, src(1280, 720, 4_000_000), 1080))
        assertEquals(720, downloadRung(hevcPhone, src(1280, 720, 4_000_000), 720)!!.second.boxHeight)
        assertNull(downloadRung(hevcPhone, src(854, 480, 1_500_000), 720))
        // A scope 1080p file fills 1080p's width, a copy never exceeds the file's own bitrate.
        assertEquals(1080, downloadRung(hevcPhone, src(1920, 800, 3_000_000), 1080)!!.second.boxHeight)
        assertEquals(3_000_000L, downloadRung(hevcPhone, src(1920, 1080, 3_000_000), 1080)!!.second.videoBps)
        // The phone's own decode ceiling holds a copy under 0.9 of it.
        assertEquals(3_600_000L, downloadRung(hevcPhone.copy(maxHevcBitrate = 4_000_000), src(3840, 2160, 40_000_000), 1080)!!.second.videoBps)
    }

    @Test fun `one download stream per phone — the same phone replaces its own and another phone gets its own beside it`() = runBlocking {
        val enc = encoder()
        val streams = DownloadStreams(enc, clock = { now })
        val plan = planOf(enc)
        val a = streams.open(plan, "phone-a", "film", now + 1000, 0)
        assertEquals(listOf(a), enc.downloadStreams().map { it.id })
        // Another phone (DEB-4256): its own stream, beside the first — there is no server-wide limit.
        val b = streams.open(plan, "phone-b", "film-2", now + 1000, 0)
        assertNotEquals(a, b)
        assertEquals(setOf(a, b), enc.downloadStreams().map { it.id }.toSet())
        // The same phone asking again (a resume, or its next download): its old stream goes, a fresh one is there, the other phone's stays.
        val a2 = streams.open(plan, "phone-a", "film", now + 1000, 150)
        assertNotEquals(a, a2)
        assertEquals(setOf(a2, b), enc.downloadStreams().map { it.id }.toSet())
        assertTrue(streams.holds(a), "the replaced one answers waiting, not 404")
        assertFalse(streams.holds(a2))
        assertFalse(streams.holds(b))
    }

    @Test fun `a download stream is no play — no play's stop ends it it has no session and no device`() = runBlocking {
        val enc = encoder()
        val streams = DownloadStreams(enc, clock = { now })
        val id = streams.open(planOf(enc), "phone-a", "film", now + 1000, 0)
        assertEquals(0, enc.stopFor("phone-a-film"), "the play session id of the same phone and item")
        assertEquals(0, enc.stopFor(id))
        assertEquals(0, enc.stopFor("download:$id"))
        assertFalse(enc.serves(id))
        assertEquals(listOf(id), enc.downloadStreams().map { it.id })
        assertNull(enc.running().firstOrNull { !isDownloadStreamId(it.streamId) })
    }

    @Test fun `a dl- stream that is not running answers waiting never 404 and a play's stream is none of its business`() = runBlocking {
        val enc = encoder()
        val streams = DownloadStreams(enc, clock = { now })
        val id = streams.open(planOf(enc), "phone-a", "film", now + 1000, 0)
        assertFalse(streams.holds(id), "running: served")
        assertTrue(enc.endDownload(id, "test"))
        assertTrue(streams.holds(id), "ended: waiting")
        assertTrue(streams.holds("dl-never-existed"), "lost to a restart: waiting")
        assertFalse(streams.holds("a-play-stream-id"), "a play's own stream is none of its business")
    }

    @Test fun `the card plays use last — a download's plan goes to the last card a play's to the first`() = runBlocking {
        val enc = encoder(twoCards)
        assertEquals(1, planOf(enc).cudaDevice, "a download stream tries the cards in the reverse of a play's order")
        val play = enc.planFor(hevcPhone, "tv", "/m/film.mkv", 6_000_000, tracksOf(track("hevc", 3840, 2160, 40_000_000)), audio, 1,
            takeBps = null, noRecord = false, sourceVideoRange = null).first!!
        assertEquals(0, play.cudaDevice, "a play's order is the cards' own")
    }

    @Test fun `every card full — a download's plan says no slot is free and the count is what the encoder runs for others`() = runBlocking {
        val enc = encoder(fullCards)
        val (plan, why) = downloadPlan(enc)
        assertNull(plan)
        assertTrue(why.startsWith("no encoder slot free"), why)
        assertEquals(0, enc.streamsBusy(exceptDownloadOwner = "phone-a"), "nothing runs here (no ffmpeg): the count is honest")
        // Room on a card: the same plan is admitted, whether or not anyone plays — nothing asks.
        assertNotNull(downloadPlan(encoder()).first, "a card with room admits the plan")
        Unit
    }

    @Test fun `the reaper ends a stream nobody fetched a segment of and a fetch keeps it`() = runBlocking {
        val enc = encoder()
        val streams = DownloadStreams(enc, abandonedMs = 10 * 60_000L, clock = { now })
        val plan = planOf(enc)
        val id = streams.open(plan, "phone-a", "film", now + 4 * 3_600_000L, 0)
        now += 9 * 60_000L
        assertEquals(0, streams.reap())
        // A segment request (the job cannot start here: there is no ffmpeg) still counts as a read.
        enc.segment(id, "v", 0, 3)
        now += 9 * 60_000L
        assertEquals(0, streams.reap(), "9 minutes since the read")
        assertEquals(listOf(id), enc.downloadStreams().map { it.id })
        now += 2 * 60_000L
        assertEquals(1, streams.reap())
        assertTrue(enc.downloadStreams().isEmpty())
        assertTrue(streams.holds(id))
        assertEquals(0, streams.reap())
    }

    // ── the options and the ticket, end to end against a fake Jellyfin ───────────────────────────────────────────

    private class Spec(val codec: String, val w: Int, val h: Int, val bps: Int, val size: Long)
    private val catalog = mapOf(
        "film-4k" to Spec("hevc", 3840, 2160, 40_000_000, 30_000_000_000L),
        "film-720" to Spec("hevc", 1280, 720, 4_000_000, 3_200_000_000L),
        "film-low" to Spec("hevc", 1920, 1080, 3_000_000, 2_400_000_000L),
        "film-vc1" to Spec("vc1", 1920, 1080, 20_000_000, 20_000_000_000L),
    )

    private class Rig(val service: DownloadService, val playback: PlaybackService, val qoe: PlaybackQoeStore, val config: ConfigStore, val on: DeviceData, val other: DeviceData)

    private fun rig(name: String, block: suspend (Rig) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"viewer","Policy":{"IsAdministrator":false}}""", ContentType.Application.Json)
                }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    val s = catalog[id] ?: catalog.getValue("film-4k")
                    val streams = """[{"Type":"Video","Index":0,"Codec":"${s.codec}","Width":${s.w},"Height":${s.h},"BitRate":${s.bps}},
                        {"Type":"Audio","Index":1,"Codec":"eac3","Language":"eng","Channels":6,"IsDefault":true}]"""
                    call.respondText(
                        """{"Id":"$id","Name":"Title $id","RunTimeTicks":${100 * 60_000L * 10_000},"MediaStreams":$streams,
                            "MediaSources":[{"Id":"src-$id","Container":"mkv","Size":${s.size},"MediaStreams":$streams}]}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-deb4062-$run.toml")
        configStore.update(configStore.current.copy(
            apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run"),
            encoder = EncoderConfig(enabled = true, workDir = "/tmp/js-test-4062-run-${getpid()}", ffmpegDir = "/nonexistent"),
        ))
        val dbPath = "/tmp/jellystructure-test-deb4062-$run.db"
        val db = createDatabase(dbPath)
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-deb4062-$run-tags.json"), configStore)
        for ((id, s) in catalog) mediaStore.addOrUpdate(MediaItem(
            id = id, title = id, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/$id.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            tracks = tracksOf(track(s.codec, s.w, s.h, s.bps)), jellyfinId = id,
        ))
        val detail = DetailService(mediaStore, JellyfinClient(), configStore, ArtworkDownloader(TmdbClient(configStore), Screengrabber()), MediaSegmentStore(db), dev.jellystructure.tv.RaviloDeviceService(db), PlaybackStartSampleStore(db))
        val qoe = PlaybackQoeStore(db)
        val playback = PlaybackService(mediaStore, JellyfinClient(), configStore, qoe, PlaybackStartSampleStore(db), RaviloDeviceService(db))
        playback.encoder.testCards = cards
        playback.encoder.testFfmpeg = true
        val service = DownloadService(mediaStore, JellyfinClient(), configStore, detail, playback)
        try {
            block(Rig(service, playback, qoe, configStore, device("on"), device("on2")))
        } finally {
            server.stop(100, 500)
            for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false, kind = "phone",
    )

    private fun <T> DownloadOutcome<T>.ok(): T = (this as? DownloadOutcome.Ok)?.value ?: error("expected an answer, got $this")

    @Test fun `options list the copies a file is clearly smaller as per source size and codec family`() = rig("options") { r ->
        suspend fun qualities(item: String, caps: ClientCapabilities) = r.service.options(r.on, item, null, caps).ok().options.map { it.quality }

        // 4K HEVC, 30 GB: all three for an HEVC phone, at (video + audio) × runtime — 6000 s, 384 kb/s of AAC 5.1.
        val k = r.service.options(r.on, "film-4k", null, hevcPhone).ok().options
        assertEquals(listOf("original", "high", "medium", "small"), k.map { it.quality })
        assertEquals(listOf(true, false, false, false), k.map { it.exact })
        assertEquals(listOf(2160, 1080, 720, 480), k.map { it.height })
        assertEquals(listOf(30_000_000_000L, 6_288_000_000L, 3_288_000_000L, 1_413_000_000L), k.map { it.bytes })
        // The same file for an H.264-only phone: the H.264 family's rungs, so larger copies; the original is not decodable there? (hevc) — it is not listed.
        val h = r.service.options(r.on, "film-4k", null, h264Phone).ok().options
        assertEquals(listOf("high", "medium", "small"), h.map { it.quality })
        assertEquals(listOf(9_288_000_000L, 4_788_000_000L, 1_788_000_000L), h.map { it.bytes })
        // A 720p, 4 Mbps, 3.2 GB file: no 1080p copy (not that tall), no 720p copy (not clearly smaller), a 480p one.
        assertEquals(listOf("original", "small"), qualities("film-720", hevcPhone))
        // A 1080p, 3 Mbps, 2.4 GB file: a copy at its own bitrate is not clearly smaller; only 480p is.
        assertEquals(listOf("original", "small"), qualities("film-low", hevcPhone))
        // A codec the GPU does not decode is never ours: no copy (and the original is not decodable by this phone).
        assertEquals(emptyList(), qualities("film-vc1", hevcPhone))
    }

    @Test fun `no copy is offered when the encoder is off or has no GPU and a start for one is unavailable not refused`() = rig("no-gpu") { r ->
        r.playback.encoder.testCards = emptyList()
        assertEquals(listOf("original"), r.service.options(r.on, "film-4k", null, hevcPhone).ok().options.map { it.quality })
        // DEB-4256 — a copy that cannot be made right now is the `unavailable` wait (503), which the phone keeps asking; it was a 400 before.
        val noGpu = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "medium", capabilities = hevcPhone))
        assertTrue(noGpu is DownloadOutcome.Unavailable, noGpu.toString())
        assertEquals(HttpStatusCode.ServiceUnavailable, noGpu.httpStatus())
        r.playback.encoder.testCards = cards
        r.config.update(r.config.current.copy(encoder = r.config.current.encoder.copy(enabled = false)))
        val off = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "medium", capabilities = hevcPhone))
        assertTrue(off is DownloadOutcome.Unavailable, off.toString())
        assertEquals("encoder off", (off as DownloadOutcome.Unavailable).reason)
        // A quality the server never offers is still refused outright (400): the phone does not ask again for it.
        assertEquals(HttpStatusCode.BadRequest, r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "tiny", capabilities = hevcPhone)).httpStatus())
    }

    @Test fun `start answers an hls ticket whose master has one rung and one audio and it is no play`() = rig("start") { r ->
        val t = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "medium", capabilities = hevcPhone)).ok()
        assertEquals("hls", t.kind)
        assertEquals("medium", t.quality)
        assertEquals(3_288_000_000L, t.bytes)
        assertTrue(Regex("^/api/tv/stream/dl-[0-9a-f]+/master\\.m3u8$").matches(t.url), t.url)
        assertEquals("movie", t.itemKind)
        assertNotNull(t.artwork.posterUrl)
        val id = t.url.removePrefix("/api/tv/stream/").substringBefore('/')
        val master = r.playback.encoder.master(id)!!
        assertEquals(1, master.lines().count { it.startsWith("#EXT-X-STREAM-INF:") })
        assertTrue("RESOLUTION=1280x720" in master)
        // Not a play: no tracker entry, no QoE row, no play session behind the stream.
        assertNull(nowPlayingItem(r.on.deviceId))
        assertTrue(r.qoe.recentForDevice(r.on.deviceId, 10).isEmpty())
        assertFalse(r.playback.encoder.serves(id))
        assertEquals(1, r.playback.encoder.downloadStreams().size)
    }

    @Test fun `a second start by the same phone replaces its stream and two phones hold a stream each — an offered copy only`() = rig("one") { r ->
        val a = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone)).ok()
        val again = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone, fromSegment = 150)).ok()
        assertNotEquals(a.url, again.url)
        assertEquals(1, r.playback.encoder.downloadStreams().size)
        assertTrue(r.playback.downloads.holds(a.url.removePrefix("/api/tv/stream/").substringBefore('/')))
        // DEB-4256 — another phone's copy starts beside it: 200, two streams, one per phone.
        val other = r.service.start(r.other, DownloadStartRequest(itemId = "film-low", quality = "small", capabilities = hevcPhone)).ok()
        assertTrue(other.url.startsWith("/api/tv/stream/dl-"), other.url)
        assertEquals(2, r.playback.encoder.downloadStreams().size)
        assertEquals(setOf(r.on.deviceId, r.other.deviceId), r.playback.encoder.downloadStreams().map { it.ownerDeviceId }.toSet())
        // A quality the file is not clearly smaller at is refused, not queued.
        assertEquals(HttpStatusCode.BadRequest, r.service.start(r.on, DownloadStartRequest(itemId = "film-720", quality = "high", capabilities = hevcPhone)).httpStatus())
    }

    @Test fun `a play's start a live play and a restream leave the download stream running and a start during a play answers 200`() = rig("priority") { r ->
        val t = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone)).ok()
        val id = t.url.removePrefix("/api/tv/stream/").substringBefore('/')
        assertEquals(1, r.playback.encoder.downloadStreams().size)
        // DEB-4256 (FR-6) — a play's start (the fake Jellyfin answers it as a direct play) ends nothing.
        r.playback.startPlayback(r.other, "film-720", hevcPhone)
        assertEquals(listOf(id), r.playback.encoder.downloadStreams().map { it.id }, "the play's start left the download stream running")
        assertFalse(r.playback.downloads.holds(id), "its segments are served")
        // A play live on the tracker (a progress report, a paused play): nothing asks, so a start during it is admitted when a card has room.
        r.playback.playingProbe = { true }
        val during = r.service.start(r.other, DownloadStartRequest(itemId = "film-low", quality = "small", capabilities = hevcPhone)).ok()
        assertTrue(during.url.startsWith("/api/tv/stream/dl-"), during.url)
        assertEquals(2, r.playback.encoder.downloadStreams().size)
        assertFalse(r.playback.downloads.holds(id), "a request finding a play live ends nothing")
        // A play's restream ends nothing either.
        runCatching { r.playback.restream(r.other, "film-720", -1, 0L, hevcPhone) }
        assertEquals(2, r.playback.encoder.downloadStreams().size)
    }

    @Test fun `every card full answers waiting with encoder_full's count and the encoder off unavailable — neither names the title or the file`() = rig("full") { r ->
        r.playback.encoder.testCards = fullCards
        val full = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone))
        assertTrue(full is DownloadOutcome.Waiting, full.toString())
        assertEquals(HttpStatusCode.ServiceUnavailable, full.httpStatus())
        assertEquals(0, (full as DownloadOutcome.Waiting).streams, "no job runs here (no ffmpeg)")
        assertTrue(r.playback.encoder.downloadStreams().isEmpty(), "a wait registers nothing")
        r.playback.encoder.testCards = emptyList()
        val off = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone))
        assertTrue(off is DownloadOutcome.Unavailable, off.toString())
        // Acceptance 24 — no reason names what someone owns: not the item, not its file, not its path, not the viewer or the device.
        for (reason in listOf(full.reason, (off as DownloadOutcome.Unavailable).reason)) {
            for (name in listOf("film-4k", "/tmp", ".mkv", "Title", r.on.deviceId, r.on.jellyfinUsername)) assertFalse(name in reason, "'$name' in '$reason'")
        }
        // A card with room again: the same start is admitted at once, from the segment the phone reached.
        r.playback.encoder.testCards = cards
        val resumed = r.service.start(r.on, DownloadStartRequest(itemId = "film-4k", quality = "small", capabilities = hevcPhone, fromSegment = 150)).ok()
        assertTrue(resumed.url.startsWith("/api/tv/stream/dl-"), resumed.url)
    }
}
