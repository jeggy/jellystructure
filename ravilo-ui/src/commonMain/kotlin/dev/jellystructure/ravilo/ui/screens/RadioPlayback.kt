package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_AUDIO
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import kotlin.math.roundToInt

/*
 * DEB-2658 (DEB-2628 FR-6, FR-7, FR-8) — radio in TV mode. Everything here is a decision with no screen in it, so each one is a
 * test: which player a channel opens, what a station's tile says, where the burn-in orbit is, when the audio layout dims.
 */

/** DEB-2628 FR-1 — `LiveTvChannel.kind` of a station. A plain string on the wire; anything else is a TV channel. */
const val CHANNEL_KIND_RADIO = "radio"

val LiveTvChannel.isRadio: Boolean get() = kind == CHANNEL_KIND_RADIO

/** FR-8 — which surface a tune opens: the video player, or the audio layout in place of the picture. */
enum class LivePlayerLayout { VIDEO, RADIO }

/** A station opens the radio player; a channel — and a lineup entry not yet known — the video player. */
fun livePlayerLayout(channel: LiveTvChannel?): LivePlayerLayout = if (channel?.isRadio == true) LivePlayerLayout.RADIO else LivePlayerLayout.VIDEO

/** FR-8 — the channel [direction] steps from [currentId] over the ONE lineup (stations and TV channels in the operator's order), wrapping; null when [currentId] is not in it. */
fun zapTarget(lineup: List<LiveTvChannel>, currentId: String, direction: Int): LiveTvChannel? {
    if (lineup.isEmpty()) return null
    val idx = lineup.indexOfFirst { it.channelId == currentId }
    if (idx < 0) return null
    return lineup[((idx + direction) % lineup.size + lineup.size) % lineup.size]
}

/** FR-8 — number entry over the same lineup. */
fun channelByNumber(lineup: List<LiveTvChannel>, number: Int): LiveTvChannel? = lineup.firstOrNull { it.number == number }

/**
 * FR-6 — what a live tile says. A channel with no programme keeps R177's *No program info*. A station with none keeps its name
 * where a channel's name is and *Live radio* alone takes the programme's place, with no time range and no progress bar: the
 * name is never drawn a second time at the programme's weight (design round, 2026-10-27).
 */
data class LiveTileText(val title: String, val subtitle: String, val caption: String?, val showProgress: Boolean)

fun liveTileText(channel: LiveTvChannel, liveRadio: String, noProgram: String, timeRange: (LiveTvChannel) -> String?): LiveTileText {
    val program = channel.currentProgram
    return when {
        program != null -> LiveTileText(channel.name, program.name, timeRange(channel), showProgress = program.endMs > program.startMs)
        channel.isRadio -> LiveTileText(channel.name, liveRadio, caption = null, showProgress = false)
        else -> LiveTileText(channel.name, noProgram, caption = null, showProgress = false)
    }
}

/**
 * FR-8 — the burn-in orbit of the audio layout on a TV. Four positions on a 28-minute loop, one step every 7 minutes, 56 × 36 px
 * of travel at 1920 × 1080 (2.9 % of the width, 3.3 % of the height) — the round's own offsets, kept as fractions of the screen
 * so a 960 × 540 dp TV and a 1920 px window move the same distance relative to what they show. The step is a 600 ms glide, or a
 * cut under reduced motion. The whole centred block moves.
 */
object RadioBurnIn {
    const val STEP_MS = 7 * 60_000L
    const val GLIDE_MS = 600
    const val POSITIONS = 4

    /** Offsets in design pixels on a 1920 × 1080 canvas, per step: home, right and down, down, left and down. */
    private val OFFSETS_PX = listOf(0 to 0, 28 to 18, 0 to 36, -28 to 18)
    private const val DESIGN_WIDTH = 1920f
    private const val DESIGN_HEIGHT = 1080f

    /** Which of the four positions the block holds [elapsedMs] after the layout opened. */
    fun stepAt(elapsedMs: Long): Int = ((elapsedMs.coerceAtLeast(0L) / STEP_MS) % POSITIONS).toInt()

    /** The block's offset at [step], in px of a screen [widthPx] × [heightPx]. */
    fun offsetPx(step: Int, widthPx: Float, heightPx: Float): Pair<Int, Int> {
        val (x, y) = OFFSETS_PX[((step % POSITIONS) + POSITIONS) % POSITIONS]
        return (x / DESIGN_WIDTH * widthPx).roundToInt() to (y / DESIGN_HEIGHT * heightPx).roundToInt()
    }

    /** The glide's length: 600 ms, or 0 — a cut — under reduced motion. */
    fun glideMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else GLIDE_MS
}

/**
 * FR-8 — the audio layout dims to 40 % after 5 minutes with no key press, and any key brings it back; the LIVE mark dims with it.
 * The clock is the caller's ([nowMs]), so the rule is the same on a test's virtual time as on the screen's.
 */
class RadioIdleDim(startMs: Long) {
    private var lastKeyMs = startMs

    /** Any key press, handled or not: the layout is back at full brightness and the 5 minutes start again. */
    fun onKey(nowMs: Long) { lastKeyMs = nowMs }

    fun isDimmed(nowMs: Long): Boolean = nowMs - lastKeyMs >= IDLE_MS

    fun alpha(nowMs: Long): Float = if (isDimmed(nowMs)) DIM_ALPHA else 1f

    /** How long from [nowMs] until the layout dims; 0 when it already has. */
    fun untilDimMs(nowMs: Long): Long = (IDLE_MS - (nowMs - lastKeyMs)).coerceAtLeast(0L)

    companion object {
        const val IDLE_MS = 5 * 60_000L
        const val DIM_ALPHA = 0.4f
    }
}

/**
 * DEB-2628 FR-4 — the ticket's relay path (`/api/tv/livetv/radio/{relay_id}`) is relative; a radio-aware client resolves it
 * against its own server base, and only when the ticket says `audio`. Every other ticket's URL is handed to the player as it is.
 */
fun radioPlayUrl(ticket: LiveTvStreamTicket, serverBaseUrl: String): String =
    if (ticket.streamKind == STREAM_KIND_AUDIO && ticket.hlsUrl.startsWith("/") && serverBaseUrl.isNotBlank())
        serverBaseUrl.trimEnd('/') + ticket.hlsUrl
    else ticket.hlsUrl

/**
 * FR-8 — has this load given its first signal? A film or a channel draws a first frame. A station draws none, so its audio is
 * heard when the player is past buffering and its position has moved off zero. This is what the 15 s clock of DEB-2627 FR-4
 * waits for; without it a station that never plays would never fall back.
 */
fun liveSignalStarted(radio: Boolean, renderedFirstFrame: Boolean, isBuffering: Boolean, positionMs: Long): Boolean =
    if (radio) !isBuffering && positionMs > 0L else renderedFirstFrame
