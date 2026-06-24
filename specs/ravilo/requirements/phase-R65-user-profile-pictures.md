# R65 — User profile pictures from Jellyfin

**Status:** Planned
**Depends on:** R03 (device auth / sessions), R09 (design system / RemoteImage)

## Problem

Every screen in the Ravilo app identifies the signed-in user with a coloured circle showing their
initials. Jellyfin already hosts user profile pictures and the image infrastructure (Coil 3 +
`RemoteImage`) is already wired in the app. The avatar URL is never fetched or forwarded — neither
the `TvSession` DTO, nor the `LocalSession` store on the device, nor any destination object carries
an image URL.

## Goal

Show the user's Jellyfin profile picture in the AppBar avatar circle and on the profile picker tile.
Fall back gracefully to the current initials circle when the user has no picture, or when the image
fails to load.

## Jellyfin image URL

Jellyfin serves user avatars at:

```
GET {jellyfinBase}/Users/{userId}/Images/Primary?api_key={jellyfinUserToken}
```

No `tag=` parameter is required. Jellyfin returns `404` cleanly when the user has no primary image
set — Coil treats a 404 as a load failure and the fallback (initials) is shown. The URL is fully
deterministic from data already in `DeviceData`; no extra Jellyfin API call is needed to construct
it.

## Functional requirements

### FR-PP1 — AppBar avatar shows profile picture when available

The 40 dp avatar circle in the AppBar (`ProfileAvatar`) loads the user's Jellyfin profile picture
via `RemoteImage` clipped to `CircleShape`. If the image loads successfully it completely fills the
circle (no initials visible). If loading fails or no image is set, the circle falls back to the
existing initials text on a `surfaceVariant` / `accent` background.

### FR-PP2 — Profile picker tile shows profile picture

The 96 dp circle on each `ProfileTile` in `ProfilePickerScreen` follows the same pattern: profile
picture on success, initials on failure. The circle's background colour remains so the initials
fallback is always styled correctly.

### FR-PP3 — Avatar URL is server-computed, not client-guessed

The backend constructs the avatar URL from `DeviceData.jellyfinBase` + `DeviceData.jellyfinUserId`
+ `DeviceData.jellyfinUserToken` and includes it in `TvSession`. The TV client never assembles
Jellyfin URLs itself — all image paths come from the server (consistent with how item artwork works).

### FR-PP4 — URL is stored in LocalSession on device

`MultiTokenStore` persists `avatarUrl` alongside the existing `displayName` and `deviceToken` so
the profile picker can show pictures without a network round-trip at launch.

### FR-PP5 — CompositionLocal propagation (no Dest drilling)

Rather than adding `avatarUrl: String?` to every `Dest.*` data class and every screen signature,
a `CompositionLocal<String?>` (`LocalUserAvatarUrl`) is provided at the top of `RaviloApp` from
`LocalSession.avatarUrl`. The AppBar reads it from the ambient — screens do not need to know about
it.

### FR-PP6 — Initials remain the permanent fallback

When `avatarUrl` is `null` (user has never set a picture, token is a guest token, etc.) or when
Coil's network request fails (offline, 404, rate-limit), the existing initials rendering is
displayed unchanged. No spinner or placeholder image — the circle just shows initials while loading
too.

## Data model changes

### `shared/.../tv/Models.kt` — `TvSession`

```kotlin
@Serializable
data class TvSession(
    val deviceId: String,
    val userId: String,
    val displayName: String,
    val isAdmin: Boolean,
    @SerialName("avatar_url") val avatarUrl: String? = null,  // ← add; null = no picture
)
```

Default `null` keeps backwards compatibility with older server versions.

### `ProfilePickerScreen.kt` — `LocalSession`

```kotlin
data class LocalSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
    val avatarUrl: String? = null,   // ← add; default null for stored sessions pre-R65
)
```

`MultiTokenStore` persists and restores `avatarUrl` (nullable String). Existing stored sessions
without the field deserialize with `avatarUrl = null` — no migration needed.

## Backend changes

### `TvRoutes.kt` — `/tv/sessions` and `/tv/pair/poll`

Both endpoints return `TvSession`. Add `avatarUrl` by constructing it from fields already on
`DeviceData`:

