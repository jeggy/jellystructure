/* render.mjs — renders every named state of the music-playlists round to png/.
   Round slug music-playlists, 2026-10-23 (Ravilo music: phone + desktop/web).

   PROTOTYPE, NOT THE SHIPPED SCRIPT. The round's own renderer, kept beside the round it rendered
   so these PNGs can be reproduced exactly. scripts/render-design.sh (DEB-2125) is the engineer's
   and was still not merged when this round ran; the command that drove it is recorded verbatim in
   REPORT.md.

   It mirrors how the repo already runs Playwright in docker-compose.test.yml: the pinned image
   supplies the OS dependencies, `npx playwright install chromium` supplies a browser matching
   tests/package-lock.json. The image pin and the lockfile are NOT the same version and are not
   meant to be.

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

const out = (name) => resolve(png, `music-playlists-${name}.png`);
const url = (set, skin = 'aurora', lang = 'en') =>
  'file://' + resolve(here, 'frames.html') + `?set=${set}&skin=${skin}&lang=${lang}`;

/* The three faces are local woff2, reached through the linked music-play-all stylesheet. Without
   waiting for them the first frame can render in a fallback face and every later one in the real
   face, which reads as a type decision nobody made. font-display is `block` there for the same
   reason. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(200);
}

/* The holder carries a caption for a human reading frames.html; the PNG is the frame alone. */
async function shoot(page, id, name) {
  const el = page.locator(`#f-${id} .fr`);
  await el.waitFor({ state: 'visible' });
  await el.screenshot({ path: out(name) });
  console.log('  ✓', name);
}

const PHONE = [
  'ph-picker-5-n1', 'ph-picker-5-n2', 'ph-picker-none', 'ph-picker-long',
  'ph-name-empty', 'ph-name-typing', 'ph-name-error', 'ph-name-rename',
  'ph-pl-rest', 'ph-pl-menu', 'ph-pl-shared', 'ph-pl-shared-badge', 'ph-pl-empty', 'ph-pl-empty-menu',
  'ph-edit-rest', 'ph-edit-lift', 'ph-edit-swipe',
  'ph-confirm', 'ph-album-more', 'ph-album-menu', 'ph-album-editions', 'ph-album-editions-merged', 'ph-queue-save',
  'ph-song-sheet', 'ph-song-sheet-in-pl', 'ph-toasts',
];

const DESKTOP = [
  'dk-pop-5', 'dk-dlg-5', 'dk-pop-n2', 'dk-pop-none', 'dk-pop-long', 'dk-pop-up',
  'dk-name-empty', 'dk-name-typing', 'dk-name-error', 'dk-name-rename',
  'dk-pl-rest', 'dk-pl-menu', 'dk-pl-shared', 'dk-pl-shared-badge', 'dk-pl-empty', 'dk-pl-empty-menu',
  'dk-edit-rest', 'dk-edit-hover-e1', 'dk-edit-e2', 'dk-edit-lift',
  'dk-confirm', 'dk-album-more', 'dk-album-menu', 'dk-queue-save', 'dk-toast',
];

/* One entry per (set, skin, lang): the skin lives on <html> and the language is baked into the
   markup at build time, so neither can be switched after the page is built. */
const PASSES = [
  { set: 'phone', vp: { width: 520, height: 900 }, shots: PHONE },
  { set: 'desktop', vp: { width: 1280, height: 860 }, shots: DESKTOP },

  /* Music redefines midnight and noir, so both are drawn rather than assumed. Noir inverts the
     accent to amber, which is exactly the skin where a destructive red and a gradient Create
     button are worth looking at: --pl-bad is given its own noir value for that reason. */
  { set: 'phone', skin: 'midnight', shots: [
    ['ph-picker-5-n1', 'ph-picker-5-n1-midnight'], ['ph-pl-menu', 'ph-pl-menu-midnight'],
    ['ph-edit-swipe', 'ph-edit-swipe-midnight'], ['ph-confirm', 'ph-confirm-midnight'],
    ['ph-name-error', 'ph-name-error-midnight'],
  ] },
  { set: 'phone', skin: 'noir', shots: [
    ['ph-picker-5-n1', 'ph-picker-5-n1-noir'], ['ph-pl-menu', 'ph-pl-menu-noir'],
    ['ph-edit-swipe', 'ph-edit-swipe-noir'], ['ph-confirm', 'ph-confirm-noir'],
    ['ph-name-error', 'ph-name-error-noir'], ['ph-name-empty', 'ph-name-empty-noir'],
  ] },
  { set: 'desktop', skin: 'midnight', vp: { width: 1280, height: 860 }, shots: [
    ['dk-pop-5', 'dk-pop-5-midnight'], ['dk-pl-menu', 'dk-pl-menu-midnight'],
  ] },
  { set: 'desktop', skin: 'noir', vp: { width: 1280, height: 860 }, shots: [
    ['dk-pop-5', 'dk-pop-5-noir'], ['dk-pl-menu', 'dk-pl-menu-noir'],
    ['dk-edit-hover-e1', 'dk-edit-hover-e1-noir'], ['dk-confirm', 'dk-confirm-noir'],
  ] },

  /* The longest string. The brief asks only for the English, but a menu, a confirm and a hint are
     exactly where Faroese overruns and the house system makes every round draw that state. The
     strings are FR-16's own drafts, not translations invented in a drawing. */
  { set: 'phone', lang: 'fo', shots: [
    ['ph-pl-menu', 'ph-pl-menu-fo'], ['ph-confirm', 'ph-confirm-fo'],
    ['ph-pl-empty-menu', 'ph-pl-empty-menu-fo'], ['ph-song-sheet-in-pl', 'ph-song-sheet-in-pl-fo'],
    ['ph-queue-save', 'ph-queue-save-fo'], ['ph-name-error', 'ph-name-error-fo'],
    ['ph-toasts', 'ph-toasts-fo'],
  ] },
  { set: 'phone', lang: 'da', shots: [
    ['ph-pl-menu', 'ph-pl-menu-da'], ['ph-queue-save', 'ph-queue-save-da'],
    ['ph-pl-empty-menu', 'ph-pl-empty-menu-da'],
  ] },
  { set: 'desktop', lang: 'fo', vp: { width: 1280, height: 860 }, shots: [
    ['dk-pl-menu', 'dk-pl-menu-fo'], ['dk-queue-save', 'dk-queue-save-fo'],
    ['dk-confirm', 'dk-confirm-fo'],
  ] },
];

const browser = await chromium.launch();
let n = 0;

try {
  for (const pass of PASSES) {
    const skin = pass.skin || 'aurora';
    const lang = pass.lang || 'en';
    const vp = pass.vp || { width: 520, height: 900 };
    const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 1 });
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
