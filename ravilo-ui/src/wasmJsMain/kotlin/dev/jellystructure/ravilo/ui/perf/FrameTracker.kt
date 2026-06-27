package dev.jellystructure.ravilo.ui.perf

actual object FrameTracker {
    actual fun start()    {}
    actual fun stop()     {}
    actual fun fps()      = 0f
    actual fun dropped()  = 0
}
