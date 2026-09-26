package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.nowEpochSec
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.torrent.translateRemoteToLocal
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.closedir
import platform.posix.fgets
import platform.posix.opendir
import platform.posix.pclose
import platform.posix.popen
import platform.posix.readdir
import platform.posix.stat

/**
 * Phase 254 (FR-254-7) — the process-wide handle Triage and the Library filter read, same role
 * [MkvHealthCache] plays for the layout walk: those call sites have no service injected, and the answer
 * is a DB read plus a `stat` per damaged row, so it needs no cache of its own. [revision] bumps on every
 * stored result so a count cached against the library version alone cannot outlive a new finding.
 */
object FileDamage {
    var service: FileIntegrityService? = null
    private val rev = kotlin.concurrent.AtomicInt(0)
    val revision: Int get() = rev.value
    internal fun bump() { rev.incrementAndGet() }

    /** `null` = no service wired (unknown), never an empty set pretending to be "clean". */
    fun damagedPathsOrNull(): Set<String>? = service?.damagedPaths()
}

/** Phase 254 (FR-254-4) — three states, never two. `UNCHECKED` is never rendered or counted as `CLEAN`. */
enum class FileIntegrityState { DAMAGED, CLEAN, UNCHECKED }

/** What a file *is*, for deciding whether a stored result still describes it (FR-254-3), plus the
 *  inode for telling a separate copy from a hard link (FR-254-9). */
data class FileStamp(val size: Long, val mtime: Long, val device: Long, val inode: Long)

data class IntegrityResult(val state: FileIntegrityState, val damageCount: Int = 0, val firstDamage: String? = null, val checkedAt: Long? = null)

/** Phase 254 (FR-254-8) — one file's row on the title's page. */
@Serializable
data class FileIntegrityFileDto(
    val path: String,
    val state: String,
    val damageCount: Int = 0,
    val firstDamage: String? = null,
    val checkedAt: Long? = null,
    val sourcePath: String? = null,
    /** The replacement as one shell snippet when a source exists; the lossy remux otherwise. */
    val command: String? = null,
)

@Serializable
data class FileIntegrityStatusDto(val total: Int, val clean: Int, val unchecked: Int, val files: List<FileIntegrityFileDto>)

/** Phase 263 (FR-263-6) — one library track and the source track the replacement will put under its
 *  label, with what decided it. */
@Serializable
data class TrackPairDto(
    val libraryIndex: Int,
    val sourceIndex: Int,
    val kind: String,
    val language: String,
    /** "content" · "identical" (the label chose among source tracks identical in the window) · "label". */
    val by: String,
    val matched: Int = 0,
    val window: Int = 0,
    val identicalTo: List<Int> = emptyList(),
)

/** Phase 263 (FR-263-6) — one damaged file's replacement, worked out when the operator opens it: the
 *  command the job would run, or the sentence saying why there is none. */
@Serializable
data class FileRepairPlanDto(
    val path: String,
    val sourcePath: String? = null,
    val command: String? = null,
    val refusal: String? = null,
    val pairs: List<TrackPairDto> = emptyList(),
)

@Serializable
private data class ProbeStreams(val streams: List<ProbeStream> = emptyList())

@Serializable
private data class ProbeStream(
    val index: Int,
    @SerialName("codec_type") val codecType: String = "",
    @SerialName("codec_name") val codecName: String = "",
    val disposition: Map<String, Int> = emptyMap(),
    val tags: Map<String, String> = emptyMap(),
)

/**
 * Phase 254 — a file damaged past its first `Cluster`: found (a persisted deep check), said (per-title
 * status + Triage count) and repaired (replaced from a clean copy qBittorrent already knows about).
 * See `specs/requirements/phase-254-a-file-damaged-past-its-first-cluster.md`.
 */
class FileIntegrityService(private val db: JellystructureDb, private val seeding: SeedingSnapshot) {
    private val q get() = db.fileIntegrityQueries
    private val json = Json { ignoreUnknownKeys = true }

    // ── finding ─────────────────────────────────────────────────────────────────────────────────

    /** Every video file path of [items] — same shape as [MkvLayoutAudit.mkvPaths] but not `.mkv`-only:
     *  the deep check is ffmpeg's, so it reads whatever ffmpeg reads. */
    fun videoPaths(items: List<MediaItem>): List<String> = items.flatMap { item ->
        if (item.episodes.isNotEmpty()) item.episodes.map { it.path } else listOf(item.path)
    }.filter { it.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS }.distinct()

