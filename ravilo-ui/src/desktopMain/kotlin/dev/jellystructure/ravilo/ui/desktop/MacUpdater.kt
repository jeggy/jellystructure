package dev.jellystructure.ravilo.ui.desktop

/**
 * R386 (FR-R386-4) — what Sparkle last said, as `Update.swift` reports it. Every field is Sparkle's, not Ravilo's:
 * nothing here is derived or guessed (the screens, in the next build, show exactly this).
 */
data class SparkleSnapshot(
    val phase: Phase,
    /** The update's version as Sparkle displays it (`1.53`); empty before one was found. */
    val version: String,
    val failure: Failure?,
    /** With [Phase.UNAVAILABLE]: why, for the log. */
    val reason: String,
    val autoDownload: Boolean,
    /** Bytes of a background download, or -1 when Sparkle reports none — which it does not (Update.swift's header). */
    val bytes: Long,
    val total: Long,
) {
    enum class Phase { UNAVAILABLE, IDLE, CHECKING, DOWNLOADING, AVAILABLE, READY, INSTALLING, WINDOW, FAILED, NOT_FOUND }
    enum class Failure { DOWNLOAD, SIGNATURE, FEED, OTHER }

    companion object {
        val UNAVAILABLE = SparkleSnapshot(Phase.UNAVAILABLE, "", null, "no Mac library", true, -1, -1)

        /** `phase=ready\nversion=1.53\n…`; anything unreadable is [Phase.UNAVAILABLE], never an exception. */
        fun parse(text: String?): SparkleSnapshot {
            if (text == null) return UNAVAILABLE
            val kv = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            val phase = when (kv["phase"]) {
                "idle" -> Phase.IDLE
                "checking" -> Phase.CHECKING
                "downloading" -> Phase.DOWNLOADING
                "available" -> Phase.AVAILABLE
                "ready" -> Phase.READY
                "installing" -> Phase.INSTALLING
                "window" -> Phase.WINDOW
                "failed" -> Phase.FAILED
                "notfound" -> Phase.NOT_FOUND
                else -> Phase.UNAVAILABLE
            }
            val failure = when (kv["failure"]) {
                "download" -> Failure.DOWNLOAD
                "signature" -> Failure.SIGNATURE
                "feed" -> Failure.FEED
                "other" -> Failure.OTHER
                else -> null
            }
            return SparkleSnapshot(
                phase = phase,
                version = kv["version"].orEmpty(),
                failure = failure.takeIf { phase == Phase.FAILED },
                reason = kv["reason"].orEmpty(),
                autoDownload = kv["auto"] != "0",
                bytes = kv["bytes"]?.toLongOrNull() ?: -1,
                total = kv["total"]?.toLongOrNull() ?: -1,
            )
        }
    }
}

/**
 * R386 (FR-R386-4) — the Kotlin end of `Update.swift`. Where the library is absent (Linux, a Mac build without the
 * Swift library, an older library) every call is a no-op and [snapshot] says `UNAVAILABLE`: never a crash.
 */
object MacUpdater {
    private fun <T> native(default: T, block: (MacNative.Lib) -> T): T {
        val lib = MacNative.lib ?: return default
        return try { block(lib) } catch (_: UnsatisfiedLinkError) { default }
    }

    fun start() = native(Unit) { it.ravilo_update_start() }

    fun setAutoDownload(on: Boolean) = native(Unit) { it.ravilo_update_set_auto_download(if (on) 1 else 0) }

    fun checkInBackground(feedUrl: String) = native(Unit) { it.ravilo_update_check_background(feedUrl) }

    fun checkWithWindow(feedUrl: String) = native(Unit) { it.ravilo_update_check_window(feedUrl) }

    fun installAndRelaunch() = native(Unit) { it.ravilo_update_install() }

    fun snapshot(): SparkleSnapshot = native(SparkleSnapshot.UNAVAILABLE) { lib ->
        SparkleSnapshot.parse(MacNative.take(lib.ravilo_update_snapshot()))
    }
}
