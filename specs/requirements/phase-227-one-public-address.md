# Phase 227 — One public address, configured once

> Phase 218's dev review put `public_url` **inside the `chromecast` block** and called it out as *"the
> first shipped instance of the explicit reach address pattern"*. It is the wrong home: how
> Jellystructure is reached from outside is a property of the installation, not of one optional feature,
> and the next thing that needs it (165's reach address, a future share link, a webhook callback) would
> either duplicate the key or read it out of a feature it does not depend on. This phase promotes it to a
> single field in Settings → Connections, deletes the Chromecast card's own address field, and makes every
> externally-reachable URL in the product a derivation of the one value.

## Status

`Planned` — written 2026-09-17, **dev-reviewed 2026-09-17 against `main` `8873cea7`** (see §Dev review at
the bottom: FR-227-1 no longer fails the config load; 165's reach address is a different address and stays
separate). Supersedes **FR-218-5's storage location** and the
*Your receiver address* field of FR-218-4; 218's reachability check, its three card states and FR-218-7's
honest verification are unchanged. Pairs with **226** (the registration steps that consume the value).

**Numbering:** verified against `main` on 2026-09-17 — admin taken through **226** (ours, same day).
Next free: 228.

Design: built into `design/app/settings.html` — the `#pub-field` field in the Connections card, the
Chromecast card's address field removed, and `#cc-fv-url` derived.

## Current state

- `ChromecastConfig.public_url` (218 FR-218-2/5) is the only place the installation's external address
  lives. A deployment with Chromecast disabled has nowhere to put it.
- The Chromecast card renders *Your receiver address* — `public_url` + `/cast/` — with the reachability
  badge and a Copy, and 226's step 3 renders the same string again a few centimetres below it.
- The card pre-fills the field from the page's origin as a saveable default, and the backend's check never
  trusts the browser (218 dev review). That behaviour is right and moves with the field.

## Functional requirements

**FR-227-1 — One config key, at the root.** `public_url: String = ""` on `AppConfig` (not inside
`chromecast`). Normalised on read: trimmed, trailing slashes stripped. **Rejected on write** (the Settings
save route answers 400 with the reason) unless it parses as an absolute `https://` URL with a host and no
path, query or fragment — the field is an origin, not a URL. A stored value that fails the same rule (a
hand-edited file) does **not** fail the load: the value is kept, the field renders it marked invalid, and
every consumer treats it as unset. *(Corrected in dev review — a config load that refuses one string takes
the whole backend down with it; 218's `effectiveMaxSessions()` shape is the precedent.)*

**FR-227-2 — Migration from `chromecast.public_url`.** On load, a `chromecast.public_url` with no root
`public_url` is adopted as the root value and the old key is dropped on the next write. Both present and
disagreeing: the root wins and the load logs it once. No admin action, no prompt.

**FR-227-3 — One field in Settings → Connections.** Below the Jellyfin row, above the Chromecast card:
label **Public address**, one text input, the reachability badge, and one hint — *How Jellystructure is
reached from outside your network — scheme and host only, no trailing slash* + the check's result + what
uses it. It is **not** gated on Chromecast being enabled; it is an installation property.

**FR-227-4 — Every external URL derives from it.** `publicUrl + "/cast/"` is computed in one place and
used by: 226's *Receiver Application URL* row, the reachability check (which still fetches its own
`/cast/` through the public address and never trusts the browser), and `RaviloConfig.cast.receiver_url`
(218 FR-218-3/11). No second source, no re-derivation from a request's `Host` header — a reverse proxy
makes that a lie.

**FR-227-5 — The Chromecast card loses its address field.** *Your receiver address* is deleted: the value
now appears exactly once, in 226's step 3, labelled with the console's field name. The status list's
first line names the derivation instead of pointing at a field that is no longer there — *Receiver
reachable at your public address + `/cast/`*.

**FR-227-6 — Unset is a first-class state.** With no public address: 226's *Receiver Application URL* row
shows *Set your public address above first* instead of a value, with no Copy; the hint on the field says
nothing that needs reaching from outside can work until it is set; and the Chromecast enable switch stays
operable (a half-configured feature the admin can see is better than a disabled control with no
explanation — 218's off-means-absent rule is about the *phone*, not about the admin's own card).

**FR-227-7 — Nothing else changes.** No new route, no new check, no viewer-facing string, no change to
what Ravilo is told (`cast` capability is still resolved per read and never persisted). Google remains
named only on the Chromecast card.

## Non-goals

- **Deriving the address from the request.** `Host`/`X-Forwarded-Host` behind a reverse proxy is exactly
  the class of guess this field exists to replace.
- **A port or path in the field.** An origin only; anything that needs a path appends its own.
- **Validating that the address belongs to this machine.** The reachability check answers the useful
  question (can something outside reach `/cast/` here) and 218 FR-218-7's honesty rule still applies.
- **Multiple public addresses** (split-horizon DNS, a second domain). One installation, one address.
- **Merging 165's reach address into this key — ever.** ~~It should adopt it~~ — *corrected in dev review:*
  `ingest.jellyfin_reach_url` is where **Jellyfin** reaches jellystructure from *its* network position
  (`http://<lan-ip>:9505`, `AppConfig.kt:43-50`), which is deliberately not the public https origin. Two
  addresses, two questions; they stay separate.

## Acceptance

1. A config with root `public_url` and Chromecast disabled loads, and the field renders with its value.
2. `chromecast.public_url` alone is adopted as the root value; the next config write contains the root key
   and not the nested one; the served `receiver_url` is unchanged across the migration.
3. `http://…`, a value with a path (`https://x/cast/`), and a trailing-slash value all normalise or fail
   per FR-227-1 — `https://x/` becomes `https://x`, `https://x/cast/` is rejected.
4. 226's *Receiver Application URL* row equals `public_url + "/cast/"` byte-for-byte, and there is now
   exactly **one** rendering of that string on the page (the old field is gone).
5. With `public_url` empty: the row shows the set-it-first note with no Copy, the reachability badge is
   absent (not red), and the Chromecast switch still toggles.
6. A reverse-proxied request with a `Host` header different from `public_url` changes nothing anywhere.

## Source references

- `design/app/settings.html` — `#pub-field` / `#pub-input` / `#pub-hint` in the Connections card;
  `#cc-fv-url` (derived); the second fenced preview control (`#pub-preview`, mockup-only) exists so the
  unset state of FR-227-6 stays visible in the design, like the card-state control beside it.
- `specs/requirements/phase-218-chromecast-receiver-and-registration.md` — FR-218-2/3/4/5/11 and the dev
  review's note naming this pattern; `phase-226-chromecast-registration-fields.md` — the consumer.
- Phase **165** — `ingest.jellyfin_reach_url`, the sibling *explicit, never derived* address for a different
  direction (Jellyfin → jellystructure over the LAN); it does **not** adopt this key (see non-goals).

## Open questions — for the dev team

1. ~~**Should the reachability check move to the field?**~~ **Answered 2026-09-17: no.**
   `CastService.checkReceiver(publicUrl)` (`CastService.kt:142`) stays; it takes the root value instead of
   the nested one, and the field displays its result. No new endpoint.
2. ~~**Is a root `public_url` the right key name?**~~ **Answered 2026-09-17: yes, root.** `AppConfig` has no
   `server`/`network` table today (bind address and port are process arguments, not config), so a table
   would be invented for one key.
3. ~~**What should an invalid stored value do?**~~ **Answered 2026-09-17: render it, marked** — folded into
   FR-227-1.

## Dev review (2026-09-17)

Reviewed against `main` at `8873cea7`, alongside **226**. The move is right and small; three corrections.

- **What exists.** `ChromecastConfig.publicUrl` (`AppConfig.kt:127-138`, `@SerialName("public_url")`,
  default `""`) with `receiverUrl()` doing the `trimEnd('/') + "/cast/"` derivation;
  `CastService.capability()` (`:70-74`) and the enrolment path (`:172`) both call it;
  `CastService.checkReceiver(publicUrl)` (`:142`) already refuses a non-https value with a sentence rather
  than an error. Nothing derives an external URL from a request: the only header reads in the codebase are
  `X-Forwarded-For` for the login rate limiter (`AuthRoutes.kt:34`, `TvRoutes.kt:246,416,572`). FR-227-4's
  "never from `Host`" is therefore a rule to keep, not a bug to fix.
- **FR-227-1 must not fail the load.** `ConfigStore` is memory-resident and the whole backend starts from
  it; a load that rejects one malformed string is an outage for every viewer (the same reason 218 clamps
  `max_sessions` instead of refusing it). Validation belongs on the Settings write path, where the admin
  is present to read the 400. Corrected above, and open question 3 is answered by the same rule.
- **FR-227-2's migration needs the old field kept deserialisable.** Keep `ChromecastConfig.publicUrl`
  as a `@Deprecated` field so an existing `[chromecast] public_url` still parses; adopt it into the root
  in `ConfigStore`'s load (root blank → take nested; both set and different → root wins, log once); write
  the root key and omit the nested one on the next save. The 213 precedent (`segment_workers` →
  `job_workers`, `AppConfig.kt:242-250`) simply ignored the retired key because its default was fine; here
  the value is the admin's, so it is carried, not dropped.
- **165's reach address is not a consumer.** `ingest.jellyfin_reach_url` is the LAN address Jellyfin's
  webhook plugin posts to, explicitly *not* the browser's or the public address (`AppConfig.kt:43-49`). The
  first draft's "165 should adopt it" would have pointed Jellyfin at a hairpin through the reverse proxy —
  the exact class of guess this field exists to remove. Struck from the non-goals with the reason.
- **One rendering, one derivation.** After this phase `receiverUrl()` moves off `ChromecastConfig` to a
  single top-level function over `AppConfig.publicUrl`; `capability()`, enrolment, `checkReceiver` and
  226's card row all call it. `RaviloConfig.cast.receiver_url` (`Models.kt:1129`) is unchanged on the wire.
