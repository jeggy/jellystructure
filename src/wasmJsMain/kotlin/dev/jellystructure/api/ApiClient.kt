package dev.jellystructure.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val httpClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    expectSuccess = false
}
