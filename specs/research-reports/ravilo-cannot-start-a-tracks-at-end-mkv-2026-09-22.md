# Ravilo buffers forever on a file whose track list is at the end — and this one arrived that way

**Date:** 2026-09-22 · **Status:** research only, nothing built, no spec yet · **Author:** dev (Claude) with the owner

> Live report, 2026-09-22 ~21:20: *"Why doesn't it work to stream temptation island on stue tv?"*
> followed by *"I moved to wholphin. So its ravilo thats not working for some reason."*

## Short answer

*Fristelsens Ø Danmark* **S01E19** has its Matroska `Tracks` element at **byte 995 998 578 of a
996 000 982-byte file** — after every Cluster. ExoPlayer reads the HTTP body linearly, reaches the
first Cluster having never seen a track list, and never leaves the buffering state. Jellyfin and
Wholphin open the file as a seekable source, follow `SeekHead`, and are fine. That is
**[Phase 201](../requirements/phase-201-mkvpropedit-evicts-the-tracks-element.md)'s failure mode
exactly**.

What is **new**, and what makes this its own problem:

1. **This file was never written by us.** `media_history` for the title holds one row —
   `realtime_ingest`. No `bulk_reorder_tracks`, no set-language, no set-default. Its muxer string is
   `Lavf62.12.102`; our container writes `Lavf59.27.100` and the host `Lavf61.7.100`. **It arrived
   broken from the source.** Phase 201's guard (FR-201-2: *check the layout after every mkvpropedit
   edit*) structurally cannot catch a file we never edit.
2. **Phase 201 deliberately left the client alone** — FR-201-7: *"Ravilo says nothing new… A
   client-side guard, if ever wanted, belongs in its own phase."* The owner has now asked for exactly
   that: **Ravilo should play it no matter what, like Wholphin and Jellyfin do.**
3. **Parity is feasible and cheaper than assumed.** Media3's `MatroskaExtractor` **already** jumps to
   a `SeekHead`-referenced element mid-parse and resumes — it does it for **Cues**, on every file, and
   there is even a `FLAG_DISABLE_SEEK_FOR_CUES` to switch it off. It simply discards the `Tracks`
   entry. So Phase 201's reason for ruling the client fix out — *"reading the tail of an HTTP stream
   before the head is not something to build into a player"* — is contradicted by the player we ship.
   See §7.
4. **The 2026-09-08 population is gone.** A full sweep today of **8 314 `.mkv` files** finds
   **one** broken file — this one. The 164/165 files of 2026-09-08/12 no longer have the defect.
   So this is not a backlog; it is **new arrivals**, one at a time, indefinitely.

## 1. What the viewer did

All times UTC (local = +2). From the prod backend log:

| time | item | result |
|---|---|---|
| 19:17:27 | S01E19 | `PlaybackInfo … directPlay=true transcode=false` |
| 19:18:01 | S01E19 | same |
| 19:18:25 | *Tricked by Marvin* E01 | same — tried something else |
| 19:18:35 | S01E19 | same |
| 19:19:10 | S01E19 | same |
| 19:19:58 | S01E19 | same |
| 19:20:55 | S01E19 | same |
| 19:21:26 | another title | played normally — gave up and moved on |

**Six negotiations in four minutes, and not one `playback_qoe` row.** A successful play writes exactly
one `PlaybackInfo` line and one QoE row; every other title that evening did. Zero QoE rows is the
Phase 201 signature: a player that never had a track to decode, so it never reported a decoder, a
rebuffer or a dropped frame.

## 2. Everything that is not wrong

Measured today, so none of this needs re-litigating:

- **The file is not damaged.** `ffmpeg -v error -i … -c copy -f null -` walks it end to end, exit 0,
  no output. Phase 254's damage check is silent on it, and `file_integrity` has no row for it.
- **Jellyfin is healthy.** `/Items?ids=…&fields=MediaSources` returns `SupportsDirectPlay: true`,
  `RunTimeTicks 20680640000`, all five streams with correct codecs, languages and flags.
- **Delivery is healthy.** `GET /Videos/{id}/stream?Static=true` answers **206** with a full
  1 MiB body both at offset 0 and at offset 500 000 000.
- **Our negotiation is correct.** `directPlay=true` is the right answer for a 3.75 Mbps H.264/EAC3
  1080p SDR file against a decoder rated 60 Mbps.
