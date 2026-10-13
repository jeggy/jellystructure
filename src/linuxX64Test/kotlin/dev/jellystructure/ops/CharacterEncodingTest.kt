package dev.jellystructure.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 318 (FR-318-2) — the one pure function the Tests section asks for: a codeset string in,
 *  green/red plus the warning sentence out. The three cases are the spec's three: `UTF-8`, the
 *  `ANSI_X3.4-1968` the C locale actually reports, and the empty string a failed `newlocale` gives. */
class CharacterEncodingTest {

    @Test
    fun `UTF-8 is green and says nothing at startup`() {
        val v = CharacterEncoding.verdict("UTF-8")
        assertTrue(v.ok, "UTF-8 is the one green answer")
        assertEquals("UTF-8", v.codeset)
        assertEquals("UTF-8", v.detail)
        assertNull(v.warning, "a green startup is a silent startup")
    }

    @Test
    fun `a libc that spells it utf8 is not called a fault`() {
        assertTrue(CharacterEncoding.verdict("utf8").ok, "normalised spelling is still UTF-8")
        assertTrue(CharacterEncoding.verdict(" UTF-8 ").ok, "surrounding whitespace is not an encoding")
    }

    @Test
    fun `the C locale's ANSI_X3 4-1968 is red and names itself`() {
        val v = CharacterEncoding.verdict("ANSI_X3.4-1968")
        assertFalse(v.ok, "the C locale is the whole fault of phase 318")
        assertEquals("ANSI_X3.4-1968", v.codeset)
        assertTrue(v.detail.contains("ANSI_X3.4-1968"), "a red check names the encoding found: ${v.detail}")
        assertTrue(v.detail.contains(CharacterEncoding.CONSEQUENCE), "and the consequence: ${v.detail}")
        assertTrue(v.detail.contains(CharacterEncoding.REMEDY), "and what to do: ${v.detail}")
        val warning = assertNotNull(v.warning, "red warns at startup")
        assertTrue(warning.contains("ANSI_X3.4-1968"), "the WARN names the encoding found: $warning")
        assertTrue(warning.contains(CharacterEncoding.CONSEQUENCE), "the WARN carries the consequence: $warning")
    }

    @Test
    fun `an empty codeset is red and names no encoding at all`() {
        val v = CharacterEncoding.verdict("")
        assertFalse(v.ok, "a check that cannot tell you the encoding is not a check that may pass")
        assertEquals("", v.codeset)
        val warning = assertNotNull(v.warning, "red warns at startup")
        assertTrue(
            warning.contains("could not be determined"),
            "an unbuildable locale is reported as unknown rather than given a name: $warning",
        )
        assertFalse(warning.contains("is , not UTF-8"), "and never as an empty name: $warning")
        assertTrue(v.detail.contains(CharacterEncoding.CONSEQUENCE), "the consequence holds either way: ${v.detail}")
    }

    @Test
    fun `the live environment is read without touching this process's own locale`() {
        // Not an assertion about the host's LANG — CI, this checkout and the container disagree by
        // design, and FR-318-1 is what settles the container. What is asserted is that the read
        // returns the same answer twice, which it cannot do if asking the question had changed it.
        val first = CharacterEncoding.effectiveCodeset()
        val second = CharacterEncoding.effectiveCodeset()
        // Printed, not asserted, for the same reason: it lands in the test report and in the output of
        // the test binary run by hand under a chosen LC_ALL, which is the only way to see what this
        // read actually answers before the container exists (acceptance 4).
        println("effectiveCodeset() = '$first' → ok=${CharacterEncoding.verdict(first).ok}")
        assertEquals(first, second, "reading the codeset must not be a mutation")
        assertEquals(CharacterEncoding.verdict(first).ok, CharacterEncoding.check().ok, "check() is verdict(read())")
    }
}
