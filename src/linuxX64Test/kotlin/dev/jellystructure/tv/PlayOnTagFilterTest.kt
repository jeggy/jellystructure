package dev.jellystructure.tv

import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2518 (FR-6) — *Play on…* checks the caller: a start of an item the **calling** device cannot see answers 404 and
 * writes no session, and a target outside `targetsFor(caller)` is refused before anything is written. Spec:
 * `a-tag-filter-holds-on-every-ravilo-surface.md`.
 */
class PlayOnTagFilterTest {
    private fun film(id: String, tags: List<String>) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), tags = tags, jellyfinId = id,
    )

    private val catalogue = listOf(film("film-household", listOf("t")), film("film-other", listOf("u")))

    private fun SessionLoopback.filtered(id: String, user: String): dev.jellystructure.auth.DeviceData {
        val d = devices.loginDevice(
            deviceId = id, deviceName = id, jellyfinUserId = user, jellyfinUsername = user.removePrefix("u-"), jellyfinUserToken = "jf",
            isAdmin = false, isKids = false, allowedTags = setOf("t"), platform = "phone", kind = "phone",
        ).first
        devices.recordAddress(id, user, "198.51.100.7")
        return d
    }

    @Test fun `a start of a held title the caller cannot see is 404 and writes no session`() = loopback("playon-hidden") {
        starter.canSee = { viewer, _, id, _ -> catalogue.firstOrNull { it.jellyfinId == id }?.visibleTo(viewer) == true }
        val phone = filtered("phone-t", "u-anna")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-other"]}""", phone)
        assertEquals(404, r.status, r.body)
        assertTrue(sessions.all().isEmpty(), "no session row was written, so no title or still reached one")
        assertTrue(ts.frames("session_load").isEmpty())
    }

    @Test fun `a queue with one title the caller cannot see is refused whole`() = loopback("playon-queue") {
        starter.canSee = { viewer, _, id, _ -> catalogue.firstOrNull { it.jellyfinId == id }?.visibleTo(viewer) == true }
        val phone = filtered("phone-t", "u-anna")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video")
        open(tv, "sessions,session_control", plays = "video")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-household","film-other"]}""", phone)
        assertEquals(404, r.status, r.body)
        assertTrue(sessions.all().isEmpty())
    }

    @Test fun `a start of a title the caller can see still goes through`() = loopback("playon-visible") {
        starter.canSee = { viewer, _, id, _ -> catalogue.firstOrNull { it.jellyfinId == id }?.visibleTo(viewer) == true }
        val phone = filtered("phone-t", "u-anna")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video")
        val ts = open(tv, "sessions,session_control", plays = "video")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-household"]}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets session_load") { ts.frames("session_load").any { "film-household" in it } }
    }

    @Test fun `a target the caller is not offered is refused before anything is written`() = loopback("playon-target") {
        val ben = device("phone-b", "u-ben", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        devices.recordAddress("tv-a", "u-anna", "203.0.113.9")   // another household's network
        open(ben, "sessions,session_control", plays = "video")
        open(tv, "sessions,session_control", plays = "video")
        assertTrue(starter.targetsFor(fresh(ben)) { null }.none { it.id == "tv-a" })
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-1"]}""", ben)
        assertEquals(409, r.status, r.body)
        assertTrue(sessions.all().isEmpty())
    }
}