- **It is not the codec.** Six EAC3 titles played on the same TV the same evening.
- **It is not the subtitle count.** *JRock Ghost Chasers* (21 subtitle tracks) played fine.
- **It is not the null `runtime`.** S01E19 has `runtime: null` in our catalog, but so do
  *Pratarna* S1E1–E7 and *Tellytots* S1E8–E10, all of which played on this TV since 09-21.
- **It is not the app build.** Reproduced on the Pixel 9 Pro running the clean `1.36` release; the
  TV runs `1.36-dirty`.

## 3. The finding

Top-level EBML walk, S01E18 (plays) against S01E19 (does not):

```
E18   SeekHead > Void > Info > Tracks > Cluster
E19   SeekHead > Void > Info > Void   > Cluster          ← no Tracks before the media
```

E19's header carries an **886-byte `Void` where `Tracks` should be**, and the real `Tracks` element
sits at **995 998 578** — 2 404 bytes from EOF. Its own `SeekHead` says so:

```
114d9b74 … 53ab84 1654ae6b 53ac84 3b5dbb3e      Tracks → 0x3B5DBB3E = 995 999 038 (segment-relative)
```

A `Void` of the exact size of the vacated slot plus the element appended to EOF is the
**`mkvpropedit` in-place-growth signature** Phase 201 documented byte-for-byte: setting `flag-default=0`
or adding `language-ietf` makes `Tracks` outgrow its slot, and with no adjacent `Void` big enough to
absorb it (886 B available, ~2.4 KB needed) the tool voids the slot and appends. The difference is
**who ran it** — see §4.

Every other episode in the season is clean:

```
E01–E03, E06, E07   SeekHead > Void > Info > Tracks > Void > Cluster
E04, E05, E08–E14   SeekHead > Void > Info > Tracks > Cluster
E15, E16            SeekHead > Void > Info > Tracks > Tags > Cues > Cluster
E17, E18            SeekHead > Void > Info > Tracks > Cluster
E19                 SeekHead > Void > Info > Void   > Cluster          ← only this one
```

## 4. It came in broken — the part Phase 201 does not cover

| evidence | reading |
|---|---|
| `media_history` for `fristelsens-o-danmark-2026` = **one row, `realtime_ingest`** | we never ran a track edit on this title |
| no `mkvpropedit` in 72 h of backend logs | the guarded path did not run |
| E19 `WritingApp` = **`Lavf62.12.102`** | ffmpeg 8.1.2 |
| our container = `Lavf59.27.100` (5.1) · host = `Lavf61.7.100` (7.1) | **neither of ours** |
| 82 `-ADDICTION` files in the library carry `Lavf61.7.100` (34), `Lavf62.12.101` (19), `Lavf62.12.102` (3), `Lavf62.12.100` (1) | the release group's own spread of muxers |
| E15/E16 (same series) carry `Lavf59.27.100` | those two *were* remuxed by us, and are correct |

`Lavf62.12.102` is also Jellyfin's bundled ffmpeg 8.1.2 (`libavformat 62.12.102`, exact match), so
attribution is not airtight on the version string alone — but Jellyfin does not rewrite library files,
and the release group demonstrably ships this spread of ffmpeg builds. The `media_history` row is the
decisive evidence: **nothing of ours touched the file.**

**Consequence.** Phase 201's invariant is *"no write path may leave a file whose `Tracks` element
follows the first `Cluster`"* — scoped to **our** write paths. A file that is broken on arrival passes
through ingest, scan, NFO write and Jellyfin sync without any of them looking at the container layout.
FR-201-2's check only fires after an mkvpropedit edit that, here, never happens.

## 5. Blast radius, measured 2026-09-22

Full sweep of both library roots:

```
scanned 8 314 .mkv files, 23 unreadable/not-mkv
tracks-after-cluster: 1
  /mnt/series/jellyfin/Fristelsens Ø Danmark/Season 1/…S01E19…-ADDICTION.mkv
```

The 164 files of 2026-09-08 and the 165th of 2026-09-12 are **no longer present in the library in
this state**. That reframes the problem: this is no longer a one-off backlog to repair, it is a
**steady trickle of externally-muxed arrivals**, invisible until a viewer presses Play and waits.

## 6. Reproduced on a device

Pixel 9 Pro, Ravilo `1.36` release, over WiFi, 2026-09-22 21:29 local:

