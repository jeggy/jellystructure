package dev.jellystructure.tv

import dev.jellystructure.shared.tv.HomeFeed

/**
 * R314 (FR-R314-3) — focus detail exists only on a TV (FR-R314-1, decided in the app), so a phone or the
 * web app never draws R240's per-title facts. They were still sent on every Home card: 347 fact objects,
 * 61 % of production's `/api/tv/home` and three quarters of its gzip bytes, parsed and thrown away on
 * every Home load and live refresh of a phone on mobile data. `platform` is R252's `X-Ravilo-Platform`,
 * refreshed on every request (`DeviceData.platform`). An unreported platform keeps the facts, as does `tv`.
 * R328 — the Mac app (and its Linux development build) is not a TV either: `isTvPlatform` is false there.
 */
internal fun focusFactsReach(platform: String?): Boolean = platform !in NO_FOCUS_FACTS

private val NO_FOCUS_FACTS = setOf("phone", "web", "mac", "linux")

/**
 * The feed without any row card's facts. A projection over what the Home cache returned, never part of
 * the cache key: one cached feed per user serves every platform (R233 FR-R233-5's trap, which R254
 * recorded when it rejected a server-side mode). The top-level `focus_detail` / delay stay as they are;
 * the app decides, and the facts field is optional on the wire for every installed app.
 */
internal fun HomeFeed.withoutFocusFacts(): HomeFeed = copy(rows = rows.map { row ->
    if (row.items.none { it.focusDetail != null }) row
    else row.copy(items = row.items.map { if (it.focusDetail == null) it else it.copy(focusDetail = null) })
})
