package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.shared.qr.QrEncoder
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import dev.jellystructure.shared.tv.TvSignInPoll
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

private const val STRAY_OK_MS = 1_000L

/** DEB-4657 FR-2: the two open sign-in routes and the password fallback, drawn in DOM. */
internal class ScreenSignIn(
    private val api: TvApiClient,
    private val deviceId: String,
    private val deviceName: String,
    private val onSignedIn: (PairResult) -> Unit,
    private val onBack: () -> Unit,
    private val onChangeServer: () -> Unit,
) {
    private val root get() = document.getElementById("signin") as HTMLElement
    private val message get() = document.getElementById(if (showingPassword) "signin-password-message" else "signin-message") as HTMLElement
    private val code get() = document.getElementById("signin-code") as HTMLElement
    private val qr get() = document.getElementById("signin-qr") as HTMLElement
    private val passwordButton get() = document.getElementById("signin-password-instead") as HTMLElement
    private val retryButton get() = document.getElementById("signin-retry") as HTMLElement
    private val username get() = document.getElementById("signin-username") as HTMLInputElement
    private val password get() = document.getElementById("signin-password") as HTMLInputElement
    private val form get() = document.getElementById("signin-form") as HTMLElement
    private val codeOnlyView get() = document.getElementById("signin-code-only") as HTMLElement
    private val codeOnlyButton get() = document.getElementById("signin-code-only-button") as HTMLElement
    private val changeServerButton get() = document.getElementById("signin-change-server") as HTMLElement
    private var job: Job? = null
    private var showingPassword = false
    private var showingCodeOnlyMessage = false
    private var hasProfiles = false
    private var generation = 0
    private var signingIn = false
    private var openedAt = 0L

    private fun label(key: String, vars: Map<String, String> = emptyMap()) = t(key, ScreenLanguage.lang, vars)

    fun open(withProfiles: Boolean) {
        hasProfiles = withProfiles
        root.classList.add("on")
        document.getElementById("signin-title")?.textContent = label("tvsignin.title")
        document.getElementById("signin-code-label")?.textContent = label("tvsignin.code_label")
        document.getElementById("signin-tv-label")?.textContent = label("tvsignin.this_tv")
        document.getElementById("signin-tv-name")?.textContent = deviceName
        passwordButton.textContent = label("tvsignin.password_instead")
        codeOnlyButton.textContent = label("tvsignin.use_code_instead")
        retryButton.textContent = label("action.retry")
        document.getElementById("signin-username-label")?.textContent = label("login.username")
        document.getElementById("signin-password-label")?.textContent = label("login.password")
        document.getElementById("signin-submit")?.textContent = label("login.sign_in")
        document.getElementById("signin-cancel")?.textContent = label("profile.cancel")
        document.getElementById("signin-back")?.textContent = label("tvsignin.back_to_profiles")
        (document.getElementById("signin-back") as HTMLElement).style.display = if (withProfiles) "block" else "none"
        // DEB-4831 — the OK press that confirmed the address (or its key-up, or Tizen's own click) can still be on its way
        // when this screen opens with *password instead* focused; it must not read as a choice of the password form.
        passwordButton.onclick = {
            if (nowMs() - openedAt >= STRAY_OK_MS) openPassword("chosen")
            else console.log("Ravilo screen: sign-in: ignored an OK within ${STRAY_OK_MS}ms of opening")
        }
        codeOnlyButton.onclick = { openCode() }
        retryButton.onclick = { openCode() }
        (document.getElementById("signin-submit") as HTMLElement).onclick = { submitPassword() }
        (document.getElementById("signin-cancel") as HTMLElement).onclick = { openCode() }
        // DEB-4947 (FR-11) — the way back to setup, in every view of this screen; its host line is the server being signed in to.
        changeServerButton.textContent = label("login.change_server")
        document.getElementById("signin-server")?.textContent = api.baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        changeServerButton.onclick = { onChangeServer() }
        openedAt = nowMs()
        openCode()
    }

    fun hide() { generation++; job?.cancel(); job = null; signingIn = false; root.classList.remove("on") }
    fun isOpen(): Boolean = root.classList.contains("on")

    private fun openCode() {
        generation++
        job?.cancel()
        signingIn = false
        (document.getElementById("signin-submit") as HTMLElement).removeAttribute("disabled")
        showingPassword = false
        showingCodeOnlyMessage = false
        form.style.display = "none"
        codeOnlyView.style.display = "none"
        (document.getElementById("signin-code-view") as HTMLElement).style.display = "block"
        passwordButton.style.display = if (isCodeOnly()) "none" else "inline-block"
        retryButton.style.display = "none"
        retryButton.textContent = label("action.retry")
        code.textContent = ""
        qr.textContent = ""
        message.textContent = ""
        if (isCodeOnly()) code.focus() else passwordButton.focus()
        job = GlobalScope.launch { codeLoop() }
    }

    private suspend fun codeLoop() {
        val requestGeneration = generation
        while (requestGeneration == generation) {
            val minted = runCatching { api.signInCode(deviceId, deviceName) }.getOrNull()
            if (requestGeneration != generation) return
            if (minted == null) { unreachable(); return }
            if (!minted.available) {
                if (isCodeOnly()) {
                    message.textContent = label("tvsignin.code_unavailable_code_only")
                    retryButton.textContent = label("tvsignin.try_again")
                    retryButton.style.display = "inline-block"
                    retryButton.focus()
                } else openPassword("code unavailable")
                return
            }
            val value = minted.code ?: run { unreachable(); return }
            val secret = minted.secret ?: run { unreachable(); return }
            code.textContent = if (value.length > 3) value.take(3) + " " + value.drop(3) else value
            message.textContent = label(if (minted.linkUrl == null) "tvsignin.how_noqr" else "tvsignin.how")
            drawQr(minted.linkUrl)
            var failures = 0
            while (requestGeneration == generation) {
                delay(3_000)
                // An expired code is re-minted without showing an error or an empty code.
                if (minted.expiresAt?.let { (js("Date.now()") as Double) >= it.toDouble() } == true) break
                val poll = try { api.signInPoll(value, secret) } catch (e: Exception) {
                    if (requestGeneration != generation) return
                    if (++failures == 5) { unreachable(); return }
                    continue
                }
                if (requestGeneration != generation) return
                failures = 0
                when (poll) {
                    is TvSignInPoll.Waiting -> {
                        val name = poll.approverName
                        message.textContent = if (poll.approving && name != null)
                            label("tvsignin.approving", mapOf("name" to name))
                        else label(if (minted.linkUrl == null) "tvsignin.how_noqr" else "tvsignin.how")
                    }
                    is TvSignInPoll.Done -> { hide(); onSignedIn(poll.result); return }
                    TvSignInPoll.Invalid -> break
                }
            }
        }
    }

    private fun unreachable() {
        message.textContent = label("login.error_unreachable")
        retryButton.style.display = "inline-block"
        retryButton.focus()
    }

    private fun openPassword(reason: String) {
        if (isCodeOnly()) return
        console.log("Ravilo screen: sign-in: password form opened ($reason)")
        generation++
        job?.cancel(); job = null
        showingPassword = true
        showingCodeOnlyMessage = false
        (document.getElementById("signin-code-view") as HTMLElement).style.display = "none"
        codeOnlyView.style.display = "none"
        form.style.display = "block"
        message.textContent = ""
        password.value = ""
        username.focus()
    }

    private fun submitPassword() {
        if (signingIn) return
        val user = username.value.trim()
        val pass = password.value
        if (user.isEmpty() || pass.isEmpty()) { message.textContent = label("login.error_required"); return }
        message.textContent = label("login.signing_in")
        signingIn = true
        val requestGeneration = generation
        (document.getElementById("signin-submit") as HTMLElement).setAttribute("disabled", "true")
        job = GlobalScope.launch {
            val result = runCatching { api.login(user, pass, deviceId, deviceName) }
            if (requestGeneration != generation) return@launch
            signingIn = false
            (document.getElementById("signin-submit") as HTMLElement).removeAttribute("disabled")
            result.fold(
                onSuccess = { hide(); onSignedIn(it) },
                onFailure = {
                    val http = it as? TvApiError.Http
                    if (http?.status == 403 && http.message.isEmpty()) {
                        localStorage.setItem(CODE_ONLY_SERVER_KEY, api.baseUrl)
                        showCodeOnlyMessage()
                        return@fold
                    }
                    message.textContent = label(if (http?.status == 401) "login.error_invalid" else "login.error_unreachable")
                    password.value = ""
                    password.focus()
                },
            )
        }
    }

    private fun isCodeOnly() = localStorage.getItem(CODE_ONLY_SERVER_KEY)?.let { sameServer(it, api.baseUrl) } == true

    private fun showCodeOnlyMessage() {
        showingPassword = false
        showingCodeOnlyMessage = true
        form.style.display = "none"
        codeOnlyView.style.display = "block"
        (document.getElementById("signin-code-only-message") as HTMLElement).textContent = label("tvsignin.password_unavailable")
        (document.getElementById("signin-password-message") as HTMLElement).textContent = ""
        password.value = ""
        codeOnlyButton.focus()
    }

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!isOpen()) return false
        if (isBackKey(event)) {
            if (showingPassword || showingCodeOnlyMessage) openCode() else if (hasProfiles) { hide(); onBack() } else return false
            event.preventDefault(); return true
        }
        if (event.key == "Enter" && showingPassword && (document.activeElement == username || document.activeElement == password)) {
            submitPassword(); event.preventDefault(); return true
        }
        if (event.key == "ArrowDown" || event.key == "ArrowUp" ||
            ((event.key == "ArrowLeft" || event.key == "ArrowRight") && document.activeElement != username && document.activeElement != password)) {
            val items = if (showingCodeOnlyMessage) listOf(codeOnlyButton)
            else if (showingPassword) listOf(username, password,
                document.getElementById("signin-submit") as HTMLElement,
                document.getElementById("signin-cancel") as HTMLElement)
            else listOf(passwordButton, retryButton).filter { it.style.display != "none" }
            val step = if (event.key == "ArrowDown" || event.key == "ArrowRight") 1 else -1
            // DEB-4947 (FR-11) — *Wrong server?* is the last stop: Down from the item above reaches it, Down from it goes
            // nowhere, and it is never where focus starts or where Up from the first item wraps to.
            val onChange = document.activeElement == changeServerButton
            if (onChange) {
                if (step < 0) items.lastOrNull()?.focus()
            } else {
                val current = items.indexOf(document.activeElement)
                if (step > 0 && (items.isEmpty() || current == items.lastIndex)) changeServerButton.focus()
                else if (items.isEmpty()) return false
                else items[(current + step + items.size) % items.size].focus()
            }
            event.preventDefault(); return true
        }
        return false
    }

    private fun drawQr(link: String?) {
        qr.textContent = ""
        if (link == null) { qr.style.display = "none"; return }
        val matrix = runCatching { QrEncoder.encode(link) }.getOrNull() ?: return
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val module = 6
        canvas.width = (matrix.size + 8) * module
        canvas.height = canvas.width
        val context = canvas.getContext("2d")!!.asDynamic()
        context.fillStyle = "#fff"
        context.fillRect(0, 0, canvas.width, canvas.height)
        context.fillStyle = "#000"
        for (y in 0 until matrix.size) for (x in 0 until matrix.size)
            if (matrix.isDark(x, y)) context.fillRect((x + 4) * module, (y + 4) * module, module, module)
        qr.style.display = "block"
        qr.appendChild(canvas)
    }
}
