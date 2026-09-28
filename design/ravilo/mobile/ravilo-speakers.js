/* Speakers — prospective 286 / R324, built into Ravilo Mobile.html from ravilo/Speakers - Directions.html (owner picks 2026-09-28).
   Music mode only. Hooks into the music player through RaviloMusic.MS.P (state, openSheet, paintNow, paintMini) and replaces its
   stand-in cast toggle. Rules held:
     · one sheet, titled "Play on…" in music mode (the video sheet keeps "Play on a TV" and never lists a speaker);
     · a speaker busy with another app is TAPPABLE and asks "Stop {app} and play here?" first (owner);
     · the receiver owns the queue: the position carries over on hand-off and "Play on this phone" pulls it back;
     · volume = the phone's keys (+ / − here, 5 % steps) and a slider in ⋯ (Q3);
     · swipe-down on the mini bar only hides the bar — the room plays on, a toast says so with Stop (Q2);
     · on a screen (hub, TV) the ⋯ menu offers "Lyrics on {device}", synced songs only, remembered per display, off at first (Q9);
     · the iPhone lists no speakers and says why once (Q7). Stand-in names only. */
(function () {
  const RM = window.RaviloMusic, H = window.RaviloHost; if (!RM || !RM.MS || !RM.MS.P) return;
  const MS = RM.MS, PL = MS.P, P = PL.state, M = window.MUSIC, t = window.t, esc = H.esc;
  const tr = (k, v, fb) => { const s = t(k, v); return s === k ? fb : s; };
  const isIOS = () => document.querySelector('.phone').classList.contains('ios');
  const G = {
    speaker: '<svg viewBox="0 0 24 24"><rect x="6" y="2.5" width="12" height="19" rx="3"/><circle cx="12" cy="14.5" r="3.2"/><circle cx="12" cy="7" r="1"/></svg>',
    group: '<svg viewBox="0 0 24 24"><rect x="2.5" y="5" width="8.5" height="15" rx="2.4"/><rect x="13" y="5" width="8.5" height="15" rx="2.4"/><circle cx="6.75" cy="14" r="2.1"/><circle cx="17.25" cy="14" r="2.1"/></svg>',
    hub: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="11" rx="2"/><path d="M7 15l-2 5h14l-2-5"/></svg>',
    tv: '<svg viewBox="0 0 24 24"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M9 21h6"/></svg>',
    go: '<svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
    vol: '<svg viewBox="0 0 24 24"><path d="M11 5L6 9H2v6h4l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"/></svg>',
    phone: '<svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/></svg>',
    stop: '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
    lyr: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>',
    info: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></svg>',
  };
  // the household as measured 2026-09-28 (research §0): two speakers, a group made in the Home app, the hub, a TV
  const ROUTES = [
    { name: 'Stue', kind: 'speaker', state: 'ready', vol: 25 },
    { name: 'Gæsteværelse', kind: 'speaker', state: 'busy', app: 'Spotify', vol: 10 },
    { name: 'Hele huset', kind: 'group', state: 'ready', vol: 30 },
    { name: 'Køkken hub', kind: 'hub', state: 'ready', vol: 40, display: true },
  ];
  const route = n => ROUTES.find(r => r.name === n);
  const glyph = r => G[r.kind] || G.tv;
  const dispLyr = {}; // per display, off at first
  P.castHidden = false;

  function stateLine(r) {
    if (P.cast === r.name) return '<span class="sub sc-play">' + tr('music.playing_on', { d: r.name }, 'Playing on ' + r.name).replace(/^.*$/, 'Playing · ' + esc((M.track(PL.cur()) || {}).title || '')) + '</span>';
    if (r.state === 'busy') return '<span class="sub">' + esc(tr('cast_busy_with', { app: r.app }, 'Busy · ' + r.app)) + '</span>';
    const kind = r.kind === 'speaker' ? tr('cast_speaker', null, 'Speaker') + ' · ' : r.kind === 'group' ? tr('cast_group', null, 'Speaker group') + ' · ' : '';
    return '<span class="sub">' + kind + 'Ready</span>';
  }
  function rowHTML(r) {
    return '<button class="mp-row sc-row' + (P.cast === r.name ? ' on' : '') + '" data-spk="' + esc(r.name) + '"><span class="sc-ic spk-ic">' + glyph(r) + '</span>'
      + '<span class="tx"><span class="nm">' + esc(r.name) + '</span>' + stateLine(r) + '</span><span class="rt"><svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></span></button>';
  }
  // R265's sheet unchanged in shape (the host's): tier 2, Add a TV and AirPlay stay as the host draws them. Music mode adds a
  // title, the speaker/group/hub rows at the top of "On this network", the iPhone footnote, and — while casting to a speaker — Play on this phone.
  const SPK_KINDS = ['speaker', 'group', 'hub'];
  function hostExtra() {
    const mine = ROUTES.filter(r => SPK_KINDS.indexOf(r.kind) >= 0 && !isIOS());
    return {
      title: esc(tr('cast_sheet_music', null, 'Play on…')),
      rows: mine.map(rowHTML).join(''),
      skip: ROUTES.filter(r => SPK_KINDS.indexOf(r.kind) >= 0).map(r => r.name),
      foot: isIOS() ? '<div class="spk-foot">' + G.info + '<span>' + esc(tr('cast_speakers_ios', null, 'Speakers need the Android app for now')) + '</span></div>' : '',
      after: P.cast ? '<button class="mp-row" data-spk-act="phone"><span class="sc-ic spk-ic">' + G.phone + '</span><span class="tx"><span class="nm">' + esc(tr('cast_play_here', null, 'Play on this phone')) + '</span></span></button>'
        + '<button class="mp-row" data-spk-act="stop"><span class="sc-ic spk-ic" style="color:#ff9b8a">' + G.stop + '</span><span class="tx"><span class="nm" style="color:#ff9b8a">Stop casting</span></span></button>' : '',
    };
  }
  function openRoutes() { H.openScreensSheet(hostExtra()); wireHostSheet(); }
  let wired = false;
  function wireHostSheet() {
    if (wired) return; const cs = document.getElementById('csSheet'); if (!cs) return; wired = true;
    cs.addEventListener('click', e => {
      const rw = e.target.closest('[data-spk]'); if (rw) { e.stopPropagation(); const r = route(rw.dataset.spk); if (P.cast === r.name) { H.closeScreensSheet(); return; } if (r.state === 'busy') { H.closeScreensSheet(); confirmBusy(r); } else castTo(r.name); return; }
      const a = e.target.closest('[data-spk-act]'); if (!a) return; e.stopPropagation(); H.closeScreensSheet();
      if (a.dataset.spkAct === 'stop') stopCast(false); else if (a.dataset.spkAct === 'phone') stopCast(true);
    }, true);
  }
  function confirmBusy(r) {
    PL.openSheet('spkbusy', '<div class="mp-sh"><h4>' + esc(tr('cast_take_over', { app: r.app }, 'Stop ' + r.app + ' and play here?')) + '</h4></div>'
      + '<div class="mp-note" style="padding:0 20px 8px;font-size:14px;line-height:1.5;color:var(--ink-soft)">' + esc(tr('cast_take_over_sub', { device: r.name, app: r.app }, r.name + ' is playing ' + r.app + '. Playing here stops it for whoever started it.')) + '</div>'
      + '<button class="mp-row" data-spk-go="' + esc(r.name) + '"><span class="sc-ic spk-ic">' + glyph(r) + '</span><span class="tx"><span class="nm">' + esc(tr('cast_play_on', { device: r.name }, 'Play on ' + r.name)) + '</span></span></button>'
      + '<button class="mp-row" data-spk-act="back"><span class="tx"><span class="nm" style="color:var(--ink-soft)">Cancel</span></span></button>');
  }
  function castTo(name) {
    const r = route(name) || { name, state: 'ready' }; PL.closeSheet(); if (H.closeScreensSheet) H.closeScreensSheet();
    if (!PL.cur()) { H.phToast('Pick a song first'); return; }
    if (r.state === 'busy') { r.state = 'ready'; r.app = null; }
    P.cast = name; P.castHidden = false;
    // hand-off keeps the song and its position (R245 FR-R245-4 for music, §6.9)
    H.phToast('Sending to ' + name + '…');
    setTimeout(() => { if (P.cast === name) H.phToast('Playing on ' + name); }, 1100);
    PL.paintNow(); PL.paintMini();
  }
  function stopCast(pull) {
    const was = P.cast; PL.closeSheet(); if (H.closeScreensSheet) H.closeScreensSheet(); P.cast = null; P.castHidden = false;
    if (pull) { H.phToast('Playing on this phone'); }
    else { P.playing = false; H.phToast('Stopped on ' + was); }
    PL.paintNow(); PL.paintMini();
  }
  // ⋯ while casting: the device block sits on top of the song menu
  function afterMenu(id) {
    if (!P.cast || !PL.sheet) return;
    const r = route(P.cast), x = M.track(id), synced = !!(M.LYRICS && M.LYRICS[x.id]);
    const blk = document.createElement('div'); blk.className = 'spk-mblk';
    blk.innerHTML = '<div class="spk-mh">' + glyph(r) + '<b>' + esc(r.name) + '</b></div>'
      + '<div class="spk-vol">' + G.vol + '<input type="range" min="0" max="100" step="5" value="' + r.vol + '" data-spk-vol aria-label="Volume on ' + esc(r.name) + '"><span data-spk-volv>' + r.vol + ' %</span></div>'
      + (r.display ? '<button class="mp-row" data-spk-act="lyr"' + (synced ? '' : ' disabled style="opacity:.45"') + '><span class="sc-ic spk-ic">' + G.lyr + '</span><span class="tx"><span class="nm">' + esc(tr('cast_lyrics_on', { device: r.name }, 'Lyrics on ' + r.name)) + '</span>'
        + '<span class="sub">' + (synced ? (dispLyr[r.name] ? 'On · synced' : 'Off') : 'This song has no timed lyrics') + '</span></span><span class="toggle' + (dispLyr[r.name] ? ' on' : '') + '" style="margin-left:auto"></span></button>' : '')
      + '<button class="mp-row" data-spk-act="phone"><span class="sc-ic spk-ic">' + G.phone + '</span><span class="tx"><span class="nm">' + esc(tr('cast_play_here', null, 'Play on this phone')) + '</span></span></button>'
      + '<button class="mp-row" data-spk-act="stop"><span class="sc-ic spk-ic" style="color:#ff9b8a">' + G.stop + '</span><span class="tx"><span class="nm" style="color:#ff9b8a">Stop casting</span></span></button>';
    const head = PL.sheet.querySelector('.mp-sh'); if (head) head.after(blk);
  }
  PL.sheet.addEventListener('click', e => {
    const rw = e.target.closest('[data-spk]'); if (rw) { e.stopPropagation(); const r = route(rw.dataset.spk); if (P.cast === r.name) { PL.closeSheet(); return; } if (r.state === 'busy') confirmBusy(r); else castTo(r.name); return; }
    const go = e.target.closest('[data-spk-go]'); if (go) { e.stopPropagation(); castTo(go.dataset.spkGo); return; }
    const a = e.target.closest('[data-spk-act]'); if (!a) return; e.stopPropagation();
    const k = a.dataset.spkAct;
    if (k === 'stop') stopCast(false); else if (k === 'phone') stopCast(true); else if (k === 'back') openRoutes();
    else if (k === 'lyr' && !a.disabled) { dispLyr[P.cast] = !dispLyr[P.cast]; a.querySelector('.toggle').classList.toggle('on', dispLyr[P.cast]); a.querySelector('.sub').textContent = dispLyr[P.cast] ? 'On · synced' : 'Off'; H.phToast(dispLyr[P.cast] ? 'Lyrics on ' + P.cast : 'Lyrics off on ' + P.cast); }
  }, true);
  PL.sheet.addEventListener('input', e => { const v = e.target.closest('[data-spk-vol]'); if (!v || !P.cast) return; route(P.cast).vol = +v.value; const o = PL.sheet.querySelector('[data-spk-volv]'); if (o) o.textContent = v.value + ' %'; });
  // the cast button and the device chip open the routes sheet (capture: before the player's stand-in toggle)
  PL.now.addEventListener('click', e => { const b = e.target.closest('[data-np="cast"],.mu-ndev'); if (!b) return; e.stopPropagation(); e.preventDefault(); openRoutes(); }, true);
  // the top bar's cast button in music mode opens this sheet (video mode keeps the TV sheet, which never lists a speaker)
  const cb = document.getElementById('castBtn');
  if (cb) cb.addEventListener('click', e => { if (MS.mode !== 'music') return; e.stopImmediatePropagation(); openRoutes(); }, true);
  // volume keys (+ / − stand in for the phone's hardware keys): Android's own panel, named for the speaker
  let hud = null, hudT = 0;
  document.addEventListener('keydown', e => {
    if (!P.cast || !/^(\+|=|-|_|ArrowUp|ArrowDown)$/.test(e.key) || e.target.closest('input,textarea')) return;
    if (/Arrow/.test(e.key) && !e.altKey) return;
    const r = route(P.cast), up = e.key === '+' || e.key === '=' || e.key === 'ArrowUp';
    r.vol = Math.max(0, Math.min(100, r.vol + (up ? 5 : -5)));
    const scr = document.querySelector('.phone .screen') || document.querySelector('.phone');
    if (!hud) { hud = document.createElement('div'); hud.className = 'spk-hud'; scr.appendChild(hud); }
    hud.innerHTML = '<small>' + esc(r.name) + '</small><div class="tr"><i style="height:' + r.vol + '%"></i></div>' + G.vol;
    hud.classList.add('on'); clearTimeout(hudT); hudT = setTimeout(() => hud.classList.remove('on'), 1400);
  });
  // mini bar: the speaker's name with its glyph; swipe-down only hides the bar while casting (Q2)
  function afterMini(mini) {
    if (P.cast && P.castHidden) { mini.classList.remove('on'); return; }
    if (P.cast) { const d = mini.querySelector('.d'); if (d && !d.querySelector('.spk-on')) d.innerHTML = '<span class="spk-ar">' + d.innerHTML + '</span><span class="spk-on">· ' + glyph(route(P.cast) || ROUTES[0]) + esc(P.cast) + '</span>'; }
  }
  const mini = document.getElementById('muMini');
  if (mini) {
    let y0 = null, dy = 0;
    mini.addEventListener('pointerdown', e => { if (!P.cast || e.target.closest('button')) return; y0 = e.clientY; dy = 0; }, true);
    mini.addEventListener('pointermove', e => { if (y0 == null) return; dy = e.clientY - y0; if (dy > 0) mini.style.transform = 'translateY(' + Math.min(dy, 60) + 'px)'; }, true);
    mini.addEventListener('pointerup', e => {
      if (y0 == null) return; const d = dy; y0 = null;
      if (d > 40) {
        e.stopImmediatePropagation(); mini.classList.add('gone'); P.castHidden = true;
        setTimeout(() => { mini.classList.remove('on', 'gone'); mini.style.transform = ''; }, 220);
        stillToast();
      } else mini.style.transform = '';
    }, true);
  }
  function stillToast() {
    const scr = document.getElementById('pmSheet').parentNode;
    const el = document.createElement('div'); el.className = 'ph-toast spk-toast';
    el.innerHTML = '<span>' + esc(tr('cast_still_playing', { device: P.cast }, 'Still playing on ' + P.cast)) + '</span><button>' + esc(tr('cast_stop_room', null, 'Stop')) + '</button>';
    scr.appendChild(el);
    el.querySelector('button').onclick = () => { el.remove(); stopCast(false); };
    setTimeout(() => el.remove(), 5000);
  }
  // opening Now playing (or starting a song from this phone) brings the bar back
  const baseStart = PL.playCtx;
  PL.playCtx = function () { P.castHidden = false; return baseStart.apply(this, arguments); };
  MS.P.playCtx = PL.playCtx;
  window.RaviloSpeakers = { ROUTES, openRoutes, castTo, stopCast, afterMini, afterMenu };
  PL.paintMini(); PL.paintNow();
})();
