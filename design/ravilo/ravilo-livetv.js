/* Ravilo Live TV (Phase R177) — woven into the main TV app.
   Surfaces on Home (an "On now" row + a Channels-rail collection tile) → a full EPG
   guide (channels × time, now-line, live progress) → a full-screen live player with
   zapping (←/→), number entry (0–9), and a Now/Next overlay (↑). Live TV is NEVER a
   top-nav tab. Channels + guide come from window.LiveTV (jellystructure → Jellyfin).

   window.initRaviloLiveTV(stage, ctx) → { enabled, onNowRow, collectionTile,
   renderGuide, focusGuide, tune, playerOpen }. Own capture-phase key handling so the
   guide + player own input while active. */
(function () {
  const LT = window.LiveTV;

  // density presets — MUST match ravilo-livetv.css .lt-guide.compact/.spacious vars
  const DENS = {
    compact:  { rowh: 66, chcol: 290, slot: 190 },
    spacious: { rowh: 88, chcol: 320, slot: 240 },
  };
  // channel categories a restricted / kids profile is allowed to see (rides Phase 142)
  const KID_CATS = ['kids', 'doc', 'music', 'nature'];

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function initRaviloLiveTV(stage, ctx) {
    const el = ctx.el, t = ctx.t, scroll = ctx.scroll, appbar = ctx.appbar, flash = ctx.flash;
    const R = window.RAVILO;
    const cfg = (R.config && R.config.livetv) || {};
    const CAT_LIST = ['all', ...Object.keys(LT.CATS)];
    const catLabel = c => c === 'all' ? t('lt_all_channels') : LT.CATS[c].label;

    // channels the current viewer may see (config + restricted-policy filter, Phase 142)
    function channels() {
      const u = ctx.currentUser && ctx.currentUser();
      let list = LT.channels;
      if (u && u.kid) list = list.filter(c => KID_CATS.indexOf(c.cat) >= 0);
      return list;
    }
    function filteredCh(cat) { const c = channels(); return cat === 'all' ? c : c.filter(x => x.cat === cat); }
    function progsWin(chId) { return (LT.schedule[chId] || []).filter(p => p.end > LT.WIN_START && p.start < LT.WIN_END); }
    function nowColIdx(chId) { const w = progsWin(chId); const i = w.findIndex(p => LT.NOW >= p.start && LT.NOW < p.end); return i < 0 ? 0 : i; }
    function chById(id) { return LT.channels.find(c => c.id === id); }
    function logoHTML(ch, cls) { return `<span class="lt-chlogo ${cls || ''}" style="background:${ch.grad}">${ch.initials}</span>`; }

    function enabled() { return !!cfg.enabled && channels().length > 0; }

    /* ========================= HOME surfaces ========================= */
    function onNowCard(x) {
      const c = el('div', 'lt-onnow foc'); c._livech = x.ch;
      const pc = LT.pct(x.now);
      c.innerHTML =
        `<div class="art"><div class="grad" style="background:${x.ch.grad}"></div><div class="scrim"></div>` +
        `<span class="badge-live">${t('lt_live')}</span><div class="biglogo">${x.ch.initials}</div></div>` +
        `<div class="meta"><div class="chrow"><span class="chnum">${x.ch.num}</span><span class="chname">${esc(x.ch.name)}</span></div>` +
        `<div class="ptitle">${esc(x.now.title)}</div>` +
        `<div class="ptime">${LT.fmt(x.now.start)}–${LT.fmt(x.now.end)}${x.next ? ' · ' + t('lt_next') + ': ' + esc(x.next.title) : ''}</div>` +
        `<div class="pbar"><i style="width:${pc}%"></i></div></div>`;
      return c;
    }
    // the Home "On now" row (with a leading Open-TV-Guide tile). Returns a .crow or null.
    function onNowRow() {
      if (!enabled() || !cfg.onNowRow) return null;
      const chs = channels();
      const r = el('div', 'crow lt-onnow-row');
      r.innerHTML = `<div class="crow-head lt-onnow-head"><h2><span class="live-dot"></span>${t('lt_on_now')}</h2>` +
        `<span class="more">${chs.length} ${t('lt_channels')} · ${t('lt_from_jellyfin')}</span></div>`;
      const track = el('div', 'track focus-row');
      const gt = el('div', 'lt-guidetile foc'); gt.dataset.liveguide = '1';
      gt.innerHTML = `<div class="grid-ic">▦</div><div class="gt-k">${t('lt_live_tv')}</div>` +
        `<div class="gt-t">${t('lt_open_guide')}</div><div class="gt-s">${t('lt_guide_sub')}</div>`;
      track.appendChild(gt);
      const on = LT.onNow().filter(x => chs.indexOf(x.ch) >= 0);
      on.slice(0, 10).forEach(x => track.appendChild(onNowCard(x)));
      r.appendChild(track);
      return r;
    }
    // a "Live TV" tile for the Channels & Collections rail. Returns a .studio .foc or null.
    function collectionTile() {
      if (!enabled() || !cfg.collection) return null;
      const c = el('div', 'studio foc'); c.dataset.liveguide = '1';
      c.style.background = 'linear-gradient(135deg,#c8102e,#3a0b12)';
      c.innerHTML = `<div class="sheen"></div><div class="wm" style="display:flex;align-items:center;gap:10px;">` +
        `<span style="width:12px;height:12px;border-radius:50%;background:#fff;box-shadow:0 0 0 4px rgba(255,255,255,.25);"></span>${t('lt_live_tv')}</div>`;
      return c;
    }

    /* ========================= GUIDE (EPG grid) ========================= */
    let g = { zone: 'cats', cat: 'all', r: 0, c: 0 };

    function renderGuide() {
      ctx.stopHero && ctx.stopHero();
      scroll.innerHTML = '';
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
      const dens = DENS[cfg.density] || DENS.spacious;
      const chs = filteredCh(g.cat);
      g.r = clamp(g.r, 0, Math.max(0, chs.length - 1));

      const wrap = el('div', 'lt-guide ' + (cfg.density === 'compact' ? 'compact' : 'spacious'));
      wrap.style.setProperty('--lt-rowh', dens.rowh + 'px');
      wrap.style.setProperty('--lt-chcol', dens.chcol + 'px');
      wrap.innerHTML =
        `<div class="lt-guide-head"><h1>${t('lt_tv_guide')}</h1><span class="sub">${channels().length} ${t('lt_channels')}</span>` +
        `<span class="now-ts"><span class="live-dot"></span>${t('lt_now')} · ${LT.fmt(LT.NOW)}</span></div>`;
      // category chips
      const cats = el('div', 'lt-cats');
      CAT_LIST.forEach(c => {
        const chip = el('div', 'lt-cat' + (c === g.cat ? ' on' : ''));
        chip.dataset.ltcat = c; chip.textContent = catLabel(c);
        cats.appendChild(chip);
      });
      wrap.appendChild(cats);

      // EPG grid
      const ppm = dens.slot / LT.SLOT;
      const timeW = (LT.WIN_END - LT.WIN_START) * ppm;
      const epg = el('div', 'lt-epg');
      const scr = el('div', 'lt-epg-scroll');
      const canvas = el('div', 'lt-epg-canvas'); canvas.style.width = (dens.chcol + timeW) + 'px';
      // time header
      let tb = `<div class="lt-epg-timebar"><div class="corner">${t('lt_channel')}</div>`;
      LT.slots().forEach(sl => { tb += `<div class="lt-epg-slot" style="width:${dens.slot}px">${LT.fmt(sl)}</div>`; });
      tb += '</div>';
      // rows
      let rowsHTML = '';
      chs.forEach((ch, ri) => {
        const w = progsWin(ch.id);
        let cells = '';
        w.forEach((p, ci) => {
          const l = (Math.max(p.start, LT.WIN_START) - LT.WIN_START) * ppm;
          const width = Math.max((Math.min(p.end, LT.WIN_END) - Math.max(p.start, LT.WIN_START)) * ppm - 6, 40);
          const live = LT.NOW >= p.start && LT.NOW < p.end;
          cells += `<div class="lt-epg-cell${live ? ' live' : ''}" data-ltcell="${ri}-${ci}" data-ch="${ch.id}" style="left:${l}px;width:${width}px">` +
            `<div class="pt">${esc(p.title)}</div>` +
            `<div class="ps">${live ? '<span class="liveflag">● ' + t('lt_live') + '</span>' : ''}${LT.fmt(p.start)}</div>` +
            (live ? `<div class="cbar" style="width:${LT.pct(p)}%"></div>` : '') + `</div>`;
        });
        rowsHTML += `<div class="lt-epg-row" data-ltrow="${ri}">` +
          `<div class="lt-epg-chcol">${logoHTML(ch)}<div class="cn"><div class="cnum">${ch.num}</div><div class="cname">${esc(ch.name)}</div></div></div>` +
          `<div class="lt-epg-track" style="width:${timeW}px">${cells}</div></div>`;
      });
      const nowLeft = dens.chcol + (LT.NOW - LT.WIN_START) * ppm;
      canvas.innerHTML = tb + `<div class="lt-epg-nowline" style="left:${nowLeft}px;top:42px"></div>` + rowsHTML;
      scr.appendChild(canvas); epg.appendChild(scr); wrap.appendChild(epg);
      scroll.appendChild(wrap);
      wrap._scr = scr; wrap._dens = dens;
      // mouse support (keyboard/D-pad is primary)
      wrap.addEventListener('click', e => {
        const chip = e.target.closest('.lt-cat');
        if (chip) { g.zone = 'cats'; setCat(chip.dataset.ltcat); return; }
        const cell = e.target.closest('.lt-epg-cell');
        if (cell) { tune(cell.dataset.ch, { type: 'liveGuide' }); return; }
        const rowEl = e.target.closest('.lt-epg-row');
        if (rowEl) { const ch = filteredCh(g.cat)[+rowEl.dataset.ltrow]; if (ch) tune(ch.id, { type: 'liveGuide' }); return; }
      });
      paintGuide();
    }

    function guideEls() { return { wrap: scroll.querySelector('.lt-guide') }; }
    function paintGuide() {
      const wrap = scroll.querySelector('.lt-guide'); if (!wrap) return;
      wrap.querySelectorAll('.lt-cat').forEach(c => c.classList.toggle('focused', g.zone === 'cats' && c.dataset.ltcat === g.cat));
      wrap.querySelectorAll('.lt-epg-cell').forEach(c => c.classList.remove('focused'));
      if (g.zone === 'grid') {
        const cell = wrap.querySelector(`.lt-epg-cell[data-ltcell="${g.r}-${g.c}"]`);
        if (cell) { cell.classList.add('focused'); scrollGuideTo(wrap, cell); }
      }
    }
    function scrollGuideTo(wrap, cell) {
      const scr = wrap._scr, dens = wrap._dens; if (!scr) return;
      const left = cell.offsetLeft;   // relative to .lt-epg-track
      const trackLeft = dens.chcol;
      const targetLeft = left;                              // left edge of program in time space
      if (targetLeft < scr.scrollLeft) scr.scrollLeft = Math.max(0, targetLeft - 24);
      else if (targetLeft + cell.offsetWidth > scr.scrollLeft + (scr.clientWidth - trackLeft)) scr.scrollLeft = targetLeft - 40;
      const top = g.r * dens.rowh;
      if (top < scr.scrollTop) scr.scrollTop = Math.max(0, top - dens.rowh);
      else if (top + dens.rowh > scr.scrollTop + scr.clientHeight - 42) scr.scrollTop = top - scr.clientHeight + dens.rowh * 2 + 42;
    }
    function setCat(cat) { g.cat = cat; g.r = 0; g.c = 0; renderGuide(); }
    function focusGuide() { g.zone = 'cats'; paintGuide(); }

    function handleGuideKey(e) {
      const k = e.key;
      const handled = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ', 'Escape', 'Backspace'].includes(k);
      if (!handled) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (k === 'Escape' || k === 'Backspace') { ctx.goHome(); return; }
      const chs = filteredCh(g.cat);
      if (g.zone === 'cats') {
        const ci = CAT_LIST.indexOf(g.cat);
        if (k === 'ArrowRight') { setCat(CAT_LIST[clamp(ci + 1, 0, CAT_LIST.length - 1)]); return; }
        if (k === 'ArrowLeft') { setCat(CAT_LIST[clamp(ci - 1, 0, CAT_LIST.length - 1)]); return; }
        if (k === 'ArrowDown' || k === 'Enter' || k === ' ') { g.zone = 'grid'; g.r = 0; g.c = nowColIdx((chs[0] || {}).id || ''); paintGuide(); return; }
        return;
      }
      // grid
      if (k === 'ArrowUp') { if (g.r === 0) { g.zone = 'cats'; paintGuide(); } else { g.r--; g.c = clamp(g.c, 0, progsWin(chs[g.r].id).length - 1); paintGuide(); } return; }
      if (k === 'ArrowDown') { g.r = clamp(g.r + 1, 0, chs.length - 1); g.c = clamp(g.c, 0, progsWin(chs[g.r].id).length - 1); paintGuide(); return; }
      if (k === 'ArrowRight') { g.c = clamp(g.c + 1, 0, progsWin(chs[g.r].id).length - 1); paintGuide(); return; }
      if (k === 'ArrowLeft') { g.c = clamp(g.c - 1, 0, progsWin(chs[g.r].id).length - 1); paintGuide(); return; }
      if (k === 'Enter' || k === ' ') { const ch = chs[g.r]; if (ch) tune(ch.id, { type: 'liveGuide' }); return; }
    }

    /* ========================= LIVE PLAYER ========================= */
    const playerEl = el('div', 'lt-player');
    playerEl.setAttribute('data-screen-label', 'Live TV · player');
    stage.appendChild(playerEl);
    let p = { ch: 0, overlay: 'none', nn: 0, bar: true, num: '', ret: { type: 'home' } };
    let barTimer = null, numTimer = null, zapTimer = null;

    function playerOpen() { return playerEl.classList.contains('on'); }

    function tune(chId, fromView) {
      const chs = channels();
      let idx = chs.findIndex(c => c.id === chId);
      if (idx < 0) idx = 0;
      p.ch = idx; p.overlay = 'none'; p.num = ''; p.bar = true;
      p.ret = fromView || p.ret || { type: 'home' };
      ctx.stopHero && ctx.stopHero();
      playerEl.classList.add('on');
      renderPlayer(true);
      armBarTimer();
    }
    function closePlayer() {
      playerEl.classList.remove('on');
      clearTimeout(barTimer); clearTimeout(numTimer); clearTimeout(zapTimer);
      ctx.go(p.ret || { type: 'home' });
    }
    function curCh() { return channels()[p.ch]; }
    function armBarTimer() { clearTimeout(barTimer); if (p.bar && p.overlay !== 'nownext') barTimer = setTimeout(() => { p.bar = false; renderPlayer(); }, 4500); }

    function renderPlayer(withZap) {
      const chs = channels(); const ch = chs[p.ch]; if (!ch) return;
      const nn = LT.nowNext(ch.id); const now = nn.now, next = nn.next;
      const showBar = p.bar && p.overlay !== 'nownext';
      // now/next mini-guide rows (5 around current)
      const start = Math.max(0, Math.min(p.nn - 2, chs.length - 5));
      let nnRows = '';
      for (let i = start; i < Math.min(start + 5, chs.length); i++) {
        const c = chs[i], cn = LT.nowNext(c.id);
        nnRows += `<div class="lt-nn-row${i === p.ch ? ' cur' : ''}${i === p.nn ? ' focused' : ''}" data-nn="${i}">` +
          `<span class="cnum">${c.num}</span>${logoHTML(c)}<span class="cname">${esc(c.name)}</span>` +
          `<span class="nn-col nn-now"><span class="nn-k">${t('lt_now').toUpperCase()}</span>${cn.now ? esc(cn.now.title) : '—'}</span>` +
          `<span class="nn-col nn-next"><span class="nn-k">${t('lt_next').toUpperCase()}</span>${cn.next ? esc(cn.next.title) : '—'}</span></div>`;
      }
      playerEl.innerHTML =
        `<div class="video"><div class="bg" style="background:${ch.grad}"></div><div class="noise"></div>` +
          `<div class="biglogo">${logoHTML(ch)}<div class="cn">${ch.num} · ${esc(ch.name)}</div></div></div>` +
        `<div class="lt-live-badge"><span class="d"></span> ${t('lt_live')}</div>` +
        `<div class="lt-zap${withZap ? '' : ' hidden'}">${logoHTML(ch)}<div><div><span class="cnum">${ch.num}</span> <span class="cname">${esc(ch.name)}</span></div>` +
          `<div class="now">${now ? esc(now.title) : ''}</div></div></div>` +
        `<div class="lt-numosd${p.num ? ' on' : ''}">${p.num}<span class="dim">${'_'.repeat(Math.max(0, 3 - p.num.length))}</span></div>` +
        `<div class="lt-nownext${p.overlay === 'nownext' ? '' : ' hidden'}"><div class="lt-nn-h">${t('lt_now_next')} · ↑↓ ${t('lt_browse')} · ↵ ${t('lt_tune')}</div>${nnRows}</div>` +
        `<div class="lt-chanbar${showBar ? '' : ' hidden'}"><div class="lt-chanbar-top">${logoHTML(ch)}` +
          `<span class="cnum">${ch.num}</span><span class="cname">${esc(ch.name)}</span><span class="catpill">${ch.catLabel}</span>` +
          `<span class="hint">← → ${t('lt_change_channel')} · ↑ ${t('lt_now_next')}</span></div>` +
          (now ? `<div class="now-t">${esc(now.title)}</div><div class="now-times">${LT.fmt(now.start)}–${LT.fmt(now.end)} · ${esc(now.sub)}</div>` : '') +
          (next ? `<div class="next-t">${t('lt_next')} <b>${esc(next.title)}</b> · ${LT.fmt(next.start)}</div>` : '') +
          `<div class="lt-prog"><span class="t">${LT.fmt(now ? now.start : LT.NOW)}</span>` +
            `<div class="track"><i style="width:${LT.pct(now)}%"></i></div>` +
            `<span class="liveedge"><span class="d"></span>${t('lt_live')}</span></div></div>`;
      if (withZap) { clearTimeout(zapTimer); zapTimer = setTimeout(() => { const z = playerEl.querySelector('.lt-zap'); if (z) z.classList.add('hidden'); }, 2600); }
    }

    function zap(d) {
      const n = channels().length; p.ch = (p.ch + d + n) % n; p.overlay = 'none'; p.bar = true;
      renderPlayer(true); armBarTimer();
    }
    function tuneNum() {
      const chs = channels(); const idx = chs.findIndex(c => String(c.num) === p.num);
      if (idx < 0) { flash(t('lt_no_channel') + ' ' + p.num); p.num = ''; renderPlayer(); return; }
      p.ch = idx; p.num = ''; p.bar = true; renderPlayer(true); armBarTimer();
    }

    function handlePlayerKey(e) {
      const k = e.key;
      const nav = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ', 'Escape', 'Backspace'].includes(k);
      const digit = /^[0-9]$/.test(k);
      if (!nav && !digit) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (digit) { p.num = (p.num + k).slice(0, 3); p.bar = true; renderPlayer(); clearTimeout(numTimer); numTimer = setTimeout(tuneNum, 1600); return; }
      if (p.num) { if (k === 'Enter' || k === ' ') { clearTimeout(numTimer); tuneNum(); return; } if (k === 'Escape' || k === 'Backspace') { p.num = ''; renderPlayer(); return; } }
      if (k === 'Escape' || k === 'Backspace') { if (p.overlay === 'nownext') { p.overlay = 'none'; renderPlayer(); armBarTimer(); } else closePlayer(); return; }
      if (p.overlay === 'nownext') {
        if (k === 'ArrowUp') { p.nn = clamp(p.nn - 1, 0, channels().length - 1); renderPlayer(); return; }
        if (k === 'ArrowDown') { p.nn = clamp(p.nn + 1, 0, channels().length - 1); renderPlayer(); return; }
        if (k === 'Enter' || k === ' ') { p.ch = p.nn; p.overlay = 'none'; p.bar = true; renderPlayer(true); armBarTimer(); return; }
        return;
      }
      if (k === 'ArrowUp') { p.overlay = 'nownext'; p.nn = p.ch; p.bar = true; clearTimeout(barTimer); renderPlayer(); return; }
      if (k === 'ArrowDown' || k === 'Enter' || k === ' ') { p.bar = !p.bar; renderPlayer(); armBarTimer(); return; }
      if (k === 'ArrowRight') { zap(1); return; }
      if (k === 'ArrowLeft') { zap(-1); return; }
    }

    // mouse: within the player overlay
    playerEl.addEventListener('click', e => {
      e.stopPropagation();
      const row = e.target.closest('.lt-nn-row');
      if (row) { p.ch = +row.dataset.nn; p.overlay = 'none'; p.bar = true; renderPlayer(true); armBarTimer(); return; }
      if (e.target.closest('.lt-chanbar')) return;
      p.bar = !p.bar; renderPlayer(); armBarTimer();
    });

    /* ---- one capture-phase key handler: player owns input while open; else guide ---- */
    if (ctx.interactive !== false) {
      window.addEventListener('keydown', e => {
        if (playerOpen()) { handlePlayerKey(e); return; }
        if (ctx.getView && ctx.getView().type === 'liveGuide') { handleGuideKey(e); return; }
      }, true);
    }

    return { enabled, onNowRow, collectionTile, renderGuide, focusGuide, tune, playerOpen };
  }

  window.initRaviloLiveTV = initRaviloLiveTV;
})();
