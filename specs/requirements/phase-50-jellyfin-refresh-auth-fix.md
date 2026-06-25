# Phase 50 — Fix Jellyfin item-refresh auth + single-item fetch (FR-JR1)


## Problem
Refreshing / re-pulling a single item from Jellyfin on the detail page fails. The backend logs
show two distinct failures in the same flow (observed on series `south-park`):

```
[INFO] Jellyfin item refresh 6075f741… (full/recursive): 401
[WARN] pushToJellyfin: Jellyfin refresh failed for 'south-park' (jellyfinId=6075f741…)
[INFO] pushToJellyfin: triggered library scan to pick up tvshow.nfo for 'south-park'
[INFO] Downloaded artwork: …/South Park/poster.jpg
[WARN] Jellyfin getItem failed: Expected response body of the type
       'class dev.jellystructure.auth.JellyfinItem' but was 'class …SourceByteReadChannel'
       In response from `…/Items/6075f741…?Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields`
       Response status `400 Bad Request`  ·  Response header `ContentType: text/plain`
       NoTransformationFoundException
```

So the NFO/artwork are written and a library scan is triggered, but the **targeted Jellyfin
metadata refresh never runs** (401) and the **post-write verification/lock read crashes** (400 →
`NoTransformationFoundException`). The item silently fails to update in Jellyfin until the next full
library scan.

## Current state (as-is) — root causes
All in `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt`.

### (A) 401 — malformed `Authorization` header in `refreshItem`
`refreshItem` (line ~112) builds the header **without** the `, Token="…"` segment that every other
call uses:

```kotlin
// refreshItem — WRONG (produces: …Version="0.1.0" "<token>")
header("Authorization", """$AUTH_HEADER "$token"""")
```

Compare the correct form used by `testConnection`/`getUsers`/`getItems`/`getItem`/
`triggerLibraryRefresh`/playback calls (line ~99, 121, …):

```kotlin
header("Authorization", """$AUTH_HEADER, Token="$token"""")
```

Jellyfin can't parse the malformed `MediaBrowser …Version="0.1.0" "<token>"` and rejects the
refresh with **401**. This is a single-character-class typo (space instead of `, Token=`), but it
breaks **every** `refreshItem` caller — `pushToJellyfin`, the batch `jellyfin-push`, and the
artwork / track / triage routes that refresh the item after a write.

### (B) 400 + `NoTransformationFoundException` — `getItem` endpoint + no response validation
`getItem` (lines ~95–104) fetches a single item from the **non-user-scoped** single-item endpoint
and immediately deserializes the body:

```kotlin
val url = baseUrl.trimEnd('/') +
    "/Items/$jellyfinId?Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields"
http.get(url) { header("Authorization", """$AUTH_HEADER, Token="$token"""") }
    .body<JellyfinItem>()      // ← throws on a non-2xx text/plain body
```

Two compounding problems:
1. **The endpoint is unreliable with a server token.** `GET /Items/{id}` (no `userId`) is
   inconsistent across Jellyfin versions — it returns 405/400 depending on build (it expects
   `GET /Users/{userId}/Items/{id}`; see jellyfin/jellyfin#12260). The **list** endpoint
   `GET /Items?…&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields` used by `getItems`
   works with the exact same token and Fields — only the single-item route 400s.
2. **The HTTP client has no response validator.** `JellyfinClient.http` installs only
   `ContentNegotiation(json)` (lines ~24–28), no `expectSuccess`/`HttpResponseValidator`. So when
   Jellyfin returns `400` with `Content-Type: text/plain`, `.body<JellyfinItem>()` tries to parse
   the plain-text error as JSON and throws `NoTransformationFoundException` instead of degrading.

`getItem` was added in Phase 25 (re-pull from Jellyfin) and is also used by the Phase 22 lock
check and Phase 33 drift check — all inherit this 400.

## Requirements

### A. Fix the `refreshItem` auth header
1. Change `refreshItem`'s header to the canonical form
   `"""$AUTH_HEADER, Token="$token"""`, identical to the other calls. To prevent recurrence,
   factor the auth header into a single private helper (e.g. `private fun authHeader(token: String)`)
   and route **all** authenticated calls through it so no call site can drift again.

### B. Make `getItem` use a reliable request + tolerate error responses
2. **Reuse the proven list-endpoint shape.** Re-implement `getItem` to query the same endpoint
   `getItems` uses, filtered to one id:
   `GET /Items?Ids=$jellyfinId&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields`,
   parse `JellyfinItemsResponse`, and return `items.firstOrNull()`. This keeps the lock fields
   (`LockData`/`LockedFields`, needed by Phase 22), needs no user context, and uses a request the
   server already accepts. (Alternative, if a true single-item fetch is preferred: the user-scoped
   `GET /Users/{adminUserId}/Items/{id}` form that `getItemDetail` already uses successfully — but
   it requires resolving an admin `userId`; the `Ids=` filter is lower-risk and preferred.)
3. **Validate the status before deserializing.** Either install an `HttpResponseValidator` on the
   shared client, or have each item-returning call check `response.status.isSuccess()` before
   `.body()` and, on failure, log the status + the text body and return `null`/empty (the functions
   are already `runCatching`-wrapped; the goal is to **degrade gracefully, never crash**). A
   non-2xx Jellyfin response must surface as a clean warning, not a `NoTransformationFoundException`.

### C. Surface the failure honestly (no silent success)
4. When `refreshItem` returns `false` (or `getItem` returns `null`) inside `pushToJellyfin`, keep
   the existing fallback (write NFO + trigger a library scan) **but** make the per-item action's
   result reflect that the targeted refresh did not run, so the detail page can show an accurate
   state rather than implying the item refreshed. (FE renders BE state — Phase 9 dirty model;
   don't report a refresh that 401'd as success.)

## Invariants
- Frontend renders server-pushed state only — don't paper over a failed refresh with optimistic UI.
- Preserve Jellystructure tags and all existing `pushToJellyfin` follow-ups; this phase only fixes
  the auth header, the single-item fetch, and error handling — no behavioural redesign.
- Keep using the server/admin token (`apiKeys.jellyfinToken`) for these admin operations.

## Out of scope
- Whether `LockData`/`LockedFields` reflect web-UI lock changes on Jellyfin 10.11+ is a known
  upstream issue (jellyfin/jellyfin#15117/#15549) — note it, don't try to work around it here.
- Re-architecting `pushToJellyfin` or the refresh-vs-scan strategy.

## Design reference
`src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt`
(`refreshItem`, `getItem`, `getItems`, `getItemDetail`, the `http` client block);
`MediaRoutes.kt` `pushToJellyfin` + `repull-jellyfin`/`jellyfin-locks`/`drift` routes.
