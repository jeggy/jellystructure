package dev.jellystructure.ravilo.ui.seams

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * DEB-2205 (FR-6) — fetches a subtitle file with OkHttp, the client the rest of the app already uses (R316): a
 * cancelled call closes its socket, so [SubtitleCall.cancel] frees a worker that a hanging server holds.
 *
 * The connect timeout is short; the read timeout tolerates a slow server (Jellyfin extracting a track) — and since every
 * call runs on [SubtitleWorkers], a server that never answers can only ever hold that worker, never the player.
 *
 * @param bearerFor the device token to send to [url], or null: the token is jellystructure's own and only goes to it.
 */
internal class OkHttpSubtitleSource(
    private val client: OkHttpClient = defaultClient(),
    private val bearerFor: (String) -> String? = { null },
) : SubtitleSource {
    override fun newCall(url: String): SubtitleCall {
        val request = try {
            Request.Builder().url(url).apply { bearerFor(url)?.let { header("Authorization", "Bearer $it") } }.build()
        } catch (e: IllegalArgumentException) {
            return object : SubtitleCall {
                override fun execute(): SubtitleFetch = SubtitleFetch.Failed("bad url")
                override fun cancel() = Unit
            }
        }
        val call = client.newCall(request)
        return object : SubtitleCall {
            override fun execute(): SubtitleFetch = try {
                call.execute().use { resp ->
                    when {
                        resp.code == 202 -> SubtitleFetch.NotReady(retryAfterMs(resp.header("Retry-After")))
                        resp.isSuccessful -> {
                            val body = resp.body
                            if (body.contentLength() > MAX_BYTES) SubtitleFetch.Failed("too large")
                            else SubtitleFetch.Body(body.string())
                        }
                        else -> SubtitleFetch.Failed("HTTP ${resp.code}")
                    }
                }
            } catch (e: IOException) {
                if (call.isCanceled()) SubtitleFetch.Cancelled else SubtitleFetch.Failed(e.javaClass.simpleName)
            }
            override fun cancel() = call.cancel()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 60L
        const val MAX_BYTES = 16L * 1024 * 1024

        /** The app's own client (R316) with this fetcher's timeouts: the same connection pool, its own limits. */
        fun deriveFrom(app: OkHttpClient): OkHttpClient = app.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        /** `Retry-After: 5` (delta-seconds) in ms; an HTTP-date, or nothing, is null (the controller then uses its default). */
        internal fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }
    }
}

/** DEB-2205 (FR-6) — two daemon threads, apart from the player's and the UI's: all subtitle I/O runs here. */
internal class SubtitleWorkers : SubtitleScheduler {
    private val pool = ScheduledThreadPoolExecutor(2) { r -> Thread(r, "ravilo-subtitle").apply { isDaemon = true } }
        .also {
            it.removeOnCancelPolicy = true
            it.setKeepAliveTime(30, TimeUnit.SECONDS)
            it.allowCoreThreadTimeOut(true)   // a player screen that is gone leaves no idle thread behind
        }

    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun post(task: Runnable): Cancellable { val f = pool.submit(task); return Cancellable { f.cancel(false) } }
    override fun postDelayed(delayMs: Long, task: Runnable): Cancellable {
        val f = pool.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return Cancellable { f.cancel(false) }
    }
    fun shutdown() { pool.shutdownNow() }
}

/**
 * DEB-2205 (FR-6) — where a ticket subtitle URL is requested and with what. jellystructure's own tracks come as a path
 * (`/api/tv/subtitles/{item}/{containerIndex}.vtt`), resolved against the address the app talks to and sent with the
 * device token (a player cannot attach one, the app can); a sidecar keeps Jellyfin's tokened absolute URL, sent as it is.
 */
internal object SubtitleRequest {
    /** The absolute URL for [url], or null when it is a path and there is no [base]. */
    fun absolute(url: String, base: String): String? = when {
        url.startsWith("http://", true) || url.startsWith("https://", true) -> url
        url.startsWith("/") && base.isNotBlank() -> base.trimEnd('/') + url
        else -> null
    }

    /** The device token for [absoluteUrl]: only when it is on [base]'s origin and under `/api/tv/`. */
    fun bearer(absoluteUrl: String, base: String, token: String?): String? {
        if (token.isNullOrBlank() || base.isBlank()) return null
        val b = base.trimEnd('/')
        if (!absoluteUrl.startsWith("$b/api/tv/", ignoreCase = true)) return null
        return token
    }
}
