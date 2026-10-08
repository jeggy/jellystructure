package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import platform.posix.fgets
import platform.posix.lstat
import platform.posix.pclose
import platform.posix.popen
import platform.posix.stat

/**
 * Phase 315 (FR-315-4) — find what was already changed. Read-only: nothing is written to any file, and qBittorrent is
 * only read (no recheck is triggered — the owner presses *Force recheck* in qBittorrent).
 *
 * Before phase 315 the seeding guard matched paths only, so `mkvpropedit` changed hard-linked files whose other name is a
 * seeding torrent's data. A seeding torrent never re-hashes its data by itself (qBittorrent learns of a changed piece only
 * on a recheck; a peer that downloads it rejects it), so asking qBittorrent for piece state would say nothing. What can
 * be known without touching anything: which files jellystructure edited in place (its History), which of them still share
 * their bytes with a name outside the library, and which torrents those names belong to. Those torrents very likely hold
 * changed pieces; *Force recheck* re-downloads exactly those.
 */
object SeedingDamageCheck {
    /** History actions that ran `mkvpropedit` on a file in place (the routes that record them). */
    val IN_PLACE_ACTIONS = setOf("set_default", "set_forced", "set_language", "assign_language", "ep_assign_language", "bulk_reorder_tracks")

    @Serializable
    data class TorrentHit(val name: String, val state: String, val hash: String)

    @Serializable
    data class Entry(
        val mediaId: String,
        val title: String,
        val file: String,
        val editedAt: Long,
        /** Names this file has outside the library. */
        val outsideNames: Int,
        val torrents: List<TorrentHit>,
    )

    @Serializable
    data class Result(
        val ranAt: Long,
        val running: Boolean = false,
        val filesChecked: Int = 0,
        val entries: List<Entry> = emptyList(),
        val torrentCount: Int = 0,
        val error: String? = null,
    )

    private val lock = dev.jellystructure.ops.SpinLock()
    private var last: Result? = null

    fun last(): Result? = lock.withLock { last }

    private fun set(r: Result) = lock.withLock { last = r }

    /** Marks a run as started; false when one is already running. */
    fun begin(): Boolean = lock.withLock {
        if (last?.running == true) false else { last = (last ?: Result(ranAt = 0L)).copy(running = true); true }
    }

    suspend fun run(store: MediaStore, history: MediaHistory, snapshot: SeedingSnapshot, config: AppConfig): Result {
        val result = runCatching { compute(store, history, snapshot, config) }.getOrElse {
            Logger.warn("Seeding damage check failed: ${it.message}", "torrent")
            Result(nowEpochSec(), error = it.message ?: "failed")
        }
        set(result)
        Logger.info("Seeding damage check: ${result.filesChecked} edited files checked, ${result.entries.size} still shared with data outside the library, ${result.torrentCount} torrents (315)", "torrent")
        return result
    }

