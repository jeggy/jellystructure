package dev.jellystructure.support

import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.SupportSeatTokenIssued
import dev.jellystructure.model.SupportSeatTokenLive
import dev.jellystructure.model.SupportSeatTokenPast
import dev.jellystructure.model.SupportSeatTokenState

/**
 * DEB-1508 (FR-1, FR-2) — Hervør's support seat token. One live row per seat; only `sha256Hex` of the `jss_` plaintext is
 * ever stored, and a revoked row stays as the audit record. Built like Phase 111's `ApiKeyStore`, **without its cache**:
 * there is one token, the lookup is an indexed read, and a cache is exactly how a revoke would outlive its click (FR-2:
 * revocation takes effect on the next request).
 *
 * The plaintext, its hash and the row id never reach a log line or a wire other than the one response that issues it.
 */
class SupportSeatTokens(private val db: JellystructureDb, private val clock: () -> Long) {
    private val q get() = db.supportQueries

    /** Whether Hervør has a token that works. The effective switch (FR-8) reads this on every ticket decision. */
    val hasLive: Boolean get() = q.liveSeatToken(SEAT_HERVOR).executeAsOneOrNull() != null

    fun state(): SupportSeatTokenState = SupportSeatTokenState(
        live = q.liveSeatToken(SEAT_HERVOR).executeAsOneOrNull()?.let { SupportSeatTokenLive(it.created_at, it.created_by, it.last_used_at) },
        past = q.pastSeatTokens(SEAT_HERVOR).executeAsList().map {
            SupportSeatTokenPast(it.created_at, it.created_by, it.revoked_at ?: 0L, it.revoked_by.orEmpty(), it.last_used_at)
        },
    )

    /** FR-2 — issues a token; a live one is revoked in the same transaction (`SYSTEM`). Returns the plaintext, once. */
    fun issue(by: String): SupportSeatTokenIssued {
        val plaintext = PREFIX + generateSecureToken()
        val t = clock()
        db.transaction {
            q.liveSeatToken(SEAT_HERVOR).executeAsOneOrNull()?.let { q.revokeSeatToken(t, REVOKED_BY_REPLACEMENT, it.id) }
            q.insertSeatToken(generateSecureToken(), SEAT_HERVOR, sha256Hex(plaintext), t, by)
        }
        return SupportSeatTokenIssued(plaintext, state().live!!)
    }

    /** FR-2 — revokes the live token. False when there was none. */
    fun revoke(by: String): Boolean {
        val live = q.liveSeatToken(SEAT_HERVOR).executeAsOneOrNull() ?: return false
        q.revokeSeatToken(clock(), by, live.id)
        return true
    }

    /**
     * The id of the live token [plaintext] is, or null when it is unknown or revoked. `last_used_at` moves at most once a
     * minute, whatever the switch says: a seat that polls while she is off still shows as used.
     */
    fun validate(plaintext: String): String? {
        val row = q.liveSeatTokenByHash(sha256Hex(plaintext)).executeAsOneOrNull() ?: return null
        val t = clock()
        q.touchSeatToken(t, row.id, t - TOUCH_EVERY)
        return row.id
    }

    companion object {
        const val PREFIX = "jss_"
        const val SEAT_HERVOR = "HERVOR"
        const val REVOKED_BY_REPLACEMENT = "SYSTEM"
        const val TOUCH_EVERY = 60L
    }
}
