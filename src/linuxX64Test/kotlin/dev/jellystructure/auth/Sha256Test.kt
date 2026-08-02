package dev.jellystructure.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security fix (2026-08-02 review, finding L8) — Sha256.kt is a hand-rolled, dependency-free FIPS
 * 180-4 implementation (used to hash API keys at rest, ApiKeyStore.kt) with no test coverage at all —
 * a subtle bug in a hand-rolled primitive can silently degrade preimage resistance without ever
 * throwing or producing an obviously-wrong result. Pinning it against the standard published test
 * vectors closes that gap. Expected digests below were independently computed via `hashlib.sha256`
 * (Python's own, well-established implementation), not transcribed from memory.
 */
class Sha256Test {

    @Test
    fun emptyString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
    }

    @Test
    fun singleBlockMessage() {
        // NIST CAVP standard vector for SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test
    fun multiBlockMessage() {
        // Spans the 64-byte block boundary (exercises the padding/length-encoding path, not just the
        // single-block case above).
        val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", sha256Hex(input))
    }

    @Test
    fun outputIsAlways64HexChars() {
        listOf("", "a", "abc", "the quick brown fox jumps over the lazy dog", "unicode: 日本語").forEach {
            val hex = sha256Hex(it)
            assertEquals(64, hex.length, "unexpected length for input '$it'")
            assertTrue(hex.all { c -> c in "0123456789abcdef" }, "non-hex char for input '$it': $hex")
        }
    }

    @Test
    fun deterministicAndCollisionFreeForSimilarInputs() {
        // Not a real collision-resistance proof, just a sanity net: near-identical API-key-shaped
        // inputs must not hash to the same digest (the practical property ApiKeyStore relies on).
        assertEquals(sha256Hex("jsk_abc123"), sha256Hex("jsk_abc123"))
        assertFalse(sha256Hex("jsk_abc123") == sha256Hex("jsk_abc124"))
        assertFalse(sha256Hex("jsk_abc123") == sha256Hex("Jsk_abc123"))
    }

    @Test
    fun constantTimeEqualsIsCorrectNotJustConstantTime() {
        assertTrue(constantTimeEquals("", ""))
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "ab"))
        assertFalse(constantTimeEquals("ab", "abc"))
        assertFalse(constantTimeEquals("abc", "xyz"))
    }
}
