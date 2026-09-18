# Jellyfin 12.1 upgrade — compatibility audit

**Date:** 2026-09-18 · **Server audited:** the household's own, `12.1.0`, live · **Backend:** v1.27
**Status:** research, not spec. Produced phases **238–243** and **R271**.

The household's Jellyfin was upgraded from 10.11.11 to 12.1.0. This is what still works, what
broke, and what only appears to work.

## Method

Read-only probes against the live server using the same auth header the product builds, plus:

- an existence probe for mutating routes, sending a deliberately **invalid** token and reading 401
  (route exists, authenticates) against 404/405 (route gone or method changed), which proves a route
  without executing it;
- a field-by-field diff of every `@SerialName` in `auth/Models.kt` against a live payload per model.

The second technique replaces the OpenAPI probe this project normally uses, which is unavailable on
12.1 (see finding 6).

**One probe caused harm and the method is corrected in FR-240-6.** `POST /System/Restart` with an
invalid token was expected to answer 401 like every other privileged route. It answered **204 and
restarted the server**, causing roughly one minute of downtime and four failed progress writes for a
viewer who was mid-playback. Lifecycle routes are now on a written deny-list.

## Findings

### 1. `/socket` no longer accepts `api_key` — one live regression

| Handshake | 12.1 |
|---|---|
| `/socket?api_key=<token>` | 403 |
| `/socket?apikey=<token>` | 101 |
| `/socket` + `Authorization: MediaBrowser …` | 101 |
| `/socket`, no credential | 403 |

`JellyfinSessionBridge.kt:92` sends the first form, so phase 110's bridge cannot open. Jellyfin
logged `Token is required. URL GET /socket` 17 times in 15 minutes for one device. Because
`postCapabilities` runs inside the connected block, the TV never registers remote-control
capability: no dashboard pause or seek, no Home Assistant player, no `TvEventBus` command delivery.

It failed silently. `bridgeDropLogged` logs once per device and never again, and the retry loop runs
forever at the 60 s backoff cap. One warning at 20:16:51 was the entire record of a failure still
live at 20:32. → **238**.

### 2. `X-Emby-Token` no longer authenticates

| Credential form | `/Items` |
|---|---|
| `Authorization: MediaBrowser … Token="…"` | 200 |
| `X-Emby-Token` | 401 |
| `X-MediaBrowser-Token` | 401 |
| `X-Emby-Authorization` | 401 |
| `Authorization: Emby …` | 401 |

`JellyfinLiveRouteGuardTest.kt:50` uses `X-Emby-Token`, so the one test written to catch route-shape
breakage is itself broken on 12.x. `tests/mock-jellyfin/server.js:80` accepts
`x-emby-authorization`, a form the real server rejects. → **240**, **241**.

### 3. Query-string tokens work only because the routes are anonymous

`/Items?api_key=` returns 401 while `?apikey=` returns 200. Eight backend sites and two client sites
use `api_key`. None is broken, because every route they target answers with **no credential at
all**: image routes 200, `/Videos/{id}/stream` 206 with real `video/mp4`, subtitle `.vtt` 200. The
token is ignored, not honoured. → **239**, **R271**.

### 4. Media is served to anonymous callers over the public URL

Confirmed against `https://jellyfin.jebster.net` with no credential, fresh request, no cookies:
`/Videos/{id}/stream` returns 206 and `video/mp4`; a nonexistent id returns 400, so the route is
genuinely evaluating rather than short-circuiting. `network.xml` has no LAN allowlist, no
`KnownProxies` and no `RemoteIPFilter`.

Anyone who can reach the host and holds an item id can pull the file. Item ids are not secret; they
ride in Ravilo payloads and image URLs. This is Jellyfin's behaviour, not jellystructure's, and this
report does not propose a code change. **Open:** is there a Jellyfin setting that requires auth on
media routes, and was this also true on 10.11.11? Both unanswered — every Jellyfin instance on this
host is now 12.1, so there was nothing left to A/B against.

### 5. `POST /System/Restart` executed with an invalid token

204 and a real restart, while `/System/Configuration`, `/System/Logs`, `/System/ActivityLog/Entries`,
`/ScheduledTasks`, `/Users`, `/Library/VirtualFolders`, `/System/Endpoint` and `/Devices` all
correctly returned 401 for the same token. Jellyfin's log shows the request being rejected as
`Invalid token` and the shutdown beginning in the same second.

