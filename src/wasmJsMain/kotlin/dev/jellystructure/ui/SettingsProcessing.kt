package dev.jellystructure.ui

import dev.jellystructure.api.AgeTiers
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.ProcessingApi
import dev.jellystructure.api.ProcessingConfig
import dev.jellystructure.api.ProcessingFeatureView
import dev.jellystructure.api.ProcessingPartView
import dev.jellystructure.api.ProcessingView
import dev.jellystructure.api.SubtitleCheckApi
import dev.jellystructure.api.SubtitleCheckSettings
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent

// DEB-1664 (FR-4) — Settings ▸ Processing: the nine features, one row each, replacing the pipeline configurator.
//
// What is drawn comes from `GET /api/processing` (names, intervals in words, last ran, next, state, every reason sentence —
// FR-12.1). What is *edited* is `[processing]` (FR-2.1), kept here as a draft that the page's one Save sends back with the rest of
// the config, exactly like every other field on this page. Nothing here is derived from the draft: after a save the view is asked
// for again.

private const val AGE_THIS_YEAR = "this_year"
private const val AGE_1_5 = "one_to_five_years"
private const val AGE_OLDER = "older"

private val CAD_VALUES = listOf("daily", "weekly", "monthly", "6months", "yearly", "2years", "5years", "never")
private val CAD_WORDS = listOf("Every day", "Every week", "Every month", "Every 6 months", "Every year", "Every 2 years", "Every 5 years", "Never")
private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private var draft = ProcessingConfig()
private var saved = ProcessingConfig()
private var view: ProcessingView? = null
private var viewLoaded = false
private val openRows = mutableSetOf<String>()
private var bazarrOn = false
private var subcheck: SubtitleCheckSettings? = null
private var subcheckNote = ""
private var findForceCustom = false
private var findCronValid = true
private var findCronJob: Job? = null
private var pfScope: CoroutineScope? = null

// ── the section ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The tab's host (FR-4.1). `.pfx` is the scope processing.css needs. The hold card is static, not re-rendered, because Settings
 * wires its switch (`defer-while-playing-toggle`) like every other switch on the page; the list under it is redrawn from the
 * draft and the server's view. *Make files play directly*'s controls live in the stash while its row is closed: they hold their
 * own listeners, and a redraw of the list must not take those away.
 */
internal fun processingSectionHtml(): String = """
            <div class="set-section pfx" id="sect-processing" data-tab="processing">
              <p class="page-sub" style="margin:0 0 16px">Everything switched on here runs by itself. A new or changed title gets every feature at once; everything else is re-checked when its own interval says it is due. <a href="#/activity">See what is running now →</a></p>
              <div class="card" style="margin-bottom:18px">
                <div class="row center">
                  <div><span id="defer-while-playing-toggle" class="toggle" style="cursor:pointer"></span></div>
                  <div class="fill">
                    <div style="font-weight:600;font-size:.95rem">Wait while someone is watching</div>
                    <div class="tiny muted" style="margin-top:3px">Scans wait while a TV is playing. A title you refresh yourself never waits.</div>
                  </div>
                  <span id="pf-hold-badge" class="badge warn" style="display:none">Holding now</span>
                </div>
                <div id="pf-hold-note" class="pf-note" style="display:none;margin-top:13px"></div>
              </div>
              <div id="pf-dirty" class="pf-note warn" style="display:none;margin-bottom:14px">Changes on this tab apply when you press Save.</div>
              <div class="card"><div class="pf-list" id="pf-list"></div></div>
              <div class="tiny muted" style="margin-top:14px">Nine features, and that is the whole list. There is no order to arrange: what runs when a title changes, and what order it runs in, is fixed.</div>
              <div id="pf-ff-stash" style="display:none">${fileFixSectionHtml()}</div>
            </div>
"""

/** Takes the saved `[processing]` into the draft (the server always sends one once it has loaded) and what the Subtitles row needs of the connections. */
internal fun populateProcessing(config: dev.jellystructure.api.AppConfig) {
    draft = config.processing ?: ProcessingConfig()
    saved = draft
    bazarrOn = config.bazarr?.enabled == true
    findForceCustom = false
    findCronValid = true
    renderProcessing()
}

/** What the page's Save sends for `[processing]`. */
internal fun readProcessing(): ProcessingConfig = draft

/** Save is held while Find's schedule is not a valid cron (phase 166, FR-166-5). */
internal fun processingScheduleValid(): Boolean = findCronValid

/** The server's own rejection of the schedule at save time (the backstop behind the live check). */
internal fun showFindScheduleError(message: String) {
    findCronValid = false
    openRows += "find"
    renderProcessing()
    (document.getElementById("pf-find-check") as? HTMLElement)?.innerHTML = """<span class="badge bad">${message.esc()}</span>"""
}

/** After a successful save the draft is what is stored, and the lines under every name describe the saved state again. */
internal fun processingSaved(scope: CoroutineScope) {
    saved = draft
    scope.launch { loadProcessingView() }
}

