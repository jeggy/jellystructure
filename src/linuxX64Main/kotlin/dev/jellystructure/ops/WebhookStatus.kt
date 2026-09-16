package dev.jellystructure.ops

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 221 — what happened to notifications, remembered.
 *
 * Before this phase `fireWebhook` logged "fired" after a failure, counted nothing and remembered
 * nothing; the Settings page showed a URL field and no delivery status; and the deprecated *arr route
 * warned into a log the operator does not read. This object keeps, per webhook target, the consecutive
 * failure count, the last failure (time + reason) and the last success; and per *arr source the last
 * hit on the retired route. Small, persisted next to the database (FR-221-5) — never in `config.toml`.
 *
 * [findings] turns that into phase 212's `AdvisorFinding` shape with the silence rule: nothing while
 * deliveries succeed, nothing when no webhook is configured, nothing 30 quiet days after the last
 * deprecated hit.
 */
@Serializable
data class WebhookTargetStatus(
    val url: String,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("last_attempt_at") val lastAttemptAt: Long? = null,
    @SerialName("last_success_at") val lastSuccessAt: Long? = null,
    @SerialName("last_failure_at") val lastFailureAt: Long? = null,
    @SerialName("last_failure_reason") val lastFailureReason: String? = null,
    @SerialName("last_elapsed_ms") val lastElapsedMs: Long? = null,
)

@Serializable
data class WebhookStatusData(
    val targets: Map<String, WebhookTargetStatus> = emptyMap(),
    /** "radarr" / "sonarr" → epoch ms of the last hit on the deprecated route. */
    @SerialName("arr_last_hit") val arrLastHit: Map<String, Long> = emptyMap(),
    /** Per source, the day (epoch days) an INFO line was last written — one per day, FR-221-4. */
    @SerialName("arr_last_logged_day") val arrLastLoggedDay: Map<String, Long> = emptyMap(),
)

/** The shape phase 212's `advisorFindingHtml` renders; kept structurally identical on purpose. */
@Serializable
data class OperatorFinding(
    val id: String,
    val summary: String,
    @SerialName("current_value") val currentValue: String,
    @SerialName("cost_here") val costHere: String,
    @SerialName("navigation_path") val navigationPath: String,
    @SerialName("field_label") val fieldLabel: String,
    val recommendation: String,
    val tradeoff: String,
)

object WebhookStatus {
    const val FAILURES_TO_RAISE = 3
    const val RECENT_FAILURE_WINDOW_MS = 60 * 60_000L
    const val ARR_FINDING_WINDOW_MS = 30L * 24 * 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private var path: String? = null
    private var data = WebhookStatusData()
    /** Injectable for tests. */
    var clock: () -> Long = ::nowEpochMs

    fun init(dataDir: String) {
        path = "$dataDir/webhook-status.json"
        data = runCatching { json.decodeFromString(WebhookStatusData.serializer(), FileIo.readText(Path(path!!))) }.getOrDefault(WebhookStatusData())
    }

    /** Tests: start from nothing, persist nowhere. */
    fun resetForTests() { path = null; data = WebhookStatusData() }

    private fun persist() {
        val p = path ?: return
        runCatching { FileIo.writeText(Path(p), json.encodeToString(WebhookStatusData.serializer(), data)) }
    }

    fun recordDelivery(url: String, ok: Boolean, reason: String?, elapsedMs: Long) {
        val now = clock()
        val prev = data.targets[url] ?: WebhookTargetStatus(url)
        val next = if (ok) prev.copy(consecutiveFailures = 0, lastAttemptAt = now, lastSuccessAt = now, lastElapsedMs = elapsedMs)
                   else prev.copy(consecutiveFailures = prev.consecutiveFailures + 1, lastAttemptAt = now, lastFailureAt = now, lastFailureReason = reason, lastElapsedMs = elapsedMs)
        data = data.copy(targets = data.targets + (url to next))
        persist()
    }

