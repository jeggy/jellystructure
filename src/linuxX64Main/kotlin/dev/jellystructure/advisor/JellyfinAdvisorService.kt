package dev.jellystructure.advisor

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinLibrary
import dev.jellystructure.auth.JellyfinTaskInfo
import dev.jellystructure.config.AppConfig
import dev.jellystructure.io.FileIo
import dev.jellystructure.nowEpochSec
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 212 — read-only advisory surface: reads Jellyfin's own configuration plus host storage facts
 * and renders a finding ONLY where the live value differs from the recommendation (FR-212-2). Never
 * writes to Jellyfin (§2 of the spec) and never guesses at storage it can't resolve (FR-212-6's "unknown
 * must suppress" rule).
 *
 * Phase 246 corrected it. An advisor is only worth opening if every finding on it is true, and an audit
 * of the live output on 2026-09-19 found one finding that was wrong in four separate ways and would have
 * made the server worse if followed, several whose copy described the benefit of a change in the field
 * reserved for the cost of the current value, one command that fails outright on this host, and the
 * single most consequential misconfiguration on the machine — Jellyfin running with no hardware
 * acceleration at all — checked by nothing. Every finding below now carries a severity (FR-246-9) and
 * every cost string states what the PRESENT value costs (FR-246-5).
 *
 * A Kotlin `object` (like [dev.jellystructure.media.MkvHealthCache]), not a constructor-injected class —
 * every dependency it needs ([JellyfinClient], [AppConfig]) is already constructed and passed to
 * `Server.kt`'s route-wiring function for other reasons, so this avoids adding a new threading-through
 * parameter to that already-long chain for a feature that is a single GET endpoint.
 */
object JellyfinAdvisorService {

    // FR-212-1 — 5-minute cache; the pass runs on demand (opening the tab), not on a timer.
    private const val CACHE_TTL_SEC = 300L
    private var cachedAt: Long = 0
    private var cached: AdvisorResponse? = null

    // FR-246-9 — render order. `critical` is a setting that makes the server behave unlike what its own
    // configuration page claims; `warning` asks for a decision; `info` asks for nothing and must not be
    // counted in the card's badge, because a page that warns about things it doesn't want changed trains
    // the reader to skim it.
    const val CRITICAL = "critical"
    const val WARNING = "warning"
    const val INFO = "info"
    private val SEVERITY_ORDER = listOf(CRITICAL, WARNING, INFO)

    // FR-246-6 — a latency budget, not a hardware ceiling. A single request is dispatched whole by
    // mq-deadline, so its size IS a head-of-line blocking time for everything queued behind it on that
    // spindle. 200 KB/ms is a typical sequential rate for the 7200rpm SATA drives this fires on; it is
    // used only to state the cost in milliseconds, never to decide whether the finding fires.
    private const val SEQUENTIAL_KB_PER_MS = 200.0
    private const val MAX_SECTORS_TARGET_KB = 4096

    // FR-246-8 — matches [MemoryBudgetService]'s own SWAPPINESS_THRESHOLD deliberately: two advisory
    // surfaces in the same product must not disagree about what counts as too high.
    private const val SWAPPINESS_THRESHOLD = 20
    // Roughly 4 GB written to swap. Below this, `pswpout` is the ordinary trickle of a long-uptime host
    // and there is nothing to report.
    private const val PSWPOUT_PAGES_THRESHOLD = 1_000_000L

    // FR-246-12 — Jellyfin's own stable task keys. Never the Id, per phase 165's rule.
    private const val TASK_TRICKPLAY = "RefreshTrickplayImages"
    private const val TASK_CHAPTER_IMAGES = "RefreshChapterImages"

    // Phase 242 FR-242-3 — the carve-out, as a named allow-list rather than a substring guess. Both of
    // these read the file already on disk and reach no external service, so flagging them would be
    // wrong; on the household server they are the only image fetchers configured anywhere.
    private val LOCAL_ONLY_IMAGE_FETCHERS = setOf("Embedded Image Extractor", "Screen Grabber")

