@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

private data class LangEntry(val code: String, val name: String)

private val LANGUAGES: List<LangEntry> = listOf(
    LangEntry("af", "Afrikaans"),
    LangEntry("ak", "Akan"),
    LangEntry("sq", "Albanian"),
    LangEntry("am", "Amharic"),
    LangEntry("ar", "Arabic"),
    LangEntry("hy", "Armenian"),
    LangEntry("az", "Azerbaijani"),
    LangEntry("eu", "Basque"),
    LangEntry("be", "Belarusian"),
    LangEntry("bn", "Bengali"),
    LangEntry("bs", "Bosnian"),
    LangEntry("br", "Breton"),
    LangEntry("bg", "Bulgarian"),
    LangEntry("my", "Burmese"),
    LangEntry("ca", "Catalan"),
    LangEntry("zh", "Chinese"),
    LangEntry("hr", "Croatian"),
    LangEntry("cs", "Czech"),
    LangEntry("da", "Danish"),
    LangEntry("nl", "Dutch"),
    LangEntry("en", "English"),
    LangEntry("eo", "Esperanto"),
    LangEntry("et", "Estonian"),
    LangEntry("fo", "Faroese"),
    LangEntry("fi", "Finnish"),
    LangEntry("fr", "French"),
    LangEntry("ff", "Fula"),
    LangEntry("gl", "Galician"),
    LangEntry("ka", "Georgian"),
    LangEntry("de", "German"),
    LangEntry("el", "Greek"),
    LangEntry("gn", "Guaraní"),
    LangEntry("gu", "Gujarati"),
    LangEntry("ht", "Haitian Creole"),
    LangEntry("ha", "Hausa"),
    LangEntry("he", "Hebrew"),
    LangEntry("hi", "Hindi"),
    LangEntry("hu", "Hungarian"),
    LangEntry("ia", "Interlingua"),
    LangEntry("id", "Indonesian"),
    LangEntry("ga", "Irish"),
    LangEntry("ig", "Igbo"),
    LangEntry("ik", "Inupiaq"),
    LangEntry("is", "Icelandic"),
    LangEntry("it", "Italian"),
    LangEntry("iu", "Inuktitut"),
    LangEntry("ja", "Japanese"),
    LangEntry("jv", "Javanese"),
    LangEntry("kl", "Kalaallisut"),
    LangEntry("kn", "Kannada"),
    LangEntry("ks", "Kashmiri"),
    LangEntry("kk", "Kazakh"),
    LangEntry("km", "Khmer"),
    LangEntry("ki", "Kikuyu"),
    LangEntry("rw", "Kinyarwanda"),
    LangEntry("ky", "Kyrgyz"),
    LangEntry("kv", "Komi"),
    LangEntry("kg", "Kongo"),
    LangEntry("ko", "Korean"),
    LangEntry("ku", "Kurdish"),
    LangEntry("la", "Latin"),
    LangEntry("lb", "Luxembourgish"),
    LangEntry("lg", "Luganda"),
    LangEntry("ln", "Lingala"),
    LangEntry("lo", "Lao"),
    LangEntry("lt", "Lithuanian"),
    LangEntry("lu", "Luba-Katanga"),
    LangEntry("lv", "Latvian"),
    LangEntry("gv", "Manx"),
    LangEntry("mk", "Macedonian"),
    LangEntry("mg", "Malagasy"),
    LangEntry("ms", "Malay"),
    LangEntry("ml", "Malayalam"),
    LangEntry("mt", "Maltese"),
    LangEntry("mi", "Māori"),
    LangEntry("mr", "Marathi"),
    LangEntry("mn", "Mongolian"),
    LangEntry("na", "Nauru"),
    LangEntry("nv", "Navajo"),
    LangEntry("nb", "Norwegian Bokmål"),
    LangEntry("nd", "North Ndebele"),
    LangEntry("ne", "Nepali"),
    LangEntry("nn", "Norwegian Nynorsk"),
    LangEntry("no", "Norwegian"),
    LangEntry("oc", "Occitan"),
    LangEntry("om", "Oromo"),
    LangEntry("or", "Odia"),
    LangEntry("os", "Ossetian"),
    LangEntry("pa", "Punjabi"),
    LangEntry("fa", "Persian"),
    LangEntry("pl", "Polish"),
    LangEntry("ps", "Pashto"),
    LangEntry("pt", "Portuguese"),
    LangEntry("qu", "Quechua"),
    LangEntry("rm", "Romansh"),
    LangEntry("rn", "Kirundi"),
    LangEntry("ro", "Romanian"),
    LangEntry("ru", "Russian"),
    LangEntry("sa", "Sanskrit"),
    LangEntry("sc", "Sardinian"),
    LangEntry("sd", "Sindhi"),
    LangEntry("se", "Northern Sami"),
    LangEntry("sm", "Samoan"),
    LangEntry("sg", "Sango"),
    LangEntry("sr", "Serbian"),
    LangEntry("gd", "Scottish Gaelic"),
    LangEntry("sn", "Shona"),
    LangEntry("si", "Sinhala"),
    LangEntry("sk", "Slovak"),
    LangEntry("sl", "Slovenian"),
    LangEntry("so", "Somali"),
    LangEntry("st", "Southern Sotho"),
    LangEntry("es", "Spanish"),
    LangEntry("su", "Sundanese"),
    LangEntry("sw", "Swahili"),
    LangEntry("ss", "Swati"),
    LangEntry("sv", "Swedish"),
    LangEntry("ta", "Tamil"),
    LangEntry("te", "Telugu"),
    LangEntry("tg", "Tajik"),
    LangEntry("th", "Thai"),
    LangEntry("ti", "Tigrinya"),
    LangEntry("bo", "Tibetan"),
    LangEntry("tk", "Turkmen"),
    LangEntry("tl", "Tagalog"),
    LangEntry("tn", "Tswana"),
    LangEntry("to", "Tonga"),
    LangEntry("tr", "Turkish"),
    LangEntry("ts", "Tsonga"),
    LangEntry("tt", "Tatar"),
    LangEntry("tw", "Twi"),
    LangEntry("ty", "Tahitian"),
    LangEntry("ug", "Uyghur"),
    LangEntry("uk", "Ukrainian"),
    LangEntry("ur", "Urdu"),
    LangEntry("uz", "Uzbek"),
    LangEntry("ve", "Venda"),
    LangEntry("vi", "Vietnamese"),
    LangEntry("vo", "Volapük"),
    LangEntry("wa", "Walloon"),
    LangEntry("cy", "Welsh"),
    LangEntry("wo", "Wolof"),
    LangEntry("fy", "Western Frisian"),
    LangEntry("xh", "Xhosa"),
    LangEntry("yi", "Yiddish"),
    LangEntry("yo", "Yoruba"),
    LangEntry("za", "Zhuang"),
    LangEntry("zu", "Zulu"),
)

