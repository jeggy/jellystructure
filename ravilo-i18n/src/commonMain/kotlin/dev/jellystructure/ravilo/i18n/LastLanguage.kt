package dev.jellystructure.ravilo.i18n

/**
 * R279 — the middle rung of [resolveLanguage]: what this *device* drew in last, remembered across
 * sign-out and across a power cycle, so a surface with no signed-in user is not automatically
 * English.
 *
 * Deliberately a one-value store and not part of any session: signing out, switching profile or
 * unpairing must not lose it, because the screen you see straight afterwards is exactly the one
 * this exists for. Nothing reads it while a user is known — the user's own configured language
 * always wins.
 */
public interface LastLanguageStore {
    /** Whatever was last [remember]ed, or null. Never validated here — [resolveLanguage] does that. */
    public fun read(): String?

    /** Overwrites; only ever called with a language [normalizeLanguage] accepted. */
    public fun write(code: String)
}

/** The default until a client installs its own: correct, and forgets on restart. */
public class InMemoryLastLanguageStore(private var value: String? = null) : LastLanguageStore {
    override fun read(): String? = value
    override fun write(code: String) { value = code }
}

/**
 * The device-wide store. A client sets this once at startup — Android to SharedPreferences, the web
 * app and both receivers to `localStorage` — and never touches it again; everything else goes
 * through [rememberLanguage] and [lastLanguage].
 */
public object LastLanguage {
    public var store: LastLanguageStore = InMemoryLastLanguageStore()

    /** The remembered language, or null if this device has never drawn one we still have. */
    public fun read(): String? = normalizeLanguage(store.read())

    /**
     * Remembers [code] as what this device draws in, if it names a language we have. A code we do
     * not have is **not** remembered: a bad value must not outlive the session that produced it.
     * Returns what is remembered afterwards.
     */
    public fun remember(code: String?): String? {
        val normalized = normalizeLanguage(code) ?: return read()
        if (normalized != store.read()) store.write(normalized)
        return normalized
    }
}

/**
 * [resolveLanguage] against the device's remembered language, and — since resolving is also the
 * moment we learn something worth remembering — writes the answer back.
 *
 * This is the call every client makes. [configured] is the signed-in user's `uiLanguage`, or null
 * when nobody is signed in; in that case nothing new is learned and the remembered value simply
 * survives.
 */
public fun resolveAndRememberLanguage(configured: String?): String {
    val resolved = resolveLanguage(configured = configured, lastSession = LastLanguage.read())
    if (configured != null) LastLanguage.remember(resolved)
    return resolved
}