```
21:29:23.532  ExoPlayerImpl: Init [AndroidXMedia3/1.8.0]
21:29:23.537  DefaultRenderersFactory: Loaded FfmpegAudioRenderer.
              … 18 seconds with no app log output at all …
21:29:41.948  nativeloader: Load libffmpegJNI.so
21:29:41.967  Creating an asynchronous MediaCodec adapter for track type video
21:29:42.074  CCodec: Created component [c2.exynos.h264.decoder]
```

The screen held R218's cold-start card (*"Heintar…"*) for that whole window, then played. **A phone
on fast WiFi needs ~18 s to scan far enough into a 996 MB body to reach the track list.** The BRAVIA
did not get there inside the four minutes the owner gave it across six attempts — which is why it
presents as "doesn't work" rather than "slow".

This also means the failure is **not binary**: it is "unbounded time to first frame, proportional to
how far into the file `Tracks` sits and how fast the link is." A 1080p 35-minute episode is the mild
case. A 4K remux would be hopeless.

## 7. Part 1 — Ravilo must play it anyway

The owner's bar, 2026-09-22: *"Jellyfin and Wholphin can play it without issues, then we should also
be able to play without issues."* That rules out the easy answer. Falling back to a transcode would
make it *start*, but Jellyfin and Wholphin **direct-play** this file; matching them means our demuxer
must do what theirs does — **follow `SeekHead` to a track list that isn't where it expected it.**

### The good news: Media3 already does exactly this, for Cues

Read out of `media3-extractor-1.8.0`'s own bytecode, not from docs:

- `MatroskaExtractor` parses **every** `SeekHead` entry into `seekEntryId` / `seekEntryPosition`.
- In `endMasterElement(ID_SEEK)` it then compares:
  `if (seekEntryId != 475249515) return;` — `475249515` is `0x1C53BB6B`, **`ID_CUES`**. Only the Cues
  entry is kept, into `cuesContentPosition`. **`ID_TRACKS` (`0x1654AE6B` = `374648427`) is parsed and
  thrown away.**
- `private boolean maybeSeekForCues(PositionHolder, long)` is called from `read()`; when it fires,
  `read()` returns `RESULT_SEEK` and the player jumps to `cuesContentPosition`, with
  `seekPositionAfterBuildingCues` remembering where to come back to.
- There is even a public `FLAG_DISABLE_SEEK_FOR_CUES` to turn that behaviour off.

So the machinery to jump to a `SeekHead`-referenced element mid-parse, use it, and resume **is already
shipped and already on**. The defect is only that it is hard-coded to one element id.

**This means Phase 201's stated reason for ruling the fix out is factually wrong.** Its Out-of-scope
reads: *"Changing ExoPlayer/`MatroskaExtractor` behaviour or making a player seek to a trailing
`Tracks` element. Reading the tail of an HTTP stream before the head is not something to build into a
player."* The player we ship **already reads the tail before the head**, on every Matroska file whose
Cues sit at the end. That sentence should be retracted in whatever phase supersedes it.

The change itself is small: keep the `ID_TRACKS` entry alongside the Cues one, and if the first
`Cluster` is reached with no `Tracks` seen and a position is known, seek there, parse it, and resume —
the same round trip the Cues path already performs.

### The cost: it cannot be done by subclassing

`MatroskaExtractor` is not final and has a deliberately broad `protected` surface
(`startMasterElement`, `endMasterElement`, `integerElement`, `floatElement`, `stringElement`,
`binaryElement`, `isLevel1Element`, `getElementType`). A subclass can therefore **observe** the
`SeekHead` and learn where `Tracks` lives.

It cannot **act** on it: `read(ExtractorInput, PositionHolder)` is **`final`**, and `maybeSeekForCues`,
`seekEntryId`, `seekEntryPosition` and `cuesContentPosition` are all `private`. The seek can only be
issued from inside `read`.

So parity requires **vendoring the class** — it is Apache-2.0, so this is permitted — into
`:ravilo-player` (which already exists as the module that carries player internals and the FFmpeg
decoder), patched, and wired in through a custom `ExtractorsFactory` in place of
`DefaultExtractorsFactory`. That is a ~2 600-line file pinned to a Media3 version and re-synced on
upgrade: a real maintenance cost, but a contained one, and far smaller than the "fork the player"
framing that got this deferred before. **Upstreaming the same patch to Media3 is the good-citizen
route and would retire the vendored copy**; it should be attempted in parallel, not waited on.

### The three roads, re-scored against the owner's bar

