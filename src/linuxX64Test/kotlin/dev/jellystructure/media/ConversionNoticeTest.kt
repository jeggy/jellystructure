package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2051 (FR-9, FR-11) — the admin's state mapping from the server's payload to the notice it draws. */
class ConversionNoticeTest {
    private fun preview(
        available: Boolean = true, estimate: Long? = 120L, temp: Long? = 1_000L,
        block: ConversionBlock? = null, latest: ConversionLatest? = null,
    ) = ConversionPreview(available = available, estimateSeconds = estimate, tempBytes = temp, block = block, latest = latest)

    private fun state(
        p: ConversionPreview?, refused: Boolean = true, view: ConvertView = ConvertView.NOTICE, offline: Boolean = false, episode: Boolean = false,
    ) = ConversionNotice.stateOf(p, refused, view, offline, episode)

    private fun latest(state: String, reason: String? = null) = ConversionLatest("j", state, reason = reason)

    @Test fun `nothing is drawn without a payload or without a refusal or a job`() {
        assertEquals(NoticeState.NONE, state(null))
        assertEquals(NoticeState.NONE, state(preview(), refused = false))
        assertEquals(NoticeState.NONE, state(preview(available = false)))
    }

    @Test fun `a refusal draws the limitation and Review opens the preview`() {
        assertEquals(NoticeState.LIMITATION, state(preview()))
        assertEquals(NoticeState.PREVIEW, state(preview(), view = ConvertView.PREVIEW))
        assertEquals(NoticeState.PREVIEW, state(preview(), view = ConvertView.CONFIRM))
    }

    @Test fun `an estimate the server could not work out reads Estimate unavailable`() {
        assertEquals(NoticeState.ESTIMATE_UNKNOWN, state(preview(estimate = null, temp = null), view = ConvertView.PREVIEW))
        assertEquals(NoticeState.ESTIMATE_UNKNOWN, state(preview(estimate = 60L, temp = null), view = ConvertView.PREVIEW), "either half missing is unavailable")
    }

    @Test fun `each FR-5 block draws its notice in the preview and never the dialog`() {
        fun blocked(reason: String) = state(preview(block = ConversionBlock(reason, "m")), view = ConvertView.CONFIRM)
        assertEquals(NoticeState.BLOCKED_PATH_MISMATCH, blocked(ContainerConversion.PATH_MISMATCH))
        assertEquals(NoticeState.BLOCKED_SEEDING, blocked(ContainerConversion.SEEDED))
        assertEquals(NoticeState.BLOCKED_UNKNOWN, blocked(ContainerConversion.UNKNOWN))
        assertEquals(NoticeState.BLOCKED_NAME_TAKEN, blocked(ContainerConversion.NAME_TAKEN))
        assertEquals(NoticeState.BLOCKED_NO_ROOM, blocked(ContainerConversion.NO_ROOM))
        assertEquals(false, ConversionNotice.dialogOpen(blocked(ContainerConversion.SEEDED), ConvertView.CONFIRM))
        assertEquals(true, ConversionNotice.dialogOpen(NoticeState.PREVIEW, ConvertView.CONFIRM))
        assertEquals(false, ConversionNotice.dialogOpen(NoticeState.PREVIEW, ConvertView.PREVIEW))
    }

    @Test fun `the latest job is the notice while the operator has not reopened the flow`() {
        assertEquals(NoticeState.RUNNING, state(preview(latest = latest("queued")), refused = false))
        assertEquals(NoticeState.RUNNING, state(preview(latest = latest("running")), refused = false))
        assertEquals(NoticeState.VERIFIED, state(preview(available = false, latest = latest("verified")), refused = false))
        assertEquals(NoticeState.FAILED_TOOL, state(preview(latest = latest("failed_tool"))))
        assertEquals(NoticeState.FAILED_UNVERIFIED, state(preview(latest = latest("failed_unverified"))))
        assertEquals(NoticeState.BLOCKED_SEEDING, state(preview(latest = latest("blocked", "seeded"))))
    }

    @Test fun `a failure is never drawn as a success and Try again reopens the preview`() {
        val failed = preview(latest = latest("failed_unverified"))
        assertEquals(NoticeState.FAILED_UNVERIFIED, state(failed))
        assertEquals(NoticeState.PREVIEW, state(failed, view = ConvertView.PREVIEW))
    }

    @Test fun `a running job stays running however far the flow was opened and offline keeps its last step`() {
        val running = preview(latest = latest("running"))
        assertEquals(NoticeState.RUNNING, state(running, view = ConvertView.CONFIRM))
        assertEquals(NoticeState.OFFLINE, state(running, offline = true))
        assertEquals(NoticeState.VERIFIED, state(preview(available = false, latest = latest("verified")), offline = true), "offline only dims a running job")
    }

    @Test fun `an episode draws no notice and its row carries the sentence without the link`() {
        assertEquals(NoticeState.NONE, state(preview(), episode = true))
        assertEquals(RowNote.NOT_KEPT_NO_LINK, ConversionNotice.rowNote(NoticeState.NONE, refused = true, episode = true))
        assertEquals(RowNote.NONE, ConversionNotice.rowNote(NoticeState.NONE, refused = false, episode = true))
    }

    @Test fun `the track row follows the notice`() {
        fun row(s: NoticeState) = ConversionNotice.rowNote(s, refused = true, episode = false)
        assertEquals(RowNote.NOT_KEPT_WITH_LINK, row(NoticeState.LIMITATION))
        assertEquals(RowNote.NOT_KEPT_WITH_LINK, row(NoticeState.PREVIEW))
        assertEquals(RowNote.BLOCKED, row(NoticeState.BLOCKED_NO_ROOM))
        assertEquals(RowNote.PATH_MISMATCH, row(NoticeState.BLOCKED_PATH_MISMATCH))
        assertEquals(RowNote.LOCKED, row(NoticeState.RUNNING))
        assertEquals(RowNote.LOCKED, row(NoticeState.OFFLINE))
        assertEquals(RowNote.STILL_FAILED, row(NoticeState.FAILED_TOOL))
        assertEquals(RowNote.STILL_UNVERIFIED, row(NoticeState.FAILED_UNVERIFIED))
        assertEquals(RowNote.NONE, row(NoticeState.VERIFIED))
        assertEquals(RowNote.NONE, row(NoticeState.NONE))
    }
}
