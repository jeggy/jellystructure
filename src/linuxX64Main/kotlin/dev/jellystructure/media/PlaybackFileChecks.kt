package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.tv.PlaybackFileChecks
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * DEB-3652 (a-damaged-file-still-plays, DEB-3642 FR-1–FR-5) — what the playback plan reads from the stored file checks.
 * A DB read and, for the rare file whose deep check found damage, one header-only walk (a few hundred bytes), never a read of
 * the file. [items] is the library, asked only for FR-5's rescue.
 */
class StoredFileChecks(
    private val integrity: FileIntegrityService,
    private val jobs: MediaJobQueue,
    private val items: suspend () -> List<MediaItem>,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : PlaybackFileChecks {
    override fun emptyStreams(path: String): Set<Int>? =
        integrity.timeline.resultFor(path).report?.emptyStreams?.toSet()

    override fun damagedBeforeFirstCluster(path: String): Boolean {
        val damage = integrity.resultFor(path)
        if (damage.state != FileIntegrityState.DAMAGED) return false
        val pos = damagePosition(damage.firstDamage) ?: return false
        val cluster = runCatching { SystemFileSystem.source(Path(path)).buffered().use { firstClusterOffset(it) } }.getOrNull() ?: return false
        return pos < cluster
    }

    override fun hasCurrentDeepCheck(path: String): Boolean =
        integrity.resultFor(path).state != FileIntegrityState.UNCHECKED && integrity.timeline.resultFor(path).state != TimelineState.UNCHECKED

    override fun verifyFirst(path: String) {
        scope.launch {
            val item = items().firstOrNull { it.path == path || it.episodes.any { ep -> ep.path == path } }
            if (item == null) { Logger.warn("deep check first: no library item holds $path", "integrity"); return@launch }
            jobs.verifyFirst(item, path)
        }
    }

    companion object {
        private val POS = Regex("""\bpos (\d+)""")

        /** The byte position in a demuxer's damage line (`… at pos 72900 …`), or null when the line names none. */
        fun damagePosition(line: String?): Long? = line?.let { POS.find(it)?.groupValues?.get(1)?.toLongOrNull() }
    }
}
