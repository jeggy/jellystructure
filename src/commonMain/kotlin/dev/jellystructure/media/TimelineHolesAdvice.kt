package dev.jellystructure.media

/** The words one file's block shows (DEB-3633 FR-6/FR-7). Every figure is decided here, not on the page. */
data class TimelineAdvice(val what: String, val experience: String, val suggestion: String)

/**
 * DEB-3637 (DEB-3633 FR-7) — the suggestion is text, chosen by the case, from ONE builder. There is no
 * command and no button: no local edit restores what a file is missing. Pure, so it is tested not eyeballed.
 */
object TimelineHolesAdvice {
    const val SAME_RELEASE =
        "These files came from one release, and it is missing parts. Replace them with a different release: in Sonarr, delete the files and search again, rejecting this release."
    const val ONE_FILE =
        "This file is missing parts, and nothing local can restore them. Replace it with a different release: in Sonarr or Radarr, delete the file and search again."
    const val EXPERIENCE =
        "The episode jumps ahead at each gap. On a transcoded stream the picture freezes and the sound goes silent instead."

    fun advise(report: TimelineReport, otherFilesOfSameReleaseHaveHoles: Boolean): TimelineAdvice {
        val places = if (report.holeCount == 1) "1 place" else "${report.holeCount} places"
        val longestAt = report.holes.firstOrNull { it.lengthMs == report.longestMs }?.let { ", at ${TrackCoverage.mmss(it.startMs)}" } ?: ""
        return TimelineAdvice(
            what = "Picture and sound are missing in $places, ${TrackCoverage.mmss(report.missingMs)} in all. The longest gap is ${seconds(report.longestMs)}$longestAt.",
            experience = EXPERIENCE,
            suggestion = if (otherFilesOfSameReleaseHaveHoles) SAME_RELEASE else ONE_FILE,
        )
    }

    /** `m:ss (n.n s)` — one listed hole. */
    fun holeLine(h: TimelineHole): String = "${TrackCoverage.mmss(h.startMs)} (${seconds(h.lengthMs)})"

    /** `3.9 s`, rounded to a tenth. */
    fun seconds(ms: Long): String {
        val tenths = (ms + 50) / 100
        return "${tenths / 10}.${tenths % 10} s"
    }

    /** *S01: 5 episodes, 2 to 9 minutes missing each* — one season's line, from its files' missing times. */
    fun seasonLine(label: String, missingMs: List<Long>): String {
        val n = missingMs.size
        val lo = minutes(missingMs.min(), up = false)
        val hi = minutes(missingMs.max(), up = true)
        val span = if (lo == hi) "about $lo minute${if (lo == 1L) "" else "s"}" else "$lo to $hi minutes"
        return "$label: $n ${if (n == 1) "episode" else "episodes"}, $span missing${if (n == 1) "" else " each"}"
    }

    private fun minutes(ms: Long, up: Boolean): Long {
        val m = if (up) (ms + 59_999) / 60_000 else ms / 60_000
        return maxOf(1L, m)
    }

    /**
     * The release a file belongs to: its release group taken from the name (`…-GROUP.mkv`, `[Group] …`), and
     * where the name carries none, its parent directory plus its name with the digits blanked out — files of
     * one release are named by one pattern. A hint in the text, never a gate (FR-7).
     */
    fun releaseKey(path: String): String {
        val name = path.substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name)
        releaseGroup(stem)?.let { return "group:${it.lowercase()}" }
        return "dir:${path.substringBeforeLast('/', "")}|" + stem.replace(Regex("[0-9]+"), "#")
    }

    fun releaseGroup(stem: String): String? {
        Regex("""^\[([^\]]{2,32})]""").find(stem)?.let { return it.groupValues[1] }
        val m = Regex("""[^\s-]-([A-Za-z0-9_]{2,24})$""").find(stem) ?: return null
        val token = m.groupValues[1]
        return token.takeUnless { t -> t.all { it.isDigit() } }
    }

    fun sameRelease(a: String, b: String): Boolean = a != b && releaseKey(a) == releaseKey(b)
}
