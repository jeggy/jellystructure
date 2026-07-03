package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.config.TrackerEntry
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import platform.posix.closedir
import platform.posix.localtime
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.time_tVar

// ---------- API DTOs ----------

@Serializable
data class TorrentCoversDto(
    val all: Boolean = false,
    val s: Int? = null,
    val e: Int? = null,
)

@Serializable
data class ArrRefDto(val app: String, val indexer: String)

@Serializable
data class GuardStatusDto(val configured: Boolean, val reachable: Boolean)

@Serializable
data class SeasonInfoDto(val n: Int, val episodes: Int)

@Serializable
data class TorrentRef(
    val hash: String,
    val name: String,
    val announce: List<String>,
    val state: String,
    val ratio: Double,
    val seeders: Int,
    val leechers: Int,
    val uploaded: String,
    val added: String,
    @SerialName("seedTime") val seedTime: String,
    val scope: String,
    val covers: TorrentCoversDto? = null,
    val arr: ArrRefDto? = null,
    val xseed: String? = null,
    val xseedNote: String? = null,
    val error: String? = null,
)

@Serializable
data class SeedingReport(
    val guard: GuardStatusDto,
    val torrents: List<TorrentRef>,
    @SerialName("takenAt") val takenAt: Long,
    val ttl: Long,
    val reachable: Boolean,
    val seasons: List<SeasonInfoDto> = emptyList(),
)

// ---------- raw snapshot ----------

data class RawSnapshot(
    val torrents: List<QBTorrent>,
    val takenAt: Long,
    val reachable: Boolean,
    val unreachableReason: String?,
)

private val ACTIVE_STATES = setOf("uploading", "stalledUP", "forcedUP", "queuedUP")
private val ERROR_STATES = setOf("error", "missingFiles", "unknown")
private val GUARD_SEEDING_STATES = setOf("uploading", "stalledUP", "forcedUP", "queuedUP", "pausedUP")

fun qbDisplayState(state: String) = when (state) {
    in ACTIVE_STATES -> "seeding"
    in ERROR_STATES -> "errored"
    else -> "paused"
}

// ---------- SeedingSnapshot ----------

