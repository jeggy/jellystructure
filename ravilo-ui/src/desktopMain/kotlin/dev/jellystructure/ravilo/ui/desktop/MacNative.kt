package dev.jellystructure.ravilo.ui.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * R328 (FR-R328-4) — the one place the Mac app loads its Swift library, `libravilo-mac.dylib`, whose C exports
 * (`@_cdecl`) are called through JNA. Absent on Linux, and absent on a Mac when the library was not built
 * (a `gradle run` without Xcode): then [lib] is null, one line says so, and every caller takes its fallback —
 * never a crash. The packaged app finds the library in Compose's resources directory; `-Dravilo.native.lib` points
 * at a specific file.
 *
 * [ABI] is bumped whenever an export changes shape, so a stale library is refused rather than called wrongly.
 */
object MacNative {
    const val ABI = 4
    const val LIBRARY_FILE = "libravilo-mac.dylib"

    @Suppress("FunctionName")
    interface Lib : Library {
        fun ravilo_abi(): Int
        fun ravilo_free(p: Pointer?)

        // ── R328 — the Keychain and the Computer Name ──
        /** The generic password for (service, account), UTF-8; null when there is none. Free with [ravilo_free]. */
        fun ravilo_keychain_get(service: String, account: String): Pointer?
        /** Creates or replaces; 0 on success, else the `OSStatus`. */
        fun ravilo_keychain_set(service: String, account: String, value: String): Int
        /** 0 on success or when there was nothing to delete, else the `OSStatus`. */
        fun ravilo_keychain_delete(service: String, account: String): Int
        fun ravilo_computer_name(): Pointer?

        // ── R329 — the player (Player.swift; see MacPlayer) ──
        fun ravilo_player_create(): Long
        fun ravilo_player_load(h: Long, url: String, mime: String, startMs: Long, audioOnly: Int)
        fun ravilo_player_play(h: Long)
        fun ravilo_player_pause(h: Long)
        fun ravilo_player_seek(h: Long, ms: Long)
        fun ravilo_player_set_rate(h: Long, rate: Float)
        fun ravilo_player_set_volume(h: Long, volume: Float)
        fun ravilo_player_select_audio(h: Long, index: Int)
        fun ravilo_player_tick(h: Long)
        fun ravilo_player_state(h: Long, out: LongArray, count: Int)
        fun ravilo_player_error(h: Long): Pointer?
        fun ravilo_player_audio_options(h: Long): Pointer?
        fun ravilo_player_debug(h: Long): Pointer?
        fun ravilo_player_copy_frame(h: Long, dst: Pointer?, capacity: Long, dims: IntArray): Int
        fun ravilo_player_release(h: Long)
        fun ravilo_caps_playable(mime: String): Int

        // ── R329 — Now Playing and the media keys (NowPlaying.swift), display sleep (Power.swift) ──
        fun ravilo_nowplaying_set_handler(cb: RemoteCallback?)
        fun ravilo_nowplaying_set_mode(mode: Int)
        fun ravilo_nowplaying_update(title: String, artist: String, album: String, durationMs: Long, positionMs: Long, rate: Double, playing: Int, video: Int)
        fun ravilo_nowplaying_artwork(bytes: ByteArray?, length: Long)
        fun ravilo_nowplaying_clear()
        fun ravilo_display_keep_awake(on: Int)
        // R338 — 1 when the system is in Dark mode. Added without an ABI bump (no existing export changed shape);
        // a library from before it has no such symbol and the call throws, which DesktopAppearance catches.
        fun ravilo_appearance_dark(): Int

        // ── R330 — Bonjour (Bonjour.swift; see MacBonjour) ──
        fun ravilo_bonjour_start()
        fun ravilo_bonjour_stop()
        fun ravilo_bonjour_snapshot(): Pointer?
    }

    /** A media key or Control Center: `command` as NowPlaying.swift numbers them, `value` in seconds. */
    fun interface RemoteCallback : com.sun.jna.Callback {
        fun invoke(command: Int, value: Double)
    }

    /** Why the library is absent, for `--self-test` and the log; null when it loaded. */
    @Volatile var loadError: String? = null
        private set

    val lib: Lib? by lazy { load() }

    /** The library's path, or null where it is not expected (Linux) or not found. */
    fun locate(): File? {
        System.getProperty("ravilo.native.lib")?.trim()?.ifBlank { null }?.let { return File(it).takeIf(File::isFile) }
        val resources = System.getProperty("compose.application.resources.dir")?.ifBlank { null } ?: return null
        return File(resources, LIBRARY_FILE).takeIf(File::isFile)
    }

    private fun load(): Lib? {
        if (!DesktopPaths.isMac) { loadError = "not a Mac"; return null }
        val file = locate() ?: return absent("$LIBRARY_FILE not found")
        val loaded = runCatching {
            Native.load(file.absolutePath, Lib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.getOrElse { return absent("$LIBRARY_FILE did not load: ${it.message}") }
        val abi = runCatching { loaded.ravilo_abi() }.getOrElse { return absent("$LIBRARY_FILE answered no ABI: ${it.message}") }
        if (abi != ABI) return absent("$LIBRARY_FILE is ABI $abi, this app needs $ABI")
        return loaded
    }

    private fun absent(why: String): Lib? {
        loadError = why
        println("Ravilo: the Mac library is absent ($why) — no playback, tokens in a file")
        return null
    }

    /** Reads and frees a string the library allocated. */
    fun take(p: Pointer?): String? {
        if (p == null) return null
        return try { p.getString(0, "UTF-8") } finally { lib?.ravilo_free(p) }
    }
}
