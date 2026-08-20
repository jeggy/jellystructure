package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 170 (detect_segments follow-ups, §2) — a force re-detect must never let a lower-precedence
 *  source clobber a higher-precedence existing row. */
class SegmentSourcePrecedenceTest {

    private fun overwriteAllowed(incoming: String, existing: String?) =
        SegmentSource.precedence(incoming) >= SegmentSource.precedence(existing)

    @Test
    fun `fingerprint may not overwrite an exact chapter match`() {
        assertFalse(overwriteAllowed(SegmentSource.FINGERPRINT, SegmentSource.CHAPTER))
    }

    @Test
    fun `heuristic may not overwrite a fingerprint consensus`() {
        assertFalse(overwriteAllowed(SegmentSource.HEURISTIC, SegmentSource.FINGERPRINT))
    }

    @Test
    fun `heuristic may not overwrite an exact chapter match`() {
        assertFalse(overwriteAllowed(SegmentSource.HEURISTIC, SegmentSource.CHAPTER))
    }

    @Test
    fun `nothing auto-detected may overwrite a manual entry`() {
        assertFalse(overwriteAllowed(SegmentSource.CHAPTER, SegmentSource.MANUAL))
        assertFalse(overwriteAllowed(SegmentSource.FINGERPRINT, SegmentSource.MANUAL))
        assertFalse(overwriteAllowed(SegmentSource.HEURISTIC, SegmentSource.MANUAL))
    }

    @Test
    fun `chapter may overwrite a fingerprint or heuristic guess`() {
        assertTrue(overwriteAllowed(SegmentSource.CHAPTER, SegmentSource.FINGERPRINT))
        assertTrue(overwriteAllowed(SegmentSource.CHAPTER, SegmentSource.HEURISTIC))
    }

    @Test
    fun `same-tier re-detection stays allowed`() {
        assertTrue(overwriteAllowed(SegmentSource.CHAPTER, SegmentSource.CHAPTER))
        assertTrue(overwriteAllowed(SegmentSource.FINGERPRINT, SegmentSource.FINGERPRINT))
        assertTrue(overwriteAllowed(SegmentSource.HEURISTIC, SegmentSource.HEURISTIC))
    }

    @Test
    fun `no existing row never blocks a write`() {
        assertTrue(overwriteAllowed(SegmentSource.HEURISTIC, null))
    }
}
