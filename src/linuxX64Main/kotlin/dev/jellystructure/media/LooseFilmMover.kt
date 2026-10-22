package dev.jellystructure.media

import dev.jellystructure.arr.RadarrProvenance
import dev.jellystructure.arr.provenanceGaps
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * 4b. Phase 314 (FR-314-13) — after an in-place rename, the film's images and NFO are written back if Radarr's extras
 *    cleanup took them with the `moviefile` row for the vanished path. Here, not after step 7: steps 5 and 7 wait on
 *    Jellyfin and Radarr for up to two minutes and a minute, and the folder must not be bare for that long.
 * 4c. Phase 314 (FR-314-14) — the provenance Radarr rebuilds from the new filename (quality, languages, edition,
 *    release group, scene name, indexer flags), read before step 2 and written onto the row Radarr creates after it.
 * 5. Jellyfin: told exactly which paths changed, the new item found by path, each user's data written back where the new
 *    item lacks it. An item is only bound when Jellyfin reports it **at the path asked for** (DEB-1252: 12.1 ignores the
 *    `Path=` filter, and the first item of the whole library was bound to three films).
 * 6. Our own data: the media record gets the new path (and id); rows keyed by the old Jellyfin id move to the new one,
 *    and each is written to the history entry's `before_snapshot` (FR-316-9).
 * 7. Verify: Jellyfin's item for the film is at its new path, Radarr has its file (when updated), every torrent that
 *    seeded it is still fine, and the root holds nothing of it any more.
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
        // Phase 314c — a rename in place (the film keeps its folder, its files get the folder's name) has no new folder.
        val inPlace = film.targetFolder == film.root
        if (inPlace) {
            val clash = film.files.firstOrNull { it.target != null && it.target != it.name && ports.exists("${film.root}/${it.target}") }
            if (clash != null) { step("check", false, "${clash.target} already exists"); return failed("${clash.target} already exists") }
        } else if (ports.exists(film.targetFolder) && !ports.isEmptyDir(film.targetFolder)) {
            step("check", false, "${film.targetFolder.substringAfterLast('/')} already exists and isn't empty")
            return failed("The folder ${film.targetFolder.substringAfterLast('/')} already exists")
        }
        val userData = film.jellyfinId?.let { runCatching { ports.readUserData(it) }.getOrNull() }.orEmpty()
        step("check", true, "Nobody is playing it; ${userData.size} ${if (userData.size == 1) "viewer has" else "viewers have"} watch data")

        // Phase 314 (FR-314-14) — read before a single file moves: the row this comes off is the one Radarr drops when
        // the path under it changes, and the row it builds instead comes from a fresh parse of the new filename. Only
        // where Radarr owns this very file (step 4's UPDATE); any other plan leaves Radarr alone, here too.
        val radarrId = film.radarrId.takeIf { film.radarr == RadarrPlan.UPDATE }
        val provenance = radarrId?.let { runCatching { ports.readProvenance(it) }.getOrNull() }

        // ── 2 — move ──
        val planned = film.files.filter { it.target != null && !(inPlace && it.target == it.name) }
        val createdDir = !inPlace && !ports.exists(film.targetFolder)
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
        val newVideo = "${film.targetFolder}/${film.files.firstOrNull { it.name == film.video }?.target ?: film.video}"
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

        // ── 4b — the artwork Radarr takes with the old path (FR-314-13) ──
        //
        // Only for a rename in place: a move into a new folder carries the film's images in its own plan (FR-316-1),
        // and this is the case where Radarr sees a path vanish under a `moviefile` row and deletes the poster, fanart,
        // clearlogo and `movie.nfo` it had adopted as that movie's extras (DEB-869, measured on prod 2026-10-13).
        if (inPlace) {
            val back = runCatching { ports.reassertAssets(film, newVideo) }.getOrDefault(emptyList())
            step("artwork", true, if (back.isEmpty()) "Its images and NFO are in the folder" else "Put ${back.joinToString(", ")} back")
        }

        // ── 4c — the provenance Radarr rebuilds from the new filename (FR-314-14) ──
        //
        // A name taken from the folder carries no quality, group or source tokens, so the row Radarr re-imports comes
        // back with a remux labelled a WEB-DL and its group, scene name and edition emptied (DEB-881, measured on prod
        // 2026-10-13). Unlike FR-314-12's `movieFileId`, Radarr takes these back — and what it does not take is said
        // out loud against the film, because provenance lost quietly is the defect.
        if (provenance != null && radarrId != null) {
            val carried = provenance.carried()
            if (carried.isEmpty()) step("provenance", true, "Radarr's file record has no quality or release to carry")
            else {
                val carry = runCatching { ports.carryProvenance(radarrId, provenance, newVideo.substringAfterLast('/')) }
                    .getOrElse { ProvenanceCarry(null, carried) }
                val gaps = carry.notRestored
                // `media-safety` §2's field: a film that had an edition and comes back without one is the shape a
                // blind replacement starts from, so it is named for what it is.
                val degraded = if ("edition" in gaps) " — the rename degraded the record" else ""
                if (gaps.isEmpty()) step("provenance", true,
                    if (carry.kept) "Radarr kept the film's ${carried.joinToString(", ")}"
                    else "Put the film's ${carried.joinToString(", ")} back on Radarr's new file record")
                else {
                    step("provenance", false,
                        if (carry.movieFileId == null) "Radarr hasn't rebuilt its file record — ${gaps.joinToString(", ")} not put back$degraded"
                        else "Radarr didn't take ${gaps.joinToString(", ")}$degraded")
                    problems += "Radarr's file record lost ${gaps.joinToString(", ")}$degraded"
                }
            }
        }

        // ── 5 — Jellyfin ──
        ports.jellyfinNotify(film.key, film.targetFolder)
        val found = ports.findJellyfinItem(newVideo)
        // DEB-1252 — what the lookup returns is only taken when Jellyfin reports that item at the path it was asked
        // for. A lookup that fails this is "not found yet", never a binding: nothing below runs on the wrong item.
        val foundAt = found?.let { ports.jellyfinItemPath(it) }
        val newId = found?.takeIf { foundAt == newVideo }
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
        val remap = if (newId != null && film.jellyfinId != null && newId != film.jellyfinId) ports.remapJellyfinId(film.jellyfinId, newId) else RemapResult.NONE
        if (film.mediaId != null) ports.updateMedia(film.mediaId, newVideo, newId)
        step("records", true, "Path updated" + if (remap.count > 0) "; ${remap.count} rows moved to the new id" else "")

        // ── 7 — verify ──
        val checks = ArrayList<String>()
        if (newId == null) checks += "Jellyfin has no item yet"
        else {
            // DEB-1252 — read again, not carried from step 5: the album passed "a file is there"; what has to hold is
            // that the item bound to this film is the film's own file.
            val at = ports.jellyfinItemPath(newId)
            if (at != newVideo) checks += "Jellyfin's item for it is at ${at ?: "no path"}, not ${newVideo.substringAfterLast('/')}"
        }
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
        val what = if (inPlace) "Renamed to ${newVideo.substringAfterLast('/')}" else "Moved into its own folder ${film.targetFolder.substringAfterLast('/')}"
        film.mediaId?.let {
            ports.history(
                it, "$what: " + steps.joinToString(" · ") { s -> "${s.step} ${if (s.ok) "✓" else "✗"} ${s.detail}" },
                // FR-316-9 — only when step 6 moved rows: old id, new id and every row, so a repair is a lookup.
                if (remap.count > 0 && film.jellyfinId != null && newId != null) movedRowsSnapshot(film.jellyfinId, newId, remap.rows) else "",
            )
        }
        return result
    }
}

