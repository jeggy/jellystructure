/* render.mjs — renders every named state of the coming-soon-stack-shape round to png/.
   Round slug coming-soon-stack-shape, 2026-10-22 (Ravilo: TV + phone + desktop).

   PROTOTYPE, NOT THE SHIPPED SCRIPT. The round's own renderer, kept beside the round it
   rendered so these PNGs can be reproduced exactly. scripts/render-design.sh (DEB-2125) is the
   engineer's and is not merged as of this round; the command that drove this one is recorded
   verbatim in REPORT.md.

   It mirrors how the repo already runs Playwright in docker-compose.test.yml: the pinned image
   supplies the OS dependencies, `npx playwright install chromium` supplies a browser matching
   tests/package-lock.json. The image pin and the lockfile are NOT the same version and are not
   meant to be — see REPORT.md.

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

const out = (name) => resolve(png, `stack-shape-${name}.png`);
const url = (set, skin = 'aurora', lang = 'en') =>
  'file://' + resolve(here, 'frames.html') + `?set=${set}&skin=${skin}&lang=${lang}`;

/* The three faces are local woff2. Without waiting for them the first frame can render in a
   fallback face and every later one in the real face, which reads as a type decision nobody made.
   font-display is `block` in the round's CSS for the same reason. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(180);
}

async function shoot(page, id, name) {
  const el = page.locator(`#f-${id}`);
  await el.waitFor({ state: 'visible' });
  await el.screenshot({ path: out(name) });
  console.log('  ✓', name);
}

const TV_SET = dir => [
  `tv-${dir}-today`, `tv-${dir}-focus`, `tv-${dir}-two`, `tv-${dir}-mixed`, `tv-${dir}-missing`,
];

/* One entry per (set, skin, lang) combination, because the skin lives on <html> and the language
   is baked into the markup at build time. */
const PASSES = [
  { set: 'tv', vp: { width: 1920, height: 1200 }, shots: [
    'tv-a-compare',
    ...TV_SET('d'), ...TV_SET('e'), ...TV_SET('f'), ...TV_SET('g'),
    'tv-d-24', 'tv-e-24', 'tv-g-24',
  ] },

  /* All three TV skins, on the recommended direction. Noir in particular inverts the accent to
     amber, which is exactly the skin where a mixed pill that leaned on colour would disappear
     into the skin's own accent — so noir is where the mixed frame is worth having too. */
  { set: 'tv', skin: 'midnight', vp: { width: 1920, height: 1200 },
    shots: [['tv-d-today', 'tv-d-midnight']] },
  { set: 'tv', skin: 'noir', vp: { width: 1920, height: 1200 },
    shots: [['tv-d-today', 'tv-d-noir'], ['tv-d-mixed', 'tv-d-noir-mixed']] },

  /* Danish is the longest-string case on this surface: "Allerede tilgængelig" beats the English
     pill and "4 af 6 tilgængelige" is the longest string in the round's set. Faroese is drawn
     because the brief asks for it on the recommended direction. */
  { set: 'tv', lang: 'da', vp: { width: 1920, height: 1200 },
    shots: [['tv-d-today', 'tv-d-da'], ['tv-d-mixed', 'tv-d-da-longest']] },
  { set: 'tv', lang: 'fo', vp: { width: 1920, height: 1200 },
    shots: [['tv-d-today', 'tv-d-fo']] },

  { set: 'phone', vp: { width: 900, height: 1000 }, shots: [
    'ph-d-today', 'ph-e-today', 'ph-f-today', 'ph-g-today',
  ] },

  { set: 'desktop', vp: { width: 1500, height: 1000 }, shots: [
    'dk-d-wide', 'dk-e-wide', 'dk-f-wide', 'dk-g-wide',
  ] },
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
      await shoot(page, id, name);
      n++;
    }
    await ctx.close();
  }
} finally {
  await browser.close();
}

console.log(`done — ${n} frames`);
