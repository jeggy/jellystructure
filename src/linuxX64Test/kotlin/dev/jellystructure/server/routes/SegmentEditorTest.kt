package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaSegmentRow
import dev.jellystructure.media.parseKeyframeLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 222 — the pure decisions behind the segment editor's duration, validation and seek reporting. */
class SegmentEditorTest {

    private fun row(kind: String, start: Long, end: Long?) = MediaSegmentRow(itemId = "x", kind = kind, startMs = start, endMs = end)

    // FR-222-3 — one duration, labelled by where it came from.

    @Test
    fun measuredLengthWinsOverTmdbAndMarkers() {
        assertEquals(2612.48 to "file", resolveDuration(2_612_480L, 42, listOf(row("credits", 2_590_000L, null))))
    }

    @Test
    fun tmdbIsOnlyEverALabelledFallback() {
        assertEquals(2520.0 to "tmdb", resolveDuration(null, 42, listOf(row("credits", 2_590_000L, null))))
        assertEquals(2520.0 to "tmdb", resolveDuration(0L, 42, emptyList()))
    }

    @Test
    fun markersThenUnknownWhenNothingIsMeasuredOrEstimated() {
        assertEquals(2590.0 to "markers", resolveDuration(null, null, listOf(row("intro", 30_000L, 90_000L), row("credits", 2_590_000L, null))))
        assertEquals(0.0 to "unknown", resolveDuration(null, 0, emptyList()))
    }

    // FR-222-5 — writes are validated.

    @Test
    fun aGoodEditIsAccepted() {
        assertNull(validateSegmentEdit("intro", 30_000L, 90_000L, 2_612_480L))
        assertNull(validateSegmentEdit("credits", 2_590_000L, null, 2_612_480L))
        assertNull(validateSegmentEdit("credits", 2_613_000L, null, 2_612_480L)) // within the 2 s tolerance
    }

    @Test
    fun unknownKindNegativeStartAndInvertedRangeAreRefused() {
        assertEquals("'outro' is not a marker kind", validateSegmentEdit("outro", 0L, 10L, null))
        assertEquals("a marker cannot start before the file does", validateSegmentEdit("intro", -1L, 10L, null))
        assertEquals("a marker's end must come after its start", validateSegmentEdit("intro", 90_000L, 90_000L, null))
        assertEquals("a marker's end must come after its start", validateSegmentEdit("intro", 90_000L, 30_000L, null))
    }

    @Test
    fun pastTheMeasuredEndIsRefusedButUnmeasuredIsNot() {
        assertEquals("start is past the end of the file (43:32)", validateSegmentEdit("credits", 2_700_000L, null, 2_612_480L))
        assertEquals("end is past the end of the file (43:32)", validateSegmentEdit("intro", 30_000L, 2_700_000L, 2_612_480L))
        assertNull(validateSegmentEdit("credits", 2_700_000L, null, null))
    }

    // FR-222-2 — the keyframe the stream really starts at.

    @Test
    fun keyframeLineParsesTheFirstPacketsTimestamp() {
        assertEquals(300.0, parseKeyframeLine("300.000000,K__\n300.040000,___\n"))
        assertEquals(0.0, parseKeyframeLine("0.000000,K__"))
        assertNull(parseKeyframeLine(""))
        assertNull(parseKeyframeLine("N/A,K__"))
    }
    // Phase 223 (FR-223-6) — the neighbour rule at the route, judged against the stored rows.

    @Test
    fun neighbourRuleRefusesANewOverlapAndLetsAnOldOneShrink() {
        val intro = row("intro", 30_000L, 90_000L)
        val credits = row("credits", 2_590_000L, null)
        assertEquals("credits can't start before the intro ends (1:30)", validateNeighbours("credits", 80_000L, null, credits, listOf(intro), 2_612_480L))
        assertNull(validateNeighbours("credits", 90_000L, null, credits, listOf(intro), 2_612_480L))
        // Unmeasured file: the credits still run to the end, so an intro reaching past their start is refused.
        assertEquals("intro can't end after the credits start (43:10)", validateNeighbours("intro", 30_000L, 2_600_000L, intro, listOf(credits), null))
        // One of the 775 production rows (credits inside the intro): fixable by moving the credits later,
        // refused when moved earlier — the overlap may shrink but never grow.
        val bad = row("credits", 60_000L, null)
        assertNull(validateNeighbours("credits", 80_000L, null, bad, listOf(intro), 2_612_480L))
        assertEquals("credits can't start before the intro ends (1:30)", validateNeighbours("credits", 10_000L, null, bad, listOf(intro), 2_612_480L))
        // A stinger inside the credits is where it belongs.
        assertNull(validateNeighbours("stinger", 2_600_000L, null, null, listOf(intro, credits), 2_612_480L))
    }
}
