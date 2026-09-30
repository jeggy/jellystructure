package dev.jellystructure.ravilo.ui.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R337 / R338 — the freedesktop portals Ravilo uses on Linux, over one session-bus connection through GIO
 * (`libgio-2.0`, in every GNOME desktop and in the Flatpak's runtime) and JNA, which the app already carries for mpv.
 * No new artefact for the Flatpak's offline sources (R333/R334) — the dev review's `dbus-java` lean, taken this way.
 *
 * - **Settings** (R338): `org.freedesktop.appearance color-scheme` (1 dark, 2 light, 0 no preference = light) and
 *   GNOME's `org.gnome.desktop.interface font-name` (R337 Q9), read once and followed through `SettingChanged`.
 * - **Background** (FR-R337-12): asked once, the first time the window closes while music plays.
 *
 * Portals need no Flatpak permission. Everything here is best-effort: no bus, no portal or no library ⇒ null, and
 * the caller keeps its default (dark; the fontconfig default font; keep playing). Values are read as GVariant text
 * (`g_variant_print`) — every call and every signal goes through one parser, and nothing is marshalled by hand.
 */
internal object LinuxPortal {
    private const val DEST = "org.freedesktop.portal.Desktop"
    private const val PATH = "/org/freedesktop/portal/desktop"

    @Suppress("FunctionName")
    private interface Gio : Library {
        fun g_bus_get_sync(busType: Int, cancellable: Pointer?, error: PointerByReference?): Pointer?
        fun g_dbus_connection_get_unique_name(connection: Pointer): String?
        fun g_variant_parse(type: Pointer?, text: String, limit: Pointer?, endptr: Pointer?, error: PointerByReference?): Pointer?
        fun g_variant_print(value: Pointer, typeAnnotate: Int): Pointer?
        fun g_variant_unref(value: Pointer)
        fun g_free(p: Pointer?)
        fun g_error_free(error: Pointer)
        fun g_dbus_connection_call_sync(
            connection: Pointer, busName: String, objectPath: String, interfaceName: String, methodName: String,
            parameters: Pointer?, replyType: Pointer?, flags: Int, timeoutMsec: Int, cancellable: Pointer?, error: PointerByReference?,
        ): Pointer?
        fun g_dbus_connection_signal_subscribe(
            connection: Pointer, sender: String?, interfaceName: String?, member: String?, objectPath: String?, arg0: String?,
            flags: Int, callback: SignalCallback, userData: Pointer?, userDataFreeFunc: Pointer?,
        ): Int
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(context: Pointer)
        fun g_main_context_iteration(context: Pointer, mayBlock: Int): Int
    }

    fun interface SignalCallback : Callback {
        fun invoke(connection: Pointer?, sender: String?, path: String?, iface: String?, signal: String?, parameters: Pointer?, userData: Pointer?)
    }

    private val gio: Gio? by lazy {
        if (DesktopPaths.isMac) return@lazy null
        runCatching { Native.load("libgio-2.0.so.0", Gio::class.java) }.getOrNull()
    }
    private val connection: Pointer? by lazy { gio?.let { g -> runCatching { g.g_bus_get_sync(2 /* G_BUS_TYPE_SESSION */, null, null) }.getOrNull() } }

    // Signal callbacks are invoked on the thread that subscribed, while it iterates its own main context. One thread
    // does both, forever (a daemon); the callbacks are held here so the garbage collector never frees them under GLib.
    private val callbacks = mutableListOf<SignalCallback>()
    private val loop: Pointer? by lazy {
        val g = gio ?: return@lazy null
        val ready = CountDownLatch(1)
        var ctx: Pointer? = null
        Thread({
            ctx = g.g_main_context_new().also { g.g_main_context_push_thread_default(it) }
            ready.countDown()
            val c = ctx!!
            while (true) {
                synchronized(pending) { pending.removeAll { it(); true } }
                g.g_main_context_iteration(c, 0)
                Thread.sleep(50)
            }
        }, "ravilo-portal").apply { isDaemon = true; start() }
        ready.await(2, TimeUnit.SECONDS)
        ctx
    }
    private val pending = mutableListOf<() -> Unit>()

    /** Subscribes on the portal thread (signals are dispatched to the subscribing thread's context). */
    private fun subscribe(iface: String, member: String, path: String?, onSignal: (String) -> Unit) {
        val g = gio ?: return
        val conn = connection ?: return
        loop ?: return
        val cb = SignalCallback { _, _, _, _, _, params, _ -> params?.let { printed(g, it) }?.let(onSignal) }
        synchronized(callbacks) { callbacks += cb }
        synchronized(pending) {
            pending += { runCatching { g.g_dbus_connection_signal_subscribe(conn, DEST, iface, member, path, null, 0, cb, null, null) } }
        }
    }

    private fun printed(g: Gio, v: Pointer): String? {
        val p = g.g_variant_print(v, 1) ?: return null
        return try { p.getString(0, "UTF-8") } finally { g.g_free(p) }
    }

    /** One method call; the reply as GVariant text, or null. */
    private fun call(iface: String, method: String, args: String, timeoutMs: Int = 1_500): String? {
        val g = gio ?: return null
        val conn = connection ?: return null
        return runCatching {
            val err = PointerByReference()
            val params = g.g_variant_parse(null, args, null, null, err) ?: return null
            try {
                val reply = g.g_dbus_connection_call_sync(conn, DEST, PATH, iface, method, params, null, 0, timeoutMs, null, err)
                if (reply == null) { err.value?.let { g.g_error_free(it) }; return null }
                try { printed(g, reply) } finally { g.g_variant_unref(reply) }
            } finally { g.g_variant_unref(params) }
        }.getOrNull()
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** A setting's value as GVariant text: `ReadOne` (portal v2), or `Read` on an older portal (a variant inside one). */
    fun readSetting(namespace: String, key: String): String? =
        call("org.freedesktop.portal.Settings", "ReadOne", "(${quote(namespace)}, ${quote(key)})")
            ?: call("org.freedesktop.portal.Settings", "Read", "(${quote(namespace)}, ${quote(key)})")

    /** R338 — the desktop's light/dark: true dark, false light (1 dark, 0 or 2 light), null when there is no portal. */
    fun colorSchemeDark(): Boolean? = readSetting("org.freedesktop.appearance", "color-scheme")?.let(::schemeDark)

    private fun schemeDark(text: String): Boolean? = Regex("""uint32 (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it == 1 }

    /** R338 — calls [onChange] whenever the system's light/dark changes. */
    fun watchColorScheme(onChange: (Boolean) -> Unit) {
        subscribe("org.freedesktop.portal.Settings", "SettingChanged", PATH) { text ->
            if ("'org.freedesktop.appearance'" in text && "'color-scheme'" in text) schemeDark(text)?.let(onChange)
        }
    }

    /**
     * R337 — GNOME's window buttons as the desktop orders them (`org.gnome.desktop.wm.preferences button-layout`, which
     * the Settings portal passes on so an app that draws its own header bar can follow it): `appmenu:close`,
     * `close,minimize,maximize:`, `icon,menu:minimize,maximize,close`. Null where the portal has no such setting.
     */
    fun buttonLayout(): String? = readSetting("org.gnome.desktop.wm.preferences", "button-layout")?.let(::lastQuoted)

    /** Calls [onChange] whenever the desktop's button layout changes (GNOME Tweaks, a `gsettings set`). */
    fun watchButtonLayout(onChange: (String) -> Unit) {
        subscribe("org.freedesktop.portal.Settings", "SettingChanged", PATH) { text ->
            if ("'org.gnome.desktop.wm.preferences'" in text && "'button-layout'" in text) lastQuoted(text)?.let(onChange)
        }
    }

    /** The last quoted string of a printed variant: the value in `(<<'x'>>,)` and in `('ns', 'key', <'x'>)`. */
    private fun lastQuoted(text: String): String? = Regex("""'([^']*)'""").findAll(text).lastOrNull()?.groupValues?.get(1)

    /** R337 (Q9) — GNOME's interface font family (`Adwaita Sans 11` ⇒ `Adwaita Sans`), or null. */
    fun interfaceFontFamily(): String? {
        val text = readSetting("org.gnome.desktop.interface", "font-name") ?: return null
        val name = Regex("'([^']+)'").find(text)?.groupValues?.get(1) ?: return null
        return name.trim().split(' ').let { parts -> if (parts.size > 1 && parts.last().toDoubleOrNull() != null) parts.dropLast(1) else parts }
            .joinToString(" ").ifBlank { null }
    }

    /**
     * FR-R337-12 — asks the Background portal once whether Ravilo may keep playing with its window closed. [onAnswer]
     * gets true (allowed), false (refused) or null (no portal — keep playing, as the Mac does). The response arrives
     * as a `Response` signal on a request object whose path we know in advance (the handle token), so the
     * subscription is in place before the call.
     */
    fun requestBackground(reason: String, onAnswer: (Boolean?) -> Unit) {
        val g = gio
        val conn = connection
        if (g == null || conn == null) { onAnswer(null); return }
        val sender = runCatching { g.g_dbus_connection_get_unique_name(conn) }.getOrNull()?.removePrefix(":")?.replace('.', '_')
        if (sender == null) { onAnswer(null); return }
        val token = "ravilo${System.nanoTime() % 1_000_000}"
        var answered = false
        subscribe("org.freedesktop.portal.Request", "Response", "/org/freedesktop/portal/desktop/request/$sender/$token") { text ->
            if (answered) return@subscribe
            answered = true
            val code = Regex("""uint32 (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            onAnswer(code == 0 && "'background': <true>" in text)
        }
        Thread.sleep(150)   // the subscription is made on the portal thread's next turn
        val reply = call(
            "org.freedesktop.portal.Background", "RequestBackground",
            "('', {'handle_token': <${quote(token)}>, 'reason': <${quote(reason)}>, 'autostart': <false>})",
            timeoutMs = 5_000,
        )
        if (reply == null && !answered) { answered = true; onAnswer(null) }
    }
}