internal fun wireProcessing(scope: CoroutineScope) {
    pfScope = scope
    val list = document.getElementById("pf-list") as? HTMLElement ?: return
    list.addEventListener("click", { ev -> onClick(ev.target as? HTMLElement ?: return@addEventListener, scope) })
    list.addEventListener("change", { ev -> onChange(ev.target as? HTMLElement ?: return@addEventListener, scope) })
    list.addEventListener("input", { ev -> onInput(ev.target as? HTMLElement ?: return@addEventListener) })
    list.addEventListener("keydown", { ev ->
        val k = ev as? KeyboardEvent ?: return@addEventListener
        val t = k.target as? HTMLElement ?: return@addEventListener
        if ((k.key == "Enter" || k.key == " ") && t.classList.contains("toggle")) { k.preventDefault(); t.click() }
        if (k.key == "Enter" && t.getAttribute("data-kw") == "new") { k.preventDefault(); addKeyword(t as? HTMLInputElement) }
    })
    renderProcessing()
    scope.launch { loadProcessingView() }
}

private suspend fun loadProcessingView() {
    view = ProcessingApi.get()
    viewLoaded = true
    subcheck = SubtitleCheckApi.settings()
    renderProcessing()
}

// ── editing the draft ───────────────────────────────────────────────────────────────────────────────────────────────

private fun featureOnDraft(id: String): Boolean = when (id) {
    "find" -> true
    "metadata" -> draft.metadata.enabled
    "segments" -> draft.segments.enabled
    "health" -> draft.health.enabled
    "subtitles" -> draft.subtitles.enabled
    "suggestions" -> draft.suggestions.enabled
    "music" -> draft.music.enabled
    "audiobooks" -> draft.audiobooks.enabled
    else -> false
}

private fun setFeature(id: String, on: Boolean) {
    draft = when (id) {
        "metadata" -> draft.copy(metadata = draft.metadata.copy(enabled = on))
        "segments" -> draft.copy(segments = draft.segments.copy(enabled = on))
        "health" -> draft.copy(health = draft.health.copy(enabled = on))
        "subtitles" -> draft.copy(subtitles = draft.subtitles.copy(enabled = on))
        "suggestions" -> draft.copy(suggestions = draft.suggestions.copy(enabled = on))
        "music" -> draft.copy(music = draft.music.copy(enabled = on))
        "audiobooks" -> draft.copy(audiobooks = draft.audiobooks.copy(enabled = on))
        else -> draft
    }
}

/** A part's switch, by its id on the wire (the engine's step id; it never reaches a screen). */
private fun partOnDraft(id: String): Boolean = when (id) {
    "pull_tmdb" -> draft.metadata.tmdb
    "fetch_artwork" -> draft.metadata.artwork
    "sync_imdb_ratings" -> draft.metadata.imdb
    "write_nfo" -> draft.metadata.nfo
    "detect_drift" -> draft.metadata.drift
    "rescan_arr" -> draft.metadata.rescanArr
    "verify_files" -> draft.health.verify
    "check_track_lengths" -> draft.health.lengths
    "check_av_sync" -> draft.health.avSync
    "prewarm_subtitles" -> draft.subtitles.prewarm
    "check_subtitles" -> draft.subtitles.checkFit
    "build_recommendations" -> draft.suggestions.recommendations
    "build_suggestions" -> draft.suggestions.suggestions
    "scan_music" -> draft.music.read
    "match_musicbrainz" -> draft.music.match
    "compare_songs" -> draft.music.compare
    "fetch_music_artwork" -> draft.music.artwork
    "fetch_lyrics" -> draft.music.lyrics
    "write_music_nfo" -> draft.music.nfo
    "write_tags" -> draft.music.tags
    else -> false
}

private fun setPart(id: String, on: Boolean) {
    val m = draft.metadata; val h = draft.health; val s = draft.subtitles; val g = draft.suggestions; val mu = draft.music
    draft = when (id) {
        "pull_tmdb" -> draft.copy(metadata = m.copy(tmdb = on))
        "fetch_artwork" -> draft.copy(metadata = m.copy(artwork = on))
        "sync_imdb_ratings" -> draft.copy(metadata = m.copy(imdb = on))
        "write_nfo" -> draft.copy(metadata = m.copy(nfo = on))
        "detect_drift" -> draft.copy(metadata = m.copy(drift = on))
        "rescan_arr" -> draft.copy(metadata = m.copy(rescanArr = on))
        "verify_files" -> draft.copy(health = h.copy(verify = on))
        "check_track_lengths" -> draft.copy(health = h.copy(lengths = on))
        "check_av_sync" -> draft.copy(health = h.copy(avSync = on))
        "prewarm_subtitles" -> draft.copy(subtitles = s.copy(prewarm = on))
        "check_subtitles" -> draft.copy(subtitles = s.copy(checkFit = on))
        "build_recommendations" -> draft.copy(suggestions = g.copy(recommendations = on))
        "build_suggestions" -> draft.copy(suggestions = g.copy(suggestions = on))
        "scan_music" -> draft.copy(music = mu.copy(read = on))
        "match_musicbrainz" -> draft.copy(music = mu.copy(match = on))
        "compare_songs" -> draft.copy(music = mu.copy(compare = on))
        "fetch_music_artwork" -> draft.copy(music = mu.copy(artwork = on))
        "fetch_lyrics" -> draft.copy(music = mu.copy(lyrics = on))
        "write_music_nfo" -> draft.copy(music = mu.copy(nfo = on))
        "write_tags" -> draft.copy(music = mu.copy(tags = on))
        else -> draft
    }
}

