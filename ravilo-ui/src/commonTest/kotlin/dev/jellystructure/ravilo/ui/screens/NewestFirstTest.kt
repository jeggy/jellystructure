package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-2938 (DEB-2916, FR-8..FR-10, FR-12, FR-15, FR-16) — a newest-first series on the series page and the Continue
 * tile. The stand-in programme is *Dagbók*: a daily programme numbered by air date, season 2026, 12 episodes.
 */
class NewestFirstTest {
    // Episode n of 2026 is E(290 + n), aired on 2026-10-(n + 17): E302 is Thu 29 Oct, E291 is Mon 18 Oct.
    private fun ep(n: Int, airDate: String? = "2026-10-${n + 17}", season: Int = 2026) =
        Episode(id = "e$n", episodeNumber = 290 + n, title = "Dagbók $n", runtime = 28, overview = null, stillUrl = null,
            airDate = airDate, file = "/f/$season/e$n.mkv")

    private fun dagbok(newestFirst: Boolean = true, extra: List<Season> = emptyList()) = SeriesDetail(
        card = MediaCard(id = "series", kind = MediaKind.SERIES, title = "Dagbók", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null),
        synopsis = null,
        seasons = listOf(Season(2026, "Season 2026", (1..12).map { ep(it) })) + extra,
        cast = emptyList(), related = emptyList(), newestFirst = newestFirst,
    )

    private fun watched(vararg ids: String) = ids.associateWith { CardPlayState(played = true, playedPct = 1f) } + ("series" to CardPlayState())
    private val upToWed = (1..11).map { "e$it" }.toTypedArray()   // everything but E302 (Thu 29 Oct)

    private fun en(key: String, vars: Map<String, String>) = t(key, "en", vars)

