package dev.jellystructure.media

import kotlinx.serialization.Serializable

/**
 * DEB-2051 (FR-1) — which containers cannot keep a per-stream language tag. AVI has no field for one, so a stream-copy
 * remux exits 0 and writes nothing: the language edit rewrote the whole file to find that out, and then failed with a
 * 500. The routes ask this before they write anything.
 *
 * The container is read from the path's extension: the stored probe carries no container field, and adding one for
 * a file whose name already says what it is would be a schema change with no case behind it.
 */
object ContainerLanguage {
    /** The `reason` a refused language edit carries. The admin keys on it, never on the sentence. */
    const val REASON = "container_cannot_store_language"

    /** The sentence old admin bundles already show from `error`. */
    const val REFUSAL = "This AVI can't store an audio language, so it was not changed."

    /** `"avi"` for a file that cannot keep a language tag, else null (MKV, MP4 and the rest keep today's path). */
    fun cannotStore(path: String): String? =
        if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() == "avi") "avi" else null

    fun refusal(container: String): LanguageRefusal = LanguageRefusal(REFUSAL, REASON, container)
}

/** FR-1's 422 body. `error` is unchanged for old bundles; `reason` and `container` are new. */
@Serializable
data class LanguageRefusal(val error: String, val reason: String, val container: String)
