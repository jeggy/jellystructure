package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317, the 2026-10-12 amendment: **when** a second window triple is asked for (FR-317-17), and what
 * the Checks card says while the lane cannot run (FR-317-16) — both against a stand-in sidecar on
 * `127.0.0.1`, because both bugs lived in a condition no pure test could reach. Acceptance 6 hands
 * [AvSyncVerdict] its windows directly, so the one that mattered — three confident windows that disagree
 * asking for nothing, which made a steady drift unreachable for a well-measured file — was invisible.
 *
 * Nothing here decodes anything: the stand-in answers `/health` and `/measure` with chosen numbers, which
 * is also how DEB-722 reached `drift` at all (the test fixtures are faceless by construction).
 */
class AvSyncSecondPassTest {
    private val dir = "/tmp/jellystructure-test-avsync-${getpid()}"

    /** How far a requested window start may sit from the percentage the spec names. A tenth of a second
     *  is the quantization the service applies; a quarter leaves room and is still two orders of
     *  magnitude below the 45 s window. */
    private val windowToleranceS = 0.25

    /** What the stand-in answers for a window starting at this second; absent = a window with no face
     *  track at all (never confident, FR-317-7). */
    private val offsets = AtomicReference<Map<Double, Int>>(emptyMap())

    /** How many `/measure` calls the stand-in has been given, and the window starts of each. */
    private val measures = AtomicInt(0)
    private val asked = AtomicReference<List<List<Double>>>(emptyList())

    /** FR-317-42 — the 1-based `/measure` call from which the stand-in answers 500 (0 = never). */
    private val failFrom = AtomicInt(0)

    /**
     * The table is keyed by the window start the spec names — 35 % of a 90-minute film is 1890 s — but
     * [AvSyncService] quantizes a start **down** to the tenth of a second, and `0.35 * 5400.0` is
     * `1889.99999999999988` in a Double, so that window arrives as `1889.9`. Resolve by nearest key: this
     * test is about which triples are asked for and which windows come back confident, never about the
     * last tenth of a second of a 45 s window.
     */
    private fun Map<Double, Int>.at(start: Double): Int? =
        entries.firstOrNull { abs(it.key - start) <= windowToleranceS }?.value

    /** Window starts, compared with [windowToleranceS] for the same reason [at] exists. */
    private fun assertStarts(expected: List<Double>, actual: List<Double>, message: String = "") {
        assertEquals(expected.size, actual.size, "$message — expected $expected, asked $actual")
        assertTrue(
            expected.zip(actual).all { (e, a) -> abs(e - a) <= windowToleranceS },
            "$message — expected ~$expected, asked $actual",
        )
    }

