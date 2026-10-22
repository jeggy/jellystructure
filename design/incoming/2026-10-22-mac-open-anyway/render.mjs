/* render.mjs — renders every named state of the mac-open-anyway round to png/.
   Round slug mac-open-anyway, 2026-10-22 (lane C pilot round 1).

   PROTOTYPE, NOT THE SHIPPED SCRIPT. This is the round's own renderer, kept beside the round it
   rendered so the PNGs can be reproduced exactly. The permanent script under scripts/ is the
   engineer's to write, and the command that drove this one is recorded in REPORT.md.

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

/* The round lives three levels under the repo root and Playwright lives in tests/. A bare
   `import ... from 'playwright'` cannot find it: ESM resolves from the importing FILE's directory
   upwards and never consults NODE_PATH, so tests/node_modules is invisible from here. Resolved by
   path instead — no symlink into the repo root, which several sessions share. */
const PW = process.env.PLAYWRIGHT_PKG || resolve(here, '../../../tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);
const png = resolve(here, 'png');
mkdirSync(png, { recursive: true });

const url = (f, q = '') => 'file://' + resolve(here, f) + q;
const out = (name) => resolve(png, `mac-open-anyway-${name}.png`);

/* Fonts are local woff2 beside the markup; without this the first frame can render in a fallback
   face and every later frame in the real one, which reads as a type decision nobody made. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
}

async function shoot(page, id, name) {
  const el = page.locator('#' + id);
  await el.waitFor({ state: 'visible' });
  await el.screenshot({ path: out(name) });
  console.log('  ✓', name);
}

const DESKTOP = ['a-desktop', 'a-desktop-toggle', 'a-desktop-collapsed', 'b-desktop', 'a-desktop-noimg'];
const PHONE = ['a-phone', 'b-phone', 'a-phone-noimg', 'a-phone-da', 'a-phone-fo'];

const browser = await chromium.launch();

try {
  /* ---- the download page, desktop: a 1280 viewport, which is the width the live page's 1080px
     wrap stops growing at. */
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url('page.html', '?mode=desktop'));
    await settle(page);
    console.log('download page · desktop 1280');
    for (const id of DESKTOP) await shoot(page, id, id);
    await ctx.close();
  }

  /* ---- the download page, phone: a real 390 viewport rather than a narrow box on a wide one, so
     the page's own viewport-relative type (the section head is a clamp on vw) is honest. */
  {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url('page.html', '?mode=phone'));
    await settle(page);
    console.log('download page · phone 390');
    for (const id of PHONE) await shoot(page, id, id);
    await ctx.close();
  }

  /* ---- the .dmg background, the deliverable asset. 720 x 520 pt of Finder content area:
     one image pixel per point at 1x, two at 2x, same picture. */
  for (const scale of [1, 2]) {
    const ctx = await browser.newContext({ viewport: { width: 900, height: 700 }, deviceScaleFactor: scale });
    const page = await ctx.newPage();
    await page.goto(url('dmg.html'));
    await settle(page);
    console.log(`.dmg background · ${scale}x`);
    await shoot(page, 'dmg', `dmg-bg-${scale}x`);
    await ctx.close();
  }

  /* ---- the same background in a Light and a Dark Finder window, with the two icons on their
     drawn targets. The background bytes are identical in both; only the chrome and the labels
     change, which is the whole point of the mid-tone label bands. */
  {
    const ctx = await browser.newContext({ viewport: { width: 1700, height: 760 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url('dmg-window.html'));
    await settle(page);
    console.log('.dmg background in a Finder window');
    await shoot(page, 'win-light', 'dmg-window-light');
    await shoot(page, 'win-dark', 'dmg-window-dark');
    await ctx.close();
  }
} finally {
  await browser.close();
}

console.log('done');
