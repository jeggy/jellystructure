package dev.jellystructure.tv

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import platform.posix.SIGCONT
import platform.posix.SIGKILL
import platform.posix.SIGSTOP
import platform.posix.access
import platform.posix.F_OK
import platform.posix.fgets
import platform.posix.mkdir
import platform.posix.pclose
import platform.posix.popen
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** R291 — one audio rendition's source: the file on this server's disk, the track, and how long the file is. */
/** [audioOrder]: the track's place among the file's own audio streams (`-map 0:a:<n>`), never Jellyfin's number (R382). */
/** [fmp4]: the video variant's segments are fragmented MP4, so the rendition's must be too (see [segmentsAreFmp4]). */
data class RenditionSource(val path: String, val audioOrder: Int, val channels: Int?, val durationMs: Long, val fmp4: Boolean = false)

/** R291 (FR-R291-12, 2026-10-09) — whether a Jellyfin HLS transcode's segments are fragmented MP4, from its URL
 *  (`SegmentContainer=mp4`/`fmp4`, what Jellyfin answers a client that takes HEVC over HLS, phase 253); anything else
 *  is MPEG-TS, Jellyfin's default. A rendition is written in the same container: MPEG-TS audio beside fMP4 video made
 *  Shaka on the Chromecast fail with 3018 TRANSMUXING_FAILED (captured live on Stue TV). */
internal fun segmentsAreFmp4(transcodingUrl: String): Boolean =
    Regex("[?&]SegmentContainer=([^&]*)", RegexOption.IGNORE_CASE).find(transcodingUrl)?.groupValues?.get(1)
        ?.lowercase()?.let { it == "mp4" || it == "fmp4" } == true

/** R291 — a rendition is 3.000 s segments from 0, as Jellyfin's own audio playlists are; segment k is [3k, 3k+3). */
const val RENDITION_SEGMENT_MS = 3_000L

/**
 * R291 (FR-R291-2, mechanism 1, 2026-09-26) — the rendition's playlist: every segment of the whole file,
 * VOD, so a player can switch to it at any position. A segment's bytes are made on demand ([AudioRenditionJobs]).
 */
internal fun renditionPlaylist(durationMs: Long, fmp4: Boolean = false): String {
    val count = ((durationMs + RENDITION_SEGMENT_MS - 1) / RENDITION_SEGMENT_MS).coerceAtLeast(1)
    val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:${if (fmp4) 7 else 3}\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
    // R291 (FR-R291-12) — fMP4 segments share one init segment (identical for every job, see [renditionCommand]).
    if (fmp4) out.append("#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n")
    for (k in 0 until count) {
        val len = if (k == count - 1) durationMs - k * RENDITION_SEGMENT_MS else RENDITION_SEGMENT_MS
        out.append("#EXTINF:").append(len / 1000).append('.').append((len % 1000).toString().padStart(3, '0')).append(",\n")
        out.append(renditionSegmentName(k.toInt(), fmp4)).append('\n')
    }
    return out.append("#EXT-X-ENDLIST\n").toString()
}

/** R291 — segment [k]'s file name in a rendition playlist (and on disk, with an `s` before it). */
internal fun renditionSegmentName(k: Int, fmp4: Boolean): String = if (fmp4) "$k.m4s" else "$k.ts"

/**
 * R291 — the ffmpeg command for one rendition job, from segment [startSegment] onward. Jellyfin's own audio-only
 * job, with the two things its audio endpoint never adds (measured 2026-09-26, and in its source: an audio-only
 * request is never mapped): `-map 0:<track>` — the track the viewer picked, not ffmpeg's default (the one with
 * the most channels) — and `-sn`, because an unmapped subtitle stream in the HLS muxer cut hundreds of empty
 * segments. The timing arguments are Jellyfin's (`-copyts -avoid_negative_ts disabled`, `-max_delay 5000000`,
 * 3 s HLS): measured, segment 100 starts at 309.979 s against the video variant's 310.000 s — a 21 ms lead,
 * half of Jellyfin's own. `-hls_flags temp_file`: a segment exists only once it is whole. [codec] is the one
 * the video variant's own audio is in (see [renditionAudioCodec]).
 *
 * R291 (FR-R291-12) — fMP4 when the video is fMP4: `use_editlist=0` makes every job's `init.mp4` byte-identical
 * (an edit list records where the job started), so one init serves every job; the price is that ffmpeg 5.1 then
 * starts each job's fragment times (`tfdt`) at 0, which [shiftTfdt] corrects as the segment is served.
 */
