package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastEpisode
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R266 — the pure half of Cast Connect: what a LOAD asks for, whose profile it plays under, and the debug app id. */
class CastConnectTest {
    private fun load(userId: String? = "u-anna", tracks: List<CastTrackItem> = emptyList(), episodes: List<CastEpisode> = emptyList()) =
        CastLoadData(serverUrl = "https://media.example.test", code = "ABC123", itemId = "item-1", title = "A film",
            kicker = "S1 · E2", positionMs = 61_000L, tracks = tracks, episodes = episodes, userId = userId)

    @Test
    fun `a film LOAD becomes a play under the casting viewer`() {
        val p = assertNotNull(castConnectPlayOf(load()))
        assertEquals("item-1", p.itemId)
        assertEquals("movie", p.kind)
        assertEquals(61_000L, p.positionMs)
        assertEquals("u-anna", p.userId)
    }

    @Test
    fun `an episode LOAD is an episode and survives the wire as the phone sends it`() {
        val text = RaviloWireJsonWithDefaults.encodeToString(CastLoadData.serializer(),
            load(episodes = listOf(CastEpisode(id = "item-1", title = "Two", kicker = "S1 · E2"))))
        val p = assertNotNull(castConnectPlayFromJson(text))
        assertEquals("episode", p.kind)
        assertEquals("u-anna", p.userId)
    }

    @Test
    fun `no viewer, no item, music or junk is refused`() {
        assertNull(castConnectPlayOf(load(userId = null)), "an older phone, or a relay of someone else's session")
        assertNull(castConnectPlayOf(load(userId = "  ")))
        assertNull(castConnectPlayOf(load().copy(itemId = "")))
        assertNull(castConnectPlayOf(load(tracks = listOf(CastTrackItem(id = "s", title = "Song")))), "the TV app has no music mode")
        assertNull(castConnectPlayFromJson(null))
        assertNull(castConnectPlayFromJson("not json"))
        assertNull(castConnectPlayFromJson("""{"contentId":"https://jellyfin.example/Videos/1/stream"}"""), "never a Jellyfin URL")
    }

    @Test
    fun `a phone-supplied viewer plays only when this TV already holds their token`() {
        val held = listOf("u-anna", "u-olivar")
        assertEquals(CastConnectVerdict.PLAY, castConnectVerdict("u-anna", held, activeUserId = "u-anna", signingIn = false))
        assertEquals(CastConnectVerdict.SWITCH_THEN_PLAY, castConnectVerdict("u-olivar", held, activeUserId = "u-anna", signingIn = false))
        assertEquals(CastConnectVerdict.SWITCH_THEN_PLAY, castConnectVerdict("u-olivar", held, activeUserId = null, signingIn = false))
        assertEquals(CastConnectVerdict.REFUSE_NO_TOKEN, castConnectVerdict("u-guest", held, activeUserId = "u-anna", signingIn = false))
        assertEquals(CastConnectVerdict.REFUSE_NO_TOKEN, castConnectVerdict("u-guest", emptyList(), activeUserId = null, signingIn = true))
        assertEquals(CastConnectVerdict.REFUSE_NOT_READY, castConnectVerdict("u-anna", held, activeUserId = "u-anna", signingIn = true))
    }

    @Test
    fun `the inbox hands a LOAD to the app and waits for its answer`() = runTest {
        val first = async { CastConnectInbox.submit(assertNotNull(castConnectPlayOf(load()))) }
        yield()
        val req = assertNotNull(CastConnectInbox.pending.value)
        CastConnectInbox.taken(req)
        assertNull(CastConnectInbox.pending.value)
        req.answer.complete(true)
        assertTrue(first.await())
        // A newer cast replaces one still waiting: the older one is answered no.
        val a = async { CastConnectInbox.submit(assertNotNull(castConnectPlayOf(load()))) }
        yield()
        val b = async { CastConnectInbox.submit(assertNotNull(castConnectPlayOf(load(userId = "u-olivar")))) }
        yield()
        assertFalse(a.await())
        val reqB = assertNotNull(CastConnectInbox.pending.value)
        assertEquals("u-olivar", reqB.play.userId)
        reqB.answer.complete(false)
        assertFalse(b.await())
        // Nobody answers: it times out as a refusal.
        assertFalse(CastConnectInbox.submit(assertNotNull(castConnectPlayOf(load())), timeoutMs = 50L))
        assertNull(CastConnectInbox.pending.value)
    }

    @Test
    fun `a debug build casts with the development app id and a release build with the server's`() {
        assertEquals("A1B2C3D4", effectiveCastAppId("A1B2C3D4", null))
        assertEquals("A1B2C3D4", effectiveCastAppId("A1B2C3D4", ""))
        assertEquals("EA91BAE4", effectiveCastAppId("A1B2C3D4", "ea91bae4"))
        assertEquals("A1B2C3D4", effectiveCastAppId("A1B2C3D4", "not-hex!"), "a typo never casts to nothing")
        assertNull(effectiveCastAppId(null, "EA91BAE4"), "casting off on the server stays off: absent, never greyed")
    }
}
