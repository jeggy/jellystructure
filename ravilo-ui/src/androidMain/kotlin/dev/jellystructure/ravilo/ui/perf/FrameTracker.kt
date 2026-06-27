package dev.jellystructure.ravilo.ui.perf

import android.view.Choreographer

actual object FrameTracker {
    private var running = false
    private val frameTimes = ArrayDeque<Long>()
    private var fpsValue = 0f
    private var droppedCount = 0
    private const val TARGET_NS = 16_666_666L  // 60 fps

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (frameTimes.isNotEmpty()) {
                val delta = frameTimeNanos - frameTimes.last()
                if (delta > TARGET_NS * 1.5f) droppedCount++
            }
            frameTimes.addLast(frameTimeNanos)
            val cutoff = frameTimeNanos - 1_000_000_000L
            while (frameTimes.isNotEmpty() && frameTimes.first() < cutoff) frameTimes.removeFirst()
            fpsValue = frameTimes.size.toFloat()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    actual fun start() {
        if (!running) {
            running = true
            droppedCount = 0
            frameTimes.clear()
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }
    actual fun stop()    { running = false; frameTimes.clear(); fpsValue = 0f }
    actual fun fps()     = fpsValue
    actual fun dropped() = droppedCount
}
