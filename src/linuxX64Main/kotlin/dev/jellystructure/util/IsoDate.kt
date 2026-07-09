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
