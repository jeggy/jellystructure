package dev.jellystructure.ui

import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicProvidersDto
import dev.jellystructure.model.MusicProvidersUpdate
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Phase 276 (FR-276-8) — Settings → Connections → **Metadata providers**: one row per provider the music library uses,
 * each with a status line and *Test*. The card names its providers (the admin registers with them) — nothing a viewer
 * reads ever does. It saves through `PUT /api/music/providers`, never the general Settings save, so a save from
 * another tab cannot reset it. Page-local classes are `jmp-` (not `adv-*`, which ad blockers hide).
 */
internal fun musicProvidersCardHtml(): String = """
    <div class="card set-section" id="sect-musicprov" data-tab="connections">
      <div class="row center"><h3 style="font-size:1.05rem;margin:0;">Metadata providers</h3>
        <span class="badge" style="margin-left:8px;">Music</span><span class="spacer"></span>
        <span id="jmp-status" class="tiny muted"></span></div>
      <div class="tiny muted" style="margin-top:8px;line-height:1.6;">Where the music library's facts come from. Films and series keep TMDB.</div>
      <div id="jmp-body" style="margin-top:10px"><div class="tiny muted">Loading…</div></div>
    </div>
""".trimIndent()

private fun row(name: String, id: String, body: String, test: Boolean = true) = """
    <div class="jmp-row" style="display:flex;gap:10px;align-items:flex-start;padding:10px 0;border-top:1px solid var(--line)">
      <span class="jmp-dot" id="jmp-dot-$id" style="width:8px;height:8px;border-radius:50%;margin-top:6px;flex:none;background:var(--line-2,rgba(255,255,255,.22))"></span>
      <div style="flex:1;min-width:0"><b style="font-size:.9rem">$name</b>$body</div>
      ${if (test) """<button type="button" class="btn sm ghost jmp-test" data-p="$id" style="flex:none">Test</button>""" else ""}
    </div>"""

private fun render(p: MusicProvidersDto): String = buildString {
    append(row("MusicBrainz", "musicbrainz", """
        <div class="tiny muted" style="margin:3px 0 6px">No key. One request a second — MusicBrainz's rule.
          <span id="jmp-mb-last">${p.musicbrainzLast?.let { "Last call: ${it.esc()}." } ?: ""}</span></div>
        <label class="tiny" style="display:flex;gap:6px;align-items:center;margin-bottom:6px">
          <input type="checkbox" id="jmp-mb-on" ${if (p.musicbrainzEnabled) "checked" else ""}> Match albums and artists against MusicBrainz</label>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="tiny muted" style="width:70px">Contact</span>
          <input id="jmp-mb-contact" class="input" type="text" style="flex:1;min-width:200px" value="${p.musicbrainzContact.esc()}"
                 placeholder="${p.musicbrainzContactEffective.esc()}">
        </div>
        <div class="hint">MusicBrainz requires a contact in the request header — an e-mail or a URL. Blank sends the project's own page.</div>"""))
    append(row("Cover Art Archive", "caa", """<div class="tiny muted" style="margin-top:3px">Nothing to configure. Album covers for every matched album.</div>""", test = false))
    append(row("AcoustID", "acoustid", """
        <div class="tiny muted" style="margin:3px 0 6px">Identifies a track by its sound when the tags are not enough.
          ${if (p.acoustIdKeySet) "A key is saved." else "Without a key, Find match… offers no identify-by-sound."}
          <a href="https://acoustid.org/new-application" target="_blank" rel="noopener">Get a client key</a></div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="tiny muted" style="width:70px">Client key</span>
          <input id="jmp-acoustid" class="input mono" type="password" style="flex:1;min-width:200px" placeholder="${if (p.acoustIdKeySet) "saved — type to replace" else "not set"}">
          ${if (p.acoustIdKeySet) """<button type="button" class="btn sm ghost" id="jmp-acoustid-clear">Remove</button>""" else ""}
        </div>"""))
    append(row("fanart.tv", "fanart", """
        <div class="tiny muted" style="margin:3px 0 6px">Artist pictures, backgrounds and logos.
          ${if (p.fanartKeySet) "A key is saved." else "Without a key, artist pictures come from Wikimedia Commons only."}</div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="tiny muted" style="width:70px">Project key</span>
          <input id="jmp-fanart" class="input mono" type="password" style="flex:1;min-width:200px" placeholder="${if (p.fanartKeySet) "saved — type to replace" else "not set"}">
          ${if (p.fanartKeySet) """<button type="button" class="btn sm ghost" id="jmp-fanart-clear">Remove</button>""" else ""}
        </div>""", test = false))
    append(row("Lyrics · LRCLIB", "lrclib", """
        <div class="tiny muted" style="margin:3px 0 6px">No key. Synced lyrics as a <span class="mono">.lrc</span> file beside each song, which Jellyfin reads too.</div>
        <label class="tiny" style="display:flex;gap:6px;align-items:center"><input type="checkbox" id="jmp-lyrics" ${if (p.lyricsEnabled) "checked" else ""}> Fetch lyrics</label>""", test = false))
    append("""<div style="display:flex;gap:8px;align-items:center;margin-top:10px;padding-top:10px;border-top:1px solid var(--line)">
        <button type="button" class="btn sm" id="jmp-save">Save providers</button><span id="jmp-saved" class="tiny muted"></span></div>""")
}

