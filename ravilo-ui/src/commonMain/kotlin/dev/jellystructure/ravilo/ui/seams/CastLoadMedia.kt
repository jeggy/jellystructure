package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.castLoadPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The LOAD's media for [data]: the receiver resolves contentId itself (it enrols and negotiates its own ticket), so no
 * media URL leaves the sender; `CastLoadData` rides as its customData — once (R359 FR-R359-2). Moved here from the Mac's
 * sender by R378: an Android TV's relay sends the same LOAD over the same `:ravilo-castv2` client.
 */
internal fun castLoadMedia(data: CastLoadData, json: Json = RaviloWireJsonWithDefaults): JsonObject {
    val song = data.tracks.getOrNull(data.currentIndex)
    fun abs(url: String?) = url?.let { if (it.startsWith("http")) it else data.serverUrl.trimEnd('/') + it }
    val metadata = buildJsonObject {
        if (song != null) {
            // R324 (FR-R324-9) — a song's card: cover · title · artist; the receiver rewrites it per song.
            put("metadataType", 3)
            put("title", song.title)
            song.artist?.let { put("artist", it) }
            song.album?.let { put("albumName", it) }
            abs(song.coverUrl ?: data.artUrl)?.let { url -> putJsonArray("images") { add(buildJsonObject { put("url", url) }) } }
        } else {
            put("metadataType", 1)
            put("title", data.title)
            data.kicker?.let { put("subtitle", it) }
            data.artUrl?.let { url -> putJsonArray("images") { add(buildJsonObject { put("url", url) }) } }
        }
    }
    return buildJsonObject {
        put("contentId", "ravilo://${data.itemId}")
        put("streamType", "BUFFERED")
        put("contentType", if (song != null) "audio/mpeg" else "application/x-mpegURL")
        put("metadata", metadata)
        put("customData", json.encodeToJsonElement(CastLoadData.serializer(), data))
    }
}

/** R378 (FR-R378-3) — what a relay sends: the LOAD's media, its start in seconds, and the queue's parts (R359) as JSON. */
internal class CastRelayFrames(val media: JsonObject, val startSec: Double, val parts: List<String>)

/**
 * R378 (FR-R378-3) — a relay's LOAD, exactly as the Mac's sender builds it for the same [data] (R359's plan: a queue too
 * long for one message goes as a window, the rest in parts after it; `CastLoadData` once, as the media's customData).
 */
internal fun castRelayFrames(data: CastLoadData, queueId: String, json: Json = RaviloWireJsonWithDefaults): CastRelayFrames {
    val plan = castLoadPlan(data, queueId, json)
    return CastRelayFrames(
        media = castLoadMedia(plan.load, json),
        startSec = (data.positionMs ?: 0L) / 1000.0,
        parts = plan.parts.map { json.encodeToString(CastCommand.serializer(), it) },
    )
}
