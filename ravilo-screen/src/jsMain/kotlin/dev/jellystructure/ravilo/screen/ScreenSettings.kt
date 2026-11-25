package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement

/**
 * DEB-5632 (DEB-5621 FR-11, FR-5) — the viewer's two playback switches, Android TV's pair. The app's loaded [config] is
 * the one copy of each value: a flip writes it at once, so the next card and the next Home use it without a reload,
 * and a failed `PUT /api/tv/settings` puts it back. Before the config has loaded both read as on, its defaults.
 */
internal class ScreenPlaybackSwitches(
    private val api: TvApiClient,
    private val config: () -> RaviloConfig?,
    private val onConfig: (RaviloConfig) -> Unit,
) {
    val showProgress: Boolean get() = config()?.showContinueProgress ?: true
    val autoplayNext: Boolean get() = config()?.autoplayNext ?: true

    /** Flips Show progress; [shown] is called with the value to draw, again with the old one if the write fails. */
    fun flipShowProgress(shown: (Boolean) -> Unit) = flip(shown, { showProgress }, { copy(showContinueProgress = it) }) {
        api.putViewerSettings(showContinueProgress = it)
    }

    fun flipAutoplayNext(shown: (Boolean) -> Unit) = flip(shown, { autoplayNext }, { copy(autoplayNext = it) }) {
        api.putViewerSettings(autoplayNext = it)
    }

    private fun flip(
        shown: (Boolean) -> Unit,
        read: () -> Boolean,
        with: RaviloConfig.(Boolean) -> RaviloConfig,
        write: suspend (Boolean) -> Unit,
    ) {
        val value = !read()
        onConfig((config() ?: RaviloConfig()).with(value))
        shown(value)
        GlobalScope.launch {
            runCatching { write(value) }.onFailure {
                // Only while nothing newer has been chosen since.
                if (read() == value) {
                    onConfig((config() ?: RaviloConfig()).with(!value))
                    shown(!value)
                }
            }
        }
    }
}

/**
 * DEB-4663 (FR-11) — Settings, the minimum: the language, the server, and the two versions. It is a
 * shell route (`settings`), reached from the profile menu, drawn into the shell's body so the bar, the
 * spatial focus and Back all behave as on every other route.
 */
internal object ScreenSettings {
    const val ROUTE = "settings"
    private val json = Json { ignoreUnknownKeys = true }

    /** What `/api/health` said this session; kept so a language switch re-draws without a flash. */
    private var serverVersion: String? = null
    private var fetching = false

    /** The id Settings opens on: the language row's current choice. */
    fun firstFocusId(): String = languageId(ScreenLanguage.choice)

    private fun languageId(choice: String) = "set-lang-$choice"

    fun render(body: HTMLElement, serverUrl: String, playback: ScreenPlaybackSwitches, onLanguage: () -> Unit, onChangeServer: () -> Unit) {
        body.textContent = ""
        body.appendChild(line("h1", "set-title", ScreenLanguage.t("nav.settings")))

        body.appendChild(line("h2", null, ScreenLanguage.t("settings.language")))
        val languages = document.createElement("div") as HTMLElement
        languages.className = "set-choices"
        val options = listOf(FOLLOW_TV to ScreenLanguage.t("settings.language_tv")) +
            SUPPORTED_LANGUAGES.map { it.code to it.name }
        for ((code, label) in options) {
            languages.appendChild(target(languageId(code), "lang", label, selected = ScreenLanguage.choice == code) {
                ScreenLanguage.choose(code)
                onLanguage()
            })
        }
        body.appendChild(languages)

        body.appendChild(line("h2", null, ScreenLanguage.t("settings.playback")))
        body.appendChild(switch("set-show-progress", ScreenLanguage.t("settings.show_progress"), playback.showProgress, playback::flipShowProgress))
        body.appendChild(switch("set-autoplay-next", ScreenLanguage.t("settings.autoplay_next"), playback.autoplayNext, playback::flipAutoplayNext))

        body.appendChild(line("h2", null, ScreenLanguage.t("settings.server")))
        body.appendChild(line("div", "set-server-address", serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')))
        val change = document.createElement("div") as HTMLElement
        change.className = "set-choices"
        change.appendChild(target("set-change-server", "server", ScreenLanguage.t("settings.change_server"), selected = false) { onChangeServer() })
        body.appendChild(change)
        body.appendChild(line("div", "set-server-note", ScreenLanguage.t("settings.server_signs_out")))

        body.appendChild(line("h2", null, ScreenLanguage.t("about.title")))
        body.appendChild(versionLine("set-app-version", ScreenLanguage.t("settings.app_version"), raviloVersion()))
        body.appendChild(versionLine("set-server-version", ScreenLanguage.t("settings.server_version"), serverVersion ?: "…"))
        if (serverVersion == null) loadServerVersion(serverUrl)
    }

    private fun loadServerVersion(serverUrl: String) {
        if (fetching) return
        fetching = true
        GlobalScope.launch {
            val client = HttpClient(Js)
            val found = runCatching {
                val response = client.get("${serverUrl.trimEnd('/')}/api/health")
                if (response.status.isSuccess()) parseHealthVersion(response.bodyAsText()) else null
            }.getOrNull()
            runCatching { client.close() }
            fetching = false
            serverVersion = found ?: "—"
            document.getElementById("set-server-version")?.textContent = serverVersion
        }
    }

    /** The `version` of `/api/health`'s `{"status":"ok","version":"…"}`, or null. */
    internal fun parseHealthVersion(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun line(tag: String, id: String?, text: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { if (id != null) it.id = id; it.textContent = text }

    private fun versionLine(id: String, label: String, value: String): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        row.className = "set-version"
        row.appendChild(line("span", null, label))
        row.appendChild(line("b", id, value))
        return row
    }

    /** One focus target in its own row (Down reaches the next); OK flips it, the flip is [flip]'s to draw. */
    private fun switch(id: String, label: String, on: Boolean, flip: ((Boolean) -> Unit) -> Unit): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        row.id = id
        row.className = "shell-target set-switch"
        row.setAttribute("data-row", id)
        row.setAttribute("tabindex", "-1")
        row.setAttribute("role", "switch")
        fun draw(value: Boolean) {
            row.setAttribute("aria-checked", value.toString())
            if (value) row.classList.add("on") else row.classList.remove("on")
        }
        draw(on)
        row.appendChild(line("span", null, label))
        row.appendChild(line("span", "$id-knob", "").also { it.className = "set-switch-track" })
        row.onclick = { flip(::draw) }
        return row
    }

    private fun target(id: String, row: String, label: String, selected: Boolean, action: () -> Unit): HTMLElement =
        (document.createElement("div") as HTMLElement).apply {
            this.id = id
            className = "shell-target set-choice" + if (selected) " cur" else ""
            setAttribute("data-row", row)
            setAttribute("tabindex", "-1")
            textContent = label
            onclick = { action() }
        }
}
