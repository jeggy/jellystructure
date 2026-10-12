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
import argparse, json, os, subprocess, sys, time
from collections import deque
from concurrent.futures import ThreadPoolExecutor

import numpy as np, python_speech_features, torch, torch.nn.functional as F, torchvision
from scipy import signal

APP_DIR = os.environ.get('AVSYNC_APP_DIR', '/app')
# The pipeline's own version, appended to the model name: a bump makes every stored result due again
# (FR-317-2/FR-317-7). Raise it whenever a change here would move a reading.
PIPELINE_VERSION = 1
SYNCNET_COMMIT = '907c0b5'
MODEL = f'syncnet_v2+s3fd@{SYNCNET_COMMIT}/{PIPELINE_VERSION}'

# Limits the prototype measured (FR-317-2): one GPU worker, this many decoders ahead WITHIN the file, and a
# hard VRAM cap per process — the card is shared with Immich, which keeps ~4 GB.
VRAM_GB = float(os.environ.get('AVSYNC_VRAM_GB', '2'))
DECODERS = int(os.environ.get('AVSYNC_DECODERS', '3'))

FPS = 25                  # every reading is in 25 fps frames: one frame = 40 ms
VSHIFT = 15               # the distance curve spans −15…+15 frames, SyncNet's own range
RES = 640                 # decoded width; the height keeps the aspect
DETECT_EVERY = 5          # face detection on every Nth frame, linked + interpolated
DET_SCALE = 0.5           # detector input, relative to the decoded frame
MIN_TRACK = 50            # frames a face must stay on screen (2 s)
ENV_BUCKET_MS = 20        # the loudness envelope's resolution for the `others` correlation (half a frame)
ENV_MAX_LAG_S = 2.0       # FR-317-4: a dub is looked for within ±2 s of the measured track

dev, fp16 = 'cuda', not os.environ.get('AVSYNC_FP32')


class Undecodable(Exception):
    """ffmpeg could not read the file (or the stream asked for): `str(e)` is its last line → HTTP 422."""


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


# ── decode (NVDEC + scale_cuda + tonemap_cuda; AV1 on the CPU) ───────────────────────────────────

def probe(path):
    v = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                        'stream=codec_name,width,height,color_transfer', '-of', 'csv=p=0', path],
                       capture_output=True, text=True).stdout.strip().split(',')
    d = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                       capture_output=True, text=True).stdout.strip()
    if len(v) < 3 or not v[1].isdigit():
        raise Undecodable('ffprobe found no video stream')
    return dict(codec=v[0], w=int(v[1]), h=int(v[2]), trc=v[3] if len(v) > 3 else '', dur=float(d or 0))


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


def decode_video(path, start, dur, W, H, trc, gpu):
    """The window's frames as a uint8 CPU tensor, and whether NVDEC really served them."""
    hw = ['-hwaccel', 'cuda', '-hwaccel_output_format', 'cuda'] if gpu else ['-filter_threads', '8']
    cmd = ['ffmpeg', '-v', 'error', *hw, '-ss', str(start), '-i', path, '-t', str(dur), '-map', '0:v:0',
           '-vf', video_filter(W, H, trc, gpu), '-pix_fmt', 'bgr24', '-f', 'rawvideo', '-']
    fsz, cap = W * H * 3, int(dur * FPS) + 5
    buf = torch.empty((cap, H, W, 3), dtype=torch.uint8)   # read straight into the tensor: no bytes copies
    mv, off = memoryview(buf.numpy()).cast('B'), 0
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    while off < cap * fsz:
        k = p.stdout.readinto(mv[off:])
        if not k: break
        off += k
    p.stdout.close()
    err = p.stderr.read().decode('utf-8', 'replace'); p.stderr.close(); p.wait()
    if off < fsz and gpu:                         # a profile NVDEC refuses: the CPU decodes it instead
        return decode_video(path, start, dur, W, H, trc, False)
    if off < fsz:
        raise Undecodable(last_line(err) or 'ffmpeg produced no frames')
    return buf[:off // fsz], gpu


def decode_audio(path, start, dur, spec='0:a:0'):
    """One audio stream of the window as mono 16 kHz PCM. [spec] is an ffprobe/ffmpeg specifier
    (`0:a:N`, FR-317-2) — never Jellyfin's own numbering."""
    cmd = ['ffmpeg', '-v', 'error', '-ss', str(start), '-i', path, '-t', str(dur), '-map', spec, '-ac', '1',
           '-ar', '16000', '-f', 's16le', '-']
    r = subprocess.run(cmd, capture_output=True)
    pcm = np.frombuffer(r.stdout, np.int16)
    if pcm.size == 0:
        raise Undecodable(last_line(r.stderr.decode('utf-8', 'replace')) or f'no audio decoded from {spec}')
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
    n = min(lf.shape[0], af.shape[0] - f0)
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


# ── one file, measured (FR-317-2's `POST /measure`) ──────────────────────────────────────────────

def stat(path):
    try:
        st = os.stat(path)
        return dict(exists=True, size=st.st_size)
    except OSError:
        return dict(exists=False, size=0)


def health():
    busy = _lock.locked() if _lock is not None else False
    try:
        name = torch.cuda.get_device_name(0)
        vram = round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1)
    except Exception as e:                        # no card, no driver, the container without `video`
        return dict(ok=False, gpu=f'unavailable: {e}', vram_total_gb=0.0, model=MODEL, busy=busy)
    return dict(ok=True, gpu=name, vram_total_gb=vram, model=MODEL, busy=busy)


