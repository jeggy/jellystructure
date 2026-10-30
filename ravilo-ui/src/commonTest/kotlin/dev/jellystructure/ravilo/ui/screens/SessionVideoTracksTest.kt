package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.sessionRemoteCommand
import dev.jellystructure.shared.tv.applyTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R266 (found live 2026-10-09) — a film the server started on the TV app had a session remote with no *Audio & Subs*:
 * the TV never told the server its tracks, and a `set_audio` reaching it picked nothing. The film player now reports its
 * picker's flat lists on the session, and a session's track command picks through the picker's own door.
 */
class SessionVideoTracksTest {
    private fun v(flat: Int, default: Boolean = false) =
        PickerVersion(flatIndex = flat, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false, isDefault = default,
            hadTitleText = false, ordinal = 0, clusterSize = 1)
    private fun g(lang: String?, vararg versions: PickerVersion, off: Boolean = false) =
        PickerLanguage(language = lang, isOff = off, versions = versions.toList(), isUnnamed = false)

    private val lists = castVideoLists(
        audioGroups = listOf(g("eng", v(0, default = true)), g("dan", v(1))),
        subGroupsWithOff = listOf(g(null, v(-1), off = true), g("dan", v(0)), g("hrv", v(1))),
        audioTracks = listOf(PlayerAudioTrack(0, "English", "eng"), PlayerAudioTrack(1, "Dansk", "dan")),
        subOptions = listOf(PlayerSubtitleTrack(0, "Dansk", "dan"), PlayerSubtitleTrack(1, "Hrvatski", "hrv")),
        selectedAudioFlat = 0, selectedSubFlat = -1,
    )

    @Test fun `the report carries the remote's flat lists and the picks, and no queue`() {
        val r = assertNotNull(sessionTracksReport("film-1", lists))
        assertEquals("film-1", r.itemId)
        assertEquals(emptyList(), r.queue, "a film has no queue: the server keeps the session's own")
        assertEquals(listOf("English", "Dansk"), r.audioTracks.map { it.label })
        assertEquals(listOf("Dansk", "Hrvatski"), r.subtitleTracks.map { it.label })
        assertEquals(0, r.audioIndex)
        assertEquals(-1, r.subtitleIndex)
        assertNull(sessionTracksReport("film-1", null), "no lists yet: nothing to say")
    }

    // DEB-2961 (one-remote FR-2, FR-14) — the player says the caption size it draws, and a set_subtitle_size reaches it.
    @Test fun `the report carries the caption size the player draws and a size command reaches the player`() {
        assertEquals("L", sessionTracksReport("film-1", lists, 'L')?.subSize)
        assertNull(sessionTracksReport("film-1", lists)?.subSize, "an app that does not say leaves it absent (M)")
        var got: String? = null
        val player = object : dev.jellystructure.shared.tv.RemotePlayer {
            override fun play() {}
            override fun pause() {}
            override fun toggle() {}
            override fun stop() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun next() {}
            override fun previous() {}
            override fun setVolume(level: Float, muted: Boolean) {}
            override fun setSubtitleSize(size: String) { got = size }
        }
        sessionRemoteCommand(SessionCommandRequest(op = "set_subtitle_size", size = "L"))!!
            .applyTo(player, dev.jellystructure.shared.tv.RemoteVolume())
        assertEquals("L", got)
    }

    /**
     * R370 (FR-R370-7, DEB-781) — a player that IS a handed-over queue's place reports the queue and where in it, so
     * the session's index follows the play instead of standing at the item the hand-over was started on.
     */
    @Test fun `a handed-over queue is reported under the session, with the entry's place`() {
        val ids = listOf("ep-1", "ep-2")
        SessionVideoTracks.place = SessionVideoTracks.QueuePlace("sess-1", ids, 1)
        try {
            val r = assertNotNull(sessionTracksReport("ep-2", lists))
            assertEquals("sess-1", r.sessionId)
            assertEquals(ids, r.queue)
            assertEquals(1, r.queueIndex)
            assertEquals(listOf("English", "Dansk"), r.audioTracks.map { it.label }, "the tracks still ride it (R266)")
            // Before the player has any track list the queue is still worth saying.
            val early = assertNotNull(sessionTracksReport("ep-2", null))
            assertEquals(1, early.queueIndex)
            assertEquals(emptyList(), early.audioTracks)
            // A place that is not this player's says nothing about a queue.
            assertEquals(emptyList(), assertNotNull(sessionTracksReport("ep-1", lists)).queue)
            assertNull(sessionTracksReport("ep-1", null))
        } finally {
            SessionVideoTracks.place = null
        }
    }

    @Test fun `a session's set_audio and set_subtitle pick the row the remote showed, Off included`() {
        val picks = mutableListOf<Pair<Int, String?>>()
        val source = CastVideoSource().apply {
            this.lists = { this@SessionVideoTracksTest.lists }
            applyPick = { tab, group, version -> picks += tab to (if (group.isOff) "off" else "${group.language}/${version.flatIndex}") }
        }
        val player = object : dev.jellystructure.shared.tv.RemotePlayer {
            override fun play() {}
            override fun pause() {}
            override fun toggle() {}
            override fun stop() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun next() {}
            override fun previous() {}
            override fun setVolume(level: Float, muted: Boolean) {}
            override fun selectAudio(index: Int) = source.selectAudioAt(index)
            override fun selectSubtitle(index: Int) = source.selectSubtitleAt(index)
        }
        fun send(op: String, index: Int): Unit = assertNotNull(sessionRemoteCommand(SessionCommandRequest(op = op, index = index))).applyTo(player, dev.jellystructure.shared.tv.RemoteVolume())
        send("set_audio", 1)
        send("set_subtitle", 1)
        send("set_subtitle", -1)
        send("set_audio", 9)   // a row that is not there picks nothing
        assertEquals(listOf<Pair<Int, String?>>(0 to "dan/1", 1 to "hrv/1", 1 to "off"), picks)
        assertEquals(RemoteCommand.SelectAudio(1), sessionRemoteCommand(SessionCommandRequest(op = "set_audio", index = 1)))
    }
}
