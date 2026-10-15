package dev.jellystructure.media

import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 316, FR-316-7 (DEB-1251's amendment, built in DEB-1252) — the repair of films the mover bound to the wrong
 * Jellyfin item: a one-off, by hand, a dry run unless told otherwise. Every outside effect is behind [RepairPorts] so
 * the partition rules are tested with fakes (test 8).
 *
 * Nothing here guesses. A row is moved only when it carries evidence of its film (FR-316-7's table), and a row the
 * evidence cannot account for stays where it is and is listed. The derived tables are not attributed at all: the
 * existing full recommendations build rewrites them from the library's real ids.
 */

/** One `moved_into_folder` history entry (FR-316-4's line, with the step results in [detail]). */
data class MovedEntry(val mediaId: String, val ts: Long, val detail: String)

data class RepairSessionRow(
    val id: String, val deviceId: String, val startedBy: String?, val queueJson: String, val queueIndex: Int,
    val createdAtMs: Long, val updatedAtMs: Long, val endedAtMs: Long?,
)
data class RepairSampleRow(val id: Long, val deviceId: String, val fileId: String)
/** [updatedAtSec] is **seconds** (`playback_qoe` is), where a session's times are milliseconds. */
data class RepairQoeRow(val deviceId: String, val playSessionId: String, val updatedAtSec: Long)
data class RepairItem(val type: String, val path: String?)
data class RepairMediaRow(val id: String, val path: String, val jellyfinId: String?)

/**
 * The path-asserting lookup behind [RepairPorts.itemIdAt]: one read of **every** `(path, id)` pair Jellyfin holds,
 * then an exact match. This Jellyfin ignores `/Items?Path=`, and a recency window misses a moved film — it keeps its
 * file's original creation time and sits thousands of items deep (DEB-1328). An empty or failed read is not cached.
 */
class RepairPathIndex(private val readAll: suspend () -> List<Pair<String, String>>) {
    private var index: Map<String, String>? = null

    /** The id of the item whose `Path` is exactly [jellyfinPath] (Jellyfin's view of it), or null — never another item. */
    suspend fun idAt(jellyfinPath: String): String? {
        val known = index ?: readAll().takeIf { it.isNotEmpty() }?.toMap()?.also { index = it } ?: return null
        return known[jellyfinPath]
    }
}

/** What [planRepair] reads and what [applyRepair] writes. Production in [LooseFilmsService], fakes in tests. */
interface RepairPorts {
    fun movedEntries(): List<MovedEntry>
    fun mediaRowById(id: String): RepairMediaRow?
    suspend fun mediaRowAtPath(path: String): RepairMediaRow?
    fun fileExists(path: String): Boolean
    /** The single video file in folder [folderName] under a movie library root; null when none or several. */
    fun videoInFolder(folderName: String): String?
    suspend fun jellyfinItem(id: String): RepairItem?
    /** The id of the item Jellyfin has **at** [path] — the path-asserting lookup, never another item. */
    suspend fun itemIdAt(path: String): String?
    fun sessionsOn(id: String): List<RepairSessionRow>
    fun samplesOn(id: String): List<RepairSampleRow>
    fun qoeOn(id: String): List<RepairQoeRow>
    /** Rows on [id] in each derived table (`starter_list`, `recommendation`, …), zero counts left out. */
    fun derivedOn(id: String): Map<String, Int>
    suspend fun viewers(): List<String>
    /** One viewer's data for an item; null when that viewer cannot read the item at all. */
    suspend fun watchData(userId: String, itemId: String): ViewerData?
    suspend fun writeWatchData(userId: String, itemId: String, data: ViewerData): Boolean

    fun repairSession(id: String, oldId: String, newId: String, queueJson: String): Boolean
    fun repairSample(id: Long, oldId: String, newId: String): Boolean
    fun repairQoe(deviceId: String, playSessionId: String, oldId: String, newId: String): Boolean
    suspend fun setMediaJellyfinId(rowId: String, jellyfinId: String): Boolean
    fun history(mediaId: String, detail: String, beforeSnapshot: String)
    /** History lines of [mediaId] since [sinceSec] that were the owner's own edits, as `date action` strings. */
    fun handEditsSince(mediaId: String, sinceSec: Long): List<String>
    /** FR-269-8's full build, the pipeline's own step; rewrites every derived row from the library's real ids. */
    fun queueRecommendationsBuild()
}

@Serializable
data class RepairFilm(
    val mediaId: String,
    /** The folder the history line names, or null for a rename in place. */
    val folder: String?,
    val path: String?,
    val rightId: String?,
    /** `ready`, or `waiting` — nothing is moved for a film Jellyfin has no item for yet; a later run picks it up. */
    val state: String,
    val note: String,
    val movedAt: Long,
)

@Serializable
data class RepairRow(
    val table: String,
    val key: String,
    /** The film (media id) the evidence names, or null when it names none. */
    val film: String?,
    /** The rule of FR-316-7's table that decided it, or why no rule did. */
    val rule: String,
    /** `move` · `keep` (evidence names a film that is not ready, or a live session) · `unaccounted`. */
    val action: String,
    val to: String? = null,
)

@Serializable
data class RepairAlbum(
    val viewersRead: Int,
    val nonEmpty: Int,
    val userId: String? = null,
    val carryTo: String? = null,
    val resetAlbumRow: Boolean = false,
    val note: String,
)

@Serializable
data class RepairMediaFix(val mediaId: String, val rowId: String?, val action: String, val note: String = "")

@Serializable
data class RepairViewers(val mediaId: String, val withData: Int, val empty: Int, val unreadable: Int)

@Serializable
data class RepairPlan(
    val wrongId: String,
    val confirmed: Boolean,
    val stop: String? = null,
    val films: List<RepairFilm> = emptyList(),
    val rows: List<RepairRow> = emptyList(),
    val derived: Map<String, Int> = emptyMap(),
    val historyTotal: Int = 0,
    val attributed: Int = 0,
    val derivedTotal: Int = 0,
    val unaccounted: Int = 0,
    val countCheck: String = "",
    val album: RepairAlbum? = null,
    val mediaRows: List<RepairMediaFix> = emptyList(),
    val viewers: List<RepairViewers> = emptyList(),
    val handEdits: List<String> = emptyList(),
    val nothingToDo: Boolean = false,
    val digest: String = "",
)

/** What a planned row becomes on apply; kept out of [RepairPlan] so the printed plan stays readable. */
internal sealed interface RepairWrite {
    data class Session(val id: String, val film: String, val newId: String, val oldQueue: String, val newQueue: String) : RepairWrite
    data class Sample(val id: Long, val film: String, val newId: String) : RepairWrite
    data class Qoe(val deviceId: String, val playSessionId: String, val film: String, val newId: String) : RepairWrite
}

internal data class AlbumWrite(val userId: String, val film: String, val rightId: String, val carry: ViewerData, val resetAlbumRow: Boolean)

internal class RepairWork(
    val writes: List<RepairWrite>,
    val album: AlbumWrite?,
    val mediaFixes: List<Pair<String, String>>,   // media row id → right Jellyfin id
    val films: Map<String, RepairFilm>,
    val buildRecommendations: Boolean,
)

private val repairJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val SLUG_RE = Regex("""/api/tv/image/([^/?#]+)/""")
private val FOLDER_RE = Regex("""^Moved into its own folder (.+?): check [✓✗]""")
private val ROWS_RE = Regex("""(\d+) rows moved to the new id""")
private val PUT_BACK_ONE_RE = Regex("""watch data put back for 1 viewer\b""")

/** The slug an artwork URL names (`/api/tv/image/<slug>/poster` → `<slug>`), else null. */
internal fun slugOfArtwork(url: String?): String? = url?.let { SLUG_RE.find(it)?.groupValues?.get(1) }

/** One queue entry of a session's `queue_json`: its item id and the slug its artwork names. */
internal data class QueueEntry(val id: String?, val slug: String?)

internal fun queueEntries(queueJson: String): List<QueueEntry>? = runCatching {
    (Json.parseToJsonElement(queueJson) as JsonArray).map { e ->
        val o = e as JsonObject
        QueueEntry(
            o["id"]?.jsonPrimitive?.contentOrNull,
            slugOfArtwork((o["artwork"] as? JsonPrimitive)?.contentOrNull),
        )
    }
}.getOrNull()

/** [queueJson] with each entry whose id is [wrongId] and whose own slug names a ready film given that film's id. */
internal fun rewriteQueue(queueJson: String, wrongId: String, rightBySlug: Map<String, String>): String? = runCatching {
    val arr = Json.parseToJsonElement(queueJson) as JsonArray
    JsonArray(arr.map { e ->
        val o = e as JsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull
        val right = if (id == wrongId) slugOfArtwork((o["artwork"] as? JsonPrimitive)?.contentOrNull)?.let { rightBySlug[it] } else null
        if (right == null) o else JsonObject(o + ("id" to JsonPrimitive(right)))
    }).toString()
}.getOrNull()

/** 2026-09-27 00:00Z — the newest database backup on the host when FR-316-7 was written (DEB-1251). Anything a row held
 *  only after it is gone with a swept row; the plan lists the owner's own edits since, so they can be re-checked. */
internal const val NEWEST_BACKUP_SEC = 1_790_467_200L

private fun countOf(m: Map<String, Int>) = m.values.sum()

/**
 * FR-316-7 — reads everything, writes nothing. [wrongId] is given, then confirmed: Jellyfin must report it as an item
 * that is not a film, at a path that is no moved film's path.
 */
internal suspend fun planRepair(ports: RepairPorts, wrongId: String, backupAtSec: Long = NEWEST_BACKUP_SEC): Pair<RepairPlan, RepairWork> {
    fun stop(why: String) = RepairPlan(wrongId, confirmed = false, stop = why) to RepairWork(emptyList(), null, emptyList(), emptyMap(), false)

    val entries = ports.movedEntries()
    if (entries.isEmpty()) return stop("no film has been moved by the mover — nothing to repair")

    // ── the films: every moved_into_folder line ──
    val byFilm = entries.groupBy { it.mediaId }
    val films = ArrayList<RepairFilm>()
    for ((mediaId, lines) in byFilm) {
        val latest = lines.maxBy { it.ts }
        val folder = FOLDER_RE.find(latest.detail)?.groupValues?.get(1)
        val row = ports.mediaRowById(mediaId)
        val path = row?.path?.takeIf { ports.fileExists(it) } ?: folder?.let { ports.videoInFolder(it) }
        val rightId = path?.let { ports.itemIdAt(it) }
        val (state, note) = when {
            path == null -> "waiting" to "no video found for it yet (no media row, and no single video in its folder) — waiting for the scan"
            rightId == null -> "waiting" to "Jellyfin has no item at $path yet — waiting for the scan"
            else -> "ready" to "Jellyfin's item at its path"
        }
        films += RepairFilm(mediaId, folder, path, rightId, state, note, latest.ts)
    }
    val ready = films.filter { it.state == "ready" }
    val bySlug = films.associateBy { it.mediaId }
    val rightBySlug = ready.associate { it.mediaId to it.rightId!! }

    // ── confirm the wrong id ──
    val wrongItem = ports.jellyfinItem(wrongId)
        ?: return RepairPlan(wrongId, false, "Jellyfin does not know $wrongId — it cannot be confirmed as the wrong item", films) to
            RepairWork(emptyList(), null, emptyList(), emptyMap(), false)
    if (wrongItem.type.equals("Movie", ignoreCase = true))
        return RepairPlan(wrongId, false, "Jellyfin reports $wrongId as a film — it is not the wrong item", films) to
            RepairWork(emptyList(), null, emptyList(), emptyMap(), false)
    if (films.any { it.path != null && it.path == wrongItem.path } || (wrongItem.path != null && ports.mediaRowAtPath(wrongItem.path) != null))
        return RepairPlan(wrongId, false, "$wrongId is at a moved film's path — it is not the wrong item", films) to
            RepairWork(emptyList(), null, emptyList(), emptyMap(), false)

    // ── partition the rows by the evidence each carries ──
    val rows = ArrayList<RepairRow>()
    val writes = ArrayList<RepairWrite>()
    val sessionFilm = HashMap<String, String>()   // session id → film, for QoE's window rule

    val sessions = ports.sessionsOn(wrongId)
    for (s in sessions) {
        val q = queueEntries(s.queueJson)
        val current = q?.let { it.getOrNull(s.queueIndex)?.takeIf { e -> e.id == wrongId } ?: it.firstOrNull { e -> e.id == wrongId } }
        val slug = current?.slug
        val film = slug?.let { bySlug[it] }
        when {
            q == null -> rows += RepairRow("playback_session", s.id, null, "its queue does not parse", "unaccounted")
            slug == null -> rows += RepairRow("playback_session", s.id, null, "no slug in its queue entry's artwork URL", "unaccounted")
            film == null -> rows += RepairRow("playback_session", s.id, null, "its slug names no moved film", "unaccounted")
            s.endedAtMs == null -> rows += RepairRow("playback_session", s.id, film.mediaId, "queue entry slug — session is live, left to end by itself", "keep")
            film.state != "ready" -> rows += RepairRow("playback_session", s.id, film.mediaId, "queue entry slug — ${film.note}", "keep")
            else -> {
                val newQueue = rewriteQueue(s.queueJson, wrongId, rightBySlug)
                if (newQueue == null) rows += RepairRow("playback_session", s.id, film.mediaId, "its queue does not rewrite", "unaccounted")
                else {
                    writes += RepairWrite.Session(s.id, film.mediaId, film.rightId!!, s.queueJson, newQueue)
                    sessionFilm[s.id] = film.mediaId
                    rows += RepairRow("playback_session", s.id, film.mediaId, "queue entry slug, each entry by its own", "move", film.rightId)
                }
            }
        }
    }

    // playback_start_sample — file_id is the path at the time: the root + the film's basename, or its path now.
    for (m in ports.samplesOn(wrongId)) {
        val owners = ready.filter { f ->
            val p = f.path ?: return@filter false
            m.fileId == p || m.fileId.substringAfterLast('/') == p.substringAfterLast('/') && m.fileId.substringBeforeLast('/') == f.folderRoot()
        }
        val unready = films.filter { it.state != "ready" && it.path != null && it.path.substringAfterLast('/') == m.fileId.substringAfterLast('/') }
        when {
            owners.size == 1 -> {
                writes += RepairWrite.Sample(m.id, owners[0].mediaId, owners[0].rightId!!)
                rows += RepairRow("playback_start_sample", m.id.toString(), owners[0].mediaId, "file_id is the film's path before the move", "move", owners[0].rightId)
            }
            owners.size > 1 -> rows += RepairRow("playback_start_sample", m.id.toString(), null, "file_id matches several films", "unaccounted")
            unready.size == 1 -> rows += RepairRow("playback_start_sample", m.id.toString(), unready[0].mediaId, "file_id is that film's path — ${unready[0].note}", "keep")
            else -> rows += RepairRow("playback_start_sample", m.id.toString(), null, "file_id matches no moved film's path", "unaccounted")
        }
    }

    // playback_qoe — the attributed session on the same device whose window holds the row's time; one film only.
    val attributed = sessions.filter { it.id in sessionFilm }
    for (r in ports.qoeOn(wrongId)) {
        val atMs = r.updatedAtSec * 1000L
        val hits = attributed.filter { s ->
            (s.deviceId == r.deviceId || s.startedBy == r.deviceId) && atMs >= s.createdAtMs && atMs <= (s.endedAtMs ?: s.updatedAtMs)
        }
        val filmsHit = hits.map { sessionFilm.getValue(it.id) }.toSet()
        val key = "${r.deviceId}|${r.playSessionId}"
        when {
            filmsHit.size == 1 -> {
                val f = bySlug.getValue(filmsHit.single())
                writes += RepairWrite.Qoe(r.deviceId, r.playSessionId, f.mediaId, f.rightId!!)
                rows += RepairRow("playback_qoe", key, f.mediaId, "the attributed session on its device whose window holds its time", "move", f.rightId)
            }
            filmsHit.isEmpty() -> rows += RepairRow("playback_qoe", key, null, "no attributed session on its device holds its time", "unaccounted")
            else -> rows += RepairRow("playback_qoe", key, null, "sessions of ${filmsHit.size} films hold its time", "unaccounted")
        }
    }

    val derived = ports.derivedOn(wrongId)
    val moveCount = rows.count { it.action == "move" }
    val unaccounted = rows.count { it.action != "move" }
    val historyTotal = entries.sumOf { ROWS_RE.find(it.detail)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    val derivedTotal = countOf(derived)
    // The history lines count rows moved *then*; a second run has fewer on the id, and the check says so.
    val countCheck = when {
        moveCount + unaccounted + derivedTotal == 0 -> "no row is on the wrong id"
        moveCount + derivedTotal == historyTotal -> "attributed $moveCount + derived $derivedTotal = the history lines' $historyTotal"
        else -> "MISMATCH: attributed $moveCount + derived $derivedTotal = ${moveCount + derivedTotal}, the history lines say $historyTotal" +
            (if (unaccounted > 0) " ($unaccounted rows left where they are, listed)" else "")
    }

    // ── the album's own watch data ──
    val viewers = ports.viewers()
    val album = ArrayList<Pair<String, ViewerData>>()
    var readable = 0
    for (u in viewers) {
        val d = ports.watchData(u, wrongId) ?: continue
        readable++
        if (!d.isEmpty()) album += u to d
    }
    val putBackOne = films.filter { f -> byFilm.getValue(f.mediaId).any { PUT_BACK_ONE_RE.containsMatchIn(it.detail) } }
    var albumWrite: AlbumWrite? = null
    val albumPlan = when {
        album.isEmpty() -> RepairAlbum(readable, 0, note = "no viewer's row on it holds data — nothing to carry or reset")
        album.size > 1 -> RepairAlbum(readable, album.size, note = "${album.size} viewers' rows hold data; exactly one is expected — none is carried or reset")
        else -> {
            val (user, data) = album.single()
            val source = putBackOne.singleOrNull()
            when {
                source == null -> RepairAlbum(readable, 1, user, note = "one row holds data, but ${putBackOne.size} history lines say 'put back for 1 viewer' — none is carried or reset")
                source.state != "ready" -> RepairAlbum(readable, 1, user, source.mediaId, note = "the film it came from is ${source.note}")
                else -> {
                    val movedAt = byFilm.getValue(source.mediaId).first { PUT_BACK_ONE_RE.containsMatchIn(it.detail) }.ts
                    val playedAt = data.lastPlayedDate?.let { isoToEpochSeconds(it) }
                    val reset = playedAt == null || playedAt <= movedAt
                    albumWrite = AlbumWrite(user, source.mediaId, source.rightId!!, data, reset)
                    RepairAlbum(readable, 1, user, source.mediaId, reset,
                        if (reset) "carry to ${source.mediaId} if its item reads empty, then reset the album's row"
                        else "carry to ${source.mediaId} if its item reads empty; the album's row was played after the move (${data.lastPlayedDate}) and is left alone")
                }
            }
        }
    }

    // ── the media rows and what the real items carry ──
    val fixes = ArrayList<RepairMediaFix>()
    val fixWrites = ArrayList<Pair<String, String>>()
    val viewerReports = ArrayList<RepairViewers>()
    for (f in films) {
        if (f.state != "ready") { fixes += RepairMediaFix(f.mediaId, null, "waiting for the scan", f.note); continue }
        val row = ports.mediaRowAtPath(f.path!!) ?: ports.mediaRowById(f.mediaId)
        when {
            row == null -> fixes += RepairMediaFix(f.mediaId, null, "waiting for the scan", "no media row yet; ingest recreates it from Jellyfin")
            row.path != f.path -> fixes += RepairMediaFix(f.mediaId, row.id, "left", "its row is at ${row.path}, not ${f.path}")
            row.jellyfinId == f.rightId -> fixes += RepairMediaFix(f.mediaId, row.id, "ok")
            else -> { fixes += RepairMediaFix(f.mediaId, row.id, "correct jellyfinId", "${row.jellyfinId} → ${f.rightId}"); fixWrites += row.id to f.rightId!! }
        }
        var with = 0; var empty = 0; var unreadable = 0
        for (u in viewers) {
            val d = ports.watchData(u, f.rightId!!)
            if (d == null) unreadable++ else if (d.isEmpty()) empty++ else with++
        }
        viewerReports += RepairViewers(f.mediaId, with, empty, unreadable)
    }

    val handEdits = films.flatMap { f -> ports.handEditsSince(f.mediaId, backupAtSec).map { "${f.mediaId}: $it" } }

    val nothing = writes.isEmpty() && albumWrite == null && fixWrites.isEmpty() && derivedTotal == 0
    val plan = RepairPlan(
        wrongId = wrongId, confirmed = true, films = films, rows = rows.sortedWith(compareBy({ it.table }, { it.key })),
        derived = derived, historyTotal = historyTotal, attributed = moveCount, derivedTotal = derivedTotal, unaccounted = unaccounted,
        countCheck = countCheck, album = albumPlan, mediaRows = fixes, viewers = viewerReports,
        handEdits = handEdits, nothingToDo = nothing,
    )
    val digest = sha256Hex(repairJson.encodeToString(RepairPlan.serializer(), plan))
    return plan.copy(digest = digest) to RepairWork(
        writes, albumWrite, fixWrites, films.associateBy { it.mediaId }, buildRecommendations = derivedTotal > 0,
    )
}

/** The directory a film's pre-move video lived in: the parent of its own folder. */
private fun RepairFilm.folderRoot(): String? = path?.substringBeforeLast('/')?.let { if (folder != null) it.substringBeforeLast('/') else it }

/**
 * FR-316-7 — why a `POST` is refused, or null when it may apply: the plan did not confirm the wrong id, the plan read
 * is not the plan now (a stale digest), or there is nothing to do. Only a plan someone has read gets applied.
 */
internal fun repairRefusal(plan: RepairPlan, digest: String): String? = when {
    !plan.confirmed -> plan.stop ?: "The wrong id could not be confirmed"
    plan.digest != digest -> "The plan has changed since it was read — read the new one first"
    plan.nothingToDo -> "Nothing to do"
    else -> null
}

/** What [applyRepair] did; one line per effect, in the order they ran. */
@Serializable
data class RepairReport(val applied: Boolean, val lines: List<String>, val moved: Int = 0)

/**
 * FR-316-7 — applies a plan that was just planned afresh (the route compares the digest first). Every row write is
 * guarded by `AND item_id = <wrong id>`, so a row that moved meanwhile is skipped; one `rebound_jellyfin_id` history
 * entry per film records every row it moved with its key and old value, so the repair itself can be undone by hand.
 */
internal suspend fun applyRepair(ports: RepairPorts, plan: RepairPlan, work: RepairWork): RepairReport {
    val lines = ArrayList<String>()
    val moved = HashMap<String, MutableList<MovedRow>>()
    val newIdOf = HashMap<String, String>()
    fun note(film: String, newId: String, row: MovedRow) { moved.getOrPut(film) { ArrayList() } += row; newIdOf[film] = newId }

    for (w in work.writes) when (w) {
        is RepairWrite.Session -> if (ports.repairSession(w.id, plan.wrongId, w.newId, w.newQueue))
            note(w.film, w.newId, MovedRow("playback_session", w.id, mapOf("item_id" to plan.wrongId, "queue_json" to w.oldQueue)))
        is RepairWrite.Sample -> if (ports.repairSample(w.id, plan.wrongId, w.newId))
            note(w.film, w.newId, MovedRow("playback_start_sample", w.id.toString(), mapOf("item_id" to plan.wrongId)))
        is RepairWrite.Qoe -> if (ports.repairQoe(w.deviceId, w.playSessionId, plan.wrongId, w.newId))
            note(w.film, w.newId, MovedRow("playback_qoe", "${w.deviceId}|${w.playSessionId}", mapOf("jellyfin_id" to plan.wrongId)))
    }
    lines += "${moved.values.sumOf { it.size }} of ${work.writes.size} planned rows moved"

    // The album's own row: carried to the film first, reset only once the carry has read back (or the item already holds it).
    work.album?.let { a ->
        val now = ports.watchData(a.userId, a.rightId)
        val landed = when {
            now == null -> { lines += "album: the film's item cannot be read for that viewer — nothing carried, nothing reset"; false }
            now.isEmpty() -> {
                val wrote = ports.writeWatchData(a.userId, a.rightId, a.carry)
                val back = ports.watchData(a.userId, a.rightId)
                val ok = wrote && back != null && back.played == a.carry.played && back.playCount == a.carry.playCount
                lines += if (ok) "album: watch data carried to ${a.film}" else "album: the write to ${a.film} did not read back — nothing reset"
                ok
            }
            now.played == a.carry.played && now.playCount >= a.carry.playCount -> { lines += "album: ${a.film}'s item already holds it"; true }
            else -> { lines += "album: ${a.film}'s item already holds other data — nothing carried, nothing reset"; false }
        }
        if (landed && a.resetAlbumRow) {
            val wrote = ports.writeWatchData(a.userId, plan.wrongId, ViewerData(false, 0, false, 0, null))
            val back = ports.watchData(a.userId, plan.wrongId)
            lines += if (wrote && back != null && back.isEmpty()) "album: its row reset to empty, read back" else "album: the reset did not read back"
        }
    }

    for ((rowId, jf) in work.mediaFixes) lines += if (ports.setMediaJellyfinId(rowId, jf)) "media row $rowId bound to $jf" else "media row $rowId was not changed"

    for ((film, rows) in moved) {
        val newId = newIdOf.getValue(film)
        ports.history(
            film, "Re-bound ${rows.size} rows from the wrong Jellyfin item to the film's own (FR-316-7)",
            movedRowsSnapshot(plan.wrongId, newId, rows),
        )
    }
    if (work.buildRecommendations) { ports.queueRecommendationsBuild(); lines += "the full recommendations build is queued" }
    return RepairReport(true, lines, moved.values.sumOf { it.size })
}

/** The reply of `POST /api/loose-films/repair`: refused with a reason, or applied with what was done. */
@Serializable
data class RepairApplyResponse(val applied: Boolean, val refused: String? = null, val report: RepairReport? = null, val plan: RepairPlan)
