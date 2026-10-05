package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities

// R370 — one *Play on…* list: the server's places merged with this app's own Cast discovery, in four tiers.

/** One row of *Play on…*: a server place, a local Cast route, or both (one Cast device, review item 3). */
data class PlayOnRow(
    val id: String,
    val name: String,
    /** `phone · computer · tv · display · speaker` */
    val icon: String,
    val target: PlaybackTarget? = null,
    val route: CastRoute? = null,
    val busy: SessionView? = null,
    val reachable: Boolean = true,
    val here: Boolean = false,
)

/** FR-R370-2 — the four tiers, in this order. */
data class PlayOnTiers(
    val thisDevice: PlayOnRow?,
    val playingNow: List<PlayOnRow>,
    val free: List<PlayOnRow>,
    val unreachable: List<PlayOnRow>,
)

/** What a place must play for [kind] (review item 6; owner decision 4: a book never on a Cast place). */
fun canPlay(c: TargetCapabilities, kind: String, castPlace: Boolean): Boolean = when (kind) {
    "film", "episode", "video" -> c.video
    "audiobook" -> !castPlace && c.book
    else -> c.audio
}

/**
 * Review items 2–5 — merge the server's places with this app's own discovery: one row per Cast device id (a server
 * row and a local route with one id), one row per physical TV whose app and route share a name (the app while its
 * socket is open), never a group route. With [serverTargets] null (the server cannot be reached, or is older) the
 * local list stands alone.
 */
fun mergeTargets(serverTargets: List<PlaybackTarget>?, localRoutes: List<CastRoute>): List<PlayOnRow> {
    val routes = localRoutes.filter { it.kind != "group" }
    if (serverTargets == null) return routes.map { r ->
        PlayOnRow(id = "cast:${r.deviceKey}", name = r.name, icon = if (r.kind == "speaker") "speaker" else "tv", route = r)
    }
    val rows = mutableListOf<PlayOnRow>()
    val usedRoutes = mutableSetOf<String>()
    for (t in serverTargets) {
        if (t.kind == "cast") {
            // By the shared Cast id; else by name — a receiver's own record (`no_relay`, seen hours ago) carries no Cast id,
            // and dropping the local route under it hid a speaker this app reaches itself (found on the Pixel).
            val r = routes.firstOrNull { it.id !in usedRoutes && t.castDeviceId != null && it.deviceKey == t.castDeviceId }
                ?: routes.firstOrNull { it.id !in usedRoutes && it.name.equals(t.name, ignoreCase = true) }
            if (r != null) usedRoutes += r.id
            // A place this app reaches itself is reachable whatever the server's relay state says.
            rows += PlayOnRow(t.id, t.name, t.icon, target = t, route = r, busy = t.busy, reachable = t.reachable || r != null)
        } else {
            // A TV running Ravilo and its own Cast route: one row, the app while its socket is open (review item 4).
            routes.firstOrNull { it.name.equals(t.name, ignoreCase = true) }?.let { usedRoutes += it.id }
            rows += PlayOnRow(t.id, t.name, t.icon, target = t, busy = t.busy, reachable = t.reachable, here = t.here)
        }
    }
    for (r in routes) if (r.id !in usedRoutes && rows.none { it.name.equals(r.name, ignoreCase = true) }) {
        rows += PlayOnRow(id = "cast:${r.deviceKey}", name = r.name, icon = if (r.kind == "speaker") "speaker" else "tv", route = r)
    }
    return rows
}

/**
 * FR-R370-2 — the four tiers for [kind]: this device · playing now · free (TVs and displays first, then speakers, by
 * name) · not reachable. A place that cannot play this kind is absent, not greyed.
 */
fun playOnTiers(rows: List<PlayOnRow>, kind: String): PlayOnTiers {
    fun plays(r: PlayOnRow): Boolean {
        val castPlace = r.route != null || r.target?.kind == "cast"
        val caps = r.target?.capabilities ?: TargetCapabilities(video = r.route?.kind != "speaker", audio = true, display = r.route?.kind != "speaker")
        return canPlay(caps, kind, castPlace)
    }
    val usable = rows.filter { plays(it) }
    val here = usable.firstOrNull { it.here }
    val rest = usable.filter { !it.here }
    val playing = rest.filter { it.reachable && it.busy != null && it.busy.state != "ended" }
    val free = rest.filter { it.reachable && (it.busy == null || it.busy.state == "ended") }
        .sortedWith(compareBy<PlayOnRow> { it.icon == "speaker" }.thenBy { it.name.lowercase() })
    val unreachable = rest.filter { !it.reachable }.sortedBy { it.name.lowercase() }
    return PlayOnTiers(here, playing, free, unreachable)
}

/** FR-R370-4 — what tapping a place does. */
sealed interface BusyChoice {
    /** Free (or this app's own cast): start straight away. */
    data object Start : BusyChoice
    /** Busy with a session started elsewhere: ask *Play {title} here instead* / *Cancel*, every time. */
    data object AskReplace : BusyChoice
    /** Someone else's, and 304's switch lets this viewer stop it: *Stop {person}'s {title} and play here?* */
    data object AskReplacePerson : BusyChoice
    /** Someone else's without the switch: not offered. */
    data object NotOffered : BusyChoice
    /** Not reachable: a tap does nothing. */
    data object Nothing : BusyChoice
}

/**
 * FR-R370-4, owner decision 2 — the busy place always asks, never offers *Add*, never remembered; no question while this
 * app holds that session's Cast link (as today, R324); someone else's only with the switch ([SessionView.controllable]).
 */
fun busyChoice(row: PlayOnRow, holdsCastLink: Boolean): BusyChoice {
    if (!row.reachable) return BusyChoice.Nothing
    val busy = row.busy?.takeIf { it.state != "ended" } ?: return BusyChoice.Start
    if (holdsCastLink) return BusyChoice.Start
    return when {
        busy.mine -> BusyChoice.AskReplace
        busy.controllable -> BusyChoice.AskReplacePerson
        else -> BusyChoice.NotOffered
    }
}

/** FR-R370-4 — pressing Play on something of the same kind already playing for this viewer elsewhere asks too. */
fun sameKindElsewhere(sessions: List<SessionView>, kind: String): SessionView? {
    val lane = if (kind == "music" || kind == "audiobook") setOf("music", "audiobook") else setOf("film", "episode")
    return sessions.filter { it.mine && !it.here && it.state != "ended" && it.kind in lane }.maxByOrNull { it.updatedAt }
}
