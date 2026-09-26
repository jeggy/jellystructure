package dev.jellystructure.shared.wire

import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.wire.WireContract.bool
import dev.jellystructure.shared.wire.WireContract.obj
import dev.jellystructure.shared.wire.WireContract.str
import dev.jellystructure.shared.wire.WireContract.strings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * R319 (FR-R319-3) — today's models against the baseline of every release still in use.
 *
 * Two halves. **Static:** kinds, nullability, enum values, polymorphic subtypes and, for requests, which
 * fields the server requires — read off the descriptors. **Dynamic:** for a response, a minimal payload is
 * built from today's descriptor (optional scalars left out, so they take their defaults), read back
 * leniently, and re-encoded exactly as the server encodes; every field an old app requires must come out.
 * The dynamic half is what catches a default that silences a field (v1.41), because a descriptor does not
 * say whether a field is written when it equals its default (`@EncodeDefault` is invisible to it).
 */
object WireCheck {
    /** The server's own REST `Json` (Server.kt): no `encodeDefaults`, nulls written. */
    val serverJson = Json { ignoreUnknownKeys = true }

    /**
     * Enum values an old app does not know, that the server never sends — each with where that is made
     * sure of. Keyed by the enum's serial name.
     */
    val NEVER_SENT: Map<String, Set<String>> = mapOf(
        // Phase 269: `forClients()` maps it to CUSTOM on /api/tv/config, and Home builds the row as CUSTOM.
        "dev.jellystructure.shared.tv.RowKind" to setOf("RECOMMENDED"),
    )

    fun check(baseline: JsonObject, roots: List<WireRoot>): List<String> {
        val out = ArrayList<String>()
        for (root in roots) {
            val old = baseline.obj(root.name)?.obj("node") ?: continue   // a root no release in use decodes
            val d = root.serializer.descriptor
            when (root.dir) {
                WireDir.RESPONSE -> {
                    staticResponse(old, d, root.name, out, HashSet())
                    dynamicResponse(root, old, out)
                }
                WireDir.REQUEST -> staticRequest(old, d, root.name, out, HashSet())
            }
        }
        return out.distinct()
    }

    private fun kindMismatch(old: JsonObject, d: SerialDescriptor, path: String, out: MutableList<String>): Boolean {
        val ko = old.str("k"); val kc = WireContract.kindOf(d)
        if (ko == "REF" || ko == "ANY" || kc == "ANY") return true   // nothing further to compare here
        if (ko != kc) { out += "$path: was $ko on the wire, is now $kc"; return true }
        return false
    }

    // ─── Responses ────────────────────────────────────────────────────────────

    private fun staticResponse(old: JsonObject, d: SerialDescriptor, path: String, out: MutableList<String>, seen: HashSet<String>) {
        if (kindMismatch(old, d, path, out)) return
        if (!old.bool("n") && d.isNullable) out += "$path: may now be null, where an app in use requires a value"
        when (old.str("k")) {
            "ENUM" -> {
                val name = d.serialName.removeSuffix("?")
                val now = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
                val unknown = now - old.strings("v") - NEVER_SENT[name].orEmpty()
                if (unknown.isNotEmpty()) out += "$path: value(s) ${unknown.sorted()} of $name are unknown to an app in use — send an existing value, or list it in NEVER_SENT with where that is made sure of"
            }
            "LIST" -> staticResponse(old.obj("e")!!, d.getElementDescriptor(0), "$path[]", out, seen)
            "MAP" -> staticResponse(old.obj("v")!!, d.getElementDescriptor(1), "$path{}", out, seen)
            "POLY" -> {
                val now = WireContract.subtypes(d)
                val known = old.obj("s")!!
                val unknown = now.keys - known.keys
                if (unknown.isNotEmpty()) out += "$path: subtype(s) ${unknown.sorted()} are unknown to an app in use"
                for ((n, sd) in now) known.obj(n)?.let { staticResponse(it, sd, "$path<$n>", out, seen) }
            }
            "CLASS" -> {
                if (!seen.add(d.serialName + "@" + path.substringBefore('.'))) return
                for ((name, f) in old.obj("f")!!) {
                    val fo = f as JsonObject
                    val i = d.getElementIndex(name)
                    if (i < 0) { if (fo.bool("r")) out += "$path.$name: removed, and an app in use requires it"; continue }
                    val ed = d.getElementDescriptor(i)
                    // A required container given a default: the dynamic half supplies containers to reach
                    // what is inside them, so it cannot see this one left out. Say it here instead.
                    if (fo.bool("r") && d.isElementOptional(i) && WireContract.kindOf(ed) in setOf("LIST", "MAP", "CLASS", "POLY")) {
                        out += "$path.$name: an app in use requires it; with a default the server leaves it out when it equals the default — keep it required, or @EncodeDefault(ALWAYS) and list it in CONTAINERS_ALWAYS_WRITTEN"
                            .takeUnless { "$path.$name" in CONTAINERS_ALWAYS_WRITTEN } ?: continue
                    }
                    staticResponse(fo.obj("t")!!, ed, "$path.$name", out, seen)
                }
            }
        }
    }

    /** Container fields that became optional but are always written (`@EncodeDefault(ALWAYS)`). None today. */
    val CONTAINERS_ALWAYS_WRITTEN: Set<String> = emptySet()

    private fun dynamicResponse(root: WireRoot, old: JsonObject, out: MutableList<String>) {
        @Suppress("UNCHECKED_CAST") val ser = root.serializer as KSerializer<Any?>
        val sample = minimal(ser.descriptor, 0)
        val value = runCatching { RaviloWireJson.decodeFromJsonElement(ser, sample) }.getOrElse {
            out += "${root.name}: could not build a sample to encode (${it.message?.take(160)}) — the checker needs teaching this shape"
            return
        }
        val encoded = serverJson.encodeToJsonElement(ser, value)
        presence(encoded, old, root.name, out)
    }

