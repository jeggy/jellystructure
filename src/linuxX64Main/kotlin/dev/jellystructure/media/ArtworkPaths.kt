package dev.jellystructure.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

/**
 * Phase 316 — a loose film keeps its artwork to itself.
 *
 * A film whose video file sits loose in a library root must never get **folder-named** artwork (`poster.jpg`, `fanart.jpg`, `clearlogo.png`): in a library root Jellyfin
 * reads those as the whole library's own images, and every film without its own logo inherited one film's logo
 * (2026-10-08). Such a film gets Jellyfin's per-file names instead, the ones Jellyfin itself already wrote for these files:
 * `<basename>-poster.jpg`, `<basename>-backdrop.jpg`, `<basename>-logo.png`, `<basename>-landscape.jpg`.
 */

/** FR-316-1 — the library roots `assetFilePath` checks against; set from the config at startup and on every save. */
object LibraryRootsRegistry {
    @kotlin.concurrent.Volatile
    var roots: List<String> = emptyList()
        set(value) { field = value.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct() }
}

/** Video file extensions the finder looks for (FR-316-3). */
val LOOSE_VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mpg", "mpeg", "webm", "iso")

fun isVideoFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in LOOSE_VIDEO_EXTENSIONS

/** Folder-level image names (any extension) Jellyfin reads as *the folder's own* image. */
private val FOLDER_IMAGE_STEMS = setOf("poster", "folder", "cover", "default", "fanart", "backdrop", "background", "art",
    "clearlogo", "logo", "landscape", "thumb", "banner", "clearart", "disc", "discart")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tbn")

/** True for a folder-level image name such as `poster.jpg` or `clearlogo.png` (never a `<basename>-poster.jpg`). */
fun isFolderNamedImage(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    val stem = name.substringBeforeLast('.').lowercase()
    return ext in IMAGE_EXTENSIONS && stem in FOLDER_IMAGE_STEMS
}

/** FR-316-1 — Jellyfin's per-file suffix for one of our folder-level artwork names. */
fun basenameSuffix(filename: String): String = when (filename) {
    "poster.jpg" -> "-poster.jpg"
    "fanart.jpg" -> "-backdrop.jpg"
    "clearlogo.png" -> "-logo.png"
    "landscape.jpg" -> "-landscape.jpg"
    else -> "-$filename"
}

/** The inverse of [basenameSuffix] for the move into a folder of its own (FR-316-4): suffix → folder name. */
val BASENAME_TO_FOLDER_NAME: List<Pair<String, String>> = listOf(
    "-poster.jpg" to "poster.jpg",
    "-backdrop.jpg" to "fanart.jpg",
    "-fanart.jpg" to "fanart.jpg",
    "-logo.png" to "clearlogo.png",
    "-clearlogo.png" to "clearlogo.png",
    "-landscape.jpg" to "landscape.jpg",
)

/** Every entry name in [dir] (files and folders, no `.`/`..`). Empty when the folder can't be read. */
@OptIn(ExperimentalForeignApi::class)
fun listNames(dir: String): List<String> {
    val d = opendir(dir) ?: return emptyList()
    val out = ArrayList<String>()
    try {
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (n != "." && n != "..") out += n
        }
    } finally {
        closedir(d)
    }
    return out
}

/**
 * FR-316-1 — is [videoPath] loose in a library root? Only a library root counts: checked against the real films library
 * (2026-10-08), every film folder holding more than one video holds one title (macOS `._` copies, two versions of one
 * film, a sample), and a rule judging folder contents would have moved their `poster.jpg` to per-file names.
 */
fun inSharedFolder(videoPath: String, roots: List<String> = LibraryRootsRegistry.roots): Boolean {
    val dir = videoPath.substringBeforeLast('/').trimEnd('/')
    return roots.any { it.trimEnd('/') == dir }
}

/**
 * FR-316-2 — a folder-named image may never be written into a library root, whatever wrote it. Null when [destPath] is
 * fine, else the reason (the writer logs it and writes nothing).
 */
fun refuseRootFolderImage(destPath: String, roots: List<String> = LibraryRootsRegistry.roots): String? {
    val dir = destPath.substringBeforeLast('/').trimEnd('/')
    val name = destPath.substringAfterLast('/').removeSuffix(".tmp")
    if (!isFolderNamedImage(name)) return null
    return if (roots.any { it.trimEnd('/') == dir }) "refused: $name would become the whole library's image in $dir (316)" else null
}
