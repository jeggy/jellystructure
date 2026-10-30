package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3066 (Phase 147 D1 as amended by DEB-3067; radio spec FR-9/FR-10). A fake Jellyfin answers `/LiveTv/Channels` with an
 * embedded `CurrentProgram` and `/LiveTv/Programs`; the service's `clock` moves instead of the test waiting an hour.
 */
class LiveTvNowNextTest {
    private val run = getpid().toString()
    private val dir = "/tmp/jellystructure-test-nownext-$run"
    private val cfgPath = "$dir.toml"
    private val storePath = "$dir-store.json"

    @AfterTest fun tearDown() {
        for (f in listOf(cfgPath, storePath)) runCatching { platform.posix.remove(f) }
    }

    private val t0 = 1_800_000_000_000L   // an arbitrary "now"
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun iso(ms: Long): String {
        val s = ms / 1000; val days = (s / 86_400).toInt(); val rem = (s % 86_400).toInt()
        // civil-from-days (Howard Hinnant)
        val z = days + 719_468; val era = z / 146_097; val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1; val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${y}-${p(m)}-${p(d)}T${p(rem / 3600)}:${p(rem % 3600 / 60)}:${p(rem % 60)}Z"
    }

    private fun program(name: String, start: Long, end: Long, ch: String? = null) =
        """{"Name":"$name",${ch?.let { "\"ChannelId\":\"$it\"," } ?: ""}"StartDate":"${iso(start)}","EndDate":"${iso(end)}"}"""

    private class Fake {
        var current = mutableMapOf<String, String?>()      // channel id → CurrentProgram JSON (null = none)
        var programs = mutableListOf<Pair<String, String>>() // channel id → program JSON, for /LiveTv/Programs
        var channelsDown = false
        var programsDown = false
        var channelCalls = 0
        var programCalls = 0
    }

    private fun harness(body: suspend (Fake, LiveTvService, MutableClock) -> Unit) = runBlocking {
        val fake = Fake()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/LiveTv/Channels") {
                    fake.channelCalls++
                    if (fake.channelsDown) return@get call.respond(HttpStatusCode.InternalServerError)
                    val items = fake.current.entries.joinToString(",") { (id, p) ->
                        """{"Id":"$id","Name":"$id","ChannelType":"Radio"${p?.let { ""","CurrentProgram":$it""" } ?: ""}}"""
                    }
                    call.respondText("""{"Items":[$items]}""", ContentType.Application.Json)
                }
                get("/LiveTv/Programs") {
                    fake.programCalls++
                    if (fake.programsDown) return@get call.respond(HttpStatusCode.InternalServerError)
                    val ids = call.request.queryParameters["ChannelIds"].orEmpty().split(",")
                    val items = fake.programs.filter { it.first in ids }.joinToString(",") { it.second }
                    call.respondText("""{"Items":[$items]}""", ContentType.Application.Json)
                }
                get("/LiveTv/Info") { call.respondText("{}", ContentType.Application.Json) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-key")))
        val service = LiveTvService(dir, LiveTvStore(storePath), JellyfinClient(), configStore)
        val clock = MutableClock(t0)
        service.clock = { clock.now }
        try {
            body(fake, service, clock)
        } finally {
            server.stop(100, 500)
        }
    }

    private class MutableClock(var now: Long)

    private fun LiveTvService.of(id: String) = lineup().single { it.channelId == id }

    @Test fun `a programme past its end is not served and the read re-reads and serves the next`() = harness { fake, service, clock ->
        fake.current["r1"] = program("First", t0 - 30 * minute, t0 + 30 * minute)
        service.sync()
        assertEquals("First", service.of("r1").currentProgram?.title())
        clock.now = t0 + 31 * minute
        assertNull(service.of("r1").currentProgram, "lineup() alone never serves an ended programme")
        fake.current["r1"] = program("Second", t0 + 30 * minute, t0 + hour)
        val before = fake.channelCalls
        assertEquals("Second", service.freshLineup().single().currentProgram?.title())
        assertEquals(before + 1, fake.channelCalls)
    }

