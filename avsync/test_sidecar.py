"""Phase 317 (FR-317-27, FR-317-28) — the sidecar's bounds, proven on a fake decoder.

Every case here runs with **no card and no library file**: a fake `ffmpeg`/`ffprobe` on `PATH` plays the
decoder, and the GPU half of `measure()` is stubbed, so what is under test is exactly the part DEB-897
wedged — the pipes, the watchdog, the process group, the lock and `/health`.

Run it in the sidecar's own image (it needs torch, numpy, scipy), from the repo root:

    docker run --rm --entrypoint python -v "$PWD/avsync:/test:ro" -w /tmp \
      jellystructure-avsync:latest /test/test_sidecar.py -v

`/test` comes first on `sys.path`, so the working tree's `measure.py` is what is tested, not the copy
baked into `/app`.
"""
import json, os, signal, subprocess, sys, tempfile, threading, time, unittest, urllib.error, urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import measure
import server

W, H = measure.RES, 360          # the fake ffprobe reports 640×360, so measure() asks for exactly this
FRAME = W * H * 3
STALL_S = 2.0                    # the real default is 60 s; the behaviour is the same and the wait is not


# ── the fake decoder ─────────────────────────────────────────────────────────────────────────────

# One script standing in for both binaries. Its behaviour comes from FAKE_MODE in the environment, which
# the test process owns — the children are spawned from inside this process, so changing it between
# requests changes what the next decoder does.
FAKE = r'''#!/usr/bin/env python3
import os, sys, time
mode, pids = os.environ.get('FAKE_MODE', 'frames'), os.environ.get('FAKE_PIDS', '')
argv = sys.argv[1:]
me = os.path.basename(sys.argv[0])

if pids:
    with open(pids, 'a') as f: f.write(f'{me} {os.getpid()}\n')

if me == 'ffprobe':
    if mode == 'novideo': sys.exit(0)
    print('h264,640,360,bt709' if 'stream=codec_name,width,height,color_transfer' in argv else '7200.0')
    sys.exit(0)

audio = 's16le' in argv
dur = float(argv[argv.index('-t') + 1])
out = sys.stdout.buffer

if mode in ('stall', 'ceiling'):
    # ffmpeg's own children keep the GPU context: a kill that does not take the process group leaves them.
    child = os.fork()
    if child == 0:
        if pids:
            with open(pids, 'a') as f: f.write(f'{me}-child {os.getpid()}\n')
        time.sleep(600)
        os._exit(0)

if audio:
    n = int(16000 * dur) * 2
    if mode == 'stall':
        out.write(b'\0' * 4096); out.flush(); time.sleep(600)
    step = 16000 * 2
    for i in range(0, n, step):
        out.write(os.urandom(min(step, n - i))); out.flush()
        if mode in ('slow', 'ceiling'): time.sleep(0.02)
    sys.exit(0)

frames, fsz = int(dur * 25), 640 * 360 * 3
if mode == 'stall':
    out.write(b'\x10' * fsz); out.flush(); time.sleep(600)
for i in range(frames):
    if mode == 'noisy':
        # Well over a pipe buffer (64 KiB on Linux) of stderr while stdout keeps flowing: the mutual wait
        # that is the leading candidate for DEB-897. 2 KiB a frame over 45 s is ~2 MiB.
        sys.stderr.write(('x' * 2000) + '\n'); sys.stderr.flush()
    out.write(b'\x10' * fsz); out.flush()
    if mode == 'slow': time.sleep(0.05)
    if mode == 'ceiling': time.sleep(0.2)
sys.exit(0)
'''


def fake_on_path(tmp):
    d = os.path.join(tmp, 'bin')
    os.makedirs(d, exist_ok=True)
    for name in ('ffmpeg', 'ffprobe'):
        p = os.path.join(d, name)
        with open(p, 'w') as f: f.write(FAKE)
        os.chmod(p, 0o755)
    os.environ['PATH'] = d + os.pathsep + os.environ['PATH']
    return d


