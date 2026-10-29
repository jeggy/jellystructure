package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.util.isoToEpochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2916 (FR-1, FR-4, FR-6) — a newest-first series offers Continue Watching only its newest episode: resume when it is
 * in progress, new when it is unwatched, nothing when it is watched; an older episode never gives a card. A stand-in
 * daily programme, 12 episodes of season 2026 numbered by air date.
 */
class NewestFirstContinueTest {
    private val sid = "daily-jf"

    private fun ep(n: Int, created: Long = 1_000L + n, airDate: String? = "2026-10-${(n - 271).toString().padStart(2, '0')}") = Episode(  // E291 = 20 Oct … E302 = 31 Oct
        filename = "S2026E$n.mkv", path = "/tv/daily/S2026E$n.mkv", seasonNumber = 2026, episodeNumber = n, tracks = emptyList(),
        issueCount = 0, title = "Day $n", jellyfinId = "d$n", airDate = airDate, createdAt = created,
    )

    private val episodes = (291..302).map { ep(it) }
    private fun series(eps: List<Episode> = episodes, newestFirst: Boolean? = null) = MediaItem(
        id = "daily", title = "Daily", year = 2026, kind = MediaKind.TV_SHOW, path = "/tv/daily", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        episodes = eps, jellyfinId = sid, newestFirst = newestFirst,
    )

    private fun watched(n: Int, at: String) = JellyfinPlayItem(id = "d$n", type = "Episode", name = "jf-$n", seriesId = sid,
        userData = JellyfinUserData(played = true, playbackPositionTicks = 0L, lastPlayedDate = at))

    private fun resume(n: Int, at: String, pct: Double) = JellyfinPlayItem(id = "d$n", type = "Episode", name = "jf-$n", seriesId = sid,
        userData = JellyfinUserData(played = false, playbackPositionTicks = 1_000_000L, playedPercentage = pct, lastPlayedDate = at))

    private fun touched(n: Int, at: String) = JellyfinPlayItem(id = "d$n", type = "Episode", name = "jf-$n", seriesId = sid,
        userData = JellyfinUserData(played = false, playbackPositionTicks = 0L, lastPlayedDate = at))

    private val early = "2026-10-20T08:00:00.0000000Z"
    private val late = "2026-10-29T08:00:00.0000000Z"
    private val lateS = isoToEpochSeconds(late)!!

    private fun plan(
        lib: List<MediaItem> = listOf(series()),
        resume: List<JellyfinPlayItem> = emptyList(),
        nextUp: List<JellyfinPlayItem> = emptyList(),
        finished: List<JellyfinPlayItem> = emptyList(),
        touched: List<JellyfinPlayItem> = emptyList(),
        marked: (MediaItem) -> Boolean = { true },
    ) = planContinue(lib, resume, nextUp, finished, touched, marked)

    // ── FR-1 / FR-4 ─────────────────────────────────────────────────────────────

    @Test fun `FR-1 — only a TV series can be newest first and the admin's choice outranks Auto-fetch`() {
        val s = series()
        assertTrue(isNewestFirst(s, dateNumbered = true)); assertTrue(!isNewestFirst(s, dateNumbered = false))
        assertTrue(isNewestFirst(series(newestFirst = true), dateNumbered = false))
        assertTrue(!isNewestFirst(series(newestFirst = false), dateNumbered = true), "the admin's off beats a date-numbered fetcher")
        assertTrue(!isNewestFirst(s.copy(kind = MediaKind.MOVIE, newestFirst = true), dateNumbered = true))
        assertEquals(NewestFirstSource.AUTO, newestFirstSource(s, true))
        assertEquals(NewestFirstSource.OFF, newestFirstSource(s, false))
        assertEquals(NewestFirstSource.ADMIN, newestFirstSource(series(newestFirst = false), true))
    }

    @Test fun `FR-4 — the newest is the last counted episode and a multi-episode file gives its first part`() {
        assertEquals("d302", newestEpisode(series())!!.jellyfinId)
        val special = Episode(filename = "sp.mkv", path = "/tv/daily/sp.mkv", seasonNumber = 0, episodeNumber = 1, tracks = emptyList(), issueCount = 0, jellyfinId = "sp")
        val noJf = ep(303).copy(jellyfinId = null)
        assertEquals("d302", newestEpisode(series(episodes + special + noJf))!!.jellyfinId, "specials and unplayable rows never count")
        val shared = "/tv/daily/S2026E302E303.mkv"
        val twoInOne = episodes.dropLast(1) + ep(302).copy(path = shared, jellyfinId = "dd", partIndex = 0, partCount = 2) +
            ep(303).copy(path = shared, jellyfinId = "dd", partIndex = 1, partCount = 2)
        val first = newestEpisode(series(twoInOne))!!
        assertEquals("dd", first.jellyfinId); assertEquals(302, first.episodeNumber)
        assertNull(newestEpisode(series(emptyList())))
    }

    // ── FR-6: the table ────────────────────────────────────────────────────────

    @Test fun `FR-6 — the newest in progress is a resume card with its progress the title alone and the air date`() {
        val pick = plan(resume = listOf(resume(302, late, 40.0)), finished = listOf(watched(300, early))).picks.single()
        assertEquals("d302", pick.episodeId)
        assertEquals(0.4f, pick.progressPct)
        assertEquals("Day 302", pick.nextUpLabel)
        assertEquals(2026, pick.seasonNumber); assertEquals(302, pick.episodeNumber)
        assertEquals("2026-10-31", pick.episodeAirDate)
    }

