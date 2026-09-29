package dev.jellystructure.ravilo.ui.desktop

import kotlin.system.measureNanoTime

/**
 * R335 (FR-R335-11) — `Ravilo --mpv-bench <file-or-url> [seconds]`: the engine alone, no window, no server. Loads
 * the file through [MpvPlayer] exactly as the player would, pulls frames through the ring as the surface would, and
 * prints what a build note needs: time to the first frame, frames rendered per second at a 1080p-window hint, dropped
 * frames, the decoder, the tracks — then a seek and an audio switch. Exit 0 when a frame was rendered.
 */
object MpvBench {
    fun run(args: List<String>): Int {
        val file = args.getOrNull(0) ?: run { println("usage: --mpv-bench <file-or-url> [seconds]"); return 2 }
        val seconds = args.getOrNull(1)?.toIntOrNull() ?: 6
        val lib = Mpv.lib ?: run { println("mpv-bench: no libmpv (${Mpv.loadError})"); return 3 }
        println("mpv-bench: libmpv api ${lib.mpv_client_api_version() ushr 16}.${lib.mpv_client_api_version() and 0xffff}")
        val p = MpvPlayer(audioOnly = false)
        p.surfaceHint(1920, 1080)
        val t0 = System.nanoTime()
        p.load(file)
        var firstAt = -1L
        var frames = 0
        var renderNs = 0L
        val deadline = t0 + seconds * 1_000_000_000L
        var lastLine = ""
        while (System.nanoTime() < deadline) {
            val img: org.jetbrains.skia.Image?
            renderNs += measureNanoTime { img = p.takeFrame() }
            if (img != null) {
                frames++
                if (firstAt < 0) { firstAt = System.nanoTime(); println("first frame after ${(firstAt - t0) / 1_000_000} ms: ${img.width}x${img.height}") }
                img.close()
            }
            val s = p.state
            if (s.failed) { println("FAILED: ${p.error()}"); println(p.debug()); p.release(); return 1 }
            val line = "item=${s.item} tc=${s.timeControl} pos=${s.positionMs / 1000}s"
            if (line != lastLine) { lastLine = line; println("  $line") }
            Thread.sleep(8)
        }
        val played = (System.nanoTime() - (if (firstAt > 0) firstAt else t0)) / 1e9
        println(p.debug())
        println("frames rendered: $frames in %.1f s = %.1f fps · render %.2f ms/frame".format(java.util.Locale.ROOT, played, frames / played, if (frames > 0) renderNs / 1e6 / frames else 0.0))
        println("audio: " + (p.audioTracks()?.joinToString(" | ") { "${it.id} ${it.language ?: "-"} ${it.channels ?: "?"}ch${if (it.isDefault) " default" else ""}" } ?: "-"))
        println("subs:  " + (p.subtitleTracks()?.joinToString(" | ") { "${it.id} ${it.language ?: "-"} ${it.codec ?: ""}${if (it.external) " external" else ""}" } ?: "-"))
        // A seek, an audio switch and a subtitle pick, each given a moment.
        p.seekTo(2_000); Thread.sleep(600)
        println("after seek to 2 s: pos=${p.state.positionMs} ms seeking=${p.state.seeking}")
        if ((p.audioTracks()?.size ?: 0) > 1) { p.selectAudio(1); Thread.sleep(400); println("audio 2 selected: aid=${Mpv.getString(lib, handleOf(p), "aid")}") }
        if ((p.subtitleTracks()?.size ?: 0) > 0) { p.selectSubtitle(0); Thread.sleep(400); println("subtitle 1 selected: sid=${Mpv.getString(lib, handleOf(p), "sid")}") }
        p.release()
        println("mpv-bench: " + if (frames > 0) "OK" else "no frame rendered")
        return if (frames > 0) 0 else 1
    }

    private fun handleOf(p: MpvPlayer) = p.handleForBench()
}
