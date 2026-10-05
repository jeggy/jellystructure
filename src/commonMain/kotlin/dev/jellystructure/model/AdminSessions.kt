package dev.jellystructure.model

import kotlinx.serialization.Serializable

// 304 (dev review item 2) — the admin's view of playback sessions: every owner, every network, no visibility filter
// (owner decision 2). Shared by the backend (`GET /api/tv/admin/playback/sessions`, `JobEvent.PlaybackSessions` on
// `/ws`) and the admin page; the admin is English only (FR-304-6), so these carry facts and the page words them.

/** One timeline entry (FR-304-3): `started` · `paused` · `resumed` · `reconnected` · `offline` · `moved` · `room_added`
 *  · `room_removed` · `command` · `ended`. [source] is a device's name, `admin`, or null (the server itself). */
@Serializable
data class AdminSessionEvent(
    val at: Long,
    val what: String,
    val source: String? = null,
    val detail: String? = null,
)

@Serializable
data class AdminSessionRow(
    val id: String,
    val revision: Long,
    val ownerId: String,
    val ownerName: String,
    /** `film` · `episode` · `music` · `audiobook` */
    val kind: String,
    val title: String? = null,
    val subtitle: String? = null,
    val artwork: String? = null,
    val targetKind: String,
    val targetId: String,
    val targetName: String,
    /** `phone · computer · tv · display · speaker · group` */
    val targetIcon: String,
    val state: String,
    val positionMs: Long = 0L,
    val positionAt: Long = 0L,
    val durationMs: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val endedBy: String? = null,
    /** FR-304-1 / review item 9 — *started from {app}* until R369's controllers exist. */
    val startedFrom: String? = null,
    /** R369 — the apps attached to this session as controllers (device names). */
    val controllers: List<String> = emptyList(),
    val reconnecting: Boolean = false,
    val offline: Boolean = false,
    /** R371 — the rooms, in the order they joined (names). */
    val rooms: List<String> = emptyList(),
    /** R369 — the commands the target obeys (`play`, `pause`, `seek`, `next` …); empty = no remote yet. */
    val ops: List<String> = emptyList(),
    val events: List<AdminSessionEvent> = emptyList(),
    /** 308 (FR-308-5) — the video variant the player last reported playing: its bandwidth (bits/s), picture height and
     *  how many times it stepped down and up; null on a stream with one variant (direct play, a non-adaptive player). */
    val variantBps: Long? = null,
    val variantHeight: Int? = null,
    val variantStepsDown: Int = 0,
    val variantStepsUp: Int = 0,
)

@Serializable
data class AdminSessionList(
    val sessions: List<AdminSessionRow> = emptyList(),
    val serverNowMs: Long = 0L,
    /** 304b (FR-304-4) — *Household members can control each other's playing*; null before 304b. */
    val householdControl: Boolean? = null,
)