private fun tiersOf(group: String): AgeTiers = when (group) {
    "find" -> draft.metadata.tiers
    "verify" -> draft.health.verifyTiers
    "lengths" -> draft.health.lengthsTiers
    "fit" -> draft.subtitles.checkFitTiers
    else -> AgeTiers()
}

private fun setTiers(group: String, t: AgeTiers) {
    draft = when (group) {
        "find" -> draft.copy(metadata = draft.metadata.copy(tiers = t))
        "verify" -> draft.copy(health = draft.health.copy(verifyTiers = t))
        "lengths" -> draft.copy(health = draft.health.copy(lengthsTiers = t))
        "fit" -> draft.copy(subtitles = draft.subtitles.copy(checkFitTiers = t))
        else -> draft
    }
}

/** The on/off options that are not a part of their own. */
private fun optOn(key: String): Boolean = when {
    key.startsWith("recheck:") -> tiersOf(key.removePrefix("recheck:")).recheckUnchanged
    key == "nfo_overwrite" -> draft.metadata.nfoOverwrite
    key == "auto_reassert" -> draft.metadata.autoReassert
    key == "seg.fingerprint" -> draft.segments.detectFingerprint
    key == "seg.stinger" -> draft.segments.trustStingerTags
    else -> false
}

private fun setOpt(key: String, on: Boolean) {
    when {
        key.startsWith("recheck:") -> key.removePrefix("recheck:").let { g -> setTiers(g, tiersOf(g).copy(recheckUnchanged = on)) }
        key == "nfo_overwrite" -> draft = draft.copy(metadata = draft.metadata.copy(nfoOverwrite = on))
        key == "auto_reassert" -> draft = draft.copy(metadata = draft.metadata.copy(autoReassert = on))
        key == "seg.fingerprint" -> draft = draft.copy(segments = draft.segments.copy(detectFingerprint = on))
        key == "seg.stinger" -> draft = draft.copy(segments = draft.segments.copy(trustStingerTags = on))
    }
}

/** The choices (a segmented control or a select): `scope`, `interval`, `rebuild every` and the three release-age tiers of a part. */
private fun choice(key: String): String = when {
    key.startsWith("tier:") -> key.split(":").let { (_, g, f) ->
        val t = tiersOf(g)
        when (f) { AGE_THIS_YEAR -> t.thisYear; AGE_1_5 -> t.oneToFiveYears; else -> t.older }
    }
    key == "tmdb_scope" -> draft.metadata.tmdbScope
    key == "artwork_scope" -> draft.metadata.artworkScope
    key == "seg.scope" -> draft.segments.scope
    key == "rec_every" -> draft.suggestions.recommendationsEvery
    key == "sug_every" -> draft.suggestions.suggestionsEvery
    key == "music.interval" -> draft.music.interval
    key == "audio.interval" -> draft.audiobooks.interval
    key == "match_scope" -> draft.music.matchScope
    key == "music_artwork_scope" -> draft.music.artworkScope
    key == "lyrics_scope" -> draft.music.lyricsScope
    else -> ""
}

private fun setChoice(key: String, v: String) {
    when {
        key.startsWith("tier:") -> key.split(":").let { (_, g, f) ->
            val t = tiersOf(g)
            setTiers(g, when (f) { AGE_THIS_YEAR -> t.copy(thisYear = v); AGE_1_5 -> t.copy(oneToFiveYears = v); else -> t.copy(older = v) })
        }
        key == "tmdb_scope" -> draft = draft.copy(metadata = draft.metadata.copy(tmdbScope = v))
        key == "artwork_scope" -> draft = draft.copy(metadata = draft.metadata.copy(artworkScope = v))
        key == "seg.scope" -> draft = draft.copy(segments = draft.segments.copy(scope = v))
        key == "rec_every" -> draft = draft.copy(suggestions = draft.suggestions.copy(recommendationsEvery = v))
        key == "sug_every" -> draft = draft.copy(suggestions = draft.suggestions.copy(suggestionsEvery = v))
        key == "music.interval" -> draft = draft.copy(music = draft.music.copy(interval = v))
        key == "audio.interval" -> draft = draft.copy(audiobooks = draft.audiobooks.copy(interval = v))
        key == "match_scope" -> draft = draft.copy(music = draft.music.copy(matchScope = v))
        key == "music_artwork_scope" -> draft = draft.copy(music = draft.music.copy(artworkScope = v))
        key == "lyrics_scope" -> draft = draft.copy(music = draft.music.copy(lyricsScope = v))
    }
}

