package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

data class LogoBatchResult(val fetched: Int, val skipped: Int, val failed: Int)

class LogoDownloader(
    private val dataDir: String,
    private val tmdbClient: TmdbClient,
) {
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }

    private val http = HttpClient(Curl) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis  = 120_000
            requestTimeoutMillis = 120_000
        }
    }

    private fun logoFile(kind: String, name: String): String {
        val slug = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        return "$dataDir/artwork/$kind/$slug.png"
    }

    private fun ensureDir(kind: String) {
        val dir = Path("$dataDir/artwork/$kind")
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
    }

    fun hasLogo(kind: String, name: String): Boolean =
        SystemFileSystem.exists(Path(logoFile(kind, name)))

    fun serveLogo(kind: String, name: String): ByteArray? {
        val path = Path(logoFile(kind, name))
        return if (SystemFileSystem.exists(path))
            SystemFileSystem.source(path).buffered().readByteArray()
        else null
    }

    suspend fun fetchStudioLogo(name: String, tmdbId: Int?, capturedLogoPath: String?): Boolean {
        ensureDir("studios")
        val destPath = logoFile("studios", name)
        if (SystemFileSystem.exists(Path(destPath))) return true
        val logoPath = capturedLogoPath ?: run {
            tmdbClient.searchCompany(name)?.logoPath
        } ?: return false
        return download("$TMDB_ORIGINAL$logoPath", destPath)
    }

    suspend fun fetchNetworkLogo(name: String, capturedLogoPath: String?): Boolean {
        if (capturedLogoPath.isNullOrBlank()) return false
        ensureDir("networks")
        val destPath = logoFile("networks", name)
        if (SystemFileSystem.exists(Path(destPath))) return true
        return download("$TMDB_ORIGINAL$capturedLogoPath", destPath)
    }

    suspend fun batchFetchStudios(studios: List<Triple<String, Int?, String?>>): LogoBatchResult {
        var fetched = 0; var skipped = 0; var failed = 0
        for ((name, tmdbId, logoPath) in studios) {
            if (hasLogo("studios", name)) { skipped++; continue }
            val ok = runCatching { fetchStudioLogo(name, tmdbId, logoPath) }.getOrDefault(false)
            if (ok) fetched++ else failed++
        }
        return LogoBatchResult(fetched, skipped, failed)
    }

    suspend fun batchFetchNetworks(networks: List<Pair<String, String?>>): LogoBatchResult {
        var fetched = 0; var skipped = 0; var failed = 0
        for ((name, logoPath) in networks) {
            if (hasLogo("networks", name)) { skipped++; continue }
            val ok = runCatching { fetchNetworkLogo(name, logoPath) }.getOrDefault(false)
            if (ok) fetched++ else failed++
        }
        return LogoBatchResult(fetched, skipped, failed)
    }

    private fun personImageFile(tmdbId: Int) = "$dataDir/artwork/people/$tmdbId.jpg"

    fun servePersonImage(tmdbId: Int): ByteArray? {
        val path = Path(personImageFile(tmdbId))
        return if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered().readByteArray() else null
    }

    suspend fun fetchPersonImage(tmdbId: Int, profilePath: String?): Boolean {
        if (profilePath.isNullOrBlank()) return false
        ensureDir("people")
        val destPath = personImageFile(tmdbId)
        if (SystemFileSystem.exists(Path(destPath))) return true
        return download("https://image.tmdb.org/t/p/w185$profilePath", destPath)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun download(url: String, destPath: String): Boolean {
        val result = runCatching {
            val bytes = httpGet(url).readRawBytes()
            if (bytes.isEmpty()) return@runCatching false
            val tmp = "$destPath.tmp"
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.write(bytes, 0, bytes.size)
            sink.flush()
            sink.close()
            platform.posix.rename(tmp, destPath)
            Logger.info("Downloaded logo: $destPath", "artwork")
            true
        }
        if (result.isFailure) Logger.warn("Failed to download logo $url: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(false)
    }
}
