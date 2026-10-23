package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.media.FileStamp
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.ops.GateClass
import dev.jellystructure.subtitles.SubtitleReferences
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.time.Clock

/**
 * DEB-2205 (FR-6) — every embedded text subtitle of a video, as WebVTT, extracted by jellystructure and served from
 * `GET /api/tv/subtitles/{item}/{containerIndex}.vtt` instead of Jellyfin's `Stream.vtt`.
 *
 * Why not Jellyfin's: its extraction cache is keyed by the stream index **it** numbers, and a sidecar added later shifts every
 * embedded index by one, so index *n* is answered with the old *n*'s file — the wrong language, silently — and an index that
 * missed the cache made Jellyfin read a 53 GB file for four minutes while the player waited on it (the 2026-10-22 play that
 * never passed 0:02).
 *
 * The key is **path + size + mtime + the container's own stream index** (ffprobe's `Track.streamIndex`, which no sidecar
 * can move). A changed file changes its size or mtime and so misses; the old files of that path are deleted by the next
 * extraction. One ffmpeg pass reads the file once for all its text tracks, at background priority.
 *
 * Layout: `<root>/<sha256(path)[0..32]>/<size>-<mtime>-<containerIndex>.vtt`.
 */
class EmbeddedSubtitleCache(
    private val root: String,
    private val scope: CoroutineScope,
    private val stampOf: (String) -> FileStamp? = { FileIntegrityService.stampOf(it) },
    /** Runs ONE ffmpeg pass: extracts [containerIndices] of [videoPath] into [outDir] as `<index>.vtt`; false when it failed. */
    private val extract: suspend (videoPath: String, containerIndices: List<Int>, outDir: String) -> Boolean = ::ffmpegExtractWebvtt,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val inflight = HashMap<String, Deferred<Boolean>>()
    private val failedAtMs = kotlin.concurrent.AtomicReference<Map<String, Long>>(emptyMap())

    private fun noteFailure(videoPath: String, atMs: Long?) {
        while (true) {
            val old = failedAtMs.value
            val next = if (atMs == null) old - videoPath else (old + (videoPath to atMs)).let { m -> if (m.size > 500) m.entries.drop(m.size - 500).associate { it.toPair() } else m }
            if (failedAtMs.compareAndSet(old, next)) return
        }
    }

    /** What a request for one track finds. */
    sealed interface Lookup {
        data class Ready(val file: String) : Lookup
        /** Not extracted yet (a pass is, or has just been, started): answer 202 and let the client ask again. */
        data object Pending : Lookup
        /** The pass failed, or the file is not on this server's disk or holds no such text track. */
        data object Unavailable : Lookup
    }

    /** The cached WebVTT for [containerIndex] of [videoPath] as the file is now, or null. */
    fun cachedFile(videoPath: String, containerIndex: Int): String? {
        val stamp = stampOf(videoPath) ?: return null
        val f = "$root/${relativePath(videoPath, stamp.size, stamp.mtime, containerIndex)}"
        return f.takeIf { SystemFileSystem.exists(Path(it)) }
    }

    /**
     * A request for one track: the file when it exists; otherwise starts [videoPath]'s pass (once) in the background and
     * says [Lookup.Pending]. [tracks] are the store's tracks of the file: the pass extracts every text track in them, and a
     * request for an index that is not one of them is [Lookup.Unavailable].
     */
    fun lookup(videoPath: String, tracks: List<Track>, containerIndex: Int): Lookup {
        if (containerIndex !in textContainerIndices(tracks)) return Lookup.Unavailable
        cachedFile(videoPath, containerIndex)?.let { return Lookup.Ready(it) }
        if (recentlyFailed(videoPath)) return Lookup.Unavailable
        kick(videoPath, tracks)
        return Lookup.Pending
    }

    /** Starts the pass for [videoPath] if none is running; never waits for it. */
    fun kick(videoPath: String, tracks: List<Track>) {
        scope.launch(GateClass.BACKGROUND) { ensure(videoPath, tracks) }
    }

    /**
     * Makes sure every text track of [videoPath] is cached (one pass, joined by concurrent callers). True when all are.
     * A file without text tracks, or with all of them cached, does no work.
     */
    suspend fun ensure(videoPath: String, tracks: List<Track>): Boolean {
        val wanted = textContainerIndices(tracks)
        if (wanted.isEmpty()) return true
        val stamp = stampOf(videoPath) ?: return false
        if (wanted.all { SystemFileSystem.exists(Path("$root/${relativePath(videoPath, stamp.size, stamp.mtime, it)}")) }) return true
        val run = mutex.withLock {
            inflight[videoPath] ?: scope.async(GateClass.BACKGROUND) { pass(videoPath, stamp, wanted) }.also { inflight[videoPath] = it }
        }
        return try { run.await() } finally { mutex.withLock { if (inflight[videoPath] === run) inflight.remove(videoPath) } }
    }

    private suspend fun pass(videoPath: String, stamp: FileStamp, wanted: List<Int>): Boolean {
        val dir = "$root/${dirName(videoPath)}"
        val tmp = "$dir/.tmp-${stamp.size}-${stamp.mtime}"
        runCatching { SystemFileSystem.createDirectories(Path(tmp)) }
        val startMs = clock()
        val ok = runCatching { extract(videoPath, wanted, tmp) }.getOrElse { Logger.warn("subtitles: extraction threw for $videoPath: ${it.message}", "subtitles"); false }
        // The file changed under the pass (a replace, an upgrade): what was read is not for the new file.
        val after = stampOf(videoPath)
        if (!ok || after == null || after.size != stamp.size || after.mtime != stamp.mtime) {
            removeDir(tmp)
            noteFailure(videoPath, clock())
            Logger.warn("subtitles: extraction of ${wanted.size} text track(s) failed or the file changed — $videoPath", "subtitles")
            return false
        }
        for (i in wanted) {
            val made = Path("$tmp/$i.vtt")
            // ffmpeg exited 0 yet wrote nothing for a track (no cues at all): an empty, valid file, so it is not asked for again.
            if (!SystemFileSystem.exists(made)) FileIo.writeText(made, "WEBVTT\n\n")
            SystemFileSystem.atomicMove(made, Path("$root/${relativePath(videoPath, stamp.size, stamp.mtime, i)}"))
        }
        removeDir(tmp)
        removeStale(dir, stamp)
        noteFailure(videoPath, null)
        Logger.info("subtitles: ${wanted.size} text track(s) of one file extracted to WebVTT in ${clock() - startMs} ms (DEB-2205 FR-6)", "subtitles")
        return true
    }

    private fun recentlyFailed(videoPath: String): Boolean = (failedAtMs.value[videoPath] ?: return false).let { clock() - it < RETRY_AFTER_FAILURE_MS }

    /** Files of other versions of this path (older size or mtime) go: they can never be served again. */
    private fun removeStale(dir: String, current: FileStamp) {
        val keep = "${current.size}-${current.mtime}-"
        runCatching {
            SystemFileSystem.list(Path(dir)).forEach { p ->
                val name = p.name
                if (name.endsWith(".vtt") && !name.startsWith(keep)) SystemFileSystem.delete(p, mustExist = false)
            }
        }
    }

    private fun removeDir(dir: String) {
        runCatching {
            SystemFileSystem.list(Path(dir)).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(Path(dir), mustExist = false)
        }
    }

    companion object {
        const val RETRY_AFTER_FAILURE_MS = 10 * 60_000L

        /** The folder of one video path. */
        fun dirName(videoPath: String): String = sha256Hex(videoPath).take(32)

        /** The file, relative to the cache root: path + size + mtime + the container's own stream index. */
        fun relativePath(videoPath: String, size: Long, mtime: Long, containerIndex: Int): String =
            "${dirName(videoPath)}/$size-$mtime-$containerIndex.vtt"

        /** What the ticket's `SubTrack.url` is for an embedded text track: relative to the server, like the encoder's `/api/tv/stream/…`. */
        fun urlFor(jellyfinId: String, containerIndex: Int): String = "/api/tv/subtitles/$jellyfinId/$containerIndex.vtt"

        /** The container indices (ffprobe's) of the file's embedded **text** subtitle tracks. */
        fun textContainerIndices(tracks: List<Track>): List<Int> =
            tracks.filter { it.kind == TrackKind.SUBTITLE && !it.external && isTextSubCodec(it.codec) }.map { it.streamIndex }

        /**
         * The container index of one of Jellyfin's embedded text subtitle [stream], read from the store's [tracks] by position
         * among the internal subtitle streams (Jellyfin numbers sidecars first, FR-301-1). Null when the store and Jellyfin do not
         * agree on the file — the ticket then keeps Jellyfin's URL for that track rather than guess.
         */
        fun containerIndexOf(stream: JellyfinMediaStream, tracks: List<Track>, streams: List<JellyfinMediaStream>): Int? =
            SubtitleReferences.trackForJellyfinStream(stream, tracks, streams)?.takeIf { it.streamIndex in textContainerIndices(tracks) }?.streamIndex
    }
}

/** One ffmpeg pass, `nice`/`ionice -c3`: every index in [containerIndices] to `<outDir>/<index>.vtt` as WebVTT. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun ffmpegExtractWebvtt(videoPath: String, containerIndices: List<Int>, outDir: String): Boolean {
    val q = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
    val maps = containerIndices.joinToString(" ") { "-map 0:$it -c:s webvtt ${q("$outDir/$it.vtt")}" }
    val cmd = "nice -n 19 ionice -c3 ffmpeg -nostdin -y -v error -i ${q(videoPath)} $maps 2>&1"
    Logger.info("subtitles: ffmpeg pass for ${containerIndices.size} text track(s)", "subtitles")
    return dev.jellystructure.ops.ProcessGate.withPermit {
        memScoped {
            val pipe = popen(cmd, "r")
            if (pipe == null) false
            else {
                val sb = StringBuilder()
                val buf = allocArray<ByteVar>(4096)
                while (fgets(buf, 4096, pipe) != null) if (sb.length < 2_000) sb.append(buf.toKString())
                val rc = pclose(pipe)
                if (rc != 0) Logger.warn("subtitles: ffmpeg exit $rc: ${sb.toString().take(500)}", "subtitles")
                rc == 0
            }
        }
    }
}
