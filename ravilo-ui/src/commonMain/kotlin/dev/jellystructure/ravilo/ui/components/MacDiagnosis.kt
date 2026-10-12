package dev.jellystructure.ravilo.ui.components

/**
 * R385 (FR-R385-1) — where macOS is running this app from, as the launcher path says. A quarantined copy that Finder
 * did not move is run by macOS from a throwaway location, and the Local Network consent the household granted the app
 * in `/Applications` does not cover it: to the system it is a different app, so **no socket of its own reaches a
 * private address**. That is a fact the app can read, which is why it is allowed to explain a failure (D2).
 */
enum class MacInstallState {
    /** The normal install, and every other platform: nothing is ever said. */
    IN_PLACE,

    /** macOS is running a quarantined copy from a location it made up; the path says `/AppTranslocation/`. */
    TRANSLOCATED,

    /** Opened straight out of the mounted disk image; the path starts with `/Volumes/`. */
    ON_VOLUME,
}

/** R385 (FR-R385-2) — whether the configured server's address is one that needs the household's network at all. */
enum class ServerHostClass {
    /** A private or link-local address: reaching it needs Local Network consent on macOS 15 and later. */
    LAN,

    /** A routable address: no consent is in the way, so a failure here is a plain failure. */
    PUBLIC,

    /** The host did not resolve. Treated as [PUBLIC] — an unresolvable name is a DNS answer, not a permission state. */
    UNKNOWN,
}

/**
 * R385 — the two facts the Mac knows about itself, and the two system actions its causes offer, exposed the way R335
 * exposes `PlaybackAvailability`: a platform sets them, `ravilo-ui` reads them, and every other platform leaves
 * [installState] null, so nothing anywhere else is ever refined.
 *
 * Read on a **failure only** (D1). A translocated app pointed at a server that resolves publicly works perfectly
 * well — a viewer away from the house is exactly that case — and must not be nagged at launch or anywhere else.
 */
object MacDiagnosis {
    /** FR-R385-1 — set once at startup by the Mac; null on every other platform. */
    var installState: MacInstallState? = null

    /** FR-R385-2 — the class of the configured server host, as the Mac last resolved it. */
    var serverHostClass: ServerHostClass = ServerHostClass.UNKNOWN

    /** FR-R385-4 — R330's own button and pane (Privacy & Security ▸ Local Network); null where there is no such place. */
    var openLocalNetworkSettings: (() -> Unit)? = null

    /** FR-R385-4 — the **Quit Ravilo** the install-state message offers, which is the menu bar's own quit (FR-R328-9). */
    var quit: (() -> Unit)? = null

    /**
     * FR-R385-3 — the cause a transport failure with no answer at all really has, or null when the app cannot prove
     * one and the plain *Couldn't reach the server* is the honest thing to say.
     */
    fun refinedUnreachable(): LoadErrorKind? = macRefinedUnreachable(installState, serverHostClass)

    /** FR-R385-7 — what the test driver prints, so a walk records the state instead of reading paths by hand. */
    fun describe(): String = "install=${installState?.name ?: "n/a"} host=${serverHostClass.name}"
}

/**
 * R385 (FR-R385-3) — the table, pure and whole:
 *
 * | Install state | Server host | A transport failure becomes |
 * |---|---|---|
 * | `TRANSLOCATED` / `ON_VOLUME` | `LAN` | `MAC_INSTALL_PLACE` |
 * | `TRANSLOCATED` / `ON_VOLUME` | `PUBLIC` | unchanged — this install works (D1) |
 * | `IN_PLACE` | `LAN` | `MAC_LOCAL_NETWORK` |
 * | `IN_PLACE` | `PUBLIC` | unchanged |
 *
 * [install] is null off the Mac, and then nothing is refined. `UNKNOWN` host counts as `PUBLIC`: a name that does not
 * resolve is a DNS answer, and guessing a permission from it would put a false explanation on an ordinary outage.
 *
 * The `IN_PLACE` + `LAN` row is an **inference**, not a reading — see open question 1, answered no — so it is ordered
 * last: the state the app can prove is said first, and the permission only as the best remaining explanation.
 */
fun macRefinedUnreachable(install: MacInstallState?, host: ServerHostClass): LoadErrorKind? = when {
    install == null -> null
    host != ServerHostClass.LAN -> null
    install == MacInstallState.IN_PLACE -> LoadErrorKind.MAC_LOCAL_NETWORK
    else -> LoadErrorKind.MAC_INSTALL_PLACE
}
