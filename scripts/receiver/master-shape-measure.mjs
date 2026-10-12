// R291 (FR-R291-3, item 2) — "can the video variant be requested WITHOUT audio?", measured on the host.
//
// The cast receiver fails R291's composed master with Shaka 3018 TRANSMUXING_FAILED before it fetches any
// rendition (Stue TV's Chromecast, 2026-10-09 and again 2026-10-10 with fMP4 renditions), while our own
// encoder's master plays. This reproduces both master SHAPES in a desktop Chromium against the same engine
// family the receiver runs (shaka-player 4.16; CAF's bundled Shaka is 4.15), so the cause can be settled
// without spending a cast on a TV:
//
//   jellyfin.m3u8              Jellyfin's variant (muxed audio, CODECS names it) + an `aud` group — today's
//                              composed master for a client that declares `hls_audio_renditions`
//   jellyfin-videoonly.m3u8    the same segments, but CODECS naming the video alone
//   jellyfin-noaudiogroup.m3u8 the same variant with NO audio group — what the receiver is handed today
//   ours.m3u8                  our encoder's shape: a video-only variant, every track its own rendition
//
// The fixture is synthetic (H.264 + AAC from lavfi, fMP4 segments on the same 3 s grid): the question is the
// master's shape and what MSE will take, not the codecs, and this Chromium has no HEVC or AC-3 decoder.
//
// Prerequisites: ffmpeg on PATH, and a directory whose node_modules holds @playwright/test and shaka-player
// (`cd tests && npm install shaka-player@4`). Usage:
//
//   node scripts/receiver/master-shape-measure.mjs <work-dir> <node_modules-owner-dir>
//
// e.g. `node scripts/receiver/master-shape-measure.mjs /tmp/r291 tests`. Prints one line per shape.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const WORK = path.resolve(process.argv[2] || 'build/r291-master-shape');
const DEPS = path.resolve(process.argv[3] || 'tests');
const require = createRequire(path.join(DEPS, 'package.json'));
const { chromium } = require('@playwright/test');
const SHAKA = path.join(path.dirname(require.resolve('shaka-player/package.json')), 'dist/shaka-player.compiled.debug.js');

const HLS = path.join(WORK, 'hls');
const ff = (args) => execFileSync('ffmpeg', ['-nostdin', '-loglevel', 'error', '-y', ...args], { stdio: ['ignore', 'inherit', 'inherit'] });

// ── the fixture: 12 s of colour bars with two different sine tracks, as fMP4 HLS on a 3 s grid ────────────
if (!fs.existsSync(path.join(HLS, 'v/main.m3u8'))) {
  for (const d of ['v', 'vo', 'audio/0', 'audio/1']) fs.mkdirSync(path.join(HLS, d), { recursive: true });
  const src = path.join(WORK, 'src.mp4'), alt = path.join(WORK, 'alt.m4a');
  ff(['-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
    '-map', '0:v', '-map', '1:a', '-t', '12', '-c:v', 'libx264', '-profile:v', 'high', '-level', '4.0',
    '-pix_fmt', 'yuv420p', '-g', '24', '-c:a', 'aac', '-ac', '2', '-b:a', '128k', src]);
  ff(['-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000', '-t', '12', '-c:a', 'aac', '-ac', '2', '-b:a', '128k', alt]);
  const hls = (dir) => ['-f', 'hls', '-hls_time', '3', '-hls_playlist_type', 'vod', '-hls_segment_type', 'fmp4',
    '-hls_fmp4_init_filename', 'init.mp4', '-hls_segment_filename', path.join(HLS, dir, '%d.m4s'), path.join(HLS, dir, 'main.m3u8')];
  ff(['-i', src, '-c', 'copy', ...hls('v')]);                  // video + muxed audio
  ff(['-i', src, '-an', '-c:v', 'copy', ...hls('vo')]);         // video only
  ff(['-i', src, '-vn', '-c:a', 'copy', ...hls('audio/0')]);    // the carried track, alone
  ff(['-i', alt, '-vn', '-c:a', 'copy', ...hls('audio/1')]);    // another track
}

