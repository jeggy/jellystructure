package dev.jellystructure.ravilo.ui.support

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jellystructure.shared.tv.SupportSendResult
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DEB-1501 — the viewer's support lane as the screens see it. The stores below hold what the server last said and
 * nothing else (the constitution: no derived or optimistic state): a message the viewer sent is in the thread when the
 * server's answer carries it, and not before.
 */
interface SupportGateway {
    suspend fun unread(): Int
    suspend fun tickets(): List<SupportTicketRow>
    suspend fun thread(n: Long): SupportThread
    suspend fun markRead(n: Long, upTo: Long)
    suspend fun file(body: String): SupportSendResult
    suspend fun post(n: Long, body: String): SupportSendResult
}

class TvSupportGateway(private val api: TvApiClient) : SupportGateway {
    override suspend fun unread() = api.supportUnread()
    override suspend fun tickets() = api.supportTickets()
    override suspend fun thread(n: Long) = api.supportThread(n)
    override suspend fun markRead(n: Long, upTo: Long) = api.supportMarkRead(n, upTo)
    override suspend fun file(body: String) = api.supportFile(body)
    override suspend fun post(n: Long, body: String) = api.supportPost(n, body)
}

/**
 * FR-1, FR-2, FR-7 — what is app-wide: whether the lane is open for this app (`RaviloConfig.supportEnabled`, on a
 * platform that may file), the unread count, and the stream of `support_changed` signals the open screens read again on.
 */
object SupportLane {
    val enabled = MutableStateFlow(false)
    val unread = MutableStateFlow(0)

    /** A `support_changed` ticket number, or [ALL] after the events socket (re)opened. Carries no content (FR-7). */
    val changes = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 32)

    const val ALL = 0L

    /** FR-2 — reads the count again. A failed read leaves the last answer as it was; the app never counts on its own. */
    suspend fun readUnread(gateway: SupportGateway) {
        try {
            unread.value = gateway.unread().coerceAtLeast(0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** A different viewer, or the lane closed: nothing of the last viewer's is kept. */
    fun clear() {
        unread.value = 0
    }
}

/** How a read ended, for the screen that asked: fine, no answer at all (FR-8), or a 404 (the ticket or the lane is gone). */
enum class ReadEnd { OK, OFFLINE, GONE }

private suspend fun <T> guarded(block: suspend () -> T, onOk: (T) -> Unit): ReadEnd = try {
    onOk(block())
    ReadEnd.OK
} catch (e: CancellationException) {
    throw e
} catch (e: TvApiError.Http) {
    if (e.status == 404) ReadEnd.GONE else ReadEnd.OFFLINE
} catch (_: Exception) {
    ReadEnd.OFFLINE
}

/** FR-3 — My tickets. [rows] is null until the first read of this screen (the skeleton rows). */
class SupportListStore(private val gateway: SupportGateway) {
    var rows by mutableStateOf<List<SupportTicketRow>?>(null)
        private set
    var offline by mutableStateOf(false)
        private set

    suspend fun refresh(): ReadEnd {
        val end = guarded({ gateway.tickets() }) { rows = it }
        offline = end == ReadEnd.OFFLINE
        return end
    }
}

/** FR-5: what the composer shows above the field. */
enum class SendPhase { IDLE, SENDING, NOT_SENT_OFFLINE, LIMIT, TOO_LONG }

/** What a send came to, for the screen: the thread to adopt (a ticket just filed is told apart by [filed]), or why not. */
sealed class SendEnd {
    data class Sent(val thread: SupportThread, val filed: Boolean) : SendEnd()
    data object Failed : SendEnd()
    data object Gone : SendEnd()
}

/**
 * FR-4, FR-5, FR-6 — one ticket's thread, or [number] null for the first message of a ticket not yet filed.
 * [seed] is a thread the caller already holds (the 201 that just filed it).
 */
class SupportThreadStore(
    private val gateway: SupportGateway,
    val number: Long?,
    seed: SupportThread? = null,
    /** Fires after the app posted `…/read`, so the unread count is read again (FR-2). */
    private val onRead: suspend () -> Unit = {},
) {
    var thread by mutableStateOf(seed)
        private set
    var offline by mutableStateOf(false)
        private set
    var phase by mutableStateOf(SendPhase.IDLE)
        private set

    // A read that was asked for before a newer answer landed must not replace it (a send's answer is the newest).
    private var issued = 0
    private var applied = 0
    private var postedUpTo = 0L

    /** FR-4 — the read behind the screen: on open, on `support_changed`, after a reconnect. */
    suspend fun refresh(): ReadEnd {
        val n = number ?: thread?.ticket?.number ?: return ReadEnd.OK
        val mine = ++issued
        var got: SupportThread? = null
        val end = guarded({ gateway.thread(n) }) { got = it }
        offline = end == ReadEnd.OFFLINE
        got?.let { adopt(mine, it) }
        return end
    }

    private suspend fun adopt(mine: Int, t: SupportThread) {
        if (mine > applied) {
            applied = mine
            thread = t
        }
        markRead(t)
    }

    /** FR-4 — `POST …/read {upTo: <last id>}` after every read while the thread is open; never twice for one id. */
    private suspend fun markRead(t: SupportThread) {
        val last = t.messages.maxOfOrNull { it.id } ?: return
        if (last <= postedUpTo) return
        try {
            gateway.markRead(t.ticket.number, last)
            postedUpTo = last
            onRead()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** FR-5 — locks the composer, asks, and says what the viewer sees. The draft is the caller's: kept unless [SendEnd.Sent]. */
    suspend fun send(body: String): SendEnd {
        val text = body.trim()
        if (text.isEmpty() || phase == SendPhase.SENDING) return SendEnd.Failed
        phase = SendPhase.SENDING
        val filing = number == null && thread == null
        val result = try {
            if (filing) gateway.file(text) else gateway.post(number ?: thread!!.ticket.number, text)
        } catch (e: CancellationException) {
            phase = SendPhase.IDLE
            throw e
        } catch (_: Exception) {
            phase = SendPhase.NOT_SENT_OFFLINE
            return SendEnd.Failed
        }
        return when (result) {
            is SupportSendResult.Sent -> {
                phase = SendPhase.IDLE
                offline = false
                adopt(++issued, result.thread)
                SendEnd.Sent(result.thread, filed = filing)
            }
            is SupportSendResult.Refused -> when (result.status) {
                404 -> { phase = SendPhase.IDLE; SendEnd.Gone }
                429 -> { phase = SendPhase.LIMIT; SendEnd.Failed }
                400 -> { phase = SendPhase.TOO_LONG; SendEnd.Failed }
                // Any other refusal (a 5xx, a 403) is not a reason the viewer can act on; the draft stays and Try again is offered.
                else -> { phase = SendPhase.NOT_SENT_OFFLINE; SendEnd.Failed }
            }
        }
    }
}

/** FR-2 — whether the unread dot shows on the Profile entry and on the Support row or item: the lane is open and something is unread. */
@androidx.compose.runtime.Composable
fun supportUnreadShows(): Boolean {
    val on by SupportLane.enabled.collectAsState()
    val n by SupportLane.unread.collectAsState()
    return on && n > 0
}
