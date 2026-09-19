package dev.jellystructure.advisor

import dev.jellystructure.auth.JellyfinLibrary
import dev.jellystructure.auth.JellyfinLibraryOptions
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
    fun internetProvidersOnIsAFinding() {
        val lib = library("x", "Film", JellyfinLibraryOptions(enableInternetProviders = true))
        assertEquals(listOf("internet_providers_x"), JellyfinAdvisorService.metadataOwnershipFindings(lib).map { it.id })
    }

    @Test
    fun internetProvidersAbsentIsNotInternetProvidersOn() {
        val lib = library("x", "Film", JellyfinLibraryOptions())
        assertTrue(JellyfinAdvisorService.metadataOwnershipFindings(lib).isEmpty())
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
