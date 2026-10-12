package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.PlainPost

/**
 * R372 (FR-R372-11, amendment 2026-10-12) — the stop of a client whose process is going away *now*.
 *
 * A browser aborts every request in flight when its page unloads, so a tab closed mid-film left its row
 * *paused* at the last heartbeat's position until the watchdog found it (prod, 2026-10-12). The stop therefore
 * has to be issued inside the unload event itself, through a transport that survives the page —
 * `fetch(…, { keepalive: true })`, which still carries the device token in `Authorization` exactly as every
 * other call does. (`navigator.sendBeacon` is the fallback the spec allows if `keepalive` turns out unreliable
 * in a browser we support; it cannot set headers, so the token would have to ride the query string.)
 *
 * [transport] is installed by the browser alone. Everywhere else it is null, [post] answers false, and the stop
 * is the Ktor one on `exitScope` that every platform has always sent — there is no second code path to keep in
 * step. Nothing here is state the UI renders.
 */
object UnloadStop {

    /** The platform's page-surviving post; true when it handed the request to the browser. */
    var transport: ((PlainPost) -> Boolean)? = null

    /**
     * True while the page this stop belongs to is being torn down. Read by `PlayerStore.stopSession`, which then
     * posts through [transport] instead of launching a coroutine the page will never get round to running. An
     * ambient fact rather than a parameter: one platform of five ever sets it, and the three `PlayerLifecycleEffect`
     * actuals would otherwise all carry a flag only the browser's can be true.
     */
    var unloading: Boolean = false
        private set

    /** Runs [block] with [unloading] set — the browser's `pagehide` / `visibilitychange → hidden` handler. */
    fun <T> whileUnloading(block: () -> T): T {
        unloading = true
        return try { block() } finally { unloading = false }
    }

    /** Posts [request] through [transport]. False when this platform has none, or the transport refused it. */
    fun post(request: PlainPost): Boolean =
        transport?.let { runCatching { it(request) }.getOrDefault(false) } ?: false
}
