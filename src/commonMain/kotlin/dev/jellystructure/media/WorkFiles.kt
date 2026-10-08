package dev.jellystructure.media

/**
 * Phase 311 — the one place that names a work file: the file jellystructure writes while making a new version of a
 * library file (a remux's output, FileIntegrity's replacement).
 *
 * Radarr's and Sonarr's disk scans skip a *folder* whose name starts with a dot, but not a dot-*file*: a work file
 * beside the video (`.jstmp_<name>`) was seen mid-remux, assigned as the film's file, then dropped as missing, and the
 * film read as missing until a search replaced it (five times since June). So every work file lives in a hidden folder
 * in the same directory, `<dir>/.jellystructure/`: the same filesystem, so the final `mv` stays one atomic rename, and
 * a folder Radarr, Sonarr and Jellyfin all skip. The file keeps its real extension so ffmpeg still picks the muxer
 * from the name. Shared by the backend runners and the admin's command preview, so the preview shows what runs.
 */
object WorkFiles {
    /** The hidden work folder's name, inside the library file's own directory. */
    const val DIR = ".jellystructure"

    /** What the work file is for; its prefix keeps two kinds of work on one file apart (MediaFileLock serialises them). */
    enum class Kind(val prefix: String) { REMUX("remux_"), REPLACE("replace_") }

    /** The library file's directory. */
    fun parentOf(libraryPath: String): String = libraryPath.substringBeforeLast('/')

    /** `<dir>/.jellystructure` for [libraryPath]. */
    fun dirFor(libraryPath: String): String = "${parentOf(libraryPath)}/$DIR"

    /** `<dir>/.jellystructure/<prefix><name>`: the one work-file path for [libraryPath] and [kind]. */
    fun pathFor(libraryPath: String, kind: Kind): String =
        "${dirFor(libraryPath)}/${kind.prefix}${libraryPath.substringAfterLast('/')}"

    /** The library file a work file belongs to, or null if [workPath] is not one of ours. */
    fun libraryPathOf(workPath: String): String? {
        val dir = workPath.substringBeforeLast('/')
        if (dir.substringAfterLast('/') != DIR) return null
        val name = workPath.substringAfterLast('/')
        val kind = Kind.entries.firstOrNull { name.startsWith(it.prefix) && name.length > it.prefix.length } ?: return null
        return "${dir.substringBeforeLast('/')}/${name.removePrefix(kind.prefix)}"
    }

    /** True for a path inside a work folder, or an old-style work file beside the video (before this phase). */
    fun isWorkPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return path.substringBeforeLast('/').substringAfterLast('/') == DIR || LEGACY_PREFIXES.any { name.startsWith(it) }
    }

    /** The names work files had before this phase, beside the video. Only the sweep still looks for them. */
    val LEGACY_PREFIXES: List<String> = listOf(".jstmp_", ".jsreplace_")

    private fun q(path: String) = "'${path.replace("'", "'\\''")}'"

    /** The shell step that makes the work folder before a write: owner and mode copied from the library file's own
     *  directory where the process may set them (a non-root container simply keeps its own). */
    fun prepareCommand(libraryPath: String): String {
        val dir = q(dirFor(libraryPath)); val parent = q(parentOf(libraryPath))
        return "mkdir -p $dir && { chown --reference=$parent $dir 2>/dev/null; chmod --reference=$parent $dir 2>/dev/null; true; }"
    }

    /** Removes the work folder if it is empty (never anything in it); harmless when it is gone or holds other work. */
    fun cleanupCommand(libraryPath: String): String = "rmdir ${q(dirFor(libraryPath))} 2>/dev/null || true"
}
