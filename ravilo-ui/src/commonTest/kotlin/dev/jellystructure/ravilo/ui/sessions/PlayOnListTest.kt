package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R370 — the four tiers, the merge with this app's own discovery, and the busy place's question. */
class PlayOnListTest {
    private val video = TargetCapabilities(video = true, audio = true, display = true)
    private val speakerCaps = TargetCapabilities(audio = true)
    private fun t(id: String, name: String, icon: String = "tv", caps: TargetCapabilities = video, busy: SessionView? = null, reachable: Boolean = true,
                  here: Boolean = false, castId: String? = null, kind: String = if (castId != null) "cast" else "app") =
        PlaybackTarget(id = id, kind = kind, name = name, icon = icon, capabilities = caps, busy = busy, reachable = reachable, here = here, castDeviceId = castId)
    private fun route(id: String, name: String, kind: String = "display") = CastRoute(id = id, name = name, selected = false, select = {}, kind = kind)
    private fun s(mine: Boolean = true, controllable: Boolean = mine, kind: String = "music") =
        SessionView("s1", 1, SessionOwner("u", if (mine) "Anna" else "Ben"), mine = mine, kind = kind, title = "Paper Harbour",
            target = SessionTarget("cast", "d", "Office", "speaker"), state = "playing", controllable = controllable)

    @Test fun `four tiers in order this device playing now free not reachable`() {
        val rows = mergeTargets(listOf(
            t("pixel", "Pixel", "phone", here = true),
            t("cast:a", "Office", "speaker", speakerCaps, busy = s(), castId = "a"),
            t("cast:b", "Kitchen", "speaker", speakerCaps, castId = "b"),
            t("cast:c", "Bedroom", "speaker", speakerCaps, reachable = false, castId = "c"),
        ), emptyList())
        val tiers = playOnTiers(rows, "music")
        assertEquals("pixel", tiers.thisDevice?.id)
        assertEquals(listOf("Office"), tiers.playingNow.map { it.name })
        assertEquals(listOf("Kitchen"), tiers.free.map { it.name })
        assertEquals(listOf("Bedroom"), tiers.unreachable.map { it.name })
    }

    @Test fun `a speaker this app reaches itself is free even when the server calls its old receiver record unreachable`() {
        // The server's row is the receiver's own record (no Cast id, `no_relay`, seen hours ago); the phone's route has
        // its own id. One row, reachable, through the local route (found on the Pixel with the guest-room speaker).
        val rows = mergeTargets(listOf(
            t("pixel", "Pixel", "phone", here = true),
            t("cast-e9", "Guest room", "speaker", speakerCaps, reachable = false, kind = "cast"),
        ), listOf(route("route-5c29", "Guest room", "speaker")))
        assertEquals(1, rows.count { it.name == "Guest room" })
        val tiers = playOnTiers(rows, "music")
        assertEquals(listOf("Guest room"), tiers.free.map { it.name })
        assertEquals("route-5c29", tiers.free.single().route?.id)
        assertTrue(tiers.unreachable.isEmpty())
    }

    @Test fun `free lists TVs and displays first then speakers each by name`() {
        val rows = mergeTargets(listOf(t("cast:s", "Attic", "speaker", speakerCaps, castId = "s"), t("tv-b", "Bedroom TV"), t("tv-a", "Atrium TV")), emptyList())
        assertEquals(listOf("Atrium TV", "Bedroom TV", "Attic"), playOnTiers(rows, "music").free.map { it.name })
    }

    @Test fun `a film lists only video places and the rest are absent`() {
        val rows = mergeTargets(listOf(t("cast:s", "Attic", "speaker", speakerCaps, castId = "s"), t("tv-a", "Atrium TV")), emptyList())
        assertEquals(listOf("Atrium TV"), playOnTiers(rows, "film").free.map { it.name })
    }

    @Test fun `a book lists only Ravilo apps that declare book and never a Cast place`() {
        val rows = mergeTargets(listOf(t("mac", "Mac", "computer", TargetCapabilities(video = true, audio = true, book = true)),
            t("cast:s", "Attic", "speaker", TargetCapabilities(audio = true, book = true), castId = "s")), emptyList())
        assertEquals(listOf("Mac"), playOnTiers(rows, "audiobook").free.map { it.name })
    }

    @Test fun `a server row and a local route with one cast_device_id are one row`() {
        val rows = mergeTargets(listOf(t("cast:abc", "Office", "speaker", speakerCaps, castId = "abc")), listOf(route("abc", "Office", "speaker")))
        assertEquals(1, rows.size)
        assertTrue(rows.single().route != null)
    }

    @Test fun `a TV app and a Cast route with the same name are one row the app`() {
        val rows = mergeTargets(listOf(t("tvapp", "Living room TV")), listOf(route("r1", "living room tv")))
        assertEquals(listOf("tvapp"), rows.map { it.id })
    }

    @Test fun `different names stay two rows`() =
        assertEquals(2, mergeTargets(listOf(t("tvapp", "Den TV")), listOf(route("r1", "Office"))).size)

    @Test fun `a group route is never a row`() =
        assertTrue(mergeTargets(null, listOf(route("g", "Everywhere", "group"))).isEmpty())

    @Test fun `with the server unreachable the local discovery list stands alone`() =
        assertEquals(listOf("cast:r1"), mergeTargets(null, listOf(route("r1", "Office"))).map { it.id })

    @Test fun `a not-reachable row cannot be picked`() =
        assertEquals(BusyChoice.Nothing, busyChoice(PlayOnRow("x", "Bedroom", "speaker", reachable = false), holdsCastLink = false))

    @Test fun `a busy place started elsewhere asks and never adds and asks again next time`() {
        val row = PlayOnRow("cast:a", "Office", "speaker", busy = s())
        assertEquals(BusyChoice.AskReplace, busyChoice(row, holdsCastLink = false))
        assertEquals(BusyChoice.AskReplace, busyChoice(row, holdsCastLink = false))
    }

    @Test fun `no question while this app holds the busy sessions Cast link`() =
        assertEquals(BusyChoice.Start, busyChoice(PlayOnRow("cast:a", "Office", "speaker", busy = s()), holdsCastLink = true))

    @Test fun `someone elses session asks to stop them only with the switch on`() {
        assertEquals(BusyChoice.AskReplacePerson, busyChoice(PlayOnRow("a", "Office", "speaker", busy = s(mine = false, controllable = true)), false))
        assertEquals(BusyChoice.NotOffered, busyChoice(PlayOnRow("a", "Office", "speaker", busy = s(mine = false, controllable = false)), false))
    }

    @Test fun `a free place just starts a second session`() =
        assertEquals(BusyChoice.Start, busyChoice(PlayOnRow("a", "Kitchen", "speaker"), false))

    @Test fun `the same kind playing for you elsewhere is found`() {
        val other = s().copy(here = false)
        assertEquals("s1", sameKindElsewhere(listOf(other), "music")?.id)
        assertNull(sameKindElsewhere(listOf(other), "film"))
    }
}
