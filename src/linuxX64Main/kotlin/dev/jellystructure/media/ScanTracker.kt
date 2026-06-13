package dev.jellystructure.media

import kotlinx.serialization.Serializable

@Serializable
data class ScanStatus(
    val running: Boolean,
    val lastCount: Int? = null,
)

class ScanTracker {
    var running: Boolean = false
    var lastCount: Int? = null
    var cancelRequested: Boolean = false

    fun status() = ScanStatus(running = running, lastCount = lastCount)

    fun cancel() {
        if (running) cancelRequested = true
    }

    fun reset() {
        cancelRequested = false
    }
}
