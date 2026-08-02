package dev.jellystructure.ravilo.tizen

/**
 * R189 — external bindings for the Tizen WebAPIs this app touches: `tizen.tvinputdevice` (remote-key
 * registration) and `webapis.avplay` (Samsung's native video pipeline — required on 2016-2018 Tizen;
 * the plain HTML5 `<video>` tag has no real HLS/DASH support on that era's WebKit/Chromium, per the
 * phase spec's research). **Unverified against real Tizen hardware** — this repo has no Tizen Studio
 * install to package/sideload a `.wgt` for on-device testing (see the spec's Verification section);
 * these signatures follow Samsung's public developer docs as closely as reasoning-without-a-device
 * allows, and may need correction once tested on a real 2016-2018 TV.
 */
@JsName("tizen")
external object TizenGlobal {
    val tvinputdevice: TvInputDevice
}

external interface TvInputDevice {
    fun registerKey(keyName: String)
    fun unregisterKey(keyName: String)
    fun getSupportedKeys(): Array<InputDeviceKey>
}

external interface InputDeviceKey {
    val name: String
    val code: Int
}

/** Samsung's video-playback namespace — historically `webapis.avplay`, distinct from `tizen.*`. */
@JsName("webapis")
external object WebApisGlobal {
    val avplay: AvPlayObject
}

external interface AvPlayObject {
    fun open(url: String)
    fun close()
    fun setDisplayRect(x: Int, y: Int, width: Int, height: Int)
    fun setDisplayMethod(method: String)
    fun prepareAsync(successCallback: () -> Unit, errorCallback: (dynamic) -> Unit)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(ms: Int, successCallback: (() -> Unit)? = definedExternally, errorCallback: ((dynamic) -> Unit)? = definedExternally)
    fun getState(): String
    fun getCurrentTime(): Int
    fun getDuration(): Int
    fun setListener(listener: AvPlayListener)
    fun setStreamingProperty(type: String, value: String)
    /** Samsung docs: `"AUDIO"` | `"TEXT"` | `"VIDEO"`. Returns the container's own enumerated tracks —
     *  index alignment with the `StreamTicket.audio`/`.subtitles` Jellyfin metadata is a best-effort
     *  assumption (both are container-stream-order), unverified against real hardware (see this
     *  module's other AVPlay doc comments). */
    fun getTotalTrackInfo(): Array<AvPlayTrackInfo>
    fun setSelectTrack(type: String, index: Int)
}

external interface AvPlayTrackInfo {
    val index: Int
    val type: String
    val extra_info: String
}

external interface AvPlayListener {
    var onbufferingstart: (() -> Unit)?
    var onbufferingprogress: ((Int) -> Unit)?
    var onbufferingcomplete: (() -> Unit)?
    var onstreamcompleted: (() -> Unit)?
    var oncurrentplaytime: ((Int) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
}

fun avPlayListener(
    onBufferingStart: () -> Unit = {},
    onBufferingComplete: () -> Unit = {},
    onStreamCompleted: () -> Unit = {},
    onCurrentPlayTime: (Int) -> Unit = {},
    onError: (dynamic) -> Unit = {},
): AvPlayListener = js("({})").unsafeCast<AvPlayListener>().apply {
    onbufferingstart = onBufferingStart
    onbufferingcomplete = onBufferingComplete
    onstreamcompleted = onStreamCompleted
    oncurrentplaytime = onCurrentPlayTime
    onerror = onError
}

/** Remote-key names this app registers/handles — Tizen's `tizen.tvinputdevice` key-name vocabulary
 *  (`KeyboardEvent.key` values these map to on keydown are the same as a standard D-pad remote). */
object TizenKeys {
    val REQUIRED = arrayOf(
        "MediaPlay", "MediaPause", "MediaPlayPause", "MediaStop",
        "MediaRewind", "MediaFastForward", "Return", "Exit",
    )
}

fun registerTvKeys() {
    runCatching {
        for (key in TizenKeys.REQUIRED) TizenGlobal.tvinputdevice.registerKey(key)
    }
}