def procs(pidfile):
    """Whatever is left in /proc of the pids the fake wrote down, as (what, pid, state)."""
    out = []
    with open(pidfile) as f: lines = f.read().splitlines()
    for line in lines:
        what, pid = line.split()
        try:
            with open(f'/proc/{int(pid)}/stat') as f: state = f.read().split(') ', 1)[1].split()[0]
        except OSError:
            continue
        out.append((what, int(pid), state))
    return out


def alive(pidfile):
    """The ones still running or sleeping. `Z` is not one of them: a zombie has already been killed and is
    only waiting to be reaped — and the fake's *forked* child is reparented on its parent's death to this
    process, which is PID 1 inside the container and does not reap strangers. That `Z` is the harness's
    own artefact; what it proves is that the group signal reached a process we never spawned ourselves."""
    return [p for p in procs(pidfile) if p[2] != 'Z']


# ── the GPU half, stubbed ────────────────────────────────────────────────────────────────────────

def stub_gpu():
    """Everything after the decode, replaced: frames of flat grey have no faces anyway, so a measurement
    that finds none is the honest answer for the fake's output, and the card is never opened."""
    measure.dev = 'cpu'
    measure.detect = lambda *a, **k: {}
    measure.audio_feats = lambda pcm: None
    measure.torch.cuda.synchronize = lambda *a, **k: None
    measure.torch.cuda.empty_cache = lambda *a, **k: None


class SidecarCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix='avsync-test-')
        fake_on_path(cls.tmp)
        stub_gpu()
        measure.DECODE_STALL_S = STALL_S
        measure.MEASURE_MAX_S = 900.0
        cls.media = os.path.join(cls.tmp, 'subject.mkv')
        with open(cls.media, 'wb') as f:          # only `os.path.exists` reads it: the fake answers for it
            f.write(b'a stand-in, with no library file behind it')
        cls.srv = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f'http://127.0.0.1:{cls.srv.server_address[1]}'

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        self.pidfile = os.path.join(self.tmp, f'pids-{self.id().rsplit(".", 1)[-1]}')
        os.environ['FAKE_PIDS'] = self.pidfile
        open(self.pidfile, 'w').close()

    def mode(self, m):
        os.environ['FAKE_MODE'] = m

    def post(self, route, body, timeout=120):
        req = urllib.request.Request(self.base + route, data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def get_health(self):
        with urllib.request.urlopen(self.base + '/health', timeout=10) as r:
            return json.loads(r.read())

    def windows(self, start=1620.0, dur=10.0):
        return [dict(start_s=start, dur_s=dur)]


class Watchdog(SidecarCase):
    def test_a_decoder_that_produces_nothing_is_killed_and_answered(self):
        """FR-317-27, acceptance 20 — a 500 with the reason, nothing left running, and the lock freed."""
        self.mode('stall')
        t0 = time.monotonic()
        code, body = self.post('/measure', dict(path=self.media, windows=self.windows()))
        took = time.monotonic() - t0
        self.assertEqual(code, 500, body)
        self.assertEqual(body['error'], f'decode stalled after {measure.secs(STALL_S)}s on window 1620.0s')
        self.assertLess(took, STALL_S + 20, f'answered in {took:.1f}s')
        time.sleep(0.5)
        self.assertEqual(alive(self.pidfile), [],
                         'neither the decoder nor the child it forked may be left running')
        self.assertEqual([p for p in procs(self.pidfile) if p[0] == 'ffmpeg'], [],
                         'the decoder itself is reaped by Popen.wait() — not left a zombie')
        h = self.get_health()
        self.assertFalse(h['busy'])
        self.assertEqual(h['busy_for_s'], 0)

        self.mode('frames')                       # and the next measurement is served
        code, body = self.post('/measure', dict(path=self.media, windows=self.windows(dur=3.0)))
        self.assertEqual(code, 200, body)
        self.assertEqual(len(body['windows']), 1)
        self.assertIsNone(body['windows'][0]['offset_frames'])

    def test_a_stalled_audio_decode_is_bounded_too(self):
        self.mode('stall')
        with self.assertRaises(measure.DecodeStalled) as e:
            measure.decode_audio(self.media, 10.0, 45.0)
        self.assertIn('decode stalled after', str(e.exception))
        self.assertIn('audio 0:a:0', str(e.exception))

    def test_a_noisy_stderr_does_not_deadlock(self):
        """The regression test for the leading candidate cause: ~2 MiB of stderr while stdout streams.
        Against the old code (stdout read to completion, `p.stderr.read()` only afterwards) this never
        returns, so the decode runs in a thread and the assertion is that it finished at all."""
        self.mode('noisy')
        done, box = threading.Event(), {}
        def go():
            try:
                box['frames'], box['gpu'] = measure.decode_video(self.media, 60.0, 20.0, W, H, 'bt709', False)
            except BaseException as e:            # noqa: BLE001 — reported by the assertions below
                box['error'] = e
            finally:
                done.set()
        threading.Thread(target=go, daemon=True).start()
        self.assertTrue(done.wait(60), 'decode_video deadlocked on a full stderr pipe')
        self.assertNotIn('error', box, box.get('error'))
        self.assertEqual(tuple(box['frames'].shape), (20 * 25, H, W, 3))


class Ceiling(SidecarCase):
    def test_the_whole_call_ceiling_fires_and_frees_everything(self):
        """FR-317-27 — a decoder that keeps producing, slowly, cannot hold the handler or the lock past
        AVSYNC_MEASURE_MAX_S."""
        self.mode('ceiling')
        measure.MEASURE_MAX_S = 3.0
        try:
            t0 = time.monotonic()
            code, body = self.post('/measure', dict(path=self.media, windows=self.windows()))
            took = time.monotonic() - t0
        finally:
            measure.MEASURE_MAX_S = 900.0
        self.assertEqual(code, 500, body)
        self.assertEqual(body['error'], 'measurement exceeded the 3s ceiling')
        self.assertLess(took, 30, f'answered in {took:.1f}s')
        time.sleep(0.5)
        self.assertEqual(alive(self.pidfile), [], 'no decoder may outlive the call')
        self.assertEqual([p for p in procs(self.pidfile) if p[0] == 'ffmpeg'], [], 'and none is a zombie')
        h = self.get_health()
        self.assertFalse(h['busy'])
        self.assertEqual(h['busy_for_s'], 0)


class Health(SidecarCase):
    def test_busy_for_s_rises_while_busy_and_is_zero_when_idle(self):
        """FR-317-28 — the field the backend reads to tell *measuring* from *wedged since 04:39*."""
        idle = self.get_health()
        self.assertEqual(idle['busy_for_s'], 0)
        self.assertEqual(idle['current_path_basename'], '')
        for k in ('ok', 'gpu', 'vram_total_gb', 'model', 'busy'):
            self.assertIn(k, idle, 'the old fields stay: an installed backend reads them')

        self.mode('slow')                         # 200 frames at 0.05 s: busy for ~10 s, no stall
        box = {}
        def go(): box['answer'] = self.post('/measure', dict(path=self.media, windows=self.windows(dur=8.0)))
        t = threading.Thread(target=go, daemon=True)
        t.start()
        seen = []
        for _ in range(40):
            time.sleep(0.25)
            h = self.get_health()
            if h['busy']: seen.append(h)
            if len(seen) >= 2 and seen[-1]['busy_for_s'] >= 2: break
        self.assertTrue(seen, 'the measurement never showed as busy')
        self.assertEqual(seen[-1]['current_path_basename'], 'subject.mkv')
        self.assertNotIn('/', seen[-1]['current_path_basename'], 'the basename only, never the path')
        self.assertGreaterEqual(seen[-1]['busy_for_s'], 2, [h['busy_for_s'] for h in seen])
        self.assertGreaterEqual(seen[-1]['busy_for_s'], seen[0]['busy_for_s'])

        # ...and a second request while it is busy is still refused, not queued (FR-317-2).
        code, body = self.post('/measure', dict(path=self.media, windows=self.windows()))
        self.assertEqual(code, 503, body)
        self.assertEqual(body['retry_after_s'], server.RETRY_AFTER_S)

        t.join(timeout=120)
        self.assertFalse(t.is_alive(), 'the handler never returned')
        self.assertEqual(box['answer'][0], 200, box['answer'])
        h = self.get_health()
        self.assertFalse(h['busy'])
        self.assertEqual(h['busy_for_s'], 0)
        self.assertEqual(h['current_path_basename'], '')


class Group(SidecarCase):
    def test_kill_group_takes_the_children_too(self):
        """A `-hwaccel cuda` ffmpeg that has stopped answering is not reached by closing its stdout, and
        its children are not reached by killing it alone."""
        self.mode('stall')
        p, tail, t = measure.spawn(['ffmpeg', '-v', 'error', '-ss', '0', '-i', self.media, '-t', '45',
                                    '-f', 'rawvideo', '-'])
        for _ in range(100):                      # wait until it has forked and written both pids
            if len(alive(self.pidfile)) >= 2: break
            time.sleep(0.05)
        self.assertEqual(len(alive(self.pidfile)), 2, 'the fake did not fork its child')
        self.assertEqual(os.getpgid(p.pid), p.pid, 'the child must lead its own process group')
        measure.kill_group(p)
        measure.finish(p, tail, t, measure.Stall(p, 0))
        time.sleep(0.5)
        self.assertEqual(alive(self.pidfile), [])
        self.assertEqual([q for q in procs(self.pidfile) if q[0] == 'ffmpeg'], [])
        self.assertIn(p.returncode, (-signal.SIGTERM, -signal.SIGKILL), p.returncode)


class CardHandedBack(SidecarCase):
    """FR-317-35/FR-317-36 — what a `/measure` that ran out of VRAM answers, and what it releases on the
    way out. The failure is injected into the GPU half, which is stubbed here anyway: what is under test
    is the handler's two exits and the `finally` they share, not the card."""

    def setUp(self):
        super().setUp()
        self.mode('frames')
        self.detect, self.freed = measure.detect, []
        measure.torch.cuda.empty_cache = lambda *a, **k: self.freed.append(1)

    def tearDown(self):
        measure.detect = self.detect
        measure.torch.cuda.empty_cache = lambda *a, **k: None

    def failing(self, e):
        def detect(*a, **k): raise e
        measure.detect = detect
        return self.post('/measure', dict(path=self.media, windows=self.windows(dur=3.0)))

    def test_a_vram_refusal_answers_500_with_error_kind_vram(self):
        floor = measure.VramExhausted(
            'out of VRAM in detect at the one-frame floor: decoded 640×490, a 1125-frame window, '
            '225 frames to process, 29.4 MB estimated for one frame, budget 2.00 GB, peak 1904 MB allocated')
        code, body = self.failing(floor)
        self.assertEqual(code, 500, body)
        self.assertEqual(body['error_kind'], 'vram')
        self.assertIn('one-frame floor', body['error'])
        self.assertIn('640×490', body['error'])
        self.assertGreaterEqual(len(self.freed), 1, 'the card is handed back on the failure path')

    def test_an_oom_outside_a_budgeted_chunk_says_vram_too(self):
        """A model load, a lip batch, anything that did not come up through [measure.chunked]: still the
        card refusing this file, so still a row rather than an hourly retry."""
        code, body = self.failing(measure.OOM('CUDA out of memory. Tried to allocate 572.00 MiB'))
        self.assertEqual(code, 500, body)
        self.assertEqual(body['error_kind'], 'vram')
        self.assertGreaterEqual(len(self.freed), 1)

    def test_any_other_failure_keeps_todays_body_exactly(self):
        """The old-sidecar case in reverse, and the one that must not regress: a 500 with no `error_kind`
        is what every other fault answers, and the backend's lane fails the row as it does today."""
        code, body = self.failing(RuntimeError('the shapes do not match'))
        self.assertEqual(code, 500, body)
        self.assertNotIn('error_kind', body)
        self.assertEqual(body['error'], 'RuntimeError: the shapes do not match')
        self.assertGreaterEqual(len(self.freed), 1, 'the card is handed back whatever the failure was')

    def test_a_measurement_that_succeeds_hands_the_card_back_as_well(self):
        code, body = self.post('/measure', dict(path=self.media, windows=self.windows(dur=3.0)))
        self.assertEqual(code, 200, body)
        self.assertGreaterEqual(len(self.freed), 1)


if __name__ == '__main__':
    unittest.main()
