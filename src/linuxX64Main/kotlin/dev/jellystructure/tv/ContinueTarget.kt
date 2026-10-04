package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.util.isoToEpochSeconds
import dev.jellystructure.util.isoToEpochTicks

/**
 * R219 + R375 — the canonical Continue Watching list as pure rules over the four Jellyfin fetches (resume, Next Up,
 * the watched list, the touched list) and our catalog. [HomeFeedService.buildCanonicalContinueList] fetches, calls
 * [planContinue] and turns each [ContinuePick] into a card; nothing else decides a next episode (FR-R375-4).
 *
 * R375 (FR-R375-1–3): per series, the *last finished episode* (the anchor) is the counted, watched, position-0
 * episode with the newest `LastPlayedDate` at full precision, a tie going to the later episode in order. When there
 * is one, the next card is the next episode **in order** after it ([nextEpisodeAfter]), watched or not, instead of
 * Jellyfin's Next Up (which follows the furthest episode ever watched). Without one, R219's rule with Jellyfin's
 * Next Up stands unchanged.
 */

/** One entry of the list, before it becomes a card. [episodeId] is null for a film. */
internal data class ContinuePick(
    val mediaItem: MediaItem,
    val episodeId: String?,
    val progressPct: Float?,
    val nextUpLabel: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeNumberEnd: Int?,
    val lastActivityAt: Long,
)

/** FR-R375-1 — a series' last finished episode: the catalog row, its Jellyfin id, and its `LastPlayedDate` as
 *  Jellyfin wrote it ([iso]) and at full precision ([ticks], 100 ns). */
internal data class ContinueAnchor(val episode: Episode, val jellyfinId: String, val ticks: Long, val iso: String)

/** The list, and per series Jellyfin id the anchor's date (R375 FR-R375-6's `anchor − 1 s`, read at a shuffle's start). */
internal data class ContinuePlan(val picks: List<ContinuePick>, val anchorDates: Map<String, String>)

private fun JellyfinPlayItem.playedTicks(): Long? = userData?.lastPlayedDate?.let { isoToEpochTicks(it) }
private fun JellyfinPlayItem.playedSeconds(): Long? = userData?.lastPlayedDate?.let { isoToEpochSeconds(it) }

/**
 * FR-R375-1 — among [watched] (one series' items from the watched list), the episode with the newest `LastPlayedDate`
 * that is watched with no resume position (a watched episode re-opened and left partway keeps one) and counts (season
 * 1+, with a Jellyfin item, in our catalog). A max over the items, never "the first one seen" (R198: Jellyfin's sort is
 * not trusted); equal timestamps (marking a season watched stamps one second) go to the later episode in order.
 */
internal fun lastFinishedEpisode(series: MediaItem, watched: List<JellyfinPlayItem>): ContinueAnchor? {
    val ordered = countedEpisodesInOrder(series)
    if (ordered.isEmpty()) return null
    val orderOf = HashMap<String, Int>()
    ordered.forEachIndexed { i, ep -> orderOf[ep.jellyfinId!!] = i }   // a multi-episode file: its last part's place
    var best: Triple<JellyfinPlayItem, Long, Int>? = null
    for (p in watched) {
        val ud = p.userData ?: continue
        if (!ud.played || ud.playbackPositionTicks != 0L) continue
        val order = orderOf[p.id] ?: continue
        val ticks = p.playedTicks() ?: continue
        val b = best
        if (b == null || ticks > b.second || (ticks == b.second && order > b.third)) best = Triple(p, ticks, order)
    }
    val (item, ticks, _) = best ?: return null
    val ep = ordered.first { it.jellyfinId == item.id }
    return ContinueAnchor(ep, item.id, ticks, item.userData!!.lastPlayedDate!!)
}

