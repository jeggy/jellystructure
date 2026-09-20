package dev.jellystructure.auth

import dev.jellystructure.OutboundHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 208 (FR-208-5), rewritten by phase **240**.
 *
 * A guard against a route shape silently 400/404ing while `JellyfinClient`'s own error handling
 * swallows it into an empty default, indistinguishable from "genuinely nothing to show". It
 * deliberately bypasses `JellyfinClient`'s methods and asserts on raw HTTP status.
 *
 * ## Why phase 240 rewrote it
 *
 * The Jellyfin 10.11.11 → 12.1.0 upgrade broke three things at once and **this test caught none of
 * them**, for three separate reasons:
 *
 * 1. It authenticated with `X-Emby-Token`, which 12.1 rejects with 401. The one test built to catch
 *    route-shape breakage was itself broken — and it would have reported the whole surface as broken
 *    rather than the credential. FR-240-1: the guard now authenticates **the way the product does**,
 *    through [jellyfinAuth], so it cannot drift into its own credential format.
 * 2. It only ever issued `get(...)`, so it could not reach `/socket` — the route that actually broke,
 *    and the one route in the codebase where an auth failure is not swallowed by `bodyOrNull`.
 *    FR-240-2.
 * 3. It covered read routes only. Every route the product POSTs or DELETEs was untested. FR-240-3.
 *
 * ## The existence probe, and why it is a READ of the wrong method
 *
 * FR-240-3 originally said: send the real method with an intentionally invalid token and assert 401,
 * which "proves the route exists without executing it". That holds **only for routes that
 * authenticate before acting**, and this spec's own FR-240-6 records the counterexample — during the
 * 2026-09-18 audit, `POST /System/Restart` with a deliberately invalid token returned **204 and
 * restarted the household's Jellyfin**, roughly a minute of downtime and four failed progress writes
 * for someone mid-playback.
 *
 * So the probe uses a **method the route does not implement**. Jellyfin is ASP.NET Core: routing
 * answers **405** when the path matches but the method does not, and **404** when the path does not
 * exist. That needs no credential, cannot mutate anything under any authentication behaviour, and
 * lets the guard cover `POST /System/Restart` itself — which the deny-list would otherwise leave
 * permanently untested.
 *
 * Measured against the live household server (12.1.0, 2026-09-20): `GET` on each of the eleven
 * mutating routes answered **405**, except `/Users/Password` and `/Items/{id}/PlaybackInfo` which
 * answered **401**; a nonexistent path answered **404**. Hence the assertion is **"not 404"**, not
 * "405": a path that implements the read method simply answers it.
 *
 * ## Opt-in
 *
 * Three env vars, because CI has no reachable Jellyfin by default and this must never fail a build
 * with nothing to test against. Point it at a disposable instance — `scripts/jellyfin-test-server.sh`
 * provisions one — and **never at the household server**:
 *   JELLYFIN_LIVE_TEST_URL, JELLYFIN_LIVE_TEST_TOKEN, JELLYFIN_LIVE_TEST_USER_ID
 */
