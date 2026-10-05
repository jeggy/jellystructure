package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.components.linkedDeviceKeyOf
import dev.jellystructure.ravilo.ui.music.handOffPositionMs
import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.ravilo.ui.seams.GroupController
import dev.jellystructure.ravilo.ui.seams.addableRooms
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionMembersReport
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionRoom
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The second Pixel 9 Pro / Mac round with the speakers (2026-10-05): bugs 6, 7, 8, 10, 12, each as it was found. */
class SpeakerRoundTwoTest {
    private class FakeGroup(var members: MutableList<SessionRoom>, val joins: Boolean, val accepts: Boolean = true) : GroupController {
        override fun members() = members.toList()
        override fun selectable() = listOf(SessionRoom("c-stue", "Lounge"))
        override fun add(castDeviceId: String): Boolean { if (accepts && joins) members += SessionRoom(castDeviceId, "Lounge"); return accepts }
        override fun remove(castDeviceId: String) = members.removeAll { it.castDeviceId == castDeviceId }
        override fun setRoomVolume(castDeviceId: String, percent: Int) = true
    }
    private val guest = SessionRoom("c-guest", "Guest room", 6)

    // ── 7 — Add a speaker… ──

    @Test fun `an added room that joins is reported among the members`() = runTest {
        val g = FakeGroup(mutableListOf(guest), joins = true)
        val reports = mutableListOf<SessionMembersReport>()
        applyRoomOp(g, SessionCommandRequest(op = "add_room", castDeviceId = "c-stue"), "song-1", { "Lounge" }, { reports += it }, wait = {})
        assertEquals(listOf("c-guest", "c-stue"), reports.single().members.map { it.castDeviceId })
        assertNull(reports.single().failed)
    }

    @Test fun `an add the controller refuses or that never joins says Couldn't add`() = runTest {
        val refused = mutableListOf<SessionMembersReport>()
        applyRoomOp(FakeGroup(mutableListOf(guest), joins = false, accepts = false), SessionCommandRequest(op = "add_room", castDeviceId = "c-hub"), "song-1", { "Kitchen hub" }, { refused += it }, wait = {})
        assertEquals("Kitchen hub", refused.single().failed)
        val never = mutableListOf<SessionMembersReport>()
        applyRoomOp(FakeGroup(mutableListOf(guest), joins = false), SessionCommandRequest(op = "add_room", castDeviceId = "c-hub"), "song-1", { "Kitchen hub" }, { never += it }, wait = {})
        assertEquals("Kitchen hub", never.single().failed)
        assertEquals(listOf("c-guest"), never.single().members.map { it.castDeviceId }, "the session keeps its room")
    }

    @Test fun `a room op waits for no reflection so it never reads Can't reach`() {
        assertFalse(commandAwaitsReflection(SessionCommandRequest(op = "add_room", castDeviceId = "c")))
        assertFalse(commandAwaitsReflection(SessionCommandRequest(op = "set_volume", level = 10, castDeviceId = "c")))
        assertTrue(commandAwaitsReflection(SessionCommandRequest(op = "set_volume", level = 10)))
        assertTrue(commandAwaitsReflection(SessionCommandRequest(op = "pause")))
    }

    @Test fun `Add a speaker offers only speakers and only what the controller says can join`() {
        val hub = PlaybackTarget("cast:c-hub", "cast", "Kitchen hub", "tv", TargetCapabilities(video = true, audio = true, display = true), castDeviceId = "c-hub")
        val stue = PlaybackTarget("cast:c-stue", "cast", "Lounge", "speaker", TargetCapabilities(audio = true), castDeviceId = "c-stue")
        val attic = PlaybackTarget("cast:c-attic", "cast", "Attic", "speaker", TargetCapabilities(audio = true), castDeviceId = "c-attic")
        assertEquals(listOf("c-stue", "c-attic"), addableRooms(listOf(hub, stue, attic), setOf("c-guest"), null).map { it.castDeviceId })
        assertEquals(listOf("c-stue"), addableRooms(listOf(hub, stue, attic), setOf("c-guest"), listOf(SessionRoom("c-stue", "Lounge"))).map { it.castDeviceId })
    }

