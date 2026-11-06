package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.StoredFileChecks
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PlaybackQoeReport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3652 (a-damaged-file-still-plays, DEB-3642 FR-2–FR-5; acceptance 2–5) — a file whose header lists a second set of
 * tracks that hold no packets. Synthetic tracks only: no title, no filename from the library.
 */
class GhostTracksTest {
    private fun track(i: Int, kind: TrackKind, codec: String, lang: String?, channels: Int? = null) =
        Track(streamIndex = i, specifier = "0:$i", kind = kind, codec = codec, language = lang, title = null, default = false, forced = false,
            width = if (kind == TrackKind.VIDEO) 1920 else null, height = if (kind == TrackKind.VIDEO) 1080 else null, videoBitrate = if (kind == TrackKind.VIDEO) 8_000_000 else null,
            durationMs = if (kind == TrackKind.VIDEO) HEADER_MS else null)

    /** DEB-3653 — what the header claims; the video streams carry it, as the scan stores it. */
    private val HEADER_MS = 3_000_000L

    /** User-data writes Jellyfin received (the stop's own `Played` flag is in them). */
    private val userData = mutableListOf<String>()

    /** Video 0, audio 1–2, then a second set the header lists and no packet fills: video 5, audio 6–7. */
    private val tracks = listOf(
        track(0, TrackKind.VIDEO, "h264", null), track(1, TrackKind.AUDIO, "ac3", "eng", 6), track(2, TrackKind.AUDIO, "ac3", "dan", 6),
        track(5, TrackKind.VIDEO, "h264", null), track(6, TrackKind.AUDIO, "ac3", "eng", 6), track(7, TrackKind.AUDIO, "ac3", "dan", 6),
    )
    private val audio = listOf(
        AudioTrack(1, "eng", "English", "ac3", 6, true), AudioTrack(2, "dan", "Dansk", "ac3", 6, false),
        AudioTrack(6, "eng", "English", "ac3", 6, false), AudioTrack(7, "dan", "Dansk", "ac3", 6, false),
    )
    private val empty = setOf(5, 6, 7)

    private fun encoder() = Encoder({ EncoderConfig(enabled = true, workDir = "/tmp/js-encoder-test") }, cardsOverride = { listOf(EncoderCard(0, "Quadro P4000", null, 0)) }, ffmpegOverride = { true })
    private val caps = ClientCapabilities(hlsAdaptive = true, hlsAudioRenditions = true, maxAudioChannels = 6)

