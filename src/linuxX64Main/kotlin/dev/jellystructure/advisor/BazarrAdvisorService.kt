package dev.jellystructure.advisor

import dev.jellystructure.bazarr.BazarrClient
import dev.jellystructure.bazarr.SubtitleHook
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/** Phase 273 (FR-273-18) — the Bazarr advisor's answer: its findings, and whether Bazarr could be read at all. */
@Serializable
data class BazarrAdvisorResponse(
    val configured: Boolean,
    val reachable: Boolean,
    val findings: List<AdvisorFinding> = emptyList(),
    /** The hook command this install would put in Bazarr (null until an address and secret exist). */
    @SerialName("hook_command") val hookCommand: String? = null,
    /** When Bazarr last called the hook (epoch ms), for *Waiting for Bazarr's first call* / *Last called …*. */
    @SerialName("hook_last_called_at") val hookLastCalledAt: Long? = null,
)

/**
 * Phase 273 (§E) — which Bazarr settings work against subtitles fitting their video, in phase 212's finding shape:
 * a finding renders only when Bazarr's live value differs from the recommendation, each carries this library's own
 * numbers, Bazarr's exact labels (read from its v1.6.1 frontend bundle) and the page they are on, and a Bazarr that
 * cannot answer is *Couldn't reach Bazarr*, never an empty list. Bazarr's settings carry every secret it holds, so
 * the raw response never leaves this class.
 *
 * *Apply in Bazarr* (FR-273-19) posts only the finding's own keys ([fieldsFor]).
 */