    fun target(url: String): WebhookTargetStatus? = data.targets[url]

    /** Records a hit on the retired *arr route; returns true when the caller should write its one INFO line today. */
    fun recordArrHit(source: String): Boolean {
        val now = clock()
        val day = now / 86_400_000L
        val logToday = data.arrLastLoggedDay[source] != day
        data = data.copy(
            arrLastHit = data.arrLastHit + (source to now),
            arrLastLoggedDay = if (logToday) data.arrLastLoggedDay + (source to day) else data.arrLastLoggedDay,
        )
        persist()
        return logToday
    }

    fun arrLastHit(source: String): Long? = data.arrLastHit[source]

    /**
     * FR-221-3/4 — the findings, or nothing. [configuredUrl] blank ⇒ no delivery finding (unconfigured is
     * not broken). [jellyfinWebhookSince] is the Jellyfin webhook's first/last delivery for the *arr
     * finding's wording; null ⇒ it has never delivered and the recommendation says so.
     */
    fun findings(configuredUrl: String, jellyfinWebhookSince: Long?): List<OperatorFinding> {
        val out = ArrayList<OperatorFinding>()
        val now = clock()
        val url = configuredUrl.trim()
        if (url.isNotBlank()) {
            val t = data.targets[url]
            val recentFailure = t?.lastFailureAt != null && now - t.lastFailureAt < RECENT_FAILURE_WINDOW_MS && (t.lastSuccessAt == null || t.lastSuccessAt < t.lastFailureAt)
            if (t != null && (t.consecutiveFailures >= FAILURES_TO_RAISE || recentFailure)) {
                val host = url.substringAfter("://").substringBefore('/')
                out += OperatorFinding(
                    id = "webhook-failing",
                    summary = "Notifications are not being delivered",
                    currentValue = "the last ${t.consecutiveFailures} deliver${if (t.consecutiveFailures == 1) "y" else "ies"} to $host failed — last reason: ${t.lastFailureReason ?: "unknown"} (${ago(now, t.lastFailureAt)})",
                    costHere = "every scan-done, write-failed and crash-recovery notification since ${ago(now, t.lastFailureAt)} went nowhere",
                    navigationPath = "Settings → Notifications",
                    fieldLabel = "Webhook URL",
                    recommendation = "point it at something that listens, or clear it — use Test beside the field to confirm",
                    tradeoff = "clearing it turns notifications off entirely; nothing is retried",
                )
            }
        }
        for ((source, at) in data.arrLastHit) {
            if (now - at > ARR_FINDING_WINDOW_MS) continue
            val name = source.replaceFirstChar { it.uppercase() }
            val since = jellyfinWebhookSince?.let { "has been delivering since ${dateOf(it)}" } ?: "is the supported path (Settings → Download tools → Realtime ingest)"
            out += OperatorFinding(
                id = "arr-deprecated-$source",
                summary = "$name is still configured to call jellystructure's deprecated webhook",
                currentValue = "last hit ${ago(now, at)}; the Jellyfin webhook (phase 165) $since",
                costHere = "a redundant call per import that does nothing but nudge Jellyfin; the route will be retired",
                navigationPath = "$name → Settings → Connect",
                fieldLabel = "the jellystructure webhook connection",
                recommendation = "remove the connection in $name; keep the Jellyfin webhook",
                tradeoff = "none — the Jellyfin webhook already delivers what this used to",
            )
        }
        return out
    }

    private fun ago(now: Long, at: Long?): String {
        at ?: return "never"
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} day${if (s / 86_400 == 1L) "" else "s"} ago"
        }
    }

    private fun dateOf(ms: Long): String {
        val days = ms / 86_400_000L
        // civil-from-days (Howard Hinnant), enough for a date in a sentence
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val yy = if (m <= 2) y + 1 else y
        return "$yy-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowEpochMs(): Long = platform.posix.time(null) * 1000L
