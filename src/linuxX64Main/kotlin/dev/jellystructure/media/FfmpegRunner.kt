package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.TrackKind
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import platform.posix.remove

object FfmpegRunner {
    private fun typeChar(kind: TrackKind) = when (kind) {
        TrackKind.AUDIO -> "a"
        TrackKind.SUBTITLE -> "s"
        else -> "v"
    }

    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): Boolean {
        val core = TrackCommandBuilder.ffmpegDefault(filePath, defaultStreamIndex, sameTypeIndices, typeChar(kind))
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        val core = TrackCommandBuilder.ffmpegLanguage(filePath, streamIndex, language) ?: return false
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    suspend fun reorderTracks(filePath: String, kind: TrackKind, orderedIndices: List<Int>): Boolean {
        val core = TrackCommandBuilder.ffmpegReorder(filePath, orderedIndices, kind == TrackKind.AUDIO)
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    // Dry-run command strings for the /tracks/plan endpoint — delegate to the shared builder.
    fun planSetDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): String =
        TrackCommandBuilder.ffmpegDefault(filePath, defaultStreamIndex, sameTypeIndices, typeChar(kind))

    /** Phase 201 (FR-201-3/5) — repair a file whose `Tracks` element has been evicted past the first
     *  `Cluster` (see [MkvLayout]). Same remux plumbing as every other write here. */
    suspend fun repairTracksLayout(filePath: String): Boolean {
        val core = TrackCommandBuilder.ffmpegRepairTracksLayout(filePath)
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    private suspend fun runRemux(filePath: String, cmd: String): Boolean {
        val ok = runCommand(cmd)
        if (!ok) {
            val tmp = tmpPath(filePath)
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Wraps a core shell command with stat capture before and chown/chmod restore after success.
    // `escapedOrig` must already be single-quote-safe. The approach is shell-only so it works
    // in any POSIX sh (GNU stat -c is Linux-specific but that's our only deployment target).
    private fun withOwnershipPreservation(escapedOrig: String, core: String): String =
        "_jsu=\$(stat -c '%u' '$escapedOrig' 2>/dev/null);" +
        "_jsg=\$(stat -c '%g' '$escapedOrig' 2>/dev/null);" +
        "_jsm=\$(stat -c '%a' '$escapedOrig' 2>/dev/null);" +
        "$core && " +
        "{ [ -n \"\$_jsu\" ] && chown \"\${_jsu}:\${_jsg}\" '$escapedOrig' 2>/dev/null || true;" +
        "[ -n \"\$_jsm\" ] && chmod \"\$_jsm\" '$escapedOrig' 2>/dev/null || true; }"

    // Phase 109: exposed so MediaJobQueue can target the same temp file for cancellation (pkill -f) and
    // for the disk-space preflight — the tmp copy is a second full-size file on the same filesystem.
    fun tmpPath(filePath: String): String {
        val dir = filePath.substringBeforeLast('/')
        val name = filePath.substringAfterLast('/')
        return "$dir/.jstmp_$name"
    }

    /** Phase 109: source duration in seconds via ffprobe, for live remux progress %. Null if unknown
     *  (ffprobe failure, or a non-numeric/empty duration) — callers degrade to speed-only progress. */
    suspend fun probeDurationSeconds(filePath: String): Double? {
        val escaped = filePath.replace("'", "'\\''")
        val out = captureCommand("ffprobe -v error -show_entries format=duration -of csv=p=0 '$escaped' 2>/dev/null")
        return out?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /**
     * Phase 109: like [runRemux] but runs the core command with `nice`/`ionice` (protects API/playback
     * from a big remux) and `-progress pipe:1 -nostats` (structured progress instead of ffmpeg's default
     * human-readable stats line), parsing `out_time_ms=`/`speed=` out of each progress block and invoking
     * [onProgress] once per block (~every 0.5s, ffmpeg's own default `-progress` cadence). [onProgress]
     * runs synchronously inside the blocking read loop — callers must keep it cheap (a WS broadcast) and
     * non-suspending; it uses `runBlocking` internally to bridge into a suspend broadcaster.
     */
    suspend fun runRemuxTracked(
        filePath: String,
        cmd: String,
        durationSeconds: Double?,
        onProgress: (pct: Double, speed: String?, etaSeconds: Long?) -> Unit,
    ): Boolean {
        val niced = cmd.replaceFirst("ffmpeg -y ", "nice -n 19 ionice -c3 ffmpeg -y -progress pipe:1 -nostats ")
        val escaped = filePath.replace("'", "'\\''")
        val ok = runCommandTracked(withOwnershipPreservation(escaped, niced), durationSeconds, onProgress)
        if (!ok) {
            val tmp = tmpPath(filePath)
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Phase 118 (FR C.3) — shared ProcessGate on top of the Phase 109 worker's own single-remux
    // serialization. No bare `return` inside the gated block — ProcessGate.withPermit's lambda isn't
    // inline, so a non-local return isn't allowed there; a nullable pipe + if/else avoids it.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureCommand(cmd: String): String? = dev.jellystructure.ops.ProcessGate.withPermit {
        captureCommandRaw(cmd)
    }

    // Phase 170 — same popen/read logic as [captureCommand], but through the segment-detection lane's
    // own dedicated (smaller) SegmentProcessGate instead of the shared ProcessGate — see that gate's
    // doc comment. Used only by the three heavy-decode segment-detection calls below.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureCommandSegments(cmd: String): String? = dev.jellystructure.ops.SegmentProcessGate.withPermit {
        captureCommandRaw(cmd)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun captureCommandRaw(cmd: String): String? = memScoped {
        val pipe = popen(cmd, "r")
        if (pipe == null) {
            null
        } else {
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
            pclose(pipe)
            sb.toString()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommandTracked(
        cmd: String,
        durationSeconds: Double?,
        onProgress: (pct: Double, speed: String?, etaSeconds: Long?) -> Unit,
    ): Boolean {
        Logger.info("ffmpeg (tracked): $cmd", "track")
        // Phase 118 (FR C.3) — shared ProcessGate.
        return dev.jellystructure.ops.ProcessGate.withPermit {
            memScoped {
                val pipe = popen(cmd, "r")
                if (pipe == null) {
                    false
                } else {
                    val sb = StringBuilder()
                    val buf = allocArray<ByteVar>(4096)
                    var lastSpeed: String? = null
                    var lastOutTimeUs: Long? = null
                    while (fgets(buf, 4096, pipe) != null) {
                        val line = buf.toKString()
                        sb.append(line)
                        for (raw in line.split('\n')) {
                            val trimmed = raw.trim()
                            when {
                                trimmed.startsWith("out_time_ms=") -> lastOutTimeUs = trimmed.removePrefix("out_time_ms=").toLongOrNull()
                                trimmed.startsWith("speed=") -> lastSpeed = trimmed.removePrefix("speed=").trim().takeIf { it.isNotBlank() && it != "N/A" }
                                trimmed == "progress=continue" || trimmed == "progress=end" -> {
                                    val outTimeS = (lastOutTimeUs ?: 0L) / 1_000_000.0
                                    val pct = if (durationSeconds != null && lastOutTimeUs != null)
                                        (outTimeS / durationSeconds * 100.0).coerceIn(0.0, 100.0)
                                    else 0.0
                                    val speedMult = lastSpeed?.removeSuffix("x")?.toDoubleOrNull()
                                    val etaSeconds: Long? = if (speedMult != null && speedMult > 0 && durationSeconds != null)
                                        ((durationSeconds - outTimeS) / speedMult).toLong().coerceAtLeast(0)
                                    else null
                                    onProgress(pct, lastSpeed, etaSeconds)
                                }
                            }
                        }
                    }
                    val rc = pclose(pipe)
                    if (rc != 0) Logger.warn("ffmpeg exit $rc: $sb", "track")
                    rc == 0
                }
            }
        }
    }

    // Phase 118 (FR C.3) — shared ProcessGate.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommand(cmd: String): Boolean {
        Logger.info("ffmpeg: $cmd", "track")
        return dev.jellystructure.ops.ProcessGate.withPermit {
            memScoped {
                val pipe = popen(cmd, "r")
                if (pipe == null) {
                    false
                } else {
                    val sb = StringBuilder()
                    val buf = allocArray<ByteVar>(4096)
                    while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
                    val rc = pclose(pipe)
                    if (rc != 0) Logger.warn("ffmpeg exit $rc: $sb", "track")
                    rc == 0
                }
            }
        }
    }

    /** R131: extract a single JPEG frame at [atSeconds] into [output] (overwrites) — the screen-grabber's
     *  core. `-ss` before `-i` is a fast input seek; `-q:v 3` ≈ JPEG quality 90. */
    suspend fun extractFrame(input: String, output: String, atSeconds: Int): Boolean {
        val inEsc = input.replace("'", "'\\''")
        val outEsc = output.replace("'", "'\\''")
        return runCommand("ffmpeg -y -ss $atSeconds -i '$inEsc' -frames:v 1 -q:v 3 '$outEsc' 2>&1")
    }

    // Phase 163 (step 6) — binary-safe stdout capture. captureCommand/runCommand above read via
    // fgets()+toKString(), which is text-only: fgets stops at every newline byte (common in raw PCM) and
    // toKString() truncates at the first embedded NUL. This reads raw bytes via fread() instead, growing
    // a chunk list rather than one big pre-sized buffer since ffmpeg's output length isn't known upfront.
    // Phase 170 — through the segment-detection lane's dedicated SegmentProcessGate (see its doc
    // comment): this is only ever used by [computeWaveform], the trim view's own heavy-decode call.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureBinaryCommand(cmd: String): ByteArray? = dev.jellystructure.ops.SegmentProcessGate.withPermit {
        memScoped {
            val pipe = popen(cmd, "r")
            if (pipe == null) {
                null
            } else {
                val chunkSize = 65536
                val buf = allocArray<ByteVar>(chunkSize)
                val chunks = mutableListOf<ByteArray>()
                var total = 0
                while (true) {
                    val n = platform.posix.fread(buf, 1u, chunkSize.toULong(), pipe).toInt()
                    if (n <= 0) break
                    chunks.add(buf.readBytes(n))
                    total += n
                }
                pclose(pipe)
                val out = ByteArray(total)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, offset)
                    offset += chunk.size
                }
                out
            }
        }
    }

    // Peak-per-bucket amplitude only needs coarse temporal resolution (a handful of buckets per minute at
    // most) — 8kHz mono is already generous for that, keeping a full movie's raw PCM in the tens-of-MB
    // range rather than hundreds.
    private const val WAVEFORM_SAMPLE_RATE = 8000

    /** Phase 163 (step 6) — [buckets] peak amplitudes (0-100) across [startSec]..[startSec]+[windowSec]
     *  of [filePath]'s audio, for the trim view's waveform. Null on any ffmpeg failure (no audio track,
     *  corrupt file, etc.) — the frontend renders no waveform rather than a fake flat one. */
    suspend fun computeWaveform(filePath: String, startSec: Double, windowSec: Double, buckets: Int): List<Int>? {
        if (windowSec <= 0 || buckets <= 0) return null
        val escaped = filePath.replace("'", "'\\''")
        val cmd = "nice -n 19 ionice -c3 ffmpeg -ss $startSec -i '$escaped' -t $windowSec -vn -ac 1 -ar $WAVEFORM_SAMPLE_RATE -f s16le - 2>/dev/null"
        val bytes = captureBinaryCommand(cmd) ?: return null
        val sampleCount = bytes.size / 2
        if (sampleCount == 0) return List(buckets) { 0 }
        val samplesPerBucket = (sampleCount / buckets).coerceAtLeast(1)
        return IntArray(buckets) { b ->
            val startIdx = b * samplesPerBucket
            val endIdx = (startIdx + samplesPerBucket).coerceAtMost(sampleCount)
            var peak = 0
            for (i in startIdx until endIdx) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                val sample = kotlin.math.abs((hi shl 8) or lo)
                if (sample > peak) peak = sample
            }
            (peak / 32768.0 * 100).toInt().coerceIn(0, 100)
        }.toList()
    }

    /** R133: resize [input] into [output] for the Ravilo artwork service. Pass [width] OR [height] (the
     *  other side scales to preserve aspect; -2 keeps it even, required by some encoders). PNG output
     *  (logos) preserves alpha; JPEG gets `-q:v 3`. */
    suspend fun resizeImage(input: String, output: String, width: Int? = null, height: Int? = null): Boolean {
        val inEsc = input.replace("'", "'\\''")
        val outEsc = output.replace("'", "'\\''")
        val w = width?.takeIf { it > 0 } ?: -2
        val h = height?.takeIf { it > 0 } ?: -2
        val q = if (output.endsWith(".png")) "" else "-q:v 3 "
        return runCommand("ffmpeg -y -i '$inEsc' -vf scale=$w:$h -frames:v 1 $q'$outEsc' 2>&1")
    }

    /**
     * Phase 187 (FR-187-6) — server-side centre-crop-to-square + bound, for an uploaded account photo.
     * There is no crop UI (R234 FR-R234-9: the owner didn't ask for one, every surface renders a
     * circle), so a non-square original is cropped exactly once, here, for every viewer of it —
     * `crop=min(iw\,ih):min(iw\,ih)` takes the largest centred square Ffmpeg's own filter graph can
     * express, then `scale` bounds it to [size]px. Always JPEG: Jellyfin's `/UserImage` re-serves
     * whatever bytes it was given verbatim (FR-187-1's probe found it never resizes), so this is the
     * only place in the whole chain that ever bounds what a TV eventually downloads.
     */
    suspend fun centerCropSquareJpeg(input: String, output: String, size: Int): Boolean {
        val inEsc = input.replace("'", "'\\''")
        val outEsc = output.replace("'", "'\\''")
        // The comma inside min(iw,ih) MUST be escaped (\,) — ffmpeg's own filtergraph parser splits
        // filters on an unescaped comma, so an un-escaped one here silently mangles the whole -vf value
        // into three broken fragments (crop='min(iw / ih)':'min(iw / ih)':scale=...) rather than erroring
        // loudly. Caught by testing this against a real non-square image before it was ever wired up.
        return runCommand("ffmpeg -y -i '$inEsc' -vf \"crop='min(iw\\,ih)':'min(iw\\,ih)',scale=$size:$size\" -frames:v 1 -q:v 3 '$outEsc' 2>&1")
    }

    // Phase 150 (FR-SEG1-3) — below this runtime, treat the file as TV-episode-length (a short window
    // is enough); at/above it, movie-length (credits can run much longer, so scan further back).
    private const val CREDITS_WINDOW_THRESHOLD_SEC = 3000.0  // 50min
    private const val CREDITS_WINDOW_TV_SEC = 180.0          // last 3min
    private const val CREDITS_WINDOW_MOVIE_SEC = 900.0       // last 15min

    // Empirically verified live (2026-07-13, a real ~104min library file): black_start:/silence_start:
    // in blackdetect/silencedetect's stderr output are relative to the SEEK point (matching that same
    // run's own progress "time=" counter resetting to ~0), never absolute file position — confirmed by
    // a black_start/black_end landing exactly at the requested window's own end. Every timestamp below
    // must have windowStartSec added back before it means anything against the file's real timeline.
    private const val BLACK_SILENCE_TOLERANCE_SEC = 2.0

    /** Phase 163 (dev-review addendum §2's "full raw evidence" decision) — one black-frame or silence
     *  interval the credits heuristic scanned, in absolute file ms. [accepted] marks the specific
     *  interval pair that became the returned [CreditsHeuristicResult] — everything else here is a
     *  rejected/unrelated candidate, kept so the trim view's evidence lane can show *why* the winner
     *  won, not just the final number. */
    data class HeuristicEvidencePoint(val type: String, val startMs: Long, val endMs: Long?, val accepted: Boolean)

    data class CreditsHeuristicResult(val startMs: Long, val confidence: Double, val evidence: List<HeuristicEvidencePoint> = emptyList())

    /**
     * FR-SEG1-3 — the credits heuristic: scans only the file's own last few minutes (sized from
     * [durationSec], never the whole file — this is the "cheap, no reference episode needed" tier) for
     * the earliest point where a black-frame run and a silence run coincide, the classic "the last scene
     * ends, credits roll" cut. [CreditsHeuristicResult.confidence] is highest when the black and silence
     * onsets nearly align (a clean cut) and lower the further apart they are, floored/capped so it's
     * never reported as fully certain either way — this is a heuristic, not an exact marker (unlike a
     * chapter match). Returns null when nothing coincides in the window; the caller leaves
     * `creditsStartMs` unset so the player keeps today's end-of-file fallback — never a worse guess than
     * the status quo.
     */
    suspend fun detectCreditsStart(filePath: String, durationSec: Double): CreditsHeuristicResult? {
        if (durationSec <= 0) return null
        val windowSec = if (durationSec < CREDITS_WINDOW_THRESHOLD_SEC) CREDITS_WINDOW_TV_SEC else CREDITS_WINDOW_MOVIE_SEC
        val windowStartSec = (durationSec - windowSec).coerceAtLeast(0.0)
        val escaped = filePath.replace("'", "'\\''")
        // Bug fix: blackdetect/silencedetect decode every frame in the window and ffmpeg defaults to
        // using as many threads as it finds useful — unlike runRemuxTracked's nice/ionice treatment,
        // this call ran at normal priority with no thread cap, so ProcessGate's 16 concurrent slots
        // could each spin up several decode threads and saturate the whole host, starving the API
        // server (same fix shape as runRemuxTracked's "protects API/playback from a big remux").
        val cmd = "nice -n 19 ionice -c3 ffmpeg -ss $windowStartSec -i '$escaped' -t ${durationSec - windowStartSec} " +
            "-threads 2 -vf blackdetect=d=0.5:pic_th=0.98:pix_th=0.10 -af silencedetect=noise=-60dB:d=0.5 -f null - 2>&1"
        val output = captureCommandSegments(cmd) ?: return null

        val blackStarts = Regex("""black_start:([\d.]+)""").findAll(output)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val blackEnds = Regex("""black_end:([\d.]+)""").findAll(output)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val silenceStarts = Regex("""silence_start:\s*([\d.]+)""").findAll(output)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        // Phase 163 — ffmpeg already emits silence_end in the same stderr text (no new invocation); it
        // was simply never parsed before evidence capture needed it.
        val silenceEnds = Regex("""silence_end:\s*([\d.]+)""").findAll(output)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (blackStarts.isEmpty() || silenceStarts.isEmpty()) return null

        val windowLenSec = durationSec - windowStartSec

        // Phase 159 (FR-159-4) — the pre-159 code took the FIRST black+silence coincidence in the
        // window unconditionally. That's wrong whenever the window contains an earlier false-positive:
        // a dramatic fade-to-black-and-quiet within the final act (a single brief cut, normal picture
        // and sound resume right after) fires the exact same signal as genuine rolling credits, and
        // being first in time, it used to win outright regardless of what followed it.
        //
        // Real credits are a *sustained* black/quiet stretch running to the end of the window — a
        // scene-cut fade is not. So for every candidate coincidence, score it by how much of the
        // remaining window (candidate -> window end) is actually covered by black-frame runs; a
        // one-off cut leaves most of the tail non-black (normal footage resumed), while genuine
        // credits leave most of it black. Pick the EARLIEST candidate whose tail-black-coverage clears
        // a floor (i.e. the true transition point into a sustained black stretch), not just the
        // earliest coincidence of any kind.
        data class Candidate(val timeSec: Double, val gap: Double, val blackStartSec: Double, val silenceStartSec: Double)
        val candidates = blackStarts.mapNotNull { b ->
            val s = silenceStarts.firstOrNull { kotlin.math.abs(it - b) <= BLACK_SILENCE_TOLERANCE_SEC } ?: return@mapNotNull null
            Candidate(minOf(b, s), kotlin.math.abs(s - b), b, s)
        }
        if (candidates.isEmpty()) return null

        fun blackCoverageAfter(timeSec: Double): Double {
            val tailLen = durationSec - (windowStartSec + timeSec)
            if (tailLen <= 0) return 0.0
            var covered = 0.0
            for (i in blackStarts.indices) {
                val bs = blackStarts[i]
                if (bs < timeSec) continue
                val be = blackEnds.getOrNull(i) ?: windowLenSec
                covered += (be - bs).coerceIn(0.0, tailLen)
            }
            return (covered / tailLen).coerceIn(0.0, 1.0)
        }

        val accepted = candidates
            .sortedBy { it.timeSec }
            .firstOrNull { blackCoverageAfter(it.timeSec) >= CREDITS_TAIL_BLACK_COVERAGE_MIN }
            ?: return null // no coincidence looked like a sustained credits stretch — refuse rather than guess

        val confidence = (1.0 - accepted.gap / BLACK_SILENCE_TOLERANCE_SEC).coerceIn(0.3, 0.9)

        // Phase 163 — every black/silence interval the window scanned, in absolute file ms, tagged with
        // whether it's the specific pair that won. Only built once a result is actually returned (the
        // several `return null` branches above have nothing to attach evidence to).
        fun toAbsMs(sec: Double) = ((windowStartSec + sec) * 1000).toLong()
        val evidence = buildList {
            for (i in blackStarts.indices) {
                val bs = blackStarts[i]
                add(HeuristicEvidencePoint(
                    type = "black_frame", startMs = toAbsMs(bs), endMs = blackEnds.getOrNull(i)?.let(::toAbsMs),
                    accepted = kotlin.math.abs(bs - accepted.blackStartSec) < 0.01,
                ))
            }
            for (i in silenceStarts.indices) {
                val ss = silenceStarts[i]
                add(HeuristicEvidencePoint(
                    type = "silence", startMs = toAbsMs(ss), endMs = silenceEnds.getOrNull(i)?.let(::toAbsMs),
                    accepted = kotlin.math.abs(ss - accepted.silenceStartSec) < 0.01,
                ))
            }
        }
        return CreditsHeuristicResult(startMs = toAbsMs(accepted.timeSec), confidence = confidence, evidence = evidence)
    }

    // Phase 159 (FR-159-4) — a genuine rolling-credits stretch is black/dark for most of its duration;
    // a one-off scene fade is not. 35% is a deliberately loose floor (credits often include lit studio
    // logos, colored text-on-image cards, etc., so 100% black is not expected) chosen to reject only the
    // clear false-positive case: a brief cut with normal, non-black footage resuming right after it.
    private const val CREDITS_TAIL_BLACK_COVERAGE_MIN = 0.35

    // Phase 150 (FR-SEG1-4) — Chromaprint fingerprint for cross-episode intro matching, via the
    // `fpcalc` CLI (Chromaprint's own command-line tool; must be present on PATH — the
    // detect_fingerprint pipeline-step toggle is what an admin opts into once it's installed).
    // -length bounds decoded/analyzed audio to the first [windowSec] of the file — the intro is always
    // near the start, and a full-episode fingerprint (many thousands of frames) would be needless
    // compute and cache size for content this tier never looks at.
    // Phase 159 (FR-159-5) — widened 600s->900s: a variable-length cold open (Offboarding: up to ~5 min)
    // plus the title theme itself only left a thin margin at 600s; 900s covers that with real headroom.
    const val FINGERPRINT_WINDOW_SEC = 900

    /**
     * Raw Chromaprint fingerprint (one 32-bit value per ~0.124s of audio, verified empirically 2026-07-13
     * against two real library files of different codecs/sample-rates — see SegmentDetection's own
     * FRAME_SEC/FRAME_OFFSET_SEC constants) for the first [windowSec] of [filePath]'s audio. Each value
     * is parsed via Long (fpcalc prints these as *unsigned* 32-bit decimals, some exceeding
     * Int.MAX_VALUE) then narrowed to Int — same bit pattern, just reinterpreted as signed, which is all
     * XOR/popcount frame comparison ever needs. Null on any fpcalc failure (not installed, corrupt file).
     */
    suspend fun computeFingerprint(filePath: String, windowSec: Int = FINGERPRINT_WINDOW_SEC): List<Int>? {
        val escaped = filePath.replace("'", "'\\''")
        // Same nice/ionice treatment as detectCreditsStart — fpcalc shells out to libavcodec for audio
        // decode, and up to 16 of these can run concurrently under ProcessGate.
        val output = captureCommandSegments("nice -n 19 ionice -c3 fpcalc -raw -length $windowSec '$escaped' 2>&1") ?: return null
        val match = Regex("""FINGERPRINT=([\d,]+)""").find(output) ?: return null
        return match.groupValues[1].split(",").mapNotNull { it.trim().toLongOrNull()?.toInt() }.takeIf { it.isNotEmpty() }
    }

    // Phase 159 (FR-159-3) — cross-episode OUTRO/credits fingerprinting, mirroring the intro fingerprint
    // above. fpcalc's own CLI only supports "-length SECS from the start of the file", not an offset, so
    // the tail window is carved out with ffmpeg's `-sseof` (seek-from-end-of-file) flag and piped to
    // fpcalc as WAV on stdin — verified live 2026-08-08 against a synthetic sine-wave WAV before writing
    // this. 300s covers the vast majority of real end-credits rolls without fingerprinting the whole file.
    const val OUTRO_FINGERPRINT_WINDOW_SEC = 300

    /**
     * Raw Chromaprint fingerprint for the last [windowSec] of [filePath]'s audio (see [computeFingerprint]
     * for the frame format). Frame index 0 of the returned list corresponds to file time
     * `durationSec - windowSec`, NOT file time 0 — callers converting a match position back to an
     * absolute file timestamp must add that offset themselves (this function has no way to know
     * [windowSec] was actually available, e.g. on a file shorter than the window; ffmpeg just clamps
     * `-sseof` to the start of the file in that case, so callers must derive the real analyzed offset
     * from the file's own duration, not assume `durationSec - windowSec`).
     */
    suspend fun computeOutroFingerprint(filePath: String, windowSec: Int = OUTRO_FINGERPRINT_WINDOW_SEC): List<Int>? {
        val escaped = filePath.replace("'", "'\\''")
        val cmd = "nice -n 19 ionice -c3 ffmpeg -sseof -$windowSec -i '$escaped' -f wav - 2>/dev/null | " +
            "nice -n 19 ionice -c3 fpcalc -raw -length $windowSec - 2>&1"
        val output = captureCommandSegments(cmd) ?: return null
        val match = Regex("""FINGERPRINT=([\d,]+)""").find(output) ?: return null
        return match.groupValues[1].split(",").mapNotNull { it.trim().toLongOrNull()?.toInt() }.takeIf { it.isNotEmpty() }
    }
}
