package dev.jellystructure.tv

import dev.jellystructure.model.Episode
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 200 (FR-200-4) — the series flag strip must be the union of every episode's languages, not
 * episode 1's alone. Measured live: 64 of 182 multi-episode series disagree between episodes (Modern
 * Family's strip showed `eng` while 20 more languages, including `dan`/`swe`/`nor`/`fin`, sat on other
 * episodes unseen).
 */
class DetailServiceLanguageUnionTest {

    private fun track(kind: TrackKind, lang: String?) = Track(
        streamIndex = 0, specifier = "0:${if (kind == TrackKind.AUDIO) "a" else "s"}:0",
        kind = kind, codec = "srt", language = lang, title = null, default = false, forced = false,
    )

    private fun episode(season: Int, ep: Int, tracks: List<Track>) = Episode(
        filename = "S${season}E${ep}.mkv", path = "/mnt/tv/Show/S${season}E${ep}.mkv",
        seasonNumber = season, episodeNumber = ep, tracks = tracks, issueCount = 0,
    )

    @Test
    fun `languages present only on a later episode are still included`() {
        val eps = listOf(
            episode(1, 1, listOf(track(TrackKind.SUBTITLE, "eng"))),
            episode(1, 2, listOf(track(TrackKind.SUBTITLE, "eng"), track(TrackKind.SUBTITLE, "dan"))),
        )

        assertEquals(listOf("eng", "dan"), unionLanguagesInFirstSeenOrder(eps, TrackKind.SUBTITLE))
    }

    @Test
    fun `order follows the first episode that has each language and not alphabetical`() {
        val eps = listOf(
            episode(1, 1, listOf(track(TrackKind.AUDIO, "swe"))),
            episode(1, 2, listOf(track(TrackKind.AUDIO, "eng"), track(TrackKind.AUDIO, "dan"))),
        )

        assertEquals(listOf("swe", "eng", "dan"), unionLanguagesInFirstSeenOrder(eps, TrackKind.AUDIO))
    }

    @Test
    fun `a repeated language across episodes is not duplicated`() {
        val eps = listOf(
            episode(1, 1, listOf(track(TrackKind.AUDIO, "eng"))),
            episode(1, 2, listOf(track(TrackKind.AUDIO, "eng"))),
        )

        assertEquals(listOf("eng"), unionLanguagesInFirstSeenOrder(eps, TrackKind.AUDIO))
    }

    @Test
    fun `an untagged track never contributes a null entry`() {
        val eps = listOf(episode(1, 1, listOf(track(TrackKind.SUBTITLE, null), track(TrackKind.SUBTITLE, "fao"))))

        assertEquals(listOf("fao"), unionLanguagesInFirstSeenOrder(eps, TrackKind.SUBTITLE))
    }

    @Test
    fun `no episodes returns an empty list`() {
        assertEquals(emptyList(), unionLanguagesInFirstSeenOrder(emptyList(), TrackKind.AUDIO))
    }

    @Test
    fun `the wrong kind never leaks in`() {
        val eps = listOf(episode(1, 1, listOf(track(TrackKind.AUDIO, "eng"))))

        assertEquals(emptyList(), unionLanguagesInFirstSeenOrder(eps, TrackKind.SUBTITLE))
    }
}
