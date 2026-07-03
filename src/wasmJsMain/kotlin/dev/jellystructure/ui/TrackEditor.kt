@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.media.TrackCommandBuilder
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

// ── Model ──────────────────────────────────────────────────────────────────────

internal data class TrkModel(
    val sp: String,
    val kind: TrackKind,
    val codec: String,
    val title: String?,
    var lang: String?,
    var def: Boolean,
    var forced: Boolean,
    val streamIndex: Int = -1,
)

private fun Track.toModel() = TrkModel(specifier, kind, codec, title, language, default, forced, streamIndex)

// Phase 127: shared by buildCommandText/renderPending/applyChanges for both audio and subtitle blocks —
// factored out after the Phase 120 bug where this exact diff, duplicated per call site, was fixed
// incorrectly in one place at a time. A per-track flag diff (not just comparing the first default)
// so "keep this one" registers even when the kept track was already first-default.
private fun defaultChanged(model: List<TrkModel>, original: List<TrkModel>): Boolean =
    model.any { m -> original.find { it.sp == m.sp }?.def != m.def }

// ── JS helpers (must be top-level for WASM interop) ──────────────────────────

private fun trkRectBottom(el: HTMLElement): Double = js("el.getBoundingClientRect().bottom")
private fun trkRectLeft(el: HTMLElement): Double = js("el.getBoundingClientRect().left")
private fun trkWinInnerWidth(): Double = js("window.innerWidth")

// ── Searchable language menu ───────────────────────────────────────────────────

private var trkLangMenuEl: HTMLElement? = null
private var trkMenuListenerInstalled = false

private fun ensureTrkMenuDocumentListener() {
    if (trkMenuListenerInstalled) return
    trkMenuListenerInstalled = true
    document.addEventListener("click") { _ ->
        trkLangMenuEl?.remove()
        trkLangMenuEl = null
    }
}

internal fun closeTrkLangMenu() {
    trkLangMenuEl?.remove()
    trkLangMenuEl = null
}

internal fun openTrkLangMenu(anchor: HTMLElement, currentCode: String?, onPick: (String) -> Unit) {
    ensureTrkMenuDocumentListener()
    closeTrkLangMenu()
    injectPickerStyles()  // reuse the shared searchable-picker styling (.lp-*) — no bespoke menu CSS

    // ffprobe languages are often 3-letter (fao/eng); the option list is 2-letter — normalize so the
    // current-selection marker highlights the right row (Phase 45 C). Independent of the Phase 46 writes.
    val current = currentCode?.let { LanguageResolver.normalize(it.trim().lowercase()) }
    val langs = getLanguages()

    val menu = document.createElement("div") as HTMLElement
    menu.className = "lp-dropdown"
    val searchInput = document.createElement("input") as HTMLInputElement
    searchInput.type = "text"
    searchInput.className = "lp-search"
    searchInput.placeholder = "Search language…"
    searchInput.setAttribute("autocomplete", "off")
    val listEl = document.createElement("div") as HTMLElement
    listEl.className = "lp-list"
    menu.appendChild(searchInput)
    menu.appendChild(listEl)
    document.body?.appendChild(menu)
    trkLangMenuEl = menu

    // Stop clicks inside the menu from closing it via the document listener
    menu.addEventListener("click") { it.stopPropagation() }

    // Body-mounted + position:fixed so the menu is never clipped by the episode modal's overflow.
    val bottom = trkRectBottom(anchor)
    val left = trkRectLeft(anchor)
    val winW = trkWinInnerWidth()
    menu.style.setProperty("top", "${bottom + 3}px")
    menu.style.setProperty("left", "${minOf(left, winW - 248)}px")
    menu.style.setProperty("width", "240px")

    var filtered = langs
    var activeIdx = 0

    fun draw() {
        listEl.innerHTML = if (filtered.isEmpty()) {
            """<div class="lp-opt" style="opacity:.6;cursor:default;">No language matches</div>"""
        } else {
            filtered.mapIndexed { i, (code, name) ->
                val cls = "lp-opt" + (if (i == activeIdx) " active" else "") + (if (code == current) " cur" else "")
                """<div class="$cls" data-code="$code">${name.esc()} (${code.esc()})</div>"""
            }.joinToString("")
        }
    }

    fun filter() {
        val q = searchInput.value.trim().lowercase()
        filtered = if (q.isEmpty()) langs
        else langs.filter { (code, name) -> name.lowercase().contains(q) || code.contains(q) }
        activeIdx = 0
        draw()
    }

    fun choose(code: String) {
        onPick(code)
        closeTrkLangMenu()
    }

    searchInput.addEventListener("input") { filter() }
    searchInput.addEventListener("keydown") { e ->
        val ke = e as? KeyboardEvent ?: return@addEventListener
        when (ke.key) {
            "ArrowDown" -> { ke.preventDefault(); activeIdx = minOf(filtered.size - 1, activeIdx + 1); draw() }
            "ArrowUp" -> { ke.preventDefault(); activeIdx = maxOf(0, activeIdx - 1); draw() }
            "Enter" -> { ke.preventDefault(); if (activeIdx < filtered.size) choose(filtered[activeIdx].first) }
            "Escape" -> { ke.preventDefault(); closeTrkLangMenu() }
        }
    }
    listEl.addEventListener("click") { e ->
        val opt = (e.target as? HTMLElement)?.closest(".lp-opt") as? HTMLElement ?: return@addEventListener
        val code = opt.getAttribute("data-code") ?: return@addEventListener
        choose(code)
    }

    draw()
    searchInput.focus()
}

