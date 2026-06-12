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

    fun status() = ScanStatus(running = running, lastCount = lastCount)
}
