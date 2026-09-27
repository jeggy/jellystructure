package dev.jellystructure.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 267 (FR-267-1) — the three ordering rules of Users & devices. */
class OverviewOrderTest {
    private data class Dev(val id: String, val connected: Boolean, val lastSeen: Long, val createdAt: Long = 0)
    private data class Ses(val id: String, val lastUsedAt: Long)
    private data class User(val name: String, val devices: List<Dev>, val sessions: List<Ses> = emptyList())

    private fun devs(list: List<Dev>) = OverviewOrder.devices(list, { it.connected }, { it.lastSeen }, { it.createdAt }).map { it.id }

    @Test
    fun `a connected TV with an old last_seen ranks above a phone that made a request since`() {
        val order = devs(listOf(
            Dev("phone", connected = false, lastSeen = 5_000),
            Dev("tv", connected = true, lastSeen = 1_000),
            Dev("web", connected = false, lastSeen = 3_000),
        ))
        assertEquals(listOf("tv", "phone", "web"), order)
    }

    @Test
    fun `equal last_seen breaks by created_at — newest first — so two loads agree`() {
        val order = devs(listOf(
            Dev("old", connected = false, lastSeen = 2_000, createdAt = 10),
            Dev("new", connected = false, lastSeen = 2_000, createdAt = 20),
        ))
        assertEquals(listOf("new", "old"), order)
    }

    @Test
    fun `web sessions newest first`() {
        assertEquals(listOf("b", "c", "a"), OverviewOrder.sessions(listOf(Ses("a", 1), Ses("b", 9), Ses("c", 5))) { it.lastUsedAt }.map { it.id })
    }

    @Test
    fun `the user who used Ravilo last comes first — connected counts as now — then by name`() {
        val now = 100_000L
        val users = listOf(
            User("zed", listOf(Dev("z1", connected = false, lastSeen = 90_000))),
            User("anna", listOf(Dev("a1", connected = false, lastSeen = 10_000)), listOf(Ses("s", 95_000))),
            User("bo", listOf(Dev("b1", connected = true, lastSeen = 1_000))),
            User("Ada", listOf(Dev("x1", connected = false, lastSeen = 90_000))),
            User("nobody", emptyList()),
        )
        val order = OverviewOrder.users(
            users,
            activity = { u -> OverviewOrder.activity(now, u.devices.any { it.connected }, u.devices.map { it.lastSeen }, u.sessions.map { it.lastUsedAt }) },
            name = { it.name },
        ).map { it.name }
        assertEquals(listOf("bo", "anna", "Ada", "zed", "nobody"), order)
    }
}
