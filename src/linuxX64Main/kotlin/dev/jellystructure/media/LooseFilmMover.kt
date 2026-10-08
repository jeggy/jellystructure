package dev.jellystructure.media

/**
 * Phase 316 (FR-316-4) — moves one loose film into its own folder, in the spec's order, with every outside effect behind
 * [LooseFilmPorts] so the order and the failure rules are tested with fakes (FR-316-5 test 4).
 *
 * 1. Preconditions: nobody is playing it, no job holds its file, the new folder doesn't exist or is empty. Every user's
 *    Jellyfin data for the film is read now (step 5 needs it, and the old item may be gone afterwards).
 * 2. Same-filesystem `rename` of every planned file (never copy + delete), recorded; any failure renames what was done back
 *    in reverse order and removes the new folder: **never half-moved**. Losing images are then copied to the backup folder
 *    and only removed once the copy is there.
 * 3. qBittorrent: only a torrent whose own data path is the library file is pointed at the new folder and rechecked.
 * 4. Radarr: only when [LooseFilm.radarr] is [RadarrPlan.UPDATE] — path updated with `moveFiles=false`, then refreshed.
 * 5. Jellyfin: told exactly which paths changed, the new item found by path, each user's data written back where the new
 *    item lacks it.
 * 6. Our own data: the media record gets the new path (and id); rows keyed by the old Jellyfin id move to the new one.
 * 7. Verify: Jellyfin has the new item, Radarr has its file (when updated), every torrent that seeded it is still fine,
 *    and the root holds nothing of it any more.
 *
 * After step 2 the film plays from its new folder; a failure in steps 3–7 marks it `partial` with what didn't finish.
 */
class LooseFilmMover(private val ports: LooseFilmPorts) {

