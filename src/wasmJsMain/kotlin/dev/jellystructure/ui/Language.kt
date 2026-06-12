package dev.jellystructure.ui

import dev.jellystructure.api.ConfigApi
import dev.jellystructure.resolver.LanguageResolver
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderLanguage(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Language</h1>
        </div>
        <p class="page-sub">The resolver picks the TMDB fetch language per file based on its audio tracks, in track-index order. This preview runs in the browser — no round-trip.</p>

        <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">
          <div class="col fill" style="min-width:280px">

            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 14px">Global fallback</h3>
              <div class="field">
                <label>Fallback language</label>
                <input id="lang-fallback" class="input" type="text" placeholder="en" style="width:100%">
                <span class="hint">Used when TMDB has no result in any of the file's track languages. Change in Settings to persist.</span>
              </div>
            </div>

            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 14px">Live resolver preview</h3>
              <div class="field">
                <label>Audio track languages (comma-separated, in track order)</label>
                <input id="lang-tracks" class="input" type="text" placeholder="fo, da" style="width:100%">
                <span class="hint">Enter the language codes exactly as ffprobe reports them (e.g. fo, da, en).</span>
              </div>
              <div id="lang-result" style="margin-top:16px"></div>
            </div>

          </div>

          <div class="card" style="width:300px;flex-shrink:0">
            <h3 style="font-size:1rem;margin:0 0 10px">How it works</h3>
            <ol style="font-size:.87rem;line-height:1.65;padding-left:18px;margin:0">
              <li>The resolver reads each audio track's language tag in physical index order (track 0 first).</li>
              <li>It queries TMDB in that language. If the result has a non-empty plot, it's used.</li>
              <li>If no track language yields a TMDB result, the global fallback is used.</li>
            </ol>
            <div style="margin-top:14px;padding:10px 12px;background:var(--fill-2);border-radius:6px;font-size:.82rem">
              Track languages and the default flag are <strong>never changed automatically</strong>.
              Track management is done from the Triage and Track Order pages.
            </div>
          </div>
        </div>
    """.trimIndent()

    scope.launch {
        val config = ConfigApi.get()
        val fallback = config?.languageRules?.fallbackLanguage ?: "en"
        setInputValue("lang-fallback", fallback)
        runPreview()
    }

    document.getElementById("lang-tracks")?.addEventListener("input") { runPreview() }
    document.getElementById("lang-fallback")?.addEventListener("input") { runPreview() }
}

private fun runPreview() {
    val raw = getInputValue("lang-tracks")
    val fallback = getInputValue("lang-fallback").ifEmpty { "en" }
    val trackLangs = raw.split(",").map { it.trim() }
    val priority = LanguageResolver.priorityList(trackLangs, fallback)

    val el = document.getElementById("lang-result") as? HTMLElement ?: return
    if (priority.isEmpty()) {
        el.innerHTML = ""
        return
    }

    val steps = priority.mapIndexed { i, lang ->
        val isLast = i == priority.lastIndex
        val isFallback = lang == fallback && i == priority.lastIndex
        val label = when {
            isFallback -> "fallback"
            i == 0 -> "track 0"
            else -> "track $i"
        }
        val badgeClass = if (isFallback) "badge" else if (i == 0) "badge ok" else "badge"
        """<div style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
             <span class="$badgeClass" style="min-width:60px;text-align:center">$lang</span>
             <span class="muted tiny">$label — ${if (!isLast) "try TMDB; if no result →" else "use this (final fallback)"}</span>
           </div>"""
    }.joinToString("")

    el.innerHTML = """
        <div style="margin-bottom:8px"><strong style="font-size:.85rem">Resolver would try:</strong></div>
        $steps
    """.trimIndent()
}

private fun setInputValue(id: String, value: String) {
    (document.getElementById(id) as? HTMLInputElement)?.value = value
}

private fun getInputValue(id: String): String =
    (document.getElementById(id) as? HTMLInputElement)?.value?.trim() ?: ""
