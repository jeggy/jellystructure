package dev.jellystructure.shared.wire

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import kotlin.test.Test

/**
 * R319 (FR-R319-1) — writes this tree's wire contract to `$WIRE_CONTRACT_OUT`, and does nothing without it.
 * `scripts/record-wire-baseline.sh` copies this file (with [WireContract] and a generated `WireRoots.kt`)
 * into each release's checkout to record that release.
 */
class WireContractRecordTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun record() {
        val path = getenv("WIRE_CONTRACT_OUT")?.toKString() ?: return
        val f = fopen(path, "w") ?: error("cannot write $path")
        try { fputs(WireContract.record(WIRE_ROOTS).toString(), f) } finally { fclose(f) }
    }
}