_lock = None      # set by server.py to the measurement lock, so /health can report `busy`


def measure(path, audio='0:a:0', others=(), windows=()):
    """FR-317-2/-3 — one file, its windows measured one after another on the one GPU worker, with
    [DECODERS] windows of the SAME file decoded ahead. Raises [Undecodable] (→ 422) and leaves
    `FileNotFoundError` (→ 404) to the caller."""
    if not os.path.exists(path): raise FileNotFoundError(path)
    t0 = time.time()
    p = probe(path)
    W = RES; H = int(round(p['h'] * W / p['w'] / 2) * 2)
    gpu = p['codec'] != 'av1'                     # no AV1 in Pascal/Turing NVDEC: dav1d on the CPU
    wins = [(float(w['start_s']), float(w.get('dur_s', 45))) for w in windows]
    wins = [(max(0.0, s), d) for s, d in wins if p['dur'] <= 0 or s < p['dur']]
    others = list(others)

    def fetch(start, dur):
        fr, used_gpu = decode_video(path, start, dur, W, H, p['trc'], gpu)
        pcm = decode_audio(path, start, dur, audio)
        extra = [decode_audio(path, start, dur, spec) if spec != audio else pcm for spec in others]
        return fr, pcm, extra, used_gpu

    out, nvdec = [], gpu
    ref_envs, other_envs = [], [[] for _ in others]
    with ThreadPoolExecutor(max(1, DECODERS)) as ex:
        q = deque()
        it = iter(wins)
        pending = next(it, None)
        while pending is not None and len(q) < max(1, DECODERS):
            q.append((pending, ex.submit(fetch, *pending))); pending = next(it, None)
        while q:
            (start, dur), fu = q.popleft()
            if pending is not None:
                q.append((pending, ex.submit(fetch, *pending))); pending = next(it, None)
            fr_cpu, pcm, extra, used_gpu = fu.result()
            nvdec = nvdec and used_gpu
            fr = fr_cpu.to(dev)
            dets = detect(fr, list(range(0, fr.shape[0], DETECT_EVERY)), W, H)
            trs = [t for s0, s1 in shots(fr) for t in tracks(dets, s0, s1, W)]
            af = audio_feats(pcm)
            curve, frames, faces = None, 0, []
            for fi, bb in trs:
                md, n = distance_curve(lip_feats(crops(fr, fi, bb)), af, int(fi[0]))
                curve = md * n if curve is None else curve + md * n; frames += n
                off, conf = verdict(md)
                faces.append(dict(at_s=round(start + fi[0] / FPS, 1), frames=n, offset_frames=off, conf=conf))
            torch.cuda.synchronize()
            if curve is not None:                 # the window's answer: every face's curve, weighted by its length
                off, conf = verdict(curve / frames)
                out.append(dict(start_s=round(start, 1), offset_frames=off, conf=conf, frames=frames, faces=faces))
            else:
                out.append(dict(start_s=round(start, 1), offset_frames=None, conf=0.0, frames=0, faces=[]))
            ref_envs.append(envelope(pcm))
            for i, pcm_other in enumerate(extra): other_envs[i].append(envelope(pcm_other))
            del fr, fr_cpu; torch.cuda.empty_cache()     # hand the card back between windows

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
        print(json.dumps(dict(file=path, **r)), flush=True)
    print(json.dumps(dict(files=len(paths), wall_s=round(time.time() - wall, 2))))


if __name__ == '__main__':
    main()
