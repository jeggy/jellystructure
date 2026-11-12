package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadWaitReason

/**
 * DEB-4258 (DEB-4256 FR-7) — what a copy that waits for the server last heard from it. [reason] is the latest `start` answer's; [long] turns true
 * once the wait has gone [WAIT_GENERIC_MS] with no reason to give, which is when an unexplained *Waiting for the server* stops being enough.
 */
data class WaitInfo(val reason: DownloadWaitReason = DownloadWaitReason.Unknown, val long: Boolean = false)

/** The first stretch of an unexplained wait (the spec's 10 s), in which `download.waiting_server` still reads. */
const val WAIT_GENERIC_MS = 10_000L

/** One line of a waiting copy: a string key and its placeholders. */
data class WaitLine(val key: String, val args: Map<String, String> = emptyMap())

/**
 * FR-7 — the line a copy that waits for the server reads. The latest answer's reason wins from the first answer that has one; an answer with
 * none (an older server, a reason this phone does not know, a count-less `encoder_full`) keeps `download.waiting_server` for the first 10 s of
 * the wait and reads `download.wait_busy` after. Null [info] is a wait nothing has answered yet.
 */
fun waitLine(info: WaitInfo?): WaitLine = when (val r = info?.reason) {
    is DownloadWaitReason.EncoderFull -> when {
        r.streams == null -> WaitLine("download.wait_busy")
        r.streams == 1 -> WaitLine("download.wait_full_one")
        else -> WaitLine("download.wait_full", mapOf("n" to r.streams.toString()))
    }
    DownloadWaitReason.Unavailable -> WaitLine("download.wait_unavailable")
    else -> WaitLine(if (info?.long == true) "download.wait_busy" else "download.waiting_server")
}