// The four masters. `a{position} {label}` is the backend's own naming (`AudioRenditions.composeMaster`);
// a rendition with no URI means "muxed in the variant", which is what that master writes for the carried track.
const CARRIED_MUXED = '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="a0 English",LANGUAGE="eng",DEFAULT=YES,AUTOSELECT=YES';
const CARRIED_OWN = '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="a0 English",LANGUAGE="eng",DEFAULT=YES,AUTOSELECT=YES,URI="audio/0/main.m3u8"';
const OTHER = '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="a1 Dansk",LANGUAGE="dan",DEFAULT=NO,AUTOSELECT=NO,URI="audio/1/main.m3u8"';
const MASTERS = {
  'jellyfin.m3u8': [CARRIED_MUXED, OTHER, '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=320x240,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"', 'v/main.m3u8'],
  'jellyfin-videoonly.m3u8': [CARRIED_MUXED, OTHER, '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=320x240,CODECS="avc1.640028",AUDIO="aud"', 'v/main.m3u8'],
  'jellyfin-noaudiogroup.m3u8': ['#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=320x240,CODECS="avc1.640028,mp4a.40.2"', 'v/main.m3u8'],
  'ours.m3u8': [CARRIED_OWN, OTHER, '#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=320x240,CODECS="avc1.640028",AUDIO="aud"', 'vo/main.m3u8'],
};
for (const [name, lines] of Object.entries(MASTERS)) fs.writeFileSync(path.join(HLS, name), ['#EXTM3U', ...lines].join('\n') + '\n');

// ── serve the tree (and shaka) from localhost ─────────────────────────────────────────────────────────────
const MIME = { '.m3u8': 'application/vnd.apple.mpegurl', '.m4s': 'video/iso.segment', '.mp4': 'video/mp4' };
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (url === '/shaka.js') { res.writeHead(200, { 'content-type': 'application/javascript' }); return fs.createReadStream(SHAKA).pipe(res); }
  if (url === '/') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<!doctype html><video id=v autoplay muted></video><script src="/shaka.js"></script>'); }
  const f = path.join(HLS, url);
  if (!f.startsWith(HLS) || !fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

// ── load each shape, play, switch to the other rendition, play on ─────────────────────────────────────────
const browser = await chromium.launch();
const page = await browser.newPage();
const rows = [];
for (const master of Object.keys(MASTERS)) {
  await page.goto(base + '/');
  rows.push(await page.evaluate(async (url) => {
    const out = { master: url.split('/').pop(), shaka: shaka.Player.version, errors: [] };
    const video = document.getElementById('v');
    const player = new shaka.Player();
    await player.attach(video);
    player.addEventListener('error', (e) => out.errors.push(`${e.detail.code} (category ${e.detail.category})`));
    const wait = (ms) => new Promise((r) => setTimeout(r, ms));
    try {
      await player.load(url);
      await wait(1500);
      out.playingAt = video.currentTime;
      const other = player.getVariantTracks().find((t) => !t.active);
      if (other) {
        out.switchedTo = other.label;
        player.selectVariantTrack(other, false);
        await wait(2500);
        out.playingAfterSwitch = video.currentTime;
      }
    } catch (e) {
      out.loadError = `${e.code} (category ${e.category})`;
    }
    out.readyState = video.readyState;
    out.renditions = player.getVariantTracks().map((t) => t.label);
    try { await player.destroy(); } catch (e) { /* teardown is not the measurement */ }
    return out;
  }, base + '/' + master));
}
await browser.close();
server.close();

const n = (v) => (typeof v === 'number' ? v.toFixed(2) : '—');
for (const r of rows) {
  const played = r.readyState === 4 && r.playingAt > 0;
  console.log(
    `${r.master.padEnd(28)} ${(played ? 'PLAYS' : 'FAILS').padEnd(6)} readyState=${r.readyState} ` +
    `t=${n(r.playingAt)}s → ${n(r.playingAfterSwitch)}s after picking ${r.switchedTo || '—'} ` +
    `renditions=[${r.renditions.join(', ')}] ${r.loadError ? 'load error ' + r.loadError : ''}${r.errors.length ? 'errors ' + [...new Set(r.errors)].join(', ') : ''}`,
  );
}
console.log(`shaka ${rows[0]?.shaka} in this Chromium (CAF's own is 4.15.x)`);
