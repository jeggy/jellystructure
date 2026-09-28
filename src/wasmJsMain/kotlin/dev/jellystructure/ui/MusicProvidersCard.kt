package dev.jellystructure.ui

import dev.jellystructure.api.AudiobooksApi
import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.AudiobookProvidersDto
import dev.jellystructure.model.AudiobookProvidersUpdate
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
 * reads ever does. It has **no Save of its own** (2026-09-28 amendment): the page's one Save sends it, after
 * `PUT /api/config`, through `PUT /api/music/providers` and `PUT /api/audiobooks/providers` — and only what changed,
 * so an untouched card never overwrites a newer value. `PUT /api/config` still keeps the stored values, so a page
 * loaded before this card existed cannot reset them. Page-local classes are `jmp-` (not `adv-*`, which ad blockers hide).
 */
internal fun musicProvidersCardHtml(): String = """
    <div class="card set-section" id="sect-musicprov" data-tab="connections">
      <div class="row center"><h3 style="font-size:1.05rem;margin:0;">Metadata providers</h3>
        <span class="badge" style="margin-left:8px;">Music · Audiobooks</span><span class="spacer"></span>
        <span id="jmp-status" class="tiny muted"></span></div>
      <div class="tiny muted" style="margin-top:8px;line-height:1.6;">Where the music and audiobook libraries' facts come from. Films and series keep TMDB. For music MusicBrainz is the source of truth; for audiobooks the files and what you type come first, and these only <b>suggest</b>.</div>
      <div id="jmp-body" style="margin-top:10px"><div class="tiny muted">Loading…</div></div>
      <div id="jmp-ab-body" style="margin-top:14px"></div>
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
          ${if (p.fanartKeySet) "A key is saved." else "Without a key, artist pictures come from Wikimedia Commons only."}
          <a href="https://fanart.tv/get-an-api-key/#project" target="_blank" rel="noopener">Get a project key</a></div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="tiny muted" style="width:70px">Project key</span>
          <input id="jmp-fanart" class="input mono" type="password" style="flex:1;min-width:200px" placeholder="${if (p.fanartKeySet) "saved — type to replace" else "not set"}">
          ${if (p.fanartKeySet) """<button type="button" class="btn sm ghost" id="jmp-fanart-clear">Remove</button>""" else ""}
        </div>
        <div class="hint">Free. Sign in to fanart.tv (or create an account), open <b>Get an API key</b>, and request a key under <b>Project API Keys</b>. Paste that one here — a <i>personal</i> key is a different kind and does not work on its own.</div>""", test = false))
    append(row("Lyrics · LRCLIB", "lrclib", """
        <div class="tiny muted" style="margin:3px 0 6px">No key. Synced lyrics as a <span class="mono">.lrc</span> file beside each song, which Jellyfin reads too.</div>
        <label class="tiny" style="display:flex;gap:6px;align-items:center"><input type="checkbox" id="jmp-lyrics" ${if (p.lyricsEnabled) "checked" else ""}> Fetch lyrics</label>""", test = false))
}

/** Phase 281 (FR-281-4/8) — the audiobook rows: suggestion providers and the tag-writing switch. The page's Save sends
 *  them through `PUT /api/audiobooks/providers`. */
