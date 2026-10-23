package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import dev.jellystructure.shared.tv.episodeCode

/*
 * DEB-2078 FR-2..FR-6 — Coming Soon stacks a series' same-day episodes into one card. Everything here is
 * pure, so it is tested without a screen: when a stack forms, what it says, and which feed rows it opens.
 */

/** One card's worth of the feed: a single item, or two or more episodes of one series (FR-2). */
internal sealed interface UpcomingEntry {
    data class Single(val item: UpcomingItem) : UpcomingEntry

    /** [key] is the stack key; [members] are in the feed's own order. */
    data class Stack(val key: String, val members: List<UpcomingItem>) : UpcomingEntry
}

/** FR-2 — `seriesKey ?: itemId`; a film, or an episode with neither, has none and never stacks. */
internal fun stackKeyOf(item: UpcomingItem): String? =
    if (item.kind == MediaKind.SERIES) item.seriesKey ?: item.itemId else null

/**
 * FR-2 — builds a row's entries from [items], already filtered. With [byDay] two or more episodes with the same
 * stack key on the same `date` stack (a day row); without it the same key stacks whatever the day (the Missing
 * row). A stack takes its first member's position and its members keep the feed's order.
 */
internal fun stackUpcoming(items: List<UpcomingItem>, byDay: Boolean): List<UpcomingEntry> {
    fun groupOf(item: UpcomingItem): String? = stackKeyOf(item)?.let { if (byDay) "${item.date}\u0000$it" else it }
    val members = LinkedHashMap<String, MutableList<UpcomingItem>>()
    items.forEach { item -> groupOf(item)?.let { members.getOrPut(it) { mutableListOf() }.add(item) } }
    val placed = HashSet<String>()
    val out = ArrayList<UpcomingEntry>(items.size)
    for (item in items) {
        val group = groupOf(item)
        val all = group?.let { members[it] }
        if (group == null || all == null || all.size < 2) out += UpcomingEntry.Single(item)
        else if (placed.add(group)) out += UpcomingEntry.Stack(stackKeyOf(item)!!, all)
    }
    return out
}

/** FR-5 — the id a row stores in `lastSelectedItemKey`, and gives `StaticContentRow` as the card's key. */
internal fun UpcomingEntry.restoreKey(inMissingRow: Boolean): String = when (this) {
    is UpcomingEntry.Single -> if (inMissingRow) "missing:${item.id}" else item.id
    is UpcomingEntry.Stack -> if (inMissingRow) "missing-stack:$key" else "stack:$key:${members.first().date}"
}

/** FR-6 — a stack opens what its first member opens. */
internal fun UpcomingEntry.opens(): UpcomingItem = when (this) {
    is UpcomingEntry.Single -> item
    is UpcomingEntry.Stack -> members.first()
}

/**
 * FR-6 — every episode that shares [item]'s stack, in feed order, for the not-held detail's schedule: those with
 * its key on its date, or — when [item] is itself in `missing` — those in `missing` with its key. An item that is
 * on no stack (a film, a lone episode, no key) is its own only member.
 */
internal fun stackMembersOf(feed: UpcomingFeed, item: UpcomingItem): List<UpcomingItem> {
    val key = stackKeyOf(item) ?: return listOf(item)
    val inMissing = feed.missing.any { it.id == item.id }
    val pool = if (inMissing) feed.missing.filter { stackKeyOf(it) == key }
    else feed.items.filter { stackKeyOf(it) == key && it.date == item.date }
    return pool.takeIf { it.size >= 2 && pool.any { m -> m.id == item.id } } ?: listOf(item)
}

/** The word a stack's status pill says (FR-4). */
internal enum class StackPill { ALL_AVAILABLE, SOME_AVAILABLE, ALL_MISSING, NONE }

/** FR-4 — [pill] with [available] of [size]; [downloading] is the first member (episode order) being grabbed. */
internal data class StackStatus(val pill: StackPill, val available: Int, val size: Int, val downloading: UpcomingItem?)

internal fun stackStatus(members: List<UpcomingItem>): StackStatus {
    val k = members.count { it.status == UpcomingStatus.AVAILABLE }
    val n = members.size
    val pill = when {
        k == n && n > 0 -> StackPill.ALL_AVAILABLE
        k > 0 -> StackPill.SOME_AVAILABLE
        members.isNotEmpty() && members.all { it.status == UpcomingStatus.MISSING } -> StackPill.ALL_MISSING
        else -> StackPill.NONE
    }
    val downloading = members.filter { it.status == UpcomingStatus.DOWNLOADING }.minWithOrNull(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    return StackStatus(pill, k, n, downloading)
}

/** FR-3 — the stack's earliest air time (smallest non-null `time`); null when no member has one. */
internal fun stackEarliestTime(members: List<UpcomingItem>): String? = members.mapNotNull { it.time }.minOrNull()

/** FR-3 — the earliest due date of a Missing stack. */
internal fun stackEarliestDate(members: List<UpcomingItem>): String = members.minOf { it.date }

/**
 * FR-3 — the episode range, built from data: `S02E01–E08` within one season, `S01E10–S02E02` across seasons, a lone
 * code when it is one episode; null when no member carries a season and an episode.
 */
internal fun stackEpisodeRange(members: List<UpcomingItem>): String? {
    val numbered = members.mapNotNull { m -> m.season?.let { s -> m.episode?.let { e -> s to e } } }
    val first = numbered.minWithOrNull(compareBy({ it.first }, { it.second })) ?: return null
    val last = numbered.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
    return when {
        first == last -> episodeCode(first.first, first.second)
        first.first == last.first -> episodeCode(first.first, first.second, last.second)
        else -> "${episodeCode(first.first, first.second)}–${episodeCode(last.first, last.second)}"
    }
}
