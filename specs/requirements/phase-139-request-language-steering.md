# Phase 139 — Request-language steering: intents, *arr auto-provisioning, change-later (FR-RL1)

> The backend half of "let a viewer request a title in **Original** or **Nordic/Danish**". This phase
> owns the concept, the Radarr/Sonarr setup, and the request plumbing. The TV UI is **[Phase R172](../ravilo/requirements/phase-R172-request-language-picker.md)**.
> Investigation + verified mechanics: [`../research-reports/request-language-steering-2026-07-04.md`](../research-reports/request-language-steering-2026-07-04.md).

## Goal
When a title is requested from Ravilo, the request carries a **language intent** (e.g. `original` or
`nordic`). jellystructure translates that intent into a Radarr/Sonarr **quality profile + tags** so the
grab lands the right release — a Nordic/Danish release (which usually bundles English too), typically
from the **NordicVault** tracker, versus the standard original-language release. jellystructure
**creates and maintains** the needed *arr custom formats + profiles itself (no hand-editing Radarr),
and lets a viewer **change a still-waiting request's language later** from the app.

## Why this is needed (verified 2026-07-04, live stack)
- The request pipeline carries **no language**: `POST /api/tv/discover/request`
  (`TvRoutes.kt:392-399`, DTO `{mediaKind, tmdbId, title}`) → `SeerrDiscoverService.request()`
  (`SeerrDiscoverService.kt:128`) → `SeerrClient.createRequest()` (`SeerrClient.kt:173-184`) sends only
  `{mediaType, mediaId, seasons}`.
- Jellyseerr (v3.3.0) **can't** decide this: override rules match a title's *original* language (a Disney
  movie is "en", so no rule expresses "the Danish dub") **and** are skipped for MANAGE_REQUESTS/admin
  callers — but Seerr `POST /request` **does** accept explicit `profileId` + `tags` (verified in
  `MediaRequest.ts:225/386/497`), which pass straight through to the *arr add.
- The live *arr stack has NONE of the steering layer yet: NordicVault is synced+enabled (Radarr indexer
  19, Sonarr 22) but there are **zero language custom formats, no Nordic profile, no tags** — profiles are
  stock `Any/…/1080p` at `minFormatScore=0`.
- Radarr's parser does **not** recognize `NORDiC` as a language token — so matching must be on the
  **release title** (regex), not the Language condition.

## Decisions (confirmed with product owner)
1. **Auto-provision** the *arr custom formats + quality profiles from jellystructure (idempotent, with a
   preview) — the admin never builds them by hand.
2. **Strict** matching for a Nordic-type intent (only grab a matching release; keep waiting if none) —
   **plus** a viewer-driven **change-later** so a still-waiting request can be switched to another
   language (e.g. Nordic → Original) from the Ravilo app days later. Strict-vs-prefer is a **per-intent**
   config flag (Nordic defaults strict; Original is prefer/plain).
3. Language options are a **configurable list of intents** (start: Original + Dansk/Nordic; add
   Faroese/Icelandic later without code changes).
