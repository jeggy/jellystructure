package dev.jellystructure.server.routes

import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 190 — the server-side direct-play/remux decision. Confirmed against a real codec census
 * (2026-09-06): 46.4% of library files lead with a codec (eac3/ac3/dts/truehd) no mainstream desktop
 * browser decodes, 90% of those in an MKV container — this is exactly the shape a bare Static=true
 * stream silently plays with picture and no sound.
 */
class SegmentStreamModeTest {

    private fun audioTrack(codec: String) = Track(
        streamIndex = 1, specifier = "a:0", kind = TrackKind.AUDIO, codec = codec,
        language = "eng", title = null, default = true, forced = false,
    )

    @Test
    fun aacInMp4IsDirectPlay() {
        assertTrue(isBrowserSafeDirectPlay(listOf(audioTrack("aac")), "/movies/Foo (2020)/foo.mp4"))
    }

    @Test
    fun theExactReportedShape_dtsInMkvIsNotDirectPlay() {
        // 28 Weeks Later (2007) — the real title this phase's live probe used.
        assertFalse(isBrowserSafeDirectPlay(listOf(audioTrack("dts")), "/movies/28 Weeks Later (2007)/28 Weeks Later 2007 1080p BluRay Remux AVC DTS-HD MA 5.1.mkv"))
    }

    @Test
    fun eac3IsNotDirectPlayEvenInAContainerBrowsersOpen() {
        // The container alone isn't enough — the audio codec is the actual failure (23.2% of the library).
        assertFalse(isBrowserSafeDirectPlay(listOf(audioTrack("eac3")), "/movies/Foo (2020)/foo.mp4"))
    }

    @Test
    fun aacInMkvIsNotDirectPlay() {
        // The codec alone isn't enough either — Chromium won't open a bare <video src> at an .mkv file.
        assertFalse(isBrowserSafeDirectPlay(listOf(audioTrack("aac")), "/movies/Foo (2020)/foo.mkv"))
    }

    @Test
    fun everyAudioTrackMustBeSafe() {
        val tracks = listOf(audioTrack("aac"), audioTrack("ac3"))
        assertFalse(isBrowserSafeDirectPlay(tracks, "/movies/Foo (2020)/foo.mp4"))
    }

    @Test
    fun noAudioTrackAtAllIsNotFailedOnByThisCheck() {
        // Nothing to fail on here — the video-only <video> `error` fallback still covers this file.
        assertTrue(isBrowserSafeDirectPlay(emptyList(), "/movies/Foo (2020)/foo.mkv"))
    }

    @Test
    fun containerIsTheFileExtensionLowercased() {
        assertEquals("mkv", containerOf("/movies/Foo (2020)/foo.MKV"))
        assertEquals("", containerOf("/movies/Foo (2020)/no-extension"))
    }

    @Test
    fun playSessionIdIsStableAcrossRepeatedCalls() {
        // Deterministic, not random: a page reload/reopen must reuse the same id (see the function's
        // own doc — Jellyfin's StopEncodingProcess no-ops harmlessly when it doesn't match anything).
        val a = segmentsPlaySessionId("jf-123", "S01E01.mkv")
        val b = segmentsPlaySessionId("jf-123", "S01E01.mkv")
        assertEquals(a, b)
    }

    @Test
    fun playSessionIdDiffersByEpisode() {
        val ep1 = segmentsPlaySessionId("jf-123", "S01E01.mkv")
        val ep2 = segmentsPlaySessionId("jf-123", "S01E02.mkv")
        assertTrue(ep1 != ep2)
    }
}
