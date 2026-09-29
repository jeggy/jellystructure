package dev.jellystructure.ravilo.castv2

/**
 * R330 (FR-R330-2, dev review 1) — a Cast device as its `_googlecast._tcp` record describes it, before any
 * connection: `id` (the device id), `fn` (the friendly name), `md` (the model), `ca` (capability bits — 1 video
 * out, 2 video in, 4 audio out, 8 audio in, 32 a multizone group) and `rs` (the running app's display name, which
 * the Cast SDK shows as a route's status line).
 */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String?,
    val host: String,
    val port: Int,
    val capabilities: Int,
    /** The app another sender left running, as the record says; null when idle or not said. */
    val runningApp: String?,
) {
    /** R324 (dev review 1) — `group` for a group, `speaker` without a video output, else `display`. */
    val kind: String get() = when {
        capabilities and CAP_GROUP != 0 -> "group"
        capabilities and CAP_VIDEO_OUT == 0 -> "speaker"
        else -> "display"
    }

    companion object {
        const val CAP_VIDEO_OUT = 1
        const val CAP_GROUP = 32

        /** From a TXT record (keys in any case); null without an id or a name — such a record is not a device. */
        fun fromTxt(txt: Map<String, String>, host: String, port: Int): CastDevice? {
            val t = txt.mapKeys { it.key.lowercase() }
            val id = t["id"]?.trim()?.ifBlank { null } ?: return null
            val name = t["fn"]?.trim()?.ifBlank { null } ?: return null
            return CastDevice(
                id = id,
                name = name,
                model = t["md"]?.trim()?.ifBlank { null },
                host = host,
                port = port,
                capabilities = t["ca"]?.trim()?.toIntOrNull() ?: CAP_VIDEO_OUT,
                runningApp = t["rs"]?.trim()?.ifBlank { null },
            )
        }
    }
}