private fun renderAudiobooks(p: AudiobookProvidersDto): String = buildString {
    append("""<div class="mu-sec" style="margin-top:0">Audiobooks · suggestions only</div>""")
    val stores = listOf("dk", "no", "se", "gb", "us")
    append(row("iTunes", "itunes", """
        <div class="tiny muted" style="margin:3px 0 6px">No key, no narrators. Covers at 600 px. There is no Faroese store.</div>
        <div style="display:flex;gap:6px;align-items:center"><span class="tiny muted" style="width:70px">Store</span>
          <select id="jmp-itunes" class="input" style="width:auto">${(stores + p.itunesStore).distinct().joinToString("") { """<option value="$it"${if (it == p.itunesStore) " selected" else ""}>${it.uppercase()}</option>""" }}</select></div>""", test = false))
    append(row("Google Books", "googlebooks", """
        <div class="tiny muted" style="margin:3px 0 6px">Book data, not audiobook data. ${if (p.googleBooksKeySet) "A key is saved." else "Without a key the shared quota is always used up, so this provider is skipped."}</div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="tiny muted" style="width:70px">API key</span>
          <input id="jmp-gbooks" class="input mono" type="password" style="flex:1;min-width:200px" placeholder="${if (p.googleBooksKeySet) "saved — type to replace" else "not set"}">
          ${if (p.googleBooksKeySet) """<button type="button" class="btn sm ghost" id="jmp-gbooks-clear">Remove</button>""" else ""}
        </div>""", test = false))
    append(row("Open Library", "openlibrary", """<div class="tiny muted" style="margin-top:3px">Nothing to configure. Bibliographic only — one request a second.</div>""", test = false))
    val regions = listOf("au", "ca", "de", "es", "fr", "in", "it", "jp", "us", "uk")
    append(row("Audnexus", "audnexus", """
        <div class="tiny muted" style="margin:3px 0 6px">Narrators and series for the titles Audible sells — asked only when you paste an Audible ASIN on a book. No Danish store.</div>
        <div style="display:flex;gap:6px;align-items:center"><span class="tiny muted" style="width:70px">Region</span>
          <select id="jmp-audnexus" class="input" style="width:auto">${regions.joinToString("") { """<option value="$it"${if (it == p.audnexusRegion) " selected" else ""}>$it</option>""" }}</select></div>""", test = false))
    append(row("Write tags into audiobook files", "abtags", """
        <div class="tiny muted" style="margin:3px 0 6px;line-height:1.6">The only way Jellyfin’s own apps show a narrator or a description — Jellyfin reads no metadata file for audiobooks. When on, <b>Save</b> on a book writes title, author, narrator and description into every part. Ravilo doesn’t need it. Files seeding in qBittorrent are skipped.</div>
        ${if (p.taggerAvailable) """<label class="tiny" style="display:flex;gap:6px;align-items:center"><input type="checkbox" id="jmp-abtags" ${if (p.writeTags) "checked" else ""}> Write tags when a book is saved</label>"""
          else """<div class="tiny" style="color:var(--warn)">This server has no tag writer installed (python3 with mutagen), so the switch has nothing to drive.</div>"""}""", test = false))
}

// What the page's Save reads: the values each half was rendered from (null until it loaded, so a card that never
// loaded is never saved) and the keys marked for removal.
private var musicLoaded: MusicProvidersDto? = null
private var audiobooksLoaded: AudiobookProvidersDto? = null
private var clearAcoustId = false
private var clearFanart = false
private var clearGoogleBooks = false
private var providersScope: CoroutineScope? = null

private fun note(text: String) { (document.getElementById("jmp-status") as? HTMLElement)?.textContent = text }

private fun wireAudiobookProviders(scope: CoroutineScope) {
    audiobooksLoaded = null
    clearGoogleBooks = false
    val body = document.getElementById("jmp-ab-body") as? HTMLElement ?: return
    scope.launch {
        val p = AudiobooksApi.providers() ?: return@launch
        body.innerHTML = renderAudiobooks(p)
        audiobooksLoaded = p
        listOf("itunes", "openlibrary").forEach { (document.getElementById("jmp-dot-$it") as? HTMLElement)?.style?.background = "var(--ok)" }
        if (p.googleBooksKeySet) (document.getElementById("jmp-dot-googlebooks") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.writeTags && p.taggerAvailable) (document.getElementById("jmp-dot-abtags") as? HTMLElement)?.style?.background = "var(--ok)"
        document.getElementById("jmp-gbooks-clear")?.addEventListener("click") { clearGoogleBooks = true; note("The Google Books key goes when you press Save.") }
    }
}

