"""Phase 317 (FR-317-34, FR-317-35, FR-317-36) — the byte budget, and the proof that it moved no number.

Three halves, and only one of them wants a card:

* **the arithmetic** — a budget and a frame's own size give a chunk count, the floor is one frame, and the
  three geometries of DEB-979's census cost what the amendment's table says they cost. Pure, no CUDA.
* **the floor's refusal** — a chunk of one frame that still will not fit raises [VramExhausted] with the
  geometry, the window's frame count and the peak in it, and hands the card back on the way out. The OOM
  is injected, so this needs no card either.
* **numerical neutrality** — the one FR-317-34 lives or dies by: `shots`, `detect` and `crops` over one
  synthetic ~300-frame window give **identical** output at two different chunk sizes and identical to the
  whole-window path they replace. Synthetic frames; **no library file ever**. Needs the card, and skips
  without one.

    docker run --rm --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_vram_budget.py -v          # the two halves above
    docker run --rm --gpus all --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_vram_budget.py -v          # all three

`/test` comes first on `sys.path`, so the working tree's `measure.py` is what is tested, not the copy
baked into `/app`.
"""
import os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import torch, torch.nn.functional as F, torchvision, numpy as np

import measure

HAS_CUDA = torch.cuda.is_available()
gpu_only = unittest.skipUnless(HAS_CUDA, 'needs the card')


# ── the whole-window path FR-317-34 replaced, verbatim ───────────────────────────────────────────
#
# Copied from `measure.py` at cbb90a15, with `fr` on the DEVICE as it was there — `measure()` moved the
# whole window with `fr = fr_cpu.to(dev)` and these three indexed into it. Nothing about them is mocked:
# they are the second opinion the new code is compared against, and they are the reason this file is
# allowed to repeat code.

def ref_shots(fr, cut=30.):
    small = torch.cat([F.interpolate(fr[i:i + 128].permute(0, 3, 1, 2).float(), size=(36, 64), mode='area')
                       for i in range(0, fr.shape[0], 128)])
    d = (small[1:] - small[:-1]).abs().mean(dim=(1, 2, 3))
    b = [0] + (torch.nonzero(d > cut).flatten() + 1).tolist() + [fr.shape[0]]
    return [(b[i], b[i + 1]) for i in range(len(b) - 1)]


@torch.no_grad()
def ref_detect(fr, idx, W, H, batch=12):
    det, _, RGB_MEAN, decode = measure.models()
    out, dw, dh = {}, int(W * measure.DET_SCALE) // 2 * 2, int(H * measure.DET_SCALE) // 2 * 2
    scale = torch.tensor([W, H, W, H], device=measure.dev)
    for i in range(0, len(idx), batch):
        bi = idx[i:i + batch]
        x = fr[bi].permute(0, 3, 1, 2).float()[:, [2, 1, 0]]
        x = F.interpolate(x, size=(dh, dw), mode='bilinear', align_corners=False) - RGB_MEAN
        with torch.autocast('cuda', dtype=torch.float16, enabled=measure.fp16):
            loc, conf, priors = det(x)
        loc = loc.float().view(len(bi), -1, 4); conf = conf.float().view(len(bi), -1, 2)[..., 1]
        pr = priors.to(measure.dev)
        for j, f in enumerate(bi):
            m = conf[j] > 0.9
            if not m.any(): out[f] = []; continue
            bx = decode(loc[j][m], pr[m], [0.1, 0.2]) * scale
            keep = torchvision.ops.nms(bx, conf[j][m], 0.1)
            out[f] = bx[keep].cpu().numpy().tolist()
    return out


@torch.no_grad()
def ref_crops(fr, fi, bb, cs=0.4):
    from scipy import signal
    s = signal.medfilt(np.maximum(bb[:, 3] - bb[:, 1], bb[:, 2] - bb[:, 0]) / 2, 13)
    y = signal.medfilt((bb[:, 1] + bb[:, 3]) / 2, 13); x = signal.medfilt((bb[:, 0] + bb[:, 2]) / 2, 13)
    boxes = np.stack([np.arange(len(fi)), x - s * (1 + cs), y - s, x + s * (1 + cs), y + s * (1 + 2 * cs)], 1)
    res = []
    for i in range(0, len(fi), 16):
        img = fr[fi[i:i + 16]].permute(0, 3, 1, 2).float() - 110.
        b = torch.tensor(boxes[i:i + 16], device=measure.dev, dtype=torch.float32); b[:, 0] -= i
        res.append((torchvision.ops.roi_align(img, b, (224, 224), 1.0, 2, aligned=True) + 110.).half())
    return torch.cat(res)


