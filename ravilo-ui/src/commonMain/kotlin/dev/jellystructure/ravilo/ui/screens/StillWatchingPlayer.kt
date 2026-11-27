package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.STILL_WATCHING_CLOSE_MS

/**
 * DEB-5843 (FR-3, FR-4) — the decisions of the "Are you still watching?" ask in the app's player, as pure functions so
 * the state class holds only the wiring (in the style of `endClosesPlayer`).
 */

/** What the next-up countdown does when it runs out. */
internal enum class CountdownEnd { NOTHING, ADVANCE, ASK }

/** [cardStillUp] the card was not dismissed under the countdown; [autoplay] R182's setting; [askDue] FR-1's rule. With
 *  autoplay off nothing advances, so nothing asks. */
internal fun countdownEnd(cardStillUp: Boolean, autoplay: Boolean, askDue: Boolean): CountdownEnd = when {
    !cardStillUp || !autoplay -> CountdownEnd.NOTHING
    askDue -> CountdownEnd.ASK
    else -> CountdownEnd.ADVANCE
}

/** The ask pauses the item where it is; one that already ended under the countdown (R384) has nothing to pause. */
internal fun askPausesPlayer(playing: Boolean, ended: Boolean): Boolean = playing && !ended

/** The ask's two buttons: the first (focused) continues, the second is Back. */
internal enum class AskAnswer { CONTINUE, LEAVE }

internal fun askAnswerFor(focus: NuFocus): AskAnswer = if (focus == NuFocus.STAY) AskAnswer.LEAVE else AskAnswer.CONTINUE

/** FR-4 — an ask nobody answered for [STILL_WATCHING_CLOSE_MS] closes the player once. */
internal fun askClosesPlayer(sinceAskMs: Long, alreadyClosed: Boolean): Boolean =
    !alreadyClosed && sinceAskMs >= STILL_WATCHING_CLOSE_MS
