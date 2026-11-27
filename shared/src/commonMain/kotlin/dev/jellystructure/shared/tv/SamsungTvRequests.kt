package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DEB-4842 (FR-2/3) — additive Samsung TV request contract. State is open for future server values. */
@Serializable
data class SamsungTvRequestCreate(
    val duid: String,
    val model: String,
    @SerialName("tizen_version") val tizenVersion: String,
    val name: String,
)

@Serializable
data class SamsungTvRequest(
    val id: Long,
    val duid: String,
    val model: String,
    @SerialName("tizen_version") val tizenVersion: String,
    val name: String,
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("decided_at") val decidedAt: Long? = null,
    @SerialName("decision_note") val decisionNote: String? = null,
    @SerialName("ready_from_release") val readyFromRelease: String? = null,
    @SerialName("wgt_url") val wgtUrl: String? = null,
)

@Serializable
data class SamsungTvRequestList(val requests: List<SamsungTvRequest>)

@Serializable
data class SamsungTvAdminRequest(
    val id: Long,
    val duid: String,
    val model: String,
    @SerialName("tizen_version") val tizenVersion: String,
    val name: String,
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("decided_at") val decidedAt: Long? = null,
    @SerialName("decision_note") val decisionNote: String? = null,
    @SerialName("ready_from_release") val readyFromRelease: String? = null,
    @SerialName("wgt_url") val wgtUrl: String? = null,
    @SerialName("requester_name") val requesterName: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("decided_by") val decidedBy: String? = null,
)

@Serializable
data class SamsungTvDecision(val decision: String, val note: String? = null)

@Serializable
data class SamsungTvReleaseStatus(
    val tag: String? = null,
    @SerialName("checked_at") val checkedAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class SamsungTvCertificateStatus(
    @SerialName("author_expires_on") val authorExpiresOn: String,
    @SerialName("author_days_left") val authorDaysLeft: Int,
    @SerialName("distributor_expires_on") val distributorExpiresOn: String,
    @SerialName("distributor_days_left") val distributorDaysLeft: Int,
)

@Serializable
data class SamsungTvAdminList(
    val requests: List<SamsungTvAdminRequest>,
    val release: SamsungTvReleaseStatus,
    val certificates: SamsungTvCertificateStatus,
    /** DEB-5909 (FR-10) — rows in `REQUESTED`, the menu badge. Additive. */
    val requested: Int = 0,
)
