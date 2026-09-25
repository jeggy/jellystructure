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
 * deliveries succeed, nothing when no webhook is configured, nothing 7 quiet days after the last
 * deprecated hit (30 until the 2026-09-25 amendment — a week is long enough to see that the *arr
 * connection is gone, and the operator has no way to dismiss the note sooner).
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
    const val ARR_FINDING_WINDOW_MS = 7L * 24 * 3_600_000L

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
     * not broken). [jellyfinWebhookLastAt] is the Jellyfin webhook's LAST delivery, in epoch **ms**
     * (the ingest service keeps seconds — convert at the call site). It is held in memory only, so null
     * means "not since jellystructure last started", not "never"; the wording says exactly that.
     */
    fun findings(configuredUrl: String, jellyfinWebhookLastAt: Long?): List<OperatorFinding> {
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
            val quietFor = now - at
            if (quietFor > ARR_FINDING_WINDOW_MS) continue
            val name = source.replaceFirstChar { it.uppercase() }
            val jellyfin = jellyfinWebhookLastAt?.let { "Jellyfin's webhook last delivered ${ago(now, it)}" }
                ?: "Jellyfin's webhook has not delivered since jellystructure last started"
            out += OperatorFinding(
                id = "arr-deprecated-$source",
                summary = "$name is still calling jellystructure's old import webhook",
                currentValue = "last call ${ago(now, at)}. This note clears itself ${ARR_FINDING_WINDOW_MS / 86_400_000L} days " +
                    "after the last call, so ${until(ARR_FINDING_WINDOW_MS - quietFor)} if $name stops calling. $jellyfin.",
                costHere = "one extra request per import that only asks Jellyfin to look for the file sooner; a later release removes this route",
                navigationPath = "$name → Settings → Connect",
                fieldLabel = "the Webhook connection whose URL ends in /api/webhooks/$source",
                recommendation = "delete that connection; leave Jellyfin's webhook as it is",
                tradeoff = if (jellyfinWebhookLastAt != null) "nothing — Jellyfin's webhook already brings each import in"
                           else "nothing, as long as Jellyfin's webhook works — check it with Test delivery now under Settings → Download tools → Realtime ingest",
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

    /** How long until [ms] from now, rounded UP so "in 0 days" never appears while the note still shows. */
    private fun until(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(1)
        return when {
            s < 3600 -> "within the hour"
            s < 86_400 -> "in ${(s + 3599) / 3600} h"
            else -> "in ${(s + 86_399) / 86_400} day${if ((s + 86_399) / 86_400 == 1L) "" else "s"}"
        }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowEpochMs(): Long = platform.posix.time(null) * 1000L
