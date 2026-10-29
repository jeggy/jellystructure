/* render-design.mjs — the in-container half of scripts/render-design.sh. Not run by hand: the shell
   script owns the docker command line, the browser cache and the flag documentation.

   Modes:
     --check   exit 0 if the cached Chromium launches, 1 if it does not (the shell script then installs)
     (default) render one HTML file to PNGs

   Playwright is resolved BY PATH: ESM ignores NODE_PATH, so a bare `import 'playwright'` cannot find
   tests/node_modules from here. No symlink at the repo root — several sessions share the checkout.

   A render is all or nothing. Anything the page requests off-host, or any local file that fails to
   load, fails the run and removes the PNGs this run wrote, so a half-loaded picture never survives.
   The one exception is the Google Fonts stylesheet that design/ravilo/*.css @imports (DEB-2922): it is
   answered from design/app/fonts/ for the three families we self-host, and a family we do not host
   still fails the run, as does fonts.gstatic.com and every other off-host request. */

import { mkdirSync, unlinkSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const PW = process.env.PLAYWRIGHT_PKG || resolve('tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

const FONT_DIR = resolve('design/app/fonts');
const SELF_HOSTED = { 'Space Grotesk': 'space-grotesk.woff2', 'Sora': 'sora.woff2', 'JetBrains Mono': 'jetbrains-mono.woff2' };

/* The stylesheet a fonts.googleapis.com/css2 URL would have returned, built from the self-hosted
   variable-weight files in the shape design/app/wf.css uses. Returns { css } or { missing: [...] }. */
const googleFontsSheet = (url) => {
  const css = [];
  const missing = [];
  for (const spec of url.searchParams.getAll('family')) {
    const [family, axes = ''] = spec.split(':');
    const file = SELF_HOSTED[family];
    if (!file) { missing.push(family); continue; }
    const m = /^wght@(.+)$/.exec(axes);
    if (axes && !m) { missing.push(`${family} (axes ${axes})`); continue; }
    for (const w of m ? m[1].split(';') : ['400']) {
      css.push(`@font-face { font-family: '${family}'; font-style: normal; font-weight: ${w.replace('..', ' ')}; font-display: swap; src: url('${pathToFileURL(resolve(FONT_DIR, file)).href}') format('woff2'); }`);
    }
  }
  return missing.length ? { missing } : { css: css.join('\n') };
};

const fail = (msg) => { console.error(`render-design: ${msg}`); process.exit(1); };

const opt = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--check') opt.check = true;
  else if (a === '--full-page') opt.fullPage = true;
  else if (a.startsWith('--')) opt[a.slice(2)] = argv[++i];
  else fail(`unexpected argument ${a}`);
}

if (opt.check) {
  try { await (await chromium.launch()).close(); process.exit(0); } catch { process.exit(1); }
}

const [, vw, vh] = /^(\d+)x(\d+)$/.exec(opt.viewport || '') || fail('--viewport must be WIDTHxHEIGHT');
const scale = Number(opt.scale || 1);
if (!(scale > 0)) fail('--scale must be a positive number');
const modes = [opt.all, opt.element, opt.fullPage].filter(Boolean).length;
if (modes !== 1) fail('give exactly one of --all SELECTOR, --element SELECTOR or --full-page');
if ((opt.element || opt.fullPage) && !opt.name) fail('--element and --full-page need --name');
if (!opt.html || !opt.out) fail('internal: --html and --out are required');

const target = pathToFileURL(resolve(opt.html)).href + (opt.query || '');
const prefix = opt.prefix || '';
const out = resolve(opt.out);
mkdirSync(out, { recursive: true });

const written = [];
const problems = [];

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({
    viewport: { width: Number(vw), height: Number(vh) },
    deviceScaleFactor: scale,
  });
  const page = await ctx.newPage();

  /* Only file:, data:, blob: and about: may load. Everything else is aborted and recorded; a local
     file that is missing shows up as a failed request and is recorded too. The one answered request
     is the Google Fonts stylesheet for a family design/app/fonts/ holds (see the header). */
  await page.route((url) => !/^(file|data|blob|about):/.test(url.href), (route) => {
    const url = new URL(route.request().url());
    if (url.origin === 'https://fonts.googleapis.com' && url.pathname === '/css2') {
      const sheet = googleFontsSheet(url);
      if (sheet.css) return route.fulfill({ status: 200, contentType: 'text/css', body: sheet.css });
      problems.push(`off-host font family not self-hosted in design/app/fonts/: ${sheet.missing.join(', ')} (${url.href})`);
      return route.abort();
    }
    problems.push(`off-host request: ${url.href}`);
    return route.abort();
  });
  page.on('requestfailed', (req) => {
    if (req.url().startsWith('file:')) problems.push(`local file failed to load: ${req.url()}`);
  });

  await page.goto(target);

  /* The families are self-hosted woff2. Without waiting for them the first frame can render in a
     fallback face and every later one in the real face. */
  const settle = async () => {
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(200);
    if (problems.length) throw new Error([...new Set(problems)].join('\n  '));
  };
  const save = (name) => {
    const path = resolve(out, `${prefix}${name}.png`);
    written.push(path);
    return path;
  };
  await settle();

  if (opt.all) {
    const ids = await page.evaluate((sel) => [...document.querySelectorAll(sel)].filter((el) => el.getClientRects().length).map((el) => el.id), opt.all);
    if (!ids.length) throw new Error(`no element matches ${opt.all}`);
    if (ids.some((id) => !id)) throw new Error(`an element matching ${opt.all} has no id to name its PNG`);
    if (new Set(ids).size !== ids.length) throw new Error(`two elements matching ${opt.all} share an id`);
    for (const id of ids) {
      const el = page.locator('#' + id);
      await el.waitFor({ state: 'visible' });
      await el.screenshot({ path: save(id) });
      console.log('  ✓', `${prefix}${id}.png`);
    }
  } else if (opt.element) {
    const el = page.locator(opt.element);
    if ((await el.count()) !== 1) throw new Error(`${opt.element} matches ${await el.count()} elements, need exactly 1`);
    await el.waitFor({ state: 'visible' });
    await el.screenshot({ path: save(opt.name) });
    console.log('  ✓', `${prefix}${opt.name}.png`);
  } else {
    await page.screenshot({ path: save(opt.name), fullPage: true });
    console.log('  ✓', `${prefix}${opt.name}.png`);
  }
  await settle();
} catch (e) {
  for (const p of written) { try { unlinkSync(p); } catch { /* not written yet */ } }
  console.error(`render-design: failed, ${written.length} PNG(s) removed\n  ${e.message}`);
  process.exitCode = 1;
} finally {
  await browser.close();
}
if (!process.exitCode) console.log(`done · ${written.length} png → ${opt.shown || opt.out}`);
