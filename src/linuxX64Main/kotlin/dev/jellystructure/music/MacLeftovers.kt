package dev.jellystructure.music

import dev.jellystructure.log.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.DT_DIR
import platform.posix.DT_REG
import platform.posix.DT_UNKNOWN
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rmdir

/**
 * Phase 284 (FR-284-13) — macOS leftovers are removed by every scan: `._*` resource forks and `.DS_Store` under a
 * music or audiobooks library (and an empty `__MACOSX` folder they leave behind). Only those names, nothing else, and
 * never inside a folder [skip] says is seeding. Returns how many files went, for the run's Activity line.
 */
@OptIn(ExperimentalForeignApi::class)
object MacLeftovers {
    suspend fun sweep(roots: Collection<String>, skip: (String) -> Boolean = { false }): Int {
        var removed = 0
        for (root in roots.map { it.trimEnd('/') }.filter { it.isNotBlank() }) {
            if (!SystemFileSystem.exists(Path(root))) continue
            removed += walk(root, skip, depth = 0)
        }
        if (removed > 0) Logger.info("macOS leftovers: removed $removed file(s)", "music")
        return removed
    }

    private fun walk(dir: String, skip: (String) -> Boolean, depth: Int): Int {
        if (depth > 12 || skip(dir)) return 0
        val d = opendir(dir) ?: return 0
        var removed = 0
        val subdirs = ArrayList<String>()
        try {
            while (true) {
                val e = readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val path = "$dir/$name"
                val type = e.pointed.d_type.toInt()
                val isDir = type == DT_DIR || (type == DT_UNKNOWN && SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true)
                if (isDir) { subdirs += path; continue }
                if (type == DT_REG || type == DT_UNKNOWN) {
                    if (name.startsWith("._") || name == ".DS_Store") {
                        if (!skip(path) && remove(path) == 0) removed++
                    }
                }
            }
        } finally { closedir(d) }
        for (sub in subdirs) {
            removed += walk(sub, skip, depth + 1)
            if (sub.substringAfterLast('/') == "__MACOSX") rmdir(sub)   // only when empty — rmdir refuses otherwise
        }
        return removed
    }
}
