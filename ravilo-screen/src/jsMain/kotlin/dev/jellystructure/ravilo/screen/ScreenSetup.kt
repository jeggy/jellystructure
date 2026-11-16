package dev.jellystructure.ravilo.screen

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
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

    var probing = false
    suspend fun tryConnect() {
        val host = input.value.trim()
        if (host.isBlank() || probing) return
        probing = true
        val hasScheme = host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)
        val url = (if (hasScheme) host else "https://$host").trimEnd('/')
        hint.textContent = ScreenLanguage.t("receiver.setup_trying")
        val ok = runCatching {
            val r = probeClient.get("$url/api/health")
            r.status.isSuccess() && r.bodyAsText().contains("\"status\":\"ok\"")
        }.getOrDefault(false)
        probing = false
        if (ok) resolved.complete(url) else {
            // FR-R269-3 — the typed text stays, and the field is where the remote is again.
            hint.textContent = ScreenLanguage.t("receiver.setup_not_found")
            input.focus()
        }
    }
    button.addEventListener("click", { GlobalScope.launch { tryConnect() } })

    // DEB-4817 (FR-4 of the full-app spec, FR-R269-1) — the remote's own keys drive setup. Nothing in the
    // page was focused before, so Up/Down/OK reached no handler at all. Two targets, field and button:
    // Down/Up move between them, OK on the button probes, OK on the field is left to Tizen (it raises
    // the IME), and the IME's Done key hands the focus on to the button. Matched by `key` and by key code
    // (Tizen 5's Chromium 56 does not always set `key` for the remote).
    val onSetupKey: (org.w3c.dom.events.Event) -> Unit = { event ->
        val e = event as KeyboardEvent
        val onField = document.activeElement == input
        val onButton = document.activeElement == button
        when (setupKeyOf(e.key, e.keyCode)) {
            SetupKey.Down -> { e.preventDefault(); button.focus() }
            SetupKey.Up -> { e.preventDefault(); input.focus() }
            SetupKey.Ok -> if (onButton) { e.preventDefault(); GlobalScope.launch { tryConnect() } }
            SetupKey.ImeDone -> if (onField) { button.focus() }
            null -> {}
        }
    }
    window.addEventListener("keydown", onSetupKey)
    input.focus()

    val url = resolved.await()
    window.removeEventListener("keydown", onSetupKey)
    localStorage.setItem(SERVER_URL_KEY, url)
    root.classList.remove("on")
    return url
}

internal enum class SetupKey { Up, Down, Ok, ImeDone }

/** The setup screen's remote keys. 13 is OK/Enter, 38/40 Up/Down, 65376 Tizen's IME "Done". */
internal fun setupKeyOf(key: String?, keyCode: Int): SetupKey? = when {
    key == "ArrowDown" || keyCode == 40 -> SetupKey.Down
    key == "ArrowUp" || keyCode == 38 -> SetupKey.Up
    key == "Enter" || keyCode == 13 -> SetupKey.Ok
    keyCode == 65376 -> SetupKey.ImeDone
    else -> null
}

/** Two typings of one address: scheme-and-host compare without case or a trailing slash. */
internal fun sameServer(a: String, b: String): Boolean =
    a.trim().trimEnd('/').lowercase() == b.trim().trimEnd('/').lowercase()
