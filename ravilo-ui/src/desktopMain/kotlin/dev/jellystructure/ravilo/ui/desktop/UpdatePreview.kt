package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.CheckedAt
import dev.jellystructure.ravilo.ui.components.SoftwareUpdateModel
import dev.jellystructure.ravilo.ui.components.UpdateBlockState

/**
 * R386 — the test seam for the update block's states. A development build shows no block (state 10), so the twelve states
 * cannot be reached by running it: the test driver's `update <state>` puts one of them on screen, and `update off` takes it
 * away. Off unless the test driver is on (`RAVILO_TESTDRIVER`), so no viewer can reach it. It changes what is *drawn* and
 * nothing else — no check runs and Sparkle is not asked.
 */
object UpdatePreview {
    @Volatile var model: SoftwareUpdateModel? = null
        private set
    @Volatile var active: Boolean = false
        private set

    private val at = CheckedAt(today = true, date = "16 Oct 2026", time = "09:14")

    /** The names `update <state>` takes — the round's frame names, so a frame and its screen are found by the same word. */
    val names = listOf(
        "up-to-date", "checking", "downloading", "downloading-progress", "offer", "toggle-off", "toggle-on", "ready", "ready-playing", "held-back",
        "failed-download", "failed-verify", "cannot-update-temporary-copy", "cannot-update-disk-image", "dev-build", "offline", "handover",
    )

    /** The block each name stands for; the outer null is an unknown name, the inner one is state 10 (absent). */
    fun parse(name: String): Pair<Boolean, SoftwareUpdateModel?>? {
        fun m(block: UpdateBlockState, auto: Boolean = true) = SoftwareUpdateModel(block, "1.52", auto)
        return when (name) {
            "up-to-date", "toggle-on" -> true to m(UpdateBlockState.UpToDate(at))
            "toggle-off" -> true to m(UpdateBlockState.UpToDate(at), auto = false)
            "checking" -> true to m(UpdateBlockState.Checking)
            "downloading" -> true to m(UpdateBlockState.Downloading("1.53", -1, -1))
            "downloading-progress" -> true to m(UpdateBlockState.Downloading("1.53", 41_000_000, 94_000_000))
            "offer" -> true to m(UpdateBlockState.Offer("1.53"), auto = false)
            "ready", "ready-playing" -> true to m(UpdateBlockState.Ready("1.53", UpdateView.notesUrl("1.53")))
            "held-back" -> true to m(UpdateBlockState.HeldBack("1.53", "1.52"))
            "failed-download" -> true to m(UpdateBlockState.Failed(signature = false))
            "failed-verify" -> true to m(UpdateBlockState.Failed(signature = true))
            "cannot-update-temporary-copy" -> true to m(UpdateBlockState.CannotUpdate(fromDiskImage = false))
            "cannot-update-disk-image" -> true to m(UpdateBlockState.CannotUpdate(fromDiskImage = true))
            "offline" -> true to m(UpdateBlockState.Offline(at))
            "handover" -> true to m(UpdateBlockState.Handover("1.53"))
            "dev-build" -> true to null
            "off" -> false to null
            else -> null
        }
    }

    /** True when [name] was understood. */
    fun set(name: String): Boolean {
        val (on, shown) = parse(name) ?: return false
        active = on
        model = shown
        return true
    }
}