/** Returns "Language name (code)" for known codes, or just the raw code. */
internal fun langDisplay(code: String): String {
    val c = code.trim().lowercase()
    if (c.isBlank()) return ""
    val entry = LANGUAGES.find { it.code == c }
    return if (entry != null) "${entry.name} (${entry.code})" else code
}

// Top-level single-expression helpers for Kotlin/WASM js() constraints
private fun elRectBottom(el: HTMLElement): Double = js("el.getBoundingClientRect().bottom")
private fun elRectLeft(el: HTMLElement): Double = js("el.getBoundingClientRect().left")
private fun elRectWidth(el: HTMLElement): Double = js("el.getBoundingClientRect().width")
private fun scrollIntoViewNearest(el: HTMLElement): Unit = js("el.scrollIntoView({block:'nearest'})")

/** Currently open picker dropdown — only one open at a time. */
private var openPickerDropdown: HTMLElement? = null
private var documentListenerInstalled = false

private fun ensureDocumentListener() {
    if (documentListenerInstalled) return
    documentListenerInstalled = true
    // Any click that reaches the document closes the open picker.
    // Clicks inside a picker wrapper are stopped via stopPropagation before reaching here.
    document.addEventListener("click") { _ ->
        openPickerDropdown?.style?.display = "none"
        openPickerDropdown = null
    }
}

