package dev.jellystructure.ravilo.ui.screens

/**
 * R290 — pressing Play or Resume is one moment, not three.
 *
 * What the viewer saw: the full transport chrome over black at 0:00, then the same chrome at the resume
 * position, then R218's loader, then the film — and on a remembered-audio transcode the whole sequence
 * twice, because the first stream was thrown away for a restream. Three causes: `chromeVisible` started
 * true and `wake()` re-raised it after every `load()`; the position came only from the poll; and nothing
 * held a discarded stream back from playing.
 *
 * One derived phase per **item** (dev review item 2), from a pure helper so it is tested without a
 * composition and costs `PlayerScreen`'s dex budget nothing:
 *  - [StartPhase.BLACK] — under R218's 400 ms from the press: plain black, no chrome, no loader.
 *  - [StartPhase.START] — the start screen (R218's Direction B, unchanged) until the latch opens; it
 *    stays up across a restream, R237's retry and the negotiation (open question 2: merged).
 *  - [StartPhase.PLAYING] — the latch has opened for this item: the chrome and R218's own moments take
 *    over, exactly as before this phase.
 */
enum class StartPhase { BLACK, START, PLAYING }

fun startPhase(latched: Boolean, startScreenDue: Boolean): StartPhase = when {
    latched -> StartPhase.PLAYING
    startScreenDue -> StartPhase.START
    else -> StartPhase.BLACK
}

/**
 * FR-R290-1/4 — the latch: "the first frame of the stream the viewer will actually watch". Two facts the
 * screen already holds: the CURRENT stream has rendered its first frame (reset on every `load()`), and
 * R181's resolver has settled for this item without sending the stream back for a restream
 * ([resolverSettled] and ![restreamPending]). A silent stream never gives the resolver a track list to
 * settle on, so a rendered frame that has waited [renderedForMs] ≥ [RESOLVER_GRACE_MS] opens the latch on
 * its own rather than never.
 */
fun startLatchOpens(
    sessionReady: Boolean,
    renderedFirstFrame: Boolean,
    resolverSettled: Boolean,
    restreamPending: Boolean,
    renderedForMs: Long,
): Boolean = sessionReady && renderedFirstFrame && !restreamPending && (resolverSettled || renderedForMs >= RESOLVER_GRACE_MS)

const val RESOLVER_GRACE_MS = 1_500L
