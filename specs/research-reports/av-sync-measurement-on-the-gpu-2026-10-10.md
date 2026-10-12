# Measuring A/V sync on the GPU — what it costs to check the whole library (2026-10-10)

Investigation behind phase 317 after the owner decided (2026-10-10) that **every file is checked**, and asked
first how to use the GPU properly for it. Prototype: `avsync/` (`measure.py` + `Dockerfile`), not wired.
Titles below are stand-ins (household library, `check-deanonymization.sh`).

## Summary

| | syncnet_python as published | rebuilt on the GPU (`avsync/measure.py`) |
|---|---|---|
| one 45 s window, 1080p H.264 | **~160 s** (124 s pipeline + ~35 s SyncNet) | **~1.6 s of GPU** + decode in the background |
| GPU busy | 16 % | 58 % (1 process) · **84 % (2 processes)** |
| one file (3 × 45 s) | ~8 min | **5.6 s** (2 processes, 28-file random sample) |
| the library (~9 600 files) | ~53 days | **~15 h** on the RTX 2060 SUPER |
| VRAM | ~2 GB | ≤ 2 GB per process, capped |

The same two models (S3FD faces, SyncNet v2) give the same answer on the reference window: the published pipeline
read **+4 frames, conf 6.08 / 3.71** on its two confident faces; the rebuilt one reads **+4, conf 6.34 / 3.97**
(and +4 / 6.49 / 3.97 in its fastest settings).

## Where the published pipeline spends its time

cProfile, one 45 s window (1 129 frames), GPU 1:

| | s | what it is |
|---|---|---|
| `cv2.imread` | 35 | every frame written to JPEG by ffmpeg, then read back — twice (detection, then crops) |
| `detect_faces` | 52 | batch 1; the network itself is **2.2 s** of `conv2d` — the rest is a Python NMS loop (29 s) and per-call prior boxes |
| `crop_video` + ffmpeg | 36 | every face crop encoded to XVID, re-muxed with audio, decoded again by SyncNet |
| SyncNet | ~35 | reads those AVIs back, batch 20 |

The GPU did ~3 s of useful work in ~160 s.

## What the rebuild does instead

1. **Decode on the GPU, scale on the GPU, only the small frame crosses PCIe.** NVDEC + `scale_cuda` to 640 px wide,
   `fps=25` (SyncNet's rate; offsets are then in 40 ms frames whatever the source rate). Frames are read straight
   into a tensor (`readinto`) — the `subprocess.run(capture_output)` + `bytearray` copy alone cost ~5 s per window.
2. **HDR is tone-mapped first**, with Jellyfin's own chain (`tonemap_cuda … bt2390`). Without it a PQ frame is flat
   grey and the detector misses faces it finds after tone-mapping (a 4K HDR10 remux: zero faces → faces).
3. **jellyfin-ffmpeg** (7.1) in the image: Ubuntu's ffmpeg 4.4 has no `tonemap_cuda` and its `scale_cuda` has no
   `format=` (10-bit sources). Same binary family Jellyfin runs on this host.
4. **AV1 goes straight to the CPU** (dav1d, ~4.8 s per window). Neither card's NVDEC decodes AV1 (P4000 = Pascal,
   2060 SUPER = Turing), and asking NVDEC first costs ~20 s before it fails. Codec is known from the probe.
5. **Faces on every 5th frame**, batched (12), FP16, at half the decoded size; NMS on the GPU (`torchvision.ops.nms`),
   prior boxes cached. Detection 12 s → 0.7 s per window.
6. **Linking sparse detections by centre distance**, not IoU > 0.5: five frames apart a walking actor's box overlaps
   only ~0.45 and every track broke after five samples. Centre within half a face, size within 1.5×.
7. **Tracks of 2 s, not 4 s.** Film shots average 3–4 s; with a 100-frame minimum a 4K film window gave no faces.
8. **Crops with `roi_align` on the GPU** (the original geometry: median-filtered box, 0.4 padding, grey 110 border),
   never written anywhere.
9. **Speech features once per window**, shared by every face; lip features batched (64), FP16.
10. **One answer per window**: every face's distance curve, weighted by its length, summed — far steadier than
    picking one face (a cartoon window reads conf < 1.6 summed; a dialogue window 2–6).
11. **Decode runs ahead** in a pool (3–4 windows) while the GPU works on the current one. With 1 process the GPU
    waits on nothing after the first file; with 2 processes on one card SM rises from 58 % to 84 %.