/** FR-316-9 — one row [LooseFilmPorts.remapJellyfinId] carried to a new id: [old] is column → the value it held before. */
data class MovedRow(val table: String, val key: String, val old: Map<String, String>)

/** What step 6 moved; [count] is rows, not columns. */
data class RemapResult(val rows: List<MovedRow>) {
    val count: Int get() = rows.size
    companion object { val NONE = RemapResult(emptyList()) }
}

/** FR-316-9 / FR-316-7 — the `before_snapshot` of a rebinding: both ids and every row moved, with its key and old values. */
fun movedRowsSnapshot(oldId: String, newId: String, rows: List<MovedRow>): String = JsonObject(mapOf(
    "oldId" to JsonPrimitive(oldId),
    "newId" to JsonPrimitive(newId),
    "rows" to JsonArray(rows.map { r ->
        JsonObject(mapOf(
            "table" to JsonPrimitive(r.table),
            "key" to JsonPrimitive(r.key),
            "old" to JsonObject(r.old.mapValues { JsonPrimitive(it.value) }),
        ))
    }),
)).toString()

/** FR-314-14 — what became of one film's provenance across Radarr's drop and re-create. */
data class ProvenanceCarry(
    /** The row the values went onto; null when Radarr had not rebuilt one by the last pass. */
    val movieFileId: Int?,
    /** Carried fields that do not read back on that row — everything, when no row appeared at all. */
    val notRestored: List<String>,
    /** True when the record already carried everything and nothing had to be written. */
    val kept: Boolean = false,
)

