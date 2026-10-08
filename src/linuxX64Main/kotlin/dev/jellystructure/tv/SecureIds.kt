package dev.jellystructure.tv

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Phase 313 (dev review item 7) — 128 bits from the kernel's random source, as 32 hex characters. A stream URL under
 * `/api/tv/stream/{id}` is public (a player cannot attach a token to an HLS fetch), so its id is the capability: it
 * must not be guessable. `kotlin.random.Random` (R291's first version) is not a cryptographic generator.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun secureHexId(): String {
    val bytes = ByteArray(16)
    val f = fopen("/dev/urandom", "rb") ?: error("/dev/urandom unavailable")
    try {
        val n = bytes.usePinned { fread(it.addressOf(0), 1u, 16u, f) }
        check(n == 16uL) { "/dev/urandom short read" }
    } finally { fclose(f) }
    return buildString { for (b in bytes) { val v = b.toInt() and 0xff; append("0123456789abcdef"[v shr 4]); append("0123456789abcdef"[v and 15]) } }
}
