package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.music.TvMusicAction
import dev.jellystructure.ravilo.ui.music.TvMusicKey
import dev.jellystructure.ravilo.ui.music.tvMusicAction
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.SessionLoadEnvelope
import dev.jellystructure.shared.tv.kindsPlayed
import dev.jellystructure.shared.tv.playsQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R380 (FR-R380-11, amendment 2026-10-13, DEB-919) — the TV offered itself as a book place and dropped every book the
 * server handed it. What is held here: the set of kinds the app advertises and the set it has an arm for are the **same
 * set**, so the next capability cannot outrun its handler; a book envelope takes the book arm with the book and its
 * place; and a kind with no arm is refused by name instead of being dropped in silence.
 */
class SessionLoadArmsTest {
    @Test fun `every kind this app advertises in plays has an arm, and nothing else does`() {
        // The maximal declaration: a TV, where the engine supports music and books (RaviloApp's `plays=`).
        assertEquals(SESSION_LOAD_KINDS, kindsPlayed(playsQuery(video = true, music = true, book = true)),
            "a capability outran its handler: $SESSION_LOAD_KINDS is handled, ${kindsPlayed(playsQuery(true, true, true))} is advertised")
        // The web app has no music engine: it is offered for video only, and that is all it handles.
        assertEquals(setOf("film", "episode"), kindsPlayed(playsQuery(video = true, music = false, book = false)))
        assertEquals(setOf("film", "episode"), kindsPlayed(null), "an older build that declared nothing plays video")
    }

    @Test fun `each advertised kind reaches an arm and an unknown kind reaches none`() {
        for (kind in kindsPlayed(playsQuery(true, true, true))) assertEquals(true, sessionLoadArm(kind) != null, kind)
        assertEquals(SessionLoadArm.BOOK, sessionLoadArm("audiobook"))
        assertEquals(SessionLoadArm.MUSIC, sessionLoadArm("music"))
        assertEquals(SessionLoadArm.VIDEO, sessionLoadArm("film"))
        assertEquals(SessionLoadArm.VIDEO, sessionLoadArm("episode"))
        assertNull(sessionLoadArm("book"), "the `plays=` token is not a kind — the server reads it as `audiobook`")
        assertNull(sessionLoadArm("podcast"))
    }

    @Test fun `a book session_load carries the book and its place, and takes the book arm`() {
        val env = RaviloWireJsonWithDefaults.decodeFromString(SessionLoadEnvelope.serializer(),
            """{"type":"session_load","session_id":"ps-1","kind":"audiobook","items":["part-3"],""" +
                """"book_id":"book-1","book_ms":2223874,"start_ms":120000,"sender_name":"Pixel 9 Pro"}""")
        assertEquals(SessionLoadArm.BOOK, sessionLoadArm(env.kind))
        assertEquals("book-1", env.bookId, "the items are the part being played; the book is named on its own")
        assertEquals(2223874L, env.bookMs)
        // FR-R380-4 — on that arm ◀ ▶ are ∓30 s, not the previous/next song.
        assertEquals(TvMusicAction.SkipBook(-30_000L), tvMusicAction(TvMusicKey.LEFT, book = true))
        assertEquals(TvMusicAction.SkipBook(30_000L), tvMusicAction(TvMusicKey.RIGHT, book = true))
    }

    @Test fun `an older server's book load names no book and no place, and the stored place is used`() {
        val env = RaviloWireJsonWithDefaults.decodeFromString(SessionLoadEnvelope.serializer(),
            """{"type":"session_load","session_id":"ps-1","kind":"audiobook","items":["book-1"]}""")
        assertNull(env.bookId, "then the single item IS the book (a start's items are book ids)")
        assertNull(env.bookMs, "then the place is this viewer's own, which the server keeps anyway")
    }

    @Test fun `a refusal names the session and the kind`() {
        assertEquals("""{"type":"session_load_failed","id":"ps-1","kind":"audiobook"}""", loadRefusedFrame("ps-1", "audiobook"))
    }
}
