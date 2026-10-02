package dev.jellystructure.shared.tv

/*
 * R354 (FR-R354-9, amended 2026-10-02) — the Jellyfin dashboard's *Send message*, read once for every screen that shows
 * it: the app (phone, TV, desktop, web) and the Cast receiver on a device with a screen. Pure, so it is tested once.
 */

/** A message to show: [header] on its own line above [text] (null when the sender gave none), for [durationMs]. */
data class ServerNotice(val header: String?, val text: String, val durationMs: Long)

/** The shortest and longest a message the sender timed stays up. */
const val SERVER_NOTICE_MIN_MS = 3_000L
const val SERVER_NOTICE_MAX_MS = 60_000L

/**
 * A `server_message` envelope → what to show, or null when there is nothing to show (a blank text; a header alone is
 * not a message). The sender's `TimeoutMs` wins when it gives one (kept between 3 s and 60 s); otherwise R152's
 * length rule ([serverNoticeLengthMs]).
 */
fun serverNoticeOf(env: ServerMessageEnvelope): ServerNotice? {
    val text = env.text.trim()
    if (text.isEmpty()) return null
    val header = env.header?.trim()?.takeIf { it.isNotEmpty() }
    val duration = env.timeoutMs?.takeIf { it > 0 }?.coerceIn(SERVER_NOTICE_MIN_MS, SERVER_NOTICE_MAX_MS)
        ?: serverNoticeLengthMs(if (header != null) "$header $text" else text)
    return ServerNotice(header, text, duration)
}

/**
 * R152 — how long an untimed message stays up, from its length: 1.5 s to notice it plus 75 ms a character, 3–15 s. A
 * short "Dinner's ready" does not linger as long as a sentence.
 */
fun serverNoticeLengthMs(message: String): Long = (1_500L + message.length * 75L).coerceIn(3_000L, 15_000L)
