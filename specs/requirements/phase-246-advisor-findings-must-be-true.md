# Phase 246 — An advisor is only worth opening if every finding on it is true

> Owner direction 2026-09-19, after a line-by-line audit of phase 212's live output against the
> household server: *"Before I'm going to apply them I would like to be sure that they actually are
> proper good."* They were not all good. One would have made things worse.

## Status

`Planned` — written 2026-09-19 from a full audit of the advisor's live output against the household
server (Jellyfin `12.1.0`, backend `v1.27`), not dev-reviewed, not built. Corrects and extends phase
**212**. Carries the shared `AdvisorFinding` extension that phases **242** and **244** both need, per
244's dev review item 3 ("extend `AdvisorFinding` once, for both phases — whichever lands first does
it").

**Nothing in this phase changes a Jellyfin setting, a sysctl or a block-device queue.** 212's
read-only, suggest-only posture is not reopened. The operator reads the findings and applies them by
hand; this phase is only about the findings being right.

## What is wrong

Phase 212 shipped a surface whose entire value is that the operator trusts it. Audited against the
live server on 2026-09-19 it produced **21 findings**, of which:

- **11** are correct and applicable as written.
- **6** are correct in direction but carry a wrong number, a command that fails, or copy that
  describes the benefit of changing a setting in the field reserved for the cost of not changing it.
- **1 is wrong, and following it would have made the server worse.**
- and the single most consequential misconfiguration on the host — Jellyfin running with **no
  hardware acceleration at all**, on a machine with two NVIDIA GPUs — is not checked by any finding,
  went unnoticed through an upgrade, and silently invalidates the cost estimate of several findings
  that *are* checked.

### 1. The HEVC finding is wrong four times over, and acting on it would hurt

`serverWideEncodingFindings` (c) fires on `!allowHevcEncoding && enableHardwareEncoding` and
recommends turning `AllowHevcEncoding` on. Measured live:

```
HardwareAccelerationType: "none"      <- hardware acceleration is OFF
EnableHardwareEncoding:   true        <- inert while the line above is "none"
AllowHevcEncoding:        false
```

1. **Its "Now:" line is false.** It reports *"Hardware encoding: On"* from `EnableHardwareEncoding`,
   a field that does nothing while `HardwareAccelerationType` is `none`.
2. **Its recommendation is harmful in the current state.** With no accelerator, allowing HEVC output
   selects `libx265` on the CPU. For 4K material on this host that is not real-time.
3. **It cannot affect this product's own clients even when hardware encoding is working.**
   `JellyfinClient.kt:76` sends `"TranscodingProfiles":[{"Container":"ts","Type":"Video",
   "VideoCodec":"h264",...}]`. Ravilo's own device profile admits **H.264 only** as a transcode
   target. The finding justifies itself by naming Ravilo devices' recorded decode ceilings and then
   recommends a Jellyfin setting that Ravilo's own profile forbids from taking effect. It is circular.
4. **Its device predicate is far too loose.** It counts any device with a non-null `hevcMaxBitrate`.
   Of the six it counts on this server, three are BRAVIAs at 60 Mbps (genuinely constrained) and
   three are a Pixel 9 Pro at 240 Mbps and an SM-S911B at 160/220 Mbps — ceilings no title in this
   library approaches. *"BRAVIA 4K VH2 and 5 others"* over-counts by half.

### 2. Nothing reads `HardwareAccelerationType`, and it regressed during the 12.1 upgrade

`jellyfin_config/config/encoding.xml` was rewritten **2026-09-18 22:22**, seven minutes after
`system.xml` (22:15) — i.e. during the 10.11.11 → 12.1.0 upgrade. Transcodes from **the same
morning** used `-init_hw_device cuda=cu:0 -hwaccel cuda -hwaccel_output_format cuda` and
`-codec:v:0 h264_nvenc`. After the rewrite, `HardwareAccelerationType` is `none`.

The host has a Quadro P4000 and an RTX 2060 SUPER, the compose file requests the NVIDIA runtime with
`NVIDIA_DRIVER_CAPABILITIES=all`, and none of it is in use. Every transcode and every background
decode on this server is now software.

This is not a small miss. It is the field that decides what several *other* findings cost, and the
advisor cannot see it.

### 3. The transcode temp path moved in the same upgrade, and "(default)" hid it

