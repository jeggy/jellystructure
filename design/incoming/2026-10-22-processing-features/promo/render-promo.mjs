/* render-promo.mjs — renders the PROMOTION (design/app/processing.css) at both widths and in both
   themes. 2026-10-23, DEB-2193.

   PROTOTYPE, not the shipped script. scripts/render-design.sh (DEB-2125) cannot pin the theme — it
   has no --theme and its --query only appends to the file URL, so it cannot reach the theme at all,
   and every frame it renders is light. An admin promotion has to be looked at in both, so this does
   it the way the previous two promotions did: set data-theme on <html> and loop.

   Usage (from the repo root, inside the container):
     node design/incoming/2026-10-22-processing-features/promo/render-promo.mjs
*/

import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));

/* Playwright is resolved BY PATH — ESM resolves from the importing file's own directory upwards and
   ignores NODE_PATH, so tests/node_modules is invisible from four levels down. No symlink at the
   repo root: several sessions share this checkout. */
const PW = process.env.PLAYWRIGHT_PKG || resolve(here, '../../../../tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

const png = resolve(here, 'png');
mkdirSync(png, { recursive: true });

const url = (q) => 'file://' + resolve(here, 'frames-promo.html') + q;
const out = (name, theme) => resolve(png, `promo-${name}-${theme}.png`);

/* The three families are self-hosted woff2 reached through wf.css. Without waiting for them the
   first frame can render in a fallback face and every later one in the real face, which reads as a
   type decision nobody made. */
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(200);
}

async function shootAll(page, label, theme) {
  const ids = await page.evaluate(() =>
    [...document.querySelectorAll('.frame')].map((el) => el.id));
  console.log(`${label} · ${theme} · ${ids.length} frames`);
  for (const id of ids) {
    const el = page.locator('#' + id);
    await el.waitFor({ state: 'visible' });
    await el.screenshot({ path: out(id, theme) });
  }
  return ids.length;
}

const WIDTHS = [
  ['desktop 1440', '?w=desktop', { width: 1440, height: 1000 }],
  /* a real 412 viewport, not a narrow box in a wide window: the promoted narrow faces key off
     @media (max-width: 640px), exactly as the admin's own do. */
  ['phone 412', '?w=phone', { width: 412, height: 915 }],
];

const browser = await chromium.launch();
let n = 0;
try {
  for (const [label, query, viewport] of WIDTHS) {
    for (const theme of ['dark', 'light']) {
      const ctx = await browser.newContext({ viewport, deviceScaleFactor: 1 });
      const page = await ctx.newPage();
      /* both, because the admin reads one and styles off the other: app-shell.js keeps the choice in
         localStorage['js-theme'] and reflects it onto <html data-theme>. */
      await page.addInitScript((t) => {
        try { localStorage.setItem('js-theme', t); } catch (e) { /* file:// can refuse */ }
      }, theme);
      await page.goto(url(query));
      await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
      await settle(page);
      n += await shootAll(page, label, theme);
      await ctx.close();
    }
  }
} finally {
  await browser.close();
}
console.log(`done · ${n} png`);