// ── events ──────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun onClick(t: HTMLElement, scope: CoroutineScope) {
    val tog = t.closest(".toggle") as? HTMLElement
    if (tog != null) {
        if (tog.classList.contains("dis")) return
        val on = !tog.classList.contains("on")
        tog.getAttribute("data-feat")?.let { setFeature(it, on) }
        tog.getAttribute("data-part")?.let { setPart(it, on) }
        tog.getAttribute("data-opt")?.let { setOpt(it, on) }
        renderProcessing()
        return
    }
    val seg = t.closest(".seg span[data-v]") as? HTMLElement
    if (seg != null) {
        val key = (seg.parentElement as? HTMLElement)?.getAttribute("data-seg") ?: return
        setChoice(key, seg.getAttribute("data-v") ?: return)
        renderProcessing()
        return
    }
    (t.closest("[data-kw-del]") as? HTMLElement)?.let { chip ->
        val word = chip.getAttribute("data-kw-del") ?: return
        draft = draft.copy(segments = draft.segments.copy(chapterKeywords = draft.segments.chapterKeywords.filterNot { it == word }))
        renderProcessing()
        return
    }
    (t.closest("[data-kw-add]") as? HTMLElement)?.let {
        addKeyword(document.querySelector("[data-kw='new']") as? HTMLInputElement)
        return
    }
    if (t.closest("[data-pf='retry']") != null) {
        viewLoaded = false
        renderProcessing()
        scope.launch { loadProcessingView() }
        return
    }
    // A press on the row opens it. Anything that is a control, a link or inside the opened body is left alone.
    val head = t.closest(".pf-head") as? HTMLElement ?: return
    if (t.closest("a") != null) return
    val id = head.getAttribute("data-row") ?: return
    if (!openRows.add(id)) openRows.remove(id)
    renderProcessing()
}

private fun onChange(t: HTMLElement, scope: CoroutineScope) {
    t.getAttribute("data-tier")?.let { key ->
        setChoice("tier:$key", (t as HTMLSelectElement).value)
        refreshDirty()
        return
    }
    t.getAttribute("data-choice")?.let { key ->
        setChoice(key, (t as HTMLSelectElement).value)
        refreshDirty()
        return
    }
    t.getAttribute("data-find")?.let { which ->
        if (which == "mode") chooseFindMode((t as HTMLSelectElement).value) else findControlsChanged()
        return
    }
    if (t.getAttribute("data-kw") == "new") { addKeyword(t as? HTMLInputElement); return }
    if (t.getAttribute("data-sub") == "action") {
        val sel = t as HTMLSelectElement
        scope.launch {
            // read it again first: the Bazarr card saves the budget and the address through the same route
            val current = SubtitleCheckApi.settings() ?: subcheck ?: return@launch
            subcheckNote = "Saving…"; renderProcessing()
            val next = SubtitleCheckApi.saveSettings(current.copy(action = sel.value))
            subcheck = next ?: current
            subcheckNote = if (next != null) "Saved" else "Couldn't save"
            refreshSubtitleCheck(scope)
            renderProcessing()
            delay(2500); subcheckNote = ""; renderProcessing()
        }
    }
}

private fun onInput(t: HTMLElement) {
    if (t.getAttribute("data-find") == "cron") findControlsChanged()
}

private fun addKeyword(input: HTMLInputElement?) {
    val word = input?.value?.trim().orEmpty()
    if (word.isEmpty()) return
    val have = draft.segments.chapterKeywords
    if (have.none { it.equals(word, ignoreCase = true) }) draft = draft.copy(segments = draft.segments.copy(chapterKeywords = have + word))
    renderProcessing()
    (document.querySelector("[data-kw='new']") as? HTMLInputElement)?.focus()
}

// ── Find new & changed's interval (FR-3.2) ─────────────────────────────────────────────────────────────────────────

private val EVERY_N = Regex("""^(\d{1,2}) \*/(\d{1,2}) \* \* \*$""")
private val DAILY_AT = Regex("""^(\d{1,2}) (\d{1,2}) \* \* \*$""")
private val WEEKLY_ON = Regex("""^(\d{1,2}) (\d{1,2}) \* \* ([0-6])$""")

/** `everyN` | `daily` | `weekly` | `custom` — a saved cron that matches a preset shows as that preset, anything else as Custom. */
private fun findMode(): String {
    val c = draft.find.schedule.trim()
    return when {
        findForceCustom -> "custom"
        EVERY_N.matches(c) -> "everyN"
        DAILY_AT.matches(c) -> "daily"
        WEEKLY_ON.matches(c) -> "weekly"
        else -> "custom"
    }
}

private fun two(n: Int) = n.toString().padStart(2, '0')

private fun chooseFindMode(mode: String) {
    findForceCustom = mode == "custom"
    if (mode != "custom") draft = draft.copy(find = draft.find.copy(schedule = when (mode) {
        "daily" -> "0 3 * * *"
        "weekly" -> "0 3 * * 0"
        else -> "0 */1 * * *"
    }))
    renderProcessing()
    scheduleFindCheck()
}

