"""Phase 317 (FR-317-30) — nothing the sidecar serialises is a bare `NaN` or an `Infinity`.

Every case here runs on a **hand-written response document**: no card, no library file, no decode. That is
the whole point — what failed on 2026-10-13 was not the measurement but the last step of it, and a document
with a `NaN` in a known place reaches that step in milliseconds.

Run it in the sidecar's own image (importing `measure` needs torch, numpy, scipy), from the repo root:

    docker run --rm --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_json_finite.py -v

`/test` comes first on `sys.path`, so the working tree's `measure.py` is what is tested, not the copy baked
into `/app`.
"""
import json, os, sys, threading, unittest, urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import measure
import server

NAN = float('nan')
INF = float('inf')


def strict(text):
    """`json.loads` is NOT a strict JSON parser: it accepts `NaN`, `Infinity` and `-Infinity` by default,
    which is exactly why this bug could ship unnoticed from the Python side. `parse_constant` is the hook
    that makes it refuse them — the same answer `kotlinx.serialization` gives the backend."""
    def refuse(token):
        raise AssertionError(f'the document carries a bare {token}')
    return json.loads(text, parse_constant=refuse)


def document():
    """One `/measure` answer as `measure()` builds it, with the two values the prod rows failed on: a
    non-finite `conf` on a window (its face track's own `conf` too) and a non-finite `corr` on another
    audio stream. Everything else is a plausible measurement, so a sanitiser that flattened the document
    would be caught here as surely as one that let a `NaN` through."""
    return dict(
        windows=[
            dict(start_s=1080.0, offset_frames=-1, conf=3.5, frames=800,
                 faces=[dict(at_s=1081.2, frames=800, offset_frames=-1, conf=3.5)]),
            dict(start_s=2700.0, offset_frames=2, conf=NAN, frames=640,
                 faces=[dict(at_s=2701.0, frames=640, offset_frames=2, conf=NAN)]),
        ],
        others=[dict(audio='0:a:1', lag_frames=0, corr=INF)],
        decode='nvdec',
        seconds=8.2,
    )


class JsonableTest(unittest.TestCase):

    def test_a_non_finite_conf_and_corr_become_null_and_the_object_says_so(self):
        out = measure.jsonable(document())
        bad = out['windows'][1]
        self.assertIsNone(bad['conf'])
        self.assertTrue(bad['nonfinite'], 'the window a replacement happened in must say so')
        self.assertIsNone(bad['faces'][0]['conf'])
        self.assertTrue(bad['faces'][0]['nonfinite'])
        other = out['others'][0]
        self.assertIsNone(other['corr'])
        self.assertTrue(other['nonfinite'])
        # FR-317-30 — a null `conf` is *no answer*, and the rest of the answer is untouched: the window
        # that measured cleanly is byte-for-byte what it was, and carries no `nonfinite` key at all.
        good = out['windows'][0]
        self.assertEqual(3.5, good['conf'])
        self.assertEqual(-1, good['offset_frames'])
        self.assertNotIn('nonfinite', good)
        self.assertNotIn('nonfinite', good['faces'][0])
        self.assertNotIn('nonfinite', out, f'nothing non-finite sat in the top-level object: {out}')

    def test_the_document_serialises_under_a_parser_that_refuses_NaN(self):
        text = json.dumps(measure.jsonable(document()), allow_nan=False)
        self.assertNotIn('NaN', text)
        self.assertNotIn('Infinity', text)
        strict(text)                                   # the assertion: a strict reader takes it

    def test_the_unsanitised_document_is_what_broke_prod(self):
        # The baseline, so the test says what it is protecting against: `json.dumps` writes bare `NaN` and
        # `Infinity`, and a strict reader — the backend's — refuses the whole document.
        raw = json.dumps(document())
        self.assertIn('NaN', raw)
        self.assertIn('Infinity', raw)
        with self.assertRaises(AssertionError):
            strict(raw)

    def test_an_integer_stays_an_integer(self):
        # `offset_frames`, `frames` and `lag_frames` decode into Kotlin `Int`s: a sanitiser that turned
        # `-1` into `-1.0` on its way past would fail every one of them at the same last step.
        out = measure.jsonable(dict(offset_frames=-1, frames=800, lag_frames=0, start_s=1080.0))
        text = json.dumps(out)
        self.assertIn('"offset_frames": -1', text)
        self.assertIn('"frames": 800', text)
        self.assertIn('"lag_frames": 0', text)
        self.assertIn('"start_s": 1080.0', text)

    def test_numpy_scalars_the_measurement_actually_produces(self):
        import numpy as np
        out = measure.jsonable(dict(
            corr=np.float32('nan'), lag=np.int64(-3), conf=np.float64(2.5), neg=-INF, ok=np.float32(0.5)))
        self.assertIsNone(out['corr'])
        self.assertIsNone(out['neg'])
        self.assertTrue(out['nonfinite'])
        self.assertEqual(-3, out['lag'])
        self.assertEqual(2.5, out['conf'])
        self.assertAlmostEqual(0.5, out['ok'])
        strict(json.dumps(out, allow_nan=False))

    def test_what_is_not_a_number_is_not_rewritten(self):
        out = measure.jsonable(dict(decode='nvdec', ok=True, missing=None, empty=[], nested=[[NAN]]))
        self.assertEqual('nvdec', out['decode'])
        self.assertIs(True, out['ok'])
        self.assertIsNone(out['missing'])
        self.assertEqual([], out['empty'])
        self.assertEqual([[None]], out['nested'])
        # A null that was already null is not a replacement: `missing` must not make the object `nonfinite`.
        self.assertNotIn('nonfinite', out)


class RouteTest(unittest.TestCase):
    """The same document through the real HTTP face, because `send_json` is where FR-317-30 is enforced and
    a sanitiser applied anywhere else would leave the route as it was."""

    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.real_measure = measure.measure
        measure.measure = lambda *a, **k: document()   # no decode: the answer is the fixture above

    @classmethod
    def tearDownClass(cls):
        measure.measure = cls.real_measure
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def post(self, route, body):
        req = urllib.request.Request(f'http://127.0.0.1:{self.port}{route}', json.dumps(body).encode(),
                                     {'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()

    def test_measure_answers_a_document_a_strict_parser_takes(self):
        status, text = self.post('/measure', dict(path=__file__, windows=[dict(start_s=0, dur_s=45)]))
        self.assertEqual(200, status)
        self.assertNotIn('NaN', text)
        self.assertNotIn('Infinity', text)
        body = strict(text)
        self.assertIsNone(body['windows'][1]['conf'])
        self.assertTrue(body['windows'][1]['nonfinite'])
        self.assertIsNone(body['others'][0]['corr'])
        self.assertEqual(2, body['windows'][1]['offset_frames'])

    def test_health_and_stat_are_serialised_through_the_same_place(self):
        with urllib.request.urlopen(f'http://127.0.0.1:{self.port}/health', timeout=30) as r:
            strict(r.read().decode())
        status, text = self.post('/stat', dict(path=__file__))
        self.assertEqual(200, status)
        self.assertTrue(strict(text)['exists'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
