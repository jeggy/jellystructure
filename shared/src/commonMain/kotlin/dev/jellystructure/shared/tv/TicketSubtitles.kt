package dev.jellystructure.shared.tv

/*
 * DEB-4692 (DEB-2205 FR-6, FR-R285-4) — a ticket subtitle URL, fetched by a JS client itself. Since DEB-2205 an embedded
 * text track of a scanned file is a path on this server (`/api/tv/subtitles/{item}/{containerIndex}.vtt`) that needs the
 * device token and answers 202 until the track is extracted. A browser `<track>`, a Cast receiver's CAF and a Tizen
 * screen's plain GET sent neither the token nor a second request, so on those clients every such subtitle was a 401 or
 * an empty file. The Android player has its own copy of this rule (ravilo-ui's `SubtitleRequest` / `AppFedSubtitles`);
 * this is the same rule for the clients that share :shared's [TvApiClient]. Pure, so :shared:desktopTest reaches it.
 */

/** DEB-4692 — the answer to one fetch attempt of a ticket subtitle URL. */
sealed interface SubtitleAttempt {
    data class Body(val text: String) : SubtitleAttempt
    /** 202: not extracted yet. [retryAfterMs] is the server's `Retry-After`, if any. */
    data class NotReady(val retryAfterMs: Long?) : SubtitleAttempt
    /** Any other status, or an I/O error. */
    data class Failed(val detail: String) : SubtitleAttempt
}

object TicketSubtitles {
    // The Android player's numbers (AppFedSubtitles), so every client gives a track the same chance to arrive.
    const val MIN_RETRY_MS = 1_000L
    const val MAX_RETRY_MS = 15_000L
    const val GIVE_UP_MS = 10 * 60_000L
    const val FAILURE_RETRIES = 2
    const val FAILURE_RETRY_MS = 3_000L

    /** The absolute URL for [url]: an absolute one as it is, a path on [base]; null for a path when there is no [base]. */
    fun absolute(url: String, base: String): String? = when {
        url.startsWith("http://", true) || url.startsWith("https://", true) -> url
        url.startsWith("/") && base.isNotBlank() -> base.trimEnd('/') + url
        else -> null
    }

    /**
     * The device token for [absoluteUrl]: only on [base]'s own origin and under `/api/tv/`. A sidecar keeps Jellyfin's
     * own tokened absolute URL and is sent as it is: our token never travels to another host.
     */
    fun bearer(absoluteUrl: String, base: String, token: String?): String? {
        if (token.isNullOrBlank() || base.isBlank()) return null
        if (!absoluteUrl.startsWith(base.trimEnd('/') + "/api/tv/", ignoreCase = true)) return null
        return token
    }

    /** `Retry-After: 5` (delta-seconds) in ms; an HTTP-date, or nothing, is null (the default wait is used). */
    fun retryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

    /** DEB-4792 — a WebVTT file with no cue: what CAF is handed for a track the receiver draws itself, so nothing is drawn twice. */
    const val EMPTY_VTT = "WEBVTT\n\n"

    /** A track that arrived, and whether it had to be waited for (the first answer was not the file). */
    data class Arrival(val text: String, val waited: Boolean)

    /** DEB-4692 — where a Cast receiver sends CAF's request for a subtitle file, and with which token (null = none). */
    data class CastRequest(val url: String, val bearer: String?)

    /**
     * DEB-4692 — CAF's request for [url], seen by the receiver's request handler: null for anything that is not one of
     * this item's own subtitle paths ([own]), which is left exactly as it was (a segment, a sidecar). One of them goes to
     * the `blob:` the receiver hands CAF for a track it draws itself ([copies], absolute URL → an empty file), else to the server with the device token.
     */
    fun castRequest(url: String, own: Set<String>, copies: Map<String, String>, base: String, token: String?): CastRequest? {
        if (url !in own) return null
        copies[url]?.let { return CastRequest(it, null) }
        return CastRequest(url, bearer(url, base, token))
    }

    /** DEB-4994 (FR-R285-4) — what the receiver's drawing tick does about the track CAF says is active. */
    sealed interface DrawStep {
        /** Nothing is to be drawn: Off, a burn-in, or a position with no file. */
        data object Clear : DrawStep
        /** Not fetched and not being fetched: start fetching [url]. Nothing is drawn until it arrives. */
        data class Fetch(val url: String) : DrawStep
        /** Fetched: draw the cues of [url] at the player's clock. */
        data class Draw(val url: String) : DrawStep
        /** Being fetched, or the fetch gave up: nothing is drawn and nothing new is started. */
        data object Wait : DrawStep
    }

    /**
     * DEB-4994 (FR-R285-4 b) — the receiver draws every text subtitle itself, whichever way the pick arrived, so the
     * track is simply the one CAF's text-track manager reports as [activePosition] (-1 = none) on the tick. [urls] is
     * the item's text subtitles by position (null = no address); [cached] the URLs whose cues are parsed, [loading]
     * those being fetched, [gaveUp] those whose fetch ended without a file. A burn-in ([burnedIn]) is the only
     * subtitle (R282): nothing is drawn.
     */
    fun drawStep(activePosition: Int, burnedIn: Boolean, urls: List<String?>, cached: Set<String>, loading: Set<String>, gaveUp: Set<String>): DrawStep {
        if (burnedIn) return DrawStep.Clear
        val url = urls.getOrNull(activePosition) ?: return DrawStep.Clear
        return when (url) {
            in cached -> DrawStep.Draw(url)
            in loading, in gaveUp -> DrawStep.Wait
            else -> DrawStep.Fetch(url)
        }
    }

    /**
     * DEB-2205 (FR-6) — "the client retries in the background and shows the subtitle when it arrives. It never waits on
     * it." Runs [attempt] until it answers the file: a 202 again after its `Retry-After` (clamped to
     * [MIN_RETRY_MS]..[MAX_RETRY_MS]) for at most [GIVE_UP_MS]; any other failure [FAILURE_RETRIES] more times,
     * [FAILURE_RETRY_MS] apart. Null = it never came. The caller runs this off the picture's path, and cancels it by
     * cancelling its coroutine (a new pick, a new item).
     */
    suspend fun fetch(attempt: suspend () -> SubtitleAttempt, nowMs: () -> Long, sleep: suspend (Long) -> Unit): Arrival? {
        val startedAt = nowMs()
        var failures = 0
        var waited = false
        while (true) {
            val wait = when (val r = attempt()) {
                is SubtitleAttempt.Body -> return Arrival(r.text, waited)
                is SubtitleAttempt.NotReady -> {
                    if (nowMs() - startedAt >= GIVE_UP_MS) return null
                    (r.retryAfterMs ?: FAILURE_RETRY_MS).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
                }
                is SubtitleAttempt.Failed -> {
                    if (++failures > FAILURE_RETRIES) return null
                    FAILURE_RETRY_MS
                }
            }
            waited = true
            sleep(wait)
        }
    }
}
