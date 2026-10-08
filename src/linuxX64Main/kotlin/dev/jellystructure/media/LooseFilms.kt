package dev.jellystructure.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 316 (FR-316-3/-4) — films with no folder of their own.
 *
 * A film's video file loose in a library root takes the root's folder-level image names with it (its artwork, or the
 * owner's picks for it, became the whole library's images on 2026-10-08). The Dashboard lists such films and offers to
 * move each into `<root>/<Title> (<Year>)/`: a same-filesystem `rename` per file, so every hard link elsewhere (cross-seed's
 * link folders) keeps pointing at the same bytes and every torrent keeps seeding.
 *
 * This file is the model and the pure planning rules; [LooseFilmMover] runs a move, [LooseFilmsService] wires them to the
 * disk and the services.
 */

/** One file (or folder) that belongs to a loose film, and where it goes. */
@Serializable
data class LooseFile(
    /** Its name in the library root. */
    val name: String,
    /** `video` · `image` · `sidecar` (an image's `.src`/`.manual`) · `other` (NFO, subtitle, a `.trickplay` folder …) · `root_image`. */
    val role: String,
    /** Its name in the film's new folder; null when it loses to another image and is backed up instead. */
    val target: String?,
)

/** A torrent that seeds a loose film. */
@Serializable
data class LooseTorrent(
    val name: String,
    val state: String,
    val hash: String,
    /** True when the torrent's own data path *is* the library file (it needs `setLocation`); false when it seeds a hard link elsewhere. */
    @SerialName("seeds_library_path") val seedsLibraryPath: Boolean,
)

/** One step of a move, as shown on the film's row and in its History. */
@Serializable
data class LooseStep(val step: String, val ok: Boolean, val detail: String)

/** What Radarr says about one loose film, and what the move does with it (FR-316-4 step 4, dev review 5). */
object RadarrPlan {
    /** Radarr manages it and has no file, or has exactly this file: its path is updated (`moveFiles=false`). */
    const val UPDATE = "update"
    /** Radarr maps the film to a different file: left alone, shown. */
    const val OTHER_FILE = "other_file"
    const val NOT_MANAGED = "not_managed"
    const val NOT_CONFIGURED = "not_configured"
}

@Serializable
data class LooseFilm(
    /** The video file's full local path (the film's key in this list). */
    val key: String,
    val root: String,
    @SerialName("media_id") val mediaId: String? = null,
    val title: String,
    val year: Int? = null,
    @SerialName("jellyfin_id") val jellyfinId: String? = null,
    val video: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val files: List<LooseFile> = emptyList(),
    /** How many names this video file has outside the library (hard links, e.g. cross-seed's). */
    @SerialName("outside_names") val outsideNames: Int = 0,
    val torrents: List<LooseTorrent> = emptyList(),
    val radarr: String = RadarrPlan.NOT_CONFIGURED,
    @SerialName("radarr_id") val radarrId: Int? = null,
    @SerialName("radarr_note") val radarrNote: String? = null,
    /** The folder it moves to (full local path). */
    @SerialName("target_folder") val targetFolder: String,
    /** `waiting` · `moving` · `moved` · `failed` · `partial` (in its folder, but a later step didn't finish). */
    val state: String = "waiting",
    val steps: List<LooseStep> = emptyList(),
    val error: String? = null,
)

@Serializable
data class LooseFilmsResult(
    @SerialName("ran_at") val ranAt: Long,
    val running: Boolean = false,
    val applying: Boolean = false,
    val films: List<LooseFilm> = emptyList(),
    /** Folder-named images in a library root that match no loose film (left where they are). */
    @SerialName("unmatched_root_images") val unmatchedRootImages: List<String> = emptyList(),
    val error: String? = null,
)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tbn")
private val SIDECAR_SUFFIXES = listOf(".src", ".manual")

internal fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

/**
 * The folder name a loose film moves to: `<Title> (<Year>)`, with the characters a folder name can't carry replaced the
 * way Radarr's default does (`:` → ` -`), and spaces collapsed.
 */
fun looseFilmFolderName(title: String, year: Int?): String {
    val clean = title.replace(":", " -").replace(Regex("""[/\\?*"<>|]"""), "").replace(Regex("""\s+"""), " ").trim().trimEnd('.')
    return if (year != null) "$clean ($year)" else clean
}

