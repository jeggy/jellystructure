/* render.mjs — renders every named state of the processing-features round to png/.
   Round slug processing-features, 2026-10-22 (lane C, pilot round 2).

   PROTOTYPE, NOT THE SHIPPED SCRIPT, exactly as pilot round 1's was. It lives beside the round it
   rendered so the PNGs can be reproduced; the permanent script under scripts/ is the engineer's to
   write, and the command that drove this one is in REPORT.md verbatim.

   Usage (from the repo root, inside the container):
     node design/incoming/2026-10-22-processing-features/render.mjs
*/

import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));

/* Playwright is resolved BY PATH. A bare `import ... from 'playwright'` fails from
   design/incoming/<round>/: ESM resolves from the importing file's directory upwards and ignores
   NODE_PATH entirely, so tests/node_modules is invisible from here. No symlink at the repo root —
   several sessions share this checkout. */
const PW = process.env.PLAYWRIGHT_PKG || resolve(here, '../../../tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

const png = resolve(here, 'png');
mkdirSync(png, { recursive: true });

const url = (q) => 'file://' + resolve(here, 'frames.html') + q;
const out = (name) => resolve(png, `processing-features-${name}.png`);

/* The three families are self-hosted woff2 reached through wf.css. Without waiting for them the
   first frame can render in a fallback face and every later one in the real face, which reads as a
   type decision nobody made. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(200);
}

async function shootAll(page, label) {
  const ids = await page.evaluate(() =>
    [...document.querySelectorAll('.frame')].map((el) => el.id));
  console.log(`${label} · ${ids.length} frames`);
  for (const id of ids) {
    const el = page.locator('#' + id);
    await el.waitFor({ state: 'visible' });
    await el.screenshot({ path: out(id) });
    console.log('  ✓', id);
  }
  return ids.length;
}

const browser = await chromium.launch();
let n = 0;
try {
  /* desktop: a 1440 viewport, which is where this admin's 248px sidebar plus a content column stops
     being the interesting case. The frames are 1440 wide, so the picture is the whole page. */
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url('?w=desktop'));
    await settle(page);
    n += await shootAll(page, 'desktop 1440');
    await ctx.close();
  }

  /* phone: a real 412 viewport — the Pixel 9 width the brief fixes, and the width the admin's own
     breakpoints have to be honest at. Not a narrow box inside a wide window. */
  {
    const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.goto(url('?w=phone'));
    await settle(page);
    n += await shootAll(page, 'phone 412');
    await ctx.close();
  }
} finally {
  await browser.close();
}
console.log(`done · ${n} png`);