/**
 * Upgrades an existing HTMLInputElement into a searchable language picker.
 * The input is hidden but retains its id and value; existing `input` event
 * listeners on it continue to fire when a selection is made.
 */
fun installLanguagePicker(inputEl: HTMLInputElement) {
    if (inputEl.getAttribute("data-picker") == "installed") return
    inputEl.setAttribute("data-picker", "installed")
    injectPickerStyles()
    ensureDocumentListener()

    val origStyle = inputEl.getAttribute("style") ?: ""
    val origClass = inputEl.className

    // Strip width constraints from the display style so the display is never clipped
    // to the original (possibly very narrow) input width. The wrapper preserves the
    // original sizing for layout purposes while the display fills it at 100%.
    val displayStyle = origStyle.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("width:") && !it.startsWith("max-width:") }
        .joinToString(";")

    // Wrapper: keeps the original dimensions for layout (flex sizing etc.) + min-width
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.className = "lp-wrap"
    wrapper.setAttribute("style", "${origStyle};min-width:180px;")
    // Stop clicks inside the wrapper reaching the document close-listener
    wrapper.addEventListener("click") { it.stopPropagation() }

    // Display: looks like the input but fills wrapper width and never clips the name
    val display = document.createElement("div") as HTMLElement
    display.className = "$origClass lp-display"
    display.setAttribute("style", "${displayStyle};width:100%;cursor:pointer;user-select:none;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;box-sizing:border-box;")
    display.tabIndex = 0

    // Dropdown is appended to <body> so it is never clipped by overflow:hidden ancestors.
    // It is positioned with position:fixed using getBoundingClientRect coordinates on open.
    val dropdown = document.createElement("div") as HTMLElement
    dropdown.className = "lp-dropdown"
    dropdown.style.display = "none"
    // Stop clicks inside dropdown from reaching document close-listener
    dropdown.addEventListener("click") { it.stopPropagation() }
    document.body?.appendChild(dropdown)

    val searchEl = document.createElement("input") as HTMLInputElement
    searchEl.type = "text"
    searchEl.className = "lp-search"
    searchEl.placeholder = "Search…"

    val listEl = document.createElement("div") as HTMLElement
    listEl.className = "lp-list"

    dropdown.appendChild(searchEl)
    dropdown.appendChild(listEl)

    inputEl.parentElement?.insertBefore(wrapper, inputEl)
    inputEl.style.display = "none"
    wrapper.appendChild(inputEl)
    wrapper.appendChild(display)
    // dropdown is in body, not in wrapper

    fun updateDisplay(code: String) {
        val label = langDisplay(code)
        if (label.isBlank()) {
            display.innerHTML = """<span style="opacity:.45;font-weight:400;">Select language…</span>"""
        } else {
            display.textContent = label
        }
    }
    updateDisplay(inputEl.value)

    var filtered = LANGUAGES
    var selIdx = -1

    fun markActive() {
        val opts = listEl.querySelectorAll(".lp-opt")
        for (i in 0 until opts.length) {
            val opt = opts.item(i) as? HTMLElement ?: continue
            opt.className = if (i == selIdx) "lp-opt active" else "lp-opt"
            if (i == selIdx) scrollIntoViewNearest(opt)
        }
    }

    fun fireInput() {
        val ev = document.createEvent("Event")
        ev.initEvent("input", bubbles = true, cancelable = false)
        inputEl.dispatchEvent(ev)
    }

    fun pick(entry: LangEntry) {
        inputEl.value = entry.code
        updateDisplay(entry.code)
        dropdown.style.display = "none"
        openPickerDropdown = null
        fireInput()
    }

    fun renderList(query: String) {
        filtered = if (query.isBlank()) LANGUAGES
                   else LANGUAGES.filter { it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true) }
        listEl.innerHTML = ""
        filtered.forEachIndexed { i, entry ->
            val opt = document.createElement("div") as HTMLElement
            opt.className = "lp-opt"
            opt.textContent = "${entry.name} (${entry.code})"
            opt.addEventListener("mouseover") { selIdx = i; markActive() }
            opt.addEventListener("click") { pick(entry) }
            listEl.appendChild(opt)
        }
        selIdx = -1
    }

    fun positionAndShow() {
        // Position dropdown with fixed coordinates so it escapes overflow:hidden parents
        val bottom = elRectBottom(display)
        val left = elRectLeft(display)
        val w = elRectWidth(display).coerceAtLeast(220.0)
        dropdown.style.setProperty("top", "${bottom + 3}px")
        dropdown.style.setProperty("left", "${left}px")
        dropdown.style.setProperty("width", "${w}px")
        dropdown.style.display = "block"
    }

    fun openDropdown() {
        openPickerDropdown?.style?.display = "none"
        renderList("")
        searchEl.value = ""
        positionAndShow()
        openPickerDropdown = dropdown
        searchEl.focus()
    }

    display.addEventListener("click") {
        if (dropdown.style.display == "none") openDropdown()
        else { dropdown.style.display = "none"; openPickerDropdown = null }
    }

    display.addEventListener("keydown") { e ->
        val key = (e as? KeyboardEvent)?.key
        if (key == "Enter" || key == " ") { e.preventDefault(); openDropdown() }
    }

    searchEl.addEventListener("input") { renderList(searchEl.value) }

    searchEl.addEventListener("keydown") { e ->
        val ke = e as? KeyboardEvent ?: return@addEventListener
        when (ke.key) {
            "Escape" -> { dropdown.style.display = "none"; openPickerDropdown = null; display.focus() }
            "ArrowDown" -> { ke.preventDefault(); selIdx = (selIdx + 1).coerceAtMost(filtered.size - 1); markActive() }
            "ArrowUp"   -> { ke.preventDefault(); selIdx = (selIdx - 1).coerceAtLeast(0); markActive() }
            "Enter"     -> { if (selIdx in filtered.indices) pick(filtered[selIdx]) }
        }
    }
}

