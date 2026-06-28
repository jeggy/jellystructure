# R130 — Title-logo with readable text fallback (hero + detail) (FR-RV-LF1)

> Builds on the home hero carousel (clearlogo overlay), the R85 image proxy (`/api/tv/image/{id}/{type}`),
> and the R110 "readable on any background" principle from subtitles. UI/DTO only — no new image pipeline.

---

## Problem

A title's **clearlogo** is shown as the headline on the home **hero carousel** and should be on the
**Movie/Series detail** hero too. But:

1. **The hero only fell back to text when `logoUrl` was *null*** — yet the server *always* sends a logo
   **proxy URL** (`JellyfinImageUrl.logo(jellyfinId)`), which **404s** for titles that have no clearlogo.
   So the common "no logo" case wasn't null — the image just failed to load, leaving a **blank space**
   where the title should be.
2. **Detail screens showed the title as plain white text only** — no logo at all, and no readability
   treatment, so on a bright/busy backdrop it could be hard to read.

## Requirements

1. **Logo-or-text, on null *and* load failure.** A shared composable shows the clearlogo when it loads,
   and falls back to the **title as text** when `logoUrl` is null **or the image fails to load** (404/empty).
   Used by the hero carousel and both detail heroes, so a missing logo never leaves a gap.
2. **Readable over any backdrop.** The fallback text carries a **dark blurred halo** (`Shadow`, ~16px blur,
   small offset) so it stays legible over bright or busy images — the hero/detail analogue of the R110
   subtitle outline. (Both heroes already darken the lower third with a scrim gradient; the halo covers
   anything bleeding through.)
3. **Detail screens gain the logo.** Movie/Series detail now show the clearlogo when present, falling back
   to the styled title — matching the hero. This needs the logo URL on the detail DTOs.

## Data / transport

- `MovieDetail`/`SeriesDetail` gain `@SerialName("logo_url") logoUrl: String? = null`; `DetailService`
  populates it from `item.jellyfinId?.let { JellyfinImageUrl.logo(it) }` — the same proxy URL the hero uses
  (always set when the item has a Jellyfin id; the app decides at load time whether to show it). No new
  Jellyfin round-trip; the proxy + R129 disk cache handle the image.

## Scope / invariants

- One shared `TitleLogoOrText(logoUrl, title, logoModifier, …)` composable in `ravilo-ui` `components/` —
  no duplicated logo/fallback logic across hero + the two detail screens.
- Load-failure detection via Coil `AsyncImage` `onState` → `AsyncImagePainter.State.Error`; the failure flag
  resets per `logoUrl` so a reused slot (hero slide advance) re-tries the new logo.
- Title typography unchanged (Space Grotesk, 34sp, bold) — only the halo is added on the fallback path.

## Out of scope

- Channel-rail / tile logos (ChannelCard already handles its own logo/watermark).
- A true multi-pass text outline (the single blurred halo + existing scrim is sufficient; revisit only if a
  backdrop defeats it).

## Files

- `ravilo-ui/.../components/TitleLogo.kt` (new — `TitleLogoOrText`), `components/HeroCarousel.kt`,
  `screens/MovieDetailScreen.kt`, `screens/SeriesDetailScreen.kt`.
- `shared/.../tv/Models.kt` (`MovieDetail`/`SeriesDetail` `logoUrl`), `tv/DetailService.kt` (populate it).
