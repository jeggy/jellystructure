package dev.jellystructure.advisor

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 215 — "here is the RAM I'll give it, tell me what to change." One input (a GB budget for the
 * whole media stack), concrete copy-pasteable changes out. Suggest-only, same posture as Phase 212 (§2):
 * never writes to Jellyfin, never edits a compose file, never runs a sysctl.
 *
 * A Kotlin `object`, like [JellyfinAdvisorService] — every dependency is already available at the one
 * call site, and this is a single POST endpoint, not a stateful subsystem.
 */
object MemoryBudgetService {

    // Resolves open question 2 ("one budget or two?"): ONE figure for the whole stack, matching the
    // owner's own phrasing ("jellystructure/jellyfin" as a unit). jellystructure already has its own
    // independent mem_limit in its own compose file (16g today) and a small, stable measured footprint
    // (~3 GB) — this budget is really a decision about JELLYFIN, so a small fixed slice is reserved for
    // jellystructure and the rest goes to the number this calculator actually exists to produce.
    private const val JELLYSTRUCTURE_FIXED_GB = 4.0

    // Resolves open question 1 ("what is the safety reserve?"). jellystructure cannot sum the other
    // ~15 containers' live usage without docker-socket access it doesn't have (see the spec's §8 amendment
    // for the identical limitation on Phase 212's FR-212-5(b)/(c)), so this is a stated proxy, not a
    // measurement: 10% of host RAM or 8 GB, whichever is larger, for the OS and everything else on this
    // host. FR-215-1 requires showing this as an assumption, not hiding it as if it were measured.
    private const val RESERVE_FLOOR_GB = 8.0
    private const val RESERVE_FRACTION = 0.10

    // FR-215-3 — tmpfs cap: a fraction of Jellyfin's own allowance, floored/capped to a sane range so a
    // tiny budget doesn't get a useless sub-1GB transcode directory and a huge one doesn't get an
    // unbounded-in-practice cap that defeats the point.
    private const val TMPFS_FRACTION_OF_JELLYFIN = 0.25
    private const val TMPFS_FLOOR_GB = 1.0
    private const val TMPFS_CEILING_GB = 8.0

    // FR-215-5 — only flagged if it's actually worse than this; matches Phase 212's own silence rule.
    private const val SWAPPINESS_THRESHOLD = 20

