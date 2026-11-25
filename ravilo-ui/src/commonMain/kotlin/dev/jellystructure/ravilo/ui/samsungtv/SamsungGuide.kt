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
enum class Target { FIND, STEP1, STEP2, STEP3, ASK, WAITING }

/** DEB-5504 (FR-13) — which remote the viewer holds: a long one with real number keys, or a Smart Remote with the *123* key. */
enum class Remote { KEYS, NOKEYS }

/** One screen of the guide. Everything the screen shows is in the value; nothing is derived in the composable. */
sealed interface GuideScreen {
    data object Loading : GuideScreen
    data class Intro(val wifi: Boolean) : GuideScreen
    data object Searching : GuideScreen
    data class Pick(val tvs: List<SamsungTv>, val forInstall: Boolean) : GuideScreen
    data object NotFound : GuideScreen
    /** [remote] is null on the question and set on the instruction (DEB-5504, FR-13). */
    data class Step1(val tv: SamsungTv, val remote: Remote? = null) : GuideScreen
    data class Step2(val tv: SamsungTv, val phoneIp: String) : GuideScreen
    data class Step3(val tv: SamsungTv) : GuideScreen
    data class Checking(val tv: SamsungTv) : GuideScreen
    data class Ask(val tv: SamsungTv, val busy: Boolean = false) : GuideScreen
    data class Waiting(val request: SamsungTvRequest) : GuideScreen
    data class Ready(val request: SamsungTvRequest) : GuideScreen
    data class Rejected(val request: SamsungTvRequest) : GuideScreen
    data class Installing(val stage: Stage, val fraction: Float?) : GuideScreen
    data class Done(val request: SamsungTvRequest) : GuideScreen
    /** One sentence ([key] with [vars]) and one button, [back], to the step that fixes it. */
    data class Problem(val key: String, val vars: Map<String, String>, val back: Target) : GuideScreen

    enum class Stage { DOWNLOADING, SENDING, INSTALLING }
}

