package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import dev.jellystructure.shared.tv.AdminSsoStartRequest
import dev.jellystructure.shared.tv.AdminSsoStartResponse
import dev.jellystructure.shared.tv.SsoProviders
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(val id: String, val name: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginError(val error: String)

object AuthApi {
    suspend fun isSetupNeeded(): Boolean = runCatching {
        val response = httpClient.get("/api/setup")
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)

    suspend fun me(): UserProfile? = runCatching {
        val response = httpClient.get("/api/auth/me")
        if (response.status == HttpStatusCode.OK) response.body<UserProfile>() else null
    }.getOrNull()

    suspend fun login(username: String, password: String): Result<UserProfile> = runCatching {
        val response = httpClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }
        when (response.status) {
            HttpStatusCode.OK -> response.body<UserProfile>()
            else -> {
                val err = runCatching { response.body<LoginError>() }.getOrNull()
                throw Exception(err?.error ?: "Login failed (${response.status.value})")
            }
        }
    }

    /** DEB-2233 (FR-8, FR-13) — the names the server's single sign-on lists, for *Sign in with ‹name›*. `[]` when there is none or it cannot be asked. */
    suspend fun ssoProviders(): List<String> = runCatching {
        val response = httpClient.get("/api/auth/providers")
        if (response.status == HttpStatusCode.OK) response.body<SsoProviders>().providers else emptyList()
    }.getOrDefault(emptyList())

    /** DEB-2233 (FR-9, FR-13) — where to send this browser. The server also sets the `js_sso` cookie that binds the return to this browser. */
    suspend fun ssoStart(provider: String): Result<String> = runCatching {
        val response = httpClient.post("/api/auth/sso/start") {
            contentType(ContentType.Application.Json)
            setBody(AdminSsoStartRequest(provider))
        }
        when (response.status) {
            HttpStatusCode.OK -> response.body<AdminSsoStartResponse>().authorizeUrl
            HttpStatusCode.ServiceUnavailable -> throw Exception("Single sign-on is set up wrong — check the dashboard")
            HttpStatusCode.TooManyRequests -> throw Exception("Too many attempts — try again in a minute")
            else -> throw Exception("Single sign-on can’t be started (${response.status.value})")
        }
    }

    suspend fun logout() {
        runCatching { httpClient.post("/api/auth/logout") }
    }

    suspend fun setup(jellyfinUrl: String, jellyfinToken: String, tmdbKey: String): Result<Unit> = runCatching {
        val response = httpClient.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"jellyfinUrl":"$jellyfinUrl","jellyfinToken":"$jellyfinToken","tmdbKey":"$tmdbKey"}""")
        }
        if (response.status != HttpStatusCode.NoContent) {
            val err = runCatching { response.body<LoginError>() }.getOrNull()
            throw Exception(err?.error ?: "Setup failed")
        }
    }
}
