# Backend security review — public-internet exposure readiness

**Date:** 2026-08-02
**Scope:** the jellystructure Kotlin/Native Ktor backend (`src/linuxX64Main/`), its auth model, HTTP route
surface, secret handling, and injection/DoS posture.
**Trigger:** the operator is considering exposing jellystructure to the public internet (Jellyfin itself is
already exposed).
**Status (updated 2026-08-02):** all 21 in-app findings (C1/C2, H1-H6, M1-M8, L1/L2/L5/L6/L8) are now
**fixed and committed** — the user rejected the narrower "publish only Ravilo's API surface" mitigation in
§Deployment and directed fixing everything so the full API surface can be exposed. **D1** (FD-exhaustion DoS)
is not fixable in-process; its mitigation is the reverse-proxy config in the companion
[`backend-deployment-guide-2026-08-02.md`](backend-deployment-guide-2026-08-02.md), which also covers the two
new env vars (`COOKIE_SECURE`, `CORS_ALLOWED_ORIGINS`) the fixes introduced. **This file is left as-written at
audit time** (below) as the historical record of what was found; see the deployment guide for current status
and the post-deploy checklist.

---

## ⚠️ Do this first, before anything else

**Rotate every credential in `config/config.toml`.** During this audit the arbitrary-file-read bug
(**C2** below) was exercised against the running instance and `config.toml` was successfully read back
over an unauthenticated HTTP request. Its contents therefore exist in tooling transcripts on this host.
Treat as compromised and rotate:

- `api_keys.jellyfin_token` (Jellyfin **admin** API key)
- `api_keys.tmdb_v3_key`
- `qbittorrent.password`
- `radarr.api_key`, `sonarr.api_key`, and the other `*arr`/Seerr keys
- `ingest.webhook_secret`

This is a precaution about the audit itself, independent of whether anyone else ever exploited it.

---

## Verdict

**Do not expose this backend to the public internet in its current state.**

There are two independent **pre-authentication, remotely exploitable paths to full compromise**. Either one
alone is sufficient for an unauthenticated internet attacker to take over the jellystructure instance, and
through it the Jellyfin server, the *arr stack, and qBittorrent.

