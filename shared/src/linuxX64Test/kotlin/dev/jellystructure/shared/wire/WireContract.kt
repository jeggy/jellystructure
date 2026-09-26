package dev.jellystructure.shared.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * R319 — the wire contract: what a payload looks like on the wire, recorded from the serial descriptors,
 * so that the models of a released app can be compared with today's without running that app.
 *
 * A node is a small JSON object:
 * - `k`: kind: `STRING` `INT` `FRAC` `BOOL` `ENUM` `LIST` `MAP` `CLASS` `POLY` `ANY` (a raw JSON element),
 *   or `REF` (a type already being expanded above, i.e. recursion);
 * - `n`: present and `true` when the value may be `null`;
 * - `CLASS`: `f` = field name → `{ r: required?, t: node }`;
 * - `ENUM`: `name` (the enum's serial name) and `v` (its values);
 * - `LIST`: `e`; `MAP`: `key`, `v`; `POLY`: `s` = subtype serial name → node.
 *
 * Class names are never compared: the wire does not carry them, so a renamed or moved class is the same
 * contract. This file must compile against the kotlinx.serialization of every release it records (it is
 * copied into old tags by `scripts/record-wire-baseline.sh`), so it sticks to long-stable API.
 */
enum class WireDir { RESPONSE, REQUEST }

class WireRoot(val name: String, val dir: WireDir, val serializer: KSerializer<*>)

object WireContract {
    // ─── Recording (FR-R319-1) ────────────────────────────────────────────────

    fun record(roots: List<WireRoot>): JsonObject = buildJsonObject {
        for (r in roots.sortedBy { it.name }) put(r.name, buildJsonObject {
            put("dir", r.dir.name)
            put("node", node(r.serializer.descriptor, ArrayList()))
        })
    }

    private fun isRawJson(d: SerialDescriptor) = d.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.")

    private fun prim(k: PrimitiveKind): String = when (k) {
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "INT"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "FRAC"
        PrimitiveKind.BOOLEAN -> "BOOL"
        else -> "STRING"
    }

    /** The kind a descriptor has on the wire, in the contract's words. */
    fun kindOf(d: SerialDescriptor): String {
        if (isRawJson(d)) return "ANY"
        return when (val k = d.kind) {
            is PrimitiveKind -> prim(k)
            SerialKind.ENUM -> "ENUM"
            SerialKind.CONTEXTUAL -> "ANY"
            StructureKind.LIST -> "LIST"
            StructureKind.MAP -> "MAP"
            is PolymorphicKind -> "POLY"
            else -> "CLASS"
        }
    }

    /** A polymorphic type's class discriminator on the wire: `@JsonClassDiscriminator`, else `type`. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun discriminator(d: SerialDescriptor): String =
        d.annotations.filterIsInstance<kotlinx.serialization.json.JsonClassDiscriminator>().firstOrNull()?.discriminator ?: "type"

    /** A sealed type's subtypes, by serial name (element 1 of its descriptor lists them). */
    fun subtypes(d: SerialDescriptor): Map<String, SerialDescriptor> {
        if (d.elementsCount < 2) return emptyMap()
        val value = d.getElementDescriptor(1)
        return (0 until value.elementsCount).associate { value.getElementName(it) to value.getElementDescriptor(it) }
    }

    fun node(d: SerialDescriptor, stack: MutableList<String>): JsonObject = buildJsonObject {
        if (d.isNullable) put("n", true)
        val kind = kindOf(d)
        val name = d.serialName.removeSuffix("?")
        when (kind) {
            "ENUM" -> {
                put("k", "ENUM"); put("name", name)
                put("v", JsonArray((0 until d.elementsCount).map { d.getElementName(it) }.sorted().map { JsonPrimitive(it) }))
            }
            "LIST" -> { put("k", "LIST"); put("e", node(d.getElementDescriptor(0), stack)) }
            "MAP" -> { put("k", "MAP"); put("key", node(d.getElementDescriptor(0), stack)); put("v", node(d.getElementDescriptor(1), stack)) }
            "POLY" -> {
                put("k", "POLY")
                put("disc", discriminator(d))
                put("s", buildJsonObject { for ((sub, sd) in subtypes(d).toList().sortedBy { it.first }) put(sub, node(sd, stack)) })
            }
            "CLASS" -> if (name in stack) { put("k", "REF"); put("ref", name) } else {
                stack.add(name)
                put("k", "CLASS")
                put("f", buildJsonObject {
                    for (i in 0 until d.elementsCount) put(d.getElementName(i), buildJsonObject {
                        if (!d.isElementOptional(i)) put("r", true)
                        put("t", node(d.getElementDescriptor(i), stack))
                    })
                })
                stack.removeAt(stack.size - 1)
            }
            else -> put("k", kind)
        }
    }

    // ─── Merging releases (FR-R319-2) ─────────────────────────────────────────

    /** Every release's contract into the strictest rules they imply, root by root. */
    fun merge(contracts: List<JsonObject>): JsonObject {
        val out = LinkedHashMap<String, JsonObject>()
        for (c in contracts) for ((root, v) in c) {
            val o = v.jsonObject
            val prev = out[root]
            out[root] = if (prev == null) o else {
                val dir = WireDir.valueOf(prev.str("dir")!!)
                buildJsonObject { put("dir", dir.name); put("node", mergeNode(prev.obj("node")!!, o.obj("node")!!, dir)) }
            }
        }
        return JsonObject(out.toList().sortedBy { it.first }.toMap())
    }

    private fun mergeNode(a: JsonObject, b: JsonObject, dir: WireDir): JsonObject {
        val ka = a.str("k"); val kb = b.str("k")
        if (ka == "REF" || ka == "ANY") return b
        if (kb == "REF" || kb == "ANY" || ka != kb) return a   // a kind changed in history: keep the older one
        val nullable = if (dir == WireDir.RESPONSE) a.bool("n") && b.bool("n") else a.bool("n") || b.bool("n")
        return buildJsonObject {
            put("k", ka!!)
            if (nullable) put("n", true)
            when (ka) {
                "ENUM" -> {
                    put("name", a.str("name") ?: b.str("name") ?: "")
                    val va = a.strings("v"); val vb = b.strings("v")
                    put("v", JsonArray((if (dir == WireDir.RESPONSE) va intersect vb else va union vb).sorted().map { JsonPrimitive(it) }))
                }
                "LIST" -> put("e", mergeNode(a.obj("e")!!, b.obj("e")!!, dir))
                "MAP" -> { put("key", mergeNode(a.obj("key")!!, b.obj("key")!!, dir)); put("v", mergeNode(a.obj("v")!!, b.obj("v")!!, dir)) }
                "POLY" -> {
                    put("disc", a.str("disc") ?: b.str("disc") ?: "type")
                    val sa = a.obj("s")!!; val sb = b.obj("s")!!
                    val names = if (dir == WireDir.RESPONSE) sa.keys intersect sb.keys else sa.keys union sb.keys
                    put("s", buildJsonObject {
                        for (n in names.sorted()) put(n, if (n in sa && n in sb) mergeNode(sa.obj(n)!!, sb.obj(n)!!, dir) else (sa.obj(n) ?: sb.obj(n))!!)
                    })
                }
                "CLASS" -> {
                    val fa = a.obj("f")!!; val fb = b.obj("f")!!
                    put("f", buildJsonObject {
                        for (n in (fa.keys union fb.keys).sorted()) {
                            val x = fa.obj(n); val y = fb.obj(n)
                            put(n, when {
                                x != null && y != null -> buildJsonObject {
                                    val r = if (dir == WireDir.RESPONSE) x.bool("r") || y.bool("r") else x.bool("r") && y.bool("r")
                                    if (r) put("r", true)
                                    put("t", mergeNode(x.obj("t")!!, y.obj("t")!!, dir))
                                }
                                // A field only one release has: required for a response if it was; for a
                                // request, a release without it never sends it.
                                dir == WireDir.REQUEST -> buildJsonObject { put("t", (x ?: y)!!.obj("t")!!) }
                                else -> (x ?: y)!!
                            })
                        }
                    })
                }
            }
        }
    }

    // ─── Small JSON helpers ───────────────────────────────────────────────────

    fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.content == "true"
    fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    fun JsonObject.strings(k: String): Set<String> = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
    @Suppress("unused") fun JsonElement.arr(): JsonArray = jsonArray
}
