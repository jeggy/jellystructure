"""Phase 317 (FR-317-32, FR-317-33) — lips are never compared against audio that is not there.

Every case here runs with **no card, no decode and no library file**: the decoders and the GPU half of
`measure()` are stubbed, so what is under test is exactly what DEB-939 read off the four failed sweep jobs
— the lengths. A damaged first audio track decodes 36–45 s of a 45 s window, ffmpeg does not pad a dropped
packet, and frame *k*'s lips were then measured against speech from *k* + the gap. Lengths are the whole
input, which is why this file needs nothing of the real pipeline but its arithmetic.

Run it in the sidecar's own image (importing `measure` needs torch, numpy, scipy), from the repo root:

    docker run --rm --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_short_audio.py -v

`/test` comes first on `sys.path`, so the working tree's `measure.py` is what is tested, not the copy baked
into `/app`.
"""
import json, os, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np, python_speech_features, torch

import measure

RATE = 16000
FULL = 45 * measure.FPS          # the video frames of a full window: 1 125


def pcm_n(n):
    """[n] samples of 16 kHz mono PCM as `decode_audio` returns it. Noise, not silence: the envelope of a
    silent stream is `None` for its own reasons (FR-317-4) and would hide what is asserted here."""
    return (np.random.default_rng(7).standard_normal(int(n)) * 3000).astype(np.int16)


def pcm_of(seconds):
    return pcm_n(RATE * seconds)


class FeatureArithmetic(unittest.TestCase):
    """FR-317-32 — the count is derived from the sample count, never by running the model (the MFCC of a
    45 s window is not free, and the check is made before any model runs). That is only sound while the
    arithmetic and `python_speech_features` agree exactly, so this is the test that keeps the two from
    drifting. CPU only; it does not touch the card."""

    def expected(self, pcm):
        return python_speech_features.mfcc(pcm, RATE).shape[0] // 4 - 5

    def test_a_full_window(self):
        pcm = pcm_of(45)
        self.assertEqual(measure.audio_feature_count(pcm.size), self.expected(pcm))
        # The construction gap, which is AUDIO_FEATURE_LAG's whole justification: 1 125 video frames meet
        # 1 119 audio features in a perfectly healthy 45 s window, every time, whatever the file.
        self.assertEqual(measure.audio_feature_count(pcm.size), 1119)
        self.assertEqual(FULL - 1119, measure.AUDIO_FEATURE_LAG)

    def test_the_shortest_window_the_backend_asks_for(self):
        pcm = pcm_of(10)                          # FR-317-8 never asks for less than this
        self.assertEqual(measure.audio_feature_count(pcm.size), self.expected(pcm))

    def test_a_damaged_window_that_decoded_36_of_45_seconds(self):
        pcm = pcm_of(36)                          # the shape of both 2026-10-13 tensor-shape failures
        self.assertEqual(measure.audio_feature_count(pcm.size), self.expected(pcm))
        self.assertEqual(measure.audio_feature_count(pcm.size), 894)

    def test_one_sample_and_fewer_than_one_window(self):
        """`framesig` returns ONE frame for anything up to its 400-sample window, so the count goes
        negative — and is left negative. A clamp at 0 would hide how far short the audio came, which is
        the one number FR-317-32 needs."""
        for n in (1, 399, 400, 401, 1024):
            self.assertEqual(measure.audio_feature_count(n), self.expected(pcm_n(n)), f'{n} samples')
        self.assertEqual(measure.audio_feature_count(1), -5)
        self.assertEqual(measure.mfcc_frames(400), 1)
        self.assertEqual(measure.mfcc_frames(401), 2)


class ClampBound(unittest.TestCase):
    """FR-317-33 — `distance_curve`'s own bound, driven by lengths alone: the shape both 2026-10-13
    failures were reproduced from, with no library file and none to be used."""

    def curve(self, lf_len, af_len, f0):
        return measure.distance_curve(torch.zeros(lf_len, 1024), torch.zeros(af_len, 1024), f0)

    def test_n_zero_is_not_measured_rather_than_a_NaN_curve(self):
        # The *"Unexpected special floating-point value"* class: every slice empty, `d.mean(0)` over an
        # empty dimension is NaN, and the window's `conf` was NaN.
        md, n = self.curve(10, 5, 5)
        self.assertEqual(n, 0)
        self.assertIsNone(md, 'an empty window is not an answer')

    def test_n_negative_is_not_measured_rather_than_a_tensor_shape_raise(self):
        # The *"The size of tensor a (N) must match the size of tensor b (0)"* class.
        md, n = self.curve(100, 20, 60)
        self.assertEqual(n, -40)
        self.assertIsNone(md)

    def test_a_healthy_tail_track_still_measures(self):
        """No minimum-`n` filter: a face track that ends at the last frame of a healthy window legitimately
        reaches n = 24, and FR-317-7 already weights it against a 250-frame confidence bar, so it cannot
        move a verdict. A floor would drop the last second of every window's evidence to buy nothing."""
        md, n = self.curve(24, 1119, 1095)
        self.assertEqual(n, 24)
        self.assertIsNotNone(md)
        self.assertEqual(tuple(md.shape), (2 * measure.VSHIFT + 1,))
        self.assertTrue(bool(torch.isfinite(md).all()))


