"""Phase 317 (FR-317-38, FR-317-40, DEB-1216) — a window past the end of a stream refuses the window, not the file.

The five files DEB-1216 found were not undecodable. They were files whose streams end before their header
says they do, and the sidecar turned *nothing here* into *nothing anywhere*: `decode_video` and `decode_audio`
raised on the first empty window, before FR-317-32's per-window test ran. These cases rebuild each shape from
**generated** material (`ffmpeg -f lavfi`) — no library file is read and none is named — and run the real
decoders, with only the GPU half of `measure()` stubbed (a face-less test pattern has no faces anyway).

Run it in the sidecar's own image (it needs torch, numpy, scipy and jellyfin-ffmpeg), from the repo root:

    docker run --rm --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_undecodable.py -v

`/test` comes first on `sys.path`, so the working tree's `measure.py` is what is tested, not the copy baked
into `/app`.
"""
import io, json, os, shutil, subprocess, sys, tempfile, threading, unittest, urllib.error, urllib.request
from contextlib import redirect_stdout
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import measure
import server

REPEAT = '    Last message repeated 3 times\n'
MATROSKA = '[matroska,webm @ 0x55eca1297b40] File ended prematurely\n'


def stub_gpu():
    """The card is never opened: a test pattern has no faces, so the honest answer for a window that does
    have a picture is *no track*, which is exactly what an empty detection gives."""
    measure.dev = 'cpu'
    measure.detect = lambda *a, **k: {}
    measure.audio_feats = lambda pcm: None
    measure.torch.cuda.synchronize = lambda *a, **k: None
    measure.torch.cuda.empty_cache = lambda *a, **k: None


