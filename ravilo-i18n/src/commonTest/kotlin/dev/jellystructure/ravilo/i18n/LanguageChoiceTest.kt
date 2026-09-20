package dev.jellystructure.ravilo.i18n

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R279 FR-R279-12 — the language ladder: the signed-in user's configured language, else the one
 * this device drew in last, else English. Every client walks it, including both receivers, so it is
 * tested once here rather than four times badly.
 *
 * Nothing here names a shipped language. Adding `i18n/es.json` — or removing one — must not make a
 * test about *resolution* go red, or the whole point of the phase is lost. Which languages this
 * build ships is asserted in exactly one place, [RaviloStringsTest].
 */
class LanguageChoiceTest {

    /** Some language that is not the base one. Every build has at least two; if one ever does not,
     *  this falls back to the base and the tests still say something true. */
    private val other: String = SUPPORTED_LANGUAGES.firstOrNull { it.code != BASE_LANGUAGE }?.code ?: BASE_LANGUAGE

    /** A code no build has strings for — what a stale config field or a hand-typed value looks like. */
    private val unknown: String = generateSequence(0) { it + 1 }
        .map { "zz$it" }
        .first { code -> SUPPORTED_LANGUAGES.none { it.code.equals(code, ignoreCase = true) } }

    @BeforeTest
    fun installEmptyStore() { LastLanguage.store = InMemoryLastLanguageStore() }

    @AfterTest
    fun restoreDefaultStore() { LastLanguage.store = InMemoryLastLanguageStore() }

    // ── The ladder ────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_signed_in_users_configured_language_wins() {
        assertEquals(other, resolveLanguage(configured = other, lastSession = BASE_LANGUAGE))
    }

    @Test
    fun with_nobody_signed_in_the_last_sessions_language_is_used() {
        assertEquals(other, resolveLanguage(configured = null, lastSession = other))
    }

    @Test
    fun with_nobody_signed_in_and_no_last_session_it_is_english() {
        assertEquals("en", BASE_LANGUAGE)
        assertEquals(BASE_LANGUAGE, resolveLanguage(configured = null, lastSession = null))
        assertEquals(BASE_LANGUAGE, resolveLanguage())
    }

    @Test
    fun a_configured_language_we_have_no_strings_for_falls_through_to_the_last_session() {
        // The viewer's stored `uiLanguage` can name a language a later build dropped, or one the
        // admin typed by hand. Falling back to what this device was actually drawing beats English.
        assertEquals(other, resolveLanguage(configured = unknown, lastSession = other))
        assertEquals(BASE_LANGUAGE, resolveLanguage(configured = unknown, lastSession = unknown))
    }

    @Test
    fun blank_and_whitespace_are_treated_as_absent_not_as_a_language() {
        // RaviloConfig.uiLanguage is a plain String with a default, so "" reaches us in practice.
        assertEquals(other, resolveLanguage(configured = "", lastSession = other))
        assertEquals(other, resolveLanguage(configured = "   ", lastSession = other))
        assertEquals(BASE_LANGUAGE, resolveLanguage(configured = "", lastSession = ""))
    }

    // ── Normalising what a caller hands over ──────────────────────────────────────────────────

    @Test
    fun a_region_tagged_or_oddly_cased_code_resolves_to_the_language_we_have() {
        val up = other.uppercase()
        for (code in listOf(other, up, "$other-$up", "${other}_$up", " $other ")) {
            assertEquals(other, normalizeLanguage(code), "normalizeLanguage(\"$code\")")
        }
    }

    @Test
    fun an_unknown_language_normalises_to_null() {
        assertNull(normalizeLanguage(unknown))
        assertNull(normalizeLanguage("$unknown-GL"))
        assertNull(normalizeLanguage(null))
        assertNull(normalizeLanguage(""))
        assertTrue(isSupportedLanguage(other))
        assertFalse(isSupportedLanguage(unknown))
    }

    @Test
    fun an_unknown_region_on_a_known_language_still_resolves() {
        // A TV reporting `en-GB` or a browser reporting `da-DK` must not land on the fallback by
        // accident — that is the whole reason the base subtag is tried.
        assertEquals(BASE_LANGUAGE, normalizeLanguage("$BASE_LANGUAGE-ZZ"))
        assertEquals(other, normalizeLanguage("$other-ZZ"))
    }

    // ── Remembering ───────────────────────────────────────────────────────────────────────────

    @Test
    fun the_language_a_signed_in_user_resolves_to_is_remembered_for_next_time() {
        assertEquals(other, resolveAndRememberLanguage(configured = other))
        assertEquals(other, LastLanguage.read())
        // …and is what the very next surface draws in, with nobody signed in.
        assertEquals(other, resolveAndRememberLanguage(configured = null))
    }

    @Test
    fun signing_out_does_not_forget_the_language() {
        resolveAndRememberLanguage(configured = other)
        // A sign-out clears sessions, not this. The login screen that follows is still in `other`.
        assertEquals(other, resolveLanguage(configured = null, lastSession = LastLanguage.read()))
    }

    @Test
    fun a_language_we_have_no_strings_for_is_never_remembered() {
        LastLanguage.remember(other)
        LastLanguage.remember(unknown)
        assertEquals(other, LastLanguage.read(), "a junk code must not outlive the session that produced it")
    }

    @Test
    fun a_remembered_code_is_stored_normalised_so_an_older_builds_value_still_reads() {
        LastLanguage.store = InMemoryLastLanguageStore("$other-${other.uppercase()}")
        assertEquals(other, LastLanguage.read())
        assertEquals(other, resolveLanguage(configured = null, lastSession = LastLanguage.read()))
    }

    @Test
    fun resolving_with_nobody_signed_in_learns_nothing_and_loses_nothing() {
        LastLanguage.remember(other)
        assertEquals(other, resolveAndRememberLanguage(configured = null))
        assertEquals(other, LastLanguage.read(), "an anonymous surface must not overwrite the memory with English")
    }

    // ── The scenarios the owner named, end to end ─────────────────────────────────────────────

    @Test
    fun a_receiver_between_casts_draws_in_the_language_of_the_viewer_who_last_cast() {
        // Cast → the hand-off payload carries that viewer's own configured language.
        assertEquals(other, resolveAndRememberLanguage(configured = other))
        // Cast ends. Nobody is signed in to a Chromecast; the idle screen still speaks their language.
        assertEquals(other, resolveLanguage(configured = null, lastSession = LastLanguage.read()))
    }

    @Test
    fun a_second_viewer_casting_is_drawn_in_their_own_language_not_the_first_viewers() {
        resolveAndRememberLanguage(configured = other)
        assertEquals(BASE_LANGUAGE, resolveAndRememberLanguage(configured = BASE_LANGUAGE))
        assertEquals(BASE_LANGUAGE, LastLanguage.read())
    }

    @Test
    fun a_receiver_out_of_its_box_is_english() {
        // Nothing remembered, nobody signed in — the only honest answer.
        assertEquals(BASE_LANGUAGE, resolveLanguage(configured = null, lastSession = LastLanguage.read()))
    }
}
