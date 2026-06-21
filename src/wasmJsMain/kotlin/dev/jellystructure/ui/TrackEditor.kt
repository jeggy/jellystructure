@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
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
)

private fun Track.toModel() = TrkModel(specifier, kind, codec, title, language, default, forced)
private fun TrkModel.copy() = TrkModel(sp, kind, codec, title, lang, def, forced)

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
          <div class="tiny muted" style="margin-top:10px;">Reorder by dragging the grip or the ▲▼ buttons · click a language to change it · ★ sets the default. Everything is <b>manual</b> and only touches the file when you press <b>Apply</b>.</div>
        </div>
        <div id="$prefix-staged" class="card" style="display:none;border-color:var(--hi);box-shadow:0 0 0 1px var(--hi-soft);margin-top:16px;">
          <div class="row center">
            <h4 style="margin:0;">Staged changes <span class="badge" id="$prefix-count" style="margin-left:6px;">0</span></h4>
            <span class="spacer"></span>
            <span class="btn sm ghost" id="$prefix-discard">Discard all</span>
          </div>
          <hr class="dash" style="margin:10px 0;">
          <div id="$prefix-ops"></div>
          <div style="margin-top:14px;">
            <div class="tiny muted" style="margin-bottom:6px;">Exact command — runs only on Apply:</div>
            <div class="cmd-block" id="$prefix-cmd" style="background:var(--bg-2);border:1px solid var(--line);border-radius:var(--radius-s);padding:12px 14px;font-family:'JetBrains Mono',monospace;font-size:.78rem;line-height:1.7;overflow-x:auto;white-space:pre;color:var(--ink-soft);"></div>
          </div>
          <div class="row center" style="margin-top:14px;gap:10px;flex-wrap:wrap;">
            <span id="$prefix-cost" style="display:inline-flex;align-items:center;gap:6px;"></span>
            <span class="spacer" style="flex:1;"></span>
            <span class="btn ghost" id="$prefix-discard-2">Discard</span>
            <span class="btn primary" id="$prefix-apply">Apply to file</span>
          </div>
          <div id="$prefix-apply-msg" class="tiny" style="display:none;margin-top:8px;"></div>
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
) {
    val audioModel = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.toModel() }.toMutableList()
    val subsModel = tracks.filter { it.kind == TrackKind.SUBTITLE }.map { it.toModel() }.toMutableList()
    val origAudio = audioModel.map { it.copy() }.toMutableList()
    val origSubs = subsModel.map { it.copy() }.toMutableList()

    var currentKind = "audio"
    var dragIdx: Int? = null

    fun model() = if (currentKind == "audio") audioModel else subsModel
    fun origModel() = if (currentKind == "audio") origAudio else origSubs

    val listEl = document.getElementById("$prefix-list") as? HTMLElement ?: return
    val segEl = document.getElementById("$prefix-seg") as? HTMLElement ?: return
    val colMidEl = document.getElementById("$prefix-col-mid") as? HTMLElement
    val explainEl = document.getElementById("$prefix-explain") as? HTMLElement
    val cascadeEl = document.getElementById("$prefix-cascade") as? HTMLElement
    val cascadeDefEl = document.getElementById("$prefix-cascade-def") as? HTMLElement
    val stagedEl = document.getElementById("$prefix-staged") as? HTMLElement
    val opsEl = document.getElementById("$prefix-ops") as? HTMLElement
    val countEl = document.getElementById("$prefix-count") as? HTMLElement
    val cmdEl = document.getElementById("$prefix-cmd") as? HTMLElement
    val costEl = document.getElementById("$prefix-cost") as? HTMLElement

    fun langShortName(code: String?): String {
        if (code.isNullOrBlank()) return "untagged"
        val found = getLanguages().find { it.first == code }
        return found?.second ?: code
    }

    fun cascade() {
        cascadeEl ?: return
        if (currentKind != "audio" || resolvedLanguage.isNullOrBlank()) {
            cascadeEl.style.display = "none"
            return
        }
        val def = audioModel.find { it.def }
        val show = def != null && !def.lang.isNullOrBlank() && def.lang != resolvedLanguage
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

    fun diff() {
        val ops = mutableListOf<Pair<String, String>>() // icon to text
        var anyReorder = false

        listOf("audio" to (audioModel to origAudio), "subs" to (subsModel to origSubs)).forEach { (kindName, pair) ->
            val (cur, orig) = pair
            val reordered = cur.indices.any { i -> cur[i].sp != (orig.getOrNull(i)?.sp ?: "") }
            if (reordered) {
                anyReorder = true
                val label = if (kindName == "audio") "Audio" else "Subtitle"
                ops += "⇅" to "$label order → ${cur.mapIndexed { i, _ -> i + 1 }.joinToString(", ")}"
            }
            cur.forEach { t ->
                val ob = orig.find { it.sp == t.sp } ?: return@forEach
                if (ob.lang != t.lang) ops += "🏷" to "${t.sp} language → ${if (t.lang.isNullOrBlank()) "untagged" else "${t.lang} (${langShortName(t.lang)})"}"
                if (ob.def != t.def && t.def) ops += "★" to "${t.sp} set as default $kindName"
                if (kindName == "subs" && ob.forced != t.forced) ops += "⮕" to "${t.sp} forced ${if (t.forced) "on" else "off"}"
            }
        }

        countEl?.textContent = "${ops.size}"
        stagedEl?.style?.display = if (ops.isEmpty()) "none" else "block"
        if (ops.isEmpty()) return

        opsEl?.innerHTML = ops.joinToString("") { (ic, text) ->
            """<div style="display:flex;align-items:center;gap:10px;padding:7px 0;border-bottom:1px dashed var(--line);font-size:.86rem;">
              <span style="width:22px;height:22px;flex:none;border-radius:6px;display:inline-flex;align-items:center;justify-content:center;font-size:.7rem;background:var(--hi-soft);color:var(--acc-ink);">$ic</span>
              <span>$text</span>
            </div>"""
        }

        // Command preview — reflect the tool that actually runs for this container and the resolved
        // 3-letter code that gets written (Phase 46): mkvpropedit in place for MKV, an ffmpeg -c copy
        // remux for MP4/other. Flag/language edits on a non-MKV file are a remux, not "instant".
        val fileName = filePath.substringAfterLast('/')
        val isMkv = filePath.substringAfterLast('.').lowercase() == "mkv"
        val cmdLines = mutableListOf<String>()
        val mkvParts = mutableListOf<String>()
        val ffMeta = mutableListOf<String>()
        var hasFlagOps = false
        listOf(audioModel to origAudio, subsModel to origSubs).forEach { (cur, orig) ->
            cur.forEach { t ->
                val ob = orig.find { it.sp == t.sp } ?: return@forEach
                val m = Regex("0:([as]):(\\d+)").find(t.sp) ?: return@forEach
                val typeChar = m.groupValues[1]
                val idx = m.groupValues[2].toIntOrNull() ?: return@forEach
                val tname = "track:${typeChar}${idx + 1}"  // mkvpropedit: 1-based, type-relative
                val sSpec = "$typeChar:$idx"                // ffmpeg output stream specifier, e.g. a:0
                if (ob.lang != t.lang && !t.lang.isNullOrBlank()) {
                    hasFlagOps = true
                    val iso3 = LanguageResolver.toIso6392(t.lang!!) ?: t.lang!!
                    val bcp = LanguageResolver.normalize(t.lang!!)
                    mkvParts += "  --edit $tname --set language=$iso3 --set language-ietf=$bcp"
                    ffMeta += "  -metadata:s:$sSpec language=$iso3"
                }
                if (ob.def != t.def) {
                    hasFlagOps = true
                    mkvParts += "  --edit $tname --set flag-default=${if (t.def) 1 else 0}"
                    ffMeta += "  -disposition:s:$sSpec ${if (t.def) "default" else "0"}"
                }
                if (t.kind == TrackKind.SUBTITLE && ob.forced != t.forced) {
                    hasFlagOps = true
                    mkvParts += "  --edit $tname --set flag-forced=${if (t.forced) 1 else 0}"
                    ffMeta += "  -disposition:s:$sSpec ${if (t.forced) "forced" else "0"}"
                }
            }
        }
        if (hasFlagOps) {
            if (isMkv) {
                cmdLines += "mkvpropedit \"$fileName\" \\"
                cmdLines += mkvParts.joinToString(" \\\n")
            } else {
                cmdLines += "ffmpeg -i \"$fileName\" -map 0 -c copy \\"
                cmdLines += ffMeta.joinToString(" \\\n")
                cmdLines += "  \"$fileName.fixed\"   # -c copy = remux, no re-encode"
            }
        }
        if (anyReorder) {
            if (cmdLines.isNotEmpty()) cmdLines += ""
            val maps = (audioModel + subsModel).joinToString(" ") { "-map ${it.sp}" }
            cmdLines += "ffmpeg -i \"$fileName\" \\\n  $maps -map 0:v -c copy \\\n  \"${fileName}.reordered.mkv\"  # -c copy = no re-encode"
        }
        cmdEl?.textContent = cmdLines.joinToString("\n")

        val needsRemux = anyReorder || (!isMkv && hasFlagOps)
        if (needsRemux) {
            costEl?.innerHTML = """<span class="badge warn">⚠ remux</span><span class="tiny muted" style="margin-left:6px;">this file needs an <b>ffmpeg -c copy</b> remux (no re-encode, but rewrites the file — minutes on large files)</span>"""
        } else {
            costEl?.innerHTML = """<span class="badge ok">instant</span><span class="tiny muted" style="margin-left:6px;"><b>mkvpropedit</b> edits flags in place · ~40 ms · no re-encode</span>"""
        }
    }

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
        diff()
    }

    fun reset() {
        audioModel.clear(); audioModel.addAll(origAudio.map { it.copy() })
        subsModel.clear(); subsModel.addAll(origSubs.map { it.copy() })
        currentKind = "audio"
        segEl.querySelectorAll("[data-tk]").let { nodes ->
            for (i in 0 until nodes.length) {
                val s = nodes.item(i) as? HTMLElement ?: continue
                if (s.getAttribute("data-tk") == "audio") s.classList.add("on") else s.classList.remove("on")
            }
        }
        renderList()
    }

    suspend fun applyChanges() {
        val applyMsgEl = document.getElementById("$prefix-apply-msg") as? HTMLElement
        val applyBtn = document.getElementById("$prefix-apply") as? HTMLElement
        applyBtn?.setAttribute("disabled", "true")
        applyMsgEl?.style?.display = "block"
        applyMsgEl?.textContent = "Applying…"

        var anyError: String? = null

        // Language changes — adopt the re-probed on-disk code the server returns, not our 2-letter
        // guess, so the committed baseline matches what a page reload (re-probe) will show (Phase 46).
        for (t in audioModel) {
            val ob = origAudio.find { it.sp == t.sp } ?: continue
            if (ob.lang != t.lang && !t.lang.isNullOrBlank()) {
                val res = if (epFilename == null) MediaApi.setTrackLanguage(mediaId, t.sp, t.lang!!)
                          else MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, t.sp, t.lang!!)
                if (res.error != null) { anyError = res.error; break }
                if (res.language != null) t.lang = res.language
            }
        }
        if (anyError == null) {
            for (t in subsModel) {
                val ob = origSubs.find { it.sp == t.sp } ?: continue
                if (ob.lang != t.lang && !t.lang.isNullOrBlank()) {
                    val res = if (epFilename == null) MediaApi.setTrackLanguage(mediaId, t.sp, t.lang!!)
                              else MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, t.sp, t.lang!!)
                    if (res.error != null) { anyError = res.error; break }
                    if (res.language != null) t.lang = res.language
                }
            }
        }

        // Default track changes (set the single new default)
        if (anyError == null) {
            val newAudioDef = audioModel.find { it.def }
            val oldAudioDef = origAudio.find { it.def }
            if (newAudioDef != null && newAudioDef.sp != oldAudioDef?.sp) {
                val err = if (epFilename == null) MediaApi.setDefaultTrack(mediaId, newAudioDef.sp)
                          else MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, newAudioDef.sp)
                if (err != null) anyError = err
            }
        }
        if (anyError == null) {
            val newSubsDef = subsModel.find { it.def }
            val oldSubsDef = origSubs.find { it.def }
            if (newSubsDef != null && newSubsDef.sp != oldSubsDef?.sp) {
                val err = if (epFilename == null) MediaApi.setDefaultTrack(mediaId, newSubsDef.sp)
                          else MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, newSubsDef.sp)
                if (err != null) anyError = err
            }
        }

        // Forced flag changes (subs only)
        if (anyError == null) {
            for (t in subsModel) {
                val ob = origSubs.find { it.sp == t.sp } ?: continue
                if (ob.forced != t.forced) {
                    val ok = if (epFilename == null) MediaApi.setForcedFlag(mediaId, t.sp, t.forced)
                             else MediaApi.setEpisodeForcedFlag(mediaId, epFilename, t.sp, t.forced)
                    if (!ok) { anyError = "Forced flag change failed for ${t.sp}"; break }
                }
            }
        }

        // Reorder
        if (anyError == null) {
            val aNewOrder = audioModel.map { it.sp }
            val aOrigOrder = origAudio.map { it.sp }
            if (aNewOrder != aOrigOrder) {
                val ok = if (epFilename == null) MediaApi.reorderTracks(mediaId, "audio", aNewOrder)
                         else MediaApi.reorderEpisodeTracks(mediaId, epFilename, "audio", aNewOrder)
                if (!ok) anyError = "Audio reorder failed"
            }
        }
        if (anyError == null) {
            val sNewOrder = subsModel.map { it.sp }
            val sOrigOrder = origSubs.map { it.sp }
            if (sNewOrder != sOrigOrder) {
                val ok = if (epFilename == null) MediaApi.reorderTracks(mediaId, "subtitle", sNewOrder)
                         else MediaApi.reorderEpisodeTracks(mediaId, epFilename, "subtitle", sNewOrder)
                if (!ok) anyError = "Subtitle reorder failed"
            }
        }

        applyBtn?.removeAttribute("disabled")
        if (anyError != null) {
            applyMsgEl?.style?.display = "block"
            applyMsgEl?.innerHTML = """<span style="color:var(--bad);">Error: ${anyError.esc()}</span>"""
        } else {
            // Commit: update orig snapshots to current state
            origAudio.clear(); origAudio.addAll(audioModel.map { it.copy() })
            origSubs.clear(); origSubs.addAll(subsModel.map { it.copy() })
            applyMsgEl?.innerHTML = """<span style="color:var(--ok);">Applied ✓</span>"""
            renderList()
        }
    }

    // ── Event wiring ────────────────────────────────────────────────────────────

    // Event delegation on track list
    listEl.addEventListener("click") { e ->
        val el = (e.target as? HTMLElement)?.closest("[data-act]") as? HTMLElement ?: return@addEventListener
        val act = el.getAttribute("data-act") ?: return@addEventListener
        val i = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        val arr = model()
        when (act) {
            "up" -> if (i > 0) {
                val tmp = arr[i - 1]; arr[i - 1] = arr[i]; arr[i] = tmp; renderList()
            }
            "down" -> if (i < arr.size - 1) {
                val tmp = arr[i + 1]; arr[i + 1] = arr[i]; arr[i] = tmp; renderList()
            }
            "default" -> { arr.forEach { it.def = false }; arr[i].def = true; renderList() }
            "forced" -> { arr[i].forced = !arr[i].forced; renderList() }
            "lang" -> {
                e.stopPropagation() // prevent document close-listener from closing the menu immediately
                val anchor = (e.target as? HTMLElement)?.closest(".lang-pickwrap") as? HTMLElement ?: el
                openTrkLangMenu(anchor, arr[i].lang) { code ->
                    arr[i].lang = code
                    renderList()
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
        renderList()
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

    // Cascade fix
    document.getElementById("$prefix-cascade-fix")?.addEventListener("click") { _ ->
        if (resolvedLanguage.isNullOrBlank()) return@addEventListener
        val target = audioModel.find { it.lang == resolvedLanguage } ?: audioModel.firstOrNull() ?: return@addEventListener
        audioModel.forEach { it.def = false }
        target.def = true
        renderList()
    }

    // Discard
    document.getElementById("$prefix-discard")?.addEventListener("click") { _ -> reset() }
    document.getElementById("$prefix-discard-2")?.addEventListener("click") { _ -> reset() }

    // Apply
    document.getElementById("$prefix-apply")?.addEventListener("click") { _ ->
        scope.launch { applyChanges() }
    }

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
