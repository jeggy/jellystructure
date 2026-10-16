@file:OptIn(ExperimentalSerializationApi::class)

package dev.jellystructure.shared.tv

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 320 (FR-320-18) — the viewer's half of the support lane. New paths (`/api/tv/support/**`), new types and one new
// event name only; nothing existing changes shape. Prefixed `Support` because "ticket" already means a stream
// ticket in this codebase. The value sets below are Strings, not enums, on purpose: an installed app decodes an
// enum strictly, and a value a later phase adds must never fail an old app.

/**
 * 320 (FR-320-10) — the `features=` value a socket sends to receive `support_changed`. An installed app reads any
 * unknown event as "the config changed" and re-fetches its config, so the event goes only to a socket that asked.
 */
const val EVENTS_FEATURE_SUPPORT = "support"

/** The value sets the viewer wire carries (FR-320-2, FR-320-8, FR-320-15). Documentation as constants; never exhaustive. */
object SupportWire {
    const val STATE_OPEN = "OPEN"
    const val STATE_IN_PROGRESS = "IN_PROGRESS"
    const val STATE_RESOLVED = "RESOLVED"

    /** What a viewer sees as the holder: never a seat's name (FR-320-8) — a seat that may not answer is `TEAM`. */
    const val HOLDER_AI_AGENT = "AI_AGENT"
    const val HOLDER_HERVOR = "HERVOR"
    const val HOLDER_ADMIN = "ADMIN"
    const val HOLDER_TEAM = "TEAM"

    const val KIND_MESSAGE = "MESSAGE"
    const val KIND_SYSTEM = "SYSTEM"
    const val KIND_SAME_HERE = "SAME_HERE"

    const val AUTHOR_VIEWER = "VIEWER"
    const val AUTHOR_AI_AGENT = "AI_AGENT"
    const val AUTHOR_HERVOR = "HERVOR"
    const val AUTHOR_ADMIN = "ADMIN"

    /** A `SYSTEM` entry is a code and arguments, never a sentence (FR-320-15): the client words it itself. */
    const val EVENT_RECEIVED = "RECEIVED"
    const val EVENT_FORWARDED = "FORWARDED"
    const val EVENT_STATE_CHANGED = "STATE_CHANGED"
    const val EVENT_CLASSIFIED = "CLASSIFIED"
    const val EVENT_REOPENED = "REOPENED"
}

/** One ticket in the viewer's list, and the head of a [SupportThread]. [number] is the `#41` the viewer sees. */
@Serializable
data class SupportTicketRow(
    val number: Long,
    val state: String,
    val holder: String,
    /** `BUG` · `REQUEST` · `QUESTION` · `ACCOUNT` · `OTHER`, or null while unclassified. */
    @SerialName("class") val ticketClass: String? = null,
    /**
     * DEB-1500 (FR-13) — the case title `AI Agent` wrote in the viewer's own language, or null (omitted) while there is none.
     * Additive: an installed app ignores it, and a client reading an older server sees it absent (FR-320-18).
     */
    val title: String? = null,
    /** The first message's first 140 characters. */
    @EncodeDefault val preview: String = "",
    /** Epoch seconds. */
    @EncodeDefault val lastActivity: Long = 0L,
    @EncodeDefault val unread: Int = 0,
)

@Serializable
data class SupportTicketList(@EncodeDefault val tickets: List<SupportTicketRow> = emptyList())

/** One entry the viewer may see. `ADMIN_ONLY` entries and every internal field never reach this type. */
@Serializable
data class SupportMessageView(
    val id: Long,
    val kind: String,
    /** Null for `SYSTEM`. */
    val author: String? = null,
    /** An admin's Jellyfin user name, `Hervør`, or the literal `AI Agent`. */
    val authorLabel: String? = null,
    val body: String? = null,
    val event: String? = null,
    @EncodeDefault val eventArgs: List<String> = emptyList(),
    @EncodeDefault val createdAt: Long = 0L,
)

@Serializable
data class SupportThread(
    val ticket: SupportTicketRow,
    @EncodeDefault val messages: List<SupportMessageView> = emptyList(),
    /**
     * 321 (FR-321-3) — `AI Agent` has this ticket queued or in a batch that is out: the server state the working
     * indicator draws. Additive, and `false` when nothing is composing, so an installed app decodes it unharmed.
     */
    @EncodeDefault val aiComposing: Boolean = false,
)

/** `POST /api/tv/support/tickets` and `POST …/{n}/messages`. */
@Serializable
data class SupportPostRequest(val body: String)

/** `POST …/{n}/read` — moves the viewer's marker forward, never back. */
@Serializable
data class SupportReadRequest(val upTo: Long)

/** `GET /api/tv/support/unread` — the number of tickets with something unread, for the nav marker. */
@Serializable
data class SupportUnread(@EncodeDefault val tickets: Int = 0)

/** `{"type":"support_changed","ticket":41}` on `/api/tv/events`, only to a socket opened with `features=support`. */
@Serializable
data class SupportChangedEnvelope(
    @EncodeDefault val type: String = "support_changed",
    @EncodeDefault val ticket: Long = 0L,
)

/**
 * DEB-1501 (FR-5) — the server's answer to a send, once it answered at all. [Sent] carries the thread the 200 or 201
 * returned; [Refused] is any other status (400 too long, 404 gone or the lane closed, 429 the daily limit). No answer
 * at all is not a value here: [TvApiClient] lets that exception through, and the caller says "offline".
 */
sealed class SupportSendResult {
    data class Sent(val thread: SupportThread) : SupportSendResult()
    data class Refused(val status: Int) : SupportSendResult()
}
