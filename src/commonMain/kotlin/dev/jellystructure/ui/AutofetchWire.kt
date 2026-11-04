package dev.jellystructure.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DEB-2477 (DEB-2474, FR-1, FR-9, FR-10, FR-12) — what `GET /api/autofetch` answers, as the admin reads it. Mirrors
 * `autofetch/Runtime.kt`'s views field for field; the defaults match the server's, because the server leaves a default-valued
 * field out of the body. In `commonMain` for the reason `EpisodeOrderHtml.kt` gives: the admin frontend has no test target of its own.
 */

@Serializable
data class AfRetention(
    val mode: String = "all",
    val keep: Int = 12,
    @SerialName("leaves_page") val leavesPage: Boolean = false,
) {
    val newest: Boolean get() = mode == "newest"
}

@Serializable
data class AfRunLine(val kind: String = "", val text: String = "")

@Serializable
data class AfRun(
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val trigger: String = "",
    val status: String = "",
    val summary: String = "",
    val lines: List<AfRunLine> = emptyList(),
)

@Serializable
data class AfRecipeLine(val key: String = "", val text: String = "", val comment: String = "")

@Serializable
data class AfFetcher(
    val id: String,
    val name: String = "",
    val pageUrl: String = "",
    val site: String = "",
    val tmdbId: Int? = null,
    val pageOnly: Boolean = false,
    val proven: Boolean = false,
    val schedule: String = "",
    val enabled: Boolean = true,
    val retention: AfRetention = AfRetention(),
    /** `ok` · `needs_reinvestigation` · `refused`. */
    val state: String = "ok",
    val stateReason: String = "",
    val siteStopped: Boolean = false,
    val siteStoppedReason: String? = null,
    val siteStoppedAt: Long? = null,
    val numbering: String = "",
    val running: Boolean = false,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastRun: AfRun? = null,
    val placed: Int = 0,
    val runs: List<AfRun> = emptyList(),
    val recipe: List<AfRecipeLine> = emptyList(),
    val pattern: String = "",
    val quality: String = "",
    val queued: Int = 0,
    /** FR-4.3, FR-10.2 — the items waiting for a number the admin approves. */
    val waiting: List<AfWaiting> = emptyList(),
    /** DEB-3592 (FR-2.8, FR-10.7) — this fetcher's requests ignore the site's `robots.txt`. */
    val ignoreRobots: Boolean = false,
)

/** An item the page lists that is not downloaded until it has a number: the page's own words, and the model's proposal when asked for. */
@Serializable
data class AfWaiting(
    val itemId: String = "",
    val title: String? = null,
    val link: String = "",
    val published: String? = null,
    val description: String? = null,
    val why: String = "",
    val proposal: AfProposal? = null,
)

@Serializable
data class AfProposal(val season: Int = 0, val episode: Int = 0, val code: String = "", val note: String = "")

@Serializable
data class AfSite(
    val site: String,
    val stopped: Boolean = false,
    val reason: String? = null,
    val stoppedAt: Long? = null,
    val backoffUntil: Long? = null,
    val fetchers: Int = 0,
    /** DEB-3613 (FR-3.6, FR-10.8) — the site has the admin's own session: when it was saved, and when its first cookie runs out. Never the session. */
    val session: AfSession? = null,
)

@Serializable
data class AfSession(val set: Boolean = true, val savedAt: Long = 0, val expiresAt: Long? = null)

/** One row of *Not on TMDB yet*. [title] and [overview] are the page's own words, verbatim, or null when the page has none (FR-8.2). */
@Serializable
data class AfQueueEntry(
    val id: Long,
    val fetcherId: String = "",
    val series: String = "",
    val tmdbId: Int? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val code: String = "",
    val pageLabel: String = "",
    /** `queued` · `submitted`. */
    val state: String = "queued",
    val filedAt: Long = 0,
    val submittedAt: Long? = null,
    val runtimeSec: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val stills: List<Int> = emptyList(),
)

