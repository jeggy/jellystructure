package dev.jellystructure.shared.wire

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R319 (FR-R319-3/5) — today's models keep every app in use working, and the check itself catches what it
 * is for (tested on invented types, so it does not depend on today's models being good or bad).
 */
class WireCompatTest {
    @Test
    fun todaysModelsKeepEveryAppInUseWorking() {
        val baseline = Json.parseToJsonElement(WIRE_BASELINE).jsonObject
        val violations = WireCheck.check(baseline, WIRE_ROOTS)
        assertTrue(violations.isEmpty(),
            "An app installed from a release in ${WIRE_BASELINE_TAGS.firstOrNull()}..${WIRE_BASELINE_TAGS.lastOrNull()} would break:\n" +
                violations.joinToString("\n") { "  - $it" })
    }

    // ─── The check itself (FR-R319-5) ─────────────────────────────────────────

    @Serializable enum class Kind { A, B }
    @Serializable enum class KindPlus { A, B, C }
    @Serializable data class Old(val kind: Kind, val name: String, val note: String? = null)
    @Serializable data class DefaultedSilently(val kind: Kind = Kind.A, val name: String, val note: String? = null)
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable data class DefaultedWritten(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val kind: Kind = Kind.A, val name: String, val note: String? = null)
    @Serializable data class NewValue(val kind: KindPlus, val name: String)
    @Serializable data class NewKind(val kind: Kind, val name: Int)
    @Serializable data class NowNullable(val kind: Kind?, val name: String)
    @Serializable data class NewOptional(val kind: Kind, val name: String, val note: String? = null, val extra: String = "")
    @Serializable data class Page(val rows: List<Old>)
    @Serializable data class PageSilent(val rows: List<DefaultedSilently>)

    @Serializable data class OldRequest(val a: String, val b: Int = 0)
    @Serializable data class RequestNewRequired(val a: String, val b: Int = 0, val c: String)
    @Serializable data class RequestNewOptional(val a: String, val b: Int = 0, val c: String = "")
    @Serializable data class RequestNowNonNull(val a: String, val b: Int = 0)
    @Serializable data class OldNullableRequest(val a: String?, val b: Int = 0)

    private fun baselineOf(vararg roots: WireRoot) = WireContract.merge(listOf(WireContract.record(roots.toList())))
    private fun violations(old: WireRoot, now: WireRoot) = WireCheck.check(baselineOf(old), listOf(now))

    @Test fun aRequiredFieldGivenASilentDefaultIsCaughtAndAWrittenOneIsNot() {
        val old = WireRoot("T", WireDir.RESPONSE, Old.serializer())
        val silent = violations(old, WireRoot("T", WireDir.RESPONSE, DefaultedSilently.serializer()))
        assertTrue(silent.any { it.startsWith("T.kind:") && "leaves it out" in it }, silent.toString())
        assertEquals(emptyList(), violations(old, WireRoot("T", WireDir.RESPONSE, DefaultedWritten.serializer())))
    }

    @Test fun itIsCaughtInsideAListToo() {
        val v = violations(WireRoot("P", WireDir.RESPONSE, Page.serializer()), WireRoot("P", WireDir.RESPONSE, PageSilent.serializer()))
        assertTrue(v.any { it.startsWith("P.rows[].kind:") }, v.toString())
    }

    @Test fun aNewEnumValueAChangedKindAndANewNullAreCaught() {
        val old = WireRoot("T", WireDir.RESPONSE, Old.serializer())
        assertTrue(violations(old, WireRoot("T", WireDir.RESPONSE, NewValue.serializer())).any { "[C]" in it })
        assertTrue(violations(old, WireRoot("T", WireDir.RESPONSE, NewKind.serializer())).any { "T.name: was STRING" in it })
        assertTrue(violations(old, WireRoot("T", WireDir.RESPONSE, NowNullable.serializer())).any { "T.kind: may now be null" in it })
    }

    @Test fun aNewOptionalFieldIsFine() =
        assertEquals(emptyList(), violations(WireRoot("T", WireDir.RESPONSE, Old.serializer()), WireRoot("T", WireDir.RESPONSE, NewOptional.serializer())))

    @Test fun aRequestTheServerNowRequiresMoreOfIsCaught() {
        val old = WireRoot("R", WireDir.REQUEST, OldRequest.serializer())
        assertTrue(violations(old, WireRoot("R", WireDir.REQUEST, RequestNewRequired.serializer())).any { it.startsWith("R.c:") })
        assertEquals(emptyList(), violations(old, WireRoot("R", WireDir.REQUEST, RequestNewOptional.serializer())))
        assertTrue(violations(WireRoot("R", WireDir.REQUEST, OldNullableRequest.serializer()), WireRoot("R", WireDir.REQUEST, RequestNowNonNull.serializer()))
            .any { it.startsWith("R.a:") && "null" in it })
    }

    @Test fun mergingKeepsTheStrictestRule() {
        // Responses: required if any release required it; an enum value only if every release knows it.
        val merged = WireContract.merge(listOf(
            WireContract.record(listOf(WireRoot("T", WireDir.RESPONSE, NewValue.serializer()))),
            WireContract.record(listOf(WireRoot("T", WireDir.RESPONSE, Old.serializer()))),
        ))
        val v = WireCheck.check(merged, listOf(WireRoot("T", WireDir.RESPONSE, NewValue.serializer())))
        assertTrue(v.any { "[C]" in it }, "C is unknown to the older release: $v")
    }
}