class SeedingSnapshot(
    private val configStore: ConfigStore,
    private val qbClient: QBittorrentClient,
) {
    private val mutex = Mutex()
    private var cached: RawSnapshot? = null

    suspend fun get(forceRefresh: Boolean = false): RawSnapshot = mutex.withLock {
        val snap = cached
        val ttl = configStore.current.qbittorrent?.seedingCacheTtl ?: 600L
        if (!forceRefresh && snap != null && (nowEpochSec() - snap.takenAt) < ttl) {
            return@withLock snap
        }
        val fresh = buildSnapshot(configStore.current)
        cached = fresh
        fresh
    }

    suspend fun checkPath(localFilePath: String, config: AppConfig): SeedingCheckResult {
        val qbConfig = config.qbittorrent ?: return SeedingCheckResult.Unconfigured
        if (!qbConfig.enabled) return SeedingCheckResult.Unconfigured
        val remotePath = translateLocalToRemote(localFilePath, qbConfig)
        val snap = get()
        if (!snap.reachable && snap.unreachableReason != null) {
            return SeedingCheckResult.Unreachable(snap.unreachableReason)
        }
        for (t in snap.torrents) {
            val norm = t.contentPath.trimEnd('/')
            if ((remotePath == norm || remotePath.startsWith("$norm/")) && t.state in GUARD_SEEDING_STATES) {
                return SeedingCheckResult.Blocked(t.name)
            }
        }
        return SeedingCheckResult.Allowed
    }

    suspend fun reportForItem(item: MediaItem, config: AppConfig): SeedingReport {
        val qbConfig = config.qbittorrent
        val snap = get()
        val configured = qbConfig != null && qbConfig.enabled
        val guard = GuardStatusDto(configured, snap.reachable)
        val ttl = qbConfig?.seedingCacheTtl ?: 600L
        if (!configured) return SeedingReport(guard, emptyList(), snap.takenAt, ttl, snap.reachable)

        val matching = snap.torrents.filter { coversItem(it, item, qbConfig) }
        val crossSeedMap = buildCrossSeedMap(matching, qbConfig)
        val refs = matching.map { buildRef(it, item, qbConfig, config.trackers, crossSeedMap) }

        val seasons = if (item.kind == MediaKind.TV_SHOW) {
            item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted().map { sn ->
                SeasonInfoDto(sn, item.episodes.count { it.seasonNumber == sn })
            }
        } else emptyList()

        return SeedingReport(guard, refs, snap.takenAt, ttl, snap.reachable, seasons)
    }

    suspend fun seededItemIds(trackerFilter: String, allItems: List<MediaItem>, config: AppConfig): Set<String> {
        val qbConfig = config.qbittorrent ?: return emptySet()
        if (!qbConfig.enabled) return emptySet()
        val snap = get()
        val result = mutableSetOf<String>()
        for (t in snap.torrents) {
            val announce = listOfNotNull(t.tracker.takeIf { it.isNotBlank() })
            val resolved = TrackerResolver.resolve(announce, config.trackers)
            if (trackerFilter != "any" && resolved.name != trackerFilter) continue
            val localPath = translateRemoteToLocal(t.contentPath, qbConfig)
            for (item in allItems) {
                if (coversItem(t, item, qbConfig, localPath)) result.add(item.id)
            }
        }
        return result
    }

    // ---------- private ----------

    private suspend fun buildSnapshot(config: AppConfig): RawSnapshot {
        val qbConfig = config.qbittorrent
        if (qbConfig == null || !qbConfig.enabled) {
            return RawSnapshot(emptyList(), nowEpochSec(), false, null)
        }
        return runCatching {
            val sid = qbClient.login(qbConfig)
            val torrents = qbClient.getTorrents(qbConfig, sid)
            RawSnapshot(torrents, nowEpochSec(), true, null)
        }.getOrElse { e ->
            Logger.warn("SeedingSnapshot: unreachable — ${e.message}")
            RawSnapshot(emptyList(), nowEpochSec(), false, e.message)
        }
    }

    private fun coversItem(t: QBTorrent, item: MediaItem, qbConfig: QBittorrentConfig, localPath: String? = null): Boolean {
        val norm = (localPath ?: translateRemoteToLocal(t.contentPath, qbConfig)).trimEnd('/')
        // Primary: path-prefix matching (works when qBittorrent saves directly into the media tree)
        val directMatch = when (item.kind) {
            MediaKind.MOVIE -> item.path == norm || item.path.startsWith("$norm/") || norm.startsWith(item.path.substringBeforeLast("/"))
            MediaKind.TV_SHOW ->
                item.episodes.any { ep -> ep.path == norm || ep.path.startsWith("$norm/") } ||
                norm.startsWith(item.path.trimEnd('/'))
        }
        if (directMatch) return true
        // Fallback: filename-based matching for hard-link / cross-seed setups where the torrent's
        // save path differs from the media library path. List files in the content directory and
        // compare basenames against the item's known file names.
        return filenameMatch(norm, item)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun filenameMatch(contentPath: String, item: MediaItem): Boolean {
        val targetNames: Set<String> = when (item.kind) {
            MediaKind.MOVIE -> setOf(item.path.substringAfterLast("/"))
            MediaKind.TV_SHOW -> item.episodes.mapTo(mutableSetOf()) { it.path.substringAfterLast("/") }
        }
        if (targetNames.isEmpty() || targetNames.all { it.isBlank() }) return false
        // Check contentPath as a directory whose files are hard-linked to the media tree
        val dir = opendir(contentPath) ?: run {
            // contentPath is a single file — compare its basename directly
            return contentPath.substringAfterLast("/") in targetNames
        }
        return try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != ".." && name in targetNames) return true
            }
            false
        } finally {
            closedir(dir)
        }
    }

    // Returns the set of file/dir names in path (empty if path can't be opened as directory).
    @OptIn(ExperimentalForeignApi::class)
    private fun filenameSetInDir(path: String): Set<String> {
        val dir = opendir(path) ?: return emptySet()
        return buildSet {
            try {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") add(name)
                }
            } finally {
                closedir(dir)
            }
        }
    }

    private fun buildCrossSeedMap(torrents: List<QBTorrent>, qbConfig: QBittorrentConfig): Map<String, String> {
        val byPath = torrents.groupBy { translateRemoteToLocal(it.contentPath, qbConfig).trimEnd('/') }
        val map = mutableMapOf<String, String>()
        var idx = 0
        byPath.forEach { (_, group) ->
            if (group.size > 1) {
                val key = "g${++idx}"
                group.forEach { map[it.hash] = key }
            }
        }
        return map
    }

    private fun buildRef(
        t: QBTorrent,
        item: MediaItem,
        qbConfig: QBittorrentConfig,
        trackers: List<TrackerEntry>,
        xseedMap: Map<String, String>,
    ): TorrentRef {
        val localPath = translateRemoteToLocal(t.contentPath, qbConfig).trimEnd('/')
        val (scope, covers) = computeScopeCovers(t, item, localPath)
        val state = qbDisplayState(t.state)
        val xseedGroup = xseedMap[t.hash]
        return TorrentRef(
            hash = t.hash,
            name = t.name,
            announce = listOfNotNull(t.tracker.takeIf { it.isNotBlank() }),
            state = state,
            ratio = t.ratio,
            seeders = t.numSeeds,
            leechers = t.numLeechs,
            uploaded = humanBytes(t.uploaded),
            added = formatEpochDate(t.addedOn),
            seedTime = formatSeedDuration(t.seedingTime),
            scope = scope,
            covers = covers,
            arr = null,
            xseed = xseedGroup,
            xseedNote = if (xseedGroup != null) "cross-seeded" else null,
            error = if (state == "errored") "tracker error" else null,
        )
    }

    private fun computeScopeCovers(t: QBTorrent, item: MediaItem, localPath: String): Pair<String, TorrentCoversDto?> {
        if (item.kind == MediaKind.MOVIE) return "movie" to null
        // 1. Path-based episode matching (works when content is saved into the media tree directly).
        var coveredEps = item.episodes.filter { ep -> ep.path == localPath || ep.path.startsWith("$localPath/") }
        // 2. Filename-based fallback for cross-seed / hard-link setups where the torrent save path
        //    differs from the media library path (e.g. /mnt/cross-seed/IPT/Show.S01/ → /mnt/media/Show/).
        if (coveredEps.isEmpty()) {
            val dirFiles = filenameSetInDir(localPath)
            if (dirFiles.isNotEmpty()) {
                coveredEps = item.episodes.filter { ep -> ep.path.substringAfterLast("/") in dirFiles }
            }
        }
        val allSeasons = item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
        val coveredSeasons = coveredEps.mapNotNull { it.seasonNumber }.distinct().sorted()
        return when {
            coveredSeasons.isNotEmpty() && coveredSeasons == allSeasons ->
                "complete" to TorrentCoversDto(all = true)
            coveredSeasons.size == 1 && coveredEps.size > 1 ->
                "season" to TorrentCoversDto(s = coveredSeasons.first())
            coveredEps.size == 1 -> {
                val ep = coveredEps.first()
                "episode" to TorrentCoversDto(s = ep.seasonNumber, e = ep.episodeNumber)
            }
            coveredSeasons.size > 1 ->
                // Multi-season partial pack — report first covered season as a reasonable label.
                "season" to TorrentCoversDto(s = coveredSeasons.firstOrNull())
            else -> {
                // No episode match via path or filename — last resort: parse SXX from torrent name.
                val sn = SEASON_PACK_RE.find(t.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (sn != null) "season" to TorrentCoversDto(s = sn)
                else "complete" to TorrentCoversDto(all = true)
            }
        }
    }

    companion object {
        // Matches season-pack names: S01, S1, etc. — not followed by E (episode marker).
        private val SEASON_PACK_RE = Regex("""[Ss]0*(\d{1,2})(?![Ee\d])""")
    }
}