internal fun wireMusicProvidersCard(scope: CoroutineScope) {
    val body = document.getElementById("jmp-body") as? HTMLElement ?: return
    scope.launch {
        val p = MusicApi.providers()
        if (p == null) { body.innerHTML = """<div class="tiny muted">Couldn't load the providers.</div>"""; return@launch }
        body.innerHTML = render(p)
        (document.getElementById("jmp-dot-musicbrainz") as? HTMLElement)?.style?.background =
            if (p.musicbrainzEnabled) "var(--ok)" else "var(--line-2,rgba(255,255,255,.22))"
        (document.getElementById("jmp-dot-caa") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.acoustIdKeySet) (document.getElementById("jmp-dot-acoustid") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.fanartKeySet) (document.getElementById("jmp-dot-fanart") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.lyricsEnabled) (document.getElementById("jmp-dot-lrclib") as? HTMLElement)?.style?.background = "var(--ok)"

        var clearAcoustId = false
        var clearFanart = false
        document.getElementById("jmp-acoustid-clear")?.addEventListener("click") { clearAcoustId = true; (document.getElementById("jmp-saved") as? HTMLElement)?.textContent = "The AcoustID key goes when you save." }
        document.getElementById("jmp-fanart-clear")?.addEventListener("click") { clearFanart = true; (document.getElementById("jmp-saved") as? HTMLElement)?.textContent = "The fanart.tv key goes when you save." }

        val tests = document.querySelectorAll(".jmp-test")
        for (i in 0 until tests.length) {
            val b = tests.item(i) as? HTMLElement ?: continue
            b.addEventListener("click") {
                val name = b.getAttribute("data-p") ?: return@addEventListener
                b.textContent = "Testing…"
                scope.launch {
                    val result = MusicApi.testProvider(name)
                    b.textContent = "Test"
                    (document.getElementById("jmp-status") as? HTMLElement)?.textContent = result
                }
            }
        }

        document.getElementById("jmp-save")?.addEventListener("click") {
            fun v(id: String) = (document.getElementById(id) as? HTMLInputElement)
            val acoustid = v("jmp-acoustid")?.value?.trim().orEmpty()
            val fanart = v("jmp-fanart")?.value?.trim().orEmpty()
            val update = MusicProvidersUpdate(
                musicbrainzEnabled = v("jmp-mb-on")?.checked,
                musicbrainzContact = v("jmp-mb-contact")?.value?.trim(),
                acoustIdKey = if (clearAcoustId) "" else acoustid.ifBlank { null },
                fanartKey = if (clearFanart) "" else fanart.ifBlank { null },
                lyricsEnabled = v("jmp-lyrics")?.checked,
            )
            scope.launch {
                val ok = MusicApi.saveProviders(update)
                (document.getElementById("jmp-saved") as? HTMLElement)?.textContent = if (ok) "Saved ✓" else "Couldn't save"
                if (ok) wireMusicProvidersCard(scope)
            }
        }
    }
}
