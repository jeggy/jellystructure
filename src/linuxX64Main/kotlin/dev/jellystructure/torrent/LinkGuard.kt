package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.DT_DIR
import platform.posix.DT_LNK
import platform.posix.DT_REG
import platform.posix.DT_UNKNOWN
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.closedir
import platform.posix.lstat
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/**
 * Phase 315 — a hard-linked file counts as seeded.
 *
 * `SeedingSnapshot.checkPath` used to match the library path against each seeding torrent's path only. The household's
 * torrents keep their data in cross-seed's link dirs (`…/cross-seed-links`, `…/perma-seed-links`, the download folder),
 * hard-linked to the library file: one inode, two names. The path check said *Allowed*, and an in-place edit
 * (`mkvpropedit`, a tag writer) then changed the torrent's bytes too. Measured 2026-10-08: 182 of 338 film files and
 * 4 146 of 9 197 episode files have more than one link.
 *
 * The question asked here needs no torrent client and no list of link dirs: **does this file have a name outside the
 * library?** Every library root is walked once (cached), counting how many names each multiply-linked inode has inside
 * the library. A file whose link count is higher than that has a name somewhere else on the disk: a torrent's copy, a
 * cross-seed link, a backup. Changing it in place changes that copy too, so it is refused. A file whose extra links are
 * all inside the library (an owner's own hard link between two library folders) is allowed, with a note.
 *
 * A remux that writes a new file and `mv`s it over the library name is not affected: it gives the library a new inode
 * and leaves the torrent's data alone.
 */

/** One file's identity on disk. */
data class LinkInfo(val links: Long, val device: Long, val inode: Long)

/** What [linkVerdict] says about a file's other names. */
enum class LinkVerdict {
    /** One name only: nothing else shares the bytes. */
    SINGLE,
    /** More names, all inside the library. */
    LIBRARY_ONLY,
    /** At least one name outside the library: another copy (most likely a torrent's) shares the bytes. */
    OUTSIDE,
}

/** FR-315-1 — the pure rule. [insideLibrary] counts this inode's names inside the library roots (the file itself included). */
fun linkVerdict(links: Long, insideLibrary: Int): LinkVerdict = when {
    links <= 1L -> LinkVerdict.SINGLE
    // An index that missed the file itself (built before it existed) counts at least the name being checked.
    links > insideLibrary.coerceAtLeast(1) -> LinkVerdict.OUTSIDE
    else -> LinkVerdict.LIBRARY_ONLY
}

/** The local roots of every configured library (blank ones skipped), normalised without a trailing slash. */
fun libraryRoots(config: AppConfig): List<String> =
    config.libraries.mapNotNull { it.localPath.trim().trimEnd('/').takeIf { p -> p.isNotBlank() } }.distinct()

@OptIn(ExperimentalForeignApi::class)
fun linkInfo(path: String): LinkInfo? = memScoped {
    val st = alloc<stat>()
    if (lstat(path, st.ptr) != 0) return@memScoped null
    if ((st.st_mode.toInt() and S_IFMT) != S_IFREG) return@memScoped null
    LinkInfo(st.st_nlink.toLong(), st.st_dev.toLong(), st.st_ino.toLong())
}

/**
 * FR-315-1 — for every multiply-linked regular file under [roots], how many of its names lie inside them. Only inodes
 * with `st_nlink > 1` are kept (the rest can never be asked about). Symlinks are never followed.
 */