class LastLine(unittest.TestCase):
    """FR-317-40 — ffmpeg's log de-duplication ends a repeated tail with a line that says nothing."""

    def test_the_repeat_line_is_skipped_and_the_real_one_returned(self):
        self.assertEqual(measure.last_line(MATROSKA + REPEAT), '[matroska,webm @ 0x55eca1297b40] File ended prematurely')

    def test_a_tail_made_only_of_repeat_lines_is_empty(self):
        self.assertEqual(measure.last_line(REPEAT + 'Last message repeated 1 time\n   \n'), '')

    def test_a_prefixed_repeat_line_is_skipped_too(self):
        self.assertEqual(measure.last_line('real line\n[aac @ 0x1] Last message repeated 12 times\n'), 'real line')

    def test_a_real_line_that_merely_mentions_it_is_kept(self):
        line = 'cannot decode: Last message repeated 3 times is not what this says'
        self.assertEqual(measure.last_line(line), line)

    def test_nothing_and_blank_are_the_fallback_the_callers_already_have(self):
        self.assertEqual(measure.last_line(''), '')
        self.assertEqual(measure.last_line(None), '')
        self.assertEqual(measure.last_line('first\n\nsecond\n\n'), 'second')


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'needs ffmpeg and ffprobe')
class Generated(unittest.TestCase):
    """FR-317-38 on real Matroska files built here, each the shape of one DEB-1216 failure."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix='avsync-undec-')
        stub_gpu()
        cls.saved = (measure.DECODE_STALL_S, measure.MEASURE_MAX_S)
        measure.DECODE_STALL_S, measure.MEASURE_MAX_S = 30.0, 300.0
        cls.files = {}
        cls.srv = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f'http://127.0.0.1:{cls.srv.server_address[1]}'

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        measure.DECODE_STALL_S, measure.MEASURE_MAX_S = cls.saved
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def build(cls, name, inputs, maps, extra=()):
        """`inputs` are lavfi sources as (spec, seconds); one file, video H.264, audio AAC."""
        out = os.path.join(cls.tmp, name)
        cmd = ['ffmpeg', '-v', 'error', '-y']
        for spec, secs in inputs:
            cmd += ['-f', 'lavfi', '-t', str(secs), '-i', spec]
        cmd += [a for m in maps for a in ('-map', m)]
        cmd += ['-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', *extra, out]
        subprocess.run(cmd, check=True, capture_output=True)
        return out

    def media(self, name):
        """Built once per file name: each file is a few hundred KB of test pattern."""
        if name not in self.files:
            self.files[name] = getattr(self, f'_build_{name}')()
        return self.files[name]

    # A header that says 60 s over a picture that ends at 30 s: `-2620`/`-2621`. Matroska takes its duration
    # from the longest stream, so the audio is what makes the header say 60.
    def _build_video_short(self):
        return self.build('video_short.mkv', [('testsrc=s=320x240:r=25', 30), ('sine=f=440', 60)], ['0:v', '1:a'])

    # `-2619`: the only audio track stops at 10 s of a 60 s file.
    def _build_audio_short(self):
        return self.build('audio_short.mkv', [('testsrc=s=320x240:r=25', 60), ('sine=f=440', 10)], ['0:v', '1:a'])

    # `-2618`: a full-length track measured, and a second one that stops early as the `others` entry.
    def _build_two_audio(self):
        return self.build('two_audio.mkv',
                          [('testsrc=s=320x240:r=25', 60), ('sine=f=440', 10), ('sine=f=660', 60)],
                          ['0:v', '1:a', '2:a'])

    # `-2617`: the file stops at a few seconds of itself. The header is written first, so it still says 60.
    def _build_truncated(self):
        full = self.build('truncated_src.mkv', [('testsrc=s=320x240:r=25', 60), ('sine=f=440', 60)], ['0:v', '1:a'])
        out = os.path.join(self.tmp, 'truncated.mkv')
        with open(full, 'rb') as f, open(out, 'wb') as g:
            g.write(f.read(150_000))
        return out

    def windows(self, *starts, dur=10.0):
        return [dict(start_s=s, dur_s=dur) for s in starts]

    def test_a_window_past_the_picture_is_refused_and_the_file_is_not(self):
        path = self.media('video_short')
        buf = io.StringIO()
        with redirect_stdout(buf):
            r = measure.measure(path, '0:a:0', [], self.windows(5.0, 48.0))
        early, late = r['windows']

        self.assertTrue(late['no_picture'], 'the 48 s window lands past a 30 s picture')
        self.assertEqual((late['frames'], late['faces']), (0, []))
        self.assertIsNone(late['offset_frames'])
        self.assertFalse(late['audio_short'], 'its audio was never asked for')
        self.assertNotIn('no_picture', early, 'a window with a picture does not carry the key')
        self.assertFalse(early['audio_short'])
        self.assertIn('window 48.0s has no picture', buf.getvalue(), 'the reason is readable in the log')

    def test_a_stream_with_no_audio_in_the_window_is_short_audio_not_an_error(self):
        path = self.media('audio_short')
        r = measure.measure(path, '0:a:0', [], self.windows(20.0, 40.0))
        self.assertEqual([w['audio_short'] for w in r['windows']], [True, True])
        for w in r['windows']:
            self.assertNotIn('no_picture', w)
            self.assertEqual((w['offset_frames'], w['frames']), (None, 0))
            # FR-317-32's own arithmetic: 250 frames − (−5) − 6 for an empty PCM.
            self.assertEqual(w['audio_short_frames'], 250 + 5 - measure.AUDIO_FEATURE_LAG)

    def test_an_others_stream_that_stops_early_contributes_none_and_the_file_is_measured(self):
        path = self.media('two_audio')
        # `0:a:1` is the full-length stream: measure it, and let the 10 s one be the `others` entry.
        r = measure.measure(path, '0:a:1', ['0:a:0'], self.windows(20.0, 40.0))
        self.assertEqual([w['audio_short'] for w in r['windows']], [False, False])
        self.assertEqual(len(r['others']), 1)
        self.assertIsNone(r['others'][0]['lag_frames'], 'no window voted for the short stream')

    def test_one_window_with_a_picture_is_an_answer(self):
        path = self.media('video_short')
        r = measure.measure(path, '0:a:0', [], self.windows(48.0, 5.0, 55.0))
        self.assertEqual([w.get('no_picture', False) for w in r['windows']], [True, False, True])

    def test_no_window_with_a_picture_is_a_422_with_the_real_line(self):
        path = self.media('truncated')
        with self.assertRaises(measure.Undecodable) as caught:
            measure.measure(path, '0:a:0', [], self.windows(12.0, 30.0, 48.0))
        self.assertIn('File ended prematurely', str(caught.exception))
        self.assertNotIn('Last message repeated', str(caught.exception))

    def test_the_422_over_http_carries_that_line_and_the_log_does_too(self):
        path = self.media('truncated')
        req = urllib.request.Request(self.base + '/measure',
                                     data=json.dumps(dict(path=path, windows=self.windows(12.0, 30.0))).encode(),
                                     headers={'Content-Type': 'application/json'})
        buf = io.StringIO()
        with redirect_stdout(buf):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(req, timeout=120)
        self.assertEqual(caught.exception.code, 422)
        body = json.loads(caught.exception.read())
        self.assertIn('File ended prematurely', body['error'])
        self.assertNotIn('Last message repeated', body['error'])
        self.assertIn('File ended prematurely', buf.getvalue(), 'acceptance 35 reads the sidecar log')

    def test_a_file_with_no_video_stream_is_still_a_422(self):
        path = os.path.join(self.tmp, 'audio_only.mka')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-t', '5', '-i', 'sine=f=440', path],
                       check=True, capture_output=True)
        with self.assertRaises(measure.Undecodable) as caught:
            measure.measure(path, '0:a:0', [], self.windows(1.0))
        self.assertIn('no video stream', str(caught.exception))

    def test_a_refused_window_serialises_strictly(self):
        path = self.media('video_short')
        text = json.dumps(measure.jsonable(measure.measure(path, '0:a:0', [], self.windows(5.0, 48.0))), allow_nan=False)
        self.assertTrue(json.loads(text)['windows'][1]['no_picture'])


if __name__ == '__main__':
    unittest.main()
