# R61 — Ravilo TV: channel logo URL resolution (FR-CL1)

**Status:** Planned

## Goal

Channel logos uploaded via the Jellystructure admin (Ravilo config editor) are not displayed in the Ravilo TV app. The logo images are stored and the route exists, but the URL the TV app tries to load has no scheme or host.

## Root cause

`ChannelLogoStore.kt` returns the logo URL as a relative path:
```
/api/tv/channel-logos/1234567890-logo.png
```

This value is passed through `HomeFeedService` into `Channel.logoUrl` and eventually into `ChannelCard(logoUrl = ch.logoUrl)` in `HomeScreen.kt`. The `RemoteImage` composable passes it directly to Coil `AsyncImage`. Coil receives a bare path with no scheme or host — it silently fails to load the image. No error is surfaced.

The channel-logo route (`GET /api/tv/channel-logos/{name}`) is public (in `OPEN_API_PATHS`) — no auth token required. Constructing a full URL is all that's needed.

## Fix

**Client-side** (preferred — no server change required):

`TvApiClient.baseUrl: String` (e.g. `http://192.168.1.100:8080`) is already available at the composition root. Expose it via a `CompositionLocal` in `RaviloApp.kt` (e.g. `LocalServerBaseUrl`), then in `HomeScreen.kt` where `ChannelCard` is called, prepend the base URL for relative paths:

```kotlin
val baseUrl = LocalServerBaseUrl.current
val resolvedLogoUrl = ch.logoUrl?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
ChannelCard(name = ch.name, logoUrl = resolvedLogoUrl, ...)
```

Apply the same resolution to any other relative URL fields in `HomeFeed` that Ravilo renders (e.g. channel backdrop images if any).

**Alternative — server-side**: In `HomeFeedService.buildChannels()`, inject the server's own external base URL (from config or the Ktor `ApplicationCall`) and prepend it to the relative path before serialising. `HomeFeedService` already builds full Jellyfin image URLs this way (via `jellyfinBase`). This is architecturally cleaner but requires threading the server base URL into `HomeFeedService`.

The client-side approach is sufficient and avoids a backend change.

## Files

| File | Change |
|------|--------|
| `ravilo-ui/.../RaviloApp.kt` | Define `val LocalServerBaseUrl = staticCompositionLocalOf<String> { "" }` and provide `apiClient.baseUrl` |
| `ravilo-ui/.../screens/HomeScreen.kt` | Resolve relative `logoUrl` before passing to `ChannelCard` |

## Non-goals

- No change to the upload flow or logo storage.
- No auth change — the route is already public.
- No change to Jellyfin image URLs (already absolute).
