package dev.jellystructure.watcher

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")
private const val POLL_INTERVAL_MS = 30_000L
private const val DEBOUNCE_MS = 15_000L

class FolderWatcher(
    private val configStore: ConfigStore,
    private val onNewFilesDetected: suspend () -> Unit,
) {
    private val knownPaths = mutableSetOf<String>()

    suspend fun start() {
        Logger.info("FolderWatcher starting")
        initKnownFiles()

        while (true) {
            delay(POLL_INTERVAL_MS)

            if (!configStore.current.behavior.watchEnabled) continue

            val newFiles = detectNewFiles()
            if (newFiles.isEmpty()) continue

            Logger.info("FolderWatcher: ${newFiles.size} new file(s) detected — debouncing for ${DEBOUNCE_MS}ms")
            delay(DEBOUNCE_MS)

            // Only process files that are still present and stable (non-zero size)
            val stable = newFiles.filter { isStable(it) }
            if (stable.isNotEmpty()) {
                Logger.info("FolderWatcher: ${stable.size} stable new file(s) — triggering scan")
                stable.forEach { Logger.infoSync("  $it") }
                runCatching { onNewFilesDetected() }
                    .onFailure { Logger.warnSync("FolderWatcher: scan trigger failed: ${it.message}") }
            } else {
                Logger.info("FolderWatcher: no stable new files after debounce (still copying?)")
            }
        }
    }

    private fun initKnownFiles() {
        knownPaths.clear()
        collectAll(knownPaths)
        Logger.infoSync("FolderWatcher: tracking ${knownPaths.size} existing video file(s)")
    }

    private fun detectNewFiles(): List<String> {
        val current = mutableSetOf<String>()
        collectAll(current)
        val new = current - knownPaths
        // Update known set — add current, remove stale
        knownPaths.clear()
        knownPaths.addAll(current)
        return new.toList().sorted()
    }

    private fun collectAll(result: MutableSet<String>) {
        val libs = configStore.current.libraries.filter { !it.skip && it.localPath.isNotBlank() }
        for (lib in libs) {
            collectVideoFiles(lib.localPath, result)
        }
    }

    private fun collectVideoFiles(dir: String, result: MutableSet<String>) {
        val path = Path(dir)
        if (!SystemFileSystem.exists(path)) return
        val meta = SystemFileSystem.metadataOrNull(path) ?: return
        if (!meta.isDirectory) return
        runCatching {
            for (entry in SystemFileSystem.list(path)) {
                val entryMeta = SystemFileSystem.metadataOrNull(entry) ?: continue
                val entryStr = entry.toString()
                when {
                    entryMeta.isDirectory -> collectVideoFiles(entryStr, result)
                    entryMeta.isRegularFile && isVideoFile(entryStr) -> result.add(entryStr)
                }
            }
        }.onFailure { Logger.warnSync("FolderWatcher: error reading $dir: ${it.message}") }
    }

    private fun isVideoFile(path: String) =
        path.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS

    // Stable = file still exists and has non-zero size
    private fun isStable(path: String): Boolean {
        val meta = SystemFileSystem.metadataOrNull(Path(path)) ?: return false
        return meta.isRegularFile
    }
}