/**
 * FR-316-4 step 2 — the pure plan of one move: every name in the root that belongs to [video] and where it goes.
 *
 * - The video keeps its name.
 * - The film's own `<basename>-poster.jpg`/`-backdrop.jpg`/`-logo.png`/`-landscape.jpg` become `poster.jpg`, `fanart.jpg`,
 *   `clearlogo.png`, `landscape.jpg` (now alone in its folder, the film takes folder names — `assetFilePath`'s rule), each
 *   with its `.src`/`.manual` sidecars.
 * - Everything else of the film (`<basename>.nfo`, subtitles, `<basename>.trickplay/`, `-thumb.jpg` …) keeps its name.
 * - [rootImages] (folder-named images in the root already matched to this film) come along under their own names.
 * - Two images wanting one name: the one with a `.manual` (the owner's own pick) wins; otherwise the film's own file wins.
 *   The loser and its sidecars get `target = null` (backed up, never deleted).
 */
fun planLooseFilm(video: String, rootEntries: Collection<String>, rootImages: Collection<String>): List<LooseFile> {
    val base = video.substringBeforeLast('.')
    val entries = rootEntries.toSet()
    val own = entries.filter { it != video && (it.startsWith("$base.") || it.startsWith("$base-")) }
    val sidecarOf = HashMap<String, String>()   // sidecar name → its image
    val images = (own.filter { isImageName(it) } + rootImages.filter { it in entries }).distinct()
    for (img in images) for (sfx in SIDECAR_SUFFIXES) if ("$img$sfx" in entries) sidecarOf["$img$sfx"] = img

    data class Cand(val image: String, val target: String, val isRoot: Boolean, val manual: Boolean)
    val cands = images.map { img ->
        val isRoot = img in rootImages
        val target = if (isRoot) img else {
            val suffix = img.removePrefix(base)
            BASENAME_TO_FOLDER_NAME.firstOrNull { suffix.equals(it.first, ignoreCase = true) }?.second ?: img
        }
        Cand(img, target, isRoot, "$img.manual" in entries)
    }
    val winners = HashMap<String, Cand>()
    for (c in cands) {
        val cur = winners[c.target]
        winners[c.target] = when {
            cur == null -> c
            c.manual && !cur.manual -> c
            cur.manual && !c.manual -> cur
            !c.isRoot && cur.isRoot -> c      // a tie: the film's own file wins
            else -> cur
        }
    }
    val out = ArrayList<LooseFile>()
    out += LooseFile(video, "video", video)
    for (c in cands) {
        val win = winners[c.target] === c
        out += LooseFile(c.image, if (c.isRoot) "root_image" else "image", if (win) c.target else null)
        for (sfx in SIDECAR_SUFFIXES) if ("${c.image}$sfx" in entries) out += LooseFile("${c.image}$sfx", "sidecar", if (win) "${c.target}$sfx" else null)
    }
    for (n in own) if (!isImageName(n) && n !in sidecarOf) out += LooseFile(n, "other", n)
    return out
}

/**
 * FR-316-3 (dev review 3) — does folder-named root image [image] belong to a film? Its `.src` equals one of the film's
 * recorded sources, or its bytes equal one of the film's own images ([sameBytesAsOwn]).
 */
fun rootImageMatches(srcValue: String?, filmSources: Collection<String?>, sameBytesAsOwn: Boolean): Boolean =
    (srcValue != null && srcValue.isNotBlank() && filmSources.any { it != null && it == srcValue.trim() }) || sameBytesAsOwn

/** FR-316-4 step 4 / dev review 5 — what to do with Radarr for one film. */
fun radarrPlan(configured: Boolean, movieFound: Boolean, radarrFilePath: String?, radarrViewOfVideo: String): String = when {
    !configured -> RadarrPlan.NOT_CONFIGURED
    !movieFound -> RadarrPlan.NOT_MANAGED
    radarrFilePath == null || radarrFilePath == radarrViewOfVideo -> RadarrPlan.UPDATE
    else -> RadarrPlan.OTHER_FILE
}