This is a *fixable* situation and the codebase is not careless — the shell-escaping discipline, SQL
parameterisation, per-user catalog scoping, and API-key least-privilege design are genuinely well done (see
[What is already right](#what-is-already-right)). The problems are concentrated in a handful of specific
places, and the two criticals are each a few lines.

The realistic path to safe exposure is: **fix C1–C2, then H1–H3, then put it behind a reverse proxy with TLS
and connection limits** (the proxy is not optional — see **D1**).

---

## Findings at a glance

| # | Severity | Finding | Auth needed | Verified |
|---|---|---|---|---|
| **C1** | 🔴 Critical | Percent-encoding the path bypasses **all** authentication | none | Live + source |
| **C2** | 🔴 Critical | Unauthenticated arbitrary file read (2 image-cache routes) | none | Live + source |
| **H1** | 🟠 High | `/ws` WebSocket is unauthenticated; streams live logs + library | none | Live |
| **H2** | 🟠 High | Jellyfin **admin** token handed to TV devices on token staleness | device | Source |
| **H3** | 🟠 High | `GET /api/config` returns every secret unmasked | admin | Source |
| **H4** | 🟠 High | No rate limiting on any auth path | none | Source |
| **H5** | 🟠 High | Command injection in `fireWebhook` (broken quote escape) | admin/config | Source |
| **H6** | 🟠 High | `generateSecureToken()` can silently return an all-zero token | n/a | Source |
| **D1** | 🟠 High | Unauthenticated DoS → hard process abort at 1024 FDs | none | Source |
| **M1** | 🟡 Medium | CORS reflects any Origin **with** credentials | none | Live |
| **M2** | 🟡 Medium | Session cookie missing `Secure`; no TLS in-process | none | Source |
| **M3** | 🟡 Medium | Revoked device tokens stay valid up to 5 minutes | device | Source |
| **M4** | 🟡 Medium | Playback routes skip the `visibleTo` policy check | device | Source |
| **M5** | 🟡 Medium | SSRF: no outbound URL guard (artwork, config-test, `PUT /config`) | admin | Source |
| **M6** | 🟡 Medium | No request body size limit → pre-auth OOM | none | Source |
| **M7** | 🟡 Medium | Secrets stored unencrypted, world-readable (0644/0664) | local | Live |
| **M8** | 🟡 Medium | No security response headers (HSTS/CSP/nosniff/frame-deny) | none | Source |
| **L1–L9** | ⚪ Low | See [Low severity](#low-severity) | various | Source |

---

## Critical

### C1 — Percent-encoding the request path bypasses all authentication

**Files:** `auth/AuthPlugin.kt:45-52`, every route in `server/Server.kt:227-330`

`AuthPlugin` decides which auth tier applies by prefix-matching `call.request.path()`. Ktor 3.5.0 defines
that as the **raw, undecoded** request target:

```kotlin
// ktor-server-core .../request/ApplicationRequestProperties.kt:62
public fun ApplicationRequest.path(): String = origin.uri.substringBefore('?')
```

Routing, however, resolves against the **decoded** path, percent-decoding each segment:

```kotlin
// ktor-server-core .../routing/RoutingResolveContext.kt:76
val segment = path.decodeURLPart(beginSegment, nextSegment)
```

The two disagree. A request to `/%61pi/stats` (`%61` = `a`) does **not** start with `/api/`, so
`AuthPlugin.kt:48-51` calls `proceed()` with no authentication at all — and routing then decodes it to
`api/stats` and dispatches the fully-privileged handler.

Confirmed live against the running instance:

```
GET /api/stats     → 401  {"error":"Not authenticated"}
GET /%61pi/stats   → 200  {"movies":279,"tvShows":155,"tvEpisodes":7390,…}
```

Independently confirmed by reading the Ktor 3.5.0 sources on this host (both call sites quoted above).

**What an unauthenticated attacker gets.** Every one of these is a real registered route with no in-handler
auth check:

| Encoded request | Effect |
|---|---|
| `GET /%61pi/config` | Full config **unredacted** — Jellyfin admin token, TMDB key, qBittorrent password, all *arr keys, webhook secret |
| `PUT /%61pi/config` | Overwrite the entire config, including `jellyfin_url`/`jellyfin_token` |
| `DELETE /%61pi/media/all` | Wipe the scanned library |
| `POST /%61pi/media/{id}/tracks/…` | **Mutate media files on disk** (mkvpropedit / ffmpeg remux) |
| `POST /%61pi/media/{id}/artwork/upload` | Write attacker bytes into the media library |
| `POST /%61pi/settings/api-keys` | Mint a `/api/remote/**` key bound to any Jellyfin user |
| `POST /%61pi/pipeline/run`, `/scan` | Trigger unbounded CPU/IO work (DoS) |
| `GET /%61pi/jellyfin/users` | Enumerate all Jellyfin users |

**Full-takeover chain:** `PUT /%61pi/config` blanking `api_keys.jellyfin_url` → the `/api/setup` guard
(`SetupRoutes.kt:27,35`, which keys purely off `jellyfinUrl.isNotBlank()`) re-opens → `POST /api/setup`
repoints Jellyfin at the attacker's own server → `POST /api/auth/login` as an administrator of *that*
server → a legitimate `js_session` admin cookie on the victim instance.

**Two accidental mitigations** (they fail closed, and are the reason this isn't even worse):
- Every `/api/tv/admin/**` handler independently re-checks `call.attributes[SessionKey]` → `401`.
- Device routes read `call.attributes[DeviceKey]` without `runCatching`, so the missing attribute throws →
  `500` via StatusPages.

**Suggested fix.** Do not derive authorization from a raw path prefix.
1. Primary: move the auth decision into a routing-phase plugin so it sees the same resolved route the
   dispatcher does, **or** decode each segment exactly as `RoutingResolveContext` does before matching.
2. Cheap immediate mitigation: reject any request whose raw path contains `%` (nothing legitimate in this
   API needs percent-encoded path segments), or normalise-then-compare.
3. Defence in depth: give every privileged handler the same explicit `SessionKey` check the
   `/api/tv/admin/**` handlers already have — that pattern is what saved those routes here.

---

### C2 — Unauthenticated arbitrary file read via two image-cache routes

**Files:** `tv/RaviloArtworkService.kt:96` (route `server/routes/TvRoutes.kt:815-820`)
and `tv/LiveTvService.kt:332` (route `server/routes/LiveTvRoutes.kt:138-142`)
**Exempted from auth by:** `auth/AuthPlugin.kt:29` (`/api/tv/image/`) and `:32` (`/api/tv/livetv/logo/`)

Both build a cache path by raw string concatenation of a caller-controlled path parameter, with no
validation whatsoever:

```kotlin
// RaviloArtworkService.serveAvatar
val cachePath = "$avatarDir/$userId"
readSimple(cachePath, ctPath)?.let { return it }   // ← returns file bytes before any network call

// LiveTvService.serveLogo
val cachePath = "$logoDir/$channelId"
readCached(cachePath, ctPath)?.let { return it }
```

No `..` check, no `/` check, no canonicalise-and-prefix-check. Ktor splits the raw path on literal `/` and
then percent-decodes each segment, so `%2F` arrives at the handler as a real slash and escapes the cache
directory. Critically, the cached-read happens *first* — an attacker never needs the Jellyfin fetch to
succeed; the handler just reads the path off disk and returns it.

Confirmed live (both):

```
GET /api/tv/image/user/..%2F..%2Fconfig.toml/avatar   → 200, 8314 bytes of config.toml (as image/jpeg)
GET /api/tv/livetv/logo/..%2F..%2Fconfig.example.toml → 200, exact file bytes
```

(8314 bytes matches `config.toml` on disk exactly.)

**Impact.** Read any file the process can open, unauthenticated:
- `config/config.toml` → every API key and the Jellyfin **admin** token
- `config/jellystructure.db` → live `js_session` tokens, stored **in plaintext** (see **M7**), which are
  directly replayable as a cookie ⇒ instant admin session, no brute force
- anything else readable by the service user

**Suggested fix.** Both services should use the guard `ChannelLogoStore.read` (`tv/ChannelLogoStore.kt:60`)
already implements correctly — reject `..`, `/`, `\` — or, better, derive the cache filename by hashing the
identifier the way `serveStill` already does (`RaviloArtworkService.kt:91`), so the caller never influences
the path at all. Additionally validate `channelId` against the known channel lineup and `userId` against
real Jellyfin user ids.

---

## High

### H1 — `/ws` accepts unauthenticated clients and streams the live log + library

**File:** `server/Server.kt:332-348`

`/ws` does not start with `/api/`, so `AuthPlugin.kt:48-51` returns `proceed()` before any check, and the
handler registers the socket with zero validation. Confirmed live — an anonymous handshake with no
credentials returns `HTTP/1.1 101 Switching Protocols`.

The blast radius is larger than it first appears, because **every** `Logger.info/warn/error` call in the
entire backend is fanned out to this socket: `Logger.emit` → `ActivityLog.log` →
`broadcaster.broadcast(JobEvent.LogLine(...))` (`log/Logger.kt:52-59`, `media/ActivityLog.kt:93-102`).

So an unauthenticated internet client gets a live feed of:
- full absolute media paths and ffmpeg command lines (`/mnt/series/jellyfin/…`)
- `JobEvent.ItemScanned` → the complete serialized `MediaItem` (paths, ids, cast, track layout)
- unhandled route exceptions **including the request path** (`Server.kt:179`)
- the configured Jellyfin URL (`AuthRoutes.kt:46`, logged on every failed login)

It also lets an attacker hold FDs against the ceiling in **D1**.

**Suggested fix.** Move it under `/api/` so the existing plugin covers it, or validate the admin session in
the handler the way `/api/tv/events` validates its device token (`Server.kt:356-362`). Note that "not under
`/api/`" silently means "unauthenticated" — worth an explicit deny-by-default so the next non-`/api/` route
doesn't inherit the same hole.

### H2 — The Jellyfin admin token is handed to TV devices when a user token goes stale

**File:** `tv/PlaybackService.kt:515-544` (`tvToken`), used at `:190, :229, :257, :268, :300, :329, :399, :435, :458`

`tvToken()` returns the device's own Jellyfin user token *only while Jellyfin still accepts it*; otherwise
it falls back to `configStore.current.apiKeys.jellyfinToken` — the long-lived **server/admin** key:

```kotlin
return if (valid) userToken else serverToken
```

That value is then interpolated into stream URLs handed back to the client
(`…/stream?…&api_key=$token` at `:229`, `:399`, `:458`) and returned as `StreamTicket.accessToken`
(`:236`, `:466`).

So any signed-in Ravilo device — including a Kids profile or a library-restricted user — receives the
Jellyfin admin key the moment its own token goes stale, which the surrounding code documents as routine
(there is a negative cache and a "re-pair this user" health flag around it, `:545-551`). With that key the
device holder has full admin over Jellyfin: all libraries regardless of `allowedLibraries`/`blockedTags`,
user management, server config. Every Phase-142 library-scoping control is bypassed.

**Suggested fix.** Never send the server token to a device. Fail the playback ticket and surface the
existing "re-sign-in required" state instead. If a server-token fallback must exist for server-side work,
scope it to calls whose result never reaches the client.

### H3 — `GET /api/config` returns every stored secret in plaintext

**File:** `server/routes/ConfigRoutes.kt:72-74`

Responds with the whole `AppConfig`, which carries `apiKeys.jellyfinToken`, `apiKeys.tmdbV3Key`,
`qbittorrent.password`, `radarr.apiKey`, `sonarr.apiKey`, `seerr.apiKey`, `ingest.webhookSecret`
(`config/AppConfig.kt:36, 80, 103, 145-149`). Nothing is masked on read, and the admin UI puts the real
values into DOM inputs (`wasmJsMain/.../ui/Settings.kt:583-584, 595, 647, 655`) plus a TOML export that
prints `jellyfin_token` verbatim (`Settings.kt:1159-1161`).

The UI does not actually need them: the write path already implements a `##KEEP##` sentinel
(`ConfigRoutes.kt:92-105`) and the *arr/Seerr fields already render "(unchanged)" placeholders. Masking on
read is a drop-in change — the feature is half-built already.

This is admin-gated in principle, but it is the payload that turns **C1**, an XSS, or a stolen cookie into
"attacker owns Jellyfin + the whole *arr stack".

### H4 — No rate limiting, lockout, or delay on any authentication path

**Files:** `AuthRoutes.kt:25-81` (`POST /api/auth/login`), `TvRoutes.kt:201-234` (`POST /api/tv/login`),
`AuthPlugin.kt:58-96` (API key / device token), `WebhookRoutes.kt:66-72` (webhook secret)

A repo-wide search for rate limiting, lockout, throttling, or failed-attempt tracking on inbound requests
returns nothing — the only throttles in the codebase are **outbound** (TMDB, *arr, ProcessGate). There is no
Ktor `RateLimit` plugin installed.

Each login is a synchronous proxy to Jellyfin's `AuthenticateByName`. On the public internet this is
unbounded online password guessing against every Jellyfin account, and it uses jellystructure as an
amplifier/anonymiser in front of whatever rate limiting Jellyfin itself might have.

There is also **no authentication audit trail**: a successful login logs nothing at all
(`AuthRoutes.kt:64-80`), so a compromise leaves no trace.

Minor enumeration oracle alongside it: `AuthRoutes.kt:56-62` returns a distinct `403 "requires a Jellyfin
administrator account"`, confirming a *valid non-admin* credential pair to an attacker who otherwise only
sees `401`.

### H5 — Command injection in `fireWebhook` via a broken shell escape

**File:** `server/routes/MediaRoutes.kt:2329-2335`

```kotlin
val safePayload = payload.replace("'", "\\'")     // ← wrong for single-quoted sh
posixSystem("curl … -d '$safePayload' '$url' &")
```

Backslash is **not** an escape character inside POSIX single quotes, so a `'` in the payload terminates the
quoted string and everything after it is interpreted by the shell. The codebase already contains the
correct idiom two files away — `ops/CrashResilience.kt:55` uses `replace("'", "'\\''")` — so this is an
inconsistency, not a missing concept.

Two feeders can put an unescaped `'` into `payload`:
- `ops/FdWatchdog.kt:154` → `census.toJson()` embeds real open-file paths; its `fdJsonEsc` (`:44`) escapes
  `\` and `"` but **not** `'`. A media file named `Bob's Burgers…` reaches the sink.
- `ops/CrashResilience.kt:71` → crash text, which routinely contains file paths; `crashJsonEsc` also omits `'`.

Requires `notifications_webhook` to be configured plus the trigger (FD pressure >850, or a crash). Note the
`>980` drain path at `FdWatchdog.kt:183` uses the *correct* helper — only the 850 path is affected.

Separately and more simply: `'$url'` is interpolated with **no escaping at all** in both helpers, so the
admin-settable webhook URL field is straight command execution.

**Suggested fix.** Replace both `posixSystem("curl …")` calls with the existing `OutboundHttp` client — no
shell involved at all. If a shell call must remain, use the `'\''` idiom uniformly and escape the URL too.

### H6 — `generateSecureToken()` can silently mint an all-zero token

**File:** `auth/SessionService.kt:106-114`

```kotlin
val fd = open("/dev/urandom", O_RDONLY)
read(fd, pinned.addressOf(0), 32.convert())
close(fd)
```

Neither return value is checked. On failure `fd == -1`, `read` returns `-1`, the buffer stays zero-filled,
and the function returns the constant string of 64 zeros — no exception, no log.

The realistic trigger is not "urandom broke", it is **EMFILE**: this process runs against a hard 1024-FD
ceiling with a dedicated watchdog and a documented history of FD-exhaustion incidents (see **D1**). At the
FD ceiling, `open("/dev/urandom")` returns `-1` — and that is exactly when load is highest.

Every secret in the system comes from this one function: admin session tokens (`:41`), Ravilo device tokens
(`tv/RaviloDeviceService.kt:70`), API keys (`auth/ApiKeyStore.kt:35-36`), and the *arr webhook secret, which
is generated **once at first boot and persisted permanently** to `config.toml` (`Main.kt:218-219`). If first
boot happens under FD pressure, the all-zero webhook secret is written to config and never regenerated.

Aggravating: `Session.sq`'s `upsert` is `INSERT OR REPLACE` on the token primary key, so two concurrent
zero-tokens silently collapse into one shared session row.

Good news — this pattern is **not** copy-pasted anywhere else; a fix is single-point (~4 lines: check
`fd >= 0`, check `read` returned 32, throw otherwise).

### D1 — Unauthenticated DoS: FD exhaustion causes a hard process abort

**Files:** `server/Server.kt:158-167`, `ops/FdWatchdog.kt:49-60, 117, 134-183`

Ktor's CIO Native engine uses `select()`, so any file descriptor numbered ≥ 1024 (`FD_SETSIZE`) **fatally
aborts the whole process** — upstream KTOR-8703, documented in-repo as unfixable. The mitigation is a
watchdog that warns at 700, alerts at 850, sheds load above 900, and drains/restarts above 980 — but it
ticks only every **2 seconds**, leaving ~124 FDs of headroom checked at 0.5 Hz.

On a LAN that is adequate. On the public internet, an attacker can open thousands of TCP connections per
second and cross 900 → 1024 well inside a single watchdog tick, aborting the process. The `restart:
unless-stopped` policy turns that into a crash-loop rather than an outage, but it is still a trivially
triggered, unauthenticated, remote denial of service — and via **H6** each restart is a fresh chance to mint
a zero-entropy token under pressure.

**Suggested fix.** This one is not really fixable in-process; it is the strongest argument for the reverse
proxy in [Deployment](#deployment-recommendation). Terminate connections at nginx/Caddy with
`worker_connections`, per-IP connection limits, and request rate limits so the Kotlin process never sees
more than a bounded number of sockets.

---

## Medium

### M1 — CORS reflects any Origin together with credentials

**File:** `server/Server.kt:189-203` — `anyHost()`, `allowHeaders { true }`, `allowCredentials = true`

Verified in the Ktor 3.5.0 CORS source: `val headerOrigin = if (allowsAnyHost && !allowCredentials) "*" else origin`.
Because `allowCredentials = true`, Ktor does **not** send `*` — it **echoes the caller's Origin** and adds
`Access-Control-Allow-Credentials: true`. Confirmed live:

```
OPTIONS /api/auth/me   Origin: https://evil.example.com
→ Access-Control-Allow-Origin: https://evil.example.com
→ Access-Control-Allow-Credentials: true
```

Today the practical damage is limited because `js_session` is `SameSite=Lax`, so browsers won't attach it to
cross-site fetches — i.e. CSRF is blocked **by the cookie attribute, not by CORS**, and there is no CSRF
token anywhere as a second layer. That makes this one attribute deep: switching the cookie to
`SameSite=None` (tempting the moment the Ravilo web client moves to its own origin — the exact scenario the
comment at `:190-191` cites) instantly becomes full cross-origin admin API access from any website.

It already grants any web page credentialed cross-origin access to the open routes (`/api/setup`,
`/api/tv/login` for browser-driven credential spraying, `/api/webhooks/*`).

**Suggested fix.** Replace `anyHost()` with an explicit `allowHost(...)` list of the real origins, or drop
`allowCredentials`.

### M2 — Session cookie is not `Secure`; no TLS in-process

**File:** `auth/AuthRoutes.kt:70-79` — `httpOnly = true`, `SameSite=Lax`, but `secure` is never set (defaults
`false`). The logout cookie (`:87`) omits both `Secure` and `SameSite`.

`server/Server.kt:158-167` creates a plain `EngineConnectorBuilder()` — HTTP only, bound `0.0.0.0`, no
`sslConnector`. There is also no `ForwardedHeaders`/`XForwardedHeaders` plugin anywhere, so the app cannot
know whether a request arrived over TLS, and no real client IP reaches the logs (which also means any future
rate limiting would see only the proxy's IP unless this is added).

Any accidental plain-HTTP reach — a misconfigured vhost, an `http://` bookmark, an HSTS-less first visit —
sends a 7-day admin session token in cleartext.

### M3 — Revoked device tokens keep working for up to 5 minutes

**File:** `tv/RaviloDeviceService.kt:177-179` (`removeSession`), `:202-204` (`deleteAllForUser`)

Both delete the DB row but never clear `tokenCache`, and `validateDeviceToken` (`:113-125`) serves the
cached `DeviceData` for `TOKEN_CACHE_TTL_MS = 5 min` without re-reading the DB. So the Phase-143 **"sign out
everywhere"** action reports success while a stolen TV token stays fully live for another five minutes —
including `/api/tv/playback/*`, which under **H2** can hand it the Jellyfin admin token. The cached entry
also freezes the stale `allowedLibraries`/`blockedTags` policy.

Clearly unintentional: `unpair()` (`:151-154`) and `loginDevice` (`:92`) *do* call `tokenCache.remove`, and
`ApiKeyStore.revoke` (`:67-70`) purges its cache correctly. Admin web sessions are unaffected —
`SessionService.validate` always hits the DB.

### M4 — Playback routes skip the per-device visibility check

**Files:** `TvRoutes.kt:395-442` → `PlaybackService.kt:184-203, 249-274, 298-314, 322-336, 428-435`

`DetailService` and `BrowseService`/`HomeFeedService` correctly gate on `MediaItem.visibleTo(device)`
(library allow-list + Jellyfin `AllowedTags`/`BlockedTags`, `MediaStore.kt:36-58`). **Playback does not** —
`startPlayback` takes the caller-supplied `jellyfinId` straight through.

Jellyfin's own per-user token is normally the backstop, but per **H2** that token is replaced by the admin
token whenever it goes stale. In that window a Kids/restricted profile can start a stream of, and mark
played, any item id in the library — bypassing both jellystructure's and Jellyfin's access control.

### M5 — SSRF: no outbound URL validation anywhere

- `media/ArtworkDownloader.kt:361-362` — `toUrl(source)` returns `source` verbatim for any `http(s)://`
  input; reachable from `MediaRoutes.kt:680, :741, :937`. Fetched bytes are written to disk and re-servable
  via `/api/tv/image/…`, making this a **full-read** SSRF, not blind. (`file://` is only *accidentally*
  blocked, by falling into the TMDB-prefix branch.)
- `ConfigRoutes.kt:116-152` — connection-test routes take `url` + `apiKey` unvalidated and send the key as
  `X-Api-Key`; the error `detail` echoes `e.message`, giving an internal port-scan oracle.
- `PUT /api/config` (`:89-115`) accepts the whole `AppConfig` with no URL validation, so repointing
  `jellyfinUrl`/`radarr.url` makes every later background call ship real credentials to an attacker host,
  **persistently**.
- `OutboundHttp.kt:47-56` — one shared Curl client with no `followRedirects = false`, no host filter, no
  response size cap. Ktor follows redirects by default, so any allowlist added later must be enforced
  **per hop**, not just on the initial URL.

Admin-gated today — but trivially reachable via **C1**.

### M6 — No request body size limit → pre-authentication OOM

`server/Server.kt:158-207` configures only `connectionIdleTimeoutSeconds`. There is no `maxContentLength`,
no `RequestValidation`. Unauthenticated handlers call `call.receive<…>()` on unbounded bodies:
`AuthRoutes.kt:26`, `TvRoutes.kt:202`, `SetupRoutes.kt:39`. A multi-GB POST to `/api/auth/login` exhausts
memory.

Related unbounded reads: whole-file NFO reads into a UTF-16 `StringBuilder` (`nfo/NfoWriter.kt:106-108,
170-172` — a planted 4 GB `.nfo` costs ~8 GB heap, triggered by the *scheduled* scan, so no auth needed),
and uncapped `readRawBytes()` on upstream responses (`ArtworkDownloader.kt:193`, `LogoDownloader.kt:101`,
`RaviloArtworkService.kt:106`, `LiveTvService.kt:341`).

### M7 — Secrets stored unencrypted and world-readable

Nothing is encrypted at rest, and no `chmod`/`umask`/mode argument appears anywhere in
`src/linuxX64Main` — `ConfigStore.persist()` writes via `FileIo.writeText` with the default mode, and
`createDatabase` passes no permissions. Verified on this host:

```
-rw-r--r--  jeggy jeggy 29904896  config/jellystructure.db     (0644, world-readable)
-rw-rw-r--  jeggy jeggy     8314  config/config.toml           (0664, world-readable)
```

- `config.toml` → Jellyfin admin token, TMDB key, qBittorrent password, Radarr/Sonarr/Seerr keys, webhook
  secret (confirmed present at lines 4, 5, 104, 120, 126, 142, 324)
- `jellystructure.db` → every live `js_session` token **in plaintext** (directly replayable as a cookie —
  unlike API keys, which are correctly hashed) and every device's Jellyfin user token

Suggested: `chmod 0600` both at creation; consider hashing session tokens at rest the way API keys already are.

### M8 — No security response headers

No `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, or
`Referrer-Policy` anywhere in the tree. The admin SPA is framable (clickjacking), and there is no `nosniff`
on the static/image handlers — which matters because admin-uploaded SVGs are served as `image/svg+xml` from
the app's own origin (`tv/ChannelLogoStore.kt:27`, `TvRoutes.kt:847-851`), an active-content stored-XSS
surface that would then have full access to **H3**.

---

## Low severity

- **L1 — `pkill -f` regex injection** (`media/MediaJobQueue.kt:127`): the temp path is shell-escaped but not
  *regex*-escaped, and `pkill -f` treats its argument as an ERE. A media file named `.*` produces
  `pkill -f '.jstmp_.*'`, killing unrelated processes — including the server. Filename-driven DoS.
- **L2 — Constrained command injection via language tags** (`commonMain/.../TrackCommandBuilder.kt:28-31`):
  `--set language=$iso3` is **unquoted**. `TrackRoutes.kt:329` and `MediaRoutes.kt:1090` validate the tag
  with a regex, but `TriageRoutes.kt:211` and `:270` only check `isBlank()`. `toIso6392` returns a 3-char
  input verbatim, so exactly 3 shell metacharacters survive. Currently constrained; becomes unconstrained
  RCE if `toIso6392` ever accepts longer BCP-47 tags.
- **L3 — Unauthenticated first-run config write** (`SetupRoutes.kt:35-53`): correctly 404s once
  `jellyfinUrl` is non-blank, but that guard is the *only* thing protecting it, and **C1** can clear it.
  On a fresh public deployment there is a genuine first-come-first-served race window.
- **L4 — Unauthenticated image/user enumeration**: `/api/tv/image/{itemId}/{type}` and
  `/api/tv/image/user/{userId}/avatar` are open with no `visibleTo` check, so any internet visitor can pull
  artwork for the whole library by id, and any Jellyfin user's avatar. Documented tradeoff (Coil can't
  attach a token) — defensible on a LAN, not on the internet without unguessable ids.
- **L5 — Non-constant-time webhook secret compare** (`WebhookRoutes.kt:68`): `provided != secret`
  short-circuits. Not remotely exploitable against a 64-hex-char secret, but a constant-time compare is a
  one-liner. Each valid call also spawns a 5-minute polling coroutine (`:105, :131-137`) with no concurrency
  bound.
- **L6 — Login error text proxied to the client** (`AuthRoutes.kt:48-51`): the *authored* messages are safe
  and non-enumerating, but a transport failure isn't caught in `JellyfinClient.authenticateByName`, so a raw
  ktor-curl exception — which embeds the internal Jellyfin hostname/port — reaches the client.
  `POST /api/tv/login` (`TvRoutes.kt:227-234`) already does this correctly and is the pattern to copy.
- **L7 — Device token in a query string** (`Server.kt:356`): `/api/tv/events?token=…` lands in proxy access
  logs and browser history. Justified (browsers can't set handshake headers); a short-lived ticket would
  avoid it.
- **L8 — Hand-rolled SHA-256** (`auth/Sha256.kt`): traced line-by-line against FIPS 180-4 and it is correct.
  Risk is contained — it is used only to hash a 256-bit-entropy API key, never for passwords or MACs, where
  unsalted single-round SHA-256 is appropriate. The gap is that **no test vectors exist in the repo**, so a
  future edit could silently break it. Worth adding the three standard vectors to `src/linuxX64Test`.
- **L9 — Cross-profile signout on a shared TV** (`TvRoutes.kt:291-298`): correctly scoped to the caller's
  own `deviceId`, but any profile signed in on that TV can sign out any other profile on it.
- **L10 — Expired sessions purged only at startup** (`SessionService.kt:31-34`): not a bypass (`validate()`
  checks `expires_at` every call), just unbounded row growth. Same for the `lastUsedWritten` debounce map.
- **L11 — TMDB key on a shell command line** (`Server.kt:277`): injection is adequately blocked (value is
  single-quoted and `'` is stripped, which is sound), but the key is visible in `/proc/*/cmdline` to local
  users for the life of the call.

---

## What is already right

Worth stating plainly, because these are the parts that should **not** change:

- **Shell path escaping is correct at every media call site.** `FfprobeRunner`, `FfmpegRunner`,
  `MkvpropeditRunner`, `MediaJobQueue`, `TrackRoutes.kt:771` all apply the correct POSIX `'\''` idiom inside
  single quotes. No unquoted path interpolation exists. The only broken sinks are the two `curl` webhook
  calls (**H5**) and the language tags (**L2**).
- **SQL is 100% parameterised** via SQLDelight; the only raw statements are two static DDL literals.
- **XXE is structurally impossible** — there is no XML *parser* in the repo at all. NFO handling is
  write-only, and `NfoWriter.esc()` escapes `&` first (no double-escape bug), with only `Int`s interpolated
  unescaped.
- **API keys are genuinely least-privilege**: accepted *only* for `/api/remote/**` (`AuthPlugin.kt:59-75`),
  every handler re-scopes to the key's bound Jellyfin user, keys can't mint keys, and they are the one
  secret **stored hashed** (SHA-256) with the plaintext shown exactly once.
- **Every `/api/tv/admin/**` handler independently re-checks `SessionKey`** — real defence in depth, and the
  reason those routes survive the **C1** bypass.
- **Catalog access is per-device policy-enforced** via `MediaItem.visibleTo(device)` in Detail/Browse/Home
  (playback is the gap — **M4**).
- **Session tokens are never leaked to the admin UI** — `/api/tv/admin/overview` deliberately returns only a
  12-char prefix and resolves it server-side on revoke.
- **Logout revokes server-side**, and `SessionService.validate` always hits the DB (no stale-cache bypass).
- **Secret logging is clean** — every `Logger.*` call was checked for token/key/password interpolation and
  none logs a secret; `grep -c api_key` over the live 2.4 MB activity log returns **0**.
- **Static file serving rejects `..`** and, by accident of Ktor's raw-path API, the guard and the sink see
  the same string (no double-decode bug) — though this is worth making deliberate rather than incidental.
- **Background coroutines cannot abort the process** — both scopes use `SupervisorJob` +
  `CoroutineExceptionHandler`, and WebSocket handlers catch all throwables (a liveness control on
  Kotlin/Native, not just hygiene).
- **`/api/health` and `/api/health/full` are cookie-gated** (verified: 401 unauthenticated), so the FD
  census, Jellyfin URL, disk stats and *arr topology are not publicly readable.

---

## Deployment recommendation

Even after every finding above is fixed, **do not expose the Kotlin process directly.** Put it behind
nginx/Caddy/Traefik:

1. **TLS termination + HSTS**, and set `secure = true` on the session cookie (**M2**).
2. **Connection and rate limits** — this is the only practical mitigation for **D1**, since the FD ceiling is
   an unfixable upstream constraint. Cap concurrent connections per IP well below the 900 shed threshold.
3. **Request body size cap** at the proxy as a second layer behind **M6**.
4. **Security headers** at the proxy if not added in-app (**M8**).
5. **Path normalisation caution:** if the proxy normalises or re-encodes paths differently from Ktor, it can
   *change* the behaviour of **C1** and the `..` guards in either direction. Fix **C1** in the app; don't rely
   on the proxy for it.
6. Consider **not exposing the admin surface at all** — publish only `/api/tv/**` + `/tv/**` (what Ravilo
   clients need) and keep `/api/config`, `/api/media/**`, `/api/settings/**` on the LAN or behind a VPN.
   That single split removes most of this report's blast radius.
7. Add `ForwardedHeaders` in-app so real client IPs reach the logs — a prerequisite for any useful rate
   limiting or audit trail (**H4**).

---

## Suggested remediation order

| Order | Items | Why first |
|---|---|---|
| 0 | **Rotate all secrets in `config.toml`** | Exposed during this audit |
| 1 | **C1**, **C2** | Pre-auth full compromise; both are small, local fixes |
| 2 | **H1** (`/ws`), **H6** (urandom check) | Small, high-value, no design work |
| 3 | **H2**, **H3**, **M3**, **M4** | Privilege containment — stop handing out admin credentials |
| 4 | **H4** + `ForwardedHeaders`, **M6** | Brute force + resource limits before exposure |
| 5 | **H5**, **L2** | Command injection; replace `posixSystem` curl with `OutboundHttp` |
| 6 | **M1**, **M2**, **M8**, **M7** | Browser/transport hardening + file modes |
| 7 | **M5** shared outbound URL guard (per-redirect-hop) | SSRF |
| 8 | **D1** via reverse proxy, then the rest of the Low list | Ongoing hardening |

---

## Method and caveats

- Findings were produced by four parallel read-only reviews (route authorization/IDOR, injection/SSRF/path
  traversal, secrets/crypto/disclosure, and transport/CORS/DoS), then cross-checked against each other.
- **C1**, **C2**, **H1**, and **M1** were confirmed against the live instance on `127.0.0.1:9505` with
  read-only requests. **C1** and **C2** were additionally re-verified statically from source (including the
  Ktor 3.5.0 sources on this host) rather than taken on trust.
- Everything else was traced in source; each finding cites file:line. Where a claimed issue turned out to be
  correctly handled, it is listed under [What is already right](#what-is-already-right) rather than dropped.
- **Not covered:** the WASM admin frontend and Ravilo clients (only the backend was in scope), dependency
  CVE scanning, the Jellyfin instance itself, and host/network configuration.
- No remediation was implemented, per the brief.
