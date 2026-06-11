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
        runCatching {
            val path = Path(filePath)
            val sink = SystemFileSystem.sink(path).buffered()
            sink.writeString(Toml.encodeToString(AppConfig.serializer(), _config))
            sink.flush()
            sink.close()
        }.onFailure {
            println("[ERROR] Failed to persist config: ${it.message}")
        }
    }
}
