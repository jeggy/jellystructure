package dev.jellystructure.filefix

import dev.jellystructure.resolver.LanguageResolver
import kotlinx.serialization.Serializable

/**
 * Phase 314 — a file every device can play directly. The pure rules: which file gets what, named how, verified how.
 * No I/O here; [FileFixService] probes, decides with these functions, and runs [FileFixCommands].
 *
 * Three kinds, each with its own switch (FR-314-1):
 * - **A · Stereo for everything:** an AAC-LC stereo 256 kbps track per audio language that has no AAC/MP3/Opus/FLAC/
 *   Vorbis track, made from that language's main track (the Chromecast, browsers, phones without AC-3).
 * - **B · Surround for lossless-only languages:** an E-AC-3 5.1 640 kbps track per language whose only tracks are
 *   TrueHD/DTS (the Mac, an iPhone, receivers by passthrough).
 * - **C · Dolby Vision the TVs play:** a second *version* of a profile-7 film, its RPU converted to 8.1, as Jellyfin's
 *   own version file `<folder> - Dolby Vision.mkv` beside the original (owner, 2026-10-08). The original is never changed.
 */
@Serializable
enum class FixKind(val id: String, val label: String) {
    STEREO("a", "Stereo for everything"),
    SURROUND("b", "Surround for lossless-only audio"),
    DOLBY_VISION("c", "Dolby Vision the TVs play");

    companion object {
        fun of(id: String?): FixKind? = entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }
    }
}

/** One audio stream of the FILE (never Jellyfin's numbering, R382): its place among the file's audio streams. */
@Serializable
data class AudioFacts(
    val order: Int,
    val codec: String,
    val language: String? = null,
    val channels: Int? = null,
    val title: String? = null,
    val default: Boolean = false,
    /** Matroska `comment` disposition, or a title saying so. */
    val commentary: Boolean = false,
    /** `visual_impaired` disposition (audio description), or a title saying so. */
    val description: Boolean = false,
    /** The source track UID tag a copy carries (`JELLYSTRUCTURE_COPY_OF`), when this stream is one of ours. */
    val copyOf: String? = null,
    /** The track's own UID (Matroska `uid`), for the copy's tag. */
    val uid: String? = null,
)

@Serializable
data class VideoFacts(
    val codec: String,
    /** Dolby Vision configuration record: profile, BL compatibility id, EL present. Null without DV. */
    val dvProfile: Int? = null,
    val dvBlCompatibility: Int? = null,
    val dvElPresent: Boolean = false,
    val frameRate: String? = null,
)

/** What a probe of one file says, as the rules need it. */
@Serializable
data class FileFacts(
    val path: String,
    /** ffprobe's `format_name` first word: `matroska`, `mov`… */
    val container: String,
    val durationMs: Long,
    val video: VideoFacts? = null,
    val audio: List<AudioFacts> = emptyList(),
    /** The global `JELLYSTRUCTURE_DV` tag: this file IS our profile-8.1 version. */
    val isOurDvVersion: Boolean = false,
)

/** One track a kind would add. [sourceOrder] indexes the file's own audio streams (`-map 0:a:<n>`). */
@Serializable
data class PlannedTrack(
    val sourceOrder: Int,
    val language: String?,
    val sourceCodec: String,
    val sourceChannels: Int?,
    val codec: String,
    val channels: Int,
    val bitrateKbps: Int,
    /** What a viewer sees as the track's name (FR-314-4). */
    val name: String,
    val estBytes: Long,
) {
    /** The dry-run line (FR-314-2): `+ AAC 2.0 English, from TrueHD 7.1 · ~230 MB`. */
    fun describe(): String {
        val lang = language?.let { LanguageResolver.normalize(it) }?.uppercase() ?: "untagged"
        return "+ ${codecLabel(codec)} ${channelLabel(channels)} $lang, from ${codecLabel(sourceCodec)} ${channelLabel(sourceChannels)} · ~${estBytes / 1_000_000} MB"
    }
}

