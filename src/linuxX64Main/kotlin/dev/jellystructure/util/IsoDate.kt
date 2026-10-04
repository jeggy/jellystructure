package dev.jellystructure.util

private val ISO_DATE_RE = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""")

/** Parses a Jellyfin ISO-8601 UTC timestamp (e.g. "2021-06-27T18:51:37.0000000Z") to epoch seconds.
 *  Manual days-from-civil (Hinnant) since kotlinx-datetime isn't available on Native; null if unparseable. */
fun isoToEpochSeconds(iso: String): Long? {
    val (ys, mos, ds, hs, mis, ss) = (ISO_DATE_RE.find(iso) ?: return null).destructured
    val y = ys.toInt(); val mo = mos.toInt(); val d = ds.toInt()
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097L + doe.toLong() - 719468L
    return days * 86400L + hs.toInt() * 3600L + mis.toInt() * 60L + ss.toInt()
}

private val ISO_FRACTION_RE = Regex("""T\d{2}:\d{2}:\d{2}\.(\d+)""")

/** R375 (dev review item 5) — the same timestamp at full precision, in .NET ticks (100 ns) since the epoch, so two
 *  plays inside one second still order. Jellyfin writes seven fraction digits; fewer are padded, more cut. */
fun isoToEpochTicks(iso: String): Long? {
    val seconds = isoToEpochSeconds(iso) ?: return null
    val frac = ISO_FRACTION_RE.find(iso)?.groupValues?.get(1)?.take(7)?.padEnd(7, '0')?.toLongOrNull() ?: 0L
    return seconds * 10_000_000L + frac
}

/** R375 — epoch ticks back to the ISO-8601 UTC form Jellyfin reads (`2026-10-04T12:00:00.0000000Z`). */
fun epochTicksToIso(ticks: Long): String {
    val seconds = ticks.floorDiv(10_000_000L)
    val frac = ticks.mod(10_000_000L)
    val days = seconds.floorDiv(86_400L)
    val secOfDay = seconds.mod(86_400L)
    // Hinnant's civil-from-days, the inverse of isoToEpochSeconds above.
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    fun p(v: Long, n: Int = 2) = v.toString().padStart(n, '0')
    return "${p(y, 4)}-${p(m)}-${p(d)}T${p(secOfDay / 3600)}:${p(secOfDay % 3600 / 60)}:${p(secOfDay % 60)}.${p(frac, 7)}Z"
}
