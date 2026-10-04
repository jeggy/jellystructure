package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.JobEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 304a — the admin's list: every owner on every network in full, *started from*, today's ended rows, and the `/ws` frame. */
class AdminSessionListTest {
    private val dbPath = "/tmp/jellystructure-test-adminses-${getpid()}.db"
    private var now = 1_000_000_000L

    @AfterTest fun tearDown() { dev.jellystructure.db.closeLastDatabaseForTests(); for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") } }

    @Test fun `the admin sees every owner on every network in full`() = runBlocking {
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val sessions = PlaybackSessions(db) { now }
        sessions.describe = { id, _ -> SessionKind.MUSIC to SessionItem(id, "Song $id", "The Lanterns") }
        // A viewer may see nothing (canSee false) — the admin's list ignores that.
        val publisher = SessionPublisher(sessions, devices, bus = null, canSee = { _, _, _, _ -> false }, clock = { now })
        fun dev(id: String, user: String, address: String) = devices.loginDevice(id, id.replaceFirstChar { it.uppercase() }, user, user, "jf", false, false, kind = "phone").first
            .also { devices.recordAddress(id, user, address) }
        val a = dev("phone-a", "u-anna", "198.51.100.7")
        val b = dev("phone-b", "u-ben", "203.0.113.9")
        sessions.onStart(a, "song-1", 0)
        val ended = sessions.onStart(b, "song-2", 0)
        sessions.end(ended, "stopped")

        val list = publisher.adminList()
        assertEquals(2, list.sessions.size)
        val live = list.sessions.first { it.endedAt == null }
        assertEquals("Song song-1", live.title)
        assertEquals("Phone-a", live.startedFrom)
        assertTrue(live.events.any { it.what == "started" })
        assertEquals("stopped", list.sessions.first { it.endedAt != null }.endReason)
        assertEquals(false, list.householdControl)
    }

    @Test fun `the sessions event round-trips with its type and the whole list`() {
        val json = Json { classDiscriminator = "type" }
        val ev: JobEvent = JobEvent.PlaybackSessions(dev.jellystructure.model.AdminSessionList(listOf(
            dev.jellystructure.model.AdminSessionRow("s1", 1, "u", "Anna", "music", targetKind = "cast", targetId = "d", targetName = "Office", targetIcon = "speaker", state = "playing"),
        ), 5))
        val text = json.encodeToString(JobEvent.serializer(), ev)
        assertTrue("\"type\":\"playback_sessions\"" in text, text)
        val back = json.decodeFromString(JobEvent.serializer(), text)
        assertIs<JobEvent.PlaybackSessions>(back)
        assertEquals("Office", back.list.sessions.single().targetName)
    }
}
