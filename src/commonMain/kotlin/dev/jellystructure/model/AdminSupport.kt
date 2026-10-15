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
)

@Serializable
data class SupportAdminList(@EncodeDefault val tickets: List<SupportAdminTicket> = emptyList())

/** FR-320-14 — computed over unflagged tickets. */
@Serializable
data class SupportSummary(
    @EncodeDefault val open: Int = 0,
    @EncodeDefault val owedByAdmin: Int = 0,
    @EncodeDefault val overdue: Int = 0,
)

/** `POST …/forward`. [holder] is `ADMIN` · `HERVOR` · `SEAT`; [seat] names the seat when it is `SEAT`. */
@Serializable
data class SupportForwardRequest(val holder: String, val seat: String? = null, val owes: String = "ADMIN", val note: String = "")

@Serializable data class SupportStateRequest(val state: String)
@Serializable data class SupportClassRequest(@SerialName("class") val ticketClass: String)
@Serializable data class SupportFlagRequest(val flagged: Boolean)
