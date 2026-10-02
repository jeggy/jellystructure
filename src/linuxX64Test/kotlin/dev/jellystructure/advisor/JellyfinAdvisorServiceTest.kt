package dev.jellystructure.advisor

import dev.jellystructure.auth.JellyfinLibrary
import dev.jellystructure.auth.JellyfinLibraryOptions
import dev.jellystructure.auth.JellyfinPluginInfo
import dev.jellystructure.auth.JellyfinSessionInfo
import dev.jellystructure.auth.JellyfinSystemInfoAuth
import dev.jellystructure.auth.JellyfinTrickplayOptions
import dev.jellystructure.auth.JellyfinTypeOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * Phases 242 and 246. Every expectation here was first measured against the household's live Jellyfin
 * (12.1.0, `GET /Library/VirtualFolders`, 2026-09-19) — the names, the empty-versus-absent distinction
 * and the two local image fetchers are all real values from that server, not invented fixtures.
 *
 * The point of pinning them is that the failures these guard against are all **silent**: a check that
 * stops firing reads exactly like a server that has become correct.
 */
class JellyfinAdvisorServiceTest {

    private fun library(id: String, name: String, opts: JellyfinLibraryOptions?) =
        JellyfinLibrary(id = id, name = name, libraryOptions = opts)

    // ── Phase 242 FR-242-4 — absent is not empty ────────────────────────────────

    @Test
    fun absentMetadataSaversIsNotTreatedAsNoneConfigured() {
        // `Recordings` and `Samlinger` return no MetadataSavers key at all on the live server. If the
        // field carried a value default, a key Jellyfin RENAMES would land here too and the NFO warning
        // would stop firing with no error and no log line — which is the whole reason it is nullable.
        val absent = library("a", "Recordings", JellyfinLibraryOptions())
        assertNull(absent.libraryOptions?.metadataSavers)
        assertTrue(JellyfinAdvisorService.metadataOwnershipFindings(absent).isEmpty())
    }

    @Test
    fun nfoSaverOnAManagedLibraryIsCritical() {
        val musik = library("m", "Musik", JellyfinLibraryOptions(metadataSavers = listOf("Nfo")))
        val findings = JellyfinAdvisorService.metadataOwnershipFindings(musik)
        assertEquals(1, findings.size)
        assertEquals("nfo_saver_m", findings[0].id)
        assertEquals(JellyfinAdvisorService.CRITICAL, findings[0].severity)
        assertTrue(findings[0].navigationPath.contains("Musik"))
    }

    @Test
    fun anEmptySaverListIsSilence() {
        // Film and Serier both return [] and must produce nothing: phase 212's silence rule is what makes
        // the surface worth opening at all.
        val film = library("f", "Film", JellyfinLibraryOptions(metadataSavers = emptyList()))
        assertTrue(JellyfinAdvisorService.metadataOwnershipFindings(film).isEmpty())
    }

    // ── Phase 242 FR-242-3 — the carve-out is an allow-list, not a substring guess ──

    @Test
    fun purelyLocalImageFetchersAreNotFindings() {
        // Musik's real value. Both read the file already on disk and reach no external service, so
        // flagging them would be wrong.
        val musik = library("m", "Musik", JellyfinLibraryOptions(
            typeOptions = listOf(JellyfinTypeOptions(
                type = "MusicVideo",
                metadataFetchers = emptyList(),
                imageFetchers = listOf("Embedded Image Extractor", "Screen Grabber"),
            )),
        ))
        assertTrue(JellyfinAdvisorService.metadataOwnershipFindings(musik).none { it.id.startsWith("image_fetchers_") })
    }

    @Test
    fun anExternalImageFetcherAlongsideLocalOnesStillFires() {
        val lib = library("x", "Film", JellyfinLibraryOptions(
            typeOptions = listOf(JellyfinTypeOptions(
                type = "Movie",
                imageFetchers = listOf("Embedded Image Extractor", "TheMovieDb"),
            )),
        ))
        val f = JellyfinAdvisorService.metadataOwnershipFindings(lib).single { it.id.startsWith("image_fetchers_") }
        assertTrue(f.currentValue.contains("TheMovieDb"))
        // The local one must not be named as a problem even when a real one is present beside it.
        assertFalse(f.currentValue.contains("Embedded Image Extractor"))
    }

