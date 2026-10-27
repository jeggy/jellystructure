package dev.jellystructure.ravilo.ui

/**
 * DEB-2482 (FR-R337-6) — where leaving the Playing page goes on the desktop. With a page under Playing the caller
 * pops back to it (`null`); with Playing alone on the stack (the sidebar's *Playing* row, or restored on its own) it
 * goes to the mode's first page (FR-R337-3), so the lyrics button and ⌥⌘L / Ctrl+L are never a dead click.
 */
internal fun leavePlayingTarget(stack: List<Dest>, inMusic: Boolean, displayName: String): Dest? = when {
    stack.size > 1 -> null
    inMusic -> Dest.MusicListen(displayName)
    else -> Dest.Home(displayName)
}