/**
 * DEB-4843 (FR-2–FR-7) — the guide's state machine. Server state is only ever the last read of the viewer's routes; a screen is
 * a value of [GuideScreen], and every failure is a [GuideScreen.Problem] with one way back.
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
    private var request: SamsungTvRequest? = null

    private fun go(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                platform.log("samsung guide: ${e::class.simpleName}: ${e.message}")
                _screen.value = GuideScreen.Problem("samsungtv.problem.generic", emptyMap(), Target.FIND)
            }
        }
    }

    /** Opens the guide: re-reads the viewer's requests and lands on the screen their state says. */
    fun open() = go {
        _screen.value = GuideScreen.Loading
        remote = null
        val mine = backend.list().firstOrNull { it.state != "WITHDRAWN" }
        request = mine
        _screen.value = when (mine?.state) {
            null -> GuideScreen.Intro(wifi = platform.phoneAddress() != null)
            "READY" -> GuideScreen.Ready(mine)
            "REJECTED" -> GuideScreen.Rejected(mine)
            else -> GuideScreen.Waiting(mine)
        }
    }

    /** *Start*, *Try again* and the first button of every flow that needs the TV located. */
    fun find(forInstall: Boolean = false) = go {
        if (platform.phoneAddress() == null) { _screen.value = GuideScreen.Intro(wifi = false); return@go }
        _screen.value = GuideScreen.Searching
        val tvs = scan()
        _screen.value = when {
            tvs.isEmpty() -> GuideScreen.NotFound
            tvs.size == 1 && forInstall -> { chosen = tvs[0]; checkThenInstall(tvs[0]); return@go }
            else -> GuideScreen.Pick(tvs, forInstall)
        }
    }

    private suspend fun scan(): List<SamsungTv> {
        val phone = platform.phoneAddress() ?: return emptyList()
        val gate = Semaphore(48)
        return coroutineScope {
            Subnet.hosts(phone).map { ip ->
                async { gate.withPermit { platform.probe(ip)?.let { SamsungTv.parse(ip, it) } } }
            }.awaitAll().filterNotNull().sortedBy { it.ip.substringAfterLast('.').toInt() }
        }.also { platform.log("samsung guide: found ${it.size} TV(s) on ${phone.substringBeforeLast('.')}.0/24") }
    }

    fun choose(tv: SamsungTv, forInstall: Boolean) {
        chosen = tv
        if (forInstall) go { checkThenInstall(tv) } else _screen.value = GuideScreen.Step1(tv, remote)
    }

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
            Target.WAITING -> open()
        }
    }

    /** Re-reads the TV's own answer and says what is wrong with it, or null when Developer Mode is on and points at this phone. */
    private suspend fun verify(tv: SamsungTv): GuideScreen.Problem? {
        val body = platform.probe(tv.ip) ?: return GuideScreen.Problem("samsungtv.problem.tv_silent", emptyMap(), Target.FIND)
        val now = SamsungTv.parse(tv.ip, body) ?: return GuideScreen.Problem("samsungtv.problem.tv_silent", emptyMap(), Target.FIND)
        chosen = now
        val phone = platform.phoneAddress() ?: return GuideScreen.Problem("samsungtv.problem.no_wifi", emptyMap(), Target.FIND)
        return when {
            !now.developerMode -> GuideScreen.Problem("samsungtv.problem.devmode_off", emptyMap(), Target.STEP1)
            now.developerIp != phone -> GuideScreen.Problem(
                "samsungtv.problem.ip_differs", mapOf("tv" to (now.developerIp ?: "—"), "phone" to phone), Target.STEP2,
            )
            else -> null
        }
    }

    /** Step 4: read the TV's id over `sdb`, send the request. */
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
                platform.log("samsung guide: request refused: ${e.message}")
                _screen.value = GuideScreen.Problem("samsungtv.problem.request_failed", emptyMap(), Target.ASK); return@go
            }
            request = made
            _screen.value = if (made.state == "READY") GuideScreen.Ready(made) else GuideScreen.Waiting(made)
        }
    }

    fun withdraw() {
        val r = request ?: return
        go {
            val after = backend.withdraw(r.id)
            request = null
            platform.log("samsung guide: request withdrawn (${after.state})")
            _screen.value = GuideScreen.Intro(wifi = platform.phoneAddress() != null)
        }
    }

    /** *Ask again* after a rejection. */
    fun askAgain() = find()

    /** *Install on TV*, *Update* and *Reinstall*: locate the TV, check it, install. */
    fun install() = find(forInstall = true)

    private suspend fun checkThenInstall(tv: SamsungTv) {
        val r = request ?: run { _screen.value = GuideScreen.Intro(platform.phoneAddress() != null); return }
        _screen.value = GuideScreen.Searching
        verify(tv)?.let { _screen.value = it; return }
        val now = chosen ?: tv
        val url = r.wgtUrl ?: run { _screen.value = GuideScreen.Problem("samsungtv.problem.not_covered", emptyMap(), Target.WAITING); return }
        val duid = try { platform.sdb.readDuid(now.ip) } catch (e: SdbException) {
            platform.log("samsung guide: ${e.message}")
            _screen.value = GuideScreen.Problem(if (e.message?.contains("ID") == true) "samsungtv.problem.no_duid" else "samsungtv.problem.sdb", emptyMap(), Target.STEP2); return
        }
        if (duid != r.duid) { _screen.value = GuideScreen.Problem("samsungtv.problem.wrong_tv", emptyMap(), Target.FIND); return }
        _screen.value = GuideScreen.Installing(GuideScreen.Stage.DOWNLOADING, 0f)
        val wgt = try {
            platform.download(url) { got, total ->
                _screen.value = GuideScreen.Installing(GuideScreen.Stage.DOWNLOADING, total?.let { (got.toFloat() / it).coerceIn(0f, 1f) })
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            platform.log("samsung guide: download failed: ${e.message}")
            _screen.value = GuideScreen.Problem("samsungtv.problem.download", emptyMap(), Target.WAITING); return
        }
        // FR-7 — a package whose certificate does not name this TV is never pushed.
        if (platform.packageDeviceIds(wgt)?.contains(duid) != true) {
            platform.log("samsung guide: the package does not name this TV")
            _screen.value = GuideScreen.Problem("samsungtv.problem.not_covered", emptyMap(), Target.WAITING); return
        }
        try {
            platform.sdb.install(now.ip, wgt, PACKAGE_ID, APP_ID) { stage, sent ->
                _screen.value = when (stage) {
                    SdbClient.Stage.SENDING -> GuideScreen.Installing(GuideScreen.Stage.SENDING, (sent.toFloat() / wgt.size).coerceIn(0f, 1f))
                    SdbClient.Stage.INSTALLING -> GuideScreen.Installing(GuideScreen.Stage.INSTALLING, null)
                }
            }
        } catch (e: SdbException) {
            platform.log("samsung guide: install failed: ${e.message}")
            _screen.value = GuideScreen.Problem("samsungtv.problem.install", emptyMap(), Target.STEP3); return
        }
        _screen.value = GuideScreen.Done(r)
    }

    fun done() = open()

    companion object {
        const val PACKAGE_ID = "RaviloScrn"
        const val APP_ID = "RaviloScrn.RaviloScrn"
    }
}
