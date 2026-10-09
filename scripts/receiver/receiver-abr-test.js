// Loads ReceiverAbr's FACTORY string from the Kotlin source and checks the 2026-10-09 fix with a fake Shaka manager.
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const body = src.split('private const val FACTORY = """')[1].split('"""')[0];
let interval = null;
global.setInterval = (f) => { interval = f; return 1; }; global.clearInterval = () => { interval = null; };
let buffered = { start: 0, end: 0 }, now = 0;
const el = { get currentTime() { return now; }, buffered: { length: 1, start: () => buffered.start, end: () => buffered.end } };
global.document = { querySelector: () => null };
class Simple { init(cb) { this.cb = cb; } setVariants(v) {} setMediaElement(e) {} chooseVariant() { return null; } enable() {} disable() {} stop() {} release() {} }
global.shaka = { abr: { SimpleAbrManager: Simple } };
const notes = []; const switched = [];
const decide = (bands, cur, aheadMs, last) => (aheadMs < 20000 ? 12192000 : -1);   // always wants to step down when < 20 s
const old = [{ bandwidth: 30192000 }, { bandwidth: 12192000 }];
Simple.prototype.chooseVariant = function () { return old[0]; };
const factory = eval(body)(decide, (n) => notes.push(n));
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
const factory2 = eval(body)(decide, (n) => notes.push(n)); const w2 = factory2();
const vs = [{ bandwidth: 30192000 }, { bandwidth: 12192000 }];
let picked = []; w2.init((v) => picked.push(v)); w2.setMediaElement(el); w2.setVariants(vs); w2.enable();
Simple.prototype.chooseVariant = function () { return vs[0]; };
buffered = { start: 0, end: 30 }; now = 0; w2.chooseVariant();           // 30 s ahead: no step (decide says -1 ≥ 20 s)
picked = []; interval && interval();                                        // records last = 30000
buffered = { start: 0, end: 15 };  interval && interval();                 // 15 s and falling → step down
if (!(picked.length === 1 && picked[0] === vs[1])) { console.log('FAIL: a falling buffer did not step down', picked); ok = false; }
console.log(ok ? 'PASS' : 'FAILED', '|', notes.filter(n => n.includes('step')).join(' / '));
process.exit(ok ? 0 : 1);