/** Reads Find's controls for the chosen mode back into the cron, and asks the server whether it is one (phase 166). */
private fun findControlsChanged() {
    fun input(which: String) = (document.querySelector("[data-find='$which']") as? HTMLInputElement)?.value ?: ""
    fun select(which: String) = (document.querySelector("[data-find='$which']") as? HTMLSelectElement)?.value ?: ""
    fun time(): Pair<Int, Int> = input("at").split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 3) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val cron = when (findMode()) {
        "everyN" -> "${(input("m").toIntOrNull() ?: 0).coerceIn(0, 59)} */${(input("n").toIntOrNull() ?: 1).coerceIn(1, 23)} * * *"
        "daily" -> time().let { (h, m) -> "$m $h * * *" }
        "weekly" -> time().let { (h, m) -> "$m $h * * ${select("dow").ifBlank { "0" }}" }
        else -> input("cron")
    }
    draft = draft.copy(find = draft.find.copy(schedule = cron))
    scheduleFindCheck()
    refreshDirty()
}

private fun scheduleFindCheck() {
    findCronJob?.cancel()
    val cron = draft.find.schedule
    findCronJob = pfScope?.launch { delay(400); checkFindCron(cron) }
}

private suspend fun checkFindCron(cron: String) {
    val out = document.getElementById("pf-find-check") as? HTMLElement
    if (cron.isBlank()) { findCronValid = true; out?.innerHTML = ""; gateSave(); return }
    val result = ConfigApi.validateSchedule(cron)
    if (result == null) {
        // Not reachable right now: do not hold Save for it, the save itself validates (FR-166-4).
        findCronValid = true
        out?.innerHTML = """<span class="muted tiny">Couldn't check the schedule right now — it will still be checked when you save.</span>"""
    } else {
        findCronValid = result.valid
        out?.innerHTML = if (result.valid) {
            val preview = result.nextRuns.joinToString(" · ") { dev.jellystructure.formatStoredTs(it.toString()) }
            """<span class="badge ok" style="margin-right:6px">✓ ${(result.description ?: "Valid").esc()}</span>""" +
                (if (preview.isNotBlank()) """<div class="tiny muted" style="margin-top:4px">Next: $preview</div>""" else "")
        } else """<span class="badge bad">${(result.error ?: "Invalid schedule").esc()}</span>"""
    }
    gateSave()
}

private fun gateSave() {
    val btn = document.getElementById("save-settings") as? HTMLElement ?: return
    if (findCronValid) btn.removeAttribute("disabled") else btn.setAttribute("disabled", "")
}

// ── drawing ─────────────────────────────────────────────────────────────────────────────────────────────────────────

private fun sw(on: Boolean, attrs: String, dis: Boolean = false, title: String? = null): String =
    """<span class="toggle${if (on) " on" else ""}${if (dis) " dis" else ""}" role="switch" aria-checked="$on" tabindex="0" $attrs${if (title != null) """ title="${title.esc()}"""" else ""}></span>"""

private fun badge(kind: String, text: String) = """<span class="badge $kind">${text.esc()}</span>"""

private fun refreshDirty() {
    (document.getElementById("pf-dirty") as? HTMLElement)?.style?.display = if (draft != saved) "block" else "none"
}

private fun renderHold() {
    val text = view?.holdingText
    (document.getElementById("pf-hold-badge") as? HTMLElement)?.style?.display = if (text != null) "" else "none"
    (document.getElementById("pf-hold-note") as? HTMLElement)?.let {
        it.style.display = if (text != null) "block" else "none"
        it.textContent = text ?: ""
    }
}

private fun renderProcessing() {
    val list = document.getElementById("pf-list") as? HTMLElement ?: return
    val stash = document.getElementById("pf-ff-stash") as? HTMLElement
    val ff = document.getElementById("pf-ff") as? HTMLElement
    // keep what has a focus and a caret across a redraw
    val focused = document.activeElement as? HTMLElement
    val focusKey = focused?.let { it.getAttribute("data-find") ?: it.getAttribute("data-kw") }
    if (ff != null && stash != null && ff.parentElement != stash) stash.appendChild(ff)
    val v = view
    list.innerHTML = when {
        !viewLoaded -> skeletonHtml()
        v == null -> """<div class="pf-note warn" style="margin:14px 4px">Couldn't load the features from the server. <button class="btn sm" data-pf="retry">Try again</button></div>"""
        else -> v.features.joinToString("") { rowHtml(it) }
    }
    if (ff != null) (document.getElementById("pf-ff-slot") as? HTMLElement)?.appendChild(ff)
    renderHold()
    refreshDirty()
    if (focusKey != null) (document.querySelector("[data-find='$focusKey'], [data-kw='$focusKey']") as? HTMLElement)?.focus()
}

private fun skeletonHtml(): String = (0 until 6).joinToString("") {
    """<div class="sk-row"><div class="sk-sw"></div><span class="sk w1"></span><span class="sk w4" style="height:10px"></span><span class="sk w3"></span><div></div></div>"""
}