// ---------- path mapping ----------

fun translateLocalToRemote(localPath: String, cfg: QBittorrentConfig): String {
    val best = cfg.pathMappings.filter { localPath.startsWith(it.local) }.maxByOrNull { it.local.length }
    return if (best != null) localPath.replaceFirst(best.local, best.remote) else localPath
}

fun translateRemoteToLocal(remotePath: String, cfg: QBittorrentConfig): String {
    val best = cfg.pathMappings.filter { remotePath.startsWith(it.remote) }.maxByOrNull { it.remote.length }
    return if (best != null) remotePath.replaceFirst(best.remote, best.local) else remotePath
}

// ---------- formatting ----------

@OptIn(ExperimentalForeignApi::class)
fun formatEpochDate(epochSec: Long): String = runCatching {
    memScoped {
        val t = alloc<time_tVar>().apply { value = epochSec.convert() }
        val tm = localtime(t.ptr)?.pointed ?: return@runCatching "$epochSec"
        val y = tm.tm_year + 1900
        val m = (tm.tm_mon + 1).toString().padStart(2, '0')
        val d = tm.tm_mday.toString().padStart(2, '0')
        "$y-$m-$d"
    }
}.getOrElse { "$epochSec" }

fun formatSeedDuration(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    seconds < 2592000 -> "${seconds / 86400}d"
    else -> "${seconds / 2592000}mo"
}

fun humanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1000.0 && i < units.size - 1) { v /= 1000.0; i++ }
    val s = if (v >= 10.0) v.toLong().toString() else {
        val t = (v * 10.0).toLong()
        "${t / 10}.${t % 10}"
    }
    return "$s ${units[i]}"
}
