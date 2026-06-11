package dev.jellystructure.ui

import dev.jellystructure.api.AuthApi
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderLogin(onSuccess: suspend () -> Unit) {
    val body = document.body ?: return
    body.innerHTML = """
        <div style="display:flex;align-items:center;justify-content:center;min-height:100vh;padding:20px">
          <div class="card" style="width:100%;max-width:380px;padding:28px 30px">
            <div style="display:flex;align-items:center;gap:10px;margin-bottom:22px">
              <span class="glyph" style="width:26px;height:26px;border:2px solid var(--ink);border-radius:6px 3px 7px 4px;background:repeating-linear-gradient(135deg,transparent 0 3px,var(--ink) 3px 4px);display:inline-block"></span>
              <strong style="font-size:1.15rem">Jellystructure</strong>
            </div>
            <h2 style="font-size:1.35rem;margin:0 0 4px">Sign in</h2>
            <p class="muted tiny" style="margin:0 0 20px">Use your Jellyfin administrator credentials</p>
            <form id="login-form">
              <div class="field">
                <label>Username</label>
                <input id="username" class="input" type="text" autocomplete="username" style="width:100%">
              </div>
              <div class="field">
                <label>Password</label>
                <input id="password" class="input" type="password" autocomplete="current-password" style="width:100%">
              </div>
              <div id="login-error" style="display:none;margin-bottom:12px">
                <span class="badge bad" id="login-error-text"></span>
              </div>
              <button type="submit" class="btn primary" style="width:100%;justify-content:center;margin-top:4px">
                Sign in
              </button>
            </form>
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