The segment-deletion finding prints `Transcode temp path: ${transcodingTempPath ?: "(default)"}`.
`TranscodingTempPath` is unset in `encoding.xml`, so it printed *"(default)"* — while Jellyfin's own
`GET /System/Info` reports the **resolved** value, which changed:

| | before the upgrade | after |
|---|---|---|
| resolved `TranscodingTempPath` | `/transcode` | `/cache/transcodes` |
| what backs it | `/dev/shm`, a 63 GB tmpfs (RAM) | the `jellyfin_cache` bind mount, NVMe |

`docker-compose.yml` still maps `/dev/shm:/transcode`. That mount is now unused by Jellyfin, and
**1181 orphaned `.ts` segments totalling 3.0 GB are stranded in RAM**, last written 2026-09-18
18:48–20:51. Jellyfin's *Clean Transcode Directory* task now cleans `/cache/transcodes` and will
never visit the old path again. The advisor printed *"(default)"* through all of it.

This also **corrects the finding's own hedge in the opposite direction from the obvious one**: the
copy warns the path is *"especially costly if RAM-backed (tmpfs)"*, which was true before the upgrade
and is not true now. jellystructure still cannot see inside Jellyfin's mount namespace and must not
claim to — but it can print the path Jellyfin resolved instead of the word "(default)", and that
alone is the difference between an operator noticing this and not.

### 4. Two findings are one fix, and read as though they are independent

`EnableThrottling: false` and `EnableSegmentDeletion: false` bound the same quantity from two ends —
how far ahead of the playhead a transcode runs, and how much of what it produced is kept behind it.
They are rendered as two unrelated warnings with two separate recommendations, which invites
applying one.

### 5. The host findings carry a wrong number, a command that fails, and backwards copy

- **`read_ahead_kb`.** Its `costHere` reads *"Fewer, larger sequential reads per seek on a spinning
  disk"* — that is the **benefit of raising it**, printed in the field reserved for what the current
  value costs. As rendered it says the 128 KB default already delivers the thing it does not.
- **`max_sectors_kb` recommends the hardware ceiling, 32767 KB.** That is a **32 MB single request**.
  mq-deadline dispatches whole requests, so at roughly 200 MB/s one of them occupies the device for
  about **160 ms**, during which nothing else on that spindle is served — on a server whose entire
  purpose is not stalling playback. The trade-off line calls this *"very slightly increase worst-case
  latency"*. The devices here report `queue_depth 32` and `nr_requests 64`, so there is little
  behind which to hide it.
- **The BFQ command does not work on this host.** `cat /sys/block/sda/queue/scheduler` returns
  `none [mq-deadline]` — `bfq` is a module (`/lib/modules/6.12.95+deb13-amd64/kernel/block/bfq.ko.xz`)
  and is not loaded, so `echo bfq | sudo tee …` fails with `Invalid argument`. The finding hands the
  operator a command that errors.
- **The BFQ finding also claims more than BFQ buys.** Only jellystructure's own ffmpeg carries
  `nice -n 19 ionice -c3` (`FfmpegRunner.kt:118`, `:254`, `:365`, `:474`, `:497`). Jellyfin's
  background work — trickplay generation, chapter-image extraction, LUFS scanning, subtitle
  extraction — runs in Jellyfin's own container under no ionice at all. Those are the dominant source
  of disk contention on this host, including the incident that produced phases 212–215. Switching to
  BFQ restores a protection that has never taken effect, and that protection would still not cover
  the work that actually causes the problem. Both halves have to be said.

### 6. The swappiness finding reaches the right conclusion from evidence that proves nothing

Its predicate is `swappiness >= 60 && Cached > MemFree * 2`, and it renders
*"104 GB cached vs 1 GB free"* as the evidence. **That second clause is true of every healthy Linux
host.** Near-zero `MemFree` is normal and desirable, and phase **215**'s own FR-215-2 already says so
in as many words: *"page cache is neither free nor spare memory"*. The advisor's sibling states the
principle and the advisor's own copy breaks it.

Its present-tense claim — *"the kernel is choosing to page out live processes"* — was also not
happening when measured: `pswpout` moved **0 pages in 20 s**, and `MemAvailable` was 112 GB of 125 GB.

**The real evidence exists, and it is elsewhere.** `/proc/vmstat` reports `pswpout` 109 806 226 pages
(≈ 419 GB written to swap over 51 days of uptime) and `pswpin` 55 916 867 (≈ 213 GB read back), with
**24.2 GB resident in swap right now**, including Jellyfin (25–62 MB per process) and jellystructure
(39–79 MB). That is the argument. The finding should make it.

