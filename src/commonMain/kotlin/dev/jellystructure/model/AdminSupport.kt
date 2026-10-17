@file:OptIn(ExperimentalSerializationApi::class)

package dev.jellystructure.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 320 (FR-320-12/14) — the admin's view of the support lane: every viewer's tickets, `ADMIN_ONLY` lines included.
// Shared by the backend (`/api/support/**`, `JobEvent.SupportTicketChanged` on `/ws`) and a later admin page, which
// renders what is pushed and keeps no count of its own. The admin is English only; these carry facts.

/** One ticket as the admin's list shows it. [holder] is the real holder (`AI_AGENT` · `HERVOR` · `ADMIN` · `SEAT`). */
@Serializable
data class SupportAdminTicket(
    val number: Long,
    val ownerId: String,
    val ownerName: String,
    val state: String,
    val holder: String,
    val holderSeat: String? = null,
    val owes: String,
    @EncodeDefault val owedSince: Long = 0L,
    @EncodeDefault val overdue: Boolean = false,
    @SerialName("class") val ticketClass: String? = null,
    val englishSummary: String? = null,
    /** DEB-1500 (FR-13) — `AI Agent`'s case title in English (all tickets are English on our side), and in the viewer's language. Null until a turn writes one. */
    val englishTitle: String? = null,
    val title: String? = null,
    @EncodeDefault val viewerLanguage: String = "en",
    @EncodeDefault val flagged: Boolean = false,
    @EncodeDefault val preview: String = "",
    @EncodeDefault val lastActivity: Long = 0L,
    @EncodeDefault val unread: Int = 0,
)

@Serializable
data class SupportAdminMessage(
    val id: Long,
    val kind: String,
    val author: String? = null,
    val authorLabel: String? = null,
    val body: String? = null,
    val event: String? = null,
    @EncodeDefault val eventArgs: List<String> = emptyList(),
    /** `VIEWER` · `ADMIN_ONLY`. */
    val visibility: String,
    val devicePlatform: String? = null,
    @EncodeDefault val createdAt: Long = 0L,
)

@Serializable
data class SupportAdminThread(
    val ticket: SupportAdminTicket,
    @EncodeDefault val messages: List<SupportAdminMessage> = emptyList(),
    /** DEB-1500 (FR-8) — the same flag the viewer's thread carries: `AI Agent` has the ticket queued or out. */
    @EncodeDefault val aiComposing: Boolean = false,
)

@Serializable
data class SupportAdminList(@EncodeDefault val tickets: List<SupportAdminTicket> = emptyList())

/**
 * FR-320-14 — computed over unflagged tickets. DEB-1500 (FR-4) adds two admin-only fields: [laneOpen] is `[support]
 * enabled`, and [hervorEnabled] is `[support] hervor_enabled`. The tab and the sidebar count read them from here, never
 * from the config form.
 */
@Serializable
data class SupportSummary(
    @EncodeDefault val open: Int = 0,
    @EncodeDefault val owedByAdmin: Int = 0,
    @EncodeDefault val overdue: Int = 0,
    @EncodeDefault val laneOpen: Boolean = false,
    /** DEB-1508 (FR-8) — the **effective** switch: `[support] hervor_enabled` and a live seat token. */
    @EncodeDefault val hervorEnabled: Boolean = false,
    /** DEB-1508 (FR-10) — tickets Hervør owes the next message on, counted like [owedByAdmin]. Additive. */
    @EncodeDefault val owedByHervor: Int = 0,
)

/** DEB-1500 (FR-3) — `POST /api/support/enabled`. */
@Serializable data class SupportEnabledRequest(val enabled: Boolean)

/** DEB-1508 (FR-8) — `POST /api/support/hervor`. */
@Serializable data class SupportHervorRequest(val enabled: Boolean)

/**
 * DEB-1508 (FR-2) — Hervør's seat token as an admin reads it. **No hash and no id leave the server**: the plaintext is in
 * [SupportSeatTokenIssued] once, and nothing else about a token identifies it.
 */
@Serializable
data class SupportSeatTokenLive(val issuedAt: Long, val issuedBy: String, val lastUsedAt: Long? = null)

@Serializable
data class SupportSeatTokenPast(val issuedAt: Long, val issuedBy: String, val revokedAt: Long, val revokedBy: String, val lastUsedAt: Long? = null)

/** `GET` and `DELETE /api/support/seat-token`. [live] is null (written out) when there is none; [past] is the five most recent revoked. */
@Serializable
data class SupportSeatTokenState(
    @EncodeDefault val live: SupportSeatTokenLive? = null,
    @EncodeDefault val past: List<SupportSeatTokenPast> = emptyList(),
)

/** `POST /api/support/seat-token` → 201. [token] is the plaintext `jss_…`, in this response and nowhere else, ever. */
@Serializable
data class SupportSeatTokenIssued(val token: String, val live: SupportSeatTokenLive)

/**
 * DEB-1500 (FR-12) — one line of Activity › Support log. [id] orders it (it is also the `before` cursor), [action] is a
 * code (`received` · `forwarded` · `state_changed` · `classified` · `reopened` · `flagged` · `flag_removed` · `replied` ·
 * `ai_verdict`), and [args] are the facts that go with it. **No body, note, summary or title is ever here** (FR-320-16).
 */
@Serializable
data class SupportLogEntry(
    val id: Long,
    val at: Long,
    val ticket: Long,
    /** `AI_AGENT` · `HERVOR` · `ADMIN` · `VIEWER` · `SYSTEM`. */
    val actor: String,
    val actorLabel: String? = null,
    val action: String,
    @EncodeDefault val args: List<String> = emptyList(),
)

@Serializable
data class SupportLog(@EncodeDefault val entries: List<SupportLogEntry> = emptyList())

/** `POST …/forward`. [holder] is `ADMIN` · `HERVOR` · `SEAT`; [seat] names the seat when it is `SEAT`. */
@Serializable
data class SupportForwardRequest(val holder: String, val seat: String? = null, val owes: String = "ADMIN", val note: String = "")

@Serializable data class SupportStateRequest(val state: String)
@Serializable data class SupportClassRequest(@SerialName("class") val ticketClass: String)
@Serializable data class SupportFlagRequest(val flagged: Boolean)

/**
 * 321 (FR-321-10) — one request `AI Agent` made for a ticket: what was sent (the user block, exactly), what came back,
 * what became of it and what it cost. [verdict] is `SENT`, `FORWARDED: <rule>`, `FLAGGED` or `DISCARDED: <why>`, and
 * null while the batch is still out.
 */
@Serializable
data class SupportAiTurn(
    val at: Long,
    val model: String,
    val sent: String,
    val answer: String? = null,
    val verdict: String? = null,
    @EncodeDefault val inputTokens: Long = 0L,
    @EncodeDefault val outputTokens: Long = 0L,
    @EncodeDefault val costMicroUsd: Long = 0L,
)

@Serializable
data class SupportAiTranscript(val ticket: Long, @EncodeDefault val turns: List<SupportAiTurn> = emptyList())