    private suspend fun compute(store: MediaStore, history: MediaHistory, snapshot: SeedingSnapshot, config: AppConfig): Result {
        // 1 — what was edited in place, latest edit per item.
        val editedAt = HashMap<String, Long>()
        for (h in history.recent(5_000)) if (h.action in IN_PLACE_ACTIONS) editedAt[h.mediaId] = maxOf(editedAt[h.mediaId] ?: 0L, h.timestamp)
        // 2 — their mkv files that still have a name outside the library.
        data class Cand(val mediaId: String, val title: String, val path: String, val info: LinkInfo, val at: Long)
        val cands = ArrayList<Cand>()
        var checked = 0
        for ((id, at) in editedAt) {
            val item = store.get(id) ?: continue
            val paths = when (item.kind) {
                MediaKind.TV_SHOW -> item.episodes.map { it.path }
                else -> listOf(item.path)
            }.filter { it.endsWith(".mkv", ignoreCase = true) }.distinct()
            for (p in paths) {
                checked++
                val info = linkInfo(p) ?: continue
                if (info.links <= 1L) continue
                if (snapshot.hardLinkVerdict(p, config) != LinkVerdict.OUTSIDE) continue
                cands += Cand(id, item.title, p, info, at)
            }
        }
        if (cands.isEmpty()) return Result(nowEpochSec(), filesChecked = checked)
        // 3 — every name of those inodes: one `find` per disk, filtered to the inodes asked for.
        val roots = libraryRoots(config)
        val wanted = cands.map { it.info.device to it.info.inode }.toHashSet()
        val names = HashMap<Pair<Long, Long>, MutableList<String>>()
        for (mount in cands.mapNotNull { mountRootOf(it.path) }.distinct()) {
            for ((key, path) in multiLinked(mount)) if (key in wanted) names.getOrPut(key) { ArrayList() } += path
        }
        // 4 — which torrents those outside names belong to.
        val torrents = runCatching { snapshot.get().torrents }.getOrDefault(emptyList())
        val qb = config.qbittorrent
        val entries = cands.map { c ->
            val outside = names[c.info.device to c.info.inode].orEmpty().filter { n -> roots.none { r -> n == r || n.startsWith("$r/") } }
            val hits = torrents.filter { t -> outside.any { torrentCovers(t, it, qb) } }
                .map { TorrentHit(it.name, qbDisplayState(it.state), it.hash) }.distinctBy { it.hash }
            Entry(c.mediaId, c.title, c.path.substringAfterLast('/'), c.at, outside.size.coerceAtLeast((c.info.links - 1).toInt()), hits)
        }
        return Result(nowEpochSec(), filesChecked = checked, entries = entries, torrentCount = entries.flatMap { it.torrents }.distinctBy { it.hash }.size)
    }

    /**
     * Whether torrent [t] holds [localPath]: its content path (in qBittorrent's view) mapped through `path_mappings`
     * equals or contains it, or — for a client whose view isn't mapped (the seeder sees `/media/cross-seed-links`) — the
     * content path's last component names a folder (or the file) on [localPath].
     */
    internal fun torrentCovers(t: QBTorrent, localPath: String, qb: QBittorrentConfig?): Boolean {
        val content = t.contentPath.trimEnd('/')
        if (content.isEmpty()) return false
        val mapped = if (qb != null) translateRemoteToLocal(content, qb).trimEnd('/') else content
        if (localPath == mapped || localPath.startsWith("$mapped/")) return true
        val leaf = content.substringAfterLast('/')
        if (leaf.isEmpty()) return false
        return localPath.endsWith("/$leaf") || localPath.contains("/$leaf/")
    }

    /** The top directory of [path]'s filesystem (walk up while the device stays the same). */
    internal fun mountRootOf(path: String): String? {
        val d0 = devOf(path) ?: return null
        var cur = path.substringBeforeLast('/')
        while (true) {
            val parent = cur.substringBeforeLast('/', "").ifEmpty { "/" }
            if (parent == cur || devOf(parent) != d0) return cur
            cur = parent
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun devOf(p: String): Long? = memScoped {
        val st = alloc<stat>()
        if (lstat(p, st.ptr) == 0) st.st_dev.toLong() else null
    }

    /** Every multiply-linked regular file under [mount] as ((device, inode), path), one low-priority `find`. */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun multiLinked(mount: String): List<Pair<Pair<Long, Long>, String>> = dev.jellystructure.ops.ProcessGate.withPermit {
        val q = mount.replace("'", "'\\''")
        val out = ArrayList<Pair<Pair<Long, Long>, String>>()
        memScoped {
            val pipe = popen("nice -n 19 ionice -c3 find '$q' -xdev -type f -links +1 -printf '%D %i %p\\n' 2>/dev/null", "r") ?: return@memScoped
            val buf = allocArray<ByteVar>(8192)
            while (fgets(buf, 8192, pipe) != null) {
                val line = buf.toKString().trimEnd('\n')
                val a = line.indexOf(' '); val b = line.indexOf(' ', a + 1)
                if (a <= 0 || b <= a) continue
                val dev = line.substring(0, a).toLongOrNull() ?: continue
                val ino = line.substring(a + 1, b).toLongOrNull() ?: continue
                out += (dev to ino) to line.substring(b + 1)
            }
            pclose(pipe)
        }
        out
    }
}
