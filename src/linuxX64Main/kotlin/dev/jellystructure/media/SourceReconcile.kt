package dev.jellystructure.media

import kotlinx.serialization.Serializable
import dev.jellystructure.model.MediaItem
import kotlin.concurrent.AtomicReference

/**
 * DEB-5709 (FR-4) — a full scan found that Jellyfin no longer lists [gone] of the [held] titles it had held (not flagged, with a Jellyfin
 * id), which is more than half at once, or the whole library: that looks like a failed read, so none were flagged. [at] is epoch seconds.
 */
@Serializable
data class HeldBack(val at: Long, val gone: Int, val held: Int)

/** The last hold, in memory only: after a restart the next full scan sets it again if it still applies (FR-4). */
object LibraryHold {
    private val ref = AtomicReference<HeldBack?>(null)
    val current: HeldBack? get() = ref.value
    fun set(hold: HeldBack?) { ref.value = hold }
}

/** FR-2 — said in Activity when a full scan's read of Jellyfin failed, and by the hand-flag route (503) when its re-read fails. */
const val JELLYFIN_READ_FAILED_SENTENCE = "Scan stopped: Jellyfin's library could not be read, so nothing was marked missing. The next scan tries again."

/** FR-3 — said in Activity when a scan holds back a mass removal. */
fun heldBackSentence(gone: Int, held: Int) =
    "Scan: Jellyfin no longer lists $gone of $held titles. More than half at once looks like a failed read, so none were marked missing. " +
        "If they really were removed, use Flag them missing on the Library page."

/** What [MediaStore.reconcileWithSource] did: the titles it newly flagged, and the hold when it refused to flag. */
class SourceReconcile(val newlyMissing: List<MediaItem>, val heldBack: HeldBack?)
