package dev.jellystructure.ravilo.tizen

import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/** R189 FR-RV-TIZEN1-2 — username/password sign-in via the shared `TvApiClient.login`. Two fields + a
 *  submit control, D-pad-navigable with Up/Down (mirrors the fix made to Ravilo's own LoginScreen this
 *  session: Up/Down must move focus even without relying on an on-screen-keyboard's own Next button —
 *  Tizen has no IME concept at all here, this app owns 100% of the input focus). */
class LoginScreen : Screen {
    private var focusIndex = 0 // 0 = username, 1 = password, 2 = submit
    private lateinit var usernameInput: HTMLInputElement
    private lateinit var passwordInput: HTMLInputElement
    private lateinit var submitButton: HTMLElement
    private lateinit var errorEl: HTMLElement

    override fun mount(container: HTMLElement) {
        container.child("div", "login-screen") {
            child("h1", "brand", "Ravilo")
            child("p", "subtitle", "Sign in with your Jellyfin username and password")
            errorEl = child("p", "error")

            usernameInput = input("text", "Username", "field") as HTMLInputElement
            appendChild(usernameInput)
            passwordInput = input("password", "Password", "field") as HTMLInputElement
            appendChild(passwordInput)

            submitButton = child("div", "button", "Sign in")
        }
        updateFocusVisual()
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "ArrowDown" -> { focusIndex = (focusIndex + 1).coerceAtMost(2); updateFocusVisual(); return true }
            "ArrowUp" -> { focusIndex = (focusIndex - 1).coerceAtLeast(0); updateFocusVisual(); return true }
            "Enter" -> { activate(); return true }
        }
        return false
    }

    private fun updateFocusVisual() {
        usernameInput.classList.toggle("focused", focusIndex == 0)
        passwordInput.classList.toggle("focused", focusIndex == 1)
        submitButton.classList.toggle("focused", focusIndex == 2)
        when (focusIndex) {
            0 -> usernameInput.focus()
            1 -> passwordInput.focus()
            else -> submitButton.focus()
        }
    }

    private fun activate() {
        when (focusIndex) {
            0 -> { focusIndex = 1; updateFocusVisual() }
            1 -> { focusIndex = 2; updateFocusVisual() }
            2 -> submit()
        }
    }

    private fun submit() {
        val username = usernameInput.value.trim()
        val password = passwordInput.value
        if (username.isEmpty() || password.isEmpty()) {
            errorEl.textContent = "Enter a username and password"
            return
        }
        errorEl.textContent = "Signing in…"
        app.scope.launch {
            runCatching {
                app.api.login(username, password, DeviceIdStore.get(), deviceName = "Ravilo Tizen")
            }.onSuccess { result ->
                TokenStore.set(result.deviceToken)
                app.show(HomeScreen(), replaceStack = true)
            }.onFailure {
                errorEl.textContent = "Sign-in failed — check your username and password"
            }
        }
    }
}