/** DEB-2478 (FR-1.2) — whether *Investigate* is on, and the reason it is shown disabled with. */
@Serializable
data class AfGate(val allowed: Boolean = false, val reason: String? = null)

/** One of the investigation's five steps. [state] is `pending` · `run` · `done` · `fail`; [text] is what this server saw, never the model's words. */
@Serializable
data class AfStep(val key: String = "", val label: String = "", val state: String = "pending", val text: String = "", val ms: Long? = null)

@Serializable
data class AfReviewRow(
    val title: String = "", val code: String = "", val date: String? = null, val have: Boolean = false, val notOnTmdb: Boolean = false,
    /** FR-4.3 — a `mapped` review's row: the id a tick sends, the TMDB episode's name and the model's reason. [code] is blank for an item the model left out. */
    val itemId: String = "", val tmdbName: String? = null, val why: String? = null,
)

@Serializable
data class AfLibrary(val path: String = "", val name: String = "")

/** FR-10.1 — the review state: what the engine proved, in the server's counts and words. */
@Serializable
data class AfReview(
    val name: String = "",
    val tmdbId: Int? = null,
    val pageOnly: Boolean = false,
    val source: String = "",
    val found: Int = 0,
    val missing: Int = 0,
    val have: Int = 0,
    val notOnTmdb: Int = 0,
    val pattern: String = "",
    val numbering: String = "",
    val proof: String = "",
    val proven: Boolean = false,
    val rows: List<AfReviewRow> = emptyList(),
    val libraries: List<AfLibrary> = emptyList(),
    val libraryPath: String = "",
    val retention: AfRetention = AfRetention(),
    val schedule: String = "daily@04:00",
    val replaces: String? = null,
)

/** FR-10.4 (DEB-3569) — the bug report the server wrote for a failed investigation: the issue's title and body, already redacted. */
@Serializable
data class AfReport(val title: String = "", val body: String = "")

/** `running` · `review` · `failed` · `stopped`. */
@Serializable
data class AfInvestigation(
    val id: String = "",
    val state: String = "running",
    val url: String = "",
    val host: String = "",
    val tmdbId: Int? = null,
    val pageOnly: Boolean = false,
    val fetcherId: String? = null,
    /** DEB-3592 (FR-2.8) — the investigation ignores this site's `robots.txt`. */
    val ignoreRobots: Boolean = false,
    val steps: List<AfStep> = emptyList(),
    val reason: String? = null,
    /** DEB-3592 (FR-10.7) — a `robots.txt` refusal ended it: the server's call, never read from [reason]'s words. */
    val robotsRefused: Boolean = false,
    val notASeries: Boolean = false,
    /** DEB-3613 (FR-3.6, FR-10.8) — the site plays only to a signed-in viewer: the server's call, never read from [reason]'s words. */
    val needsSignIn: Boolean = false,
    /** The site a pasted session is saved for (FR-2's lane key). */
    val site: String = "",
    val limitReached: Boolean = false,
    val turns: Int = 0,
    val requests: Int = 0,
    val spentMicroUsd: Long = 0,
    val startedAt: Long = 0,
    val review: AfReview? = null,
    /** FR-10.4 — set on a `failed` investigation only; a stopped one has none, and the card draws no link. */
    val report: AfReport? = null,
)

@Serializable
data class AfOverview(
    val enabled: Boolean = false,
    val fetchers: List<AfFetcher> = emptyList(),
    val sites: List<AfSite> = emptyList(),
    val queue: List<AfQueueEntry> = emptyList(),
    val investigate: AfGate = AfGate(),
    val investigation: AfInvestigation? = null,
)

/** `POST …/retention/preview`: what saving that retention would do at the next run, counted by the server (FR-12, FR-16.1). */
@Serializable
data class AfPreview(val deletes: Int = 0, val keeps: Int = 0, val onPage: Int = 0)
