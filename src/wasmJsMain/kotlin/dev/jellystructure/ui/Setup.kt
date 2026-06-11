package dev.jellystructure.ui

import dev.jellystructure.api.AuthApi
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderSetup(onComplete: suspend () -> Unit) {
    val body = document.body ?: return
    body.innerHTML = """
        <div style="display:flex;align-items:center;justify-content:center;min-height:100vh;padding:20px">
          <div class="card" style="width:100%;max-width:480px;padding:28px 30px">
            <div style="display:flex;align-items:center;gap:10px;margin-bottom:18px">
              <span class="glyph" style="width:26px;height:26px;border:2px solid var(--ink);border-radius:6px 3px 7px 4px;background:repeating-linear-gradient(135deg,transparent 0 3px,var(--ink) 3px 4px);display:inline-block"></span>
              <strong style="font-size:1.15rem">Jellystructure</strong>
            </div>
            <h2 style="font-size:1.35rem;margin:0 0 4px">Welcome — connect your server</h2>
            <p class="muted tiny" style="margin:0 0 22px">This runs once. After saving, sign in with your Jellyfin admin account.</p>
            <form id="setup-form">
              <div class="field">
                <label>Jellyfin URL</label>
                <input id="jellyfin-url" class="input" type="url" placeholder="http://localhost:8096" style="width:100%">
                <span class="hint">Base URL of your Jellyfin server, no trailing slash</span>
              </div>
              <div class="field">
                <label>Jellyfin machine token</label>
                <input id="jellyfin-token" class="input" type="text" placeholder="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" style="width:100%">
                <span class="hint">API key for background jobs — Jellyfin → Dashboard → API Keys → + New key</span>
              </div>
              <div class="field">
                <label>TMDB API key (v3)</label>
                <input id="tmdb-key" class="input" type="text" style="width:100%">
                <span class="hint">Free key at themoviedb.org/settings/api</span>
              </div>
              <div id="setup-error" style="display:none;margin-bottom:12px">
                <span class="badge bad" id="setup-error-text"></span>
              </div>
              <button type="submit" class="btn primary" style="width:100%;justify-content:center;margin-top:4px">
                Save &amp; continue to sign in
              </button>
            </form>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("setup-form")?.addEventListener("submit") { e ->
        e.preventDefault()
        val url = (document.getElementById("jellyfin-url") as? HTMLInputElement)?.value?.trim() ?: ""
        val token = (document.getElementById("jellyfin-token") as? HTMLInputElement)?.value?.trim() ?: ""
        val tmdb = (document.getElementById("tmdb-key") as? HTMLInputElement)?.value?.trim() ?: ""

        if (url.isBlank()) {
            showSetupError("Jellyfin URL is required")
            return@addEventListener
        }

        val btn = document.querySelector("#setup-form button") as? HTMLElement
        btn?.setAttribute("disabled", "true")
        hideSetupError()

        MainScope().launch {
            AuthApi.setup(url, token, tmdb)
                .onSuccess { onComplete() }
                .onFailure { err ->
                    showSetupError(err.message ?: "Setup failed")
                    btn?.removeAttribute("disabled")
                }
        }
    }
}

private fun showSetupError(msg: String) {
    val el = document.getElementById("setup-error") as? HTMLElement ?: return
    val text = document.getElementById("setup-error-text") ?: return
    text.textContent = msg
    el.style.display = "block"
}

private fun hideSetupError() {
    val el = document.getElementById("setup-error") as? HTMLElement ?: return
    el.style.display = "none"
}
