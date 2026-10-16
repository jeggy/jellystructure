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
    /** FR-R386-12 — the engine's changes of state since the previous snapshot, oldest first; usually empty. */
    val changes: List<EngineChange> = emptyList(),
) {
    enum class Phase { UNAVAILABLE, IDLE, CHECKING, DOWNLOADING, AVAILABLE, READY, INSTALLING, WINDOW, FAILED, NOT_FOUND }
    enum class Failure { DOWNLOAD, SIGNATURE, FEED, OTHER }

    /** One change of the engine's state, as `Update.swift` recorded it: phases and failure kinds are its words. */
    data class EngineChange(val from: String, val to: String, val version: String, val failure: String, val reason: String) {
        /** The `ravilo.log` line (FR-R386-12). No account, token or library text is ever in these fields. */
        fun logLine(): String = buildString {
            append("update: engine $from -> $to")
            if (version.isNotEmpty()) append(" version=$version")
            if (failure.isNotEmpty()) append(" failure=$failure")
            if (reason.isNotEmpty()) append(" reason=$reason")
        }

        companion object {
            /** `from|to|version|failure|reason`; a malformed line is dropped, never an exception. */
            fun parse(value: String): EngineChange? {
                val f = value.split('|', limit = 5)
                return if (f.size == 5 && f[0].isNotEmpty() && f[1].isNotEmpty()) EngineChange(f[0], f[1], f[2], f[3], f[4]) else null
            }
        }
    }

    companion object {
        val UNAVAILABLE = SparkleSnapshot(Phase.UNAVAILABLE, "", null, "no Mac library", true, -1, -1)

        /** `phase=ready\nversion=1.53\n…`; anything unreadable is [Phase.UNAVAILABLE], never an exception. */
        fun parse(text: String?): SparkleSnapshot {
            if (text == null) return UNAVAILABLE
            val pairs = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toList()
            val kv = pairs.toMap()
            val changes = pairs.filter { it.first == "change" }.mapNotNull { EngineChange.parse(it.second) }
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
                changes = changes,
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

    /** FR-R386-12 — each check writes one line naming the feed it was pointed at. */
    fun checkLine(kind: String, feedUrl: String) = "update: check ($kind) feed=$feedUrl"

    fun checkInBackground(feedUrl: String) {
        println(checkLine("background", feedUrl))
        native(Unit) { it.ravilo_update_check_background(feedUrl) }
    }

    fun checkWithWindow(feedUrl: String) {
        println(checkLine("window", feedUrl))
        native(Unit) { it.ravilo_update_check_window(feedUrl) }
    }

    fun installAndRelaunch() = native(Unit) { it.ravilo_update_install() }

    fun snapshot(): SparkleSnapshot = native(SparkleSnapshot.UNAVAILABLE) { lib ->
        SparkleSnapshot.parse(MacNative.take(lib.ravilo_update_snapshot()))
    }
}
