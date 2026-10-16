package dev.jellystructure.ravilo.desktop

import dev.jellystructure.ravilo.ui.desktop.DesktopLog
import dev.jellystructure.ravilo.ui.desktop.DesktopWindow
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicLong

/**
 * R383 (FR-R383-1) — the instrument the measurement was missing: nothing in the app reported how often Skia draws.
 * Off unless `-Dravilo.debug.frames` (or `RAVILO_DEBUG_FRAMES=1`) is set; then every minute one line goes to the log:
 *
 *     2026-10-16 09:00:00.000 frames: 3612 in 60 s = 60.2/s · shown=true minimised=false focused=true
 *
 * A frame is a call to the Skia layer's render delegate, i.e. a frame Compose actually drew. A window with nothing
 * animating should log 0–a few; a hidden or minimised one should log 0 (FR-R383-3). The counter only counts: it hands
 * every call straight on to Compose's own delegate.
 */
internal object FrameCounter {
    /** What a window's frames are tallied into; the part a test can drive without a window. */
    class Tally(private val clock: () -> Long = System::nanoTime) {
        private val frames = AtomicLong()
        private var since = clock()

        fun frame() { frames.incrementAndGet() }

        /** The frames since the last call and the seconds they took; starts the next period. */
        fun drain(): Pair<Long, Double> {
            val now = clock()
            val seconds = (now - since) / 1e9
            since = now
            return frames.getAndSet(0) to seconds
        }
    }

    fun line(frames: Long, seconds: Double, shown: Boolean, minimised: Boolean, focused: Boolean): String =
        "frames: $frames in ${"%.0f".format(seconds)} s = ${"%.1f".format(if (seconds > 0) frames / seconds else 0.0)}/s" +
            " · shown=$shown minimised=$minimised focused=$focused"

    val enabled: Boolean =
        System.getProperty("ravilo.debug.frames")?.let { it != "false" } ?: (System.getenv("RAVILO_DEBUG_FRAMES") == "1")

    private val tally = Tally()
    @Volatile private var wrapper: SkikoRenderDelegate? = null
    @Volatile private var started = false

    /** Hooks the window's Skia layer, once; a no-op unless enabled. Call from the EDT once the window exists. */
    fun install(window: Component) {
        if (!enabled) return
        hook(window)
        if (started) return
        started = true
        Thread({
            while (true) {
                Thread.sleep(PERIOD_MS)
                // Compose can swap its delegate (a window recreated, a renderer restart): count through the new one too.
                javax.swing.SwingUtilities.invokeLater { hook(window) }
                val (frames, seconds) = tally.drain()
                println("${DesktopLog.stamp()} " + line(frames, seconds, DesktopWindow.shown.value, DesktopWindow.minimised.value, DesktopWindow.focused.value))
            }
        }, "ravilo-frame-counter").apply { isDaemon = true; start() }
    }

    private fun hook(window: Component) {
        val skia = layerOf(window) ?: return
        val current = skia.renderDelegate
        if (current == null || current === wrapper) return
        val counting = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                tally.frame()
                current.onRender(canvas, width, height, nanoTime)
            }
        }
        wrapper = counting
        skia.renderDelegate = counting
    }

    private fun layerOf(c: Component): SkiaLayer? = when (c) {
        is SkiaLayer -> c
        is Container -> c.components.firstNotNullOfOrNull(::layerOf)
        else -> null
    }

    private const val PERIOD_MS = 60_000L
}
