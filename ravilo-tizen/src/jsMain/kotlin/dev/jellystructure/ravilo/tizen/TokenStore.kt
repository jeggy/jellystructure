package dev.jellystructure.ravilo.tizen

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/** R189 — device token + device id persistence, same shape/purpose as the other clients' token
 *  stores (`TokenStore`/`DeviceIdStore` in `ravilo-ui`), backed by browser `localStorage` (available
 *  on Tizen's WebKit same as any browser). */
object TokenStore {
    private const val TOKEN_KEY = "ravilo_device_token"

    fun get(): String? = localStorage[TOKEN_KEY]
    fun set(token: String) { localStorage[TOKEN_KEY] = token }
    fun clear() { localStorage.removeItem(TOKEN_KEY) }
}

object DeviceIdStore {
    private const val DEVICE_ID_KEY = "ravilo_device_id"

    fun get(): String {
        localStorage[DEVICE_ID_KEY]?.let { return it }
        val id = "tizen-" + randomId()
        localStorage[DEVICE_ID_KEY] = id
        return id
    }

    private fun randomId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
