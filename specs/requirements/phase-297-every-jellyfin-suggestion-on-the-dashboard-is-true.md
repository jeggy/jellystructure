# Phase 297 — Every Jellyfin suggestion on the Dashboard is true and can be acted on

> Written 2026-10-02 from an owner request: *"fix all of this, so we only get good and correct suggestions on our
> dashboard."* Every Jellyfin and host row on the live Dashboard was checked against the server it describes.

## Status

`✓ Built` 2026-10-02, not deployed (see Build notes). Written 2026-10-02 (dev-authored), against `main` `1fe3a178`.
Number verified free (admin specs top at 296). Not dev-reviewed.

**Amends** phase 212 FR-212-4 rows (a), (b), (c), (e); phase 242 FR-242-2; phase 244 FR-244-1, FR-244-4 and
FR-244-8; phase 246 FR-246-1's cost sentence and FR-246-12; phase 285's grouping of advisor rows. Builds on 296
(LUFS), which is not repeated here.

## How each row was checked (2026-10-02, Jellyfin 12.1.0)

- **The Jellyfin web page that the row sends you to.** The library editor in the shipped web bundle
  (`jellyfin-web`), and its English string table.
- **The live values.** `GET /Library/VirtualFolders`, `/System/Configuration`, `/System/Configuration/network`,
  `/Sessions` and `/Plugins`.
- **What Jellyfin actually does.** Its log, its scheduled tasks and its database.
- **What Ravilo does.** The code.

The host rows (scheduler, `max_sectors_kb`, read-ahead, swappiness) were re-measured and match the host. They are
unchanged, as are the four information rows other than the pending restart.

| Row | Finding | Change |
|---|---|---|
| Chapter image extraction is on, on rotational storage | The cost is wrong. Jellyfin's log shows one frame grab per chapter mark (about 2 s each, `ChapterManager: Extracting chapter image`), not a read of the whole file. It runs once per new title. | FR-297-1 |
| Chapter images extract during the library scan itself | True. The nightly *Chapter images* task runs at 02:00. It is a choice, though, not a fault. | FR-297-1 |
| Trickplay image extraction is on, on rotational storage | True, but it says the work is "all CPU if Jellyfin has no hardware acceleration selected". Trickplay has its own *Enable hardware decoding* switch (Dashboard → Playback → Trickplay), which is off here, so the work is CPU-only even with NVENC selected. The useful change, *Only generate images from key frames* plus hardware decoding, is never suggested. | FR-297-2, FR-297-3 |
| Jellyfin fetches its own metadata for a library jellystructure manages | Cannot be acted on, and not true. 12.1's library editor has no such checkbox (no `InternetProvider` string exists), and it saves `EnableInternetProviders: true` on every save. What decides fetching is each type's *Metadata downloaders* and *Image fetchers*, which 242 FR-242-3 already checks and which are empty for both libraries. | FR-297-4 |
| "During the scan" is on while trickplay itself is off | True, but harmless: the flag does nothing. "A mistake either way" overstates it. | FR-297-5 |
| Known proxies is set, but this server still classifies a public caller as in-network | The conclusion is true today, for a reason the row cannot see. Known proxies is `172.28.0.17` (now another container); Caddy was recreated on 09-30 and is `172.28.0.27`, which is what every Jellyfin session shows. The probe behind the row cannot tell this from a correct setup: 244's own open question 1 records it firing on a correctly configured server, because a probe sent from inside the network is always in-network. | FR-297-6 |
| Jellyfin reports a pending restart (Moonbase, Moonbase) | True; the name is repeated because `/Plugins` lists the pending version twice. | FR-297-7 |
| every grouped row | The path names only the first library (*Dashboard → Libraries → Film → Manage library* on a row for three). | FR-297-8 |

## Requirements

**FR-297-1 — Chapter images are information, and say what they cost.** Rows (a) and (b) become `info`.
- **(a)'s cost:** at each chapter mark of every new title, Jellyfin seeks and grabs one frame. That is a short read
  per chapter, not a read of the whole file, and it happens once per title.
