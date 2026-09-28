/* Ravilo phone — music mode: the player (Now playing, lyrics, queue), the mini bar, the sheets,
   and the mockup-only review panel beside the phone (§J's seven questions + the unreachable states). */
(function () {
  const RM = window.RaviloMusic, M = window.MUSIC, H = window.RaviloHost;
  if (!RM || !M || !H) return;
  const MS = RM.MS, q = MS.q, G = MS.G, U = MS.util, esc = H.esc, t = (k, v) => (window.t ? window.t(k, v) : k), $ = id => document.getElementById(id);
  const screen = document.querySelector('.screen'), phone = document.querySelector('.phone');
  const IC = {
    down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
    prev: '<svg viewBox="0 0 24 24"><path d="M18 6v12l-9-6z" fill="currentColor" stroke="none"/><rect x="5" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M6 6v12l9-6z" fill="currentColor" stroke="none"/><rect x="16.6" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6.5" y="5" width="4" height="14" rx="1.3"/><rect x="13.5" y="5" width="4" height="14" rx="1.3"/></svg>',
    repeat: '<svg viewBox="0 0 24 24"><path d="M4 11V9a3 3 0 0 1 3-3h12M16 3l3 3-3 3"/><path d="M20 13v2a3 3 0 0 1-3 3H5M8 21l-3-3 3-3"/></svg>',
    lyr: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>',
    queue: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h12M4 16h7"/><path d="M16 14l5 3.2-5 3.2z"/></svg>',
    cast: '<svg viewBox="0 0 24 24"><path d="M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9.5V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.8" fill="currentColor" stroke="none"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    handle: '<svg viewBox="0 0 24 24"><path d="M5 9h14M5 15h14"/></svg>',
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
  };
  const P = { queue: [], qi: 0, pos: 0, playing: false, buffering: false, repeat: 'off', shuffle: false, ctx: '', open: false, lyrics: q.j4 === 'lyrics', cast: null, fail: false, stickyBuf: false };
  const cur = () => P.queue[P.qi] || null;
  const trk = () => M.track(cur());
  const ctxName = c => !c ? '' : c.indexOf('al:') === 0 ? M.album(c.slice(3)).title : c.indexOf('ar:') === 0 ? M.artist(c.slice(3)).name : c.indexOf('pl:') === 0 ? (MS.playlists.find(p => p.id === c.slice(3)) || {}).name : c === 'recent' ? t('mhome.recent_played') : c === 'mix' ? t('mhome.mix') : c === 'songs' ? t('mlib.songs') : c === 'srch' ? t('mnav.search') : '';
  const hasLyrics = x => !!(x && (M.LYRICS[x.id] || M.PLAIN[x.id]));

  /* ---- playback model (a stand-in clock; the product's is Media3's) ---- */
  const BK = () => (window.RaviloBooks && RaviloBooks.active() ? RaviloBooks : null);
  function playCtx(ids, i, ctx, shuffle) {
    if (!ids || !ids.length) return;
    if (BK()) BK().stop(true);
    let list = ids.slice();
    if (shuffle) { const first = list.splice(i, 1)[0]; for (let k = list.length - 1; k > 0; k--) { const j = (M.hash(list[k] + k) + Date.now()) % (k + 1); [list[k], list[j]] = [list[j], list[k]]; } list.unshift(first); i = 0; }
    P.queue = list; P.qi = i; P.ctx = ctx; P.shuffle = !!shuffle; start();
  }
  function start() {
    P.pos = 0; P.playing = true; P.fail = false; P.buffering = true; P.lyrics = q.j4 === 'lyrics' && hasLyrics(trk());
    clearTimeout(P.bt); if (!P.stickyBuf) P.bt = setTimeout(() => { P.buffering = false; paintLive(); }, 650);
    repaintAll();
  }
  function next(auto) {
    if (P.repeat === 'one' && auto) { P.pos = 0; repaintAll(); return; }
    if (P.qi < P.queue.length - 1) { P.qi++; start(); return; }
    if (P.repeat === 'all') { P.qi = 0; start(); return; }
    // queue end: the screen stays on the last song, paused at 0:00 — nothing restarts on its own
    P.pos = 0; P.playing = false; repaintAll();
  }
  function prev() { if (P.pos > 3 || P.qi === 0) { P.pos = 0; repaintAll(); return; } P.qi--; start(); }
  function stop() { if (BK()) BK().stop(true); P.queue = []; P.qi = 0; P.playing = false; closeNow(); repaintAll(); }
  setInterval(() => {
    if (!P.playing || P.buffering || !cur() || P.fail) return;
    P.pos++; if (P.pos >= trk().len) { next(true); return; }
    paintLive();
  }, 1000);

  /* ---- Now playing ---- */
  const now = document.createElement('div'); now.className = 'mu-now'; now.id = 'muNow'; screen.appendChild(now);
  const lyrLines = x => M.LYRICS[x.id] || null;
  function lyricsHTML(x) {
    const L = lyrLines(x);
    if (L) return '<div class="mu-lyr" id="muLyr">' + L.map(l => '<p data-mu-seek="' + l[0] + '">' + esc(l[1]) + '</p>').join('') + '</div>';
    return '<div class="mu-lyr plain" id="muLyr"><p>' + esc(M.PLAIN[x.id] || '') + '</p></div>';
  }
  function seekHTML(x) { return '<div class="mu-seek" id="muSeek"><div class="tr"><span class="dn" id="muDn"></span><span class="kb" id="muKb"></span></div><div class="bub" id="muBub">0:00</div><div class="tm"><span id="muPos">0:00</span><span id="muLeft">−' + M.fmtLen(x.len) + '</span></div></div>'; }
  function transportHTML() {
    const end = P.qi >= P.queue.length - 1 && P.repeat !== 'all';
    return '<div class="mu-tp"><button class="mu-ib sm' + (P.shuffle ? ' on' : '') + '" data-np="shuffle" aria-label="' + t('music.shuffle') + '">' + G.shuffle + '</button>'
      + '<button class="mu-ib fill" data-np="prev" aria-label="Previous">' + IC.prev + '</button>'
      + '<button class="mu-pp" data-np="pp" id="muPP" aria-label="Play">' + ppGlyph() + '</button>'
      + '<button class="mu-ib fill' + (end ? ' ghost' : '') + '" data-np="next" aria-label="Next">' + IC.next + '</button>'
      + '<button class="mu-ib sm' + (P.repeat !== 'off' ? ' on' : '') + '" data-np="repeat" aria-label="' + t('music.repeat_' + P.repeat) + '">' + IC.repeat + (P.repeat === 'one' ? '<span class="one">1</span>' : '') + '</button></div>';
  }
  const ppGlyph = () => P.buffering ? '<span class="mp-pulse"><i></i><i></i><i></i></span>' : (P.playing ? IC.pause : IC.play);
  function metaHTML(x, a) {
    const long = x.title.length > 24;
    return '<div class="mu-nmeta"><div class="b"><div class="mu-mq' + (long ? ' long' : '') + '"><span>' + esc(x.title) + '</span></div>'
      + '<div class="mu-nsub"><a data-np="artist">' + esc(M.artistNames(x.artistIds)) + '</a> · <a data-np="album">' + esc(a.title) + '</a></div>'
      + (P.cast ? '<div class="mu-ndev"><span class="rc-ic"><span class="bx"></span><span class="fl"></span></span>' + esc(t('music.playing_on', { d: P.cast })) + '</div>' : '') + '</div>'
      + '<button class="mu-ib" data-np="fav" style="color:' + (MS.favs.has(x.id) ? 'var(--accent)' : 'var(--ink-soft)') + '" aria-label="My List">' + (MS.favs.has(x.id) ? IC.heart.replace('fill="none"', 'fill="currentColor"') : IC.heart) + '</button></div>';
  }
  function smallHead(x, a) { return '<div class="mu-lhead">' + U.cover(a, 'sm') + '<div class="b"><div class="t">' + esc(x.title) + '</div><div class="s">' + esc(M.artistNames(x.artistIds)) + '</div>' + (P.cast ? '<div class="mu-ndev"><span class="rc-ic"><span class="bx"></span><span class="fl"></span></span>' + esc(t('music.playing_on', { d: P.cast })) + '</div>' : '') + '</div></div>'; }
  function bottomHTML(x) {
    return '<div class="mu-nbot">' + (hasLyrics(x) ? '<button class="mu-ib' + (P.lyrics ? ' on' : '') + '" data-np="lyrics" aria-label="' + t('music.lyrics') + '">' + IC.lyr + '</button>' : '<span class="mu-ib ghost"></span>')
      // owner 2026-09-28: with a Queue tab in the bar the button is dropped (it opened the same list); bars without one keep it
      + (RM.bar().some(x => x[0] === 'm-queue') ? '' : '<button class="mu-ib' + (q.j4 === 'queue' && !P.lyrics ? ' on' : '') + '" data-np="queue" aria-label="' + t('music.queue') + '">' + IC.queue + '</button>')
      + '<button class="mu-ib' + (P.cast ? ' on' : '') + '" data-np="cast" aria-label="Cast">' + IC.cast + '</button>'
      + '<button class="mu-ib" data-np="menu" aria-label="More">' + IC.more + '</button></div>';
  }
  function nowHTML() {
    const x = trk(); if (!x) return '';
    const a = M.album(x.albumId), dir = q.j4, land = phone.classList.contains('land');
    const tint = 'background:radial-gradient(120% 70% at 50% 18%, hsl(' + a.hue + ' 45% 26%), var(--bg) 78%)';
    const top = '<div class="mu-ntop"><button class="mu-ib" data-np="close" aria-label="Close">' + IC.down + '</button><div class="ctx">' + (q.j3 === 'draw' ? '♪ ' : '') + t('music.from', { x: '' }).replace(/\s*$/, '') + '<b>' + esc(ctxName(P.ctx)) + '</b></div><button class="mu-ib" data-np="menu" aria-label="More">' + IC.more + '</button></div>';
    const cov = '<div class="mu-ncov" id="muCov">' + U.cover(a) + '</div>';
    let body;
    if (land) {
      const right = P.lyrics && hasLyrics(x) ? smallHead(x, a) + lyricsHTML(x) : metaHTML(x, a) + seekHTML(x) + transportHTML();
      body = '<button class="mu-ib mu-lclose" data-np="close" aria-label="Close">' + IC.down + '</button><div class="mu-nland">' + cov + '<div class="right">' + right + (P.lyrics ? seekHTML(x) + transportHTML() : '') + bottomHTML(x) + '</div></div>';
      return '<div class="bgt" style="' + tint + '"></div>' + body;
    }
    if (P.lyrics && hasLyrics(x)) body = smallHead(x, a) + lyricsHTML(x) + seekHTML(x) + transportHTML();
    else if (dir === 'queue') body = '<div class="mu-qhead">' + U.cover(a) + '<div style="min-width:0;flex:1">' + metaHTML(x, a).replace('mu-nmeta', 'mu-nmeta" style="margin-top:0') + '</div></div><div class="mu-qbody" id="muQBody">' + queueListHTML(true) + '</div>' + seekHTML(x) + transportHTML();
    else body = '<div class="mu-nspacer"></div>' + cov + metaHTML(x, a) + seekHTML(x) + transportHTML() + '<div class="mu-nspacer"></div>';
    return '<div class="bgt" style="' + tint + '"></div>' + top + '<div class="mu-nmain">' + body + '</div>' + bottomHTML(x);
  }
  function openNow() { if (!cur() && !BK()) return; if (MS.mode === 'music' && RM.bar().some(b => b[0] === 'm-now')) { H.setTab('m-now'); H.render(); return; } P.open = true; now.classList.add('on'); paintNow(); paintMini(); }
  function closeNow() { P.open = false; P.tabbed = false; now.classList.remove('on', 'tabbed'); now.style.transform = ''; now.style.bottom = ''; paintMini(); }
  /* the Now playing TAB (owner, 2026-09-27): the same screen as a page, the bar stays under it. Nothing
     playing ⇒ whatever was played last, loaded and paused where it was left. */
  function openTab() {
    if (!cur() && !BK()) {
      const rp = MS.size === 'empty' ? [] : MS.util.recentPlayed();
      if (rp.length) { const x = rp[0], ids = M.tracksOf(x.albumId).map(y => y.id); P.queue = ids; P.qi = Math.max(0, ids.indexOf(x.id)); P.ctx = 'al:' + x.albumId; P.pos = Math.min(84, x.len - 1); P.playing = false; P.buffering = false; P.fail = false; }
    }
    P.tabbed = true; P.open = true; now.classList.add('on', 'tabbed');
    const bn = document.getElementById('bnav'); now.style.bottom = (bn ? bn.offsetHeight : 74) + 'px';
    if (!cur() && !BK()) { now.innerHTML = '<div class="mu-empty" style="margin-top:45%">' + t('music.nothing_played') + '</div>'; paintMini(); return; }
    paintNow(); paintMini();
  }
  document.getElementById('bnav').addEventListener('click', e => { const b = e.target.closest('[data-tab]'); if (b && P.tabbed && b.dataset.tab !== 'm-now') closeNow(); }, true);
  window.addEventListener('resize', () => { if (P.tabbed) { const bn = document.getElementById('bnav'); now.style.bottom = (bn ? bn.offsetHeight : 74) + 'px'; } });
  function paintNow() { if (!P.open) return; if (BK()) { now.innerHTML = BK().nowHTML(); BK().live(true); return; } now.innerHTML = nowHTML(); paintLive(true); }
  function paintLive(jump) {
    if (BK()) return BK().live(jump);
    const x = trk(); if (!x) return;
    const pct = Math.min(100, P.pos / x.len * 100);
    if (P.open) {
      const dn = $('muDn'), kb = $('muKb'); if (dn) { dn.style.width = pct + '%'; kb.style.left = pct + '%'; $('muPos').textContent = M.fmtLen(P.pos); $('muLeft').textContent = '−' + M.fmtLen(x.len - P.pos); }
      const pp = $('muPP'); if (pp) pp.innerHTML = ppGlyph();
      const L = lyrLines(x), box = $('muLyr');
      if (L && box && !box.classList.contains('plain')) {
        let k = 0; L.forEach((l, i) => { if (l[0] <= P.pos) k = i; });
        [...box.children].forEach((p, i) => { p.classList.toggle('on', i === k); p.classList.toggle('past', i < k); });
        const on = box.children[k]; if (on && !P.userScroll) box.scrollTo({ top: on.offsetTop - box.clientHeight * 0.42, behavior: jump ? 'auto' : 'smooth' });
      }
    }
    const mi = $('muMiniBar'); if (mi) mi.style.width = pct + '%';
    const mpp = $('muMiniPP'); if (mpp) mpp.innerHTML = P.buffering ? '<span class="mu-pulse-s"><i></i><i></i><i></i></span>' : (P.playing ? IC.pause : IC.play);
    if (RM.MS.det) document.querySelectorAll('#muDsc .mu-bars').forEach(b => b.classList.toggle('paused', !P.playing));
  }

  /* ---- the mini bar ---- */
  const mini = document.createElement('div'); mini.className = 'mu-mini'; mini.id = 'muMini'; screen.appendChild(mini);
  function navShown() {
    const d = $('detail'), mp = $('mp'), rr = $('rcRem');
    if (MS.mode === 'music' && !(mp && mp.classList.contains('on')) && !(rr && rr.classList.contains('on'))) return true; // the bar is always there in music mode
    return !(MS.det || (d && d.classList.contains('on')) || (mp && mp.classList.contains('on')) || (rr && rr.classList.contains('on')));
  }
  function paintMini() {
    const bk = BK(), x = trk(), show = (!!x || !!bk) && !P.open;
    mini.classList.toggle('on', show); mini.classList.remove('gone'); mini.style.transform = '';
    screen.classList.toggle('mu-mini-on', show);
    mini.classList.toggle('abovenav', navShown());
    if (show && bk) { mini.innerHTML = bk.miniHTML(); bk.live(); return; }
    screen.classList.toggle('mu-mini-on', show);
    mini.classList.toggle('abovenav', navShown());
    if (!show) return;
    const a = M.album(x.albumId);
    mini.innerHTML = '<div class="pg"><i id="muMiniBar"></i></div>' + (q.j5 === 'swipe' ? '<span class="gr"></span>' : '') + U.cover(a, 'sm')
      + '<div class="tx"><div class="n">' + esc(x.title) + '</div><div class="d">' + esc(M.artistNames(x.artistIds)) + '' + '</div></div>'
      + '<button id="muMiniPP" data-mini="pp" aria-label="Play or pause"></button>'
      + (P.qi < P.queue.length - 1 || P.repeat === 'all' ? '<button data-mini="next" aria-label="Next">' + IC.next.replace(/ fill="currentColor" stroke="none"/g, '') + '</button>' : '')
      + (q.j5 === 'x' ? '<button class="x" data-mini="stop" aria-label="' + t('music.stop') + '">' + IC.x + '</button>' : '');
    if (window.RaviloSpeakers) RaviloSpeakers.afterMini(mini);
    paintLive();
  }
  new MutationObserver(() => paintMini()).observe($('detail'), { attributes: true, attributeFilter: ['class'] });
  ['mp', 'rcRem'].forEach(id => { const el = $(id); if (el) new MutationObserver(() => mini.classList.toggle('abovenav', navShown())).observe(el, { attributes: true, attributeFilter: ['class'] }); });
  (function miniGestures() {
    let y0 = null, x0 = 0, dy = 0, moved = false;
    mini.addEventListener('pointerdown', e => { if (e.target.closest('button')) return; y0 = e.clientY; x0 = e.clientX; dy = 0; moved = false; mini.setPointerCapture(e.pointerId); });
    mini.addEventListener('pointermove', e => { if (y0 == null) return; dy = e.clientY - y0; if (Math.abs(dy) > 6 || Math.abs(e.clientX - x0) > 6) moved = true; if (q.j5 === 'swipe' && dy > 0) mini.style.transform = 'translateY(' + dy + 'px)'; mini.style.opacity = q.j5 === 'swipe' ? String(Math.max(.3, 1 - dy / 120)) : ''; });
    mini.addEventListener('pointerup', () => { if (y0 == null) return; y0 = null; mini.style.opacity = '';
      if (q.j5 === 'swipe' && dy > 40) { mini.classList.add('gone'); setTimeout(stop, 200); H.phToast(t('music.stop')); return; }
      mini.style.transform = ''; if (!moved) { if (RM.bar().some(b => b[0] === 'm-now')) { H.setTab('m-now'); H.render(); } else openNow(); } });
    mini.addEventListener('click', e => { const b = e.target.closest('[data-mini]'); if (!b) return; e.stopPropagation();
      if (BK() && (b.dataset.mini === 'pp' || b.dataset.mini === 'back30')) { b.dataset.mini === 'pp' ? BK().toggle() : BK().skip(-30); return; }
      if (b.dataset.mini === 'pp') { P.playing = !P.playing; paintLive(); RM.MS.det && RM.paintDet(); }
      else if (b.dataset.mini === 'next') next(); else if (b.dataset.mini === 'stop') stop(); });
  })();

  /* ---- sheets ---- */
  const scrim = document.createElement('div'); scrim.className = 'mp-scrim'; scrim.style.zIndex = '90';
  const sheet = document.createElement('div'); sheet.className = 'mp-sheet'; sheet.id = 'muSheet'; sheet.style.zIndex = '92';
  screen.appendChild(scrim); screen.appendChild(sheet);
  let sheetKind = null;
  function openSheet(kind, html) { sheetKind = kind; sheet.innerHTML = '<div class="mp-grab"></div>' + html; sheet.classList.add('on'); scrim.classList.add('on'); }
  function closeSheet() { sheetKind = null; sheet.classList.remove('on'); scrim.classList.remove('on'); }
  scrim.addEventListener('click', closeSheet);
  const rowB = (act, ic, label, sub, extra) => '<button class="mp-row" data-ms="' + act + '"' + (extra || '') + '><span style="width:22px;height:22px;flex:none;display:flex;color:var(--ink-soft)">' + ic + '</span><span><span class="nm">' + label + '</span>' + (sub ? '<span class="sub">' + sub + '</span>' : '') + '</span></button>';
  const S = { next: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h10M4 12h10M4 17h6"/><path d="M17 10v8M13 14h8"/></svg>', q: IC.queue.replace('<svg', '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'), pl: G.playlists.replace('<svg', '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'),
    al: G.albums.replace('<svg', '<svg fill="none" stroke="currentColor" stroke-width="2"'), ar: G.artists.replace('<svg', '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'), heart: IC.heart };
  let menuId = null;
  function menu(id) {
    menuId = id; const x = M.track(id), a = M.album(x.albumId), fav = MS.favs.has(id);
    openSheet('menu', '<div class="mp-sh" style="gap:12px">' + U.cover(a, 'sm') + '<div style="min-width:0"><h4 style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + esc(x.title) + '</h4><div style="font-size:13px;color:var(--ink-dim);margin-top:2px">' + esc(M.artistNames(x.artistIds)) + ' — ' + esc(a.title) + '</div></div></div>'
      + rowB('playnext', S.next, t('music.play_next')) + rowB('addq', S.q, t('music.add_queue'))
      + (q.j7 === 'flow' ? rowB('addpl', S.pl, t('music.add_playlist')) : '<div class="mu-row-ph">' + rowB('addpl', S.pl, t('music.add_playlist'), 'phase 2') + '</div>')
      + rowB('goal', S.al, t('music.go_album')) + (a.artistId !== 'various' || x.artistIds.length ? rowB('goar', S.ar, t('music.go_artist')) : '')
      + rowB('fav', fav ? S.heart.replace('fill="none"', 'fill="currentColor"') : S.heart, (fav ? '✓ ' : '♡ ') + (window.t ? (t('pm.my_list') !== 'pm.my_list' ? t('pm.my_list') : 'My List') : 'My List')));
    if (window.RaviloSpeakers) RaviloSpeakers.afterMenu(id);
  }
  function queueRow(id, i, isNow) {
    const x = M.track(id), a = M.album(x.albumId);
    return '<div class="mu-qrow' + (isNow ? ' now' : '') + '" data-qi="' + i + '">' + (isNow ? '' : '<div class="rm">Remove</div>') + '<div class="in">' + U.cover(a, 'sm') + '<div class="b"><div class="t">' + esc(x.title) + '</div><div class="s">' + esc(M.artistNames(x.artistIds)) + '</div></div>'
      + (isNow ? '<span class="mu-bars' + (P.playing ? '' : ' paused') + '" style="margin-right:16px"><i></i><i></i><i></i></span>' : '<span class="mu-handle" data-qh="' + i + '">' + IC.handle + '</span>') + '</div></div>';
  }
  function queueListHTML(compact) {
    const rest = P.queue.slice(P.qi + 1);
    return (compact ? '' : '<div class="mu-qlab">' + t('music.now_playing') + '</div>') + (cur() ? queueRow(cur(), P.qi, true) : '')
      + (rest.length ? '<div class="mu-qlab">' + t('music.up_next') + '</div>' + rest.map((id, k) => queueRow(id, P.qi + 1 + k, false)).join('') : '')
      + (rest.length && !compact ? '<button class="mu-qclear" data-ms="clearq">' + t('music.clear_queue') + '</button>' : '');
  }
  function leftLabel() {
    const x = trk(); if (!x) return '';
    const left = (x.len - P.pos) + P.queue.slice(P.qi + 1).reduce((s, id) => s + M.track(id).len, 0);
    return t('music.queue_left', { n: P.queue.length - P.qi, t: M.fmtTotal(left) });
  }
  function queueSheet() { openSheet('queue', '<div class="mp-sh"><h4>' + t('music.queue') + '</h4><span style="margin-left:auto;font-size:13px;color:var(--ink-dim)">' + leftLabel() + '</span></div><div id="muQList">' + queueListHTML(false) + '</div>'); wireQueue($('muQList')); }
  function queuePage() {
    if (!cur()) return '<div class="mu-empty">' + t('music.up_next') + ' —</div>';
    setTimeout(() => wireQueue($('muQPage')), 0);
    return '<div class="row" style="margin-top:6px"><h3>' + t('music.queue') + '<span class="more" style="cursor:default">' + leftLabel() + '</span></h3></div><div id="muQPage">' + queueListHTML(false) + '</div>';
  }
  function wireQueue(root) {
    if (!root || root.dataset.w) return; root.dataset.w = '1';
    let drag = null, sw = null;
    root.addEventListener('pointerdown', e => {
      const h = e.target.closest('[data-qh]'), r = e.target.closest('.mu-qrow');
      if (h) { drag = { row: h.closest('.mu-qrow') }; drag.row.classList.add('dragging'); root.setPointerCapture(e.pointerId); e.preventDefault(); return; }
      if (r && !r.classList.contains('now')) sw = { row: r, x0: e.clientX, dx: 0 };
    });
    root.addEventListener('pointermove', e => {
      if (drag) { const rows = [...root.querySelectorAll('.mu-qrow:not(.now)')]; const over = rows.find(r => { const b = r.getBoundingClientRect(); return e.clientY > b.top && e.clientY < b.bottom; });
        if (over && over !== drag.row) { const b = over.getBoundingClientRect(); over.parentNode.insertBefore(drag.row, e.clientY < b.top + b.height / 2 ? over : over.nextSibling); } return; }
      if (sw) { sw.dx = Math.min(0, e.clientX - sw.x0); if (sw.dx < -6) sw.row.querySelector('.in').style.transform = 'translateX(' + sw.dx + 'px)'; }
    });
    const end = () => {
      if (drag) { drag.row.classList.remove('dragging'); const order = [...root.querySelectorAll('.mu-qrow:not(.now)')].map(r => P.queue[+r.dataset.qi]); P.queue = P.queue.slice(0, P.qi + 1).concat(order); drag = null; refreshQueue(); paintMini(); return; }
      if (sw) { const r = sw.row; if (sw.dx < -110) { P.queue.splice(+r.dataset.qi, 1); refreshQueue(); paintMini(); } else r.querySelector('.in').style.transform = ''; sw = null; }
    };
    root.addEventListener('pointerup', end); root.addEventListener('pointercancel', end);
  }
  function refreshQueue() {
    const l = $('muQList'); if (l && sheetKind === 'queue') { l.innerHTML = queueListHTML(false); const h = sheet.querySelector('.mp-sh span'); if (h) h.textContent = leftLabel(); }
    const b = $('muQBody'); if (b) { b.innerHTML = queueListHTML(true); }
    const pg = $('muQPage'); if (pg) pg.innerHTML = queueListHTML(false);
  }
  function sortSheet() {
    const K = ['music.sort_added', 'music.sort_az', 'music.sort_year', 'music.sort_played'];
    openSheet('sort', '<div class="mp-sh"><h4>' + t('music.sort') + '</h4></div>' + K.map((k, i) => '<button class="mp-row' + (i === MS.libSort ? ' on' : '') + '" data-ms="sort" data-i="' + i + '"><span><span class="nm">' + t(k) + '</span></span><span class="rt">' + (i === MS.libSort ? '<svg viewBox="0 0 24 24"><path d="M4 12.5l5 5L20 6.5"/></svg>' : '') + '</span></button>').join(''));
  }
  function bioSheet(id) { const r = M.artist(id); openSheet('bio', '<div class="mp-sh"><h4>' + esc(r.name) + '</h4></div><div class="mp-note" style="font-size:15px;line-height:1.6;color:var(--ink-soft);padding-bottom:10px">' + esc(r.bio) + '</div>'); }
  function newPlaylist(addId) {
    openSheet('newpl', '<div class="mp-sh"><h4>' + t('music.new_playlist') + '</h4>' + (q.j7 === 'two' ? '<span class="mu-ph2">phase 2</span>' : '') + '</div><div class="mu-shfield"><label>' + t('music.name') + '</label><input id="muPlName" autocomplete="off"></div>'
      + '<button class="mu-shbtn" data-ms="createpl"' + (addId ? ' data-add="' + addId + '"' : '') + '>' + t('music.create') + '</button>'
      + (q.j7 === 'two' ? '<div class="mp-note">Drawn once so the shape is fixed. Playlists are Jellyfin’s own, so they show up in every Jellyfin client.</div>' : ''));
    setTimeout(() => { const i = $('muPlName'); if (i) i.focus(); }, 60);
  }
  function addToPlaylist(id) {
    openSheet('addpl', '<div class="mp-sh"><h4>' + t('music.add_playlist').replace('…', '') + '</h4></div>' + MS.playlists.map(p => '<button class="mp-row" data-ms="topl" data-pl="' + p.id + '"><span><span class="nm">' + esc(p.name) + '</span><span class="sub">' + U.songsN(p.ids.length) + '</span></span></button>').join('')
      + '<button class="mp-row" data-ms="newpl-add"><span><span class="nm">＋ ' + t('music.new_playlist') + '</span></span></button>');
  }
  function failSheet() { openSheet('fail', '<div class="mp-fail"><h4>' + t('music.fail_t') + '</h4><p>' + t('music.fail_p') + '</p><div class="acts"><button class="pri" data-ms="retry">' + t('music.try_again') + '</button><button data-ms="skip">' + t('music.skip') + '</button></div></div>'); }
  sheet.addEventListener('click', e => {
    const b = e.target.closest('[data-ms]'); if (!b) return;
    const k = b.dataset.ms, x = menuId && M.track(menuId);
    if (k === 'playnext') { P.queue.splice(P.qi + 1, 0, menuId); if (P.queue.length === 1) start(); closeSheet(); H.phToast(t('music.play_next')); paintMini(); }
    else if (k === 'addq') { P.queue.push(menuId); if (P.queue.length === 1) start(); closeSheet(); H.phToast(t('music.add_queue')); paintMini(); }
    else if (k === 'addpl') { if (q.j7 === 'flow') addToPlaylist(menuId); else { closeSheet(); H.phToast(t('music.add_playlist') + ' · phase 2'); } }
    else if (k === 'goal') { closeSheet(); closeNow(); RM.openDet('album', x.albumId); }
    else if (k === 'goar') { closeSheet(); closeNow(); RM.openDet('artist', x.artistIds[0] === 'various' ? M.album(x.albumId).artistId : x.artistIds[0]); }
    else if (k === 'fav') { MS.favs.has(menuId) ? MS.favs.delete(menuId) : MS.favs.add(menuId); closeSheet(); H.phToast(MS.favs.has(menuId) ? '✓ My List' : 'Removed from My List'); paintNow(); }
    else if (k === 'clearq') { P.queue = P.queue.slice(0, P.qi + 1); refreshQueue(); paintMini(); }
    else if (k === 'sort') { MS.libSort = +b.dataset.i; closeSheet(); RM.rerender(); }
    else if (k === 'createpl') { const n = ($('muPlName').value || '').trim() || t('music.new_playlist');
      if (q.j7 === 'two') { closeSheet(); H.phToast('Phase 2 — the shape is drawn, creating is not built yet'); return; }
      const p = { id: 'p' + Date.now(), name: n, ids: b.dataset.add ? [b.dataset.add] : [] }; MS.playlists.push(p); closeSheet(); H.phToast('✓ ' + n); RM.rerender(); }
    else if (k === 'topl') { const p = MS.playlists.find(p => p.id === b.dataset.pl); if (p.ids.indexOf(menuId) < 0) p.ids.push(menuId); closeSheet(); H.phToast('✓ ' + p.name); }
    else if (k === 'newpl-add') newPlaylist(menuId);
    else if (k === 'retry') { closeSheet(); P.fail = false; start(); }
    else if (k === 'skip') { closeSheet(); P.fail = false; next(); }
  });

  /* ---- Now playing events + gestures ---- */
  now.addEventListener('click', e => {
    const ls = e.target.closest('[data-mu-seek]'); if (ls && !ls.closest('.plain')) { P.pos = +ls.dataset.muSeek; P.userScroll = false; paintLive(); return; }
    const qr = e.target.closest('.mu-qrow:not(.now)'); if (qr && !e.target.closest('[data-qh]') && e.target.closest('#muQBody')) { P.qi = +qr.dataset.qi; start(); return; }
    const b = e.target.closest('[data-np]'); if (!b) return;
    const k = b.dataset.np, x = trk();
    if (k === 'close') closeNow();
    else if (k === 'pp') { if (P.buffering) return; P.playing = !P.playing; paintLive(); }
    else if (k === 'next') next(); else if (k === 'prev') prev();
    else if (k === 'shuffle') { P.shuffle = !P.shuffle; paintNow(); H.phToast(t('music.shuffle') + (P.shuffle ? ' ✓' : ' ✕')); }
    else if (k === 'repeat') { P.repeat = P.repeat === 'off' ? 'all' : P.repeat === 'all' ? 'one' : 'off'; paintNow(); paintMini(); H.phToast(t('music.repeat_' + P.repeat)); }
    else if (k === 'lyrics') { P.lyrics = !P.lyrics; P.userScroll = false; paintNow(); }
    else if (k === 'queue') { if (q.j4 === 'queue' && P.lyrics) { P.lyrics = false; paintNow(); } else queueSheet(); }
    else if (k === 'cast') { P.cast = P.cast ? null : 'Stue TV'; paintNow(); paintMini(); H.phToast(P.cast ? t('music.playing_on', { d: P.cast }) : 'This phone'); }
    else if (k === 'menu') menu(cur());
    else if (k === 'artist') { closeNow(); RM.openDet('artist', x.artistIds[0]); }
    else if (k === 'album') { closeNow(); RM.openDet('album', x.albumId); }
    else if (k === 'fav') { MS.favs.has(x.id) ? MS.favs.delete(x.id) : MS.favs.add(x.id); paintNow(); }
  });
  now.addEventListener('wheel', e => { if (e.target.closest('#muLyr')) P.userScroll = true; }, { passive: true });
  (function nowGestures() {
    let g = null;
    now.addEventListener('pointerdown', e => {
      if (e.target.closest('button, a, .mu-qbody, [data-mu-seek]')) return;
      const sk = e.target.closest('#muSeek .tr');
      if (sk) { g = { seek: true, el: sk }; seekTo(e); $('muSeek').classList.add('drag'); now.setPointerCapture(e.pointerId); return; }
      if (e.target.closest('#muLyr')) { P.userScroll = true; return; }
      g = { x0: e.clientX, y0: e.clientY, dx: 0, dy: 0, axis: null, cov: !!e.target.closest('#muCov') }; now.setPointerCapture(e.pointerId);
    });
    function seekTo(e) { const r = g.el.getBoundingClientRect(), x = trk(); const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); P.pos = Math.round(f * x.len); const bub = $('muBub'); if (bub) { bub.textContent = M.fmtLen(P.pos); bub.style.left = (f * 100) + '%'; } paintLive(true); }
    now.addEventListener('pointermove', e => {
      if (!g) return; if (g.seek) { seekTo(e); return; }
      g.dx = e.clientX - g.x0; g.dy = e.clientY - g.y0;
      if (!g.axis && (Math.abs(g.dx) > 8 || Math.abs(g.dy) > 8)) g.axis = Math.abs(g.dx) > Math.abs(g.dy) ? 'x' : 'y';
      if (g.axis === 'y' && g.dy > 0) now.style.transform = 'translateY(' + g.dy + 'px)';
      if (g.axis === 'x' && g.cov) { const c = $('muCov'); if (c) c.style.transform = 'translateX(' + g.dx + 'px) rotate(' + (g.dx / 40) + 'deg)'; }
    });
    const up = () => {
      if (!g) return; const s = g; g = null;
      if (s.seek) { const sk = $('muSeek'); if (sk) sk.classList.remove('drag'); return; }
      if (s.axis === 'y') { if (s.dy > 120 && !P.tabbed) closeNow(); else now.style.transform = ''; return; }
      if (s.axis === 'x' && s.cov) { const c = $('muCov'); if (!c) return;
        if (Math.abs(s.dx) > 70 && (s.dx < 0 ? (P.qi < P.queue.length - 1 || P.repeat === 'all') : true)) { c.style.transform = 'translateX(' + (s.dx < 0 ? -420 : 420) + 'px)'; c.style.opacity = '0'; setTimeout(() => { s.dx < 0 ? next() : prev(); }, 180); }
        else c.style.transform = ''; }
    };
    now.addEventListener('pointerup', up); now.addEventListener('pointercancel', up);
  })();

  function repaintAll() { paintNow(); paintMini(); if (RM.MS.det) RM.paintDet(); if (H.tab.indexOf('m-') === 0 && !P.open) { const sc = H.scroll.scrollTop; RM.rerender(); H.scroll.scrollTop = sc; } refreshQueue(); }
  MS.P = { cur, playing: () => P.playing, playCtx, paintMini, openNow, closeNow, paintNow, menu, queuePage, sortSheet, bioSheet, newPlaylist, stop, repaintAll, state: P,
    openTab, openSheet, closeSheet, sheet, now, silenceMusic: () => { P.queue = []; P.qi = 0; P.playing = false; }, paintPanel: () => paintPanel() };

  /* ================= review panel (mockup only; the product has none of this) ================= */
  const J = [
    ['j1', 'J1 · The switch', 'Where films ⇄ music lives. Absent — never greyed — without a music library.', [['row', 'A row in Account'], ['card', 'Mode card', 1], ['top', 'Top-row segment']]],
    ['j2', 'J2 · The bar in music mode', 'Owner, 2026-09-27: Listen · Browse · Playing · Queue — Search merged into Browse, so Playing sits in the middle; the screen itself as a page, nothing playing ⇒ the last song, paused. Audiobooks and Playlists are Browse chips. Profile stays last — it is the way back.', [['cnp', 'Listen · Browse(+Search) · Playing · Queue', 1], ['cnp6', 'Listen · Browse · Playing · Search · Queue'], ['np', 'Home · Library · Now playing · Audiobooks'], ['a', 'Home · Library · Search · Audiobooks'], ['ap', '(a′) … · Playlists, books a chip'], ['b', 'Albums & Artists as tabs'], ['c', 'Listen · Browse · Search · Queue']]],
    ['j3', 'J3 · ♪ beside the brand', 'The only reminder of which app you are in on the player.', [['draw', 'Draw it', 1], ['none', 'Don’t']]],
    ['j4', 'J4 · Now playing', 'Lyrics are a state of whichever wins.', [['cover', 'Cover-first', 1], ['lyrics', 'Lyrics-first'], ['queue', 'Queue-first']]],
    ['j5', 'J5 · Mini bar dismissal', 'The cast bar never dismisses; music is different.', [['swipe', 'Swipe down stops', 1], ['x', 'A ✕ that stops'], ['never', 'No dismissal']]],
    ['j6', 'J6 · Even out volume', 'Album gain on an album, track gain on a mix.', [['on', 'In Settings, on', 1], ['none', 'Not exposed']]],
    ['j7', 'J7 · Playlists this round', 'Brokered to Jellyfin’s own playlists.', [['two', 'Two states only', 1], ['flow', 'Whole flow now']]],
  ];
  const PV = [
    ['granted', 'Account', [['1', 'Has music'], ['0', 'No music library']]],
    ['size', 'Library', [['household', 'Household (no Mix)'], ['large', 'Larger (Mix row)'], ['empty', 'Empty']]],
    ['pls', 'Playlists', [['0', 'None yet'], ['2', 'Two']]],
    ['pst', 'Player', [['play', 'Playing'], ['pause', 'Paused'], ['buf', 'Buffering'], ['nocov', 'No cover'], ['end', 'Queue end'], ['fail', 'Failure'], ['cast', 'Casting']]],
    ['land', 'Orientation', [['0', 'Portrait'], ['1', 'Landscape']]],
    ['castbar', 'A film is casting too', [['0', 'No'], ['1', 'Stack the bars']]],
    ['skin', 'Skin', [['aurora', 'Aurora'], ['midnight', 'Midnight'], ['noir', 'Noir']]],
  ];
  const pv = { granted: MS.granted ? '1' : '0', size: MS.size, pls: '0', pst: 'play', land: '0', castbar: '0', skin: document.documentElement.getAttribute('data-skin') || 'aurora' };
  const panel = document.createElement('div'); panel.className = 'muq'; document.body.appendChild(panel);
  if (window.innerWidth > 1000) document.body.classList.add('muq-open');
  const btn = (grp, v, l, lean, on) => '<button data-g="' + grp + '" data-v="' + v + '" class="' + (on ? 'on' : '') + '">' + l + (lean ? '<span class="ln">lean</span>' : '') + '</button>';
  function paintPanel() {
    panel.innerHTML = ''; panel.hidden = true; panel.style.display = 'none'; return; // owner 2026-09-28: every §J / §M7 question decided — the review panel is gone
    panel.innerHTML = '<div class="mq-h" data-toggle="1"><b>♪ Music & audiobooks · round 1</b><span>brief 2026-09-27 §J + §M7 · mockup only</span><span style="margin-left:auto">' + (document.body.classList.contains('muq-open') ? '›' : '‹') + '</span></div><div class="mq-body">'
      + '<div class="mq-sec">Round-1 questions · lean marked</div>' + J.map(j => '<div class="mq-q"><div class="t">' + j[1] + '</div><div class="d">' + j[2] + '</div><div class="mq-opts">' + j[3].map(o => btn(j[0], o[0], o[1], o[2], q[j[0]] === o[0])).join('') + '</div></div>').join('')
      + (window.RaviloBooks ? RaviloBooks.panelQs(btn) : '')
      + '<div class="mq-sec">Preview · states a mockup can’t otherwise reach</div>' + PV.map(p => '<div class="mq-q"><div class="t">' + p[1] + '</div><div class="mq-opts">' + p[2].map(o => btn('pv:' + p[0], o[0], o[1], 0, pv[p[0]] === o[0])).join('') + '</div></div>').join('')
      + (window.RaviloBooks ? RaviloBooks.panelPv(btn) : '')
      + '<div class="mq-sec">Jump to</div><div class="mq-jump">' + [['profile', 'Profile'], ['home', 'Music Home'], ['lib', 'Library'], ['album', 'Album'], ['artist', 'Artist'], ['search', 'Search'], ['pl', 'Playlists'], ['now', 'Now playing'], ['lyrics', 'Lyrics'], ['queue', 'Queue'], ['menu', 'Track ⋯'], ['video', 'Video Home + mini bar'], ['settings', 'Settings · Listening']].map(x => '<button data-jump="' + x[0] + '">' + x[1] + '</button>').join('') + (window.RaviloBooks ? RaviloBooks.panelJumps() : '') + '</div>'
      + '<div class="mq-q" style="margin-top:14px"><div class="d">The lock screen and notification are the platform’s (§G): title = song · artist = credited artists joined “, ” · album = album title · artwork = the cover shown here, ≥ 512 px · actions previous · play/pause · next. For a book: title = chapter · artist = author · album = book · side actions ' + (q.s4 === 'platform' ? 'previous / next (the platform default)' : '−30 s / +30 s') + '.</div></div></div>';
  }
  function reflow() { window.dispatchEvent(new Event('resize')); }
  function applyQ() {
    RM.paintBar(); RM.paintChrome();
    if (MS.mode === 'music' && H.tab.indexOf('m-') === 0 && !RM.bar().some(b => b[0] === H.tab)) H.setTab(RM.firstTab());
    H.render(); if (P.open) { P.lyrics = q.j4 === 'lyrics' && hasLyrics(trk()); paintNow(); } paintMini(); if (MS.det) RM.paintDet();
  }
  function ensurePlaying(id) { if (BK()) BK().stop(true); if (!cur()) playCtx(MS.CTX['al:salt-on-the-window'] || M.tracksOf('salt-on-the-window').map(x => x.id), id || 0, 'al:salt-on-the-window'); }
  function applyPv(k, v) {
    pv[k] = v;
    if (k === 'granted') { MS.granted = v === '1'; if (!MS.granted) { stop(); RM.setMode('video'); } else { RM.paintChrome(); H.render(); } }
    else if (k === 'size') { MS.size = v; H.render(); }
    else if (k === 'pls') { MS.playlists = v === '2' ? [{ id: 'sun', name: 'Sunday morning', ids: ['glass-birds-1', 'salt-on-the-window-2', 'brim-3', 'slow-green-4', 'kaffe-og-cigaretter-1', 'undertow-3', 'morning-fields-2'] }, { id: 'kit', name: 'Kitchen radio', ids: ['paper-planes-1', 'kite-weather-1', 'stormur-1', 'signal-lost-1', 'copperline-2'] }] : []; RM.rerender(); if (MS.det && MS.det.kind === 'playlist') RM.closeAllDet(); }
    else if (k === 'pst') {
      if (MS.mode !== 'music') RM.setMode('music');
      ensurePlaying(); MS.nocoverAll = v === 'nocov'; P.cast = v === 'cast' ? 'Stue' : null; P.castHidden = false; P.stickyBuf = v === 'buf'; P.fail = false; closeSheet();
      if (v === 'buf') { P.buffering = true; P.playing = true; }
      else { P.buffering = false; P.playing = v !== 'pause' && v !== 'end'; }
      if (v === 'end') { P.qi = P.queue.length - 1; P.repeat = 'off'; P.pos = 0; }
      openNow(); if (v === 'fail') { P.fail = true; P.playing = false; failSheet(); }
      paintMini(); if (MS.det) RM.paintDet();
    }
    else if (k === 'land') { closeSheet(); phone.classList.toggle('land', v === '1'); if (v === '1') { ensurePlaying(); openNow(); } else paintNow(); reflow(); }
    else if (k === 'castbar') { const rm = $('rcMini'); if (rm) { rm.classList.toggle('on', v === '1'); if (v === '1') { $('rcMiniTitle').textContent = 'Big Buck Bunny'; $('rcMiniDev').textContent = 'Stue TV'; ensurePlaying(); closeNow(); } } }
    else if (k === 'skin') { document.documentElement.setAttribute('data-skin', v); }
  }
  function jump(k) {
    closeSheet();
    if (window.RaviloBooks && RaviloBooks.jump(k)) return;
    if (k === 'settings') { closeNow(); RM.closeAllDet(); H.setTab('profile'); H.render(); const s = document.querySelector('[data-pf="settings"]'); if (s) s.click(); return; }
    if (k === 'video') { ensurePlaying(); closeNow(); RM.closeAllDet(); RM.setMode('video'); return; }
    if (k === 'profile') { closeNow(); RM.closeAllDet(); H.setTab('profile'); H.render(); return; }
    if (MS.mode !== 'music') RM.setMode('music');
    if (k === 'home' || k === 'lib' || k === 'search' || k === 'pl') { closeNow(); RM.closeAllDet(); const want = { home: ['m-home', 'm-listen'], lib: ['m-library', 'm-browse', 'm-albums'], search: ['m-search'], pl: ['m-playlists', 'm-browse', 'm-home'] }[k];
      const tb = RM.bar().find(b => want.indexOf(b[0]) >= 0); if (k === 'pl' && q.j2 === 'c') MS.libChip = 'playlists'; H.setTab(tb ? tb[0] : RM.firstTab()); H.render(); return; }
    if (k === 'album') { closeNow(); RM.closeAllDet(); RM.openDet('album', 'salt-on-the-window'); return; }
    if (k === 'artist') { closeNow(); RM.closeAllDet(); RM.openDet('artist', 'harbour-lights'); return; }
    ensurePlaying();
    if (k === 'now') { P.lyrics = q.j4 === 'lyrics'; openNow(); }
    else if (k === 'lyrics') { if (!hasLyrics(trk())) { P.qi = P.queue.indexOf('salt-on-the-window-1'); if (P.qi < 0) { playCtx(M.tracksOf('salt-on-the-window').map(x => x.id), 0, 'al:salt-on-the-window'); } else start(); } P.lyrics = true; P.pos = Math.max(P.pos, 40); openNow(); }
    else if (k === 'queue') { openNow(); queueSheet(); }
    else if (k === 'menu') { menu(cur()); }
  }
  panel.addEventListener('click', e => {
    if (e.target.closest('[data-toggle]')) { document.body.classList.toggle('muq-open'); paintPanel(); reflow(); return; }
    const j = e.target.closest('[data-jump]'); if (j) { jump(j.dataset.jump); return; }
    const b = e.target.closest('[data-g]'); if (!b) return;
    const g = b.dataset.g, v = b.dataset.v;
    if (g.indexOf('pv:') === 0) applyPv(g.slice(3), v); else if (g.indexOf('bk:') === 0 && window.RaviloBooks) RaviloBooks.applyPv(g.slice(3), v); else { RM.setQ(g, v); applyQ(); }
    paintPanel();
  });
  paintPanel();

  /* ---- boot ---- */
  RM.paintChrome();
  if (MS.mode === 'music' && MS.granted) { RM.paintBar(); if (H.tab !== 'profile' && H.tab.indexOf('m-') !== 0) H.setTab(RM.firstTab()); }
  H.render(); paintMini(); reflow();
})();
