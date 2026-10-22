package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.torrent.SeedingCheckResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** What FR-8 did once the new file was in place; the job keeps the two flags the admin shows (FR-9 *verified*). */
data class ConversionIdentityResult(
    /** Jellyfin showed an item at the new path. False: other devices still see the old file until it indexes it. */
    val jellyfinFound: Boolean,
    /** Radarr's file link names the `.mkv`. False: a person re-links it in Radarr (it has no API to do so). Null: not Radarr's. */
    val radarrTaken: Boolean?,
    /** One line per step, written to the title's history. */
    val steps: List<LooseStep>,
)

/** The outside effects of a conversion; production in `LooseFilmsService`, fakes in tests (FR-11). */
interface ConversionPorts {
    suspend fun seeding(path: String): SeedingCheckResult
    /** The item's current path in Jellyfin, mapped to the backend's view; null when it cannot be read. */
    suspend fun jellyfinPath(item: MediaItem): String?
    fun freeBytes(dir: String): Long?
    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))
    fun sizeOf(path: String): Long? = SystemFileSystem.metadataOrNull(Path(path))?.size
    /** A same-filesystem `rename(2)`; never overwrites. */
    @OptIn(ExperimentalForeignApi::class)
    fun rename(from: String, to: String): Boolean = !SystemFileSystem.exists(Path(to)) && platform.posix.rename(from, to) == 0
    @OptIn(ExperimentalForeignApi::class)
    fun remove(path: String) { platform.posix.remove(path) }
    suspend fun probe(path: String): List<Track> = FfprobeRunner.probe(path).filter { !it.external }
    suspend fun videoPackets(path: String): Long? = FfmpegRunner.videoPacketCount(path)
    /** FR-8 — the title's identity moves to [newPath] (stored path, Jellyfin, watch data, Radarr, artwork). */
    suspend fun moveIdentity(item: MediaItem, oldPath: String, newPath: String): ConversionIdentityResult
}

sealed class ConvertOutcome {
    data class Done(val identity: ConversionIdentityResult) : ConvertOutcome()
    /** A copy or a rename did not finish, or a cancel arrived; [step] is where. The original is as it was. */
    data class Failed(val code: String, val step: Int, val message: String) : ConvertOutcome()
    /** The re-check in the job found it unsafe (FR-5) — nothing was written to the library. */
    data class Blocked(val block: ConversionBlock, val step: Int) : ConvertOutcome()
    data class Cancelled(val step: Int) : ConvertOutcome()
}

/**
 * DEB-2051 (FR-5, FR-7) — converts one AVI film into an MKV that carries the requested languages, in three steps, and
 * keeps the original in the hidden work folder. Never rewrites the original: it is renamed, not changed, so its inode
 * and bytes are the ones it had (a hard link elsewhere stays valid).
 *
 * Holds [MediaFileLock] on the source for the whole run. Every failure leaves the original where and as it was and
 * removes the work file; a clean ffmpeg exit with no tag is a failure (FR-7 step 2), never a success.
 */
class ContainerConverter(private val ports: ConversionPorts) {

    /** FR-5 — the first reason this file may not be converted, or null. Asked by the preview, by Start, and again in step 3. */
    suspend fun safety(item: MediaItem): ConversionBlock? {
        val path = item.path
        val seeding = ports.seeding(path)
        val storedExists = ports.exists(path)
        val sourceBytes = ports.sizeOf(path)
        return ContainerConversion.firstBlock(
            storedPath = path,
            storedExists = storedExists,
            jellyfinPath = if (storedExists) ports.jellyfinPath(item) else null,
            seeding = when (seeding) { is SeedingCheckResult.Blocked -> ContainerConversion.Seeding.BLOCKED; is SeedingCheckResult.Unreachable -> ContainerConversion.Seeding.UNREACHABLE; else -> ContainerConversion.Seeding.ALLOWED },
            seedingMessage = when (seeding) { is SeedingCheckResult.Blocked -> seeding.message; is SeedingCheckResult.Unreachable -> "qBittorrent unreachable: ${seeding.reason}"; else -> null },
            destinationExists = ports.exists(ContainerConversion.destinationOf(path)),
            keptExists = ports.exists(ContainerConversion.keptPathOf(path)),
            sourceBytes = sourceBytes,
            freeBytes = ports.freeBytes(path.substringBeforeLast('/')),
        )
    }

    suspend fun run(
        item: MediaItem,
        requested: List<ConversionRequested>,
        onStep: (Int) -> Unit,
        onProgress: (pct: Double, speed: String?, etaSeconds: Long?) -> Unit,
        isCancelled: () -> Boolean,
    ): ConvertOutcome = MediaFileLock.withLock(item.path) { runLocked(item, requested, onStep, onProgress, isCancelled) }

