package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.LastLanguageStore
import dev.jellystructure.ravilo.i18n.normalizeLanguage
import dev.jellystructure.ravilo.i18n.resolveLanguage
import dev.jellystructure.ravilo.i18n.t as translate
import kotlinx.browser.localStorage

/** The choice stored when the viewer picked no language: the TV's own (DEB-4663, FR-11). */
internal const val FOLLOW_TV = "tv"
private const val LANGUAGE_CHOICE_KEY = "ravilo.screen.language"
/** The key the receivers already remember their last language under, so one TV keeps one story. */
private const val LAST_LANGUAGE_KEY = "ravilo.lang"

/**
 * DEB-4663 (FR-11, FR-12) — the full app's language: the TV's, or the one Settings picked. It holds
 * only which language is drawn; every string comes from `ravilo-i18n`, and the app never carries a
 * table of its own. [ReceiverStrings][dev.jellystructure.ravilo.receiver.ReceiverStrings] stays for
 * what the Chromecast receiver shows.
 */
internal object ScreenLanguage {
    init {
        LastLanguage.store = object : LastLanguageStore {
            override fun read(): String? = runCatching { localStorage.getItem(LAST_LANGUAGE_KEY) }.getOrNull()
            override fun write(code: String) { runCatching { localStorage.setItem(LAST_LANGUAGE_KEY, code) } }
        }
    }

    private var tvLanguage: String? = null

    /** [FOLLOW_TV] or a language code `ravilo-i18n` has. */
    var choice: String = FOLLOW_TV
        private set

    var lang: String = resolveLanguage()
        private set

    /** [tvLanguage] is what the TV reports (`navigator.language`); a language we have no file for reads as English. */
    fun start(tvLanguage: String?) {
        this.tvLanguage = tvLanguage
        val stored = runCatching { localStorage.getItem(LANGUAGE_CHOICE_KEY) }.getOrNull()
        apply(normalizeLanguage(stored) ?: FOLLOW_TV)
    }

    fun choose(choice: String) {
        val code = if (choice == FOLLOW_TV) FOLLOW_TV else normalizeLanguage(choice) ?: return
        runCatching { localStorage.setItem(LANGUAGE_CHOICE_KEY, code) }
        apply(code)
    }

    private fun apply(code: String) {
        choice = code
        lang = if (code == FOLLOW_TV) resolveLanguage(configured = tvLanguage) else code
        LastLanguage.remember(lang)
    }

    /** [n] fills the `{n}` placeholder. */
    fun t(key: String, n: Int? = null): String = if (n == null) translate(key, lang) else translate(key, lang, n)
}
