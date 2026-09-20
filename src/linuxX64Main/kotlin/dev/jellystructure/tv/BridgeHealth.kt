package dev.jellystructure.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 238 (FR-238-3) — what `/api/health/full` says about one TV's Jellyfin session bridge.
 *
 * Lives on the **authenticated** endpoint, not `/api/health` (dev review item 4): a per-device list of
 * ids plus failure state on the unauthenticated one would be world-readable, which on this household's
 * internet-facing server means exactly that. `/api/health` carries counts only.
 */
@Serializable
data class BridgeHealth(
    @SerialName("device_id") val deviceId: String,
    val connected: Boolean,
    @SerialName("consecutive_failures") val consecutiveFailures: Int,
    /**
     * A **classified** reason, never a raw exception message (dev review item 4b). Curl and Ktor
     * failure text routinely carries the request URL, and before this phase that URL was the one with
     * `api_key=<token>` in it — so echoing `e.message` onto a health endpoint would have published the
     * household's Jellyfin token. See [BridgeFailure].
     */
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_connected_ms_ago") val lastConnectedMsAgo: Long? = null,
    /**
     * FR-238-6 (dev review item 6) — a bridge that has never even attempted a handshake, because
     * `runLoop`'s guard found no Jellyfin URL or no device token. That branch throws nothing, so it
     * logged nothing at all — not even the first line — and in a plain connected/failed model it reads
     * as `connected: false, last_error: null`, indistinguishable from a handshake being refused.
     */
    @SerialName("never_attempted") val neverAttempted: Boolean = false,
)

/**
 * Phase 238 — the closed set of reasons a bridge is not connected. Classified at the throw site so
 * nothing downstream has to parse an exception message, and so no message can ever carry a credential.
 */
object BridgeFailure {
    const val CONNECT_FAILED = "connect failed"
    const val NO_JELLYFIN_URL = "no Jellyfin URL configured"
    const val NO_DEVICE_TOKEN = "no token stored for this device"

    /**
     * Map a thrown failure onto one of the reasons above **plus an HTTP status when the exception
     * carries one**, without letting any of the exception's own text through.
     *
     * A 403 is what Jellyfin 12.1 answers to `/socket?api_key=…`, which is the whole subject of this
     * phase: it must be legible on the health endpoint as "403 at handshake", not as a truncated curl
     * error that happens to contain the token.
     */
    fun classify(e: Throwable): String {
        val status = statusIn(e.message)
        return when {
            status != null -> "$status at handshake"
            // Curl's own transport failures. Matched on the exception TYPE where possible; the message
            // is inspected only for a small allow-list of words and is never echoed.
            looksLikeTimeout(e) -> "$CONNECT_FAILED (timeout)"
            looksLikeRefused(e) -> "$CONNECT_FAILED (refused)"
            looksLikeDns(e) -> "$CONNECT_FAILED (host not found)"
            else -> CONNECT_FAILED
        }
    }

    /**
     * The HTTP status a failure message names, when there is one. Digits only — no text escapes.
     *
     * **101 is skipped deliberately.** Ktor's own upgrade failure reads "Expected status code 101 but
     * was 403": the first status in that sentence is the one that did *not* happen. 101 is the success
     * code for a WebSocket upgrade, so it can never be the reason a handshake failed, and a message
     * naming nothing else carries no status at all.
     */
    internal fun statusIn(message: String?): Int? {
        val m = message ?: return null
        var i = 0
        while (i + 2 < m.length) {
            if (m[i].isDigit() && m[i + 1].isDigit() && m[i + 2].isDigit()) {
                val before = if (i == 0) ' ' else m[i - 1]
                val after = if (i + 3 >= m.length) ' ' else m[i + 3]
                if (!before.isDigit() && !after.isDigit()) {
                    val v = m.substring(i, i + 3).toInt()
                    if (v in 100..599 && v != 101) return v
                    i += 2
                }
            }
            i++
        }
        return null
    }

    private fun looksLikeTimeout(e: Throwable): Boolean =
        contains(e, "timeout") || contains(e, "timed out")

    private fun looksLikeRefused(e: Throwable): Boolean =
        contains(e, "refused") || contains(e, "ECONNREFUSED")

    private fun looksLikeDns(e: Throwable): Boolean =
        contains(e, "could not resolve") || contains(e, "not known") || contains(e, "no such host")

    private fun contains(e: Throwable, needle: String): Boolean =
        e.message?.contains(needle, ignoreCase = true) == true ||
            e::class.simpleName?.contains(needle, ignoreCase = true) == true
}
