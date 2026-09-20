package dev.jellystructure.ravilo.receiver

import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.LastLanguageStore
import dev.jellystructure.ravilo.i18n.normalizeLanguage
import dev.jellystructure.ravilo.i18n.resolveLanguage
import dev.jellystructure.ravilo.i18n.t as translate
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.receiverSubtitles
import kotlinx.browser.localStorage

/**
 * R264 — extracted verbatim from ravilo-cast/Receiver.kt (the phase's own "no behaviour change" core
 * extraction, done before :ravilo-screen existed): the pieces of a Ravilo receiver that never touch
 * `cast.framework` and are identical whether the receiver is a Chromecast or a Tizen/webOS screen.
 * Everything CAF-specific (the LOAD interceptor, `playerManager`, the custom-namespace channel) stays in
 * :ravilo-cast; everything AVPlay/`<video>`-specific stays in :ravilo-screen.
 */

fun nowMs(): Long = (js("Date.now()") as Double).toLong()

fun hms(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

/**
 * The subtitle track list a [StreamTicket] resolves to, index-aligned with how a receiver addresses
 * tracks by position. [trackIdBase] lets a CAF receiver keep its historical 100-based `trackId` scheme
 * (CAF's own numbering convention) while a screen that has no such requirement can pass 0.
 *
 * Index alignment with the ticket's own stream order is a best-effort assumption carried over
 * unchanged from the original code — see TizenPlatform.kt's AVPlay wrapper for the same caveat on the
 * AVPlay side.
 */
fun subtitleTracksOf(ticket: StreamTicket?, trackIdBase: Long = 0): List<CastTrack> =
    // R285 (FR-R285-1) — text tracks first, then the burn-in (PGS) candidates this list used to drop:
    // a receiver offered no picture subtitle at all. Order and rule live in :shared's receiverSubtitles().
    receiverSubtitles(ticket).mapIndexed { i, s ->
        CastTrack(index = i, label = s.label, language = s.language, forced = s.forced, isDefault = s.isDefault, trackId = trackIdBase + i)
    }

fun audioTracksOf(ticket: StreamTicket?, trackIdBase: Long? = null): List<CastTrack> =
    // R285 — index is the position a receiver is addressed by ("audio" command / "audio_track").
    // [trackIdBase] gives a Cast sender a handle to send back (its remote UI speaks in trackIds); it is
    // deliberately NOT a CAF track id — an HLS stream has one audio track, there is nothing to activate.
    ticket?.audio?.mapIndexed { i, a -> CastTrack(index = i, label = a.label, language = a.language, isDefault = a.isDefault, trackId = trackIdBase?.plus(i)) } ?: emptyList()

/**
 * R279 — the receiver's language, and the `localStorage` it remembers it in.
 *
 * A receiver spends most of its life with nobody signed in: a Chromecast sits idle between casts, a
 * Tizen set shows its pairing code from the moment it is switched on. Both used to draw those
 * screens in English whatever the household spoke, because the only language they ever saw arrived
 * with a cast. So the ladder is [resolveLanguage]'s: the casting user's configured language while a
 * cast is running, else whatever this receiver drew last, else English.
 *
 * Installing the store here rather than in each receiver's `main()` is deliberate — both receivers
 * are already `localStorage` clients (token, receiver id), and neither should be able to forget.
 */
private const val LANG_KEY = "ravilo.lang"

/**
 * The mutable [lang] stays, because a receiver is plain Kotlin/JS with no Compose runtime and so no
 * `CompositionLocal` to carry it.
 */
object ReceiverStrings {
    // Runs before `lang`'s initializer below — Kotlin runs init blocks and property initializers in
    // declaration order — so the seed already reads through this store rather than the in-memory
    // default. Both sides swallow: `localStorage` throws outright when site data is blocked, and a
    // language that will not persist is not worth failing a receiver's whole boot over.
    init {
        LastLanguage.store = object : LastLanguageStore {
            override fun read(): String? = runCatching { localStorage.getItem(LANG_KEY) }.getOrNull()
            override fun write(code: String) { runCatching { localStorage.setItem(LANG_KEY, code) } }
        }
    }

    /**
     * What this receiver draws in. Seeded from the remembered language so the idle and pairing
     * screens are right before any user is known; set again, and remembered, once a cast names one.
     */
    var lang: String = resolveLanguage(lastSession = LastLanguage.read())
        private set

    /**
     * Adopts the first of [candidates] this build has strings for and remembers it, so the idle
     * screen after this cast — and after the next power cycle — stays in the same language.
     *
     * The order at the call site is the point: the hand-off payload carries the *casting user's*
     * own configured language, which is the only thing that knows which of a household's viewers
     * pressed play. A config fetched by the receiver can be a previous viewer's.
     */
    fun adopt(vararg candidates: String?) {
        val chosen = candidates.firstNotNullOfOrNull { normalizeLanguage(it) } ?: return
        lang = chosen
        LastLanguage.remember(chosen)
    }

    /** [n] fills the `{n}` placeholder — the only one any receiver string has. */
    fun t(key: String, n: Int? = null): String =
        if (n == null) translate(key, lang) else translate(key, lang, n)
}