internal fun wireMusicProvidersCard(scope: CoroutineScope) {
    providersScope = scope
    wireAudiobookProviders(scope)
    musicLoaded = null
    clearAcoustId = false
    clearFanart = false
    val body = document.getElementById("jmp-body") as? HTMLElement ?: return
    scope.launch {
        val p = MusicApi.providers()
        if (p == null) { body.innerHTML = """<div class="tiny muted">Couldn't load the providers.</div>"""; return@launch }
        body.innerHTML = render(p)
        musicLoaded = p
        (document.getElementById("jmp-dot-musicbrainz") as? HTMLElement)?.style?.background =
            if (p.musicbrainzEnabled) "var(--ok)" else "var(--line-2,rgba(255,255,255,.22))"
        (document.getElementById("jmp-dot-caa") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.acoustIdKeySet) (document.getElementById("jmp-dot-acoustid") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.fanartKeySet) (document.getElementById("jmp-dot-fanart") as? HTMLElement)?.style?.background = "var(--ok)"
        if (p.lyricsEnabled) (document.getElementById("jmp-dot-lrclib") as? HTMLElement)?.style?.background = "var(--ok)"

        document.getElementById("jmp-acoustid-clear")?.addEventListener("click") { clearAcoustId = true; note("The AcoustID key goes when you press Save.") }
        document.getElementById("jmp-fanart-clear")?.addEventListener("click") { clearFanart = true; note("The fanart.tv key goes when you press Save.") }

        val tests = document.querySelectorAll(".jmp-test")
        for (i in 0 until tests.length) {
            val b = tests.item(i) as? HTMLElement ?: continue
            b.addEventListener("click") {
                val name = b.getAttribute("data-p") ?: return@addEventListener
                b.textContent = "Testing…"
                scope.launch {
                    val result = MusicApi.testProvider(name)
                    b.textContent = "Test"
                    note(result)
                }
            }
        }
    }
}

/** The card's part of the page's one Save, called after `PUT /api/config` succeeded. Sends each half only when
 *  something in it changed, one after the other (both write `config.toml`). False when a write failed. */
internal suspend fun saveMetadataProviders(): Boolean {
    fun input(id: String) = document.getElementById(id) as? HTMLInputElement
    fun select(id: String) = (document.getElementById(id) as? org.w3c.dom.HTMLSelectElement)?.value
    var wrote = false
    var ok = true
    musicLoaded?.let { p ->
        val acoustid = input("jmp-acoustid")?.value?.trim().orEmpty()
        val fanart = input("jmp-fanart")?.value?.trim().orEmpty()
        val update = MusicProvidersUpdate(
            musicbrainzEnabled = input("jmp-mb-on")?.checked,
            musicbrainzContact = input("jmp-mb-contact")?.value?.trim(),
            acoustIdKey = if (clearAcoustId) "" else acoustid.ifBlank { null },
            fanartKey = if (clearFanart) "" else fanart.ifBlank { null },
            lyricsEnabled = input("jmp-lyrics")?.checked,
        )
        val unchanged = MusicProvidersUpdate(musicbrainzEnabled = p.musicbrainzEnabled, musicbrainzContact = p.musicbrainzContact.trim(), lyricsEnabled = p.lyricsEnabled)
        if (update != unchanged) { wrote = true; ok = MusicApi.saveProviders(update) && ok }
    }
    audiobooksLoaded?.let { p ->
        val key = input("jmp-gbooks")?.value?.trim().orEmpty()
        val tags = input("jmp-abtags")
        val update = AudiobookProvidersUpdate(
            itunesStore = select("jmp-itunes"), audnexusRegion = select("jmp-audnexus"),
            googleBooksKey = if (clearGoogleBooks) "" else key.ifBlank { null },
            writeTags = tags?.checked,
        )
        val unchanged = AudiobookProvidersUpdate(itunesStore = p.itunesStore, audnexusRegion = p.audnexusRegion, writeTags = if (tags != null) p.writeTags else null)
        if (update != unchanged) { wrote = true; ok = AudiobooksApi.saveProviders(update) && ok }
    }
    // Re-read so the rows say what is saved now ("A key is saved.", the dots) and the key fields empty again.
    if (wrote && ok) providersScope?.let { wireMusicProvidersCard(it) }
    return ok
}
