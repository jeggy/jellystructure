package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.auth.userDataBody
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.util.epochTicksToIso
import dev.jellystructure.util.isoToEpochSeconds
import dev.jellystructure.util.isoToEpochTicks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R375 (FR-R375-1–3) — Continue Watching's next card follows the last episode finished, in order; and R219's rule with
 * Jellyfin's Next Up stands where nothing counted was finished. Fictional series, plain data.
 */
class ContinueTargetTest {
    private val sid = "series-jf"

    private fun ep(s: Int, e: Int, id: String? = "e${s}x$e", path: String = "/tv/show/S${s}E$e.mkv", part: Int = 0) =
        Episode(filename = path.substringAfterLast('/'), path = path, seasonNumber = s, episodeNumber = e, tracks = emptyList(),
            issueCount = 0, title = "Ep $s-$e", jellyfinId = id, partIndex = part)

    private fun series(episodes: List<Episode>) = MediaItem(
        id = "show", title = "A Show", year = 2005, kind = MediaKind.TV_SHOW, path = "/tv/show", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        episodes = episodes, jellyfinId = sid,
    )

    /** Six seasons of sixteen, plus one special. */
    private val show = series((1..6).flatMap { s -> (1..16).map { e -> ep(s, e) } } + ep(0, 1, id = "sp1", path = "/tv/show/S00E01.mkv"))

    private fun watched(id: String, at: String, posTicks: Long = 0L, s: Int? = null, e: Int? = null) =
        JellyfinPlayItem(id = id, type = "Episode", name = "jf-$id", seriesId = sid, seasonNumber = s, episodeNumber = e,
            userData = JellyfinUserData(played = true, playbackPositionTicks = posTicks, lastPlayedDate = at))

    private fun resume(id: String, at: String, pct: Double) =
        JellyfinPlayItem(id = id, type = "Episode", name = "jf-$id", seriesId = sid,
            userData = JellyfinUserData(played = false, playbackPositionTicks = 1_000_000L, playedPercentage = pct, lastPlayedDate = at))

    private fun nextUp(id: String) = JellyfinPlayItem(id = id, type = "Episode", name = "jf-$id", seriesId = sid)

    private val today = "2026-10-04T10:00:00.5000000Z"
    private val august = "2026-08-12T20:00:00.0000000Z"

    private fun plan(
        lib: List<MediaItem> = listOf(show),
        resume: List<JellyfinPlayItem> = emptyList(),
        nextUp: List<JellyfinPlayItem> = emptyList(),
        finished: List<JellyfinPlayItem> = emptyList(),
        touched: List<JellyfinPlayItem> = emptyList(),
    ) = planContinue(lib, resume, nextUp, finished, touched)

    @Test fun `the sweeps case — S03E14 finished today offers S03E15 — not Jellyfins S06E07`() {
        val p = plan(
            resume = listOf(resume("e6x7", august, 40.0)),
            nextUp = listOf(nextUp("e6x7")),
            finished = listOf(watched("e6x6", august), watched("e3x14", today)),
        )
        val pick = p.picks.single()
        assertEquals("e3x15", pick.episodeId)
        assertEquals("S03E15 · Ep 3-15", pick.nextUpLabel)
        assertEquals(3, pick.seasonNumber); assertEquals(15, pick.episodeNumber)
        assertNull(pick.progressPct)
        assertEquals(isoToEpochSeconds(today), pick.lastActivityAt)
        assertEquals(today, p.anchorDates[sid])
    }

    @Test fun `a resume newer than the last finish keeps the resume card`() {
        val pick = plan(
            resume = listOf(resume("e6x7", today, 40.0)),
            finished = listOf(watched("e3x14", august)),
        ).picks.single()
        assertEquals("e6x7", pick.episodeId)
        assertEquals(0.4f, pick.progressPct)
        assertNull(pick.nextUpLabel)
    }

    @Test fun `a special finished last never moves the position`() {
        val pick = plan(finished = listOf(watched("e3x14", august), watched("sp1", today))).picks.single()
        assertEquals("e3x15", pick.episodeId)
    }

    @Test fun `a watched episode re-opened and left partway is not a finish`() {
        val pick = plan(finished = listOf(watched("e3x14", august), watched("e6x6", today, posTicks = 9_000_000_000L))).picks.single()
        assertEquals("e3x15", pick.episodeId)
    }

    @Test fun `a finished multi-episode file offers the episode after its last part — a next file reads as a range`() {
        val s = series(listOf(
            ep(1, 1, id = "f1", path = "/tv/show/S01E01-E03.mkv", part = 0), ep(1, 2, id = "f1", path = "/tv/show/S01E01-E03.mkv", part = 1),
            ep(1, 3, id = "f1", path = "/tv/show/S01E01-E03.mkv", part = 2),
            ep(1, 4, id = "f2", path = "/tv/show/S01E04-E06.mkv", part = 0), ep(1, 5, id = "f2", path = "/tv/show/S01E04-E06.mkv", part = 1),
            ep(1, 6, id = "f2", path = "/tv/show/S01E04-E06.mkv", part = 2), ep(1, 7),
        ))
        val pick = plan(lib = listOf(s), finished = listOf(watched("f1", today))).picks.single()
        assertEquals("f2", pick.episodeId)
        assertEquals("S01E04–E06 · Ep 1-4", pick.nextUpLabel)
        assertEquals(6, pick.episodeNumberEnd)
    }

    @Test fun `the last episode of a season offers the next seasons first`() {
        assertEquals("e4x1", plan(finished = listOf(watched("e3x16", today))).picks.single().episodeId)
    }