Not re-tested, deliberately. **Confirm on a disposable instance and report upstream if it holds.**
Treat as one observation, not a confirmed vulnerability.

### 6. The OpenAPI document is gone

`GET /api-docs/openapi.json` returns **500 Error processing request**. `/openapi/v1/openapi.json`,
`/api-docs/swagger.json` and `/swagger/v1/swagger.json` all 404.

This matters more than it looks. Phase 163's `POST /MediaSegments` → 405 and FR-187-1's
actively-wrong image body are the two incidents that made "probe the live OpenAPI before writing
code" a working rule here. That rule has no document on 12.x. → **240** FR-240-4.

## What is unchanged

- **All 36 serializable models parse.** Every `@SerialName` field is present in the live payloads,
  including `JellyfinPlaybackInfoResponse`, `JellyfinMediaSourceInfo` and `JellyfinMediaStream`. No
  renames anywhere.
- **Every REST route shape answers 2xx**: item enumeration in all five filter shapes, playstate and
  continue-watching, episodes, NextUp, users, libraries, plugins, scheduled tasks, encoding config,
  Live TV, media segments, and both `PlaybackInfo` verbs.
- **Login semantics are identical.** 401 with a `Version` in the header, 400 without. Phase 224's fix
  is still required and still correct.
- **Direct play still negotiates**, `SupportsDirectPlay` true.
- **`DELETE /Videos/ActiveEncodings` still exists.** A GET returns 405, which is correct; the product
  uses DELETE.
- **Media segments return empty**, which is expected: no segment-provider plugin is installed, and the
  plugin list confirms it. Phase 163's finding stands.
- **Plugin status values are unchanged** — one plugin reported `Restart`, which phase 212's
  `restartPendingFinding` matches on.
- **`linuxX64Test` is green** on a forced (non-cached) run.

## Library metadata ownership

The premise that jellystructure owns metadata and Jellyfin reads disk holds, with one exception. All
six `LibraryOptions` field names the product reads survive on 12.x, so no check has gone blind.

| Library | Managed | `MetadataSavers` | `EnableInternetProviders` | `MetadataFetchers` |
|---|---|---|---|---|
| Film | yes | empty | false | empty |
| Serier | yes | empty | false | empty |
| Musik | yes | **`["Nfo"]`** | false | empty |
| Blandet | no | `["Nfo"]` | false | empty |

**Musik is a managed library with Jellyfin's NFO saver on**, so Jellyfin rewrites NFO files there
after every refresh. Its options file is dated March, so this predates the upgrade. Nothing reads
`EnableInternetProviders` or the per-type fetchers today. → **242**.

The upgrade rewrote Film's and Serier's option files at 22:15 and preserved every value. It added one
new key, `SimilarItemProviders`, which defaulted to the local `Local Genre/Tag` provider on Film and
empty on Serier. It defaulted to something local; nothing would have reported it if it had not.

## Version-anchored comments

Nineteen sites cite 10.11.x as their verification baseline. Listed here so FR-243-5 is finite.

| File | Sites |
|---|---|
| `auth/JellyfinClient.kt` | 6 |
| `auth/Models.kt` | 2 |
| `server/routes/SegmentRoutes.kt` | 2 |
| `server/routes/WebhookRoutes.kt` | 2 (one cites 10.11.8.0) |
| `wasmJsMain/ui/Segments.kt` | 2 |
| `auth/DeviceIdentityRegistry.kt` | 1 |
| `linuxX64Test/auth/DeviceProfileTest.kt` | 1 |
| `linuxX64Test/auth/JellyfinIdentityHeaderTest.kt` | 1 |
| `tests/mock-jellyfin/server.js` | 1 |

Re-measured and still true: the `Version`-header rule in `JellyfinClient.kt:1240` and the mock's
matching 400. The rest were not re-checked.

## Phases produced

| Phase | Covers |
|---|---|
| **238** | The socket handshake, and making a dead bridge visible |
| **239** | The remaining backend query-string tokens |
| **240** | The live route guard, and a model-field check replacing the OpenAPI probe |
| **241** | The mock refusing what the real server refuses |
| **242** | Metadata ownership as an advisor finding |
| **243** | The 12.x floor, a reported version, and no compatibility branches |
| **R271** | Ravilo clients no longer building Jellyfin URLs |

Findings 4 and 5 produced no phase. Both are Jellyfin's behaviour and need a decision, not code.