    @Test
    fun internetProvidersIsNeverAFinding() {
        // Phase 297 FR-297-4 — 12.1's library editor has no control for it and saves `true` on every save.
        // The JSON key is ignored on read, so a library carrying it is silent unless a fetcher list says otherwise.
        val lib = library("x", "Film", JellyfinLibraryOptions(typeOptions = listOf(JellyfinTypeOptions(type = "Movie", metadataFetchers = emptyList(), imageFetchers = emptyList()))))
        assertTrue(JellyfinAdvisorService.metadataOwnershipFindings(lib).isEmpty())
    }

    // ── Phase 297 — every suggestion is true and can be acted on ────────────────

    private fun session(device: String, addr: String) = JellyfinSessionInfo(deviceId = device, remoteEndPoint = addr)

    @Test
    fun knownProxiesFiresWhenEveryDeviceIsOneProxyHop() {
        // The household on 2026-10-02: Known proxies still named Caddy's old address, Caddy had been recreated.
        val sessions = listOf(session("ravilo-a", "172.28.0.27"), session("ravilo-b", "172.28.0.27"), session("web", "172.28.0.27"))
        val f = JellyfinAdvisorService.knownProxiesFinding(listOf("172.28.0.17"), sessions)
        assertEquals("known_proxies_ignored", f?.id)
        assertEquals(JellyfinAdvisorService.WARNING, f?.severity)
        assertTrue(f!!.costHere.startsWith("172.28.0.27 is not in Known proxies."))
        assertTrue(f.currentValue.contains("3 devices"))
        assertEquals("recheck_exposure", f.action)
    }

    @Test
    fun knownProxiesIsSilentWhenCallersAreSeen() {
        // What a working setup looks like: Jellyfin attributes the callers' own addresses.
        val working = listOf(session("a", "192.168.1.20"), session("b", "192.168.1.21"))
        assertNull(JellyfinAdvisorService.knownProxiesFinding(listOf("caddy"), working))
        val publicCaller = listOf(session("a", "198.51.100.4"), session("b", "198.51.100.4"))
        assertNull(JellyfinAdvisorService.knownProxiesFinding(listOf("172.28.0.17"), publicCaller))
    }

    @Test
    fun knownProxiesNeedsTwoDevices() {
        // 244's caveat: one session that genuinely originates on the Docker network proves nothing.
        val one = listOf(session("a", "172.28.0.27"), session("a", "172.28.0.27"))
        assertNull(JellyfinAdvisorService.knownProxiesFinding(listOf("172.28.0.17"), one))
    }

    @Test
    fun knownProxiesListedButNoForwardedHeader() {
        val f = JellyfinAdvisorService.knownProxiesFinding(listOf("172.28.0.27"), listOf(session("a", "172.28.0.27"), session("b", "::ffff:172.28.0.27")))
        assertTrue(f!!.costHere.contains("no X-Forwarded-For"))
        assertTrue(f.recommendation.startsWith("Make the reverse proxy send X-Forwarded-For"))
    }

    @Test
    fun knownProxiesHostnameEntryIsExplained() {
        val f = JellyfinAdvisorService.knownProxiesFinding(listOf("caddy"), listOf(session("a", "172.28.0.27"), session("b", "172.28.0.27")))
        assertTrue(f!!.costHere.startsWith("Known proxies names caddy."))
    }