    // ── 6 — a paused song cast from a relaunched app ──

    @Test fun `a song restored paused hands over its place, not 0`() {
        assertEquals(45_825, handOffPositionMs(live = 0, statePositionMs = 45_825, playing = false))
        assertEquals(12_000, handOffPositionMs(live = 12_000, statePositionMs = 45_825, playing = false))
        assertEquals(0, handOffPositionMs(live = 0, statePositionMs = 45_825, playing = true))
    }

    // ── 8 — the remote follows the end ──

    private fun v(state: String = "playing", title: String? = "Song", pos: Long? = 1_000, dur: Long? = 180_000, castId: String? = null, kind: String = "cast", name: String = "Guest room") =
        SessionView("s1", 4, SessionOwner("u", "Anna"), mine = true, kind = "music", title = title, target = SessionTarget(kind, "rx", name, "speaker", castDeviceId = castId),
            state = state, positionMs = pos, durationMs = dur, controllable = true)

    @Test fun `a session that left the list shows as ended`() {
        assertEquals("ended", remoteSessionView(null, v(), listKnown = true)?.state)
        assertEquals("playing", remoteSessionView(null, v(), listKnown = false)?.state, "before any list, the detail stands")
        assertEquals("paused", remoteSessionView(v(state = "paused"), v(), listKnown = true)?.state, "the row wins")
    }

    // ── 10 / 12 — an unknown title is not a hidden one ──

    @Test fun `a title not known yet is not hidden`() {
        assertFalse(sessionTitleHidden(v(title = null)))
        assertTrue(sessionTitleHidden(v(title = null, pos = null, dur = null)))
    }

    // ── 12 — a busy Cast place is never free ──

    @Test fun `a Cast place playing a session is busy even when the server row could not say`() {
        val row = PlaybackTarget("cast:c-guest", "cast", "Guest room", "speaker", TargetCapabilities(audio = true), castDeviceId = "c-guest")
        val byId = playOnTiers(mergeTargets(listOf(row), emptyList(), listOf(v(castId = "c-guest"))), "music")
        assertTrue(byId.free.isEmpty()); assertEquals(listOf("Guest room"), byId.playingNow.map { it.name })
        val byName = playOnTiers(mergeTargets(listOf(row), emptyList(), listOf(v())), "music")
        assertTrue(byName.free.isEmpty())
        val ended = playOnTiers(mergeTargets(listOf(row), emptyList(), listOf(v(state = "ended"))), "music")
        assertEquals(listOf("Guest room"), ended.free.map { it.name })
    }

    // ── 13 / 9 — a room added must not hand the music back, and a hand-back keeps the whole queue ──

    @Test fun `the moment on a group route is not the end of the cast`() {
        var live = false
        val ends = mutableListOf<Boolean>()
        for (link in listOf(dev.jellystructure.ravilo.ui.seams.CastLinkState.CONNECTED, dev.jellystructure.ravilo.ui.seams.CastLinkState.RECONNECTING,
                dev.jellystructure.ravilo.ui.seams.CastLinkState.CONNECTED, dev.jellystructure.ravilo.ui.seams.CastLinkState.NONE)) {
            ends += dev.jellystructure.ravilo.ui.music.castSessionGone(live, link)
            live = dev.jellystructure.ravilo.ui.music.castLinkLive(live, link)
        }
        assertEquals(listOf(false, false, false, true), ends, "only the final NONE hands back (09:51:54 on the Pixel handed back mid-group)")
        assertTrue(dev.jellystructure.ravilo.ui.music.castSessionGone(dev.jellystructure.ravilo.ui.music.castLinkLive(true, dev.jellystructure.ravilo.ui.seams.CastLinkState.RECONNECTING),
            dev.jellystructure.ravilo.ui.seams.CastLinkState.NONE), "a rejoin that gives up still hands back")
        assertFalse(dev.jellystructure.ravilo.ui.music.castLinkLive(false, dev.jellystructure.ravilo.ui.seams.CastLinkState.RECONNECTING), "a resume at start-up is not live yet")
    }

