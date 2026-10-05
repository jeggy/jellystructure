# Phase R378 — An Android TV relays a speaker launch

> Owner, 2026-10-05: *"I think TVs are a good candidate for being relays"* — and *"Please do not reject it. Only reject
> if TVs being a relay isn't actually possible."*

## Status

`Planned` — written 2026-10-05 (dev-authored) from the owner's ask, checked against `main` `e0ed5f9b`. Not dev-reviewed.
Server half already on `main` (`e0ed5f9b`: `RELAY_PLATFORMS` gains `tv`). Client: the Android TV build. Amends R370's
owner decision 1 (which apps relay).

## What happens today

- A relay is an app that sees a Cast device and launches the receiver on it for someone else (R370 owner decision 1).
  The server picks one from the apps that report what they see (`PlaybackReach.report`, `chooseRelayApp`).
- The Ravilo TV app reports every few seconds — and always **0 Cast devices** (Stue TV `666cf820…`, 2026-10-05 23:27).
- **Why:** Google Play services on Android TV registers **no media route provider** (`dumpsys media_router` on the
  BRAVIA: `mProviders.size()=0`, `<no providers>`; on the Pixel 9 Pro: `CastRemoteDisplayProviderService`). The Cast
  sender SDK's discovery (`CastContext` → MediaRouter) therefore finds nothing on a TV, ever. A TV is a receiver to
  Google.
- The Mac and Linux apps cast without Google's SDK: `ravilo-castv2` (our own CASTV2 client: TLS socket, protobuf
  framing, receiver/media/heartbeat namespaces, `CastSession`) with Bonjour discovery (`Bonjour.swift`) or Avahi.

## Requirements

### FR-R378-1 — The TV finds Cast devices itself

On a TV (`RaviloAppContext.isTelevision`), the app discovers `_googlecast._tcp` with Android's `NsdManager` while it is
on screen (R293's rule), resolving each service over IPv4 first (the Mac's lesson, `cc6a4bec`), and reads the TXT
record (`id`, `fn`, `md`, `ca`) as the desktop does. Its own Cast device (the TV's built-in receiver, matched by its
`fn` = the TV's `Settings.Global.DEVICE_NAME`, or by its address) is never listed.

### FR-R378-2 — The TV reports what it sees

The found devices go to the server through the existing reach report (`SessionRemote.reportCastDevices`, the same
`CastSeenDevice` shape, `kind` from the TXT `ca` bits / model as the desktop decides it). Nothing new on the wire.

### FR-R378-3 — The TV relays with `ravilo-castv2`

A `cast_relay_load` on a TV launches the receiver on that device with `ravilo-castv2` (connect, `LAUNCH` the Ravilo
receiver app id, `LOAD` the relay's hand-off as the desktop's relay does), then leaves (`leave()` semantics: the
receiver keeps playing, nothing is mirrored or handed back). The TV never shows any of it — no glyph, no sheet, no toast
(R360 amendment: a TV never casts in its interface).

### FR-R378-4 — One relay at a time, never while the TV plays

A TV that is playing a film or episode itself is a fine relay (relaying uses the network, not the screen), but it
never holds a lasting link: it relays and leaves. A relay that fails says so to the server as today (`Unreachable`).

### FR-R378-5 — Room ops

A TV does not take R371 room ops (grouping is the Cast SDK's routing controller on Android, absent on a TV); it declares
no `group_control`, so the server sends those to a phone, as for the desktop.

## Non-goals

- A cast button, *Play on…* sheet or any casting UI on a TV.
- Group control from a TV.

## Acceptance

1. With the Ravilo TV app open on Stue TV, the server logs *sees N Cast devices* for the TV with the speakers and the
   hub (not Stue TV itself).
2. With no phone or computer in the house holding the app open, a start on the Stue speaker from the web app is relayed
   by the TV, the speaker plays, and the session shows *Playing on Stue*.
3. The TV's own film keeps playing throughout acceptance 2.

## Tests

- TXT parsing and IPv4-first address choice (shared with the desktop's parser where possible).
- The TV's own device is excluded from the report.
- `chooseRelayApp` picks a TV (already on `main`).

## Open questions

1. Does `ravilo-castv2` need anything for Android (it runs on the JVM desktop today)? Lean: it is common code; only the
   socket factory may need an Android actual.
2. Should a TV be preferred over a phone as the relay (always on, always on the network)? Lean: no preference; the most
   recently reporting app wins, as today.