    fun resultFor(path: String, rows: Map<String, dev.jellystructure.db.File_integrity> = rowsByPath()): IntegrityResult {
        val row = rows[path] ?: return IntegrityResult(FileIntegrityState.UNCHECKED)
        val stamp = stampOf(path) ?: return IntegrityResult(FileIntegrityState.UNCHECKED)
        if (!isCurrent(row.size, row.mtime, stamp)) return IntegrityResult(FileIntegrityState.UNCHECKED)
        return if (row.damage_count > 0) IntegrityResult(FileIntegrityState.DAMAGED, row.damage_count.toInt(), row.first_damage, row.checked_at)
        else IntegrityResult(FileIntegrityState.CLEAN, checkedAt = row.checked_at)
    }

    fun rowsByPath(): Map<String, dev.jellystructure.db.File_integrity> = q.all().executeAsList().associateBy { it.path }

    /** Phase 261 (FR-261-10) — one file's stored row, current or not (the caller applies [isCurrent]). */
    fun rowsFor(path: String): dev.jellystructure.db.File_integrity? = q.byPath(path).executeAsOneOrNull()

    /** FR-254-7 — currently-damaged paths: a stored damaged row that still describes the file on disk.
     *  Cheap: one query plus one `stat` per damaged row, never a file read. */
    fun damagedPaths(): Set<String> = q.damaged().executeAsList()
        .filter { row -> stampOf(row.path)?.let { isCurrent(row.size, row.mtime, it) } == true }
        .mapTo(mutableSetOf()) { it.path }

    /** FR-254-1 — one deep check, persisted. Returns null when the file cannot even be stat-ed. */
    suspend fun check(path: String): IntegrityResult? {
        val before = stampOf(path) ?: return null
        val lines = demuxDamage(path) ?: return null
        val after = stampOf(path) ?: return null
        // Rewritten while it was being read: the answer describes neither file. Leave it unchecked.
        if (after.size != before.size || after.mtime != before.mtime) return IntegrityResult(FileIntegrityState.UNCHECKED)
        val first = lines.firstOrNull()?.let(FileIntegrity::displayLine)
        q.put(path, after.size, after.mtime, nowEpochSec(), lines.size.toLong(), first)
        if (lines.isNotEmpty()) FileDamage.bump()
        if (lines.isNotEmpty()) Logger.warn("deep check: ${lines.size} damage line(s) in $path — $first", "integrity")
        return if (lines.isEmpty()) IntegrityResult(FileIntegrityState.CLEAN, checkedAt = nowEpochSec())
        else IntegrityResult(FileIntegrityState.DAMAGED, lines.size, first, nowEpochSec())
    }

    /** The damage lines of one demux pass, or null when the pass itself did not happen — and *did not
     *  happen* must never be stored as *clean*. ffmpeg exits 0 on a file it could read to the end however
     *  many demux errors it printed; 126/127 is "no ffmpeg here" (null); any other failure is a file
     *  ffmpeg could not open at all, which is damage, reported with ffmpeg's own last line. */
    private suspend fun demuxDamage(path: String): List<String>? {
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { capture("nice -n 19 ${FileIntegrity.checkCommand(path)}; echo JS_EXIT=\$?") } ?: return null
        val exit = out.substringAfterLast("JS_EXIT=", "").trim().toIntOrNull() ?: return null
        val body = out.substringBeforeLast("JS_EXIT=")
        return when (exit) {
            0 -> FileIntegrity.damageLines(body)
            126, 127 -> null
            else -> FileIntegrity.damageLines(body).ifEmpty { listOf(body.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "ffmpeg could not open the file (exit $exit)") }
        }
    }

    // ── saying ──────────────────────────────────────────────────────────────────────────────────

