# Phase 212 — tell the operator which Jellyfin settings are costing them, per library

**Status:** ✓ Built — see `STATUS.md`, which is authoritative. (Header as originally written: Planned)
**Authored:** 2026-09-15 (design-authored with the owner, not dev-reviewed)
**Depends on:** Phase 185 (per-device decode ceilings), Phase 177/R216 (the 0.9 × ceiling predicate),
Phase 201 (MKV health sweep), Phase 143 (Settings → Libraries surface)
**Sibling:** Phase 213 (`prewarm_subtitles` moves to its own serialized job lane) — 213 fixes the
incident that prompted this; 212 is the standing advisory surface and is *not* a fix for it.

---

## 1. Why

On 2026-09-15 a viewer's direct play of *The Patriarch* (19.3 Mbps HEVC DV, well inside every ceiling)
stalled every ~10 s after 30 minutes. The cause was disk starvation on `/mnt/media`: 10 concurrent
Jellyfin ffmpeg subtitle extractions, each doing a full linear read of a 20–80 GB UHD remux, pushing
`sdc` to 80.8 % utilisation, 205 MB/s, `r_await` 74 ms, queue depth 20.8, with I/O pressure `full`
avg10 at **53 %** — over half of all wall time with *every* task blocked on I/O. CPU pressure over the
same window was 0.15 %.

Phase 213 removes the mechanism that generated that load. But the incident exposed a second, standing
problem: **Jellyfin is configured in ways this hardware cannot afford, and nothing tells the operator.**
Several settings on this server generate full-file reads of 4K remuxes off rotational disks as routine
background work, and the only way to discover any of them today is to already know they exist.

The owner independently produced a three-item optimisation report. Tested against the live server:

| Recommendation | Reality |
|---|---|
| Move the transcode directory to tmpfs | **Already done** — `/transcode` is bind-mounted to `/dev/shm` (63 GB tmpfs); Jellyfin's `TranscodingTempPath` agrees |
| Increase the OS read-ahead buffer | **Not done** — every block device sits at the 128 KB default |
| Isolate metadata/cache on SSD | **Already done** — `/config` + `/cache` resolve to `nvme0n1` (`rotational=0`); media is on `sda`/`sdc` (`rotational=1`) |

**Two of three were already correct.** That ratio is the whole design constraint: an advisor that
recites generic best practice is worse than nothing, because two thirds of its output would have been
noise, and the operator would learn to skip it. This phase only ever renders a finding when the live
configuration actually differs from the recommendation — a satisfied recommendation renders *nothing*,
not a green tick, not a "✓ already configured" row.

## 2. What this is not

- **It does not write to Jellyfin.** Suggest-only (owner decision, 2026-09-15). Every finding is a
  read, plus instructions precise enough to act on without searching. `POST /System/Configuration/...`
  is deliberately unused. jellystructure does not own this server's configuration, several findings are
  genuine trade-offs rather than defects, and one of them (read-ahead) is not a Jellyfin setting at all
  — so a mixed "apply" affordance would be lying about what it can do.
- **It is not a health check or an alert.** No badge on the dashboard, no notification, no triage-dock
  entry. It is a surface you visit.
- **It does not score or grade the server.** No "optimisation score", no percentage. A count of open
  findings per library is the only aggregate.
- **It does not touch Ravilo.** No client-visible change of any kind.

## 3. Where it lives

**Per library — Settings → Libraries, inside each library's own card** (owner decision, 2026-09-15).

That placement is right for the majority of findings because they *are* per-library settings and
because the cost of each one is a property of the library's own storage: "enable chapter image
extraction" is cheap on the SSD-backed `Recordings` library and ruinous on `Film`, whose 4K remuxes sit
on a rotational 12.7 TB disk. A server-wide list could not say that.