internal fun planContinue(
    libraryAll: List<MediaItem>,
    resumeItems: List<JellyfinPlayItem>,
    nextUpItems: List<JellyfinPlayItem>,
    finishedItems: List<JellyfinPlayItem>,
    touchedItems: List<JellyfinPlayItem>,
): ContinuePlan {
    val byJellyfinId = libraryAll.asSequence().mapNotNull { mi -> mi.jellyfinId?.let { it to mi } }.toMap()

    // R198 — never trust an upstream SortBy as a guarantee (confirmed live: two adjacent Resume items came back out of
    // order while the surrounding ~90 were fine). Own the ordering here, at full precision since R375.
    val resumeSorted = resumeItems.sortedByDescending { it.playedTicks() ?: 0L }

    // §3/§4 need "when did I last finish an episode of THIS title" and "when was THIS title last touched at all".
    // R375 (dev review item 5) — a max per key, not the first one seen: R198 applies to these lists too.
    val lastFinishedByKey = HashMap<String, Long>()
    for (p in finishedItems) {
        val key = p.seriesId ?: p.id
        val ts = p.playedSeconds() ?: continue
        if (ts > (lastFinishedByKey[key] ?: Long.MIN_VALUE)) lastFinishedByKey[key] = ts
    }
    val lastTouchedByKey = HashMap<String, Long>()
    for (p in touchedItems) {
        val key = p.seriesId ?: p.id
        val ts = p.playedSeconds() ?: continue
        if (ts > (lastTouchedByKey[key] ?: Long.MIN_VALUE)) lastTouchedByKey[key] = ts
    }

    // §2(a) / resume candidate — one per title, the MOST RECENT in-progress episode if several (FR-R219-4).
    // R375 (dev review item 6) — and every in-progress episode by its own id, for a next-in-order card's progress.
    data class ResumeCandidate(val pick: ContinuePick, val ticks: Long)
    val resumeByKey = LinkedHashMap<String, ResumeCandidate>()
    val resumeByEpisodeId = HashMap<String, JellyfinPlayItem>()
    for (play in resumeSorted) {
        // R185 — an item flagged Played is never resurrected as in-progress, whatever a leaked position says.
        if (play.userData?.played == true) continue
        if (play.seriesId != null && play.id !in resumeByEpisodeId) resumeByEpisodeId[play.id] = play
        val key = play.seriesId ?: play.id
        if (key in resumeByKey) continue  // already holding this key's newest episode (list is sorted)
        val mediaItem = byJellyfinId[key] ?: continue
        val pct = play.userData?.playedPercentage?.toFloat()?.div(100f)
        // R113 / R199 / R309 — the on-image badge, with our own scanned numbers as the fallback and a file's range end.
        val span = resolvedEpisodeSpan(mediaItem, play.id, play.seasonNumber, play.episodeNumber)
        resumeByKey[key] = ResumeCandidate(
            ContinuePick(mediaItem, play.id.takeIf { play.seriesId != null }, pct, null, span.season, span.episode, span.episodeEnd, play.playedSeconds() ?: 0L),
            play.playedTicks() ?: 0L,
        )
    }

    // "Something left to watch" half of §2 — Jellyfin's next-up candidate per title (used only without an anchor).
    val nextUpByKey = LinkedHashMap<String, ContinuePick>()
    for (play in nextUpItems) {
        val key = play.seriesId ?: play.id
        if (key in nextUpByKey) continue
        val mediaItem = byJellyfinId[key] ?: continue
        val span = resolvedEpisodeSpan(mediaItem, play.id, play.seasonNumber, play.episodeNumber)
        val label = span.code?.let { "$it · ${play.name}" } ?: play.name  // R309: S01E04–E06 · … for a multi-episode file
        nextUpByKey[key] = ContinuePick(mediaItem, play.id.takeIf { play.seriesId != null }, null, label, span.season, span.episode, span.episodeEnd, 0L)
    }

    // R375 (FR-R375-1/2) — per series: the last finished episode and the next one in order after it.
    data class Target(val anchor: ContinueAnchor, val next: ContinuePick?)
    val targets = HashMap<String, Target>()
    for ((seriesId, items) in finishedItems.filter { it.seriesId != null }.groupBy { it.seriesId!! }) {
        val series = byJellyfinId[seriesId]?.takeIf { it.kind == MediaKind.TV_SHOW } ?: continue
        val anchor = lastFinishedEpisode(series, items) ?: continue
        val next = nextEpisodeAfter(series, anchor.episode)?.let { n ->
            val span = resolvedEpisodeSpan(series, n.jellyfinId, null, null)
            // FR-R375-3 — an in-progress, unwatched next episode shows as a resume card (its progress, its badge).
            val inProgress = resumeByEpisodeId[n.jellyfinId]
            val pct = inProgress?.userData?.playedPercentage?.toFloat()?.div(100f)
            // The label from our catalog: Jellyfin's own name is only on the items its Next Up returned.
            val label = if (inProgress != null) null else listOfNotNull(span.code, n.title).joinToString(" · ").ifEmpty { null }
            ContinuePick(series, n.jellyfinId, pct, label, span.season, span.episode, span.episodeEnd, 0L)
        }
        targets[seriesId] = Target(anchor, next)
    }

    // §2 membership: "genuinely started" (a: resume, b: finished, c: touched within the window) AND "something left
    // to watch" (resume, Jellyfin's next-up, or — R375 — a next episode in order after the last finished one).
    val startedKeys = resumeByKey.keys + lastFinishedByKey.keys + lastTouchedByKey.keys
    val candidateKeys = startedKeys.filter { it in resumeByKey || it in nextUpByKey || targets[it]?.next != null }

    // §3 — the conflict rule: most recent activity wins.
    val picks = candidateKeys.mapNotNull { key ->
        val resume = resumeByKey[key]
        val finishedTs = lastFinishedByKey[key]
        val target = targets[key]
        if (target != null) {
            // FR-R375-3 — with an anchor, Jellyfin's Next Up is not consulted.
            when {
                resume != null && resume.ticks >= target.anchor.ticks -> resume.pick
                target.next != null -> target.next.copy(lastActivityAt = finishedTs ?: (target.anchor.ticks / 10_000_000L))
                resume != null -> resume.pick              // the last episode was finished: the older resume point
                else -> null                               // nothing left in order: the series leaves the list
            }
        } else {
            val nextUp = nextUpByKey[key]
            // R219 unchanged. A next-up-only title (§2(c): no resume, no finish, just "touched") falls to its own
            // touched timestamp — membership above required it to be in startedKeys.
            when {
                resume != null && nextUp != null && finishedTs != null && finishedTs > resume.pick.lastActivityAt ->
                    nextUp.copy(lastActivityAt = finishedTs)
                resume != null -> resume.pick
                nextUp != null -> nextUp.copy(lastActivityAt = finishedTs ?: lastTouchedByKey[key] ?: 0L)
                else -> null  // unreachable — candidateKeys already required resume or nextUp present
            }
        }
    }

    // §4 — one chronological order across the whole merged list. sortedByDescending is stable, so ties keep the order
    // they were built in. R198: unparseable/missing (the 0L sentinels above) sorts last, never first.
    return ContinuePlan(
        picks = picks.sortedByDescending { it.lastActivityAt },
        anchorDates = targets.mapValues { it.value.anchor.iso },
    )
}