    @Test fun `the series last episode finished — an older resume stays — else the series leaves the list`() {
        val withResume = plan(resume = listOf(resume("e2x5", august, 30.0)), finished = listOf(watched("e6x16", today)), nextUp = listOf(nextUp("e2x5")))
        assertEquals("e2x5", withResume.picks.single().episodeId)
        val none = plan(finished = listOf(watched("e6x16", today)), nextUp = listOf(nextUp("e1x1")))
        assertTrue(none.picks.isEmpty(), "nothing left in order: no entry, whatever Jellyfin's Next Up says")
    }

    @Test fun `a catalog row with no Jellyfin item is skipped`() {
        val s = series(listOf(ep(1, 1), ep(1, 2, id = null), ep(1, 3)))
        assertEquals("e1x3", plan(lib = listOf(s), finished = listOf(watched("e1x1", today))).picks.single().episodeId)
    }

    @Test fun `nothing finished — or an anchor outside the catalog — Jellyfins Next Up exactly as R219 had it`() {
        val touchedOnly = plan(nextUp = listOf(nextUp("e1x2")), touched = listOf(watched("e1x1", today).copy(userData = JellyfinUserData(lastPlayedDate = today))))
        val pick = touchedOnly.picks.single()
        assertEquals("e1x2", pick.episodeId)
        assertEquals("S01E02 · jf-e1x2", pick.nextUpLabel)
        assertEquals(isoToEpochSeconds(today), pick.lastActivityAt)

        val unknownAnchor = plan(resume = listOf(resume("e2x5", august, 30.0)), nextUp = listOf(nextUp("e6x7")), finished = listOf(watched("not-in-catalog", today)))
        assertEquals("e6x7", unknownAnchor.picks.single().episodeId, "R219's row: the finish is newer and a next-up exists")
        assertTrue(unknownAnchor.anchorDates.isEmpty())
    }

    @Test fun `an in-progress unwatched next episode carries its own progress — not the series newest resume`() {
        val pick = plan(
            resume = listOf(resume("e6x7", "2026-09-01T10:00:00.0000000Z", 40.0), resume("e3x15", august, 25.0)),
            finished = listOf(watched("e3x14", today)),
        ).picks.single()
        assertEquals("e3x15", pick.episodeId)
        assertEquals(0.25f, pick.progressPct)
    }

    @Test fun `a fully watched series is in the list with an anchor mid-way — and not with it at the end`() {
        val all = show.episodes.filter { (it.seasonNumber ?: 0) >= 1 }.map { watched(it.jellyfinId!!, august) }
        // Every episode marked watched in one second: the tie goes to the last episode — no entry.
        assertTrue(plan(finished = all).picks.isEmpty())
        // Then S02E03 played to the end today: S02E04.
        val again = all.filter { it.id != "e2x3" } + watched("e2x3", today)
        assertEquals("e2x4", plan(finished = again).picks.single().episodeId)
    }

    @Test fun `ties in one second go to the later episode in order — fractions still order`() {
        val sameSecond = "2026-10-04T10:00:00.0000000Z"
        assertEquals("e2x1", plan(finished = listOf(watched("e1x5", sameSecond), watched("e1x16", sameSecond))).picks.single().episodeId)
        // Inside one second the fraction decides: S01E05 later by a millisecond.
        val pick = plan(finished = listOf(watched("e1x5", "2026-10-04T10:00:00.0010000Z"), watched("e1x16", sameSecond))).picks.single()
        assertEquals("e1x6", pick.episodeId)
    }

    @Test fun `Jellyfins sort is not trusted — the newest finish wins wherever it sits`() {
        // Oldest first: a "first one seen" rule would anchor on S01E05.
        val pick = plan(finished = listOf(watched("e1x5", august), watched("e3x14", today))).picks.single()
        assertEquals("e3x15", pick.episodeId)
        assertEquals(isoToEpochSeconds(today), pick.lastActivityAt)
    }

    @Test fun `lastFinishedEpisode reads the full timestamp and keeps Jellyfins own text`() {
        val a = assertNotNull(lastFinishedEpisode(show, listOf(watched("e1x1", today))))
        assertEquals("e1x1", a.jellyfinId)
        assertEquals(today, a.iso)
        assertEquals(isoToEpochTicks(today), a.ticks)
    }

    // ── the date helpers and the user-data body ──

    @Test fun `ticks round-trip through ISO`() {
        val t = assertNotNull(isoToEpochTicks("2026-10-04T10:00:00.1234567Z"))
        assertEquals(isoToEpochSeconds("2026-10-04T10:00:00Z")!! * 10_000_000L + 1_234_567L, t)
        assertEquals("2026-10-04T10:00:00.1234567Z", epochTicksToIso(t))
        assertEquals("2024-02-29T23:59:59.0000000Z", epochTicksToIso(isoToEpochTicks("2024-02-29T23:59:59Z")!!))
        assertEquals(isoToEpochTicks("2026-10-04T10:00:00.5Z"), isoToEpochTicks("2026-10-04T10:00:00.5000000Z"))
    }

    @Test fun `the user-data body carries a date only when given`() {
        assertEquals("""{"Played":false,"PlaybackPositionTicks":1230000}""", userDataBody(false, 1_230_000L))
        assertEquals("""{"LastPlayedDate":"2026-10-04T10:00:00.0000000Z"}""", userDataBody(null, null, "2026-10-04T10:00:00.0000000Z"))
    }
}