**Server-wide findings** (the encoding configuration) do not belong to any one library but must not be
dropped. They render in a single **Server-wide** group pinned above the library list on the same
Settings → Libraries tab. *This is a judgment call made while writing the spec, not an owner decision —
flag it at review.* The alternative considered and rejected: repeating each server-wide finding inside
every library card, which would show the same "Throttle Transcodes is off" row six times.

Per the constitution's *frontend renders server-pushed state only*: the evaluation runs server-side and
the frontend renders the resolved finding list verbatim. The admin frontend must not re-implement any
predicate, re-read any Jellyfin endpoint, or decide whether a finding applies.

## 4. Requirements

### FR-212-1 — one read pass, server-side, cached

A new `JellyfinAdvisorService` resolves all findings from four Jellyfin reads plus local facts:

| Source | Used for |
|---|---|
| `GET /System/Configuration/encoding` | transcoding findings |
| `GET /Library/VirtualFolders` | per-library `LibraryOptions` |
| `GET /System/Info` | pending-restart state |
| `GET /Plugins` | plugins awaiting restart |

All four are confirmed working against Jellyfin 10.11.11 with the admin token already in
`config.toml`. The pass runs on demand (opening the tab) and is cached for 5 minutes. It uses the
**BACKGROUND** class of Phase 182's `OutboundHttp` gate: an advisory read may never compete with
playback negotiation for a reserved interactive permit.

A Jellyfin that cannot answer renders "Couldn't reach Jellyfin" for the whole surface — never an empty
finding list, which would read as "everything is fine". (Phase 207's FR-207-3 lesson: a zero that means
"lookup failed" must never be presented as a zero that means "nothing to report".)

### FR-212-2 — a finding renders only when the live value differs

Every finding is a predicate over live configuration. When the predicate does not hold, **nothing
renders for it** — no row, no collapsed entry, no "already configured" acknowledgement. Verified
silence cases on this server, which the implementation must reproduce:

- `TranscodingTempPath` resolves to a `tmpfs` mount ⇒ the transcode-path finding is silent.
- Jellyfin's `/config` and `/cache` resolve to a device with `rotational=0` ⇒ the storage-isolation
  finding is silent.

### FR-212-3 — every finding carries exact steps

A finding is not advice, it is an instruction. Each one states, in this order:

1. **What is set now** — the live value, in the operator's terms.
2. **What it costs here** — tied to this library's own storage and contents, never generic.
3. **Exactly where to change it** — the Jellyfin navigation path, and the field's **exact on-screen
   label** in Jellyfin's own UI, not the API property name.
4. **What to set it to.**
5. **What you lose** — every finding in §5 is a trade-off; the row says so plainly.

The labels below were extracted from this server's own `jellyfin-web` string table
(`en-us-json.*.chunk.js`) rather than written from memory, and must be re-verified on a Jellyfin major
upgrade. The string key is recorded beside each so a future reader can re-extract it.

### FR-212-4 — per-library findings

Path for all of these: **Dashboard → Libraries → _<library>_ → Manage library**.

| # | Live value that triggers it | Field label (string key) | Ask |
|---|---|---|---|
| a | `EnableChapterImageExtraction: true` **and** the library's path is on a `rotational=1` device | **"Enable chapter image extraction"** (`OptionExtractChapterImage`) | Turn off, or accept the cost knowingly |
| b | `ExtractChapterImagesDuringLibraryScan: true` **and** (a) holds | **"Extract chapter images during the library scan"** (`LabelExtractChaptersDuringLibraryScan`) | Turn off — it moves the work to the nightly task instead of into every scan |
| c | `EnableTrickplayImageExtraction: true` **and** the library's path is on a `rotational=1` device | **"Enable trickplay image extraction"** (`OptionExtractTrickplayImage`) | Consider off |
| d | `EnableLUFSScan: true` **and** the library's path is on a `rotational=1` device | **"Enable LUFS scan"** (`LabelEnableLUFSScan`) | Consider off |
| e | `EnableTrickplayImageExtraction: false` **and** `ExtractTrickplayImagesDuringLibraryScan: true` | both of the above | Contradictory pair — the during-scan flag is inert while the feature is off |

