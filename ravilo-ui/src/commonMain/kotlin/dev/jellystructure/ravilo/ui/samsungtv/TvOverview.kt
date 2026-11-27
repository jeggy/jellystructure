package dev.jellystructure.ravilo.ui.samsungtv

import dev.jellystructure.shared.tv.SamsungTvRequest

/** DEB-5905 (FR-10f) — the pill on a card, from the viewer's request for that TV and from nothing else. */
enum class Badge { APPROVED, WAITING, REJECTED, NOT_SET_UP }

/** What a card offers. [UP_TO_DATE] is a line with a quiet *Reinstall*; [SET_UP], [ASK_AGAIN] and [WITHDRAW] are quiet links. */
enum class CardAction { INSTALL, UPDATE, UP_TO_DATE, SET_UP, ASK_AGAIN, WITHDRAW, NONE }

/** A TV the scan found, with what it said about Ravilo (FR-11). */
data class FoundTv(val tv: SamsungTv, val onTv: InstalledRavilo)

/**
 * DEB-5905 (FR-10) — one card of the overview. [tv] is the TV that answered the scan, null for a request built from the server's row
 * alone. [request] is null for a TV the viewer has no request for. [candidates] are the found TVs that match a row without being
 * *the* TV for it: two sets of one model that both keep the factory name (FR-10, *Which TV a request belongs to*).
 */
data class TvCard(
    val tv: SamsungTv?,
    val request: SamsungTvRequest?,
    /** Null where nothing was read: the scan is running, did not run, or did not find the TV. */
    val onTv: InstalledRavilo? = null,
    val candidates: List<SamsungTv> = emptyList(),
) {
    val name: String get() = tv?.name ?: request?.name.orEmpty()
    val model: String get() = tv?.model ?: request?.model.orEmpty()

    val badge: Badge get() = when (request?.state) {
        "READY", "APPROVED" -> Badge.APPROVED
        "REQUESTED" -> Badge.WAITING
        "REJECTED" -> Badge.REJECTED
        else -> Badge.NOT_SET_UP
    }

    /** FR-10b — an Approved or Ready TV gets the big card; every other card is small. */
    val big: Boolean get() = badge == Badge.APPROVED

    /** An Approved row no release covers yet: *Comes with the next Ravilo release* replaces *Latest*. */
    val nextRelease: Boolean get() = request?.state == "APPROVED"

    private val latest: RaviloVersion? get() = if (request?.state == "READY") RaviloVersion.parse(request.readyFromRelease) else null

    /** FR-10 item 3 — the row's `ready_from_release`, set by the server to the newest release covering the TV. */
    val latestText: String? get() = if (request?.state == "READY") (latest?.toString() ?: request.readyFromRelease?.takeIf { it.isNotBlank() }) else null

    val action: CardAction get() = when (request?.state) {
        null -> if (tv != null) CardAction.SET_UP else CardAction.NONE
        "REQUESTED" -> CardAction.WITHDRAW
        // Ask again needs the TV on this Wi-Fi to read its id, so a request the scan did not find has no action beyond Withdraw (FR-10 item 4).
        "REJECTED" -> if (tv != null) CardAction.ASK_AGAIN else CardAction.NONE
        "READY" -> {
            val installed = (onTv as? InstalledRavilo.Version)?.version
            val latest = latest
            when {
                tv == null && candidates.isEmpty() -> CardAction.NONE
                installed != null && latest != null && installed >= latest -> CardAction.UP_TO_DATE
                installed != null && latest != null -> CardAction.UPDATE
                else -> CardAction.INSTALL
            }
        }
        else -> CardAction.NONE
    }
}

private fun Badge.rank() = ordinal   // FR-10c: Approved/Ready, Waiting, Not approved, Not set up

/** FR-10d — before the scan answers, every request is a card of its own from the server's row. */
internal fun serverCards(rows: List<SamsungTvRequest>): List<TvCard> = rows.map { TvCard(null, it) }.sortedBy { it.badge.rank() }

/** FR-10 — a found TV belongs to the request whose `model` and `name` equal its own `modelName` and `name`, as the request was sent. */
internal fun SamsungTv.matches(row: SamsungTvRequest) = model.take(MAX_TEXT) == row.model && name.take(MAX_TEXT) == row.name

private const val MAX_TEXT = 120

/**
 * FR-10, FR-10c — the cards once the scan has ended: the found TVs first, then the requests the scan did not find, each group in
 * badge order. A TV and a row pair only when each matches the other alone; otherwise the phone does not guess. The TV is *Not set
 * up*, and the row keeps its own card with the TVs it could be ([TvCard.candidates]), because the install's DUID check picks the
 * right one. A TV the viewer's own TV does not know the version of falls back to the tag this phone last installed on its DUID.
 */
internal fun overviewCards(rows: List<SamsungTvRequest>, found: List<FoundTv>, lastInstalled: (String) -> String?): List<TvCard> {
    val rowsOf = found.associateWith { f -> rows.filter { f.tv.matches(it) } }
    val tvsOf = rows.associateWith { r -> found.filter { it.tv.matches(r) } }
    val paired = mutableSetOf<Long>()
    val foundCards = found.map { f ->
        val row = rowsOf.getValue(f).singleOrNull()?.takeIf { tvsOf.getValue(it).size == 1 }
        if (row == null) TvCard(f.tv, null) else {
            paired += row.id
            TvCard(f.tv, row, if (f.onTv == InstalledRavilo.Unknown) fallback(lastInstalled(row.duid)) else f.onTv)
        }
    }
    val rowCards = rows.filter { it.id !in paired }.map { TvCard(null, it, candidates = tvsOf.getValue(it).map(FoundTv::tv)) }
    return foundCards.sortedBy { it.badge.rank() } + rowCards.sortedBy { it.badge.rank() }
}

private fun fallback(tag: String?): InstalledRavilo = RaviloVersion.parse(tag)?.let(InstalledRavilo::Version) ?: InstalledRavilo.Unknown
