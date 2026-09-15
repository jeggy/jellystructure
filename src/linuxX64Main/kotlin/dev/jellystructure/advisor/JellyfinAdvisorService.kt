package dev.jellystructure.advisor

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinLibrary
import dev.jellystructure.config.AppConfig
import dev.jellystructure.io.FileIo
import dev.jellystructure.nowEpochSec
import dev.jellystructure.tv.RaviloDeviceService
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 212 — read-only advisory surface: reads Jellyfin's own configuration plus host storage facts
 * and renders a finding ONLY where the live value differs from the recommendation (FR-212-2). Never
 * writes to Jellyfin (§2 of the spec) and never guesses at storage it can't resolve (FR-212-6's "unknown
 * must suppress" rule).
 *
 * A Kotlin `object` (like [dev.jellystructure.media.MkvHealthCache]), not a constructor-injected class —
 * every dependency it needs ([JellyfinClient], [AppConfig], [RaviloDeviceService]) is already
 * constructed and passed to `Server.kt`'s route-wiring function for other reasons, so this avoids adding
 * a new threading-through parameter to that already-long chain for a feature that is a single GET
 * endpoint.
 */
object JellyfinAdvisorService {

    // FR-212-1 — 5-minute cache; the pass runs on demand (opening the tab), not on a timer.
    private const val CACHE_TTL_SEC = 300L
    private var cachedAt: Long = 0
    private var cached: AdvisorResponse? = null

    suspend fun findings(jellyfinClient: JellyfinClient, cfg: AppConfig, raviloDeviceService: RaviloDeviceService): AdvisorResponse {
        val now = nowEpochSec()
        cached?.let { if (now - cachedAt < CACHE_TTL_SEC) return it }

        val base = cfg.apiKeys.jellyfinUrl
        val token = cfg.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) {
            return AdvisorResponse(reachable = false, computedAt = now)
        }