    @Test fun `a gap gives no current programme and keeps has_guide`() = harness { fake, service, clock ->
        fake.current["r1"] = program("First", t0 - 30 * minute, t0 + 30 * minute)
        service.sync()
        clock.now = t0 + 31 * minute
        fake.current["r1"] = null   // Jellyfin sends no CurrentProgram in the gap
        fake.programs += "r1" to program("Second", t0 + 35 * minute, t0 + hour, "r1")
        val ch = service.freshLineup().single()
        assertNull(ch.currentProgram)
        assertTrue(ch.hasGuide, "the guide runs on past a gap")
        // the gap is looked at again once the snapshot is over a minute old, and the next programme arrives
        clock.now = t0 + 36 * minute
        fake.current["r1"] = program("Second", t0 + 35 * minute, t0 + hour)
        assertEquals("Second", service.freshLineup().single().currentProgram?.title())
    }

    @Test fun `a failed re-read serves no ended programme and leaves has_guide`() = harness { fake, service, clock ->
        fake.current["r1"] = program("First", t0 - 30 * minute, t0 + 30 * minute)
        service.sync()
        clock.now = t0 + 31 * minute
        fake.channelsDown = true
        val ch = service.freshLineup().single()
        assertNull(ch.currentProgram)
        assertTrue(ch.hasGuide)
        assertEquals(listOf("r1"), service.lineup().map { it.channelId }, "the rest of the lineup stands")
        // and a Programs call that fails on the way back leaves the flag as it was too
        fake.channelsDown = false; fake.programsDown = true; fake.current["r1"] = null
        clock.now = t0 + 40 * minute
        assertTrue(service.freshLineup().single().hasGuide)
    }

    @Test fun `a channel with nothing listed for 24 hours has no guide and causes no re-read`() = harness { fake, service, clock ->
        fake.current["r2"] = null
        fake.programs += "r2" to program("Far", t0 + 30 * hour, t0 + 31 * hour, "r2")   // beyond the reach
        service.sync()
        assertFalse(service.of("r2").hasGuide)
        clock.now = t0 + 10 * minute
        val before = fake.channelCalls
        assertFalse(service.freshLineup().single().hasGuide)
        clock.now = t0 + 10 * hour
        service.freshLineup()
        assertEquals(before, fake.channelCalls, "no programme to end and no gap to watch")
    }

    @Test fun `a station off air overnight with a programme within the day keeps its guide`() = harness { fake, service, _ ->
        fake.current["r3"] = null
        fake.programs += "r3" to program("Morning", t0 + 8 * hour, t0 + 9 * hour, "r3")
        service.sync()
        assertTrue(service.of("r3").hasGuide)
    }

    @Test fun `concurrent reads make one Jellyfin call and the cadence does not delay it`() = harness { fake, service, clock ->
        fake.current["r1"] = program("First", t0 - 30 * minute, t0 + 30 * minute)
        service.sync()
        clock.now = t0 + 31 * minute
        fake.current["r1"] = program("Second", t0 + 30 * minute, t0 + hour)
        val before = fake.channelCalls
        val all = coroutineScope { (1..6).map { async { service.freshLineup().single().currentProgram?.title() } }.awaitAll() }
        assertEquals(List(6) { "Second" }, all)
        assertEquals(before + 1, fake.channelCalls)
    }

    @Test fun `a re-read does not move lastSyncedAt and is at most one in five seconds`() = harness { fake, service, clock ->
        fake.current["r1"] = program("First", t0 - 30 * minute, t0 + 30 * minute)
        service.sync()
        val synced = service.overview().lastSyncedAt
        clock.now = t0 + 31 * minute
        fake.current["r1"] = program("Stale", t0 - hour, t0 + 30 * minute)   // Jellyfin still lags: an ended programme again
        service.freshLineup()
        val after = fake.channelCalls
        service.freshLineup(); service.freshLineup()
        assertEquals(after, fake.channelCalls, "inside five seconds nothing is asked again")
        clock.now += 6_000
        service.freshLineup()
        assertEquals(after + 1, fake.channelCalls)
        assertNull(service.of("r1").currentProgram)
        assertEquals(synced, service.overview().lastSyncedAt)
    }

    private fun dev.jellystructure.shared.tv.LiveTvProgramInfo.title() = name
}
