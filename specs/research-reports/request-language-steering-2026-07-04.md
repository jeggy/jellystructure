# Request-language steering — investigation (2026-07-04)

**Question:** when a viewer requests a title from Ravilo's Request/Discover tab, how can they say
*which language the download should be in* — e.g. "Danish (NORDiC) for Disney movies", which in
practice means grabbing the NORDiC release that usually lives on the **NordicBytes** tracker — with a
nice, non-technical UI on the TV?

**Status: investigation only — nothing implemented.** Everything below was verified against the live
stack (Radarr v6.2.1 @ 7003, Sonarr v4 @ 7004, Jellyseerr **v3.3.0** @ stream.jebster.net) and the
current working tree on 2026-07-04.

---

## 1. Verified current state

### The request pipeline carries no language anywhere
`POST /api/tv/discover/request` (`TvRoutes.kt:392-399`, DTO `{mediaKind, tmdbId, title}` at `:77-81`)
→ `SeerrDiscoverService.request()` (`:128-140`, gates on `canRequest`) →
`SeerrClient.createRequest()` (`SeerrClient.kt:173-184`) → Seerr `POST /api/v1/request` with body
**`{mediaType, mediaId, seasons:"all"}` and nothing else**. The old direct-to-*arr path
(`AcquisitionService` → `ArrClient.addMovie/addSeries`, `ArrClient.kt:159-203`) also sends only the
one global quality profile + root folder — no tags, no language. The `acquisition` table has no
profile/tags/language column. Per-user Ravilo config has `uiLanguage` (display only) and
`SeerrFeed.param` (browse-feed filter by *original* language) — **no "preferred dub" field exists.**

### The live *arr stack has none of the steering layer yet
- **NordicBytes is synced and enabled in both** Radarr (indexer id 19) and Sonarr (id 22), untagged.
- **Zero language custom formats** (Radarr has only "Block ISO"; Sonarr has none), no Nordic quality
  profile (stock Any/SD/720p/1080p/UHD…, all `minFormatScore=0`), no language tags (only `1-jogvan`,
  `seedbox`).
- Jellyseerr has one Radarr + one Sonarr service, both defaulting to profile **"Any"**; its override-
  rules list is empty (`GET /api/v1/overrideRule` → `[]`).

### Upstream mechanics (verified in seerr v3 source + Radarr source)
- Seerr `POST /request` accepts per-request **`serverId`, `profileId`, `rootFolder`,
  `languageProfileId`, `userId`** (openapi) **and `tags`** (in code: `MediaRequest.ts:225` reads
  `requestBody.tags`; merged into the *arr add call at `:386/:497`).
- **Override rules are the wrong tool for this feature, twice over:** (a) their `language` condition
  matches the media's **original** language — a Disney movie is "en", so a rule can never express
  "the viewer wants the Danish dub"; (b) rules are **skipped entirely for callers with
  MANAGE_REQUESTS** (`MediaRequest.ts:219-226`) — jellystructure calls Seerr with the admin API key,
  so explicit body `profileId`/`tags` pass through untouched, and rules wouldn't fire anyway.
- **Radarr's release parser does NOT recognize "NORDiC" as a language token**
  (`LanguageParser.cs` matches `danish` but has no `nordic` entry) — a `Movie.2026.NORDiC.1080p-GRP`
  release parses as English-by-default. ⇒ any Nordic custom format must match the **release title**
  (regex on `\bNORDiC\b` etc.), not rely on the Language condition alone.
- *arr **indexer-tag semantics**: an indexer carrying tags is used *only* for items sharing a tag;
  untagged indexers serve everything. So tagging NordicBytes `nordic` would *remove* it from normal
  grabs and reserve it for nordic-tagged items — optional traffic hygiene, not the correctness
  mechanism.

---

## 2. Recommended architecture: "request language intents", owned by jellystructure

Seerr can't decide this (see above), and pushing viewers into Radarr concepts breaks "non-technical".
So jellystructure owns a tiny new concept — a named **request-language intent** — and translates it
into *arr reality at the two places it already talks to the stack:

```
Viewer picks "Dansk"           (Ravilo, flags + endonyms, zero jargon)
        │  language="da" on TvDiscoverRequest
        ▼
jellystructure maps intent → { radarr profileId, sonarr profileId, tags[] }   (admin-configured once)
        │  POST seerr /request { mediaType, mediaId, seasons, profileId, tags }
        ▼
Radarr/Sonarr profile "HD-1080p · Nordic" — custom format `\b(NORDiC|DANiSH|DKSUBS)\b`
scored +10000, profile minFormatScore=10000 (strict) or 0 (prefer)
        ▼
Only (or preferably) Nordic releases qualify → in practice the grab lands on NordicBytes,
because that's where NORDiC releases live. Optional: tag NordicBytes ↔ `nordic` to pin it.
```

Three layers:

**(a) *arr provisioning — jellystructure can do it FOR the user.** The needed objects don't exist
today, and creating them by hand in two *arrs is exactly the technical UX we want to avoid.
`ArrClient` already authenticates to both; `POST /api/v3/customformat` + `POST /api/v3/qualityprofile`
can create, per enabled intent: a release-title CF (e.g. `Nordic: \b(NORDiC|NORDIC|DANiSH|DKSUBS)\b`)
and a cloned quality profile (`HD-1080p · Nordic`) scoring it +10000 — with `minFormatScore` deciding
**strict** ("wait for Danish") vs **prefer** ("Danish if it exists, else best available"). One admin
button: *"Set up in Radarr/Sonarr"*, idempotent, re-runnable.

