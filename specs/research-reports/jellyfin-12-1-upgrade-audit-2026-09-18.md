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

**Every finding below was subsequently double-proved on clean, isolated containers** of both
`jellyfin/jellyfin:12.1` and `jellyfin/jellyfin:10.11.11`, each installed from scratch with the
startup wizard completed via the API and a one-file movie library. The production server was not
touched again. Both probe containers were removed afterwards.

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

### 4. Media is served to anonymous callers — confirmed, and it is not a 12.x regression

**Double-proved 2026-09-18 on clean, isolated containers of both versions**, wizard completed, one
movie library, `KnownProxies` set so `X-Forwarded-For` is honoured and the caller resolves to
`8.8.8.8` — definitively not local access. No credential sent.

| Request | 12.1.0 | 10.11.11 |
|---|---|---|
| `GET /Videos/{id}/stream` | 206 `video/mp4` | 206 `video/mp4` |
| `GET /Videos/{id}/stream.mp4` | 206 `video/mp4` | 206 `video/mp4` |
| `GET /Items/{id}/Images/Primary` | 200 `image/jpeg` | 200 `image/jpeg` |
| `GET /Items?…` (control) | 401 | 401 |
| `POST /System/Restart` (control) | 401 | — |

The two controls are the proof that authentication works on these servers and only the media routes
ignore it. No header, an invalid token and a valid admin token all return 206.

**Intentional, or at least long-known.** `VideosController` carries no `[Authorize]` attribute at
class or method level in current master. Jellyfin issue **#1501** ("Video streams completely
unauthenticated", opened 2019-07-01) is labelled `bug`, `confirmed`, `security`; **#5415** collects
the same family and was closed as a duplicate, framed as needing breaking changes in a hypothetical
11.0+; **#13986** carries the source comment *"TODO: In order to authenticate this in the future,
Dlna playback will require updating"*. The `HlsSegmentController` comment cites Chrome sending
requests without the full query string.

→ Upstream report drafted at `jellyfin-upstream-report-unauthenticated-media-2026-09-18.md`. It is
written to **ask whether this is still intentional in 12.x** rather than to assert a vulnerability,
and it says to check for an open tracker before filing.

### 5. `POST /System/Restart` is unauthenticated — by design, and our exposure was our own config

**Corrected.** The first pass reported this as possibly specific to 12.1 and did not isolate the
cause. Re-tested properly on clean containers of both versions with real settle delays between
requests:

| Credential | 12.1.0 | 10.11.11 |
|---|---|---|
| no `Authorization` header | 204, **restarts** | 204, **restarts** |
| invalid token | 204, **restarts** | 204, **restarts** |
| valid admin token (control) | 204, restarts | 204, restarts |

The earlier "no-header does not restart" result was a timing artifact from too short a settle window.

**This is working as designed.** `SystemController.RestartApplication` carries
`[Authorize(Policy = Policies.LocalAccessOrRequiresElevation)]` — local callers are allowed without
authentication. `ShutdownApplication` by contrast requires elevation outright.

**Our exposure was an empty `KnownProxies`.** Jellyfin sits behind Caddy at `172.28.0.17`, a private
address on the Docker network. With `KnownProxies` unset, Jellyfin ignores `X-Forwarded-For` and
classifies every internet request as local, so the restart route was open to the internet. Proven,
and the fix proven with it:

| `KnownProxies` | Caller presented as | Result |
|---|---|---|
| empty | anything via the proxy | 204, restarts |
| set to the proxy | `8.8.8.8` | **401, refused** |
| set to the proxy | `192.168.1.50` | 204, restarts — by design |

The IP-spoofing variant is **CVE-2025-32012**, CVSS 7.5, patched in 10.10.7. The proxy variant needs
no spoofing at all.

**Action: set `KnownProxies` to Caddy's address on the household server.** Not filed upstream; a
hardening suggestion is noted in the upstream report instead. jellystructure should also detect this
— added as FR-242-8.

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

Finding 4 produced no phase — it is Jellyfin's own behaviour and the response is an upstream
question, drafted at `jellyfin-upstream-report-unauthenticated-media-2026-09-18.md`.

Finding 5 produced **FR-242-8** (the advisor detects an empty `KnownProxies` behind a proxy) plus one
immediate configuration action on the household server.
