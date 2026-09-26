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
data class RenditionSource(val path: String, val streamIndex: Int, val channels: Int?, val durationMs: Long)

/** R291 — a rendition is 3.000 s segments from 0, as Jellyfin's own audio playlists are; segment k is [3k, 3k+3). */
const val RENDITION_SEGMENT_MS = 3_000L

/**
 * R291 (FR-R291-2, mechanism 1, 2026-09-26) — the rendition's playlist: every segment of the whole file,
 * VOD, so a player can switch to it at any position. A segment's bytes are made on demand ([AudioRenditionJobs]).
 */
internal fun renditionPlaylist(durationMs: Long): String {
    val count = ((durationMs + RENDITION_SEGMENT_MS - 1) / RENDITION_SEGMENT_MS).coerceAtLeast(1)
    val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
    for (k in 0 until count) {
        val len = if (k == count - 1) durationMs - k * RENDITION_SEGMENT_MS else RENDITION_SEGMENT_MS
        out.append("#EXTINF:").append(len / 1000).append('.').append((len % 1000).toString().padStart(3, '0')).append(",\n")
        out.append(k).append(".ts\n")
    }
    return out.append("#EXT-X-ENDLIST\n").toString()
}

/**
 * R291 — the ffmpeg command for one rendition job, from segment [startSegment] onward. Jellyfin's own audio-only
 * job, with the two things its audio endpoint never adds (measured 2026-09-26, and in its source: an audio-only
 * request is never mapped): `-map 0:<track>` — the track the viewer picked, not ffmpeg's default (the one with
 * the most channels) — and `-sn`, because an unmapped subtitle stream in the HLS muxer cut hundreds of empty
 * segments. The timing arguments are Jellyfin's (`-copyts -avoid_negative_ts disabled`, `-max_delay 5000000`,
 * 3 s HLS): measured, segment 100 starts at 309.979 s against the video variant's 310.000 s — a 21 ms lead,
 * half of Jellyfin's own. `-hls_flags temp_file`: a segment exists only once it is whole. [codec] is the one
 * the video variant's own audio is in (see [renditionAudioCodec]).
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
        "-map 0:${src.streamIndex} -sn -dn -vn -map_metadata -1 -map_chapters -1 " +
        "-c:a $encoder -b:a $bitrate -ac $channels " +
        "-copyts -avoid_negative_ts disabled -max_muxing_queue_size 2048 " +
        "-f hls -max_delay 5000000 -hls_time 3 -hls_segment_type mpegts -hls_flags temp_file " +
        "-start_number $startSegment -hls_segment_filename ${q("$dir/s%d.ts")} -hls_playlist_type vod -hls_list_size 0 " +
        "-progress pipe:1 -nostats -y ${q("$dir/p.m3u8")}"
}

/**
 * R291 — this server's own audio renditions: one ffmpeg per (stream, track), started when a player asks for a
 * segment and kept going only as far as the player is likely to need.
 *
 * A job starts at the requested segment and runs forward at 5–7× real time (TrueHD/AC3 → AAC 5.1, measured in
 * the production container: first segment 0.6–0.8 s cold). It is paused (SIGSTOP) once it is [PAUSE_AHEAD]
 * segments ahead of the last request and resumed when requests close in, so a two-hour film is not encoded
 * whole for a viewer who listens for a minute. A request outside what the job can reach soon (a seek) replaces
 * it with one starting there. Idle for [IDLE_MS], or stopped with its playback (phase 180), it is killed and
 * its segments deleted; old segments go as the player passes them.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioRenditionJobs(private val root: String = "/tmp/js-renditions") {
    private class Job(val key: String, val dir: String, val startSegment: Int) {
        @Volatile var pid: Int = -1
        @Volatile var exited = false
        @Volatile var stopped = false
        @Volatile var paused = false
        @Volatile var highest = startSegment - 1
        @Volatile var lastRequest = startSegment
        @Volatile var lastRequestAtMs = nowMs()
    }

    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
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
            if (j.paused && j.highest - k < RESUME_AHEAD && j.pid > 0) { platform.posix.kill(j.pid, SIGCONT); j.paused = false }
            j
        }
        val file = "${job.dir}/s$k.ts"
        val deadline = nowMs() + SEGMENT_WAIT_MS
        while (nowMs() < deadline) {
            if (access(file, F_OK) == 0) {
                if (k - DROP_BEHIND >= job.startSegment) unlink("${job.dir}/s${k - DROP_BEHIND}.ts")
                return runCatching { FileIo.readBytes(Path(file)) }.getOrNull()
            }
            if (job.exited || job.stopped) return if (access(file, F_OK) == 0) runCatching { FileIo.readBytes(Path(file)) }.getOrNull() else null
            delay(30)
        }
        Logger.warn("audio rendition $key: segment $k not ready in ${SEGMENT_WAIT_MS}ms", "tv")
        return null
    }

    /** Phase 180 — every job of [streamId]'s renditions, with its playback. */
    suspend fun stopStream(streamId: String) {
        val gone = mutex.withLock { jobs.values.filter { it.key.startsWith("$streamId:") }.onEach { jobs.remove(it.key) } }
        gone.forEach { stop(it, "playback stopped") }
    }

    private fun Job.reaches(k: Int): Boolean = !stopped && k >= startSegment && k <= highest + REACH_AHEAD && !(exited && k > highest)

    private fun refresh(j: Job) { while (access("${j.dir}/s${j.highest + 1}.ts", F_OK) == 0) j.highest++ }

    private suspend fun start(key: String, src: RenditionSource, codec: String, k: Int): Job {
        val dir = "$root/${key.replace(':', '-')}-$k-${nowMs()}"
        mkdir(dir, 0x1C0u)
        val job = Job(key, dir, k)
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
                    if (!job.paused && !job.stopped && job.highest - job.lastRequest > PAUSE_AHEAD && job.pid > 0) {
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
        const val PAUSE_AHEAD = 40         // 2 min of audio ahead of the last request: stop encoding
        const val RESUME_AHEAD = 20        // requests within 1 min of what exists: encode on
        const val REACH_AHEAD = 10         // a request up to 30 s past what exists waits for this job
        const val DROP_BEHIND = 20         // a segment 1 min behind the request is deleted
        const val SEGMENT_WAIT_MS = 15_000L
        const val IDLE_MS = 90_000L
    }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
