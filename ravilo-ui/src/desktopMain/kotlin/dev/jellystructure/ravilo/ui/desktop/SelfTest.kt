package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.shared.raviloVersion
import java.io.File

/**
 * R331 (FR-R331-4) — `Ravilo --self-test`: load the Swift library, read the app's own version and its data
 * directory, print one line and return the exit code, without opening a window. It catches what actually breaks in
 * a bundled JVM app — a nested library that is missing, unsigned or built for the wrong ABI. On Linux there is no
 * library to load, and that is not a failure.
 */
object SelfTest {
    fun run(): Int {
        val version = raviloVersion()
        val dataOk = runCatching {
            val probe = File(DesktopPaths.dataDir, ".self-test")
            probe.writeText("ok")
            probe.readText() == "ok" && probe.delete()
        }.getOrDefault(false)
        val lib = MacNative.lib
        val native = when {
            lib != null -> "library ABI ${lib.ravilo_abi()}"
            DesktopPaths.isMac -> "library MISSING (${MacNative.loadError})"
            else -> "no library (not a Mac)"
        }
        val ok = dataOk && (lib != null || !DesktopPaths.isMac)
        // R342 — the music Dock icon's pictures, said and not required: without them the Dock keeps the films icon.
        val dock = if (DesktopPaths.isMac) " · ${DesktopDock.packagedPictures()} Dock pictures" else ""
        println("Ravilo $version · $native$dock · data ${DesktopPaths.dataDir} ${if (dataOk) "writable" else "NOT WRITABLE"} · ${if (ok) "OK" else "FAILED"}")
        return if (ok) 0 else 1
    }
}
