package dev.jellystructure.ravilo.screen

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/**
 * R269 — the server-address setup screen, reached only when the TV holds no server
 * address at all (FR-R269-1). Probes `GET /api/health` — already unauthenticated, already returns
 * `{"status":"ok",…}` (see the phase's dev review: no backend change was needed, the endpoint already
 * existed for an unrelated reason) — and stores the address only once that succeeds, so a half-typed
 * address can never brick the boot path (the dev notes' own requirement). Scheme inference matches what
 * R226's `ServerSetupScreen` actually ships (verified against that code, not the FR's own — since
 * corrected — description of it): `https://` unless the household types a scheme themselves.
 */
internal suspend fun runServerSetup(): String {
    val root = document.getElementById("setup") as HTMLElement
    val input = document.getElementById("setupHost") as HTMLInputElement
    val hint = document.getElementById("setupHint") as HTMLElement
    val button = document.getElementById("setupConnect") as HTMLElement
    document.getElementById("setupTitle")?.textContent = ScreenLanguage.t("receiver.setup_title")
    button.textContent = ScreenLanguage.t("receiver.setup_connect")
    hint.textContent = ScreenLanguage.t("receiver.setup_hint")
    // FR-R269-7 — a hold-Back reopen prefills the address that was just working, never a blank field.
    // DEB-4663 — an `http://` address keeps its scheme: stripped, it would be re-read as `https://` and
    // look like a new address, which signs every profile out.
    localStorage.getItem(SETUP_PREFILL_KEY)?.let { input.value = it.removePrefix("https://") }
    localStorage.removeItem(SETUP_PREFILL_KEY)
    // The idle screen is `on` in the static markup (so a set with a server never flashes black); with
    // no server yet it would sit under setup, both "on" at once (seen in the emulator's DOM).
    document.getElementById("idle")?.classList?.remove("on")
    root.classList.add("on")

    val probeClient = HttpClient(Js)
    val resolved = CompletableDeferred<String>()

    suspend fun tryConnect() {
        val host = input.value.trim()
        if (host.isBlank()) return
        val hasScheme = host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)
        val url = (if (hasScheme) host else "https://$host").trimEnd('/')
        hint.textContent = ScreenLanguage.t("receiver.setup_trying")
        val ok = runCatching {
            val r = probeClient.get("$url/api/health")
            r.status.isSuccess() && r.bodyAsText().contains("\"status\":\"ok\"")
        }.getOrDefault(false)
        if (ok) resolved.complete(url) else hint.textContent = ScreenLanguage.t("receiver.setup_not_found")
    }
    button.addEventListener("click", { GlobalScope.launch { tryConnect() } })
    input.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") GlobalScope.launch { tryConnect() } })

    val url = resolved.await()
    localStorage.setItem(SERVER_URL_KEY, url)
    root.classList.remove("on")
    return url
}

/** Two typings of one address: scheme-and-host compare without case or a trailing slash. */
internal fun sameServer(a: String, b: String): Boolean =
    a.trim().trimEnd('/').lowercase() == b.trim().trimEnd('/').lowercase()