    suspend fun statusFor(item: MediaItem, config: AppConfig): FileIntegrityStatusDto {
        val paths = videoPaths(listOf(item))
        val rows = rowsByPath()
        val results = paths.associateWith { resultFor(it, rows) }
        val damaged = results.filterValues { it.state == FileIntegrityState.DAMAGED }
        val sources = if (damaged.isEmpty()) emptyMap() else damaged.keys.associateWith { findSourceCandidate(it, config) }
        val files = results.filterValues { it.state != FileIntegrityState.CLEAN }.map { (path, r) ->
            val source = sources[path]
            FileIntegrityFileDto(
                path = path, state = r.state.name.lowercase(), damageCount = r.damageCount, firstDamage = r.firstDamage,
                checkedAt = r.checkedAt, sourcePath = source,
                // Phase 263 (FR-263-6): a replacement's command needs its tracks paired, which reads both
                // files — it is asked for per file ([repairPlanFor]), never while this page loads.
                command = if (r.state == FileIntegrityState.DAMAGED && source == null) TrackCommandBuilder.ffmpegRepairTracksLayout(path) else null,
            )
        }
        return FileIntegrityStatusDto(
            total = paths.size,
            clean = results.values.count { it.state == FileIntegrityState.CLEAN },
            unchecked = results.values.count { it.state == FileIntegrityState.UNCHECKED },
            files = files,
        )
    }

    // ── fixing ──────────────────────────────────────────────────────────────────────────────────

    /** FR-254-9, the cheap half: same basename, inside a torrent's content path, a different inode.
     *  Whether the candidate is itself clean is decided at repair time ([replaceFromSource]) — this is
     *  called while rendering a page and must not read a file. */
    suspend fun findSourceCandidate(libraryPath: String, config: AppConfig): String? {
        val qb = config.qbittorrent?.takeIf { it.enabled } ?: return null
        val lib = stampOf(libraryPath) ?: return null
        val name = libraryPath.substringAfterLast('/')
        return seeding.get().torrents.asSequence()
            .map { translateRemoteToLocal(it.contentPath, qb).trimEnd('/') }
            .distinct()
            .mapNotNull { content -> if (content.substringAfterLast('/') == name) content else "$content/$name".takeIf { name in namesIn(content) } }
            .filter { it != libraryPath }
            .firstOrNull { cand -> stampOf(cand)?.let { it.device != lib.device || it.inode != lib.inode } == true }
    }

    /** Phase 263 — a replacement worked out: the plan, or the sentence saying why there is none; plus the
     *  library copy's tracks and its first two minutes of packets, which the job checks the result against. */
    private class Planning(
        val plan: FileRepairPlan?,
        val refusal: String?,
        val library: List<StreamShape> = emptyList(),
        val libraryWindow: Map<Int, Map<String, Int>> = emptyMap(),
    )

    /** FR-263-1..4 — pair every library track with its source track by content, then build the plan in
     *  the library's order. [interactive] = a page is waiting (the request gate), else the job's lane. */
    private suspend fun planning(libraryPath: String, sourcePath: String, config: AppConfig, interactive: Boolean): Planning {
        val library = streamShapes(libraryPath, interactive) ?: return Planning(null, "Couldn't read the library file's tracks")
        val source = streamShapes(sourcePath, interactive) ?: return Planning(null, "Couldn't read the source copy's tracks")
        val libraryWindow = windowPackets(libraryPath, interactive) ?: return Planning(null, "Couldn't read the library file's first two minutes")
        val sourceWindow = windowPackets(sourcePath, interactive) ?: return Planning(null, "Couldn't read the source copy's first two minutes")
        return when (val pairing = TrackPairer.pair(library, source, libraryWindow, sourceWindow)) {
            is TrackPairing.Refused -> Planning(null, pairing.reason, library, libraryWindow)
            is TrackPairing.Paired -> Planning(
                FileRepairPlan(libraryPath, sourcePath, quarantinePathFor(libraryPath, config), library.map { it.flags }, pairing.pairs),
                null, library, libraryWindow,
            )
        }
    }

    /** FR-263-6 — one damaged file's replacement for the page, worked out when the operator opens it. */
    suspend fun repairPlanFor(libraryPath: String, config: AppConfig): FileRepairPlanDto {
        val source = findSourceCandidate(libraryPath, config) ?: return FileRepairPlanDto(libraryPath, refusal = "No clean copy found in qBittorrent")
        val p = try {
            planning(libraryPath, source, config, interactive = true)
        } catch (e: dev.jellystructure.ops.ProcessGate.GateTimeoutException) {
            return FileRepairPlanDto(libraryPath, source, refusal = "The server is busy — open this again in a moment")
        }
        val plan = p.plan ?: return FileRepairPlanDto(libraryPath, source, refusal = p.refusal)
        val byIndex = p.library.associateBy { it.index }
        return FileRepairPlanDto(
            libraryPath, source, plan.snippet,
            pairs = plan.pairs.map { pair ->
                val s = byIndex.getValue(pair.libraryIndex)
                TrackPairDto(pair.libraryIndex, pair.sourceIndex, s.codecType, s.flags.language, pair.by.name.lowercase(), pair.matched, pair.window, pair.identicalTo)
            },
        )
    }

