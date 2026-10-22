/* render.mjs — renders every named state of the coming-soon-stacks round to png/.
   Round slug coming-soon-stacks, 2026-10-22 (lane C, Ravilo: TV + phone + desktop).

   PROTOTYPE, NOT THE SHIPPED SCRIPT. The round's own renderer, kept beside the round it
   rendered so these PNGs can be reproduced exactly. The permanent script under scripts/ is the
   engineer's to write, and the command that drove this one is recorded verbatim in REPORT.md.

   It mirrors how the repo already runs Playwright in docker-compose.test.yml: the pinned image
   supplies the OS dependencies, `npm ci` + `npx playwright install chromium` supply a browser
   matching tests/package-lock.json. The image pin and the lockfile are NOT the same version and
   are not meant to be — see REPORT.md.

   Usage (from the repo root, inside the container):  node design/incoming/<round>/render.mjs
*/

import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));

/* The round sits three levels under the repo root and Playwright lives in tests/. A bare
   `import ... from 'playwright'` cannot find it: ESM resolves from the importing FILE's directory
   upwards and never consults NODE_PATH, so tests/node_modules is invisible from here. Resolved by
   path instead — no symlink into the repo root, which several sessions share. */
const PW = process.env.PLAYWRIGHT_PKG || resolve(here, '../../../tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

const png = resolve(here, 'png');
mkdirSync(png, { recursive: true });

const out = (name) => resolve(png, `coming-soon-stacks-${name}.png`);
const url = (set, skin = 'aurora', lang = 'en') =>
  'file://' + resolve(here, 'frames.html') + `?set=${set}&skin=${skin}&lang=${lang}`;

/* The three faces are local woff2. Without waiting for them the first frame can render in a
   fallback face and every later one in the real face, which reads as a type decision nobody made.
   font-display is `block` in the round's CSS for the same reason. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(180);
}

/* `pick` is an optional descendant selector, used where the frame is a whole 1920×1080 screen but
   the picture wanted is one component of it (the date strip). */
async function shoot(page, id, name, pick) {
  const el = pick ? page.locator(`#f-${id} ${pick}`) : page.locator(`#f-${id}`);
  await el.waitFor({ state: 'visible' });
  await el.screenshot({ path: out(name) });
  console.log('  ✓', name);
}

/* Every page load, and what comes off it. One entry per (set, skin, lang) combination, because
   the skin lives on <html> and the language is baked into the markup at build time. */
const PASSES = [
  { set: 'tv', vp: { width: 1920, height: 1200 }, shots: [
    'tv-a-today', 'tv-a-focus', 'tv-b-today', 'tv-b-focus', 'tv-c-today', 'tv-c-focus',
    'tv-a-times', 'tv-a-times-range', 'tv-a-times-count', 'tv-a-min3',
    'tv-a-mixed', 'tv-b-mixed', 'tv-a-missing', 'tv-b-missing',
    'tv-a-chip-series', 'tv-a-chip-movies', 'tv-a-offline',
  ] },
  { set: 'tv', skin: 'midnight', vp: { width: 1920, height: 1200 },
    shots: [['tv-a-today', 'tv-a-midnight']] },
  { set: 'tv', skin: 'noir', vp: { width: 1920, height: 1200 },
    shots: [['tv-a-today', 'tv-a-noir']] },
  /* Danish is the longest-string case on this surface: "Allerede tilgængelig" beats the English
     pill, and "4 af 6 tilgængelige" is the longest string the round introduces anywhere. */
  { set: 'tv', lang: 'da', vp: { width: 1920, height: 1200 },
    shots: [['tv-a-today', 'tv-a-da'], ['tv-a-mixed', 'tv-a-da-longest']] },
  { set: 'tv', lang: 'fo', vp: { width: 1920, height: 1200 },
    shots: [['tv-a-today', 'tv-a-fo'], ['tv-a-mixed', 'tv-a-fo-mixed']] },

  /* the date strip under both count rules, cropped to the strip so the two can be read side by
     side rather than hunted for inside two full screens */
  { set: 'strip', vp: { width: 1920, height: 1200 }, pick: '.up-railwrap',
    shots: ['tv-strip-cards', 'tv-strip-episodes'] },

  /* what selecting a stack opens, and the two states behind it that have no picture yet */
  { set: 'detail', vp: { width: 1920, height: 1200 },
    shots: ['tv-detail-notheld', 'tv-detail-loading', 'tv-detail-error'] },

  { set: 'phone', vp: { width: 1000, height: 1000 }, shots: [
    'ph-a-today', 'ph-b-today', 'ph-c-today', 'ph-a-mixed', 'ph-a-missing', 'ph-a-land',
    'dk-a-compact',
  ] },
  /* NOT noir. ravilo-phone.css carries graphite and daylight as full skins and gives midnight and
     noir nothing but a --bg-rgb override, so `?skin=noir` on the phone renders aurora exactly —
     verified by rendering it and finding the two frames identical. The three skins the brief asks
     for are covered on the TV, where they are defined; the phone is covered in the skins it
     actually has. See REPORT.md. */
  { set: 'phone', skin: 'graphite', vp: { width: 1000, height: 1000 },
    shots: [['ph-a-today', 'ph-a-graphite']] },
  /* Daylight is the phone's real light theme (ravilo-phone.css). The poster keeps its dark art:
     a surface with a picture behind it is not light-themed. */
  { set: 'phone', skin: 'daylight', vp: { width: 1000, height: 1000 },
    shots: [['ph-a-today', 'ph-a-daylight']] },
  { set: 'phone', lang: 'da', vp: { width: 1000, height: 1000 },
    shots: [['ph-a-today', 'ph-a-da']] },
  { set: 'phone', lang: 'fo', vp: { width: 1000, height: 1000 },
    shots: [['ph-a-today', 'ph-a-fo']] },

  { set: 'desktop', vp: { width: 1500, height: 1000 }, shots: [
    'dk-a-wide', 'dk-b-wide', 'dk-c-wide', 'dk-a-focus', 'dk-a-mixed',
  ] },
  { set: 'desktop', skin: 'noir', vp: { width: 1500, height: 1000 },
    shots: [['dk-a-wide', 'dk-a-noir']] },
];

const browser = await chromium.launch();
let n = 0;

try {
  for (const pass of PASSES) {
    const skin = pass.skin || 'aurora';
    const lang = pass.lang || 'en';
    const ctx = await browser.newContext({ viewport: pass.vp, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url(pass.set, skin, lang));
    await settle(page);
    console.log(`${pass.set} · ${skin} · ${lang}`);
    for (const s of pass.shots) {
      const [id, name] = Array.isArray(s) ? s : [s, s];
      await shoot(page, id, name, pass.pick);
      n++;
    }
    await ctx.close();
  }
} finally {
  await browser.close();
}

console.log(`done — ${n} frames`);