    suspend fun findings(jellyfinClient: JellyfinClient, cfg: AppConfig): AdvisorResponse {
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
            computeFindings(jellyfinClient, cfg, base, token, now)
        }
        cached = result
        cachedAt = now
        return result
    }

    private suspend fun computeFindings(
        jellyfinClient: JellyfinClient, cfg: AppConfig, base: String, token: String, now: Long,
    ): AdvisorResponse {
        val libraries = jellyfinClient.getLibraries(base, token)
        val encoding = jellyfinClient.getEncodingConfiguration(base, token)
        val systemInfo = jellyfinClient.getSystemInfoAuth(base, token)
        val plugins = jellyfinClient.getPlugins(base, token)
        val tasks = jellyfinClient.getScheduledTasks(base, token).orEmpty()
        val network = jellyfinClient.getNetworkConfiguration(base, token)

        // FR-212-1 — a Jellyfin that cannot answer renders "Couldn't reach Jellyfin" for the whole
        // surface, never an empty finding list (which would read as "everything is fine").
        if (libraries.isEmpty() && encoding == null && systemInfo == null) {
            return AdvisorResponse(reachable = false, computedAt = now)
        }

        // FR-246-11 — storage is resolved for EVERY Jellyfin library it can be resolved for, managed or
        // not. A spindle does not care who owns a library's metadata, and on this household the worst
        // configured library (chapter images + trickplay + LUFS, all on, on the same disk as two managed
        // ones) is a skipped one, so the old managed-only gate rendered nothing for it.
        val storageByLibrary: Map<String, DeviceInfo?> = libraries.associate { it.id to resolveLibraryDevice(it, cfg) }

        // Phase 242 FR-242-1 — metadata-ownership findings, unlike the performance ones above, DO gate on
        // jellystructure managing the library: a skipped library is Jellyfin's to manage and a finding
        // there would be noise. `Blandet` has the NFO saver on and is deliberately silent for that reason,
        // while still receiving 246's storage findings, because the two questions are genuinely different.
        val managedIds = managedJellyfinIds(cfg)

        val perLibrary = mutableListOf<LibraryAdvisorSection>()
        for (lib in libraries) {
            val managed = lib.id in managedIds
            // Phase 242 FR-242-7 — a managed library whose whole LibraryOptions object is missing used to
            // yield zero findings and therefore render exactly like one whose settings are perfect. It is
            // now a distinct, transported state rather than silence.
            if (managed && lib.libraryOptions == null) {
                perLibrary += LibraryAdvisorSection(lib.name, emptyList(), optionsUnavailable = true)
                continue
            }
            val libFindings = (
                perLibraryFindings(lib, storageByLibrary[lib.id], tasks) +
                    (if (managed) metadataOwnershipFindings(lib) else emptyList())
                ).sortedBySeverity()
            if (libFindings.isNotEmpty()) perLibrary += LibraryAdvisorSection(lib.name, libFindings)
        }

        val serverWide = mutableListOf<AdvisorFinding>()
        // Phase 244 — the security section. Its findings carry CRITICAL severity, so 246's sort puts
        // them above every performance finding without needing a second ordering rule.
        serverWide += exposureFindings(jellyfinClient, cfg, base, token, network)
        if (encoding != null) serverWide += serverWideEncodingFindings(encoding, systemInfo)

        // FR-212-6 — host storage, deduped by device: several libraries can share one spindle (Film,
        // Musik and Blandet all sit on sdc here), and the finding is about the DEVICE, not the library.
        val devices = storageByLibrary.values.filterNotNull().filter { it.rotational == true }
            .distinctBy { it.device }
        for (dev in devices) serverWide += hostStorageFindings(dev)
        swappinessFinding()?.let { serverWide += it }

        restartPendingFinding(systemInfo, plugins)?.let { serverWide += it }

        return AdvisorResponse(
            reachable = true,
            computedAt = now,
            serverWide = serverWide.sortedBySeverity(),
            perLibrary = perLibrary,
        )
    }

    internal fun List<AdvisorFinding>.sortedBySeverity(): List<AdvisorFinding> =
        sortedBy { SEVERITY_ORDER.indexOf(it.severity).let { i -> if (i < 0) SEVERITY_ORDER.size else i } }

    // ── FR-212-4 / FR-246-12 — per-library findings ─────────────────────────────

    private fun perLibraryFindings(lib: JellyfinLibrary, storage: DeviceInfo?, tasks: List<JellyfinTaskInfo>): List<AdvisorFinding> {
        val opts = lib.libraryOptions ?: return emptyList()
        val path = "Dashboard → Libraries → ${lib.name} → Manage library"
        val out = mutableListOf<AdvisorFinding>()
        val rotational = storage?.rotational == true
        val disk = storage?.device?.let { " ($it)" } ?: ""

        // (a)
        val chapterImagesOnRotational = opts.enableChapterImageExtraction && rotational
        if (chapterImagesOnRotational) {
            out += AdvisorFinding(
                id = "chapter_images_${lib.id}",
                severity = WARNING,
                summary = "Chapter image extraction is on, on rotational storage",
                currentValue = "Enable chapter image extraction: On" + taskSuffix(tasks, TASK_CHAPTER_IMAGES),
                costHere = "Every affected title in ${lib.name} is read in full to cut chapter thumbnails, on a spinning disk$disk.",
                navigationPath = path,
                fieldLabel = "\"Enable chapter image extraction\" (OptionExtractChapterImage)",
                recommendation = "Turn off, or accept the cost knowingly.",
                // FR-246-12 — name the real consumer. Ravilo does not render a chapter thumbnail.
                tradeoff = "Jellyfin's own clients lose chapter-selection thumbnails in the scrub bar for this library. Ravilo shows none either way.",
            )
        }
        // (b) — only when (a) also holds
        if (chapterImagesOnRotational && opts.extractChapterImagesDuringLibraryScan) {
            out += AdvisorFinding(
                id = "chapter_images_during_scan_${lib.id}",
                severity = WARNING,
                summary = "Chapter images extract during the library scan itself",
                currentValue = "Extract chapter images during the library scan: On",
                costHere = "The chapter-image work runs inside every library scan instead of the dedicated nightly task — Jellyfin's own help text: \"The process can be slow, resource intensive, and may require several gigabytes of space… It is not recommended to run this task during peak usage hours.\"",
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
                severity = WARNING,
                summary = "Trickplay image extraction is on, on rotational storage",
                currentValue = "Enable trickplay image extraction: On" + taskSuffix(tasks, TASK_TRICKPLAY),
                costHere = "Every title in ${lib.name} is decoded end to end to cut scrub-preview thumbnails, on a spinning disk$disk. The work is a whole-file decode, so it costs CPU as well as reads — and all of it is CPU if Jellyfin has no hardware acceleration selected (see the server-wide findings).",
                navigationPath = path,
                fieldLabel = "\"Enable trickplay image extraction\" (OptionExtractTrickplayImage)",
                recommendation = "Turn off unless Jellyfin's own web client is used for scrubbing in this library.",
                // FR-246-12 — PlaybackService.kt:530 and :939 both hardcode trickplayUrl = null.
                tradeoff = "Jellyfin's own clients lose the hover/scrub thumbnail previews for this library. Ravilo never displays one — it sends no trickplay URL at all — so nothing changes there.",
            )
        }
        // (d)
        if (opts.enableLufsScan && rotational) {
            out += AdvisorFinding(
                id = "lufs_${lib.id}",
                severity = WARNING,
                summary = "LUFS loudness scan is on, on rotational storage",
                currentValue = "Enable LUFS scan: On",
                costHere = "The full audio track of every title in ${lib.name} is read to measure loudness, on a spinning disk$disk.",
                navigationPath = path,
                fieldLabel = "\"Enable LUFS scan\" (LabelEnableLUFSScan)",
                recommendation = "Turn off unless Jellyfin's own clients are relied on for loudness normalisation.",
                tradeoff = "Jellyfin's own clients lose automatic loudness normalisation for this library. Ravilo reads no loudness value, so nothing changes there.",
            )
        }
        // (e) — consistency check, fires regardless of storage
        if (!opts.enableTrickplayImageExtraction && opts.extractTrickplayImagesDuringLibraryScan) {
            out += AdvisorFinding(
                id = "trickplay_contradiction_${lib.id}",
                severity = WARNING,
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

    /** FR-246-12 — what the owning scheduled task is doing right now, appended to the finding's current
     *  value. This is the difference between "consider off" about a hypothetical cost and the same
     *  sentence about a job that is running at this moment and has not completed a pass in two days. It
     *  reports only what `GET /ScheduledTasks` answers; jellystructure has no access to Jellyfin's log
     *  directory and the spec's non-goals say so explicitly. */
    private fun taskSuffix(tasks: List<JellyfinTaskInfo>, key: String): String {
        val task = tasks.firstOrNull { it.key == key } ?: return ""
        val name = task.name ?: key
        val running = task.state.equals("Running", ignoreCase = true)
        val progress = task.currentProgressPercentage?.let { " at ${(it * 10).toInt() / 10.0}%" } ?: ""
        val last = task.lastExecutionResult?.endTimeUtc?.take(19)?.let { " · last completed pass $it UTC" } ?: " · never completed a pass"
        return if (running) " · \"$name\" is running now$progress$last" else " · \"$name\" is ${task.state ?: "idle"}$last"
    }

    // ── Phase 244 — exposure ────────────────────────────────────────────────────

    /** FR-244-4's **Re-check**, and FR-244-8's health-endpoint source.
     *
     *  Deliberately NOT a `force` flag on [findings]: the advisor's 5-minute cache would otherwise hand
     *  a Re-check the stale answer, so an operator would set `KnownProxies` correctly, press the button,
     *  watch the finding stay, and conclude the guidance is wrong — which is the failure this phase's own
     *  open question 1 worries about, reached without any Jellyfin restart being involved. Re-running the
     *  whole advisor pass to answer one question would also be slower and noisier, so this runs only the
     *  probe.
     *
     *  It invalidates the cached pass on the way out, so the page's own findings agree with the answer
     *  the operator was just given rather than disagreeing for up to five minutes.
     *
     *  Stays on the BACKGROUND gate: operator-initiated is still advisory, and must never take a
     *  reserved interactive permit from playback negotiation. */
    suspend fun exposureCheck(jellyfinClient: JellyfinClient, cfg: AppConfig): List<AdvisorFinding> {
        val base = cfg.apiKeys.jellyfinUrl
        val token = cfg.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) return emptyList()
        return kotlinx.coroutines.withContext(dev.jellystructure.ops.GateClass.BACKGROUND) {
            val network = jellyfinClient.getNetworkConfiguration(base, token)
            exposureFindings(jellyfinClient, cfg, base, token, network)
        }
    }

    /** The Re-check button's own path: [exposureCheck] plus dropping the cached full pass, so the page's
     *  findings agree with the answer the operator was just given instead of disagreeing for up to five
     *  minutes. Deliberately NOT done inside [exposureCheck] — `/health/full` calls that one too, and a
     *  polled endpoint must not be able to invalidate an advisory cache as a side effect. */
    suspend fun exposureRecheck(jellyfinClient: JellyfinClient, cfg: AppConfig): List<AdvisorFinding> =
        exposureCheck(jellyfinClient, cfg).also { cached = null; cachedAt = 0 }


    /** FR-244-1/2/3/4/5 — the two security findings, shared with `/health/full` per FR-244-8.
     *
     *  **How FR-244-1's check is built, and why it is not the probe alone.** The dev review's blocking
     *  item was that jellystructure reaches Jellyfin *through* the same reverse proxy the finding is
     *  about (`jellyfin_url` is `https://jellyfin.example.net` on this household), and Caddy **appends**
     *  to `X-Forwarded-For` rather than replacing it — so once `KnownProxies` is set, which entry
     *  Jellyfin selects from that list decides the probe's answer, and the discriminating case was never
     *  measured through this path. A probe that cannot observe the capability is worse than no probe.
     *
     *  So the check reads two signals and is only definitive where it genuinely is:
     *
     *  - **`KnownProxies` empty** is decisive on its own, and needs no inference about proxies: with it
     *    empty Jellyfin ignores `X-Forwarded-For` outright, so every caller is classified by the address
     *    it arrived from, which behind any reverse proxy is private. Measured live 2026-09-19 — the
     *    probe returned `IsInNetwork: true` *identically with and without* the header, which is the
     *    header being ignored, observed rather than assumed.
     *  - **`KnownProxies` set** hands the question to the probe. `IsInNetwork: false` clears the
     *    finding. `IsInNetwork: true` is the case jellystructure cannot tell apart — a wrong proxy
     *    address and the append-hazard look the same from here — so it says exactly that instead of
     *    claiming the hole is open or closed. An advisor that guesses in the one state it cannot see is
     *    how an operator stops believing the rest of the page. */
    private suspend fun exposureFindings(
        jellyfinClient: JellyfinClient, cfg: AppConfig, base: String, token: String,
        network: dev.jellystructure.auth.JellyfinNetworkConfig?,
    ): List<AdvisorFinding> {
        network ?: return emptyList()
        // FR-244-2, corrected by the dev review: gate on Jellyfin's OWN remote access, not on
        // jellystructure's `public_url`. `public_url` says *this* product is reachable from outside; the
        // finding is about whether *Jellyfin* is. They correlate on this household and nothing makes
        // them: a LAN-only Jellyfin behind an exposed jellystructure would get a finding it cannot act
        // on, and an exposed Jellyfin behind a LAN-only jellystructure — the more dangerous case — would
        // get silence.
        if (network.enableRemoteAccess != true) return emptyList()

        val out = mutableListOf<AdvisorFinding>()
        val proxies = network.knownProxies.orEmpty().filter { it.isNotBlank() }
        val path = "Dashboard → Networking"

        if (proxies.isEmpty()) {
            out += AdvisorFinding(
                id = "known_proxies_empty",
                severity = CRITICAL,
                action = "recheck_exposure",
                summary = "Anyone who can reach this server from the internet can restart it, with no password",
                currentValue = "Known proxies: (empty) · Remote access: enabled",
                // FR-244-3 — state the consequence, not the setting.
                costHere = "Jellyfin lets a caller it considers in-network restart it without authenticating, and it decides that from the address the request arrived from. With no known proxies configured it ignores the forwarded-for header, so every request coming through the reverse proxy — including every request from the internet — is classified as in-network. That is the impact of CVE-2025-32012 with none of the IP spoofing that CVE describes, reachable by anyone who knows the address.",
                navigationPath = path,
                fieldLabel = "\"Known proxies\" (KnownProxies)",
                // FR-244-4 — guide the fix, and do not guess the value. A wrong one silently leaves the
                // hole open while looking fixed.
                recommendation = "Set it to the address Jellyfin sees requests arriving *from* — your reverse proxy — not the client's address. jellystructure will not guess it: it cannot see that address, and a wrong value leaves this open while looking fixed. Set it, then use Re-check below. Jellyfin may need a restart before it takes effect — do that when nobody is watching.",
                tradeoff = "None for a proxied installation. It is what makes Jellyfin's own in-network rules mean what they are supposed to mean.",
            )
        } else {
            val endpoint = jellyfinClient.probeEndpointClassification(base, token)
            if (endpoint != null && endpoint.isInNetwork) {
                out += AdvisorFinding(
                    id = "known_proxies_unconfirmed",
                    severity = WARNING,
                    action = "recheck_exposure",
                    summary = "Known proxies is set, but this server still classifies a public caller as in-network",
                    currentValue = "Known proxies: ${proxies.joinToString(", ")} · a caller presenting ${dev.jellystructure.auth.EXPOSURE_PROBE_ADDRESS} is still reported IsInNetwork: true",
                    costHere = "Either the configured address is not the one Jellyfin actually sees requests arriving from, or Jellyfin needs a restart to pick the change up, or the header was rewritten on the way here — jellystructure reaches Jellyfin through the same proxy this setting is about, so it cannot tell those apart from where it stands. What it can say is that the classification has not changed, and unauthenticated restart is reachable while that is true.",
                    navigationPath = path,
                    fieldLabel = "\"Known proxies\" (KnownProxies)",
                    recommendation = "Check the value against what Jellyfin logs as the remote address for an incoming request, and restart Jellyfin if it has not been restarted since the change — when nobody is watching. Then Re-check.",
                    tradeoff = "n/a — this is a state jellystructure cannot resolve from here, reported rather than guessed at.",
                )
            }
        }

        // FR-244-5 — honest, and offers no fix, because there is none. Not softened: naming a problem,
        // saying no fix exists, linking upstream and explicitly forbidding the plausible-but-wrong
        // remedy is the whole requirement.
        out += AdvisorFinding(
            id = "anonymous_media_routes",
            severity = INFO,
            summary = "Jellyfin serves media to callers with no credential at all",
            currentValue = "Remote access: enabled · GET /Videos/{id}/stream and GET /Items/{id}/Images/* answer without authentication",
            costHere = "Anyone holding an item id can download the file or fetch its artwork from this server without signing in. Verified on both 12.1.0 and 10.11.11 — no header, an invalid token and a valid admin token all return the same 206.",
            navigationPath = "Not a setting — upstream behaviour (Jellyfin issues #1501, #5415, #13986)",
            fieldLabel = "n/a — there is nothing here to set",
            recommendation = "No fix exists. `VideosController` carries no authorize attribute and this appears to be a deliberate compatibility trade for DLNA and browser clients. The only real levers are not exposing the server, or accepting it knowingly. **Do not put authentication in front of those paths at the reverse proxy** — Ravilo's own playback depends on them answering anonymously, so it would break every client in the household.",
            tradeoff = "n/a",
        )
        return out
    }

    // ── Phase 242 — metadata ownership ──────────────────────────────────────────

    /** FR-242-6 — the one gate both consumers read. `/health/full` used to build its own set with
     *  `filter { !it.skip }` and no `jellyfinId` condition, so the two surfaces could disagree about
     *  which libraries this product manages. They now cannot. This is deliberately the advisor's
     *  stricter form: a mapping with no `jellyfinId` names no Jellyfin library at all. */
    fun managedJellyfinIds(cfg: AppConfig): Set<String> =
        cfg.libraries.filter { !it.skip && it.jellyfinId.isNotBlank() }.map { it.jellyfinId }.toSet()

    /** FR-242-1/2/3 — whether Jellyfin is configured to write metadata over jellystructure's, or to go
     *  and fetch its own. Pure, and shared with `/health/full` per FR-242-6: the advisor is the human
     *  surface and the health endpoint the machine-readable one, and they must read one resolver.
     *
     *  The whole premise of this product is that jellystructure owns metadata and Jellyfin reads what is
     *  on disk, and until phase 242 exactly one setting was checked against that premise, on an endpoint
     *  nobody opens. */
    fun metadataOwnershipFindings(lib: JellyfinLibrary): List<AdvisorFinding> {
        val opts = lib.libraryOptions ?: return emptyList()
        val path = "Dashboard → Libraries → ${lib.name} → Manage library"
        val out = mutableListOf<AdvisorFinding>()

        // FR-242-1. Note `metadataSavers` is nullable: absent means Jellyfin did not tell us, which is
        // not the same as "none configured" and must not be reported as either.
        if (opts.metadataSavers?.any { it.equals("Nfo", ignoreCase = true) } == true) {
            out += AdvisorFinding(
                id = "nfo_saver_${lib.id}",
                severity = CRITICAL,
                summary = "Jellyfin's NFO metadata saver is on for a library jellystructure manages",
                currentValue = "Metadata savers: ${opts.metadataSavers.joinToString(", ")}",
                costHere = "Jellyfin re-writes the NFO files in ${lib.name} after every refresh, on top of the ones jellystructure wrote. This product's entire arrangement is that it owns metadata and Jellyfin reads what is on disk; with this on, the two write to the same files and the last writer wins.",
                navigationPath = path,
                fieldLabel = "\"Metadata savers\" → uncheck \"Nfo\"",
                recommendation = "Uncheck Nfo. Then check whether the NFO files jellystructure wrote for this library still say what it wrote — a saver that has been on for a while has already overwritten them.",
                tradeoff = "Jellyfin stops maintaining its own copy of the metadata on disk. That is the intended arrangement here: jellystructure writes the NFOs.",
            )
        }

        // FR-242-2 — the master switch for Jellyfin fetching metadata itself, read by nothing before 242.
        if (opts.enableInternetProviders == true) {
            out += AdvisorFinding(
                id = "internet_providers_${lib.id}",
                severity = WARNING,
                summary = "Jellyfin fetches its own metadata for a library jellystructure manages",
                currentValue = "Enable internet providers: On",
                costHere = "Jellyfin goes to external metadata providers for ${lib.name} itself, in parallel with jellystructure doing the same job — two sources of truth for one library, and outbound requests that none of this product's pacing (phase 183) knows about.",
                navigationPath = path,
                fieldLabel = "\"Enable internet providers\" (EnableInternetProviders)",
                recommendation = "Turn off, so metadata for this library comes from one place.",
                tradeoff = "Jellyfin will show only what jellystructure has written. That is the point, but it does mean a gap in jellystructure's metadata is now visible rather than being papered over by Jellyfin's own fetch.",
            )
        }

        // FR-242-3 — per-type fetchers, with the local-extractor carve-out.
        for (t in opts.typeOptions.orEmpty()) {
            val type = t.type ?: continue
            val metadataFetchers = t.metadataFetchers.orEmpty().filter { it.isNotBlank() }
            if (metadataFetchers.isNotEmpty()) {
                out += AdvisorFinding(
                    id = "metadata_fetchers_${lib.id}_$type",
                    severity = WARNING,
                    summary = "Jellyfin has metadata fetchers configured for $type in ${lib.name}",
                    currentValue = "$type → Metadata downloaders: ${metadataFetchers.joinToString(", ")}",
                    costHere = "Even with the master switch off, a configured fetcher is a second route to an external metadata provider for a library jellystructure already owns.",
                    navigationPath = path,
                    fieldLabel = "\"Metadata downloaders\" for $type",
                    recommendation = "Clear them, unless this library is deliberately Jellyfin's to enrich.",
                    tradeoff = "Same as above: one source of truth, and its gaps become visible.",
                )
            }
            val external = t.imageFetchers.orEmpty().filter { it.isNotBlank() && it !in LOCAL_ONLY_IMAGE_FETCHERS }
            if (external.isNotEmpty()) {
                out += AdvisorFinding(
                    id = "image_fetchers_${lib.id}_$type",
                    severity = WARNING,
                    summary = "Jellyfin fetches its own artwork for $type in ${lib.name}",
                    currentValue = "$type → Image fetchers: ${external.joinToString(", ")}",
                    costHere = "Jellyfin downloads artwork for ${lib.name} itself, alongside the artwork jellystructure resolves, writes and serves — so which image a viewer sees depends on which one wrote last.",
                    navigationPath = path,
                    fieldLabel = "\"Image fetchers\" for $type",
                    // The carve-out is stated, not just applied, so nobody re-adds the two local ones.
                    recommendation = "Clear the external ones. Purely local extractors (${LOCAL_ONLY_IMAGE_FETCHERS.joinToString(", ")}) read the file on disk, reach no external service, and are deliberately not flagged.",
                    tradeoff = "Artwork comes only from jellystructure, which is the arrangement — a title it has no art for now shows none rather than showing Jellyfin's pick.",
                )
            }
        }
        return out
    }

    // ── FR-212-5 / FR-246-1..4 — server-wide encoding findings ──────────────────

    private fun serverWideEncodingFindings(
        enc: dev.jellystructure.auth.JellyfinEncodingConfig,
        systemInfo: dev.jellystructure.auth.JellyfinSystemInfoAuth?,
    ): List<AdvisorFinding> {
        val path = "Dashboard → Playback → Transcoding"
        val out = mutableListOf<AdvisorFinding>()

        // FR-246-1 — the field that decides whether any of the rest means what it says. This is the one
        // the old (c) finding read around: it reported "Hardware encoding: On" from EnableHardwareEncoding
        // while the accelerator was "none", i.e. advertised and inert.
        val accel = enc.hardwareAccelerationType?.trim().orEmpty()
        val accelOff = accel.isEmpty() || accel.equals("none", ignoreCase = true)
        if (accelOff && enc.enableHardwareEncoding) {
            out += AdvisorFinding(
                id = "hwaccel_none",
                severity = CRITICAL,
                summary = "Hardware encoding is switched on with no accelerator selected",
                currentValue = "Hardware acceleration: ${if (accel.isEmpty()) "(unset)" else accel} · Hardware encoding: On",
                costHere = "\"Hardware encoding\" is on and does nothing: with no acceleration type selected every transcode, and every background decode Jellyfin runs for trickplay and chapter images, is done in software on the CPU. A transcode that would be a fraction of one core on a GPU takes whole cores, and 4K material may not reach real time at all.",
                navigationPath = path,
                fieldLabel = "\"Hardware acceleration\" (HardwareAccelerationType)",
                recommendation = "Pick the accelerator this host actually has from Jellyfin's own dropdown, then confirm with one real transcode — jellystructure cannot see from its own container whether a GPU is present and usable, so it will not name one for you. Worth checking after any Jellyfin upgrade: an upgrade rewrites this file.",
                tradeoff = "None while the setting is inert. Once an accelerator is selected, check that the codecs under \"Enable hardware decoding for\" match the library, since that list is rewritten by an upgrade too.",
            )
        }

        // FR-246-3 — throttling and segment deletion are ONE finding. They bound the same quantity from
        // two ends (how far ahead of the playhead a transcode runs, and how much behind it is kept), and
        // rendering them separately invited applying one.
        if (!enc.enableThrottling || !enc.enableSegmentDeletion) {
            // FR-246-4 — the RESOLVED path from /System/Info, never the word "(default)". The encoding
            // config's own value is unset on this server while Jellyfin resolves it to /cache/transcodes,
            // and printing "(default)" is what hid the path moving during the 12.1 upgrade.
            val tempPath = systemInfo?.transcodingTempPath?.takeIf { it.isNotBlank() }
                ?: enc.transcodingTempPath?.takeIf { it.isNotBlank() }
                ?: "unknown"
            val parts = mutableListOf<String>()
            if (!enc.enableThrottling) parts += "Throttle Transcodes: Off (Throttle delay: ${enc.throttleDelaySeconds}s — set but inert while this is off)"
            else parts += "Throttle Transcodes: On (${enc.throttleDelaySeconds}s)"
            if (!enc.enableSegmentDeletion) parts += "Delete segments: Off (Time to keep segments: ${enc.segmentKeepSeconds}s — set but inert while this is off)"
            else parts += "Delete segments: On (${enc.segmentKeepSeconds}s)"
            parts += "Transcode temp path (as Jellyfin resolves it): $tempPath"

            out += AdvisorFinding(
                id = "transcode_pacing",
                severity = WARNING,
                summary = if (!enc.enableThrottling && !enc.enableSegmentDeletion)
                    "Neither Throttle Transcodes nor Delete segments is on"
                else if (!enc.enableThrottling) "Throttle Transcodes is off"
                else "Delete segments is off",
                currentValue = parts.joinToString(" · "),
                costHere = "A transcode runs ahead of playback at whatever speed the machine allows and keeps everything it has produced, so one session writes the whole remaining file into \"$tempPath\" whether or not the viewer watches it. Jellyfin's own \"Clean Transcode Directory\" task only ever cleans the path configured now, so if that path has changed the previous directory is never collected.",
                navigationPath = path,
                fieldLabel = "\"Throttle Transcodes\" (AllowFfmpegThrottling) and \"Delete segments\" (AllowSegmentDeletion)",
                recommendation = "Turn both on. They are one fix, not two: throttling caps how far ahead the encoder runs, segment deletion reclaims what is behind the playhead. Both delays are already configured and do nothing until their switches are on.",
                tradeoff = "After a seek a transcode takes slightly longer to build its lead again, and a seek backwards past a deleted segment can fail rather than degrade — the exposed window is whatever \"Time to keep segments\" is set to (${enc.segmentKeepSeconds}s here).",
            )
        }

        // FR-246-2 — the HEVC-encoding finding is deleted, not repaired. It read EnableHardwareEncoding
        // (inert without an accelerator, see above), recommended libx265-on-CPU in the state this server
        // was actually in, justified itself with Ravilo device decode ceilings while Ravilo's own
        // DeviceProfile (JellyfinClient.kt:76) admits h264 ONLY as a transcode target so the setting can
        // never affect this product's clients, and counted any device with a recorded ceiling including
        // ones no title in the library comes near. No predicate over those ceilings can make it true.

        // FR-246-9 — real, and asks for nothing: information, not a warning.
        if (enc.allowOnDemandMetadataBasedKeyframeExtractionForExtensions.isNotEmpty()) {
            val exts = enc.allowOnDemandMetadataBasedKeyframeExtractionForExtensions.joinToString(", ")
            out += AdvisorFinding(
                id = "keyframe_extraction_extensions",
                severity = INFO,
                summary = "On-demand keyframe extraction is enabled for $exts",
                currentValue = "AllowOnDemandMetadataBasedKeyframeExtractionForExtensions: $exts",
                costHere = "When a client needs keyframe positions a file's own index cannot supply, Jellyfin scans that file for them — a real cost on rotational storage, but only for the files and seeks that need it, not on every play.",
                navigationPath = "Not exposed in the Jellyfin admin UI — file-only (encoding.xml)",
                fieldLabel = "AllowOnDemandMetadataBasedKeyframeExtractionForExtensions (no on-screen label; edit encoding.xml directly)",
                recommendation = "Nothing to do. Listed so it isn't a surprise if a seek on one of these containers is slow. Changing it means editing Jellyfin's config file by hand.",
                tradeoff = "Turning it off (file edit) risks less accurate seeking on affected files if the fallback keyframe data is imprecise.",
            )
        }
        return out
    }

    // ── FR-212-6 / FR-246-5..8 — host storage findings ──────────────────────────

    private fun hostStorageFindings(dev: DeviceInfo): List<AdvisorFinding> {
        val out = mutableListOf<AdvisorFinding>()
        val persistNote = "None of this survives a reboot without a udev rule."
        if (dev.scheduler != null && dev.scheduler != "bfq") {
            // FR-246-7 — the command has to work. bfq is a module; where it is not loaded it does not
            // appear in the device's available list and `echo bfq > .../scheduler` returns EINVAL.
            val bfqAvailable = dev.availableSchedulers.any { it.equals("bfq", ignoreCase = true) }
            val command = if (bfqAvailable) "echo bfq | sudo tee /sys/block/${dev.device}/queue/scheduler"
            else "sudo modprobe bfq && echo bfq | sudo tee /sys/block/${dev.device}/queue/scheduler"
            val modNote = if (bfqAvailable) "" else
                " BFQ is a kernel module and is not loaded here — it is absent from this device's scheduler list, so the write fails with \"Invalid argument\" without the modprobe. Persist it with a line reading `bfq` in /etc/modules-load.d/."
            out += AdvisorFinding(
                id = "scheduler_${dev.device}",
                severity = WARNING,
                summary = "${dev.device} runs ${dev.scheduler}, not BFQ",
                currentValue = "/sys/block/${dev.device}/queue/scheduler = ${dev.scheduler}" +
                    (if (dev.availableSchedulers.isNotEmpty()) " (available: ${dev.availableSchedulers.joinToString(", ")})" else ""),
                // FR-246-7 — both halves, including the half that limits what switching buys.
                costHere = "jellystructure runs its own background ffmpeg under ionice -c3 (FfmpegRunner.kt) to keep it out of playback's way, and ${dev.scheduler} ignores ionice classes entirely — only BFQ and the legacy CFQ honour them — so that protection has never taken effect on this device. Note what it would and would not cover: Jellyfin's own background work (trickplay, chapter images, LUFS, subtitle extraction) runs in Jellyfin's container under no ionice at all, and on this host that is the larger source of disk contention. Switching schedulers restores a protection that never worked; it does not reprioritise Jellyfin's work.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = command,
                recommendation = "Switch to BFQ if you want ionice to mean something.$modNote $persistNote",
                tradeoff = "BFQ carries more per-request CPU overhead than ${dev.scheduler} and can lower peak sequential throughput slightly — a real trade, not a free win. Turning the per-library extraction work off removes that I/O entirely and is the larger lever.",
            )
        }
        if (dev.readAheadKb != null && dev.readAheadKb <= 256) {
            // FR-246-5 — the cost field states what the PRESENT value costs. The old copy described the
            // benefit of raising it, which read as though 128 KB already delivered it.
            out += AdvisorFinding(
                id = "readahead_${dev.device}",
                severity = WARNING,
                summary = "${dev.device}'s read-ahead is at the ${dev.readAheadKb} KB default",
                currentValue = "/sys/block/${dev.device}/queue/read_ahead_kb = ${dev.readAheadKb}",
                costHere = "A sequential read of a large media file is served as many small requests instead of a few large ones, so a spinning disk spends more of its time on per-request overhead and head movement than it needs to. A minor lever — 16 MB of read-ahead is a small fraction of a typical remux — but a real one.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = "echo 16384 | sudo tee /sys/block/${dev.device}/queue/read_ahead_kb",
                recommendation = "Raise to 8–16 MB. $persistNote",
                tradeoff = "More RAM held by speculative read-ahead that may go unused if playback jumps around instead of reading sequentially.",
            )
        }
        if (dev.maxSectorsKb != null && dev.maxHwSectorsKb != null && dev.maxSectorsKb * 4 < dev.maxHwSectorsKb) {
            // FR-246-6 — recommend a latency budget, not the hardware ceiling. mq-deadline dispatches a
            // request whole, so request size IS head-of-line blocking time on a server whose whole job is
            // not stalling playback.
            val target = minOf(MAX_SECTORS_TARGET_KB, dev.maxHwSectorsKb)
            val targetMs = (target / SEQUENTIAL_KB_PER_MS).toInt()
            val ceilingMs = (dev.maxHwSectorsKb / SEQUENTIAL_KB_PER_MS).toInt()
            out += AdvisorFinding(
                id = "max_sectors_${dev.device}",
                severity = WARNING,
                summary = "${dev.device}'s max_sectors_kb (${dev.maxSectorsKb}) is well below what it could carry",
                currentValue = "/sys/block/${dev.device}/queue/max_sectors_kb = ${dev.maxSectorsKb} (hardware ceiling ${dev.maxHwSectorsKb})",
                costHere = "A raised read-ahead is split into more, smaller requests than the hardware would need, so part of a read-ahead increase is given back at the queue.",
                navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
                fieldLabel = "echo $target | sudo tee /sys/block/${dev.device}/queue/max_sectors_kb",
                recommendation = "Raise to $target KB. Deliberately not the hardware ceiling: ${dev.maxHwSectorsKb} KB is a single request of about ${ceilingMs} ms at a typical sequential rate, and this scheduler dispatches a request whole. $persistNote",
                tradeoff = "A ${target} KB request occupies the device for roughly ${targetMs} ms, so an unrelated small read queued behind one waits that long. That is the whole reason the recommendation stops well short of the ceiling.",
            )
        }
        return out
    }

    /** FR-246-8 — evidenced by paging, not by page cache. The old predicate required `Cached > MemFree * 2`
     *  and rendered that as the evidence, which is true of every healthy Linux host: near-zero MemFree is
     *  normal, and phase 215's own FR-215-2 already says page cache is neither free nor spare memory. The
     *  real evidence is that this host has genuinely paged — `/proc/vmstat`'s pswpout — and that processes
     *  are sitting in swap now. */
    private fun swappinessFinding(): AdvisorFinding? {
        val swappiness = readProcInt("/proc/sys/vm/swappiness") ?: return null
        if (swappiness <= SWAPPINESS_THRESHOLD) return null
        val mem = readMeminfo() ?: return null
        val swapUsedKb = mem.swapTotalKb - mem.swapFreeKb
        val pswpout = readVmstatLong("pswpout") ?: return null
        if (swapUsedKb <= 0 || pswpout < PSWPOUT_PAGES_THRESHOLD) return null
        // 4 KB pages; stated as GB because "109 806 226 pages" is not a quantity anyone can weigh.
        val outGb = pswpout * 4 / 1_048_576
        val inGb = (readVmstatLong("pswpin") ?: 0L) * 4 / 1_048_576
        return AdvisorFinding(
            id = "swappiness",
            severity = WARNING,
            summary = "vm.swappiness is $swappiness and this host has paged ${outGb} GB out to swap",
            currentValue = "/proc/sys/vm/swappiness = $swappiness · ${swapUsedKb / 1_048_576} GB currently in swap · pswpout ${outGb} GB / pswpin ${inGb} GB since boot",
            costHere = "At this setting the kernel is willing to write process memory out to make room for more page cache. This host has actually done it, repeatedly, and some of what is in swap belongs to the processes serving video: a page that has to be read back from disk before a request can be answered is a stall the request did not need.",
            navigationPath = "Host shell (not a Jellyfin or jellystructure setting)",
            fieldLabel = "sudo sysctl vm.swappiness=10",
            recommendation = "Lower to ~10, and persist it with a line in /etc/sysctl.d/ (e.g. vm.swappiness = 10) — it does not survive a reboot otherwise. Note this only changes future behaviour: the ${swapUsedKb / 1_048_576} GB already in swap stays there until those pages are next touched.",
            tradeoff = "Under real memory pressure the kernel keeps slightly less page cache, favouring process memory instead.",
        )
    }

    // ── FR-212-7 — restart pending ──────────────────────────────────────────────

    private fun restartPendingFinding(systemInfo: dev.jellystructure.auth.JellyfinSystemInfoAuth?, plugins: List<dev.jellystructure.auth.JellyfinPluginInfo>?): AdvisorFinding? {
        val pendingPlugins = plugins.orEmpty().filter { it.status == "Restart" }.map { it.name }
        val hasPending = (systemInfo?.hasPendingRestart == true) || pendingPlugins.isNotEmpty()
        if (!hasPending) return null
        val pluginNote = if (pendingPlugins.isNotEmpty()) " (${pendingPlugins.joinToString(", ")})" else ""
        return AdvisorFinding(
            // FR-246-9 — a fact, not a warning: it asks for nothing and claims no cost.
            id = "restart_pending",
            severity = INFO,
            summary = "Jellyfin reports a pending restart$pluginNote",
            currentValue = "System/Info HasPendingRestart: ${systemInfo?.hasPendingRestart ?: false}",
            costHere = "Stated as a fact only — this finding does not claim a performance cost.",
            navigationPath = "Dashboard → Plugins (or Dashboard's own restart banner, if Jellyfin shows one)",
            fieldLabel = "n/a — nothing here to set",
            recommendation = "Not offered. Restarting the household's media server is not an admin-panel side effect — decide when, yourself, and preferably when nobody is watching.",
            tradeoff = "n/a",
        )
    }

    // ── Host storage resolution (FR-212-6's core mechanism, widened by FR-246-11) ─

    data class DeviceInfo(
        val device: String,
        val rotational: Boolean?,
        val scheduler: String?,
        val availableSchedulers: List<String> = emptyList(),
        val readAheadKb: Int?,
        val maxSectorsKb: Int?,
        val maxHwSectorsKb: Int?,
    )

    /** FR-246-11 — resolves a Jellyfin library to its backing block device, for libraries jellystructure
     *  manages AND for ones it skips.
     *
     *  A managed library answers directly from its own `local_path`. A skipped one has none, and its
     *  Jellyfin `Locations` entry is a path inside *Jellyfin's* container, which does not exist here —
     *  the two containers mount the same host directories at different internal paths (verified live,
     *  2026-09-15: Jellyfin sees `/media/movies`, jellystructure sees `/mnt/media/jellyfin/movies`).
     *
     *  So the local path is DERIVED from the managed mappings — take the longest managed pair whose
     *  Jellyfin-side root prefixes this location, substitute the jellystructure-side root — and then
     *  **confirmed against the filesystem**. A derived path that exists is a resolution; one that does
     *  not is unknown, and unknown suppresses per FR-212-6. Nothing is guessed that is not then checked.
     *
     *  This matters because the worst-configured library on the household server is a skipped one, on the
     *  same spindle as two managed ones, and the old managed-only gate rendered nothing for it. */
    private fun resolveLibraryDevice(lib: JellyfinLibrary, cfg: AppConfig): DeviceInfo? {
        val managed = cfg.libraries.firstOrNull { it.jellyfinId == lib.id && !it.skip && it.localPath.isNotBlank() }
        if (managed != null) return resolveDevice(managed.localPath)

        val location = lib.locations.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val roots = cfg.libraries
            .filter { it.jellyfinPath.isNotBlank() && it.localPath.isNotBlank() }
            .mapNotNull { rootPair(it.jellyfinPath, it.localPath) }
            .sortedByDescending { it.first.length }
        for ((jellyfinRoot, localRoot) in roots) {
            if (!location.startsWith(jellyfinRoot)) continue
            val derived = localRoot + location.removePrefix(jellyfinRoot)
            if (!runCatching { SystemFileSystem.exists(Path(derived)) }.getOrDefault(false)) continue
            return resolveDevice(derived)
        }
        return null
    }

    /** Strips the segments a Jellyfin path and its jellystructure counterpart end in common, leaving the
     *  two roots that map onto each other: `/media/movies/` + `/mnt/media/jellyfin/movies/` yields
     *  `/media/` -> `/mnt/media/jellyfin/`. Returns null when they share no trailing segment at all (as
     *  `/media/series/` and `/mnt/series/jellyfin/` do here), because then nothing can be derived from
     *  that pair and inventing a relationship is exactly what FR-212-6 forbids. */
    internal fun rootPair(jellyfinPath: String, localPath: String): Pair<String, String>? {
        val j = jellyfinPath.trimEnd('/').split('/')
        val l = localPath.trimEnd('/').split('/')
        var shared = 0
        while (shared < j.size - 1 && shared < l.size - 1 && j[j.size - 1 - shared] == l[l.size - 1 - shared]) shared++
        if (shared == 0) return null
        val jRoot = j.subList(0, j.size - shared).joinToString("/") + "/"
        val lRoot = l.subList(0, l.size - shared).joinToString("/") + "/"
        return jRoot to lRoot
    }

    /** Resolves a jellystructure-local path to its backing block device, reading `/proc/self/mountinfo`
     *  for the mount covering it. Returns null (unknown) rather than guessing — an LVM/device-mapper
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
        val (active, available) = readSysfsScheduler(devName)
        return DeviceInfo(
            device = devName,
            rotational = rotationalStr == 1,
            scheduler = active,
            availableSchedulers = available,
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

    /** `/sys/block/<dev>/queue/scheduler` reads like `none [mq-deadline]` — the active one is bracketed
     *  and every offered one is listed. FR-246-7 needs BOTH: a recommendation to switch to a scheduler
     *  the kernel is not currently offering has to say `modprobe` first, or the command it prints fails
     *  with EINVAL. Returns (active, available) — (null, empty) if unreadable. */
    private fun readSysfsScheduler(dev: String): Pair<String?, List<String>> {
        val raw = runCatching { FileIo.readText(Path("/sys/block/$dev/queue/scheduler")) }.getOrNull()?.trim()
            ?: return null to emptyList()
        val available = raw.split(Regex("\\s+")).map { it.trim('[', ']') }.filter { it.isNotBlank() }
        val bracketed = Regex("""\[([^]]+)]""").find(raw)?.groupValues?.get(1)
        return (bracketed ?: available.firstOrNull()) to available
    }

    private fun readProcInt(path: String): Int? =
        runCatching { FileIo.readText(Path(path)).trim().toInt() }.getOrNull()

    private fun readVmstatLong(key: String): Long? {
        val text = runCatching { FileIo.readText(Path("/proc/vmstat")) }.getOrNull() ?: return null
        for (line in text.lineSequence()) {
            if (line.startsWith("$key ")) return line.removePrefix("$key ").trim().toLongOrNull()
        }
        return null
    }

    private data class MemInfo(val cachedKb: Long, val freeKb: Long, val swapTotalKb: Long, val swapFreeKb: Long)

    private fun readMeminfo(): MemInfo? {
        val text = runCatching { FileIo.readText(Path("/proc/meminfo")) }.getOrNull() ?: return null
        var cached: Long? = null
        var free: Long? = null
        var swapTotal: Long? = null
        var swapFree: Long? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Cached:") -> cached = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("MemFree:") -> free = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("SwapTotal:") -> swapTotal = line.filter { it.isDigit() }.toLongOrNull()
                line.startsWith("SwapFree:") -> swapFree = line.filter { it.isDigit() }.toLongOrNull()
            }
        }
        if (cached == null || free == null) return null
        return MemInfo(cached, free, swapTotal ?: 0L, swapFree ?: 0L)
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
    // Phase 246 FR-246-9/10 — added once, for this phase and for 242's and 244's use, rather than each
    // growing its own variant. Defaulted so no existing producer (notably MemoryBudgetService) changes
    // meaning. `action` is an id the frontend binds a button to; nothing in 246 sets it.
    val severity: String = JellyfinAdvisorService.WARNING,
    val action: String? = null,
)

@Serializable
data class LibraryAdvisorSection(
    @SerialName("library_name") val libraryName: String,
    val findings: List<AdvisorFinding>,
    // Phase 242 FR-242-7 — an empty `findings` list already means "this library is fine", so the state
    // "Jellyfin did not return this library's options at all, and nothing here was checked" needs its own
    // transport or it renders identically to being fine.
    @SerialName("options_unavailable") val optionsUnavailable: Boolean = false,
)

@Serializable
data class AdvisorResponse(
    val reachable: Boolean,
    @SerialName("computed_at") val computedAt: Long,
    @SerialName("server_wide") val serverWide: List<AdvisorFinding> = emptyList(),
    @SerialName("per_library") val perLibrary: List<LibraryAdvisorSection> = emptyList(),
)