    /** FR-254-10 — replace, verified, reversible. Returns null on success, else the sentence saying
     *  which step refused; on any refusal the library file is untouched and the temp file is gone.
     *  Phase 263: the source is mapped in the library's order (FR-263-4) and the result is checked by what
     *  its tracks carry, not by what they are called (FR-263-5). */
    suspend fun replaceFromSource(libraryPath: String, config: AppConfig): String? = MediaFileLock.withLock(libraryPath) {
        val source = findSourceCandidate(libraryPath, config) ?: return@withLock "No clean copy found in qBittorrent"
        val planning = planning(libraryPath, source, config, interactive = false)
        val plan = planning.plan ?: return@withLock planning.refusal ?: "Couldn't work out which track is which"
        val oldFlags = planning.library.map { it.flags }

        // The source under the plan's maps: its deep check (FR-254-9) and its per-stream hashes, one pass.
        val want = hashPass(plan.sourceHashCommand) ?: return@withLock "Couldn't read the source copy"
        if (want.damage.isNotEmpty()) return@withLock "The copy in qBittorrent is damaged too"
        if (want.hashes.size != plan.pairs.size) return@withLock "Couldn't hash the source copy's tracks"
        // FR-263-8 — a label chose among these because the first two minutes could not; that is only
        // harmless if they are the same track all the way through.
        for (group in plan.identicalGroups) {
            if (plan.positionsOf(group).map { want.hashes[it] }.distinct().size != 1) {
                return@withLock "Source tracks ${group.joinToString(", ")} are identical for the first two minutes but not after — can't tell which is which"
            }
        }

        fun refuse(why: String): String { platform.posix.remove(plan.tmpPath); return why }
        if (!run(plan.copyCommand)) return@withLock refuse("ffmpeg couldn't copy the source")
        val got = hashPass(plan.newHashCommand) ?: return@withLock refuse("Couldn't verify the new file")
        if (got.damage.isNotEmpty()) return@withLock refuse("The new file did not verify clean")
        if (got.hashes != want.hashes) return@withLock refuse("The new file's tracks are not the source's tracks in the planned order")
        if (streamFlags(plan.tmpPath) != oldFlags) return@withLock refuse("The new file's track flags differ from the library copy's")
        val newWindow = windowPackets(plan.tmpPath, interactive = false) ?: return@withLock refuse("Couldn't read the new file's first two minutes")
        val wrong = TrackPairer.positionsNotHolding(planning.libraryWindow, newWindow)
        if (wrong.isNotEmpty()) return@withLock refuse("Track${if (wrong.size != 1) "s" else ""} ${wrong.joinToString(", ")} of the new file don't hold what the library copy held there")
        if (!run(plan.swapCommand)) return@withLock refuse("Couldn't move the damaged file to ${plan.quarantinePath}")

        stampOf(libraryPath)?.let { q.put(libraryPath, it.size, it.mtime, nowEpochSec(), 0, null) }
        FileDamage.bump()
        Logger.info(
            "replaced $libraryPath from $source; damaged original kept at ${plan.quarantinePath}; tracks " +
                plan.pairs.joinToString(" ") { "${it.libraryIndex}←${it.sourceIndex}${if (it.by == PairedBy.CONTENT) "" else "(${it.by.name.lowercase()})"}" },
            "integrity",
        )
        null
    }

    /** `<parent of the library root>/.js-quarantine/<path relative to the root>` — outside anything
     *  Jellyfin scans. A second repair of the same path gets a timestamp suffix, never an overwrite. */
    fun quarantinePathFor(libraryPath: String, config: AppConfig): String {
        val root = config.libraries.map { it.localPath.trimEnd('/') }
            .filter { it.isNotEmpty() && libraryPath.startsWith("$it/") }.maxByOrNull { it.length }
            ?: libraryPath.substringBeforeLast('/')
        val base = root.substringBeforeLast('/') + "/.js-quarantine/" + root.substringAfterLast('/') + libraryPath.removePrefix(root)
        return if (stampOf(base) == null) base else "$base.${nowEpochSec()}"
    }

    private suspend fun streamFlags(path: String): List<StreamFlags>? = streamShapes(path, interactive = false)?.map { it.flags }

