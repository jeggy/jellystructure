package dev.jellystructure.ravilo.ui.downloads

import android.content.Context
import dev.jellystructure.ravilo.ui.RaviloAppContext
import java.io.File

/** FR-17 — the download settings are this phone's own: a SharedPreferences file of their own, never synced, never in the server config. */
class AndroidDownloadPrefs(private val ctx: Context, private val onWifiOnlyChanged: () -> Unit = {}) : DownloadPrefs {
    private val p get() = ctx.getSharedPreferences("ravilo_downloads", Context.MODE_PRIVATE)

    override var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", true)
        set(value) { p.edit().putBoolean("wifi_only", value).apply(); onWifiOnlyChanged() }

    override fun lastQuality(kind: String): String? = p.getString("last_quality_$kind", null)
    override fun setLastQuality(kind: String, quality: String) { p.edit().putString("last_quality_$kind", quality).apply() }
}

/** FR-7, FR-12 — one index file per viewer under `filesDir/downloads/index` (never `cacheDir`). Signing out touches none of them. */
class AndroidDownloadIndexStorage : DownloadIndexStorage {
    private fun dir() = File(File(RaviloAppContext.get().filesDir, "downloads"), "index").also { it.mkdirs() }
    private fun file(userId: String) = File(dir(), userId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override fun read(userId: String): String? = runCatching { file(userId).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(userId: String, text: String) {
        val f = file(userId)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    override fun delete(userId: String) { file(userId).delete() }

    override fun users(): List<String> = dir().listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }.orEmpty()
}
