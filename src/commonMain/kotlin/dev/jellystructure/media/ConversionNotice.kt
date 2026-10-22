package dev.jellystructure.media

/** Which part of the flow the operator has opened. UI navigation only — never data; every fact comes from the server. */
enum class ConvertView { NOTICE, PREVIEW, CONFIRM }

/** DEB-2051 (FR-9) — the notice the admin draws above the Tracks card; each is one of direction A's drawn frames. */
enum class NoticeState {
    NONE, LIMITATION, PREVIEW, ESTIMATE_UNKNOWN,
    BLOCKED_SEEDING, BLOCKED_UNKNOWN, BLOCKED_NAME_TAKEN, BLOCKED_NO_ROOM, BLOCKED_PATH_MISMATCH,
    RUNNING, OFFLINE, VERIFIED, FAILED_TOOL, FAILED_UNVERIFIED,
}

/** What the failed track's own sub-line says (FR-2, FR-9). */
enum class RowNote { NONE, NOT_KEPT_WITH_LINK, NOT_KEPT_NO_LINK, BLOCKED, PATH_MISMATCH, LOCKED, STILL_FAILED, STILL_UNVERIFIED }

/**
 * DEB-2051 (FR-9) — the one mapping from what the server said to what the admin draws. The admin derives nothing else:
 * the [preview] is the server's (`GET …/container-conversion`), [refused] is that this view saw FR-1's 422 for the file,
 * [offline] is that the push connection is down, [view] is how far into the flow the operator has opened.
 */
object ConversionNotice {
    fun stateOf(preview: ConversionPreview?, refused: Boolean, view: ConvertView, offline: Boolean, episode: Boolean): NoticeState {
        // An episode's notice offers nothing: the conversion is films-only. Its row carries FR-2's sentence alone.
        if (episode || preview == null) return NoticeState.NONE
        val latest = preview.latest
        if (latest != null && view == ConvertView.NOTICE) when (latest.state) {
            ContainerConversion.QUEUED, ContainerConversion.RUNNING -> return if (offline) NoticeState.OFFLINE else NoticeState.RUNNING
            ContainerConversion.VERIFIED -> return NoticeState.VERIFIED
            ContainerConversion.FAILED_TOOL -> return NoticeState.FAILED_TOOL
            ContainerConversion.FAILED_UNVERIFIED -> return NoticeState.FAILED_UNVERIFIED
            ContainerConversion.BLOCKED -> return blockedState(latest.reason)
        }
        // A job that is queued or running keeps its notice however far the operator had opened the flow.
        if (latest != null && (latest.state == ContainerConversion.QUEUED || latest.state == ContainerConversion.RUNNING))
            return if (offline) NoticeState.OFFLINE else NoticeState.RUNNING
        if (!preview.available || !(refused || latest != null)) return NoticeState.NONE
        if (view == ConvertView.NOTICE) return NoticeState.LIMITATION
        preview.block?.let { return blockedState(it.reason) }
        return if (preview.estimateSeconds == null || preview.tempBytes == null) NoticeState.ESTIMATE_UNKNOWN else NoticeState.PREVIEW
    }

    private fun blockedState(reason: String?): NoticeState = when (reason) {
        ContainerConversion.SEEDED -> NoticeState.BLOCKED_SEEDING
        ContainerConversion.UNKNOWN -> NoticeState.BLOCKED_UNKNOWN
        ContainerConversion.NAME_TAKEN -> NoticeState.BLOCKED_NAME_TAKEN
        ContainerConversion.NO_ROOM -> NoticeState.BLOCKED_NO_ROOM
        else -> NoticeState.BLOCKED_PATH_MISMATCH
    }

    /** The confirmation dialog is open: the operator pressed *Continue*, and the preview it stands on is not blocked. */
    fun dialogOpen(state: NoticeState, view: ConvertView): Boolean =
        view == ConvertView.CONFIRM && (state == NoticeState.PREVIEW || state == NoticeState.ESTIMATE_UNKNOWN)

    /** FR-2 / FR-9 — the sub-line under the track the edit was refused on. [refused]: this view saw the 422 for that track. */
    fun rowNote(state: NoticeState, refused: Boolean, episode: Boolean): RowNote = when {
        episode -> if (refused) RowNote.NOT_KEPT_NO_LINK else RowNote.NONE
        state == NoticeState.LIMITATION || state == NoticeState.PREVIEW || state == NoticeState.ESTIMATE_UNKNOWN -> RowNote.NOT_KEPT_WITH_LINK
        state == NoticeState.BLOCKED_SEEDING || state == NoticeState.BLOCKED_UNKNOWN ||
            state == NoticeState.BLOCKED_NAME_TAKEN || state == NoticeState.BLOCKED_NO_ROOM -> RowNote.BLOCKED
        state == NoticeState.BLOCKED_PATH_MISMATCH -> RowNote.PATH_MISMATCH
        state == NoticeState.RUNNING || state == NoticeState.OFFLINE -> RowNote.LOCKED
        state == NoticeState.FAILED_TOOL -> RowNote.STILL_FAILED
        state == NoticeState.FAILED_UNVERIFIED -> RowNote.STILL_UNVERIFIED
        else -> RowNote.NONE
    }
}