**(b) Config — the intent table (admin, Settings ▸ Download tools ▸ Requests).** Something like:
```toml
[[request_language]]
id = "da"            # what Ravilo sends
label = "Dansk"      # endonym shown on TV (flag: da → flag_dk)
radarr_profile = "HD-1080p · Nordic"
sonarr_profile = "HD-1080p · Nordic"
tags = ["nordic"]    # optional indexer steering
strict = true        # wait for a matching release vs prefer-only
```
plus an implicit `"any"` intent = today's behavior (Seerr defaults). Two or three rows cover this
household (Dansk/NORDiC · Original/English · Any).

**(c) Request plumbing.** `TvDiscoverRequest` gains `language: String?`; `SeerrDiscoverService`
resolves it (explicit pick → per-user default → kids default → global default) and
`SeerrClient.createRequest` adds `profileId` (+ `tags`) to the payload. ~4 small touchpoints, all
already identified: `TvRoutes.kt:392`, `SeerrDiscoverService.kt:128`, `SeerrClient.kt:173`,
`TvApiClient.kt:270`. Persist the chosen intent (e.g. reuse/extend the request-status surface) so the
detail page can show *"Requested · 🇩🇰 Dansk"*.

Rejected alternatives, for the record: **Seerr override rules** (can't see the viewer's wish; skipped
for admin callers) · **second Seerr instance per language** (heavy, same profile work anyway) ·
**direct-to-*arr bypass of Seerr** (Phase 136 just deliberately centralized requests through Seerr;
per-request `profileId` keeps Seerr's approval/visibility flow intact).

---

## 3. The non-technical Ravilo UX

Principle: **viewers see flags and words, never profiles/trackers.** Defaults do the work; the picker
appears only when a real choice exists.

1. **Per-user default request language** (admin: Ravilo config ▸ Request card, next to `canRequest`;
   model: clone the R162 `uiLanguage` per-user-override pattern —
   `ResolvedBehaviourField`/`ravilo_behaviour`). Kids devices (`device.isKids`, already in scope at
   the request route but unused) default to **Dansk** — "kids TV always requests dubs" with zero
   interaction.
2. **On Request press:**
   - 0–1 intents configured, or the user has a default and no alternates → **no new UI at all**;
     request fires exactly as today, steering happens silently.
   - Otherwise a small popup in the existing **TrackPicker** style (`PlayerScreen.kt:1252-1305` —
     the audio/subtitle picker viewers already know): 2–3 rows, flag + endonym + ✓ on the default,
     e.g. `🇩🇰 Dansk` / `🇬🇧 Original (English)` / `Any language`, Select = request, Back = cancel.
     Reuse `AudioFlagStrip`'s `LANG_CC` map + `flag_*.png` assets and the `RAVILO_LANGS_UI` endonyms.
3. **Status keeps the promise visible:** the detail status line and tile badge carry the flag
   (`Requested · 🇩🇰` → `Downloading 43% · 🇩🇰`), reusing the existing `episodeBadge`/status-chip
   slots. For a **strict** intent with nothing grabbed yet, show honest copy — *"Bíðar eftir donskum
   útgávu"* / "Waiting for a Danish release" — instead of a generic stall.
4. **i18n:** three new string keys ("Request in…", "Any language", "Waiting for a … release") in
   en/da/fo; language rows themselves use endonyms (no translation needed).

Optional later refinement: a per-feed default on `SeerrFeed` (e.g. a "Disney · Dansk" studio feed
whose requests default to `da`), which is where "Danish for Disney movies specifically" lands without
the viewer ever picking anything.

---

## 4. Risks / open questions

- **Strict intents can stall** — if no NORDiC release ever surfaces, a `minFormatScore` profile never
  grabs. Mitigation: honest "waiting for …" status (above); possibly a later auto-relax ("fall back to
  Any after N days") — needs product decision.
- **Nordic ≠ Danish audio, strictly** — NORDiC releases are usually Scandinavian dub bundles or
  DK-subbed originals; for kids' animation this is exactly right (dubs), for live-action it usually
  means *subs*, which is also usually what's wanted. The CF regex is the household's definition and
  is admin-tunable.
- **Seerr attribution** — requests are created by the admin API user (no `userId` passed today), so
  Seerr's own UI shows them under admin. Unchanged by this feature; could pass `userId` later if
  Seerr users are ever mapped.
- **Sonarr granularity** — the profile applies to the whole series; per-season language mixing is out
  of scope.
- **`languageProfileId`** is legacy (Sonarr v3) — ignore it; CF-based profiles are the v4 way.
- **Case gotcha** already found in-tree: Seerr language endpoints require lowercase ISO-639-1 (an
  uncommitted fix for the discover feeds exists in `SeerrClient.kt`/`RaviloConfig.kt` right now).

## 5. Suggested phase split (when we build it)

- **Admin phase (backend + settings):** intent config model + Settings UI; idempotent *arr
  provisioning (CF + profiles + optional indexer tags) via `ArrClient`; `SeerrClient.createRequest`
  gains `profileId`/`tags`; request route resolves intent (explicit → user default → kids → global).
- **Ravilo phase (TV UI):** per-user default in the config editor (uiLanguage-pattern clone);
  TrackPicker-style language popup on Request; flag on status chips; i18n keys; kids default rule.