### 7. Two "findings" recommend doing nothing

`keyframe_extraction_extensions` recommends *"Leave as-is"*. `restart_pending` says *"Not offered"*
and explicitly *"this finding does not claim a performance cost"*. Both are useful information and
neither is a warning. They render with the same ⚠ and count toward the same badge as findings that
do ask for action, which is how an operator learns to skim the page.

### 8. A spindle does not care who owns a library's metadata

`computeFindings` considers only libraries in
`cfg.libraries.filter { !it.skip && it.jellyfinId.isNotBlank() }` (`:67`, `:74`). That gate is
correct for **242**'s metadata-ownership findings — a skipped library is Jellyfin's to manage. It is
wrong for performance findings, and this server proves it: **`Blandet`** (`homevideos`,
`/media/mixed`) has chapter-image extraction, trickplay extraction **and** LUFS scanning all on, sits
on `sdc` — the same spindle as `Film` and `Musik` — and produces **not one finding**, because
jellystructure is configured to skip it.

It is the worst-configured library on the server and the advisor is silent about it.

### 9. The trickplay finding says "consider off" about something that is on fire

Measured live, while the audit was running:

- **`Generate Trickplay Images` is in state `Running`**, at **9.3 %** rising to **9.9 %** over the
  review window. Its last *completed* pass ended **2026-09-17T05:09Z**. It has a daily trigger. It is
  not completing between triggers.
- The log for 2026-09-19 records **23 files attempted and 9 `Trickplay process unresponsive` failures
  — 39 %** — with 4 more the previous day.
- The ffmpeg it runs is `-threads 1` with **no `-hwaccel`** (because of §2 above): whole-file software
  decode, holding a core at 101 %, reading `/mnt/series` sequentially.
- **Ravilo never displays a trickplay thumbnail.** `trickplayUrl = null` is hardcoded at
  `PlaybackService.kt:530` and `:939`. Every hour of this benefits Jellyfin's own web client only.

`"Consider off."` / `"Removes the hover/scrub thumbnail previews for this library."` is a true
sentence about a completely different situation. The same applies to the LUFS finding's trade-off:
nothing in this repository reads a loudness value either.

## Requirements

**FR-246-1 — Read the field that decides whether hardware encoding exists.**
`JellyfinEncodingConfig` gains `HardwareAccelerationType` (confirmed live on 12.1.0, 2026-09-19). A
finding fires when it resolves to `none` or blank **while `EnableHardwareEncoding` is true**: the
server advertises hardware encoding and has no accelerator selected, so every transcode and every
background decode is software. It renders **first** among the encoding findings, because every other
encoding finding's cost depends on it.

It states the trade-off honestly: jellystructure cannot verify from its own container that an
accelerator is present and usable, so the recommendation is to set the type Jellyfin's own
*Hardware acceleration* dropdown offers and confirm with one real transcode — not to assume NVENC.

**FR-246-2 — The HEVC finding is withdrawn, not repaired.** Supersedes **FR-212-5(c)**. It is deleted
outright rather than given a tighter predicate, for the reason in §1.3: for this product's own
clients the setting cannot take effect at all, so no predicate over Ravilo device ceilings can make
the finding true. If it is ever revived it must (a) require a real `HardwareAccelerationType`,
(b) compare a ceiling against material actually in the library rather than against the existence of a
recorded number, and (c) say in its own copy that it concerns Jellyfin's own clients and not Ravilo.

**FR-246-3 — Throttling and segment deletion are one finding.** Supersedes **FR-212-5(a)** and
**FR-212-5(b)**, which become a single finding carrying both current values, one recommendation
("turn both on — they bound the same thing from two ends"), and both trade-offs. Each half still
renders its own current value so an operator who has already fixed one can see it.

The backward-seek trade-off stops calling the cost *"a small re-transcode"*: a seek past a deleted
segment can fail outright rather than degrade. At `SegmentKeepSeconds = 720` the exposed window is
twelve minutes, which is worth saying next to the risk.

