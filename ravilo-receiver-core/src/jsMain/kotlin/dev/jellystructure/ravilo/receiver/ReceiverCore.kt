package dev.jellystructure.ravilo.receiver

import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.StreamTicket

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
    ticket?.subtitles?.filter { it.url != null && it.deliveryMethod != "encode" }?.mapIndexed { i, s ->
        CastTrack(index = i, label = s.label, language = s.language, forced = s.forced, isDefault = s.isDefault, trackId = trackIdBase + i)
    } ?: emptyList()

fun audioTracksOf(ticket: StreamTicket?): List<CastTrack> =
    ticket?.audio?.mapIndexed { i, a -> CastTrack(index = i, label = a.label, language = a.language, isDefault = a.isDefault) } ?: emptyList()

/**
 * The receiver's own on-screen strings (idle/loading/busy/no-server/next-up), extracted verbatim from
 * ravilo-cast/Receiver.kt's private `Strings` object — a receiver draws these itself rather than reading
 * them from the shared `ravilo-ui` string table (dev review, R263 §Build notes: nothing Kotlin/Compose
 * exists in a plain Kotlin/JS receiver for that table to live in).
 */
object ReceiverStrings {
    private val en = mapOf(
        "ready" to "Ready to play from your phone", "loading" to "Loading…",
        "noserver" to "Can’t reach your Ravilo server", "noserver_s" to "Check that the server is on and try again from your phone.",
        "busy" to "The server is busy right now", "busy_s" to "It will start as soon as it can.", "waiting" to "waiting {n} s",
        "nextep" to "UP NEXT", "startsin" to "Starts in {n} s",
    )
    private val da = mapOf(
        "ready" to "Klar til at spille fra din telefon", "loading" to "Indlæser…",
        "noserver" to "Kan ikke nå din Ravilo-server", "noserver_s" to "Tjek at serveren er tændt, og prøv igen fra din telefon.",
        "busy" to "Serveren er travl lige nu", "busy_s" to "Den starter, så snart den kan.", "waiting" to "venter {n} s",
        "nextep" to "NÆSTE", "startsin" to "Starter om {n} s",
    )
    private val fo = mapOf(
        "ready" to "Klár at spæla frá telefonini", "loading" to "Løðir…",
        "noserver" to "Kann ikki ná Ravilo-servaranum", "noserver_s" to "Kanna um servarin er á, og royn aftur frá telefonini.",
        "busy" to "Servarin hevur mikið at gera nú", "busy_s" to "Hon byrjar, so skjótt sum gjørligt.", "waiting" to "bíðar {n} s",
        "nextep" to "NÆSTA", "startsin" to "Byrjar um {n} s",
    )
    var lang = "en"
    fun t(key: String, n: Int? = null): String {
        val table = when (lang) { "da" -> da; "fo" -> fo; else -> en }
        val s = table[key] ?: en[key] ?: key
        return if (n != null) s.replace("{n}", n.toString()) else s
    }
}
