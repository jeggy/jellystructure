package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 232 (FR-232-5) — which ink a TITLE's clearlogo is drawn in. Ravilo sets the logo on a hero whose
 * text column is deliberately dark (R257's tint), so a black-ink clearlogo (*Gone Missing*, stue TV
 * 2026-09-17) all but disappears. Same judgement as the studio/network logos ([logoInkOf] over a 32×32
 * frame from ffmpeg) — but the clearlogo lives BESIDE THE MEDIA, so nothing is written next to it:
 * results are held in memory, keyed by path + size (a replaced logo is a new key).
 *
 * [inkFor] never blocks and never computes: a miss returns `null` (= unknown, the client draws the
 * artwork untouched) and queues the file; the next request has the answer. [warm] fills the map in the
 * background at boot. One worker, background gate class — never on the request path.
 */
class ClearlogoInk(
    private val scope: CoroutineScope,
    private val tmpDir: String,
    /** Where `clearlogo-ink.json` lives; `null` keeps the map in memory only. */
    private val configDir: String? = null,
    /** The ink of one logo at [path] (a scratch file [raw] is available); `null` = unreadable. */
    private val inkOf: suspend (path: String, raw: String) -> String? = { path, raw ->
        if (FfmpegRunner.rawRgbaThumb(path, raw, SIZE)) runCatching { clearlogoInkOf(FileIo.readBytes(Path(raw)), SIZE, SIZE) }.getOrNull() else null
    },
) {
    private val known = HashMap<String, String>()
    private val inkFile = configDir?.let { "$it/clearlogo-ink.json" }
    private var dirty = false
    private var lastWrite: TimeMark? = null
    private val queued = HashSet<String>()
    private val work = Channel<String>(Channel.UNLIMITED)

    init {
        load()
        scope.launch(GateClass.BACKGROUND) {
            for (path in work) {
                runCatching { judge(path) }.onFailure { Logger.warn("Clearlogo ink failed for $path: ${it.message}", "artwork") }
                queued.remove(path)
                flush(force = false)
            }
        }
    }

    /** A missing or corrupt file means start empty. */
    private fun load() {
        val f = inkFile ?: return
        runCatching {
            if (!SystemFileSystem.exists(Path(f))) return
            known.putAll(Json.decodeFromString<Map<String, String>>(FileIo.readText(Path(f))))
        }
    }

    /** At most one write a minute; a verdict that lands inside the minute is written once the queue is idle and the minute is up. */
    private suspend fun flush(force: Boolean) {
        val f = inkFile ?: return
        if (!dirty) return
        val last = lastWrite
        if (!force && last != null && last.elapsedNow() < MIN_WRITE_GAP) {
            if (!work.isEmpty) return
            delay(MIN_WRITE_GAP - last.elapsedNow())
        }
        runCatching { FileIo.writeText(Path(f), Json.encodeToString<Map<String, String>>(known)) }
            .onFailure { Logger.warn("Clearlogo ink file not written: ${it.message}", "artwork") }
        dirty = false
        lastWrite = TimeSource.Monotonic.markNow()
    }

    private fun keyFor(path: String): String? {
        val meta = SystemFileSystem.metadataOrNull(Path(path)) ?: return null
        return "$path|${meta.size}"
    }

    fun inkFor(item: MediaItem): String? {
        val path = assetFilePath(item, "clearlogo.png")
        val key = keyFor(path) ?: return null          // no logo on disk
        known[key]?.let { return it.takeIf { v -> v != UNKNOWN } }
        if (queued.add(path)) work.trySend(path)
        return null
    }

    fun warm(items: List<MediaItem>) { for (item in items) inkFor(item) }

    private suspend fun judge(path: String) {
        val key = keyFor(path) ?: return
        if (key in known) return
        val raw = "$tmpDir/.clearlogo-ink-${path.hashCode().toUInt()}.rgba"
        val ink = inkOf(path, raw)
        runCatching { SystemFileSystem.delete(Path(raw), mustExist = false) }
        known[key] = ink ?: UNKNOWN                     // an unreadable logo is not retried for this file
        dirty = true
    }

    private companion object { const val SIZE = 32; const val UNKNOWN = "?"; val MIN_WRITE_GAP = 1.minutes }
}
