package dev.jellystructure.ravilo.ui

import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.RemoteVolume
import dev.jellystructure.shared.tv.applyTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R369 (FR-R369-9, amendment 2026-10-14, DEB-1099) — a remote `seek` on a **book** is book time, and reaches the door
 * that crosses the book's parts.
 *
 * Found while siting DEB-1099, not on a device: `MusicRemotePlayer.seekTo` handed the position straight to
 * `MusicEngine.seekTo`, which applies it **inside the playing part**, while its own sibling `seekBy` has had the book
 * arm since R323 (`if (st.book != null) skipBy(delta)`). With the remote's bar honestly drawn in book time
 * (FR-R368-13), a drag past the part's share of it asked the engine for a position beyond the part's end: the unheard
 * rest of the part skipped and the stored place rewritten forward. Both arms are asserted here, off one predicate, so
 * the asymmetry cannot come back.
 *
 * Specs: `MusicRemoteSeekTest`.
 */
class MusicRemoteSeekTest {
    private class Doors(override val bookPlaying: Boolean, private val at: Long = 0L, override val durationMs: Long = 0L) : MusicSeekDoors {
        val log = mutableListOf<String>()
        override fun currentPositionMs(): Long = at
        override fun seekTo(positionMs: Long) { log += "seekTo $positionMs" }
        override fun seekBook(bookMs: Long) { log += "seekBook $bookMs" }
        override fun skipBy(deltaMs: Long) { log += "skipBy $deltaMs" }
    }

    private val saved = MusicRemotePlayer.doors
    @AfterTest fun tearDown() { MusicRemotePlayer.doors = saved }

    private fun drive(d: Doors, body: () -> Unit): List<String> { MusicRemotePlayer.doors = d; body(); return d.log }

    @Test fun `a seek on a book goes through the book door in book time`() {
        val d = Doors(bookPlaying = true, durationMs = 900_000)
        // Halfway down a 30-minute book: the second part's end, which `seekTo` would have put inside part one.
        assertEquals(listOf("seekBook 900000"), drive(d) { MusicRemotePlayer.seekTo(900_000) })
    }

    @Test fun `a seek on a song still goes through the songs own door`() {
        val d = Doors(bookPlaying = false, durationMs = 240_000)
        assertEquals(listOf("seekTo 9000"), drive(d) { MusicRemotePlayer.seekTo(9_000) })
    }

    @Test fun `the whole wire command reaches the same doors`() {
        val book = Doors(bookPlaying = true, durationMs = 900_000)
        assertEquals(listOf("seekBook 660000"), drive(book) {
            RemoteCommand.SeekTo(660_000).applyTo(MusicRemotePlayer, RemoteVolume())
        })
        val song = Doors(bookPlaying = false, durationMs = 240_000)
        assertEquals(listOf("seekTo 30000"), drive(song) {
            RemoteCommand.SeekTo(30_000).applyTo(MusicRemotePlayer, RemoteVolume())
        })
    }

    @Test fun `a negative position is the start of the book not a position before it`() {
        val d = Doors(bookPlaying = true, durationMs = 900_000)
        assertEquals(listOf("seekBook 0"), drive(d) { MusicRemotePlayer.seekTo(-5_000) })
    }

    /** R322/R323's rule, unchanged and now held to the same predicate: a book's ±30 s crosses parts, a song's clamps. */
    @Test fun `a relative seek keeps the arms it has always had`() {
        val book = Doors(bookPlaying = true, at = 10_000, durationMs = 900_000)
        assertEquals(listOf("skipBy 30000"), drive(book) { MusicRemotePlayer.seekBy(30_000) })
        val song = Doors(bookPlaying = false, at = 230_000, durationMs = 240_000)
        assertEquals(listOf("seekTo 240000"), drive(song) { MusicRemotePlayer.seekBy(30_000) }, "a song clamps to its end")
        val start = Doors(bookPlaying = false, at = 5_000, durationMs = 240_000)
        assertEquals(listOf("seekTo 0"), drive(start) { MusicRemotePlayer.seekBy(-30_000) })
    }
}
