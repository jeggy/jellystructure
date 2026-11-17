package dev.jellystructure.ravilo.ui.apps

/** Android holds a key for about half a second before it starts repeating, so the first repeat arrives later than the rest. A gap longer than this means the previous press ended with its key-up lost. */
internal const val ENTER_FIRST_REPEAT_GAP_MS = 1_000L

/** Once repeating, a key repeats every few dozen milliseconds: a gap longer than this is a new press. */
internal const val ENTER_REPEAT_GAP_MS = 400L

/**
 * DEB-4912 (FR-14) — the remote's long press on Select, as plain Kotlin with an injected clock. A TV remote has no Menu key (the
 * Google TV Streamer's has none), so *press and hold* is the way to a tile's menu. Android's key auto-repeat is what makes a hold
 * a hold: the first key-down is a press; a second key-down with no key-up between is the key repeating, which is a long press.
 *
 * - [down] returns true exactly once per hold, on the repeat that makes it long.
 * - [up] returns true when the press was a short one — select it now. After a long press it returns false.
 */
internal class EnterPress(private val now: () -> Long) {
    private var held = false
    private var long = false
    private var lastAt = 0L

    fun down(): Boolean {
        val t = now()
        if (held && t - lastAt > (if (long) ENTER_REPEAT_GAP_MS else ENTER_FIRST_REPEAT_GAP_MS)) { held = false; long = false }   // a lost key-up: this is a new press
        lastAt = t
        if (!held) { held = true; long = false; return false }
        if (long) return false
        long = true
        return true
    }

    fun up(): Boolean {
        val short = held && !long
        held = false
        long = false
        return short
    }

    fun reset() { held = false; long = false }
}

/**
 * The menu a long press opens is focused while the key is still down, so the repeats that follow would reach its first button
 * as presses. [swallow] eats the key-downs of the press that opened it, and that press's key-up, and then gets out of the way.
 */
internal class OpeningPressGuard(private val now: () -> Long, private var armed: Boolean) {
    private var lastAt = now()

    /** True when the key event belongs to the press that opened the menu and must not reach the buttons. */
    fun swallow(isDown: Boolean): Boolean {
        if (!armed) return false
        if (!isDown) { armed = false; return true }
        val t = now()
        val repeat = t - lastAt <= ENTER_REPEAT_GAP_MS
        lastAt = t
        if (!repeat) armed = false
        return repeat
    }
}
