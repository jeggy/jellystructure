/* render-design.mjs — the in-container half of scripts/render-design.sh. Not run by hand: the shell
   script owns the docker command line, the browser cache and the flag documentation.

   Modes:
     --check   exit 0 if the cached Chromium launches, 1 if it does not (the shell script then installs)
     (default) render one HTML file to PNGs

   Playwright is resolved BY PATH: ESM ignores NODE_PATH, so a bare `import 'playwright'` cannot find
   tests/node_modules from here. No symlink at the repo root — several sessions share the checkout.

   A render is all or nothing. Anything the page requests off-host, or any local file that fails to
   load, fails the run and removes the PNGs this run wrote, so a half-loaded picture never survives. */

import { mkdirSync, unlinkSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const PW = process.env.PLAYWRIGHT_PKG || resolve('tests/node_modules/playwright');
const { chromium } = createRequire(import.meta.url)(PW);

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
     file that is missing shows up as a failed request and is recorded too. */
  await page.route((url) => !/^(file|data|blob|about):/.test(url.href), (route) => {
    problems.push(`off-host request: ${route.request().url()}`);
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
