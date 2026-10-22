/* render-mac-open-anyway.mjs — renders the Mac "Open Anyway" pictures from the drawn round.

   DEB-1469 (the-mac-download-shows-open-anyway-in-pictures.md), built by DEB-2122.

   The round's HTML/CSS/JS is the drawing (design/incoming/2026-10-22-mac-open-anyway/). This script is
   the permanent form of the round's own prototype render.mjs, cut down to the two deliverables:

     --dmg-out <dir>       the .dmg window background, background.png (1x) and background@2x.png (2x)
     --pictures-out <dir>  the five step pictures of the download page, one PNG each, drawn at 2x

   The .dmg background is rendered 28pt TALLER than the 720 x 520 content area the spec names. Finder's
   WindowBounds is the window's frame, title bar included, and a Finder background is anchored to the
   content area's top-left, so the extra strip is what keeps the bottom of steps 4 and 5 on screen if the
   title bar turns out to be inside the bounds. The strip is the gradient's own last colour; the drawn
   content, the two icon centres and the label bands are untouched (FR-4's numbers are unchanged).

   Run from the repo root in the same container the round's REPORT.md records (the pinned image supplies
   the OS libraries, tests/package-lock.json supplies the matching browser):

     docker run --rm -v "$PWD":/work -w /work -v ~/.cache/ms-playwright:/ms-playwright-host \
       -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host --user "$(id -u):$(id -g)" \
       mcr.microsoft.com/playwright:v1.45.0-noble \
       node scripts/render-mac-open-anyway.mjs --dmg-out ravilo-desktop/dmg --pictures-out /tmp/pictures
*/

import { existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function arg(name) {
  const i = process.argv.indexOf(name);
  return i > 0 ? resolve(process.argv[i + 1]) : null;
}

/* The round is promoted into design/ravilo/ once Rannvá gets to it; use whichever exists. */
const roundCandidates = [
  resolve(root, 'design/incoming/2026-10-22-mac-open-anyway'),
  resolve(root, 'design/ravilo/mac-open-anyway'),
];
const round = arg('--round') || roundCandidates.find((d) => existsSync(resolve(d, 'page.html')));
if (!round) throw new Error('the mac-open-anyway round is in neither ' + roundCandidates.join(' nor '));

const dmgOut = arg('--dmg-out');
const picturesOut = arg('--pictures-out');
if (!dmgOut && !picturesOut) throw new Error('nothing to do: pass --dmg-out and/or --pictures-out');

/* Playwright lives in tests/; resolved by path, never by name (ESM ignores NODE_PATH). */
const PW = process.env.PLAYWRIGHT_PKG || resolve(root, 'tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

const url = (f, q = '') => 'file://' + resolve(round, f) + q;
async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
}

const PICTURES = [
  'step-1-refused-disk-image',
  'step-2-privacy-security-open-anyway',
  'step-3-drag-ravilo-to-applications',
  'step-4-refused-installed-app',
  'step-5-open-anyway-and-password',
];
const TITLEBAR_PAD = 28;

const browser = await chromium.launch();
try {
  if (dmgOut) {
    mkdirSync(dmgOut, { recursive: true });
    for (const scale of [1, 2]) {
      const ctx = await browser.newContext({ viewport: { width: 900, height: 700 }, deviceScaleFactor: scale });
      const page = await ctx.newPage();
      await page.goto(url('dmg.html'));
      await page.addStyleTag({ content: `#dmg{ height:${520 + TITLEBAR_PAD}px !important; }` });
      await settle(page);
      const name = scale === 1 ? 'background.png' : 'background@2x.png';
      await page.locator('#dmg').screenshot({ path: resolve(dmgOut, name) });
      console.log('  ✓ .dmg background', name, `${720 * scale} x ${(520 + TITLEBAR_PAD) * scale}`);
      await ctx.close();
    }
  }

  if (picturesOut) {
    mkdirSync(picturesOut, { recursive: true });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await page.goto(url('page.html', '?mode=desktop'));
    /* Transparent all the way up, so the picture's rounded corners are transparent rather than the
       canvas colour of whichever frame it was cut from. */
    await page.addStyleTag({
      content: 'html,body,.host,.shot,.moa-page,.moa-section,.moa-steps>li{ background:transparent !important; border-color:transparent !important; }',
    });
    await settle(page);
    const pics = page.locator('#a-desktop .moa-steps .moa-pic');
    if ((await pics.count()) !== PICTURES.length) throw new Error('expected five step pictures');
    for (let i = 0; i < PICTURES.length; i++) {
      const name = `mac-open-anyway-${PICTURES[i]}.png`;
      await pics.nth(i).screenshot({ path: resolve(picturesOut, name), omitBackground: true });
      console.log('  ✓ step picture', name);
    }
    await ctx.close();
  }
} finally {
  await browser.close();
}
console.log('done');
