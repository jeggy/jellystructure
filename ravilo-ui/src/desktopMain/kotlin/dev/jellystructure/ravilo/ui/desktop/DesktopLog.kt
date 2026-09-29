package dev.jellystructure.ravilo.ui.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's log: everything it prints, and every uncaught exception, also goes to `~/Library/Logs/Ravilo/ravilo.log`
 * (Console.app shows it; on Linux, `ravilo.log` in the data directory). An app opened from Finder has no terminal, so
 * without this a Mac that misbehaves says nothing. Kept under 5 MB: the previous file becomes `ravilo.log.1`.
 */
object DesktopLog {
    val file: File by lazy {
        if (DesktopPaths.isMac) File(System.getProperty("user.home"), "Library/Logs/Ravilo/ravilo.log")
        else File(DesktopPaths.dataDir, "ravilo.log")
    }

    fun install() {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > 5L * 1024 * 1024) file.renameTo(File(file.parentFile, "ravilo.log.1"))
            val out = FileOutputStream(file, true)
            System.setOut(PrintStream(Tee(System.out, out), true))
            System.setErr(PrintStream(Tee(System.err, out), true))
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                System.err.println("${stamp()} uncaught on ${t.name}: $e")
                e.printStackTrace()
            }
        }
    }

    fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))

    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(byte: Int) = synchronized(this) { a.write(byte); runCatching { b.write(byte) }; Unit }
        override fun write(bytes: ByteArray, off: Int, len: Int) = synchronized(this) { a.write(bytes, off, len); runCatching { b.write(bytes, off, len) }; Unit }
        override fun flush() = synchronized(this) { a.flush(); runCatching { b.flush() }; Unit }
    }
}