private fun chipHtml(f: ProcessingFeatureView): String = when (f.state) {
    "running" -> badge("warn", "Running")
    "waiting" -> badge("warn", "Holding")
    "unavailable" -> badge("flat", "Not mapped")
    "off" -> badge("flat", "Off")
    else -> badge("ok", "On")
}

private fun rowHtml(f: ProcessingFeatureView): String {
    val open = f.id in openRows
    val unavailable = f.state == "unavailable"
    val off = f.state == "off"
    val on = featureOnDraft(f.id)
    val control = when {
        f.locked -> sw(true, """data-feat="${f.id}"""", dis = true, title = f.reason)
        f.id == "direct_play" -> sw(f.enabled, """data-feat="${f.id}"""", dis = true, title = DIRECT_PLAY_REASON)
        unavailable -> sw(on, """data-feat="${f.id}"""", dis = true, title = f.reason)
        else -> sw(on, """data-feat="${f.id}"""")
    }
    val why = when {
        f.locked || unavailable -> f.reason?.let { """<span class="pf-why">${it.esc()}</span>""" } ?: ""
        f.id == "direct_play" -> """<span class="pf-why">$DIRECT_PLAY_REASON</span>"""
        else -> ""
    }
    val when_ = when {
        off -> "<b>Off</b><br>${(f.reason ?: "").esc()}"
        else -> "<b>${f.interval.esc()}</b><br>" + listOfNotNull(
            f.lastRan.takeIf { it.isNotBlank() }, f.next.takeIf { it.isNotBlank() && !unavailable },
            f.waitingText.takeIf { f.waiting > 0 },
        ).joinToString(" · ") { it.esc() }
    }
    return """
      <div class="pf-row${if (open) " open" else ""}${if (off) " off" else ""}${if (unavailable) " inert" else ""}">
        <div class="pf-head" data-row="${f.id}" style="cursor:pointer">
          <div>$control</div>
          <div class="pf-name">${f.name.esc()}$why</div>
          <div class="pf-when">$when_</div>
          <div>${chipHtml(f)}</div>
          <div class="pf-chev">⌄</div>
        </div>
        ${if (open) """<div class="pf-body">${bodyHtml(f)}</div>""" else ""}
      </div>"""
}

/** FR-11 — the row's own switch cannot act: the feature has no key of its own, its switches are the repair kinds' database rows (FR-2.2). */
private const val DIRECT_PLAY_REASON = "Each repair has its own switch inside."

private fun note(html: String, cls: String = "") = """<div class="pf-note${if (cls.isNotEmpty()) " $cls" else ""}">$html</div>"""

private fun queuesNote(f: ProcessingFeatureView): String =
    if (!f.queuesWork) "" else note("<b>This queues work.</b> What is due is put in a queue; it is read afterwards, in the background, and shows on <a href=\"#/activity\">Activity</a>.", "q")

private fun partRow(on: Boolean, attrs: String, name: String, help: String = "", extra: String = "", dis: Boolean = false): String =
    """<div class="pf-part"><div>${sw(on, attrs, dis)}</div><div><div class="pn">${name.esc()}</div>${if (help.isNotEmpty()) """<div class="ph-line">${help.esc()}</div>""" else ""}$extra</div><div></div></div>"""

private fun seg(key: String, options: List<Pair<String, String>>): String =
    """<span class="seg" data-seg="$key" style="margin-top:9px">""" +
        options.joinToString("") { (v, label) -> """<span data-v="$v"${if (choice(key) == v) """ class="on"""" else ""}>${label.esc()}</span>""" } + "</span>"

private val SCOPE = listOf("missing" to "Missing only", "all" to "All items")
private val EVERY = listOf("weekly" to "Every week", "daily" to "Every day")
private val INTERVAL = listOf("hourly" to "Every hour", "daily" to "Daily", "weekly" to "Weekly")

private fun cadSelect(attrs: String, value: String, disabled: Boolean): String =
    """<select class="select" $attrs${if (disabled) " disabled" else ""}>""" +
        CAD_VALUES.mapIndexed { i, v -> """<option value="$v"${if (v == value) " selected" else ""}>${CAD_WORDS[i]}</option>""" }.joinToString("") + "</select>"

/** The release-age rule for unchanged titles: one switch and the three intervals (FR-3.3). */
private fun agesHtml(group: String, head: String, help: String): String {
    val t = tiersOf(group)
    fun age(label: String, field: String, value: String) =
        """<label class="pf-age"><span>$label</span>${cadSelect("""data-tier="$group:$field"""", value, disabled = !t.recheckUnchanged)}</label>"""
    return """<div class="pf-part" style="grid-template-columns:44px minmax(0,1fr)"><div>${sw(t.recheckUnchanged, """data-opt="recheck:$group"""")}</div><div>
        <div class="pn">${head.esc()}</div><div class="ph-line">${help.esc()}</div>
        <div class="pf-ages" style="margin-top:11px">${age("Released this year", AGE_THIS_YEAR, t.thisYear)}${age("1–5 years old", AGE_1_5, t.oneToFiveYears)}${age("Older", AGE_OLDER, t.older)}</div>
      </div></div>"""
}

private fun intervalSelect(key: String, label: String): String =
    """<div class="pf-every"><span class="muted tiny">${label.esc()}</span><select class="select" data-choice="$key">""" +
        INTERVAL.joinToString("") { (v, w) -> """<option value="$v"${if (choice(key) == v) " selected" else ""}>$w</option>""" } + "</select></div>"

private val PART_HELP = mapOf(
    "verify_files" to "Proves the file is readable all the way through. This is the expensive one — it reads every byte.",
    "check_track_lengths" to "Catches audio or subtitles that run past the picture.",
    "check_av_sync" to "Measured once per file. A file is not measured again unless it changes.",
    "prewarm_subtitles" to "Prepares a subtitle before anyone presses play, so it appears instantly.",
    "build_recommendations" to "The rows a viewer sees.",
    "build_suggestions" to "Things to request that we do not hold. These need the request app connected.",
)

/** One part of an expanded feature, as the server lists it, with the options that belong to it. */
private fun partHtml(f: ProcessingFeatureView, p: ProcessingPartView): String {
    val extra = buildString {
        when (p.id) {
            "pull_tmdb" -> append(seg("tmdb_scope", SCOPE))
            "fetch_artwork" -> append(seg("artwork_scope", SCOPE))
            "write_nfo" -> append(partRow(draft.metadata.nfoOverwrite, """data-opt="nfo_overwrite"""", "Overwrite other tools' fields in the NFO files", "Off leaves a field alone once another tool has written it."))
            "detect_drift" -> append(partRow(draft.metadata.autoReassert, """data-opt="auto_reassert"""", "Put our values back when something drifts", "Off still notices the drift and shows it; it just does not correct it."))
            "rescan_arr" -> append("""<div class="ph-line">Whether Radarr and Sonarr rescan after a write is set on their own cards. <a href="#/settings?tab=downloads">Radarr and Sonarr →</a></div>""")
            "verify_files" -> append(agesHtml("verify", "Re-check unchanged files on a schedule", "A whole-file read of the library is 200–300 GB — every 5 years is a sensible default."))
            "check_track_lengths" -> append(agesHtml("lengths", "Re-check unchanged files on a schedule", "A tail probe reads ~15–60 MB per file."))
            "check_subtitles" -> append(agesHtml("fit", "Re-check unchanged subtitles on a schedule", "A subtitle that fits is looked at again once its release-age interval has passed."))
            "build_recommendations" -> append(seg("rec_every", EVERY))
            "build_suggestions" -> append(seg("sug_every", EVERY))
            "match_musicbrainz" -> append(seg("match_scope", SCOPE))
            "fetch_music_artwork" -> append(seg("music_artwork_scope", SCOPE))
            "fetch_lyrics" -> append(seg("lyrics_scope", SCOPE))
        }
        // The reason a switched-on part cannot run (FR-1.4), in the server's words. The feature's own reason is on its row.
        if (p.on && !p.canRun && f.enabled && !p.reason.isNullOrBlank() && p.reason != f.reason)
            append("""<div class="pf-note warn" style="margin-top:10px">${p.reason.esc()}</div>""")
    }
    // a part whose options are rows of their own sit under it, not inside its text block
    val inline = extra.contains("""class="pf-part"""")
    val head = partRow(partOnDraft(p.id), """data-part="${p.id}"""", p.name, PART_HELP[p.id] ?: "", if (inline) "" else extra)
    return if (inline) head + extra else head
}

private fun bodyHtml(f: ProcessingFeatureView): String = buildString {
    when (f.id) {
        "find" -> append(findBodyHtml())
        "segments" -> append(segmentsBodyHtml())
        "subtitles" -> {
            f.parts.forEach { append(partHtml(f, it)) }
            append(subtitleActionHtml())
        }
        "music" -> {
            append(intervalSelect("music.interval", "Read the music library"))
            f.parts.forEach { append(partHtml(f, it)) }
        }
        "audiobooks" -> {
            append(intervalSelect("audio.interval", "Read the audiobook library"))
            append(note("Nothing else to configure. It reads the audiobook library and stops."))
        }
        "direct_play" -> append("""<div id="pf-ff-slot"></div>""")
        else -> f.parts.forEach { append(partHtml(f, it)) }
    }
    if (f.id != "find" && f.id != "direct_play") append(queuesNote(f))
}

private fun findBodyHtml(): String {
    val mode = findMode()
    val c = draft.find.schedule.trim()
    val every = EVERY_N.matchEntire(c)?.groupValues
    val daily = DAILY_AT.matchEntire(c)?.groupValues
    val weekly = WEEKLY_ON.matchEntire(c)?.groupValues
    fun time(h: String, m: String) = "${two(h.toIntOrNull() ?: 3)}:${two(m.toIntOrNull() ?: 0)}"
    val modes = listOf("everyN" to "Every N hours", "daily" to "Daily at", "weekly" to "Weekly on", "custom" to "Custom")
    val controls = when (mode) {
        "everyN" -> """<input class="input" type="number" min="1" max="23" data-find="n" value="${every?.get(2) ?: "1"}"><span class="muted tiny">hours, at minute</span><input class="input" type="number" min="0" max="59" data-find="m" value="${every?.get(1) ?: "0"}">"""
        "daily" -> """<input class="input" type="time" data-find="at" style="width:112px" value="${time(daily?.get(2) ?: "3", daily?.get(1) ?: "0")}">"""
        "weekly" -> """<select class="select" data-find="dow">${WEEKDAYS.mapIndexed { i, d -> """<option value="$i"${if (weekly?.get(3) == i.toString()) " selected" else ""}>$d</option>""" }.joinToString("")}</select>""" +
            """<span class="muted tiny">at</span><input class="input" type="time" data-find="at" style="width:112px" value="${time(weekly?.get(2) ?: "3", weekly?.get(1) ?: "0")}">"""
        else -> """<input class="input mono" type="text" data-find="cron" style="width:230px" placeholder="0 3 * * *" value="${c.esc()}"><span class="muted tiny">Leave it empty to look only when you scan.</span>"""
    }
    return """
      <div class="pf-every">
        <span class="muted tiny">Look for new and changed titles</span>
        <select class="select" data-find="mode">${modes.joinToString("") { (v, w) -> """<option value="$v"${if (mode == v) " selected" else ""}>$w</option>""" }}</select>
        $controls
      </div>
      <div class="tiny" id="pf-find-check"></div>
      ${note("The order the work happens in is fixed and is not configurable. This looks first; everything switched on then runs on what it found.")}
      ${agesHtml("find", "Re-check titles that have not changed", "Nothing on disk has changed, so this decides how often we look anyway. Older titles change less, so they are looked at less.")}"""
}

private fun segmentsBodyHtml(): String {
    val kw = draft.segments.chapterKeywords
    return """
      <div class="pf-part" style="grid-template-columns:minmax(0,1fr)"><div>
        <div class="pn">Which titles</div>${seg("seg.scope", SCOPE)}</div></div>
      ${partRow(draft.segments.detectFingerprint, """data-opt="seg.fingerprint"""", "Cross-episode intro fingerprinting", "Finds a shared intro across episodes. Slower, and the only thing that works when an episode has no chapters.")}
      ${partRow(draft.segments.trustStingerTags, """data-opt="seg.stinger"""", "Trust TMDB stinger tags", "Trusts a tag that marks a scene after the credits.")}
      <div class="pf-part" style="grid-template-columns:minmax(0,1fr)"><div>
        <div class="pn">Extra chapter-title keywords (credits)</div>
        <div class="ph-line">Added to the built-in list: chapter names that mean &ldquo;intro&rdquo; or &ldquo;credits&rdquo;.</div>
        <div class="row wrap center" style="gap:6px;margin-top:9px">
          ${kw.joinToString("") { """<span class="chip" data-kw-del="${it.esc()}" title="Click to remove" style="cursor:pointer">${it.esc()} ✕</span>""" }}
          <input class="input" type="text" data-kw="new" placeholder="Add a keyword" style="width:180px;min-height:32px;padding:5px 10px">
          <button class="btn sm ghost" data-kw-add="1">+ Add</button>
        </div></div></div>
"""
}

/** *When a subtitle does not fit*, moved here from the Bazarr card (FR-4.5). It stays `[subtitle_check] action`, saved at once through its own route. */
private fun subtitleActionHtml(): String {
    val s = subcheck
    val opts = listOf("fix" to "Fix it", "ask" to "Ask me first", "report" to "Only report")
    val usable = bazarrOn && s != null
    val reason = when {
        !bazarrOn -> "Needs Bazarr: connect it under Settings ▸ Download tools."
        s == null -> "Couldn't read this setting from the server."
        else -> ""
    }
    return """
      <div class="pf-part" style="grid-template-columns:minmax(0,1fr)"><div>
        <div class="pn">When a subtitle does not fit</div>
        <div class="ph-line">${SUBCHECK_WORDS[s?.action] ?: ""}</div>
        <div class="row center wrap" style="margin-top:10px;max-width:420px;gap:10px">
          <select class="select" data-sub="action"${if (usable) "" else " disabled"} style="min-width:200px">
            ${opts.joinToString("") { (v, w) -> """<option value="$v"${if (s?.action == v) " selected" else ""}>$w</option>""" }}
          </select><span class="tiny muted">${(if (reason.isNotEmpty()) reason else subcheckNote).esc()}</span>
        </div></div></div>"""
}

private val SUBCHECK_WORDS = mapOf(
    "fix" to "Bazarr is steered on its own. A subtitle that only the audio doubts is hidden and waits for your OK.",
    "ask" to "Every action waits for your OK on the Dashboard. A subtitle that does not fit is still hidden from viewers.",
    "report" to "Verdicts and advice only. Nothing is hidden and nothing is asked of Bazarr.",
)
