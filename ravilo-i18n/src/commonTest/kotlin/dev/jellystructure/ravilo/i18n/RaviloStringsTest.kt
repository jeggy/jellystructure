package dev.jellystructure.ravilo.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R279 — the generated table itself. These assert the *contract* the generator promises, not the
 * copy: which languages exist, that the fallback is per key rather than per table, and that a
 * placeholder is really substituted. Text-level drift is the `checkRaviloStrings` report's job.
 */
class RaviloStringsTest {

    /**
     * The one place that names the shipped languages. Adding `i18n/es.json` does not touch it —
     * `containsAll`, not `==`. Deliberately *removing* a language does, which is the point: that
     * should be a decision, not a side effect.
     */
    @Test
    fun this_build_ships_english_danish_and_faroese_with_english_first() {
        val codes = SUPPORTED_LANGUAGES.map { it.code }
        assertEquals(BASE_LANGUAGE, codes.first(), "the base language leads every picker")
        assertTrue(codes.containsAll(listOf("en", "da", "fo")), "expected en/da/fo, got $codes")
        // A picker shows endonyms (R161) — `Føroyskt`, not `Faroese`.
        assertEquals("Føroyskt", SUPPORTED_LANGUAGES.single { it.code == "fo" }.name)
        assertEquals("Dansk", SUPPORTED_LANGUAGES.single { it.code == "da" }.name)
        assertEquals("Faroese", SUPPORTED_LANGUAGES.single { it.code == "fo" }.englishName)
    }

    @Test
    fun a_key_resolves_in_every_language_and_never_comes_back_blank() {
        for (lang in SUPPORTED_LANGUAGES) {
            assertTrue(t("nav.home", lang.code).isNotBlank(), "nav.home is blank in ${lang.code}")
        }
    }

    @Test
    fun an_unknown_language_falls_back_to_english_rather_than_failing() {
        assertEquals(t("nav.home", BASE_LANGUAGE), t("nav.home", "kl"))
    }

    @Test
    fun an_unknown_key_renders_as_itself_so_the_mistake_is_visible() {
        assertEquals("nav.nope", t("nav.nope", "da"))
    }

    @Test
    fun placeholders_are_substituted_and_an_unfilled_one_is_left_alone() {
        assertEquals("Playing on Stue TV", t("cast.playing_on", "en", mapOf("device" to "Stue TV")))
        // A caller that forgets a var gets the brace back, not a crash — and it is visible on screen.
        assertTrue("{device}" in t("cast.playing_on", "en"))
    }

    @Test
    fun the_int_shorthand_fills_n() {
        assertEquals(t("cast.waiting", "en", mapOf("n" to "7")), t("cast.waiting", "en", 7))
    }

    @Test
    fun the_receivers_keys_resolve_in_every_language() {
        // R279 FR-R279-7 — the Chromecast and Tizen receivers stopped carrying their own table and
        // now read these out of the shared one. A key renaming mistake would render the key itself
        // on a 1920×1080 TV screen, which no other test would catch.
        val receiverKeys = listOf(
            "cast.ready", "loading", "cast.no_server", "cast.no_server_sub",
            "srv.busy", "srv.busy_sub", "cast.waiting", "player.up_next",
            "receiver.starts_in", "receiver.setup_title", "receiver.setup_hint",
            "receiver.setup_connect", "receiver.setup_trying", "receiver.setup_not_found",
        )
        for (lang in SUPPORTED_LANGUAGES) {
            for (key in receiverKeys) {
                assertTrue(t(key, lang.code) != key, "$key has no string in ${lang.code}")
            }
        }
    }

    /**
     * R279 FR-R279-9 — the app table used to carry `NAESTE`/`NAESTA` while the receiver table
     * carried `NÆSTE`/`NÆSTA` for the same moment on screen. One table now, the correct one.
     *
     * R288 — this asserted those two words exactly, and broke when the Faroese became `NÆSTI`,
     * agreeing with `partur`, which is masculine. That is a legitimate change to the copy, and this
     * file's own contract is that these tests assert the generator's promises and not the copy. The
     * wording is the translator's; the digraph is not. So the digraph is what is pinned.
     *
     * The general case is no longer this test's to carry: `scripts/check-i18n-spelling.sh` fails on
     * an `ae`/`oe`/`aa` digraph in *any* string of *any* language, which is strictly more than the
     * two keys here. This stays as the unit-level witness that the generated table — not just the
     * JSON — arrives with its letters intact.
     */
    @Test
    fun the_danish_and_faroese_next_up_kept_their_diacritics() {
        for (lang in listOf("da", "fo")) {
            val text = t("player.up_next", lang)
            assertTrue(text.contains("Æ"), "$lang lost the Æ in player.up_next: '$text'")
            assertFalse(text.contains("AE"), "$lang wrote the AE digraph in player.up_next: '$text'")
        }
    }
}
