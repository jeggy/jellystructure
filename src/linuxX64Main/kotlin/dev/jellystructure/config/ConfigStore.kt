package dev.jellystructure.config

import com.akuleshov7.ktoml.Toml
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

class ConfigStore(private val filePath: String) {
    private val mutex = Mutex()
    private var _config: AppConfig = AppConfig()

    val current: AppConfig get() = _config

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) {
            persist()
            println("[INFO] Created default config at $filePath")
            return
        }
        runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            _config = Toml.decodeFromString(AppConfig.serializer(), content)
        }.onFailure {
            println("[WARN] Failed to parse config, using defaults: ${it.message}")
        }
    }

    suspend fun update(config: AppConfig) = mutex.withLock {
        _config = config
        persist()
    }

    private fun persist() {
        val tmp = "$filePath.tmp"
        runCatching {
            val content = Toml.encodeToString(AppConfig.serializer(), _config)
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(content)
            sink.flush()
            sink.close()
            // Atomic rename — POSIX guarantees this is atomic on the same filesystem
            platform.posix.rename(tmp, filePath)
        }.onFailure {
            println("[ERROR] Failed to persist config: ${it.message}")
        }
    }
}
