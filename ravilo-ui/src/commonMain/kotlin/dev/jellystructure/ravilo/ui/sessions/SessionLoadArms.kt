package dev.jellystructure.ravilo.ui.sessions

/*
 * R380 (FR-R380-11, amendment 2026-10-13, DEB-919) — which arm of the app's `session_load` handler a kind takes.
 *
 * Found on devices (QA-D, DEB-788, Stue TV): the app declares `plays=video,music,book`, the server offered the TV as a
 * book place, and the handler branched on `music` / `film` / `episode` with no book arm and no `else` — so every book
 * handed to it fell out of the `when`, nothing played, nothing was said, and the server's watchdog ended the session
 * `failed` 11 s later. The arms live here, out of the Compose tree, so the set the app handles can be held against the
 * set it advertises ([dev.jellystructure.shared.tv.kindsPlayed]) by a test instead of by a reading.
 */

/** One arm of the `session_load` handler. */
enum class SessionLoadArm { MUSIC, BOOK, VIDEO }

private val ARMS: Map<String, SessionLoadArm> = mapOf(
    "music" to SessionLoadArm.MUSIC,
    "audiobook" to SessionLoadArm.BOOK,
    "film" to SessionLoadArm.VIDEO,
    "episode" to SessionLoadArm.VIDEO,
)

/** Every kind this app has an arm for — the set a test holds equal to what the app advertises. */
val SESSION_LOAD_KINDS: Set<String> get() = ARMS.keys

/** The arm [kind] takes; null = this app has none, and the load is refused loudly (never dropped). */
fun sessionLoadArm(kind: String): SessionLoadArm? = ARMS[kind]

/** R369/R380 — the `session_load_failed` frame: this place cannot play what it was handed, and says which kind. */
fun loadRefusedFrame(sessionId: String, kind: String): String =
    "{\"type\":\"session_load_failed\",\"id\":${kotlinx.serialization.json.JsonPrimitive(sessionId)}," +
        "\"kind\":${kotlinx.serialization.json.JsonPrimitive(kind)}}"
