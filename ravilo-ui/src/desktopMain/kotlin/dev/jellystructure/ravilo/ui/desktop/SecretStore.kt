package dev.jellystructure.ravilo.ui.desktop

/**
 * R328 (D5, FR-R328-4) — device tokens: `tokens.json`, mode `0600`, in the app's data directory, on the Mac as on Linux.
 *
 * **Not the Keychain (owner, 2026-09-30).** R328 first kept each token as a Keychain item, on the assumption that one
 * signing certificate would let every later build read it. It does not: macOS binds a Keychain item to a *team ID*
 * when the app has one and to the **exact build** (its code-directory hash) when it has none — and an app signed with a
 * certificate of our own (R331, no Apple account) has none. So *Always Allow* lasted only until the next update, and
 * every update asked for the Mac's login password again. A token is a Ravilo device token, revocable in Users &
 * devices, never the viewer's password; a private file is what the Linux app has always used.
 *
 * A token an earlier build left in the Keychain is moved across the first time it is asked for (that read is the last
 * time macOS asks) and the Keychain item is removed.
 *
 * Every request asks for the active token, so reads are served from memory after the first.
 */
object SecretStore {
    const val SERVICE = "dev.jellystructure.ravilo"

    private val file by lazy { PrefsFile("tokens", private = true) }
    private val cache = HashMap<String, String?>()
    private val lock = Any()

    /** Where tokens live on this run, for `--self-test`. */
    val backend: String get() = "file"

    fun get(account: String): String? = synchronized(lock) {
        if (cache.containsKey(account)) return cache[account]
        val value = file.get(account) ?: fromKeychain(account)
        cache[account] = value
        value
    }

    /** One-way: a token from a build that used the Keychain comes into the file, and its Keychain item goes. */
    private fun fromKeychain(account: String): String? {
        val lib = MacNative.lib ?: return null
        val value = runCatching { MacNative.take(lib.ravilo_keychain_get(SERVICE, account)) }.getOrNull() ?: return null
        file.put(account, value)
        runCatching { lib.ravilo_keychain_delete(SERVICE, account) }
        println("${DesktopLog.stamp()} a sign-in moved from the Keychain to the app's own file")
        return value
    }

    fun set(account: String, value: String) = synchronized(lock) {
        if (cache.containsKey(account) && cache[account] == value) return
        cache[account] = value
        file.put(account, value)
    }

    fun delete(account: String) = synchronized(lock) {
        cache[account] = null
        file.put(account, null)
    }
}
