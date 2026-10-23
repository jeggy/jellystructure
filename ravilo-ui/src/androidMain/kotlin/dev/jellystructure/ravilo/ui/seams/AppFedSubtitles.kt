package dev.jellystructure.ravilo.ui.seams

/** DEB-2205 (FR-6) — the answer to one fetch attempt of a subtitle file. */
internal sealed interface SubtitleFetch {
    class Body(val text: String) : SubtitleFetch
    /** 202: not extracted yet (jellystructure's `/api/tv/subtitles`). [retryAfterMs] is the server's `Retry-After`, if any. */
    class NotReady(val retryAfterMs: Long?) : SubtitleFetch
    /** Any other status, or an I/O error: one counted failure (`subtitle_load_errors`). */
    class Failed(val detail: String) : SubtitleFetch
    /** The call was cancelled by [SubtitleCall.cancel]; not a failure. */
    object Cancelled : SubtitleFetch
}

/** One attempt. [execute] BLOCKS the thread it runs on, so it is only ever run on the controller's own worker. */
internal interface SubtitleCall {
    fun execute(): SubtitleFetch
    /** Safe from any thread; unblocks a pending [execute] (the socket is closed). */
    fun cancel()
}

internal fun interface SubtitleSource {
    fun newCall(url: String): SubtitleCall
}

internal fun interface Cancellable { fun cancel() }

/** Where the controller's work runs and when: a worker thread pool in the app, a hand-cranked one in the pure tests. */
internal interface SubtitleScheduler {
    fun nowMs(): Long
    fun post(task: Runnable): Cancellable
    fun postDelayed(delayMs: Long, task: Runnable): Cancellable
}

/**
 * DEB-2205 (FR-6) — the subtitle the viewer is looking at, fetched by the app and kept OFF the media item, so a subtitle
 * URL that hangs can only ever block this class's worker, never the video's preparation, its first frame or its position.
 *
 * A pure state machine over [SubtitleSource] and [SubtitleScheduler] (no Android types, JVM-tested):
 * - [reset] gives it the ticket's URL-bearing text tracks, in ticket order. Nothing is requested.
 * - [select] with a position in that list fetches THAT track only; any other value (off, or one of Media3's own
 *   in-container tracks) fetches nothing and drops the shown cues. Selecting another track cancels the previous fetch.
 * - Every call returns at once; the work happens on the scheduler.
 * - 202 is retried in the background after `Retry-After` (clamped to [MIN_RETRY_MS]..[MAX_RETRY_MS]) for at most
 *   [GIVE_UP_MS]. Another failure is counted ([Listener.onAttemptFailed]) and retried [FAILURE_RETRIES] times, [FAILURE_RETRY_MS] apart.
 * - A track that arrived is kept by URL: selecting it again, or a rebuilt engine re-selecting it, never fetches again.
 */