    suspend fun calculate(budgetGb: Double, jellyfinClient: JellyfinClient, cfg: AppConfig): MemoryBudgetResult {
        val mem = readMeminfo()
        val swappiness = readProcInt("/proc/sys/vm/swappiness")

        val arithmetic = mutableListOf<MemoryBudgetLine>()
        if (mem != null) {
            arithmetic += MemoryBudgetLine("Host RAM", "${gb(mem.totalKb)} GB total")
            // FR-215-2 — stated explicitly, not just implied by omission: page cache is never added to
            // "available" below. It is reclaimable (so it's not a hard blocker) and it is doing real work
            // (so it's not spare either) — this calculator only ever allocates from RAM nothing is
            // currently using, never from what the page cache currently holds.
            arithmetic += MemoryBudgetLine("Page cache right now", "${gb(mem.cachedKb)} GB — reclaimable, but doing real work (repeat reads of media). Never counted as free capacity here.")
        }
        val reserveGb = mem?.let { maxOf(RESERVE_FLOOR_GB, gb(it.totalKb) * RESERVE_FRACTION) } ?: RESERVE_FLOOR_GB
        arithmetic += MemoryBudgetLine("Reserved for the OS + ~15 other containers", "${round1(reserveGb)} GB (10% of host RAM or 8 GB, whichever is larger — an assumption, not a measurement: jellystructure has no way to sum other containers' live usage)")
        arithmetic += MemoryBudgetLine("Reserved for jellystructure itself", "${round1(JELLYSTRUCTURE_FIXED_GB)} GB (its own compose file already sets mem_limit independently; measured real usage is ~3 GB)")
        arithmetic += MemoryBudgetLine("Your budget", "${round1(budgetGb)} GB")

        val availableForJellyfin = budgetGb - reserveGb - JELLYSTRUCTURE_FIXED_GB
        arithmetic += MemoryBudgetLine(
            "Left for Jellyfin",
            "${round1(budgetGb)} − ${round1(reserveGb)} − ${round1(JELLYSTRUCTURE_FIXED_GB)} = ${round1(availableForJellyfin)} GB",
        )

        // FR-215-7 — refuse rather than emit a best-effort over-commit with a warning attached; a
        // warning above a copy-pasteable block loses to the copy-pasteable block every time.
        if (availableForJellyfin <= 0) {
            return MemoryBudgetResult(
                ok = false,
                refusalReason = "This budget leaves nothing for Jellyfin once the OS/other-container reserve and jellystructure's own footprint are subtracted. Raise the budget by at least ${round1(-availableForJellyfin + 0.5)} GB, or lower the reserve assumptions above if you're confident this host runs lighter than that.",
                arithmetic = arithmetic,
            )
        }

        val jellyfinMemLimitGb = floor1(availableForJellyfin)
        val tmpfsCapGb = (availableForJellyfin * TMPFS_FRACTION_OF_JELLYFIN).coerceIn(TMPFS_FLOOR_GB, TMPFS_CEILING_GB)

        val findings = mutableListOf<AdvisorFinding>()

        // FR-215-4 — an unlimited container is always a finding. jellystructure cannot read Jellyfin's
        // own compose file from inside its own container (no shared mount), so this is unconditional —
        // it cannot verify Jellyfin already has a limit close to this one, the same limitation FR-212-5's
        // tmpfs check has for the identical reason (see phase-212's §8 amendment).
        findings += AdvisorFinding(
            id = "jellyfin_mem_limit",
            summary = "Cap Jellyfin's container memory at ${round1(jellyfinMemLimitGb)} GB",
            currentValue = "Jellyfin's compose file has no mem_limit today — it can take the whole host.",
            costHere = "An unbounded container turns a memory spike into an unpredictable, host-wide OOM that can kill ANY process, including jellystructure or something unrelated. A limit turns that into a predictable failure of Jellyfin alone — better, but not good, for a media server mid-playback. This is the trade you're choosing, not a free win.",
            navigationPath = "~/jellyfin/docker-compose.yml",
            fieldLabel = "the jellyfin service's own block",
            recommendation = "mem_limit: ${round1(jellyfinMemLimitGb)}g",
            tradeoff = "Jellyfin gets OOM-killed (and restarts) if it ever legitimately needs more than ${round1(jellyfinMemLimitGb)} GB — e.g. several concurrent 4K HDR transcodes at once.",
        )

        val encoding = runCatching {
            if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank())
                jellyfinClient.getEncodingConfiguration(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
            else null
        }.getOrNull()
        val segmentDeletionOff = encoding?.enableSegmentDeletion == false

        // FR-215-3 — tmpfs cap, ALWAYS paired with segment deletion: a size cap alone just converts an
        // OOM into a failed transcode once the cap fills, since nothing would ever free space inside it.
        findings += AdvisorFinding(
            id = "tmpfs_cap",
            summary = "Cap the host's /dev/shm (feeding Jellyfin's /transcode) at ${round1(tmpfsCapGb)} GB",
            currentValue = "/dev/shm has no size limit set on this host — it defaults to half of RAM (measured: 63 GB), and Jellyfin's /transcode bind-mounts it directly." +
                (if (segmentDeletionOff) " Jellyfin's own \"Delete segments\" is currently OFF, so nothing inside it is ever cleaned up during a session." else " Jellyfin's own \"Delete segments\" is already ON, so this cap is the backstop, not the only protection."),
            costHere = "Unlike a normal directory, tmpfs pages can only be reclaimed by swapping or by the kernel's OOM killer — never silently, the way page cache is. A long transcode with segment deletion off can fill an uncapped tmpfs with nothing to stop it.",
            navigationPath = "Host: /etc/fstab or a systemd mount unit for /dev/shm" + (if (segmentDeletionOff) "; Jellyfin: Dashboard → Playback → Transcoding" else ""),
            fieldLabel = "/dev/shm size" + (if (segmentDeletionOff) " and \"Delete segments\" (AllowSegmentDeletion) + \"Time to keep segments\" (LabelSegmentKeepSeconds)" else ""),
            recommendation = "Mount /dev/shm at ${round1(tmpfsCapGb)}g (e.g. `tmpfs /dev/shm tmpfs defaults,size=${round1(tmpfsCapGb)}g 0 0` in /etc/fstab)." +
                (if (segmentDeletionOff) " Also turn Delete segments ON and lower Time to keep segments from its current value — 60s is enough for a normal seek-back." else ""),
            tradeoff = "A transcode session that would have needed more than ${round1(tmpfsCapGb)} GB of segments at once now fails outright instead of degrading — this is the point (FR-215-4's same trade, applied to disk instead of the whole container), but it means the cap has to be big enough for a real session, not just comfortable-looking.",
        )

        // FR-215-5 — real silence-when-correct: swappiness is one of the few facts here jellystructure
        // CAN read live from inside its own container (/proc/sys/vm is host-wide, not namespaced).
        if ((swappiness ?: 0) > SWAPPINESS_THRESHOLD) {
            findings += AdvisorFinding(
                id = "swappiness",
                summary = "Lower vm.swappiness from $swappiness",
                currentValue = "/proc/sys/vm/swappiness = $swappiness" + (mem?.let { " (${gb(it.swapUsedKb)} GB of swap in use against ${gb(it.cachedKb)} GB of page cache)" } ?: ""),
                costHere = "The kernel is willing to page out live process memory to grow page cache further — the wrong trade when one of those processes is serving video to a household.",
                navigationPath = "Host: /etc/sysctl.d/",
                fieldLabel = "vm.swappiness",
                recommendation = "vm.swappiness = 10",
                tradeoff = "The kernel keeps slightly less page cache under memory pressure, favouring process RAM instead.",
            )
        }

        return MemoryBudgetResult(
            ok = true,
            arithmetic = arithmetic,
            findings = findings,
            // FR-215-8 — static, not live-derived (jellystructure can't see other containers' PIDs to
            // compute real OOM scores at request time): the durable explanation of why this budget
            // survives a later neighbour for one kind of memory and not the other.
            survivalNote = "If something else on this host later needs 60 GB: page cache (including any future prefetch feature, open question 6 in Phase 212) is reclaimable, so it's evicted automatically and playback falls back to reading from disk — your clients keep working. tmpfs is NOT reclaimable the same way — only swap and the OOM killer can free it — which is exactly why the cap above exists. Today, real usage is small enough (Jellyfin ~3.6 GB, jellystructure ~3 GB measured) that a 60 GB neighbour would fit without touching anything; the risk this budget defends against is that neighbour arriving while an uncapped tmpfs is mid-transcode, not the neighbour by itself.",
        )
    }