    private fun record(starts: List<Double>) {
        while (true) {
            val cur = asked.value
            if (asked.compareAndSet(cur, cur + listOf(starts))) return
        }
    }

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                call.respondText(
                    """{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":false}""",
                    ContentType.Application.Json,
                )
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                val body = call.receiveText()
                val starts = Regex("\"start_s\":\\s*([0-9.]+)").findAll(body).map { it.groupValues[1].toDouble() }.toList()
                record(starts)
                val n = measures.incrementAndGet()
                if (failFrom.value in 1..n) {
                    call.respondText("""{"error":"boom"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return@post
                }
                val table = offsets.value
                val windows = starts.joinToString(",") { s ->
                    // conf 3.0 over 800 face frames clears both of FR-317-7's bars; a window with no
                    // offset at all is the "no face track" case and is never confident whatever its conf.
                    """{"start_s":$s,${table.at(s)?.let { "\"offset_frames\":$it," } ?: ""}"conf":3.0,"frames":800,"faces":[]}"""
                }
                call.respondText(
                    """{"windows":[$windows],"others":[],"decode":"cpu","seconds":8.2}""",
                    ContentType.Application.Json,
                )
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    private lateinit var db: JellystructureDb
    private lateinit var configStore: ConfigStore
    private lateinit var store: MediaStore
    private lateinit var integrity: FileIntegrityService
    private lateinit var service: AvSyncService

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(
                configStore.current.copy(
                    avsync = dev.jellystructure.config.AvSyncConfig(enabled = true, url = "http://127.0.0.1:$port", timeoutS = 30),
                ),
            )
        }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        integrity = FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient()))
        service = AvSyncService(db, integrity, configStore, store, null)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    /** A 90-minute film with one audio track, on disk. Its windows land at 1080/2700/4320 s (20/50/80 %)
     *  and the second triple at 1890/3510/4860 s (35/65/90 %) — [AvSyncService]'s own fractions. */
    private fun film(name: String, minutes: Int = 90): MediaItem {
        val path = "$dir/$name.mp4"
        FileIo.writeText(Path(path), "not a real film — only its size and mtime are read here")
        return MediaItem(
            id = name, title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            runtime = minutes,
            tracks = listOf(
                Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false),
                Track(1, "0:a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
            ),
        )
    }

    // ── FR-317-17 — a second triple whenever the first pass has no steady answer ──

    @Test
    fun `three confident windows that disagree ask for a second triple — and six on a line are a drift`() = runBlocking {
        // The signature of a steady drift measured in three windows: all three confident, none agreeing.
        // Before FR-317-17 this requested nothing and came out `unknown` — never counted, never shown.
        offsets.value = mapOf(
            1080.0 to 1, 2700.0 to 4, 4320.0 to 6,          // the first triple, on a line
            1890.0 to 2, 3510.0 to 5, 4860.0 to 7,          // the second, on the same line
        )
        val item = film("drifting")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(2, measures.value, "the second triple must be requested: ${asked.value}")
        assertStarts(listOf(1080.0, 2700.0, 4320.0), asked.value[0], "20/50/80 % of the file")
        assertStarts(listOf(1890.0, 3510.0, 4860.0), asked.value[1], "35/65/90 % of the file, FR-317-8's own")
        // Six confident windows on a line, which is four or more: DRIFT_MIN_WINDOWS is still 4 — it was
        // reached by measuring more of the file, never by lowering the bar.
        assertEquals(AvSyncState.DRIFT, reading.state)
        assertEquals(6, reading.confidentWindows)
        assertTrue(reading.isFinding)
        assertEquals(1080.0, reading.driftFromS)
        assertEquals(4860.0, reading.driftToS)
        assertTrue((reading.driftToMs ?: 0) - (reading.driftFromMs ?: 0) >= AvSyncVerdict.DRIFT_MIN_FRAMES * AvSyncVerdict.MS_PER_FRAME)
    }

    @Test
    fun `a steady first pass asks for nothing more`() = runBlocking {
        offsets.value = mapOf(1080.0 to 4, 2700.0 to 4, 4320.0 to 4)
        val item = film("steady")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(1, measures.value, "an answered file must not pay for a second pass")
        assertEquals(AvSyncState.OFFSET, reading.state)
        assertEquals(200L, reading.offsetMs)   // +4 against the library's zero of −1 = +5 frames
        assertEquals(3, reading.confidentWindows)
    }

    @Test
    fun `a file that stays unknown asks for exactly five triples — and then there is no sixth`() = runBlocking {
        // Six windows that disagree wildly, then nothing: no face track anywhere in passes 3-5 (never confident).
        offsets.value = mapOf(
            1080.0 to 0, 2700.0 to 6, 4320.0 to -4,
            1890.0 to 7, 3510.0 to -5, 4860.0 to 2,
        )
        val item = film("noisy")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(5, measures.value, "FR-317-42: up to five triples, never a sixth: ${asked.value}")
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertEquals(6, reading.confidentWindows)
        assertFalse(reading.isFinding)
        assertTrue(reading.why?.contains("disagree") == true, reading.why)
        // The fifteen windows are the spec's fractions of 5400 s, in the spec's order.
        AvSyncVerdict.PASS_FRACTIONS.forEachIndexed { i, triple ->
            assertStarts(triple.map { it * 5400.0 }, asked.value[i], "pass ${i + 1}")
        }
        val row = assertNotNull(service.rowFor(item.path))
        assertEquals(AvSyncVerdict.PASSES_MAX.toLong(), row.passes_max, "stored with the plan it was measured under")
    }

    @Test
    fun `the escalation stops after the pass that first gives an answer`() = runBlocking {
        // Passes 1 and 2 read nothing; pass 3 (10 / 42.5 / 72.5 %) reads three steady windows.
        offsets.value = mapOf(540.0 to 4, 2295.0 to 4, 3915.0 to 4)
        val item = film("late-answer")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(3, measures.value, "no pass after the one that answered: ${asked.value}")
        assertEquals(AvSyncState.OFFSET, reading.state)
        assertEquals(3, reading.confidentWindows)
    }

    @Test
    fun `an in-sync first pass costs one call`() = runBlocking {
        offsets.value = mapOf(1080.0 to -1, 2700.0 to -1, 4320.0 to -1)
        val item = film("in-step")
        assertEquals(AvSyncState.IN_SYNC, service.check(item, item.path).getOrThrow().state)
        assertEquals(1, measures.value)
    }

    @Test
    fun `a failure on a later pass keeps what came before`() = runBlocking {
        failFrom.value = 3
        val item = film("fails-on-three")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(3, measures.value, "pass 3 was asked for and refused: ${asked.value}")
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        val row = assertNotNull(service.rowFor(item.path), "the six windows of passes 1-2 are stored, not thrown away")
        assertEquals(6, Json.parseToJsonElement(row.windows).jsonArray.size)
        assertEquals(AvSyncVerdict.PASSES_MAX.toLong(), row.passes_max)
    }

    @Test
    fun `on a short file no window is asked for twice — and a pass with nothing left ends the run`() = runBlocking {
        // 6 minutes: pass 1 is 72 / 180 / 288 s; pass 2 would clamp 90 % onto 315 s, 27 s from 288, so it asks
        // for 126 and 234 only; pass 3 has nothing left that is 45 s clear of those five, and the run ends.
        val item = film("short", minutes = 6)
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(2, measures.value, "the third pass has nothing to ask for: ${asked.value}")
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        val starts = asked.value.flatten()
        assertEquals(5, starts.size)
        // Across passes, never within a window's length of one already measured (pass 1's own clamping, which
        // FR-317-8 has always allowed, is not what the rule is about).
        for ((i, later) in asked.value.withIndex()) for (earlier in asked.value.take(i).flatten()) {
            assertTrue(later.all { abs(it - earlier) >= 45.0 }, "$later vs $earlier")
        }
    }

    // ── FR-317-42 — rows measured under the old plan, and FR-317-41's states ──

    private fun storeAs(item: MediaItem, passesMax: Long, windowsJson: String, reason: String? = null) {
        val fresh = assertNotNull(service.rowFor(item.path))
        db.fileAvSyncQueries.put(fresh.path, fresh.size, fresh.mtime, fresh.checked_at, fresh.model, fresh.audio, reason, windowsJson, fresh.others, fresh.mkv_audio_ids, passesMax)
    }

    private fun confident(vararg starts: Double, frames: Int = 0) =
        starts.joinToString(",", "[", "]") { """{"start_s":$it,"offset_frames":$frames,"conf":3.0,"frames":800,"faces":[]}""" }

    @Test
    fun `an unknown row from the two-triple plan is stale — an answered one or a current-plan one is not`() = runBlocking {
        val item = film("old-plan")
        service.check(item, item.path).getOrThrow()          // unknown, stored with the current plan
        assertTrue(item.path in service.rowsCurrentByPath(), "passes_max = 5 with unknown is current")
        assertNotNull(service.readingFor(item.path))

        val noFace = """[{"start_s":1080.0,"conf":3.0,"frames":800,"faces":[]},{"start_s":2700.0,"conf":3.0,"frames":800,"faces":[]}]"""
        storeAs(item, 2, noFace)
        assertFalse(item.path in service.rowsCurrentByPath(), "passes_max = 2 with unknown is stale")
        assertNull(service.readingFor(item.path), "so it reads as no current result, and ↻ is live")

        storeAs(item, 2, confident(1080.0, 2700.0, 4320.0, frames = -1))
        assertTrue(item.path in service.rowsCurrentByPath(), "passes_max = 2 with in_sync is current: no answered file is re-measured")
        assertEquals(AvSyncState.IN_SYNC, assertNotNull(service.readingFor(item.path)).state)

        storeAs(item, 2, "[]", reason = AvSyncReason.ANIMATION)
        assertTrue(item.path in service.rowsCurrentByPath(), "a skip stands whatever its plan")
        storeAs(item, 2, "[]", reason = AvSyncReason.VRAM)
        assertTrue(item.path in service.rowsCurrentByPath(), "a pass-1 failure has no later pass to be missing")
    }

    @Test
    fun `an unreadable file says can't tell and blocks the refresh — a changed file unblocks it`() = runBlocking {
        val item = film("unreadable")
        val config = configStore.current.copy(
            scan = configStore.current.scan.copy(
                pipeline = listOf(PipelineStep(step = "scan_files"), PipelineStep(step = FileCheckSteps.AVSYNC)),
            ),
        )
        val checks = TitleChecks(db, store, null, integrity, null, null, service)
        fun line() = checks.forItem(item, config).steps.single { it.step == FileCheckSteps.AVSYNC }
        assertTrue(service.ready(configStore.current))

        // No row: not yet, and nothing to block.
        assertEquals("unchecked", line().files.single().state)
        assertFalse(line().refreshBlocked)
        assertNull(line().refreshBlockedWhy)

        // Measured and settled, unreadable: can't tell, ↻ blocked with the second sentence. Still partial.
        service.check(item, item.path).getOrThrow()
        val settled = line()
        assertEquals("cant_tell", settled.files.single().state)
        assertTrue(settled.refreshBlocked)
        assertEquals(TitleChecks.AVSYNC_CANT_TELL_ALL, settled.refreshBlockedWhy)
        assertEquals("partial", settled.outcome)

        // The file changes (its size does): no current row, so ↻ is live again.
        FileIo.writeText(Path(item.path), "not a real film — only its size and mtime are read here, and now it is longer")
        val changed = line()
        assertEquals("unchecked", changed.files.single().state)
        assertFalse(changed.refreshBlocked)
    }

    @Test
    fun `an answered file blocks the refresh with the general sentence`() = runBlocking {
        offsets.value = mapOf(1080.0 to -1, 2700.0 to -1, 4320.0 to -1)
        val item = film("answered")
        val config = configStore.current.copy(
            scan = configStore.current.scan.copy(
                pipeline = listOf(PipelineStep(step = "scan_files"), PipelineStep(step = FileCheckSteps.AVSYNC)),
            ),
        )
        assertTrue(service.ready(configStore.current))
        service.check(item, item.path).getOrThrow()
        val step = TitleChecks(db, store, null, integrity, null, null, service).forItem(item, config).steps.single { it.step == FileCheckSteps.AVSYNC }
        assertEquals("clean", step.files.single().state)
        assertTrue(step.refreshBlocked)
        assertEquals(TitleChecks.AVSYNC_MEASURED_ALREADY, step.refreshBlockedWhy)
    }

    @Test
    fun `the refresh block is a rule over every file the step would re-measure`() {
        val inSync = AvSyncVerdict.of((1..4).map { AvSyncWindow(startS = it * 100.0, offsetFrames = -1, conf = 3.0, frames = 800) }, zeroFrames = -1)
        val unreadable = AvSyncVerdict.of(listOf(AvSyncWindow(startS = 100.0, offsetFrames = 1, conf = 1.0, frames = 100)), zeroFrames = -1)
        val skipped = AvSyncVerdict.of(emptyList(), zeroFrames = -1, reason = AvSyncReason.ANIMATION)

        assertNull(TitleChecks.avSyncRefreshBlock(emptyList()), "a title with no files has nothing to block")
        assertEquals(TitleChecks.AVSYNC_CANT_TELL_ALL, TitleChecks.avSyncRefreshBlock(listOf(unreadable, unreadable)))
        assertEquals(TitleChecks.AVSYNC_MEASURED_ALREADY, TitleChecks.avSyncRefreshBlock(listOf(unreadable, inSync)))
        assertEquals(TitleChecks.AVSYNC_MEASURED_ALREADY, TitleChecks.avSyncRefreshBlock(listOf(skipped, unreadable)), "a skip is not can't tell")
        assertNull(TitleChecks.avSyncRefreshBlock(listOf(inSync, null)), "one file without a current reading keeps ↻ live")
    }

    @Test
    fun `too few confident windows still ask for a second triple — the original rule unchanged`() = runBlocking {
        // One confident window in the first triple; the other two had no face track at all.
        offsets.value = mapOf(1080.0 to 4, 1890.0 to 4, 3510.0 to 4, 4860.0 to 4)
        val item = film("faceless")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(2, measures.value)
        assertEquals(AvSyncState.OFFSET, reading.state)
        assertEquals(4, reading.confidentWindows)
    }

    // ── FR-317-16 — the Checks card says *off* wherever the check cannot run ──

    @Test
    fun `the A-V sync line reads off until the lane's own condition is met`() = runBlocking {
        val item = film("checks")
        val config = configStore.current.copy(
            scan = configStore.current.scan.copy(
                pipeline = listOf(PipelineStep(step = "scan_files"), PipelineStep(step = FileCheckSteps.AVSYNC)),
            ),
        )
        val checks = TitleChecks(db, store, null, integrity, null, null, service)
        fun line() = checks.forItem(item, config).steps.single { it.step == FileCheckSteps.AVSYNC }

        // The switch is on, but nothing has asked `/health` since boot: not ready, so acceptance 4's
        // *off* — never *next run*, which invites the operator to wait for a check that cannot happen.
        assertFalse(service.readyNow())
        val before = line()
        assertFalse(before.enabled)
        assertEquals("off", before.nextDue)
        assertEquals(1, before.files.size)
        assertTrue(before.files.all { it.nextDue == "off" }, "${before.files}")

        // The sidecar answers and every library root is visible to it: the same line is on, and the file
        // that has no result reads *next run* again.
        assertTrue(service.ready(configStore.current))
        assertTrue(service.readyNow())
        val after = line()
        assertTrue(after.enabled)
        assertEquals("next run", after.nextDue)
        assertTrue(after.files.all { it.nextDue == "next run" }, "${after.files}")
    }

    @Test
    fun `a readiness answer nobody is refreshing stops counting`() = runBlocking {
        assertTrue(service.ready(configStore.current))
        assertTrue(service.readyNow())
        // The lane's worker re-asks every 30–60 s; an answer this old means it has stopped asking, and
        // the card must not claim a readiness nobody is maintaining.
        assertTrue(service.readyNow(nowSec = service.readyAnsweredAt + AvSyncService.READY_MAX_AGE_S))
        assertFalse(service.readyNow(nowSec = service.readyAnsweredAt + AvSyncService.READY_MAX_AGE_S + 1))
    }

    @Test
    fun `the switch off is not ready — and says so without asking the sidecar`() = runBlocking {
        configStore.update(configStore.current.copy(avsync = configStore.current.avsync.copy(enabled = false)))
        assertFalse(service.ready(configStore.current))
        assertFalse(service.readyNow())
        assertEquals(0, measures.value)
        // FR-317-16's last bullet — the Settings card's own line is the one place that says why nothing
        // is happening, and the server writes it whether the switch is on or off.
        val settings = service.settings(configStore.current)
        assertFalse(settings.enabled)
        assertFalse(settings.reachable)
        assertEquals("Off — nothing is measured and nothing is counted.", settings.status)
    }
}
