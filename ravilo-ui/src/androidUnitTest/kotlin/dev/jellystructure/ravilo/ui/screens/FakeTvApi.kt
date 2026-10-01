package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * R350 — a [TvApiClient] for Compose focus tests that never touches a network: OkHttp answers every request from
 * [route] (the request's path → a JSON body, or null for a 404) inside an interceptor.
 */
internal fun fakeTvApiClient(route: (path: String) -> String?): TvApiClient {
    val client = HttpClient(OkHttp) {
        engine {
            addInterceptor { chain ->
                val request = chain.request()
                val body = route(request.url.encodedPath)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (body != null) 200 else 404)
                    .message(if (body != null) "OK" else "Not Found")
                    .body((body ?: "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }
    return TvApiClient(client, baseUrl = "http://fake.invalid", deviceToken = { "test" }, platform = "tv")
}
