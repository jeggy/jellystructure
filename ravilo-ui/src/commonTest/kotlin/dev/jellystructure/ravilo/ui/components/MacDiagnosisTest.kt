package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R385 (acceptance 7) — what a Mac that cannot reach the server is told.
 *
 * The bug this phase exists for was not a wrong string: it was the right string in a state nothing knew about. A
 * quarantined `Ravilo.app` that Finder never moved reaches a server on the household's own network not at all, and
 * R237's classifier called that *Couldn't reach the server · Check the connection and try again · Retry* — a sentence
 * that was false, and a button that could not work however many times it was pressed. So what is asserted here is the
 * table (which state means which cause) and the mapping (which cause says what, and offers which buttons), because
 * those are the two things that were wrong in a way no screen made obvious.
 */
class MacDiagnosisTest {

    private val languages = listOf("en", "da", "fo")

    /** The globals are a platform's to set (R335's `PlaybackAvailability` precedent); a test puts them back. */
    private fun <T> withMac(install: MacInstallState?, host: ServerHostClass, block: () -> T): T {
        val previousInstall = MacDiagnosis.installState
        val previousHost = MacDiagnosis.serverHostClass
        MacDiagnosis.installState = install
        MacDiagnosis.serverHostClass = host
        try {
            return block()
        } finally {
            MacDiagnosis.installState = previousInstall
            MacDiagnosis.serverHostClass = previousHost
        }
    }

    /** FR-R385-3's table, all eight rows of it, as a pure function of the two facts. */
    @Test
    fun the_table_is_exactly_what_the_spec_writes() {
        for (install in listOf(MacInstallState.TRANSLOCATED, MacInstallState.ON_VOLUME)) {
            assertEquals(
                LoadErrorKind.MAC_INSTALL_PLACE, macRefinedUnreachable(install, ServerHostClass.LAN),
                "$install + LAN is the one state the app can prove",
            )
            // D1 — a copy running against a public-resolving server works, and is not nagged. A viewer away from
            // the house is exactly that case, which is why this row is the whole reason install state is not a banner.
            assertEquals(null, macRefinedUnreachable(install, ServerHostClass.PUBLIC), "$install + PUBLIC works")
        }
        assertEquals(LoadErrorKind.MAC_LOCAL_NETWORK, macRefinedUnreachable(MacInstallState.IN_PLACE, ServerHostClass.LAN))
        assertEquals(null, macRefinedUnreachable(MacInstallState.IN_PLACE, ServerHostClass.PUBLIC))
    }

    /** FR-R385-2 — an unresolvable host is a DNS answer, not a permission state, so it is PUBLIC's row. */
    @Test
    fun a_host_that_does_not_resolve_explains_nothing() {
        for (install in MacInstallState.entries) {
            assertEquals(null, macRefinedUnreachable(install, ServerHostClass.UNKNOWN), "$install + UNKNOWN")
        }
    }

    /** Every other platform leaves the install state unset, and then nothing anywhere is refined. */
    @Test
    fun nothing_is_refined_off_the_mac() {
        for (host in ServerHostClass.entries) assertEquals(null, macRefinedUnreachable(null, host), "host=$host")
        withMac(null, ServerHostClass.LAN) {
            assertEquals(LoadErrorKind.UNREACHABLE, loadErrorKindOf(RuntimeException("connection reset")))
        }
    }

    /** FR-R385-3 — a failure with no answer at all is the only shape either cause refines. */
    @Test
    fun a_failure_with_no_answer_becomes_the_cause_the_mac_can_name() {
        withMac(MacInstallState.TRANSLOCATED, ServerHostClass.LAN) {
            assertEquals(LoadErrorKind.MAC_INSTALL_PLACE, loadErrorKindOf(RuntimeException("Connection refused")))
        }
        withMac(MacInstallState.IN_PLACE, ServerHostClass.LAN) {
            assertEquals(LoadErrorKind.MAC_LOCAL_NETWORK, loadErrorKindOf(RuntimeException("timeout")))
            // The stores that hand on a null cause get the same verdict: there was no answer there either.
            assertEquals(LoadErrorKind.MAC_LOCAL_NETWORK, loadErrorKindOf(null))
        }
    }

    /** FR-R385-3 — a reply proves the socket, so no status whatsoever is ever one of these two. */
    @Test
    fun an_http_answer_of_any_status_is_never_a_mac_cause() {
        withMac(MacInstallState.TRANSLOCATED, ServerHostClass.LAN) {
            for (status in listOf(401, 403, 404, 409, 429, 500, 503, 418)) {
                val kind = loadErrorKindOf(TvApiError.Http(status, "{}"))
                assertNotEquals(LoadErrorKind.MAC_INSTALL_PLACE, kind, "$status answered")
                assertNotEquals(LoadErrorKind.MAC_LOCAL_NETWORK, kind, "$status answered")
            }
        }
    }