    @Test fun `FR-6 — the newest unwatched with no position is a new card with no progress`() {
        val pick = plan(finished = listOf(watched(301, early))).picks.single()
        assertEquals("d302", pick.episodeId)
        assertNull(pick.progressPct)
        assertEquals("Day 302", pick.nextUpLabel)
        assertEquals("2026-10-31", pick.episodeAirDate)
    }

    @Test fun `FR-6 — the newest watched gives no entry whatever is unwatched behind it`() {
        val p = plan(finished = listOf(watched(291, early), watched(302, late)))
        assertEquals(emptyList(), p.picks)
    }

    @Test fun `FR-6 — an older episode in progress never makes a card the newest does`() {
        val pick = plan(resume = listOf(resume(295, late, 70.0))).picks.single()
        assertEquals("d302", pick.episodeId)
        assertNull(pick.progressPct, "the older episode's progress is not the newest's")
    }

    @Test fun `FR-6 — an older episode in progress with the newest watched gives nothing`() {
        assertEquals(emptyList(), plan(resume = listOf(resume(295, late, 70.0)), finished = listOf(watched(302, early))).picks)
    }

    @Test fun `FR-6 — membership is still genuinely started`() {
        assertEquals(emptyList(), plan().picks, "never touched ⇒ not in the row")
        assertEquals(1, plan(touched = listOf(touched(293, late))).picks.size, "a touch counts (R219)")
    }

    @Test fun `FR-6 — the activity time is the later of the viewer's last activity and the newest episode's arrival`() {
        val arrivedAfter = series(episodes.dropLast(1) + ep(302, created = lateS + 3_600L))
        val a = plan(lib = listOf(arrivedAfter), finished = listOf(watched(301, late))).picks.single()
        assertEquals(lateS + 3_600L, a.lastActivityAt, "a new episode brings the series forward")
        val b = plan(finished = listOf(watched(301, late))).picks.single()
        assertEquals(lateS, b.lastActivityAt, "the viewer's activity is later than the old arrival")
    }

    @Test fun `FR-6 — a newer arrival moves the card ahead of another series`() {
        val other = MediaItem(
            id = "other", title = "Other", year = 2020, kind = MediaKind.TV_SHOW, path = "/tv/other", tmdbId = null, originalLanguage = null,
            posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = "other-jf",
            episodes = listOf(Episode(filename = "o1.mkv", path = "/tv/other/o1.mkv", seasonNumber = 1, episodeNumber = 1, tracks = emptyList(), issueCount = 0, title = "O1", jellyfinId = "o1")),
        )
        val otherResume = JellyfinPlayItem(id = "o1", type = "Episode", name = "o1", seriesId = "other-jf",
            userData = JellyfinUserData(played = false, playbackPositionTicks = 1_000_000L, playedPercentage = 10.0, lastPlayedDate = late))
        val fresh = series(episodes.dropLast(1) + ep(302, created = lateS + 60L))
        val picks = plan(lib = listOf(fresh, other), resume = listOf(otherResume), finished = listOf(watched(301, early)), marked = { it.id == "daily" }).picks
        assertEquals(listOf("d302", "o1"), picks.map { it.episodeId })
    }

    @Test fun `FR-6 — a multi-episode newest file gives one card on its first part with the range end`() {
        val shared = "/tv/daily/S2026E302E303.mkv"
        val eps = episodes.dropLast(1) + ep(302).copy(path = shared, jellyfinId = "dd", partIndex = 0, partCount = 2) +
            ep(303).copy(path = shared, jellyfinId = "dd", partIndex = 1, partCount = 2)
        val pick = plan(lib = listOf(series(eps)), finished = listOf(watched(301, early))).picks.single()
        assertEquals("dd", pick.episodeId); assertEquals(302, pick.episodeNumber); assertEquals(303, pick.episodeNumberEnd)
    }

    @Test fun `FR-6 — an episode with no air date or an odd one sets no episode_air_date`() {
        val none = series(episodes.dropLast(1) + ep(302, airDate = null))
        assertNull(plan(lib = listOf(none), finished = listOf(watched(301, early))).picks.single().episodeAirDate)
        val odd = series(episodes.dropLast(1) + ep(302, airDate = "29 Oct"))
        assertNull(plan(lib = listOf(odd), finished = listOf(watched(301, early))).picks.single().episodeAirDate)
    }

    @Test fun `FR-6 — the next in order is never computed so an unwatched day behind the newest gives no card`() {
        // Watched E291 would, in order, point at E292 (R375). A newest-first series points at E302 and nothing else.
        val pick = plan(finished = listOf(watched(291, late))).picks.single()
        assertEquals("d302", pick.episodeId)
        assertTrue(plan(finished = listOf(watched(291, late))).anchorDates.isEmpty(), "no anchor ⇒ no shuffle's anchor−1 s")
    }

    @Test fun `a series that is not marked behaves exactly as before`() {
        val pick = plan(finished = listOf(watched(291, late)), marked = { false }).picks.single()
        assertEquals("d292", pick.episodeId, "R375: the next in order")
        assertNull(pick.episodeAirDate)
        assertEquals("S2026E292 · Day 292", pick.nextUpLabel)
    }
}