- **(a)'s trade-off:** unchanged. Ravilo shows no chapter images, and Jellyfin's own apps use them for scene
  selection.
- **(b)'s recommendation:** says that turning it off moves the work to the nightly *Chapter images* task, with
  Jellyfin's own reason (the scan completes faster). Its trade-off is unchanged.

**FR-297-2 — Trickplay's cost is read from the trickplay settings.** The server-wide `GET /System/Configuration`
is read for `TrickplayOptions.EnableHwAcceleration` and `.EnableKeyFrameOnlyExtraction` (nullable, 242 FR-242-4).
When any library has trickplay on and either is an explicit `false`, one server-wide `warning` names what to tick
on **Dashboard → Playback → Trickplay**:
- **"Only generate images from key frames"**, whenever it is off. Jellyfin's own words for the gain are
  "significantly faster processing", and for the loss "less accurate timing".
- **"Enable hardware decoding"**, only when an accelerator is selected (`HardwareAccelerationType` not `none`).
  With none selected, 246's `hwaccel_none` finding already covers it, and this row says so instead of suggesting
  a switch that does nothing.

Current value: both switches, plus the libraries with trickplay on. Trade-off: preview timing snaps to key frames,
and hardware decoding shares the GPU with transcodes. Jellyfin falls back to software for a codec the GPU cannot
decode. Silent when both are on, or when no library has trickplay on.

**FR-297-3 — The per-library trickplay row is information.** Row (c) becomes `info`.
- **Cost:** each new title is read in full, once, to build its previews.
- **Decode sentence:** says it is on the CPU unless the trickplay page's own *Enable hardware decoding* is on (the
  stale "if no hardware acceleration is selected" sentence goes).
- **Recommendation and trade-off:** keep their current sense. Ravilo never shows previews, and Jellyfin's own apps
  (and Jellyfin-based ones) do.

**FR-297-4 — The *Enable internet providers* finding is withdrawn.** FR-242-2 is deleted, along with the model
field only it read. 242 FR-242-3's per-type checks are the finding for this question.

**FR-297-5 — A setting that does nothing is information.** Row (e) becomes `info`. Its trade-off says the flag is
inert while trickplay is off, so unticking it only tidies the page.

**FR-297-6 — Known proxies is judged by what Jellyfin attributes, not by a probe.** This replaces FR-244-1's second
signal and the `known_proxies_unconfirmed` row. It implements 244's open question 4, with that question's caveat
handled.

- **The evidence:** `GET /Sessions?activeWithinSeconds=86400` (read-only), using each session's `DeviceId` and
  `RemoteEndPoint` (any `::ffff:` prefix stripped).
- **When it fires:** two or more devices, every one attributed to a single **private** address A (RFC 1918,
  loopback, link-local, unique-local). Then Jellyfin is not seeing callers, it is seeing one hop. One device,
  differing addresses, or any public address is silence.