    /**
     * FR-R385-4 / FR-R280-3 — Retry only where pressing it can change the answer. Turning the permission on does;
     * moving the app is not something the app can be retried into. This is R237's own mistake, so it is asserted
     * on the classifier's verdict as well as on the button.
     */
    @Test
    fun retry_is_offered_for_the_permission_and_never_for_the_install() {
        assertTrue(loadErrorOffersRetry(LoadErrorKind.MAC_LOCAL_NETWORK))
        assertFalse(loadErrorOffersRetry(LoadErrorKind.MAC_INSTALL_PLACE), "nothing the viewer can press changes it")
        withMac(MacInstallState.ON_VOLUME, ServerHostClass.LAN) {
            assertFalse(classifyLoadFailure(RuntimeException("no route to host")).retryable, "the retry loop stops too")
        }
        withMac(MacInstallState.IN_PLACE, ServerHostClass.LAN) {
            assertTrue(classifyLoadFailure(RuntimeException("no route to host")).retryable, "a grant changes it")
        }
    }

    /** FR-R385-4 — the two causes' headings and sentences, including the one that depends on where the copy is. */
    @Test
    fun each_cause_says_its_own_thing() {
        assertEquals("mac.install_place.title", loadErrorTitleKey(LoadErrorKind.MAC_INSTALL_PLACE))
        assertEquals("mac.local_network_blocked.title", loadErrorTitleKey(LoadErrorKind.MAC_LOCAL_NETWORK))
        assertEquals(
            "mac.install_place.body_volume",
            loadErrorBodyKey(LoadErrorKind.MAC_INSTALL_PLACE, MacInstallState.ON_VOLUME),
        )
        assertEquals(
            "mac.install_place.body_translocated",
            loadErrorBodyKey(LoadErrorKind.MAC_INSTALL_PLACE, MacInstallState.TRANSLOCATED),
        )
        assertEquals("mac.local_network_blocked.body", loadErrorBodyKey(LoadErrorKind.MAC_LOCAL_NETWORK))
        // Both causes owe a sentence: a heading alone would be the half-explanation this phase replaces.
        for (kind in listOf(LoadErrorKind.MAC_INSTALL_PLACE, LoadErrorKind.MAC_LOCAL_NETWORK)) {
            assertTrue(loadErrorBodyKey(kind) != null, "$kind needs a body")
        }
    }

    /**
     * FR-R385-8 — every key this phase names is in all three tables. `t` renders an unknown key as itself and falls
     * back to English per key, so "not the key" proves it exists and "not the English" proves the translation is
     * really there. `mac.menu_quit` and `mac.open_settings` are the buttons, reused rather than restated.
     */
    @Test
    fun every_key_the_mac_shows_exists_in_every_language() {
        val keys = listOf(
            "mac.install_place.title", "mac.install_place.body_translocated", "mac.install_place.body_volume",
            "mac.local_network_blocked.title", "mac.local_network_blocked.body",
            "mac.menu_quit", "mac.open_settings", "mac.open_anyway", "action.retry",
        )
        for (key in keys) {
            val english = t(key, "en")
            assertNotEquals(key, english, "'$key' is in no table — it would render as itself")
            assertTrue(english.isNotBlank(), "'$key' is blank in English")
            for (lang in languages - "en") {
                assertNotEquals(english, t(key, lang), "'$key' falls back to English in '$lang'")
            }
        }
    }

    /**
     * D3 — the copy names what the viewer must do and what their own Mac calls it. It never names the mechanism:
     * "translocation", "TCC" and "bundle" are ours, not theirs, and a sentence with one of them in it explains
     * nothing to the person reading it.
     */
    @Test
    fun no_string_names_the_mechanism() {
        val forbidden = listOf("transloc", "tcc", "bundle", "quarantin")
        val keys = listOf(
            "mac.install_place.title", "mac.install_place.body_translocated", "mac.install_place.body_volume",
            "mac.local_network_blocked.title", "mac.local_network_blocked.body", "mac.open_anyway",
        )
        for (lang in languages) for (key in keys) {
            val text = t(key, lang).lowercase()
            for (word in forbidden) assertFalse(word in text, "'$key' in '$lang' names '$word': $text")
        }
    }

    /** FR-R385-6 — the update line's sentence leads with the drag, which is the install this phase is about. */
    @Test
    fun the_update_line_says_to_drag_it_first() {
        val english = t("mac.open_anyway", "en")
        assertTrue(english.startsWith("Open the disk image and drag Ravilo"), english)
        assertTrue(
            english.indexOf("Applications") < english.indexOf("Open Anyway"),
            "the drag comes before the Gatekeeper sentence: $english",
        )
    }
}