Finding (b) may quote Jellyfin's own help text, which already makes the argument:
*"The process can be slow, resource intensive, and may require several gigabytes of space… It is not
recommended to run this task during peak usage hours."* When the vendor's own UI says it, the finding
cites it rather than editorialising.

Finding (e) is a **consistency** check, not a performance one: it fires regardless of storage, because
a contradictory pair is a mistake at any speed. On this server it fires for **Film**.

### FR-212-5 — server-wide findings

Path: **Dashboard → Playback → Transcoding**.

| # | Live value that triggers it | Field label (string key) | Ask |
|---|---|---|---|
| a | `EnableThrottling: false` | **"Throttle Transcodes"** (`AllowFfmpegThrottling`) | Turn on. `ThrottleDelaySeconds` is already 180 and is inert until it is — the row says so, since a set-but-ignored value is exactly what makes this hard to notice |
| b | `EnableSegmentDeletion: false` **and** `TranscodingTempPath` is on a `tmpfs` | **"Delete segments"** (`AllowSegmentDeletion`), **"Time to keep segments"** (`LabelSegmentKeepSeconds`) | Turn on |
| c | `AllowHevcEncoding: false` **and** `EnableHardwareEncoding: true` **and** ≥1 known device has a recorded decode ceiling | **"Allow encoding in HEVC format"** (`AllowHevcEncoding`) | Consider on |
| d | `AllowOnDemandMetadataBasedKeyframeExtractionForExtensions` is non-empty **and** any library path is `rotational=1` | Not exposed in the Jellyfin UI — `encoding.xml` only | State the field name and that it is file-only |

Finding (b) is the one that only exists **because** the owner's report item 1 is already implemented:
with transcode output in RAM, segments kept for `SegmentKeepSeconds: 720` accumulate in tmpfs for the
whole session. It is a rule whose trigger is another setting's value, and the implementation must
support that shape rather than treating findings as independent.

Finding (c) is where jellystructure knows something Jellyfin cannot. Phase 185 persists
`decode_max_bitrate_hevc` / `_h264` per device; Phase 177's 0.9 predicate decides when a file exceeds
one. The row therefore names the actual devices — *"BRAVIA 4K GB ATV3 and 2 others transcode 4K HDR
titles today; with HEVC encoding disallowed those all become H.264"* — instead of giving generic codec
advice. Findings that cannot cite a device do not fire.

Finding (d) has no UI. The row says so and gives the property name and file, rather than sending the
operator to hunt for a toggle that does not exist.

### FR-212-6 — host storage findings

Not Jellyfin settings, and jellystructure cannot change them. Detection is nonetheless local and needs
**no new mounts and no added privileges** — verified from inside the running container:

```
/mnt/media   src=/dev/sdc1  dev=sdc  read_ahead=128  rotational=1
/mnt/series  src=/dev/sda1  dev=sda  read_ahead=128  rotational=1
```

Method: read `/proc/self/mountinfo` for the mount covering the library path to get its source device,
strip the partition suffix, then read `/sys/block/<dev>/queue/...`.

Two implementation traps, both hit while proving this out:

- **`/sys/dev/block/<major>:<minor>` is not populated in the container** — only a handful of symlinks
  exist (`11:0`, `254:0`, `254:1`, `259:0`, `259:1`), none of them `8:x`. Resolving by major:minor
  fails. Resolve by device *name* via `/sys/block/<name>/` instead, which works.
- **Device-mapper/LVM sources do not resolve.** `/config` reports
  `src=/dev/mapper/debian--vg-root`, which has no `/sys/block` entry under that name. A library path on
  LVM yields an **unknown** storage kind, and unknown must suppress every storage-conditional finding
  rather than assume rotational. There must be no finding that fires on a guess.

#### The sizing of these findings, measured

