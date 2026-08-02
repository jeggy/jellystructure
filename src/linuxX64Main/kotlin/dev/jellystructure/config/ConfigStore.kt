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
    }

    suspend fun update(config: AppConfig) = mutex.withLock {
        _config = config
        persist()
    }

    private suspend fun persist() {
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
    }
}
