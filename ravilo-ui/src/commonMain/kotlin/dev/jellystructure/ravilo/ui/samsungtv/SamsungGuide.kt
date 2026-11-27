package dev.jellystructure.ravilo.ui.samsungtv

import dev.jellystructure.shared.tv.SamsungTvRequest
import dev.jellystructure.shared.tv.SamsungTvRequestCreate
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What the guide needs from the Android phone; null on every other platform, which is how the row stays away. */
interface SamsungTvPlatform {
    /** The phone's own Wi-Fi address, or null off Wi-Fi. */
    fun phoneAddress(): String?
    /** The body of `http://<ip>:8001/api/v2/`, or null when nothing answers within a short timeout. */
    suspend fun probe(ip: String): String?
    /** DEB-5409 (FR-11) — `http://<ip>:8001/api/v2/applications/RaviloScrn.RaviloScrn`: the status and body the TV answered, or null when nothing did. */
    suspend fun appReply(ip: String): TvAppReply? = null
    /** DEB-5409 (FR-11) — the release tag this phone itself last installed on [duid]; the version when the TV's own answer is not known. */
    fun lastInstalledTag(duid: String): String? = null
    fun rememberInstalledTag(duid: String, tag: String) {}
    suspend fun download(url: String, onProgress: (Long, Long?) -> Unit): ByteArray
    /** The device ids named by the package's distributor certificate; null if it cannot be read. */
    fun packageDeviceIds(wgt: ByteArray): Set<String>?
    val sdb: SdbClient
    fun log(line: String)
}

/** The viewer's three routes (DEB-4842 FR-2); [TvApiClient] in the app, a fake in tests. */
interface SamsungTvBackend {
    suspend fun list(): List<SamsungTvRequest>
    suspend fun create(body: SamsungTvRequestCreate): SamsungTvRequest
    suspend fun withdraw(id: Long): SamsungTvRequest
}

fun TvApiClient.asSamsungBackend(): SamsungTvBackend = object : SamsungTvBackend {
    override suspend fun list() = samsungTvRequests()
    override suspend fun create(body: SamsungTvRequestCreate) = samsungTvRequest(body)
    override suspend fun withdraw(id: Long) = samsungTvWithdraw(id)
}

/** Where a failure's one button leads. */
enum class Target { FIND, STEP1, STEP2, STEP3, ASK, OVERVIEW }

/** DEB-5504 (FR-13) — which remote the viewer holds: a long one with real number keys, or a Smart Remote with the *123* key. */
enum class Remote { KEYS, NOKEYS }

/** One screen of the guide. Everything the screen shows is in the value; nothing is derived in the composable. */
sealed interface GuideScreen {
    data object Loading : GuideScreen
    data class Intro(val wifi: Boolean) : GuideScreen
    data object Searching : GuideScreen
    data class Pick(val tvs: List<SamsungTv>) : GuideScreen
    data object NotFound : GuideScreen
    /** [remote] is null on the question and set on the instruction (DEB-5504, FR-13). */
    data class Step1(val tv: SamsungTv, val remote: Remote? = null) : GuideScreen
    data class Step2(val tv: SamsungTv, val phoneIp: String) : GuideScreen
    data class Step3(val tv: SamsungTv) : GuideScreen
    data class Checking(val tv: SamsungTv) : GuideScreen
    data class Ask(val tv: SamsungTv, val busy: Boolean = false) : GuideScreen
    /** DEB-5905 (FR-10, FR-14) — *Your Samsung TVs*, the home of any viewer with a request. */
    data class Overview(
        val cards: List<TvCard>,
        /** The scan of the phone's subnet is still running: the cards are the server's rows and no TV has answered yet. */
        val scanning: Boolean,
        val found: Int,
        /** False: the phone is not on Wi-Fi, no scan ran, so no card may say *Not found on this Wi-Fi*. */
        val wifi: Boolean,
        /** FR-15 — the server answered a request sent from this guide; stays until the guide is closed. */
        val sent: Boolean = false,
    ) : GuideScreen {
        /** FR-10 item 4: a request whose TV the finished scan did not find. */
        fun notFound(card: TvCard) = wifi && !scanning && card.tv == null && card.request != null && card.candidates.isEmpty()
    }
    data class Installing(val stage: Stage, val fraction: Float?) : GuideScreen
    data class Done(val request: SamsungTvRequest) : GuideScreen
    /** One sentence ([key] with [vars]) and one button, [back], to the step that fixes it. */
    data class Problem(val key: String, val vars: Map<String, String>, val back: Target) : GuideScreen

