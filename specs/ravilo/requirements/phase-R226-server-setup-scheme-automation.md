# Phase R226 — Server setup: drop the http/https toggle, infer the scheme

> Requested 2026-09-03, immediately after R225 (server indicator + `DEFAULT_SERVER_URL`) shipped and was
> verified live — the user saw a fresh `ServerSetupScreen` screenshot (a browser session with no saved
> override and no injected default falls through to it) and asked to simplify it: *"let's remove these two
> options and rather have some nice automation on top of it. So it just becomes a single input field and
> when no prefix like http:// or https:// is added, then we automatically add https:// infront of it. If
// the user manually adds http:// when we will use that one instead of https."*

**Status:** Planned — spec only, not yet built.

## 1. What's there today

`ServerSetupScreen.kt` (`ravilo-ui/src/commonMain/.../screens/ServerSetupScreen.kt`) renders two
`SchemeButton`s ("http://" / "https://") above the address field, backed by a `useHttps: Boolean` toggle
defaulting to `false` (**http**). The full URL is built as `(if (useHttps) "https://" else "http://") + host`
unless `host` itself already starts with a scheme, in which case the typed scheme wins outright. This is
one extra decision (and one extra D-pad row to navigate through) for something that has an obviously correct
default: virtually every real deployment — including both this project's own demo stack and the household
one — serves Ravilo's backend over `https://`(behind Caddy/a reverse proxy) or, on a bare LAN IP, doesn't
care because the field also accepts an explicit scheme already. The toggle exists but nothing in the
household setup actually depends on **http** being the default.

## 2. New behavior

### FR-R226-1: Single field, no scheme selector

Remove both `SchemeButton`s and the `useHttps` state entirely. `ServerSetupScreen` becomes: title,
subtitle, one address `TextField`, one `Connect` button — matching the screenshot's request directly.

### FR-R226-2: Scheme inference

Given the raw typed `host` string:

- If it already starts with `http://` or `https://` (case-insensitive, matching how browsers/URL fields
  treat schemes) — **use exactly what was typed**, unchanged. A person who explicitly types `http://` gets
  plain HTTP, not an auto-upgraded `https://`; this is the one deliberate escape hatch for a LAN box with
  no TLS in front of it.
- Otherwise — **prefix `https://`**. This flips today's default (was `http://` unless overridden); the
  reasoning above (virtually every real deployment is behind TLS) is why `https://` is now the *implicit*
  choice rather than a toggle a person has to remember to flip.

No new state beyond the existing `host` string — the scheme is derived, never stored separately. Exactly
the same derivation `ServerSetupScreen` already does for the "typed a full URL" case today; this phase only
removes the toggle-driven branch and hardcodes its replacement to `https://`.

### FR-R226-3: `canConnect` simplifies

Today `canConnect` re-checks that the derived `fullUrl` starts with `http://`/`https://` — trivially true
now that the scheme is always either exactly what was typed or an inferred `https://`. Replace it with a
plain non-blank check on the trimmed `host` — the only real failure mode (an empty field) is unchanged.

### FR-R226-4: Focus order

`httpFR`/`httpsFR` and their D-pad wiring (`onLeft`/`onRight` between the two scheme buttons) are deleted
along with the buttons. D-pad flow becomes: address field → Connect (unchanged shape from today's field →
Connect path, just without the scheme row above it to route around).

## 3. Non-goals

- No change to `RaviloRoot`/`saveBaseUrl`/`raviloBaseUrl` — this phase touches only how `ServerSetupScreen`
  derives the URL it hands to `onUrlSaved`, not how that URL is persisted or later resolved (R225's
  three-tier fallback on web is unaffected).
- No new validation beyond today's (e.g. no host-format checking, no reachability probe before enabling
  Connect) — `LoginScreen`'s existing `UNREACHABLE` error state is still what surfaces a bad address, same
  as before this phase.
- No copy/placeholder changes beyond what's implied by removing the scheme row (title/subtitle/placeholder
  text stay as-is).

## 4. Verification

- Typing `demo.jellystructure.jebster.net` and connecting resolves to `https://demo.jellystructure.jebster.net`.
- Typing `http://192.168.1.50:9505` and connecting resolves to exactly that — the explicit `http://` is
  preserved, not upgraded.
- Typing `https://example.com` behaves identically to today (no functional change for an already-schemed
  input).
- Compiles clean across `:ravilo-ui:compileKotlinWasmJs` and `:ravilo-ui:compileDebugKotlinAndroid` (the two
  targets that actually render this screen — TV, phone, and web all share it via `commonMain`).
