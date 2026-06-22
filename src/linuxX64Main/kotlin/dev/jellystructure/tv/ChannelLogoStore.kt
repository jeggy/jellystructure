package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ChannelLogo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.rename
import platform.posix.timespec

/**
 * Server-owned (per-server) library of channel-button logo images, uploaded from the Ravilo config
 * editor (R36 §F). Files live under `<dataDir>/channel-logos` and are served publicly at
 * `/api/tv/channel-logos/<file>` — brand logos are not sensitive, and the TV loads them as the
 * channel's `logoUrl` without a device token. Uploads are validated (type + size); a malformed
 * filename is sanitised.
 */
class ChannelLogoStore(dataDir: String) {
    private val dir = "$dataDir/channel-logos"
    private val publicBase = "/api/tv/channel-logos"
    private val allowedExt = setOf("png", "svg", "jpg", "jpeg", "webp")
    private val maxBytes = 2 * 1024 * 1024 // 2 MB

    /** Validate + store an uploaded logo; returns its public URL + label, or null if rejected. */
    suspend fun save(originalName: String, bytes: ByteArray): ChannelLogo? {
        val ext = originalName.substringAfterLast('.', "").lowercase()
        if (ext !in allowedExt) return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        ensureDir()
        val base = originalName.substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("").trim('-').lowercase().take(40).ifEmpty { "logo" }
        val stored = "${nowMs()}-$base.$ext"
        val tmp = "$dir/$stored.tmp"
        val sink = SystemFileSystem.sink(Path(tmp)).buffered()
        sink.write(bytes, 0, bytes.size)
        sink.flush()
        sink.close()
        @OptIn(ExperimentalForeignApi::class)
        rename(tmp, "$dir/$stored")
        Logger.info("Channel logo uploaded: $stored (${bytes.size} bytes)")
        return ChannelLogo("$publicBase/$stored", labelFor(stored))
    }

    /** Prior uploads for the picker, newest first. */
    fun list(): List<ChannelLogo> {
        ensureDir()
        val entries = runCatching { SystemFileSystem.list(Path(dir)) }.getOrNull() ?: return emptyList()
        return entries.map { it.name }
            .filter { !it.endsWith(".tmp") && it.substringAfterLast('.', "").lowercase() in allowedExt }
            .sortedDescending()
            .map { ChannelLogo("$publicBase/$it", labelFor(it)) }
    }

    /** Read a stored logo by filename (path-traversal guarded). */
    fun read(name: String): ByteArray? {
        if (".." in name || "/" in name || "\\" in name) return null
        val p = Path("$dir/$name")
        if (!SystemFileSystem.exists(p)) return null
        return SystemFileSystem.source(p).buffered().readByteArray()
    }

    fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun ensureDir() {
        val p = Path(dir)
        if (!SystemFileSystem.exists(p)) runCatching { SystemFileSystem.createDirectories(p) }
    }

    /** "<ms>-<base>.<ext>" → "<base>" (the original filename, sans timestamp prefix + extension). */
    private fun labelFor(stored: String): String {
        val noExt = stored.substringBeforeLast('.')
        return noExt.substringAfter('-', noExt).ifEmpty { stored }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun nowMs(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }
}