```kotlin
fun DeviceData.avatarUrl(): String? =
    jellyfinBase?.takeIf { it.isNotBlank() }?.let { base ->
        "$base/Users/$jellyfinUserId/Images/Primary?api_key=$jellyfinUserToken"
    }
```

`jellyfinBase` is the Jellyfin server URL already stored in `DeviceData`. Pass
`avatarUrl = device.avatarUrl()` when constructing `TvSession`. No additional Jellyfin API calls.

### No changes to `JellyfinClient`, `JellyfinUser`, or `RaviloDeviceService`

The `PrimaryImageTag` approach (capturing the tag from `/Users` to add `?tag=...`) is **not**
required. Jellyfin's image endpoint works without the tag; the tag is only needed for aggressive
HTTP caching in a CDN context — not relevant here. Keeping the URL simple avoids an extra Jellyfin
round-trip per session.

## Frontend changes

### `AppBar.kt`

```kotlin
@Composable
fun AppBar(
    ...
    userInitials: String = "",
    avatarUrl: String? = null,   // ← add; default null = initials mode
    ...
)
```

`ProfileAvatar` updated:

```kotlin
@Composable
private fun ProfileAvatar(initials: String, avatarUrl: String?, focused: Boolean, ...) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(if (focused) colors.accent else colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // initials always rendered as the background layer (visible while loading / on failure)
        Text(initials, color = ..., fontSize = ...)
        // picture overlaid on top — Coil shows nothing if the request fails
        avatarUrl?.let {
            RemoteImage(url = it, contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
    }
}
```

The `avatarUrl` is read from `LocalUserAvatarUrl.current` inside `AppBar` so callers need not
change their signatures.

### `RaviloApp.kt` — `LocalUserAvatarUrl`

```kotlin
val LocalUserAvatarUrl = staticCompositionLocalOf<String?> { null }
```

Provided once, near the top of the `RaviloApp` composable alongside `LocalTileScale` and
`LocalLiveConfig`:

```kotlin
val avatarUrl = remember(sessions) { sessions.firstOrNull { it.userId == activeUserId }?.avatarUrl }

CompositionLocalProvider(
    LocalUserAvatarUrl provides avatarUrl,
    LocalLiveConfig provides liveConfig,
    ...
) {
    // navigation stack
}
```

### `ProfilePickerScreen.kt` — `ProfileTile`

```kotlin
// inside ProfileTile circle Box:
Box(Modifier.size(96.dp).clip(CircleShape).background(colors.surfaceVariant)) {
    Text(initials, ...)           // initials behind (fallback)
    session.avatarUrl?.let {
        RemoteImage(url = it, contentDescription = session.displayName,
            modifier = Modifier.fillMaxSize().clip(CircleShape))
    }
}
```

`MultiTokenStore.add(session: TvSession)` stores `session.avatarUrl` in the same storage slot;
`getAll()` restores it.

### `HomeScreen.kt`, `BrowseScreen.kt`, `DiscoverScreen.kt` — no signature changes

These screens pass `userInitials` to `AppBar` as before. `AppBar` reads `avatarUrl` from
`LocalUserAvatarUrl.current` internally. No parameter threading required.

## What is NOT in scope

- Updating the avatar in real time if the user changes their picture in Jellyfin (a server restart /
  re-pair / re-launch refreshes the URL; real-time refresh is out of scope)
- Letting the user change their Jellyfin profile picture from within the Ravilo app
- Any caching invalidation beyond what Coil provides by default

## Files affected

| File | Change |
|---|---|
| `shared/.../tv/Models.kt` | Add `avatarUrl: String? = null` to `TvSession` |
| `src/.../server/routes/TvRoutes.kt` | Compute and include `avatarUrl` in both session-returning endpoints |
| `ravilo-ui/.../screens/ProfilePickerScreen.kt` | `LocalSession` gains `avatarUrl`; `ProfileTile` shows image with initials fallback; `MultiTokenStore` persists the field |
| `ravilo-ui/.../components/AppBar.kt` | `ProfileAvatar` layers `RemoteImage` over initials; reads `LocalUserAvatarUrl.current` |
| `ravilo-ui/.../RaviloApp.kt` | Define `LocalUserAvatarUrl`; provide it from the active session |