    private const val MAX_DEPTH = 8

    /** A payload with every required field, and optional containers filled so nested types are reached. */
    fun minimal(d: SerialDescriptor, depth: Int): JsonElement = when (WireContract.kindOf(d)) {
        "STRING" -> JsonPrimitive("x")
        "INT" -> JsonPrimitive(0)
        "FRAC" -> JsonPrimitive(0.0)
        "BOOL" -> JsonPrimitive(false)
        "ENUM" -> JsonPrimitive(d.getElementName(0))
        "ANY" -> JsonObject(emptyMap())
        "LIST" -> if (depth > MAX_DEPTH) JsonArray(emptyList()) else {
            val e = d.getElementDescriptor(0)
            if (WireContract.kindOf(e) == "POLY") JsonArray(WireContract.subtypes(e).map { (n, sd) -> tagged(WireContract.discriminator(e), n, sd, depth + 1) })
            else JsonArray(listOf(minimal(e, depth + 1)))
        }
        "MAP" -> if (depth > MAX_DEPTH || WireContract.kindOf(d.getElementDescriptor(0)) != "STRING") JsonObject(emptyMap())
            else JsonObject(mapOf("k" to minimal(d.getElementDescriptor(1), depth + 1)))
        "POLY" -> WireContract.subtypes(d).entries.firstOrNull()?.let { (n, sd) -> tagged(WireContract.discriminator(d), n, sd, depth + 1) } ?: JsonObject(emptyMap())
        else -> JsonObject((0 until d.elementsCount).mapNotNull { i ->
            val ed = d.getElementDescriptor(i)
            val container = WireContract.kindOf(ed) in setOf("LIST", "MAP", "CLASS", "POLY")
            when {
                !d.isElementOptional(i) -> d.getElementName(i) to minimal(ed, depth + 1)
                container && depth < MAX_DEPTH -> d.getElementName(i) to minimal(ed, depth + 1)
                else -> null   // an optional scalar or enum takes its default: the case that must still be written
            }
        }.toMap())
    }

    private fun tagged(disc: String, name: String, d: SerialDescriptor, depth: Int): JsonElement =
        JsonObject(mapOf(disc to JsonPrimitive(name)) + (minimal(d, depth) as JsonObject))

    private fun presence(json: JsonElement, old: JsonObject, path: String, out: MutableList<String>) {
        when (old.str("k")) {
            "CLASS" -> {
                val o = json as? JsonObject ?: return
                for ((name, f) in old.obj("f")!!) {
                    val fo = f as JsonObject
                    val child = o[name]
                    val t = fo.obj("t")!!
                    when {
                        child == null -> if (fo.bool("r")) out += "$path.$name: an app in use requires it, and the server leaves it out (a default equal to the value is not written — use @EncodeDefault(ALWAYS))"
                        child is JsonNull -> if (!t.bool("n") && fo.bool("r")) out += "$path.$name: sent as null, where an app in use requires a value"
                        else -> presence(child, t, "$path.$name", out)
                    }
                }
            }
            "LIST" -> (json as? JsonArray)?.forEach { presence(it, old.obj("e")!!, "$path[]", out) }
            "MAP" -> (json as? JsonObject)?.values?.forEach { presence(it, old.obj("v")!!, "$path{}", out) }
            "POLY" -> {
                val o = json as? JsonObject ?: return
                val type = (o[old.str("disc") ?: "type"] as? JsonPrimitive)?.content ?: return
                old.obj("s")!!.obj(type)?.let { presence(o, it, "$path<$type>", out) }
            }
        }
    }

    // ─── Requests ─────────────────────────────────────────────────────────────

    private fun staticRequest(old: JsonObject, d: SerialDescriptor, path: String, out: MutableList<String>, seen: HashSet<String>) {
        if (kindMismatch(old, d, path, out)) return
        if (old.bool("n") && !d.isNullable) out += "$path: an app in use may send null here, and the server no longer accepts it"
        when (old.str("k")) {
            "ENUM" -> {
                val now = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
                val gone = old.strings("v") - now
                if (gone.isNotEmpty()) out += "$path: an app in use may send ${gone.sorted()}, which the server no longer knows"
            }
            "LIST" -> staticRequest(old.obj("e")!!, d.getElementDescriptor(0), "$path[]", out, seen)
            "MAP" -> staticRequest(old.obj("v")!!, d.getElementDescriptor(1), "$path{}", out, seen)
            "POLY" -> {
                val now = WireContract.subtypes(d)
                val known = old.obj("s")!!
                val gone = known.keys - now.keys
                if (gone.isNotEmpty()) out += "$path: an app in use may send subtype(s) ${gone.sorted()}, which the server no longer knows"
                for ((n, sd) in now) known.obj(n)?.let { staticRequest(it, sd, "$path<$n>", out, seen) }
            }
            "CLASS" -> {
                if (!seen.add(d.serialName + "@" + path.substringBefore('.'))) return
                val fields = old.obj("f")!!
                for (i in 0 until d.elementsCount) {
                    val name = d.getElementName(i)
                    val fo = fields.obj(name)
                    if (!d.isElementOptional(i) && (fo == null || !fo.bool("r")))
                        out += "$path.$name: the server requires it, and an app in use may not send it — give it a default"
                    if (fo != null) staticRequest(fo.obj("t")!!, d.getElementDescriptor(i), "$path.$name", out, seen)
                }
            }
        }
    }

    @Suppress("unused") private fun JsonElement.content(): String = jsonPrimitive.content
}
