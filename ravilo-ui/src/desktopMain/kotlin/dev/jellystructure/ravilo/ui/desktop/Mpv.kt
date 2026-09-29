package dev.jellystructure.ravilo.ui.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * R335 (FR-R335-1) — `libmpv` over JNA: the twenty-odd functions the engine needs, the two structs it reads
 * (`mpv_event`, `mpv_render_param`) and the property helpers, so nothing above this file sees a pointer. Loaded
 * lazily; absent (a Linux without libmpv, or the Mac) leaves [lib] null and the engine unavailable.
 */
internal object Mpv {
    /** The C library (`libmpv.so.2`), or null with [loadError] saying why. */
    val lib: MpvLib? by lazy { load() }
    var loadError: String? = null
        private set

    private fun load(): MpvLib? {
        if (DesktopPaths.isMac) { loadError = "not used on a Mac"; return null }
        for (name in listOf("libmpv.so.2", "mpv")) {
            try {
                return Native.load(name, MpvLib::class.java)
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message?.lineSequence()?.firstOrNull() ?: "libmpv not found"
            }
        }
        return null
    }

    // ── client.h ──
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_VIDEO_RECONFIG = 17
    const val EVENT_SEEK = 20
    const val EVENT_PLAYBACK_RESTART = 21

    /** `mpv_event_end_file.reason` */
    const val END_FILE_EOF = 0
    const val END_FILE_STOP = 2
    const val END_FILE_QUIT = 3
    const val END_FILE_ERROR = 4
    const val END_FILE_REDIRECT = 5

    // ── render.h ──
    const val RENDER_PARAM_INVALID = 0
    const val RENDER_PARAM_API_TYPE = 1
    const val RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
    const val RENDER_PARAM_SW_SIZE = 17
    const val RENDER_PARAM_SW_FORMAT = 18
    const val RENDER_PARAM_SW_STRIDE = 19
    const val RENDER_PARAM_SW_POINTER = 20
    const val RENDER_UPDATE_FRAME = 1L

    /** `mpv_render_param` is `{ int type; void* data; }` — 16 bytes on x86-64 with the pointer aligned at 8. */
    private const val PARAM_BYTES = 16L

    /** A NUL-terminated C string that lives as long as the returned memory does. */
    fun cString(s: String): Memory {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return Memory((bytes.size + 1).toLong()).also { it.write(0, bytes, 0, bytes.size); it.setByte(bytes.size.toLong(), 0) }
    }

    /** An array of `mpv_render_param`, terminated by `MPV_RENDER_PARAM_INVALID`. The data pointers must outlive it. */
    fun params(vararg entries: Pair<Int, Pointer?>): Memory {
        val m = Memory((entries.size + 1) * PARAM_BYTES)
        entries.forEachIndexed { i, (type, data) ->
            m.setInt(i * PARAM_BYTES, type)
            m.setPointer(i * PARAM_BYTES + 8, data)
        }
        m.setInt(entries.size * PARAM_BYTES, RENDER_PARAM_INVALID)
        m.setPointer(entries.size * PARAM_BYTES + 8, null)
        return m
    }

    /** `mpv_event`: `{ int event_id; int error; uint64_t reply_userdata; void* data; }`. */
    class Event(private val p: Pointer) {
        val id: Int get() = p.getInt(0)
        val error: Int get() = p.getInt(4)
        val data: Pointer? get() = p.getPointer(16)
        /** `mpv_event_end_file`: `{ int reason; int error; … }` */
        val endFileReason: Int get() = data?.getInt(0) ?: END_FILE_EOF
        val endFileError: Int get() = data?.getInt(4) ?: 0
        /** `mpv_event_log_message`: `{ const char* prefix; const char* level; const char* text; int log_level; }` */
        val logText: String get() = data?.let { d -> (d.getPointer(16)?.getString(0) ?: "").trimEnd() + " [" + (d.getPointer(0)?.getString(0) ?: "") + "]" } ?: ""
    }

    // ── property helpers (every call is thread-safe on mpv's side) ──

    fun getDouble(lib: MpvLib, h: Pointer, name: String): Double? {
        val ref = DoubleByReference()
        return if (lib.mpv_get_property(h, name, FORMAT_DOUBLE, ref.pointer) >= 0) ref.value else null
    }

    fun getLong(lib: MpvLib, h: Pointer, name: String): Long? {
        val ref = LongByReference()
        return if (lib.mpv_get_property(h, name, FORMAT_INT64, ref.pointer) >= 0) ref.value else null
    }

    fun getFlag(lib: MpvLib, h: Pointer, name: String): Boolean? {
        val ref = IntByReference()
        return if (lib.mpv_get_property(h, name, FORMAT_FLAG, ref.pointer) >= 0) ref.value != 0 else null
    }

    fun getString(lib: MpvLib, h: Pointer, name: String): String? {
        val p = lib.mpv_get_property_string(h, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }
}

@Suppress("FunctionName")
internal interface MpvLib : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_set_option_string(h: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(h: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_get_property(h: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_command(h: Pointer, args: StringArray): Int
    fun mpv_command_string(h: Pointer, args: String): Int
    fun mpv_request_log_messages(h: Pointer, minLevel: String): Int
    fun mpv_wait_event(h: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(h: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_free(ctx: Pointer)
}
