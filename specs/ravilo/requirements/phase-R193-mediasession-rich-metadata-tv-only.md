# Phase R193 — Ravilo TV: rich media-session metadata (title/episode/artwork), scoped to TV only (FR-RV-SESS2)

> Direct follow-up to **R192** (which stopped the TV's `MediaSession` from lingering after
> backgrounding). Once that card only appears while genuinely watching, the user asked for it to
> actually be useful: show the title, episode numbering, and poster/still art of what's playing on
> the TV — so a household member can glance at their phone and see exactly what's on, not just
> "Ravilo" / "Stue TV". They also raised a real scoping question: since `ravilo-android` (TV) and
> `ravilo-phone` share this exact code (`ravilo-ui`'s `androidMain`), would the phone app now start
> advertising **its own** playback to other devices too? Decided: no — the phone's own session
> should never be visible/controllable from another device, TV-only. See the conversation's
> AskUserQuestion: chosen approach is "no `MediaSession` at all on phone" (loses Bluetooth/headset
> transport-button support there) over "local-only session, still momentarily exposable" — the phone
> app has no established reliance on hardware transport keys today, so the trade favors the stronger
> privacy guarantee.

**Status:** Implemented.

## Requirements

### FR-RV-SESS2-1 — Runtime TV detection, not a build flavor
`RaviloAppContext` (`RaviloAppContext.kt`, androidMain — shared by both `:ravilo-android` and
`:ravilo-phone`) gains `val isTelevision: Boolean`, computed from
`(configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION`.
Runtime hardware detection, not a Gradle product-flavor/`BuildConfig` flag — the two apps already
share one `androidMain` source set with no existing way to tell them apart, and this is the standard
Android mechanism for "am I running on TV hardware" independent of which app module is asking.

### FR-RV-SESS2-2 — MediaSession creation itself is TV-only
`RaviloPlayerAndroid.ensureMediaSession()` returns `null` immediately when `!RaviloAppContext.isTelevision`,
never constructing a `MediaSession` at all. This is a stronger guarantee than "created but always
inactive": there is no session object for Android's cross-device layer (or anything else) to ever
observe, discover, or surface from the phone app, at any point in its lifecycle. `setSessionActive`/
`release`'s existing null-safe (`mediaSessionRef?.`) handling already covers this — no other code
needed to change.

### FR-RV-SESS2-3 — Session metadata: title, episode kicker, artwork
`RaviloPlayer.load(...)` (expect/actual) gains `title: String, subtitle: String? = null, artworkUrl:
String? = null`. Android actual builds an `androidx.media3.common.MediaMetadata` (`.setTitle`,
`.setSubtitle`, `.setArtworkUri`) attached to the `MediaItem` passed to `exo.setMediaItem(...)`.
Media3's `MediaMetadata` has **no separate numeric season/episode fields** (confirmed against its
sources) — `subtitle` reuses the caller's existing "S1 · E3"-style kicker text verbatim (already
computed for on-screen chrome by both `PlayerScreen`/`SeriesDetailScreen` and
`LiveTvPlayerScreen`/channel data), satisfying the episode-numbering ask without inventing a new
format. Setting metadata is harmless even when no session is ever created (phone) — it's just
`MediaItem` state ExoPlayer already carries regardless.

Call sites:
- `PlayerScreen.kt` (movies/episodes): `title = itemTitle`, `subtitle = itemKicker`, `artworkUrl =
  episodes?.getOrNull(currentEpIndex)?.stillUrls?.firstOrNull() ?: posterUrl` — an episode's own
  still takes priority; `posterUrl` (new `Dest.Player`/`PlayerScreen` field, `MediaCard.posterUrl`)
  is the movie-path fallback, since movies have no `episodes` list to pull a still from.
- `LiveTvPlayerScreen.kt`: `title = channel.name`, `subtitle = channel.currentProgram?.name`,
  `artworkUrl = channel.logoUrl`.

### FR-RV-SESS2-4 — Web actual accepts but ignores the new metadata params
`RaviloPlayerWasm.load(...)` gains the same three parameters (to satisfy the shared `expect`
signature) but does not act on them. Browser tabs already wire the Web Media Session API
(`navigator.mediaSession`, R44) for local play/pause/seek action handlers — that API isn't mirrored
to other devices the way Android's cross-device layer surfaces a native `MediaSession`, so there's no
privacy concern to gate on web the way there is on Android. Wiring `navigator.mediaSession.metadata`
for a nicer browser lock-screen/OS overlay is a reasonable future enhancement, not this phase's scope
(Android-native controls only, per the bug report that started this).

## Invariants
- **The phone app never creates an OS-level `MediaSession`, full stop** — not "creates but keeps
  inactive." No code path on `:ravilo-phone` can accidentally leave one behind for R192-style
  linger, because there is never one to begin with.
- **Metadata is always cosmetic.** A missing/failed artwork URL, blank kicker, etc. never blocks or
  degrades playback — `MediaMetadata` fields are all nullable/optional and ExoPlayer works
  identically with or without them.
- **The TV's session stays exactly as visible/controllable to other devices as R192 already made
  it** — this phase only enriches what it displays; the create/release lifecycle from R192 is
  unchanged.

## Out of scope
- Web (`ravilo-web`) `navigator.mediaSession.metadata` wiring — accepted-but-unused params only (FR-RV-SESS2-4).
- Restoring Bluetooth/headset hardware-transport-key support on the phone app — deliberately traded
  away for the stronger no-session-at-all privacy guarantee (see the AskUserQuestion decision above).
  If this is missed later, a local-only (never-cross-device-advertised) session would need its own
  design — no such distinction is available in Media3's public API today (confirmed while
  implementing R192: no `isActive` setter, only whole-session existence).
- `DiscoverDetailScreen`'s `onWatchMovie: (itemId, title) -> Unit` callback (`RaviloApp.kt:880`) still
  doesn't carry a poster — that entry point (a TMDB-only title, not yet in the local library, being
  watched directly) is left without session artwork; a minor, narrow gap not worth widening that
  callback's signature for.
- R155's remote-play command path (`RaviloApp.kt:463`, Home Assistant/dashboard-cast trigger) also
  has no poster available at that call site — same reasoning, left without artwork.

## Dev-review addendum (2026-08-10 — implementation notes)
1. Verified via `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
   `:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`,
   `:ravilo-phone:compileDebugKotlin` — all pass. Not on-device verified this session (deploy is
   user-initiated, per standing preference) — the way to verify: play something on the TV, check the
   phone's Cast card now shows the real title/episode/artwork instead of "Ravilo"/"Stue TV"; play
   something on the phone app itself and confirm no card appears on any other device at all.

## Source references
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/RaviloAppContext.kt` — new
  `isTelevision`.
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerAndroid.kt` —
  `ensureMediaSession()` TV-gate, `load()`'s new `MediaMetadata` construction.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayer.kt` — `load()`
  expect signature.
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt` — no-op
  actual for the new params.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` — new
  `posterUrl` param, `artworkUrl` resolution, updated `player.load(...)` call.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/LiveTvPlayerScreen.kt` —
  updated `player.load(...)` call (channel name/program/logo).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/RaviloApp.kt` — `Dest.Player.posterUrl`
  (new field) + the two call sites that populate it (`MovieDetailScreen.onPlay`, Home `onItemPlay`).
- Related: **phase-R192-mediasession-background-cleanup.md** (the session lifecycle this phase adds
  metadata and TV-only scoping on top of).
