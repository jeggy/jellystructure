package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.shared.tv.CastSeenDevice
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R370 — the places list, the relay choice and the *who can reach what* register. */
class PlaybackTargetsTest {
    private val home = "198.51.100.7"
    private fun dev(id: String, user: String = "u-anna", platform: String? = "phone", kind: String = "phone", address: String? = home, lastSeen: Long = 0) =
        DeviceData(deviceId = id, deviceToken = "t", jellyfinUserId = user, jellyfinUsername = user, jellyfinUserToken = "jf", isAdmin = false,
            displayName = id.replaceFirstChar { it.uppercase() }, platform = platform, kind = kind, lastPublicAddress = address, lastSeen = lastSeen)

    private val viewer = dev("pixel")
    private fun app(d: DeviceData, vararg plays: String) = LiveApp(d, plays.map { "plays:$it" }.toSet())
    private fun reach(app: DeviceData, id: String, name: String, kind: String = "speaker", at: Long = 0) = ReachEntry(app, CastSeenDevice(id, name, kind), at)

    @Test fun `a Ravilo app is a place only while it holds its socket`() {
        val t = buildTargets(viewer, listOf(app(dev("mac", platform = "mac", kind = "tv"), "video", "music")), emptyList(), emptyList(), emptyMap(), { _, _ -> null }, 0)
        assertEquals(listOf("mac"), t.map { it.id })
        assertTrue(buildTargets(viewer, emptyList(), emptyList(), emptyList(), emptyMap(), { _, _ -> null }, 0).isEmpty())
    }

    @Test fun `plays decides what an app plays and the web app is not a music place`() {
        val t = buildTargets(viewer, listOf(app(dev("web", platform = "web", kind = "web"), "video")), emptyList(), emptyList(), emptyMap(), { _, _ -> null }, 0).single()
        assertTrue(t.capabilities.video)
        assertFalse(t.capabilities.audio)
    }

    @Test fun `a Cast device seen by a relay app on the same public address is a free place`() {
        val t = buildTargets(viewer, emptyList(), listOf(reach(dev("phone2"), "cast-1", "Office")), emptyList(), emptyMap(), { _, _ -> null }, 0).single()
        assertEquals("cast:cast-1", t.id)
        assertTrue(t.reachable)
        assertTrue(t.capabilities.audio)
        assertFalse(t.capabilities.video, "a speaker plays no film")
    }

    @Test fun `a Cast device only a web app or another network sees is not a place`() {
        assertTrue(buildTargets(viewer, emptyList(), listOf(reach(dev("web", platform = "web"), "cast-1", "Office")), emptyList(), emptyMap(), { _, _ -> null }, 0).isEmpty())
        assertTrue(buildTargets(viewer, emptyList(), listOf(reach(dev("far", address = "203.0.113.9"), "cast-1", "Office")), emptyList(), emptyMap(), { _, _ -> null }, 0).isEmpty())
    }

    @Test fun `Not reachable is receivers seen in the last 24 h and never a Ravilo app`() {
        val now = 100L * 60 * 60_000
        val recent = dev("cast-r1", kind = "cast", platform = "cast-audio", lastSeen = now - 60_000)
        val old = dev("cast-r2", kind = "cast", platform = "cast-audio", lastSeen = now - 25L * 60 * 60_000)
        val t = buildTargets(viewer, emptyList(), emptyList(), listOf(recent, old), mapOf("cast-r1" to "cast-1"), { _, _ -> null }, now)
        val only = t.single()
        assertFalse(only.reachable)
        assertEquals("no_relay", only.reason)
        assertEquals("cast:cast-1", only.id)
    }

    @Test fun `busy carries the session and is keyed on cast_device_id`() {
        val busy = SessionView("s1", 1, SessionOwner("u-anna", "Anna"), kind = "music", target = SessionTarget("cast", "r", "Office", "speaker"), state = "playing")
        val t = buildTargets(viewer, emptyList(), listOf(reach(dev("phone2"), "cast-1", "Office")), emptyList(), emptyMap(),
            { place, castId -> if (castId == "cast-1") busy else null }, 0).single()
        assertEquals("s1", t.busy?.id)
    }

    @Test fun `no group is ever built`() = runBlocking {
        val r = CastReach()
        r.report(dev("phone2"), listOf(CastSeenDevice("g1", "Whole house", "group"), CastSeenDevice("c1", "Office", "speaker")))
        assertEquals(listOf("c1"), r.entries().map { it.device.castDeviceId })
    }

    @Test fun `only an Android or desktop app that reports the device is a relay`() {
        val phone = dev("phone2")
        val web = dev("web", platform = "web")
        val entries = listOf(reach(phone, "cast-1", "Office", at = 5), reach(web, "cast-1", "Office", at = 9))
        assertEquals("phone2", chooseRelayApp(entries, "cast-1", home, setOf("phone2", "web"))?.deviceId)
    }

    @Test fun `an app on another public address is never chosen`() =
        assertNull(chooseRelayApp(listOf(reach(dev("phone2", address = "203.0.113.9"), "cast-1", "Office")), "cast-1", home, setOf("phone2")))

    @Test fun `the most recently reporting app wins`() {
        val a = dev("phone-a"); val b = dev("mac-b", platform = "mac")
        val pick = chooseRelayApp(listOf(reach(a, "cast-1", "Office", at = 1), reach(b, "cast-1", "Office", at = 7)), "cast-1", home, setOf("phone-a", "mac-b"))
        assertEquals("mac-b", assertNotNull(pick).deviceId)
    }

    @Test fun `an app already driving a cast is never the relay for a launch`() {
        val a = dev("phone-a"); val b = dev("mac-b", platform = "mac")
        val entries = listOf(reach(a, "cast-1", "Office", at = 9), reach(b, "cast-1", "Office", at = 1))
        assertEquals("mac-b", chooseRelayApp(entries, "cast-1", home, setOf("phone-a", "mac-b"), busy = setOf("phone-a"))?.deviceId)
        assertNull(chooseRelayApp(entries.take(1), "cast-1", home, setOf("phone-a"), busy = setOf("phone-a")))
    }

    @Test fun `no candidate means no relay`() {
        assertNull(chooseRelayApp(emptyList(), "cast-1", home, emptySet()))
        // A reporting app whose socket closed is not a candidate either.
        assertNull(chooseRelayApp(listOf(reach(dev("phone2"), "cast-1", "Office")), "cast-1", home, emptySet()))
    }

    @Test fun `a report replaces that apps list and a closed socket drops it`() = runBlocking {
        val r = CastReach()
        val p = dev("phone2")
        r.report(p, listOf(CastSeenDevice("c1", "Office", "speaker")))
        r.report(p, listOf(CastSeenDevice("c2", "Kitchen", "speaker")))
        assertEquals(listOf("c2"), r.entries().map { it.device.castDeviceId })
        r.drop("phone2")
        assertTrue(r.entries().isEmpty())
    }

    @Test fun `an app that declared nothing plays video`() {
        val c = appCapabilities(emptySet(), "tv")
        assertTrue(c.video); assertFalse(c.audio); assertFalse(c.book)
    }
}
