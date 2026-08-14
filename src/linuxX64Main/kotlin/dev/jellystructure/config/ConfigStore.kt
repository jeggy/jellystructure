package dev.jellystructure.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class ConfigStore(private val filePath: String) {
    private val mutex = Mutex()
    private var _config: AppConfig = AppConfig()

    // Phase 132: tolerant of unknown TOML keys — otherwise removing any config field (e.g. RapidAPI's
    // streaming_availability_key) throws UnknownNameException on an existing config that still has it,
    // and the runCatching below silently reverts the operator's entire live config to defaults.
    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

    val current: AppConfig get() = _config

    suspend fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) {
            persist()
            Logger.info("Created default config at $filePath")
            return
        }
        val result = runCatching {
            val content = FileIo.readText(path)
            _config = toml.decodeFromString(AppConfig.serializer(), content)
        }
        if (result.isFailure) Logger.warn("Failed to parse config, using defaults: ${result.exceptionOrNull()?.message}")
        else fixAgeRatingMapKeys()
    }

    /** Bug fix (live report, 2026-08-14) — ktoml 0.7.1 has a genuine decode bug for a QUOTED TOML table
     *  key (needed whenever a certification string contains a space, e.g. Swedish `"Från 7 år"`): it
     *  retains the surrounding `"` characters as part of the resulting Kotlin string instead of
     *  stripping them (confirmed via a direct decode test — `map["Från 7 år"]` came back null while the
     *  map actually held the 11-character key `"Från 7 år"`, quotes included). Two visible symptoms from
     *  one root cause: (1) Metadata ▸ Age ratings looked completely unmapped, because every lookup by
     *  the real (unquoted) certification string failed; (2) every `configStore.update()` afterward threw
     *  on re-encode ("Not able to parse the key"), since ktoml's encoder can't figure out how to
     *  (re-)quote a string that already contains embedded quote characters — silently, since
     *  [persist]'s failure is logged, never surfaced to the API caller (a `PUT /api/config` still 204s).
     *
     *  This is deterministic and reproduces on EVERY decode of a quoted key, not a one-time corruption —
     *  verified: re-encoding a cleaned map and decoding it back reproduces the exact same corruption. So
     *  this must run after every [load], not just once. `ageRatingMap` is the only `Map<String, _>`
     *  field in [AppConfig] (verified), so no other field needs the same treatment. */
    private suspend fun fixAgeRatingMapKeys() {
        val map = _config.metadata.ageRatingMap
        fun isMangled(k: String) = k.length >= 2 && k.first() == '"' && k.last() == '"'
        val mangledCount = map.keys.count(::isMangled)
        if (mangledCount == 0) return
        val fixed = map.mapKeys { (k, _) -> if (isMangled(k)) k.substring(1, k.length - 1) else k }
        Logger.warn("Config: repaired $mangledCount ktoml-mangled age_rating_map key(s)", "config")
        _config = _config.copy(metadata = _config.metadata.copy(ageRatingMap = fixed))
    }

    /** Bug fix (live report, 2026-08-14) — now returns whether the write to disk actually succeeded.
     *  Previously `Unit`: every caller (including `PUT /api/config`) treated the in-memory assignment as
     *  the whole story and responded success regardless of whether [persist] failed — [persist]'s own
     *  failure was logged but never propagated. The ktoml age_rating_map bug above is exactly what
     *  surfaced this: a real "Saved ✓" in the Settings UI whose write had silently never happened.
     *  Existing callers that don't check the return value are unaffected (Kotlin doesn't require using
     *  a return value) — this is purely additive; see `ConfigRoutes.kt`/`WebhookRoutes.kt`/
     *  `MetadataRoutes.kt` for the call sites now surfacing it. */
    suspend fun update(config: AppConfig): Boolean = mutex.withLock {
        _config = config
        persist()
    }

    private suspend fun persist(): Boolean {
        val tmp = "$filePath.tmp"
        val result = runCatching {
            val content = toml.encodeToString(AppConfig.serializer(), _config)
            FileIo.writeText(Path(tmp), content)   // Phase 134: use{}-scoped
            // Security fix (2026-08-02 review, finding M7) — config.toml holds every secret this
            // instance knows (Jellyfin admin token, TMDB/*arr/Seerr keys, qBittorrent password, the
            // webhook secret) and was created at the platform-default mode (0644/0664 depending on
            // umask — confirmed world-readable on the live host during the audit). kotlinx-io's
            // SystemFileSystem.sink() has no way to pass an explicit mode, so set it explicitly after
            // writing, before the rename makes this the live file — owner read/write only.
            platform.posix.chmod(tmp, "384".toUInt())  // 0600 octal = 384 decimal (Kotlin has no octal literal)
            // Atomic rename — POSIX guarantees this is atomic on the same filesystem
            platform.posix.rename(tmp, filePath)
        }
        if (result.isFailure) Logger.error("Failed to persist config: ${result.exceptionOrNull()?.message}")
        return result.isSuccess
    }
}
