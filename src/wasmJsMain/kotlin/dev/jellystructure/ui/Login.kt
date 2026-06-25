package dev.jellystructure.ui

import dev.jellystructure.api.AuthApi
import dev.jellystructure.api.httpClient
import io.ktor.client.request.get
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderLogin(onSuccess: suspend () -> Unit) {
    val body = document.body ?: return
    body.innerHTML = """
        <div style="display:flex;align-items:center;justify-content:center;min-height:100vh;padding:40px 20px">
          <div style="width:100%;max-width:408px">
            <div style="display:flex;align-items:center;gap:13px;justify-content:center;margin-bottom:26px">
              <svg style="width:40px;height:40px;flex:none;border-radius:12px;filter:drop-shadow(0 6px 20px rgba(123,110,240,.5))" viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="jsg-login" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#b15cd0"/><stop offset=".52" stop-color="#7b6ef0"/><stop offset="1" stop-color="#00a4dc"/></linearGradient></defs><rect width="100" height="100" rx="30" fill="url(#jsg-login)"/><g transform="translate(18 18) scale(.64)" fill="#fff"><rect x="10" y="10" width="35" height="35" rx="9"/><rect x="55" y="10" width="35" height="35" rx="9" opacity=".5"/><rect x="10" y="55" width="35" height="35" rx="9" opacity=".5"/><rect x="55" y="55" width="35" height="35" rx="9" fill="none" stroke="#fff" stroke-width="6"/><path d="M68 64 L84 72.5 L68 81 Z"/></g></svg>
              <strong style="font-family:'Space Grotesk',sans-serif;font-size:1.5rem;font-weight:600;letter-spacing:-.01em">Jellystructure</strong>
            </div>
            <div class="card" style="padding:34px 32px">
              <h2 style="font-size:1.35rem;margin:0 0 4px">Sign in</h2>
              <p class="muted" style="margin:0 0 22px;font-size:.92rem">Use your <b>Jellyfin administrator</b> account. Only admin accounts can manage metadata.</p>
              <form id="login-form">
                <div class="field">
                  <label>Jellyfin username</label>
                  <input id="username" class="input" type="text" autocomplete="username" style="width:100%">
                </div>
                <div class="field" style="margin-bottom:8px">
                  <label>Password</label>
                  <input id="password" class="input" type="password" autocomplete="current-password" style="width:100%">
                </div>
                <div id="login-error" style="display:none;margin:6px 0 14px">
                  <span class="badge bad" id="login-error-text"></span>
                </div>
                <button type="submit" class="btn primary" style="width:100%;justify-content:center;margin-top:8px;padding:11px">Sign in</button>
              </form>
              <hr class="dash" style="margin:20px 0 14px">
              <div id="login-conn-row" class="row center" style="gap:8px;font-size:.84rem">
                <span class="dot" id="conn-dot"></span>
                <span class="muted">Connected to</span>
                <span class="mono" id="conn-host" style="color:var(--ink)">—</span>
                <span class="spacer"></span>
                <span class="badge" id="conn-badge">checking…</span>
              </div>
            </div>
            <div class="tiny muted" style="text-align:center;margin-top:22px">
              Authenticates via Jellyfin <span class="mono">/Users/AuthenticateByName</span>. Session lasts 7 days.
            </div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("login-form")?.addEventListener("submit") { e ->
        e.preventDefault()
        val username = (document.getElementById("username") as? HTMLInputElement)?.value ?: ""
        val password = (document.getElementById("password") as? HTMLInputElement)?.value ?: ""

        if (username.isBlank()) return@addEventListener

        val btn = document.querySelector("#login-form button") as? HTMLElement
        btn?.setAttribute("disabled", "true")
        hideError()

        // Async: check Jellyfin connection and show status
    MainScope().launch {
        val (host, ok) = runCatching {
            val resp = httpClient.get("/api/health")
            val h = resp.headers["X-Jellyfin-Host"] ?: "jellyfin"
            Pair(h, resp.status.value in 200..299)
        }.getOrDefault(Pair("jellyfin", false))
        val dot = document.getElementById("conn-dot") as? HTMLElement
        val badge = document.getElementById("conn-badge") as? HTMLElement
        val hostEl = document.getElementById("conn-host") as? HTMLElement
        hostEl?.textContent = host
        if (ok) {
            dot?.className = "dot ok"
            badge?.className = "badge ok"
            badge?.textContent = "online"
        } else {
            dot?.className = "dot bad"
            badge?.className = "badge bad"
            badge?.textContent = "offline"
        }
    }

    MainScope().launch {
            AuthApi.login(username, password)
                .onSuccess { onSuccess() }
                .onFailure { err ->
                    showError(err.message ?: "Login failed")
                    btn?.removeAttribute("disabled")
                }
        }
    }
}

private fun showError(msg: String) {
    val el = document.getElementById("login-error") ?: return
    val text = document.getElementById("login-error-text") ?: return
    text.textContent = msg
    (el as? HTMLElement)?.style?.display = "block"
}

private fun hideError() {
    val el = document.getElementById("login-error") ?: return
    (el as? HTMLElement)?.style?.display = "none"
}
