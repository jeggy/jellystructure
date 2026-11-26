package dev.jellystructure.ravilo.ui.sessions

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.shared.tv.SessionView

/**
 * R372 (FR-R372-2) — the places a session may move to: every reachable single place that can play its kind (a film or an
 * episode only a video place; a book never a Cast place), the current one ticked.
 */
fun moveTargets(targets: List<dev.jellystructure.shared.tv.PlaybackTarget>, v: SessionView): List<Pair<dev.jellystructure.shared.tv.PlaybackTarget, Boolean>> =
    targets.filter { it.reachable && canPlay(it.capabilities, v.kind, it.kind == "cast") }
        .map { it to (it.id == v.target.id || (it.castDeviceId != null && it.castDeviceId == v.target.castDeviceId)) }

/**
 * DEB-5706 (FR-R372-4, FR-R372-5, review item 10) — what an *ended* session's remote says, as string keys: someone else's
 * stop is *{person} stopped it*, a play-out is *Finished*, and anything else is *Stopped on {place}* alone. The first is
 * the line and the second the sub-line, which is *Stopped on {place}* under the first two. (*End of episode* and *End of
 * film* are R245's words for a Cast link whose receiver reports it ended; they are never a session's.)
 */
internal data class EndedLines(val key: String, val args: Map<String, String>, val subKey: String?)

internal fun endedLinesOf(v: SessionView): EndedLines {
    val stoppedOn = mapOf("place" to v.target.name)
    return when {
        v.endedBy != null -> EndedLines("session.stopped_by", mapOf("person" to v.endedBy.orEmpty()), "session.ended")
        v.endReason == "finished" -> EndedLines("ab.finished", emptyMap(), "session.ended")
        else -> EndedLines("session.ended", stoppedOn, null)
    }
}

/** FR-R372-5 — the state line under the place, where the state has words of its own. */
@Composable
internal fun sessionStateLineText(v: SessionView): String? = when {
    v.reconnecting -> str("session.reconnecting")
    v.state == "ended" -> endedLinesOf(v).let { str(it.key, it.args) }
    v.offline -> str("session.place_offline", mapOf("place" to v.target.name, "time" to dev.jellystructure.ravilo.ui.music.fmtLen(v.positionMs ?: 0)))
    v.state == "starting" || v.state == "buffering" -> str("loading")
    !v.mine -> str(if (v.kind == "music" || v.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to v.owner.name))
    else -> null
}
