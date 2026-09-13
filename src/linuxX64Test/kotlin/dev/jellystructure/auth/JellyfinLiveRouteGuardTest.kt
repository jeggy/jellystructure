package dev.jellystructure.auth

import dev.jellystructure.OutboundHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 208 (FR-208-5) — a guard against the exact failure mode this phase's audit found by hand: a
 * route shape silently 400/404ing while `JellyfinClient`'s own error handling swallows it into an empty
 * default, indistinguishable from "genuinely nothing to show". Every one of the three historical
 * instances that cost a phase (163's `POST /MediaSegments` → 405, 187's actively-wrong-OpenAPI image
 * body, 207's `getItemMediaStreams` missing a `userId` for a whole phase's lifetime) would have been
 * caught by a test like this on the day it was written.
 *
 * Deliberately bypasses `JellyfinClient`'s own methods — those return `emptyList()`/`emptyMap()` on a
 * non-2xx same as on a genuinely empty result, which is exactly the ambiguity this guard exists to
 * catch. It asserts on the raw HTTP status of each migrated route SHAPE instead, mirroring the manual
 * curl verification the phase 208 spec itself was built from.
 *
 * Opt-in only via three env vars — CI has no reachable Jellyfin by default and this must never fail a
 * build with nothing to test against. Point it at the `jellyfin-demo` container (FR-208-3's own
 * preferred target) or another disposable instance; never the household:
 *   JELLYFIN_LIVE_TEST_URL, JELLYFIN_LIVE_TEST_TOKEN, JELLYFIN_LIVE_TEST_USER_ID
 */
@OptIn(ExperimentalForeignApi::class)
class JellyfinLiveRouteGuardTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    @Test
    fun `every migrated route shape answers 2xx against a real Jellyfin`() = runBlocking {
        val base = env("JELLYFIN_LIVE_TEST_URL") ?: return@runBlocking
        val token = env("JELLYFIN_LIVE_TEST_TOKEN") ?: return@runBlocking
        val userId = env("JELLYFIN_LIVE_TEST_USER_ID") ?: return@runBlocking

        suspend fun get(path: String): HttpResponse =
            OutboundHttp.client.get(base.trimEnd('/') + path) { header("X-Emby-Token", token) }

        suspend fun assertOk(label: String, path: String) {
            val resp = get(path)
            assertTrue(resp.status.isSuccess(), "$label ($path) -> HTTP ${resp.status.value}: ${resp.bodyAsText().take(180)}")
        }

        // The seven-caller /Users/{userId}/Items family (Phase 208) — one shape per distinct filter combo.
        assertOk("getResumeItems/getResumeItemsAll (IsResumable)", "/Items?userId=$userId&Filters=IsResumable&Recursive=true&IsPlayed=false&IncludeItemTypes=Movie,Episode&Limit=1")
        assertOk("getRecentlyPlayed/getRecentlyPlayedAll (IsPlayed)", "/Items?userId=$userId&Filters=IsPlayed&Recursive=true&IncludeItemTypes=Movie,Episode&Limit=1")
        assertOk("getRecentlyTouched (no filter, DatePlayed sort)", "/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Episode&Limit=1&SortBy=DatePlayed&SortOrder=Descending")
        assertOk("getFavoriteItemIds (IsFavorite)", "/Items?userId=$userId&Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series&Fields=Id&Limit=1")
        assertOk("getNextUp", "/Shows/NextUp?UserId=$userId&Limit=1")

        // Item-specific shapes need a real id — resolve one from the library rather than hard-coding it.
        val listResp = get("/Items?Recursive=true&IncludeItemTypes=Movie,Series&Limit=1")
        assertTrue(listResp.status.isSuccess(), "bootstrap item lookup -> HTTP ${listResp.status.value}")
        val body = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject
        val itemId = body["Items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Id")?.jsonPrimitive?.content
        if (itemId == null) return@runBlocking  // an empty library has nothing more to check here

        assertOk("getItemDetail", "/Items/$itemId?userId=$userId&Fields=UserData,RunTimeTicks,MediaStreams")
        // Phase 207's exact bug: this 400s without userId (verified live 2026-09-13). If this ever
        // regresses back to the old /Items/{id}?Fields=... shape with no userId, this line catches it.
        assertOk("getItemMediaStreams (Ids= form)", "/Items?Ids=$itemId&Fields=MediaStreams")
        assertOk("getUserDataBulk", "/Items?userId=$userId&Ids=$itemId&Fields=UserData,RecursiveItemCount&Limit=1")
    }
}