/**
 * FR-314-14 — the wait for the row Radarr rebuilds, and the write onto it. Shared so the rule is one piece of code
 * and testable without a Radarr: [read] is the movie's `moviefile` row as it stands, [write] the PUT-then-read-back.
 *
 * Radarr re-imports asynchronously, so the row for [newVideoName] can be two passes away — and the row that is there
 * on the first pass may still be the one Radarr is about to drop. Hence two rules:
 * - a row is only the rebuilt one once its **file name** is the new one (an id is no good: the old row keeps its id
 *   until Radarr deletes it, and FR-314-12's `movieFileId 0` moment reads as no row at all);
 * - a row with the name and the **same id** is only taken as "Radarr kept it" on the last pass, never on the first:
 *   concluding that early is how the loss would go quiet again.
 *
 * Nothing is written when the row already carries everything. When no row ever appears, every carried field is
 * returned as not restored — the caller reports it.
 */
internal suspend fun carryProvenanceAcrossRename(
    prov: RadarrProvenance,
    newVideoName: String,
    passes: Int,
    settleMs: Long,
    read: suspend () -> RadarrProvenance?,
    write: suspend (Int, RadarrProvenance) -> RadarrProvenance?,
): ProvenanceCarry {
    repeat(passes) { pass ->
        if (pass > 0) delay(settleMs)
        val now = read() ?: return@repeat
        if (now.fileName != newVideoName) return@repeat
        if (now.movieFileId == prov.movieFileId && pass < passes - 1) return@repeat
        if (provenanceGaps(prov, now).isEmpty()) return ProvenanceCarry(now.movieFileId, emptyList(), kept = true)
        return ProvenanceCarry(now.movieFileId, provenanceGaps(prov, write(now.movieFileId, prov)))
    }
    return ProvenanceCarry(null, provenanceGaps(prov, null))
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
    /**
     * FR-314-13 — writes back whatever of the film's images and NFO is not in its folder, for [videoPath] (the name the
     * rename has just given it, which the media row may not carry yet). Returns the filenames it put back; a whole
     * folder costs four `stat` calls and returns empty. Repeats behind Radarr's asynchronous delete, so it can take
     * half a minute.
     */
    suspend fun reassertAssets(film: LooseFilm, videoPath: String): List<String>
    /**
     * FR-314-14 — the provenance on Radarr's `moviefile` row for the movie **as it stands now**, before anything is
     * renamed; null when there is none to carry or Radarr can't be read.
     */
    suspend fun readProvenance(movieId: Int): RadarrProvenance?
    /**
     * FR-314-14 — puts [prov] back onto the row Radarr rebuilds for the file now called [newVideoName]. That row
     * appears asynchronously during Radarr's rescan, so this waits for it; what it returns is reported either way.
     */
    suspend fun carryProvenance(movieId: Int, prov: RadarrProvenance, newVideoName: String): ProvenanceCarry
    suspend fun radarrRefresh(movieId: Int): Boolean
    /** DEB-2051 (FR-8) — `RescanMovie`: Radarr re-reads the folder it already has. Defaults to the refresh, which rescans too. */
    suspend fun radarrRescan(movieId: Int): Boolean = radarrRefresh(movieId)
    /** Radarr's file for the movie, as a local path (waits for an asynchronous refresh). */
    suspend fun radarrFilePath(movieId: Int): String?
    /** userId → data, for every viewer. */
    suspend fun readUserData(jellyfinId: String): Map<String, ViewerData>
    suspend fun readUserDataFor(userId: String, jellyfinId: String): ViewerData?
    suspend fun writeUserData(userId: String, jellyfinId: String, data: ViewerData): Boolean
    suspend fun jellyfinNotify(oldVideoPath: String, newFolder: String)
    /** The id of the item Jellyfin has **at** [videoPath] (waits a while), else null — never another item. */
    suspend fun findJellyfinItem(videoPath: String): String?
    /** DEB-1252 — the local path Jellyfin reports for item [jellyfinId] now; null when it cannot be read. */
    suspend fun jellyfinItemPath(jellyfinId: String): String?
    /** Every row keyed by [oldId] that now follows [newId], each with its key and old value (FR-316-9). */
    fun remapJellyfinId(oldId: String, newId: String): RemapResult
    suspend fun updateMedia(mediaId: String, newVideoPath: String, newJellyfinId: String?)
    /** Of [hashes], those that are now in an error or missing-files state. */
    suspend fun torrentsNotFine(hashes: List<String>): List<String>
    fun rootStillHas(root: String, base: String): Boolean
    fun history(mediaId: String, detail: String, beforeSnapshot: String = "")
}
