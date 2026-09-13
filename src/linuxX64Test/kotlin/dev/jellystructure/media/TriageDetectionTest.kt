package dev.jellystructure.media

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 144 — cover-art-muxed-as-video detection. */
class TriageDetectionTest {

    private fun vid(idx: Int, spec: String, codec: String) = Track(idx, spec, TrackKind.VIDEO, codec, null, null, idx == 0, false)
    private fun aud(idx: Int, spec: String) = Track(idx, spec, TrackKind.AUDIO, "eac3", "eng", null, true, false)

    private fun movie(tracks: List<Track>): MediaItem = MediaItem(
        id = "m", title = "m", year = null, kind = MediaKind.MOVIE, path = "/x", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = tracks, issueCount = 0, scannedAt = 0L,
    )

    private fun episode(filename: String, tracks: List<Track>) =
        Episode(filename = filename, path = "/x/$filename", seasonNumber = 1, episodeNumber = 1, tracks = tracks, issueCount = 0)

    private fun series(episodes: List<Episode>): MediaItem = MediaItem(
        id = "s", title = "s", year = null, kind = MediaKind.TV_SHOW, path = "/x", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = episodes,
    )

    @Test
    fun `png cover beside real video is flagged`() {
        val tracks = listOf(vid(0, "0:v:0", "h264"), vid(1, "0:v:1", "png"), aud(2, "0:a:0"))
        assertEquals(1, TriageDetection.coverAsVideoCount(movie(tracks)))
        assertEquals("0:v:1", TriageDetection.coverVideoSpecifier(tracks))
    }

    @Test
    fun `single real video is not flagged`() {
        val tracks = listOf(vid(0, "0:v:0", "h264"), aud(1, "0:a:0"))
        assertEquals(0, TriageDetection.coverAsVideoCount(movie(tracks)))
        assertNull(TriageDetection.coverVideoSpecifier(tracks))
    }

    @Test
    fun `image-only file with no real video is not flagged`() {
        // A genuinely image-only container has no real video to pair with — not the cover-as-video case.
        val tracks = listOf(vid(0, "0:v:0", "mjpeg"), aud(1, "0:a:0"))
        assertEquals(0, TriageDetection.coverAsVideoCount(movie(tracks)))
        assertNull(TriageDetection.coverVideoSpecifier(tracks))
    }

    @Test
    fun `series sums cover tracks across episodes`() {
        // Two episodes carry a cover.png beside h264; one is clean.
        val s = series(listOf(
            episode("S01E01.mkv", listOf(vid(0, "0:v:0", "h264"), vid(1, "0:v:1", "png"), aud(2, "0:a:0"))),
            episode("S01E02.mkv", listOf(vid(0, "0:v:0", "h264"), vid(1, "0:v:1", "png"), aud(2, "0:a:0"))),
            episode("S01E03.mkv", listOf(vid(0, "0:v:0", "h264"), aud(1, "0:a:0"))),
        ))
        assertEquals(2, TriageDetection.coverAsVideoCount(s))
    }

    // Phase 201 amendment (2026-09-13) — mkv_track_layout folded into the triage framework.
    @Test
    fun `movie whose own path is in the broken set counts as one`() {
        val m = movie(emptyList()).copy(path = "/mnt/movies/Sintel/Sintel.mkv")
        assertEquals(1, TriageDetection.mkvLayoutBrokenCount(m, setOf("/mnt/movies/Sintel/Sintel.mkv")))
        assertEquals(0, TriageDetection.mkvLayoutBrokenCount(m, emptySet()))
    }

    @Test
    fun `series counts only its own broken episode paths`() {
        val s = series(listOf(
            episode("S01E01.mkv", emptyList()),
            episode("S01E02.mkv", emptyList()),
            episode("S01E03.mkv", emptyList()),
        ))
        val broken = setOf("/x/S01E01.mkv", "/x/S01E03.mkv", "/some/other/show/S02E04.mkv")
        assertEquals(2, TriageDetection.mkvLayoutBrokenCount(s, broken))
    }

    @Test
    fun `no overlap with the broken set counts as zero`() {
        val s = series(listOf(episode("S01E01.mkv", emptyList())))
        assertEquals(0, TriageDetection.mkvLayoutBrokenCount(s, setOf("/unrelated/path.mkv")))
    }
}