        // FR-212-1 — BACKGROUND class: an advisory read may never compete with playback negotiation for
        // a reserved interactive OutboundHttp permit.
        val result = kotlinx.coroutines.withContext(dev.jellystructure.ops.GateClass.BACKGROUND) {
            computeFindings(jellyfinClient, cfg, raviloDeviceService, base, token, now)
        }
        cached = result
        cachedAt = now
        return result
    }

    private suspend fun computeFindings(
        jellyfinClient: JellyfinClient, cfg: AppConfig, raviloDeviceService: RaviloDeviceService,
        base: String, token: String, now: Long,
    ): AdvisorResponse {
        val libraries = jellyfinClient.getLibraries(base, token)
        val encoding = jellyfinClient.getEncodingConfiguration(base, token)
        val systemInfo = jellyfinClient.getSystemInfoAuth(base, token)
        val plugins = jellyfinClient.getPlugins(base, token)

        // FR-212-1 — a Jellyfin that cannot answer renders "Couldn't reach Jellyfin" for the whole
        // surface, never an empty finding list (which would read as "everything is fine").
        if (libraries.isEmpty() && encoding == null && systemInfo == null) {
            return AdvisorResponse(reachable = false, computedAt = now)
        }

        val managed = cfg.libraries.filter { !it.skip && it.jellyfinId.isNotBlank() }
        val storageByLibrary: Map<String, DeviceInfo?> =
            managed.associate { it.jellyfinId to resolveDevice(it.localPath) }

        val managedIds = managed.map { it.jellyfinId }.toSet()
        val perLibrary = mutableListOf<LibraryAdvisorSection>()
        for (lib in libraries) {
            if (lib.id !in managedIds) continue
            val storage = storageByLibrary[lib.id]
            val libFindings = perLibraryFindings(lib, storage)
            if (libFindings.isNotEmpty()) perLibrary += LibraryAdvisorSection(lib.name, libFindings)
        }

        val serverWide = mutableListOf<AdvisorFinding>()
        if (encoding != null) serverWide += serverWideEncodingFindings(encoding, raviloDeviceService)

        // FR-212-6 — host storage, deduped by device: several libraries can share one spindle (Film and
        // Musik both sit on sdc here), and the finding is about the DEVICE, not the library.
        val devices = storageByLibrary.values.filterNotNull().filter { it.rotational == true }
            .distinctBy { it.device }
        for (dev in devices) serverWide += hostStorageFindings(dev)
        swappinessFinding()?.let { serverWide += it }

        restartPendingFinding(systemInfo, plugins)?.let { serverWide += it }

        return AdvisorResponse(reachable = true, computedAt = now, serverWide = serverWide, perLibrary = perLibrary)
    }

    // ── FR-212-4 — per-library findings ─────────────────────────────────────────

    private fun perLibraryFindings(lib: JellyfinLibrary, storage: DeviceInfo?): List<AdvisorFinding> {
        val opts = lib.libraryOptions ?: return emptyList()
        val path = "Dashboard → Libraries → ${lib.name} → Manage library"
        val out = mutableListOf<AdvisorFinding>()
        val rotational = storage?.rotational == true

        // (a)
        val chapterImagesOnRotational = opts.enableChapterImageExtraction && rotational
        if (chapterImagesOnRotational) {
            out += AdvisorFinding(
                id = "chapter_images_${lib.id}",
                summary = "Chapter image extraction is on, on rotational storage",
                currentValue = "Enable chapter image extraction: On",
                costHere = "Generates a full-file read to extract chapter thumbnails on every affected title in ${lib.name}, on a spinning disk.",
                navigationPath = path,
                fieldLabel = "\"Enable chapter image extraction\" (OptionExtractChapterImage)",
                recommendation = "Turn off, or accept the cost knowingly.",
                tradeoff = "Turning it off removes chapter-selection thumbnails from the scrub bar for this library.",
            )
        }
        // (b) — only when (a) also holds
        if (chapterImagesOnRotational && opts.extractChapterImagesDuringLibraryScan) {
            out += AdvisorFinding(
                id = "chapter_images_during_scan_${lib.id}",
                summary = "Chapter images extract during the library scan itself",
                currentValue = "Extract chapter images during the library scan: On",
                costHere = "Moves the chapter-image work into every library scan instead of the dedicated nightly task — Jellyfin's own help text: \"The process can be slow, resource intensive, and may require several gigabytes of space… It is not recommended to run this task during peak usage hours.\"",
                navigationPath = path,
                fieldLabel = "\"Extract chapter images during the library scan\" (LabelExtractChaptersDuringLibraryScan)",
                recommendation = "Turn off — it moves the work to the nightly task instead of into every scan.",
                tradeoff = "New titles won't have chapter thumbnails until the next nightly chapter-image task runs.",
            )
        }
        // (c)
        if (opts.enableTrickplayImageExtraction && rotational) {
            out += AdvisorFinding(
                id = "trickplay_${lib.id}",
                summary = "Trickplay image extraction is on, on rotational storage",
                currentValue = "Enable trickplay image extraction: On",
                costHere = "Generates scrub-preview thumbnails by decoding the whole file for every title in ${lib.name}, on a spinning disk.",
                navigationPath = path,
                fieldLabel = "\"Enable trickplay image extraction\" (OptionExtractTrickplayImage)",
                recommendation = "Consider off.",
                tradeoff = "Removes the hover/scrub thumbnail previews for this library.",
            )
        }
        // (d)
        if (opts.enableLufsScan && rotational) {
            out += AdvisorFinding(
                id = "lufs_${lib.id}",
                summary = "LUFS loudness scan is on, on rotational storage",
                currentValue = "Enable LUFS scan: On",
                costHere = "Reads the full audio track of every title in ${lib.name} to measure loudness, on a spinning disk.",
                navigationPath = path,
                fieldLabel = "\"Enable LUFS scan\" (LabelEnableLUFSScan)",
                recommendation = "Consider off.",
                tradeoff = "Loses automatic loudness normalisation for this library.",
            )
        }
        // (e) — consistency check, fires regardless of storage
        if (!opts.enableTrickplayImageExtraction && opts.extractTrickplayImagesDuringLibraryScan) {
            out += AdvisorFinding(
                id = "trickplay_contradiction_${lib.id}",
                summary = "\"During the scan\" is on while trickplay itself is off",
                currentValue = "Enable trickplay image extraction: Off · Extract trickplay images during the library scan: On",
                costHere = "The during-scan flag is inert while the feature itself is off — a contradictory pair, not a performance trade.",
                navigationPath = path,
                fieldLabel = "\"Enable trickplay image extraction\" (OptionExtractTrickplayImage) and \"Extract trickplay images during the library scan\" (its during-scan counterpart)",
                recommendation = "Turn the during-scan flag off too, or turn trickplay extraction back on if it was meant to be on.",
                tradeoff = "None — this is a mistake either way, not a trade.",
            )
        }
        return out
    }

    // ── FR-212-5 — server-wide encoding findings ────────────────────────────────

    private fun serverWideEncodingFindings(enc: dev.jellystructure.auth.JellyfinEncodingConfig, raviloDeviceService: RaviloDeviceService): List<AdvisorFinding> {
        val path = "Dashboard → Playback → Transcoding"
        val out = mutableListOf<AdvisorFinding>()

        // (a)
        if (!enc.enableThrottling) {
            out += AdvisorFinding(
                id = "throttle_transcodes",
                summary = "Throttle Transcodes is off",
                currentValue = "Throttle Transcodes: Off (Throttle delay: ${enc.throttleDelaySeconds}s — set but inert while this is off)",
                costHere = "A transcode races ahead of playback at full speed instead of pacing itself once it's built a comfortable buffer, using more CPU/disk than the viewer actually needs right now.",
                navigationPath = path,
                fieldLabel = "\"Throttle Transcodes\" (AllowFfmpegThrottling)",
                recommendation = "Turn on. The delay is already configured (${enc.throttleDelaySeconds}s) and does nothing until this is on — a set-but-ignored value is exactly what makes this easy to miss.",
                tradeoff = "A transcode takes slightly longer to build ahead-of-playback buffer after a seek.",
            )
        }
        // (b) — see the class doc: jellystructure cannot verify TranscodingTempPath is a tmpfs from its
        // own container (different mount namespace than Jellyfin's), so this fires on the one fact it
        // CAN verify — segment deletion being off — without asserting the RAM-disk severity as proven.
        if (!enc.enableSegmentDeletion) {
            out += AdvisorFinding(
                id = "segment_deletion",
                summary = "Delete segments is off",
                currentValue = "Delete segments: Off · Time to keep segments: ${enc.segmentKeepSeconds}s · Transcode temp path: ${enc.transcodingTempPath ?: "(default)"}",
                costHere = "Transcode segments accumulate at \"${enc.transcodingTempPath ?: "the transcode temp path"}\" for up to ${enc.segmentKeepSeconds}s per session instead of being cleaned up as playback moves past them — especially costly if that path is RAM-backed (tmpfs), since jellystructure cannot confirm that from its own container and this finding does not assume it.",
                navigationPath = path,
                fieldLabel = "\"Delete segments\" (AllowSegmentDeletion) and \"Time to keep segments\" (LabelSegmentKeepSeconds)",
                recommendation = "Turn on.",
                tradeoff = "A viewer who seeks backward loses a completed segment and pays a small re-transcode cost instead of an instant seek.",
            )
        }
        // (c) — simplified to "a known device has a recorded decode ceiling" rather than re-deriving
        // which titles transcode today (that needs live playback_qoe correlation, out of scope here).
        if (!enc.allowHevcEncoding && enc.enableHardwareEncoding) {
            val allDevices = runCatching { raviloDeviceService.allDevices() }.getOrDefault(emptyList())
            val devices = allDevices.distinctBy { it.deviceId }
                .filter { d -> runCatching { raviloDeviceService.decodeCapabilities(d.deviceId, d.jellyfinUserId)?.hevcMaxBitrate != null }.getOrDefault(false) }
                .map { it.displayName.ifBlank { it.deviceId } }
            if (devices.isNotEmpty()) {
                val named = devices.first() + if (devices.size > 1) " and ${devices.size - 1} other${if (devices.size - 1 == 1) "" else "s"}" else ""
                out += AdvisorFinding(
                    id = "allow_hevc",
                    summary = "HEVC encoding is disallowed with hardware encoding on",
                    currentValue = "Allow encoding in HEVC format: Off · Hardware encoding: On",
                    costHere = "$named ${if (devices.size == 1) "has" else "have"} a recorded decode ceiling — a forced transcode for ${if (devices.size == 1) "it" else "any of them"} lands on H.264 instead of HEVC, a larger encode for the same quality.",
                    navigationPath = path,
                    fieldLabel = "\"Allow encoding in HEVC format\" (AllowHevcEncoding)",
                    recommendation = "Consider on.",
                    tradeoff = "HEVC hardware encoding uses more of the GPU's fixed encode budget than H.264 for the same session count.",
                )
            }
        }
        // (d) — no Jellyfin UI for this field at all.
        if (enc.allowOnDemandMetadataBasedKeyframeExtractionForExtensions.isNotEmpty()) {
            out += AdvisorFinding(
                id = "keyframe_extraction_extensions",
                summary = "On-demand keyframe extraction is enabled for ${enc.allowOnDemandMetadataBasedKeyframeExtractionForExtensions.joinToString(", ")}",
                currentValue = "AllowOnDemandMetadataBasedKeyframeExtractionForExtensions: ${enc.allowOnDemandMetadataBasedKeyframeExtractionForExtensions.joinToString(", ")}",
                costHere = "Triggers a full-file keyframe scan for matching containers (typically needed for accurate seeking on some MKVs) — a real cost on rotational storage.",
                navigationPath = "Not exposed in the Jellyfin admin UI — file-only (encoding.xml)",
                fieldLabel = "AllowOnDemandMetadataBasedKeyframeExtractionForExtensions (no on-screen label; edit encoding.xml directly)",
                recommendation = "Leave as-is unless seek accuracy problems on these containers are a known issue — there is nowhere in the UI to toggle this, so changing it means editing Jellyfin's config file directly.",
                tradeoff = "Turning it off (file edit) risks less accurate seeking on affected files if the fallback keyframe data is imprecise.",
            )
        }
        return out
    }

    // ── FR-212-6 — host storage findings ────────────────────────────────────────

    private fun hostStorageFindings(dev: DeviceInfo): List<AdvisorFinding> {
        val out = mutableListOf<AdvisorFinding>()
        val persistNote = "None of this survives a reboot without a udev rule."
        if (dev.scheduler != null && dev.scheduler != "bfq") {
            out += AdvisorFinding(
                id = "scheduler_${dev.device}",
                summary = "${dev.device} runs ${dev.scheduler}, not BFQ",
                currentValue = "/sys/block/${dev.device}/queue/scheduler = ${dev.scheduler}",
                costHere = "jellystructure already runs background ffmpeg under ionice -c3 (FfmpegRunner.kt), intended to protect playback — but ionice classes are honoured only by BFQ (and legacy CFQ). ${dev.scheduler} ignores them entirely, so that protection has never taken effect at the I/O layer on this device.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = "echo bfq | sudo tee /sys/block/${dev.device}/queue/scheduler",
                recommendation = "Switch to BFQ. $persistNote",
                tradeoff = "BFQ carries more per-request CPU overhead than ${dev.scheduler} and can lower peak sequential throughput slightly — a real trade, not a free win.",
            )
        }
        if (dev.readAheadKb != null && dev.readAheadKb <= 256) {
            out += AdvisorFinding(
                id = "readahead_${dev.device}",
                summary = "${dev.device}'s read-ahead is at the ${dev.readAheadKb} KB default",
                currentValue = "/sys/block/${dev.device}/queue/read_ahead_kb = ${dev.readAheadKb}",
                costHere = "Fewer, larger sequential reads per seek on a spinning disk — a minor lever (a 16 MB read-ahead is a small fraction of a typical remux) but real.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = "echo 16384 | sudo tee /sys/block/${dev.device}/queue/read_ahead_kb",
                recommendation = "Raise to 8–16 MB. $persistNote",
                tradeoff = "More RAM held by speculative read-ahead that may go unused if playback jumps around instead of reading sequentially.",
            )
        }
        if (dev.maxSectorsKb != null && dev.maxHwSectorsKb != null && dev.maxSectorsKb * 4 < dev.maxHwSectorsKb) {
            out += AdvisorFinding(
                id = "max_sectors_${dev.device}",
                summary = "${dev.device}'s max_sectors_kb (${dev.maxSectorsKb}) is far below its hardware ceiling (${dev.maxHwSectorsKb})",
                currentValue = "/sys/block/${dev.device}/queue/max_sectors_kb = ${dev.maxSectorsKb} (hw ceiling ${dev.maxHwSectorsKb})",
                costHere = "A larger read-ahead gets split into many more, smaller requests than the hardware could otherwise service in one — partly wasting a read-ahead increase without this.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = "echo ${dev.maxHwSectorsKb} | sudo tee /sys/block/${dev.device}/queue/max_sectors_kb",
                recommendation = "Raise toward the hardware limit (${dev.maxHwSectorsKb}). $persistNote",
                tradeoff = "Larger single requests can very slightly increase worst-case latency for an unrelated small read queued behind one.",
            )
        }
        return out
    }

    private fun swappinessFinding(): AdvisorFinding? {
        val swappiness = readProcInt("/proc/sys/vm/swappiness") ?: return null
        val mem = readMeminfo() ?: return null
        val cachedGtFree = mem.cachedKb > mem.freeKb * 2
        if (swappiness < 60 || !cachedGtFree) return null
        return AdvisorFinding(
            id = "swappiness",
            summary = "vm.swappiness is $swappiness with ${mem.cachedKb / 1_048_576} GB cached vs ${mem.freeKb / 1_048_576} GB free",
            currentValue = "/proc/sys/vm/swappiness = $swappiness",
            costHere = "The kernel is choosing to page out live processes to grow page cache further — the wrong trade when one of those processes is serving video.",
            navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
            fieldLabel = "sudo sysctl vm.swappiness=10",
            recommendation = "Lower to ~10. Persist with a line in /etc/sysctl.d/ (e.g. vm.swappiness = 10) — this does not survive a reboot otherwise.",
            tradeoff = "The kernel keeps slightly less page cache under memory pressure, favouring process RAM instead.",
        )
    }

    // ── FR-212-7 — restart pending ──────────────────────────────────────────────

    private fun restartPendingFinding(systemInfo: dev.jellystructure.auth.JellyfinSystemInfoAuth?, plugins: List<dev.jellystructure.auth.JellyfinPluginInfo>?): AdvisorFinding? {
        val pendingPlugins = plugins.orEmpty().filter { it.status == "Restart" }.map { it.name }
        val hasPending = (systemInfo?.hasPendingRestart == true) || pendingPlugins.isNotEmpty()
        if (!hasPending) return null
        val pluginNote = if (pendingPlugins.isNotEmpty()) " (${pendingPlugins.joinToString(", ")})" else ""
        return AdvisorFinding(
            id = "restart_pending",
            summary = "Jellyfin reports a pending restart$pluginNote",
            currentValue = "System/Info HasPendingRestart: ${systemInfo?.hasPendingRestart ?: false}",
            costHere = "Stated as a fact only — this finding does not claim a performance cost.",
            navigationPath = "Dashboard → Plugins (or Dashboard's own restart banner, if Jellyfin shows one)",
            fieldLabel = "n/a — nothing here to set",
            recommendation = "Not offered. Restarting the household's media server is not an admin-panel side effect — decide when, yourself.",
            tradeoff = "n/a",
        )
    }

    // ── Host storage resolution (FR-212-6's core mechanism) ─────────────────────

    data class DeviceInfo(
        val device: String,
        val rotational: Boolean?,
        val scheduler: String?,
        val readAheadKb: Int?,
        val maxSectorsKb: Int?,
        val maxHwSectorsKb: Int?,
    )

    /** Resolves a jellystructure-local path (from `[[libraries]] local_path`, e.g. `/mnt/media/...`) to
     *  its backing block device, reading `/proc/self/mountinfo` for the mount covering it — this is
     *  jellystructure's OWN mount table, which is why `local_path` (not Jellyfin's `jellyfin_path`) is
     *  the right input: the two containers can and do mount the same host directory at different
     *  internal paths (verified live, 2026-09-15: Jellyfin sees `/media/movies`, jellystructure sees
     *  `/mnt/media/jellyfin/movies`). Returns null (unknown) rather than guessing — an LVM/device-mapper
     *  source (`/dev/mapper/...`, as `/config` resolves to here) has no `/sys/block/<name>` entry and
     *  correctly falls through to null; every storage-conditional finding must suppress on null. */
    private fun resolveDevice(localPath: String): DeviceInfo? {
        if (localPath.isBlank()) return null
        val mountinfo = runCatching { FileIo.readText(Path("/proc/self/mountinfo")) }.getOrNull() ?: return null
        var bestMountPoint: String? = null
        var bestSource: String? = null
        for (line in mountinfo.lineSequence()) {
            val fields = line.split(" ")
            if (fields.size < 5) continue
            val mountPoint = fields[4]
            val dashIdx = fields.indexOf("-")
            if (dashIdx < 0 || dashIdx + 2 >= fields.size) continue
            val source = fields[dashIdx + 2]
            if (localPath.startsWith(mountPoint) && (bestMountPoint == null || mountPoint.length > bestMountPoint.length)) {
                bestMountPoint = mountPoint
                bestSource = source
            }
        }
        val source = bestSource ?: return null
        if (!source.startsWith("/dev/")) return null
        val devName = stripPartitionSuffix(source.removePrefix("/dev/"))
        // Implementation trap (verified live, matches the spec's own §FR-212-6 note): an LVM source like
        // "mapper/debian--vg-root" has no /sys/block/<name> entry — this read fails and we return null,
        // which is the correct "unknown, suppress" outcome, not a bug to work around.
        val rotationalStr = readProcInt("/sys/block/$devName/queue/rotational")
        if (rotationalStr == null) return null
        return DeviceInfo(
            device = devName,
            rotational = rotationalStr == 1,
            scheduler = readSysfsScheduler(devName),
            readAheadKb = readProcInt("/sys/block/$devName/queue/read_ahead_kb"),
            maxSectorsKb = readProcInt("/sys/block/$devName/queue/max_sectors_kb"),
            maxHwSectorsKb = readProcInt("/sys/block/$devName/queue/max_hw_sectors_kb"),
        )
    }

    private fun stripPartitionSuffix(dev: String): String {
        Regex("""^(nvme\d+n\d+)p\d+$""").find(dev)?.let { return it.groupValues[1] }
        Regex("""^([a-zA-Z]+)\d+$""").find(dev)?.let { return it.groupValues[1] }
        return dev
    }

    /** `/sys/block/<dev>/queue/scheduler` reads like `noop [mq-deadline] cfq` — the active one is
     *  bracketed. Returns the bracketed name, or null if unreadable. */
    private fun readSysfsScheduler(dev: String): String? {
        val raw = runCatching { FileIo.readText(Path("/sys/block/$dev/queue/scheduler")) }.getOrNull()?.trim() ?: return null
        val bracketed = Regex("""\[([^]]+)]""").find(raw)?.groupValues?.get(1)
        return bracketed ?: raw.split(" ").firstOrNull()
    }

    private fun readProcInt(path: String): Int? =
        runCatching { FileIo.readText(Path(path)).trim().toInt() }.getOrNull()

    private data class MemInfo(val cachedKb: Long, val freeKb: Long)

    private fun readMeminfo(): MemInfo? {
        val text = runCatching { FileIo.readText(Path("/proc/meminfo")) }.getOrNull() ?: return null
        var cached: Long? = null
        var free: Long? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Cached:") -> cached = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("MemFree:") -> free = line.filter { it.isDigit() }.toLongOrNull()
            }
        }
        if (cached == null || free == null) return null
        return MemInfo(cached, free)
    }

    /** Test-only reset — the cache is otherwise process-lifetime. */
    internal fun clearCacheForTest() { cached = null; cachedAt = 0 }
}

@Serializable
data class AdvisorFinding(
    val id: String,
    val summary: String,
    @SerialName("current_value") val currentValue: String,
    @SerialName("cost_here") val costHere: String,
    @SerialName("navigation_path") val navigationPath: String,
    @SerialName("field_label") val fieldLabel: String,
    val recommendation: String,
    val tradeoff: String,
)

@Serializable
data class LibraryAdvisorSection(
    @SerialName("library_name") val libraryName: String,
    val findings: List<AdvisorFinding>,
)

@Serializable
data class AdvisorResponse(
    val reachable: Boolean,
    @SerialName("computed_at") val computedAt: Long,
    @SerialName("server_wide") val serverWide: List<AdvisorFinding> = emptyList(),
    @SerialName("per_library") val perLibrary: List<LibraryAdvisorSection> = emptyList(),
)
