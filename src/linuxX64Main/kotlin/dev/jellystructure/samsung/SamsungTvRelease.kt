package dev.jellystructure.samsung

import dev.jellystructure.OutboundHttp
import dev.jellystructure.shared.tv.SamsungTvReleaseStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/** One bounded response, with the body read while holding OutboundHttp's permit. */
data class SamsungReleaseResponse(val status: Int, val body: ByteArray, val etag: String? = null)

private suspend fun fetchSamsungRelease(url: String, etag: String?): SamsungReleaseResponse {
    val limit = if (url.endsWith(".wgt")) 20 * 1024 * 1024 else 512 * 1024
    return OutboundHttp.withPermit(retryForeignCancellation = true) {
        val response = OutboundHttp.client.get(url) {
            header(HttpHeaders.UserAgent, "jellystructure-samsung-tv")
            if (!url.endsWith(".wgt")) header(HttpHeaders.Accept, "application/vnd.github+json")
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            timeout { requestTimeoutMillis = if (url.endsWith(".wgt")) 60_000 else 15_000; socketTimeoutMillis = if (url.endsWith(".wgt")) 60_000 else 15_000 }
        }
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > limit) error("Release response too large")
        val bytes = response.bodyAsChannel().readRemaining(limit.toLong() + 1).readByteArray()
        if (bytes.size > limit) error("Release response too large")
        SamsungReleaseResponse(response.status.value, bytes, response.headers[HttpHeaders.ETag])
    }
}

/** DEB-4842 (FR-4) — public, fixed-host latest-release certificate check. */
class SamsungTvRelease(
    private val requests: SamsungTvRequests,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
    private val fetch: suspend (String, String?) -> SamsungReleaseResponse = ::fetchSamsungRelease,
) {
    private val mutex = Mutex()
    private var etag: String? = null
    private var nextCheckAt = 0L
    private var verified: Triple<String, String, Set<String>>? = null
    private var status = SamsungTvReleaseStatus()
    fun status(): SamsungTvReleaseStatus = status

    suspend fun refresh(): SamsungTvReleaseStatus = mutex.withLock {
        val now = clock()
        if (now < nextCheckAt) return@withLock status
        nextCheckAt = now + 3_600L
        try {
            val metadata = fetch("https://api.github.com/repos/jeggy/jellystructure/releases/latest", etag)
            if (metadata.status == 304) {
                verified?.let { (tag, url, covered) -> requests.reconcile(tag, url, covered) }
                status = status.copy(checkedAt = now, error = null)
                return@withLock status
            }
            if (metadata.status != 200) error("GitHub release metadata returned ${metadata.status}")
            val release = Json.parseToJsonElement(metadata.body.decodeToString()).jsonObject
            val tag = release.getValue("tag_name").jsonPrimitive.content
            if (!tag.matches(Regex("[A-Za-z0-9._-]{1,80}"))) error("Invalid release tag")
            val prefix = "https://github.com/jeggy/jellystructure/releases/download/$tag/"
            val asset = release.getValue("assets").jsonArray.map { it.jsonObject }.singleOrNull {
                val name = it["name"]?.jsonPrimitive?.content ?: return@singleOrNull false
                name.matches(Regex("ravilo-tizen-[A-Za-z0-9._-]+\\.wgt"))
            } ?: error("Release has no single Tizen package")
            val url = asset.getValue("browser_download_url").jsonPrimitive.content
            if (!url.startsWith(prefix) || !url.endsWith(".wgt") || url != prefix + asset.getValue("name").jsonPrimitive.content) error("Unexpected Tizen asset URL")
            val packageResponse = fetch(url, null)
            if (packageResponse.status != 200) error("Tizen package returned ${packageResponse.status}")
            val covered = WgtCertificate.deviceIds(packageResponse.body) ?: error("Invalid Tizen package signature")
            requests.reconcile(tag, url, covered)
            verified = Triple(tag, url, covered)
            etag = metadata.etag
            status = SamsungTvReleaseStatus(tag, now, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = status.copy(checkedAt = now, error = e.message?.take(160) ?: "Release check failed")
        }
        status
    }
}