    private fun song(id: String) = dev.jellystructure.shared.tv.MusicTrackItem(id = id, title = id)

    @Test fun `a hand-back of one song or a window gives back this device's whole queue`() {
        val album = (1..12).map { song("s$it") }
        val (one, at) = dev.jellystructure.ravilo.ui.music.handBackQueue(listOf(song("s5")), 0, album)
        assertEquals(12, one.size); assertEquals(4, at)
        val (window, wAt) = dev.jellystructure.ravilo.ui.music.handBackQueue(album.subList(3, 7), 2, album)
        assertEquals(12, window.size); assertEquals(5, wAt)
        val other = listOf(song("x1"), song("s5"))
        assertEquals(other to 1, dev.jellystructure.ravilo.ui.music.handBackQueue(other, 1, album), "a different queue on the speaker is the speaker's")
        assertEquals(album to 3, dev.jellystructure.ravilo.ui.music.handBackQueue(album, 3, listOf(song("s4"))), "the speaker's longer queue wins")
    }

    // ── 16 — Add a speaker made reliable where it can be ──

    private val stueRoom = SessionRoom("c-stue", "Lounge", 10)

    @Test fun `a room gone is reported only when two reads agree and an added room at once`() {
        val d = MembershipDebounce()
        assertEquals(listOf(stueRoom), d.next(listOf(stueRoom)))
        assertEquals(listOf(stueRoom, guest), d.next(listOf(stueRoom, guest)), "an added room at once")
        assertNull(d.next(listOf(stueRoom)), "one republish showing the leader alone (10:24:08) is not a room gone")
        assertNull(d.next(listOf(stueRoom, guest)), "and it was back on the next read")
        assertNull(d.next(listOf(stueRoom)))
        assertEquals(listOf(stueRoom), d.next(listOf(stueRoom)), "two reads agree: it left")
        assertEquals(setOf("c-guest"), d.lastLeft)
        assertNull(d.next(emptyList()), "no controller is never no rooms")
    }

    @Test fun `a room that just left waits before it is added again`() = runTest {
        val cooldown = RoomCooldown()
        cooldown.left("c-guest", atMs = 1_000)
        val waited = mutableListOf<Long>()
        val g = FakeGroup(mutableListOf(stueRoom), joins = true)
        applyRoomOp(g, SessionCommandRequest(op = "add_room", castDeviceId = "c-guest"), "song-1", { "Guest room" }, {}, wait = { waited += it },
            cooldown = cooldown, now = { 4_000 })
        assertEquals(7_000, waited.first(), "re-adding a member that had just left took Play services' Cast provider down (10:25:37)")
        assertEquals(0, RoomCooldown().remaining("c-guest", 4_000))
    }

    @Test fun `a session that dropped right after a room op is rejoined and not otherwise`() {
        assertTrue(rejoinAfterDrop(msSinceRoomOp = 470, stoppedHere = false, castDeviceId = "c-stue", sessionLive = true))
        assertFalse(rejoinAfterDrop(msSinceRoomOp = 470, stoppedHere = true, castDeviceId = "c-stue", sessionLive = true), "this app's own Stop")
        assertFalse(rejoinAfterDrop(msSinceRoomOp = 60_000, stoppedHere = false, castDeviceId = "c-stue", sessionLive = true), "no room op near it")
        assertFalse(rejoinAfterDrop(msSinceRoomOp = null, stoppedHere = false, castDeviceId = "c-stue", sessionLive = true))
        assertFalse(rejoinAfterDrop(msSinceRoomOp = 470, stoppedHere = false, castDeviceId = "c-stue", sessionLive = false), "the server says it ended")
    }

    @Test fun `the hand-off names the Cast device the link is on`() {
        val routes = listOf(
            CastRoute("r-group", "Guest room", selected = true, select = {}, kind = "group", deviceKey = "g"),
            CastRoute("r-guest", "Guest room", selected = false, select = {}, kind = "speaker", deviceKey = "c-guest"),
        )
        assertEquals("c-guest", linkedDeviceKeyOf(routes, "Guest room"))
        assertNull(linkedDeviceKeyOf(routes.take(1), "Guest room"))
    }
}