    @Test
    fun privateAddressRanges() {
        for (a in listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "127.0.0.1", "169.254.1.1", "::1", "fd00::1", "fe80::1"))
            assertTrue(JellyfinAdvisorService.isPrivateAddress(a), a)
        for (a in listOf("172.32.0.1", "8.8.8.8", "198.51.100.4", "2001:db8::1"))
            assertFalse(JellyfinAdvisorService.isPrivateAddress(a), a)
    }

    @Test
    fun trickplaySettingsNamesBothSwitchesWithAnAccelerator() {
        // The household after NVENC was restored: both trickplay switches still at Jellyfin's defaults.
        val f = JellyfinAdvisorService.trickplaySettingsFinding(
            listOf("Musik Videoer", "Blandet", "Serier"),
            JellyfinTrickplayOptions(enableHwAcceleration = false, enableKeyFrameOnlyExtraction = false), "nvenc",
        )
        assertEquals("trickplay_settings", f?.id)
        assertEquals(JellyfinAdvisorService.WARNING, f?.severity)
        assertEquals("Dashboard → Playback → Trickplay", f?.navigationPath)
        assertTrue(f!!.recommendation.contains("\"Only generate images from key frames\""))
        assertTrue(f.recommendation.contains("\"Enable hardware decoding\""))
    }

    @Test
    fun trickplayHardwareDecodingIsNotSuggestedWithoutAnAccelerator() {
        val f = JellyfinAdvisorService.trickplaySettingsFinding(listOf("Serier"), JellyfinTrickplayOptions(false, false), "none")
        assertFalse(f!!.recommendation.contains("hardware decoding"))
        // Key frames alone already on, hardware off, no accelerator: nothing on this page would help.
        assertNull(JellyfinAdvisorService.trickplaySettingsFinding(listOf("Serier"), JellyfinTrickplayOptions(false, true), "none"))
    }

    @Test
    fun trickplaySettingsAreSilentWhenRightOrUnused() {
        assertNull(JellyfinAdvisorService.trickplaySettingsFinding(listOf("Serier"), JellyfinTrickplayOptions(true, true), "nvenc"))
        assertNull(JellyfinAdvisorService.trickplaySettingsFinding(emptyList(), JellyfinTrickplayOptions(false, false), "nvenc"))
        // Absent keys are unknown, not off (242's rule).
        assertNull(JellyfinAdvisorService.trickplaySettingsFinding(listOf("Serier"), JellyfinTrickplayOptions(), "nvenc"))
    }

    @Test
    fun householdLibraryRowsAreInformation() {
        // Film and Serier as of 2026-10-02: chapter images on and during the scan, trickplay off with its
        // during-scan flag still on (Film), on a spinning disk. None of it is a fault (FR-297-1/3/5).
        val disk = JellyfinAdvisorService.DeviceInfo("sda", rotational = true, scheduler = "mq-deadline", readAheadKb = 128, maxSectorsKb = 1280, maxHwSectorsKb = 32767)
        val film = library("f", "Film", JellyfinLibraryOptions(
            enableChapterImageExtraction = true, extractChapterImagesDuringLibraryScan = true,
            enableTrickplayImageExtraction = false, extractTrickplayImagesDuringLibraryScan = true,
        ))
        val serier = library("s", "Serier", JellyfinLibraryOptions(enableTrickplayImageExtraction = true))
        val rows = JellyfinAdvisorService.perLibraryFindings(film, disk, emptyList()) + JellyfinAdvisorService.perLibraryFindings(serier, disk, emptyList())
        assertEquals(setOf("chapter_images_f", "chapter_images_during_scan_f", "trickplay_contradiction_f", "trickplay_s"), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.severity == JellyfinAdvisorService.INFO })
        // The cost of a chapter image is a frame grab, never a read of the whole file.
        assertFalse(rows.single { it.id == "chapter_images_f" }.costHere.contains("read in full"))
    }

    @Test
    fun aPendingRestartNamesEachPluginOnce() {
        val plugins = listOf(
            JellyfinPluginInfo(name = "Moonbase", version = "2.1.0.0", status = "Superseded"),
            JellyfinPluginInfo(name = "Moonbase", version = "2.3.0.0", status = "Restart"),
            JellyfinPluginInfo(name = "Moonbase", version = "2.3.0.0", status = "Restart"),
        )
        val f = JellyfinAdvisorService.restartPendingFinding(JellyfinSystemInfoAuth(hasPendingRestart = true), plugins)
        assertEquals("Jellyfin reports a pending restart (Moonbase 2.3.0.0)", f?.summary)
    }

    // ── Phase 296 — the loudness finding is about music only ────────────────────

    private fun typed(name: String, type: String?, lufs: Boolean?) =
        JellyfinLibrary(id = name, name = name, collectionType = type, libraryOptions = JellyfinLibraryOptions(enableLufsScan = lufs))

    @Test
    fun lufsOnIsSilentOnEveryHouseholdLibrary() {
        // The household's real 2026-10-02 values: on in six libraries, Musik the only `music` one. Jellyfin
        // shows the checkbox on none of the other five and measures nothing there.
        val libs = listOf(
            typed("Film", "movies", true), typed("Musik Videoer", "musicvideos", true),
            typed("Blandet", "homevideos", true), typed("Bøger", "books", true),
            typed("Serier", "tvshows", true), typed("Musik", "music", true),
        )
        for (lib in libs) {
            assertNull(JellyfinAdvisorService.lufsFinding(lib, rotational = true, disk = " (sdc)"), lib.name)
        }
    }

    @Test
    fun lufsOffOnAMusicLibraryFiresOnAnyStorage() {
        for (rotational in listOf(true, false)) {
            val f = JellyfinAdvisorService.lufsFinding(typed("Musik", "music", false), rotational, " (sdc)")
            assertEquals("lufs_Musik", f?.id)
            assertEquals(JellyfinAdvisorService.WARNING, f?.severity)
            assertEquals("Turn on.", f?.recommendation)
        }
    }

    @Test
    fun lufsOffOutsideMusicIsSilent() {
        assertNull(JellyfinAdvisorService.lufsFinding(typed("Film", "movies", false), rotational = true, disk = ""))
    }

    @Test
    fun lufsAbsentIsUnknownNotOff() {
        assertNull(JellyfinAdvisorService.lufsFinding(typed("Musik", "music", null), rotational = true, disk = ""))
    }

    // ── Phase 246 FR-246-11 — deriving a local path for an unmanaged library ────

    @Test
    fun rootPairStripsTheSegmentsTwoPathsEndInCommon() {
        // The household's Film mapping. /media/mixed then derives to /mnt/media/jellyfin/mixed, which is
        // what lets `Blandet` — skipped, and the worst-configured library on the server — be seen at all.
        assertEquals(
            "/media/" to "/mnt/media/jellyfin/",
            JellyfinAdvisorService.rootPair("/media/movies/", "/mnt/media/jellyfin/movies/"),
        )
    }

    @Test
    fun rootPairRefusesAPairThatSharesNoTrailingSegment() {
        // The household's Serier mapping: /media/series -> /mnt/series/jellyfin. Nothing can be derived
        // from it, and inventing a relationship is exactly what FR-212-6's unknown-suppresses rule forbids.
        assertNull(JellyfinAdvisorService.rootPair("/media/series/", "/mnt/series/jellyfin/"))
    }

    @Test
    fun derivedPathSubstitutionProducesTheRealLocalPath() {
        val (jellyfinRoot, localRoot) = JellyfinAdvisorService.rootPair("/media/music", "/mnt/media/jellyfin/music")!!
        val location = "/media/mixed"
        assertTrue(location.startsWith(jellyfinRoot))
        assertEquals("/mnt/media/jellyfin/mixed", localRoot + location.removePrefix(jellyfinRoot))
    }

    // ── Phase 246 FR-246-9 — severity ordering ──────────────────────────────────

    @Test
    fun findingsSortCriticalThenWarningThenInfo() {
        fun f(id: String, severity: String) = AdvisorFinding(
            id = id, summary = "", currentValue = "", costHere = "", navigationPath = "",
            fieldLabel = "", recommendation = "", tradeoff = "", severity = severity,
        )
        with(JellyfinAdvisorService) {
            val sorted = listOf(
                f("i", INFO), f("w", WARNING), f("c", CRITICAL),
            ).sortedBySeverity()
            assertEquals(listOf("c", "w", "i"), sorted.map { it.id })
        }
    }

    @Test
    fun aFindingDefaultsToWarningSoOlderProducersAreUnchanged() {
        // MemoryBudgetService (phase 215) builds AdvisorFindings of its own and was not touched by 246.
        val f = AdvisorFinding(
            id = "x", summary = "", currentValue = "", costHere = "", navigationPath = "",
            fieldLabel = "", recommendation = "", tradeoff = "",
        )
        assertEquals(JellyfinAdvisorService.WARNING, f.severity)
        assertNull(f.action)
    }
}
