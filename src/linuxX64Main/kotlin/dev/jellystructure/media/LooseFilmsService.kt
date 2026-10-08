package dev.jellystructure.media

import dev.jellystructure.arr.ArrClient
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingDamageCheck
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.torrent.libraryRoots
import dev.jellystructure.torrent.linkInfo
import dev.jellystructure.torrent.qbDisplayState
import dev.jellystructure.torrent.translateLocalToRemote
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import kotlinx.cinterop.toKString

/**
 * Phase 316 (FR-316-3/-4) — finds films loose in a library root, describes each (files, size, torrents, Radarr, target
 * folder) and moves the ones the owner picks. Read-only until [apply]; nothing moves without the owner's press.
 */
class LooseFilmsService(
    private val configStore: ConfigStore,
    private val store: MediaStore,
    private val jellyfin: JellyfinClient,
    private val arr: ArrClient,
    private val qb: QBittorrentClient,
    private val snapshot: SeedingSnapshot,
    private val history: MediaHistory,
    private val db: JellystructureDb,
) {
    private val lock = dev.jellystructure.ops.SpinLock()
    private var last: LooseFilmsResult? = null
    private var quick: Pair<Long, Int>? = null

    init { current = this }

    fun last(): LooseFilmsResult? = lock.withLock { last }

    private fun set(r: LooseFilmsResult) = lock.withLock { last = r }

    /** Marks a scan as started; false when a scan or a move is already running. */
    fun beginScan(): Boolean = lock.withLock {
        val l = last
        if (l?.running == true || l?.applying == true) false else { last = (l ?: LooseFilmsResult(0L)).copy(running = true, error = null); true }
    }

    /** Marks a move as started; false when one is running. */
    fun beginApply(): Boolean = lock.withLock {
        val l = last ?: return@withLock false
        if (l.running || l.applying) false else { last = l.copy(applying = true); true }
    }

    /** The movie libraries' local roots. */
    private fun movieRoots(cfg: AppConfig): List<String> =
        cfg.libraries.filter { !it.skip && it.collectionType.equals("movies", ignoreCase = true) && it.localPath.isNotBlank() }
            .map { it.localPath.trimEnd('/') }.distinct()

    /** FR-316-3 — the Dashboard's cheap count: video files directly in a movie library root, cached 2 minutes. */
    fun quickCount(): Int {
        val now = nowEpochSec()
        lock.withLock { quick?.takeIf { now - it.first < 120 }?.let { return it.second } }
        val n = movieRoots(configStore.current).sumOf { root -> listNames(root).count { !it.startsWith(".") && isVideoFile(it) && isRegularFile("$root/$it") } }
        lock.withLock { quick = now to n }
        return n
    }

    // ── FR-316-3 — the overview ─────────────────────────────────────────────────────────────────────────────

    suspend fun scan(): LooseFilmsResult {
        val result = runCatching { compute() }.getOrElse {
            Logger.warn("Loose films: scan failed: ${it.message}", "media")
            LooseFilmsResult(nowEpochSec(), error = it.message ?: "failed")
        }
        set(result)
        lock.withLock { quick = nowEpochSec() to result.films.count { it.state != "moved" } }
        Logger.info("Loose films: ${result.films.size} found, ${result.unmatchedRootImages.size} unmatched root images (316)", "media")
        return result
    }

    private suspend fun compute(): LooseFilmsResult {
        val cfg = configStore.current
        val items = store.allItems().filter { it.kind == MediaKind.MOVIE }.associateBy { it.path }
        val films = ArrayList<LooseFilm>()
        val unmatched = ArrayList<String>()
        for (root in movieRoots(cfg)) {
            val names = listNames(root).filter { !it.startsWith(".") }
            val videos = names.filter { isVideoFile(it) && isRegularFile("$root/$it") }
            if (videos.isEmpty()) continue
            val rootImages = names.filter { isFolderNamedImage(it) }
            // dev review 3 — each root image to the one film it belongs to (by `.src`, or the same bytes as the film's own).
            val assigned = HashMap<String, MutableList<String>>()
            for (img in rootImages) {
                val src = runCatching { FileIo.readText(Path("$root/$img.src")).trim() }.getOrNull()
                val owners = videos.filter { v ->
                    val item = items["$root/$v"]
                    val base = v.substringBeforeLast('.')
                    val ownImages = names.filter { it.startsWith("$base-") && isImageName(it) }
                    rootImageMatches(src, listOf(item?.posterPath, item?.backdropPath), ownImages.any { sameBytes("$root/$img", "$root/$it") })
                }
                if (owners.size == 1) assigned.getOrPut(owners[0]) { ArrayList() } += img else unmatched += "$root/$img"
            }
            for (v in videos) {
                val path = "$root/$v"
                val item = items[path]
                val (title, year) = titleAndYear(item, v)
                films += LooseFilm(
                    key = path, root = root, mediaId = item?.id, title = title, year = year, jellyfinId = item?.jellyfinId,
                    video = v, sizeBytes = SystemFileSystem.metadataOrNull(Path(path))?.size ?: 0L,
                    files = planLooseFilm(v, names, assigned[v].orEmpty()),
                    targetFolder = "$root/${looseFilmFolderName(title, year)}",
                )
            }
        }
        val withTorrents = attachTorrents(films, cfg)
        return LooseFilmsResult(nowEpochSec(), films = attachRadarr(withTorrents, cfg, items), unmatchedRootImages = unmatched)
    }

    private fun titleAndYear(item: MediaItem?, video: String): Pair<String, Int?> {
        if (item != null && item.title.isNotBlank()) return item.title to item.year
        val base = video.substringBeforeLast('.')
        val m = Regex("""^(.*?)[ ._(\[-]+((?:19|20)\d\d)(?:\b|[ ._)\]-])""").find(base)
        return if (m != null) m.groupValues[1].replace('.', ' ').replace('_', ' ').trim() to m.groupValues[2].toInt() else base to null
    }

    /** FR-316-3 — the torrents that seed each film: through a hard link outside the library, or the library path itself. */
    private suspend fun attachTorrents(films: List<LooseFilm>, cfg: AppConfig): List<LooseFilm> {
        val torrents = runCatching { snapshot.get().torrents }.getOrDefault(emptyList())
        val qbCfg = cfg.qbittorrent
        val roots = libraryRoots(cfg)
        val infos = films.associate { it.key to linkInfo(it.key) }
        val wanted = infos.values.filterNotNull().filter { it.links > 1L }.map { it.device to it.inode }.toHashSet()
        val names = HashMap<Pair<Long, Long>, MutableList<String>>()
        if (wanted.isNotEmpty()) {
            for (mount in films.filter { (infos[it.key]?.links ?: 0L) > 1L }.mapNotNull { SeedingDamageCheck.mountRootOf(it.key) }.distinct()) {
                for ((key, p) in SeedingDamageCheck.multiLinked(mount)) if (key in wanted) names.getOrPut(key) { ArrayList() } += p
            }
        }
        return films.map { f ->
            val info = infos[f.key]
            val outside = info?.let { names[it.device to it.inode] }.orEmpty().filter { n -> roots.none { r -> n == r || n.startsWith("$r/") } }
            val viaLinks = torrents.filter { t -> outside.any { SeedingDamageCheck.torrentCovers(t, it, qbCfg) } }
                .map { LooseTorrent(it.name, qbDisplayState(it.state), it.hash, seedsLibraryPath = false) }
            val direct = torrents.filter { t -> SeedingDamageCheck.torrentCovers(t, f.key, qbCfg) && viaLinks.none { it.hash == t.hash } }
                .map { LooseTorrent(it.name, qbDisplayState(it.state), it.hash, seedsLibraryPath = true) }
            f.copy(outsideNames = outside.size.coerceAtLeast(((info?.links ?: 1L) - 1L).toInt()), torrents = (viaLinks + direct).distinctBy { it.hash })
        }
    }

    /** FR-316-4 step 4 / dev review 5 — what Radarr says about each film. */
    private suspend fun attachRadarr(films: List<LooseFilm>, cfg: AppConfig, items: Map<String, MediaItem>): List<LooseFilm> {
        val r = cfg.radarr
        if (r == null || !r.enabled || r.url.isBlank()) return films.map { it.copy(radarr = RadarrPlan.NOT_CONFIGURED) }
        return films.map { f ->
            val tmdb = items[f.key]?.tmdbId
            val movie = tmdb?.let { arr.radarrMovie(r.url, r.apiKey, it) }
            val plan = radarrPlan(true, movie != null, movie?.filePath, toJellyfinView(f.key, cfg))
            val note = when (plan) {
                RadarrPlan.OTHER_FILE -> "Radarr maps this film to another file (${movie?.filePath?.substringAfterLast('/')}) — left alone"
                RadarrPlan.UPDATE -> if (movie?.filePath == null) "Radarr has no file for it; its path will point at the new folder" else "Radarr's path will point at the new folder"
                RadarrPlan.NOT_MANAGED -> "Not in Radarr"
                else -> null
            }
            f.copy(radarr = plan, radarrId = movie?.id, radarrNote = note)
        }
    }

    // ── FR-316-4 — the owner's press ────────────────────────────────────────────────────────────────────────

    suspend fun apply(keys: Set<String>) {
        val stamp = nowEpochSec()
        val mover = LooseFilmMover(Ports(configStore.current, "${configDir()}/backups/loose-films/$stamp"))
        try {
            val films = last()?.films.orEmpty()
            for (f in films) {
                if (f.key !in keys || f.state == "moved") continue
                updateFilm(f.key) { it.copy(state = "moving", error = null, steps = emptyList()) }
                val moved = runCatching { mover.move(f) }.getOrElse { e -> f.copy(state = "failed", error = e.message ?: "failed") }
                updateFilm(f.key) { moved }
                Logger.info("Loose films: ${f.title} → ${moved.state}${moved.error?.let { " ($it)" } ?: ""} (316)", "media")
            }
        } finally {
            lock.withLock { last = last?.copy(applying = false); quick = null }
        }
    }

    private fun updateFilm(key: String, f: (LooseFilm) -> LooseFilm) = lock.withLock {
        val l = last ?: return@withLock
        last = l.copy(films = l.films.map { if (it.key == key) f(it) else it })
    }

    /** Local path → Jellyfin's (and here Radarr's) view, by the library mapping whose local root holds it. */
    private fun toJellyfinView(local: String, cfg: AppConfig): String {
        val lib = cfg.libraries.filter { it.localPath.isNotBlank() && it.jellyfinPath.isNotBlank() }
            .firstOrNull { local == it.localPath.trimEnd('/') || local.startsWith(it.localPath.trimEnd('/') + "/") } ?: return local
        return lib.jellyfinPath.trimEnd('/') + local.removePrefix(lib.localPath.trimEnd('/'))
    }

    private fun fromJellyfinView(jf: String, cfg: AppConfig): String {
        val lib = cfg.libraries.filter { it.localPath.isNotBlank() && it.jellyfinPath.isNotBlank() }
            .firstOrNull { jf == it.jellyfinPath.trimEnd('/') || jf.startsWith(it.jellyfinPath.trimEnd('/') + "/") } ?: return jf
        return lib.localPath.trimEnd('/') + jf.removePrefix(lib.jellyfinPath.trimEnd('/'))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun configDir(): String = getenv("CONFIG_FILE")?.toKString()?.substringBeforeLast('/', "config")?.ifBlank { "config" } ?: "config"

    /** Production [LooseFilmPorts]. */
    private inner class Ports(private val cfg: AppConfig, private val backupDir: String) : LooseFilmPorts {
        private val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        private val token = cfg.apiKeys.jellyfinToken

        override suspend fun isPlaying(film: LooseFilm): Boolean {
            val id = film.jellyfinId ?: return false
            val body = jellyfin.getSessionsBody(base, token) ?: return true   // unknown ⇒ treat as playing (never move under a viewer)
            return runCatching {
                (Json.parseToJsonElement(body) as JsonArray).any { s ->
                    ((s as? JsonObject)?.get("NowPlayingItem") as? JsonObject)?.get("Id")?.jsonPrimitive?.contentOrNull?.replace("-", "") == id.replace("-", "")
                }
            }.getOrDefault(true)
        }

        override suspend fun isLocked(path: String) = MediaFileLock.isHeld(path)
        override fun exists(path: String) = SystemFileSystem.exists(Path(path))
        override fun isEmptyDir(path: String) = listNames(path).isEmpty()

        @OptIn(ExperimentalForeignApi::class)
        override fun mkdir(path: String) = platform.posix.mkdir(path, 0x1FDu) == 0   // 0775

        @OptIn(ExperimentalForeignApi::class)
        override fun rmdir(path: String) = platform.posix.rmdir(path) == 0

        @OptIn(ExperimentalForeignApi::class)
        override fun rename(from: String, to: String): Boolean {
            if (SystemFileSystem.exists(Path(to))) return false   // never overwrite
            return platform.posix.rename(from, to) == 0
        }

        override fun backup(path: String): Boolean = runCatching {
            SystemFileSystem.createDirectories(Path(backupDir))
            val dest = "$backupDir/${path.substringAfterLast('/')}"
            val bytes = FileIo.readBytes(Path(path))
            FileIo.writeBytes(Path(dest), bytes)
            SystemFileSystem.metadataOrNull(Path(dest))?.size == bytes.size.toLong()
        }.getOrDefault(false)

        override fun delete(path: String) = runCatching { SystemFileSystem.delete(Path(path)); true }.getOrDefault(false)

        private suspend fun sid(): String? = cfg.qbittorrent?.let { runCatching { qb.login(it) }.getOrNull() }

        override suspend fun setLocation(hash: String, localFolder: String): Boolean {
            val q = cfg.qbittorrent ?: return false
            return qb.setLocation(q, sid() ?: return false, hash, translateLocalToRemote(localFolder, q))
        }

        override suspend fun recheck(hash: String): Boolean {
            val q = cfg.qbittorrent ?: return false
            return qb.recheck(q, sid() ?: return false, hash)
        }

        override suspend fun radarrUpdatePath(movieId: Int, localFolder: String): Boolean {
            val r = cfg.radarr ?: return false
            return arr.updateMoviePath(r.url, r.apiKey, movieId, toJellyfinView(localFolder, cfg))
        }

        override suspend fun radarrRefresh(movieId: Int): Boolean {
            val r = cfg.radarr ?: return false
            return arr.refreshMovie(r.url, r.apiKey, movieId)
        }

        override suspend fun radarrFilePath(movieId: Int): String? {
            val r = cfg.radarr ?: return null
            repeat(20) {   // a refresh is asynchronous in Radarr: up to a minute for the file to show
                arr.radarrMovieById(r.url, r.apiKey, movieId)?.filePath?.let { return fromJellyfinView(it, cfg) }
                delay(3_000)
            }
            return null
        }

        override suspend fun readUserData(jellyfinId: String): Map<String, ViewerData> {
            val users = jellyfin.getUsersOrNull(base, token) ?: return emptyMap()
            return users.associate { u -> u.id to (readUserDataFor(u.id, jellyfinId) ?: ViewerData(false, 0, false, 0, null)) }
        }

        override suspend fun readUserDataFor(userId: String, jellyfinId: String): ViewerData? =
            jellyfin.getUserDataBulk(base, token, userId, listOf(jellyfinId)).firstOrNull()?.userData?.let {
                ViewerData(it.played, it.playbackPositionTicks, it.isFavorite, it.playCount, it.lastPlayedDate)
            }

        override suspend fun writeUserData(userId: String, jellyfinId: String, data: ViewerData): Boolean = runCatching {
            jellyfin.setUserData(base, token, userId, jellyfinId, data.played, data.positionTicks, data.lastPlayedDate, data.isFavorite, data.playCount)
        }.getOrDefault(false)

        override suspend fun jellyfinNotify(oldVideoPath: String, newFolder: String) {
            jellyfin.notifyMediaUpdated(base, token, listOf(toJellyfinView(newFolder, cfg) to "Created", toJellyfinView(oldVideoPath, cfg) to "Deleted"))
        }

        override suspend fun findJellyfinItem(videoPath: String): String? {
            val jf = toJellyfinView(videoPath, cfg)
            repeat(40) {   // up to two minutes for Jellyfin to pick the folder up
                jellyfin.getItemByPath(base, token, jf)?.id?.let { return it.replace("-", "") }
                delay(3_000)
            }
            return null
        }

        override fun remapJellyfinId(oldId: String, newId: String): Int {
            var n = 0
            val q = db.jellyfinIdRemapQueries
            db.transaction {
                q.remapPlaybackSession(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapStartSample(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapQoe(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapOutbox(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapDirtyItem(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapRecommendation(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapRecommendationReason(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapStarterList(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapAiOrder(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
                q.remapAiTheme(new_id = newId, old_id = oldId); n += q.changes().executeAsOne().toInt()
            }
            return n
        }

        override suspend fun updateMedia(mediaId: String, newVideoPath: String, newJellyfinId: String?) {
            val item = store.get(mediaId) ?: return
            store.updateOne(item.copy(path = newVideoPath, jellyfinId = newJellyfinId ?: item.jellyfinId))
        }

        override suspend fun torrentsNotFine(hashes: List<String>): List<String> {
            if (hashes.isEmpty()) return emptyList()
            val now = runCatching { snapshot.get(forceRefresh = true).torrents }.getOrNull() ?: return emptyList()
            return now.filter { it.hash in hashes && (it.state == "error" || it.state == "missingFiles") }.map { it.hash }
        }

        override fun rootStillHas(root: String, base: String) = listNames(root).any { it.startsWith("$base.") || it.startsWith("$base-") }

        override fun history(mediaId: String, detail: String) = history.record(mediaId, "moved_into_folder", detail)
    }

    companion object {
        /** The running instance, for the Dashboard row (set when constructed in `Main.kt`). */
        @kotlin.concurrent.Volatile
        var current: LooseFilmsService? = null
    }
}

/** True for a regular file (not a folder, not a symlink). */
@OptIn(ExperimentalForeignApi::class)
internal fun isRegularFile(path: String): Boolean = SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true

/** Byte equality of two (small) image files, size first. */
internal fun sameBytes(a: String, b: String): Boolean {
    val sa = SystemFileSystem.metadataOrNull(Path(a))?.size ?: return false
    val sb = SystemFileSystem.metadataOrNull(Path(b))?.size ?: return false
    if (sa != sb || sa > 64L * 1024 * 1024) return false
    return runCatching { FileIo.readBytes(Path(a)).contentEquals(FileIo.readBytes(Path(b))) }.getOrDefault(false)
}
