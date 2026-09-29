package dev.jellystructure.ravilo.ui.desktop

import java.io.File

/**
 * R328 (D5) — where the Mac app keeps what it remembers: `~/Library/Application Support/Ravilo/`. The Linux
 * development build follows XDG (`$XDG_DATA_HOME/ravilo`, else `~/.local/share/ravilo`). `-Dravilo.data.dir`
 * overrides both, for tests and for running a second copy beside the first.
 */
object DesktopPaths {
    val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

    val dataDir: File by lazy {
        val override = System.getProperty("ravilo.data.dir")?.trim()?.ifBlank { null }
        val home = System.getProperty("user.home")
        val dir = when {
            override != null -> File(override)
            isMac -> File(home, "Library/Application Support/Ravilo")
            else -> File(System.getenv("XDG_DATA_HOME")?.ifBlank { null } ?: "$home/.local/share", "ravilo")
        }
        dir.mkdirs()
        dir
    }

    /** Images and other things safe to lose: `~/Library/Caches/Ravilo`, or `$XDG_CACHE_HOME/ravilo`. */
    val cacheDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = when {
            System.getProperty("ravilo.data.dir") != null -> File(dataDir, "cache")
            isMac -> File(home, "Library/Caches/Ravilo")
            else -> File(System.getenv("XDG_CACHE_HOME")?.ifBlank { null } ?: "$home/.cache", "ravilo")
        }
        dir.mkdirs()
        dir
    }
}