// ── Shell HTML ─────────────────────────────────────────────────────────────────

/**
 * Returns the HTML skeleton for the unified track editor.
 * All element IDs are prefixed with [prefix].
 * For the movie Tracks tab use prefix="trk", for the episode modal use prefix="te".
 */
fun buildUnifiedTrackEditorShell(prefix: String, filePath: String): String {
    val fileName = filePath.substringAfterLast('/')
    return """
        <div id="$prefix-cascade" class="note" style="background:var(--warn-soft);border-color:rgba(245,181,66,.4);display:none;flex;gap:11px;align-items:flex-start;">
          <span class="badge warn" style="flex:none;">Default ≠ resolved</span>
          <div class="tiny" style="line-height:1.6;">The default audio track is <b id="$prefix-cascade-def"></b> but metadata resolves differently. Viewers would <b>read</b> one language while <b>hearing</b> another.
            <span class="btn sm" id="$prefix-cascade-fix" style="margin-left:4px;">Fix default</span>
          </div>
        </div>
        <div class="card" id="$prefix-main-card" style="margin-top:0;">
          <div class="row center">
            <h4 style="margin:0;white-space:nowrap;">Tracks — language &amp; order</h4>
            <span class="seg" id="$prefix-seg" style="margin-left:12px;">
              <span class="on" data-tk="audio">Audio</span><span data-tk="subs">Subtitles</span>
            </span>
            <span class="spacer"></span>
            <span class="chip seeded" id="$prefix-guard" title="qBittorrent seeding guard active — edits blocked while this file is being seeded." style="display:none;">⛨ guard active</span>
            <span class="mono tiny muted" id="$prefix-file">${fileName.esc()}</span>
          </div>
          <hr class="dash" style="margin:12px 0;">
          <div style="display:grid;grid-template-columns:30px 30px 58px 1fr auto;gap:12px;align-items:center;padding:0 12px 8px;font-size:.68rem;text-transform:uppercase;letter-spacing:.06em;color:var(--ink-dim);font-weight:600;">
            <span></span><span>#</span><span>track</span>
            <span id="$prefix-col-mid">language &amp; codec</span>
            <span style="text-align:right;">default</span>
          </div>
          <div id="$prefix-list"></div>
          <div class="note blue" id="$prefix-explain" style="margin-top:14px;"></div>
            <div class="tiny muted" style="margin-top:10px;">Reorder by dragging the grip or the ▲▼ buttons · click a language to change it · ★ sets the default. Changes are <b>saved only when you click Apply</b>.</div>
          <div id="$prefix-pending" style="display:none;margin-top:14px;border:1px solid rgba(245,181,66,.45);border-radius:var(--radius-s);background:var(--warn-soft);padding:14px 16px;">
            <div class="row center" style="margin-bottom:10px;gap:8px;">
              <span class="badge warn" id="$prefix-pending-badge">0 pending</span>
              <span class="spacer"></span>
              <button id="$prefix-discard" class="btn sm ghost">Discard</button>
              <button id="$prefix-apply" class="btn sm">Apply</button>
            </div>
            <div id="$prefix-pending-list" style="display:flex;flex-direction:column;gap:6px;"></div>
          <div style="margin-top:12px;">
            <div class="tiny muted" style="margin-bottom:5px;letter-spacing:.05em;text-transform:uppercase;font-size:.65rem;font-weight:600;">Command preview</div>
            <pre id="$prefix-cmd" style="margin:0;background:var(--fill-2);border:1px solid var(--line);border-radius:var(--radius-s);padding:10px 12px;font-family:'JetBrains Mono',monospace;font-size:.72rem;line-height:1.6;overflow-x:auto;white-space:pre;color:var(--ink-soft);max-height:200px;overflow-y:auto;"></pre>
          </div>
          </div>
        </div>
    """.trimIndent()
}

// ── Wiring ─────────────────────────────────────────────────────────────────────

/**
 * Sets up the full model-driven track editor after the shell HTML is in the DOM.
 *
 * @param prefix       ID prefix matching the shell ("trk" for movie, "te" for episode)
 * @param tracks       Tracks from the media item or episode
 * @param mediaId      Media item ID for API calls
 * @param epFilename   Episode filename for episode-scoped API calls (null → movie)
 * @param scope        CoroutineScope for async API calls
 * @param resolvedLanguage  Resolved metadata language (used for cascade alert on audio)
 * @param filePath     Full file path (for command preview)
 */
