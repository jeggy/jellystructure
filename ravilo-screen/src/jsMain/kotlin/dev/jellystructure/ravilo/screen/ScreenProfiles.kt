package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.receiver.ReceiverStrings
import dev.jellystructure.shared.tv.PairResult
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

private const val PROFILES_KEY = "ravilo.screen.profiles"
private const val ACTIVE_PROFILE_KEY = "ravilo.screen.activeProfile"
internal const val LEGACY_TOKENS_KEY = "ravilo.screen.tokens"
private val profileJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ScreenProfile(val userId: String, val displayName: String, val token: String) {
    companion object {
        fun from(result: PairResult) = ScreenProfile(result.session.userId, result.session.displayName, result.deviceToken)
    }
}

/** A token and display name per viewer; the old receiver's borrowing tokens are a separate one-time migration. */
internal class ScreenProfileStore {
    private val all = runCatching {
        profileJson.decodeFromString<List<ScreenProfile>>(localStorage.getItem(PROFILES_KEY) ?: "[]")
    }.getOrDefault(emptyList()).toMutableList()

    fun profiles(): List<ScreenProfile> = all.toList()
    fun get(userId: String?): ScreenProfile? = all.firstOrNull { it.userId == userId }
    fun activeId(): String? = localStorage.getItem(ACTIVE_PROFILE_KEY)?.takeIf { get(it) != null }
    fun setActive(userId: String?) {
        if (userId == null) localStorage.removeItem(ACTIVE_PROFILE_KEY)
        else { require(get(userId) != null); localStorage.setItem(ACTIVE_PROFILE_KEY, userId) }
    }
    fun add(result: PairResult) {
        all.removeAll { it.userId == result.session.userId }
        all.add(ScreenProfile.from(result))
        persist()
        setActive(result.session.userId)
    }
    fun remove(userId: String) {
        all.removeAll { it.userId == userId }
        persist()
        if (localStorage.getItem(ACTIVE_PROFILE_KEY) == userId) setActive(null)
    }
    private fun persist() = localStorage.setItem(PROFILES_KEY, profileJson.encodeToString(all))

    /** R264 persisted only userId → token and every such token was kind=screen. Drop before any boot route. */
    fun takeLegacyTokens(): List<String> {
        val raw = localStorage.getItem(LEGACY_TOKENS_KEY) ?: return emptyList()
        localStorage.removeItem(LEGACY_TOKENS_KEY)
        return runCatching { profileJson.decodeFromString<Map<String, String>>(raw).values.toList() }.getOrDefault(emptyList())
    }
}

/** FR-3's picker and the profile menu part of FR-4. The surrounding shell owns the other routes. */
internal class ScreenProfiles(
    private val store: ScreenProfileStore,
    private val onSelect: (ScreenProfile) -> Unit,
    private val onAdd: () -> Unit,
    private val onSwitch: () -> Unit,
    private val onSignOut: () -> Unit,
) {
    private val picker get() = document.getElementById("profile-picker") as HTMLElement
    private val menu get() = document.getElementById("profile-menu") as HTMLElement
    private fun label(key: String) = t(key, ReceiverStrings.lang)
    private var targets: List<HTMLElement> = emptyList()
    private var selected = 0

    fun showPicker() {
        menu.classList.remove("on")
        picker.classList.add("on")
        val body = document.getElementById("profile-picker-body") as HTMLElement
        body.textContent = ""
        (document.getElementById("profile-picker-title") as HTMLElement).textContent = label("profile.who")
        targets = store.profiles().map { profile -> button(profile.displayName) { onSelect(profile) } } + button(label("profile.add_user")) { onAdd() }
        targets.forEach { body.appendChild(it) }
        selected = 0; refocus()
    }

    fun showMenu() {
        picker.classList.remove("on")
        menu.classList.add("on")
        val body = document.getElementById("profile-menu-body") as HTMLElement
        body.textContent = ""
        val current = store.get(store.activeId())
        (document.getElementById("profile-menu-title") as HTMLElement).textContent = current?.displayName ?: label("nav.profile")
        targets = listOf(
            button(label("nav.my_list")) {},
            button(label("profile.switch")) { onSwitch() },
            button(label("nav.settings")) {},
            button(label("profile.sign_out")) { onSignOut() },
        )
        targets.forEach { body.appendChild(it) }
        selected = 1; refocus()
    }

    fun hide() { picker.classList.remove("on"); menu.classList.remove("on"); targets = emptyList() }
    fun isOpen(): Boolean = picker.classList.contains("on") || menu.classList.contains("on")
    fun isMenu(): Boolean = menu.classList.contains("on")

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!isOpen()) return false
        when {
            isBackKey(event) -> if (isMenu()) hide() else return false
            event.key == "ArrowLeft" || event.key == "ArrowUp" -> selected = (selected - 1 + targets.size) % targets.size
            event.key == "ArrowRight" || event.key == "ArrowDown" -> selected = (selected + 1) % targets.size
            event.key == "Enter" -> { targets[selected].click(); event.preventDefault(); return true }
            else -> return false
        }
        refocus(); event.preventDefault(); return true
    }

    private fun refocus() {
        targets.forEachIndexed { index, target -> target.classList.toggle("focused", index == selected) }
        targets.getOrNull(selected)?.focus()
    }

    private fun button(text: String, action: () -> Unit): HTMLElement = (document.createElement("button") as HTMLElement).apply {
        className = "profile-choice"
        textContent = text
        onclick = { action() }
    }
}