    private fun gb(kb: Long): Long = kb / 1_048_576L
    private fun round1(v: Double): Double = kotlin.math.round(v * 10) / 10.0
    private fun floor1(v: Double): Double = kotlin.math.floor(v * 10) / 10.0

    private fun readProcInt(path: String): Int? =
        runCatching { FileIo.readText(Path(path)).trim().toInt() }.getOrNull()

    private data class MemInfo(val totalKb: Long, val freeKb: Long, val cachedKb: Long, val swapTotalKb: Long, val swapUsedKb: Long)

    private fun readMeminfo(): MemInfo? {
        val text = runCatching { FileIo.readText(Path("/proc/meminfo")) }.getOrNull() ?: return null
        var total: Long? = null; var free: Long? = null; var cached: Long? = null
        var swapTotal: Long? = null; var swapFree: Long? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("MemFree:") -> free = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("Cached:") -> cached = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("SwapTotal:") -> swapTotal = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("SwapFree:") -> swapFree = line.filter { it.isDigit() }.toLongOrNull()
            }
        }
        if (total == null || free == null || cached == null || swapTotal == null || swapFree == null) return null
        return MemInfo(total, free, cached, swapTotal, swapTotal - swapFree)
    }
}

@Serializable
data class MemoryBudgetLine(val label: String, val value: String)

@Serializable
data class MemoryBudgetResult(
    val ok: Boolean,
    @SerialName("refusal_reason") val refusalReason: String? = null,
    val arithmetic: List<MemoryBudgetLine> = emptyList(),
    val findings: List<AdvisorFinding> = emptyList(),
    @SerialName("survival_note") val survivalNote: String = "",
)