internal class AppFedSubtitles(
    private val source: SubtitleSource,
    private val scheduler: SubtitleScheduler,
    private val listener: Listener,
) {
    interface Listener {
        /** What is to be shown now: null = nothing (off, another kind of track, or this track has not arrived yet). */
        fun onShown(timeline: CueTimeline?)
        /** One failed attempt (not a 202, not a cancel). */
        fun onAttemptFailed(url: String)
    }

    private class Job(val url: String, val startedAtMs: Long) {
        var cancelled = false
        var call: SubtitleCall? = null
        var timer: Cancellable? = null
        var task: Cancellable? = null
        var failures = 0
        fun cancel() {
            cancelled = true
            call?.cancel()
            timer?.cancel()
            task?.cancel()
        }
    }

    private val lock = Any()
    private var urls: List<String> = emptyList()
    private val loaded = HashMap<String, CueTimeline>()
    private var selected = -1
    private var job: Job? = null

    /** How many tracks the ticket listed (the picker rows this class owns). */
    val trackCount: Int get() = synchronized(lock) { urls.size }

    /** App-fed fetches in flight or waiting to retry: 0 or 1. QoE's `pending_subtitle_loads`. */
    val pendingLoads: Int get() = synchronized(lock) { if (job != null) 1 else 0 }

    /** A new load: cancel everything, forget the selection, keep only the tracks that arrived for a URL still listed. */
    fun reset(newUrls: List<String>) {
        val old: Job?
        synchronized(lock) {
            old = job
            job = null
            selected = -1
            loaded.keys.retainAll(newUrls.toSet())
            urls = newUrls
        }
        old?.cancel()
        listener.onShown(null)
    }

    /** [position] in the list given to [reset], or anything else for "no app-fed track". */
    fun select(position: Int) {
        var toCancel: Job? = null
        var show: CueTimeline? = null
        var start: Job? = null
        synchronized(lock) {
            val url = urls.getOrNull(position)
            if (url == null) {
                toCancel = job; job = null; selected = -1
            } else if (selected == position && job != null) {
                return   // already on its way
            } else {
                if (selected != position) { toCancel = job; job = null }
                selected = position
                show = loaded[url]
                if (show == null && job == null) {
                    start = Job(url, scheduler.nowMs()).also { job = it }
                }
            }
        }
        toCancel?.cancel()
        listener.onShown(show)
        start?.let { j -> val t = scheduler.post { attempt(j) }; synchronized(lock) { j.task = t; if (j.cancelled) t.cancel() } }
    }

    private fun attempt(j: Job) {
        val call = source.newCall(j.url)
        synchronized(lock) {
            if (j.cancelled) return
            j.call = call
        }
        val result = try { call.execute() } catch (t: Throwable) { SubtitleFetch.Failed(t.toString()) }
        synchronized(lock) {
            j.call = null
            if (j.cancelled) return
        }
        when (result) {
            is SubtitleFetch.Cancelled -> Unit
            is SubtitleFetch.Body -> {
                val timeline = CueTimeline(WebVttParser.parse(result.text))
                var show = false
                synchronized(lock) {
                    if (j.cancelled) return
                    if (job === j) job = null
                    if (timeline.size > 0) loaded[j.url] = timeline
                    show = urls.getOrNull(selected) == j.url
                }
                if (show) listener.onShown(timeline)
            }
            is SubtitleFetch.NotReady -> {
                val giveUp = scheduler.nowMs() - j.startedAtMs >= GIVE_UP_MS
                if (giveUp) finish(j) else retryLater(j, (result.retryAfterMs ?: FAILURE_RETRY_MS).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS))
            }
            is SubtitleFetch.Failed -> {
                listener.onAttemptFailed(j.url)
                val again = synchronized(lock) { ++j.failures <= FAILURE_RETRIES }
                if (again) retryLater(j, FAILURE_RETRY_MS) else finish(j)
            }
        }
    }

    private fun retryLater(j: Job, delayMs: Long) {
        synchronized(lock) {
            if (j.cancelled) return
            j.timer = scheduler.postDelayed(delayMs) { attempt(j) }
        }
    }

    private fun finish(j: Job) {
        synchronized(lock) { if (job === j) job = null }
    }

    companion object {
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        const val GIVE_UP_MS = 10 * 60_000L
        const val FAILURE_RETRIES = 2
        const val FAILURE_RETRY_MS = 3_000L
    }
}

/**
 * DEB-2205 (FR-6) — the picker row of a ticket text track the app feeds, at picker index [index]. Exactly what the row
 * was when the track was sideloaded: label from the ticket, then the language's name; default OR forced, never both.
 */
internal fun appFedSubtitleRow(sub: dev.jellystructure.shared.tv.SubTrack, index: Int): PlayerSubtitleTrack =
    PlayerSubtitleTrack(
        index,
        sub.label ?: languageName(sub.language) ?: sub.language?.uppercase() ?: "Track ${index + 1}",
        sub.language,
        forced = !sub.isDefault && sub.forced,
        isDefault = sub.isDefault,
        deliveryMethod = "external",
    )