    enum class Stage { DOWNLOADING, SENDING, INSTALLING }
}

/**
 * DEB-4843 (FR-2–FR-7) — the guide's state machine. Server state is only ever the last read of the viewer's routes; a screen is
 * a value of [GuideScreen], and every failure is a [GuideScreen.Problem] with one way back. DEB-5905 (FR-14) — a viewer with a
 * request lives on [GuideScreen.Overview]; every flow that starts from a card ends there.
 */
class SamsungGuide(
    private val platform: SamsungTvPlatform,
    private val backend: SamsungTvBackend,
    private val scope: CoroutineScope,
) {
    private val _screen = MutableStateFlow<GuideScreen>(GuideScreen.Loading)
    val screen: StateFlow<GuideScreen> = _screen
    private var job: Job? = null
    private var chosen: SamsungTv? = null
    /** DEB-5504 (FR-13) — held for as long as the guide is open, forgotten on [open], never persisted. */
    private var remote: Remote? = null
    /** DEB-5905 (FR-15) — *Request sent* stays above the list until the guide is closed; [open] forgets it. */
    private var sent = false
    /** Where a failed check sends a viewer who has no TV chosen from a card: the overview if they have requests, else the scan. */
    private var home = Target.FIND

    private fun go(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                platform.log("samsung guide: ${e::class.simpleName}: ${e.message}")
                _screen.value = GuideScreen.Problem("samsungtv.problem.generic", emptyMap(), Target.OVERVIEW)
            }
        }
    }

    /** Opens the guide: re-reads the viewer's requests and lands on *Your Samsung TVs*, or on the intro when there is none (FR-14). */
    fun open() = go {
        _screen.value = GuideScreen.Loading
        remote = null
        sent = false
        showOverview()
    }

    /**
     * The overview (FR-10, FR-10d): the server's cards at once, then the scan fills them in. A viewer whose requests are all
     * withdrawn or gone has none, and gets the intro.
     */
    private suspend fun showOverview() {
        val rows = backend.list().filter { it.state != "WITHDRAWN" }
        val wifi = platform.phoneAddress() != null
        if (rows.isEmpty()) { home = Target.FIND; _screen.value = GuideScreen.Intro(wifi); return }
        home = Target.OVERVIEW
        _screen.value = GuideScreen.Overview(serverCards(rows), scanning = wifi, found = 0, wifi = wifi, sent = sent)
        if (!wifi) return
        val found = scan(withVersions = true)
        _screen.value = GuideScreen.Overview(overviewCards(rows, found, platform::lastInstalledTag), scanning = false, found = found.size, wifi = true, sent = sent)
    }

    /** *Search again* on the overview, and the footer's *Try again* with no Wi-Fi. */
    fun refresh() = go { showOverview() }

    /** *Start*, *Try again* and the first button of every flow that needs the TV located. */
    fun find() = go {
        if (platform.phoneAddress() == null) { _screen.value = GuideScreen.Intro(wifi = false); return@go }
        _screen.value = GuideScreen.Searching
        val tvs = scan().map(FoundTv::tv)
        _screen.value = when {
            tvs.isEmpty() -> GuideScreen.NotFound
            else -> GuideScreen.Pick(tvs)
        }
    }

    /** The /24 sweep (FR-2). With [withVersions] each TV that answers is also asked what Ravilo version it has (FR-11), during the same sweep. */
    private suspend fun scan(withVersions: Boolean = false): List<FoundTv> {
        val phone = platform.phoneAddress() ?: return emptyList()
        val gate = Semaphore(48)
        return coroutineScope {
            Subnet.hosts(phone).map { ip ->
                async {
                    gate.withPermit {
                        platform.probe(ip)?.let { SamsungTv.parse(ip, it) }?.let { tv ->
                            FoundTv(tv, if (withVersions) InstalledRavilo.read(platform.appReply(ip)) else InstalledRavilo.Unknown)
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.tv.ip.substringAfterLast('.').toInt() }
        }.also { platform.log("samsung guide: found ${it.size} TV(s) on ${phone.substringBeforeLast('.')}.0/24") }
    }

    /** A TV picked from the scan's list, or from its card: FR-3's step 1 for that TV. */
    fun choose(tv: SamsungTv) {
        job?.cancel()
        chosen = tv
        _screen.value = GuideScreen.Step1(tv, remote)
    }

    /** *Set up this TV* on a *Not set up* card (FR-15): FR-3's steps for that TV, not a scan of every TV. */
    fun setUp(card: TvCard) { card.tv?.let(::choose) }

    /** A tap on one of step 1's two remotes, or on the other tab: the instruction changes in place. */
    fun pickRemote(r: Remote) {
        remote = r
        (_screen.value as? GuideScreen.Step1)?.let { _screen.value = it.copy(remote = r) }
    }

    fun next() {
        val tv = chosen ?: return find()
        when (_screen.value) {
            is GuideScreen.Step1 -> {
                val ip = platform.phoneAddress()
                _screen.value = if (ip == null) GuideScreen.Intro(wifi = false) else GuideScreen.Step2(tv, ip)
            }
            is GuideScreen.Step2 -> _screen.value = GuideScreen.Step3(tv)
            is GuideScreen.Step3 -> go {
                _screen.value = GuideScreen.Checking(tv)
                _screen.value = verify(tv) ?: GuideScreen.Ask(tv)
            }
            else -> {}
        }
    }

    /** The problem's one button. */
    fun back(to: Target) {
        val tv = chosen
        when (to) {
            Target.FIND -> find()
            Target.STEP1 -> _screen.value = if (tv != null) GuideScreen.Step1(tv, remote) else GuideScreen.Intro(platform.phoneAddress() != null)
            Target.STEP2 -> { val ip = platform.phoneAddress(); _screen.value = if (tv != null && ip != null) GuideScreen.Step2(tv, ip) else GuideScreen.Intro(ip != null) }
            Target.STEP3 -> _screen.value = if (tv != null) GuideScreen.Step3(tv) else GuideScreen.Intro(platform.phoneAddress() != null)
            Target.ASK -> _screen.value = if (tv != null) GuideScreen.Ask(tv) else GuideScreen.Intro(platform.phoneAddress() != null)
            Target.OVERVIEW -> refresh()
        }
    }

    /** Re-reads the TV's own answer and says what is wrong with it, or null when Developer Mode is on and points at this phone. */
    private suspend fun verify(tv: SamsungTv): GuideScreen.Problem? {
        val body = platform.probe(tv.ip) ?: return GuideScreen.Problem("samsungtv.problem.tv_silent", emptyMap(), home)
        val now = SamsungTv.parse(tv.ip, body) ?: return GuideScreen.Problem("samsungtv.problem.tv_silent", emptyMap(), home)
        chosen = now
        val phone = platform.phoneAddress() ?: return GuideScreen.Problem("samsungtv.problem.no_wifi", emptyMap(), home)
        return when {
            !now.developerMode -> GuideScreen.Problem("samsungtv.problem.devmode_off", emptyMap(), Target.STEP1)
            now.developerIp != phone -> GuideScreen.Problem(
                "samsungtv.problem.ip_differs", mapOf("tv" to (now.developerIp ?: "—"), "phone" to phone), Target.STEP2,
            )
            else -> null
        }
    }

    /** Step 4: read the TV's id over `sdb`, send the request, and go home to the overview, which shows the server's answer (FR-15). */
    fun sendRequest() {
        val tv = chosen ?: return find()
        go {
            _screen.value = GuideScreen.Ask(tv, busy = true)
            val duid = try { platform.sdb.readDuid(tv.ip) } catch (e: SdbException) {
                platform.log("samsung guide: ${e.message}")
                val key = if (e.message?.contains("ID") == true) "samsungtv.problem.no_duid" else "samsungtv.problem.sdb"
                _screen.value = GuideScreen.Problem(key, emptyMap(), Target.STEP2); return@go
            }
            val made = try {
                backend.create(SamsungTvRequestCreate(duid = duid, model = tv.model.take(120), tizenVersion = tv.tizenVersion, name = tv.name.take(120)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                platform.log("samsung guide: request refused: ${e::class.simpleName}: ${e.message}")
                _screen.value = GuideScreen.Problem("samsungtv.problem.request_failed", emptyMap(), Target.ASK); return@go
            }
            platform.log("samsung guide: request sent (${made.state})")
            sent = true
            showOverview()
        }
    }

    /** *Withdraw request* on a Requested card. */
    fun withdraw(card: TvCard) {
        val r = card.request?.takeIf { card.action == CardAction.WITHDRAW } ?: return   // only a Requested row can be withdrawn (FR-6)
        go {
            val after = backend.withdraw(r.id)
            platform.log("samsung guide: request withdrawn (${after.state})")
            showOverview()
        }
    }

    /** *Ask again* on a Rejected card the scan found: FR-3's steps for that TV. An unfound card has no such action (FR-10 item 4). */
    fun askAgain(card: TvCard) { if (card.action == CardAction.ASK_AGAIN) setUp(card) }

    /**
     * *Install on TV*, *Update* and *Reinstall* on a card (FR-15): the checks and the install run for **that card's request**. A
     * card whose TV the scan could not tell from another reads each candidate's id and takes the one the request names.
     */
    fun install(card: TvCard) {
        val r = card.request ?: return
        val tvs = listOfNotNull(card.tv) + card.candidates
        go {
            val tv = when (tvs.size) {
                0 -> return@go
                1 -> tvs[0]
                else -> tvs.firstOrNull { c -> runCatching { platform.sdb.readDuid(c.ip) }.getOrNull() == r.duid }
                    ?: run { _screen.value = GuideScreen.Problem("samsungtv.problem.wrong_tv", emptyMap(), Target.OVERVIEW); return@go }
            }
            chosen = tv
            checkThenInstall(tv, r)
        }
    }

    private suspend fun checkThenInstall(tv: SamsungTv, r: SamsungTvRequest) {
        _screen.value = GuideScreen.Searching
        verify(tv)?.let { _screen.value = it; return }
        val now = chosen ?: tv
        val url = r.wgtUrl ?: run { _screen.value = GuideScreen.Problem("samsungtv.problem.not_covered", emptyMap(), Target.OVERVIEW); return }
        val duid = try { platform.sdb.readDuid(now.ip) } catch (e: SdbException) {
            platform.log("samsung guide: ${e.message}")
            _screen.value = GuideScreen.Problem(if (e.message?.contains("ID") == true) "samsungtv.problem.no_duid" else "samsungtv.problem.sdb", emptyMap(), Target.STEP2); return
        }
        // FR-15 — a TV that belongs to another of the viewer's requests goes back to the overview, where each card has its own button.
        if (duid != r.duid) { _screen.value = GuideScreen.Problem("samsungtv.problem.wrong_tv", emptyMap(), Target.OVERVIEW); return }
        _screen.value = GuideScreen.Installing(GuideScreen.Stage.DOWNLOADING, 0f)
        val wgt = try {
            platform.download(url) { got, total ->
                _screen.value = GuideScreen.Installing(GuideScreen.Stage.DOWNLOADING, total?.let { (got.toFloat() / it).coerceIn(0f, 1f) })
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            platform.log("samsung guide: download failed: ${e.message}")
            _screen.value = GuideScreen.Problem("samsungtv.problem.download", emptyMap(), Target.OVERVIEW); return
        }
        // FR-7 — a package whose certificate does not name this TV is never pushed.
        if (platform.packageDeviceIds(wgt)?.contains(duid) != true) {
            platform.log("samsung guide: the package does not name this TV")
            _screen.value = GuideScreen.Problem("samsungtv.problem.not_covered", emptyMap(), Target.OVERVIEW); return
        }
        try {
            platform.sdb.install(now.ip, wgt, PACKAGE_ID, APP_ID) { stage, bytes ->
                _screen.value = when (stage) {
                    SdbClient.Stage.SENDING -> GuideScreen.Installing(GuideScreen.Stage.SENDING, (bytes.toFloat() / wgt.size).coerceIn(0f, 1f))
                    SdbClient.Stage.INSTALLING -> GuideScreen.Installing(GuideScreen.Stage.INSTALLING, null)
                }
            }
        } catch (e: SdbException) {
            platform.log("samsung guide: install failed: ${e.message}")
            _screen.value = GuideScreen.Problem("samsungtv.problem.install", emptyMap(), Target.STEP3); return
        }
        // FR-11 — what this phone installed is the version to show when the TV's own answer is not known.
        r.readyFromRelease?.let { platform.rememberInstalledTag(r.duid, it) }
        _screen.value = GuideScreen.Done(r)
    }

    /** *Done* goes back to the overview, which now shows the version just installed (FR-7, FR-14). */
    fun done() = refresh()

    companion object {
        const val PACKAGE_ID = "RaviloScrn"
        const val APP_ID = "RaviloScrn.RaviloScrn"
    }
}
