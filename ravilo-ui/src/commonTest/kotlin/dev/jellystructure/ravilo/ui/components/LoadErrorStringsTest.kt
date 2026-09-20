package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R280 — what a failed screen load says, and in which language.
 *
 * Before this phase ten screens drew a store's raw `message`, which is `TvApiError.Http.message` —
 * the HTTP response body verbatim. The reason nobody noticed for so long is that there was nothing
 * to notice it *with*: the text was never a literal, so R279's checker could not see it, and no test
 * ever asserted what an error screen says. These are that test.
 */
class LoadErrorStringsTest {

    private val languages = listOf("en", "da", "fo")

    /**
     * Every sentence this surface can show exists, in every language we ship. `t` renders a key that
     * is in no table as itself, and falls back to English per key — so "not the key" proves it
     * exists at all, and "not the English" proves the translation is really there and not a silent
     * fallback. The point of the phase is a Faroese household reading Faroese.
     */
    @Test
    fun every_cause_has_a_real_sentence_in_every_language() {
        for (kind in LoadErrorKind.entries) {
            val keys = listOfNotNull(loadErrorTitleKey(kind), loadErrorBodyKey(kind))
            assertTrue(keys.isNotEmpty(), "$kind must at least have a heading")
            for (key in keys) {
                val english = t(key, "en")
                assertNotEquals(key, english, "$kind: '$key' is in no table — it would render as itself")
                assertTrue(english.isNotBlank(), "$kind: '$key' is blank in English")
                for (lang in languages - "en") {
                    assertNotEquals(
                        english, t(key, lang),
                        "$kind: '$key' falls back to English in '$lang' — an untranslated error screen",
                    )
                }
            }
        }
    }

    /** GONE and GENERIC have no honest next step; the rest owe the viewer a sentence. */
    @Test
    fun only_the_causes_with_nothing_to_add_omit_their_body() {
        assertEquals(null, loadErrorBodyKey(LoadErrorKind.GONE))
        assertEquals(null, loadErrorBodyKey(LoadErrorKind.GENERIC))
        assertTrue(loadErrorBodyKey(LoadErrorKind.REAUTH) != null)
        assertTrue(loadErrorBodyKey(LoadErrorKind.FORBIDDEN) != null)
        assertTrue(loadErrorBodyKey(LoadErrorKind.UNREACHABLE) != null)
    }

    /**
     * FR-R280-3 / FR-R237-3 — Retry only where trying again can plausibly change the answer. This is
     * why moving 401 out of GENERIC mattered: GENERIC offers Retry, so a dead token used to sit
     * behind a button that was guaranteed to fail for as long as anyone was willing to press it.
     */
    @Test
    fun retry_is_offered_only_where_it_could_work() {
        assertTrue(loadErrorOffersRetry(LoadErrorKind.UNREACHABLE))
        assertTrue(loadErrorOffersRetry(LoadErrorKind.GENERIC))
        assertFalse(loadErrorOffersRetry(LoadErrorKind.REAUTH), "signing in is what fixes it, not retrying")
        assertFalse(loadErrorOffersRetry(LoadErrorKind.FORBIDDEN))
        assertFalse(loadErrorOffersRetry(LoadErrorKind.GONE))
    }

    /**
     * The regression this phase exists to prevent: a 401 reaching a screen as GENERIC, which draws
     * "Something went wrong" and a Retry. Every TV read route answers 401 for a dead device token.
     */
    @Test
    fun a_dead_token_asks_you_to_sign_in_rather_than_to_try_again() {
        val kind = loadErrorKindOf(TvApiError.Http(401, """{"error":"Not logged in"}"""))
        assertEquals(LoadErrorKind.REAUTH, kind)
        assertFalse(loadErrorOffersRetry(kind))
        assertEquals("error.load.reauth.title", loadErrorTitleKey(kind))
        // And the viewer never sees the body that caused it.
        assertFalse(t(loadErrorTitleKey(kind), "da").contains("error"))
    }

    /** A transport failure with no response at all stays what it always was: worth another go. */
    @Test
    fun no_response_at_all_is_still_worth_retrying() {
        val kind = loadErrorKindOf(RuntimeException("connection reset"))
        assertEquals(LoadErrorKind.UNREACHABLE, kind)
        assertTrue(loadErrorOffersRetry(kind))
    }
}
