package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastLoadData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

// ── R266 — Cast Connect: the Android TV app takes the cast ───────────────────────────────────────────────────────
//
// The dev review's shape: Cast Connect carries the launch and the transport; the play travels 236's road. The phone's
// LOAD carries [CastLoadData] as the media's customData (R359); the TV app reads the item, the position and the casting
// viewer's Jellyfin user id from it and plays under that viewer ONLY if it already holds a token for them. It never
// redeems the hand-off code (that would mint a second `ravilo_device` row named a Chromecast, under 218's ceiling).
// Everything here is pure or platform-neutral; the Cast SDK half lives in ravilo-android (CastConnectReceiver).

/** What a Cast Connect LOAD asks the TV app to play. [kind] is `movie` or `episode` (the play_item vocabulary). */
data class CastConnectPlay(
    val itemId: String,
    val kind: String,
    val title: String,
    val kicker: String?,
    val positionMs: Long,
    val userId: String,
)

/**
 * The play a LOAD's customData asks for, or null when the TV app cannot take it: no payload, no item, no viewer (an
 * older phone, or a relay of someone else's session), or a music queue (the TV app has no music mode — R370's
 * `plays` says so; FR-R266-3: refused, never a silent blank).
 */
fun castConnectPlayOf(data: CastLoadData?): CastConnectPlay? {
    val d = data ?: return null
    if (d.tracks.isNotEmpty()) return null
    val item = d.itemId.trim().takeIf { it.isNotEmpty() } ?: return null
    val user = d.userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return CastConnectPlay(
        itemId = item,
        kind = if (d.episodes.isNotEmpty()) "episode" else "movie",
        title = d.title,
        kicker = d.kicker,
        positionMs = (d.positionMs ?: 0L).coerceAtLeast(0L),
        userId = user,
    )
}

/** [castConnectPlayOf] from the customData's JSON text, as the Cast SDK hands it over. */
fun castConnectPlayFromJson(text: String?): CastConnectPlay? {
    if (text.isNullOrBlank()) return null
    val data = runCatching { dev.jellystructure.shared.tv.RaviloWireJson.decodeFromString(CastLoadData.serializer(), text) }.getOrNull()
    return castConnectPlayOf(data)
}

enum class CastConnectVerdict {
    /** The casting viewer is the TV's active profile: play. */
    PLAY,
    /** The TV holds a token for the casting viewer, but another profile is active: switch to them, play, and switch
     *  back when the player closes (FR-R266-4: the TV's Home afterwards shows its own last-selected profile). */
    SWITCH_THEN_PLAY,
    /** The TV holds no token for this viewer: refused. A phone-supplied identity is trusted only because the TV
     *  already holds its token (236 FR-236-4) — never on the phone's word alone. */
    REFUSE_NO_TOKEN,
    /** Someone is signing in on the TV right now: nothing is started over them. */
    REFUSE_NOT_READY,
}

/** The one rule for whose profile a Cast Connect play runs under (dev review item 1). */
fun castConnectVerdict(userId: String, heldUserIds: Collection<String>, activeUserId: String?, signingIn: Boolean): CastConnectVerdict = when {
    userId !in heldUserIds -> CastConnectVerdict.REFUSE_NO_TOKEN
    signingIn -> CastConnectVerdict.REFUSE_NOT_READY
    userId == activeUserId -> CastConnectVerdict.PLAY
    else -> CastConnectVerdict.SWITCH_THEN_PLAY
}

/** A LOAD waiting for the app's root to take it; [answer] is true once the player has been opened for it. */
class CastConnectRequest(val play: CastConnectPlay) {
    val answer = CompletableDeferred<Boolean>()
}

/**
 * The hand-over between the Cast SDK (ravilo-android, on a LOAD) and the app's root ([dev.jellystructure.ravilo.ui.RaviloApp]),
 * which owns the navigation stack and the profiles. A StateFlow, not an event: a launch LOAD arrives while the activity
 * is still composing its first frame, before anything collects.
 */
object CastConnectInbox {
    private val _pending = MutableStateFlow<CastConnectRequest?>(null)
    val pending: StateFlow<CastConnectRequest?> = _pending

    /** Hands [play] to the app and waits for its answer; a newer cast replaces one still waiting (Google's behaviour). */
    suspend fun submit(play: CastConnectPlay, timeoutMs: Long = 20_000L): Boolean {
        val req = CastConnectRequest(play)
        _pending.value?.answer?.complete(false)
        _pending.value = req
        val ok = withTimeoutOrNull(timeoutMs) { req.answer.await() } ?: false
        _pending.compareAndSet(req, null)
        return ok
    }

    /** The root took [req] off the inbox (it answers through [CastConnectRequest.answer]). */
    fun taken(req: CastConnectRequest) { _pending.compareAndSet(req, null) }
}

/**
 * R266 (owner, 2026-10-05) — a debug build casts with the development Cast application (whose Android TV package is
 * the `.debug` one) in place of the server's `chromecast.app_id`. Set once at start-up by ravilo-android from
 * `BuildConfig.CAST_DEV_APP_ID`; null/empty everywhere else (release, web, desktop) ⇒ the server's id, unchanged.
 */
object CastAppIdOverride {
    @kotlin.concurrent.Volatile var devAppId: String? = null
}

private val CAST_APP_ID = Regex("^[0-9A-Fa-f]{8}$")

/** The application id to cast with: the server's, unless a valid development id is set. Absent stays absent — a
 *  server with casting off (no id) shows no cast button, override or not (218 FR-218-3). */
fun effectiveCastAppId(serverAppId: String?, devAppId: String?): String? {
    if (serverAppId == null) return null
    val dev = devAppId?.trim()?.takeIf { CAST_APP_ID.matches(it) } ?: return serverAppId
    return dev.uppercase()
}
