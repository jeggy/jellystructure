package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.PipelinePlanStep
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/*
 * Phase 265 — the pipeline's step table and Phase 154's pre-run dialog, moved out of Settings.kt so every
 * manual full run opens the same dialog: Settings' *Run pipeline now*, Library's and Dashboard's *Scan
 * library* (both faces), and the command palette's *Start full scan*.
 */

internal data class PipeBlockDef(
    val name: String, val subtitle: String, val color: String, val icon: String,
    val needsArr: Boolean = false,
)

private const val PIPE_SCAN_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="7" cy="7" r="4.4"/><line x1="10.4" y1="10.4" x2="14" y2="14"/></svg>"""
private const val PIPE_TMDB_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3.5" width="12" height="9" rx="1.6"/><line x1="2" y1="6.4" x2="14" y2="6.4"/><line x1="4.4" y1="9.2" x2="8.4" y2="9.2"/></svg>"""
private const val PIPE_ART_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2.6" width="12" height="10.8" rx="1.6"/><circle cx="5.6" cy="6" r="1.2"/><polyline points="3,12 6.4,8.6 9,11 11,9 13.4,11.4"/></svg>"""
private const val PIPE_NFO_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 1.9h4.2L12 5.5V14.1H4Z"/><polyline points="8,1.9 8,5.6 12,5.6"/><line x1="6" y1="9.1" x2="10" y2="9.1"/><line x1="6" y1="11.3" x2="10" y2="11.3"/></svg>"""
private const val PIPE_SYNC_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13 8a5 5 0 1 1-1.5-3.6"/><polyline points="13.2,2.4 13.3,5 10.6,5.2"/></svg>"""
private const val PIPE_ARR_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M2 5.2h4l1.2 1.4h6.8V12.4H2Z"/><path d="M9.5 9.4a2 2 0 1 1-.6-1.5"/><polyline points="10.4,7.3 10.5,9 8.9,9"/></svg>"""
private const val PIPE_DRIFT_IC= """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 1.9 14.6 13.5H1.4Z"/><line x1="8" y1="6.4" x2="8" y2="9.6"/><line x1="8" y1="11.4" x2="8" y2="11.5"/></svg>"""
private const val PIPE_NOTIFY_IC="""<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6.6a4 4 0 0 1 8 0c0 2.8 1.2 3.7 1.2 3.7H2.8S4 9.4 4 6.6Z"/><path d="M6.6 12.6a1.5 1.5 0 0 0 2.8 0"/></svg>"""
internal const val PIPE_WAIT_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8.8" r="5.1"/><line x1="8" y1="8.8" x2="8" y2="5.8"/><line x1="8" y1="8.8" x2="10" y2="9.8"/><line x1="6.2" y1="1.9" x2="9.8" y2="1.9"/></svg>"""
private const val PIPE_IMDB_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M8 1.6 9.6 5.9l4.5.2-3.6 2.8 1.3 4.4L8 10.6l-3.8 2.7 1.3-4.4-3.6-2.8 4.5-.2Z"/></svg>"""
private const val PIPE_SEG_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3.2v9.6l6-4.8Z"/><line x1="11" y1="3.2" x2="11" y2="12.8"/><line x1="13.4" y1="3.2" x2="13.4" y2="12.8"/></svg>"""
private const val PIPE_VERIFY_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 1.8 13.4 4v4c0 3.2-2.3 5.4-5.4 6.3C4.9 13.4 2.6 11.2 2.6 8V4Z"/><polyline points="5.4,8.2 7.3,10 10.8,6.4"/></svg>"""
private const val PIPE_LENGTHS_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><line x1="2" y1="4.6" x2="14" y2="4.6"/><line x1="2" y1="8" x2="9.6" y2="8"/><line x1="2" y1="11.4" x2="14" y2="11.4"/><line x1="12" y1="6.6" x2="12" y2="9.4"/></svg>"""
private const val PIPE_REC_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 13.6s-5.4-3.2-5.4-7A2.9 2.9 0 0 1 8 5a2.9 2.9 0 0 1 5.4 1.6c0 3.8-5.4 7-5.4 7Z"/></svg>"""
private const val PIPE_SUB_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="1.6" y="3.6" width="12.8" height="8.8" rx="1.6"/><line x1="4" y1="7" x2="7" y2="7"/><line x1="4" y1="9.4" x2="9.4" y2="9.4"/><line x1="9" y1="7" x2="12" y2="7"/></svg>"""

internal val PIPE_BLOCKS = mapOf(
    "scan_files"    to PipeBlockDef("Scan media files",          "New & changed files + stale re-checks by release age.", "#7b6ef0", PIPE_SCAN_IC),
    "pull_tmdb"     to PipeBlockDef("Pull TMDB metadata",        "Match titles · metadata · original language.",           "#3fb6f5", PIPE_TMDB_IC),
    "fetch_artwork" to PipeBlockDef("Download artwork",          "Poster · fanart · logo · stills from TMDB.",            "#b15cd0", PIPE_ART_IC),
    "detect_segments" to PipeBlockDef("Detect intro & credits",  "Chapter-title match + ffmpeg black-frame / silence for Skip Intro / Skip Credits.", "#e0954a", PIPE_SEG_IC),
    "write_nfo"     to PipeBlockDef("Write NFO files",           "Write .nfo files to disk.",                             "#2dd49a", PIPE_NFO_IC),
    "sync_jellyfin" to PipeBlockDef("Sync Jellyfin",             "POST /Items/{id}/Refresh so Jellyfin re-reads the NFOs.","#18c2d4", PIPE_SYNC_IC),
    "rescan_arr"    to PipeBlockDef("Rescan in Radarr / Sonarr", "Nudge the *arr that manages each touched title.",       "#f5b542", PIPE_ARR_IC, needsArr = true),
    "detect_drift"  to PipeBlockDef("Detect drift",              "Compare Jellyfin ⇄ NFO and flag differences.",          "#ff6f61", PIPE_DRIFT_IC),
    "sync_imdb_ratings" to PipeBlockDef("Sync IMDb ratings",     "Refresh aggregate rating · votes from imdb.com.",    "#f5c518", PIPE_IMDB_IC),
    "prewarm_subtitles" to PipeBlockDef("Pre-warm subtitles",    "Ask Jellyfin to extract embedded text subtitles ahead of playback (Phase 179).", "#6fd0c8", PIPE_SUB_IC),
    // Phase 261 (FR-261-4) — the two file checks, one queue job per due file.
    "verify_files"  to PipeBlockDef("Verify video files",        "Read each file end to end for damage a player would hit mid-film (phase 254).", "#5fbf6a", PIPE_VERIFY_IC),
    "check_track_lengths" to PipeBlockDef("Check track lengths", "Find audio/video tracks that stop before the file does (phase 255).",           "#8fa8e8", PIPE_LENGTHS_IC),
    // Phase 269 (FR-269-8) — a whole-library step: every viewer's Recommended list, on its own cadence.
    "build_recommendations" to PipeBlockDef("Build recommendations", "Each viewer's Recommended list, from what they watch in Jellyfin (phase 269).", "#e86f9a", PIPE_REC_IC),
    // Phase 275 — the music library: whole-library, runs even when no film changed.
    "scan_music"    to PipeBlockDef("♪ Scan music",              "Artists · albums · songs from Jellyfin's music library. No file probing.", "#9b7bf5", PIPE_SCAN_IC),
    // Phase 280 — the audiobook libraries: the folder is the book; a gap or two books in one folder is flagged.
    "scan_audiobooks" to PipeBlockDef("Scan audiobooks",         "Jellyfin's audiobook files grouped into books, one folder each. Flags a missing part or two books in one folder.", "#9b7bf5", PIPE_SCAN_IC),
    // Phase 276 — one request a second; unmatched albums are retried once a day.
    "match_musicbrainz" to PipeBlockDef("♪ Match on MusicBrainz", "Albums and artists against MusicBrainz — one request a second.", "#ba478f", PIPE_TMDB_IC),
    // Phase 277 — the music library's files.
    "fetch_music_artwork" to PipeBlockDef("♪ Covers & artist pictures", "Cover Art Archive covers · fanart.tv or Commons pictures · Wikipedia biographies.", "#b15cd0", PIPE_ART_IC),
    "fetch_lyrics"  to PipeBlockDef("♪ Lyrics",                 "Synced lyrics from LRCLIB as a .lrc beside each song.", "#6fd0c8", PIPE_SUB_IC),
    "write_music_nfo" to PipeBlockDef("♪ Write album.nfo / artist.nfo", "Kodi's music NFOs, which Jellyfin reads. Only matched albums.", "#2dd49a", PIPE_NFO_IC),
    "notify"        to PipeBlockDef("Send notification",         "Ping your webhook when the run reaches here.",          "#e0639a", PIPE_NOTIFY_IC),
    "wait"          to PipeBlockDef("Wait",                      "Pause before the next step (let Jellyfin settle).",     "#9aa0b4", PIPE_WAIT_IC),
)
internal val FILE_CHECK_STEPS = setOf("verify_files", "check_track_lengths")
internal val PIPE_SHORT   = mapOf("scan_files" to "Scan","pull_tmdb" to "TMDB","fetch_artwork" to "Artwork","detect_segments" to "Segments","write_nfo" to "NFO","sync_jellyfin" to "Jellyfin","rescan_arr" to "*arr","detect_drift" to "Drift","sync_imdb_ratings" to "IMDb","prewarm_subtitles" to "Subtitles","verify_files" to "Verify","check_track_lengths" to "Lengths","build_recommendations" to "For you","scan_music" to "♪ Music","scan_audiobooks" to "Audiobooks","match_musicbrainz" to "♪ MusicBrainz","fetch_music_artwork" to "♪ Art","fetch_lyrics" to "♪ Lyrics","write_music_nfo" to "♪ NFO","notify" to "Notify","wait" to "Wait")

/**
 * Phase 154 (FR-PIPE1-1..5) — pre-run dialog. Lists the steps that will actually run and lets the operator
 * untick any of them for THIS run only; nothing here touches Settings' edit buffer or persisted config.
 * Phase 265 (FR-265-5) — the list is `GET /api/pipeline/plan`, the steps the server will run in the order it
 * runs them, so an install with no step enabled lists the built-in default it really runs. Settings adds an
 * explicit note when its unsaved edits differ from that, so the dialog can never claim a run will do
 * something it won't.
 */
internal const val PIPE_SKIP_KEY = "js-pipeline-skip"

private fun showPipelineRunDialog(title: String, full: Boolean, plan: List<PipelinePlanStep>, hasUnsavedEdits: Boolean, onStart: (List<String>) -> Unit) {
    // Phase 265 (FR-265-5): the server's plan, in execution order — not the config object.
    val enabled = plan
    // FR-PIPE1-5: restore the last choice, but only for steps that still exist and are enabled.
    val remembered = (localStorage.getItem(PIPE_SKIP_KEY) ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
    val skipped = remembered.filterTo(mutableSetOf()) { key -> enabled.any { it.step == key } && key != "scan_files" }

    val back = document.createElement("div") as HTMLElement
    back.className = "modal-back open"
    val rows = enabled.joinToString("") { step ->
        val def = PIPE_BLOCKS[step.step]
        val name = def?.name ?: step.step
        val sub = def?.subtitle ?: ""
        // FR-PIPE1-4: discovery runs regardless of the list it's handed, so its box is ticked and disabled.
        val locked = step.step == "scan_files"
        val checked = if (locked || step.step !in skipped) "checked" else ""
        val note = when {
            locked -> """<div class="muted" style="font-size:.76rem;margin-top:3px;">Always runs — file discovery can't be skipped.</div>"""
            // FR-PIPE1-3: the whole point of the dialog — say plainly why this one is usually safe to drop.
            step.step == "detect_segments" -> """<div style="font-size:.76rem;margin-top:3px;color:var(--warn);">Usually safe to skip — by far the slowest step (hours; it decodes each episode), and it only powers Skip&nbsp;Intro / Skip&nbsp;Credits. Already-detected markers are kept.</div>"""
            // Phase 261 (FR-261-8) — what unticking one of the file steps actually skips.
            step.step in FILE_CHECK_STEPS -> """<div class="muted" style="font-size:.76rem;margin-top:3px;">Only queues the files that are due; the reading happens on the segments queue. Unticked, nothing is queued this run.</div>"""
            // Phase 272 (FR-272-7) — a run started here builds whatever the schedule says.
            step.step == "build_recommendations" -> """<div class="muted" style="font-size:.76rem;margin-top:3px;">Rebuilds now — the scheduled run rebuilds ${(step.rebuildEvery ?: "weekly").esc()}. With AI re-ranking on, every viewer is then queued for it (Activity ▸ Jobs &amp; workers).</div>"""
            else -> ""
        }
        """
        <label class="row" style="align-items:flex-start;gap:10px;padding:9px 2px;border-bottom:1px solid var(--line);cursor:${if (locked) "default" else "pointer"};">
          <input type="checkbox" data-step="${step.step}" $checked ${if (locked) "disabled" else ""} style="margin-top:3px;flex:none;accent-color:#7b6ef0;">
          <span style="min-width:0;">
            <span style="display:block;font-weight:600;">$name</span>
            <span class="muted" style="font-size:.8rem;">$sub</span>
            $note
          </span>
        </label>"""
    }
    val unsavedNote = if (!hasUnsavedEdits) "" else
        """<div class="muted" style="font-size:.8rem;color:var(--warn);margin-bottom:10px;">You have unsaved pipeline edits. This run uses the <b>saved</b> pipeline shown below — save first if you want your changes applied.</div>"""
    back.innerHTML = """
        <div class="modal">
          <span class="x" id="prun-x">✕</span>
          <h3>${title.esc()}</h3>
          <p class="muted" style="margin:0 0 12px;">
            ${if (full) "Every step sees the whole library — no freshness filter. " else ""}Untick anything you want to skip <b>this run only</b>; your saved pipeline isn't changed.
          </p>
          $unsavedNote
          <div style="max-height:46vh;overflow:auto;margin-bottom:14px;">$rows</div>
          <div class="row" style="justify-content:flex-end;gap:8px;">
            <button id="prun-cancel" class="btn ghost">Cancel</button>
            <button id="prun-go" class="btn primary">Start run</button>
          </div>
        </div>""".trimIndent()
    document.body?.appendChild(back)

    var escHandler: ((org.w3c.dom.events.Event) -> Unit)? = null
    fun close() {
        escHandler?.let { document.removeEventListener("keydown", it) }
        back.remove()
    }
    escHandler = { e -> if ((e as? KeyboardEvent)?.key == "Escape") close() }
    document.addEventListener("keydown", escHandler)
    back.querySelector("#prun-x")?.addEventListener("click") { close() }
    back.querySelector("#prun-cancel")?.addEventListener("click") { close() }
    back.addEventListener("click") { e -> if (e.target == back) close() }
    back.querySelector("#prun-go")?.addEventListener("click") {
        val skip = mutableListOf<String>()
        val boxes = back.querySelectorAll("input[type=checkbox][data-step]")
        for (i in 0 until boxes.length) {
            val box = boxes.item(i) as? HTMLInputElement ?: continue
            val key = box.getAttribute("data-step") ?: continue
            if (!box.checked && key != "scan_files") skip.add(key)
        }
        localStorage.setItem(PIPE_SKIP_KEY, skip.joinToString(","))
        close()
        onStart(skip)
    }
}

