package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 300 — the rules for a borrowed Jellyfin token: the hand-off (FR-300-2), the donors (FR-300-4), the followers (FR-300-3). */
class ReceiverTokenPolicyTest {
    private fun row(id: String, kind: String, token: String, user: String = "u1", seen: Long = 0L) = DeviceData(
        deviceId = id, deviceToken = "dt-$id", jellyfinUserId = user, jellyfinUsername = "viewer",
        jellyfinUserToken = token, isAdmin = false, lastSeen = seen, kind = kind,
    )

    @Test
    fun `only receivers and screens borrow a token`() {
        assertTrue(ReceiverTokenPolicy.borrowsToken("cast"))
        assertTrue(ReceiverTokenPolicy.borrowsToken("screen"))
        for (k in listOf("tv", "phone", "web", "desktop", null)) assertFalse(ReceiverTokenPolicy.borrowsToken(k), "$k signs in itself")
    }

    @Test
    fun `a hand-off adopts the sender's token`() {
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", false, null, false), "first enrolment")
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", false, "old", false), "a live receiver token is still replaced")
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", false, "old", true), "a dead receiver token is replaced")
        assertEquals("same", ReceiverTokenPolicy.tokenOnHandoff("same", false, "same", false))
    }

    @Test
    fun `a sender whose token was just rejected does not overwrite a live receiver token`() {
        assertEquals("own", ReceiverTokenPolicy.tokenOnHandoff("sender", true, "own", false))
        assertEquals("own", ReceiverTokenPolicy.tokenOnHandoff("", false, "own", false), "a blank sender token never overwrites")
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", true, "own", true), "both dead: the sender's, as before")
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", true, null, false), "no row of its own: the sender's")
        assertEquals("sender", ReceiverTokenPolicy.tokenOnHandoff("sender", true, "", false))
    }

    @Test
    fun `donors are the same user's own sign-ins newest first three at most`() {
        val receiver = row("cast-1", "cast", "dead")
        val rows = listOf(
            receiver,
            row("mac", "tv", "t-mac", seen = 50),
            row("pixel", "phone", "t-pixel", seen = 90),
            row("web", "web", "t-web", seen = 70),
            row("linux", "desktop", "t-linux", seen = 60),
            row("other-cast", "cast", "t-cast", seen = 99),           // borrows itself: never a donor
            row("screen", "screen", "t-screen", seen = 98),           // same
            row("kid", "phone", "t-kid", user = "u2", seen = 100),    // another user: never
            row("old-mac", "tv", "dead", seen = 95),                  // the rejected token itself
            row("blank", "phone", "", seen = 94),
            row("pixel-twin", "phone", "t-pixel", seen = 10),         // the same token twice counts once
        )
        assertEquals(listOf("pixel", "web", "linux"), ReceiverTokenPolicy.donors(receiver, rows).map { it.deviceId })
        assertEquals(listOf("pixel", "linux", "mac"), ReceiverTokenPolicy.donors(receiver, rows) { it == "t-web" }.map { it.deviceId }, "a negative-cached token is skipped")
        assertEquals(emptyList(), ReceiverTokenPolicy.donors(receiver, rows.filter { it.jellyfinUserId == "u2" || ReceiverTokenPolicy.borrowsToken(it.kind) }))
    }

    @Test
    fun `a re-sign-in moves only the borrowing rows that held the old token`() {
        val rows = listOf(
            row("cast-a", "cast", "old"),
            row("cast-b", "cast", "other"),
            row("screen-a", "screen", "old"),
            row("tv", "tv", "old"),                       // an own sign-in elsewhere with the same token is not ours to move
            row("cast-u2", "cast", "old", user = "u2"),
        )
        assertEquals(listOf("cast-a", "screen-a"), ReceiverTokenPolicy.followers("tv", "u1", "old", "new", rows).map { it.deviceId })
        assertEquals(emptyList(), ReceiverTokenPolicy.followers("cast", "u1", "old", "new", rows), "a receiver's own enrolment moves nobody")
        assertEquals(emptyList(), ReceiverTokenPolicy.followers("tv", "u1", null, "new", rows), "a first sign-in")
        assertEquals(emptyList(), ReceiverTokenPolicy.followers("tv", "u1", "old", "old", rows), "the token did not change")
        assertEquals(emptyList(), ReceiverTokenPolicy.followers("tv", "u1", "old", "", rows))
    }
}
