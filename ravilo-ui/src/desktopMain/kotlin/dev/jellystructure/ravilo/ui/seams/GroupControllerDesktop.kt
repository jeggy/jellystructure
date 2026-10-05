package dev.jellystructure.ravilo.ui.seams

/** R371 (review item 3) — the desktop grows a group only once a speaker test proves ravilo-castv2 can; until then the
 *  server relays it to an Android app on the same network. */
actual fun platformGroupController(): GroupController? = null

/** R370–R372 — the sessions log (stdout; the desktop app's log file catches it). */
actual fun sessionLog(message: String) = println("RaviloSessions: $message")