    suspend fun move(film: LooseFilm): LooseFilm {
        val steps = ArrayList<LooseStep>()
        fun step(name: String, ok: Boolean, detail: String) { steps += LooseStep(name, ok, detail) }
        fun failed(reason: String) = film.copy(state = "failed", steps = steps.toList(), error = reason)

        // ── 1 — preconditions ──
        if (ports.isPlaying(film)) { step("check", false, "Someone is playing it"); return failed("Someone is playing it right now") }
        if (ports.isLocked(film.key)) { step("check", false, "A job is working on its file"); return failed("A job is working on its file") }
        if (ports.exists(film.targetFolder) && !ports.isEmptyDir(film.targetFolder)) {
            step("check", false, "${film.targetFolder.substringAfterLast('/')} already exists and isn't empty")
            return failed("The folder ${film.targetFolder.substringAfterLast('/')} already exists")
        }
        val userData = film.jellyfinId?.let { runCatching { ports.readUserData(it) }.getOrNull() }.orEmpty()
        step("check", true, "Nobody is playing it; ${userData.size} ${if (userData.size == 1) "viewer has" else "viewers have"} watch data")

        // ── 2 — move ──
        val planned = film.files.filter { it.target != null }
        val createdDir = !ports.exists(film.targetFolder)
        if (createdDir && !ports.mkdir(film.targetFolder)) { step("move", false, "Couldn't create the folder"); return failed("Couldn't create the folder") }
        val done = ArrayList<Pair<String, String>>()
        for (f in planned) {
            val from = "${film.root}/${f.name}"
            val to = "${film.targetFolder}/${f.target}"
            if (!ports.rename(from, to)) {
                for ((a, b) in done.asReversed()) ports.rename(b, a)
                if (createdDir) ports.rmdir(film.targetFolder)
                step("move", false, "Couldn't move ${f.name}; everything was put back")
                return failed("Couldn't move ${f.name}; nothing was changed")
            }
            done += from to to
        }
        var backedUp = 0
        var keptInPlace = 0
        for (f in film.files.filter { it.target == null }) {
            val from = "${film.root}/${f.name}"
            if (ports.backup(from)) { ports.delete(from); backedUp++ } else keptInPlace++
        }
        step("move", true, "${done.size} ${if (done.size == 1) "file" else "files"} moved" +
            (if (backedUp > 0) "; $backedUp older ${if (backedUp == 1) "image" else "images"} backed up" else "") +
            (if (keptInPlace > 0) "; $keptInPlace couldn't be backed up and stayed" else ""))
        val newVideo = "${film.targetFolder}/${film.video}"
        val problems = ArrayList<String>()

        // ── 3 — qBittorrent ──
        val own = film.torrents.filter { it.seedsLibraryPath }
        if (own.isEmpty()) step("torrents", true, if (film.torrents.isEmpty()) "No torrent seeds it" else
            "${film.torrents.size} ${if (film.torrents.size == 1) "torrent seeds" else "torrents seed"} a hard link elsewhere — untouched")
        else {
            val ok = own.count { ports.setLocation(it.hash, film.targetFolder) && ports.recheck(it.hash) }
            step("torrents", ok == own.size, "$ok of ${own.size} pointed at the new folder and rechecked")
            if (ok < own.size) problems += "a torrent wasn't repointed"
        }

        // ── 4 — Radarr ──
        when (film.radarr) {
            RadarrPlan.UPDATE -> {
                val id = film.radarrId
                val ok = id != null && ports.radarrUpdatePath(id, film.targetFolder) && ports.radarrRefresh(id)
                step("radarr", ok, if (ok) "Path updated (files not moved by Radarr) and refreshed" else "Radarr didn't take the new path")
                if (!ok) problems += "Radarr"
            }
            RadarrPlan.OTHER_FILE -> step("radarr", true, "Left alone: Radarr maps this film to another file")
            RadarrPlan.NOT_MANAGED -> step("radarr", true, "Not in Radarr")
            else -> step("radarr", true, "Radarr isn't set up")
        }

        // ── 5 — Jellyfin ──
        ports.jellyfinNotify(film.key, film.targetFolder)
        val newId = ports.findJellyfinItem(newVideo)
        if (newId == null) {
            step("jellyfin", false, "Jellyfin hasn't found it in the new folder yet")
            problems += "Jellyfin"
        } else {
            var restored = 0
            for ((user, data) in userData) {
                if (data.isEmpty()) continue
                val now = runCatching { ports.readUserDataFor(user, newId) }.getOrNull()
                if (now == null || now.isEmpty()) { if (ports.writeUserData(user, newId, data)) restored++ }
            }
            step("jellyfin", true, "Found it" + (if (newId != film.jellyfinId) " under a new id" else "") +
                (if (restored > 0) "; watch data put back for $restored ${if (restored == 1) "viewer" else "viewers"}" else ""))
        }

        // ── 6 — our own data ──
        val remapped = if (newId != null && film.jellyfinId != null && newId != film.jellyfinId) ports.remapJellyfinId(film.jellyfinId, newId) else 0
        if (film.mediaId != null) ports.updateMedia(film.mediaId, newVideo, newId)
        step("records", true, "Path updated" + if (remapped > 0) "; $remapped rows moved to the new id" else "")

        // ── 7 — verify ──
        val checks = ArrayList<String>()
        if (newId == null) checks += "Jellyfin has no item yet"
        if (film.radarr == RadarrPlan.UPDATE && film.radarrId != null) {
            val f = ports.radarrFilePath(film.radarrId)
            if (f == null || f.substringBeforeLast('/') != film.targetFolder) checks += "Radarr has no file there yet"
        }
        val bad = ports.torrentsNotFine(film.torrents.map { it.hash })
        if (bad.isNotEmpty()) checks += "${bad.size} ${if (bad.size == 1) "torrent isn't" else "torrents aren't"} seeding"
        if (ports.rootStillHas(film.root, film.video.substringBeforeLast('.'))) checks += "something of it is still in the root"
        step("verify", checks.isEmpty(), if (checks.isEmpty()) "Plays from its folder; Radarr and every torrent are fine" else checks.joinToString("; "))

        val result = film.copy(
            key = newVideo, jellyfinId = newId ?: film.jellyfinId,
            state = if (problems.isEmpty() && checks.isEmpty()) "moved" else "partial",
            steps = steps.toList(),
            error = (problems + checks).distinct().takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
        film.mediaId?.let { ports.history(it, "Moved into its own folder ${film.targetFolder.substringAfterLast('/')}: " + steps.joinToString(" · ") { s -> "${s.step} ${if (s.ok) "✓" else "✗"} ${s.detail}" }) }
        return result
    }
}

/** One viewer's Jellyfin data for an item, as far as the move carries it over. */
data class ViewerData(val played: Boolean, val positionTicks: Long, val isFavorite: Boolean, val playCount: Int, val lastPlayedDate: String?) {
    fun isEmpty() = !played && positionTicks == 0L && !isFavorite && playCount == 0
}

/** FR-316-4's outside effects; production in [LooseFilmsService], fakes in tests. */
interface LooseFilmPorts {
    suspend fun isPlaying(film: LooseFilm): Boolean
    suspend fun isLocked(path: String): Boolean
    fun exists(path: String): Boolean
    fun isEmptyDir(path: String): Boolean
    fun mkdir(path: String): Boolean
    fun rmdir(path: String): Boolean
    /** A same-filesystem `rename` (never a copy). */
    fun rename(from: String, to: String): Boolean
    /** Copies [path] into this run's backup folder; true once the copy is there. */
    fun backup(path: String): Boolean
    fun delete(path: String): Boolean
    suspend fun setLocation(hash: String, localFolder: String): Boolean
    suspend fun recheck(hash: String): Boolean
    suspend fun radarrUpdatePath(movieId: Int, localFolder: String): Boolean
    suspend fun radarrRefresh(movieId: Int): Boolean
    /** Radarr's file for the movie, as a local path (waits for an asynchronous refresh). */
    suspend fun radarrFilePath(movieId: Int): String?
    /** userId → data, for every viewer. */
    suspend fun readUserData(jellyfinId: String): Map<String, ViewerData>
    suspend fun readUserDataFor(userId: String, jellyfinId: String): ViewerData?
    suspend fun writeUserData(userId: String, jellyfinId: String, data: ViewerData): Boolean
    suspend fun jellyfinNotify(oldVideoPath: String, newFolder: String)
    /** The new item's id once Jellyfin has it at [videoPath] (waits a while), else null. */
    suspend fun findJellyfinItem(videoPath: String): String?
    fun remapJellyfinId(oldId: String, newId: String): Int
    suspend fun updateMedia(mediaId: String, newVideoPath: String, newJellyfinId: String?)
    /** Of [hashes], those that are now in an error or missing-files state. */
    suspend fun torrentsNotFine(hashes: List<String>): List<String>
    fun rootStillHas(root: String, base: String): Boolean
    fun history(mediaId: String, detail: String)
}
