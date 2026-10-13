"""Phase 317 — how far a file's sound sits from its picture, measured on the GPU.

The same two models as syncnet_python (S3FD faces, SyncNet v2 lips↔speech), without that pipeline's
JPEG round-trips, batch-1 detection, Python NMS and XVID re-encode of every face crop: it ran at 16 % GPU
and ~160 s per 45 s window; this runs ~2 s of GPU per window (RTX 2060 SUPER, 2026-10-10).

A module `server.py` imports ([measure], [stat], [health]) and still the CLI the research report was
written with:

    python measure.py <file | @list> <starts> <dur_s> [--pct] [--workers N]

<starts> are seconds, or percentages of the runtime with --pct ("20,50,80"). One JSON line per window:
`window.offset` in 25 fps frames (40 ms), **positive = audio early**, `window.conf` (median − min of the
distance curve), `window.frames` (face frames behind it).

See specs/requirements/phase-317-sound-that-runs-ahead-of-the-picture-is-found-and-explained.md (FR-317-2,
FR-317-3, FR-317-4) and specs/research-reports/av-sync-measurement-on-the-gpu-2026-10-10.md.
"""
import argparse, json, math, os, subprocess, sys, threading, time
from collections import deque
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from signal import SIGKILL, SIGTERM            # by name: `signal` below is scipy's, and it shadows this one

import numpy as np, python_speech_features, torch, torch.nn.functional as F, torchvision
from scipy import signal

APP_DIR = os.environ.get('AVSYNC_APP_DIR', '/app')
# The pipeline's own version, appended to the model name: a bump makes every stored result due again
# (FR-317-2/FR-317-7). Raise it whenever a change here would move a reading.
#   2 — FR-317-32: a window whose audio did not reach its picture is refused instead of measured against
#       speech from somewhere else in the file. Every row written under 1 is due again, because the
#       readings that moved are exactly the ones that cannot be identified without re-measuring.
PIPELINE_VERSION = 2
SYNCNET_COMMIT = '907c0b5'
MODEL = f'syncnet_v2+s3fd@{SYNCNET_COMMIT}/{PIPELINE_VERSION}'

# Limits the prototype measured (FR-317-2): one GPU worker, this many decoders ahead WITHIN the file, and a
# hard VRAM cap per process — the card is shared with Immich, which keeps ~4 GB.
VRAM_GB = float(os.environ.get('AVSYNC_VRAM_GB', '2'))
DECODERS = int(os.environ.get('AVSYNC_DECODERS', '3'))

# FR-317-27, after DEB-897: two prefetched decoders slept at 0 % CPU for four hours holding the lock and the
# card, and the sweep stopped. Nothing here may wait indefinitely — a child that stops producing is killed,
# and the whole call has a backstop ceiling. Both ≤ 0 disable the bound.
DECODE_STALL_S = float(os.environ.get('AVSYNC_DECODE_STALL_S', '60'))
MEASURE_MAX_S = float(os.environ.get('AVSYNC_MEASURE_MAX_S', '900'))

FPS = 25                  # every reading is in 25 fps frames: one frame = 40 ms
VSHIFT = 15               # the distance curve spans −15…+15 frames, SyncNet's own range
RES = 640                 # decoded width; the height keeps the aspect
DETECT_EVERY = 5          # face detection on every Nth frame, linked + interpolated
DET_SCALE = 0.5           # detector input, relative to the decoded frame
MIN_TRACK = 50            # frames a face must stay on screen (2 s)
ENV_BUCKET_MS = 20        # the loudness envelope's resolution for the `others` correlation (half a frame)
ENV_MAX_LAG_S = 2.0       # FR-317-4: a dub is looked for within ±2 s of the measured track

# FR-317-32 — how far short of the video the audio features of a HEALTHY window come, by construction:
# `audio_feats` keeps `mfcc_frames // 4 - 5` windows of 20 MFCC columns, so a 45 s window's 1 125 video
# frames meet 1 119 audio features, every time, whatever the file. The last ~0.24 s of every window has no
# audio under it, the clamp in [distance_curve] truncates it, and that is correct.
AUDIO_FEATURE_LAG = 6
# Anything worse than this is a decode that lost audio (DEB-939): the shortfall of a healthy window is 0,
# and one codec packet of 1 024 samples at the `-ss` seek is 1.6 frames. ffmpeg does not pad a dropped
# packet — it hands over the samples that survived, end to end — so a gap mid-window does not merely
# shorten the audio, it SHIFTS everything after it, and frame k's lips are then measured against speech
# from k + the gap. That is not a measurement of this file's sync, and no number in the row said so.
AUDIO_SHORT_TOL = 2

dev, fp16 = 'cuda', not os.environ.get('AVSYNC_FP32')


class Undecodable(Exception):
    """ffmpeg could not read the file (or the stream asked for): `str(e)` is its last line → HTTP 422."""


