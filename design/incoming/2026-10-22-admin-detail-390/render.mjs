// DEB-2145 — renders the detail page's frames for this fix, at two widths in both themes.
//
// scripts/render-design.sh (DEB-2125) is the permanent renderer and is what a round should use, but it
// cannot pin a theme: the admin's picker defaults to System, headless Chromium reports light, and half
// of these frames have to be dark. So this prototype sits beside the round, as design/PROCESS.md allows,
// and does three things render-design.sh does not: sets `js-theme` before the page boots, follows the
// page's `#tab=` hash, and renders the before/after pair of the shipped-markup harness.
//
// Run from the repo root with the repo's own Playwright, in Docker (design/PROCESS.md § Rendering):
//   docker run --rm -v "$PWD":/work -w /work -v ~/.cache/ms-playwright:/ms-playwright-host \
//     -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host -e HOME=/tmp --user "$(id -u):$(id -g)" \
//     --network none mcr.microsoft.com/playwright:v1.45.0-noble \
//     node design/incoming/2026-10-22-admin-detail-390/render.mjs
import { chromium } from '/work/tests/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const ROUND = '/work/design/incoming/2026-10-22-admin-detail-390';
const OUT = ROUND + '/png';
mkdirSync(OUT, { recursive: true });

// The mockup detail page (links the production CSS), then the shipped-markup harness before and after.
const frames = [];
for (const width of [390, 1040]) {
  for (const tab of ['overview', 'tracks']) {
    for (const theme of ['dark', 'light']) {
      frames.push({ name: `media-${tab}-${width}-${theme}`, url: `file://${ROUND}/../../app/media.html#tab=${tab}`, width, theme });
    }
  }
}
for (const state of ['before', 'after']) {
  for (const tab of ['overview', 'tracks']) {
    for (const theme of ['dark', 'light']) {
      frames.push({
        name: `shipped-markup-${state}-${tab}-390-${theme}`,
        url: `file://${ROUND}/real-markup.html?state=${state}&tab=${tab}`, width: 390, theme,
      });
    }
  }
}

const browser = await chromium.launch();
let n = 0;
for (const f of frames) {
  const ctx = await browser.newContext({ viewport: { width: f.width, height: 1000 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const offHost = [];
  page.on('request', r => { if (!r.url().startsWith('file://') && !r.url().startsWith('data:')) offHost.push(r.url()); });
  await page.addInitScript(t => { try { localStorage.setItem('js-theme', t); } catch (e) {} }, f.theme);
  await page.goto(f.url, { waitUntil: 'load' });
  await page.evaluate(t => document.documentElement.setAttribute('data-theme', t), f.theme);
  await page.waitForTimeout(500);
  const sw = await page.evaluate(() => document.documentElement.scrollWidth);
  await page.screenshot({ path: `${OUT}/admin-detail-390-${f.name}.png`, fullPage: true });
  console.log(`${f.name}\tviewport ${f.width}\tdocument ${sw}${sw > f.width ? '  ← SCROLLS SIDEWAYS' : ''}${offHost.length ? '  OFF-HOST: ' + offHost.join(' ') : ''}`);
  await ctx.close();
  n++;
}
console.log(`${n} frames`);
await browser.close();