/** A kind's verdict for one file. */
@Serializable
data class Verdict(
    val kind: FixKind,
    /** `add` · `skip` · `done` (already has what the kind adds). */
    val action: String,
    val tracks: List<PlannedTrack> = emptyList(),
    val reason: String? = null,
    val estBytes: Long = 0,
)

/** Players that decode these everywhere (the Chromecast, browsers, phones without a platform AC-3 decoder). */
val UNIVERSAL_AUDIO: Set<String> = setOf("aac", "mp3", "opus", "flac", "vorbis")

/** Lossy surround every Apple device and AV receiver takes. AAC counts only with 6 channels or more. */
private val LOSSY_SURROUND: Set<String> = setOf("ac3", "eac3")

/** Codecs only a few devices decode, so a lossless-only language needs kind B. DTS (core) included: Apple has none. */
private val NEEDS_SURROUND_COPY: Set<String> = setOf("truehd", "mlp", "dts", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_bluray")

const val STEREO_KBPS = 256
const val SURROUND_KBPS = 640

/** The added tracks' names (FR-314-4) — also the sidecar's kind token, never `default` or `forced` (review item 2). */
const val STEREO_NAME = "Stereo"
const val SURROUND_NAME = "Surround 5.1"

fun normCodec(codec: String?): String = when (val c = codec?.lowercase()?.trim().orEmpty()) {
    "dca", "dts-hd ma", "dts-hd", "dtshd" -> "dts"
    "e-ac-3", "ec-3", "a_eac3" -> "eac3"
    "ac-3", "a_ac3" -> "ac3"
    "a_aac", "mp4a" -> "aac"
    "a_truehd" -> "truehd"
    else -> c
}

private fun codecLabel(c: String?): String = when (normCodec(c)) {
    "aac" -> "AAC"; "eac3" -> "E-AC-3"; "ac3" -> "AC-3"; "truehd" -> "TrueHD"; "dts" -> "DTS"; "flac" -> "FLAC"
    "opus" -> "Opus"; "mp3" -> "MP3"; else -> (c ?: "?").uppercase()
}

private fun channelLabel(ch: Int?): String = when (ch) {
    null -> ""; 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "$ch ch"
}

private val COMMENTARY_WORDS = Regex("""\b(commentary|kommentar|commentaire|comentario|director'?s comments?)\b""", RegexOption.IGNORE_CASE)
private val DESCRIPTION_WORDS = Regex("""\b(audio ?description|described|synstolk|synstolkning|syntolkning|\bAD\b|descriptive)\b""", RegexOption.IGNORE_CASE)

fun isCommentary(title: String?): Boolean = title != null && COMMENTARY_WORDS.containsMatchIn(title)
fun isDescription(title: String?): Boolean = title != null && DESCRIPTION_WORDS.containsMatchIn(title)

/** The language key two tracks are compared by: ISO 639-2, or `und` for untagged (an untagged source gives an untagged copy). */
fun langKey(language: String?): String = language?.takeIf { it.isNotBlank() && it != "und" }?.let { LanguageResolver.toIso6392(it) ?: it.lowercase() } ?: "und"

/** A main track: not commentary, not audio description, not one of our copies. */
private fun AudioFacts.isMain(): Boolean = !commentary && !description && copyOf == null

/** Rough size of an added track: bitrate × duration (+2 % container). */
fun estimateBytes(bitrateKbps: Int, durationMs: Long): Long = (bitrateKbps * 1000L / 8L * (durationMs / 1000L) * 102L) / 100L

/**
 * FR-314-1 kinds A and B: what [kind] would add to [facts]. Per language, its main track is the default one if there is
 * one, else the first. Commentary and audio description are never sources, and a language that has only those is left
 * alone (review item 12). MP4 is out of scope (*skipped (MP4)*).
 */
fun planAudio(kind: FixKind, facts: FileFacts): Verdict {
    require(kind == FixKind.STEREO || kind == FixKind.SURROUND)
    if (facts.container != "matroska") return Verdict(kind, "skip", reason = "MP4 and other containers: MKV only in this phase")
    if (facts.audio.isEmpty()) return Verdict(kind, "skip", reason = "no audio")
    val byLang = facts.audio.groupBy { langKey(it.language) }
    val planned = ArrayList<PlannedTrack>()
    var alreadyDone = false
    for ((_, tracks) in byLang) {
        val mains = tracks.filter { it.isMain() }
        if (mains.isEmpty()) continue
        val codecs = tracks.filter { it.copyOf == null }.map { normCodec(it.codec) }
        val copies = tracks.filter { it.copyOf != null }
        val source = mains.firstOrNull { it.default } ?: mains.first()
        when (kind) {
            FixKind.STEREO -> {
                if (copies.any { normCodec(it.codec) == "aac" }) { alreadyDone = true; continue }
                if (codecs.any { it in UNIVERSAL_AUDIO }) continue
                planned += PlannedTrack(source.order, source.language, normCodec(source.codec), source.channels, "aac", 2,
                    STEREO_KBPS, STEREO_NAME, estimateBytes(STEREO_KBPS, facts.durationMs))
            }
            FixKind.SURROUND -> {
                if (copies.any { normCodec(it.codec) == "eac3" }) { alreadyDone = true; continue }
                val hasLossySurround = tracks.any { t ->
                    val c = normCodec(t.codec)
                    t.copyOf == null && (c in LOSSY_SURROUND || (c == "aac" && (t.channels ?: 0) >= 6))
                }
                if (hasLossySurround) continue
                if (mains.none { normCodec(it.codec) in NEEDS_SURROUND_COPY }) continue
                val src = mains.firstOrNull { it.default && normCodec(it.codec) in NEEDS_SURROUND_COPY } ?: mains.first { normCodec(it.codec) in NEEDS_SURROUND_COPY }
                if ((src.channels ?: 0) < 3) continue   // a stereo lossless track: kind A covers it
                planned += PlannedTrack(src.order, src.language, normCodec(src.codec), src.channels, "eac3",
                    minOf(src.channels ?: 6, 6), SURROUND_KBPS, SURROUND_NAME, estimateBytes(SURROUND_KBPS, facts.durationMs))
            }
            else -> Unit
        }
    }
    return when {
        planned.isNotEmpty() -> Verdict(kind, "add", planned, estBytes = planned.sumOf { it.estBytes })
        alreadyDone -> Verdict(kind, "done")
        else -> Verdict(kind, "skip", reason = "every language already has a ${if (kind == FixKind.STEREO) "track any device plays" else "lossy surround track"}")
    }
}

/** The Jellyfin version name of a film's profile-8.1 copy: `<folder> - Dolby Vision.mkv` beside the original. */
const val DV_VERSION_LABEL = "Dolby Vision"

fun dvVersionPath(originalPath: String): String {
    val dir = originalPath.substringBeforeLast('/')
    val folder = dir.substringAfterLast('/')
    return "$dir/$folder - $DV_VERSION_LABEL.mkv"
}

/**
 * Jellyfin groups the videos of a film folder as versions only when **every** one of them starts with the folder's name
 * (`VideoListResolver.IsEligibleForMultiVersion`, v12.1); otherwise each file is its own film. A copy beside a
 * scene-named original would show up as a second film, so kind C needs the original named after its folder.
 */
fun namedAfterFolder(originalPath: String): Boolean {
    val dir = originalPath.substringBeforeLast('/')
    val folder = dir.substringAfterLast('/')
    val name = originalPath.substringAfterLast('/').substringBeforeLast('.')
    if (folder.length <= 1 || !name.startsWith(folder, ignoreCase = true)) return false
    val rest = name.substring(folder.length).trim()
    return rest.isEmpty() || rest[0] == '-' || rest[0] == '_' || rest[0] == '.' || MULTI_VERSION_TAIL.containsMatchIn(rest)
}

/**
 * Phase 314c (Remove) — the mkvmerge track ids of the copies [kind] added in the file: audio tracks named like our copy
 * (*Stereo* AAC for A, *Surround 5.1* E-AC-3 for B) that carry the `JELLYSTRUCTURE_COPY_OF` tag when mkvmerge reports
 * tags, from `mkvmerge -J`. Never an original: a track without our name is never listed, whatever its codec.
 */
fun copyTrackIds(mkvmergeJson: String, kind: FixKind): List<Int> {
    if (kind == FixKind.DOLBY_VISION) return emptyList()
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(mkvmergeJson) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return emptyList()
    val tracks = root["tracks"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    val wantCodec = if (kind == FixKind.STEREO) "A_AAC" else "A_EAC3"
    val wantName = if (kind == FixKind.STEREO) STEREO_NAME else SURROUND_NAME
    return tracks.mapNotNull { t ->
        val o = t as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun str(obj: kotlinx.serialization.json.JsonObject?, k: String) = (obj?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (str(o, "type") != "audio") return@mapNotNull null
        val props = o["properties"] as? kotlinx.serialization.json.JsonObject
        val codecId = str(props, "codec_id") ?: return@mapNotNull null
        if (!codecId.startsWith(wantCodec)) return@mapNotNull null
        if (!isCopyTitle(str(props, "track_name"), wantName)) return@mapNotNull null
        // mkvmerge lists a track's tags as `tag_<name>` (statistics tags on every track): ours must be there —
        // `JELLYSTRUCTURE_ADDED` on every copy, `JELLYSTRUCTURE_COPY_OF` on one made from a source with a UID.
        val tagged = props?.keys?.any { it.startsWith("tag_") } == true
        if (tagged && props?.get("tag_jellystructure_added") == null && props?.get("tag_jellystructure_copy_of") == null) return@mapNotNull null
        (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    }
}

/** Phase 314c (Remove) — the file after a Remove holds every original stream, in order, and none of the removed ones. */
fun verifyRemoved(before: List<StreamFacts>, removedPositions: Set<Int>, after: List<StreamFacts>): String? {
    val expected = before.filterIndexed { i, _ -> i !in removedPositions }
    if (after.size != expected.size) return "the new file has ${after.size} streams, expected ${expected.size}"
    for ((i, e) in expected.withIndex()) {
        val a = after[i]
        if (a.type != e.type || normCodec(a.codec) != normCodec(e.codec) || langKey(a.language) != langKey(e.language)) return "stream $i changed"
        if (a.default != e.default || a.forced != e.forced) return "stream $i's flags changed"
    }
    return null
}

/** Phase 314c — the original's name once renamed after its folder (`<dir>/<folder>.<ext>`); null when it already is. */
fun renamedToFolder(originalPath: String): String? {
    val dir = originalPath.substringBeforeLast('/')
    val folder = dir.substringAfterLast('/')
    val ext = originalPath.substringAfterLast('.', "mkv")
    val target = "$dir/$folder.$ext"
    return target.takeIf { it != originalPath && folder.isNotBlank() }
}

/** Jellyfin's `CheckMultiVersionRegex` (`[0-9]{2}[0-9]+[ip]`) and its bracket form, for a tail like `2160p`. */
private val MULTI_VERSION_TAIL = Regex("""^(\[[^]]*]|[0-9]{2}[0-9]+[ip])""", RegexOption.IGNORE_CASE)

/** FR-314-1 kind C: a film whose picture is Dolby Vision profile 7 (with its enhancement layer). */
fun planDolbyVision(facts: FileFacts, isFilm: Boolean, otherVideosInFolder: List<String>): Verdict {
    val k = FixKind.DOLBY_VISION
    if (facts.isOurDvVersion) return Verdict(k, "skip", reason = "this is our profile 8.1 version")
    val v = facts.video ?: return Verdict(k, "skip", reason = "no video")
    if (normCodec(v.codec) != "hevc" || v.dvProfile != 7) return Verdict(k, "skip", reason = "not Dolby Vision profile 7")
    if (!isFilm) return Verdict(k, "skip", reason = "episodes: films only in this phase")
    if (facts.container != "matroska") return Verdict(k, "skip", reason = "MP4 and other containers: MKV only in this phase")
    val target = dvVersionPath(facts.path)
    if (otherVideosInFolder.any { it == target }) return Verdict(k, "done")
    if (!namedAfterFolder(facts.path)) return Verdict(k, "skip", reason = "the file isn't named after its folder, so Jellyfin would show the copy as a second film")
    val others = otherVideosInFolder.filter { it != facts.path && it != target }
    if (others.any { !namedAfterFolder(it) }) return Verdict(k, "skip", reason = "another video in the folder isn't named after it, so Jellyfin would not group the versions")
    return Verdict(k, "add", reason = if (v.dvElPresent) "profile 7 with its enhancement layer · the original stays as it is" else "profile 7 · the original stays as it is")
}

// ── Verification (FR-314-3 step 4) ────────────────────────────────────────────────────────────────────────────────

/** One stream as verification compares it. */
@Serializable
data class StreamFacts(
    val type: String,
    val codec: String,
    val language: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
    val title: String? = null,
)

/**
 * Kinds A/B in the file: every original stream is still there, in its order, with the same codec, language and flags;
 * the added tracks follow, last, as planned (audio, never default or forced, their language exactly the source's);
 * the duration is the same within a frame's worth (60 ms). Returns null when it holds, else why not.
 */
fun verifyAppended(before: List<StreamFacts>, beforeMs: Long, after: List<StreamFacts>, afterMs: Long, added: List<PlannedTrack>): String? {
    if (after.size != before.size + added.size) return "the new file has ${after.size} streams, expected ${before.size + added.size}"
    for ((i, b) in before.withIndex()) {
        val a = after[i]
        if (a.type != b.type || normCodec(a.codec) != normCodec(b.codec)) return "stream $i changed (${b.type}/${b.codec} → ${a.type}/${a.codec})"
        if (langKey(a.language) != langKey(b.language)) return "stream $i's language changed"
        if (a.default != b.default || a.forced != b.forced) return "stream $i's flags changed"
    }
    for ((j, p) in added.withIndex()) {
        val a = after[before.size + j]
        if (a.type != "audio" || normCodec(a.codec) != p.codec) return "the added track $j is ${a.type}/${a.codec}, expected audio/${p.codec}"
        if (langKey(a.language) != langKey(p.language)) return "the added track $j's language is ${a.language}, expected ${p.language}"
        if (a.default || a.forced) return "the added track $j is marked default or forced"
    }
    if (kotlin.math.abs(afterMs - beforeMs) > 60) return "the duration changed by ${kotlin.math.abs(afterMs - beforeMs)} ms"
    return null
}

/** Kind C: the version has every stream of the original in the same order and the same frame count; DV profile 8.1. */
fun verifyDvVersion(
    before: List<StreamFacts>, beforeFrames: Long?, after: List<StreamFacts>, afterFrames: Long?, afterVideo: VideoFacts?,
): String? {
    if (after.size != before.size) return "the version has ${after.size} streams, the original ${before.size}"
    for ((i, b) in before.withIndex()) {
        val a = after[i]
        if (a.type != b.type || normCodec(a.codec) != normCodec(b.codec)) return "stream $i changed (${b.type}/${b.codec} → ${a.type}/${a.codec})"
        if (langKey(a.language) != langKey(b.language)) return "stream $i's language changed"
    }
    if (afterVideo?.dvProfile != 8 || afterVideo.dvBlCompatibility != 1) return "the version reports Dolby Vision profile ${afterVideo?.dvProfile}/${afterVideo?.dvBlCompatibility}, expected 8.1"
    if (afterVideo.dvElPresent) return "the version still carries an enhancement layer"
    if (beforeFrames == null || afterFrames == null || beforeFrames != afterFrames) return "frame count $afterFrames, the original has $beforeFrames"
    return null
}

// ── The right track and version for each device (FR-314-5) ────────────────────────────────────────────────────

/** One audio stream as Jellyfin lists it (its index is Jellyfin's, which PlaybackInfo takes). */
data class JellyfinAudio(val index: Int, val codec: String?, val language: String?, val title: String?, val isDefault: Boolean)

/**
 * FR-314-5 — an added copy is the same audio as its source. When the device can't decode the stream that would play
 * ([chosenIndex], else Jellyfin's default audio) and a copy of the same language that it can decode exists, the copy's
 * Jellyfin index; else null (nothing changes — the TVs keep the original). [decodable] is the device's declared audio
 * codecs; empty (a client that declares none) changes nothing. A surround copy wins over a stereo one.
 */
fun copyForDevice(audio: List<JellyfinAudio>, chosenIndex: Int?, decodable: List<String>): Int? {
    if (decodable.isEmpty() || audio.isEmpty()) return null
    val can = decodable.map { normCodec(it) }.toSet()
    val source = audio.firstOrNull { it.index == chosenIndex } ?: audio.firstOrNull { it.isDefault } ?: audio.first()
    if (normCodec(source.codec) in can) return null
    fun isCopy(a: JellyfinAudio, name: String) = isCopyTitle(a.title, name)
    val sameLang = audio.filter { it.index != source.index && langKey(it.language) == langKey(source.language) && normCodec(it.codec) in can }
    return (sameLang.firstOrNull { isCopy(it, SURROUND_NAME) && normCodec(it.codec) == "eac3" }
        ?: sameLang.firstOrNull { isCopy(it, STEREO_NAME) && normCodec(it.codec) == "aac" })?.index
}

/** A track named like one of our copies (FR-314-4): `Stereo`, `Surround 5.1`, or either followed by more words. */
fun isCopyTitle(title: String?, name: String): Boolean =
    title?.trim()?.let { it.equals(name, true) || it.startsWith("$name ", true) || it.equals(name.substringBefore(' '), true) } == true

/**
 * Phase 314b (FR-314-4/-5) — which tracks are copies jellystructure added, and of which track: copy index → source index
 * (Jellyfin's numbering, external sidecar tracks included). A copy is an AAC track named *Stereo* or an E-AC-3 track named
 * *Surround 5.1*; its source is the same language's main original — the default one, else the first — never another
 * copy, a commentary or an audio description. A copy whose language has no original is not folded (it stays its own row).
 */
fun copySourcesOf(audio: List<JellyfinAudio>): Map<Int, Int> {
    fun copyKind(a: JellyfinAudio): Boolean =
        (normCodec(a.codec) == "aac" && isCopyTitle(a.title, STEREO_NAME)) || (normCodec(a.codec) == "eac3" && isCopyTitle(a.title, SURROUND_NAME))
    val copies = audio.filter { copyKind(it) }.map { it.index }.toSet()
    val out = mutableMapOf<Int, Int>()
    for (c in audio.filter { it.index in copies }) {
        val originals = audio.filter {
            it.index !in copies && langKey(it.language) == langKey(c.language) && !isCommentary(it.title) && !isDescription(it.title)
        }
        val source = originals.firstOrNull { it.isDefault } ?: originals.firstOrNull() ?: continue
        out[c.index] = source.index
    }
    return out
}

/** Kind C — of a film's media sources (id → path), the profile-8.1 version's id, for a device without dual-layer DV. */
fun dvVersionSource(sources: List<Pair<String, String>>, deviceDecodesEl: Boolean): String? {
    if (deviceDecodesEl) return null
    return sources.firstOrNull { (_, path) -> path.substringAfterLast('/').substringBeforeLast('.').endsWith(" - $DV_VERSION_LABEL") }?.first
}

/** `YYYY-MM-DD` (UTC) for [epochSec] — the `JELLYSTRUCTURE_ADDED` tag. Civil-from-days (H. Hinnant), no platform calendar. */
fun isoDateUtc(epochSec: Long): String {
    val z = epochSec.floorDiv(86_400L) + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/**
 * The sidecar's file name (FR-314-6): `<video base>.<lang>.<Stereo|Surround>.mka`, which Jellyfin 12.1 attaches to the
 * item (review item 2). An untagged source gives no language token.
 */
fun sidecarPath(videoPath: String, track: PlannedTrack): String {
    val base = videoPath.substringBeforeLast('.')
    val lang = track.language?.takeIf { langKey(it) != "und" }?.let { langKey(it) }
    val token = if (track.codec == "aac") "Stereo" else "Surround"
    return listOfNotNull(base, lang, token).joinToString(".") + ".mka"
}