- **The window:** 24 hours, not longer. A proxy that is re-addressed while Jellyfin keeps running changes A (the
  household's sessions show `.31` before 09-30 09:58 and `.27` after), and a longer window would hide that.
- **Shape:** `warning`, id `known_proxies_ignored`, kept as `recheck_exposure`.
- **Current value:** *Known proxies: … · in the last 24 hours N devices were all attributed to A*.
- **Cost, three cases:**
  - **A is not listed and every entry is an address:** "A is not in Known proxies". If A is the reverse proxy,
    Jellyfin ignores the forwarded address and counts every caller as in-network, with 244's consequence.
  - **A is listed:** the proxy is trusted but is not passing the caller's address on (no `X-Forwarded-For`).
  - **An entry is a hostname:** Jellyfin resolves it when it starts, so a proxy re-addressed since then is still
    the old address to Jellyfin.
- **Recommendation:** set Known proxies to the reverse proxy, and say why the value goes stale: Docker gives a
  recreated container a new address. So either pin the proxy's address in its compose file, or use its hostname
  and restart Jellyfin after the proxy is recreated. Jellyfin's help text says a restart is needed after saving.
  Still no CIDR (244).
- **What is removed:** the probe (`GET /System/Endpoint` with a spoofed `X-Forwarded-For`) and its model.
- **Shared consumers:** `/health/full` (FR-244-8) and the Re-check button read the same rule.

The row is a Jellyfin setting, so the Dashboard files it under **Jellyfin**, not *the host*.

**FR-297-7 — A pending restart names each plugin once.** The plugins with status `Restart` are listed by distinct
name and version (*Moonbase 2.3.0.0*).

**FR-297-8 — A grouped row's path names no single library.** When one finding covers several libraries, the
Dashboard's path reads *Dashboard → Libraries → (each library listed) → Manage library*. With one library, its name
stays.

**FR-297-9 — 246's `hwaccel_none` cost is corrected.** Trickplay and chapter-image work cannot use the GPU without
an accelerator, but selecting one is not enough for trickplay (FR-297-2). The cost sentence says so.

## Not in scope

- Changing any Jellyfin setting. The advisor stays read-only and suggest-only.
- The design mockups' stand-in Dashboard rows (`design/app/dashboard-data.js`), which belong to the design project.
- `Moonfin 2.2.0.0` shows `Malfunctioned` in `/Plugins`. No finding reads plugin health; that would be a phase of
  its own.

## Acceptance

With the household's settings as of 2026-10-02:

1. No *internet providers* row.
2. Both chapter rows and the per-library trickplay row are information.
3. One trickplay-settings warning names both switches.
4. The Known proxies row names `172.28.0.27`, says it is not in Known proxies (`172.28.0.17`), and sits under
   Jellyfin.
5. The pending restart reads *Moonbase 2.3.0.0* once.
6. Unit tests pin FR-297-2, FR-297-6 (fires; one device; a public address; A listed; a hostname entry) and FR-297-7.

## Build notes

2026-10-02. Not deployed: the live Dashboard keeps the old rows until the next deploy, and the advisor caches for 5
minutes after that.

- **`JellyfinAdvisorService`:**
  - **Per-library rows:** rows (a), (b), (c) and (e) are `info`, with the costs from the table above.
  - **New pure rules,** each unit-tested: `trickplaySettingsFinding` (FR-297-2), `knownProxiesFinding` +
    `singleProxyHop` + `isPrivateAddress` (FR-297-6), `restartPendingFinding` de-duplicated by name + version
    (FR-297-7).
  - **Removed:** FR-242-2's block (FR-297-4). The *Metadata downloaders* row's copy no longer leans on it.
  - **The empty Known-proxies row** now names the observed hop when the sessions show one, instead of saying
    jellystructure cannot see it.
- **`JellyfinClient`:** `getSessions` and `getServerConfiguration` added. `probeEndpointClassification` and
  `EXPOSURE_PROBE_ADDRESS` removed.
- **Models:** `JellyfinSessionInfo`, `JellyfinServerConfiguration` and `JellyfinTrickplayOptions` added
  (nullable). `JellyfinEndpointInfo` and `JellyfinLibraryOptions.enableInternetProviders` removed. These are
  Jellyfin's DTOs, not a Ravilo wire type.
- **`DashboardRoutes`:** `known_proxies_` rows are no longer *the host*. A grouped row's path reads
  *(each library listed)*.
- **`Settings.kt`:** the Re-check button treats `known_proxies_ignored` as still open.

`JellyfinAdvisorServiceTest` is 26/26 (11 new); `compileKotlinWasmJs` is clean.

**What the household should see after a deploy, from the live values read on 2026-10-02:**
- one `warning` *Trickplay previews decode every frame of every file*, naming both switches (NVENC is selected);
- one `warning` *Jellyfin sees every caller as 172.28.0.27*, under Jellyfin;
- the pending restart as *Moonbase 2.3.0.0*;
- chapter, trickplay and leftover-flag rows as information;
- no internet-providers row and no LUFS row (296).
