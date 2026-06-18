package dev.jellystructure

import kotlinx.browser.window

object Router {
    /** Returns the path portion of the current hash route (before `?`). */
    fun currentPath(): String {
        val hash = window.location.hash.removePrefix("#").ifEmpty { "/" }
        return hash.substringBefore('?')
    }

    /** Returns decoded query params of the current hash route. */
    fun currentQuery(): Map<String, String> = parseQuery(window.location.hash.removePrefix("#"))

    /** Legacy compat — full hash string (path + query). */
    fun current(): String = window.location.hash.removePrefix("#").ifEmpty { "/" }

    /**
     * Navigate to [path] with optional [query] params.
     * [replace] = true updates the URL without adding a history entry (use for live search etc.).
     */
    fun navigate(path: String, query: Map<String, String> = emptyMap(), replace: Boolean = false) {
        val qs = buildQuery(query)
        val hash = if (qs.isEmpty()) "#$path" else "#$path?$qs"
        if (replace) {
            window.history.replaceState(null, "", hash)
        } else {
            window.location.hash = hash
        }
    }

    /** Updates only the query of the current path, preserving path and unmentioned params. */
    fun updateQuery(updates: Map<String, String?>, replace: Boolean = true) {
        val path = currentPath()
        val current = currentQuery().toMutableMap()
        for ((k, v) in updates) {
            if (v == null) current.remove(k) else current[k] = v
        }
        navigate(path, current, replace)
    }

    fun init(handler: (String) -> Unit) {
        window.addEventListener("hashchange") {
            handler(current())
        }
    }

    private fun parseQuery(full: String): Map<String, String> {
        val qs = full.substringAfter('?', "")
        if (qs.isEmpty()) return emptyMap()
        return qs.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null
            else decodeURIComponent(pair.substring(0, idx)) to decodeURIComponent(pair.substring(idx + 1))
        }.toMap()
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
        }
}