class DecodeStalled(Exception):
    """A child produced nothing for [DECODE_STALL_S] and was killed → HTTP **500**, never 422: 422 means
    *this file cannot be decoded* (FR-317-16/FR-317-20) and a stall is a fault of the run (FR-317-27)."""


class MeasureTimeout(Exception):
    """The whole call passed [MEASURE_MAX_S] and every child was killed → HTTP 500 (FR-317-27)."""


# ── models ──────────────────────────────────────────────────────────────────────────────────────

_models = None
_priors = {}


class CachedPriorBox:                             # S3FD rebuilds its priors in Python on every call
    def __init__(self, size, fmaps): self.key = (tuple(size), tuple(map(tuple, fmaps))); self.args = (size, fmaps)

    def forward(self):
        from detectors.s3fd.box_utils import PriorBox
        if self.key not in _priors: _priors[self.key] = PriorBox(*self.args).forward()
        return _priors[self.key]


def models():
    """The two networks and the mean, built once per process. Touching CUDA is deferred to here so
    `import measure` costs nothing and `server.py` can answer before the card is opened."""
    global _models
    if _models is not None: return _models
    sys.path.insert(0, APP_DIR)
    import detectors.s3fd.nets as s3nets
    from detectors.s3fd.box_utils import decode
    from SyncNetModel import S
    torch.cuda.set_per_process_memory_fraction(
        min(1.0, VRAM_GB * 1e9 / torch.cuda.get_device_properties(0).total_memory))
    s3nets.PriorBox = CachedPriorBox
    det = s3nets.S3FDNet(device=dev).to(dev)
    det.load_state_dict(torch.load(f'{APP_DIR}/detectors/s3fd/weights/sfd_face.pth', map_location=dev, weights_only=True))
    det.eval()
    det.detect.forward = lambda loc, conf, priors: (loc, conf, priors)   # raw heads: decode + NMS batched below
    sn = S(num_layers_in_fc_layers=1024).to(dev).eval()
    state = sn.state_dict()
    for k, v in torch.load(f'{APP_DIR}/data/syncnet_v2.model', map_location='cpu', weights_only=True).items():
        state[k].copy_(v)
    mean = torch.tensor([123., 117., 104.], device=dev).view(1, 3, 1, 1)
    _models = (det, sn, mean, decode)
    return _models


# ── children, bounded (FR-317-27) ────────────────────────────────────────────────────────────────

def secs(x):
    return f'{x:g}'


def spawn(cmd, run=None):
    """One child, in a session of its own so that killing it reaches ffmpeg's own children, with `stderr`
    drained by a thread from the first byte.

    Draining concurrently is not a nicety (DEB-897): both pipes are unbuffered, and reading `stdout` to
    completion before touching `stderr` is a mutual wait the moment ffmpeg writes more than a pipe buffer
    of errors — which `-v error` keeps rare right up until a stream errors once per frame."""
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
                         start_new_session=True)
    if run is not None: run.add(p)
    tail = []
    t = threading.Thread(target=_drain, args=(p.stderr, tail), daemon=True)
    t.start()
    return p, tail, t


def _drain(f, tail, keep=8192):
    """Reads `stderr` to EOF, keeping only its last [keep] bytes: all anyone asks of it is the last line,
    and a stream that errors per frame would otherwise be held whole in memory."""
    try:
        while True:
            b = f.read(65536)
            if not b: return
            tail.append(b)
            if len(tail) > 1: tail[:] = [b''.join(tail)[-keep:]]
    except (OSError, ValueError):                 # the pipe closed under us; the caller is shutting down
        return
    finally:
        try: f.close()                            # a sweep is thousands of children: the fd goes back now
        except OSError: pass


def kill_group(p, grace=3.0):
    """`SIGTERM` the child's whole process group, then `SIGKILL` whatever is left. The group is the point:
    an `-hwaccel cuda` ffmpeg that has stopped answering is not reached by closing its stdout."""
    for sig, wait in ((SIGTERM, grace), (SIGKILL, 1.0)):
        try:
            os.killpg(p.pid, sig)
        except (ProcessLookupError, PermissionError):
            pass
        try:
            p.wait(timeout=wait); return
        except subprocess.TimeoutExpired:
            continue


class Stall:
    """A no-output watchdog over one child: every read refreshes it, and [stall_s] with nothing read kills
    the process group. `fired` is how the caller knows the silence was ours to report."""

    def __init__(self, p, stall_s=None):
        self.p = p
        self.stall_s = DECODE_STALL_S if stall_s is None else stall_s
        self.last, self.fired, self.done = time.monotonic(), False, threading.Event()
        if self.stall_s > 0:
            self.t = threading.Thread(target=self._watch, daemon=True); self.t.start()

    def touch(self):
        self.last = time.monotonic()

    def _watch(self):
        tick = min(1.0, max(0.05, self.stall_s / 10))
        while not self.done.wait(tick):
            if time.monotonic() - self.last < self.stall_s: continue
            self.fired = True                     # set before the kill: the reader must not read EOF as data
            kill_group(self.p)
            return

    def close(self):
        self.done.set()


