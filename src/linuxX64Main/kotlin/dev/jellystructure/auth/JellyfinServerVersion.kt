package dev.jellystructure.auth

import dev.jellystructure.nowEpochSec
import dev.jellystructure.ops.SpinLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 243 (FR-243-2) — the version of the Jellyfin this installation is actually talking to.
 *
 * Before this phase nothing in the product read it. `GET /System/Info/Public` has always returned
 * `Version`, and [JellyfinClient.testConnection] has always fetched that endpoint and thrown the body
 * away, keeping only the status code. When the household server went 10.11.11 → 12.1.0, three
 * route-level behaviours changed at once (`/socket` stopped accepting `api_key`, `X-Emby-Token`
 * stopped authenticating, `/api-docs/openapi.json` started answering 500) and nothing announced it,
 * because nothing was watching.
 *
 * **Read from the PUBLIC probe, not the authenticated `GET /System/Info`** (dev review of 243, item 1):
 * the failure mode this whole cluster exists for is the credential form changing, and on such a server
 * the authenticated read returns null — so the version, the one fact that would explain everything,
 * would be the field that goes missing exactly when it is needed. The public probe needs no credential
 * and is already on the hot path.
 *
 * Latched last-observed rather than fetched per read: `/api/health` is hit every 30 s by the
 * container's own `HEALTHCHECK` (`Dockerfile`), and that endpoint may not make an outbound call.
 * Whoever calls [JellyfinClient.testConnection] (`/health/full`, the Settings connection test)
 * refreshes it.
 *
 * ## FR-243-4 — this value may never choose a request shape
 *
 * [isBelowFloor] exists so a below-floor server can be *reported*: a failing `/health/full` check and
 * an advisor finding. It may not select a header form, an endpoint or a payload. Where a newer
 * Jellyfin changes a contract the product moves; it does not carry both.
 * `scripts/check-jellyfin-version-use.sh` enforces that mechanically by allow-listing the files
 * allowed to mention this object at all.
 */
object JellyfinServerVersion {
    /** FR-243-1 — jellystructure supports Jellyfin 12.0 and later. Compared on the major only, which
     *  is what makes the 12.0-vs-12.1 question academic (dev review item 3). */
    const val FLOOR_MAJOR = 12

    private val lock = SpinLock()
    private var version: String? = null
    private var observedAt: Long = 0

    /** The last version string `/System/Info/Public` reported, or null if it has never answered. */
    fun current(): String? = lock.withLock { version }

    /** Epoch seconds of that observation, or 0 when there has never been one. */
    fun observedAtEpochSec(): Long = lock.withLock { observedAt }

    fun record(raw: String?) {
        val v = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
        lock.withLock {
            version = v
            observedAt = nowEpochSec()
        }
    }

    /** Test seam — the unit tests need a known starting point, and nothing in production clears this. */
    fun resetForTest() = lock.withLock { version = null; observedAt = 0 }

    /** The leading integer of a version string, or null when it does not start with one. */
    fun majorOf(v: String?): Int? = v?.trimStart()?.takeWhile { it.isDigit() }?.toIntOrNull()

    /**
     * FR-243-3 — true only when a version has actually been observed AND its major is below the floor.
     * An unobserved or unparseable version is NOT below the floor: "we don't know" must never render
     * as "your server is too old".
     */
    fun isBelowFloor(v: String? = current()): Boolean {
        val major = majorOf(v) ?: return false
        return major < FLOOR_MAJOR
    }

    /** The sentence both reporting sites use, so they cannot disagree about the numbers. */
    fun belowFloorDetail(v: String? = current()): String =
        "Jellyfin ${v ?: "unknown"} — jellystructure targets $FLOOR_MAJOR.0 and later; " +
            "behaviour against older servers is undefined, not supported"
}

/**
 * Phase 243 — `GET /System/Info/Public`. Unauthenticated, and the only place the version is read.
 * Every other field the endpoint returns is deliberately absent: this type exists for one fact.
 */
@Serializable
data class JellyfinSystemInfoPublic(
    @SerialName("Version") val version: String? = null,
)
