/* Segment editor — three directions. Mock data + renderers.
   Shared model: 5 segment kinds, per-segment lock, three sources
   (our fingerprint / our heuristic / Jellyfin's own segments) and a
   Jellyfin publish state, matching the Phase 150+159 detect_segments work. */
(function () {
  var K = {
    recap:   { n: 'Recap',        cls: 'k-recap',   c: 'var(--info)' },
    intro:   { n: 'Intro',        cls: 'k-intro',   c: 'var(--hi)' },
    preview: { n: 'Next time',    cls: 'k-preview', c: '#4fd1c5' },
    credits: { n: 'Credits',      cls: 'k-credits', c: 'var(--warn)' },
    stinger: { n: 'After-credits',cls: 'k-stinger', c: 'var(--acc-a)' }
  };
  var SRC = {
    fp:   { cls: 'fp', t: 'matched across episodes' },
    he:   { cls: 'he', t: 'guessed from black frames' },
    jf:   { cls: 'jf', t: 'from Jellyfin' },
    tmdb: { cls: 'jf', t: 'from TMDB' },
    me:   { cls: 'me', t: 'set by you' }
  };
  var TITLES = ['Homer\'s Barbershop Quartet', 'Cape Feare', 'Homer Goes to College', 'Rosebud', 'Treehouse of Horror IV', 'Marge on the Lam', 'Bart\'s Inner Child', 'Boy-Scoutz \'n the Hood', 'The Last Temptation of Homer', '$pringfield', 'Homer the Vigilante', 'Bart Gets Famous', 'Homer and Apu', 'Lisa vs. Malibu Stacy', 'Deep Space Homer', 'Homer Loves Flanders', 'Bart Gets an Elephant', 'Burns\' Heir', 'Sweet Seymour Skinner\'s Badasssss Song', 'The Boy Who Knew Too Much', 'Lady Bouvier\'s Lover', 'Secrets of a Successful Marriage'];

  function pad(n) { return String(n).padStart(2, '0'); }
  function fmt(s) { return Math.floor(s / 60) + ':' + pad(Math.floor(s % 60)); }
  function fmtl(s) { return pad(Math.floor(s / 60)) + ':' + pad(Math.floor(s % 60)); }
  function fmtf(s) { var f = Math.round((s - Math.floor(s)) * 25); return pad(Math.floor(s / 60)) + ':' + pad(Math.floor(s % 60)) + '<s style="color:var(--ink-dim)">.' + pad(f) + '</s>'; }
  function rnd(seed) { var x = Math.sin(seed * 12.9898) * 43758.5453; return x - Math.floor(x); }

  /* one episode's markers — deterministic, with real-world messiness */
  function ep(i) {
    var dur = 1288 + Math.round(rnd(i + 3) * 26);
    var kind = i === 8 ? 'odd' : (i % 7 === 3 ? 'low' : (i % 11 === 5 ? 'none' : 'ok'));
    var iA = kind === 'odd' ? 191 : 39 + Math.round(rnd(i) * 5);
    var segs = [];
    if (kind !== 'none') {
      if (i % 3 === 1) segs.push({ k: 'recap', a: 0, b: 20 + Math.round(rnd(i + 9) * 6), src: 'jf' });
      segs.push({ k: 'intro', a: iA, b: iA + 70 + Math.round(rnd(i + 1) * 4), src: kind === 'low' ? 'he' : 'fp', conf: kind === 'low' ? 0.54 + rnd(i) * 0.05 : 0.9 + rnd(i + 2) * 0.08, lock: i % 5 === 0 });
      segs.push({ k: 'credits', a: dur - (78 + Math.round(rnd(i + 4) * 14)), b: dur, src: kind === 'low' ? 'he' : 'fp', conf: kind === 'low' ? 0.51 + rnd(i + 6) * 0.06 : 0.86 + rnd(i + 7) * 0.1 });
      if (i % 6 === 2) segs.push({ k: 'preview', a: dur - 26, b: dur - 8, src: 'he', conf: 0.62 });
      if (i % 9 === 4) segs.push({ k: 'stinger', a: dur - 22, b: dur - 3, src: 'tmdb' });
    }
    return { i: i, id: 'S05E' + pad(i + 1), t: TITLES[i], dur: dur, kind: kind, segs: segs, done: i < 3 };
  }
  var EPS = TITLES.map(function (_, i) { return ep(i); });
  var cur = 3;

  /* ---------- shared pieces ---------- */
  function ruler(dur) {
    var h = '', step = 120;
    for (var t = 0; t <= dur; t += step) h += '<i style="left:' + (t / dur * 100) + '%"></i><u style="left:' + (t / dur * 100) + '%">' + fmt(t) + '</u>';
    return '<div class="ruler">' + h + '</div>';
  }
  function segEls(e, sel) {
    return e.segs.map(function (s, n) {
      var w = (s.b - s.a) / e.dur * 100, showName = w > 6;
      return '<div class="seg ' + K[s.k].cls + (s.lock ? ' lk' : '') + '" data-s="' + n + '" style="left:' + (s.a / e.dur * 100) + '%;width:' + w + '%">' +
        (showName ? K[s.k].n : '') + (s.lock ? '' : '<i class="h l"></i><i class="h r"></i>') + '</div>';
    }).join('');
  }
  function wave(e, n) {
    var h = '';
    for (var x = 0; x < n; x++) {
      var t = x / n * e.dur, amp = 0.32 + rnd(x * 1.7 + e.i) * 0.5;
      e.segs.forEach(function (s) {
        if (s.k === 'intro' && t > s.a && t < s.b) amp = 0.72 + rnd(x * 3.1) * 0.28;
        if (s.k === 'credits' && t > s.a) amp = 0.5 + rnd(x * 2.3) * 0.2;
        if (Math.abs(t - s.a) < 2 || Math.abs(t - s.b) < 2) amp = 0.04;
      });
      h += '<i style="height:' + (amp * 100).toFixed(0) + '%"></i>';
    }
    var sil = e.segs.map(function (s) {
      return '<span class="sil" style="left:' + ((s.a - 2) / e.dur * 100) + '%;width:' + (4 / e.dur * 100) + '%"></span>';
    }).join('');
    return '<div class="wave">' + h + sil + '</div>';
  }
  function evidence(e) {
    var h = '<span class="evlbl">why</span>';
    e.segs.forEach(function (s) {
      h += '<span class="b blk" style="left:' + ((s.a - 1.5) / e.dur * 100) + '%;width:' + (3 / e.dur * 100) + '%"></span>';
      h += '<span class="b blk" style="left:' + ((s.b - 1.5) / e.dur * 100) + '%;width:' + (3 / e.dur * 100) + '%"></span>';
      if (s.k === 'intro' && s.src === 'fp') h += '<span class="b fp" style="left:' + (s.a / e.dur * 100) + '%;width:' + ((s.b - s.a) / e.dur * 100) + '%">same audio in 21 other episodes</span>';
      if (s.k === 'credits' && s.src === 'fp') h += '<span class="b fp" style="left:' + (s.a / e.dur * 100) + '%;width:' + ((s.b - s.a) / e.dur * 100) + '%">matches the series outro</span>';
    });
    [0, 0.31, 0.58, 0.79].forEach(function (p) { h += '<span class="ch" style="left:' + (p * 100) + '%"></span>'; });
    return '<div class="ev">' + h + '</div>';
  }
  function srcChip(s) {
    var d = SRC[s.src];
    return '<span class="src ' + d.cls + '">' + d.t + (s.conf ? ' · ' + s.conf.toFixed(2) : '') + '</span>';
  }
  function mini(e) {
    return '<div class="mini">' + e.segs.map(function (s) {
      return '<i style="left:' + (s.a / e.dur * 100) + '%;width:' + Math.max(1.2, (s.b - s.a) / e.dur * 100) + '%;background:' + K[s.k].c + '"></i>';
    }).join('') + '</div>';
  }
  function statusBits(e) {
    if (e.kind === 'none') return { dot: 'd-bad', txt: 'nothing found — needs a look' };
    if (e.kind === 'odd') return { dot: 'd-bad', txt: 'intro starts 3 min in — odd one out' };
    if (e.kind === 'low') return { dot: 'd-warn', txt: 'guessed, worth an eyeball' };
    if (e.done) return { dot: 'd-ok', txt: 'checked' + (e.segs.some(function (s) { return s.lock; }) ? ' · locked' : '') };
    return { dot: 'd-idle', txt: 'matched, not checked' };
  }
  function legend(kinds) {
    return '<div class="legend">' + kinds.map(function (k) {
      return '<span><i style="background:' + K[k].c + '"></i>' + K[k].n + '</span>';
    }).join('') + '</div>';
  }
  function queue(active) {
    return EPS.map(function (e) {
      var s = statusBits(e);
      return '<div class="qr' + (e.i === active ? ' on' : '') + '" data-q="' + e.i + '"><span class="id">' + e.id + '</span>' +
        '<div><div class="t">' + e.t + '</div><div class="st">' + s.txt + '</div>' + mini(e) + '</div>' +
        '<span class="dot ' + s.dot + '"></span></div>';
    }).join('');
  }
  function bar(right, sub) {
    var e = EPS[cur];
    return '<div class="sxbar"><a class="sxback" href="series-simpsons.html">‹ The Simpsons</a>' +
      '<span class="num">' + e.id + '</span><h1>' + e.t + '</h1><span class="sxsub">' + (sub || fmtl(e.dur) + ' · season 5') + '</span>' +
      '<span class="sxsp"></span>' + right + '</div>';
  }
  var PUB = '<span class="src jf">published to Jellyfin · 3 min ago</span>';

  /* ---------- DIRECTION A — watch and trim ---------- */
  function renderA() {
    var e = EPS[cur], root = document.getElementById('dirA');
    root.innerHTML = bar(PUB + '<button class="btn sm">↻ Re-detect</button><button class="btn sm pri">Save &amp; next episode →</button>') +
      '<div class="sxmain" style="grid-template-columns:1fr 322px">' +
        '<div class="sxstage">' +
          '<div class="vid"><div class="ph"><em id="A-tc">' + fmtl(e.segs[0] ? e.segs[0].b : 41) + '</em>frame at the intro’s last moment</div>' +
            '<div class="tag"><span class="vpill" style="color:var(--acc-ink);border-color:rgba(123,110,240,.4)">▍Intro ends here</span></div>' +
            '<div class="tag2"><span class="vpill ok">direct play · no transcode</span></div>' +
            '<div class="foot"><span class="vbtn pri">▶</span><span class="vbtn">◂◂</span><span class="vbtn">▸▸</span>' +
            '<span class="vpill">↺ loop this cut · 3s either side</span><span class="sxsp"></span><span class="vpill mono">' + fmtl(e.segs[0] ? e.segs[0].b : 41) + ' / ' + fmtl(e.dur) + '</span></div></div>' +
          '<div class="tl"><div class="tlh"><span class="lbl">Timeline</span>' + legend(['recap', 'intro', 'preview', 'credits', 'stinger']) + '</div>' +
            ruler(e.dur) +
            '<div class="track" id="A-track"><div class="grid"></div>' + segEls(e) + '<div class="play" style="left:' + ((e.segs[0] ? e.segs[0].b : 41) / e.dur * 100) + '%"></div></div>' +
            wave(e, 150) + evidence(e) +
          '</div>' +
          '<div class="mks" id="A-mks"></div>' +
        '</div>' +
        '<div class="sxrail"><div class="rh"><b>Season 5</b><span class="sxsp"></span><span class="lbl">3 of 22 checked</span></div>' +
          '<div class="q">' + queue(cur) + '</div>' +
          '<div class="qfoot"><div class="keys">' +
            '<div><span class="kbd">I</span><span class="kbd">O</span>set in / out at the playhead</div>' +
            '<div><span class="kbd">,</span><span class="kbd">.</span>nudge a frame · <span class="kbd">⇧</span>+ for a second</div>' +
            '<div><span class="kbd">L</span>lock the marker under the cursor</div>' +
            '<div><span class="kbd">↵</span>save and open the next episode</div>' +
          '</div><div class="sxhint">Locked markers survive every future <b>detect_segments</b> run — that is the whole point of the lock.</div></div>' +
        '</div>' +
      '</div>';
    drawMarkers(e);
    root.querySelectorAll('[data-q]').forEach(function (r) {
      r.addEventListener('click', function () { cur = +r.dataset.q; renderA(); });
    });
    root.querySelectorAll('#A-track .seg').forEach(function (el) {
      el.addEventListener('mousedown', function () { select(+el.dataset.s); });
    });
  }
  var selIdx = 0;
  function select(n) { selIdx = n; drawMarkers(EPS[cur]); }
  function drawMarkers(e) {
    var box = document.getElementById('A-mks'); if (!box) return;
    var rows = e.segs.map(function (s, n) {
      return '<div class="mk' + (n === selIdx ? ' sel' : '') + (s.lock ? ' lkd' : '') + '" data-m="' + n + '">' +
        '<span class="sw" style="background:' + K[s.k].c + '"></span>' +
        '<span class="nm">' + K[s.k].n + '</span>' +
        '<span class="tc"><span class="stp"><button data-d="-1" data-e="a">−</button><button data-d="1" data-e="a">+</button></span>' + fmtl(s.a) +
          '<s>→</s>' + fmtl(s.b) + '<span class="stp"><button data-d="-1" data-e="b">−</button><button data-d="1" data-e="b">+</button></span>' +
          '<s style="margin-left:6px">' + fmt(s.b - s.a) + ' long</s>' + srcChip(s) + '</span>' +
        '<span class="acts"><button class="btn sm ghost">▶ play the cut</button>' +
          '<button class="lockb' + (s.lock ? ' on' : '') + '" data-l="' + n + '">' + (s.lock ? '🔒 locked' : '🔓 lock') + '</button></span></div>';
    }).join('');
    var missing = ['recap', 'intro', 'preview', 'credits', 'stinger'].filter(function (k) {
      return !e.segs.some(function (s) { return s.k === k; });
    });
    box.innerHTML = rows + (missing.length ? '<div class="mk" style="grid-template-columns:15px 1fr auto;background:transparent;border-style:dashed">' +
      '<span class="sw" style="background:var(--fill-3)"></span><span class="sxhint">No ' + missing.map(function (k) { return K[k].n.toLowerCase(); }).join(', ') + ' in this episode.</span>' +
      '<span class="acts">' + missing.map(function (k) { return '<button class="btn sm ghost">＋ ' + K[k].n + '</button>'; }).join('') + '</span></div>' : '');
    box.querySelectorAll('[data-m]').forEach(function (r) {
      r.addEventListener('click', function (ev) { if (!ev.target.closest('button')) select(+r.dataset.m); });
    });
    box.querySelectorAll('[data-l]').forEach(function (b) {
      b.addEventListener('click', function () { var s = e.segs[+b.dataset.l]; s.lock = !s.lock; s.src = s.lock ? 'me' : s.src; renderA(); });
    });
    box.querySelectorAll('.stp button').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = e.segs[+b.closest('[data-m]').dataset.m], d = +b.dataset.d;
        s[b.dataset.e] = Math.max(0, Math.min(e.dur, s[b.dataset.e] + d)); s.src = 'me'; renderA();
      });
    });
  }

  /* ---------- DIRECTION B — confirm the cuts ---------- */
  var bDone = {};
  function renderB() {
    var e = EPS[cur], root = document.getElementById('dirB');
    var cards = e.segs.length ? e.segs.map(function (s, n) {
      var edge = s.k === 'credits' || s.k === 'stinger' ? 'a' : (s.k === 'recap' ? 'b' : 'b');
      var t = s[edge], key = e.id + n, done = bDone[key];
      var w = '';
      for (var x = 0; x < 60; x++) w += '<i style="height:' + ((x > 27 && x < 33 ? 6 : 20 + rnd(x + n * 5) * 74)) + '%"></i>';
      return '<div class="bcard' + (done ? ' done' : (n === selIdx ? ' on' : '')) + '" data-b="' + n + '">' +
        '<div class="bch"><span class="sw" style="width:11px;height:11px;border-radius:3px;background:' + K[s.k].c + '"></span>' +
          '<span class="nm">' + (s.k === 'credits' ? 'Where the credits start' : s.k === 'intro' ? 'Where the intro ends' : s.k === 'recap' ? 'Where the recap ends' : s.k === 'stinger' ? 'Where the after-credits scene starts' : 'Where the preview starts') + '</span>' +
          srcChip(s) + '<span class="sxsp"></span>' +
          (done ? '<span class="src me">✓ confirmed</span>' : '<span class="mono" style="font-size:13px">' + fmtl(t) + '</span>') + '</div>' +
        '<div class="bpair"><div class="bfr"><span class="cap">last frame before</span><span class="tcs">' + fmtf(t - 0.04) + '</span></div>' +
          '<div class="bfr out"><span class="cap">first frame after</span><span class="tcs">' + fmtf(t) + '</span></div></div>' +
        '<div class="bslice">' + w + '<span class="cut"></span></div>' +
        '<div class="bfoot"><span class="stp"><button data-n="-1">− frame</button><button data-n="-25">− 1s</button><button data-n="25">+ 1s</button><button data-n="1">+ frame</button></span>' +
          '<button class="btn sm ghost">↺ loop it</button><span class="sxsp"></span>' +
          '<button class="lockb' + (s.lock ? ' on' : '') + '" data-l="' + n + '">' + (s.lock ? '🔒 locked' : '🔓 lock') + '</button>' +
          '<button class="btn sm' + (done ? ' ghost' : ' pri') + '" data-ok="' + n + '">' + (done ? 'Confirmed' : 'Looks right') + '</button>' +
          '<button class="btn sm ghost" data-no="' + n + '">Not here…</button></div></div>';
    }).join('') : '<div class="bcard"><div class="bch"><span class="nm">Nothing was detected in this episode</span></div>' +
      '<div class="sxhint">Ravilo falls back to a Next-Up card 34 s before the file ends. You can mark the boundaries yourself, or borrow the ones the rest of the season agrees on.</div>' +
      '<div class="bfoot"><button class="btn sm pri">Use the season’s intro (0:41 → 1:52)</button><button class="btn sm">Mark them by hand</button><button class="btn sm ghost">↻ Detect again</button></div></div>';
    var n = e.segs.length, ok = e.segs.filter(function (_, i) { return bDone[e.id + i]; }).length;
    root.innerHTML = bar('<span class="src jf">Jellyfin has 2 of these already</span><button class="btn sm">↻ Re-detect</button><button class="btn sm pri">Confirm all &amp; next →</button>') +
      '<div class="sxmain" style="grid-template-columns:1fr 322px">' +
        '<div class="sxstage">' +
          '<div class="tl" style="padding:9px 12px 10px"><div class="tlh" style="margin-bottom:7px"><span class="lbl">The whole episode</span>' + legend(['recap', 'intro', 'credits', 'stinger']) + '</div>' +
            '<div class="track" style="height:26px"><div class="grid"></div>' + segEls(e) + '</div></div>' +
          '<div class="row" style="display:flex;align-items:center;gap:11px"><span class="lbl">' + n + ' boundaries to check</span>' +
            '<div class="prog"><i style="width:' + (n ? ok / n * 100 : 0) + '%"></i></div><span class="sxhint">' + ok + ' of ' + n + ' confirmed</span></div>' +
          '<div class="bwrap">' + cards + '</div>' +
        '</div>' +
        '<div class="sxrail"><div class="rh"><b>Left to check</b><span class="sxsp"></span><span class="lbl">19 episodes</span></div>' +
          '<div class="q">' + queue(cur) + '</div>' +
          '<div class="qfoot"><div class="keys"><div><span class="kbd">↵</span>looks right, next boundary</div>' +
          '<div><span class="kbd">J</span><span class="kbd">K</span>nudge the cut</div><div><span class="kbd">N</span>next episode</div></div>' +
          '<div class="sxhint">You never scrub here: the tool asks a yes/no question and you answer it.</div></div></div>' +
      '</div>';
    root.querySelectorAll('[data-q]').forEach(function (r) { r.addEventListener('click', function () { cur = +r.dataset.q; renderB(); }); });
    root.querySelectorAll('[data-ok]').forEach(function (b) {
      b.addEventListener('click', function () { bDone[e.id + b.dataset.ok] = true; e.segs[+b.dataset.ok].src = 'me'; selIdx = +b.dataset.ok + 1; renderB(); });
    });
    root.querySelectorAll('[data-l]').forEach(function (b) {
      b.addEventListener('click', function () { var s = e.segs[+b.dataset.l]; s.lock = !s.lock; renderB(); });
    });
    root.querySelectorAll('.bfoot .stp button').forEach(function (b) {
      b.addEventListener('click', function () {
        var i = +b.closest('[data-b]').dataset.b, s = e.segs[i], edge = (s.k === 'credits' || s.k === 'stinger') ? 'a' : 'b';
        s[edge] = Math.max(0, Math.min(e.dur, s[edge] + (+b.dataset.n) / 25)); s.src = 'me'; renderB();
      });
    });
  }

  /* ---------- DIRECTION C — season sheet ---------- */
  var picked = {}, open = 8;
  function renderC() {
    var root = document.getElementById('dirC'), nPick = Object.keys(picked).filter(function (k) { return picked[k]; }).length;
    var maxDur = Math.max.apply(null, EPS.map(function (e) { return e.dur; }));
    var rows = EPS.map(function (e) {
      var s = statusBits(e);
      return '<div class="srow' + (picked[e.i] ? ' sel' : '') + (e.kind === 'odd' || e.kind === 'none' ? ' odd' : '') + '" data-r="' + e.i + '">' +
        '<span class="cb' + (picked[e.i] ? ' on' : '') + '" data-c="' + e.i + '">' + (picked[e.i] ? '✓' : '') + '</span>' +
        '<span class="id">' + e.id + '</span>' +
        '<div class="lane">' + e.segs.map(function (g) {
          return '<i class="' + (e.kind === 'odd' && g.k === 'intro' ? 'odd' : '') + '" style="left:' + (g.a / maxDur * 100) + '%;width:' + Math.max(0.9, (g.b - g.a) / maxDur * 100) + '%;background:' + K[g.k].c + '"></i>';
        }).join('') + '</div>' +
        '<span class="sxhint" style="font-size:11.5px;display:flex;align-items:center;gap:6px"><span class="dot ' + s.dot + '"></span>' + s.txt + '</span>' +
        '<span style="display:flex;gap:5px;align-items:center">' + (e.segs.some(function (g) { return g.lock; }) ? '<span class="src me">🔒</span>' : '') +
          (e.kind === 'none' ? '<span class="src he">not published</span>' : '<span class="src jf">in Jellyfin</span>') + '</span></div>';
    }).join('');
    var e = EPS[open];
    root.innerHTML = '<div class="sxbar"><a class="sxback" href="series-simpsons.html">‹ The Simpsons</a><h1>Intro &amp; credits</h1>' +
      '<span class="sxsub">season 5 · 22 episodes</span><span class="sxsp"></span>' + PUB +
      '<button class="btn sm">↻ Re-detect the season</button><button class="btn sm pri">Publish 19 changes to Jellyfin</button></div>' +
      '<div class="sxmain" style="grid-template-columns:1fr"><div class="sxstage">' +
        '<div class="stat">' +
          '<div class="scard"><b>19 / 22</b><span>intro and credits found</span></div>' +
          '<div class="scard"><b style="color:var(--warn)">3</b><span>guessed, not checked yet</span></div>' +
          '<div class="scard"><b style="color:var(--bad)">2</b><span>disagree with the season</span></div>' +
          '<div class="scard"><b style="color:var(--ok)">4</b><span>locked by you</span></div>' +
          '<div class="scard"><b>0:41 → 1:52</b><span>what the season agrees on</span></div>' +
        '</div>' +
        '<div class="sheet"><div class="sh"><span></span><span class="lbl">Episode</span>' +
          '<div style="display:flex;align-items:center;gap:12px"><span class="lbl">Aligned on one timeline — an odd one out sticks out</span>' + legend(['recap', 'intro', 'preview', 'credits', 'stinger']) + '</div>' +
          '<span class="lbl">State</span><span class="lbl">Jellyfin</span></div>' +
          '<div class="srows">' + rows + '</div>' +
          '<div class="bulk"><span class="cb' + (nPick ? ' on' : '') + '">' + (nPick ? '✓' : '') + '</span>' +
            '<span class="sxhint"><b>' + (nPick || 'No') + '</b> episode' + (nPick === 1 ? '' : 's') + ' selected</span><span class="sxsp"></span>' +
            '<button class="btn sm"' + (nPick ? '' : ' disabled') + '>Give them the season’s intro</button>' +
            '<button class="btn sm"' + (nPick ? '' : ' disabled') + '>🔒 Lock</button>' +
            '<button class="btn sm ghost"' + (nPick ? '' : ' disabled') + '>↻ Detect again</button>' +
            '<button class="btn sm ghost"' + (nPick ? '' : ' disabled') + '>Push to Jellyfin</button></div>' +
        '</div>' +
        '<div class="drawer"><div class="vid" style="flex:none;aspect-ratio:16/9"><div class="ph"><em>' + fmtl(e.segs.length ? e.segs[0].b : 191) + '</em>' + e.id + ' · intro ends</div>' +
          '<div class="foot"><span class="vbtn pri">▶</span><span class="vpill">↺ loop the cut</span></div></div>' +
          '<div style="display:flex;flex-direction:column;gap:9px;min-width:0">' +
            '<div style="display:flex;align-items:center;gap:10px"><b style="font-size:14px">' + e.id + ' · ' + e.t + '</b>' +
              '<span class="src he">intro starts 2:29 later than the rest of the season</span><span class="sxsp"></span>' +
              '<button class="btn sm ghost">Open the full editor →</button></div>' +
            '<div class="track" style="height:34px">' + '<div class="grid"></div>' + segEls(e) + '</div>' +
            wave(e, 110) +
            '<div class="sxhint">The fingerprint matched a <b>recap</b> here, not the intro — this episode opens with a cold open twice as long as usual. Fix it once and lock it so the next scan leaves it alone.</div>' +
            '<div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn sm pri">Use the season’s intro</button><button class="btn sm">Trim by hand</button><button class="lockb">🔓 Lock this episode</button><button class="btn sm ghost">Skip — it is genuinely different</button></div>' +
          '</div></div>' +
      '</div></div>';
    root.querySelectorAll('[data-c]').forEach(function (c) {
      c.addEventListener('click', function (ev) { ev.stopPropagation(); picked[+c.dataset.c] = !picked[+c.dataset.c]; renderC(); });
    });
    root.querySelectorAll('[data-r]').forEach(function (r) {
      r.addEventListener('click', function () { open = +r.dataset.r; renderC(); });
    });
  }

  renderA(); renderB(); renderC();
})();
