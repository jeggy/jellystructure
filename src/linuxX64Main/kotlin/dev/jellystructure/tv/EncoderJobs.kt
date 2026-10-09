package dev.jellystructure.tv

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
import platform.posix.F_OK
import platform.posix.SIGCONT
import platform.posix.SIGKILL
import platform.posix.SIGSTOP
import platform.posix.access
import platform.posix.fgets
import platform.posix.mkdir
import platform.posix.pclose
import platform.posix.popen
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Phase 313 (FR-313-1/-7/-9) — the encoder's processes: one ffmpeg per play ([EncoderPlan]), started at the segment a
 * player first asks for and making every variant (the rungs, then the audio renditions) from that one process.
 *
 * - **Paused** (SIGSTOP) once it is [PAUSE_AHEAD] segments (40 s) ahead of the furthest request, resumed when requests
 *   come within [RESUME_AHEAD] (20 s): a film is never encoded far ahead for nobody. A paused job keeps its NVENC
 *   sessions until it is stopped (the budget counts it).
 * - **A seek** — a request the job cannot reach within [REACH_AHEAD] segments, or one before it started — **replaces**
 *   the job with one starting at that segment. The old job's directory is kept ([KEEP_RETIRED]) so seeking back into
 *   what was already made needs no encode; with `-copyts` and keyframes forced on the file's own 2 s grid the new
 *   job's segments sit beside the old ones.
 * - **Idle** for [IDLE_MS], or stopped with its play (phase 180), the job is killed and everything it wrote deleted. A
 *   sweep at start deletes what a crash left.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncoderJobs(
    private val root: String,
    /** The environment and binary prefix every command runs with (CUDA order and kernel cache, FR-313 313a). */
    private val envPrefix: () -> String = { "" },
    /** 313 (2026-10-09) — told when ffmpeg refuses a plan (exits with an error before its first segment): the source. */
    private val onRefused: (EncoderPlan, Int) -> Unit = { _, _ -> },
) {
    internal class Job(val streamId: String, val dir: String, val startSegment: Int, val variants: Int, val plan: EncoderPlan) {
        @Volatile var pid: Int = -1
        @Volatile var exited = false
        @Volatile var stopped = false
        @Volatile var paused = false
        @Volatile var highest = startSegment - 1
        @Volatile var furthestRequest = startSegment
        @Volatile var lastRequestAtMs = nowMs()
        @Volatile var firstSegmentMs: Long? = null
        /** 313 (2026-10-09) — segments below this were deleted behind the player ([prune]); none of them can be served. */
        @Volatile var prunedBelow = startSegment
        val startedAtMs = nowMs()
        /** Segment [k]'s file in [variant]: every job numbers its files from 0 (see [encoderCommand]). */
        fun file(variant: Int, k: Int): String = "$dir/$variant/s${k - startSegment}.${if (plan.mux == EncoderMux.FMP4) "m4s" else "ts"}"
    }

    /** [refused] — ffmpeg refused this stream's plan: no job is started again for it (the same command would fail the
     *  same way, and a player asking again would loop); its segments answer null until the play ends. */
    private class Stream(var current: Job?, val retired: ArrayDeque<Job> = ArrayDeque(), @Volatile var refused: Boolean = false)

    /** 313 (found live 2026-10-09) — ffmpeg refused [streamId]'s plan (its URLs then answer 410, never a retried 404). */
    suspend fun isRefused(streamId: String): Boolean = mutex.withLock { streams[streamId]?.refused == true }

    private val mutex = Mutex()
    private val streams = mutableMapOf<String, Stream>()
    private val dispatcher = newFixedThreadPoolContext(MAX_JOBS + 1, "encoder-jobs")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        mkdirs(root)
        scope.launch { sweep(root) }
        scope.launch {
            while (true) {
                delay(10_000)
                val idle = mutex.withLock {
                    val ids = streams.filter { (_, s) -> s.current?.let { nowMs() - it.lastRequestAtMs > IDLE_MS } ?: true }.keys.toList()
                    ids.mapNotNull { id -> streams.remove(id)?.let { id to it } }
                }
                idle.forEach { (id, s) -> stopAll(id, s, "idle") }
            }
        }
    }

    /** The running jobs, for the budget (FR-313-8) and `/api/health`. */
    internal suspend fun running(): List<Job> = mutex.withLock { streams.values.mapNotNull { it.current }.filter { !it.stopped } }

    /**
     * The file holding segment [k] of [variant] (`s<k>.m4s` / `.ts`), made on demand; null when it could not be made in
     * [SEGMENT_WAIT_MS]. [plan] is the stream's (fixed) plan.
     */
    suspend fun segment(streamId: String, plan: EncoderPlan, ffmpeg: String, variant: Int, k: Int): String? {
        val job = mutex.withLock {
            val s = streams.getOrPut(streamId) { Stream(null) }
            // Already made by a job of this stream (the current one or a retired one): served without an encode.
            (listOfNotNull(s.current) + s.retired).firstOrNull { k >= it.startSegment && access(it.file(variant, k), F_OK) == 0 }?.let { made ->
                s.current?.let { touch(it, k) }
                return made.file(variant, k)
            }
            if (s.refused) return null
            var j = s.current
            if (j != null) refresh(j)
            if (j == null || !j.reaches(k)) {
                j?.let { old -> s.retired.addFirst(old); old.stopped = true; kill(old); while (s.retired.size > KEEP_RETIRED) s.retired.removeLast().let { gone -> scope.launch { removeTree(gone.dir) } } }
                j = start(streamId, plan, ffmpeg, k)
                s.current = j
                if (j.startSegment != k) Logger.info("encoder $streamId: restarted at segment $k (seek)", "tv")
            }
            touch(j, k)
            j
        }
        val path = job.file(variant, k)
        val deadline = nowMs() + SEGMENT_WAIT_MS
        while (nowMs() < deadline) {
            if (access(path, F_OK) == 0) {
                if (job.firstSegmentMs == null) {
                    job.firstSegmentMs = nowMs() - job.startedAtMs
                    Logger.info("encoder $streamId: first segment in ${job.firstSegmentMs} ms (segment $k, variant $variant)", "tv")
                }
                return path
            }
            if (job.exited) return if (access(path, F_OK) == 0) path else null
            delay(25)
        }
        Logger.warn("encoder $streamId: segment $k of variant $variant not ready in ${SEGMENT_WAIT_MS}ms", "tv")
        return null
    }

    /** The init segment of [variant] (fMP4): whichever job of the stream has written one (they are identical). */
    suspend fun init(streamId: String, plan: EncoderPlan, ffmpeg: String, variant: Int, nearSegment: Int): String? {
        mutex.withLock { streams[streamId]?.let { s -> (listOfNotNull(s.current) + s.retired).firstNotNullOfOrNull { initIn("${it.dir}/$variant") } } }?.let { return it }
        // No job yet (a player reads the init before any segment): start one where it will read.
        segment(streamId, plan, ffmpeg, variant, nearSegment) ?: return null
        return mutex.withLock { streams[streamId]?.current?.let { initIn("${it.dir}/$variant") } }
    }

    /** Phase 180 / FR-313-1 — every job of [streamId], with its play. */
    suspend fun stop(streamId: String, why: String) {
        val s = mutex.withLock { streams.remove(streamId) } ?: return
        stopAll(streamId, s, why)
    }

    private fun touch(j: Job, k: Int) {
        if (k > j.furthestRequest) j.furthestRequest = k
        j.lastRequestAtMs = nowMs()
        if (j.paused && j.highest - j.furthestRequest < RESUME_AHEAD && j.pid > 0) { platform.posix.kill(j.pid, SIGCONT); j.paused = false }
    }

    private fun Job.reaches(k: Int): Boolean = !stopped && !exited && k >= maxOf(startSegment, prunedBelow) && k <= highest + REACH_AHEAD

    /**
     * 313 (2026-10-09) — deletes segments of every variant more than [keepBehind] segments behind the furthest request.
     * Found live: nothing was ever deleted, so a 4-rung HEVC ladder (~33 Mbps written) filled the 4 GB tmpfs after
     * ~16 min of film and a 62-min cast stalled 208 times. A seek back into the deleted range restarts the job
     * ([reaches] counts [Job.prunedBelow]).
     */
    private fun prune(j: Job, furthest: Int, keepBehind: Int) {
        val range = pruneRange(j.prunedBelow, furthest, keepBehind) ?: return
        for (k in range) for (v in 0 until j.variants) unlink(j.file(v, k))
        j.prunedBelow = range.last + 1
    }


    /** Segments are written whole (`temp_file`) and every variant advances together: variant 0 tells how far it is. */
    private fun refresh(j: Job) { while (access(j.file(0, j.highest + 1), F_OK) == 0) j.highest++ }

    private suspend fun start(streamId: String, plan: EncoderPlan, ffmpeg: String, k: Int): Job {
        val dir = "$root/$streamId-$k-${nowMs()}"
        mkdir(dir, 0x1C0u)
        for (v in 0 until plan.variantCount) mkdir("$dir/$v", 0x1C0u)
        val job = Job(streamId, dir, k, plan.variantCount, plan)
        val cmd = encoderCommand(plan, k, dir, ffmpeg)
        Logger.info("encoder $streamId: start at segment $k — ${plan.codec} ${plan.rungs.size} rungs ${plan.rungs.joinToString("/") { "${it.height}p@${it.videoBps / 1000}k" }}, " +
            "${plan.audio.size} audio, ${plan.mux}, card ${plan.cudaDevice ?: "cpu"}${if (plan.tonemaps) ", tone-mapped" else ""}${if (plan.burnSubtitleOrder != null) ", burn-in" else ""}", "tv")
        scope.launch {
            memScoped {
                val pipe = popen("echo \$\$; ${envPrefix()} exec $cmd 2>${"'" + dir + "/ffmpeg.log'"}", "r")
                if (pipe == null) { job.exited = true; return@memScoped }
                val buf = allocArray<ByteVar>(512)
                if (fgets(buf, 512, pipe) != null) job.pid = buf.toKString().trim().toIntOrNull() ?: -1
                if (job.stopped && job.pid > 0) platform.posix.kill(job.pid, SIGKILL)
                var lines = 0
                while (fgets(buf, 512, pipe) != null) {
                    if (!buf.toKString().startsWith("progress=")) continue
                    refresh(job)
                    // 313 (2026-10-09) — keep the work folder bounded: every few progress lines, delete what is far behind.
                    if (++lines % 8 == 0) {
                        mutex.withLock {
                            prune(job, job.furthestRequest, KEEP_BEHIND)
                            streams[streamId]?.retired?.forEach { prune(it, job.furthestRequest, KEEP_BEHIND) }
                        }
                    }
                    if (!job.paused && !job.stopped && job.highest - job.furthestRequest > PAUSE_AHEAD && job.pid > 0) {
                        job.paused = true
                        platform.posix.kill(job.pid, SIGSTOP)
                    }
                }
                val rc = pclose(pipe)
                job.exited = true
                refresh(job)
                if (rc != 0 && !job.stopped) {
                    Logger.warn("encoder $streamId: ffmpeg exit $rc (see $dir/ffmpeg.log)", "tv")
                    // 313 (2026-10-09) — refused before its first segment: never restarted with the same command; the
                    // source goes to Jellyfin from the next play on (the player's own retry asks for a new ticket).
                    if (refusedBeforeFirstSegment(rc, job.highest, job.startSegment)) {
                        mutex.withLock { streams[streamId]?.refused = true }
                        Logger.warn("encoder $streamId: ffmpeg refused this plan — no restart; the file goes to Jellyfin (313)", "tv")
                        runCatching { onRefused(plan, rc) }
                    }
                }
            }
        }
        return job
    }

    private fun kill(j: Job) {
        if (j.pid > 0 && !j.exited) {
            if (j.paused) platform.posix.kill(j.pid, SIGCONT)
            platform.posix.kill(j.pid, SIGKILL)
        }
    }

    private suspend fun stopAll(streamId: String, s: Stream, why: String) {
        val all = listOfNotNull(s.current) + s.retired
        all.forEach { it.stopped = true; kill(it) }
        scope.launch {
            for (j in all) {
                var waited = 0
                while (!j.exited && j.pid > 0 && waited < 5_000) { delay(50); waited += 50 }
                removeTree(j.dir)
            }
        }
        Logger.info("encoder $streamId: stopped ($why)", "tv")
    }

    private fun initIn(dir: String): String? {
        val d = platform.posix.opendir(dir) ?: return null
        try {
            while (true) {
                val e = platform.posix.readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name.startsWith("init") && name.endsWith(".mp4")) return "$dir/$name"
            }
        } finally { platform.posix.closedir(d) }
        return null
    }

    /** Deletes [dir] and its variant directories (one level of subdirectories: `<job>/<variant>/files`). */
    private fun removeTree(dir: String) {
        val d = platform.posix.opendir(dir) ?: return
        val names = mutableListOf<String>()
        try {
            while (true) {
                val e = platform.posix.readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally { platform.posix.closedir(d) }
        for (n in names) {
            val p = "$dir/$n"
            if (unlink(p) != 0) removeTree(p)
        }
        rmdir(dir)
    }

    /** FR-313-9 — what a crash left behind under [root]. */
    private suspend fun sweep(root: String) {
        val d = platform.posix.opendir(root) ?: return
        val names = mutableListOf<String>()
        try {
            while (true) {
                val e = platform.posix.readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally { platform.posix.closedir(d) }
        if (names.isNotEmpty()) Logger.info("encoder: removed ${names.size} leftover job folder(s) from a previous run", "tv")
        names.forEach { removeTree("$root/$it") }
    }

    companion object {
        const val MAX_JOBS = 8
        const val PAUSE_AHEAD = 20       // 40 s of 2 s segments
        const val RESUME_AHEAD = 10      // 20 s
        const val REACH_AHEAD = 10       // a request up to 20 s past what exists waits for this job
        const val KEEP_RETIRED = 2
        const val SEGMENT_WAIT_MS = 15_000L
        /** 60 s of 2 s segments kept behind the furthest request: with 40 s ahead, one 4-rung HEVC stream holds ~420 MB,
         *  so the 4 GB work folder fits more streams than [MAX_JOBS]. A seek further back restarts the job (~1–2 s). */
        const val KEEP_BEHIND = 30
        const val IDLE_MS = 60_000L
    }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** 313 (2026-10-09) — ffmpeg refused a plan: it exited with an error before writing the job's first segment. */
internal fun refusedBeforeFirstSegment(exitCode: Int, highest: Int, startSegment: Int): Boolean = exitCode != 0 && highest < startSegment

/** Creates [path] and its missing parents (0700); true when it exists afterwards and is writable. */
@OptIn(ExperimentalForeignApi::class)
internal fun mkdirs(path: String): Boolean {
    var p = ""
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        p += "/$part"
        if (access(p, F_OK) != 0) mkdir(p, 0x1C0u)
    }
    return access(path, platform.posix.W_OK) == 0
}

/** 313 (2026-10-09) — the segments to delete: from [prunedBelow] up to [keepBehind] behind [furthest]; null when none. */
internal fun pruneRange(prunedBelow: Int, furthest: Int, keepBehind: Int): IntRange? {
    val below = furthest - keepBehind
    return if (below <= prunedBelow) null else prunedBelow until below
}