/** Convenience wrapper: looks up by element id before installing. */
fun installLanguagePickerById(id: String) {
    val el = document.getElementById(id) as? HTMLInputElement ?: return
    installLanguagePicker(el)
}

/** Call this if an input's value is set externally after picker installation. */
fun refreshLanguagePicker(inputEl: HTMLInputElement) {
    val wrapper = inputEl.parentElement as? HTMLElement ?: return
    val display = wrapper.querySelector(".lp-display") as? HTMLElement ?: return
    val label = langDisplay(inputEl.value)
    if (label.isBlank()) {
        display.innerHTML = """<span style="opacity:.45;font-weight:400;">Select language…</span>"""
    } else {
        display.textContent = label
    }
}

private fun injectPickerStyles() {
    if (document.getElementById("lp-styles") != null) return
    val style = document.createElement("style") as? org.w3c.dom.HTMLStyleElement ?: return
    style.id = "lp-styles"
    style.textContent = """
        .lp-wrap { position: relative; display: inline-block; vertical-align: middle; }
        .lp-display { display: flex !important; align-items: center; }
        .lp-dropdown {
            position: fixed;
            background: var(--fill);
            border: 1px solid var(--line-2);
            border-radius: 8px;
            box-shadow: var(--shadow);
            z-index: 9999;
            overflow: hidden;
        }
        .lp-search {
            display: block; width: 100%; box-sizing: border-box;
            border: none; border-bottom: 1px solid var(--line);
            border-radius: 0;
            background: var(--fill-2);
            color: var(--ink);
            padding: 8px 10px; font-size: .82rem; outline: none;
        }
        .lp-search:focus { background: var(--fill-3); }
        .lp-list { max-height: 224px; overflow-y: auto; }
        .lp-opt {
            padding: 7px 10px; font-size: .82rem;
            color: var(--ink); cursor: pointer;
        }
        .lp-opt:hover, .lp-opt.active {
            background: var(--fill-3);
            color: var(--hi);
        }
    """.trimIndent()
    document.head?.appendChild(style)
}