    private suspend fun streamShapes(path: String, interactive: Boolean): List<StreamShape>? = probe(path, interactive)?.map { s ->
        fun tag(name: String) = s.tags.entries.firstOrNull { it.key.equals(name, true) }?.value
        StreamShape(
            flags = StreamFlags(
                index = s.index,
                language = tag("language") ?: "und",
                title = tag("title") ?: "",
                dispositions = CARRIED_DISPOSITIONS.filter { (s.disposition[it] ?: 0) != 0 },
            ),
            codecType = s.codecType,
            codecName = s.codecName,
            filename = tag("filename") ?: "",
        )
    }

    private suspend fun probe(path: String, interactive: Boolean): List<ProbeStream>? {
        val cmd = "nice -n 19 ffprobe -v quiet -show_streams -of json '${FileIntegrity.esc(path)}' 2>/dev/null"
        val out = gated(interactive) { capture(cmd) } ?: return null
        return runCatching { json.decodeFromString(ProbeStreams.serializer(), out).streams }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Phase 263 (FR-263-1) — the first two minutes of [path] as per-packet hashes; null when nothing
     *  could be read (an empty window can pair nothing, and must not read as "no packets to compare"). */
    private suspend fun windowPackets(path: String, interactive: Boolean): Map<Int, Map<String, Int>>? =
        gated(interactive) { capture(TrackPairer.windowCommand(path)) }?.let(TrackPairer::packets)?.takeIf { it.isNotEmpty() }

    private class HashPass(val damage: List<String>, val hashes: Map<Int, String>)

    /** Phase 263 (FR-263-5) — one `streamhash` pass: the file's damage lines and its per-stream hashes.
     *  Same exit handling as [demuxDamage]: 126/127 (no ffmpeg) is null, never a clean empty result. */
    private suspend fun hashPass(cmd: String): HashPass? {
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { capture("$cmd; echo JS_EXIT=\$?") } ?: return null
        val exit = out.substringAfterLast("JS_EXIT=", "").trim().toIntOrNull() ?: return null
        val body = out.substringBeforeLast("JS_EXIT=")
        return when (exit) {
            0 -> HashPass(FileIntegrity.damageLines(body), TrackPairer.streamHashes(body))
            126, 127 -> null
            else -> HashPass(FileIntegrity.damageLines(body).ifEmpty { listOf("ffmpeg exited $exit") }, emptyMap())
        }
    }

    /** A page waiting on the answer takes the request gate (its reserved slots, a bounded wait); the
     *  job takes the segments lane's own pool, like every other read here. */
    private suspend fun <T> gated(interactive: Boolean, block: suspend () -> T): T =
        if (interactive) dev.jellystructure.ops.ProcessGate.withPermit(block) else dev.jellystructure.ops.SegmentProcessGate.withPermit(block)

    private suspend fun run(cmd: String): Boolean =
        dev.jellystructure.ops.SegmentProcessGate.withPermit { capture("( $cmd ) >/dev/null 2>&1 && echo JS_OK") }?.contains("JS_OK") == true

    @OptIn(ExperimentalForeignApi::class)
    private fun capture(cmd: String): String? = memScoped {
        val pipe = popen(cmd, "r") ?: return@memScoped null
        val sb = StringBuilder()
        val buf = allocArray<ByteVar>(4096)
        while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun namesIn(dir: String): Set<String> {
        val d = opendir(dir) ?: return emptySet()
        return try {
            buildSet { while (true) { val e = readdir(d) ?: break; add(e.pointed.d_name.toKString()) } }
        } finally { closedir(d) }
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "ts", "webm", "mov")
        private val CARRIED_DISPOSITIONS = listOf("default", "forced", "hearing_impaired", "visual_impaired", "comment", "original", "dub")

        /** FR-254-3 — a stored result describes the file only while both still match. */
        fun isCurrent(rowSize: Long, rowMtime: Long, stamp: FileStamp): Boolean = rowSize == stamp.size && rowMtime == stamp.mtime

        @OptIn(ExperimentalForeignApi::class)
        fun stampOf(path: String): FileStamp? = memScoped {
            val st = alloc<stat>()
            if (stat(path, st.ptr) != 0) null
            else FileStamp(st.st_size, st.st_mtim.tv_sec, st.st_dev.toLong(), st.st_ino.toLong())
        }
    }
}