@OptIn(ExperimentalForeignApi::class)
fun buildInsideCounts(roots: List<String>): Map<Pair<Long, Long>, Int> {
    val counts = HashMap<Pair<Long, Long>, Int>()
    val seenDirs = HashSet<Pair<Long, Long>>()
    val stack = ArrayDeque(roots)
    while (stack.isNotEmpty()) {
        val dirPath = stack.removeLast()
        memScoped {
            val ds = alloc<stat>()
            if (lstat(dirPath, ds.ptr) != 0 || (ds.st_mode.toInt() and S_IFMT) != S_IFDIR) return@memScoped
            // A root nested in another root (or a bind mount seen twice) is walked once.
            if (!seenDirs.add(ds.st_dev.toLong() to ds.st_ino.toLong())) return@memScoped
            val dir = opendir(dirPath) ?: return@memScoped
            try {
                while (true) {
                    val e = readdir(dir) ?: break
                    val name = e.pointed.d_name.toKString()
                    if (name == "." || name == "..") continue
                    val child = "$dirPath/$name"
                    when (e.pointed.d_type.toInt()) {
                        DT_DIR -> stack.addLast(child)
                        DT_LNK -> Unit
                        DT_REG, DT_UNKNOWN -> {
                            val st = alloc<stat>()
                            if (lstat(child, st.ptr) != 0) continue
                            when (st.st_mode.toInt() and S_IFMT) {
                                S_IFDIR -> stack.addLast(child)
                                S_IFREG -> if (st.st_nlink.toLong() > 1L) {
                                    val k = st.st_dev.toLong() to st.st_ino.toLong()
                                    counts[k] = (counts[k] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
            } finally {
                closedir(dir)
            }
        }
    }
    return counts
}

/**
 * The cached [buildInsideCounts] over the configured library roots. Built lazily (only when a checked file has more
 * than one link) and kept for [ttlSec]; a batch edit of a whole series pays for one walk.
 */
class LibraryLinkIndex(private val ttlSec: Long = 600L, private val walk: (List<String>) -> Map<Pair<Long, Long>, Int> = ::buildInsideCounts) {
    private val mutex = Mutex()
    private var cached: Map<Pair<Long, Long>, Int>? = null
    private var cachedRoots: List<String> = emptyList()
    private var builtAt = 0L

    suspend fun insideCount(info: LinkInfo, roots: List<String>): Int = mutex.withLock {
        val now = nowEpochSec()
        val c = cached
        val map = if (c != null && roots == cachedRoots && now - builtAt < ttlSec) c else {
            val fresh = runCatching { walk(roots) }.getOrElse {
                Logger.warn("Seeding guard: library walk failed (${it.message}) — hard-linked files are refused until it works", "torrent")
                emptyMap()
            }
            cached = fresh; cachedRoots = roots; builtAt = now
            fresh
        }
        map[info.device to info.inode] ?: 0
    }

    suspend fun invalidate() = mutex.withLock { cached = null }
}

/** FR-315-3 — what a refusal says (one sentence for every caller). */
const val HARD_LINK_REFUSAL =
    "This file is shared with a torrent that is still seeding (hard link), so it can't be changed in place."

/** FR-315-3 — refusals counted for the Dashboard, by kind of file, for the last 7 days (in memory). */
object SeedingRefusals {
    private const val WINDOW_SEC = 7L * 86_400L
    private val lock = dev.jellystructure.ops.SpinLock()
    private val events = ArrayList<Pair<Long, String>>()

    fun record(path: String) {
        val kind = kindOf(path)
        lock.withLock {
            val now = nowEpochSec()
            events.removeAll { now - it.first > WINDOW_SEC }
            events += now to kind
        }
    }

    /** Kind → refusals in the last 7 days. */
    fun lastWeek(): Map<String, Int> = lock.withLock {
        val now = nowEpochSec()
        events.filter { now - it.first <= WINDOW_SEC }.groupingBy { it.second }.eachCount()
    }

    fun clearForTest() = lock.withLock { events.clear() }

    private fun kindOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "mkv", "mp4", "m4v", "avi", "ts", "m2ts", "webm", "mov" -> "video"
        "flac", "mp3", "m4a", "ogg", "opus", "wma", "wav", "aac", "alac" -> "audio"
        "m4b" -> "audiobook"
        else -> "file"
    }
}