class BazarrAdvisorService(
    private val configStore: ConfigStore,
    private val client: BazarrClient,
    private val db: JellystructureDb,
    private val store: MediaStore,
) {
    private val cacheTtlSec = 300L
    private var cache: Pair<Long, BazarrAdvisorResponse>? = null

    companion object {
        const val APPLY = "apply_bazarr"
        const val HOOK = "bazarr_hook"
        const val HOOK_THRESHOLD = "bazarr_hook_threshold"
        const val SYNC = "bazarr_sync"
        const val MAX_OFFSET = "bazarr_max_offset"
        const val FRAMERATE = "bazarr_framerate"
        const val UPGRADE = "bazarr_upgrade"
        const val MIN_SCORE = "bazarr_min_score"
        const val TITLE_SEARCH = "bazarr_title_search"
        const val PROCESSING = "Bazarr → Settings → Subtitles → Processing"
        const val SEARCH = "Bazarr → Settings → Subtitles → Search"
        /** Any subtitle a provider returns for the episode's number scores at least this (series 180 + year 90 +
         *  season 30 + episode 30 of 360); a film at least 75% (title 60 + year 30 of 120). */
        const val EPISODE_FLOOR_PCT = 92
        const val MOVIE_FLOOR_PCT = 76
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun now(): Long = platform.posix.time(null)

    fun invalidate() { cache = null }

    suspend fun advise(): BazarrAdvisorResponse {
        cache?.let { (at, r) -> if (now() - at < cacheTtlSec) return r }
        val cfg = configStore.current.bazarr?.takeIf { it.enabled && it.url.isNotBlank() }
            ?: return BazarrAdvisorResponse(configured = false, reachable = false)
        val hook = SubtitleHook.current
        val settings = client.systemSettings(cfg.url, cfg.apiKey)
            ?: return BazarrAdvisorResponse(true, false, hookCommand = hook?.command(), hookLastCalledAt = hook?.lastCalledAtMs())
        val r = BazarrAdvisorResponse(true, true, findings(settings, hook?.command()), hook?.command(), hook?.lastCalledAtMs())
        cache = now() to r
        return r
    }

    private fun JsonObject.section(name: String): JsonObject = (this[name] as? JsonObject) ?: JsonObject(emptyMap())
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** This library's numbers, from the stored verdicts. */
    private data class Numbers(val off: Int, val beyond: Int, val largestS: Long, val pal: Int, val wrong: Int, val checked: Int)

    private fun numbers(maxOffsetS: Int): Numbers {
        val rows = db.subtitleCheckQueries.allChecks().executeAsList()
        val off = rows.filter { it.verdict == "off" || it.verdict == "off_mid_file" }
        return Numbers(
            off = off.size,
            beyond = off.count { abs(it.shift_ms ?: 0) / 1000 > maxOffsetS },
            largestS = off.maxOfOrNull { abs(it.shift_ms ?: 0) / 1000 } ?: 0,
            pal = off.count { s -> s.scale?.let { abs(it - 25.0 / 23.976) < 1e-3 || abs(it - 23.976 / 25.0) < 1e-3 } == true },
            wrong = rows.count { it.verdict in setOf("not_this_video", "other_episode", "longer_video") },
            checked = rows.count { it.verdict != "cant_tell" },
        )
    }

    internal suspend fun findings(settings: JsonObject, command: String?): List<AdvisorFinding> {
        val general = settings.section("general")
        val subsync = settings.section("subsync")
        val out = ArrayList<AdvisorFinding>()
        val maxOffset = subsync.int("max_offset_seconds") ?: 60
        val n = numbers(maxOffset)
        val hookOn = general.bool("use_postprocessing") == true && general.str("postprocessing_cmd")?.contains("/api/webhooks/bazarr") == true

        if (!hookOn) out += AdvisorFinding(
            id = HOOK,
            summary = "Bazarr doesn't tell jellystructure when it places a subtitle",
            currentValue = if (general.bool("use_postprocessing") == true) "On, with a different command" else "Off",
            costHere = "jellystructure hears about a new subtitle at its next 15-minute look at Bazarr's history instead of " +
                "before anyone presses play. ${n.wrong} of ${n.checked} subtitles checked here were not for their video.",
            navigationPath = "$PROCESSING → Custom Post-Processing",
            fieldLabel = "Custom Post-Processing · Command",
            recommendation = command?.let { "On, with the command below" } ?: "Set the address Bazarr reaches jellystructure at on this card first",
            tradeoff = "Bazarr waits for the command after every download; it returns in well under a second.",
            severity = JellyfinAdvisorService.WARNING,
            action = if (command != null) APPLY else null,
        )
        if (hookOn && (general.bool("use_postprocessing_threshold") == true || general.bool("use_postprocessing_threshold_movie") == true)) out += AdvisorFinding(
            id = HOOK_THRESHOLD,
            summary = "Bazarr only calls jellystructure for low-scoring subtitles",
            currentValue = "On",
            costHere = "A subtitle for the wrong episode scores as high as a right one; the score only sees the label it was filed under.",
            navigationPath = "$PROCESSING → Custom Post-Processing",
            fieldLabel = "Series Score Threshold For Post-Processing · Movies Score Threshold For Post-Processing",
            recommendation = "Off, both",
            tradeoff = "None: the command is quick.",
            action = APPLY,
        )
        if (subsync.bool("use_subsync") != true) out += AdvisorFinding(
            id = SYNC,
            summary = "Bazarr doesn't align subtitles to the video when it downloads them",
            currentValue = "Off",
            costHere = "${n.off} subtitles checked here are the right episode but out of sync" + (if (n.largestS > 0) ", the largest by ${n.largestS} s." else "."),
            navigationPath = "$PROCESSING → Audio Synchronization",
            fieldLabel = "Enable Automatic Subtitles Audio Synchronization",
            recommendation = "On",
            tradeoff = "Every download is aligned by ffsubsync: an audio decode per download where the file has no subtitle inside it. jellystructure still checks the result.",
            action = APPLY,
        )
        if (maxOffset < 300) out += AdvisorFinding(
            id = MAX_OFFSET,
            summary = "Bazarr's sync gives up on large offsets",
            currentValue = "$maxOffset",
            costHere = "${n.beyond} subtitles here are off by more than $maxOffset s" + (if (n.largestS > 0) ", the largest by ${n.largestS} s." else "."),
            navigationPath = "$PROCESSING → Audio Synchronization",
            fieldLabel = "Max Offset Seconds",
            recommendation = "300",
            tradeoff = "A wider search can find a false alignment on a subtitle for the wrong episode; jellystructure's check catches that.",
            action = APPLY,
        )
        if (subsync.bool("no_fix_framerate") == true) out += AdvisorFinding(
            id = FRAMERATE,
            summary = "Bazarr's sync leaves subtitles made for 25 fps running slow",
            currentValue = "On",
            costHere = "${n.pal} subtitles here are timed for 25 fps; left alone they fall about a minute behind by the end of an episode.",
            navigationPath = "$PROCESSING → Audio Synchronization",
            fieldLabel = "Do Not Fix Framerate Mismatch",
            recommendation = "Off",
            tradeoff = "None measured.",
            action = APPLY,
        )
        if (!hookOn && general.bool("upgrade_subs") == true) out += AdvisorFinding(
            id = UPGRADE,
            summary = "Bazarr's upgrades can swap a right subtitle for a wrong one",
            currentValue = "On, every ${general.int("upgrade_frequency") ?: 12} h for ${general.int("days_to_upgrade_subs") ?: 7} days",
            costHere = "An upgrade picks by the same score that cannot see the episode. With the hook on, jellystructure checks every upgrade and puts back the one that was in sync.",
            navigationPath = "$SEARCH → Upgrading Subtitles",
            fieldLabel = "Upgrade Previously Downloaded Subtitles",
            recommendation = "Turn the hook on (above)",
            tradeoff = "n/a",
            severity = JellyfinAdvisorService.INFO,
        )
        val minEp = general.int("minimum_score") ?: 0
        val minMovie = general.int("minimum_score_movie") ?: 0
        if (minEp > EPISODE_FLOOR_PCT || minMovie > MOVIE_FLOOR_PCT) out += AdvisorFinding(
            id = MIN_SCORE,
            summary = "Bazarr's minimum score rejects nearly every subtitle",
            currentValue = "Episodes $minEp% · Movies $minMovie%",
            costHere = "Any subtitle filed under the right episode scores 91.7% (a film 75%); above that only a hash or release-group match passes, which only 3% of right subtitles here had.",
            navigationPath = "$SEARCH → Search Scores",
            fieldLabel = "Minimum Score For Episodes · Minimum Score For Movies",
            recommendation = "Episodes 90 · Movies 70",
            tradeoff = "The score does not separate right from wrong either way; jellystructure's check does.",
            action = APPLY,
        )
        titleSearch()?.let { out += it }
        return out
    }

    /** Shows Bazarr knows without an IMDb id: it searches their subtitles by title. */
    private suspend fun titleSearch(): AdvisorFinding? {
        val cfg = configStore.current.bazarr ?: return null
        val shows = client.allSeries(cfg.url, cfg.apiKey).filter { it.imdbId.isNullOrBlank() }
        if (shows.isEmpty()) return null
        val known = store.allItems().filter { it.kind == MediaKind.TV_SHOW && !it.imdbId.isNullOrBlank() }.associateBy { it.tvdbId }
        val named = shows.take(8).joinToString(" · ") { s -> s.title + (known[s.tvdbId]?.imdbId?.let { " ($it on TMDB)" } ?: "") }
        return AdvisorFinding(
            id = TITLE_SEARCH,
            summary = "Bazarr searches ${shows.size} show${if (shows.size == 1) "" else "s"} by title",
            currentValue = "No IMDb id: $named" + if (shows.size > 8) " · and ${shows.size - 8} more" else "",
            costHere = "A title search can match another show's uploads; one here got another show's subtitle.",
            navigationPath = "TheTVDB → the show's page (Sonarr and Bazarr take it from there on their next refresh)",
            fieldLabel = "IMDb",
            recommendation = "Add the show's IMDb id at TheTVDB",
            tradeoff = "n/a",
            severity = JellyfinAdvisorService.INFO,
        )
    }

    /** FR-273-19 — exactly the keys a finding changes, as Bazarr's own settings page posts them. */
    fun fieldsFor(findingId: String): Map<String, String>? = when (findingId) {
        HOOK -> SubtitleHook.current?.command()?.let {
            mapOf(
                "settings-general-use_postprocessing" to "true",
                "settings-general-postprocessing_cmd" to it,
                "settings-general-use_postprocessing_threshold" to "false",
                "settings-general-use_postprocessing_threshold_movie" to "false",
            )
        }
        HOOK_THRESHOLD -> mapOf("settings-general-use_postprocessing_threshold" to "false", "settings-general-use_postprocessing_threshold_movie" to "false")
        SYNC -> mapOf("settings-subsync-use_subsync" to "true")
        MAX_OFFSET -> mapOf("settings-subsync-max_offset_seconds" to "300")
        FRAMERATE -> mapOf("settings-subsync-no_fix_framerate" to "false")
        MIN_SCORE -> mapOf("settings-general-minimum_score" to "90", "settings-general-minimum_score_movie" to "70")
        else -> null
    }

    @Serializable
    data class ApplyResult(val applied: Boolean, val message: String, val before: Map<String, String?> = emptyMap(), val after: Map<String, String?> = emptyMap())

    /** Apply one finding: post its keys, read Bazarr back, and say whether the finding is gone. */
    suspend fun apply(findingId: String, by: String): ApplyResult {
        val cfg = configStore.current.bazarr?.takeIf { it.enabled && it.url.isNotBlank() } ?: return ApplyResult(false, "Bazarr is not connected")
        val fields = fieldsFor(findingId) ?: return ApplyResult(false, "Nothing to apply for this finding")
        // The command carries the webhook secret: it is shown as what it is, never as its text.
        fun read(s: JsonObject?): Map<String, String?> = fields.keys.associateWith { k ->
            val (_, section, key) = k.split('-', limit = 3)
            val v = (s?.get(section)?.jsonObject?.get(key) as? JsonPrimitive)?.contentOrNull
            if (key != "postprocessing_cmd" || v.isNullOrBlank()) v
            else if (v.contains("/api/webhooks/bazarr")) "(jellystructure's command)" else "(another command)"
        }
        val before = read(client.systemSettings(cfg.url, cfg.apiKey))
        if (!client.applySettings(cfg.url, cfg.apiKey, fields)) return ApplyResult(false, "Bazarr refused the change", before)
        invalidate()
        val afterSettings = client.systemSettings(cfg.url, cfg.apiKey)
        val after = read(afterSettings)
        val gone = afterSettings != null && findings(afterSettings, SubtitleHook.current?.command()).none { it.id == findingId }
        dev.jellystructure.log.Logger.info("Bazarr settings applied by $by: " + fields.keys.joinToString { k ->
            "${k.removePrefix("settings-")}: ${before[k]} → ${after[k]}"
        }, "subtitles")
        return ApplyResult(gone, if (gone) "Applied in Bazarr" else "Bazarr answered, but still reports the old value", before, after)
    }
}
