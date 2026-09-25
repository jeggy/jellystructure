package dev.jellystructure.ravilo.ui.seams

/**
 * R293 (FR-R293-6) — every events-socket end is recorded, and the last ten are told to the server on
 * the next connect as one bounded header (`X-Ravilo-Events-Prev`), which phase 256 logs beside the
 * device id. Until this phase the client recorded nothing about why a socket ended (`runCatching`, no
 * log), so the trigger on a flapping TV could not be recovered after the fact.
 *
 * One entry: `<open-s>;<how>;<lifecycle>;<interactive>;<net>;<sdk>` — how long it was open, how it
 * ended (`close:<code>:<reason>` from the server, `err:<ExceptionClass>`, `eof`, or `us:<why>` when
 * this side closed it: background / screen-off / user-switch), the app's lifecycle state, whether the
 * display was interactive, the active network's type + validation, and the platform's SDK level.
 * Entries are joined newest first with `,`. No URLs, no tokens, no free text: every field is
 * whitelisted to `[A-Za-z0-9._:+-]`, so the header can never carry anything the server should not log.
 */
class EventsSocketLog(private val keep: Int = 10) {
    data class End(val openMs: Long, val how: String, val state: String)

    private val ends = ArrayDeque<End>()

    fun record(openMs: Long, how: String, state: String) {
        ends.addFirst(End(openMs, how, state))
        while (ends.size > keep) ends.removeLast()
    }

    val size: Int get() = ends.size

    /** The header value, or null when nothing has ended yet. */
    fun headerValue(): String? {
        if (ends.isEmpty()) return null
        return ends.joinToString(",") { e ->
            listOf((e.openMs / 1000).toString(), clean(e.how), e.state).joinToString(";")
        }.take(MAX_HEADER_BYTES)
    }

    companion object {
        const val HEADER = "X-Ravilo-Events-Prev"
        const val MAX_HEADER_BYTES = 512
        private val FIELD_RE = Regex("[^A-Za-z0-9._:+-]")
        /** One field, whitelisted and bounded. */
        fun clean(s: String, max: Int = 40): String = FIELD_RE.replace(s.trim(), "_").take(max).ifEmpty { "-" }
    }
}
