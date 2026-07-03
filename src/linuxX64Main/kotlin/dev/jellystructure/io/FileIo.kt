package dev.jellystructure.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Phase 134 (FR-OPS2 §A) — the one safe way to read/write a whole file.
 *
 * kotlinx-io's `SystemFileSystem.source()` is a raw `fopen()` with **no finalizer**: an unclosed
 * source permanently leaks one file descriptor per call — GC never recovers it, and on Ktor Native
 * the process dies uncatchably when any FD reaches 1024 (FD_SETSIZE / KTOR-8703). The
 * `source(p).buffered().readString()` idiom, repeated across the tree, is exactly that leak; it took
 * a live server down (2026-07-03: 922 open FDs, ~800 of them leaked reads).
 *
 * Every helper here is `.use{}`-scoped, so the FD closes on success and on throw alike. All throw on
 * failure — call sites keep their existing `runCatching` wrappers for null-on-failure semantics.
 *
 * Hygiene invariant (enforced by `scripts/check-fd-hygiene.sh`): outside this file,
 * `SystemFileSystem.source(`/`.sink(` may only appear immediately `.use{}`-scoped (streaming call
 * sites); whole-file reads/writes go through these helpers.
 */
object FileIo {
    fun readBytes(path: Path): ByteArray =
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    fun readText(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    fun writeBytes(path: Path, bytes: ByteArray) {
        SystemFileSystem.sink(path).buffered().use { it.write(bytes, 0, bytes.size) }
    }

    fun writeText(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }
}
