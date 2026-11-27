package dev.jellystructure.ravilo.ui.samsungtv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** DEB-4843 (FR-2) — what a Samsung TV's own `:8001/api/v2/` says about itself. It answers with Developer Mode off. */
data class SamsungTv(
    val ip: String,
    val model: String,
    val name: String,
    val tizenVersion: String,
    val developerMode: Boolean,
    val developerIp: String?,
) {
    /** The model year, read from the letter after the screen size (`UE55RU7445` → R → 2019); null when the code does not parse. */
    val year: Int? get() = modelYear(model)

    /** The 2024 range (Tizen 8) and later keeps Developer Mode under *App Settings*; an unknown year gets the older, shorter path. */
    val appSettingsMenu: Boolean get() = (year ?: 0) >= 2024

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Null when [body] is not a Samsung TV's answer (no `device.modelName`). */
        fun parse(ip: String, body: String): SamsungTv? {
            val device = (runCatching { json.parseToJsonElement(body).jsonObject["device"] }.getOrNull() as? JsonObject) ?: return null
            fun text(key: String) = (device[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val model = text("modelName") ?: return null
            return SamsungTv(
                ip = ip,
                model = model,
                name = text("name") ?: model,
                tizenVersion = modelYear(model)?.let(::tizenFor) ?: "unknown",
                developerMode = text("developerMode") == "1",
                developerIp = text("developerIP")?.takeIf { it != "0.0.0.0" && it != "" },
            )
        }

        // `UE55RU7445`: size, then the year letter (R), then the series. A QLED `QE65QN90DAT`: size, Q, the series, then the year letter (D).
        private val qledCode = Regex("^[A-Z]{2}\\d{2}Q[A-Z]?\\d+([A-Z])")
        private val plainCode = Regex("^[A-Z]{2}\\d{2}([A-Z])[A-Z]\\d")
        private val years = mapOf('N' to 2018, 'R' to 2019, 'T' to 2020, 'A' to 2021, 'B' to 2022, 'C' to 2023, 'D' to 2024, 'F' to 2025, 'H' to 2026)

        fun modelYear(model: String): Int? = model.uppercase().let { m -> (qledCode.find(m) ?: plainCode.find(m)) }?.groupValues?.get(1)?.single()?.let(years::get)

        /** The Tizen release a model year shipped with. */
        fun tizenFor(year: Int): String = when (year) {
            2018 -> "4.0"; 2019 -> "5.0"; 2020 -> "5.5"; 2021 -> "6.0"; 2022 -> "6.5"; 2023 -> "7.0"; 2024 -> "8.0"; 2025 -> "9.0"
            else -> if (year > 2025) "10.0" else "unknown"
        }
    }
}

/** DEB-4843 (FR-2) — the phone's own subnet, and only it. */
object Subnet {
    /** The 254 neighbours of a dotted-quad [phone] address in its /24, excluding the phone itself; empty if it is not an address. */
    fun hosts(phone: String): List<String> {
        val parts = phone.split('.')
        if (parts.size != 4 || parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != phone }
    }

    /** Spaces a dotted address's digits for reading off a screen while typing it with a remote: `192.168.1.52` → `192 . 168 . 1 . 52`. */
    fun spaced(ip: String): String = ip.split('.').joinToString(" . ")
}

/** DEB-5409 (FR-11) — Ravilo's version as the overview compares it: major and minor only, so `v1.71`, `1.71.0` and `1.71` are one version. */
data class RaviloVersion(val major: Int, val minor: Int) : Comparable<RaviloVersion> {
    override fun compareTo(other: RaviloVersion) = compareValuesBy(this, other, { it.major }, { it.minor })
    override fun toString() = "$major.$minor"

    companion object {
        private val tag = Regex("^v?(\\d+)\\.(\\d+)(?:\\.\\d+)*")
        fun parse(text: String?): RaviloVersion? =
            text?.trim()?.let(tag::find)?.destructured?.let { (major, minor) -> RaviloVersion(major.toInt(), minor.toInt()) }
    }
}

/** What `GET :8001/api/v2/applications/RaviloScrn.RaviloScrn` returned, before it is read: the HTTP status and the body. */
data class TvAppReply(val status: Int, val body: String)

/** DEB-5409 (FR-11) — whether Ravilo is on the TV, as the TV itself says. */
sealed interface InstalledRavilo {
    data class Version(val version: RaviloVersion) : InstalledRavilo
    data object NotInstalled : InstalledRavilo
    /** Anything the TV answers that is neither a version nor a 404, or no answer at all. */
    data object Unknown : InstalledRavilo

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** A JSON object with a `version` is the version; a 404, or an object with `code` 404, is not installed; everything else is not known. */
        fun read(reply: TvAppReply?): InstalledRavilo {
            if (reply == null) return Unknown
            val obj = runCatching { json.parseToJsonElement(reply.body) as? JsonObject }.getOrNull()
            val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (reply.status == 404 || code == 404) return NotInstalled
            val version = (obj?.get("version") as? JsonPrimitive)?.contentOrNull
            return RaviloVersion.parse(version)?.let(::Version) ?: Unknown
        }
    }
}