    private suspend fun runLocked(
        item: MediaItem,
        requested: List<ConversionRequested>,
        onStep: (Int) -> Unit,
        onProgress: (pct: Double, speed: String?, etaSeconds: Long?) -> Unit,
        isCancelled: () -> Boolean,
    ): ConvertOutcome {
        val source = item.path
        val dest = ContainerConversion.destinationOf(source)
        val work = ContainerConversion.workPathOf(source)
        val kept = ContainerConversion.keptPathOf(source)
        fun cleanup() { ports.remove(work); FfmpegRunner.removeWorkDirIfEmpty(source) }

        // ── 1 — copy the streams into an MKV, in the hidden folder ──
        onStep(1)
        safety(item)?.let { return ConvertOutcome.Blocked(it, 1) }
        val streamIndexOf = item.tracks.associate { it.specifier to it.streamIndex }
        val languages = requested.map { r ->
            val index = streamIndexOf[r.specifier] ?: return ConvertOutcome.Failed(ContainerConversion.JobDetail.TOOL, 1, "Track ${r.specifier} is no longer in the file")
            val iso3 = LanguageResolver.toIso6392(r.language) ?: return ConvertOutcome.Failed(ContainerConversion.JobDetail.TOOL, 1, "No ISO-639-2 code for '${r.language}'")
            Triple(r.specifier, index, iso3)
        }
        val ok = FfmpegRunner.runConvertTracked(copyCommand(source, work, languages.map { it.second to it.third }), FfmpegRunner.probeDurationSeconds(source), onProgress)
        if (isCancelled()) { cleanup(); return ConvertOutcome.Cancelled(1) }
        if (!ok) { cleanup(); return ConvertOutcome.Failed(ContainerConversion.JobDetail.TOOL, 1, "ffmpeg did not finish copying the streams") }

        // ── 2 — re-check the new file; a clean exit that fails any of this is the failed-unverified outcome ──
        onStep(2)
        val problems = verify(source, work, languages.map { it.second to it.third })
        if (problems.isNotEmpty()) {
            cleanup()
            return ConvertOutcome.Failed(ContainerConversion.JobDetail.UNVERIFIED, 2, problems.joinToString("; "))
        }
        if (isCancelled()) { cleanup(); return ConvertOutcome.Cancelled(2) }

        // ── 3 — put the new file in place: asked again, then the new file in first, then the original aside ──
        onStep(3)
        safety(item)?.let { cleanup(); return ConvertOutcome.Blocked(it, 3) }
        if (!ports.rename(work, dest)) { cleanup(); return ConvertOutcome.Failed(ContainerConversion.JobDetail.TOOL, 3, "Couldn't put the new file in place; the original is untouched") }
        if (!ports.rename(source, kept)) {
            val undone = ports.rename(dest, work)
            cleanup()
            if (!undone) Logger.error("conversion: could not undo $dest after the original would not move — both files are in the folder", "jobs")
            return ConvertOutcome.Failed(ContainerConversion.JobDetail.TOOL, 3,
                if (undone) "Couldn't set the original aside; the new file was taken back and the original is untouched"
                else "Couldn't set the original aside, and the new file couldn't be taken back — both are in the folder")
        }
        FfmpegRunner.removeWorkDirIfEmpty(source)   // fails harmlessly: the kept original is in it
        val identity = try {
            ports.moveIdentity(item, source, dest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The files are in place and verified; what failed is the bookkeeping. Say so rather than undo a good conversion.
            Logger.warn("conversion: identity move for ${item.id} threw: ${e.message}", "jobs")
            ConversionIdentityResult(jellyfinFound = false, radarrTaken = null, steps = listOf(LooseStep("records", false, e.message ?: "failed")))
        }
        return ConvertOutcome.Done(identity)
    }

    /** FR-7 step 2 — everything the new file must be, against the original. Empty = verified. */
    internal suspend fun verify(source: String, work: String, languages: List<Pair<Int, String>>): List<String> {
        val before = ports.probe(source)
        val after = ports.probe(work)
        val problems = ArrayList<String>()
        if (after.isEmpty()) return listOf("the new file can't be read")
        fun shape(t: List<Track>) = t.map { it.kind to it.codec }
        if (shape(before) != shape(after)) problems += "the streams differ from the original (${shape(after).size} against ${shape(before).size})"
        val hasVideo = before.any { it.kind == TrackKind.VIDEO }
        if (hasVideo) {
            val a = ports.videoPackets(source); val b = ports.videoPackets(work)
            if (a == null || b == null) problems += "the video packets couldn't be counted"
            else if (a != b) problems += "the video has $b packets, the original $a"
        }
        val d0 = before.fileDurationMsOrNull(); val d1 = after.fileDurationMsOrNull()
        if (hasVideo) {
            if (d0 == null || d1 == null) problems += "the duration couldn't be read"
            else if (kotlin.math.abs(d0 - d1) > 1_000L) problems += "the duration is ${d1 / 1000} s, the original ${d0 / 1000} s"
        }
        for ((index, iso3) in languages) {
            val got = after.firstOrNull { it.streamIndex == index }?.language
            if (got == null || LanguageResolver.normalize(got) != LanguageResolver.normalize(iso3)) problems += "no language tag after the re-check (stream $index shows '${got ?: "none"}')"
        }
        return problems
    }

    private fun List<Track>.fileDurationMsOrNull(): Long? = firstOrNull { it.kind == TrackKind.VIDEO && (it.durationMs ?: 0L) > 0L }?.durationMs

    companion object {
        private fun q(path: String) = "'${path.replace("'", "'\\''")}'"

        /**
         * FR-7 step 1 — the one command: make the hidden folder (owner and mode copied from the directory), copy every
         * stream untouched into [work] with the requested languages, give the new file the original's owner and mode.
         * Missing timestamps are generated, because some AVI codecs carry none. No state ever prints this (FR-9).
         */
        internal fun copyCommand(source: String, work: String, languages: List<Pair<Int, String>>): String {
            val tags = languages.joinToString("") { (index, iso3) -> " -metadata:s:$index language=${iso3.replace(Regex("[^A-Za-z]"), "").take(10)}" }
            val own = "{ chown --reference=${q(source)} ${q(work)} 2>/dev/null; chmod --reference=${q(source)} ${q(work)} 2>/dev/null; true; }"
            return "${WorkFiles.prepareCommand(source)} && ffmpeg -y -nostdin -fflags +genpts -i ${q(source)} -map 0 -c copy$tags ${q(work)} 2>&1 && $own"
        }
    }
}
