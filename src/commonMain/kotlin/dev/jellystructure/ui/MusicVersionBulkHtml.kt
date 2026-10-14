package dev.jellystructure.ui

/*
 * Phase 292 FR-292-17 (second amendment 2026-10-14, DEB-1156) — the pending state behind the two version-bulk
 * presses, and the markup that shows it.
 *
 * These live in `commonMain` for the reason `MusicCoverHtml.kt` gives: the admin frontend has no test target of
 * its own, and what these return *is* the requirement — the attributes acceptance 11 names, the sentence
 * acceptance 12 reads, and the refusal acceptance 13 checks survives a failure. Nothing here may touch the DOM
 * or `kotlinx.browser`; the moment it does it goes back to `wasmJsMain` and loses its tests.
 */

/**
 * FR-292-17 — one press at a time, held as **state and not as markup**.
 *
 * The distinction is the whole requirement. `muRenderSel` repaints `#mu-sbar` on any row tick (FR-278-15), so an
 * `aria-disabled` written into the bar's markup is drawn away mid-flight and the next click lands on a live
 * button. The bars therefore ask this flag what to draw, and the handler refuses from this flag rather than from
 * what is on screen.
 *
 * [enter] is the claim, and it has to be taken **synchronously on the press**: `scope.launch` dispatches rather
 * than running, so a guard taken inside the coroutine lets every click in the wait through — which is exactly
 * how three clicks fired three whole-set resolves. [release] belongs in a `finally`, because a pass that fails
 * must leave the control pressable (acceptance 13).
 */
internal class MuPendingPress {
    var busy: Boolean = false
        private set

    /** `true` for the press that takes the pass, `false` for every press until [release]. */
    fun enter(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    fun release() {
        busy = false
    }
}

/**
 * FR-292-17(a) — the *Set version…* control, shared by both of Songs' selection bars and the album page's.
 *
 * While its preview is out it says what it is doing and refuses the press: the label swap is the admin's own
 * present-participle idiom (`TrackEditor.kt`, `BulkReorderWizard.kt`), `aria-disabled` is `MusicFilesTab.kt`'s
 * shipped shape for a control the handler will not act on, and the dimming is the Seerr download dialog's
 * (`Suggestions.kt`). No new class, so no served stylesheet changes.
 */
internal fun muVersionBulkBtnHtml(pending: Boolean): String =
    if (pending) """<span class="btn sm primary" data-vbulk aria-disabled="true" aria-busy="true" style="opacity:.55">Reading the versions…</span>"""
    else """<span class="btn sm primary" data-vbulk>Set version…</span>"""

/**
 * FR-292-17(b) — what the dialog's footer becomes while the write is out, in place of *Cancel* and *Apply to N*:
 * `music.css`'s own `.mu-busy` + `.sp`, the component the album page has spun since 2026-10-01. Nothing to press,
 * and no determinate bar — one transaction has no honest fraction to report. `.mu-busy` carries its own
 * `padding:14px 2px`, which is the gap the footer row's `margin-top:14px` was giving.
 */
internal fun muVersionBulkWritingHtml(songs: Int): String =
    """<div class="mu-busy"><span class="sp"></span>Setting the version on $songs song${if (songs == 1) "" else "s"}…</div>"""