**FR-246-4 — Print the transcode path Jellyfin resolved, never the word "(default)".** The finding
reads `TranscodingTempPath` from `GET /System/Info` — the resolved value — rather than from the
encoding configuration, where unset means unset. jellystructure still makes **no claim about what
backs that path** (it cannot see Jellyfin's mount namespace) and the tmpfs speculation in the current
copy is removed rather than reversed. One sentence notes that Jellyfin's *Clean Transcode Directory*
task only cleans the path currently configured, so a path that has moved leaves the old directory
uncollected.

**FR-246-5 — `costHere` states what the present value costs.** Every finding's cost field describes
the consequence of leaving the setting as it is. It never describes the benefit of changing it; that
belongs to `recommendation`. The `read_ahead_kb` finding is the one that breaks this today and is
rewritten.

**FR-246-6 — `max_sectors_kb` is recommended at a latency-safe value.** The recommendation becomes
`min(4096, max_hw_sectors_kb)` rather than the hardware ceiling, and the trade-off states the
head-of-line cost in milliseconds for the value actually being recommended. The finding continues to
fire on the same predicate; only the target and the copy change.

**FR-246-7 — The scheduler finding carries a command that works, and claims only what it buys.** The
finding reads the device's **available** scheduler list, not only the active one. When `bfq` is
absent from that list the recommendation leads with `sudo modprobe bfq` and the
`/etc/modules-load.d/` line needed to persist it, because without them the command it prints returns
`Invalid argument`.

Its cost field states both halves: that `ionice -c3` has never taken effect at the I/O layer here
**and** that it covers only jellystructure's own ffmpeg, so BFQ does not reprioritise Jellyfin's own
background work, which is the larger source of contention on this host.

**FR-246-8 — The swappiness finding is evidenced by paging, not by page cache.** The
`Cached > MemFree * 2` clause is removed. The predicate becomes swap actually in use
(`SwapTotal - SwapFree > 0`) together with a non-trivial `pswpout` from `/proc/vmstat`, and the
finding states those measured figures. It makes no present-tense claim about what the kernel is doing
unless a sampled `pswpout` delta supports one. The recommendation states that lowering `swappiness`
does not return pages already swapped out.

**FR-246-9 — Severity, and a finding that asks for nothing is not a warning.** `AdvisorFinding` gains
a `severity` of `critical` | `warning` | `info`, defaulting to `warning` so no existing call site
changes meaning. `keyframe_extraction_extensions` and `restart_pending` become `info`. Findings
render grouped and ordered by severity, `info` rows are visually distinct from ⚠ rows, and the card's
count badge counts `critical` + `warning` only.

**FR-246-10 — The severity field is the shared extension, added once.** Phase **242**'s FR-242-7
state and phase **244**'s FR-244-3 ordering and FR-244-4 action both need `AdvisorFinding` widened.
This phase adds `severity` and an optional `action` (an id the frontend can bind a button to, absent
on every finding this phase produces). Whichever of 242/244 lands next uses them; neither grows its
own variant.

**FR-246-11 — Performance findings cover every Jellyfin library whose storage can be resolved, managed
or not.** The `!skip && jellyfinId.isNotBlank()` gate stays for **242**'s metadata findings and is
dropped for the storage-conditional performance findings.

For a library jellystructure does not manage there is no `local_path`, so the device is resolved by
**deriving** one: take the (`jellyfin_path`, `local_path`) pairs of the managed libraries, find the
longest pair whose `jellyfin_path` prefixes this library's `Locations` entry, substitute, and
**`stat` the result**. A path that exists is a resolution; a path that does not is unknown and
suppresses, per FR-212-6. Nothing is inferred that is not then confirmed against the filesystem.

On this server that resolves `Blandet`'s `/media/mixed` to `/mnt/media/jellyfin/mixed` (verified to
exist, on `sdc`) and produces the three findings it should have had all along.