internal fun renditionCommand(src: RenditionSource, codec: String, startSegment: Int, dir: String): String {
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    val channels = when (codec) {
        "mp3" -> 2
        else -> (src.channels ?: 2).coerceIn(1, 6)
    }
    val encoder = when (codec) {
        "ac3" -> "ac3"
        "eac3" -> "eac3"
        "mp3" -> "libmp3lame"
        else -> "aac"
    }
    val bitrate = when {
        codec == "mp3" -> "320k"
        channels > 2 -> "640k"
        else -> "192k"
    }
    val startMs = startSegment * RENDITION_SEGMENT_MS
    return "ffmpeg -nostdin -hide_banner -loglevel error -probesize 50M -analyzeduration 10M " +
        "-ss ${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')} -i ${q(src.path)} " +
        "-map 0:a:${src.audioOrder} -sn -dn -vn -map_metadata -1 -map_chapters -1 " +
        "-c:a $encoder -b:a $bitrate -ac $channels " +
        "-copyts -avoid_negative_ts disabled -max_muxing_queue_size 2048 " +
        "-f hls -max_delay 5000000 -hls_time 3 " +
        (if (src.fmp4) "-hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_options use_editlist=0 " else "-hls_segment_type mpegts ") +
        "-hls_flags temp_file -start_number $startSegment -hls_segment_filename ${q("$dir/s%d.${if (src.fmp4) "m4s" else "ts"}")} -hls_playlist_type vod -hls_list_size 0 " +
        "-progress pipe:1 -nostats -y ${q("$dir/p.m3u8")}"
}

