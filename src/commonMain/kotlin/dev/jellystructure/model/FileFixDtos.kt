package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Phase 314 — a file every device can play directly: what the admin card and the list read. */
@Serializable
data class FileFixKindSummary(
    /** `a` · `b` · `c` (FixKind.id). */
    val kind: String,
    val label: String,
    /** One sentence: what the kind adds and for whom. */
    val sentence: String,
    val enabled: Boolean,
    @SerialName("auto_new") val autoNew: Boolean,
    /** False when the tool the kind needs is missing (kind C without dovi_tool): the card says *unavailable*. */
    val available: Boolean = true,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    /** Rows per state: would_add · sidecar · waiting · pending · running · done · failed · skipped. */
    val counts: Map<String, Int> = emptyMap(),
    /** Bytes the eligible rows would add (would_add + sidecar), for the disk line. */
    @SerialName("eligible_bytes") val eligibleBytes: Long = 0,
)

@Serializable
data class FileFixPlanStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class FileFixOverview(
    val kinds: List<FileFixKindSummary>,
    val plan: FileFixPlanStatus,
    /** The nightly read cap (bytes) and what was read in the last 24 h (FR-314-10). */
    @SerialName("read_cap_bytes") val readCapBytes: Long,
    @SerialName("read_last_day_bytes") val readLastDayBytes: Long,
)

@Serializable
data class FileFixRow(
    val path: String,
    @SerialName("media_id") val mediaId: String,
    val label: String,
    val state: String,
    val detail: String,
    @SerialName("est_bytes") val estBytes: Long,
)

@Serializable
data class FileFixSettingRequest(val enabled: Boolean, @SerialName("auto_new") val autoNew: Boolean = false)
