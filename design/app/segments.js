/* ============================================================
   Jellystructure — Intro & credits editor (Phase 163)
   Two views in one fullscreen tool:
     SHEET — the whole season on one aligned timeline (front door)
     TRIM  — one title: Jellyfin playback, timeline, waveform, evidence
   Write-through (Phases 71/74): every edit lands immediately. Jellyfin's own
   segments read in as one more candidate — never written back: the 2026-08-13
   dev review probed this house's Jellyfin 10.11.11 live (GET /MediaSegments is
   its only MediaSegments operation, POST returns 405), so publishing was
   dropped from phase-163 entirely. Confirmation is for us, not for Jellyfin.
   ============================================================ */
(function () {
  var K = {
    recap:   { n: 'Recap',         c: 'var(--info)',  cls: 'k-recap' },
    intro:   { n: 'Intro',         c: 'var(--hi)',    cls: 'k-intro' },
    preview: { n: 'Next time',     c: '#4fd1c5',      cls: 'k-preview' },
    credits: { n: 'Credits',       c: 'var(--warn)',  cls: 'k-credits' },
    stinger: { n: 'After-credits', c: 'var(--acc-a)', cls: 'k-stinger' }
  };
  var ORDER = ['recap', 'intro', 'preview', 'credits', 'stinger'];
  var SRC = {
    fp: { cls: 'fp', t: 'matched across episodes' },
    he: { cls: 'he', t: 'guessed from black frames' },
    jf: { cls: 'jf', t: 'from Jellyfin' },
    tmdb: { cls: 'jf', t: 'from TMDB' },
    me: { cls: 'me', t: 'set by you' }
  };
  var TITLES = ['Homer\'s Barbershop Quartet', 'Cape Feare', 'Homer Goes to College', 'Rosebud', 'Treehouse of Horror IV', 'Marge on the Lam', 'Bart\'s Inner Child', 'Boy-Scoutz \'n the Hood', 'The Last Temptation of Homer', '$pringfield', 'Homer the Vigilante', 'Bart Gets Famous', 'Homer and Apu', 'Lisa vs. Malibu Stacy', 'Deep Space Homer', 'Homer Loves Flanders', 'Bart Gets an Elephant', 'Burns\' Heir', 'Sweet Seymour Skinner\'s Badasssss Song', 'The Boy Who Knew Too Much', 'Lady Bouvier\'s Lover', 'Secrets of a Successful Marriage'];

  function pad(n) { return String(n).padStart(2, '0'); }
  function fmt(s) { return Math.floor(s / 60) + ':' + pad(Math.floor(s % 60)); }
  function fmtl(s) { return pad(Math.floor(s / 60)) + ':' + pad(Math.floor(s % 60)); }
  function fmtf(s) { return fmtl(s) + '<s>.' + pad(Math.round((s - Math.floor(s)) * 25)) + '</s>'; }
  function rnd(seed) { var x = Math.sin(seed * 12.9898) * 43758.5453; return x - Math.floor(x); }

  function build(i) {
    var dur = 1288 + Math.round(rnd(i + 3) * 26);
    var kind = i === 8 ? 'odd' : (i % 7 === 3 ? 'low' : (i % 11 === 5 ? 'none' : 'ok'));
    var iA = kind === 'odd' ? 191 : 39 + Math.round(rnd(i) * 5), segs = [];
    if (kind !== 'none') {
      if (i % 3 === 1) segs.push({ k: 'recap', a: 0, b: 20 + Math.round(rnd(i + 9) * 6), src: 'jf' });
      segs.push({ k: 'intro', a: iA, b: iA + 70 + Math.round(rnd(i + 1) * 4), src: kind === 'low' ? 'he' : 'fp', conf: kind === 'low' ? 0.54 + rnd(i) * .05 : .9 + rnd(i + 2) * .08, lock: i % 5 === 0 });
      segs.push({ k: 'credits', a: dur - (78 + Math.round(rnd(i + 4) * 14)), b: dur, src: kind === 'low' ? 'he' : 'fp', conf: kind === 'low' ? .51 + rnd(i + 6) * .06 : .86 + rnd(i + 7) * .1 });
      if (i % 6 === 2) segs.push({ k: 'preview', a: dur - 26, b: dur - 8, src: 'he', conf: .62 });
      if (i % 9 === 4) segs.push({ k: 'stinger', a: dur - 22, b: dur - 3, src: 'tmdb' });
    }
    segs.sort(function (x, y) { return x.a - y.a; });
    var checked = i < 3;
    /* confirmed == a human said so (checked or locked); a detector's guess never counts */
    return { i: i, id: 'S05E' + pad(i + 1), t: TITLES[i], dur: dur, kind: kind, segs: segs, checked: checked };
  }
  var EPS = TITLES.map(function (_, i) { return build(i); });

  /* Movies: same tool, no season to sheet — the rail becomes "films to check". */
  var MOVIES = [
    { slug: 'sintel', id: '2010', t: 'Sintel', dur: 888, segs: [{ k: 'credits', a: 688, b: 888, src: 'fp', conf: .91 }], checked: false },
    { slug: 'tears-of-steel', id: '2012', t: 'Tears of Steel', dur: 734, segs: [{ k: 'intro', a: 0, b: 26, src: 'he', conf: .58 }, { k: 'credits', a: 610, b: 734, src: 'he', conf: .55 }], checked: false },
    { slug: 'big-buck-bunny', id: '2008', t: 'Big Buck Bunny', dur: 596, segs: [{ k: 'credits', a: 452, b: 596, src: 'jf' }, { k: 'stinger', a: 566, b: 590, src: 'tmdb' }], checked: true },
    { slug: 'cosmos-laundromat', id: '2015', t: 'Cosmos Laundromat', dur: 731, segs: [], checked: false },
    { slug: 'spring', id: '2019', t: 'Spring', dur: 462, segs: [{ k: 'credits', a: 372, b: 462, src: 'fp', conf: .93, lock: true }], checked: true },
    { slug: 'agent-327', id: '2017', t: 'Agent 327: Operation Barbershop', dur: 232, segs: [{ k: 'credits', a: 196, b: 232, src: 'he', conf: .61 }], checked: false }
  ].map(function (m, i) { m.i = i; m.kind = m.segs.length ? (m.segs.some(function (s) { return s.conf && s.conf < .7; }) ? 'low' : 'ok') : 'none'; return m; });

  var Q = new URLSearchParams(location.search);
  var MOVIE = Q.has('movie') || Q.get('type') === 'movie';

  var S = { view: 'sheet', cur: 8, sel: 0, picked: {}, open: 8, dirty: 0, head: 0 };
  try {
    var saved = JSON.parse(localStorage.getItem('js-seg-place') || '{}');
    if (saved.view) { S.view = saved.view; S.cur = saved.cur || 0; S.open = saved.cur || 8; }
  } catch (e) {}
  if (MOVIE) {
    EPS = MOVIES; S.view = 'trim'; S.sel = 0;
    var want = MOVIES.filter(function (m) { return m.slug === Q.get('movie'); })[0];
    S.cur = want ? want.i : 0;
  }
  function place() { try { localStorage.setItem('js-seg-place', MOVIE ? JSON.stringify({}) : JSON.stringify({ view: S.view, cur: S.cur })); } catch (e) {} }

  /* what the chrome around the trim view says — a season behind it, or a film library */
  function ctx() {
    var e = EPS[S.cur];
    return MOVIE
      ? { back: '<a class="sxback" href="media.html">‹ ' + e.t + '</a>', rail: 'Films to check', unit: 'film', next: 'Save &amp; next film →', total: EPS.length }
      : { back: '<a class="sxback" href="#sheet" data-a="back">‹ Season 5</a>', rail: 'Season 5', unit: 'episode', next: 'Save &amp; next episode →', total: EPS.length };
  }

  /* ---------- consensus ---------- */
  function consensus(k) {
    var v = EPS.filter(function (e) { return e.kind !== 'odd'; }).map(function (e) {
      var s = e.segs.filter(function (x) { return x.k === k; })[0]; return s ? [s.a, s.b] : null;
    }).filter(Boolean);
    if (!v.length) return null;
    v.sort(function (x, y) { return x[0] - y[0]; });
    return v[Math.floor(v.length / 2)];
  }
  function stats() {
    var found = EPS.filter(function (e) { return e.segs.length; }).length;
    var low = EPS.filter(function (e) { return e.kind === 'low'; }).length;
    var odd = EPS.filter(function (e) { return e.kind === 'odd' || e.kind === 'none'; }).length;
    var lock = EPS.filter(function (e) { return e.segs.some(function (s) { return s.lock; }); }).length;
    return { found: found, low: low, odd: odd, lock: lock };
  }
  function status(e) {
    if (!e.segs.length) return { dot: 'd-bad', txt: 'nothing found — needs a look' };
    if (e.kind === 'odd') return { dot: 'd-bad', txt: 'disagrees with the season' };
    if (e.kind === 'low' && !e.checked) return { dot: 'd-warn', txt: 'guessed, not checked yet' };
    if (e.checked) return { dot: 'd-ok', txt: 'checked' + (e.segs.some(function (s) { return s.lock; }) ? ' · locked' : '') };
    return { dot: 'd-idle', txt: 'matched, not checked' };
  }

  /* ---------- small pieces ---------- */
  function toast(msg) {
    var t = document.createElement('div');
    t.className = 'sxtoast'; t.innerHTML = msg;
    document.getElementById('toasts').appendChild(t);
    setTimeout(function () { t.classList.add('out'); }, 2600);
    setTimeout(function () { t.remove(); }, 3100);
  }
  function legend(ks) {
    return '<div class="legend">' + (ks || ORDER).map(function (k) {
      return '<span><i style="background:' + K[k].c + '"></i>' + K[k].n + '</span>';
    }).join('') + '</div>';
  }
  function segEls(e, interactive) {
    return e.segs.map(function (s, n) {
      var w = (s.b - s.a) / e.dur * 100;
      return '<div class="seg ' + K[s.k].cls + (s.lock ? ' lk' : '') + (interactive && n === S.sel ? ' on' : '') + '" data-s="' + n + '" style="left:' + (s.a / e.dur * 100) + '%;width:' + w + '%">' +
        (w > 6 ? K[s.k].n : '') + (interactive && !s.lock ? '<i class="h l" data-e="a"></i><i class="h r" data-e="b"></i>' : '') + '</div>';
    }).join('');
  }
  function wave(e, n) {
    var h = '';
    for (var x = 0; x < n; x++) {
      var t = x / n * e.dur, amp = .3 + rnd(x * 1.7 + e.i) * .5;
      e.segs.forEach(function (s) {
        if (s.k === 'intro' && t > s.a && t < s.b) amp = .72 + rnd(x * 3.1) * .28;
        if (s.k === 'credits' && t > s.a) amp = .5 + rnd(x * 2.3) * .2;
        if (Math.abs(t - s.a) < 2 || Math.abs(t - s.b) < 2) amp = .04;
      });
      h += '<i style="height:' + (amp * 100).toFixed(0) + '%"></i>';
    }
    return '<div class="wave">' + h + e.segs.map(function (s) {
      return '<span class="sil" style="left:' + ((s.a - 2) / e.dur * 100) + '%;width:' + (4 / e.dur * 100) + '%"></span>';
    }).join('') + '</div>';
  }
  function evidence(e) {
    var h = '<span class="evlbl">why</span>';
    e.segs.forEach(function (s) {
      h += '<span class="b blk" style="left:' + ((s.a - 1.5) / e.dur * 100) + '%;width:' + (3 / e.dur * 100) + '%" title="black frames + silence"></span>' +
        '<span class="b blk" style="left:' + ((s.b - 1.5) / e.dur * 100) + '%;width:' + (3 / e.dur * 100) + '%"></span>';
if (s.src === 'fp') h += '<span class="b fp" style="left:' + (s.a / e.dur * 100) + '%;width:' + ((s.b - s.a) / e.dur * 100) + '%">' + (MOVIE ? 'matched a known outro' : (s.k === 'intro' ? 'same audio in 21 other episodes' : 'matches the series outro')) + '</span>';
      if (s.src === 'jf') h += '<span class="b jfb" style="left:' + (s.a / e.dur * 100) + '%;width:' + ((s.b - s.a) / e.dur * 100) + '%">Jellyfin says the same</span>';
    });
    [0, .31, .58, .79].forEach(function (p) { h += '<span class="ch" style="left:' + p * 100 + '%" title="chapter mark"></span>'; });
    return '<div class="ev">' + h + '</div>';
  }
  function ruler(dur) {
    var h = '';
    for (var t = 0; t <= dur; t += 120) h += '<i style="left:' + (t / dur * 100) + '%"></i><u style="left:' + (t / dur * 100) + '%">' + fmt(t) + '</u>';
    return '<div class="ruler">' + h + '</div>';
  }
  function srcChip(s) {
    /* "matched across episodes" is meaningless for a film — the same signal there is
       the studio/series outro fingerprint, so the wording follows the medium. */
    var t = (MOVIE && s.src === 'fp') ? 'matched a known outro' : SRC[s.src].t;
    return '<span class="src ' + SRC[s.src].cls + '">' + t + (s.conf ? ' · ' + s.conf.toFixed(2) : '') + '</span>';
  }
  function mini(e) {
    return '<div class="mini">' + e.segs.map(function (s) {
      return '<i style="left:' + (s.a / e.dur * 100) + '%;width:' + Math.max(1.2, (s.b - s.a) / e.dur * 100) + '%;background:' + K[s.k].c + '"></i>';
    }).join('') + '</div>';
  }
  /* Confirmation is a human act (decided 2026-08-13): a detector's guess is a
     candidate, not a decision — and it stays inside jellystructure either way. */
  function confirmed(e) { return e.segs.length && (e.checked || e.segs.some(function (s) { return s.lock; })); }
  function waiting() { return EPS.filter(function (e) { return e.segs.length && !confirmed(e); }); }
  function confChip() {
    var w = waiting().length;
    return w ? '<span class="src he">' + w + ' still waiting on me</span>'
             : '<span class="src me">every marker confirmed</span>';
  }
  function confCell(e) {
    if (!e.segs.length) return '<span class="src">nothing marked</span>';
    if (e.segs.some(function (s) { return s.lock; })) return '<span class="src me">locked by me</span>';
    if (e.checked) return '<span class="src me">checked by me</span>';
    return '<span class="src he">a guess</span>';
  }
  function dirty(n) { S.dirty += (n === undefined ? 1 : n); }

  /* ---------- SHEET ---------- */
  function sheet() {
    var st = stats(), maxDur = Math.max.apply(null, EPS.map(function (e) { return e.dur; }));
    var nPick = Object.keys(S.picked).filter(function (k) { return S.picked[k]; }).length;
    var ci = consensus('intro');
    var rows = EPS.map(function (e) {
      var s = status(e);
      return '<div class="srow' + (S.picked[e.i] ? ' sel' : '') + (e.i === S.open ? ' open' : '') + (e.kind === 'odd' || !e.segs.length ? ' odd' : '') + '" data-r="' + e.i + '">' +
        '<span class="cb' + (S.picked[e.i] ? ' on' : '') + '" data-c="' + e.i + '">' + (S.picked[e.i] ? '✓' : '') + '</span>' +
        '<span class="id">' + e.id + '</span>' +
        '<span class="et">' + e.t + '</span>' +
        '<div class="lane">' + (e.segs.length ? e.segs.map(function (g) {
          return '<i class="' + (e.kind === 'odd' && g.k === 'intro' ? 'oddm' : '') + '" style="left:' + (g.a / maxDur * 100) + '%;width:' + Math.max(.9, (g.b - g.a) / maxDur * 100) + '%;background:' + K[g.k].c + '"></i>';
        }).join('') : '<span class="lane-empty">nothing marked</span>') + '</div>' +
        '<span class="stt"><span class="dot ' + s.dot + '"></span>' + s.txt + '</span>' +
        '<span class="pubc">' + (e.segs.some(function (g) { return g.lock; }) ? '<span class="src me" title="locked — detection will not touch it">🔒</span>' : '') +
        confCell(e) + '</span></div>';
    }).join('');
    var e = EPS[S.open];
    return '<div class="sxbar"><a class="sxback" href="series-simpsons.html">‹ The Simpsons</a><h1>Intro &amp; credits</h1>' +
      '<span class="sxsub">season 5 · 22 episodes</span><span class="sxsp"></span>' + confChip() +
      '<button class="btn sm" data-a="redetect-season">↻ Re-detect the season</button>' +
      '<button class="btn sm pri" data-a="pick-attn"' + (waiting().length ? '' : ' disabled') + '>' + (waiting().length ? 'Take me to what needs me' : 'Nothing left to check') + '</button></div>' +
      '<div class="sxmain" style="grid-template-columns:1fr"><div class="sxstage">' +
        '<div class="stat">' +
          '<div class="scard"><b>' + st.found + ' / 22</b><span>intro and credits found</span></div>' +
          '<div class="scard"><b style="color:var(--warn)">' + st.low + '</b><span>guessed, not checked yet</span></div>' +
          '<div class="scard"><b style="color:var(--bad)">' + st.odd + '</b><span>disagree with the season</span></div>' +
          '<div class="scard"><b style="color:var(--ok)">' + st.lock + '</b><span>locked by you</span></div>' +
          '<div class="scard"><b>' + (ci ? fmt(ci[0]) + ' → ' + fmt(ci[1]) : '—') + '</b><span>what the season agrees on</span></div>' +
        '</div>' +
        '<div class="sheet"><div class="sh"><span></span><span class="lbl">Ep</span><span class="lbl thead">Title</span>' +
          '<div class="shl"><span class="lbl">Aligned on one timeline — an odd one out sticks out</span>' + legend() + '</div>' +
          '<span class="lbl">State</span><span class="lbl">Confirmed</span></div>' +
          '<div class="srows">' + rows + '</div>' +
          '<div class="bulk"><span class="sxhint"><b>' + (nPick || 'No') + '</b> episode' + (nPick === 1 ? '' : 's') + ' selected</span>' +
            '<span class="sxhint" style="color:var(--ink-dim)">· a detector’s guess is not a decision' + (waiting().length ? ' — <b>' + waiting().length + '</b> still unconfirmed' : '') + '</span>' +
            '<button class="btn sm ghost" data-a="pick-attn">Select everything that needs me</button><span class="sxsp"></span>' +
            '<button class="btn sm" data-a="apply"' + (nPick ? '' : ' disabled') + '>Give them the season’s intro</button>' +
            '<button class="btn sm" data-a="lock"' + (nPick ? '' : ' disabled') + '>🔒 Lock</button>' +
            '<button class="btn sm ghost" data-a="redetect"' + (nPick ? '' : ' disabled') + '>↻ Detect again</button></div>' +
        '</div>' +
        '<div class="drawer"><div class="vid"><div class="ph"><em>' + fmtl(e.segs.length ? e.segs[0].b : 0) + '</em>' + e.id + (e.segs.length ? ' · ' + K[e.segs[0].k].n.toLowerCase() + ' ends' : ' · nothing marked') + '</div>' +
            '<div class="foot"><span class="vbtn pri">▶</span><span class="vpill">↺ loop the cut</span></div></div>' +
          '<div class="dcol">' +
            '<div class="drow"><b>' + e.id + ' · ' + e.t + '</b>' +
              (e.kind === 'odd' ? '<span class="src he">intro starts ' + fmt(e.segs[0].a - (ci ? ci[0] : 0)) + ' later than the rest of the season</span>' : '') +
              (!e.segs.length ? '<span class="src he">no markers at all</span>' : '') +
              '<span class="sxsp"></span><button class="btn sm ghost" data-a="open">Open the full editor →</button></div>' +
            '<div class="track" style="height:32px"><div class="grid"></div>' + segEls(e) + '</div>' +
            wave(e, 120) +
            '<div class="sxhint">' + (e.kind === 'odd'
              ? 'The match landed on a <b>recap</b> here — this episode opens with a cold open twice the usual length. Fix it once and lock it so the next scan leaves it alone.'
              : e.segs.length ? 'Looks like the rest of the season. Open the editor if you want to watch the cut before signing it off.'
              : 'Nothing was detected. Borrow what the season agrees on, or set the boundaries yourself in the editor.') + '</div>' +
            '<div class="drow"><button class="btn sm pri" data-a="apply-one">Use the season’s intro</button>' +
              '<button class="btn sm" data-a="open">Trim by hand</button>' +
              '<button class="lockb' + (e.segs.some(function (s) { return s.lock; }) ? ' on' : '') + '" data-a="lock-one">' + (e.segs.some(function (s) { return s.lock; }) ? '🔒 locked' : '🔓 Lock this episode') + '</button>' +
              '<button class="btn sm ghost" data-a="ok-one">Fine as it is</button></div>' +
          '</div></div>' +
      '</div></div>';
  }

  /* ---------- TRIM ---------- */
  function trim() {
    var e = EPS[S.cur], s = e.segs[S.sel], head = s ? s.b : S.head;
    var missing = ORDER.filter(function (k) { return !e.segs.some(function (x) { return x.k === k; }); });
    var rows = e.segs.map(function (g, n) {
      return '<div class="mk' + (n === S.sel ? ' sel' : '') + (g.lock ? ' lkd' : '') + '" data-m="' + n + '">' +
        '<span class="sw" style="background:' + K[g.k].c + '"></span><span class="nm">' + K[g.k].n + '</span>' +
        '<span class="tc"><span class="stp"><button data-d="-1" data-e="a">−</button><button data-d="1" data-e="a">+</button></span>' + fmtl(g.a) +
          '<s>→</s>' + fmtl(g.b) + '<span class="stp"><button data-d="-1" data-e="b">−</button><button data-d="1" data-e="b">+</button></span>' +
          '<s class="len">' + fmt(g.b - g.a) + ' long</s>' + srcChip(g) + '</span>' +
        '<span class="acts"><button class="btn sm ghost" data-p="' + n + '">▶ play the cut</button>' +
          '<button class="lockb' + (g.lock ? ' on' : '') + '" data-l="' + n + '">' + (g.lock ? '🔒 locked' : '🔓 lock') + '</button></span></div>';
    }).join('');
    var C = ctx();
    return '<div class="sxbar">' + C.back +
      '<span class="num">' + e.id + '</span><h1>' + e.t + '</h1><span class="sxsub">' + fmtl(e.dur) + '</span>' +
      '<span class="sxsp"></span>' +
      (confirmed(e) ? '<span class="src me">confirmed by me</span>'
                    : '<span class="src he">still a guess — check it or lock it</span>') +
      '<button class="btn sm" data-a="redetect-one">↻ Re-detect</button>' +
      '<button class="btn sm pri" data-a="next">' + C.next + '</button></div>' +
      '<div class="sxmain" style="grid-template-columns:1fr 322px"><div class="sxstage">' +
        '<div class="vid"><div class="ph"><em>' + fmtl(head) + '</em>' + (s ? K[s.k].n.toLowerCase() + ' ends here' : 'drag on the bar to mark a segment') + '</div>' +
          '<div class="tag">' + (s ? '<span class="vpill" style="color:' + K[s.k].c + ';border-color:' + K[s.k].c + '44">▍' + K[s.k].n + '</span>' : '') + '</div>' +
          '<div class="tag2"><span class="vpill ok">direct play · no transcode</span></div>' +
          '<div class="foot"><span class="vbtn pri">▶</span><span class="vbtn" data-a="fb">◂◂</span><span class="vbtn" data-a="ff">▸▸</span>' +
            '<span class="vpill">↺ loop this cut · 3 s either side</span><span class="sxsp"></span>' +
            '<span class="vpill mono">' + fmtl(head) + ' / ' + fmtl(e.dur) + '</span></div></div>' +
        '<div class="tl"><div class="tlh"><span class="lbl">Timeline</span>' + legend() + '</div>' + ruler(e.dur) +
          '<div class="track" id="track"><div class="grid"></div>' + segEls(e, true) +
            '<div class="play" style="left:' + (head / e.dur * 100) + '%"></div></div>' +
          wave(e, 150) + evidence(e) + '</div>' +
        '<div class="mks">' + rows +
          (missing.length ? '<div class="mk add"><span class="sw" style="background:var(--fill-3)"></span>' +
            '<span class="sxhint">No ' + missing.map(function (k) { return K[k].n.toLowerCase(); }).join(', ') + ' in this ' + (MOVIE ? 'film — normal for one' : 'episode') + '.</span>' +
            '<span class="acts">' + missing.map(function (k) { return '<button class="btn sm ghost" data-add="' + k + '">＋ ' + K[k].n + '</button>'; }).join('') + '</span></div>' : '') +
        '</div></div>' +
      '<div class="sxrail"><div class="rh"><b>' + C.rail + '</b><span class="sxsp"></span><span class="lbl">' +
        EPS.filter(function (x) { return x.checked; }).length + ' of ' + C.total + ' checked</span></div>' +
        '<div class="q">' + EPS.map(function (x) {
          var st = status(x);
          return '<div class="qr' + (x.i === S.cur ? ' on' : '') + '" data-q="' + x.i + '"><span class="id">' + x.id + '</span>' +
            '<div><div class="t">' + x.t + '</div><div class="st">' + st.txt + '</div>' + mini(x) + '</div>' +
            '<span class="dot ' + st.dot + '"></span></div>';
        }).join('') + '</div>' +
        '<div class="qfoot"><div class="keys">' +
          '<div><span class="kbd">I</span><span class="kbd">O</span>set in / out at the playhead</div>' +
          '<div><span class="kbd">,</span><span class="kbd">.</span>nudge a frame · <span class="kbd">⇧</span> for a second</div>' +
          '<div><span class="kbd">L</span>lock the selected marker</div>' +
          '<div><span class="kbd">↵</span>save and open the next ' + C.unit + '</div></div>' +
          '<div class="sxhint">A locked marker survives every future <b>detect_segments</b> run — that is the whole point of the lock.</div>' +
          '<a class="sxlink" href="settings.html?tab=libraries">Detection settings →</a></div></div></div>';
  }

  /* ---------- render + wiring ---------- */
  var root = document.getElementById('root');
  function render() {
    root.innerHTML = S.view === 'sheet' ? sheet() : trim();
    document.title = 'Jellystructure — ' + (S.view === 'sheet' ? 'Intro & credits · The Simpsons S5' : EPS[S.cur].id + ' segments');
    if (S.view === 'trim') wireTrim(); else wireSheet();
    place();
  }
  function act(el) { return el.closest('[data-a]') ? el.closest('[data-a]').dataset.a : null; }

  function wireSheet() {
    root.querySelectorAll('[data-c]').forEach(function (c) {
      c.addEventListener('click', function (ev) { ev.stopPropagation(); var i = +c.dataset.c; S.picked[i] = !S.picked[i]; render(); });
    });
    root.querySelectorAll('[data-r]').forEach(function (r) {
      r.addEventListener('click', function () { S.open = +r.dataset.r; render(); });
    });
    root.addEventListener('click', function (ev) {
      var a = act(ev.target); if (!a) return;
      var ci = consensus('intro'), picks = Object.keys(S.picked).filter(function (k) { return S.picked[k]; }).map(Number);
      if (a === 'open') { S.cur = S.open; S.sel = 0; S.view = 'trim'; render(); }
      if (a === 'pick-attn') { EPS.forEach(function (e) { if (e.kind === 'odd' || e.kind === 'low' || !e.segs.length) S.picked[e.i] = true; }); render(); toast('Selected every episode that needs a decision'); }
      if (a === 'apply' || a === 'apply-one') {
        var list = a === 'apply' ? picks : [S.open];
        list.forEach(function (i) { applyConsensus(EPS[i], ci); });
        S.picked = {}; render();
        toast('Intro set to <b>' + fmt(ci[0]) + ' → ' + fmt(ci[1]) + '</b> on ' + list.length + ' episode' + (list.length === 1 ? '' : 's') + ' · written to disk');
      }
      if (a === 'lock' || a === 'lock-one') {
        var l = a === 'lock' ? picks : [S.open], on = false;
        l.forEach(function (i) { EPS[i].segs.forEach(function (s) { s.lock = a === 'lock' ? true : !s.lock; on = on || s.lock; }); });
        if (a === 'lock') S.picked = {};
        render(); toast(on ? 'Locked — detection will leave these alone' : 'Unlocked — the next scan may change these');
      }
      if (a === 'redetect' || a === 'redetect-season' || a === 'redetect-one') {
        toast('Queued <b>detect_segments</b> ' + (a === 'redetect-season' ? 'for the season' : a === 'redetect' ? 'for ' + picks.length + ' episodes' : 'for ' + EPS[S.cur].id) + ' — locked markers are skipped');
      }
      if (a === 'ok-one') { EPS[S.open].checked = true; render(); toast(EPS[S.open].id + ' marked as checked'); }
    });
  }
  function applyConsensus(e, ci) {
    if (!ci) return;
    var intro = e.segs.filter(function (s) { return s.k === 'intro'; })[0];
    if (intro) { intro.a = ci[0]; intro.b = ci[1]; intro.src = 'me'; delete intro.conf; }
    else e.segs.unshift({ k: 'intro', a: ci[0], b: ci[1], src: 'me' });
    if (!e.segs.some(function (s) { return s.k === 'credits'; })) {
      var cc = consensus('credits');
      if (cc) e.segs.push({ k: 'credits', a: e.dur - (cc[1] - cc[0]), b: e.dur, src: 'me' });
    }
    e.segs.sort(function (x, y) { return x.a - y.a; });
    e.kind = 'ok'; e.checked = true; dirty();
  }

  function wireTrim() {
    var e = EPS[S.cur];
    root.querySelectorAll('[data-q]').forEach(function (r) {
      r.addEventListener('click', function () { S.cur = +r.dataset.q; S.sel = 0; render(); });
    });
    root.querySelectorAll('[data-m]').forEach(function (r) {
      r.addEventListener('click', function (ev) { if (!ev.target.closest('button')) { S.sel = +r.dataset.m; render(); } });
    });
    root.querySelectorAll('[data-l]').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = e.segs[+b.dataset.l]; s.lock = !s.lock; if (s.lock) { s.src = 'me'; delete s.conf; }
        dirty(); render(); toast(s.lock ? K[s.k].n + ' locked — detection will not touch it' : K[s.k].n + ' unlocked');
      });
    });
    root.querySelectorAll('.stp button').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = e.segs[+b.closest('[data-m]').dataset.m], f = b.dataset.e;
        s[f] = Math.max(0, Math.min(e.dur, s[f] + +b.dataset.d)); s.src = 'me'; delete s.conf; dirty();
        S.sel = +b.closest('[data-m]').dataset.m; render();
      });
    });
    root.querySelectorAll('[data-p]').forEach(function (b) {
      b.addEventListener('click', function () { var s = e.segs[+b.dataset.p]; toast('Playing ' + fmtl(s.b - 3) + ' → ' + fmtl(s.b + 3) + ' on a loop'); });
    });
    root.querySelectorAll('[data-add]').forEach(function (b) {
      b.addEventListener('click', function () {
        var k = b.dataset.add, at = k === 'credits' || k === 'stinger' || k === 'preview' ? e.dur - 60 : 30;
        e.segs.push({ k: k, a: at, b: at + 40, src: 'me' });
        e.segs.sort(function (x, y) { return x.a - y.a; });
        S.sel = e.segs.findIndex(function (s) { return s.k === k; }); dirty(); render();
        toast(K[k].n + ' added — drag the handles or nudge the timecodes');
      });
    });
    root.addEventListener('click', function (ev) {
      var a = act(ev.target); if (!a) return;
      if (a === 'back' && !MOVIE) { S.view = 'sheet'; S.open = S.cur; render(); }
      if (a === 'next') {
        e.checked = true;
        var nx = EPS.filter(function (x) { return !x.checked; })[0];
        if (nx) { S.cur = nx.i; S.sel = 0; render(); toast((MOVIE ? e.t : e.id) + ' confirmed · opened ' + (MOVIE ? nx.t : nx.id)); }
        else if (MOVIE) { render(); toast('Every film with markers is confirmed'); }
        else { S.view = 'sheet'; render(); toast('Every episode in the season is checked'); }
      }
      if (a === 'redetect-one') toast('Queued <b>detect_segments</b> for ' + (MOVIE ? e.t : e.id) + ' — locked markers are skipped');
    });
    dragHandles(e);
  }

  function dragHandles(e) {
    var track = document.getElementById('track'); if (!track) return;
    track.querySelectorAll('.h').forEach(function (h) {
      h.addEventListener('mousedown', function (ev) {
        ev.preventDefault(); ev.stopPropagation();
        var n = +h.closest('.seg').dataset.s, f = h.dataset.e, s = e.segs[n], box = track.getBoundingClientRect();
        S.sel = n; document.body.style.cursor = 'ew-resize';
        function move(m) {
          var t = Math.max(0, Math.min(e.dur, (m.clientX - box.left) / box.width * e.dur));
          if (f === 'a') s.a = Math.min(t, s.b - 1); else s.b = Math.max(t, s.a + 1);
          var el = track.querySelector('.seg[data-s="' + n + '"]');
          el.style.left = (s.a / e.dur * 100) + '%'; el.style.width = ((s.b - s.a) / e.dur * 100) + '%';
          track.querySelector('.play').style.left = (s[f] / e.dur * 100) + '%';
        }
        function up() {
          document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up);
          document.body.style.cursor = ''; s.src = 'me'; delete s.conf; dirty(); render();
          toast(K[s.k].n + ' now ' + fmtl(s.a) + ' → ' + fmtl(s.b) + ' · saved');
        }
        document.addEventListener('mousemove', move); document.addEventListener('mouseup', up);
      });
    });
    track.querySelectorAll('.seg').forEach(function (el) {
      el.addEventListener('mousedown', function (ev) { if (!ev.target.classList.contains('h')) { S.sel = +el.dataset.s; render(); } });
    });
  }

  document.addEventListener('keydown', function (ev) {
    if (S.view !== 'trim' || ev.target.matches('input,textarea')) return;
    var e = EPS[S.cur], s = e.segs[S.sel]; if (!s) return;
    var step = ev.shiftKey ? 1 : 0.04, k = ev.key.toLowerCase();
    if (k === ',' || k === '.') { s.b = Math.max(s.a + 1, s.b + (k === ',' ? -step : step)); s.src = 'me'; delete s.conf; dirty(); render(); }
    else if (k === 'l') { s.lock = !s.lock; dirty(); render(); }
    else if (k === 'enter') root.querySelector('[data-a="next"]').click();
    else if (k === 'escape') { S.view = 'sheet'; S.open = S.cur; render(); }
  });

  render();
})();
