package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** R376 — the web player's decisions (FR-R376-2/-3/-5/-6). */
class WebPlaybackTest {

    // ── FR-R376-2 — events → R218's facts ──

    @Test fun coldStartIsNotAStall() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,waiting@10")
        assertFalse(e.firstFrame); assertTrue(e.buffering)
        e.feed("loadeddata@900,playing@1000")
        assertTrue(e.firstFrame); assertFalse(e.buffering)
        assertEquals(0, e.stallCount)
    }

    @Test fun waitingAfterTheFirstFrameIsAStallWithItsLength() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,loadeddata@100,playing@120,waiting@5000")
        assertTrue(e.buffering)
        assertEquals(1, e.stallCount)
        assertEquals(500L, e.stallMsAt(5500.0))   // a report in the middle of the stall is not short
        e.feed("playing@6200")
        assertFalse(e.buffering)
        assertEquals(1200L, e.stallMs)
    }

    @Test fun aSeeksBufferingIsMomentDNotAStall() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,playing@100,seeking@2000,waiting@2001")
        assertTrue(e.seeking); assertTrue(e.buffering)
        e.feed("seeked@2600,playing@2610")
        assertFalse(e.seeking); assertFalse(e.buffering)
        assertEquals(0, e.stallCount)
    }

    @Test fun repeatedWaitingInOneStallCountsOnce() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,playing@1,waiting@10,stalled@20,waiting@30,canplay@100")
        assertEquals(1, e.stallCount)
        assertEquals(90L, e.stallMs)
    }

    @Test fun aNewSourceStartsTheItemAgainButKeepsTheSessionsCounters() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,playing@1,waiting@10,playing@20,ended@500")
        assertTrue(e.ended)
        e.feed("loadstart@600")
        assertFalse(e.firstFrame); assertFalse(e.ended)
        assertEquals(1, e.stallCount); assertEquals(10L, e.stallMs)
    }

    @Test fun fatalErrorsFailTheStart() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,waiting@1,hlsfatal@50")
        assertTrue(e.failed); assertFalse(e.buffering)
        e.feed("loadstart@100")
        assertFalse(e.failed)
        e.feed("error@120")
        assertTrue(e.failed)
    }

    @Test fun playClearsEnded() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,playing@1,ended@9,play@10")
        assertFalse(e.ended)
    }

    @Test fun malformedQueueEntriesAreSkipped() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,garbage,@5,playing@x,playing@3")
        assertTrue(e.firstFrame)
    }

    // ── FR-R376-3 — renditions by name ──

    @Test fun renditionNamesCarryTheirPosition() {
        assertEquals(0, renditionPosition("a0"))
        assertEquals(3, renditionPosition("a3 Dansk"))
        assertEquals(12, renditionPosition("a12 English (Commentary)"))
        assertNull(renditionPosition("audio"))
        assertNull(renditionPosition("a3Dansk"))
        assertNull(renditionPosition("English"))
        assertNull(renditionPosition(null))
    }

    private val server = listOf(
        AudioTrack(1, "eng", "English", channels = 6, isDefault = true),
        AudioTrack(2, "dan", "Dansk", channels = 2),
        AudioTrack(3, "fao", null, channels = 2),
    )

    @Test fun thePickerListsWhatTheStreamCarriesWithTheServersNames() {
        val tracks = audioTracksFromRenditions(listOf("a0 English", "a2", "a1 Dansk"), server)
        assertEquals(listOf(0, 1, 2), tracks.map { it.index })
        assertEquals("English", tracks[0].label)
        assertEquals("Dansk", tracks[1].label)
        assertEquals("fao", tracks[2].language)
        assertTrue(tracks[0].isDefault)
    }

    @Test fun aRenditionTheServerDidNotNameIsNeverInvented() {
        assertEquals(listOf(0), audioTracksFromRenditions(listOf("a0", "a7 Ghost", "main"), server).map { it.index })
        assertTrue(audioTracksFromRenditions(listOf("English", "Dansk"), server).isEmpty())
    }

    // ── FR-R376-5 — containers ──

    @Test fun containersAreWhatTheBrowserOpens() {
        assertEquals(listOf("mp4"), webContainers(mkv = false, webm = false))       // Safari
        assertEquals(listOf("mp4", "webm"), webContainers(mkv = false, webm = true))
        assertEquals(listOf("mp4", "mkv", "webm"), webContainers(mkv = true, webm = true))
    }

    // ── FR-R376-3 (owner 2026-10-08) — direct play first; HLS only when another audio track is picked ──

    @Test fun anAudioPickOnABrowserThatCannotSwitchInTheFileAsksForHls() {
        assertTrue(audioPickNeedsHls(audioRestream = true, alreadyHlsOnly = false, switchesInFile = false))
    }

    @Test fun noPickNoHlsAndAPlayerThatSwitchesInTheFileOrAnHlsSessionKeepsWhatItHas() {
        assertFalse(audioPickNeedsHls(audioRestream = false, alreadyHlsOnly = false, switchesInFile = false))   // a start, a subtitle restream
        assertFalse(audioPickNeedsHls(audioRestream = true, alreadyHlsOnly = true, switchesInFile = false))
        assertFalse(audioPickNeedsHls(audioRestream = true, alreadyHlsOnly = false, switchesInFile = true))
    }

    // ── R381 (FR-R381-1/-2) — the web's stalls are QoeCounter's: per item, after the first frame, not a seek's ──

    @Test fun aStallIsCountedPerItemAndOnlyAfterTheFirstFrame() {
        val q = QoeCounter(); val e = WebPlaybackEvents(q)
        q.beginItem("ep1")
        e.feed("loadstart@0,waiting@10,loadeddata@900,playing@1000")   // the start: not a stall
        assertEquals(0, q.rebufferCount); assertEquals(1, q.waitCounts()["start"])
        e.feed("waiting@5000,playing@5600", positionMs = 4000, bufferedAheadMs = 0)
        assertEquals(1, q.rebufferCount); assertEquals(600L, q.rebufferMs)
        assertEquals(4000L, q.stallEvents().single().positionMs)
        q.beginItem("ep2")   // the next episode on the same element: its counts start at 0
        e.feed("loadstart@6000,waiting@6010,playing@7000")
        assertEquals(0, q.rebufferCount); assertEquals(1, q.sessionRebufferCount)
    }

    @Test fun aSeekATrackSwitchAVariantSwitchAndAPauseAreNeverStalls() {
        val q = QoeCounter(); val e = WebPlaybackEvents(q)
        q.beginItem("film")
        e.feed("loadstart@0,playing@500")
        e.feed("seeking@1000,waiting@1001,seeked@1500,playing@1600")
        q.trackSwitch(); e.feed("waiting@2000,playing@2400")
        e.feed("variant@3000,waiting@3500,playing@3900")
        e.feed("waiting@7000,pause@7100,play@9000,playing@9100")   // outside the variant switch's 3 s window
        assertEquals(0, q.rebufferCount)
        assertEquals(mapOf("seek" to 1, "track_switch" to 1, "variant_switch" to 1), q.waitCounts() - "start")
    }

    // ── R376 (owner 2026-10-08, changes R265 FR-R265-8) — Safari direct-plays; HLS only once on AirPlay ──

    @Test fun safariStartsLikeAnyBrowserUnlessThePictureIsAlreadyOnAirPlay() {
        assertFalse(startsAsAirPlayHls(nativeHlsWithAirPlay = true, onAirPlayNow = false))   // Safari, on the Mac's screen
        assertTrue(startsAsAirPlayHls(nativeHlsWithAirPlay = true, onAirPlayNow = true))     // the next episode while on AirPlay
        assertFalse(startsAsAirPlayHls(nativeHlsWithAirPlay = false, onAirPlayNow = true))   // Chrome, Firefox
    }

    @Test fun pickingAirPlayRestartsADirectPlayAsHlsOncePerItem() {
        assertTrue(airplayNeedsHls(onAirPlay = true, streamIsHls = false, alreadyRestartedForAirPlay = false))
        assertFalse(airplayNeedsHls(onAirPlay = true, streamIsHls = true, alreadyRestartedForAirPlay = false))    // already HLS
        assertFalse(airplayNeedsHls(onAirPlay = true, streamIsHls = false, alreadyRestartedForAirPlay = true))    // once
        assertFalse(airplayNeedsHls(onAirPlay = false, streamIsHls = false, alreadyRestartedForAirPlay = false))  // not on AirPlay
    }

    @Test fun theAirPlayRestartAsksForHlsWithTheManifestsSubtitlesAndNoHevc() {
        val c = airplayCapabilities(dev.jellystructure.shared.tv.ClientCapabilities(hlsOnly = false, hlsSubtitles = false, hlsHevc = true))
        assertTrue(c.hlsOnly); assertTrue(c.hlsSubtitles); assertFalse(c.hlsHevc)
    }

    // ── R376 (2026-10-08, Safari) — a play with no gesture plays muted until a real click, tap or key ──

    @Test fun aRefusedSoundIsKnownUntilTheViewerInteractsAndSurvivesANewSource() {
        val e = WebPlaybackEvents()
        e.feed("loadstart@0,soundblocked@10,playing@500")
        assertTrue(e.soundBlocked); assertTrue(e.firstFrame)
        e.feed("loadstart@9000,playing@9600")   // the next episode on the same, still muted, element
        assertTrue(e.soundBlocked)
        e.feed("soundon@12000")
        assertFalse(e.soundBlocked)
    }

    @Test fun aRefusedPlayIsNeverCountedAsAStall() {
        val q = QoeCounter(); val e = WebPlaybackEvents(q)
        q.beginItem("film")
        e.feed("loadstart@0,loadeddata@400,play@410,pause@411,soundblocked@412,waiting@420,playing@900")
        assertEquals(0, q.rebufferCount)
    }

    @Test fun aRestreamOfTheSameItemKeepsItsCounts() {
        val q = QoeCounter(); val e = WebPlaybackEvents(q)
        q.beginItem("film"); e.feed("loadstart@0,playing@500,waiting@2000,playing@2300")
        q.beginItem("film"); e.feed("loadstart@3000,waiting@3010,playing@3500")   // an audio restream (R284)
        assertEquals(1, q.rebufferCount)
    }

    // R376 (FR-R376-S1) — sound on the first click in Safari.

    @Test fun aTrustedClickOnTheIdleElementUnlocksIt() {
        for (ev in listOf("pointerdown", "mousedown", "click", "touchend", "keydown"))
            assertTrue(WebSoundUnlock.shouldUnlock(ev, trusted = true, hasSource = false), ev)
    }

    @Test fun aScriptedEventNeverUnlocks() {
        assertFalse(WebSoundUnlock.shouldUnlock("click", trusted = false, hasSource = false))
    }

    @Test fun aClickDuringAFilmNeverTouchesTheElement() {
        assertFalse(WebSoundUnlock.shouldUnlock("click", trusted = true, hasSource = true))
    }

    @Test fun movingThePointerIsNotAGesture() {
        assertFalse(WebSoundUnlock.shouldUnlock("pointermove", trusted = true, hasSource = false))
        assertFalse(WebSoundUnlock.shouldUnlock("scroll", trusted = true, hasSource = false))
    }

    @Test fun oneElementAcrossTwoPlayers_aStaleReleaseDoesNotResetIt() {
        val owner = SharedElementOwner()
        val first = Any(); val second = Any()
        owner.acquire(first)
        owner.acquire(second)                 // the next screen's player is created before the last one is disposed
        assertFalse(owner.release(first))     // the old player's release must not reset the element
        assertSame(second, owner.current)
        assertTrue(owner.release(second))     // the owner's own release resets it
        assertNull(owner.current)
    }
}

