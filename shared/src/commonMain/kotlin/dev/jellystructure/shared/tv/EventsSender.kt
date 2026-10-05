package dev.jellystructure.shared.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * R369/R370 — the events socket's outgoing frames (`attach_session`, `detach_session`, `cast_devices_seen`). Started
 * UNDISPATCHED, so the collector is subscribed to [outgoing] before this returns: a hot flow with no replay drops what is
 * emitted while nobody collects, and the socket's `onOpen` emits straight away (re-attach, the Cast devices this app
 * sees). A failed send is dropped (the socket is closing; the next open says it all again).
 */
fun CoroutineScope.launchEventsSender(outgoing: Flow<String>?, send: suspend (String) -> Unit): Job? =
    outgoing?.let { flow -> launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { runCatching { send(it) } } } }