An earlier draft of this section carried only a read-ahead finding (128 KB → 16 MB). The owner
challenged whether that takes any real advantage of the host. It does not, and the measurement is
stark. This host has **125 GB RAM with 102 GB in page cache**. During playback:

```
The Patriarch — file size     : 24.7 GB
              — cached in RAM :  0.18 GB  (0.7 %)
```

The entire film would fit in RAM four times over; 0.7 % of it was resident. A 16 MB read-ahead is
0.06 % of the file. Read-ahead is a real but *minor* lever, and this section must not present it as the
headline. The findings below are therefore ordered by measured value, not by ease.

| # | Live value | Finding | Why it is worth more than read-ahead |
|---|---|---|---|
| a | `/sys/block/<dev>/queue/scheduler` is `mq-deadline` on a rotational library device | Switch to **BFQ** | See below — it activates a mitigation the codebase already ships |
| b | `read_ahead_kb` ≤ 256 on a rotational library device | Raise to 8–16 MB | Fewer, larger sequential reads per seek |
| c | `max_sectors_kb` ≪ `max_hw_sectors_kb` on a rotational library device | Raise toward the hardware limit | Here 1280 KB vs a 32767 KB ceiling — a 16 MB read-ahead is split into ~13 requests regardless, so (b) is partly wasted without it |
| d | `vm.swappiness` ≥ 60 with page cache ≫ free memory | Lower to ~10 | 12 GB is swapped out on a box holding 102 GB of cache |

**Finding (a) is the most valuable one in this phase, and the reason is specific to this codebase.**
`FfmpegRunner.kt` already runs background ffmpeg under `nice -n 19 ionice -c3` in three places
(`:104`, `:268`, `:361`), and Phase 109's own comment states the intent: *"protects API/playback"*.
But `ionice` classes are honoured only by **BFQ** (and legacy CFQ) — **`mq-deadline` ignores them
entirely**. Both rotational media devices run `mq-deadline` today, so that protection has never taken
effect at the I/O layer. `bfq.ko` is present on this kernel
(`/lib/modules/6.12.95+deb13-amd64/kernel/block/bfq.ko.xz`).

Switching the two media devices to BFQ costs nothing to try, is reversible with one write, and makes an
already-written, already-intended mitigation start working. It also gives the playback reader latency
priority over batch readers, which is the exact contention shape of the 2026-09-15 incident.

Trade-off the finding must state: BFQ carries more per-request CPU overhead than `mq-deadline` and can
lower peak sequential throughput slightly. On a box at 0.15 % CPU pressure during a disk-saturating
incident, that is the right trade — but it is a trade, not a free win.

Every finding here states the device, the current value, the suggested value, the exact command, and —
required, not optional — that **none of them survive a reboot** and that persisting them needs a udev
rule (a, b, c) or `/etc/sysctl.d` (d). A suggestion that silently reverts on the next restart is a
trap, not advice.

