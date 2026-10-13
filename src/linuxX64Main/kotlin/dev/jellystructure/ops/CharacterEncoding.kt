package dev.jellystructure.ops

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.linux.CODESET
import platform.linux.nl_langinfo_l
import platform.posix.LC_ALL_MASK
import platform.posix.freelocale
import platform.posix.newlocale

/**
 * Phase 318 (FR-318-2) — the backend says its own character encoding out loud, rather than failing
 * quietly for another unknown number of months.
 *
 * The backend container shipped with no `LANG` and no `LC_*`, so every process it spawned ran in the
 * C locale, where MKVToolNix cannot open a path outside ASCII — and 30.1 % of this library's matroska
 * paths carry one. One `mkvmerge -J` exited 2 with a 115-byte error document, another exited 0 with a
 * document cut mid-string, and both reached the backend as "a file with nothing to report". The cost
 * was not the size of the fault; it was that nothing anywhere said a word.
 *
 * Two surfaces, one sentence: a startup `WARN` on the integrity channel and a health check beside
 * `mkvpropedit`'s. Both are built from [verdict], so they cannot drift apart.
 *
 * This reports; it does not repair. No `setlocale` call sets this process's locale — see the
 * 2026-10-13 amendment in the phase spec. The fix belongs in the image (`ENV LANG=C.UTF-8`,
 * FR-318-1), where every future child inherits it.
 */
data class EncodingVerdict(
    /** Green only when the effective charset is UTF-8. */
    val ok: Boolean,
    /** Exactly what `nl_langinfo(CODESET)` reported; empty when the locale could not be built. */
    val codeset: String,
    /** The health check's `detail`, in the same shape as the neighbouring checks'. */
    val detail: String,
    /** The startup `WARN` line; `null` when green, because a green startup says nothing. */
    val warning: String?,
)

object CharacterEncoding {

    /** The health check's name. One string so the test and the route cannot disagree. */
    const val CHECK_NAME = "Character encoding"

    /**
     * The consequence, in the phase's own words. A red check here is not advice about tidiness: it
     * means a third of the library cannot be written to.
     */
    const val CONSEQUENCE =
        "MKVToolNix cannot open a path outside ASCII, and roughly a third of this library's matroska " +
            "files have one — identification truncates silently and every in-place track write refuses " +
            "the file"

    /** What to do about it. The repair is an image/compose change plus a container recreate (FR-318-1). */
    const val REMEDY = "Set LANG=C.UTF-8 on the backend container and recreate it"

    /**
     * The whole of FR-318-2's judgement, pure: a codeset string in, green/red plus both sentences out.
     *
     * `UTF-8` is the only green answer. glibc reports `UTF-8` for both `C.UTF-8` and any `*.UTF-8`
     * locale; the spelling is normalised so a libc that answers `utf8` is not called a fault. An
     * empty string means the locale could not be built at all (`newlocale` returned `NULL` because
     * the environment names a locale this system cannot provide) — red, and it names no encoding
     * rather than inventing one.
     */
    fun verdict(codeset: String): EncodingVerdict {
        val found = codeset.trim()
        val normalised = found.uppercase().replace("-", "").replace("_", "")
        if (normalised == "UTF8") {
            return EncodingVerdict(ok = true, codeset = found, detail = found, warning = null)
        }
        val named = if (found.isEmpty()) {
            "the character encoding could not be determined — the environment names a locale this system cannot provide"
        } else {
            "the effective character encoding is $found, not UTF-8"
        }
        return EncodingVerdict(
            ok = false,
            codeset = found,
            detail = (if (found.isEmpty()) "Unknown" else found) + " — not UTF-8. $CONSEQUENCE. Fix: $REMEDY",
            warning = "character encoding: $named. $CONSEQUENCE. $REMEDY.",
        )
    }

    /**
     * The effective charset of the environment this process was started in, read on a locale object
     * that is built, asked and freed — so nothing in this process's own locale is touched.
     *
     * Returns `""` when `newlocale` fails, which is the environment naming a locale the system has
     * no data for. [verdict] treats that as red.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun effectiveCodeset(): String {
        val loc = newlocale(LC_ALL_MASK, "", null) ?: return ""
        val codeset = nl_langinfo_l(CODESET.toInt(), loc)?.toKString() ?: ""
        freelocale(loc)
        return codeset.trim()
    }

    /** [verdict] of the live environment. */
    fun check(): EncodingVerdict = verdict(effectiveCodeset())
}