fun wireUnifiedTrackEditor(
    prefix: String,
    tracks: List<Track>,
    mediaId: String,
    epFilename: String?,
    scope: CoroutineScope,
    resolvedLanguage: String?,
    filePath: String,
    postApply: (() -> Unit)? = null,
) {
    val audioModel = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.toModel() }.toMutableList()
    val subsModel = tracks.filter { it.kind == TrackKind.SUBTITLE }.map { it.toModel() }.toMutableList()
    // Snapshots of the on-disk state — diff against these to compute pending changes.
    val audioOriginal = audioModel.map { it.copy() }.toMutableList()
    val subsOriginal = subsModel.map { it.copy() }.toMutableList()
    var currentKind = "audio"
    var dragIdx: Int? = null

    fun model() = if (currentKind == "audio") audioModel else subsModel

    val listEl = document.getElementById("$prefix-list") as? HTMLElement ?: return
    val segEl = document.getElementById("$prefix-seg") as? HTMLElement ?: return
    val colMidEl = document.getElementById("$prefix-col-mid") as? HTMLElement
    val explainEl = document.getElementById("$prefix-explain") as? HTMLElement
    val cascadeEl = document.getElementById("$prefix-cascade") as? HTMLElement
    val cascadeDefEl = document.getElementById("$prefix-cascade-def") as? HTMLElement

    fun langShortName(code: String?): String {
        if (code.isNullOrBlank()) return "untagged"
        // Phase 72: normalize so a 3-letter tag (eng) resolves to its name, not the raw code.
        val norm = LanguageResolver.normalize(code.trim().lowercase())
        val found = getLanguages().find { it.first == norm || it.first == code }
        return found?.second ?: code
    }

    fun cascade() {
        cascadeEl ?: return
        if (currentKind != "audio" || resolvedLanguage.isNullOrBlank()) {
            cascadeEl.style.display = "none"
            return
        }
        val def = audioModel.find { it.def }
        // Phase 72: compare via canonical equivalence so eng==en doesn't false-fire the banner.
        val show = def != null && !def.lang.isNullOrBlank() && !LanguageResolver.sameLanguage(def.lang, resolvedLanguage)
        cascadeEl.style.display = if (show) "flex" else "none"
        if (show && cascadeDefEl != null && def != null) {
            cascadeDefEl.innerHTML = """<span class="lang">${def.lang!!.esc()}</span> (${langShortName(def.lang)})"""
        }
    }

    val audioExplain = """<div style="display:grid;grid-template-columns:auto 1fr;gap:7px 12px;align-items:baseline;">
        <b style="white-space:nowrap;">▲▼ Order</b><span class="tiny">The track's index in the file. Jellystructure reads audio tracks <b>top-down</b> to decide the <b>metadata language</b> (first match wins); players also fall back to the first track.</span>
        <b style="white-space:nowrap;">★ Default</b><span class="tiny">The track the player <b>auto-selects on playback</b>. Independent of order, changing it <b>never</b> affects the metadata language.</span>
    </div>"""

    val subsExplain = """<div style="display:grid;grid-template-columns:auto 1fr;gap:7px 12px;align-items:baseline;">
        <b style="white-space:nowrap;">★ Default</b><span class="tiny">The subtitle shown automatically when subtitles are on.</span>
        <b style="white-space:nowrap;">⮕ Forced</b><span class="tiny">A <b>separate</b> flag — shows only foreign-dialogue lines over a known-language audio. A track can be forced without being default.</span>
    </div>"""

    fun renderList() {
        val arr = model()
        colMidEl?.textContent = if (currentKind == "audio") "language & codec" else "language, codec & forced"
        explainEl?.innerHTML = if (currentKind == "audio") audioExplain else subsExplain

        listEl.innerHTML = arr.mapIndexed { i, t ->
            val isUntagged = t.lang.isNullOrBlank()
            val defsCount = arr.count { it.def }
            val langBadge = if (isUntagged) {
                """<span class="badge bad">no language</span>"""
            } else {
                """<span class="lang">${t.lang!!.esc()}</span> <span class="muted tiny">${langShortName(t.lang).esc()}</span>"""
            }
            val langCell = """<span class="lang-pickwrap" data-act="lang" data-i="$i" style="cursor:pointer;display:inline-flex;align-items:center;gap:3px;padding:2px 4px;border-radius:6px;">$langBadge <span class="muted" style="font-size:.7rem;">▾</span></span>"""
            val metaCell = """<span class="muted tiny mono">${t.codec.esc()}${if (!t.title.isNullOrBlank()) " · &quot;${t.title!!.esc()}&quot;" else ""}</span>"""
            val forcedCell = if (currentKind == "subs") {
                """<label class="row center tiny" style="gap:6px;cursor:pointer;"><span class="mini-toggle${if (t.forced) " on" else ""}" data-act="forced" data-i="$i"></span> forced</label>"""
            } else ""
            val starBtn = when {
                t.def && defsCount == 1 -> """<span class="badge warn">★ default</span>"""
                t.def -> """<span class="btn sm star-btn" data-act="default" data-i="$i">★ keep this one</span>"""
                else -> """<span class="btn sm ghost star-btn" data-act="default" data-i="$i">set default ★</span>"""
            }
            val trkCls = buildList {
                if (isUntagged) add("untagged")
                if (t.def) add("isdef")
            }.joinToString(" ")
            """<div class="trk $trkCls" data-trk-i="$i" draggable="true">
              <div class="trk-main">
                <span class="grip" title="Drag to reorder">⠿</span>
                <span class="ord">
                  <span class="ord-btn" data-act="up" data-i="$i"${if (i == 0) " disabled" else ""}>▲</span>
                  <span class="ord-btn" data-act="down" data-i="$i"${if (i == arr.size - 1) " disabled" else ""}>▼</span>
                </span>
                <span class="pos">${i + 1}</span>
                <span class="trk-mid">
                  <span class="num" style="width:48px;font-size:.75rem;">${t.sp.esc()}</span>
                  $langCell
                  $metaCell
                </span>
                <span class="trk-right">$forcedCell $starBtn</span>
              </div>
            </div>"""
        }.joinToString("")

        cascade()
        // Keep the pagebar audio-flag strip in sync with language/order changes (Phase 87).
        if (currentKind == "audio") refreshPagebarAudioFlags(audioModel)
    }

    // ── Pending-changes panel ───────────────────────────────────────────────────
    val isMkv = filePath.substringAfterLast('.').lowercase() == "mkv"
    val pendingEl  = document.getElementById("$prefix-pending") as? HTMLElement
    val pendingBadge = document.getElementById("$prefix-pending-badge") as? HTMLElement
    val pendingList  = document.getElementById("$prefix-pending-list") as? HTMLElement
    val cmdEl = document.getElementById("$prefix-cmd") as? HTMLElement

    fun pendingRow(desc: String, tool: String, slow: Boolean): String {
        val toolLabel = if (slow) "$tool · slow (remux)" else "$tool · ~40 ms"
        val toolColor = if (slow) "var(--bad)" else "var(--ok)"
        return """<div style="display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:.82rem;">
            <span>${desc.esc()}</span>
            <span style="white-space:nowrap;font-family:'JetBrains Mono',monospace;font-size:.7rem;color:$toolColor;flex:none;">${toolLabel.esc()}</span>
          </div>"""
    }

    // Generates the exact shell commands that Apply will run — mirrors applyChanges() step-by-step.
    // Uses TrackCommandBuilder (shared with the backend runners) so the preview is always faithful.
    fun buildCommandText(): String {
        val cmds = mutableListOf<String>()
        // 1. Language changes — one command per changed track (audio then subs)
        for (trk in audioModel) {
            val orig = audioOriginal.find { it.sp == trk.sp } ?: continue
            if (trk.lang != orig.lang && !trk.lang.isNullOrBlank()) {
                val cmd = if (isMkv) TrackCommandBuilder.mkvLanguage(filePath, trk.streamIndex, trk.lang!!)
                          else       TrackCommandBuilder.ffmpegLanguage(filePath, trk.streamIndex, trk.lang!!)
                cmd?.let { cmds += it }
            }
        }
        for (trk in subsModel) {
            val orig = subsOriginal.find { it.sp == trk.sp } ?: continue
            if (trk.lang != orig.lang && !trk.lang.isNullOrBlank()) {
                val cmd = if (isMkv) TrackCommandBuilder.mkvLanguage(filePath, trk.streamIndex, trk.lang!!)
                          else       TrackCommandBuilder.ffmpegLanguage(filePath, trk.streamIndex, trk.lang!!)
                cmd?.let { cmds += it }
            }
        }
        // 2. Audio default change
        val audioDefChanged = defaultChanged(audioModel, audioOriginal)
        val newDefAudio = audioModel.firstOrNull { it.def }
        if (audioDefChanged && newDefAudio != null) {
            val sameIdx = audioModel.map { it.streamIndex }
            cmds += if (isMkv) TrackCommandBuilder.mkvDefault(filePath, newDefAudio.streamIndex, sameIdx)
                    else       TrackCommandBuilder.ffmpegDefault(filePath, newDefAudio.streamIndex, sameIdx, "a")
        }
        // 3. Subtitle default change
        val subDefChanged = defaultChanged(subsModel, subsOriginal)
        val newDefSub = subsModel.firstOrNull { it.def }
        if (subDefChanged && newDefSub != null) {
            val sameIdx = subsModel.map { it.streamIndex }
            cmds += if (isMkv) TrackCommandBuilder.mkvDefault(filePath, newDefSub.streamIndex, sameIdx)
                    else       TrackCommandBuilder.ffmpegDefault(filePath, newDefSub.streamIndex, sameIdx, "s")
        }
        // 4. Forced flag changes (MKV only — one command per changed track, clears all others)
        if (isMkv) {
            for (trk in subsModel) {
                val orig = subsOriginal.find { it.sp == trk.sp } ?: continue
                if (trk.forced != orig.forced) {
                    val forcedIdx = if (trk.forced) trk.streamIndex else -1
                    cmds += TrackCommandBuilder.mkvForced(filePath, forcedIdx, subsModel.map { it.streamIndex })
                }
            }
        }
        // 5. Reorder — ffmpeg for both MKV and non-MKV (mkvpropedit can't reorder)
        if (audioModel.map { it.sp } != audioOriginal.map { it.sp })
            cmds += TrackCommandBuilder.ffmpegReorder(filePath, audioModel.map { it.streamIndex }, isAudio = true)
        if (subsModel.map { it.sp } != subsOriginal.map { it.sp })
            cmds += TrackCommandBuilder.ffmpegReorder(filePath, subsModel.map { it.streamIndex }, isAudio = false)
        return cmds.joinToString("\n\n")
    }

    fun renderPending() {
        val pendEl = pendingEl ?: return
        val rows = mutableListOf<String>()

        // Audio language changes
        audioModel.forEachIndexed { i, trk ->
            val orig = audioOriginal.getOrNull(i) ?: return@forEachIndexed
            if (trk.lang != orig.lang) {
                val from = if (orig.lang.isNullOrBlank()) "none" else "${orig.lang} (${langShortName(orig.lang)})"
                val to   = if (trk.lang.isNullOrBlank()) "none" else "${trk.lang} (${langShortName(trk.lang)})"
                rows += pendingRow("Audio ${i+1} (${trk.sp}): language  $from → $to", if (isMkv) "mkvpropedit" else "ffmpeg", !isMkv)
            }
        }

        // Audio default change
        val audioDefChanged = defaultChanged(audioModel, audioOriginal)
        val newDefAudio  = audioModel.firstOrNull  { it.def }
        if (audioDefChanged) {
            val pos = audioModel.indexOf(newDefAudio) + 1
            val name = langShortName(newDefAudio?.lang)
            rows += pendingRow("Audio $pos ($name): set as default", if (isMkv) "mkvpropedit" else "ffmpeg", !isMkv)
        }

        // Audio reorder
        if (audioModel.map { it.sp } != audioOriginal.map { it.sp }) {
            val seq = audioModel.joinToString(" → ") { langShortName(it.lang).ifBlank { it.sp } }
            rows += pendingRow("Audio tracks reordered: $seq", "ffmpeg", true)
        }

        // Subtitle language changes
        subsModel.forEachIndexed { i, trk ->
            val orig = subsOriginal.getOrNull(i) ?: return@forEachIndexed
            if (trk.lang != orig.lang) {
                val from = if (orig.lang.isNullOrBlank()) "none" else "${orig.lang} (${langShortName(orig.lang)})"
                val to   = if (trk.lang.isNullOrBlank()) "none" else "${trk.lang} (${langShortName(trk.lang)})"
                rows += pendingRow("Subtitle ${i+1} (${trk.sp}): language  $from → $to", if (isMkv) "mkvpropedit" else "ffmpeg", !isMkv)
            }
        }

        // Subtitle default change
        val subDefChanged = defaultChanged(subsModel, subsOriginal)
        val newDefSub  = subsModel.firstOrNull  { it.def }
        if (subDefChanged) {
            val pos = subsModel.indexOf(newDefSub) + 1
            rows += pendingRow("Subtitle $pos: set as default", if (isMkv) "mkvpropedit" else "ffmpeg", !isMkv)
        }

        // Subtitle forced changes
        subsModel.forEachIndexed { i, trk ->
            val orig = subsOriginal.getOrNull(i) ?: return@forEachIndexed
            if (trk.forced != orig.forced) {
                rows += pendingRow("Subtitle ${i+1}: forced → ${if (trk.forced) "on" else "off"}", if (isMkv) "mkvpropedit" else "ffmpeg", !isMkv)
            }
        }

        // Subtitle reorder
        if (subsModel.map { it.sp } != subsOriginal.map { it.sp }) {
            rows += pendingRow("Subtitle tracks reordered", "ffmpeg", true)
        }

        if (rows.isEmpty()) {
            pendEl.style.display = "none"
        } else {
            pendEl.style.display = "block"
            pendingBadge?.textContent = "${rows.size} pending ${if (rows.size == 1) "change" else "changes"}"
            pendingList?.innerHTML = rows.joinToString("")
            cmdEl?.textContent = buildCommandText()
        }
    }

    // ── Apply / Discard ─────────────────────────────────────────────────────────

    fun discardChanges() {
        // Restore audio model from snapshot
        audioModel.clear()
        audioModel.addAll(audioOriginal.map { it.copy() })
        // Restore sub model from snapshot
        subsModel.clear()
        subsModel.addAll(subsOriginal.map { it.copy() })
        renderList()
        renderPending()
    }

    fun applyChanges() {
        val applyBtn = document.getElementById("$prefix-apply") as? HTMLElement ?: return
        applyBtn.setAttribute("disabled", "")
        applyBtn.textContent = "Applying…"

        scope.launch {
            var anyError = false

            // 1. Language changes (mkvpropedit/fast — before reorder so specifiers stay valid)
            for (trk in audioModel) {
                val orig = audioOriginal.find { it.sp == trk.sp }
                if (orig != null && trk.lang != orig.lang && !trk.lang.isNullOrBlank()) {
                    val res = if (epFilename == null) MediaApi.setTrackLanguage(mediaId, trk.sp, trk.lang!!)
                              else MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, trk.sp, trk.lang!!)
                    if (res.error != null) anyError = true
                    else if (res.language != null) trk.lang = res.language
                }
            }
            for (trk in subsModel) {
                val orig = subsOriginal.find { it.sp == trk.sp }
                if (orig != null && trk.lang != orig.lang && !trk.lang.isNullOrBlank()) {
                    val res = if (epFilename == null) MediaApi.setTrackLanguage(mediaId, trk.sp, trk.lang!!)
                              else MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, trk.sp, trk.lang!!)
                    if (res.error != null) anyError = true
                    else if (res.language != null) trk.lang = res.language
                }
            }

            // 2. Default changes
            val audioDefChanged = defaultChanged(audioModel, audioOriginal)
            val newDefAudio    = audioModel.firstOrNull { it.def }
            if (audioDefChanged && newDefAudio != null) {
                val err = if (epFilename == null) MediaApi.setDefaultTrack(mediaId, newDefAudio.sp)
                          else MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, newDefAudio.sp)
                if (err != null) anyError = true
            }
            val subDefChanged = defaultChanged(subsModel, subsOriginal)
            val newDefSub    = subsModel.firstOrNull { it.def }
            if (subDefChanged && newDefSub != null) {
                val err = if (epFilename == null) MediaApi.setDefaultTrack(mediaId, newDefSub.sp)
                          else MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, newDefSub.sp)
                if (err != null) anyError = true
            }

            // 3. Forced flag changes (subtitles only)
            for (trk in subsModel) {
                val orig = subsOriginal.find { it.sp == trk.sp }
                if (orig != null && trk.forced != orig.forced) {
                    val ok = if (epFilename == null) MediaApi.setForcedFlag(mediaId, trk.sp, trk.forced)
                             else MediaApi.setEpisodeForcedFlag(mediaId, epFilename, trk.sp, trk.forced)
                    if (!ok) anyError = true
                }
            }

            // 4. Reorder (ffmpeg remux — last, as it changes stream indices on disk). Phase 109: this now
            // enqueues a background job instead of remuxing inline — the order shown here is what will
            // land once the job runs, not what's on disk yet.
            var reorderQueued = false
            val audioNewOrder = audioModel.map { it.sp }
            if (audioNewOrder != audioOriginal.map { it.sp }) {
                val jobId = if (epFilename == null) MediaApi.reorderTracks(mediaId, "audio", audioNewOrder)
                         else MediaApi.reorderEpisodeTracks(mediaId, epFilename, "audio", audioNewOrder)
                if (jobId == null) anyError = true else reorderQueued = true
            }
            val subsNewOrder = subsModel.map { it.sp }
            if (subsNewOrder != subsOriginal.map { it.sp }) {
                val jobId = if (epFilename == null) MediaApi.reorderTracks(mediaId, "subtitle", subsNewOrder)
                         else MediaApi.reorderEpisodeTracks(mediaId, epFilename, "subtitle", subsNewOrder)
                if (jobId == null) anyError = true else reorderQueued = true
            }

            if (!anyError) {
                // Update snapshots to match newly-applied disk state
                audioOriginal.clear(); audioOriginal.addAll(audioModel.map { it.copy() })
                subsOriginal.clear();  subsOriginal.addAll(subsModel.map { it.copy() })
            }

            renderList()
            renderPending()
            val doneMsg = if (reorderQueued) "Changes applied — reorder queued, see Activity ▸ Jobs" else "Changes applied"
            showDetailMsg(if (!anyError) doneMsg else "Some changes failed — see details", !anyError)

            applyBtn.removeAttribute("disabled")
            applyBtn.textContent = "Apply"

            if (!anyError) postApply?.invoke()
        }
    }

    // ── Event wiring ────────────────────────────────────────────────────────────

    // Event delegation on track list — all actions update the local model only; Apply writes to disk.
    listEl.addEventListener("click") { e ->
        val el = (e.target as? HTMLElement)?.closest("[data-act]") as? HTMLElement ?: return@addEventListener
        val act = el.getAttribute("data-act") ?: return@addEventListener
        val i = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        val arr = model()
        when (act) {
            "up" -> if (i > 0) {
                val tmp = arr[i - 1]; arr[i - 1] = arr[i]; arr[i] = tmp
                renderList(); renderPending()
            }
            "down" -> if (i < arr.size - 1) {
                val tmp = arr[i + 1]; arr[i + 1] = arr[i]; arr[i] = tmp
                renderList(); renderPending()
            }
            "default" -> {
                arr.forEach { it.def = false }; arr[i].def = true
                renderList(); renderPending()
            }
            "forced" -> {
                arr[i].forced = !arr[i].forced
                renderList(); renderPending()
            }
            "lang" -> {
                e.stopPropagation()
                val anchor = (e.target as? HTMLElement)?.closest(".lang-pickwrap") as? HTMLElement ?: el
                openTrkLangMenu(anchor, arr[i].lang) { code ->
                    arr[i].lang = code
                    renderList(); renderPending()
                }
            }
        }
    }

    // Drag-and-drop reorder
    listEl.addEventListener("dragstart") { e ->
        val row = (e.target as? HTMLElement)?.closest("[data-trk-i]") as? HTMLElement ?: return@addEventListener
        dragIdx = row.getAttribute("data-trk-i")?.toIntOrNull()
        row.classList.add("dragging")
    }
    listEl.addEventListener("dragend") { _ ->
        dragIdx = null
        clearDragHighlights(listEl)
    }
    listEl.addEventListener("dragover") { e ->
        e.preventDefault()
        val row = (e.target as? HTMLElement)?.closest("[data-trk-i]") as? HTMLElement ?: return@addEventListener
        val to = row.getAttribute("data-trk-i")?.toIntOrNull() ?: return@addEventListener
        val from = dragIdx ?: return@addEventListener
        clearDragHighlights(listEl)
        if (to != from) row.classList.add(if (to < from) "drop-before" else "drop-after")
    }
    listEl.addEventListener("drop") { e ->
        e.preventDefault()
        val row = (e.target as? HTMLElement)?.closest("[data-trk-i]") as? HTMLElement ?: return@addEventListener
        val to = row.getAttribute("data-trk-i")?.toIntOrNull() ?: return@addEventListener
        val from = dragIdx ?: return@addEventListener
        if (from == to) return@addEventListener
        val arr = model()
        val moved = arr.removeAt(from)
        arr.add(to, moved)
        dragIdx = null
        renderList(); renderPending()
    }

    // Segment switch
    segEl.addEventListener("click") { e ->
        val s = (e.target as? HTMLElement)?.closest("[data-tk]") as? HTMLElement ?: return@addEventListener
        segEl.querySelectorAll("[data-tk]").let { nodes ->
            for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.classList?.remove("on")
        }
        s.classList.add("on")
        currentKind = if (s.getAttribute("data-tk") == "subs") "subs" else "audio"
        renderList()
    }

    // Cascade fix — deferred like all other edits
    document.getElementById("$prefix-cascade-fix")?.addEventListener("click") { _ ->
        if (resolvedLanguage.isNullOrBlank()) return@addEventListener
        val target = audioModel.find { it.lang == resolvedLanguage } ?: audioModel.firstOrNull() ?: return@addEventListener
        audioModel.forEach { it.def = false }
        target.def = true
        renderList(); renderPending()
    }

    document.getElementById("$prefix-apply")?.addEventListener("click") { _ -> applyChanges() }
    document.getElementById("$prefix-discard")?.addEventListener("click") { _ -> discardChanges() }

    // Initial render
    renderList()
}

