package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
class ClearlogoInk(private val scope: CoroutineScope, private val tmpDir: String) {
    private val known = HashMap<String, String>()
    private val queued = HashSet<String>()
    private val work = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(GateClass.BACKGROUND) {
            for (path in work) {
                runCatching { judge(path) }.onFailure { Logger.warn("Clearlogo ink failed for $path: ${it.message}", "artwork") }
                queued.remove(path)
            }
        }
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
        val ink = if (FfmpegRunner.rawRgbaThumb(path, raw, SIZE)) runCatching { clearlogoInkOf(FileIo.readBytes(Path(raw)), SIZE, SIZE) }.getOrNull() else null
        runCatching { SystemFileSystem.delete(Path(raw), mustExist = false) }
        known[key] = ink ?: UNKNOWN                     // an unreadable logo is not retried for this file
    }

    private companion object { const val SIZE = 32; const val UNKNOWN = "?" }
}
