package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlin.coroutines.cancellation.CancellationException

/**
 * R345 — what one of the two availability questions got back. A failure is not an answer: no network, a timeout,
 * a 5xx or a restarting server says nothing about whether this viewer has music.
 */
enum class MusicAnswer {
    /** The server answered with rows (or books). */
    SOMETHING,

    /** The server answered, and there was nothing: an empty `MusicHome`, an empty or absent shelf, or a 404 route. */
    EMPTY,

    /** No answer. */
    FAILED,
}

/**
 * R345 (FR-R345-1) — three outcomes, not two. `true` when either question answered with something, `false` (a
 * definite no) only when both answered empty, otherwise `null` (unknown: stay in the stored mode, ask again).
 */
fun musicAvailability(books: MusicAnswer, music: MusicAnswer): Boolean? = when {
    books == MusicAnswer.SOMETHING || music == MusicAnswer.SOMETHING -> true
    books == MusicAnswer.EMPTY && music == MusicAnswer.EMPTY -> false
    else -> null
}

/**
 * R345 — what one question's outcome counts as. [Result.success] says whether the answer held something; a 404 is an
 * answer (a server from before 279 has no music routes), any other failure is [MusicAnswer.FAILED].
 */
fun musicAnswerOf(outcome: Result<Boolean>): MusicAnswer = outcome.fold(
    onSuccess = { if (it) MusicAnswer.SOMETHING else MusicAnswer.EMPTY },
    onFailure = { if ((it as? TvApiError.Http)?.status == 404) MusicAnswer.EMPTY else MusicAnswer.FAILED },
)

/** R345 — runs one question; cancellation is rethrown, never counted as an answer. */
suspend fun askMusic(block: suspend () -> Boolean): MusicAnswer = musicAnswerOf(
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    },
)

/** R345 — both questions, asked of the server. `books` is also what the mode card's *with audiobooks* reads. */
suspend fun askMusicAvailability(api: TvApiClient): Pair<MusicAnswer, Boolean?> {
    val books = askMusic { api.getAudiobooks()?.books?.isNotEmpty() == true }
    val music = askMusic { api.getMusicHome().rows.isNotEmpty() }
    return books to musicAvailability(books, music)
}