**Explicitly out of scope here:** actually using the 102 GB of idle page cache to hold the playing file
in RAM. That is not a configuration suggestion — it is a feature, and jellystructure is uniquely placed
to build it because it already knows what is playing (Phase 178's tracker), what file backs it, and
what is likely next. See §6 open question 6.

### FR-212-7 — restart-pending

`GET /System/Info` returning `HasPendingRestart: true`, or any `/Plugins` entry with
`Status: "Restart"`, renders one server-wide finding naming the plugins involved. On this server both
are true today (File Transformation is in `Status: "Restart"`).

This finding says **"Jellyfin reports a pending restart"** and nothing more. It does not recommend
restarting, and it does not offer to. Restarting the household's media server is not an admin-panel
side effect, and Ravilo sessions are live against it.

### FR-212-8 — no finding without evidence

Every finding names the specific thing it read. A row that cannot state a live value, a device, or a
library does not render. This exists to stop the surface degrading into a checklist of generic advice
over time — the exact failure mode that would have made it output two wrong recommendations out of
three on day one.

## 5. Every finding is a trade-off, and says so

None of these is a defect. Chapter images give scene-selection menus; trickplay gives scrub previews;
LUFS gives loudness normalisation; realtime monitoring gives fast pickup of new files. Each row states
what turning it off costs, in the same voice as what leaving it on costs. The surface's job is to make
the trade visible on *this* hardware, not to push the operator toward one answer.

## 6. Open questions

1. **Does the advisor re-check after the operator acts?** A finding stays until the 5-minute cache
   expires. A "Re-check now" control is cheap, but adds a write-shaped affordance to a read-only
   surface. Not decided.
2. **Per-library storage resolution for LVM/network paths.** FR-212-6 degrades to unknown, which is
   safe but silent. Whether unknown should be *shown* as unknown (rather than omitted) is unresolved —
   showing it risks a row that says nothing actionable.
3. **`EnableRealtimeMonitor`.** Enabled on four libraries here. jellystructure has run its own
   scanning and ingest since Phase 175, and Phase 181 established that the Jellyfin-based realtime
   ingest has delivered nothing, ever. Whether Jellyfin's own monitor is therefore redundant *for this
   deployment* is a real question, but answering it wrongly would stop new files being noticed. Left
   out of §4 deliberately until someone confirms what still depends on it.
4. **Does any finding belong on the dashboard?** §2 says no. If the restart-pending finding proves to
   matter operationally, that is the one with a case.
5. **Re-verifying labels across Jellyfin versions.** FR-212-3 pins exact UI strings from the 10.11.11
   web bundle. There is no mechanism to notice when an upgrade renames one, and a stale label is worse
   than an API name because it sends the operator looking for something that is not there.
6. **Should jellystructure prefetch the playing file into RAM?** (Raised by the owner, 2026-09-15;
   deliberately left as a question rather than folded into this phase.) The measurement in FR-212-6 —
   a 24.7 GB film 0.7 % resident against 102 GB of idle page cache — says the host's largest resource
   is unused during exactly the workload that hurts. jellystructure knows the playing file's path and,
   for a series, the next episode. A paced `posix_fadvise(POSIX_FADV_WILLNEED)` walk could make an
   entire film resident for the session, after which the rotational disk is irrelevant to playback and
   background extraction cannot starve it. This is a bigger and better idea than every tuning finding
   in FR-212-6 combined, and it is a **feature, not a suggestion**, so it does not belong in a
   suggest-only surface. It needs its own phase, and its own hard questions: eviction pressure on
   everything else the cache holds, behaviour with several concurrent viewers, what happens on a
   125 GB host versus a 16 GB one, and whether `WILLNEED` on a 25 GB range is even honoured as issued.

## 7. Verification

- Against the live server, the advisor must render: FR-212-4 (a)(b) for **Film** and **Serier**,
  (c)(d) for **Serier**/**Blandet**/**Musik**, (e) for **Film**; FR-212-5 (a)(b)(c)(d);
  FR-212-6 (a)(b)(c) for both `sdc` and `sda` and (d) once server-wide; FR-212-7.
- It must render **nothing** for the transcode path and nothing for config/cache isolation.
- **Recordings** and **Samlinger** have every per-library flag off and must produce no per-library
  findings at all — the cleanest proof that silence works.

## 8. Implementation notes (2026-09-15, same day, second pass)

Built as `JellyfinAdvisorService` (a Kotlin `object`, like `MkvHealthCache`) plus two new
`JellyfinClient` calls (`getEncodingConfiguration`, `getSystemInfoAuth`) and two new `JellyfinLibrary`/
`JellyfinLibraryOptions` fields, all re-verified live against the production server before writing any
code (`curl` against `/System/Configuration/encoding`, `/Library/VirtualFolders`, `/System/Info`,
`/Plugins` with the real admin token) — every field name in FR-212-3/4/5 checked out exactly as stated.
New endpoint: `GET /api/jellyfin/advisor`. Rendered on Settings → Libraries: a "Server-wide" card pinned
above the library list, and each library's own findings inside its existing mapping card.

Four deviations from the letter of the spec, found while implementing, each a deliberate, evidence-first
call rather than a bug:

1. **FR-212-6's storage resolution reads jellystructure's OWN `[[libraries]] local_path`, not Jellyfin's
   `jellyfin_path`.** Confirmed live: the two containers mount the same host directories at *different*
   internal paths (Jellyfin sees `/media/movies`; jellystructure sees `/mnt/media/jellyfin/movies`).
   `/proc/self/mountinfo` inside jellystructure's own container only ever contains jellystructure's own
   paths, so `local_path` is the only usable input — matches the spec's own cited example paths
   (`/mnt/media`, `/mnt/series`) exactly, but the spec's prose didn't say *which* path to start from.
2. **FR-212-5(b)'s tmpfs precondition is dropped.** The spec's predicate is `EnableSegmentDeletion:
   false AND TranscodingTempPath is on a tmpfs`. jellystructure cannot verify the second half from
   inside its own container — Jellyfin's mount namespace is invisible to it, and no docker-socket or
   shared-PID access exists to check another way. The finding now fires on `EnableSegmentDeletion ==
   false` alone (itself fully evidence-backed — Jellyfin really does report it), with the RAM-severity
   framing kept as unverified colour in the cost line rather than a proven fact. FR-212-8's "no finding
   without evidence" is the reason for the drop, not a reason to skip the finding entirely: unbounded
   segment accumulation is a real cost regardless of what backs the temp path.
3. **FR-212-5(c) is simplified.** Fires on "≥1 device has a recorded HEVC decode ceiling"
   (`ravilo_device.decode_max_bitrate_hevc IS NOT NULL`, via the existing `RaviloDeviceService`) rather
   than "≥1 device transcodes a 4K HDR title *today*", which would need live correlation against
   `playback_qoe`/177's 0.9× predicate per session — a second phase's worth of work on its own. Still
   never fires without a real device row, so FR-212-8 holds.
4. **A live correction to §7's own verification list, found by checking the current config, not by
   guessing:** `Blandet` is `skip = true` with an empty `local_path` in the live `config.toml` (it is
   NOT a jellystructure-managed library, despite having every FR-212-4(c)(d)-triggering flag on in
   Jellyfin's own `LibraryOptions`). Storage resolution for it is therefore `unknown`, and per FR-212-6's
   own suppression rule it must render **no** per-library findings — not the (c)(d) findings §7 said to
   expect. This is the verification list being wrong, not the implementation: a library jellystructure
   doesn't manage well enough to know its own local path shouldn't get a storage-conditional finding.
5. **FR-212-3's exact label re-verification could not be completed.** The production server's bundled
   web client changed between this spec's authoring and this implementation pass (same day) — a
   Moonbase-plugin-supplied "Moonfin" build replaced the chunk-per-locale `en-us-json.*.chunk.js`
   structure the original labels were extracted from, and the admin-settings strings could not be
   located in the new bundle's main/vendor chunks within reasonable effort (likely lazy-loaded per admin
   route). The labels already cited in FR-212-3/4/5 are used as-is, unverified against the current
   bundle. This is exactly open question 5's scenario, arriving before the phase even shipped — a real
   argument for treating these labels as needing a screenshot check before this goes live, not just on
   "a Jellyfin major upgrade."

Not done: open questions 1 ("re-check now" control), 2 (showing "unknown" explicitly), 3
(`EnableRealtimeMonitor`), 4 (dashboard placement), 6 (prefetch feature) all remain open, unchanged.

`compileKotlinLinuxX64` / `compileKotlinWasmJs` / `linuxX64Test` all clean. Not dev-reviewed, not
deployed (the running jellystructure container was not restarted to pick this up), not live-verified
end-to-end against the rendered Settings page.