12. **VRAM capped per process** (`set_per_process_memory_fraction`, 2 GB) and released between windows: two
    uncapped processes peaked at 7.1 GB of the 8 GB card, which Immich's ML also uses. Capped, the same run was no
    slower (152 s vs 157 s) and peaked at 6.5 GB — what remains is each ffmpeg's own CUDA context and NVDEC surfaces,
    so the **number of GPU decoders** is the other half of the VRAM budget.

Tried and left out: cuDNN autotune (−25 % detection, but every new lip-batch shape re-tunes — needs fixed shapes
first); 960 px decode (no better answers, 1.6× the decode and memory); NVDEC for 1080p H.264 is *not* faster than
the CPU here (1.3 s vs 3.0 s per window, CUDA start-up) — it is for 4K HEVC, which is the expensive case.

## Which card

| GPU | | today |
|---|---|---|
| 0 · Quadro P4000 (Pascal) | unlimited NVENC sessions, no tensor cores | Jellyfin's transcodes (`cuda:0` by default) and phase 313's encoder (`cudaDevice = 0`) |
| 1 · RTX 2060 SUPER (Turing) | tensor cores (FP16), NVDEC | Immich ML (`device_ids: ['1']`), idle most of the time |

**GPU 1.** The measurer is FP16 inference — what Turing's tensor cores are for — and it then never competes with a
viewer's transcode for NVDEC/NVENC or SMs. (Phase 317's first draft leaned to device 0; that was before this table.)
The sweep's real contention is the **disks** (both libraries are on spinning drives): it reads ~3 × 45 s per file,
so it yields while anything plays (`deferWhilePlaying`), not while the GPU is busy.

## What the sample said (42 random files + 2 known)

- **In-sync material reads −1 to 0**, not 0: every confident window on ~20 live-action titles (sitcoms, drama,
  reality, films, 4K remuxes) sat at −1 or 0, a few −2. SyncNet's zero is ~half a frame off on this material. The
  verdict should measure from the **library's own median**, not from 0.
- **Animation, nature documentaries, sports crowds**: no faces or conf < 1.6 — `unknown`, as expected (Animation is
  skipped by decision).
- **The reported broadcast** (*Broadcast K*, stand-in): +3 / +4 at every window — and its 160 ms remux −1 / 0 at
  the same seven windows: the 4-frame shift lands exactly on the library's in-sync reading.
- **A second real one, found by the sample**: a 2014 film (an 800p BluRay encode, *Film T*), 9 windows:
  +2, +3, +2, +3, +4, —, +4, +4, +2/+3 — audio 80–160 ms early, possibly growing. Not yet checked by ear.

## Throughput, measured

| run | files | wall | per file | GPU SM | notes |
|---|---|---|---|---|---|
| 1 process, 2 decoders, old decode path | 15 | 182 s | 12.1 s | 38 % | two AV1 files: NVDEC tried first (~20 s lost each) |
| 1 process, 4 decoders | 13 | 98 s | 7.5 s | 60 % | two 4K remuxes |
| 1 process, 4 decoders (repo prototype) | 14 | 90 s | 6.4 s | 58 % | three 4K remuxes; no decode waits after file 1 |
| 2 processes × 3 decoders | 28 | 157 s | 5.6 s | 84 % | the two samples above at once; **7.1 GB VRAM peak** |
| 1 process, **one file per request** (`--one-file`: no decoding into the next file), 2 GB cap | 14 | 115 s | **8.2 s** | 50 % | what one job per file on one queue lane gets; **3.9 GB VRAM peak** |
| **2 processes × 3 decoders, 2 GB cap each** | 28 | 152 s | **5.4 s** | **87 %** | same; **6.5 GB peak** — the rest is the six ffmpeg NVDEC contexts |

~640 files/h at best (two files in flight) → the ~9 600-file library in ~15 h of GPU 1. **One file per request** — what
jellystructure's per-file job rows give, one at a time on one lane — is **8.2 s per file, ~440/h, ~22 h**: phase 317
starts there (simplest to integrate, 3.9 GB of VRAM) and leaves two-in-flight to a later batch claim.

Two memory fixes came out of the one-file run: a 45 s face track's crops were one ~680 MB float tensor (OOM under the
2 GB cap) — tracks are now cut into ≤ 10 s pieces and crops kept in FP16, cropped 16 frames at a time.
