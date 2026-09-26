package dev.jellystructure.shared.tv

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * R318 (FR-R318-1) — the one `Json` every app-side decoder of server data uses.
 *
 * Every server-sent enum is decoded strictly, and `ignoreUnknownKeys` does not help an unknown *value*:
 * before this, one new enum value from a newer server (a row kind, a skin) failed the whole payload, and
 * Home, a channel or `/api/tv/config` stopped loading on every installed app. `coerceInputValues` makes an
 * unknown value fall back to the field's default instead, so every server-sent enum field in `shared`
 * carries one. There is ONE instance so that no decoding site can be missed: a single site left on its own
 * `Json` is where the next new value would break something.
 */
val RaviloWireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** [RaviloWireJson] for app-side code that also writes its own records (cast state, resume records),
 *  which wants its defaults written out. */
val RaviloWireJsonWithDefaults: Json = Json(RaviloWireJson) { encodeDefaults = true }

/**
 * R318 (FR-R318-1) — a list whose elements a newer server may add kinds of: an element that does not
 * decode (an unknown enum value with no default to fall back to) is dropped, and the rest of the list is
 * kept. Not used by any server-sent field today (none is a list of enums); it is here for the next one.
 * Encodes as a plain list.
 */
class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val plain = ListSerializer(element)
    override val descriptor: SerialDescriptor = plain.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = plain.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return plain.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { json.json.decodeFromJsonElement(element, it) }.getOrNull() }
    }
}