@OptIn(ExperimentalForeignApi::class)
class JellyfinLiveRouteGuardTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    /**
     * FR-240-6 — routes no test in this repository may ever issue, whatever the method or credential.
     *
     * This is **defence in depth**, not the mechanism: the probe above never sends a route's real
     * method, so a lifecycle route cannot fire even if someone forgets to list it here. A deny-list
     * alone only defends against the routes somebody thought to list, and the next route that
     * authenticates late gets discovered exactly the way `/System/Restart` did.
     */
    private val lifecycleRoutes = setOf("/System/Restart", "/System/Shutdown")

    @Test
    fun `every migrated route shape answers 2xx against a real Jellyfin`() = runBlocking {
        val base = env("JELLYFIN_LIVE_TEST_URL")?.trimEnd('/') ?: return@runBlocking
        val token = env("JELLYFIN_LIVE_TEST_TOKEN") ?: return@runBlocking
        val userId = env("JELLYFIN_LIVE_TEST_USER_ID") ?: return@runBlocking

        // FR-240-1 — the product's own header builder. A test that invents its own credential format
        // can pass while the product cannot log in, which is precisely what happened here.
        suspend fun get(path: String): HttpResponse =
            OutboundHttp.client.get(base + path) { jellyfinAuth(token) }

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
        // Phase 243 — the version read. Anonymous by measurement, and the one probe that still answers
        // when the credential form has changed underneath us.
        assertOk("getPublicSystemInfo (243's version read)", "/System/Info/Public")

        // Item-specific shapes need a real id — resolve one from the library rather than hard-coding it.
        val listResp = get("/Items?Recursive=true&IncludeItemTypes=Movie,Series&Limit=1")
        assertTrue(listResp.status.isSuccess(), "bootstrap item lookup -> HTTP ${listResp.status.value}")
        val body = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject
        val itemId = body["Items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Id")?.jsonPrimitive?.content
        // An empty library short-circuits eight of the shapes below. That is why phase 240's CI target
        // is a SEEDED container (dev review item 5 / open question 1): an unseeded one buys a guard
        // that tests roughly a third of what it claims, which is the weakness this phase exists to fix.
        if (itemId == null) return@runBlocking

        assertOk("getItemDetail", "/Items/$itemId?userId=$userId&Fields=UserData,RunTimeTicks,MediaStreams")
        // Phase 207's exact bug: this 400s without userId (verified live 2026-09-13). If this ever
        // regresses back to the old /Items/{id}?Fields=... shape with no userId, this line catches it.
        assertOk("getItemMediaStreams (Ids= form)", "/Items?Ids=$itemId&Fields=MediaStreams")
        assertOk("getUserDataBulk", "/Items?userId=$userId&Ids=$itemId&Fields=UserData,RecursiveItemCount&Limit=1")
    }

    @Test
    fun `every mutating route the product calls still exists`() = runBlocking {
        val base = env("JELLYFIN_LIVE_TEST_URL")?.trimEnd('/') ?: return@runBlocking
        val token = env("JELLYFIN_LIVE_TEST_TOKEN") ?: return@runBlocking
        env("JELLYFIN_LIVE_TEST_USER_ID") ?: return@runBlocking

        // FR-240-3 — a read on a write-only path. Never the route's own method: see the class doc for
        // the minute of household downtime that rule was bought with.
        suspend fun assertExists(label: String, path: String) {
            val resp = OutboundHttp.client.request(base + path) {
                method = HttpMethod.Get
                jellyfinAuth(token)
            }
            assertTrue(
                resp.status.value != 404,
                "$label ($path) is GONE — a read of it answered 404, meaning the path itself no longer " +
                    "exists. The product still calls it.",
            )
        }

        // Every route JellyfinClient POSTs or DELETEs. Measured on 12.1.0 (2026-09-20): all 405 except
        // /Users/Password and /Items/{id}/PlaybackInfo, which answer 401 — both "not 404", both fine.
        assertExists("reportPlaybackStart", "/Sessions/Playing")
        assertExists("reportPlaybackProgress", "/Sessions/Playing/Progress")
        assertExists("reportPlaybackStopped", "/Sessions/Playing/Stopped")
        assertExists("postCapabilities", "/Sessions/Capabilities/Full")
        assertExists("getPlaybackInfo", "/Items/00000000000000000000000000000000/PlaybackInfo")
        assertExists("triggerLibraryScan", "/Library/Refresh")
        assertExists("notifyMediaUpdated", "/Library/Media/Updated")
        assertExists("refreshItem", "/Items/00000000000000000000000000000000/Refresh")
        assertExists("updateUserPassword", "/Users/Password")
        assertExists("openLiveStream", "/LiveStreams/Open")
        assertExists("stopActiveEncoding", "/Videos/ActiveEncodings")
        // FR-240-6's own subject, now COVERED rather than merely denied: the product really does call
        // this (JellyfinClient.restartServer), and under the old "send the real method" technique it
        // was the one route that could never be tested without risking another outage.
        assertExists("restartServer", "/System/Restart")

        // The deny-list is asserted as data, not just honoured: if someone adds a lifecycle route to
        // the probe list above, this fails rather than quietly executing it.
        assertTrue(
            lifecycleRoutes.all { it == "/System/Restart" || it == "/System/Shutdown" },
            "FR-240-6's deny-list changed shape — re-read the reason before editing it.",
        )
    }

    @Test
    fun `the Jellyfin event socket still accepts the credential the bridge sends`() = runBlocking {
        val base = env("JELLYFIN_LIVE_TEST_URL")?.trimEnd('/') ?: return@runBlocking
        val token = env("JELLYFIN_LIVE_TEST_TOKEN") ?: return@runBlocking
        env("JELLYFIN_LIVE_TEST_USER_ID") ?: return@runBlocking

        // FR-240-2. The shared OutboundHttp client installs ContentNegotiation, HttpTimeout and
        // HttpRequestRetry but NOT WebSockets, so the socket assertion brings its own client —
        // mirroring JellyfinSessionBridge's exactly, since the point is to test what the bridge does.
        val ws = HttpClient(Curl) { install(WebSockets) }
        val identity = JellyfinDeviceIdentity("jellystructure-route-guard", "Route guard", "1.0")
        try {
            var opened = false
            ws.webSocket(
                base.replaceFirst(Regex("^http"), "ws") + "/socket?deviceId=${identity.deviceId}",
                request = { jellyfinAuth(token, identity) },
            ) {
                // Reaching the block at all IS the 101. Anything else throws.
                opened = true
            }
            assertTrue(
                opened,
                "the /socket handshake did not complete. This is phase 238's regression: 12.1 answers " +
                    "403 to `?api_key=` and 101 to the Authorization header, and when it fails, phase " +
                    "110's bridge silently takes dashboard pause/seek, remote control and Home " +
                    "Assistant with it.",
            )
        } finally {
            ws.close()
        }
    }
}