/**
 * R291 — this server's own audio renditions: one ffmpeg per (stream, track), started when a player asks for a
 * segment and kept going only as far as the player is likely to need.
 *
 * A job starts at the requested segment and runs forward at 5–7× real time (TrueHD/AC3 → AAC 5.1, measured in
 * the production container: first segment 0.6–0.8 s cold). It is paused (SIGSTOP) once it is
 * [renditionPauseAhead] segments ahead of the last request and resumed when requests close in, so a two-hour
 * film is not encoded whole for a viewer who listens for a minute — and a picker's warm, one request that may
 * never be followed, reads only a few segments of an 80 Mbps file, not a minute of it. A request outside what the job can reach soon (a seek) replaces
 * it with one starting there. Idle for [IDLE_MS], or stopped with its playback (phase 180), it is killed and
 * its segments deleted; old segments go as the player passes them.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioRenditionJobs(private val root: String = "/tmp/js-renditions") {
    private class Job(val key: String, val dir: String, val startSegment: Int, val fmp4: Boolean = false) {
        val ext = if (fmp4) "m4s" else "ts"
        /** fMP4 — what [shiftTfdt] adds to this job's fragment times (its timescale is read from its init once). */
        var tfdtShift: Long? = null
        @Volatile var pid: Int = -1
        @Volatile var exited = false
        @Volatile var stopped = false
        @Volatile var paused = false
        @Volatile var highest = startSegment - 1
        @Volatile var lastRequest = startSegment
        @Volatile var lastRequestAtMs = nowMs()
        @Volatile var requests = 0
    }

    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val inits = mutableMapOf<String, ByteArray>()
    // Each job's reader blocks on its ffmpeg's progress output for the job's life: its own threads.
    private val dispatcher = newFixedThreadPoolContext(MAX_JOBS + 1, "audio-renditions")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        mkdir(root, 0x1C0u)   // 0700
        scope.launch {
            while (true) {
                delay(15_000)
                val idle = mutex.withLock { jobs.values.filter { nowMs() - it.lastRequestAtMs > IDLE_MS }.onEach { jobs.remove(it.key) } }
                idle.forEach { stop(it, "idle") }
            }
        }
    }

    /** Segment [k] of [key]'s rendition, whole, or null when it could not be made in time. */
    suspend fun segment(key: String, src: RenditionSource, codec: String, k: Int): ByteArray? {
        val job = mutex.withLock {
            var j = jobs[key]
            if (j != null) refresh(j)
            if (j == null || !j.reaches(k)) {
                j?.let { old -> jobs.remove(old.key); scope.launch { stop(old, "replaced by a request for segment $k") } }
                if (jobs.size >= MAX_JOBS) jobs.values.minByOrNull { it.lastRequestAtMs }?.let { lru -> jobs.remove(lru.key); scope.launch { stop(lru, "the oldest of $MAX_JOBS") } }
                j = start(key, src, codec, k)
                jobs[key] = j
            }
            j.lastRequest = k
            j.lastRequestAtMs = nowMs()
            j.requests++
            if (j.paused && j.highest - k < RESUME_AHEAD && j.pid > 0) { platform.posix.kill(j.pid, SIGCONT); j.paused = false }
            j
        }
        val file = "${job.dir}/s$k.${job.ext}"
        val deadline = nowMs() + SEGMENT_WAIT_MS
        while (nowMs() < deadline) {
            if (access(file, F_OK) == 0) {
                if (k - DROP_BEHIND >= job.startSegment) unlink("${job.dir}/s${k - DROP_BEHIND}.${job.ext}")
                return runCatching { FileIo.readBytes(Path(file)) }.getOrNull()?.let { served(job, it) }
            }
            if (job.exited || job.stopped) return if (access(file, F_OK) == 0) runCatching { FileIo.readBytes(Path(file)) }.getOrNull()?.let { served(job, it) } else null
            delay(30)
        }
        Logger.warn("audio rendition $key: segment $k not ready in ${SEGMENT_WAIT_MS}ms", "tv")
        return null
    }

    /** R291 (FR-R291-12) — the fMP4 rendition's init segment: kept once read (every job writes the same bytes), else
     *  from the running job, else from a job started at the start. Null when it could not be made in time. */
    suspend fun init(key: String, src: RenditionSource, codec: String): ByteArray? {
        inits[key]?.let { return it }
        val job = mutex.withLock {
            jobs[key] ?: start(key, src, codec, 0).also { jobs[key] = it }
        }
        val deadline = nowMs() + SEGMENT_WAIT_MS
        while (nowMs() < deadline) {
            if (access("${job.dir}/init.mp4", F_OK) == 0) {
                val bytes = runCatching { FileIo.readBytes(Path("${job.dir}/init.mp4")) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) { mutex.withLock { inits[key] = bytes }; return bytes }
            }
            if (job.exited || job.stopped) break
            delay(30)
        }
        Logger.warn("audio rendition $key: init segment not ready in ${SEGMENT_WAIT_MS}ms", "tv")
        return null
    }

    /** A segment as the player gets it: an fMP4 one from a job that started past 0 has its times moved to the file's. */
    private fun served(job: Job, bytes: ByteArray): ByteArray {
        if (!job.fmp4 || job.startSegment == 0) return bytes
        val shift = job.tfdtShift ?: run {
            val timescale = runCatching { FileIo.readBytes(Path("${job.dir}/init.mp4")) }.getOrNull()?.let { mdhdTimescale(it) } ?: return bytes
            (job.startSegment * RENDITION_SEGMENT_MS * timescale / 1000).also { job.tfdtShift = it }
        }
        return shiftTfdt(bytes, shift)
    }

    /** Phase 180 — every job of [streamId]'s renditions, with its playback. */
    suspend fun stopStream(streamId: String) {
        val gone = mutex.withLock {
            inits.keys.removeAll { it.startsWith("$streamId:") }
            jobs.values.filter { it.key.startsWith("$streamId:") }.onEach { jobs.remove(it.key) }
        }
        gone.forEach { stop(it, "playback stopped") }
    }

    private fun Job.reaches(k: Int): Boolean = !stopped && k >= startSegment && k <= highest + REACH_AHEAD && !(exited && k > highest)

    private fun refresh(j: Job) { while (access("${j.dir}/s${j.highest + 1}.${j.ext}", F_OK) == 0) j.highest++ }

    private suspend fun start(key: String, src: RenditionSource, codec: String, k: Int): Job {
        val dir = "$root/${key.replace(':', '-')}-$k-${nowMs()}"
        mkdir(dir, 0x1C0u)
        val job = Job(key, dir, k, src.fmp4)
        val cmd = renditionCommand(src, codec, k, dir)
        Logger.info("audio rendition $key: from segment $k — $cmd", "tv")
        scope.launch {
            memScoped {
                val pipe = popen("echo \$\$; exec $cmd 2>${"'" + dir + "/ffmpeg.log'"}", "r")
                if (pipe == null) { job.exited = true; return@memScoped }
                val buf = allocArray<ByteVar>(512)
                if (fgets(buf, 512, pipe) != null) job.pid = buf.toKString().trim().toIntOrNull() ?: -1
                if (job.stopped && job.pid > 0) platform.posix.kill(job.pid, SIGKILL)
                while (fgets(buf, 512, pipe) != null) {
                    if (!buf.toKString().startsWith("progress=")) continue
                    refresh(job)
                    if (!job.paused && !job.stopped && job.highest - job.lastRequest > renditionPauseAhead(job.requests) && job.pid > 0) {
                        job.paused = true
                        platform.posix.kill(job.pid, SIGSTOP)
                    }
                }
                val rc = pclose(pipe)
                job.exited = true
                refresh(job)
                if (rc != 0 && !job.stopped) Logger.warn("audio rendition $key: ffmpeg exit $rc (see $dir/ffmpeg.log)", "tv")
            }
        }
        return job
    }

    private suspend fun stop(j: Job, why: String) {
        j.stopped = true
        if (j.pid > 0 && !j.exited) platform.posix.kill(j.pid, SIGKILL)
        scope.launch {
            // Let the reader see the exit before the directory goes.
            var waited = 0
            while (!j.exited && waited < 5_000) { delay(50); waited += 50 }
            removeFlat(j.dir)
        }
        Logger.info("audio rendition ${j.key}: stopped ($why)", "tv")
    }

    private fun removeFlat(dir: String) {
        val d = platform.posix.opendir(dir) ?: return
        try {
            while (true) {
                val e = platform.posix.readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name != "." && name != "..") unlink("$dir/$name")
            }
        } finally { platform.posix.closedir(d) }
        rmdir(dir)
    }

    companion object {
        const val MAX_JOBS = 8
        const val PAUSE_AHEAD = 20         // 1 min of audio ahead of the last request: stop encoding
        const val WARM_AHEAD = 3           // …or 9 s, while the job has served only the picker's warm
        const val RESUME_AHEAD = 10        // requests within 30 s of what exists: encode on
        const val REACH_AHEAD = 10         // a request up to 30 s past what exists waits for this job
        const val DROP_BEHIND = 20         // a segment 1 min behind the request is deleted
        const val SEGMENT_WAIT_MS = 15_000L
        const val IDLE_MS = 90_000L
    }
}