4. Requests still flow **through Seerr** (keeps Phase 136's centralized request list/auto-approve);
   jellystructure additionally talks to Radarr/Sonarr **directly** for provisioning and change-later.

## Requirements

### A. Request-language intent catalog (config)
1. New config array `[[request_language]]` (`config/AppConfig.kt`, alongside `[radarr]`/`[sonarr]`/`[seerr]`):
   per intent — `id` (sent by the app, e.g. `original`/`nordic`), `label` (endonym shown on TV, e.g.
   `Original`/`Dansk`), `flag` (ISO-639-1 language code for the flag asset — matches `AudioFlagStrip`'s
   existing lookup, e.g. `da`; `original` → blank ⇒ the title's own original-language flag / globe),
   `base_profile` (name of an existing *arr profile to
   clone, e.g. `HD-1080p`), `match` (release-title regex; blank = no custom format ⇒ plain base profile,
   used by `original`), `tags` (optional *arr indexer tags), `strict` (bool), and one intent flagged
   `default = true`. Optional global `kids_default` (intent id).
2. A shared serialisable `RequestLanguageIntent` in `:shared` so the DTO layer + admin editor + TV app
   see the same catalog (mirror the `SeerrFeed`/`SeerrDiscoverEndpoint` pattern in
   `shared/.../tv/Models.kt`). The catalog (labels/flags/ids, **not** the *arr internals) is exposed to
   the TV via the discover/config DTO so the picker can render it.

### B. Auto-provision into Radarr/Sonarr (idempotent, preview-first)
1. For each intent with a non-blank `match`, jellystructure ensures, via `ArrClient`
   (`arr/ArrClient.kt`, new methods on the existing authenticated client):
   - a **custom format** named e.g. `Nordic Audio` with a `ReleaseTitleSpecification` regex = `match`
     (default `\b(NORDiC|NORDIC|DANiSH|DKSUBS?)\b`, admin-editable), created via
     `POST /api/v3/customformat` (updated if it already exists / drifted).
   - a **quality profile** named e.g. `<base_profile> · <label>` = a **clone of `base_profile`**
     (`GET` the base, copy quality items/cutoff) with the custom format scored **+10000**; `strict` ⇒
     `minFormatScore = 10000` (gate — only matching releases qualify), else score-only (prefer). Created
     via `POST /api/v3/qualityprofile`.
   - both in **Radarr and Sonarr** (movies + series).
2. **Preview then apply, idempotent:** a settings action returns a diff of what would be created/updated
   (never deletes), and applying is safe to re-run. Store the resulting profile ids per intent so the
   request path can look them up. Optional (behind a toggle): tag the NordicVault indexer with the
   intent's `tags` so nordic-tagged requests prefer it (documented as traffic hygiene, not required for
   correctness).
3. Guard: if Radarr/Sonarr is disabled/unreachable, provisioning is a no-op with a clear status; the
   feature degrades to "no steering" rather than erroring.

### C. Request plumbing (language → Seerr profileId + tags)
1. `TvDiscoverRequest` (`shared/.../tv/Discover.kt`) gains `language: String?` (intent id);
   `TvApiClient.requestDiscover(...)` (`TvApiClient.kt:270`) and the route
   (`TvRoutes.kt:392`) thread it through.
2. `SeerrDiscoverService.request()` (`SeerrDiscoverService.kt:128`) **resolves the effective intent** by
   precedence: **(1)** explicit `language` on the request → **(2)** the requesting user's per-user default
   (R172, resolved viewer→global like `uiLanguage`) → **(3)** `kids_default` when `device.isKids`
   (`device.isKids` is already in scope at `TvRoutes.kt:392`) → **(4)** the catalog `default` intent.
3. `SeerrClient.createRequest()` (`SeerrClient.kt:173`) gains `profileId: Int?` + `tags: List<Int>?` and
   adds them to the `POST /request` body when the resolved intent has a provisioned profile. `original`
   (no `match`) → its plain base profile (or omit ⇒ Seerr default), which is today's behaviour.

### D. Persist the intent + make requests findable
1. New store `request_intent` (SQLDelight, `db/`): `requested_by` (jellyfinUserId), `media_kind`,
   `tmdb_id`, `language_id`, `strict`, `requested_at`, `arr_kind`/`arr_id` (nullable, filled lazily).
   PK `(media_kind, tmdb_id)` (one *arr item per title) with `requested_by` for attribution.
2. On request, upsert the row. This lets R172 render the chosen **flag** on the status chip, show the
   **"waiting for a <label> release"** state for a strict-unfulfilled request, and list a user's
   **in-progress requests** (their rows whose live Seerr status is not yet `available`).
3. Status itself stays **live** from Seerr `mediaInfo` (no new poller) — the row only records *intent*,
   not progress.

### E. Change the language of an existing (unfulfilled) request
1. New `POST /api/tv/discover/request/{mediaKind}/{tmdbId}/language` `{language}`:
   - resolve the new intent → find the *arr item by tmdbId (`ArrClient` `GET /movie?tmdbId=` /
     `GET /series` by tvdb) → `PUT` its `qualityProfileId` (+ tags) to the new intent's profile →
     trigger a fresh search (`POST /api/v3/command` `MoviesSearch`/`SeriesSearch`).
   - update the `request_intent` row.
2. Scope: valid while the title is **not yet available/downloaded** (the "gave up waiting, switch to
   standard" case). Once a file exists, changing language is a separate re-acquire and is **out of scope**
   here (the app just shows "available").

### F. Settings UI (admin — Download tools)
1. A **Request languages** card under Settings ▸ Download tools (where `[radarr]`/`[sonarr]`/`[seerr]`
   already live): a reorderable list of intents (label · flag · base profile · match regex · strict
   toggle · default radio · optional tags), an **＋ Add language**, and a **Set up profiles in
   Radarr/Sonarr** button (§B preview→apply with a result summary). The per-user *default* picker lives in
   the Ravilo config editor (R172), not here.

## Scope / critical files
- `config/AppConfig.kt` (`[[request_language]]`), `shared/.../tv/Models.kt` + `Discover.kt`
  (`RequestLanguageIntent`, `TvDiscoverRequest.language`).
- `arr/ArrClient.kt` (custom-format + quality-profile create/update/clone, item re-profile + search),
  a small `RequestLanguageService` (resolve intent, provision, change-later).
- `seerr/SeerrClient.kt:173` (+`profileId`/`tags`), `seerr/SeerrDiscoverService.kt:128` (resolution),
  `server/routes/TvRoutes.kt:392` (+ change-language route).
- New `db/RequestIntent.sq` + store; `api/TvApiClient.kt:270`.
- The Settings Download-tools tab (admin wasmJs) for the catalog editor + Set-up button.

## Non-goals
- No Jellyseerr override rules (can't see the viewer's wish; skipped for admin callers).
- No per-season / per-episode language mixing (profile is series-wide).
- No changing language **after** a file is downloaded (that's a re-acquire).
- No new progress poller — status stays live from Seerr `mediaInfo`.
- `languageProfileId` (legacy Sonarr v3) is not used — CF-scored profiles are the v4 mechanism.

## Acceptance
- Settings ▸ Request languages shows Original + Dansk (Nordic); **Set up profiles** creates
  `Nordic Audio` CF + `HD-1080p · Dansk` profile in **both** Radarr and Sonarr, is preview-first and
  idempotent (re-run = no-op).
- Requesting with `language=nordic` (strict) sends Seerr a `profileId` for the Nordic profile; Radarr
  adds the movie monitored on that profile and only grabs a NORDiC/DANiSH release (in practice from
  NordicVault), else keeps waiting. `language=original` reproduces today's behaviour.
- The `request_intent` row records the choice; a strict-unfulfilled title reports its waiting state and
  flag to the TV.
- `POST …/language {original}` on a still-waiting Nordic request flips the *arr item to the standard
  profile and re-searches, and the row updates.
- Radarr/Sonarr disabled ⇒ provisioning + steering degrade to "no steering", no errors.
- Verified via real `compileKotlinLinuxX64` (+ `:ravilo-web:compileKotlinWasmJs` for shared/DTO).
