package dev.jellystructure.ui

import dev.jellystructure.api.ConfigApi
import dev.jellystructure.resolver.LanguageResolver
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

private val LANG_CYCLE = listOf("da", "fo", "en", "de", "ja", "is", "??")
private val TMDB_LANGS = listOf("da", "fo", "en", "de", "ja", "is")
private var tracksState: MutableList<String> = mutableListOf("fo", "da")
private var tmdbHas: MutableMap<String, Boolean> = mutableMapOf(
    "da" to true, "fo" to false, "en" to true, "de" to false, "ja" to false, "is" to false,
)
private var fallbackState = "en"

fun renderLanguage(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Language Settings</h1>
          <span class="badge info">metadata only</span>
          <span class="spacer"></span>
          <button id="lang-save" class="btn primary">Save</button>
        </div>
        <p class="page-sub">Decides which language Jellystructure uses when fetching metadata from TMDB. For each file it queries TMDB for every language in the audio tracks <b>in physical track-index order</b> (track&nbsp;0 first) — the first language that returns a result wins. If nothing matches, it falls back to the global language below.</p>

        <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">
          <div class="col fill" style="min-width:280px">

            <div class="card">
              <h3 style="margin:0 0 4px;font-size:1.15rem;">How resolution works</h3>
              <div class="tiny muted" style="margin-bottom:14px;">Per movie, or per uniform TV series (sampled across episodes).</div>
              <div class="col" style="gap:10px;">
                <div class="box flat row center" style="padding:12px 14px;gap:12px;">
                  <span class="step-n">1</span>
                  <div><b>Read audio tracks</b><div class="tiny muted">ffprobe builds the track map — index, codec, language.</div></div>
                </div>
                <div class="box flat row center" style="padding:12px 14px;gap:12px;">
                  <span class="step-n">2</span>
                  <div><b>Walk tracks in physical order</b><div class="tiny muted">Track 0, then track 1, then track 2… Query TMDB for each track's language.</div></div>
                </div>
                <div class="box flat row center" style="padding:12px 14px;gap:12px;">
                  <span class="step-n">3</span>
                  <div><b>First TMDB result wins</b><div class="tiny muted">The earliest track whose language returns metadata sets the fetch language.</div></div>
                </div>
                <div class="box flat row center" style="padding:12px 14px;gap:12px;border-style:dashed;">
                  <span class="step-n alt">4</span>
                  <div><b>Otherwise, global fallback</b><div class="tiny muted">No track language matched → use the fallback language below.</div></div>
                </div>
              </div>
              <div class="note blue" style="margin-top:14px;">Untagged tracks are skipped here and surfaced in <a href="#/triage">Triage</a>. Track default flags and ordering are changed only by hand on <a href="#/track-order">Track Order</a> — never automatically.</div>
            </div>

            <div class="card">
              <div class="row center"><h3 style="margin:0;font-size:1.15rem;">Global fallback language</h3><span class="spacer"></span><span class="badge ok">[language_rules]</span></div>
              <hr class="dash" style="margin:12px 0;">
              <div class="row center" style="gap:14px;flex-wrap:wrap;">
                <div class="field" style="margin:0;min-width:200px;">
                  <label>fallback_language</label>
                  <input id="lang-fallback" class="input" type="text" placeholder="en" maxlength="10" style="width:200px;">
                </div>
                <div class="tiny muted fill" style="max-width:46ch;">Used when none of a file's track languages return a TMDB result. Default <span class="lang">en</span>. Individual libraries can override this in <a href="#/settings">Settings → Library mapping</a>.</div>
              </div>
            </div>

          </div>

          <div class="col" style="width:380px;flex-shrink:0">
            <div class="card" style="border:1px solid rgba(63,182,245,.4);">
              <div class="row center"><h4 style="margin:0;">Live resolver</h4><span class="spacer"></span><span class="chip" style="font-size:.66rem;"><span class="dot ok"></span> runs in-browser</span></div>
              <div class="tiny muted" style="margin:6px 0 14px;">Try a file — no server round-trip. Click a track to change its language; toggle which languages TMDB can match.</div>

              <div class="field" style="margin-bottom:12px;">
                <label>Audio tracks · physical order</label>
                <div class="pill-row" id="lang-tracks-chips"></div>
              </div>

              <div class="field" style="margin-bottom:14px;">
                <label>TMDB returns a result for</label>
                <div class="pill-row" id="lang-tmdb-chips"></div>
              </div>

              <div class="box" style="background:var(--bg-2);">
                <div class="mono tiny" id="lang-trace" style="line-height:2;"></div>
                <hr class="dash" style="margin:9px 0;">
                <div class="row center"><span>Fetch metadata in</span><span class="spacer"></span><span class="badge ok lang" id="lang-winner">en</span></div>
              </div>
            </div>

            <div class="card">
              <h4 style="margin:0 0 6px;">Saved to</h4>
              <div class="mono tiny" id="lang-toml-preview" style="line-height:1.9;"></div>
            </div>
            <div id="lang-save-msg" style="display:none;margin-top:4px"></div>
          </div>
        </div>
    """.trimIndent()

    scope.launch {
        val config = ConfigApi.get()
        fallbackState = config?.languageRules?.fallbackLanguage ?: "en"
        setInputValue("lang-fallback", fallbackState)
        updateTomlPreview()
        renderTrackChips()
        renderTmdbChips()
        resolveAndUpdate()
    }

    document.getElementById("lang-fallback")?.addEventListener("input") {
        fallbackState = getInputValue("lang-fallback").ifEmpty { "en" }
        updateTomlPreview()
        resolveAndUpdate()
    }

    document.getElementById("lang-save")?.addEventListener("click") {
        scope.launch {
            val config = ConfigApi.get() ?: return@launch
            val updated = config.copy(
                languageRules = config.languageRules.copy(fallbackLanguage = fallbackState),
            )
            val ok = ConfigApi.save(updated)
            val msgEl = document.getElementById("lang-save-msg") as? HTMLElement ?: return@launch
            msgEl.style.display = "block"
            msgEl.innerHTML = if (ok) """<span class="badge ok">Saved to config.toml ✓</span>"""
                              else """<span class="badge bad">Save failed</span>"""
        }
    }
}

private fun renderTrackChips() {
    val el = document.getElementById("lang-tracks-chips") as? HTMLElement ?: return
    el.innerHTML = ""
    tracksState.forEachIndexed { i, lang ->
        val chip = document.createElement("span") as HTMLElement
        chip.className = "chip sugg"
        chip.style.cursor = "pointer"
        chip.title = "click to change language"
        chip.innerHTML = """<span class="muted mono" style="font-size:.78em;">$i</span> <span class="lang">$lang</span>"""
        chip.addEventListener("click") {
            val next = LANG_CYCLE[(LANG_CYCLE.indexOf(tracksState[i]).takeIf { it >= 0 } ?: 0 + 1) % LANG_CYCLE.size]
            tracksState[i] = next
            renderTrackChips()
            resolveAndUpdate()
        }
        el.appendChild(chip)
    }
    if (tracksState.size < 6) {
        val add = document.createElement("span") as HTMLElement
        add.className = "chip ghost"
        add.textContent = "+ track"
        add.style.cursor = "pointer"
        add.addEventListener("click") {
            tracksState.add("en")
            renderTrackChips()
            resolveAndUpdate()
        }
        el.appendChild(add)
    }
}

private fun renderTmdbChips() {
    val el = document.getElementById("lang-tmdb-chips") as? HTMLElement ?: return
    el.innerHTML = ""
    TMDB_LANGS.forEach { lang ->
        val on = tmdbHas[lang] == true
        val chip = document.createElement("span") as HTMLElement
        chip.className = "chip sugg"
        chip.style.cursor = "pointer"
        if (on) chip.style.borderColor = "var(--ok)"
        chip.innerHTML = """<span class="lang">$lang</span> ${if (on) """<span style="color:var(--ok)">✓</span>""" else """<span class="muted">✗</span>"""}"""
        chip.addEventListener("click") {
            tmdbHas[lang] = !(tmdbHas[lang] ?: false)
            renderTmdbChips()
            resolveAndUpdate()
        }
        el.appendChild(chip)
    }
}

private fun resolveAndUpdate() {
    val fallback = fallbackState.ifEmpty { "en" }
    val traceEl = document.getElementById("lang-trace") as? HTMLElement ?: return
    val winnerEl = document.getElementById("lang-winner") as? HTMLElement ?: return

    val lines = mutableListOf<String>()
    var winner: String? = null
    tracksState.forEachIndexed { i, lang ->
        if (winner != null) {
            lines.add("""<span class="muted">$i $lang? skipped</span>""")
            return@forEachIndexed
        }
        if (lang == "??") {
            lines.add("""<span style="color:var(--warn)">$i ?? untagged → see triage</span>""")
            return@forEachIndexed
        }
        if (tmdbHas[lang] == true) {
            lines.add("""$i $lang? <span style="color:var(--ok)">✓ result → winner</span>""")
            winner = lang
        } else {
            lines.add("""<span class="muted">$i $lang? ✗ no result</span>""")
        }
    }
    if (winner == null) {
        lines.add("""<span style="color:var(--bad)">no track matched</span> → fallback <span style="color:var(--ok)">$fallback</span>""")
        winner = fallback
    }
    traceEl.innerHTML = lines.joinToString("<br>")
    winnerEl.textContent = winner
}

private fun updateTomlPreview() {
    val el = document.getElementById("lang-toml-preview") as? HTMLElement ?: return
    el.innerHTML = """config.toml → [language_rules]<br>fallback_language = "$fallbackState""""
}

private fun setInputValue(id: String, value: String) {
    (document.getElementById(id) as? HTMLInputElement)?.value = value
}

private fun getInputValue(id: String): String =
    (document.getElementById(id) as? HTMLInputElement)?.value?.trim() ?: ""
