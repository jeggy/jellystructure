// DEB-4727 (FR-13) — run by scripts/check-tizen-abort.sh. Loads the polyfill into a context that has no
// AbortController, as Chromium 63 does, and checks what the bundle's fetch engine relies on.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'wgt', 'abort-polyfill.js'), 'utf8');

function chromium63(nativeFetch) {
  const window = { Event, DOMException, Object, Promise, setTimeout };
  if (nativeFetch) window.fetch = nativeFetch;
  vm.createContext(window);
  window.window = window;
  vm.runInContext(source, window);
  return window;
}

(async () => {
  // Defines both classes where missing.
  const w = chromium63();
  assert.strictEqual(typeof w.AbortController, 'function');
  assert.strictEqual(typeof w.AbortSignal, 'function');

  // abort() flips the signal once, fires listeners and onabort once each, and honours removal.
  const c = new w.AbortController();
  assert.strictEqual(c.signal.aborted, false);
  let calls = 0, removed = 0, handler = 0;
  c.signal.addEventListener('abort', () => calls++);
  const gone = () => removed++;
  c.signal.addEventListener('abort', gone);
  c.signal.removeEventListener('abort', gone);
  c.signal.onabort = () => handler++;
  c.abort();
  c.abort();
  assert.strictEqual(c.signal.aborted, true);
  assert.deepStrictEqual([calls, removed, handler], [1, 0, 1]);
  assert.strictEqual(c.signal.reason.name, 'AbortError');

  // A newer engine keeps its own classes.
  const native = { AbortController: class {}, fetch() {} };
  vm.createContext(native);
  native.window = native;
  const before = native.AbortController;
  vm.runInContext(source, native);
  assert.strictEqual(native.AbortController, before);

  // fetch: the signal never reaches the native fetch, an abort rejects the caller, a normal answer passes.
  let seenInit;
  let release;
  const fw = chromium63((url, init) => { seenInit = init; return new Promise(r => { release = r; }); });
  const ac = new fw.AbortController();
  const pending = fw.fetch('/x', { method: 'POST', signal: ac.signal });
  assert.strictEqual(seenInit.signal, undefined);
  assert.strictEqual(seenInit.method, 'POST');
  ac.abort();
  await assert.rejects(pending, e => e.name === 'AbortError');
  await assert.rejects(fw.fetch('/y', { signal: ac.signal }), e => e.name === 'AbortError');

  const ok = new fw.AbortController();
  const answered = fw.fetch('/z', { signal: ok.signal });
  release('response');
  assert.strictEqual(await answered, 'response');
  assert.strictEqual(ok.signal._listeners.length, 0);

  console.log('abort polyfill behaves on a context without AbortController');
})().catch(e => { console.error(e); process.exit(1); });
