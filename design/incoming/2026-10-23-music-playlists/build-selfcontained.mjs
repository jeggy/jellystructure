/* build-selfcontained.mjs — inlines the directions canvas into one portable HTML file.

   PROTOTYPE, NOT THE SHIPPED SCRIPT, and kept beside the round it built. The round LINKS the
   sibling music-play-all stylesheet rather than copying it (PROCESS.md step 3), which is right
   for the frames and for the PNGs: it makes two rounds of music pictures diffable and keeps the
   three woff2 in one place. But "what a round must come home with" asks for a self-contained
   directions doc, so this script produces one — two stylesheets, three scripts and three faces
   folded into a single file with no link out and no network reference.

   Usage (from the repo root):  node design/incoming/<round>/build-selfcontained.mjs
*/

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, 'Music Playlists - Directions.html');
const OUT = resolve(here, 'Music Playlists - Directions - self-contained.html');

const read = (p) => readFileSync(resolve(here, p), 'utf8');
const b64 = (p) => readFileSync(resolve(here, p)).toString('base64');

/* the faces the linked stylesheet reaches by relative path; as data URIs they reach nothing */
const FONTS = {
  "url('fonts/space-grotesk.woff2')": `url(data:font/woff2;base64,${b64('../../ravilo/music-play-all/fonts/space-grotesk.woff2')})`,
  "url('fonts/sora.woff2')": `url(data:font/woff2;base64,${b64('../../ravilo/music-play-all/fonts/sora.woff2')})`,
  "url('fonts/jetbrains-mono.woff2')": `url(data:font/woff2;base64,${b64('../../ravilo/music-play-all/fonts/jetbrains-mono.woff2')})`,
};

let base = read('../../ravilo/music-play-all/music-play-all.css');
for (const [from, to] of Object.entries(FONTS)) base = base.split(from).join(to);
const round = read('music-playlists.css');

const scripts = [
  read('../../ravilo/music-play-all/music-play-all-kit.js'),
  read('mpl-data.js'),
  read('mpl-kit.js'),
];

let html = read(SRC);
html = html.replace(
  /<link rel="stylesheet" href="\.\.\/\.\.\/ravilo\/music-play-all\/music-play-all\.css">\s*<link rel="stylesheet" href="music-playlists\.css">/,
  `<style>\n${base}\n</style>\n<style>\n${round}\n</style>`);
html = html.replace(
  /<script src="\.\.\/\.\.\/ravilo\/music-play-all\/music-play-all-kit\.js"><\/script>\s*<script src="mpl-data\.js"><\/script>\s*<script src="mpl-kit\.js"><\/script>/,
  scripts.map((s) => `<script>\n${s}\n</script>`).join('\n'));

/* a self-contained file that still points at a sibling is not self-contained */
for (const bad of ['<link rel="stylesheet"', '<script src=', 'http://', 'https://']) {
  if (html.includes(bad)) throw new Error(`self-contained build still contains ${bad}`);
}

writeFileSync(OUT, html);
console.log(`wrote ${OUT} — ${(html.length / 1024).toFixed(0)} KiB, no link out`);
