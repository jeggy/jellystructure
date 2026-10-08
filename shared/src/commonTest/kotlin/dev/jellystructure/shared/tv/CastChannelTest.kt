package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R380 (FR-R380-7) — the one reading of the phone's Cast commands, for the web receiver and the Android TV app alike. */
class CastChannelTest {
    private fun t(id: String) = CastTrackItem(id = id, title = id.uppercase())
    private val queue = listOf(t("a"), t("b"), t("c"), t("d"))
    private val music = CastChannelMusic(queue, currentIndex = 1, currentItemId = "b")

    @Test fun `music next and previous`() {
        assertEquals(CastChannelStep.MusicNext, castChannelStep(CastCommand("next"), music))
        assertEquals(CastChannelStep.MusicPrevious, castChannelStep(CastCommand("prev"), music))
        assertEquals(CastChannelStep.PlayAt(3), castChannelStep(CastCommand("play_at", index = 3), music))
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("play_at"), music))
    }

    @Test fun `a command that names a song no longer playing is stale`() {
        assertEquals(CastChannelStep.Stale, castChannelStep(CastCommand("next", expectItem = "a"), music))
        assertEquals(CastChannelStep.Stale, castChannelStep(CastCommand("next", expectIndex = 0), music))
        assertEquals(CastChannelStep.MusicNext, castChannelStep(CastCommand("next", expectItem = "b", expectIndex = 1), music))
        // R359 — the index is the whole queue's: a window that starts at 10 plays song 11.
        val window = music.copy(queueStart = 10)
        assertEquals(CastChannelStep.MusicNext, castChannelStep(CastCommand("next", expectIndex = 11), window))
        assertEquals(CastChannelStep.Stale, castChannelStep(CastCommand("next", expectIndex = 1), window))
    }

    @Test fun `queue edits keep the playing song`() {
        val moved = castChannelStep(CastCommand("queue_move", index = 0, to = 3), music) as CastChannelStep.QueueEdited
        assertEquals(listOf("b", "c", "d", "a"), moved.tracks.map { it.id })
        assertEquals(0, moved.currentIndex)
        val removed = castChannelStep(CastCommand("queue_remove", index = 0), music) as CastChannelStep.QueueEdited
        assertEquals(listOf("b", "c", "d"), removed.tracks.map { it.id })
        assertEquals(0, removed.currentIndex)
        // The playing song is never removed; a place outside the queue does nothing.
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("queue_remove", index = 1), music))
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("queue_move", index = 0, to = 9), music))
        val added = castChannelStep(CastCommand("queue_add", track = t("e")), music) as CastChannelStep.QueueEdited
        assertEquals("e", added.tracks.last().id)
        val next = castChannelStep(CastCommand("queue_play_next", track = t("e")), music) as CastChannelStep.QueueEdited
        assertEquals(listOf("a", "b", "e", "c", "d"), next.tracks.map { it.id })
        assertEquals(1, next.currentIndex)
    }

    @Test fun `repeat shuffle lyrics and asking for the queue`() {
        assertEquals(CastChannelStep.Repeat("one"), castChannelStep(CastCommand("repeat", mode = "one"), music))
        assertEquals(CastChannelStep.Repeat("off"), castChannelStep(CastCommand("repeat", mode = "sometimes"), music))
        assertEquals(CastChannelStep.Shuffle(true), castChannelStep(CastCommand("shuffle", on = true), music))
        assertEquals(CastChannelStep.Lyrics(false), castChannelStep(CastCommand("lyrics", on = false), music))
        assertEquals(CastChannelStep.Status(full = true), castChannelStep(CastCommand("status"), music))
        assertEquals(CastChannelStep.Status(full = true), castChannelStep(CastCommand("get_queue"), music))
        // A film's command means nothing to a song.
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("subtitle", index = 2), music))
    }

    @Test fun `a film's commands`() {
        assertEquals(CastChannelStep.Subtitle(2), castChannelStep(CastCommand("subtitle", index = 2), null))
        assertEquals(CastChannelStep.Subtitle(-1), castChannelStep(CastCommand("subtitle"), null))
        assertEquals(CastChannelStep.Audio(1), castChannelStep(CastCommand("audio", index = 1), null))
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("audio"), null))
        assertEquals(CastChannelStep.SubSize("L"), castChannelStep(CastCommand("subsize", size = "L"), null))
        assertEquals(CastChannelStep.EpisodeNext, castChannelStep(CastCommand("next"), null))
        assertEquals(CastChannelStep.NextUpCancel, castChannelStep(CastCommand("nextup_cancel"), null))
        assertEquals(CastChannelStep.NextUpPlay, castChannelStep(CastCommand("nextup_play"), null))
        assertEquals(CastChannelStep.Status(full = false), castChannelStep(CastCommand("status"), null))
        assertEquals(CastChannelStep.Ignore, castChannelStep(CastCommand("queue_move", index = 0, to = 1), null))
    }

    @Test fun `a queue part is the receiver's to assemble`() {
        val part = CastCommand("queue_part", queueId = "q", offset = 4, tracks = listOf(t("x")))
        assertEquals(CastChannelStep.QueuePart(part), castChannelStep(part, music))
        assertEquals(CastChannelStep.QueuePart(part), castChannelStep(part, null))
    }

    @Test fun `the queue goes out whole only when it changed or was asked for`() {
        val r = CastQueueRevision()
        assertTrue(r.next(queue))            // first status after connecting
        assertFalse(r.next(queue))           // nothing changed
        assertFalse(r.next(queue))
        r.askedForQueue()
        assertTrue(r.next(queue))            // asked
        assertTrue(r.next(queue.reversed())) // the order changed
        assertEquals(2, r.rev)
        assertFalse(r.next(queue.reversed()))
    }

    @Test fun `an edit outside the queue returns null`() {
        assertNull(castQueueEdit(CastCommand("queue_move", index = -1, to = 0), queue, 1, "b"))
        assertNull(castQueueEdit(CastCommand("queue_add"), queue, 1, "b"))
    }
}
