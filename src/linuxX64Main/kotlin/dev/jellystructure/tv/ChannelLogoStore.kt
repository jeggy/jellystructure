package dev.jellystructure.tv

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ChannelLogo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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

    /**
     * DEB-1511 FR-1 — the 400 message for an `.svg` whose lettering is live `<text>`, else null. Ravilo Web and the
     * desktop apps draw SVG through Skia's `SVGDOM`, which draws no text; the admin's `<img>` preview hides that.
     */
    fun liveTextRefusal(originalName: String, bytes: ByteArray): String? =
        if (originalName.substringAfterLast('.', "").lowercase() == "svg" && carriesLiveText(bytes))
            "this SVG's lettering is live text (<text>), which Ravilo's web and desktop apps cannot draw — " +
                "outline the text (convert it to paths) or upload a PNG"
        else null

    /** Validate + store an uploaded logo; returns its public URL + label, or null if rejected. */
    suspend fun save(originalName: String, bytes: ByteArray): ChannelLogo? {
        val ext = originalName.substringAfterLast('.', "").lowercase()
        if (ext !in allowedExt) return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        if (liveTextRefusal(originalName, bytes) != null) return null
        ensureDir()
        val base = originalName.substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("").trim('-').lowercase().take(40).ifEmpty { "logo" }
        val stored = "${nowMs()}-$base.$ext"
        val tmp = "$dir/$stored.tmp"
        FileIo.writeBytes(Path(tmp), bytes)   // Phase 134: use{}-scoped — no FD leak on a mid-write throw
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
            .map { ChannelLogo("$publicBase/$it", labelFor(it), liveText = hasLiveText(it)) }
    }

    /** DEB-1788 (FR-1) — whether [name] is a stored logo this store would serve: no path part, not a `.tmp`, an allowed
     *  extension, and the file is there. */
    fun exists(name: String): Boolean =
        ".." !in name && "/" !in name && "\\" !in name && !name.endsWith(".tmp") &&
            name.substringAfterLast('.', "").lowercase() in allowedExt && SystemFileSystem.exists(Path("$dir/$name"))

    /** DEB-1788 (FR-1) — delete a stored logo; false when [exists] is false (nothing is touched). The caller decides
     *  whether anything uses it. */
    suspend fun remove(name: String): Boolean {
        if (!exists(name)) return false
        if (!runCatching { SystemFileSystem.delete(Path("$dir/$name")) }.isSuccess) return false
        Logger.info("Channel logo removed: $name")
        return true
    }

    /** DEB-1511 FR-2 — a stored `.svg` that [liveTextRefusal] would refuse today. */
    private fun hasLiveText(stored: String): Boolean {
        if (stored.substringAfterLast('.', "").lowercase() != "svg") return false
        val bytes = runCatching { FileIo.readBytes(Path("$dir/$stored")) }.getOrNull() ?: return false
        return carriesLiveText(bytes)
    }

    /** Read a stored logo by filename (path-traversal guarded). */
    fun read(name: String): ByteArray? {
        if (".." in name || "/" in name || "\\" in name) return null
        val p = Path("$dir/$name")
        if (!SystemFileSystem.exists(p)) return null
        return FileIo.readBytes(p)
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

    companion object {
        /** A `<text` element start, case-insensitive, optionally namespaced (`<svg:text`). `<textPath` does not count. */
        private val textElement = Regex("<(?:[a-z_][\\w.-]*:)?text(?=[\\s/>])", RegexOption.IGNORE_CASE)

        fun carriesLiveText(bytes: ByteArray): Boolean = textElement.containsMatchIn(bytes.decodeToString())
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun nowMs(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }
}
