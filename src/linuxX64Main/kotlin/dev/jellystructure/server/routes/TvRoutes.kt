package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.PairingChallenge
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.TvSession
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class PollRequest(@SerialName("poll_token") val pollToken: String)

@Serializable
private data class ApproveRequest(
    val code: String,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
private data class ViewerSettingsRequest(
    val skin: Skin? = null,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean? = null,
    @SerialName("tile_shape") val tileShape: TileShape? = null,
)

fun Route.tvRoutes(
    deviceService: RaviloDeviceService,
    raviloConfigService: RaviloConfigService,
    sessionService: SessionService,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
) {
    route("/tv/pair") {
        post("/start") {
            val result = deviceService.startPairing()
            call.respond(PairingChallenge(
                code = result.code,
                pollToken = result.pollToken,
                expiresAt = result.expiresAt,
            ))
        }

        post("/poll") {
            val req = call.receive<PollRequest>()
            val result = deviceService.pollPairing(req.pollToken)
            if (result == null) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "pending"))
                return@post
            }
            val (device, deviceToken) = result
            call.respond(PairResult(
                session = TvSession(
                    deviceId = device.deviceId,
                    userId = device.jellyfinUserId,
                    displayName = device.jellyfinUsername,
                    isAdmin = device.isAdmin,
                ),
                deviceToken = deviceToken,
            ))
        }

        // Approver is either an already-signed-in jellystructure admin (cookie)
        // or any Jellyfin user supplying credentials directly (phone form).
        post("/approve") {
            val req = call.receive<ApproveRequest>()

            val cookieToken = call.request.cookies["js_session"]
            val cookieSession = cookieToken?.let { sessionService.validate(it) }

            val jellyfinUserId: String
            val jellyfinUsername: String
            val jellyfinUserToken: String
            val isAdmin: Boolean

            if (cookieSession != null) {
                jellyfinUserId = cookieSession.jellyfinUserId
                jellyfinUsername = cookieSession.jellyfinUsername
                jellyfinUserToken = cookieSession.jellyfinUserToken
                isAdmin = true
            } else if (req.username != null && req.password != null) {
                val config = configStore.current
                if (config.apiKeys.jellyfinUrl.isBlank()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
                    return@post
                }
                val authResult = runCatching {
                    jellyfinClient.authenticateByName(config.apiKeys.jellyfinUrl, req.username, req.password)
                }.getOrElse {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Jellyfin credentials"))
                    return@post
                }
                jellyfinUserId = authResult.user.id
                jellyfinUsername = authResult.user.name
                jellyfinUserToken = authResult.accessToken
                isAdmin = authResult.user.policy.isAdministrator
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }

            val approved = deviceService.approvePairing(
                code = req.code,
                jellyfinUserId = jellyfinUserId,
                jellyfinUsername = jellyfinUsername,
                jellyfinUserToken = jellyfinUserToken,
                isAdmin = isAdmin,
            )
            if (!approved) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid or expired pairing code"))
                return@post
            }
            call.respond(mapOf("status" to "approved"))
        }

        post("/unpair") {
            val device = runCatching { call.attributes[DeviceKey] }.getOrNull()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No device session"))
                    return@post
                }
            deviceService.unpair(device.deviceToken)
            call.respond(mapOf("status" to "unpaired"))
        }
    }

    // ── Per-user config ──────────────────────────────────────────────────────
    get("/tv/config") {
        val device = call.attributes[DeviceKey]
        call.respond(raviloConfigService.getConfig(device.jellyfinUserId))
    }

    put("/tv/settings") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<ViewerSettingsRequest>()
        raviloConfigService.applyViewerSettings(
            userId = device.jellyfinUserId,
            skin = req.skin,
            showContinueProgress = req.showContinueProgress,
            tileShape = req.tileShape,
        )
        call.respond(mapOf("status" to "ok"))
    }
}
