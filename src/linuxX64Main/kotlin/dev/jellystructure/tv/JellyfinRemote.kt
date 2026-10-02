package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/*
 * Phase 298 — the pure half of "the Jellyfin dashboard controls every Ravilo playback": what a device declares it
 * obeys, the capabilities that follows from it, what each Jellyfin `/socket` frame becomes on the device's own events
 * socket, and which leftover Jellyfin sessions the sweep ends. No I/O here, so every rule is a unit test.
 */

/** FR-298-1 — the commands a device may declare, in the order they are registered. Jellyfin `GeneralCommandType` names. */
val REMOTE_COMMANDS = listOf("DisplayMessage", "Play", "PlayState", "SetVolume", "VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute")

/**
 * FR-298-1 — the `remote=` query parameter of `/api/tv/events`: a comma list, kept to [REMOTE_COMMANDS] (case-insensitive,
 * once each, in that order). `null` when the parameter is absent — an app older than R354, which keeps today's
 * registration. An empty list is a device that obeys nothing.
 */
fun parseRemoteDeclaration(raw: String?): List<String>? {
    if (raw == null) return null
    val said = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return REMOTE_COMMANDS.filter { it.lowercase() in said }
}

/** Phase 110's registration, kept byte for byte for a device that declares nothing (FR-298-1). */
internal const val LEGACY_CAPABILITIES = """{"PlayableMediaTypes":["Video"],"SupportedCommands":["DisplayMessage","Play","Playstate"],"SupportsMediaControl":true}"""

/** FR-298-2 — `/Sessions/Capabilities/Full`'s body. `Video` is playable only by a device that takes *Play on*. */
fun capabilitiesBody(declared: List<String>?): String {
    if (declared == null) return LEGACY_CAPABILITIES
    val media = if ("Play" in declared) "[\"Video\"]" else "[]"
    val commands = declared.joinToString(",", "[", "]") { "\"$it\"" }
    return """{"PlayableMediaTypes":$media,"SupportedCommands":$commands,"SupportsMediaControl":true}"""
}

/** FR-298-3 — what a Jellyfin `/socket` frame asks of the device. */
sealed interface BridgeCommand {
    data class Message(val text: String, val header: String?, val timeoutMs: Long?) : BridgeCommand
    data class PlayItem(val itemId: String, val startMs: Long) : BridgeCommand
    /** Sent as today's `playstate_command`, [command] unchanged (Jellyfin's own name). */
    data class Playstate(val command: String, val seekMs: Long?) : BridgeCommand
    /** Sent as phase 236's `player_command`; [argsJson] is the already-encoded argument object, or null. */
    data class Player(val command: String, val argsJson: String?) : BridgeCommand
}

private val PLAYSTATE_COMMANDS = setOf("stop", "pause", "unpause", "playpause", "seek", "nexttrack", "previoustrack", "rewind", "fastforward")

/** FR-298-3 — one Jellyfin frame → one command, or null for anything that is not one (keepalives included). */
fun parseJellyfinMessage(raw: String): BridgeCommand? {
    val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val type = (json["MessageType"] as? JsonPrimitive)?.contentOrNull ?: return null
    val data = json["Data"] as? JsonObject
    return when (type) {
        "GeneralCommand" -> generalCommand(data ?: return null)
        "Play" -> {
            val ids = data?.get("ItemIds") as? JsonArray ?: return null
            val itemId = (ids.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
            val ticks = (data["StartPositionTicks"] as? JsonPrimitive)?.longOrNull ?: 0L
            BridgeCommand.PlayItem(itemId, ticks / 10_000L)
        }
        "Playstate" -> {
            val command = (data?.get("Command") as? JsonPrimitive)?.contentOrNull ?: return null
            if (command.lowercase() !in PLAYSTATE_COMMANDS) return null
            val ticks = (data["SeekPositionTicks"] as? JsonPrimitive)?.longOrNull
            BridgeCommand.Playstate(command, ticks?.let { it / 10_000L })
        }
        else -> null
    }
}

private fun generalCommand(data: JsonObject): BridgeCommand? {
    val name = (data["Name"] as? JsonPrimitive)?.contentOrNull ?: return null
    // Jellyfin's `Arguments` is a Dictionary<string,string>: every value arrives as a string ("35", "5000").
    val args = data["Arguments"] as? JsonObject
    fun arg(key: String): String? = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    return when (name) {
        "DisplayMessage" -> BridgeCommand.Message(arg("Text") ?: return null, arg("Header"), arg("TimeoutMs")?.toLongOrNull())
        "SetVolume" -> {
            val v = arg("Volume")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: return null
            BridgeCommand.Player("set_volume", """{"volume":$v}""")
        }
        "VolumeUp" -> BridgeCommand.Player("volume_up", null)
        "VolumeDown" -> BridgeCommand.Player("volume_down", null)
        "Mute" -> BridgeCommand.Player("mute", """{"muted":true}""")
        "Unmute" -> BridgeCommand.Player("mute", """{"muted":false}""")
        "ToggleMute" -> BridgeCommand.Player("mute", null)
        else -> null
    }
}

/** FR-298-6 — the fields of one `/Sessions` entry the sweep decides on. */
data class JfSessionSummary(
    val client: String?,
    val deviceId: String?,
    val nowPlaying: Boolean,
    val isActive: Boolean,
    val lastActivityEpochSec: Long?,
)

/** FR-298-6 — `/Sessions`' body → summaries; null when it is not a JSON array. */
fun parseJellyfinSessions(body: String): List<JfSessionSummary>? {
    val arr = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        JfSessionSummary(
            client = (o["Client"] as? JsonPrimitive)?.contentOrNull,
            deviceId = (o["DeviceId"] as? JsonPrimitive)?.contentOrNull,
            nowPlaying = o["NowPlayingItem"].let { it != null && it is JsonObject },
            isActive = (o["IsActive"] as? JsonPrimitive)?.booleanOrNull ?: false,
            lastActivityEpochSec = (o["LastActivityDate"] as? JsonPrimitive)?.contentOrNull?.let { isoToEpochSeconds(it) },
        )
    }
}

/**
 * FR-298-6 — the devices whose Jellyfin session the sweep ends: a Ravilo session of a device we hold, nothing playing,
 * no socket, idle at least [graceSec], and not bridged by us (open or in its grace — that one ends on its own).
 */
fun staleRaviloSessions(
    sessions: List<JfSessionSummary>,
    devices: List<DeviceData>,
    bridged: (String) -> Boolean,
    nowEpochSec: Long,
    graceSec: Long,
): List<DeviceData> {
    val byIdentity = devices.associateBy { JellyfinDeviceIdentity.forDevice(it).deviceId }
    return sessions.mapNotNull { s ->
        if (s.client != "Ravilo" || s.nowPlaying || s.isActive) return@mapNotNull null
        val device = byIdentity[s.deviceId ?: return@mapNotNull null] ?: return@mapNotNull null
        val last = s.lastActivityEpochSec ?: return@mapNotNull null
        if (nowEpochSec - last < graceSec) return@mapNotNull null
        if (bridged(device.deviceId)) return@mapNotNull null
        device
    }.distinctBy { JellyfinDeviceIdentity.forDevice(it).deviceId }
}
