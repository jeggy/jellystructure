/* Playback sessions on the phone — R368–R372 built into Ravilo Mobile.html from the Playback Sessions canvases.
   Reads ../sessions-live.js (window.RavSess, here = Pixel 9, viewer = Eyð). Rules held:
     · the cast glyph opens ONE sheet: "Playing everywhere" on top, the places below; the glyph counts sessions elsewhere;
     · the bar always shows the one you touched last (owner 10-04); before any touch, the newest playing; films/episodes
       elsewhere are in the sheet, never on the bar; +N opens the sheet;
     · a busy place ALWAYS asks — Play here instead / Cancel, never Add, never remembered (owner 10-04);
     · someone else's session: seen with their name, no controls, unless the household switch is on;
     · the remote: place line → Move to…, Volume (+ each room), Add a speaker… one tap at a time, Play here, Stop. */
(function () {
  const RS = window.RavSess, H = window.RaviloHost, RM = window.RaviloMusic, SP = window.RaviloSpeakers;
  if (!RS || !H || !RM || !RM.MS || !RM.MS.P) return;
  const MS = RM.MS, PL = MS.P, P = PL.state, M = window.MUSIC, esc = H.esc, HERE = 'pixel', ME = 'eyd', FROM = 'Pixel 9';
  const sv = p => '<svg viewBox="0 0 24 24">' + p + '</svg>';
  const G = Object.assign({}, SP ? SP.G : {}, {
    computer: sv('<rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 19h20"/>'),
    play: sv('<path d="M8 5v14l11-7z" fill="currentColor" stroke="none"/>'),
    pause: sv('<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" fill="currentColor" stroke="none"/>'),
    next: sv('<path d="M6 6l9 6-9 6zM17 6v12"/>'), prev: sv('<path d="M18 6l-9 6 9 6zM7 6v12"/>'),
    x: sv('<path d="M6 6l12 12M18 6L6 18"/>'), plus: sv('<path d="M12 5v14M5 12h14"/>'), ck: sv('<path d="M5 12.5l4.5 4.5L19 7.5"/>'),
    move: sv('<path d="M4 12h13M13 7l5 5-5 5"/>'),
  });
  const icon = k => G[k === 'display' ? 'hub' : k] || G.speaker;
  const onBar = s => s.kind === 'music' || s.kind === 'audiobook';
  const isVideo = s => s.kind === 'film' || s.kind === 'episode';
  const verb = s => s.kind === 'music' || s.kind === 'audiobook' ? 'is listening' : 'is watching';
  const ttl = s => s.title + (s.kind === 'episode' ? ' · ' + s.subtitle.split(' · ')[0] : '');
  const live = () => RS.list(HERE).filter(s => !s.ended);
  const own = () => !!PL.cur();
  const ownTitle = () => { const x = M && M.track(PL.cur()); return x ? x.title : ''; };

  /* ---- the cast glyph's count ---- */
  const cb = document.getElementById('castBtn');
  function paintBadge() { if (!cb) return; let b = cb.querySelector('.ses-badge'); const n = live().length;
    if (!n) { if (b) b.remove(); return; } if (!b) { b = document.createElement('span'); b.className = 'ses-badge'; cb.appendChild(b); } b.textContent = n; }

  /* ---- "Playing everywhere" at the top of the Play on… sheet ---- */
  function stateTxt(s) {
    if (s.ended) return 'Stopped';
    if (s.ownerId !== ME) return s.owner + ' ' + verb(s);
    return s.state === 'playing' ? '<span class="ses-eq"><i></i><i></i><i></i></span>Playing' : s.state === 'paused' ? 'Paused · ' + RS.fmt(s.posNow) : 'Loading…';
  }
  function rowS(s) {
    return '<button class="mp-row ses-row' + (s.ended ? ' end' : '') + '" data-ses-open="' + s.id + '"><span class="ses-art' + (isVideo(s) ? ' w' : '') + '" style="background:' + s.art + '">' + (s.ownerId !== ME ? '<b>' + esc(s.owner[0]) + '</b>' : '') + '</span>'
      + '<span class="tx"><span class="nm">' + esc(ttl(s)) + '</span><span class="sub ses-pl">' + icon(s.icon) + esc(s.line) + '</span><span class="sub">' + stateTxt(s) + '</span></span>'
      + '<span class="ses-pg"><i style="width:' + (s.posNow / s.dur * 100).toFixed(1) + '%"></i></span></button>';
  }
  function ownRow() {
    if (!own()) return '';
    const where = P.cast ? (SP ? SP.roomsLine() : P.cast) : 'This phone';
    return '<button class="mp-row ses-row here" data-ses-own><span class="ses-art" style="' + (M ? M.coverStyle ? M.coverStyle(M.album(M.track(PL.cur()).albumId)) : '' : '') + '"></span><span class="tx"><span class="nm">' + esc(ownTitle()) + '</span><span class="sub ses-pl">' + (P.cast ? G.speaker : G.phone) + esc(where) + '</span><span class="sub">' + (P.playing ? '<span class="ses-eq"><i></i><i></i><i></i></span>Playing' : 'Paused') + '</span></span></button>';
  }
  function evHTML() {
    const L = RS.list(HERE).sort((a, b) => (a.ownerId === ME ? 0 : 1) - (b.ownerId === ME ? 0 : 1) || b.events[b.events.length - 1][0] - a.events[a.events.length - 1][0]);
    const o = ownRow(); if (!L.length && !o) return '';
    return '<div class="sc-sec">Playing everywhere</div>' + o + L.map(rowS).join('');
  }
  const cs = document.getElementById('csSheet');
  function patchSheet() {
    if (!cs || !cs.classList.contains('on') && !cs.innerHTML) return;
    const hd = cs.querySelector('.mp-sh'); if (!hd || !cs.querySelector('.sc-add,.sc-sec')) return;
    let ev = cs.querySelector('.ses-ev'); const html = evHTML();
    if (!ev) { ev = document.createElement('div'); ev.className = 'ses-ev'; hd.after(ev); }
    if (ev.innerHTML !== html) ev.innerHTML = html;
    cs.querySelectorAll('.mp-row').forEach(row => {
      if (row.closest('.ses-ev') || row.dataset.sesDone) return; const nm = row.querySelector('.nm'); if (!nm) return;
      const p = RS.PLACES.find(x => x.name === nm.textContent.trim()); if (!p) return; const b = RS.busy(p.id); if (!b) return;
      row.dataset.sesDone = '1'; row.dataset.sesBusy = b.id; row.dataset.sesPlace = p.name;
      let sub = row.querySelector('.sub'); if (!sub) { sub = document.createElement('span'); sub.className = 'sub'; (row.querySelector('.tx') || nm.parentNode).appendChild(sub); }
      sub.textContent = b.ownerId === ME ? 'Playing · ' + ttl(b) : b.owner + ' ' + verb(b);
      if (b.ownerId !== ME && !RS.ctlOthers) row.classList.add('spk-dis');
    });
  }
  if (cs) new MutationObserver(() => { if (!cs.querySelector('.ses-ev')) patchSheet(); }).observe(cs, { childList: true });
  function openPlaces() { if (MS.mode === 'music' && SP) SP.openRoutes(); else H.openScreensSheet({}); patchSheet(); }

  document.addEventListener('click', e => {
    if (!e.target.closest('#csSheet')) return;
    const hit = sel => e.target.closest(sel), stop = () => { e.stopPropagation(); e.preventDefault(); };
    let el;
    if ((el = hit('[data-ses-own]'))) { stop(); H.closeScreensSheet(); RS.touch(HERE, 'self'); PL.openNow(); return; }
    if ((el = hit('[data-ses-open]'))) { stop(); H.closeScreensSheet(); openRemote(el.dataset.sesOpen); return; }
    if ((el = hit('[data-ses-no]'))) { stop(); el.closest('.ses-ask').remove(); return; }
    if ((el = hit('[data-ses-go]'))) { stop(); const ask = el.closest('.ses-ask'), row = ask.previousElementSibling;
      RS.cmd(el.dataset.sesGo, 'replace', null, FROM); ask.remove(); delete row.dataset.sesBusy; row.classList.remove('spk-dis'); row.click(); return; }
    if ((el = hit('[data-ses-busy]'))) { stop(); if (el.classList.contains('spk-dis')) return;
      const nx = el.nextElementSibling; if (nx && nx.classList.contains('ses-ask')) { nx.remove(); return; }
      const b = RS.get(el.dataset.sesBusy), mine = MS.mode === 'music' ? ownTitle() : '';
      const q = b.ownerId === ME ? esc(el.dataset.sesPlace) + ' is playing <b>' + esc(ttl(b)) + '</b>.' : 'Stop ' + esc(b.owner) + '’s <b>' + esc(ttl(b)) + '</b> and play here?';
      el.insertAdjacentHTML('afterend', '<div class="ses-ask"><p>' + q + '</p><div class="bs"><button class="go" data-ses-go="' + b.id + '">' + (mine ? 'Play ' + esc(mine) + ' here instead' : 'Play here instead') + '</button><button data-ses-no>Cancel</button></div></div>');
    }
  }, true);

  /* ---- the remote: a session pointed at from this phone ---- */
  let openId = null, stopArm = 0;
  const sheetOn = () => PL.sheet.classList.contains('on') && PL.sheet.querySelector('.ses-rm');
  function openRemote(id) { openId = id; stopArm = 0; RS.touch(HERE, id); paintRemote(); paintBar(true); }
  function volHTML(s) {
    const one = (lbl, v, room) => '<div class="spk-vol' + (room ? ' rm' : '') + '">' + (room ? '<span class="spk-rn">' + esc(lbl) + '</span>' : G.vol) + '<input type="range" min="0" max="100" step="5" value="' + v + '" data-sv="' + (room || '') + '" aria-label="' + esc(lbl) + '"><span data-svv="' + (room || '') + '">' + v + ' %</span></div>';
    return '<div class="ses-vb">' + one('Volume', RS.master(s.id), '') + (s.rooms.length > 1 ? s.rooms.map(r => one(RS.place(r).name, s.vols[r], r)).join('') : '') + '</div>';
  }
  function paintRemote() {
    const s = openId && RS.get(openId); if (!s) { openId = null; return; }
    const can = RS.canControl(s, ME) && !s.ended, mine = s.ownerId === ME;
    const head = mine ? (s.ended ? 'Stopped' : 'Playing on ' + s.line) : s.owner + ' ' + verb(s);
    const h = '<div class="mp-sh"><h4>' + esc(head) + '</h4><span class="sp"></span><button class="mp-b flat" data-sr="close" aria-label="Close">' + G.x + '</button></div>'
      + '<div class="ses-rm" data-id="' + s.id + '"><div class="ses-big' + (isVideo(s) ? ' w' : '') + '" style="background:' + s.art + '"></div>'
      + '<div class="ses-tt"><b>' + esc(s.title) + '</b><span>' + esc(s.subtitle) + '</span></div>'
      + '<button class="ses-place" data-sr="move"' + (can ? '' : ' disabled') + '>' + icon(s.icon) + '<span>' + esc(s.line) + '</span>' + (can ? G.move : '') + '</button>'
      + '<div class="ses-seek"><span id="srT">' + RS.fmt(s.posNow) + '</span><input type="range" id="srS" min="0" max="' + s.dur + '" value="' + Math.floor(s.posNow) + '"' + (can ? '' : ' disabled') + ' aria-label="Position"><span>' + RS.fmt(s.dur) + '</span></div>'
      + (can ? '<div class="ses-tr"><button data-sr="prev" aria-label="Previous">' + G.prev + '</button><button class="pp" data-sr="pp" aria-label="Play or pause">' + (s.state === 'playing' ? G.pause : G.play) + '</button><button data-sr="next" aria-label="Next"' + (s.index < s.queue.length - 1 ? '' : ' disabled') + '>' + G.next + '</button></div>'
        : '<div class="ses-ro">' + (s.ended ? 'This stopped on ' + esc(s.line) + '.' : 'You can see what ' + esc(s.owner) + ' is playing. Controlling each other’s is off for this household.') + '</div>')
      + (can ? volHTML(s) : '')
      + (can && !isVideo(s) ? '<button class="mp-row" data-sr="add"><span class="sc-ic spk-ic">' + G.plus + '</span><span class="tx"><span class="nm">Add a speaker…</span></span></button>' : '')
      + (can ? '<button class="mp-row" data-sr="move"><span class="sc-ic spk-ic">' + G.move + '</span><span class="tx"><span class="nm">Move to…</span></span></button>'
        + '<button class="mp-row" data-sr="here"><span class="sc-ic spk-ic">' + G.phone + '</span><span class="tx"><span class="nm">Play here</span><span class="sub">On this phone, from ' + RS.fmt(Math.max(0, s.posNow - (s.kind === 'music' ? 0 : 2))) + '</span></span></button>'
        + '<button class="mp-row" data-sr="stop"><span class="sc-ic spk-ic" style="color:#ff9b8a">' + G.stop + '</span><span class="tx"><span class="nm" style="color:#ff9b8a">' + (stopArm ? 'Stop ' + esc(s.owner) + '’s ' + esc(s.title) + '?' : 'Stop on ' + esc(s.line)) + '</span></span></button>' : '')
      + '</div>';
    PL.openSheet('ses', h);
  }
  function placeRows(s, mode) {
    const vid = isVideo(s);
    return RS.PLACES.filter(p => !p.app && (!vid || p.video)).map(p => {
      const inS = s.rooms.indexOf(p.id) >= 0, b = inS ? null : RS.busy(p.id), ox = inS ? '' : RS.outside(p.id), dis = !!b || !!ox || (mode === 'add' && inS) || (SP && (P.cast === p.name || P.rooms.indexOf(p.name) >= 0));
      if (mode === 'add' && inS) return '';
      const sub = inS ? 'Playing here' : ox ? 'Busy · ' + ox : b ? (b.ownerId === ME ? 'Playing · ' + ttl(b) : b.owner + ' ' + verb(b)) : dis ? 'Playing from this phone' : 'Ready' + (mode === 'add' ? ' · ' + p.vol + ' %' : '');
      return '<button class="mp-row sc-row' + (inS && mode === 'move' ? ' on' : '') + (dis && !inS ? ' spk-dis' : '') + '"' + (dis && !inS ? ' disabled' : ' data-sp="' + p.id + '"') + '><span class="sc-ic spk-ic">' + icon(p.icon) + '</span><span class="tx"><span class="nm">' + esc(p.name) + '</span><span class="sub">' + esc(sub) + '</span></span><span class="rt">' + (inS ? G.ck : mode === 'add' ? G.plus : '') + '</span></button>';
    }).join('');
  }
  let sub = null;
  function openSub(kind) {
    const s = RS.get(openId); if (!s) return; sub = kind;
    const title = kind === 'add' ? 'Add a speaker' : 'Move to…';
    const note = kind === 'add' ? 'Tap a room to add it. It joins at the volume it has now.' : 'One place. ' + (s.kind === 'music' ? 'The song carries on where it is.' : 'It picks up 2 seconds back.');
    PL.openSheet('ses-sub', '<div class="mp-sh"><h4>' + title + '</h4><span class="sp"></span><button class="mp-b flat" data-sr="back" aria-label="Back">' + G.x + '</button></div><div class="mp-note" style="padding:0 20px 8px;font-size:13.5px;line-height:1.5;color:var(--ink-soft)">' + note + '</div><div class="ses-subl">' + placeRows(s, kind) + '</div>'
      + (kind === 'move' ? '<button class="mp-row" data-sr="here"><span class="sc-ic spk-ic">' + G.phone + '</span><span class="tx"><span class="nm">This phone</span><span class="sub">Play here</span></span></button>' : '<button class="mp-row" data-sr="back"><span class="tx"><span class="nm" style="color:var(--ink-soft)">Done</span></span></button>'));
  }
  PL.sheet.addEventListener('click', e => {
    const b = e.target.closest('[data-sr],[data-sp]'); if (!b || !openId) return; if (!PL.sheet.querySelector('.ses-rm,.ses-subl')) return;
    e.stopPropagation(); const s = RS.get(openId); RS.touch(HERE, openId);
    if (b.dataset.sp) { const p = RS.place(b.dataset.sp);
      if (sub === 'add') { RS.cmd(openId, 'add', p.id, FROM); H.phToast(p.name + ' joined · ' + RS.get(openId).line); openSub('add'); }
      else { RS.cmd(openId, 'move', p.id, FROM); H.phToast('Moving to ' + p.name + '…'); setTimeout(() => H.phToast('Playing on ' + p.name), 900); sub = null; paintRemote(); }
      return; }
    const k = b.dataset.sr;
    if (k === 'close') { PL.closeSheet(); openId = null; }
    else if (k === 'back') { sub = null; paintRemote(); }
    else if (k === 'pp' || k === 'next' || k === 'prev') { RS.cmd(openId, k, null, FROM); }
    else if (k === 'move' && !b.disabled) openSub('move');
    else if (k === 'add') openSub('add');
    else if (k === 'stop') { if (s.ownerId !== ME && !stopArm) { stopArm = 1; paintRemote(); return; } RS.cmd(openId, 'stop', null, FROM); H.phToast('Stopped on ' + s.line); PL.closeSheet(); openId = null; }
    else if (k === 'here') playHere(s);
  }, true);
  PL.sheet.addEventListener('input', e => {
    const v = e.target.closest('[data-sv]'); if (v && openId) { RS.cmd(openId, 'vol', { room: v.dataset.sv, v: +v.value }, FROM); const s = RS.get(openId);
      PL.sheet.querySelectorAll('[data-svv]').forEach(o => { const r = o.dataset.svv, val = r ? s.vols[r] : RS.master(s.id); o.textContent = val + ' %'; const i = o.parentNode.querySelector('input'); if (i !== v) i.value = val; }); return; }
    if (e.target.id === 'srS' && openId) { const t = PL.sheet.querySelector('#srT'); if (t) t.textContent = RS.fmt(+e.target.value); }
  });
  PL.sheet.addEventListener('change', e => {
    const v = e.target.closest('[data-sv]'); if (v && openId) RS.cmd(openId, 'volDone', { room: v.dataset.sv }, FROM);
    if (e.target.id === 'srS' && openId) RS.cmd(openId, 'seek', +e.target.value, FROM);
  });
  function playHere(s) {
    RS.cmd(s.id, 'here', null, FROM); PL.closeSheet(); openId = null;
    if (s.kind === 'music' && M) { const ids = s.queue.map(q => q[0]).filter(id => M.track(id)); if (ids.length) { PL.playCtx(ids, Math.min(s.index, ids.length - 1), 'al:glass-birds'); P.pos = Math.floor(s.posNow); } RS.touch(HERE, 'self'); PL.paintMini(); H.phToast('Playing on this phone'); }
    else { H.phToast('Playing here · ' + ttl(s) + ' from ' + RS.fmt(Math.max(0, s.posNow - 2))); try { H.openPlayer(s.title); } catch (x) {} }
  }

  /* ---- the bar: what plays elsewhere, when it is the one you touched last ---- */
  const muMini = document.getElementById('muMini'), screen = muMini.parentNode;
  const bar = document.createElement('div'); bar.className = 'mu-mini ses-mini abovenav'; bar.id = 'sesMini'; screen.appendChild(bar);
  function pick() {
    const L = live().filter(onBar), t = RS.touched(HERE);
    if (t === 'self' && own()) return null; const hit = L.find(s => s.id === t); if (hit) return hit; if (own()) return null;
    return L.filter(s => s.state === 'playing').sort((a, b) => b.created - a.created)[0] || null;
  }
  function blocked() { const q = id => { const e = document.getElementById(id); return e && e.classList.contains('on'); };
    return q('detail') || q('mp') || q('rcRem') || MS.det || P.open || (window.RaviloWeb && RaviloWeb.error && RaviloWeb.error(H.tab)); }
  let barKey = '';
  function paintBar(force) {
    const s = blocked() ? null : pick();
    if (!s) { if (bar.classList.contains('on')) { bar.classList.remove('on'); screen.classList.remove('ses-bar-on'); if (own()) PL.paintMini(); } barKey = ''; return; }
    muMini.classList.remove('on'); screen.classList.add('mu-mini-on', 'ses-bar-on');
    const n = live().length - 1 + (own() ? 1 : 0), can = RS.canControl(s, ME);
    const key = [s.id, s.state, s.index, s.line, n, can].join('|');
    if (force || key !== barKey) {
      barKey = key;
      bar.innerHTML = '<div class="pg"><i id="sbPg"></i></div><span class="ses-art sm" style="background:' + s.art + '"></span>'
        + '<div class="tx"><div class="n">' + esc(s.title) + '</div><div class="d"><span class="spk-on">' + icon(s.icon) + esc(s.line) + '</span></div></div>'
        + (n > 0 ? '<button class="ses-n" data-sb="more" aria-label="' + n + ' more playing">+' + n + '</button>' : '')
        + (can ? '<button data-sb="pp" aria-label="Play or pause">' + (s.state === 'playing' ? G.pause : G.play) + '</button>' + (s.index < s.queue.length - 1 ? '<button data-sb="next" aria-label="Next">' + G.next + '</button>' : '') : '');
      bar.dataset.id = s.id; bar.classList.add('on');
    }
    const pg = document.getElementById('sbPg'); if (pg) pg.style.width = (s.posNow / s.dur * 100).toFixed(2) + '%';
  }
  bar.addEventListener('click', e => {
    const id = bar.dataset.id, b = e.target.closest('[data-sb]'); RS.touch(HERE, id);
    if (!b) return openRemote(id);
    if (b.dataset.sb === 'more') return openPlaces();
    RS.cmd(id, b.dataset.sb, null, FROM);
  });
  // the phone's own bar: touching it makes it the one on the bar again; +N when more plays elsewhere
  muMini.addEventListener('click', e => { const c = e.target.closest('.ses-n'); if (c) { e.stopPropagation(); openPlaces(); return; } RS.touch(HERE, 'self'); }, true);
  if (SP) { const base = SP.afterMini; SP.afterMini = function (m) { base(m); if (pick()) { m.classList.remove('on'); return; }
    const n = live().length; if (n && m.classList.contains('on') && !m.querySelector('.ses-n')) { const pp = m.querySelector('#muMiniPP'); if (pp) pp.insertAdjacentHTML('beforebegin', '<button class="ses-n" aria-label="' + n + ' more playing">+' + n + '</button>'); } }; }
  const basePlay = PL.playCtx; PL.playCtx = MS.P.playCtx = function () { RS.touch(HERE, 'self'); return basePlay.apply(this, arguments); };

  /* ---- live: the store's events (another tab = another app) and a 1 s clock ---- */
  RS.on(() => { paintBadge(); paintBar(); if (cs && cs.querySelector('.ses-ev')) { const ev = cs.querySelector('.ses-ev'), h = evHTML(); if (ev.innerHTML !== h) ev.innerHTML = h; }
    if (openId && sheetOn() && !(document.activeElement && document.activeElement.type === 'range')) paintRemote(); });
  setInterval(() => {
    paintBar(); paintBadge();
    if (openId && sheetOn()) { const s = RS.get(openId); if (s) { const t = document.getElementById('srT'), r = document.getElementById('srS'); if (t && document.activeElement !== r) { t.textContent = RS.fmt(s.posNow); r.value = Math.floor(s.posNow); } } }
    else if (openId && !PL.sheet.classList.contains('on')) openId = null;
    if (cs && cs.querySelector('.ses-ev')) cs.querySelectorAll('.ses-row[data-ses-open]').forEach(r => { const s = RS.get(r.dataset.sesOpen); const i = r.querySelector('.ses-pg i'); if (s && i) i.style.width = (s.posNow / s.dur * 100).toFixed(1) + '%'; });
  }, 1000);

  /* ---- PREVIEW (mockup only): start the household's sessions again · the household switch the admin owns ---- */
  const pv = document.getElementById('prevPick');
  if (pv) { pv.insertAdjacentHTML('beforeend', '<button data-sespv="reset">Sessions · start again</button><button data-sespv="ctl">Members control each other’s · <b id="sesCtlV"></b></button>');
    const pc = () => { const v = document.getElementById('sesCtlV'); if (v) v.textContent = RS.ctlOthers ? 'on' : 'off'; }; pc(); RS.on(pc);
    pv.addEventListener('click', e => { const b = e.target.closest('[data-sespv]'); if (!b) return; e.stopPropagation();
      if (b.dataset.sespv === 'reset') { RS.reset(); H.phToast('Sessions started again'); } else { RS.ctlOthers = !RS.ctlOthers; H.phToast(RS.ctlOthers ? 'Household members can control each other’s' : 'Each person controls only their own'); } }, true); }
  paintBadge(); paintBar(true);
  window.RaviloSessions = { openRemote, openPlaces, paintBar };
})();
