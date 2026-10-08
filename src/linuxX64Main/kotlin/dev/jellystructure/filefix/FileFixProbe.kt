package dev.jellystructure.filefix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Phase 314 — one ffprobe of a file, read into what the rules and the verification compare. */
data class ProbeResult(val facts: FileFacts, val streams: List<StreamFacts>)

private val probeJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull ?: str(key)?.toIntOrNull()
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A tag read case-insensitively (Matroska tags arrive as `TITLE`, `title`, `language`…). */
private fun JsonObject.tag(key: String): String? =
    obj("tags")?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.jsonPrimitive?.contentOrNull

/**
 * Reads `ffprobe -print_format json -show_streams -show_format` (FileFixCommands.probe). [audio] orders follow the
 * FILE's own audio streams (`0:a:<n>`, R382). [uids] maps the file's n-th audio stream to its Matroska track UID
 * (from [parseTrackUids]), for the copy's tag. Null on output that isn't a probe.
 */
fun parseProbe(path: String, output: String, uids: Map<Int, String> = emptyMap()): ProbeResult? {
    val root = runCatching { probeJson.parseToJsonElement(output).jsonObject }.getOrNull() ?: return null
    val streams = (root["streams"] as? JsonArray)?.map { it.jsonObject } ?: return null
    val format = root.obj("format")
    val container = format?.str("format_name")?.substringBefore(',') ?: "unknown"
    val durationMs = ((format?.str("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    var audioOrder = 0
    var video: VideoFacts? = null
    val audio = ArrayList<AudioFacts>()
    val all = ArrayList<StreamFacts>()
    for (s in streams) {
        val type = s.str("codec_type") ?: "data"
        val codec = s.str("codec_name") ?: "unknown"
        val disp = s.obj("disposition")
        val title = s.tag("title")
        val lang = s.tag("language")?.takeIf { it.isNotBlank() && it != "und" }
        all += StreamFacts(type, codec, lang, disp?.int("default") == 1, disp?.int("forced") == 1, title)
        when (type) {
            "video" -> if (video == null && disp?.int("attached_pic") != 1) {
                val dovi = (s["side_data_list"] as? JsonArray)?.map { it.jsonObject }
                    ?.firstOrNull { it.str("side_data_type")?.contains("DOVI", ignoreCase = true) == true || it.containsKey("dv_profile") }
                video = VideoFacts(
                    codec = codec,
                    dvProfile = dovi?.int("dv_profile"),
                    dvBlCompatibility = dovi?.int("dv_bl_signal_compatibility_id"),
                    dvElPresent = dovi?.int("el_present_flag") == 1,
                    frameRate = s.str("r_frame_rate"),
                )
            }
            "audio" -> {
                audio += AudioFacts(
                    order = audioOrder,
                    codec = codec,
                    language = lang,
                    channels = s.int("channels"),
                    title = title,
                    default = disp?.int("default") == 1,
                    commentary = disp?.int("comment") == 1 || isCommentary(title),
                    description = disp?.int("visual_impaired") == 1 || isDescription(title),
                    copyOf = s.tag("JELLYSTRUCTURE_COPY_OF"),
                    uid = uids[audioOrder],
                )
                audioOrder++
            }
        }
    }
    val isOurVersion = format?.obj("tags")?.entries?.any { it.key.equals("JELLYSTRUCTURE_DV", ignoreCase = true) } == true ||
        path.substringAfterLast('/').substringBeforeLast('.').endsWith(" - $DV_VERSION_LABEL")
    return ProbeResult(FileFacts(path, container, durationMs, video, audio, isOurVersion), all)
}

/** `mkvmerge -J` → the n-th audio track's UID (n among the file's audio tracks, the order ffmpeg's `0:a:<n>` uses). */
fun parseTrackUids(output: String): Map<Int, String> {
    val root = runCatching { probeJson.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyMap()
    val tracks = root["tracks"]?.jsonArray?.map { it.jsonObject } ?: return emptyMap()
    return tracks.filter { it.str("type") == "audio" }.mapIndexedNotNull { i, t -> t.obj("properties")?.str("uid")?.let { i to it } }.toMap()
}

/**
 * Phase 314c (Remove) — the file carries our global `JELLYSTRUCTURE_DV` tag (FR-314-4): only then is a version file deleted.
 * The name alone (`… - Dolby Vision.mkv`) is enough to plan around, never to delete.
 */
fun hasOurDvTag(ffprobeOutput: String): Boolean {
    val root = runCatching { probeJson.parseToJsonElement(ffprobeOutput).jsonObject }.getOrNull() ?: return false
    return root.obj("format")?.obj("tags")?.keys?.any { it.equals("JELLYSTRUCTURE_DV", ignoreCase = true) } == true
}
