package dev.jellystructure.ravilo.castv2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * R330 (FR-R330-1, dev review 2) — the channels and their messages, as the public Cast v2 documentation and open
 * senders describe them. The platform receiver is `receiver-0`; an app is reached through the `transportId` its
 * status names; media commands carry the `mediaSessionId` of the last `MEDIA_STATUS`; `requestId` pairs an
 * answer with its question.
 */
object CastNamespaces {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
    /** Ravilo's own channel (`CastCommand` / `CastReceiverMessage`), registered by the receiver. */
    const val RAVILO = "urn:x-cast:dev.jellystructure.ravilo"
}

const val PLATFORM_RECEIVER = "receiver-0"
const val DEFAULT_SENDER = "sender-0"

internal val castJson = Json { ignoreUnknownKeys = true }

internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it.toJson() } }.toMap())

private fun Any.toJson(): JsonElement = when (this) {
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(mapNotNull { it?.toJson() })
    is Map<*, *> -> JsonObject(entries.mapNotNull { (k, v) -> v?.let { k.toString() to it.toJson() } }.toMap())
    else -> error("not JSON: ${this::class}")
}

/** An app the receiver runs, from `RECEIVER_STATUS.status.applications`. */
data class CastApp(
    val appId: String,
    val displayName: String?,
    val sessionId: String,
    val transportId: String,
    val statusText: String?,
    val namespaces: List<String>,
)

/** `RECEIVER_STATUS.status`: what runs, and the device's (or group's) volume. */
data class CastReceiverStatus(val apps: List<CastApp>, val volumeLevel: Double?, val muted: Boolean?)

/** One `MEDIA_STATUS.status[]` entry, with the moment it arrived so the position can move on between reports. */
data class CastMediaStatus(
    val mediaSessionId: Int,
    /** `IDLE` · `PLAYING` · `PAUSED` · `BUFFERING` · `LOADING` */
    val playerState: String,
    /** `FINISHED` · `CANCELLED` · `INTERRUPTED` · `ERROR`, only while idle. */
    val idleReason: String?,
    val currentTimeSec: Double,
    val playbackRate: Double,
    val durationSec: Double?,
    val activeTrackIds: List<Long>,
    /** The media's own CAF tracks; null when the status carried no media. */
    val trackIds: List<Long>?,
    val title: String?,
    val subtitle: String?,
    val imageUrl: String?,
    /** Whether this report carried `media` at all: CAF sends it only when it changed. */
    val hasMedia: Boolean = true,
    val receivedAt: TimeMark = TimeSource.Monotonic.markNow(),
) {
    /** A report without `media` keeps what the last report of the same media session said about it. */
    fun withMediaFrom(previous: CastMediaStatus?): CastMediaStatus =
        if (hasMedia || previous == null || previous.mediaSessionId != mediaSessionId) this
        else copy(durationSec = previous.durationSec, trackIds = previous.trackIds, title = previous.title,
            subtitle = previous.subtitle, imageUrl = previous.imageUrl, hasMedia = previous.hasMedia)

    /** Where it is now: the reported time, moved on by the time since while it plays. */
    fun positionMs(): Long {
        val base = currentTimeSec * 1000
        val moved = if (playerState == "PLAYING") receivedAt.elapsedNow().inWholeMilliseconds * playbackRate else 0.0
        val pos = (base + moved).toLong().coerceAtLeast(0L)
        val end = durationSec?.let { (it * 1000).toLong() }
        return if (end != null && end > 0) pos.coerceAtMost(end) else pos
    }
}

internal object CastParse {
    fun type(o: JsonObject): String? = (o["type"] ?: o["responseType"])?.jsonPrimitive?.contentOrNull
    fun requestId(o: JsonObject): Int? = o["requestId"]?.jsonPrimitive?.intOrNull

    fun receiverStatus(o: JsonObject): CastReceiverStatus? {
        val status = o["status"] as? JsonObject ?: return null
        val apps = (status["applications"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            CastApp(
                appId = a.str("appId") ?: return@mapNotNull null,
                displayName = a.str("displayName"),
                sessionId = a.str("sessionId") ?: return@mapNotNull null,
                transportId = a.str("transportId") ?: return@mapNotNull null,
                statusText = a.str("statusText"),
                namespaces = (a["namespaces"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            )
        }
        val volume = status["volume"] as? JsonObject
        return CastReceiverStatus(apps, volume?.get("level")?.jsonPrimitive?.doubleOrNull, volume?.get("muted")?.jsonPrimitive?.booleanOrNull)
    }

    /** The first entry of `MEDIA_STATUS.status`, or null for an empty list (nothing loaded). */
    fun mediaStatus(o: JsonObject): CastMediaStatus? {
        val s = (o["status"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val media = s["media"] as? JsonObject
        val meta = media?.get("metadata") as? JsonObject
        return CastMediaStatus(
            mediaSessionId = s["mediaSessionId"]?.jsonPrimitive?.intOrNull ?: return null,
            playerState = s.str("playerState") ?: "IDLE",
            idleReason = s.str("idleReason"),
            currentTimeSec = s["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            playbackRate = s["playbackRate"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
            durationSec = media?.get("duration")?.jsonPrimitive?.doubleOrNull,
            activeTrackIds = (s["activeTrackIds"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull },
            trackIds = (media?.get("tracks") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("trackId")?.jsonPrimitive?.longOrNull },
            title = meta?.str("title"),
            subtitle = meta?.str("subtitle") ?: meta?.str("artist"),
            imageUrl = (meta?.get("images") as? JsonArray)?.firstOrNull()?.jsonObject?.str("url"),
            hasMedia = media != null,
        )
    }

    /** `GET_APP_AVAILABILITY`'s answer for [appId]. */
    fun available(o: JsonObject, appId: String): Boolean =
        (o["availability"] as? JsonObject)?.str(appId) == "APP_AVAILABLE"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Parses a payload, or null when it is not a JSON object. */
internal fun parsePayload(payload: String?): JsonObject? =
    payload?.let { runCatching { castJson.parseToJsonElement(it).jsonObject }.getOrNull() }
