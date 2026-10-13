"""Phase 317 (FR-317-2) — the A/V sync sidecar's HTTP face.

The whole service is three routes over [measure]'s functions, on the stdlib's own server: the backend asks
for one file at a time and nothing else talks to this (no auth, no TLS — it is reachable only on the
compose network, like every other sidecar in this stack).

    GET  /health           → {ok, gpu, vram_total_gb, model, busy, busy_for_s, current_path_basename}
    POST /stat    {path}   → {exists, size}                        — the mount check (FR-317-13)
    POST /measure {path, audio, others, windows:[{start_s, dur_s}]}
                           → 200 {windows, others, decode, seconds}
                             404 the path is not visible here · 422 undecodable (ffmpeg's last line)
                             500 a decoder stalled, or the call passed its ceiling (FR-317-27)
                             503 {retry_after_s} a measurement is already running

**One measurement at a time**: the GPU worker is single (FR-317-2's measured limits), so a second
request is refused rather than queued — the backend's `avsync` lane is the queue (FR-317-8), and a
request parked inside this process would hold a backend worker for as long as it waited.
"""
import json, os, threading, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import measure

PORT = int(os.environ.get('AVSYNC_PORT', '8000'))
# One file is ~8 s (FR-317-3); a busy answer asks for twice that so a caller that ignores the lane rule
# still backs off instead of spinning.
RETRY_AFTER_S = 20

lock = threading.Lock()
measure._lock = lock      # so /health can report `busy` without a second source of truth


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'jellystructure-avsync'

    def log_message(self, fmt, *args):            # one line per request, on stdout with the rest
        print(f'avsync {self.command} {self.path} — {fmt % args}', flush=True)

    def send_json(self, code, body):
        # FR-317-30 — the ONE place a document becomes bytes, so no route can emit a bare `NaN` or
        # `Infinity`: [measure.jsonable] turns a non-finite float into `null` and marks the object it sat
        # in, and `allow_nan=False` is the guard that this stays true. Anything that ever gets past the
        # sanitiser raises here — a 500 the caller can read — instead of writing JSON nobody can parse.
        raw = json.dumps(measure.jsonable(body), allow_nan=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self):
        n = int(self.headers.get('Content-Length') or 0)
        if n <= 0: return {}
        return json.loads(self.rfile.read(n) or b'{}')

    def do_GET(self):
        if self.path.split('?')[0] != '/health':
            return self.send_json(404, dict(error='no such route'))
        self.send_json(200, measure.health())

    def do_POST(self):
        route = self.path.split('?')[0]
        try:
            body = self.read_json()
        except ValueError as e:
            return self.send_json(400, dict(error=f'bad JSON: {e}'))
        if route == '/stat':
            path = body.get('path') or ''
            return self.send_json(200, measure.stat(path))
        if route != '/measure':
            return self.send_json(404, dict(error='no such route'))
        path = body.get('path') or ''
        windows = body.get('windows') or []
        if not path or not windows:
            return self.send_json(400, dict(error='path and windows are both required'))
        if not lock.acquire(blocking=False):
            return self.send_json(503, dict(error='a measurement is already running', retry_after_s=RETRY_AFTER_S))
        measure.busy_started(path)                # FR-317-28: /health's `busy_for_s` starts here
        try:
            result = measure.measure(path, body.get('audio') or '0:a:0', body.get('others') or [], windows)
            self.send_json(200, result)
        except FileNotFoundError:
            self.send_json(404, dict(error=f'{path} is not visible to the sidecar'))
        except measure.Undecodable as e:
            self.send_json(422, dict(error=str(e)))
        except (measure.DecodeStalled, measure.MeasureTimeout) as e:
            # FR-317-27: a stall is a fault of the run, not of the file — 500 with the reason, and
            # deliberately not 422, which already means *this file cannot be decoded*.
            print(f'avsync {e}', flush=True)
            self.send_json(500, dict(error=str(e)))
        except Exception as e:                    # a GPU OOM, a torch error: say it, stay up
            traceback.print_exc()
            self.send_json(500, dict(error=f'{type(e).__name__}: {e}'))
        finally:
            measure.busy_done()
            lock.release()


if __name__ == '__main__':
    print(f'avsync listening on :{PORT} — {measure.health()}', flush=True)
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