    // ── the pure rules ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty carried track is swapped for its twin and a twinless one for the default real track`() {
        assertEquals(AudioPick(2), pickRealAudio(audio, 2, empty))
        val twin = pickRealAudio(audio, 7, empty)
        assertEquals(AudioPick(2, replaced = 7), twin)
        assertEquals("audio 7 holds no packets — carrying 2", twin.logLine)
        // no twin: the same language does not exist among the real tracks
        val noTwin = audio.map { if (it.index == 6) it.copy(language = "fra", codec = "dts") else it }
        assertEquals(AudioPick(1, replaced = 6), pickRealAudio(noTwin, 6, empty))
        // no pick at all, with ghosts present: Jellyfin is not left to choose one
        assertEquals(AudioPick(1), pickRealAudio(audio, null, empty))
        // a file with no ghosts is asked as before
        assertEquals(AudioPick(null), pickRealAudio(audio, null, emptySet()))
        assertNull(pickRealAudio(audio, 2, empty).logLine)
    }

    @Test
    fun `fileAudioOrder matches the lists once the empty streams are out of both and keeps the file's own places`() {
        assertEquals(listOf(0, 1), fileAudioOrder(audio, tracks, empty))
        assertEquals(listOf(0, 1, 2, 3), fileAudioOrder(audio, tracks), "without the empty set the four-track list still matches the four file streams")
    }

    @Test
    fun `header damage is an empty picture or sound stream or damage before the first Cluster and never an empty subtitle`() {
        val withSub = tracks + track(8, TrackKind.SUBTITLE, "subrip", "eng")
        assertTrue(ghostFactsFrom(tracks, empty, false).noDirect)
        assertTrue(ghostFactsFrom(tracks, null, true).noDirect, "damage at pos 72900 with the first Cluster at 80000")
        assertFalse(ghostFactsFrom(tracks, null, false).noDirect, "unchecked: as today")
        assertFalse(ghostFactsFrom(withSub, setOf(8), false).noDirect, "an empty subtitle makes nothing damaged")
        assertEquals(setOf(8), ghostFactsFrom(withSub, setOf(8), false).empty)
        assertFalse(ghostFactsFrom(listOf(track(0, TrackKind.VIDEO, "h264", null), track(1, TrackKind.VIDEO, "mjpeg", null)), setOf(1), false).noDirect, "a cover picture")
    }

    @Test
    fun `the damage position is read from the demuxer's line`() {
        val line = "[matroska,webm] 0x00 at pos 72900 (0x11cc4) invalid as first byte of an EBML number"
        assertEquals(72900L, StoredFileChecks.damagePosition(line))
        assertNull(StoredFileChecks.damagePosition("stream 2 holds no packets"))
        assertNull(StoredFileChecks.damagePosition(null))
        assertEquals("""{"DeviceProfile":{},"MediaSourceId":"x","EnableDirectPlay":false,"EnableDirectStream":false}""", dev.jellystructure.auth.playbackInfoBody("{}", ""","MediaSourceId":"x"""", true))
        assertEquals("""{"DeviceProfile":{}}""", dev.jellystructure.auth.playbackInfoBody("{}", ""))
    }

    // ── acceptance 2 — FR-2 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the encoder maps only the real audio tracks and carries a twin for a ghost`() = runBlocking {
        val (plan, why) = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 6, takeBps = null, noRecord = true, sourceVideoRange = "SDR", emptyStreams = empty)
        assertEquals("ours", why)
        plan!!
        assertEquals(listOf(0, 1), plan.audio.map { it.audioOrder })
        assertEquals(listOf("eng", "dan"), plan.audio.map { it.language })
        assertEquals(listOf(true, false), plan.audio.map { it.default }, "ghost 6 (English) was carried: its twin, stream 1, is the one")
        val cmd = encoderCommand(plan, 0, "/tmp/js-ghost-test")
        assertTrue(" -map 0:a:0 -map 0:a:1" in cmd, cmd)
        assertFalse("0:a:2" in cmd || "0:a:3" in cmd, cmd)
        assertTrue("[0:v:0]" in cmd, "the first video stream holds packets")
    }

    @Test
    fun `a ghost first video stream is never the plan's picture`() = runBlocking {
        val flipped = listOf(
            track(0, TrackKind.VIDEO, "h264", null), track(1, TrackKind.AUDIO, "ac3", "eng", 6), track(2, TrackKind.VIDEO, "h264", null),
        )
        val plan = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, flipped, listOf(AudioTrack(1, "eng", "English", "ac3", 6, true)), 1,
            takeBps = null, noRecord = true, sourceVideoRange = "SDR", emptyStreams = setOf(0)).first!!
        assertEquals(1, plan.source.videoOrder)
        assertTrue("[0:v:1]" in encoderCommand(plan, 0, "/tmp/js-ghost-test"))
    }

    @Test
    fun `a file whose every audio track is a ghost goes to Jellyfin and a file with no ghosts plans as it did`() = runBlocking {
        val none = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "SDR", emptyStreams = setOf(1, 2, 6, 7))
        assertNull(none.first)
        assertTrue("holds no packets" in none.second, none.second)
        val plain = encoder().planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 6, takeBps = null, noRecord = true, sourceVideoRange = "SDR").first!!
        assertEquals(listOf(0, 1, 2, 3), plain.audio.map { it.audioOrder })
    }

    // ── acceptance 3 and 4 — FR-3, FR-4 — and 5 — FR-5 ─────────────────────────────────────────────

    private class FakeChecks(var empty: Set<Int>? = null, var clusterDamage: Boolean = false, var current: Boolean = false, var coverage: CoverageFacts? = null) : PlaybackFileChecks {
        override fun coverage(path: String) = coverage
        val verified = mutableListOf<String>()
        override fun emptyStreams(path: String) = empty
        override fun damagedBeforeFirstCluster(path: String) = clusterDamage
        override fun hasCurrentDeepCheck(path: String) = current
        override fun verifyFirst(path: String) { verified += path }
    }

    private fun rig(name: String, checks: FakeChecks, block: suspend (PlaybackService, List<String>, CoroutineScope) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bodies = mutableListOf<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Film","RunTimeTicks":${90 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false},"MediaStreams":[
                        {"Type":"Video","Index":0,"Codec":"h264"},
                        {"Type":"Audio","Index":1,"Codec":"ac3","Language":"eng","Channels":6,"IsDefault":true},{"Type":"Audio","Index":2,"Codec":"ac3","Language":"dan","Channels":6},
                        {"Type":"Video","Index":5,"Codec":"h264"},
                        {"Type":"Audio","Index":6,"Codec":"ac3","Language":"eng","Channels":6},{"Type":"Audio","Index":7,"Codec":"ac3","Language":"dan","Channels":6}]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    bodies += call.receiveText()
                    call.respondText("""{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":true}],"PlaySessionId":"psid-$id-${call.hashCode()}"}""", ContentType.Application.Json)
                }
                post("/Sessions/Playing") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Progress") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Stopped") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/UserItems/{id}/UserData") { userData += call.receiveText(); call.respondText("{}", ContentType.Application.Json) }
                delete("/Videos/ActiveEncodings") { call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Capabilities/Full") { call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-deb3652-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-deb3652-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-deb3652-$run-tags.json"), configStore)
        mediaStore.addOrUpdate(MediaItem(
            id = "film-ghost", title = "Film", year = 2026, kind = MediaKind.MOVIE, path = "/tmp/film-ghost.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, jellyfinId = "film-ghost", tracks = tracks,
        ))
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }))
        service.fileChecks = checks
        try {
            block(service, bodies, scope)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-deb3652-$run.db")
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    @Test
    fun `a negotiation for a file with ghost tracks asks for a real audio track with direct play and direct stream off`() = rig("negotiate", FakeChecks(empty = empty)) { service, bodies, _ ->
        val tv = device("tv-ghost")
        service.startPlayback(tv, "film-ghost", caps, audioLanguage = "eng")
        assertEquals(1, bodies.size)
        assertTrue(""""AudioStreamIndex":1""" in bodies.single(), bodies.single())
        assertTrue(""""EnableDirectPlay":false,"EnableDirectStream":false""" in bodies.single(), bodies.single())

        service.restream(tv, "film-ghost", -1, 60_000, caps, audioStreamIndex = 7)
        val restream = bodies.last()
        assertTrue(""""AudioStreamIndex":2""" in restream, "7 is a ghost: its twin 2 is asked for — $restream")
        assertTrue(""""EnableDirectPlay":false""" in restream, restream)

        service.restream(tv, "film-ghost", 3, 60_000, caps, audioStreamIndex = null)
        assertTrue(""""AudioStreamIndex":1""" in bodies.last() && """"EnableDirectPlay":false""" in bodies.last(), bodies.last())
    }

    @Test
    fun `a clean or unchecked file is negotiated as today and damage before the first Cluster alone turns direct play off`() {
        rig("unchecked", FakeChecks()) { service, bodies, _ ->
            service.startPlayback(device("tv-unchecked"), "film-ghost", caps, audioLanguage = "eng")
            assertFalse("EnableDirect" in bodies.single(), bodies.single())
            assertFalse("AudioStreamIndex\":6" in bodies.single())
        }
        rig("clean", FakeChecks(empty = emptySet())) { service, bodies, _ ->
            service.startPlayback(device("tv-clean"), "film-ghost", caps)
            assertFalse("EnableDirect" in bodies.single(), bodies.single())
        }
        rig("header", FakeChecks(empty = emptySet(), clusterDamage = true)) { service, bodies, _ ->
            val tv = device("tv-header")
            service.startPlayback(tv, "film-ghost", caps)
            assertTrue(""""EnableDirectPlay":false,"EnableDirectStream":false""" in bodies.single(), bodies.single())
            assertFalse("AudioStreamIndex" in bodies.single(), "no ghost streams: no audio pick is forced")
        }
    }

    @Test
    fun `a start the bounds rescued on an unchecked file puts its verify job first and on a checked file changes nothing`() {
        val unchecked = FakeChecks(current = false)
        rig("rescue", unchecked) { service, _, _ ->
            val tv = device("tv-rescue")
            service.startPlayback(tv, "film-ghost", caps)
            service.recordQoe(tv, PlaybackQoeReport(itemId = "film-ghost", perItem = true, startFailureStatus = -1))
            repeat(50) { if (unchecked.verified.isEmpty()) delay(20) }
            assertEquals(listOf("/tmp/film-ghost.mkv"), unchecked.verified)
            service.encoder.onRefusedFile!!.invoke("/tmp/film-ghost.mkv")   // FR-313-12's refusal comes by the same road
            repeat(50) { if (unchecked.verified.size < 2) delay(20) }
            assertEquals(2, unchecked.verified.size)
        }
        val checked = FakeChecks(current = true)
        rig("rescue-checked", checked) { service, _, _ ->
            val tv = device("tv-rescue2")
            service.startPlayback(tv, "film-ghost", caps)
            service.recordQoe(tv, PlaybackQoeReport(itemId = "film-ghost", perItem = true, startFailureStatus = -1))
            service.encoder.onRefusedFile!!.invoke("/tmp/film-ghost.mkv")
            delay(300)
            assertEquals(emptyList(), checked.verified)
        }
    }
    // ── acceptance 6 — FR-6 (DEB-3653) ─────────────────────────────────────────────────────────────

    private val shortDefault = CoverageFacts(shortAudio = mapOf(1 to 6))

    @Test
    fun `the audio the server picks itself is the complete track when the default is short and a viewer's choice of the short one stands`() {
        val list = listOf(AudioTrack(1, "eng", "English", "ac3", 6, true), AudioTrack(2, "dan", "Dansk", "ac3", 6, false), AudioTrack(6, "eng", "English", "ac3", 6, false))
        assertEquals(6 to 1, avoidShortAudio(list, null, mapOf(1 to 6)))
        assertEquals(1 to null, avoidShortAudio(list, 1, mapOf(1 to 6)), "a choice is always honoured, even a short track")
        assertEquals(null to null, avoidShortAudio(list, null, mapOf(2 to 6)), "the default-ranked track is not the short one")
        assertEquals(null to null, avoidShortAudio(list, null, emptyMap()))
        assertEquals(null to null, avoidShortAudio(list, null, mapOf(1 to 9)), "an alternative the item does not list is not asked for")
        val noDefault = list.map { it.copy(isDefault = false) }
        assertEquals(6 to 1, avoidShortAudio(noDefault, null, mapOf(1 to 6)), "no flagged default: the first track is the default-ranked one")
        val finding = { kind: String, i: Int?, alt: Int? -> dev.jellystructure.media.CoverageFinding(kind, i, "AUDIO", "ac3", "eng", null, "5.1", true, true, 100_000, 2_800_000, 2_900_000, alt) }
        assertEquals(CoverageFacts(mapOf(1 to 6), null), coverageFactsFrom(listOf(finding("A", 1, 6), finding("B", 2, null)), 2_800_000))
        assertNull(coverageFactsFrom(listOf(finding("B", 2, null), finding("C", 3, null)), 2_800_000), "kinds B and C change nothing")
    }

    @Test
    fun `a negotiation with no choice asks for the complete track of a short default and one with a choice asks for that choice`() = rig("short", FakeChecks(empty = emptySet(), coverage = shortDefault)) { service, bodies, _ ->
        val tv = device("tv-short")
        service.startPlayback(tv, "film-ghost", caps)
        assertTrue(""""AudioStreamIndex":6""" in bodies.last(), "stream 1 is short, 6 says the same thing in full — ${bodies.last()}")
        service.restream(tv, "film-ghost", -1, 60_000, caps, audioStreamIndex = 1)
        assertTrue(""""AudioStreamIndex":1""" in bodies.last(), "the viewer picked the short track: ${bodies.last()}")
        service.restream(tv, "film-ghost", -1, 60_000, caps, audioStreamIndex = null)
        assertTrue(""""AudioStreamIndex":6""" in bodies.last(), bodies.last())
    }

    @Test
    fun `a file whose default track is complete or with no coverage row is negotiated as today`() {
        rig("short-none", FakeChecks(empty = emptySet())) { service, bodies, _ ->
            service.startPlayback(device("tv-short-none"), "film-ghost", caps)
            assertFalse("AudioStreamIndex" in bodies.single(), bodies.single())
        }
        rig("short-other", FakeChecks(empty = emptySet(), coverage = CoverageFacts(shortAudio = mapOf(2 to 6)))) { service, bodies, _ ->
            service.startPlayback(device("tv-short-other"), "film-ghost", caps)
            assertFalse("AudioStreamIndex" in bodies.single(), bodies.single())
        }
    }

    // ── acceptance 7 — FR-7 (DEB-3653) ─────────────────────────────────────────────────────────────

    private val contentEnd = 2_873_000L

    @Test
    fun `the content end is only ever a shortening of the claimed length`() {
        assertEquals(contentEnd, contentLimited(HEADER_MS, contentEnd))
        assertEquals(HEADER_MS, contentLimited(HEADER_MS, null))
        assertEquals(HEADER_MS, contentLimited(HEADER_MS, 4_000_000), "a content end past the claim is no shortening")
        assertEquals(null, contentLimited(null, contentEnd))
        val kindE = dev.jellystructure.media.CoverageFinding("E", null, null, null, null, null, null, false, false, contentEnd, contentEnd, HEADER_MS)
        assertEquals(CoverageFacts(emptyMap(), contentEnd), coverageFactsFrom(listOf(kindE), contentEnd))
    }

    @Test
    fun `our encoder's playlist ends at the content end and a seek past it asks for no segment that cannot be written`() = runBlocking {
        val enc = encoder()
        val plan = enc.planFor(caps, "tv", "/m/film.mkv", contentLimited(HEADER_MS, contentEnd), tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "SDR", emptyStreams = empty).first!!
        val count = encoderSegmentCount(contentEnd)
        assertEquals(1437, count)
        val playlist = encoderPlaylist(plan.source.durationMs, plan.mux)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"), playlist)
        assertEquals(count, playlist.lines().count { it.endsWith(".m4s") || it.endsWith(".ts") }, "the last segment before the content end is the last one listed")
        assertEquals("#EXTINF:1.000,", playlist.lines().last { it.startsWith("#EXTINF") }, "the last segment is the 1 000 ms that remain of 2 873 000")
        // a seek to 3 000 000 (the header's end): the stream is registered, the master says where it ends, and no segment past the last real one exists.
        val id = enc.register(plan, "psid-seek", Long.MAX_VALUE / 2, startMs = HEADER_MS)
        assertTrue("#EXT-X-START:TIME-OFFSET=2873.000" in enc.master(id)!!, enc.master(id)!!)
        assertEquals(count - 1, encoderSeekSegment(plan.source.durationMs, HEADER_MS))
        assertEquals(10, encoderSeekSegment(plan.source.durationMs, 20_500))
        assertNull(enc.segment(id, "v", 0, count), "past the last real segment: nothing is asked of ffmpeg")
        assertNull(enc.segment(id, "v", 0, 1500))
        assertTrue(enc.running().isEmpty(), "no job was started")
        assertEquals(Encoder.Miss.UNKNOWN, enc.miss(id, "v", 0, count))
    }

    @Test
    fun `a stop past 90 percent of the content end is reported as played and one before it is not`() {
        val plan = SessionPlan(durationMs = contentLimited(HEADER_MS, contentEnd)!!)
        assertTrue(stopUserData(2_600_000, plan, false).played == true, "2 600 000 is past 90 % of 2 873 000 (2 585 700)")
        assertEquals(0L, stopUserData(2_600_000, plan, false).positionMs, "played: no resume place")
        assertFalse(stopUserData(2_500_000, plan, false).played == true)
        assertFalse(stopUserData(2_600_000, SessionPlan(durationMs = HEADER_MS), false).played == true, "on the claimed length 2 600 000 is 86.7 %")
    }

    @Test
    fun `a play of a file whose header claims too much is reported to Jellyfin as played from a stop past 90 percent of its content`() = rig("content-end", FakeChecks(empty = emptySet(), coverage = CoverageFacts(contentEndMs = contentEnd))) { service, _, _ ->
        val tv = device("tv-content-end")
        service.startPlayback(tv, "film-ghost", caps)
        service.stopPlayback(tv, "film-ghost", 2_600_000)
        repeat(100) { if (userData.none { "\"Played\":true" in it }) delay(50) }
        assertTrue(userData.any { "\"Played\":true" in it }, "user-data writes: $userData")
    }

    @Test
    fun `without a content end the same stop is not a finished play`() = rig("no-content-end", FakeChecks(empty = emptySet())) { service, _, _ ->
        val tv = device("tv-no-content-end")
        service.startPlayback(tv, "film-ghost", caps)
        service.stopPlayback(tv, "film-ghost", 2_600_000)
        delay(1500)
        assertFalse(userData.any { "\"Played\":true" in it }, "user-data writes: $userData")
    }
}
