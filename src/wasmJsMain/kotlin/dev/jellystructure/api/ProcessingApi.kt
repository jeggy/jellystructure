package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

// DEB-1664 (FR-12) — `GET /api/processing`: the nine features with every value and every sentence built on the server.
// Mirrors `media/ProcessingState.kt`'s views field for field; the defaults match the server's, because the server leaves a
// default-valued field out of the body.

@Serializable
data class ProcessingPartView(val id: String, val name: String, val on: Boolean = false, val canRun: Boolean = true, val reason: String? = null)

@Serializable
data class ProcessingFeatureView(
    val id: String,
    val name: String,
    val queuesWork: Boolean = false,
    val enabled: Boolean = false,
    /** Shown on and disabled (Find new & changed). */
    val locked: Boolean = false,
    val canRun: Boolean = true,
    /** `on` | `off` | `unavailable` | `running` | `waiting` (held for a TV). */
    val state: String = "on",
    val reason: String? = null,
    val interval: String = "",
    val lastRanAt: Long? = null,
    val lastRan: String = "",
    val nextAt: Long? = null,
    val next: String = "",
    val waiting: Int = 0,
    val waitingText: String = "",
    val parts: List<ProcessingPartView> = emptyList(),
)

@Serializable
data class ProcessingView(
    val features: List<ProcessingFeatureView> = emptyList(),
    val holdingForPlayback: Boolean = false,
    /** FR-4.2 — which device is playing while the household switch holds; null when nothing is being held. */
    val holdingText: String? = null,
)

object ProcessingApi {
    suspend fun get(): ProcessingView? = runCatching { httpClient.get("/api/processing").body<ProcessingView>() }.getOrNull()
}