class RefusedWindow(unittest.TestCase):
    """FR-317-32 end to end through `measure()`'s window loop, with the decoders and the GPU replaced. The
    loop is the subject: what it refuses, what it reports, and what it does NOT compute."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix='avsync-short-')
        cls.media = os.path.join(cls.tmp, 'subject.mkv')
        with open(cls.media, 'wb') as f:          # only `os.path.exists` reads it
            f.write(b'a stand-in, with no library file behind it')

    def setUp(self):
        self.detections, self.lip_calls = [], []
        self.video_frames = FULL
        self.audio = {'0:a:0': pcm_of(45)}
        measure.dev = 'cpu'
        measure.probe = lambda path, run=None: dict(codec='h264', w=640, h=360, trc='bt709', dur=7200.0)
        # Only the LENGTH of the decoded video is read by anything left unstubbed, so the frames are 1×1:
        # a real 1 125 × 360 × 640 × 3 buffer is 777 MB per window and proves nothing extra.
        measure.decode_video = lambda *a, **k: (torch.zeros((self.video_frames, 1, 1, 3), dtype=torch.uint8), True)
        measure.decode_audio = lambda path, start, dur, spec='0:a:0', run=None: self.audio[spec]
        measure.shots = lambda fr, cut=30.: [(0, fr.shape[0])]
        measure.detect = lambda fr, idx, w, h, **k: (self.detections.append(len(idx)), {})[1]
        measure.audio_feats = lambda pcm: torch.zeros(max(0, measure.audio_feature_count(pcm.size)), 1024)
        measure.lip_feats = lambda cr, **k: self.lip_calls.append(cr) or torch.zeros(0, 1024)
        measure.torch.cuda.synchronize = lambda *a, **k: None
        measure.torch.cuda.empty_cache = lambda *a, **k: None

    def measure_one(self, others=()):
        return measure.measure(self.media, '0:a:0', list(others), [dict(start_s=1620.0, dur_s=45.0)])

    def test_a_window_whose_audio_stopped_at_36_seconds_is_refused(self):
        self.audio['0:a:0'] = pcm_of(36)
        w = self.measure_one()['windows'][0]

        self.assertTrue(w['audio_short'])
        # 1 125 video frames − 894 audio features − the 6-frame construction lag. The amendment's "≈ 219"
        # is the 25-features-a-second estimate; the arithmetic it also specifies gives 225, because the
        # 36 s of audio that did arrive are themselves 6 features short of 36 × 25.
        self.assertEqual(w['audio_short_frames'], 225)
        self.assertEqual(
            w['audio_short_frames'],
            self.video_frames - measure.audio_feature_count(self.audio['0:a:0'].size) - measure.AUDIO_FEATURE_LAG,
        )
        # Reported exactly as a window with no face track is: there is nothing to measure here, which is
        # not *the measurement failed*.
        self.assertIsNone(w['offset_frames'])
        self.assertEqual(w['conf'], 0.0)
        self.assertEqual(w['frames'], 0)
        self.assertEqual(w['faces'], [])
        # ...and the GPU saving, which is the reason the check is made before any face work at all.
        self.assertEqual(self.detections, [], 'the detector must never be called for a refused window')
        self.assertEqual(self.lip_calls, [], 'nor may a single lip feature be computed')

    def test_a_healthy_window_is_not_refused(self):
        """The regression that would otherwise refuse the whole library: the six-frame construction gap is
        in EVERY window, and a bar that cannot tell six from forty refuses everything."""
        w = self.measure_one()['windows'][0]
        self.assertFalse(w['audio_short'])
        self.assertEqual(w['audio_short_frames'], 0)
        self.assertEqual(self.detections, [len(range(0, self.video_frames, measure.DETECT_EVERY))],
                         'a window that is not refused is measured exactly as before')

    def test_the_bar_sits_between_a_lost_packet_and_a_lost_second(self):
        """A `-ss` seek that lands mid-packet loses up to 1 024 samples — 1.6 frames — and that is not a
        damaged file. The shortfall is reported either way, refused or not."""
        for samples, frames, short in ((720000 - 1024, 1, False), (718320, 2, False), (718160, 3, True)):
            self.audio['0:a:0'] = pcm_n(samples)
            w = self.measure_one()['windows'][0]
            self.assertEqual(w['audio_short_frames'], frames, samples)
            self.assertIs(w['audio_short'], short, f'{samples} samples, {frames} frames short')
        self.assertEqual(measure.AUDIO_SHORT_TOL, 2)

    def test_the_envelope_lists_stay_aligned(self):
        """FR-317-4's zip: a refused window drops the measured track's envelope AND every other stream's,
        together, so no stream is matched against a window the measured track never voted in."""
        seen, real_lag = [], measure.envelope_lag
        measure.envelope_lag = lambda ref, other, **k: seen.append((list(ref), list(other))) or real_lag(ref, other, **k)
        try:
            self.audio = {'0:a:0': pcm_of(36), '0:a:1': pcm_of(45)}        # the measured track is refused
            self.measure_one(others=['0:a:1'])
            ref, other = seen[-1]
            self.assertEqual([None], ref)
            self.assertEqual([None], other, 'the other stream is dropped from the same window')

            seen.clear()
            self.audio = {'0:a:0': pcm_of(45), '0:a:1': pcm_of(36)}        # this time the dub is the short one
            self.measure_one(others=['0:a:1'])
            ref, other = seen[-1]
            self.assertIsNotNone(ref[0], 'the measured track is fine and keeps its envelope')
            self.assertEqual([None], other, 'the short stream alone contributes None, in this window alone')
        finally:
            measure.envelope_lag = real_lag

    def test_no_audio_at_all_is_refused_even_on_a_sliver_of_video(self):
        """FR-317-38 — `decode_audio` returns an EMPTY stream instead of raising, and the length test
        refuses it: −5 features leave any window of more than three frames over the bar, and a window of
        one to three frames (the clamped end of a file) is refused by the explicit size check, so no empty
        PCM can ever reach `audio_feats`."""
        reached = []
        measure.audio_feats = lambda pcm: reached.append(pcm.size)
        for frames in (1, 3, 4, FULL):
            self.video_frames, self.audio['0:a:0'] = frames, pcm_n(0)
            w = self.measure_one()['windows'][0]
            self.assertTrue(w['audio_short'], f'{frames} frames over no audio')
            self.assertEqual(w['audio_short_frames'], frames + 5 - measure.AUDIO_FEATURE_LAG)
        self.assertEqual(reached, [], 'an empty PCM never reaches the MFCC')
        self.assertEqual(self.detections, [])

    def test_an_empty_others_stream_contributes_none_on_a_sliver_too(self):
        self.video_frames = 2
        self.audio = {'0:a:0': pcm_of(1), '0:a:1': pcm_n(0)}
        seen, real = [], measure.envelope_lag
        measure.envelope_lag = lambda ref, other, **k: seen.append(list(other)) or real(ref, other, **k)
        try:
            self.measure_one(others=['0:a:1'])
        finally:
            measure.envelope_lag = real
        self.assertEqual(seen[-1], [None])

    def test_the_document_still_serialises_strictly(self):
        """FR-317-30 — the two new fields go through `jsonable` like everything else, and neither is a
        float that could arrive non-finite. `audio_short_frames` is on every window, refused or not: the
        field is not a bare boolean so that the healthy distribution of `v − a` is readable out of
        `file_av_sync` without a new build, and the bar can be re-drawn from evidence."""
        for pcm, short, frames in ((pcm_of(45), False, 0), (pcm_of(36), True, 225)):
            self.audio['0:a:0'] = pcm
            text = json.dumps(measure.jsonable(self.measure_one()), allow_nan=False)
            w = json.loads(text)['windows'][0]
            self.assertIs(w['audio_short'], short)
            self.assertEqual(w['audio_short_frames'], frames)
            self.assertNotIn('nonfinite', w, 'a refused window is not a non-finite one')


class PipelineVersion(unittest.TestCase):
    def test_the_bump_makes_every_stored_row_due_again(self):
        self.assertEqual(measure.PIPELINE_VERSION, 2)
        self.assertEqual(measure.MODEL, 'syncnet_v2+s3fd@907c0b5/2')


if __name__ == '__main__':
    unittest.main()
