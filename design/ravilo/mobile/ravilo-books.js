/* Ravilo phone — part 2: audiobooks inside the listening mode (design brief §M). The shelf, the Book page,
   the book variant of Now playing (±30 s · speed · sleep · chapters · bookmarks), the book mini bar, the
   sheets, and the §M7 questions + book states in the review panel. Data: ../app/audiobook-data.js.
   Speed exists here and only here — the video player's "no speed" ruling stands. */
(function () {
  const RM = window.RaviloMusic, H = window.RaviloHost, B = window.BOOKS, M = window.MUSIC;
  if (!RM || !H || !B || !RM.MS.P) return;
  const MS = RM.MS, q = MS.q, P = MS.P, esc = H.esc, t = (k, v) => (window.t ? window.t(k, v) : k), $ = id => document.getElementById(id);
  const BS = {}; B.all.forEach(b => BS[b.id] = { pos: 0, speed: 1, finished: false, marks: [] });
  BS.vinterfaergen.pos = 7920; BS['the-salt-road'].pos = 14220; BS['stille-vand'].finished = true;
  const K = { shelf: 'progress', cur: null, playing: false, buffering: false, sleep: null, sleepLeft: 0, fail: false, sort: 0, chip: null, boundary: false, sheetTab: 'chapters' };
  const books = () => K.shelf === 'empty' ? [] : K.shelf === 'many' ? B.all : [B.household];
  const started = b => BS[b.id].pos > 0 && !BS[b.id].finished;
  const inProgress = () => K.shelf === 'none' ? [] : books().filter(started);
  const cover = (b, cls) => '<div class="mu-cv' + (cls ? ' ' + cls : '') + '" style="' + (b.cover ? B.coverStyle(b) : B.wordmarkStyle(b)) + '">' + (b.cover ? '' : '<span class="wm">' + esc(b.title) + '</span>') + '</div>';
  const left = b => b.len - BS[b.id].pos;
  const pct = b => BS[b.id].finished ? 100 : Math.round(BS[b.id].pos / b.len * 100);
  const ring = (b, sz) => '<span class="bk-ring" style="--p:' + pct(b) + ';width:' + sz + 'px;height:' + sz + 'px"></span>';
  const chapN = b => b.chapters.length;
  const IC = {
    b30: '<svg viewBox="0 0 24 24"><path d="M11 5.2V2.4L6 6.3l5 3.9V7.4a5 5 0 1 1-5 5"/><text x="12.4" y="16.6" font-size="6.4" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="Sora,sans-serif">30</text></svg>',
    f30: '<svg viewBox="0 0 24 24"><path d="M13 5.2V2.4L18 6.3l-5 3.9V7.4a5 5 0 1 0 5 5"/><text x="11.6" y="16.6" font-size="6.4" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="Sora,sans-serif">30</text></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6.5" y="5" width="4" height="14" rx="1.3"/><rect x="13.5" y="5" width="4" height="14" rx="1.3"/></svg>',
    moon: '<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>',
    list: '<svg viewBox="0 0 24 24"><path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r="1"/><circle cx="4.5" cy="12" r="1"/><circle cx="4.5" cy="18" r="1"/></svg>',
    mark: '<svg viewBox="0 0 24 24"><path d="M7 3.5h10v17l-5-3.6-5 3.6z"/></svg>',
    cast: '<svg viewBox="0 0 24 24"><path d="M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9.5V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.8" fill="currentColor" stroke="none"/></svg>',
    down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
    bars: '<span class="mu-bars"><i></i><i></i><i></i></span>',
  };
  const cb = () => K.cur && B.book(K.cur);

  /* ---- clock ---- */
  setInterval(() => {
    const b = cb(); if (!b || !K.playing || K.buffering || K.fail) return;
    const s = BS[b.id], ci = B.chapterAt(b, s.pos);
    s.pos = Math.min(b.len, s.pos + (q.s1 === 'no' ? 1 : s.speed));
    if (B.chapterAt(b, s.pos) !== ci) chapterTurned(b);
    if (K.sleep) { if (K.sleep === 'chapter') { if (B.chapterAt(b, s.pos) !== ci) { K.playing = false; K.sleep = null; H.phToast(t('ab.sleep') + ' · ' + t('ab.sleep_end_chapter')); } }
      else { K.sleepLeft--; if (K.sleepLeft <= 0) { K.playing = false; K.sleep = null; } } }
    if (s.pos >= b.len) { s.finished = true; K.playing = false; }
    if (P.state.open) { if (K.boundary) { K.boundary = false; P.paintNow(); } else live(); } else live();
  }, 1000);
  function chapterTurned() { K.boundary = true; }
  function resume(id, from) {
    P.silenceMusic(); P.closeNow();
    K.cur = id; const s = BS[id]; if (from != null) s.pos = from; if (s.finished && from == null) { s.finished = false; s.pos = 0; }
    K.playing = true; K.fail = false; K.buffering = true; clearTimeout(K.bt); if (!K.stickyBuf) K.bt = setTimeout(() => { K.buffering = false; live(); }, 650);
    P.paintMini(); if (MS.det) RM.paintDet(); if (H.tab === 'm-books' || MS.libChip === 'books') RM.rerender();
    P.openNow();
  }
  function stop(silent) { K.cur = null; K.playing = false; K.sleep = null; if (!silent) { P.closeNow(); P.paintMini(); } }
  function skip(d) { const b = cb(); if (!b) return; const s = BS[b.id], ci = B.chapterAt(b, s.pos); s.pos = Math.max(0, Math.min(b.len - 1, s.pos + d)); if (B.chapterAt(b, s.pos) !== ci && P.state.open) P.paintNow(); else live(); }
  function toggle() { if (K.buffering) return; const b = cb(); if (b && BS[b.id].finished) return; K.playing = !K.playing; live(); if (MS.det) RM.paintDet(); }

  /* ---- Now playing · book ---- */
  const fmt = M.fmtLen;
  function nowHTML() {
    const b = cb(), s = BS[b.id], ci = B.chapterAt(b, s.pos), c = b.chapters[ci], au = B.authorNames(b.authors);
    const tint = 'background:radial-gradient(120% 70% at 50% 18%, hsl(' + b.hue + ' 40% 24%), var(--bg) 78%)';
    const land = document.querySelector('.phone').classList.contains('land');
    const top = '<div class="mu-ntop"><button class="mu-ib" data-np="close" aria-label="Close">' + IC.down + '</button><div class="ctx">' + (q.j3 === 'draw' ? '♪ ' : '') + t('mnav.audiobooks') + '<b>' + esc(b.title) + '</b></div><button class="mu-ib" data-nb="menu" aria-label="More">' + IC.more + '</button></div>';
    const cov = '<div class="mu-ncov" id="muBCov">' + cover(b) + '</div>';
    const meta = '<div class="mu-nmeta"><div class="b"><div class="mu-mq"><span id="bkChap">' + esc(c.title) + '</span></div><div class="mu-nsub"><a data-nb="book">' + esc(b.title) + '</a> · <a data-nb="author">' + esc(au) + '</a></div></div></div>';
    const seek = '<div class="mu-seek bk-seek" id="bkSeek"><div class="tr"><span class="dn" id="bkDn"></span><span class="kb" id="bkKb"></span></div><div class="bub" id="bkBub">0:00</div><div class="tm"><span id="bkPos">0:00</span><span id="bkLeft"></span></div></div><div class="bk-bookscrub" id="bkBookScrub" data-nb="bookseek"><span class="l">' + t('ab.whole_book') + '</span><span class="trk"><i id="bkBook"></i><u id="bkBookKb"></u></span><span class="v" id="bkBookV"></span></div>';
    const speed = q.s1 !== 'no' ? '<button class="bk-chip" data-nb="speed" aria-label="' + t('ab.speed') + '">' + fmtSpeed(s.speed) + '</button>' : '<span class="bk-chip ghost"></span>';
    const sleep = '<button class="mu-ib sm' + (K.sleep ? ' on' : '') + '" data-nb="sleep" aria-label="' + t('ab.sleep') + '">' + IC.moon + (K.sleep ? '<span class="bk-sl" id="bkSl">' + sleepLabel() + '</span>' : '') + '</button>';
    const tp = s.finished ? '<div class="bk-fin"><b>' + t('ab.finished') + '</b><button class="mu-dacts-btn" data-nb="startover">' + t('ab.start_over') + '</button></div>'
      : '<div class="mu-tp">' + speed + '<button class="mu-ib" data-nb="b30" aria-label="' + t('ab.skip_back') + '">' + IC.b30 + '</button><button class="mu-pp" data-nb="pp" id="bkPP">' + pp() + '</button><button class="mu-ib" data-nb="f30" aria-label="' + t('ab.skip_fwd') + '">' + IC.f30 + '</button>' + sleep + '</div>';
    // owner 2026-09-28 (R323 open question 2): a chapter list on the page itself — tap to jump; the strip follows the playing chapter
    const chs = b.chapters.length > 1 ? '<div class="bk-chstrip" id="bkChs">' + b.chapters.map((x, i) => '<button class="bk-chc' + (i === ci ? ' on' : i < ci ? ' done' : '') + '" data-nb="chapjump" data-i="' + i + '"><b>' + (i + 1) + '</b><span>' + esc(x.title) + '</span><em>' + fmt(x.len) + '</em></button>').join('') + '</div>' : '';
    const bot = '<div class="mu-nbot"><button class="mu-ib" data-nb="chapters" aria-label="' + t('ab.chapters') + '">' + IC.list + '</button>' + (q.s3 !== 'later' ? '<button class="mu-ib" data-nb="mark" aria-label="' + t('ab.bookmark_add') + '">' + IC.mark + '</button>' : '<span class="mu-ib ghost"></span>') + '<button class="mu-ib" data-np="cast" aria-label="Cast">' + IC.cast + '</button><button class="mu-ib" data-nb="menu" aria-label="More">' + IC.more + '</button></div>';
    if (land) return '<div class="bgt" style="' + tint + '"></div><button class="mu-ib mu-lclose" data-np="close">' + IC.down + '</button><div class="mu-nland">' + cov + '<div class="right">' + meta + seek + tp + chs + bot + '</div></div>';
    return '<div class="bgt" style="' + tint + '"></div>' + top + '<div class="mu-nmain"><div class="mu-nspacer"></div>' + cov + meta + seek + tp + chs + '<div class="mu-nspacer"></div></div>' + bot;
  }
  const fmtSpeed = v => (Math.round(v * 100) / 100).toString().replace(/^(\d)$/, '$1.0') + '×';
  const pp = () => K.buffering ? '<span class="mp-pulse"><i></i><i></i><i></i></span>' : (K.playing ? IC.pause : IC.play);
  const sleepLabel = () => K.sleep === 'chapter' ? '⌐' : Math.max(1, Math.ceil(K.sleepLeft / 60)) + '';
  function live() {
    const b = cb(); if (!b) return;
    const s = BS[b.id], ci = B.chapterAt(b, s.pos), c = b.chapters[ci], inC = s.pos - c.start;
    const cp = Math.min(100, inC / c.len * 100), bp = Math.min(100, s.pos / b.len * 100);
    if ($('bkDn')) { $('bkDn').style.width = cp + '%'; $('bkKb').style.left = cp + '%'; $('bkBook').style.width = bp + '%'; if ($('bkBookKb')) $('bkBookKb').style.left = bp + '%'; if ($('bkBookV')) $('bkBookV').textContent = B.fmtH(s.pos) + ' / ' + B.fmtH(b.len);
      const strip = $('bkChs'); if (strip && strip.dataset.ci !== String(ci)) { strip.dataset.ci = ci; strip.querySelectorAll('.bk-chc').forEach((el, i) => { el.classList.toggle('on', i === ci); el.classList.toggle('done', i < ci); }); const on = strip.children[ci]; if (on) strip.scrollTo({ left: on.offsetLeft - 16, behavior: 'smooth' }); } $('bkPos').textContent = fmt(inC) + ' / ' + fmt(c.len); $('bkLeft').textContent = t('ab.left', { t: B.fmtH(left(b)) }); }
    if ($('bkPP')) $('bkPP').innerHTML = pp();
    if ($('bkSl')) $('bkSl').textContent = sleepLabel();
    if ($('bkChap') && $('bkChap').textContent !== c.title) $('bkChap').textContent = c.title;
    if ($('muMiniBar')) $('muMiniBar').style.width = bp + '%';
    if ($('muMiniPP')) $('muMiniPP').innerHTML = K.buffering ? '<span class="mu-pulse-s"><i></i><i></i><i></i></span>' : (K.playing ? IC.pause : IC.play);
    if ($('muMiniChap')) $('muMiniChap').textContent = c.title;
    if (!P.state.open && (H.tab === 'm-books') && document.querySelector('.bk-cont .bk-left')) document.querySelectorAll('.bk-cont [data-left="' + b.id + '"]').forEach(x => x.textContent = t('ab.left', { t: B.fmtH(left(b)) }));
  }
  function miniHTML() {
    const b = cb(), c = b.chapters[B.chapterAt(b, BS[b.id].pos)];
    return '<div class="pg"><i id="muMiniBar"></i></div>' + (q.j5 === 'swipe' ? '<span class="gr"></span>' : '') + cover(b, 'sm')
      + '<div class="tx"><div class="n" id="muMiniChap">' + esc(c.title) + '</div><div class="d">' + esc(b.title) + ' · ' + esc(B.authorNames(b.authors)) + '</div></div>'
      + '<button data-mini="back30" aria-label="' + t('ab.skip_back') + '" class="bk-m30">' + IC.b30 + '</button><button id="muMiniPP" data-mini="pp" aria-label="Play or pause"></button>'
      + (q.j5 === 'x' ? '<button class="x" data-mini="stop" aria-label="' + t('music.stop') + '"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>' : '');
  }

  /* ---- sheets ---- */
  const SPEEDS = [0.8, 0.9, 1, 1.1, 1.2, 1.5, 1.75, 2];
  const tick = '<svg viewBox="0 0 24 24"><path d="M4 12.5l5 5L20 6.5"/></svg>';
  function speedSheet() { const s = BS[K.cur];
    P.openSheet('bkspeed', '<div class="mp-sh"><h4>' + t('ab.speed') + '</h4><span style="margin-left:auto;font-size:13px;color:var(--ink-dim)">' + esc(cb().title) + '</span></div><div class="bk-speeds">' + SPEEDS.map(v => '<button data-bs="speed" data-v="' + v + '" class="' + (v === s.speed ? 'on' : '') + '">' + fmtSpeed(v) + '</button>').join('') + '</div><div class="mp-note">' + t('ab.speed_note') + '</div>'); }
  function sleepSheet() {
    const O = [['15', t('ab.sleep_min', { n: 15 })], ['30', t('ab.sleep_min', { n: 30 })], ['45', t('ab.sleep_min', { n: 45 })], ['60', t('ab.sleep_min', { n: 60 })], ['chapter', t('ab.sleep_end_chapter')]];
    const cur = K.sleep === 'chapter' ? 'chapter' : K.sleep ? String(K.sleep) : null;
    P.openSheet('bksleep', '<div class="mp-sh"><h4>' + t('ab.sleep') + '</h4>' + (K.sleep ? '<span style="margin-left:auto;font-size:13px;color:var(--ink-dim)">' + (K.sleep === 'chapter' ? t('ab.sleep_end_chapter') : t('ab.left', { t: Math.ceil(K.sleepLeft / 60) + ' min' })) + '</span>' : '') + '</div>'
      + O.map(o => '<button class="mp-row' + (cur === o[0] ? ' on' : '') + '" data-bs="sleep" data-v="' + o[0] + '"><span><span class="nm">' + o[1] + '</span></span><span class="rt">' + (cur === o[0] ? tick : '') + '</span></button>').join('')
      + (K.sleep ? '<button class="mp-row" data-bs="sleep" data-v="off"><span><span class="nm">' + t('ab.off') + '</span></span></button>' : '')
      + '<div class="mp-note">' + (MS.sleepFade !== false ? t('ab.sleep_fade_note') : '') + '</div>'); }
  function chapterRows(b) { const ci = B.chapterAt(b, BS[b.id].pos);
    return b.chapters.map((c, i) => '<div class="mu-tr' + (i === ci && K.cur === b.id ? ' on' : '') + (i < ci && started(b) ? ' bk-done' : '') + '" data-mu="bkchap" data-id="' + b.id + '" data-i="' + i + '"><span class="n">' + (i === ci && K.cur === b.id ? IC.bars.replace('mu-bars', 'mu-bars' + (K.playing ? '' : ' paused')) : (i + 1)) + '</span><span class="t">' + esc(c.title) + '</span><span class="ln">' + fmt(c.len) + '</span><span></span></div>').join(''); }
  function chaptersSheet(tab) {
    const b = cb(); K.sheetTab = tab || K.sheetTab; if (q.s3 === 'later') K.sheetTab = 'chapters';
    const s = BS[b.id];
    const tabs = q.s3 !== 'later' ? '<div class="tabs" style="padding:0 20px 8px"><div class="tab' + (K.sheetTab === 'chapters' ? ' on' : '') + '" data-bs="tab" data-v="chapters">' + t('ab.chapters') + '</div><div class="tab' + (K.sheetTab === 'marks' ? ' on' : '') + '" data-bs="tab" data-v="marks">' + t('ab.bookmarks') + (s.marks.length ? ' ' + s.marks.length : '') + '</div></div>' : '';
    const body = K.sheetTab === 'marks' ? (s.marks.length ? s.marks.map((m, i) => '<button class="mp-row" data-bs="gomark" data-v="' + i + '"><span><span class="nm">' + esc(m.note || b.chapters[B.chapterAt(b, m.pos)].title) + '</span><span class="sub">' + esc(b.chapters[B.chapterAt(b, m.pos)].title) + ' · ' + fmt(m.pos - b.chapters[B.chapterAt(b, m.pos)].start) + '</span></span></button>').join('') : '<div class="mp-note">' + t('ab.no_bookmarks') + '</div>')
      : '<div class="mu-trs" style="margin:0 12px">' + chapterRows(b) + '</div>';
    P.openSheet('bkchap', '<div class="mp-sh"><h4>' + esc(b.title) + '</h4><span style="margin-left:auto;font-size:13px;color:var(--ink-dim)">' + t('ab.chapters_n', { n: chapN(b) }) + '</span></div>' + tabs + body);
  }
  function markSheet() {
    const b = cb(), s = BS[b.id], c = b.chapters[B.chapterAt(b, s.pos)];
    P.openSheet('bkmark', '<div class="mp-sh"><h4>' + t('ab.bookmark_add') + '</h4><span style="margin-left:auto;font-size:13px;color:var(--ink-dim)">' + esc(c.title) + ' · ' + fmt(s.pos - c.start) + '</span></div><div class="mu-shfield"><label>' + t('ab.note') + '</label><input id="bkNote" autocomplete="off" placeholder="' + esc(t('ab.note_ph')) + '"></div><button class="mu-shbtn" data-bs="savemark">' + t('ab.save') + '</button>');
    setTimeout(() => { const i = $('bkNote'); if (i) i.focus(); }, 60);
  }
  function bookMenu(id) {
    const b = B.book(id), s = BS[id];
    const row = (a, l) => '<button class="mp-row" data-bs="' + a + '" data-v="' + id + '"><span><span class="nm">' + l + '</span></span></button>';
    P.openSheet('bkmenu', '<div class="mp-sh" style="gap:12px">' + cover(b, 'sm') + '<div style="min-width:0"><h4 style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + esc(b.title) + '</h4><div style="font-size:13px;color:var(--ink-dim);margin-top:2px">' + esc(B.authorNames(b.authors)) + '</div></div></div>'
      + (started(b) || s.finished ? row('startover', t('ab.start_over')) : '') + (!s.finished ? row('finish', t('ab.mark_finished')) : row('unfinish', t('ab.mark_unfinished'))) + row('mylist', '♡ ' + (t('pm.my_list') !== 'pm.my_list' ? t('pm.my_list') : 'My List')) + row('goauthor', t('ab.go_author')));
  }
  P.sheet.addEventListener('click', e => {
    const x = e.target.closest('[data-bs]'); if (!x) return;
    const k = x.dataset.bs, v = x.dataset.v;
    if (k === 'speed') { BS[K.cur].speed = +v; P.closeSheet(); P.paintNow(); H.phToast(t('ab.speed') + ' ' + fmtSpeed(+v)); }
    else if (k === 'sleep') { if (v === 'off') K.sleep = null; else if (v === 'chapter') K.sleep = 'chapter'; else { K.sleep = +v; K.sleepLeft = +v * 60; } P.closeSheet(); P.paintNow(); }
    else if (k === 'tab') chaptersSheet(v);
    else if (k === 'gomark') { const m = BS[K.cur].marks[+v]; BS[K.cur].pos = m.pos; P.closeSheet(); P.paintNow(); }
    else if (k === 'savemark') { BS[K.cur].marks.push({ pos: BS[K.cur].pos, note: ($('bkNote').value || '').trim() }); P.closeSheet(); H.phToast('✓ ' + t('ab.bookmark_added')); }
    else if (k === 'startover') { BS[v].finished = false; BS[v].pos = 0; P.closeSheet(); if (K.cur === v) { K.playing = true; P.paintNow(); } else resume(v, 0); refresh(); }
    else if (k === 'unfinish') { BS[v].finished = false; BS[v].pos = 0; if (K.cur === v) { K.playing = false; P.paintNow(); } P.closeSheet(); refresh(); }   // R332 — back to unstarted, nothing plays
    else if (k === 'finish') { BS[v].finished = true; BS[v].pos = B.book(v).len; if (K.cur === v) { K.playing = false; P.paintNow(); } P.closeSheet(); refresh(); H.phToast('✓ ' + t('ab.finished')); }
    else if (k === 'mylist') { P.closeSheet(); H.phToast('✓ My List'); }
    else if (k === 'goauthor') { P.closeSheet(); P.closeNow(); RM.openDet('bauthor', B.book(v).authors[0]); }
  });
  P.now.addEventListener('click', e => {
    if (!active()) return;
    const x = e.target.closest('[data-nb]'); if (!x) return; e.stopPropagation();
    const k = x.dataset.nb, b = cb();
    if (k === 'pp') toggle(); else if (k === 'b30') skip(-30); else if (k === 'f30') skip(30);
    else if (k === 'chapjump') { BS[b.id].pos = b.chapters[+x.dataset.i].start; BS[b.id].finished = false; live(); }
    else if (k === 'bookseek') { const tr = x.querySelector('.trk').getBoundingClientRect(); const f = Math.max(0, Math.min(1, (e.clientX - tr.left) / tr.width)); BS[b.id].pos = Math.round(f * b.len); live(); }
    else if (k === 'speed') speedSheet(); else if (k === 'sleep') sleepSheet(); else if (k === 'chapters') chaptersSheet('chapters'); else if (k === 'mark') markSheet();
    else if (k === 'menu') bookMenu(b.id); else if (k === 'startover') { BS[b.id].finished = false; BS[b.id].pos = 0; K.playing = true; P.paintNow(); }
    else if (k === 'book') { P.closeNow(); RM.openDet('book', b.id); } else if (k === 'author') { P.closeNow(); RM.openDet('bauthor', b.authors[0]); }
  }, true);
  // long-press ±30 repeats; the chapter seek bar
  (function () {
    let rep = null;
    P.now.addEventListener('pointerdown', e => {
      if (!active()) return;
      const s = e.target.closest('[data-nb="b30"],[data-nb="f30"]'); if (s) { const d = s.dataset.nb === 'b30' ? -30 : 30; rep = setTimeout(function r() { skip(d); rep = setTimeout(r, 380); }, 500); return; }
      const tr = e.target.closest('#bkSeek .tr'); if (!tr) return; e.stopPropagation();
      const b = cb(), seekTo = ev => { const r = tr.getBoundingClientRect(), f = Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)), c = b.chapters[B.chapterAt(b, BS[b.id].pos)]; BS[b.id].pos = Math.round(c.start + f * (c.len - 1)); $('bkBub').textContent = fmt(f * c.len); $('bkBub').style.left = f * 100 + '%'; live(); };
      $('bkSeek').classList.add('drag'); seekTo(e);
      const mv = ev => seekTo(ev), up = () => { $('bkSeek') && $('bkSeek').classList.remove('drag'); window.removeEventListener('pointermove', mv); window.removeEventListener('pointerup', up); };
      window.addEventListener('pointermove', mv); window.addEventListener('pointerup', up);
    }, true);
    window.addEventListener('pointerup', () => { clearTimeout(rep); rep = null; });
  })();

  /* ---- pages ---- */
  const SORTS = ['music.sort_added', 'ab.sort_title', 'ab.sort_author', 'ab.sort_series'];
  function sorted(l) {
    if (K.sort === 1) return l.slice().sort((a, b) => a.title.localeCompare(b.title));
    if (K.sort === 2) return l.slice().sort((a, b) => B.authorNames(a.authors).localeCompare(B.authorNames(b.authors)));
    if (K.sort === 3) return l.slice().sort((a, b) => (a.series ? a.series.name : '~').localeCompare(b.series ? b.series.name : '~'));
    return l;
  }
  function contCard(b) {
    const s = BS[b.id], ci = B.chapterAt(b, s.pos), pi = B.partAt(b, s.pos);
    return '<div class="bk-cont" data-mu="bkresume" data-id="' + b.id + '"><div class="cv">' + cover(b) + '</div><div class="bd"><div class="t">' + esc(b.title) + '</div><div class="s">' + esc(B.authorNames(b.authors)) + '</div>'
      + '<div class="pr">' + ring(b, 30) + '<span class="bk-left" data-left="' + b.id + '">' + t('ab.left', { t: B.fmtH(left(b)) }) + '</span></div>'
      + '<div class="s dim">' + t('ab.chapter_n', { n: ci + 1 }) + ' · ' + t('ab.part_of', { n: pi + 1, m: b.parts.length }) + '</div></div>'
      + '<span class="bk-play">' + (K.cur === b.id && K.playing ? IC.pause : IC.play) + '</span></div>';
  }
  function bookCell(b) {
    const s = BS[b.id];
    return '<div class="mu-acard" data-mu="book" data-id="' + b.id + '"><div style="position:relative">' + cover(b) + (s.finished ? '<span class="bk-fin-b">✓</span>' : started(b) && K.shelf !== 'none' ? '<span class="bk-ring-b">' + ring(b, 26) + '</span>' : '') + '</div><div class="t">' + esc(b.title) + '</div><div class="s">' + esc(B.authorNames(b.authors)) + '</div></div>';
  }
  function shelf(inLib) {
    const l = books();
    if (!l.length) return '<div class="mu-empty">' + t('ab.empty') + '</div>';
    let h = '';
    const ip = inProgress();
    if (ip.length) h += '<div class="row"><h3>' + t('ab.continue') + '</h3>' + ip.map(contCard).join('') + '</div>';
    const authors = [...new Set(l.flatMap(b => b.authors))], series = [...new Set(l.filter(b => b.series).map(b => b.series.name))];
    const chips = (K.shelf === 'many' && (authors.length > 1 || series.length)) ? '<div class="tabs" style="padding-top:0"><div class="tab' + (!K.chip ? ' on' : '') + '" data-mu="bkchip" data-id="">' + t('ab.all_books') + '</div>' + (authors.length > 1 ? '<div class="tab' + (K.chip === 'authors' ? ' on' : '') + '" data-mu="bkchip" data-id="authors">' + t('ab.authors') + '</div>' : '') + (series.length ? '<div class="tab' + (K.chip === 'series' ? ' on' : '') + '" data-mu="bkchip" data-id="series">' + t('ab.series') + '</div>' : '') + '</div>' : '';
    let body;
    if (K.chip === 'authors') body = '<div class="mu-g3">' + authors.map(id => { const a = B.author(id), n = l.filter(b => b.authors.indexOf(id) >= 0).length; return '<div class="mu-arc" data-mu="bkauthor" data-id="' + id + '"><div class="c" style="' + (a.image ? M.artistStyle({ hue: a.hue }) : M.wordmarkStyle(a.name)) + '">' + (a.image ? '' : esc(M.initials(a.name))) + '</div><div class="t">' + esc(a.name) + '</div><div class="s">' + n + '</div></div>'; }).join('') + '</div>';
    else if (K.chip === 'series') body = series.map(sn => { const bs = l.filter(b => b.series && b.series.name === sn); return '<div class="mu-meta" style="padding-top:6px"><b style="color:var(--ink);font-size:14px">' + esc(sn) + '</b></div><div class="mu-g2">' + bs.map(b => bookCell(b).replace('<div class="t">', '<div class="t"><span style="color:var(--ink-dim)">' + b.series.n + ' · </span>')).join('') + '</div>'; }).join('');
    else body = '<div class="mu-g2">' + sorted(l).map(bookCell).join('') + '</div>';
    h += '<div class="row"><h3>' + t('ab.all_books') + '<span class="more" style="cursor:default">' + (l.length === 1 ? t('ab.books_one') : t('ab.books_n', { n: l.length })) + '</span></h3>' + chips + body + '</div>';
    return h + '<div style="height:24px"></div>';
  }
  function contRow() { const ip = inProgress(); return ip.length ? '<div class="row"><h3>' + t('ab.continue') + '</h3>' + ip.map(contCard).join('') + '</div>' : ''; }
  function homeRow() { const l = books(); if (!l.length) return ''; return '<div class="row"><h3>' + t('mnav.audiobooks') + '</h3>' + (inProgress().length ? inProgress().map(contCard).join('') : '<div class="mu-track">' + l.map(b => bookCell(b).replace('mu-acard"', 'mu-acard" style="width:142px"')).join('') + '</div>') + '</div>'; }
  function paintSort(slot) { if (!books().length || K.chip) return; slot.innerHTML = '<button class="mu-sortpill" data-mu="bksort">' + t(SORTS[K.sort]) + '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>'; }
  function bookHTML(b) {
    const s = BS[b.id], au = b.authors, narr = b.narrators;
    const act = s.finished ? t('ab.start_over') : started(b) ? t('ab.continue_from', { t: B.fmtH(s.pos) }) : t('ab.start');
    return '<div class="mu-tint" style="background:linear-gradient(180deg,hsl(' + b.hue + ' 40% 20%) 0,var(--bg) 560px);min-height:100%"><div class="mu-dtop"><button class="mu-dback" data-mu="back">‹</button>' + cover(b) + '</div><div class="mu-dbody">'
      + '<div class="mu-dt">' + esc(b.title) + '</div>' + (b.subtitle ? '<div style="font-size:15px;color:var(--ink-soft);margin-top:3px">' + esc(b.subtitle) + '</div>' : '')
      + '<div class="mu-dby">' + au.map(a => '<a data-mu="bkauthor" data-id="' + a + '">' + esc(B.author(a).name) + '</a>').join(' & ') + '</div>'
      + (narr.length ? '<div class="mu-dm">' + esc(t('ab.read_by', { narrator: narr.join(', ') })) + '</div>' : '')
      + '<div class="mu-dm"><span>' + B.fmtH(b.len) + '</span><span>·</span><span>' + t('ab.chapters_n', { n: chapN(b) }) + '</span>' + (b.series ? '<span class="mu-badge" data-mu="bkseries" style="cursor:pointer">' + esc(b.series.name) + ' · ' + t('ab.book_of', { n: b.series.n, m: b.series.of }) + '</span>' : '') + '</div>'
      + (started(b) ? '<div class="bk-prog">' + ring(b, 22) + '<span>' + t('ab.left', { t: B.fmtH(left(b)) }) + '</span></div>' : '')
      + '<div class="mu-dacts"><button class="pri" data-mu="bkresume" data-id="' + b.id + '">' + IC.play + act + '</button><button data-mu="bkmenu" data-id="' + b.id + '" style="flex:0 0 56px" aria-label="More">' + IC.more + '</button></div>'
      + (b.desc ? '<div class="mu-bio" style="-webkit-line-clamp:3">' + esc(b.desc) + '</div><span class="mu-biomore" data-mu="bkdesc" data-id="' + b.id + '">' + t('music.more') + '</span>' : '')
      + '<div class="mu-sec">' + t('ab.chapters') + '</div><div class="mu-trs">' + chapterRows(b) + '</div></div></div>';
  }
  function authorHTML(id) {
    const a = B.author(id), l = books().filter(b => b.authors.indexOf(id) >= 0);
    return '<div class="mu-ahead" style="padding-top:60px"><button class="mu-dback" data-mu="back">‹</button><div class="img" style="' + (a.image ? M.artistStyle({ hue: a.hue }) : M.wordmarkStyle(a.name)) + '">' + (a.image ? '' : esc(M.initials(a.name))) + '</div><div class="mu-dt">' + esc(a.name) + '</div><div class="mu-dm" style="justify-content:center">' + (l.length === 1 ? t('ab.books_one') : t('ab.books_n', { n: l.length })) + '</div></div>'
      + '<div class="mu-dbody">' + (a.bio ? '<div class="mu-bio">' + esc(a.bio) + '</div>' : '') + '<div class="mu-g2" style="padding:0;margin-top:18px">' + l.map(bookCell).join('') + '</div></div>';
  }
  function detHTML(d) { return d.kind === 'book' ? bookHTML(B.book(d.id)) : authorHTML(d.id); }
  function refresh() { if (MS.det) RM.paintDet(); RM.rerender(); }
  function onClick(k, el) {
    const id = el.dataset.id;
    if (k === 'book') RM.openDet('book', id);
    else if (k === 'bkauthor') RM.openDet('bauthor', id);
    else if (k === 'bkresume') { if (K.cur === id && K.playing && !el.closest('.mu-dacts')) { toggle(); refresh(); } else resume(id, BS[id].finished ? 0 : null); }
    else if (k === 'bkmenu') bookMenu(id);
    else if (k === 'bkchap') { const b = B.book(id); resume(id, b.chapters[+el.dataset.i].start); }
    else if (k === 'bkchip') { K.chip = id || null; RM.rerender(); }
    else if (k === 'bksort') { P.openSheet('bksort', '<div class="mp-sh"><h4>' + t('music.sort') + '</h4></div>' + SORTS.map((s, i) => '<button class="mp-row' + (i === K.sort ? ' on' : '') + '" data-bs2="' + i + '"><span><span class="nm">' + t(s) + '</span></span><span class="rt">' + (i === K.sort ? tick : '') + '</span></button>').join('')); }
    else if (k === 'bkdesc') P.openSheet('bkdesc', '<div class="mp-sh"><h4>' + esc(B.book(id).title) + '</h4></div><div class="mp-note" style="font-size:15px;line-height:1.6;color:var(--ink-soft);padding-bottom:10px">' + esc(B.book(id).desc) + '</div>');
    else if (k === 'bkseries') { K.shelf === 'many' && (K.chip = 'series'); RM.closeAllDet(); H.setTab('m-books'); H.render(); }
    else return false;
    return true;
  }
  P.sheet.addEventListener('click', e => { const x = e.target.closest('[data-bs2]'); if (!x) return; K.sort = +x.dataset.bs2; P.closeSheet(); RM.rerender(); });
  // the chapter list inside the sheet plays from there
  P.sheet.addEventListener('click', e => { const x = e.target.closest('[data-mu="bkchap"]'); if (!x) return; const b = B.book(x.dataset.id); BS[b.id].pos = b.chapters[+x.dataset.i].start; BS[b.id].finished = false; K.playing = true; P.closeSheet(); P.paintNow(); });

  /* ---- review panel: §M7 + book states ---- */
  const MQ = [
    ['s1', 'M7·1 · Speed on the book player', 'Here and only here — the video player keeps no speed.', [['yes', 'Yes, per book', 1], ['no', 'No speed at all']]],
    ['s3', 'M7·3 · Bookmarks', 'A glyph on the player; long-press lists them.', [['yes', 'This round', 1], ['later', 'Later']]],
    ['s4', 'M7·4 · Lock-screen side actions', 'What the platform card shows beside play/pause for a book.', [['thirty', '−30 s / +30 s', 1], ['platform', 'Previous / next']]],
    ['s5', 'M7·5 · Skip silence', 'Media3 can drop silent stretches.', [['settings', 'Settings only, off', 1], ['none', 'Not exposed']]],
  ];
  function panelQs(btn) { return '<div class="mq-sec">Part 2 · audiobooks (M7·2 is J2 above)</div>' + MQ.map(j => '<div class="mq-q"><div class="t">' + j[1] + '</div><div class="d">' + j[2] + '</div><div class="mq-opts">' + j[3].map(o => btn(j[0], o[0], o[1], o[2], q[j[0]] === o[0])).join('') + '</div></div>').join(''); }
  const PVB = [['shelf', 'Audiobooks shelf', [['progress', 'One book · in progress'], ['none', 'One book · not started'], ['many', 'Many'], ['empty', 'Empty']]],
    ['bst', 'Book player', [['play', 'Playing'], ['pause', 'Paused'], ['buf', 'Buffering'], ['sleep', 'Sleep set'], ['boundary', 'At a chapter boundary'], ['fin', 'Finished'], ['fail', 'Failure']]]];
  const pvb = { shelf: 'progress', bst: '' };
  function panelPv(btn) { return PVB.map(p => '<div class="mq-q"><div class="t">' + p[1] + '</div><div class="mq-opts">' + p[2].map(o => btn('bk:' + p[0], o[0], o[1], 0, pvb[p[0]] === o[0])).join('') + '</div></div>').join(''); }
  function panelJumps() { return [['bshelf', 'Audiobooks'], ['bbook', 'Book'], ['bnow', 'Book player'], ['bspeed', 'Speed sheet'], ['bsleep', 'Sleep sheet'], ['bchap', 'Chapters sheet'], ['bmark', 'Bookmark']].map(x => '<button data-jump="' + x[0] + '">' + x[1] + '</button>').join(''); }
  function ensureMusicMode() { if (MS.mode !== 'music') RM.setMode('music'); }
  function shelfTab() { const tb = RM.bar().find(b => b[0] === 'm-books'); if (tb) { H.setTab('m-books'); } else { const lib = RM.bar().find(b => b[0] === 'm-library' || b[0] === 'm-browse'); if (lib) { MS.libChip = 'books'; H.setTab(lib[0]); } else H.setTab(RM.firstTab()); } }
  function ensureBook() { if (!K.cur) resume(K.shelf === 'many' ? 'the-salt-road' : 'vinterfaergen'); }
  function applyPv(k, v) {
    pvb[k] = v;
    if (k === 'shelf') { P.closeSheet(); K.shelf = v; if (v === 'none') BS.vinterfaergen.pos = 0; else if (BS.vinterfaergen.pos === 0) BS.vinterfaergen.pos = 7920; if (v === 'empty' && K.cur) stop(); ensureMusicMode(); P.closeNow(); RM.closeAllDet(); shelfTab(); H.render(); }
    else if (k === 'bst') {
      ensureMusicMode(); ensureBook(); P.closeSheet(); const b = cb(), s = BS[b.id];
      K.stickyBuf = v === 'buf'; K.fail = false; K.sleep = null; s.finished = false; if (s.pos >= b.len) s.pos = 7920;
      if (v === 'buf') { K.buffering = true; K.playing = true; } else { K.buffering = false; K.playing = v !== 'pause' && v !== 'fin' && v !== 'fail'; }
      if (v === 'sleep') { K.sleep = 30; K.sleepLeft = 23 * 60 + 40; }
      if (v === 'boundary') { const ci = B.chapterAt(b, s.pos), nx = b.chapters[Math.min(ci + 1, b.chapters.length - 1)]; s.pos = nx.start - 4; }
      if (v === 'fin') { s.finished = true; s.pos = b.len; }
      P.openNow(); P.paintNow(); P.paintMini();
      if (v === 'fail') { K.fail = true; P.openSheet('bkfail', '<div class="mp-fail"><h4>' + t('music.fail_t') + '</h4><p>' + t('ab.fail_p') + '</p><div class="acts"><button class="pri" data-bs3="retry">' + t('music.try_again') + '</button><button data-bs3="close">' + t('ab.close') + '</button></div></div>'); }
    }
    P.paintPanel();
  }
  P.sheet.addEventListener('click', e => { const x = e.target.closest('[data-bs3]'); if (!x) return; P.closeSheet(); K.fail = false; if (x.dataset.bs3 === 'retry') { K.playing = true; live(); } });
  function jump(k) {
    if (k[0] !== 'b' || ['bshelf', 'bbook', 'bnow', 'bspeed', 'bsleep', 'bchap', 'bmark'].indexOf(k) < 0) return false;
    ensureMusicMode();
    if (k === 'bshelf') { P.closeNow(); RM.closeAllDet(); shelfTab(); H.render(); return true; }
    if (k === 'bbook') { P.closeNow(); RM.closeAllDet(); RM.openDet('book', K.shelf === 'many' ? 'the-salt-road' : 'vinterfaergen'); return true; }
    ensureBook(); P.openNow();
    if (k === 'bspeed') { if (q.s1 === 'no') H.phToast('M7·1 says no speed'); else speedSheet(); }
    else if (k === 'bsleep') sleepSheet(); else if (k === 'bchap') chaptersSheet('chapters'); else if (k === 'bmark') { if (q.s3 === 'later') H.phToast('M7·3 says later'); else markSheet(); }
    return true;
  }
  function active() { return !!K.cur; }

  window.RaviloBooks = { active, nowHTML, miniHTML, live, stop, skip, toggle, shelf, homeRow, contRow, paintSort, detHTML, onClick, panelQs, panelPv, panelJumps, applyPv, jump, K, BS };
  P.paintPanel();
  if (H.tab === 'm-books' || H.tab === 'm-listen') H.render();
})();
