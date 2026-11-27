package dev.jellystructure.ravilo.ui.screens

/**
 * DEB-5836 (FR-2) — the player's re-auth heading and body name the device they happen on. One pure function of the
 * platform flags, so it can be tested off a device. Ravilo Web runs on any device, so its wording names none.
 * Android TV and Samsung keep the original keys. A Mac is also a desktop, so Mac is asked before desktop.
 */
internal fun reauthKeys(tv: Boolean, mac: Boolean, desktop: Boolean, web: Boolean): Pair<String, String> = when {
    tv -> "error.play.reauth.title" to "error.play.reauth.body"
    web -> "error.play.reauth.title_web" to "error.load.reauth.body"
    mac -> "error.play.reauth.title_mac" to "error.play.reauth.body_mac"
    desktop -> "error.play.reauth.title_desk" to "error.play.reauth.body_desk"
    else -> "error.play.reauth.title_phone" to "error.play.reauth.body_phone"
}