private fun clearDragHighlights(listEl: HTMLElement) {
    val nodes = listEl.querySelectorAll(".trk")
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as? HTMLElement ?: continue
        el.classList.remove("dragging", "drop-before", "drop-after")
    }
}

// ── CSS Injection ──────────────────────────────────────────────────────────────

fun injectTrackEditorStyles() {
    if (document.getElementById("trk-editor-styles") != null) return
    val style = document.createElement("style") as? org.w3c.dom.HTMLStyleElement ?: return
    style.id = "trk-editor-styles"
    style.textContent = """
        .trk { border:1px solid var(--line);border-radius:var(--radius-s);background:var(--fill-2);padding:10px 12px;transition:border-color .15s,box-shadow .15s,opacity .15s,transform .12s; }
        .trk+.trk { margin-top:8px; }
        .trk.untagged { background:var(--bad-soft);border-color:var(--bad); }
        .trk.isdef { border-color:rgba(245,181,66,.45);box-shadow:inset 3px 0 0 var(--warn); }
        .trk.dragging { opacity:.4; }
        .trk.drop-before { box-shadow:0 -3px 0 var(--hi); }
        .trk.drop-after { box-shadow:0 3px 0 var(--hi); }
        .trk-main { display:grid;grid-template-columns:30px 30px 58px 1fr auto;gap:12px;align-items:center; }
        .grip { cursor:grab;color:var(--ink-dim);font-size:1.05rem;line-height:1;text-align:center;user-select:none;letter-spacing:-2px; }
        .grip:active { cursor:grabbing; }
        .ord { display:flex;flex-direction:column;gap:3px; }
        .ord-btn { width:26px;height:16px;display:inline-flex;align-items:center;justify-content:center;font-size:.6rem;border:1px solid var(--line-2);border-radius:5px;background:var(--fill-3);color:var(--ink-soft);cursor:pointer;transition:all .12s; }
        .ord-btn:hover { background:var(--hi-soft);color:var(--hi);border-color:var(--hi); }
        .ord-btn[disabled] { opacity:.3;pointer-events:none; }
        .trk .pos { font-family:'JetBrains Mono',monospace;font-size:.9rem;font-weight:600;color:var(--ink);text-align:center; }
        .trk-mid { display:flex;align-items:center;gap:11px;flex-wrap:wrap;min-width:0; }
        .trk-right { display:flex;align-items:center;gap:8px;justify-content:flex-end; }
        .lang-pickwrap:hover { background:var(--hi-soft); }
        .star-btn { cursor:pointer; }
        .mini-toggle { width:38px;height:21px;border:1px solid var(--line-2);border-radius:20px;background:var(--fill-3);position:relative;cursor:pointer;flex:none;transition:background .2s;display:inline-block; }
        .mini-toggle::after { content:'';position:absolute;top:2px;left:2px;width:15px;height:15px;border-radius:50%;background:var(--ink-soft);transition:left .2s,background .2s; }
        .mini-toggle.on { background:var(--grad);border-color:transparent; }
        .mini-toggle.on::after { left:auto;right:2px;background:#fff; }
        .seeded { background:var(--warn-soft);border-color:rgba(245,181,66,.4);color:var(--warn);cursor:help; }
    """.trimIndent()
    document.head?.appendChild(style)
}