    // ── FR-8 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-8 the seasons run newest first with Specials last and each season's episodes newest first`() {
        val specials = Season(0, "Specials", listOf(ep(1, season = 0).copy(id = "sp1"), ep(2, season = 0).copy(id = "sp2")))
        val season2025 = Season(2025, "Season 2025", listOf(ep(1, "2025-12-30", 2025).copy(id = "o1"), ep(2, "2025-12-31", 2025).copy(id = "o2")))
        val wire = SeriesDetail(
            card = dagbok().card, synopsis = null, cast = emptyList(), related = emptyList(), newestFirst = true,
            seasons = listOf(season2025, dagbok().seasons[0], specials),
        )
        val shown = wire.forDisplay().seasons
        assertEquals(listOf(2026, 2025, 0), shown.map { it.index })
        assertEquals((12 downTo 1).map { "e$it" }, shown[0].episodes.map { it.id })
        assertEquals(listOf("o2", "o1"), shown[1].episodes.map { it.id })
        assertEquals(listOf("sp2", "sp1"), shown[2].episodes.map { it.id })
    }

    @Test fun `FR-8 a series that is not newest first is drawn as the server sent it`() {
        val wire = dagbok(newestFirst = false)
        assertEquals(wire, wire.forDisplay())
    }

    @Test fun `FR-8 a multi-episode file stays one slot with its parts in order`() {
        val parts = (1..3).map { ep(it).copy(file = "/f/a.mkv", partIndex = it - 1, partCount = 3, id = "p1") }
        val wire = dagbok().let { it.copy(seasons = listOf(Season(2026, "Season 2026", parts + ep(4)))) }
        val shown = wire.forDisplay().seasons[0].episodes
        assertEquals(listOf("e4", "p1", "p1", "p1"), shown.map { it.id })
        assertEquals(listOf(291, 292, 293), shown.drop(1).map { it.episodeNumber }, "the file's first part stays first")
    }

    @Test fun `FR-8 the page opens on the season and slot of the newest`() {
        val wire = dagbok(extra = listOf(Season(0, "Specials", listOf(ep(1, season = 0).copy(id = "sp1")))))
        val shown = wire.forDisplay()
        val overlay = watched(*upToWed)
        val primary = primaryEpisodeId(shown, overlay)
        assertEquals("e12", primary)
        assertEquals(0, openingSeasonIndex(shown.seasons, overlay, primary))
        val groups = episodeGroups(shown.seasons[0].episodes)
        assertEquals(0, railOpeningIndex(groups, overlay, primary, newestFirst = true))
        // A season that does not hold it opens at its newest too, not on "the first unwatched" (which is the newest unwatched).
        assertEquals(0, railOpeningIndex(groups, overlay, primaryId = null, newestFirst = true))
        assertEquals(0, railOpeningIndex(groups, watched(), primaryId = null, newestFirst = true))
        // The card the viewer comes back to still wins.
        assertEquals(3, railOpeningIndex(groups, overlay, primary, anchorId = "e9", newestFirst = true))
    }

    // ── FR-9 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-9 an air date reads as a weekday a day and a month`() {
        assertEquals("Thu 29 Oct", airDateText("2026-10-29", 2026, ::en))
        assertEquals("Mon 5 Jan", airDateText("2026-01-05", 2026, ::en))
    }

    @Test fun `FR-9 a date in another calendar year says its year`() {
        assertEquals("Dec 31, 2025", airDateText("2025-12-31", 2026, ::en))
        assertEquals("Jan 1, 2027", airDateText("2027-01-01", 2026, ::en))
    }

    @Test fun `FR-9 no date or an unreadable one keeps the code`() {
        assertNull(airDateText(null, 2026, ::en))
        assertNull(airDateText("", 2026, ::en))
        assertNull(airDateText("29/10/2026", 2026, ::en))
        assertNull(airDateText("2026-02-31", 2026, ::en))
        val dated = ep(12)
        val undated = ep(12, airDate = null)
        assertEquals(airDateText("2026-10-29", currentYearUtc(), ::en), episodeName(true, dated, "en") { "S2026E302" })
        assertEquals("S2026E302", episodeName(true, undated, "en") { "S2026E302" })
        assertEquals("S2026E302", episodeName(false, dated, "en") { "S2026E302" }, "a series that is not newest first keeps its code")
    }

    @Test fun `FR-9 the weekday and the month follow the viewer's language`() {
        assertEquals("tor 29. okt", airDateText("2026-10-29", "da", 2026)?.lowercase())
    }

    // ── FR-10 ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-10 Play plays the newest in all four states`() {
        val shown = dagbok().forDisplay()
        // never started
        assertEquals("e12", primaryEpisodeId(shown, watched()))
        // watched up to Wed 28 Oct
        assertEquals("e12", primaryEpisodeId(shown, watched(*upToWed)))
        // the newest half watched
        val inProgress = watched(*upToWed) + ("e12" to CardPlayState(resumeMs = 600_000, playedPct = 0.4f))
        assertEquals("e12", primaryEpisodeId(shown, inProgress))
        // the newest played to the end — and an older one skipped
        val caughtUp = watched("e12", "e11", "e9")
        assertEquals("e12", primaryEpisodeId(shown, caughtUp))
        assertEquals("e12", primaryEpisodeId(dagbok().forDisplay(), caughtUp), "the same, in wire order as in display order")
    }

    @Test fun `FR-10 the server's pointer does not move the button on a newest-first series`() {
        val overlay = watched("e1") + ("series" to CardPlayState(continueEpisodeId = "e2"))
        assertEquals("e12", primaryEpisodeId(dagbok().forDisplay(), overlay))
        assertEquals("e2", primaryEpisodeId(dagbok(newestFirst = false), overlay), "a series that is not marked still follows it")
    }

    @Test fun `FR-10 a catalog-only newer row is not the newest`() {
        val wire = dagbok().let { d -> d.copy(seasons = listOf(Season(2026, "Season 2026", d.seasons[0].episodes + ep(13).copy(id = "/f/e13.mkv#303")))) }
        assertEquals("e12", primaryEpisodeId(wire.forDisplay(), watched()))
    }

    @Test fun `FR-10 the button reads Resume for the newest in progress and Play newest otherwise`() {
        assertEquals("Play newest · Thu 29 Oct", newestButtonLabel("Thu 29 Oct", inProgress = false, lang = "en"))
        assertEquals("Resume · Thu 29 Oct", newestButtonLabel("Thu 29 Oct", inProgress = true, lang = "en"))
        assertEquals("Play newest · S2026E302", newestButtonLabel("S2026E302", inProgress = false, lang = "en"))
        val shown = dagbok().forDisplay()
        val newest = newestEpisode(shown.seasons)
        assertEquals(false, newestInProgress(newest, watched()))
        assertEquals(false, newestInProgress(newest, watched("e12")), "watched is not in progress")
        assertEquals(true, newestInProgress(newest, watched() + ("e12" to CardPlayState(resumeMs = 5_000))))
    }

    // ── FR-12 ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-12 the state pill and the NEW ribbon follow one condition`() {
        val shown = dagbok().forDisplay()
        assertEquals(NewestState.NONE, newestState(shown, emptyMap()), "nothing before the playstate lands")
        assertEquals(NewestState.NONE, newestState(shown, watched()), "never started: every episode is equally new")
        assertEquals(NewestState.NEW_EPISODE, newestState(shown, watched(*upToWed)))
        assertEquals(NewestState.NEW_EPISODE, newestState(shown, watched("e3")), "any older episode watched")
        assertEquals(NewestState.CAUGHT_UP, newestState(shown, watched("e12")), "the newest watched, an older one not")
        assertEquals(NewestState.CAUGHT_UP, newestState(shown, watched(*upToWed, "e12")))
        val half = watched(*upToWed) + ("e12" to CardPlayState(resumeMs = 600_000, playedPct = 0.4f))
        assertEquals(NewestState.NONE, newestState(shown, half), "the newest in progress")
        assertEquals(NewestState.NONE, newestState(dagbok(newestFirst = false), watched(*upToWed)), "another series")
    }

    // ── FR-15 ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-15 the Continue badge says New and the date with no progress and the date alone with progress`() {
        assertEquals("New · Thu 29 Oct", continueDateBadge("2026-10-29", 0f, "en", 2026))
        assertEquals("Thu 29 Oct", continueDateBadge("2026-10-29", 0.4f, "en", 2026))
        assertEquals("New · Dec 31, 2025", continueDateBadge("2025-12-31", 0f, "en", 2026))
    }

    @Test fun `FR-15 a card with no air date keeps its code badge`() {
        assertNull(continueDateBadge(null, 0f, "en", 2026))
        assertNull(continueDateBadge("soon", 0.4f, "en", 2026))
    }

    // ── FR-16 ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `FR-16 the four keys ship in every language`() {
        assertEquals("Play newest", t("detail.play_newest", "en"))
        assertEquals("Caught up", t("detail.caught_up", "en"))
        assertEquals("Newest first — this series lists its newest episode first", t("detail.newest_first_a11y", "en"))
        assertEquals("New · Thu 29 Oct", t("tile.new_on", "en", mapOf("date" to "Thu 29 Oct")))
        assertEquals("Afspil nyeste", t("detail.play_newest", "da"))
        assertEquals("Ny · Tor 29. okt", t("tile.new_on", "da", mapOf("date" to "Tor 29. okt")))
        assertEquals("Spæl nýggjasta", t("detail.play_newest", "fo"))
        assertEquals("Dagført", t("detail.caught_up", "fo"))
    }
}
