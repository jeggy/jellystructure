// Loads ReceiverAbr's FACTORY string from the Kotlin source and checks the 2026-10-09 fix with a fake Shaka manager.
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const body = src.split('private const val FACTORY = """')[1].split('"""')[0];
let interval = null;
global.setInterval = (f) => { interval = f; return 1; }; global.clearInterval = () => { interval = null; };
let buffered = { start: 0, end: 0 }, now = 0;
const el = { get currentTime() { return now; }, buffered: { length: 1, start: () => buffered.start, end: () => buffered.end } };
global.document = { querySelector: () => null };
let estimate = 5_000_000;
class Simple { getBandwidthEstimate() { return estimate; } init(cb) { this.cb = cb; } setVariants(v) {} setMediaElement(e) {} chooseVariant() { return null; } enable() {} disable() {} stop() {} release() {} }
global.shaka = { abr: { SimpleAbrManager: Simple } };
const notes = []; const switched = [];
const decide = (bands, cur, aheadMs, last) => (aheadMs < 20000 ? 12192000 : -1);   // always wants to step down when < 20 s
// DEB-5927 (FR-7): the estimate clause and the stall cap, with the pure rules of LadderRules stubbed the way ReceiverAbr hands them over.
const none = () => -1;
const estimateDown = (bands, cur, est, aheadMs) => { if (aheadMs >= 20000 || cur <= est * 0.8) return -1; const s = bands.slice().sort((a, b) => a - b); const p = s.filter((x) => x <= est * 0.8).pop() ?? s[0]; return p < cur ? p : -1; };
const stallCap = (bands, cur) => { const b = bands.filter((x) => x < cur).sort((a, b) => a - b); return b.length ? b[b.length - 1] : -1; };
const old = [{ bandwidth: 30192000 }, { bandwidth: 12192000 }];
Simple.prototype.chooseVariant = function () { return old[0]; };
const factory = eval(body)(decide, none, none, 120000, 10000, {}, (n) => notes.push(n));
const w = factory();
w.init((v) => switched.push(v)); w.setMediaElement(el); w.setVariants(old); w.enable();
buffered = { start: 0, end: 30 }; now = 0;
w.chooseVariant();                          // the first manifest plays its top variant: cur = old[0]
interval && interval();                     // 30 s ahead: last = 30000, no step
// --- restream: Shaka stops, loads a new manifest; buffer is empty (0 ms) before anything arrives
w.stop();
const fresh = [{ bandwidth: 30192000 }, { bandwidth: 12192000 }];
w.init((v) => switched.push(v)); w.setMediaElement(el); w.setVariants(fresh); w.enable();
buffered = { start: 0, end: 0 }; now = 0;
interval && interval();
let ok = true;
if (switched.some((v) => old.includes(v))) { console.log('FAIL: switched to a variant of the previous manifest'); ok = false; }
if (switched.length) { console.log('FAIL: switched at 0 ms buffered right after a load', switched.length); ok = false; }
// --- a genuinely falling buffer on the current manifest still steps down
// simulate the real chooseVariant path being wrapped: set cur through the wrapper again
const factory2 = eval(body)(decide, none, none, 120000, 10000, {}, (n) => notes.push(n)); const w2 = factory2();
const vs = [{ bandwidth: 30192000 }, { bandwidth: 12192000 }];
let picked = []; w2.init((v) => picked.push(v)); w2.setMediaElement(el); w2.setVariants(vs); w2.enable();
Simple.prototype.chooseVariant = function () { return vs[0]; };
buffered = { start: 0, end: 30 }; now = 0; w2.chooseVariant();           // 30 s ahead: no step (decide says -1 ≥ 20 s)
picked = []; interval && interval();                                        // records last = 30000
buffered = { start: 0, end: 15 };  interval && interval();                 // 15 s and falling → step down
if (!(picked.length === 1 && picked[0] === vs[1])) { console.log('FAIL: a falling buffer did not step down', picked); ok = false; }
// --- DEB-5927 (FR-7): a buffer pinned at 0.4 s is not falling, but the estimate x 0.8 is under the rung playing: step down.
{
  const hooks = {}; const n3 = [];
  const f3 = eval(body)((b, c, a, l) => -1, estimateDown, stallCap, 120000, 10000, hooks, (n) => n3.push(n)); const w3 = f3();
  const vs3 = [{ bandwidth: 3189074 }, { bandwidth: 2440000 }, { bandwidth: 1160000 }, { bandwidth: 700000 }];
  Simple.prototype.chooseVariant = function () { return vs3[0]; };
  const got = []; w3.init((v) => got.push(v)); w3.setMediaElement(el); w3.setVariants(vs3); w3.enable();
  buffered = { start: 0, end: 0.4 }; now = 0; estimate = 800000; w3.chooseVariant();
  interval && interval(); interval && interval();
  if (!(got.length === 1 && got[0] === vs3[3])) { console.log('FAIL: a pinned buffer with a low estimate did not step down to the rung under the line', got.map((v) => v.bandwidth)); ok = false; }
  // a fast path with the same pinned buffer stays where it is
  const w4 = eval(body)(none, estimateDown, stallCap, 120000, 10000, {}, () => {})(); const got4 = [];
  w4.init((v) => got4.push(v)); w4.setMediaElement(el); w4.setVariants(vs3); w4.enable(); estimate = 9_000_000; w4.chooseVariant();
  interval && interval(); interval && interval();
  if (got4.length) { console.log('FAIL: stepped down on a fast estimate', got4.length); ok = false; }
  // --- a stall caps one rung down at once, and a second stall inside the gap does nothing
  const hooks5 = {}; const w5 = eval(body)(none, none, stallCap, 120000, 10000, hooks5, () => {})(); const got5 = [];
  w5.init((v) => got5.push(v)); w5.setMediaElement(el); w5.setVariants(vs3); w5.enable(); w5.chooseVariant();
  hooks5.stall(); hooks5.stall();
  if (!(got5.length === 1 && got5[0] === vs3[1])) { console.log('FAIL: a stall did not cap exactly one rung down', got5.map((v) => v.bandwidth)); ok = false; }
  // --- the cap holds Shaka's own next pick below the rung it left
  const back = w5.chooseVariant();
  if (back !== vs3[0] && back.bandwidth > vs3[1].bandwidth) { console.log('FAIL: Shaka climbed above the stall cap', back.bandwidth); ok = false; }
  // --- the hook is let go with the wrapper
  w5.stop(); if (hooks5.stall) { console.log('FAIL: the stall hook outlived its wrapper'); ok = false; }
}
console.log(ok ? 'PASS' : 'FAILED', '|', notes.filter(n => n.includes('step')).join(' / '));
process.exit(ok ? 0 : 1);
