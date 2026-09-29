package dev.jellystructure.ravilo.ui.desktop

/**
 * R328 (D5, FR-R328-4) — device tokens. On a Mac with its library: one generic-password Keychain item per token,
 * service [SERVICE]. Anywhere else (Linux, or a Mac build without the library): `tokens.json`, mode `0600`.
 *
 * Every request asks for the active token, so reads are served from memory after the first; the Keychain is only
 * touched on first read, on write and on delete. A Keychain item's access list is bound to the app's signing
 * identity (R331 FR-R331-3), so a build signed differently — a `gradle run` — may be asked to allow access.
 */
object SecretStore {
    const val SERVICE = "dev.jellystructure.ravilo"

    private val file by lazy { PrefsFile("tokens", private = true) }
    private val cache = HashMap<String, String?>()
    private val lock = Any()

    /** Where tokens live on this run, for `--self-test`. */
    val backend: String get() = if (MacNative.lib != null) "keychain" else "file"

    fun get(account: String): String? = synchronized(lock) {
        if (cache.containsKey(account)) return cache[account]
        val lib = MacNative.lib
        val value = if (lib != null) MacNative.take(lib.ravilo_keychain_get(SERVICE, account)) else file.get(account)
        cache[account] = value
        value
    }

    fun set(account: String, value: String) = synchronized(lock) {
        if (cache.containsKey(account) && cache[account] == value) return
        cache[account] = value
        val lib = MacNative.lib
        if (lib != null) {
            val status = lib.ravilo_keychain_set(SERVICE, account, value)
            if (status != 0) println("Ravilo: the Keychain refused to store a token (OSStatus $status)")
        } else file.put(account, value)
    }

    fun delete(account: String) = synchronized(lock) {
        cache[account] = null
        val lib = MacNative.lib
        if (lib != null) {
            val status = lib.ravilo_keychain_delete(SERVICE, account)
            if (status != 0) println("Ravilo: the Keychain refused to delete a token (OSStatus $status)")
        } else file.put(account, null)
    }
}