class Run:
    """The children of one `/measure` call, so the whole-call ceiling can kill every one of them — the
    prefetched decoders included, which is what nothing could do on DEB-897."""

    def __init__(self, max_s=None):
        self.max_s = MEASURE_MAX_S if max_s is None else max_s
        self.deadline = time.monotonic() + self.max_s if self.max_s > 0 else None
        self.lock, self.children, self.expired = threading.Lock(), set(), False

    def add(self, p):
        with self.lock: self.children.add(p)

    def remaining(self):
        return None if self.deadline is None else self.deadline - time.monotonic()

    def kill_all(self):
        """Ends the run: every live child is killed, and the fetch threads still inside a decode learn from
        `expired` that their answer is no longer wanted (and must not be retried on the CPU)."""
        self.expired = True
        with self.lock: kids = list(self.children)
        for p in kids: kill_group(p)


def stall_for(run):
    """One child's no-output bound, clipped to whatever is left of the run's ceiling — so that even a child
    spawned on the handler thread itself (`ffprobe`, before any future exists to time out) cannot push the
    answer past [MEASURE_MAX_S]."""
    left = None if run is None else run.remaining()
    return DECODE_STALL_S if left is None else max(0.1, min(DECODE_STALL_S, left))


def read_all(p, wd):
    """The child's `stdout` to EOF, refreshing [wd] on every chunk that arrives."""
    chunks = []
    while True:
        b = p.stdout.read(65536)
        wd.touch()
        if not b: return b''.join(chunks)
        chunks.append(b)


def finish(p, tail, t, wd):
    """Closes one child down — watchdog off, pipe closed, process reaped, `stderr` reader joined — and
    returns the tail of its `stderr`. `p.wait()` is bounded here too: it was not before."""
    wd.close()
    try:
        p.stdout.close()
    except OSError:
        pass
    try:
        p.wait(timeout=5)
    except subprocess.TimeoutExpired:             # it ignored EPIPE; it does not get to outlive the call
        kill_group(p)
    t.join(timeout=5)
    return b''.join(tail).decode('utf-8', 'replace')


# ── decode (NVDEC + scale_cuda + tonemap_cuda; AV1 on the CPU) ───────────────────────────────────

def probe(path, run=None):
    v = ffprobe(['-select_streams', 'v:0', '-show_entries', 'stream=codec_name,width,height,color_transfer'],
                path, run).split(',')
    d = ffprobe(['-show_entries', 'format=duration'], path, run)
    if len(v) < 3 or not v[1].isdigit():
        raise Undecodable('ffprobe found no video stream')
    return dict(codec=v[0], w=int(v[1]), h=int(v[2]), trc=v[3] if len(v) > 3 else '', dur=float(d or 0))


def ffprobe(args, path, run=None):
    """ffprobe under the same watchdog as the decoders: it is a child of ours too, and it runs on the
    handler thread, so an ffprobe that never answers withholds the answer just as completely."""
    p, tail, t = spawn(['ffprobe', '-v', 'error', *args, '-of', 'csv=p=0', path], run)
    wd = Stall(p, stall_for(run))
    out = read_all(p, wd)
    finish(p, tail, t, wd)
    if wd.fired: raise DecodeStalled(f'ffprobe stalled after {secs(wd.stall_s)}s')
    return out.decode('utf-8', 'replace').strip()


def video_filter(W, H, trc, gpu):
    hdr = trc in ('smpte2084', 'arib-std-b67')
    if gpu and hdr:   # Jellyfin's own chain: faces in PQ are flat grey and the detector misses them
        return (f'setparams=color_primaries=bt2020:color_trc={trc}:colorspace=bt2020nc,scale_cuda=w={W}:h={H},'
                'tonemap_cuda=format=yuv420p:p=bt709:t=bt709:m=bt709:tonemap=bt2390:peak=100:desat=0,'
                f'hwdownload,format=yuv420p,fps={FPS}')
    if gpu:
        return f'scale_cuda=w={W}:h={H}:format=yuv420p,hwdownload,format=yuv420p,fps={FPS}'
    if hdr:
        return (f'scale={W}:{H}:flags=fast_bilinear,zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,'
                f'tonemap=bt2390:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p,fps={FPS}')
    return f'fps={FPS},scale={W}:{H}:flags=fast_bilinear'


