# A phone without Google Play Services casts with Ravilo's own sender
Task: DEB-5247

> Owner, 2026-11-21: a household guest *"couldn't use chromecast … but me and meidam [cast] onto the same chromecast.
> Please check why."* The investigation on DEB-5247 found that the guest's phone had no Google Play Services. On
> 2026-11-21 the owner accepted the recommendation that such a phone casts on its own with Ravilo's own Cast sender.

## Scope

Ravilo for Android **phones** (not TVs) on which Google's Cast library cannot run. Client only: no server change, no
wire change. It builds on R330 (`:ravilo-castv2`, the Mac and Linux sender), R378 (the Android TV's `NsdManager`
discovery and `:ravilo-castv2` relay) and R370 (the reach report and the relay). It does not amend those specs. A phone
with Play Services keeps Google's Cast library exactly as today.

## What happens today

- Android casting is `CastSenderAndroid` over `play-services-cast-framework`, and discovery is Google's MediaRouter
  provider (`CastRoutesAndroid`). Both need Google Play Services.
- `rememberCastSender` builds the sender as `runCatching { CastSenderAndroid(ctx) }.getOrNull()`. Without Play Services,
  `CastContext.getSharedInstance` fails, so the phone has **no Chromecast sender at all**: only `ScreenSender` (Ravilo
  apps on TVs) is left. Its reach report is empty, so the server logs `Playback sessions: <phone> sees 0 Cast devices`.
- The *Play on…* sheet still lists the Chromecast, because the server knows it from other apps in the house. Choosing it
  therefore goes **by relay** (R370 owner decision 1): it works only when another Ravilo app on the same network is
  online and sees the device. On 2026-11-21 that relay failed three times out of three (DEB-5249).
- Everything a Play-Services-free cast needs already exists in the tree: `:ravilo-castv2` (TLS to 8009, LAUNCH, LOAD,
  media and receiver namespaces) runs on Android today in `AndroidTvCastRelay`, which also discovers `_googlecast._tcp`
  with `NsdManager`. `CastSenderDesktop` is a complete `CastSender` (link, status, queue, commands, volume, leave) over
  `:ravilo-castv2`.

## Requirements

### FR-1 — The phone chooses its sender once, at start-up

On an Android phone (`!RaviloAppContext.isTelevision`), the app uses Google's Cast library when it can start, and
Ravilo's own sender (FR-2) when it cannot. *Cannot* means `GoogleApiAvailability.isGooglePlayServicesAvailable(ctx)` is
not `SUCCESS`, **or** `CastContext.getSharedInstance` throws. The choice is made once per process and logged once under
`RaviloSessions` (`cast sender: google` / `cast sender: own (<reason>)`). Nothing in the interface names it. A phone
never runs both senders.

### FR-2 — Ravilo's own sender on a phone does what the desktop's does

The own sender is the desktop's `:ravilo-castv2` `CastSender`, made available to Android rather than copied. Over it the
phone gets everything a Linux app gets from R330: launching the Ravilo receiver, a film or episode LOAD with its hand-off,
music to a speaker, the remote (play/pause, seek, next, subtitles, audio), the receiver's status, volume, leave, and
reconnecting to the device it was on. Behaviour that only Google's library gives is out of scope (see *Non-goals*).

### FR-3 — The phone finds Cast devices with Android's own discovery

With the own sender, the phone discovers `_googlecast._tcp` with `NsdManager` the way R378 FR-R378-1 does on a TV: only
while the app is in the foreground, IPv4 first, TXT `id`/`fn`/`md`/`ca` read as the desktop reads them, and a device
listed only once Ravilo's receiver app is available there (R330 D2). Groups are left out, as on the desktop. The found
devices are the phone's Cast routes in the *Play on…* sheet, under the same ids Google's library would give them (the
TXT `id` is `CastDevice.deviceId`, R370 review item 3), so a place keeps one id whichever sender saw it.

### FR-4 — The phone reports what it sees and can relay

The found devices go to the server through the existing reach report (`CastSeenDevice`, same shape). The server then
logs `sees N Cast devices` for the phone, and a cast the phone starts itself is **its own LOAD** (`callerSees`), not a
relay. Like any phone it is also a relay candidate for other people's starts (`RELAY_PLATFORMS` already holds `phone`),
and a relay through it uses the own sender's LAUNCH, LOAD and leave.

### FR-5 — Nothing changes for a phone with Play Services

A phone where Google's library starts behaves byte for byte as before: same sender, same discovery, same reach report.

## Non-goals

- **Group control** (R371 room ops). It is the Cast SDK's routing controller on Android. A phone on the own sender
  declares no `group_control`, as the desktop does, so the server sends room ops elsewhere.
- **The system output switcher, the Play services Cast notification and lock-screen card, and Cast Connect** (R266,
  which launches the Android TV app as a receiver). These are Google's.
- **An F-Droid build** that leaves Google's library out of the APK. This spec keeps one APK and chooses at runtime. The
  F-Droid question stays in `specs/research-reports/google-free-android-and-foss-cast-sender-2026-09-19.md`.
- **Fixing the relay.** That is DEB-5249 against R370. This spec makes such a phone stop needing the relay.

## Acceptance

1. On a phone running the own sender (a phone without Play Services, or the debug switch in *Tests*), with no other
   Ravilo app online in the house, the server logs `sees N Cast devices` (N ≥ 1) for the phone, and an episode cast from
   it to a household Chromecast plays, under that phone's user.
2. From that phone the cast pauses, resumes, seeks, changes subtitles and stops, and its volume moves.
3. Music cast from that phone to a speaker plays, and *Next* moves the speaker on.
4. On a phone with Play Services nothing changes: the log says `cast sender: google`, and casting works as before.
5. In the field, the next cast from the household phone that started this logs `sees N Cast devices` with N ≥ 1 for it,
   read from the server log with no device.

## Tests

- The FR-1 choice as a pure function of (`availability`, `contextStarted`) → `google` / `own(reason)`, unit-tested in
  `ravilo-ui` common or Android unit code.
- The desktop's sender tests stay green after it is shared with Android (FR-2), run as they are today (`:ravilo-ui:desktopTest`).
- `tvCastSeenOf`'s rules (groups out, own device out, receiver availability required) hold for a phone's discovery: one
  shared function with a phone case, so a phone never lists itself or a group.
- **A debug-build switch** that forces the own sender on a phone with Play Services, so QA can run acceptance 1–3 on the
  Pixel 7 without disabling Google Play Services there. Release builds ignore it.

## Open questions

1. **microG.** On a phone with microG, `isGooglePlayServicesAvailable` may answer `SUCCESS` while microG's Cast support
   finds nothing. Lean: out of this round. If a microG phone turns up with `sees 0 Cast devices`, add microG detection
   then.
2. **Local-network permissions.** Android 13+ phones may want `NEARBY_WIFI_DEVICES` or a multicast lock for `NsdManager`
   where a TV did not. Lean: whatever `AndroidTvCastRelay` needs, plus a multicast lock if discovery is empty on the
   Pixel 7 with the switch on. Asked at runtime only if the platform refuses without it.
