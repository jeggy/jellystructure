package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JsTag(
    val name: String,
    val color: String = "#6b7280",
    val description: String = "",
)

class JsTagStore(private val filePath: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private var tags: MutableList<JsTag> = mutableListOf()

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val text = FileIo.readText(path)
            tags = json.decodeFromString<List<JsTag>>(text).toMutableList()
        }
    }

    fun all(): List<JsTag> = tags.toList()

    fun get(name: String): JsTag? = tags.firstOrNull { it.name == name }

    fun create(tag: JsTag): Boolean {
        if (tags.any { it.name == tag.name }) return false
        tags.add(tag)
        persist()
        return true
    }

    fun update(name: String, color: String?, description: String?): Boolean {
        val idx = tags.indexOfFirst { it.name == name }
        if (idx < 0) return false
        tags[idx] = tags[idx].copy(
            color = color ?: tags[idx].color,
            description = description ?: tags[idx].description,
        )
        persist()
        return true
    }

    fun delete(name: String): Boolean {
        val removed = tags.removeAll { it.name == name }
        if (removed) persist()
        return removed
    }

    fun nameSet(): Set<String> = tags.map { it.name }.toSet()

    private fun persist() {
        val tmp = Path("$filePath.tmp")
        val target = Path(filePath)
        FileIo.writeText(tmp, json.encodeToString(tags.toList()))   // Phase 134: use{}-scoped
        SystemFileSystem.atomicMove(tmp, target)
    }
}