def decode_video(path, start, dur, W, H, trc, gpu, run=None):
    """The window's frames as a uint8 CPU tensor, and whether NVDEC really served them."""
    hw = ['-hwaccel', 'cuda', '-hwaccel_output_format', 'cuda'] if gpu else ['-filter_threads', '8']
    cmd = ['ffmpeg', '-v', 'error', *hw, '-ss', str(start), '-i', path, '-t', str(dur), '-map', '0:v:0',
           '-vf', video_filter(W, H, trc, gpu), '-pix_fmt', 'bgr24', '-f', 'rawvideo', '-']
    fsz, cap = W * H * 3, int(dur * FPS) + 5
    buf = torch.empty((cap, H, W, 3), dtype=torch.uint8)   # read straight into the tensor: no bytes copies
    mv, off = memoryview(buf.numpy()).cast('B'), 0
    p, tail, t = spawn(cmd, run)
    wd = Stall(p, stall_for(run))
    while off < cap * fsz:
        k = p.stdout.readinto(mv[off:])
        wd.touch()
        if not k: break
        off += k
    err = finish(p, tail, t, wd)
    if wd.fired:                                  # the silence was ours to report, and never a 422
        raise DecodeStalled(f'decode stalled after {secs(wd.stall_s)}s on window {start}s')
    if run is not None and run.expired:           # the ceiling killed us: no CPU retry, no verdict
        raise MeasureTimeout(f'measurement exceeded the {secs(run.max_s)}s ceiling')
    if off < fsz and gpu:                         # a profile NVDEC refuses: the CPU decodes it instead
        return decode_video(path, start, dur, W, H, trc, False, run)
    if off < fsz:
        raise Undecodable(last_line(err) or 'ffmpeg produced no frames')
    return buf[:off // fsz], gpu


def decode_audio(path, start, dur, spec='0:a:0', run=None):
    """One audio stream of the window as mono 16 kHz PCM. [spec] is an ffprobe/ffmpeg specifier
    (`0:a:N`, FR-317-2) — never Jellyfin's own numbering."""
    cmd = ['ffmpeg', '-v', 'error', '-ss', str(start), '-i', path, '-t', str(dur), '-map', spec, '-ac', '1',
           '-ar', '16000', '-f', 's16le', '-']
    p, tail, t = spawn(cmd, run)
    wd = Stall(p, stall_for(run))
    raw = read_all(p, wd)
    err = finish(p, tail, t, wd)
    if wd.fired:
        raise DecodeStalled(f'decode stalled after {secs(wd.stall_s)}s on window {start}s audio {spec}')
    if run is not None and run.expired:
        raise MeasureTimeout(f'measurement exceeded the {secs(run.max_s)}s ceiling')
    pcm = np.frombuffer(raw[:len(raw) // 2 * 2], np.int16)   # a child cut off mid-sample leaves an odd byte
    if pcm.size == 0:
        raise Undecodable(last_line(err) or f'no audio decoded from {spec}')
    return pcm


def last_line(text):
    lines = [l.strip() for l in (text or '').splitlines() if l.strip()]
    return lines[-1] if lines else ''


# ── faces ───────────────────────────────────────────────────────────────────────────────────────

def shots(fr, cut=30.):
    small = torch.cat([F.interpolate(fr[i:i + 128].permute(0, 3, 1, 2).float(), size=(36, 64), mode='area')
                       for i in range(0, fr.shape[0], 128)])
    d = (small[1:] - small[:-1]).abs().mean(dim=(1, 2, 3))
    b = [0] + (torch.nonzero(d > cut).flatten() + 1).tolist() + [fr.shape[0]]
    return [(b[i], b[i + 1]) for i in range(len(b) - 1)]


@torch.no_grad()
def detect(fr, idx, W, H, batch=12):
    det, _, RGB_MEAN, decode = models()
    out, dw, dh = {}, int(W * DET_SCALE) // 2 * 2, int(H * DET_SCALE) // 2 * 2
    scale = torch.tensor([W, H, W, H], device=dev)
    for i in range(0, len(idx), batch):
        bi = idx[i:i + batch]
        x = fr[bi].permute(0, 3, 1, 2).float()[:, [2, 1, 0]]
        x = F.interpolate(x, size=(dh, dw), mode='bilinear', align_corners=False) - RGB_MEAN
        with torch.autocast('cuda', dtype=torch.float16, enabled=fp16):
            loc, conf, priors = det(x)
        loc = loc.float().view(len(bi), -1, 4); conf = conf.float().view(len(bi), -1, 2)[..., 1]
        pr = priors.to(dev)
        for j, f in enumerate(bi):
            m = conf[j] > 0.9
            if not m.any(): out[f] = []; continue
            bx = decode(loc[j][m], pr[m], [0.1, 0.2]) * scale
            keep = torchvision.ops.nms(bx, conf[j][m], 0.1)
            out[f] = bx[keep].cpu().numpy().tolist()
    return out


def same_face(b, p):        # sparse detections move too far for IoU > 0.5: centre within half a face, size ×1.5
    sb, sp = max(b[2] - b[0], b[3] - b[1]), max(p[2] - p[0], p[3] - p[1])
    d = ((b[0] + b[2] - p[0] - p[2]) ** 2 + (b[1] + b[3] - p[1] - p[3]) ** 2) ** 0.5 / 2
    return d < 0.5 * max(sb, sp) and 1 / 1.5 < sb / sp < 1.5


def tracks(dets, s0, s1, W):                      # syncnet_python's track_shot, on sparse detections
    faces = [[dict(frame=f, bbox=b) for b in dets[f]] for f in sorted(dets) if s0 <= f < s1]
    out = []
    while True:
        tr = []
        for ff in faces:
            for fc in list(ff):
                if not tr: tr.append(fc); ff.remove(fc)
                elif fc['frame'] - tr[-1]['frame'] <= FPS:
                    if same_face(fc['bbox'], tr[-1]['bbox']): tr.append(fc); ff.remove(fc)
                else: break
        if not tr: break
        if tr[-1]['frame'] - tr[0]['frame'] + 1 > MIN_TRACK:
            fn, bb = np.array([t['frame'] for t in tr]), np.array([t['bbox'] for t in tr])
            fi = np.arange(fn[0], fn[-1] + 1)
            bbi = np.stack([np.interp(fi, fn, bb[:, k]) for k in range(4)], 1)
            if np.mean(np.maximum(bbi[:, 2] - bbi[:, 0], bbi[:, 3] - bbi[:, 1])) > 0.03 * W:
                for k in range(0, len(fi), 250):       # ≤ 10 s pieces: a piece's crops are ~150 MB, whatever the shot
                    if len(fi) - k >= 30: out.append((fi[k:k + 250], bbi[k:k + 250]))
    return out


@torch.no_grad()
def crops(fr, fi, bb, cs=0.4):                    # syncnet_python's crop geometry, via roi_align on the GPU
    s = signal.medfilt(np.maximum(bb[:, 3] - bb[:, 1], bb[:, 2] - bb[:, 0]) / 2, 13)
    y = signal.medfilt((bb[:, 1] + bb[:, 3]) / 2, 13); x = signal.medfilt((bb[:, 0] + bb[:, 2]) / 2, 13)
    boxes = np.stack([np.arange(len(fi)), x - s * (1 + cs), y - s, x + s * (1 + cs), y + s * (1 + 2 * cs)], 1)
    res = []
    for i in range(0, len(fi), 16):                # small chunks: a full-frame float copy is the big allocation here
        img = fr[fi[i:i + 16]].permute(0, 3, 1, 2).float() - 110.    # the original pads with grey 110
        b = torch.tensor(boxes[i:i + 16], device=dev, dtype=torch.float32); b[:, 0] -= i
        res.append((torchvision.ops.roi_align(img, b, (224, 224), 1.0, 2, aligned=True) + 110.).half())
    return torch.cat(res)                         # T × 3 (BGR) × 224 × 224


# ── SyncNet ─────────────────────────────────────────────────────────────────────────────────────

def mfcc_frames(n_samples):
    """How many frames `python_speech_features.mfcc(pcm, 16000)` will return for [n_samples] — its own
    `sigproc.framesig` arithmetic at the default winlen 25 ms / winstep 10 ms, which at 16 kHz is a 400
    sample window stepped by 160. Arithmetic rather than a call: FR-317-32 needs this per window, before
    any model runs, and the MFCC of a 45 s window is not free."""
    return 1 if n_samples <= 400 else 1 + math.ceil((n_samples - 400) / 160)


def audio_feature_count(n_samples):
    """How many audio features [audio_feats] will produce from [n_samples] of 16 kHz PCM, derived the same
    way it does (`// 4 - 5`) — one per 40 ms, so it is directly comparable with a 25 fps frame count.

    Signed on purpose: a window with almost no audio in it gives a NEGATIVE count, and FR-317-32's bar has
    to see how far below zero rather than be handed a clamped 0."""
    return mfcc_frames(n_samples) // 4 - 5


@torch.no_grad()
def audio_feats(pcm):                             # once per window, shared by every face in it
    _, sn, _, _ = models()
    cc = torch.tensor(python_speech_features.mfcc(pcm, 16000).T, dtype=torch.float32, device=dev)[None, None]
    n = cc.shape[-1] // 4 - 5
    win = cc.unfold(3, 20, 4)[..., :n, :].permute(3, 0, 1, 2, 4).reshape(n, 1, 13, 20)
    outs = []
    for i in range(0, n, 512):
        with torch.autocast('cuda', dtype=torch.float16, enabled=fp16):
            outs.append(sn.forward_aud(win[i:i + 512]).float())
    return torch.cat(outs)


@torch.no_grad()
def lip_feats(cr, batch=64):
    _, sn, _, _ = models()
    n, outs = cr.shape[0] - 4, []
    for i in range(0, n, batch):
        j = torch.arange(i, min(n, i + batch), device=dev)
        clip = cr[j[:, None] + torch.arange(5, device=dev)[None]].permute(0, 2, 1, 3, 4).float()   # B × 3 × 5 × 224²
        with torch.autocast('cuda', dtype=torch.float16, enabled=fp16):
            outs.append(sn.forward_lip(clip).float())
    return torch.cat(outs)


def distance_curve(lf, af, f0):                  # mean lip↔speech distance at each shift −15…+15 frames
    """The curve and the number of frames behind it, or `(None, n)` for a track with no audio under it.

    `af[i]` and lip feature `i` are the same 40 ms of the same window by construction — the video is
    decoded at exactly `fps=25` and `audio_feats` steps the MFCC by 4 columns of 10 ms — so clamping a
    video frame index against `af_len` mixes no units, and `F.pad` is what makes the ±15 frame shifts legal
    at the edges. The clamp's UPPER half is correct and nothing here moves it (FR-317-33).

    What it was missing is a lower bound. `n == 0` makes every slice empty, `d.mean(0)` over an empty
    dimension is `NaN`, and the window's `conf` is `NaN`; `n < 0` keeps `lf_len + n` lip rows against an
    empty audio slice and PyTorch raises *"The size of tensor a (N) must match the size of tensor b (0)"*.
    Both were in the 2026-10-13 sweep's failure list (DEB-939), and both are *not measured* rather than an
    answer. This is a BACKSTOP, not the fix: inside a window FR-317-32 accepted it is unreachable
    (`v − a ≤ 8`, a track piece is ≥ 30 frames, so `a − f0 ≥ 22 > 0`), and the fix for the silent wrong
    answers is FR-317-32's refusal. If it is ever reached, the caller refuses the window — which carries an
    `audio_short_frames` at or below the bar, a signature no healthy row can wear."""
    n = min(lf.shape[0], af.shape[0] - f0)
    if n <= 0: return None, n
    afp = F.pad(af, (0, 0, VSHIFT, VSHIFT))
    d = torch.stack([F.pairwise_distance(lf[:n], afp[f0 + s: f0 + s + n]) for s in range(2 * VSHIFT + 1)], 1)
    return d.mean(0), n


def verdict(curve):                               # SyncNet's own reading: + = audio early
    mi = int(curve.argmin())
    return VSHIFT - mi, round(float(curve.median() - curve[mi]), 3)


# ── the other audio tracks (FR-317-4) ────────────────────────────────────────────────────────────

def envelope(pcm, bucket_ms=ENV_BUCKET_MS):
    """Loudness per [bucket_ms], mean removed and scaled to unit energy — the shape a correlation can
    compare across two different mixes. An empty or silent stream gives None."""
    n = max(1, 16000 * bucket_ms // 1000)
    x = pcm[:len(pcm) // n * n].reshape(-1, n).astype(np.float32)
    e = np.sqrt((x * x).mean(axis=1) + 1e-9)
    e -= e.mean()
    norm = np.linalg.norm(e)
    return None if norm < 1e-6 or e.size < 8 else e / norm


def envelope_lag(ref_envs, other_envs, bucket_ms=ENV_BUCKET_MS):
    """One (lag_frames, corr) for a whole audio stream: the normalised cross-correlation curves of every
    window summed, then read once — the same "one curve per file, every piece weighted by its length"
    rule the lip measurement uses, so a window of silence cannot cast a vote of its own.

    **Positive lag = this stream is EARLY relative to the measured one**, the same sign as `offset_frames`.
    """
    max_lag = int(round(ENV_MAX_LAG_S * 1000 / bucket_ms))
    total, used = np.zeros(2 * max_lag + 1), 0
    for a, b in zip(ref_envs, other_envs):
        if a is None or b is None: continue
        c = signal.correlate(a, b, mode='full')
        lags = signal.correlation_lags(len(a), len(b), mode='full')
        keep = np.abs(lags) <= max_lag
        if not keep.any(): continue
        curve = np.zeros(2 * max_lag + 1)
        curve[lags[keep] + max_lag] = c[keep]
        total += curve; used += 1
    if used == 0: return None, 0.0
    total /= used
    k = int(np.argmax(total))
    # c[lag] = Σ a[n+lag]·b[n]: a peak at +lag means the measured track reaches at n+lag what this stream
    # reached at n — this stream is that much EARLIER.
    return int(round((k - max_lag) * bucket_ms / 1000 * FPS)), round(float(total[k]), 3)


# ── what leaves this process (FR-317-30) ─────────────────────────────────────────────────────────

def jsonable(obj):
    """Every float that leaves here is finite or `null`, and the object that held a non-finite one says so.

    `json.dumps` writes bare `NaN` and `Infinity` for non-finite floats. **Neither is JSON**, and the
    backend's parser is right to refuse them: on 2026-10-13 two files were decoded, measured, the GPU time
    spent — and thrown away at the very last step with *"Unexpected special floating-point value"*
    (DEB-915). A `conf` or a `corr` that came back `NaN` is *no answer*, which is what `null` already means
    to every reader of these documents.

    `nonfinite: true` is set on the object a replacement happened in, so *no answer* stays distinguishable
    from *nothing to answer about*: a window with no face track reports `offset_frames: null` too, and that
    one is not a fault. It is additive — a reader that does not know the key ignores it.
    """
    if obj is None or isinstance(obj, (str, bool)): return obj
    if hasattr(obj, '__index__'): return int(obj)      # ints, numpy's included: an index is never non-finite
    if isinstance(obj, dict):
        out, bad = {}, False
        for k, v in obj.items():
            out[k] = jsonable(v)
            if out[k] is None and v is not None: bad = True
        if bad: out['nonfinite'] = True
        return out
    if isinstance(obj, (list, tuple)): return [jsonable(v) for v in obj]
    try:
        f = float(obj)
    except (TypeError, ValueError):
        return obj                                     # not a number at all: not ours to rewrite
    return f if math.isfinite(f) else None


# ── one file, measured (FR-317-2's `POST /measure`) ──────────────────────────────────────────────

def stat(path):
    try:
        st = os.stat(path)
        return dict(exists=True, size=st.st_size)
    except OSError:
        return dict(exists=False, size=0)


def health():
    """FR-317-28 — `busy` alone cannot tell *measuring the file in front of me* from *wedged since 04:39*,
    so it is joined by how long the lock has been held and by the **basename** of what is being measured.
    Both are additive; nothing here is removed or retyped, and no full library path is ever printed."""
    busy = _lock.locked() if _lock is not None else False
    since, held = _busy_since, 0
    if busy and since is not None: held = int(max(0.0, time.monotonic() - since))
    state = dict(model=MODEL, busy=busy, busy_for_s=held,
                 current_path_basename=os.path.basename(_busy_path) if busy and _busy_path else '')
    try:
        name = torch.cuda.get_device_name(0)
        vram = round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1)
    except Exception as e:                        # no card, no driver, the container without `video`
        return dict(ok=False, gpu=f'unavailable: {e}', vram_total_gb=0.0, **state)
    return dict(ok=True, gpu=name, vram_total_gb=vram, **state)


_lock = None      # set by server.py to the measurement lock, so /health can report `busy`
_busy_since = None       # monotonic seconds at which the current measurement took that lock (FR-317-28)
_busy_path = None


def busy_started(path):
    """server.py says when a measurement takes the lock, so `/health` has one source of truth for both
    `busy` and how long it has been so."""
    global _busy_since, _busy_path
    _busy_since, _busy_path = time.monotonic(), path


def busy_done():
    global _busy_since, _busy_path
    _busy_since, _busy_path = None, None


def measure(path, audio='0:a:0', others=(), windows=(), max_s=None):
    """FR-317-2/-3 — one file, its windows measured one after another on the one GPU worker, with
    [DECODERS] windows of the SAME file decoded ahead. Raises [Undecodable] (→ 422), [DecodeStalled] and
    [MeasureTimeout] (→ 500, FR-317-27), and leaves `FileNotFoundError` (→ 404) to the caller.

    The call is bounded end to end: [max_s] (default [MEASURE_MAX_S]) from here to the answer, after which
    every child is killed and every decoder thread abandoned. No path can leave the caller's `finally`
    unreached — that is what held the lock for four hours on DEB-897."""
    if not os.path.exists(path): raise FileNotFoundError(path)
    t0 = time.time()
    run = Run(max_s)
    p = probe(path, run)
    W = RES; H = int(round(p['h'] * W / p['w'] / 2) * 2)
    gpu = p['codec'] != 'av1'                     # no AV1 in Pascal/Turing NVDEC: dav1d on the CPU
    wins = [(float(w['start_s']), float(w.get('dur_s', 45))) for w in windows]
    wins = [(max(0.0, s), d) for s, d in wins if p['dur'] <= 0 or s < p['dur']]
    others = list(others)

    def fetch(start, dur):
        fr, used_gpu = decode_video(path, start, dur, W, H, p['trc'], gpu, run)
        pcm = decode_audio(path, start, dur, audio, run)
        extra = [decode_audio(path, start, dur, spec, run) if spec != audio else pcm for spec in others]
        return fr, pcm, extra, used_gpu

    out, nvdec = [], gpu
    ref_envs, other_envs = [], [[] for _ in others]
    # Not `with`: the context manager joins its threads on the way out, so an exception — the ceiling's
    # included — could not free the handler thread while a decoder was stuck (DEB-897).
    ex = ThreadPoolExecutor(max(1, DECODERS))
    try:
        q = deque()
        it = iter(wins)
        pending = next(it, None)
        while pending is not None and len(q) < max(1, DECODERS):
            q.append((pending, ex.submit(fetch, *pending))); pending = next(it, None)
        while q:
            (start, dur), fu = q.popleft()
            if pending is not None:
                q.append((pending, ex.submit(fetch, *pending))); pending = next(it, None)
            try:
                fr_cpu, pcm, extra, used_gpu = fu.result(timeout=run.remaining())
            except FutureTimeout:
                raise MeasureTimeout(f'measurement exceeded the {secs(run.max_s)}s ceiling') from None
            nvdec = nvdec and used_gpu
            # FR-317-32 — does the decoded audio reach the picture it would be compared against? Against
            # `v − a` and never against the requested duration: a window clamped at the end of the file,
            # and a file whose real length is under its header's, come back short in BOTH and are perfectly
            # measurable. `v − a` is invariant to all of it. Decided from lengths alone, before any face
            # work, so a refused window costs no detection, no crops and no lip features — the audio was
            # decoded either way, and that is where the GPU is saved.
            v = fr_cpu.shape[0]
            short_frames = v - audio_feature_count(pcm.size) - AUDIO_FEATURE_LAG
            short = short_frames > AUDIO_SHORT_TOL
            fr = fr_cpu.to(dev) if not short else None
            curve, frames, faces = None, 0, []
            if not short:
                dets = detect(fr, list(range(0, v, DETECT_EVERY)), W, H)
                trs = [t for s0, s1 in shots(fr) for t in tracks(dets, s0, s1, W)]
                af = audio_feats(pcm)
                for fi, bb in trs:
                    # FR-317-33's backstop, read off the lengths before the crops are built: `lip_feats`
                    # returns one feature per frame but the last four. Unreachable in an accepted window;
                    # if it ever fires, the window is refused exactly as a short-audio one is.
                    if min(len(fi) - 4, af.shape[0] - int(fi[0])) <= 0:
                        short = True
                        continue
                    md, n = distance_curve(lip_feats(crops(fr, fi, bb)), af, int(fi[0]))
                    if md is None:                # the same backstop, from the one place that owns the clamp
                        short = True
                        continue
                    curve = md * n if curve is None else curve + md * n; frames += n
                    off, conf = verdict(md)
                    faces.append(dict(at_s=round(start + fi[0] / FPS, 1), frames=n, offset_frames=off, conf=conf))
                torch.cuda.synchronize()
            # A refused window reports exactly as a window with no face track does — `offset_frames: null`,
            # `conf: 0.0`, `frames: 0`, no faces — because that is what it is: there is nothing to measure
            # here, not *the measurement failed*. `audio_short_frames` is reported on EVERY window, refused
            # or not, so the healthy distribution of `v − a` is readable out of `file_av_sync` without a new
            # build and the bar can be re-drawn from evidence if 2 turns out to be wrong.
            w = dict(start_s=round(start, 1), offset_frames=None, conf=0.0, frames=0, faces=[])
            if curve is not None and not short:   # the window's answer: every face's curve, weighted by its length
                off, conf = verdict(curve / frames)
                w.update(offset_frames=off, conf=conf, frames=frames, faces=faces)
            w.update(audio_short=short, audio_short_frames=short_frames)
            out.append(w)
            # FR-317-4's zip stays aligned: a refused window contributes `None` to the measured track's
            # envelope list AND to every `others` list, so it casts no vote in the cross-correlation either.
            # An `others` stream short in this window alone contributes `None` for that stream only.
            ref_envs.append(None if short else envelope(pcm))
            for i, pcm_other in enumerate(extra):
                other_short = short or v - audio_feature_count(pcm_other.size) - AUDIO_FEATURE_LAG > AUDIO_SHORT_TOL
                other_envs[i].append(None if other_short else envelope(pcm_other))
            del fr, fr_cpu; torch.cuda.empty_cache()     # hand the card back between windows
    except BaseException:
        # Any failure ends the run: the windows still being prefetched are nobody's answer now, and a
        # decoder left running would hold the card until it finished by itself.
        run.kill_all(); ex.shutdown(wait=False, cancel_futures=True); raise
    ex.shutdown(wait=True)

    others_out = []
    for i, spec in enumerate(others):
        lag, corr = (0, 1.0) if spec == audio else envelope_lag(ref_envs, other_envs[i])
        others_out.append(dict(audio=spec, lag_frames=lag, corr=corr))
    return dict(windows=out, others=others_out, decode='nvdec' if nvdec else 'cpu',
                seconds=round(time.time() - t0, 2))


# ── CLI (what the research report was measured with) ─────────────────────────────────────────────

def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('path'); ap.add_argument('starts'); ap.add_argument('dur', type=float)
    ap.add_argument('--pct', action='store_true', help='starts are % of the runtime')
    ap.add_argument('--audio', default='0:a:0', help='the audio stream measured against the lips')
    ap.add_argument('--others', default='', help='comma-separated specifiers to cross-correlate with it')
    a = ap.parse_args(argv)
    paths = [l.strip() for l in open(a.path[1:]) if l.strip()] if a.path.startswith('@') else [a.path]
    others = [s for s in a.others.split(',') if s]
    wall = time.time()
    for path in paths:
        dur = probe(path)['dur']
        starts = [dur * float(x) / 100 for x in a.starts.split(',')] if a.pct else list(map(float, a.starts.split(',')))
        r = measure(path, a.audio, others, [dict(start_s=s, dur_s=a.dur) for s in starts])
        # FR-317-30 — `allow_nan=False` here too: the CLI's JSONL is read back by the research scripts, and
        # a line with a bare `NaN` in it is a line no strict parser can take either.
        print(json.dumps(jsonable(dict(file=path, **r)), allow_nan=False), flush=True)
    print(json.dumps(dict(files=len(paths), wall_s=round(time.time() - wall, 2))))


if __name__ == '__main__':
    main()
