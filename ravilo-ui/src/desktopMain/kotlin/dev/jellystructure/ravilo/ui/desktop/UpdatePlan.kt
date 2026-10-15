package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.shared.tv.RaviloVersion
import dev.jellystructure.shared.tv.parseRaviloRelease
import dev.jellystructure.shared.tv.raviloServerFloor

/**
 * R386 (FR-R386-3, D2, D12) — what one check decides, as a pure function of three versions. Nothing here touches the
 * network or Sparkle: [UpdateCheck] reads the inputs and acts on the answer.
 *
 * - **A** — the app's own release. Not a plain release (a development build): no update block at all.
 * - **S** — the server's release floor (`raviloServerFloor`). Unknown means *Offline*, keeping the last check's time.
 * - **L** — the newest release, from `releases/latest/download/appcast.xml`.
 */
sealed interface UpdatePlan {
    /** State 10 — a development build of the app: the block is absent. */
    data object NoBlock : UpdatePlan

    /** S > A — Sparkle is pointed at [feedUrl] and runs a background check. [server] is null for the debug override. */
    data class AskSparkle(val server: RaviloVersion?, val feedUrl: String) : UpdatePlan

    /** S ≤ A and L > A — state 6. Sparkle is not asked. */
    data class HeldBack(val latest: RaviloVersion, val server: RaviloVersion) : UpdatePlan

    /** State 1. */
    data object UpToDate : UpdatePlan

    /** State 12 — GitHub cannot be reached, or the server is unknown. */
    data object Offline : UpdatePlan
}

/** What reading a GitHub appcast came to: a 404 is *nothing published*, never an error (FR-R386-3). */
sealed interface AppcastRead {
    data class Release(val version: RaviloVersion) : AppcastRead
    /** A 404, or an appcast that names no plain release: nothing to offer. */
    data object NotPublished : AppcastRead
    /** GitHub cannot be reached, or answered something other than 200 or 404. */
    data object Unreachable : AppcastRead
}

object UpdateRules {
    private const val RELEASES = "https://github.com/jeggy/jellystructure/releases"

    /** FR-R386-3's L. */
    const val LATEST_APPCAST = "$RELEASES/latest/download/appcast.xml"

    /** The appcast R386 FR-R386-2 attaches to the `v<N>` release. */
    fun appcastOf(version: RaviloVersion): String = "$RELEASES/download/v$version/appcast.xml"

    /** The tag's release page — *What's new* (FR-R386-9) and the item's `releaseNotesLink`. */
    fun releasePageOf(version: RaviloVersion): String = "$RELEASES/tag/v$version"

    /**
     * FR-R386-3's table. [feed] is what reading `v<S>/appcast.xml` came to, asked only when S > A; [latest] is L,
     * asked only when S ≤ A. Passing both is fine — the one the table does not need is ignored.
     */
    fun plan(app: String?, server: String?, feed: AppcastRead?, latest: AppcastRead?): UpdatePlan {
        val a = parseRaviloRelease(app?.trim()?.removePrefix("v")) ?: return UpdatePlan.NoBlock
        val s = raviloServerFloor(server) ?: return UpdatePlan.Offline
        if (s > a) {
            return when (feed) {
                is AppcastRead.Release -> UpdatePlan.AskSparkle(s, appcastOf(s))
                AppcastRead.NotPublished -> UpdatePlan.UpToDate   // a release from before R386: nothing to offer
                AppcastRead.Unreachable, null -> UpdatePlan.Offline
            }
        }
        return when (latest) {
            is AppcastRead.Release -> if (latest.version > a) UpdatePlan.HeldBack(latest.version, s) else UpdatePlan.UpToDate
            AppcastRead.NotPublished -> UpdatePlan.UpToDate
            AppcastRead.Unreachable, null -> UpdatePlan.Offline
        }
    }

    /** The version an appcast's item names (`sparkle:shortVersionString`), as a plain release — or null. */
    fun versionInAppcast(xml: String): RaviloVersion? {
        val m = SHORT_VERSION.find(xml) ?: return null
        return parseRaviloRelease(m.groupValues[1].trim())
    }

    private val SHORT_VERSION = Regex("""<sparkle:shortVersionString>\s*([^<]+?)\s*</sparkle:shortVersionString>""")

    /** The debug-only feed override (below) is honoured only by a build that was made with the permission baked in. */
    const val OVERRIDE_ALLOWED_PROPERTY = "ravilo.update.feed.allowed"

    /**
     * The spike's feed (R386 handoff): `RAVILO_UPDATE_FEED` or `-Dravilo.update.feed` points a build at a local
     * appcast. **Never honoured by a release build**: a `workflow_dispatch` build is made with
     * `-Dravilo.update.feed.allowed=true` in its launcher, and a release is not.
     */
    fun feedOverride(env: (String) -> String?, prop: (String) -> String?): String? {
        if (prop(OVERRIDE_ALLOWED_PROPERTY)?.trim() != "true") return null
        return (prop("ravilo.update.feed") ?: env("RAVILO_UPDATE_FEED"))?.trim()?.ifBlank { null }
    }
}
