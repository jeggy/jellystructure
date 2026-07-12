/* ============================================================
   Ravilo — Player chrome engine (R14)

   The shared player *chrome* over the engine. On Android the engine is the
   forked jellyfin-androidtv playback stack (Media3 + media3-ffmpeg-decoder),
   contained in :ravilo-player; on Web it's a DOM <video> + hls.js + JASSUB.
   Either way the bytes stream DIRECTLY from Jellyfin (data plane) and only
   the control/report seams (start ticket in, progress/stop out) go through
   jellystructure /api/tv/**. This prototype mocks that chrome + state.

   window.initRaviloPlayer(stage, { restoreFocus, flash }) -> { open, close, isOpen }
   ============================================================ */
(function () {
  const NEXTUP_AT = 34;     // seconds remaining → next-up card appears
  const COUNTDOWN = 8;      // next-up auto-advance countdown
  const HIDE_MS = 3600;     // auto-hide chrome after inactivity while playing
  const SKIP_BACK = 10, SKIP_FWD = 30;   // -10s / +30s

  // simple inline icons
  const I = {
    play:  '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>',
    back10:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4 6 9l5 5"/><path d="M6 9h8a5 5 0 0 1 0 10h-3"/></svg>',
    fwd10: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 4 18 9l-5 5"/><path d="M18 9h-8a5 5 0 0 0 0 10h3"/></svg>',
    next:  '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5v14l9-7z"/><rect x="16" y="5" width="3" height="14" rx="1"/></svg>',
    cc:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M9.5 10.2A2.2 2.2 0 0 0 7.8 13.4 2.2 2.2 0 0 0 9.5 14M16 10.2a2.2 2.2 0 0 0-1.7 3.2 2.2 2.2 0 0 0 1.7.6"/></svg>',
    chev:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5 8 12l7 7"/></svg>',
    audio: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 8a5 5 0 0 1 0 8"/></svg>',
    chevdown: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>',
    mic:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4"/></svg>',
    subsoff:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 14h4M15 14h2"/><path d="M4 4l16 16"/></svg>',
  };

  // ISO-639-1 -> flag-icons country code (from ravilo-app.js LANG_CC)
  const PL_CC = { en:'gb', fr:'fr', de:'de', es:'es', da:'dk', fo:'fo', is:'is', no:'no', sv:'se', fi:'fi', nl:'nl', it:'it', pt:'pt', pl:'pl', ru:'ru', ja:'jp', ko:'kr', zh:'cn' };
  // jargon-free variant labels (no codec names, no delivery-method talk)
  const PL_KIND = { forced:'Signs only', sdh:'Sound described', describe:'Describes action', commentary:'Commentary' };
  function plFlag(o) {
    if (o.off) return `<span class="pl-flag off">${I.subsoff}</span>`;
    if (!o.lang || !PL_CC[o.lang]) return `<span class="pl-flag none">${I.mic}</span>`;
    return `<span class="fi fi-${PL_CC[o.lang]} pl-flag"></span>`;
  }
  function plChip(txt, cls) { return `<span class="pl-chip ${cls || ''}">${txt}</span>`; }
  function plBadges(o, isAudio) {
    const b = [];
    if (isAudio && o.fmt) b.push(plChip(o.fmt, 'fmt'));
    if (o.def) b.push(plChip('Default', 'def'));
    if (o.kind) b.push(plChip(PL_KIND[o.kind], 'tag'));
    if (o.note) b.push(plChip(o.note, ''));
    return b.length ? `<span class="pl-badges">${b.join('')}</span>` : '';
  }

  function fmt(s) {
    s = Math.max(0, Math.round(s));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
    const p = n => String(n).padStart(2, '0');
    return h ? `${h}:${p(m)}:${p(ss)}` : `${m}:${p(ss)}`;
  }

  function initRaviloPlayer(stage, opts) {
    opts = opts || {};
    const flash = opts.flash || function () {};
    const restoreFocus = opts.restoreFocus || function () {};

    const root = document.createElement('div');
    root.className = 'player';
    root.setAttribute('data-screen-label', 'Player');
    root.innerHTML = `
      <div class="pl-stage">
        <div class="pl-grad"></div>
        <img class="pl-frame" alt="">
        <div class="pl-grain"></div>
        <div class="pl-vignette"></div>
        <div class="pl-dim"></div>
      </div>
      <div class="pl-sub"></div>

      <div class="pl-center">
        <div class="pl-pausemark">${I.play}</div>
      </div>
      <div class="pl-buffer"><div class="pl-spin"></div><div class="blab"></div></div>

      <div class="pl-chrome">
        <div class="pl-scrim-top"></div>
        <div class="pl-scrim-bot"></div>
        <div class="pl-top">
          <div class="pl-back foc" data-pf="back"><span class="chev">${I.chev}</span><span class="bk-label">Back</span></div>
          <div class="spacer"></div>
          <div class="pl-stream"></div>
        </div>

        <div class="pl-bottom">
          <div class="pl-meta">
            <div class="pl-kicker"></div>
            <div class="pl-title"></div>
            <div class="pl-sub2"></div>
          </div>
          <div class="pl-seekrow">
            <div class="pl-time cur">0:00</div>
            <div class="pl-bar-wrap foc" data-pf="bar">
              <div class="pl-trick"><div class="shot"><div class="g"></div><img alt=""><span class="lbl">Preview</span></div><div class="tc">0:00</div></div>
              <div class="pl-bar">
                <div class="pl-buffered"></div>
                <div class="pl-played"></div>
                <div class="pl-ghost"></div>
                <div class="pl-handle"></div>
              </div>
            </div>
            <div class="pl-time dur">0:00</div>
          </div>
          <div class="pl-controls">
            <div class="pl-skip foc" data-pf="back10" title="Back 10 seconds">${I.back10}<span class="n">-10</span></div>
            <div class="pl-ctrl big foc" data-pf="play" title="Play / Pause">${I.play}</div>
            <div class="pl-skip foc" data-pf="fwd10" title="Forward 30 seconds"><span class="n">+30</span>${I.fwd10}</div>
            <div class="gap"></div>
            <div class="pl-text-ctrl foc" data-pf="tracks">${I.cc}<span>Audio &amp; Subtitles</span></div>
            <div class="pl-text-ctrl foc nextbtn" data-pf="nextbtn">${I.next}<span>Next Episode</span></div>
          </div>
          <div class="pl-epchip"><span class="dchev">${I.chevdown}</span><span class="ectext">Episodes</span></div>
        </div>
      </div>

      <div class="pl-picker">
        <div class="pl-picker-head">
          <div class="pl-tab foc cur" data-tab="audio">${I.audio}<span>Audio</span><span class="pl-tab-flag" data-flag="audio"></span></div>
          <div class="pl-tab foc" data-tab="subs">${I.cc}<span>Subtitles</span><span class="pl-tab-flag" data-flag="subs"></span></div>
        </div>
        <div class="pl-cols"></div>
      </div>

      <div class="pl-nextup">
        <div class="nu-kick"><span class="nu-kicktext">Up Next</span></div>
        <div class="pl-nu-body">
          <div class="pl-nu-shot"><div class="g"></div><img alt="">
            <svg class="ring" viewBox="0 0 56 56"><circle class="nu-ring-bg" cx="28" cy="28" r="23"/><circle class="nu-ring-fg" cx="28" cy="28" r="23"/><text class="nu-ring-num" x="28" y="28">8</text></svg>
          </div>
          <div class="pl-nu-info">
            <div class="pl-nu-ep"></div>
            <div class="pl-nu-title"></div>
            <div class="pl-nu-desc"></div>
            <div class="pl-nu-acts">
              <div class="pl-nu-btn primary foc" data-pf="nu-play">${I.play}<span>Play <span class="ct">in 8s</span></span></div>
              <div class="pl-nu-btn foc" data-pf="nu-stay"><span class="nu-stay-label">Watch credits</span></div>
            </div>
          </div>
        </div>
      </div>

      <div class="pl-eprail">
        <div class="er-head"><span class="er-season"></span><span class="er-hint"><kbd>←</kbd> <kbd>→</kbd> switch · <kbd>↵</kbd> play · <kbd>↑</kbd> back</span></div>
        <div class="er-track"></div>
      </div>`;
    stage.appendChild(root);

    const q = s => root.querySelector(s);
    const els = {
      frame: q('.pl-frame'), grad: q('.pl-grad'), dim: q('.pl-dim'),
      sub: q('.pl-sub'),
      stream: q('.pl-stream'),
      kicker: q('.pl-kicker'), title: q('.pl-title'), sub2: q('.pl-sub2'),
      cur: q('.pl-time.cur'), dur: q('.pl-time.dur'),
      barWrap: q('.pl-bar-wrap'), buffered: q('.pl-buffered'), played: q('.pl-played'),
      ghost: q('.pl-ghost'), handle: q('.pl-handle'),
      trick: q('.pl-trick'), trickImg: q('.pl-trick img'), trickG: q('.pl-trick .g'),
      trickTc: q('.pl-trick .tc'), trickLbl: q('.pl-trick .lbl'),
      playBtn: q('[data-pf="play"]'), pausemark: q('.pl-pausemark'),
      nextBtn: q('.nextbtn'), buffer: q('.pl-buffer'), blab: q('.pl-buffer .blab'),
      pickCols: q('.pl-cols'),
      nu: { ep: q('.pl-nu-ep'), title: q('.pl-nu-title'), desc: q('.pl-nu-desc'),
            img: q('.pl-nu-shot img'), g: q('.pl-nu-shot .g'), kick: q('.nu-kicktext'),
            ringFg: q('.nu-ring-fg'), ringNum: q('.nu-ring-num'), ct: q('.pl-nu-btn .ct'), stay: q('.nu-stay-label') },
      epchipText: q('.pl-epchip .ectext'), erSeason: q('.er-season'), erTrack: q('.er-track'),
    };

    // ---- state ----
    let ctx = null, pos = 0, playing = false, ended = false;
    let open = false, scrubbing = false, scrubPos = 0;
    let pickerTab = 'audio', pickIdx = 0, sel = { audio: 0, subs: 0 };
    let focus = 'play';           // current focusable id in transport
    let epIdx = 0;                // focused episode in the rail
    let countdown = COUNTDOWN;
    let tickTimer = null, hideTimer = null, bufferTimer = null, cdTimer = null;

    // transport focus order; nextbtn only when series
    function order() {
      const o = ['bar', 'back10', 'play', 'fwd10', 'tracks'];
      if (ctx && ctx.nextMeta) o.push('nextbtn');
      o.push('back');
      return o;
    }

    /* ---------- frame / trickplay ---------- */
    function frameFor(p) {
      if (!ctx || !ctx.frames || !ctx.frames.length) return null;
      const f = ctx.frames, idx = Math.min(f.length - 1, Math.floor((p / ctx.duration) * f.length));
      return f[Math.max(0, idx)];
    }
    let curFrame = null;
    function paintFrame(p) {
      const src = frameFor(p);
      if (src) {
        if (src !== curFrame) { curFrame = src; els.frame.src = src; els.frame.classList.add('on'); }
        els.grad.style.background = '#000';
      } else {
        els.frame.classList.remove('on'); curFrame = null;
        els.grad.style.background = ctx ? ctx.grad : '#000';
      }
    }

    /* ---------- render ---------- */
    function paintMeta() {
      els.kicker.textContent = ctx.kicker || '';
      els.title.textContent = ctx.title || '';
      els.sub2.innerHTML = ctx.sub2 || '';
      const s = ctx.stream;
      els.stream.innerHTML =
        `<span class="pl-pill ${s.hls ? 'hls' : ''}"><span class="dot"></span>${s.mode}</span>` +
        `<span class="pl-pill mono"><span class="mut">${s.detail}</span></span>`;
      els.nextBtn.style.display = ctx.nextMeta ? '' : 'none';
      // subtitle demo line shows only when a subtitle track is selected
      renderSub();
    }
    function renderSub() {
      const subTrack = ctx.subs[sel.subs];
      const showing = root.classList.contains('chrome') ? false : (subTrack && !subTrack.off && ctx.sampleSub);
      els.sub.textContent = ctx.sampleSub || '';
      els.sub.classList.toggle('on', !!showing && playing);
    }

    function paintTime() {
      const p = scrubbing ? scrubPos : pos;
      els.cur.textContent = fmt(p);
      els.dur.textContent = fmt(ctx.duration);
      const pct = Math.min(100, p / ctx.duration * 100);
      els.played.style.width = pct + '%';
      els.handle.style.left = pct + '%';
      const buf = Math.min(100, pct + 7);
      els.buffered.style.width = buf + '%';
      els.ghost.style.left = (scrubPos / ctx.duration * 100) + '%';
      if (scrubbing) {
        const gp = scrubPos / ctx.duration * 100;
        els.trick.style.left = `clamp(150px, ${gp}%, calc(100% - 150px))`;
        const src = frameFor(scrubPos);
        if (src) { els.trickImg.style.display = ''; els.trickImg.src = src; els.trickG.style.display = 'none'; els.trickLbl.style.display = 'none'; }
        else { els.trickImg.style.display = 'none'; els.trickG.style.display = ''; els.trickG.style.background = ctx.grad; els.trickLbl.style.display = ''; }
        els.trickTc.textContent = fmt(scrubPos);
      }
    }

    function setPlaying(on) {
      playing = on;
      root.classList.toggle('paused', !on);
      els.playBtn.innerHTML = on ? I.pause : I.play;
      if (on) { startTick(); scheduleHide(); renderSub(); }
      else { stopTick(); showChrome(); }
    }
    function flashCenter() {
      els.pausemark.innerHTML = playing ? I.play : I.pause;
      els.pausemark.classList.remove('flash'); void els.pausemark.offsetWidth; els.pausemark.classList.add('flash');
    }

    /* ---------- chrome auto-hide ---------- */
    function showChrome() {
      root.classList.add('chrome');
      renderSub();
      clearTimeout(hideTimer);
      if (playing && !pickerOpen() && !root.classList.contains('nextup')) scheduleHide();
    }
    function scheduleHide() {
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => {
        if (playing && !pickerOpen() && !root.classList.contains('nextup') && !scrubbing) {
          root.classList.remove('chrome'); renderSub();
        }
      }, HIDE_MS);
    }
    function pickerOpen() { return root.classList.contains('picker'); }

    /* ---------- playback tick ---------- */
    function startTick() { stopTick(); tickTimer = setInterval(() => {
      if (!playing || scrubbing) return;
      pos = Math.min(ctx.duration, pos + 1);
      paintTime(); paintFrame(pos);
      const remain = ctx.duration - pos;
      if (ctx.nextMeta && remain <= NEXTUP_AT && !root.classList.contains('nextup') && !root.classList.contains('eprail')) openNextUp();
      if (pos >= ctx.duration) onEnd();
    }, 1000); }
    function stopTick() { if (tickTimer) clearInterval(tickTimer); tickTimer = null; }

    function onEnd() {
      stopTick(); ended = true;
      if (ctx.nextMeta) { if (!root.classList.contains('nextup')) openNextUp(); }
      else { setPlaying(false); flash('✓ Finished · ' + ctx.title); exit(); }
    }

    /* ---------- buffering ---------- */
    function buffer(ms, label, then) {
      root.classList.add('buffering');
      els.blab.innerHTML = label || `Loading from <b>Jellyfin</b>…`;
      clearTimeout(bufferTimer);
      bufferTimer = setTimeout(() => { root.classList.remove('buffering'); if (then) then(); }, ms);
    }

    /* ---------- scrubbing ---------- */
    function beginScrub() { if (scrubbing) return; scrubbing = true; scrubPos = pos; els.barWrap.classList.add('scrubbing'); showChrome(); paintTime(); }
    function scrub(dir) { beginScrub(); const step = Math.max(5, Math.round(ctx.duration * 0.012)); scrubPos = Math.max(0, Math.min(ctx.duration, scrubPos + dir * step)); paintTime(); }
    function commitScrub() {
      if (!scrubbing) return;
      const to = scrubPos; scrubbing = false; els.barWrap.classList.remove('scrubbing');
      buffer(550, `Seeking…`, () => { pos = to; ended = false; paintTime(); paintFrame(pos); if (playing) startTick(); });
      paintTime();
    }
    function skip(sec) {
      pos = Math.max(0, Math.min(ctx.duration, pos + sec)); ended = false;
      paintTime(); paintFrame(pos); showChrome();
      els.pausemark.innerHTML = sec > 0 ? I.fwd10 : I.back10; // brief glyph reuse
    }

    /* ---------- track picker ---------- */
    function openPicker() { root.classList.add('picker'); showChrome(); pickerTab = 'audio'; pickIdx = sel.audio; renderPicker(); }
    function closePicker() { root.classList.remove('picker'); scheduleHide(); }
    // flag shown inside a tab for the currently-selected track (real language only)
    function plTabFlag(o) {
      if (!o || o.off || !o.lang || !PL_CC[o.lang]) return '';
      return `<span class="fi fi-${PL_CC[o.lang]}"></span>`;
    }
    function renderPicker() {
      root.querySelectorAll('.pl-tab').forEach(t => t.classList.toggle('cur', t.dataset.tab === pickerTab));
      const af = root.querySelector('.pl-tab-flag[data-flag="audio"]');
      const sf = root.querySelector('.pl-tab-flag[data-flag="subs"]');
      if (af) af.innerHTML = plTabFlag(ctx.audio[sel.audio]);
      if (sf) sf.innerHTML = plTabFlag(ctx.subs[sel.subs]);
      const isAudio = pickerTab === 'audio';
      const list = isAudio ? ctx.audio : ctx.subs;
      const selIdx = isAudio ? sel.audio : sel.subs;
      els.pickCols.innerHTML = list.map((o, i) =>
        `<div class="pl-opt foc${i === selIdx ? ' sel' : ''}${i === pickIdx ? ' focused' : ''}" data-oi="${i}">
           <span class="tick">✓</span>
           ${plFlag(o)}
           <span class="ol"><span class="on2">${o.label}${o.sub ? ` <span class="on-sub">${o.sub}</span>` : ''}</span>${plBadges(o, isAudio)}</span>
         </div>`).join('');
      scrollFocusIntoView();
    }
    function scrollFocusIntoView() {
      const box = els.pickCols;
      const el = box.querySelector('.pl-opt.focused');
      if (!box || !el) return;
      const top = el.offsetTop, bottom = top + el.offsetHeight;
      const pad = 8;
      if (top - pad < box.scrollTop) box.scrollTop = Math.max(0, top - pad);
      else if (bottom + pad > box.scrollTop + box.clientHeight) box.scrollTop = bottom + pad - box.clientHeight;
    }
    function pickerNav(d) {
      const list = pickerTab === 'audio' ? ctx.audio : ctx.subs;
      pickIdx = Math.max(0, Math.min(list.length - 1, pickIdx + d));
      root.querySelectorAll('.pl-opt').forEach((e, i) => e.classList.toggle('focused', i === pickIdx));
      scrollFocusIntoView();
    }
    function pickerSwitchTab(tab) { if (tab === pickerTab) return; pickerTab = tab; pickIdx = tab === 'audio' ? sel.audio : sel.subs; renderPicker(); }
    function pickerChoose() {
      if (pickerTab === 'audio') sel.audio = pickIdx; else sel.subs = pickIdx;
      renderPicker(); renderSub();
      const o = (pickerTab === 'audio' ? ctx.audio : ctx.subs)[pickIdx];
      flash((pickerTab === 'audio' ? '🔊 Audio · ' : '💬 Subtitles · ') + o.label);
    }

    /* ---------- episode rail (series only) ---------- */
    function buildEpRail() {
      els.erSeason.textContent = ctx.seasonLabel || '';
      els.epchipText.textContent = ctx.episodes.length + ' Episodes';
      els.erTrack.innerHTML = ctx.episodes.map((e, i) => {
        const cur = i === ctx.epIndex;
        return `<div class="pl-epc foc${cur ? ' current' : ''}${e.watched ? ' epc-watched' : ''}" data-ei="${i}">
          <div class="epc-thumb"><div class="g" style="background:${e.grad}"></div>
            <span class="epc-num">${e.n}</span>
            ${cur ? '<span class="epc-cur">NOW PLAYING</span>' : ''}
            ${(e.pct > 0 || e.watched) ? `<div class="epc-prog"><i style="width:${e.watched ? 100 : e.pct}%"></i></div>` : ''}
          </div>
          <div class="epc-t">${e.n}. ${e.title}</div><div class="epc-d">${e.dur}</div>
        </div>`;
      }).join('');
    }
    function scrollEp() { const c = els.erTrack.children[epIdx]; if (c) els.erTrack.scrollTo({ left: Math.max(0, c.offsetLeft - 64), behavior: 'smooth' }); }
    function paintEpRail() {
      els.erTrack.querySelectorAll('.pl-epc.focused').forEach(e => e.classList.remove('focused'));
      const c = els.erTrack.children[epIdx]; if (c) c.classList.add('focused');
      scrollEp();
    }
    function openEpRail() { if (!ctx.episodes) return; root.classList.add('eprail'); showChrome(); clearTimeout(hideTimer); epIdx = ctx.epIndex; paintEpRail(); }
    function closeEpRail() { root.classList.remove('eprail'); paintFocus(); scheduleHide(); }
    function epNav(d) { epIdx = Math.max(0, Math.min(ctx.episodes.length - 1, epIdx + d)); paintEpRail(); }
    function chooseEp() {
      if (epIdx === ctx.epIndex) { closeEpRail(); return; }
      const c = ctx.resolveEpisode ? ctx.resolveEpisode(epIdx) : null;
      if (c) load(c, true); else closeEpRail();
    }

    /* ---------- next-up ---------- */
    function openNextUp() {
      root.classList.add('nextup'); showChrome();
      const n = ctx.nextMeta;
      els.nu.kick.textContent = ctx.type === 'episode' ? 'Up Next' : 'Up Next';
      els.nu.ep.textContent = n.ep || '';
      els.nu.title.textContent = n.title || '';
      els.nu.desc.textContent = n.desc || '';
      els.nu.stay.textContent = ctx.type === 'episode' ? 'Watch credits' : 'Back to detail';
      if (n.frame) { els.nu.img.style.display = ''; els.nu.img.src = n.frame; els.nu.g.style.display = 'none'; }
      else { els.nu.img.style.display = 'none'; els.nu.g.style.display = ''; els.nu.g.style.background = n.grad || ctx.grad; }
      focus = 'nu-play';
      countdown = COUNTDOWN; updateRing();
      clearInterval(cdTimer);
      cdTimer = setInterval(() => {
        countdown--; updateRing();
        if (countdown <= 0) { clearInterval(cdTimer); advanceNext(); }
      }, 1000);
      paintFocus();
    }
    function updateRing() {
      const C = 2 * Math.PI * 23;
      els.nu.ringFg.style.strokeDasharray = C;
      els.nu.ringFg.style.strokeDashoffset = C * (1 - countdown / COUNTDOWN);
      els.nu.ringNum.textContent = Math.max(0, countdown);
      els.nu.ct.textContent = 'in ' + Math.max(0, countdown) + 's';
    }
    function closeNextUp() { root.classList.remove('nextup'); clearInterval(cdTimer); }
    function advanceNext() {
      closeNextUp();
      const next = ctx.resolveNext ? ctx.resolveNext() : null;
      if (!next) { exit(); return; }
      load(next, true);
    }
    function stayThrough() {
      closeNextUp();
      if (ctx.type === 'episode') { showChrome(); if (!playing) setPlaying(true); }   // keep watching credits
      else exit();
    }

    /* ---------- focus paint ---------- */
    function paintFocus() {
      root.querySelectorAll('.foc.focused').forEach(e => e.classList.remove('focused'));
      if (root.classList.contains('nextup')) {
        const t = root.querySelector(`[data-pf="${focus}"]`); if (t) t.classList.add('focused');
        return;
      }
      if (pickerOpen()) { /* picker handles its own focus highlight */ return; }
      const t = root.querySelector(`[data-pf="${focus}"]`); if (t) t.classList.add('focused');
    }
    function moveFocus(d) {
      const o = order();
      let i = o.indexOf(focus); if (i < 0) i = o.indexOf('play');
      i = Math.max(0, Math.min(o.length - 1, i + d));
      focus = o[i]; paintFocus();
    }

    /* ---------- key handling ---------- */
    function onKey(e) {
      if (!open) return;
      const k = e.key;
      if (!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Enter',' ','Backspace','Escape'].includes(k)) return;
      e.preventDefault(); e.stopImmediatePropagation();
      wake();

      // NEXT-UP card owns input
      if (root.classList.contains('nextup')) {
        if (k === 'ArrowLeft') { focus = 'nu-play'; paintFocus(); }
        else if (k === 'ArrowRight') { focus = 'nu-stay'; paintFocus(); }
        else if (k === 'Enter' || k === ' ') { clearInterval(cdTimer); focus === 'nu-play' ? advanceNext() : stayThrough(); }
        else if (k === 'Backspace' || k === 'Escape') { clearInterval(cdTimer); stayThrough(); }
        return;
      }
      // EPISODE RAIL owns input
      if (root.classList.contains('eprail')) {
        if (k === 'ArrowLeft') epNav(-1);
        else if (k === 'ArrowRight') epNav(1);
        else if (k === 'ArrowUp' || k === 'Backspace' || k === 'Escape') closeEpRail();
        else if (k === 'Enter' || k === ' ') chooseEp();
        return;
      }
      // PICKER owns input
      if (pickerOpen()) {
        if (k === 'ArrowUp') pickerNav(-1);
        else if (k === 'ArrowDown') pickerNav(1);
        else if (k === 'ArrowLeft') pickerSwitchTab('audio');
        else if (k === 'ArrowRight') pickerSwitchTab('subs');
        else if (k === 'Enter' || k === ' ') pickerChoose();
        else if (k === 'Backspace' || k === 'Escape') closePicker();
        return;
      }
      // SCRUBBING on the bar
      if (focus === 'bar' && scrubbing) {
        if (k === 'ArrowLeft') scrub(-1);
        else if (k === 'ArrowRight') scrub(1);
        else if (k === 'Enter' || k === ' ') commitScrub();
        else if (k === 'Backspace' || k === 'Escape') { scrubbing = false; els.barWrap.classList.remove('scrubbing'); paintTime(); }
        else if (k === 'ArrowDown') { commitScrub(); moveFocus(1); }
        return;
      }

      if (k === 'ArrowLeft') { if (focus === 'bar') scrub(-1); else moveFocus(-1); }
      else if (k === 'ArrowRight') { if (focus === 'bar') scrub(1); else moveFocus(1); }
      else if (k === 'ArrowUp') { focus = 'bar'; paintFocus(); }
      else if (k === 'ArrowDown') { if (focus === 'bar') { focus = 'play'; paintFocus(); } else if (ctx.episodes) openEpRail(); }
      else if (k === 'Enter' || k === ' ') activate();
      else if (k === 'Backspace' || k === 'Escape') exit();
    }

    function activate() {
      switch (focus) {
        case 'bar': beginScrub(); break;
        case 'play': setPlaying(!playing); flashCenter(); break;
        case 'back10': skip(-SKIP_BACK); break;
        case 'fwd10': skip(SKIP_FWD); break;
        case 'tracks': openPicker(); break;
        case 'nextbtn': advanceNext(); break;
        case 'back': exit(); break;
      }
    }

    // wake chrome on any input / pointer
    function wake() { if (!root.classList.contains('chrome')) showChrome(); else if (playing) scheduleHide(); }

    /* ---------- pointer ---------- */
    root.addEventListener('click', e => {
      const opt = e.target.closest('.pl-opt');
      if (opt && pickerOpen()) { pickIdx = +opt.dataset.oi; root.querySelectorAll('.pl-opt').forEach((x, i) => x.classList.toggle('focused', i === pickIdx)); pickerChoose(); return; }
      const tab = e.target.closest('.pl-tab'); if (tab) { pickerSwitchTab(tab.dataset.tab); return; }
      const ec = e.target.closest('.pl-epchip'); if (ec) { openEpRail(); return; }
      const epc = e.target.closest('.pl-epc'); if (epc) { epIdx = +epc.dataset.ei; paintEpRail(); chooseEp(); return; }
      const pf = e.target.closest('[data-pf]');
      if (pf) {
        const id = pf.dataset.pf;
        if (id === 'nu-play') { clearInterval(cdTimer); advanceNext(); return; }
        if (id === 'nu-stay') { clearInterval(cdTimer); stayThrough(); return; }
        focus = id; paintFocus(); activate(); return;
      }
      // click on the bar = scrub to point + commit
      const bw = e.target.closest('.pl-bar-wrap');
      if (bw) { const r = els.barWrap.getBoundingClientRect(); const ratio = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
        focus = 'bar'; paintFocus(); beginScrub(); scrubPos = ratio * ctx.duration; paintTime(); commitScrub(); return; }
      // click on empty video = toggle play/pause
      if (e.target.closest('.pl-stage') || e.target.closest('.pl-chrome')) { if (!pickerOpen() && !root.classList.contains('nextup')) { wake(); } }
    });
    root.addEventListener('mousemove', () => { if (open) wake(); });

    /* ---------- load / open / exit ---------- */
    function load(c, autoplay) {
      ctx = c; pos = Math.max(0, Math.min(c.duration - 1, c.position || 0)); ended = false;
      scrubbing = false; els.barWrap.classList.remove('scrubbing');
      sel = { audio: c.audioDefault || 0, subs: c.subsDefault || 0 };
      focus = 'play';
      root.classList.remove('picker', 'nextup', 'eprail');
      root.classList.toggle('series', !!c.episodes);
      paintMeta(); paintTime(); paintFrame(pos); paintFocus();
      if (c.episodes) buildEpRail();
      setPlaying(false);
      showChrome();
      buffer(900, c.resumeNote ? `Resuming from <b>Jellyfin</b> · ${c.resumeNote}` : `Starting stream from <b>Jellyfin</b>…`, () => {
        setPlaying(true);
        flashCenter();
      });
    }
    function start(c) {
      open = true; ended = false;
      root.classList.add('on');
      load(c, true);
    }
    function exit() {
      stopTick(); clearTimeout(hideTimer); clearTimeout(bufferTimer); clearInterval(cdTimer);
      root.classList.remove('on', 'picker', 'nextup', 'buffering', 'chrome', 'paused');
      open = false; playing = false;
      const watched = ctx ? { title: ctx.title, pos, duration: ctx.duration } : null;
      restoreFocus(watched);
    }

    window.addEventListener('keydown', onKey, true);

    return { open: start, close: exit, isOpen: () => open };
  }

  window.initRaviloPlayer = initRaviloPlayer;
})();
