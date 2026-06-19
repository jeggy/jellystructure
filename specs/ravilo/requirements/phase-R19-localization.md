# Phase R19 — Interface localization (en / da / fo), per-user (FR-RV19)

**Status:** Planned · _the Ravilo UI language follows the Jellyfin user, set in Jellystructure._

> Builds on **[R04](phase-R04-per-user-config-store.md)** (per-user config) and
> **[R18](phase-R18-multi-user-profiles.md)** (multi-user). The locale is a per-user setting, so a
> profile switch (R18) also switches the interface language.

## Problem
Ravilo's interface is English-only. A Faroese/Danish household needs the **chrome** (nav, buttons,
labels, the profile gate, system row titles) in their own language — and on a shared TV each user may
want a different one. The language must be **controlled per Jellyfin user from Jellystructure**, not
guessed from the device locale.

## Current state (as-is)
- All UI strings are hard-coded English in the client. `RaviloConfig` (R04) is per user but has no
  locale field. Profiles (R18) carry no language.

## Requirements

### Supported languages (initial set)
1. **English (`en`)**, **Danish (`da`)**, **Faroese (`fo`)**. `en` is the fallback for any missing
   key. The set is extensible (add a locale table; no code changes to consumers).

### Per-user, server-controlled
2. `RaviloConfig` gains a **`uiLanguage`** field (one of the supported codes), edited on the
   Jellystructure **Ravilo config screen** (R16) per Jellyfin user and delivered via
   `GET /api/tv/config` / the home feed. It is **not** derived from the device/browser locale.
3. The client applies the **active user's** `uiLanguage`; switching profiles (R18) re-applies the new
   user's language immediately, with the app bar + current screen re-rendering in that language.
4. A user with no set language falls back to `en` (or an operator default).

### What gets localized
5. **All interface chrome**: top nav (Home/Movies/Series/My List), hero & detail actions
   (Play/Resume/More Info/Trailer/My List/Close), "See all", "Channels & Collections" + its subtitle,
   "More Like This", the channel header copy, and the **system row titles** (Continue Watching, Newly
   Added Movies/Series, the merged Newly Added).
6. **The multi-user surfaces** (R18): "Who's watching?", "Switch profile", "Add user", admin tag,
   Kids badge, the pairing/add-user panel copy, Cancel/Back, and the "signed in as …" confirmation.
7. **Search** affordances (placeholder, empty/suggestions copy).
8. **Not** localized by this phase: **media content** (titles, overviews, genres, cast) — those come
   from TMDB/Jellyfin per the item's metadata language and are out of scope; and **per-item
   audio/subtitle** language (a playback concern, unrelated to UI language).

### Implementation shape
9. A single **locale table** keyed by string id with `en`/`da`/`fo` columns and a `t(key[, vars])`
   lookup (placeholder interpolation, e.g. `signed_in_as {name}`); fallback to `en` then the key.
   Lives in the shared UI layer so both the Android TV and web clients use it.
10. Right-to-left is not required for these three languages.

## Invariants
- **UI language is a per-Jellyfin-user setting owned by Jellystructure** — never device-locale-driven.
- A **profile switch re-localizes** the whole interface immediately.
- Missing keys **fall back to English**, never show a raw key to the user.
- **Media metadata is not translated** by this phase (only interface chrome).

## Out of scope
- Translating media titles/overviews/genres (metadata-language concern).
- Locale-aware date/number formatting beyond what the three languages need; RTL.
- A full translation-management workflow (the table is edited in-repo).

## Design reference
`design/ravilo/` (`ravilo-i18n.js` + the wired `ravilo-app.js`) implements en/da/fo with per-profile
`lang` (Eyð → fo, Olivar → en, Marjun → da) and instant re-localization on profile switch. The
`uiLanguage` per-user control belongs on the Jellystructure Ravilo config screen (R16).