**FR-246-12 — The extraction findings state the work actually happening, and who consumes it.** The
advisor reads `GET /ScheduledTasks` (keys `RefreshTrickplayImages`, `RefreshChapterImages` — stable,
never the `Id`, per phase 165's rule) and the trickplay and chapter-image findings carry:

- the task's state, and its progress when running;
- whether it has failed to complete since its previous scheduled trigger, which is the difference
  between periodic background work and a job that never finishes.

Their trade-off fields name the real consumer: Ravilo renders no trickplay thumbnail
(`trickplayUrl = null`) and reads no loudness value, so what is lost by turning either off is
confined to Jellyfin's own clients. The LUFS finding gets the same correction.

**FR-246-13 — Silence, read-only and provenance are unchanged.** 212's FR-212-2 silence rule, its
never-write posture and its unknown-suppresses rule all hold exactly. Every new or changed model
field records the date it was confirmed against a live 12.x server, per the standing rule and 242's
FR-242-4.

## Non-goals

- Changing any setting. Not Jellyfin's, not a sysctl, not a block-device queue. The operator applies
  these by hand; that is the whole point of the surface and it is not reopened.
- Fixing the household's own configuration. The measurements in this spec are evidence, and the
  actions they imply are listed in acceptance so they are not forgotten, not automated.
- Metadata ownership findings. Phase **242**.
- Security and exposure findings. Phase **244**.
- Reading Jellyfin's logs. The 39 % trickplay failure rate in §9 was measured by hand from
  `jellyfin_config/log/`; jellystructure has no access to that directory and FR-246-12 deliberately
  asks only for what `/ScheduledTasks` answers.
- Resolving what backs Jellyfin's transcode path. FR-246-4 prints the path and stops.
- A finding for `nr_requests`, which is 64 on all three spindles against a mq-deadline default of
  256. Real, small, and not measured here.

## Acceptance

1. With the household server as it stands, the advisor renders a `critical` finding naming
   `HardwareAccelerationType: none` alongside `EnableHardwareEncoding: true`, first among the
   encoding findings.
2. No HEVC-encoding finding renders, in any configuration.
3. Throttling and segment deletion render as one finding carrying both current values.
4. That finding's current value reads `/cache/transcodes`, not `(default)`.
5. `Blandet` renders chapter-image, trickplay and LUFS findings, resolved to `sdc` through
   `/mnt/media/jellyfin/mixed`, despite being `skip = true`.
6. A Jellyfin library whose location cannot be resolved to an existing local path renders no
   storage-conditional findings at all.
7. The `max_sectors_kb` finding recommends `4096`, and its trade-off states a millisecond figure for
   `4096`.
8. The scheduler finding's recommendation begins with `modprobe bfq` while `bfq` is absent from
   `/sys/block/<dev>/queue/scheduler`, and does not once `bfq` is loaded.
9. The swappiness finding cites `pswpout` and swap-in-use, and does not mention page cache.
10. `keyframe_extraction_extensions` and `restart_pending` render as `info`, and the server-wide card's
    badge counts neither.
11. The trickplay finding names the task's running state and progress, and its trade-off says the loss
    is confined to Jellyfin's own clients.
12. Separately from the code, and by hand: hardware acceleration is restored and confirmed with one
    real transcode; the 3.0 GB of orphaned segments in `/dev/shm` is cleared; `Blandet`'s three flags
    are reviewed.

## Open questions

1. **Did the 12.1 upgrade reset `HardwareAccelerationType`, or did a save on 12.1's encoding page do
   it?** It matters for whether other households will hit the same thing on upgrade, and therefore
   for whether FR-246-1 deserves an upgrade-specific sentence. The evidence is consistent with either:
   `encoding.xml` was rewritten at 22:22 and `system.xml` at 22:15.
2. **`HardwareDecodingCodecs` lists only `h264` and `vc1`**, yet a pre-upgrade transcode of an HEVC
   source used `-hwaccel cuda`. Either 12.x does not gate NVDEC on that list, or the list was also
   rewritten by the upgrade. Until it is known, FR-246-1 must not tell an operator that restoring the
   accelerator is sufficient for HEVC sources — so it says "confirm with one real transcode" instead.
3. **Should a scheduled task that has not completed between two of its own triggers be a finding in
   its own right**, rather than a sentence inside the trickplay and chapter findings? It is a
   general, cheap and genuinely useful signal, and FR-246-12 gets most of the value without the new
   surface. Deliberately not decided here.
4. **Is `4096` right for `max_sectors_kb` on these drives, or merely safer than `32767`?** It is
   chosen as a latency budget (~20 ms) rather than measured. A measurement on `sdc` under concurrent
   playback would settle it, and would also settle whether `read_ahead_kb` wants 8 MB or 16 MB.
5. **Does the derived-path resolution in FR-246-11 hold for a household whose Jellyfin paths do not
   share a prefix with any managed library?** It suppresses, which is correct, but it means the
   coverage this FR adds is a property of how the paths happen to be laid out. Worth knowing before
   the copy promises anything about unmanaged libraries.
