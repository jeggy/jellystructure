package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.HlsWarm
import io.ktor.utils.io.ByteReadChannel

/** DEB-3171 — the web player reads its own segments; the warm-up's bytes are dropped. */
actual object WarmedSegments {
    actual fun begin(masterUrl: String) {}
    actual suspend fun keep(segmentUrl: String, body: ByteReadChannel) { HlsWarm.drain(body) }
}
