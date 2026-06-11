package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