/** " · skipped Segments, Verify" for a start toast, or "" when nothing was skipped. */
internal fun skippedSuffix(skipSteps: List<String>): String =
    if (skipSteps.isEmpty()) "" else " · skipped ${skipSteps.mapNotNull { PIPE_SHORT[it] ?: it }.joinToString(", ")}"

internal fun showPipelineToast(msg: String) {
    val t = document.createElement("div") as HTMLElement
    t.textContent = msg
    t.style.cssText = "position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:var(--fill-3);border:1px solid var(--line-2);border-radius:8px;padding:8px 16px;font-size:.82rem;z-index:9999;pointer-events:none"
    document.body?.appendChild(t)
    window.setTimeout({ document.body?.removeChild(t); null }, 2000)
}

/**
 * Phase 265 (FR-265-2/3/5/6) — the one way into a manual full run. Reads the server's plan, then opens the
 * dialog titled [title] (the button that was pressed); *Start run* hands the skip list to [onStart]. A plan
 * with nothing but discovery in it starts at once: a dialog whose only row is a disabled tick box is a
 * wasted click. If the plan can't be read, nothing starts — starting blind would skip the preview the
 * operator asked to see.
 */
internal fun openPipelineRunDialog(
    scope: CoroutineScope,
    title: String,
    full: Boolean,
    hasUnsavedEdits: Boolean = false,
    onStart: suspend (List<String>) -> Unit,
) {
    scope.launch {
        val plan = MediaApi.pipelinePlan()
        if (plan == null) {
            showPipelineToast("Couldn't read what the run would do — nothing was started")
            return@launch
        }
        if (plan.all { it.step == "scan_files" }) {
            onStart(emptyList())
            return@launch
        }
        showPipelineRunDialog(title, full, plan, hasUnsavedEdits) { skip -> scope.launch { onStart(skip) } }
    }
}