/** R291 — how far a job may run ahead of its last request: a few segments while it has served only one request
 *  (the picker's warm — the viewer may never pick it), a minute once playback is reading it. Every segment of
 *  an interleaved file is read with the video around it (80 Mbps here), so this is disk, not just CPU. */
internal fun renditionPauseAhead(requestsServed: Int): Int =
    if (requestsServed < 2) AudioRenditionJobs.WARM_AHEAD else AudioRenditionJobs.PAUSE_AHEAD

/** R291 (FR-R291-12) — the media timescale (units per second) of an fMP4 init segment's first track, from its `mdhd`. */
internal fun mdhdTimescale(init: ByteArray): Long? {
    val i = indexOf(init, "mdhd") ?: return null
    // box: size(4) type(4) version(1) flags(3), then the times (4+4, or 8+8 in version 1), then the timescale
    val version = init.getOrNull(i + 8)?.toInt() ?: return null
    val at = i + 12 + if (version == 1) 16 else 8
    return readUInt(init, at, 4)?.takeIf { it > 0 }
}

/**
 * R291 (FR-R291-12) — [segment] with every fragment's decode time (`moof/traf/tfdt`, and the `sidx`'s start) moved by [shift] timescale units.
 * A job started at segment k writes its first fragment at 0 (see [renditionCommand]); the player needs the time the
 * same audio has in a job started at 0, or a seek's audio lands at the film's start.
 */
internal fun shiftTfdt(segment: ByteArray, shift: Long): ByteArray {
    if (shift == 0L) return segment
    val out = segment.copyOf()
    fun walk(from: Int, to: Int) {
        var p = from
        while (p + 8 <= to) {
            var size = readUInt(out, p, 4) ?: return
            var header = 8
            if (size == 1L) { size = readUInt(out, p + 8, 8) ?: return; header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) return
            val end = (p + size).toInt()
            when (out.decodeToString(p + 4, p + 8)) {
                "moof", "traf" -> walk(p + header, end)
                // tfdt: version/flags, then the time; sidx (same timescale as ffmpeg writes it): version/flags,
                // reference id, timescale, then the earliest presentation time.
                "tfdt", "sidx" -> {
                    val width = if (out[p + header].toInt() == 1) 8 else 4
                    val at = p + header + if (out.decodeToString(p + 4, p + 8) == "tfdt") 4 else 12
                    val time = (readUInt(out, at, width) ?: return) + shift
                    for (b in 0 until width) out[at + b] = (time ushr (8 * (width - 1 - b))).toByte()
                }
            }
            p = end
        }
    }
    walk(0, out.size)
    return out
}

private fun readUInt(b: ByteArray, at: Int, width: Int): Long? {
    if (at < 0 || at + width > b.size) return null
    var v = 0L
    for (i in 0 until width) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
    return v
}

private fun indexOf(b: ByteArray, type: String): Int? {
    val t = type.encodeToByteArray()
    for (i in 0..b.size - t.size) if ((t.indices).all { b[i + it] == t[it] }) return i - 4
    return null
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
