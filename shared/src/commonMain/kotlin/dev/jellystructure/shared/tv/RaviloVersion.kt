package dev.jellystructure.shared.tv

/**
 * Phase 259 (dev review item 1) — Ravilo versions are plain `MAJOR.MINOR` (Phase 231's tag shape) and
 * consecutive releases differ by one in MINOR, so everything the version history says about a step is
 * arithmetic on two strings: no release list exists server-side, and none is needed. Shared by the
 * backend (the page-bar count) and the admin page (the *skipped* note), so both say the same thing.
 */
data class RaviloVersion(val major: Int, val minor: Int) : Comparable<RaviloVersion> {
    override fun compareTo(other: RaviloVersion): Int =
        if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)
    override fun toString(): String = "$major.$minor"
}

private val RELEASE_RE = Regex("""^(\d+)\.(\d+)$""")
/** FR-259-8 / dev review item 7 — `git describe` yields `1.37-68-gade0523d`, or `…-gade0523d-dirty` with local
 *  changes; the one `-g<sha>` test covers both. A plain `1.38` is what GHCR and Play builds carry. */
private val DEV_RE = Regex("""-g[0-9a-f]{6,}""")

/** A published release's version, or null for a dev build, a blank, or anything else. */
fun parseRaviloRelease(version: String?): RaviloVersion? {
    val m = RELEASE_RE.matchEntire(version?.trim() ?: return null) ?: return null
    return RaviloVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt())
}

fun isRaviloDevBuild(version: String?): Boolean = version != null && DEV_RE.containsMatchIn(version)

/**
 * FR-259-7 — the releases a step from [older] to [newer] jumped over: `1.36 → 1.38` skipped `1.37`;
 * `1.33 → 1.36` skipped `1.34 – 1.35`. Null when nothing was skipped, when either side is not a release,
 * across a MAJOR change (nothing is said), or when the step goes backwards.
 */
fun raviloSkippedBetween(older: String?, newer: String?): String? {
    val a = parseRaviloRelease(older) ?: return null
    val b = parseRaviloRelease(newer) ?: return null
    if (a.major != b.major || b.minor - a.minor < 2) return null
    val first = a.minor + 1; val last = b.minor - 1
    return if (first == last) "${a.major}.$first" else "${a.major}.$first – ${a.major}.$last"
}

/**
 * FR-259-9 (256 FR-256-5's comparison) — how many releases [device] is behind [latest]: null when either
 * is not a release (a dev build is never counted as behind) or across a MAJOR change; never negative.
 */
fun raviloReleasesBehind(device: String?, latest: String?): Int? {
    val d = parseRaviloRelease(device) ?: return null
    val l = parseRaviloRelease(latest) ?: return null
    if (d.major != l.major) return null
    return (l.minor - d.minor).coerceAtLeast(0)
}
