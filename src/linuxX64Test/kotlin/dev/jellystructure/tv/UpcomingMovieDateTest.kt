package dev.jellystructure.tv

import dev.jellystructure.arr.ArrCalendarMovie
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2100 (FR-R168-5..7, -9) — a film is dated by the day Radarr will act on it, labelled by the release
 * that day is, and Radarr's cinema + 90 days estimate says so on the wire. Fake rows, today = 2026-10-22.
 */
class UpcomingMovieDateTest {
    private val today = "2026-10-22"
    private val start = "2026-04-22"
    private val end = "2026-12-21"

    private fun film(
        min: String, cinema: String? = null, digital: String? = null, physical: String? = null,
        releaseDate: String? = null, available: Boolean = false,
    ) = ArrCalendarMovie(
        id = 1, tmdbId = 1, title = "t", inCinemas = cinema, digitalRelease = digital, physicalRelease = physical,
        releaseDate = releaseDate, monitored = true, isAvailable = available, minimumAvailability = min,
    )

    private data class Placed(val release: MovieRelease?, val upcoming: Boolean, val missing: Boolean)

    private fun place(mv: ArrCalendarMovie, held: Boolean = false, queued: Boolean = false): Placed {
        val r = movieRelease(mv, start, end) ?: return Placed(null, upcoming = false, missing = false)
        val item = UpcomingItem(
            id = "mv-1", kind = MediaKind.MOVIE, title = "t", date = r.date, releaseType = r.type,
            dateEstimated = r.type == null,
            status = resolveMovieStatus(r.date, today, held, queued, mv.isAvailable),
        )
        val feed = splitFeed(listOf(item), today)
        return Placed(r, upcoming = feed.items.isNotEmpty(), missing = feed.missing.isNotEmpty())
    }

    @Test
    fun `row 1 released with a digital date is upcoming at digital`() {
        val p = place(film("released", "2026-12-03", "2026-12-18", releaseDate = "2026-12-18"))
        assertEquals(MovieRelease("2026-12-18", "digital"), p.release)
        assertTrue(p.upcoming); assertFalse(p.missing)
    }

    @Test
    fun `row 2 a date outside the window is absent`() {
        val p = place(film("released", "2026-11-05", releaseDate = "2027-02-03"))
        assertNull(p.release); assertFalse(p.upcoming); assertFalse(p.missing)
    }

    @Test
    fun `row 3 Radarr's cinema plus 90 estimate has no type and is flagged`() {
        val p = place(film("released", "2026-08-13", releaseDate = "2026-11-11"))
        assertEquals(MovieRelease("2026-11-11", null), p.release)
        assertTrue(p.upcoming)
    }

    @Test
    fun `row 4 an unheld unqueued available film whose digital date passed is missing at digital`() {
        val p = place(film("released", "2026-08-20", "2026-09-29", "2026-11-09", "2026-09-29", available = true))
        assertEquals(MovieRelease("2026-09-29", "digital"), p.release)
        assertFalse(p.upcoming); assertTrue(p.missing)
    }

    @Test
    fun `row 5 a held film is in neither list`() {
        val p = place(film("released", "2026-07-24", "2026-07-25", "2026-11-02", "2026-07-25", available = true), held = true)
        assertEquals(MovieRelease("2026-07-25", "digital"), p.release)
        assertFalse(p.upcoming); assertFalse(p.missing)
    }

    @Test
    fun `row 6 inCinemas is dated by the cinema date`() {
        val p = place(film("inCinemas", "2026-10-30", "2026-12-10", releaseDate = "2026-10-30"))
        assertEquals(MovieRelease("2026-10-30", "cinema"), p.release)
        assertTrue(p.upcoming)
    }

    @Test
    fun `row 7 announced is dated by its earliest date`() {
        val p = place(film("announced", "2026-11-20", releaseDate = "2026-11-20"))
        assertEquals(MovieRelease("2026-11-20", "cinema"), p.release)
        assertTrue(p.upcoming)
    }

    @Test
    fun `row 8 without releaseDate the estimate is derived and is the same as row 3`() {
        assertEquals(MovieRelease("2026-11-11", null), movieRelease(film("released", "2026-08-13"), start, end))
    }

    @Test
    fun `derivation follows Radarr for every minimum availability`() {
        assertEquals(MovieRelease("2026-11-01", "physical"), movieRelease(film("tba", "2026-12-01", "2026-12-10", "2026-11-01"), start, end))
        assertEquals(MovieRelease("2026-11-20", "cinema"), movieRelease(film("announced", "2026-11-20", "2026-12-10"), start, end))
        assertEquals(MovieRelease("2026-11-20", "cinema"), movieRelease(film("inCinemas", "2026-11-20", "2026-12-10"), start, end))
        assertEquals(MovieRelease("2026-12-10", "digital"), movieRelease(film("released", "2026-11-20", "2026-12-10"), start, end))
        assertEquals(MovieRelease("2026-12-10", "digital"), movieRelease(film("inCinemas", null, "2026-12-10"), start, end))
        assertEquals(MovieRelease("2026-12-10", "digital"), movieRelease(film("preDB", "2026-11-20", "2026-12-10", "2026-12-10"), start, end))
        assertNull(movieRelease(film("released"), start, end))
    }

    @Test
    fun `releaseDate with a time of day is cut to its calendar date`() {
        val r = movieRelease(film("released", digital = "2026-12-18T00:00:00Z", releaseDate = "2026-12-18T00:00:00Z"), start, end)
        assertEquals(MovieRelease("2026-12-18", "digital"), r)
    }

    @Test
    fun `an old Radarr's calendar entry without releaseDate decodes`() {
        val mv = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ArrCalendarMovie.serializer(), """{"id":1,"minimumAvailability":"released"}""")
        assertNull(mv.releaseDate)
    }

    @Test
    fun `date_estimated is on the wire only when true and an item without it decodes false`() {
        fun item(estimated: Boolean) = UpcomingItem(id = "mv-1", title = "t", date = "2026-11-11", dateEstimated = estimated)
        val on = RaviloWireJson.encodeToString(UpcomingItem.serializer(), item(true))
        assertTrue("\"date_estimated\":true" in on, on)
        val off = RaviloWireJson.encodeToString(UpcomingItem.serializer(), item(false))
        assertFalse("date_estimated" in off, off)
        val old = RaviloWireJson.decodeFromString(UpcomingItem.serializer(), """{"id":"mv-1","title":"t","date":"2026-11-11"}""")
        assertFalse(old.dateEstimated)
        assertTrue(RaviloWireJson.decodeFromString(UpcomingItem.serializer(), on).dateEstimated)
    }
}