# ── a synthetic window ───────────────────────────────────────────────────────────────────────────

def synthetic_window(frames=300, W=640, H=490, cuts=(97, 203)):
    """A uint8 BGR window with structure in it and hard cuts where [cuts] says: enough variation that
    `shots` finds boundaries and `roi_align` has something to interpolate, and reproducible to the byte.

    The shots are far apart in brightness on purpose — `shots`' threshold is a mean absolute difference of
    30 over the whole 36 × 64 reduction, which two fields of random noise do not reach."""
    g = torch.Generator().manual_seed(317)
    fr = torch.empty((frames, H, W, 3), dtype=torch.uint8)
    levels = (20, 140, 240)                                                     # one per shot
    for i in range(frames):
        shot = sum(1 for c in cuts if i >= c)
        f = torch.full((H, W, 3), levels[shot], dtype=torch.uint8)
        f += torch.randint(0, 15, (H, W, 3), dtype=torch.uint8, generator=g)    # grain
        t = (i * 7) % (W - 120)
        f[H // 4: H // 4 + 120, t: t + 120] = 90 + (i % 40)                     # something that moves
        fr[i] = f
    return fr


def face_track(frames, W, H, n=120):
    """A track's frame indices and interpolated boxes, as `tracks()` would hand them to `crops` — invented
    rather than detected, so this case needs no face and no library file."""
    fi = np.arange(0, n)
    size = 0.18 * W
    cx = 0.5 * W + 0.1 * W * np.sin(np.arange(n) / 11.0)
    cy = 0.5 * H + 0.05 * H * np.cos(np.arange(n) / 7.0)
    return fi, np.stack([cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2], 1)


class Budget(unittest.TestCase):
    """FR-317-35 — the arithmetic that replaced 128, 12 and 16. No CUDA anywhere in this class."""

    # DEB-979's census, and the amendment's own table: the width is FIXED, so the squarer the source the
    # taller — and the more expensive — the decoded frame. 720 × 552 is the library's worst frame.
    CENSUS = [((1920, 1080), (640, 360), 230_400), ((720, 552), (640, 490), 313_600),
              ((700, 394), (640, 360), 230_400)]

    def test_the_three_census_geometries_decode_to_the_amendments_table(self):
        for src, want, px in self.CENSUS:
            with self.subTest(source=src):
                self.assertEqual(measure.decode_size(*src), want)
                W, H = want
                self.assertEqual(W * H, px)
                self.assertEqual(measure.frame_bytes(W, H), (px * 3, px * 12))

    def test_the_sd_frame_is_the_expensive_one(self):
        """The inversion DEB-979 found, as an assertion: 720 × 552 costs 36 % more a frame than 1080p, and
        700 × 394 — 16:9 standard definition — costs exactly what 1080p costs."""
        hd = measure.chunk_frame_bytes(*measure.decode_size(1920, 1080), 'detect')
        sd43 = measure.chunk_frame_bytes(*measure.decode_size(720, 552), 'detect')
        sd169 = measure.chunk_frame_bytes(*measure.decode_size(700, 394), 'detect')
        self.assertEqual(hd, sd169)
        self.assertGreater(sd43, hd)
        self.assertAlmostEqual(sd43 / hd, 490 / 360, delta=0.02)

    def test_a_chunk_is_a_budget_divided_by_a_frame(self):
        self.assertEqual(measure.chunk_frames(1_000_000, 100_000), 10)
        self.assertEqual(measure.chunk_frames(1_000_000, 99_999), 10)
        self.assertEqual(measure.chunk_frames(999_999, 100_000), 9)

    def test_the_floor_is_one_frame(self):
        """A budget below a single frame still asks for one: if one frame does not fit, no chunking will,
        and that is the only case [VramExhausted] is for."""
        self.assertEqual(measure.chunk_frames(1, 10_000_000), 1)
        self.assertEqual(measure.chunk_frames(0, 10_000_000), 1)
        self.assertEqual(measure.chunk_frames(-5, 10_000_000), 1)
        self.assertEqual(measure.chunk_frames(1_000_000, 0), 1)

    def test_the_chunk_is_not_a_frame_count(self):
        """The regression that would catch a return to 128 / 12 / 16: at the same budget the squarer file
        gets FEWER frames to a chunk, and every operator's count moves with the geometry."""
        for op in ('shots', 'detect', 'crops'):
            with self.subTest(op=op):
                hd = measure.chunk_frames(1.2e9, measure.chunk_frame_bytes(*measure.decode_size(1920, 1080), op))
                sd = measure.chunk_frames(1.2e9, measure.chunk_frame_bytes(*measure.decode_size(720, 552), op))
                self.assertLess(sd, hd, 'the taller frame must buy a smaller chunk')

    def test_the_plan_reproduces_todays_constants_at_the_geometry_they_were_tuned_at(self):
        """**FR-317-34's guarantee, as arithmetic.** At 640 × 360 — what every 16:9 source in the library
        decodes to, and what every stored row was measured at — the byte target gives back exactly 128, 12
        and 16, so the partition of a window into chunks is the one those rows were produced with."""
        for op, want in measure.CHUNK_REFERENCE_FRAMES.items():
            with self.subTest(op=op):
                self.assertEqual(measure.chunk_plan(*measure.CHUNK_REFERENCE, op)[0], want)

    def test_a_cheaper_frame_does_not_buy_the_detector_a_bigger_batch(self):
        """2.40:1 decodes to 640 × 266, a third cheaper a frame than 16:9 — and the detector's batch stays
        at 12, because cuDNN's answer moves with the batch and those files have stored rows (see
        [measure.CHUNK_CEILING]). `shots` and `crops` are bit-identical at any chunk size and do take the
        extra frames, which is how the byte footprint stays the same instead of falling."""
        wide = measure.decode_size(1920, 800)
        self.assertEqual(wide, (640, 266))
        self.assertEqual(measure.chunk_plan(*wide, 'detect')[0], 12)
        for op in ('shots', 'crops'):
            with self.subTest(op=op):
                self.assertGreater(measure.chunk_plan(*wide, op)[0],
                                   measure.CHUNK_REFERENCE_FRAMES[op])

    def test_a_taller_frame_buys_fewer_frames_everywhere(self):
        """720 × 552 — the 111 episodes of DEB-979's census. Every operator takes fewer frames to a chunk,
        so the chunk's BYTES are what they were at 16:9 instead of 36 % more."""
        sd = measure.decode_size(720, 552)
        for op, tuned in measure.CHUNK_REFERENCE_FRAMES.items():
            with self.subTest(op=op):
                n, per = measure.chunk_plan(*sd, op)
                self.assertLess(n, tuned)
                ref_bytes = tuned * measure.chunk_frame_bytes(*measure.CHUNK_REFERENCE, op)
                self.assertLessEqual(n * per, ref_bytes)
                self.assertGreater(n * per, ref_bytes * 0.7, 'and not wastefully fewer')

    def test_the_budget_is_what_is_left_of_the_per_process_cap(self):
        vram, headroom = measure.VRAM_GB, measure.CHUNK_HEADROOM
        try:
            measure.VRAM_GB, measure.CHUNK_HEADROOM = 2.0, 0.5
            # No card here, so nothing is held: the whole cap, times the headroom.
            self.assertAlmostEqual(measure.chunk_budget_bytes(), 1e9, delta=1.0)
            measure.VRAM_GB = 0.0
            self.assertEqual(measure.chunk_budget_bytes(), 0.0)
        finally:
            measure.VRAM_GB, measure.CHUNK_HEADROOM = vram, headroom


class Floor(unittest.TestCase):
    """FR-317-35/FR-317-36 — what an OOM that survives the halving says, and what it releases."""

    def setUp(self):
        self.budget, self.cache = measure.chunk_budget_bytes, measure.torch.cuda.empty_cache
        self.freed = []
        measure.torch.cuda.empty_cache = lambda *a, **k: self.freed.append(1)

    def tearDown(self):
        measure.chunk_budget_bytes, measure.torch.cuda.empty_cache = self.budget, self.cache

    def oom_step(self, seen):
        def step(a, b):
            seen.append(b - a)
            raise measure.OOM('CUDA out of memory. Tried to allocate 24.00 MiB')
        return step

    def test_the_chunk_is_halved_to_the_floor_and_then_refuses(self):
        W, H = measure.decode_size(720, 552)
        per = measure.chunk_frame_bytes(W, H, 'detect')
        measure.chunk_budget_bytes = lambda: per * 4
        seen = []
        with self.assertRaises(measure.VramExhausted) as e:
            measure.chunked(1125, self.oom_step(seen), 'detect', W, H, 1125)
        self.assertEqual(seen, [4, 2, 1], 'the pass is retried at half the chunk until one frame is left')
        msg = str(e.exception)
        for expect in ('detect', 'one-frame floor', f'{W}×{H}', '1125-frame window', 'peak'):
            self.assertIn(expect, msg)
        self.assertGreaterEqual(len(self.freed), 1, 'the card is handed back on every OOM')

    def test_a_vram_gb_below_one_frame_refuses_without_a_retry(self):
        """The spec's own case: [AVSYNC_VRAM_GB] under a single frame's working set. There is nothing to
        halve — the floor is where it starts — so the first failure is the refusal."""
        W, H = measure.decode_size(720, 552)
        per = measure.chunk_frame_bytes(W, H, 'detect')
        vram = measure.VRAM_GB
        try:
            measure.VRAM_GB = per / 4 / 1e9          # a quarter of one frame
            measure.chunk_budget_bytes = self.budget
            self.assertEqual(measure.chunk_plan(W, H, 'detect')[0], 1)
            seen = []
            with self.assertRaises(measure.VramExhausted) as e:
                measure.chunked(1125, self.oom_step(seen), 'detect', W, H, 1125)
            self.assertEqual(seen, [1])
            self.assertIn(f'{per / 1e6:.1f} MB estimated for one frame', str(e.exception))
        finally:
            measure.VRAM_GB = vram

    def test_a_failure_that_is_not_an_oom_is_not_retried(self):
        """The halving is for the card and nothing else: a bug in a step must surface as itself, once."""
        calls = []
        measure.chunk_budget_bytes = lambda: 1e9

        def step(a, b):
            calls.append((a, b)); raise ValueError('a bug, not a budget')
        with self.assertRaises(ValueError):
            measure.chunked(10, step, 'shots', 640, 360, 10)
        self.assertEqual(len(calls), 1)

    def test_a_pass_that_fits_is_chunked_where_the_plan_says(self):
        per = measure.chunk_frame_bytes(640, 360, 'crops')
        measure.chunk_budget_bytes = lambda: per * 10
        seen = []
        measure.chunked(25, lambda a, b: seen.append((a, b)), 'crops', 640, 360, 25)
        self.assertEqual(seen, [(0, 10), (10, 20), (20, 25)])


@gpu_only
class Neutrality(unittest.TestCase):
    """**FR-317-34's load-bearing test.** The same synthetic window through the new chunked path at two
    different chunk sizes, and through the whole-window path it replaced, must give the same answer — the
    same bytes meeting the same operators in the same order. A single difference here means
    `PIPELINE_VERSION` owes a bump and the fix is not the one the amendment chose.

    720 × 490 rather than 640 × 360 on purpose: the geometry of the 111 episodes DEB-979 found.
    """

    @classmethod
    def setUpClass(cls):
        cls.frames, cls.W, cls.H = 300, 640, 490
        cls.fr_cpu = synthetic_window(cls.frames, cls.W, cls.H)
        cls.fr_dev = cls.fr_cpu.to(measure.dev)

    def at(self, budget, fn, *a, **k):
        """Run [fn] with the byte budget pinned, so two chunk sizes are two runs of the same code."""
        orig = measure.chunk_budget_bytes
        measure.chunk_budget_bytes = lambda: budget
        try:
            return fn(*a, **k)
        finally:
            measure.chunk_budget_bytes = orig

    def budgets(self, op):
        """Two budgets that give two genuinely different chunk sizes for [op] — and neither of them is the
        constant it replaced."""
        per = measure.chunk_frame_bytes(self.W, self.H, op)
        small, big = per * 3, per * 37
        a = self.at(small, measure.chunk_plan, self.W, self.H, op)[0]
        b = self.at(big, measure.chunk_plan, self.W, self.H, op)[0]
        self.assertNotEqual(a, b, f'{op}: both budgets gave a chunk of {a} frames')
        return small, big

    def test_shots_finds_the_same_boundaries_at_any_chunk_size(self):
        small, big = self.budgets('shots')
        ref = ref_shots(self.fr_dev)
        a = self.at(small, measure.shots, self.fr_cpu)
        b = self.at(big, measure.shots, self.fr_cpu)
        self.assertEqual(a, b)
        self.assertEqual(a, ref)
        self.assertGreater(len(ref), 1, 'the synthetic window must actually contain cuts')

    def test_the_area_reduction_is_bit_identical_however_it_is_chunked(self):
        """Why `shots` is safe, asserted on the operator rather than on its caller: `interpolate` in
        `area` mode is per image, so the concatenation of the chunks is the whole window's answer to the
        bit, and the differences `shots` then takes are taken over exactly the same tensor."""
        whole = F.interpolate(self.fr_dev.permute(0, 3, 1, 2).float(), size=(36, 64), mode='area')
        for n in (1, 7, 64, 128):
            with self.subTest(chunk=n):
                part = torch.cat([F.interpolate(self.fr_cpu[i:i + n].to(measure.dev).permute(0, 3, 1, 2).float(),
                                                size=(36, 64), mode='area')
                                  for i in range(0, self.frames, n)])
                self.assertTrue(torch.equal(whole, part))
                del part

    def test_the_detectors_own_output_moves_with_the_batch_which_is_why_it_has_a_ceiling(self):
        """**Why [measure.CHUNK_CEILING] exists**, and the regression that stops anyone removing it.

        cuDNN picks a convolution algorithm per call and the batch is one of that choice's inputs, so the
        same frames at a different batch come back *different* — measured on the RTX 2060 SUPER on
        2026-10-13 at ~2 × 10⁻³ in `loc` and ~1 × 10⁻⁴ in `conf`, and at ~2 × 10⁻⁵ with the autocast off,
        which is what rules out fp16 rounding as the cause. Asserted on `loc` and `conf` straight out of
        S3FD, which every box `detect` returns is a deterministic function of, so no face has to be in
        the frame for this to be the whole of the claim.

        Small enough to change no reading on its own — a box moving a hundredth of a pixel — and not
        small enough to bet FR-317-34 on: a prior whose confidence sits within 10⁻⁴ of the 0.9 threshold
        crosses it, the track changes by a frame and the row changes with it. Hence a chunk that is never
        larger than the 12 frames the stored rows were measured at.
        """
        det, _, RGB_MEAN, _ = measure.models()
        dw, dh = int(self.W * measure.DET_SCALE) // 2 * 2, int(self.H * measure.DET_SCALE) // 2 * 2

        def run(idx):
            x = self.fr_cpu[idx].to(measure.dev).permute(0, 3, 1, 2).float()[:, [2, 1, 0]]
            x = F.interpolate(x, size=(dh, dw), mode='bilinear', align_corners=False) - RGB_MEAN
            with torch.no_grad(), torch.autocast('cuda', dtype=torch.float16, enabled=measure.fp16):
                loc, conf, _ = det(x)
            return loc.float().view(len(idx), -1, 4), conf.float().view(len(idx), -1, 2)
        idx = list(range(0, 24))
        ref_loc, ref_conf = run(idx)                   # the whole set in one batch
        for n in (1, 3, 8):
            with self.subTest(batch=n):
                parts = [run(idx[i:i + n]) for i in range(0, len(idx), n)]
                loc, conf = torch.cat([p[0] for p in parts]), torch.cat([p[1] for p in parts])
                dloc, dconf = (loc - ref_loc).abs().max().item(), (conf - ref_conf).abs().max().item()
                self.assertGreater(dloc, 0.0, f'a batch of {n} agreed to the bit — if cuDNN has become '
                                              f'batch-invariant, CHUNK_CEILING can go, with a re-measure')
                self.assertLess(dloc, 1e-2, f'loc moved by {dloc:.3e} at a batch of {n}')
                self.assertLess(dconf, 1e-3, f'conf moved by {dconf:.3e} at a batch of {n}')

    def test_detect_takes_the_same_batch_as_the_stored_rows_were_measured_at(self):
        """The ceiling, through the real call: at this budget and this geometry the plan asks for 12
        frames at a time — the same partition of `idx`, so the same arithmetic, frame for frame."""
        self.assertEqual(measure.chunk_plan(640, 360, 'detect')[0], 12)
        self.assertLessEqual(measure.chunk_plan(self.W, self.H, 'detect')[0], 12)

    def test_the_detector_sees_the_same_bytes_whether_the_window_or_the_slice_was_moved(self):
        """FR-317-34 for `detect`, at the batch that matters and without a face in the frame: the gather
        that used to happen on the card now happens on the host, and at the same batch of 12 the network's
        own output is **bit-identical**. Everything `detect` returns is a function of these two tensors."""
        det, _, RGB_MEAN, _ = measure.models()
        dw, dh = int(self.W * measure.DET_SCALE) // 2 * 2, int(self.H * measure.DET_SCALE) // 2 * 2

        def run(window, bi):
            x = window[bi].to(measure.dev).permute(0, 3, 1, 2).float()[:, [2, 1, 0]]
            x = F.interpolate(x, size=(dh, dw), mode='bilinear', align_corners=False) - RGB_MEAN
            with torch.no_grad(), torch.autocast('cuda', dtype=torch.float16, enabled=measure.fp16):
                loc, conf, _ = det(x)
            return loc.float(), conf.float()
        for bi in ([0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55], list(range(60, 72))):
            with self.subTest(frames=bi[0]):
                was, now = run(self.fr_dev, bi), run(self.fr_cpu, bi)
                self.assertTrue(torch.equal(was[0], now[0]))
                self.assertTrue(torch.equal(was[1], now[1]))

    def test_detect_returns_the_same_boxes_as_the_whole_window_path(self):
        """The same thing end to end, at the reference geometry where the plan's batch is the ceiling's 12
        and the partition of `idx` is therefore exactly today's. Synthetic frames hold no faces the model
        will admit to, so most of what is compared here is *agreement that there is nothing* — the
        assertion with teeth is the one above, on the network's own output."""
        fr = synthetic_window(150, 640, 360)
        idx = list(range(0, 150, measure.DETECT_EVERY))
        self.assertEqual(measure.chunk_plan(640, 360, 'detect')[0], 12)
        ref = ref_detect(fr.to(measure.dev), idx, 640, 360)
        got = measure.detect(fr, idx, 640, 360)
        self.assertEqual(sorted(got), sorted(ref))
        self.assertEqual(got, ref)

    def test_crops_are_bit_identical_at_any_chunk_size(self):
        fi, bb = face_track(self.frames, self.W, self.H)
        small, big = self.budgets('crops')
        ref = ref_crops(self.fr_dev, fi, bb)
        a = self.at(small, measure.crops, self.fr_cpu, fi, bb)
        b = self.at(big, measure.crops, self.fr_cpu, fi, bb)
        self.assertEqual(tuple(a.shape), tuple(ref.shape))
        self.assertTrue(torch.equal(a, b), 'two chunk sizes gave two different crops')
        self.assertTrue(torch.equal(a, ref), 'the chunked crops differ from the whole-window path')

    def test_the_lip_features_of_those_crops_are_identical_too(self):
        """One step further than the spec asks, because it is the number that reaches the row: the crops
        feed `lip_feats`, and a difference there would be a difference in `offset_frames`."""
        fi, bb = face_track(self.frames, self.W, self.H)
        small, big = self.budgets('crops')
        ref = measure.lip_feats(ref_crops(self.fr_dev, fi, bb))
        a = measure.lip_feats(self.at(small, measure.crops, self.fr_cpu, fi, bb))
        b = measure.lip_feats(self.at(big, measure.crops, self.fr_cpu, fi, bb))
        self.assertTrue(torch.equal(a, b))
        self.assertTrue(torch.equal(a, ref))

    def test_the_card_never_holds_the_whole_window(self):
        """FR-317-34's point, measured: the window is 1 125 frames in production and 300 here, and the
        peak of a chunked pass is a fraction of what the window itself would occupy on the card."""
        window_bytes = self.fr_cpu.numel()
        torch.cuda.empty_cache(); torch.cuda.reset_peak_memory_stats()
        before = torch.cuda.memory_allocated()
        self.at(measure.chunk_frame_bytes(self.W, self.H, 'shots') * 8, measure.shots, self.fr_cpu)
        peak = torch.cuda.max_memory_allocated() - before
        self.assertLess(peak, window_bytes, f'peak {peak / 1e6:.0f} MB vs a window of {window_bytes / 1e6:.0f} MB')


if __name__ == '__main__':
    unittest.main(verbosity=2)
