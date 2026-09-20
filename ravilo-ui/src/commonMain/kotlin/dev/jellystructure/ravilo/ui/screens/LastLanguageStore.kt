package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.LastLanguageStore

/**
 * R279 — where this device remembers the language it last drew in, so that every screen shown
 * *before* a user is known is in the household's own language rather than English: the server-setup
 * screen, the login screen, the profile picker, and the whole of a cold start before `getConfig()`
 * has come back.
 *
 * Deliberately separate from [MultiTokenStore] and from [HomeSnapshotCache]: a sign-out, a profile
 * switch and an unpair all clear those, and the screen you land on immediately afterwards is
 * exactly the one this exists for.
 */
expect object DeviceLanguageStore : LastLanguageStore {
    override fun read(): String?
    override fun write(code: String)
}

/** Called once, before the first composition, so nothing reads the default in-memory store. */
fun installDeviceLanguageStore() {
    LastLanguage.store = DeviceLanguageStore
}
