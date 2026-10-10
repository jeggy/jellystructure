# Phase R378 — An Android TV relays a speaker launch

> Owner, 2026-10-05: *"I think TVs are a good candidate for being relays"* — and *"Please do not reject it. Only reject
> if TVs being a relay isn't actually possible."*

## Status

`⚠ Partial` — built 2026-10-05, not device-tested (see *Build notes*). Written 2026-10-05 (dev-authored) from the owner's
ask, checked against `main` `e0ed5f9b`. Not dev-reviewed.
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

## Build notes (2026-10-05)

Built on the Android TV build only; a phone, a computer and the web are unchanged.

- **`:ravilo-castv2` on Android (open question 1).** `ravilo-ui`'s `androidMain` depends on the module's JVM variant
  (`implementation(projects.raviloCastv2)`, JmDNS excluded). It compiles and runs as is: the TLS transport
  (`javax.net.ssl`, `java.net.Socket`) is plain Java that Android has; no Android actual was needed.
- **Discovery (FR-R378-1)** — `AndroidTvCastRelay` (`seams/TvCastRelayAndroid.kt`): `NsdManager.discoverServices`
  for `_googlecast._tcp` between `discover(appId, on = true)` and `false`, driven from `RaviloApp` by R293's
  `rememberAppOnScreen()` and the server's receiver app id. Resolving: Android 14+ `registerServiceInfoCallback` (every
  address, waiting up to 5 s for an IPv4 one); below 14 the deprecated `resolveService`, one at a time behind a mutex,
  retried once on `FAILURE_ALREADY_ACTIVE`. The TXT record goes through the desktop's own `CastDevice.fromTxt`; the
  address through the new `preferredCastHost` (IPv4 first, then a global or scoped IPv6, never a bare link-local one);
  the TV's own receiver is dropped by `isOwnCastDevice` (its `fn` = `Settings.Global.DEVICE_NAME`, Android 7.1+, or one
  of the TV's own addresses). Each device is asked once whether our receiver runs there (`GET_APP_AVAILABILITY`, R330
  D2, as the Mac does) — the phone's route selector filters the same way. All three rules are in `ravilo-castv2`'s
  `CastDiscoveryRules.kt`. No permission added (NsdManager needs only `INTERNET`, already declared).
- **The reach report (FR-R378-2)** — `tvCastSeenOf` maps the found devices to `CastSeenDevice` (TXT `id` = the Cast
  SDK's `CastDevice.deviceId`, the key the phone and the Mac report; `speaker` without a video output, else `display`;
  no group, not our own, only *available*). On a TV, `RaviloApp` reports that list instead of the Cast SDK's (always
  empty there) routes.
- **The relay (FR-R378-3/-4)** — on a TV, `cast_relay_load` goes to `AndroidTvCastRelay.relay` instead of
  `CastController.relayLoad` (no route select, no `ActiveCastSender` link, so no mini bar, remote, hand-off or
  `MusicCast` state on the TV): open TLS to the device, `launchOrJoin()` the receiver app id, send the LOAD the Mac
  sends (`castLoadMedia` moved from `CastSenderDesktop` to common `seams/CastLoadMedia.kt`; `castRelayFrames` adds
  R359's parts), wait up to 15 s for a media status that is not `IDLE`, then `close()` (a CONNECTION `CLOSE`: the
  receiver plays on). One relay at a time (a second is refused and logged); the whole conversation is capped at 45 s.
  A TV playing its own film still relays — only the network is used. A failure is logged; the server's answer is
  unchanged (it already said *Started*, as for a phone's relay).
- **Room ops (FR-R378-5)** — already true: `eventsFeaturesFor(isTv = true, …)` never declares `group_control`, even
  where `platformGroupController()` exists (MediaRouter2 on Android 11+). Now covered by a test.
- **Logging** — logcat tag `RaviloSessions`, prefix `R378:` (looking for Cast devices, found …, cannot run the
  receiver, relay launch on …, relay on …: LOAD sent … / did not start / failed).
- **Tests** — `CastDiscoveryRulesTest` (`:ravilo-castv2:jvmTest`: TXT bytes, IPv4 first, scoped/bare link-local, own
  device by name and by address); `TvCastSeenTest` (`:ravilo-ui:testDebugUnitTest`: ids and kinds, own device and
  groups left out, only available devices, one row per device); `TvRelayTest` (common: the relay LOAD carries the
  hand-off once as customData with the session id, a film's HLS card, a long queue in parts, the TV declares no
  `group_control`). `chooseRelayApp` picking a TV was already on `main`.
- **Open question 2** — no preference added: the most recently reporting app wins, as before.
- **Only the TV can confirm:** that NsdManager on the BRAVIA finds the speakers and the hub (acceptance 1), that the
  TV's own receiver is left out, and acceptance 2–3.

## Triage (2026-10-09, against `main` `12bffb29`)

- **Code: nothing left.** **Owed on Stue TV:** NsdManager finds the speakers and the hub, the TV's own receiver is
  left out, and a relayed launch plays (acceptance 1–3). Note: the Køkken Hub's Cast port 8009 did not answer on
  2026-10-09 (needs a reboot), so test it with the Stue and Gæsteværelse speakers first.

## Live, 2026-10-09 (Soveværelse TV, debug build 1.50-119)

- **Discovery — passed:** logcat `RaviloSessions` *R378: found Gæsteværelse [Nest Wifi point] at 10.10.11.188
  (speaker)*; the server logged *67cf23b9… sees 4 Cast devices: Gæsteværelse (speaker), Stue (speaker), Køkken hub
  (display), Stue TV (display)* — the TV's **own** receiver (Soveværelse TV) left out. The Køkken Hub's port 8009
  answers again.
- **Found: no relay was ever chosen inside the house.** The server sees each LAN device's own private address since
  the household's DNAT (Pixel 192.168.10.23, Soveværelse TV 192.168.11.40 — stand-ins), and `isNearby` (236 FR-236-6) needed an
  exact IPv4 match, so every Cast row read `no_relay` for a requester without its own discovery (web, Mac).
  **Fix (owner-approved via main, 2026-10-09):** two RFC 1918 IPv4 addresses count as nearby — a private address only
  reaches the server from inside the household network, a remote viewer arrives with a public one; every other pair
  keeps the public-address rule (`ScreenNetwork.kt`, `ScreenNetworkTest.twoPrivateIpv4sAreOneHousehold_r378`: a
  private and a public address are not nearby; CGNAT 100.64/10 is not private).
- **Found: a debug TV build relayed the wrong receiver.** The TV asked each device about the server's receiver id
  (`134AA282`) but launched with the sender's id, which on a debug build is the development Cast app (`EA91BAE4`, R266's,
  for Cast Connect into the debug TV app) — `APP_UNAVAILABLE` on the Køkken hub and Gæsteværelse, so the relay ended
  *the receiver did not start*. **Fix:** the TV relays with the server's id (`RaviloApp.kt`, `cast_relay_load`); a
  release build is unchanged (both ids are the same there).
- **Acceptance (relayed launch) — passed after both fixes** (debug 1.50-131, dev stack v1.50-130): a start on the
  Køkken hub from a requester that cannot see it (the fedora desktop app's sign-in, by API) → *relay 67cf23b9…* (the
  TV) → TV log *relay on Køkken hub: LOAD sent (1 KB, 0 parts); buffering; left it playing* → the session played
  (30 s in) and *Stop* from the server ended it. **Small finding:** once the receiver reports, the session's place
  reads *Chromecast* (the receiver's enrolment name) instead of *Køkken hub*.
