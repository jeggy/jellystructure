package dev.jellystructure.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 256 acceptance 2 — a reconnect inside the grace keeps the bridge; one after it opens a new one. */
class DeferredDisconnectsTest {
    @Test
    fun `a reconnect inside the grace cancels the disconnect`() = runBlocking {
        val fired = mutableListOf<String>()
        val d = DeferredDisconnects(CoroutineScope(SupervisorJob() + Dispatchers.Default), graceMs = 200L) { fired.add(it) }
        d.schedule("tv")
        assertTrue(d.isPending("tv"))
        delay(50L)
        assertTrue(d.cancel("tv"))       // the device came back: nothing fires
        delay(300L)
        assertEquals(emptyList(), fired)
        assertFalse(d.isPending("tv"))
        assertFalse(d.cancel("tv"))      // nothing left to cancel
    }

    @Test
    fun `after the grace the disconnect fires once`() = runBlocking {
        val fired = mutableListOf<String>()
        val d = DeferredDisconnects(CoroutineScope(SupervisorJob() + Dispatchers.Default), graceMs = 100L) { fired.add(it) }
        d.schedule("tv")
        d.schedule("tv")                 // a second close restarts the clock, it does not double the action
        delay(400L)
        assertEquals(listOf("tv"), fired)
        assertFalse(d.isPending("tv"))
    }
}