/** ISO-639-1 → ISO-3166-1-alpha-2 for the pagebar audio flag strip (Phase 87, shared with MediaDetail). */
// ISO 639-1 (2-letter) AND ISO 639-2/B + /T (3-letter) → ISO 3166-1-alpha-2 country code.
private val TRACK_LANG_CC = mapOf(
    "en" to "gb", "eng" to "gb",
    "fr" to "fr", "fra" to "fr", "fre" to "fr",
    "de" to "de", "deu" to "de", "ger" to "de",
    "es" to "es", "spa" to "es",
    "da" to "dk", "dan" to "dk",
    "fo" to "fo", "fao" to "fo",
    "is" to "is", "isl" to "is", "ice" to "is",
    "no" to "no", "nor" to "no", "nob" to "no", "nno" to "no",
    "sv" to "se", "swe" to "se",
    "fi" to "fi", "fin" to "fi",
    "nl" to "nl", "nld" to "nl", "dut" to "nl",
    "it" to "it", "ita" to "it",
    "pt" to "pt", "por" to "pt",
    "pl" to "pl", "pol" to "pl",
    "ru" to "ru", "rus" to "ru",
    "ja" to "jp", "jpn" to "jp",
    "ko" to "kr", "kor" to "kr",
    "zh" to "cn", "zho" to "cn", "chi" to "cn",
    "ar" to "sa", "ara" to "sa",
    "hi" to "in", "hin" to "in",
    "cs" to "cz", "ces" to "cz", "cze" to "cz",
    "tr" to "tr", "tur" to "tr",
    "uk" to "ua", "ukr" to "ua",
    "el" to "gr", "ell" to "gr", "gre" to "gr",
    "hu" to "hu", "hun" to "hu",
    "ro" to "ro", "ron" to "ro", "rum" to "ro",
    "sk" to "sk", "slk" to "sk", "slo" to "sk",
    "hr" to "hr", "hrv" to "hr",
    "he" to "il", "heb" to "il",
    "th" to "th", "tha" to "th",
    "vi" to "vn", "vie" to "vn",
)

/**
 * Update `#audio-flags` in the pagebar from the current (possibly mutated) audio model,
 * keeping the flag strip live without a full page rebuild.
 */
internal fun refreshPagebarAudioFlags(audioModel: List<TrkModel>) {
    val el = document.getElementById("audio-flags") as? HTMLElement ?: return
    val langs = audioModel.mapNotNull { t -> t.lang?.lowercase()?.let { l -> TRACK_LANG_CC[l]?.let { cc -> l to cc } } }
        .distinctBy { (_, cc) -> cc }  // one flag per country code
    if (langs.isEmpty()) { el.innerHTML = ""; return }
    val shown = langs.take(5)
    val extra = langs.size - shown.size
    val flags = shown.joinToString("") { (_, cc) -> """<span class="fi fi-$cc"></span>""" }
    val more = if (extra > 0) """<span class="af-more">+$extra</span>""" else ""
    el.innerHTML = """<span class="af-label">Audio</span><span class="af-row">$flags$more</span>"""
}
