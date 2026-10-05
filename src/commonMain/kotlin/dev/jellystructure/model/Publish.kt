package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 307 — nothing goes to a public database until someone presses Publish. What the Dashboard's *Waiting to
 * publish* panel shows: every sentence and every field label is the server's (FR-285-3's rule); the page renders.
 * Admin-only DTOs.
 */
@Serializable
data class PublishListDto(
    /** FR-307-3 — one entry per target that has anything to show: its name, its own page, what publishing means. */
    val targets: List<PublishTargetDto> = emptyList(),
    /** Waiting, in queue order (FR-307-3). */
    val waiting: List<PublishItemDto> = emptyList(),
    /** Handed to the worker, not answered yet (FR-307-4). */
    val publishing: List<PublishItemDto> = emptyList(),
    /** Tried and refused, each with its reason and *Try again* (FR-307-4). */
    val failed: List<PublishItemDto> = emptyList(),
    /** FR-307-6 — the receipt: the last 50 published. */
    val published: List<PublishItemDto> = emptyList(),
    /** FR-307-5 — *Dismissed (n)*, each with *Queue again*. */
    val dismissed: List<PublishItemDto> = emptyList(),
)

@Serializable
data class PublishTargetDto(val id: String, val name: String, val url: String, val sentence: String)

@Serializable
data class PublishItemDto(
    val id: Long,
    val target: String,
    val kind: String,
    val subject: String,
    val label: String,
    val reason: String,
    val state: String,
    /** The exact JSON body that is sent (*Show what is sent*). */
    val payload: String,
    /** The payload, field by field as it will be sent (FR-307-3). */
    val fields: List<PublishFieldDto> = emptyList(),
    @SerialName("queued_at") val queuedAt: Long,
    @SerialName("queued_by") val queuedBy: String,
    @SerialName("decided_at") val decidedAt: Long? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("sent_at") val sentAt: Long? = null,
    /** What the target replied, one line; for a failed item, the reason in one sentence. */
    val answer: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class PublishFieldDto(val label: String, val value: String)

@Serializable
data class PublishIdsRequest(val ids: List<Long> = emptyList())

@Serializable
data class PublishActionResult(val sentence: String, val count: Int = 0)