| | what it does | meets "play without issues"? |
|---|---|---|
| **A · server refuses direct play** | backend walks the EBML header at negotiation; a bad layout is not offered as direct play, so Jellyfin remuxes | **partly** — it plays, but as a transcode. Not parity. Its real virtue is that it needs **no app release**, so it fixes the TVs already in the house today |
| **C · start watchdog** | no first frame in N seconds ⇒ re-negotiate once as a transcode | **no** — a safety net, not parity. But it is the only thing that bounds *unknown* future hangs, and the stue TV has now hit an unbounded hang twice for two unrelated reasons (this, and R220's black frame) |
| **D · vendored + patched `MatroskaExtractor`** | follow `SeekHead` to `Tracks`, exactly as the Cues path already does | **yes** — direct play, no transcode, byte-for-byte what Wholphin and Jellyfin do |

**Recommendation: D is the answer to the owner's question, with A shipped first and C as a standing
net.** D alone leaves every currently-installed TV broken until an app release reaches it; A closes
that gap immediately and costs nothing on the client. C is independent of this bug and worth its own
small requirement.

Open questions for the spec:
- Exactly where the re-entry lands: the Cues path seeks *away* and returns via
  `seekPositionAfterBuildingCues`. Tracks must be parsed **before** any Cluster is consumed, so the
  return position and the `sentSeekMap` interaction need care. Needs a build, not a guess.
- Whether `sniff()` (also `final`) still behaves on a header with no `Tracks`. Untested.
- What N is for C, and how it meets **R218**'s three moments and **R222**'s *"slow to start on this
  TV"* note — C must not fire on a file that is merely slow, nor contradict copy telling the viewer
  to be patient.
- **Cast and Tizen receivers are not ExoPlayer and have the same exposure.** Untested; the Chromecast
  receiver is CAF (browser media stack) and the Tizen app uses AVPlay. Probe before scoping.
- Media3 upgrade policy for the vendored file: pin, diff on upgrade, or block upgrades until re-synced.

Verified today, so A and C are known-viable: forcing an empty `DirectPlayProfiles` on this exact item
returns `SupportsDirectPlay: false` **with a working `TranscodingUrl`**. Jellyfin has no trouble with
the file; it seeks.

## 8. Part 2 — the backend must find it and say so

Most of this is **already specified and not built**. Phase 201's amendment of 2026-09-12 says it
outright: the sweep and repair *routes* exist (`GET /media/health/mkv-layout`,
`POST /media/health/mkv-layout/repair`) and work, but *"there is no reference to `mkv-layout` anywhere
in the frontend. An operator has no way to discover a single one of these files is broken except
calling the route by hand."* FR-201-9 … FR-201-13 were written to close that and are marked **not yet
built**:

- **FR-201-9** — sweep result grouped by title (id, title, kind, affected episodes), not a flat path list.
- **FR-201-10** — an **MKV track layout** health card on `app/activity.html`; absent entirely at zero;
  run on demand, not as a page-load side effect.
- **FR-201-11** — a **fix banner** on `app/media.html` / `app/series.html` for the open title, in the
  existing drift/Jellyfin-lock banner family.
- **FR-201-12** — **Fix now** calls the repair scoped to that title only; write-through, busy state,
  honest partial-failure reporting.
- **FR-201-13** — **no new state is invented**: the card and the banner read straight off a sweep
  result, nothing is written to `media_history`, `issueCount` or any persisted field, and a fixed
  title simply stops appearing in the next sweep.

What today's finding **adds** to that scope, and what a new spec has to say:

1. **Detection must not be limited to our own edits.** The layout check has to run where files
   *arrive*, not only after mkvpropedit. Candidate seam: the scan/ingest path that already probes
   every file — it is one EBML walk of a few hundred bytes, no subprocess, no ffprobe (ffprobe seeks
   and therefore always passes). Cost is trivial next to the ffprobe already being run.
2. **The verdict is stored at scan time — owner decision, 2026-09-22 — and this overrules
   FR-201-13.** A per-file layout verdict is recorded when the file is scanned, making the dashboard
   cheap on every load and letting negotiation (Part 1's option A) answer without touching the disk.
   `file_integrity` (phase 254) is the obvious neighbour and probably the right home. **FR-201-13 says
   the opposite** — *"no new state is invented … re-running the sweep is the only source of truth"* —
   on the reasoning that a fixed file should leave no residue. That reasoning was sound for a
   sweep-on-demand card and does not survive a dashboard that must be cheap on load. The superseding
   phase must **say it is overruling FR-201-13**, and must say what happens to a stored verdict when a
   file is repaired or replaced (clear it on re-scan; a stale "broken" row is worse than no row).

3. **The suggested fix is already known and measured**: `ffmpeg -i <f> -map 0 -c copy -cues_to_front 1`,
   ~0.6 s for 92 MB, lossless, every stream/language/disposition byte-identical (FR-201-3). The
   surface should offer it, name what it does, and say it does not re-encode. Note FR-201-4: **mkvmerge
   is not a substitute** — it keeps `Tracks` at the front but writes `Cues` at EOF, trading this bug
   for the 2026-08-16 slow-start one.
4. **The dashboard is now in scope** — the owner asked for it explicitly, alongside the series detail
   page. FR-201-10 put the card on Activity; a dashboard summary needs deciding (its own card, or a
   line in an existing health block).
5. **A repaired-on-arrival policy is worth considering and is a real decision, not a detail.** FR-201-5
   was explicit that repairing existing files is *"an operator action on the media library, not
   something a scan may do on its own initiative."* Auto-repairing arrivals would reverse that. It is
   also the only version of Part 2 that makes the defect never reach a viewer — but Part 1 already
   does that, which is a good argument for leaving repair manual and letting the player cope.

## 9. Open questions

- Does the Chromecast receiver / Tizen receiver hang on the same file? Not tested.
- Why does *Tricked by Marvin* S01E01 also show a `PlaybackInfo` with no QoE row (19:18:25)? Its
  layout is **clean** (`Tracks` at 4 280). It is 1.6 GB with ten subtitle tracks; most likely the owner
  simply backed out during a slow start, but it is unconfirmed and may be a second, unrelated thing.
- What N should the Part 1 watchdog use, and is it per-device (the BRAVIA and a Pixel are not
  comparable) or global?
- Should the layout verdict be a first-class field on the media item (so Ravilo's payload can carry
  it), or stay a health-sweep concern?

## 10. How to reproduce

Layout walk of one file. Reads a few hundred bytes from the head and never seeks, which is the whole
point — **ffprobe seeks and therefore always reports the file as fine** (FR-201-2 makes the same point):

```python
def layout(path):
    """('ok'|'tracks-after-cluster'|'no-tracks-found', [elements seen before it])"""
    f = open(path, 'rb')
    def rid():
        b = f.read(1)
        if not b: return None
        for i in range(4):
            if b[0] & (0x80 >> i): return b + f.read(i)
    def rsz():
        b = f.read(1)
        if not b: return None
        for n in range(1, 9):
            if b[0] & (0x80 >> (n - 1)):
                v = b[0] & (0xFF >> n)
                for c in f.read(n - 1): v = (v << 8) | c
                return -1 if v == (1 << (7 * n)) - 1 else v
    while True:                                  # descend into Segment
        i = rid()
        if not i: return None
        s = rsz()
        if i.hex() == '18538067': break
        f.seek(f.tell() + max(s, 0))
    seen = []
    for _ in range(24):                          # walk its children
        i = rid()
        if not i: break
        s = rsz()
        if s is None or s < 0: break
        if i.hex() == '1654ae6b': return ('ok', seen)                    # Tracks
        if i.hex() == '1f43b675': return ('tracks-after-cluster', seen)  # Cluster
        seen.append(i.hex())
        f.seek(f.tell() + s)
    return ('no-tracks-found', seen)
```

Run over both library roots it scanned 8 314 `.mkv` files in well under a minute and found the one
file above.

```bash

# what the release group wrote it with
ffprobe -v error -show_entries format_tags=encoder -of default=nw=1 <file>

# prove Jellyfin is fine with it
curl -s -o /dev/null -w '%{http_code}\n' -H 'Range: bytes=0-1048575' \
  "$JF/Videos/$ID/stream?Static=true&MediaSourceId=$ID&DeviceId=probe&apikey=$TK"
```

## Related

- `specs/requirements/phase-201-mkvpropedit-evicts-the-tracks-element.md` — the original, incl. the
  byte-exact reproduction, FR-201-9…13 (unbuilt), and FR-201-7's deferral of the client guard.
- `specs/requirements/phase-254-a-file-damaged-past-its-first-cluster.md` — the neighbouring
  file-integrity surface; a different defect (payload damage, not header layout) and silent on this file.
- `specs/research-reports/stue-tv-test-sweep-2026-09-16.md`
