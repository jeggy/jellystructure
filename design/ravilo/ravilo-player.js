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
  const EPRAIL_HIDE_MS = 30000;   // R208: auto-close the episode rail after inactivity
  const STILL_AT = 5000;    // R237 FR-R237-5 — one extra line once a first attempt has failed

  /* Strings live in ravilo-i18n.js; the fallbacks keep the chrome legible if it is loaded alone. */
  function PT(k, f) { try { const s = window.t && window.t(k); return (s && s !== k) ? s : f; } catch (e) { return f; } }
  /* R237 — a terminal start failure says what is wrong and offers only what can resolve it.
     Retry survives ONLY where trying again can change the answer (FR-R237-3). */
  const PL_FAIL = {
    reauth:      { h: ['pl_err_reauth_h', 'This TV needs to be signed in again'], b: ['pl_err_reauth_b', 'Sign in again on this TV to keep watching.'], acts: ['signin', 'back'] },
    forbidden:   { h: ['pl_err_forbidden_h', 'Not available on this profile'], b: ['pl_err_forbidden_b', 'This title isn’t part of what this profile can watch.'], acts: ['back'] },
    gone:        { h: ['pl_err_gone_h', 'This title isn’t available any more'], b: ['pl_err_gone_b', ''], acts: ['back'] },
    unreachable: { h: ['pl_err_unreachable_h', 'Couldn’t reach the server'], b: ['pl_err_unreachable_b', 'Check the connection and try again.'], acts: ['retry', 'back'] },
  };
  const PL_ACT = { signin: ['pl_sign_in', 'Sign in'], retry: ['pl_retry', 'Try again'], back: ['pl_back', 'Back'] };

  // simple inline icons
  const I = {
    play:  '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>',
    back10:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4 6 9l5 5"/><path d="M6 9h8a5 5 0 0 1 0 10h-3"/></svg>',
    fwd10: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 4 18 9l-5 5"/><path d="M18 9h-8a5 5 0 0 0 0 10h3"/></svg>',
    next:  '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 5v14l9-7z"/><rect x="16" y="5" width="3" height="14" rx="1"/></svg>',
    scene: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.4 7.4H22l-6 4.5 2.3 7.4-6.3-4.6-6.3 4.6L7.9 13.9 2 9.4h7.6z"/></svg>',
    skipfwd: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4l8 8-8 8"/><path d="M15 4v16"/></svg>',
    cc:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M9.5 10.2A2.2 2.2 0 0 0 7.8 13.4 2.2 2.2 0 0 0 9.5 14M16 10.2a2.2 2.2 0 0 0-1.7 3.2 2.2 2.2 0 0 0 1.7.6"/></svg>',
    chev:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5 8 12l7 7"/></svg>',
    audio: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 8a5 5 0 0 1 0 8"/></svg>',
    chevdown: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>',
    mic:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4"/></svg>',
    subsoff:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 14h4M15 14h2"/><path d="M4 4l16 16"/></svg>',
  };

  // ISO-639-1/2 -> flag-icons country code (from ravilo-app.js LANG_CC)
  // R239 FR-R239-3 (2026-09-12) parity — same 9 codes added to ravilo-app.js's LANG_CC.
  const PL_CC = {
    en:'gb', fr:'fr', de:'de', es:'es', da:'dk', fo:'fo', is:'is', no:'no', sv:'se', fi:'fi', nl:'nl', it:'it', pt:'pt', pl:'pl', ru:'ru', ja:'jp', ko:'kr', zh:'cn',
    sr:'rs', srp:'rs', bg:'bg', bul:'bg', id:'id', ind:'id', ms:'my', msa:'my', may:'my', sl:'si', slv:'si', et:'ee', est:'ee', lv:'lv', lav:'lv', lt:'lt', lit:'lt', tl:'ph', fil:'ph',
  };
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
      <div class="pl-buffer"><div class="pl-spin"></div><div class="blab"></div><div class="bstill"></div></div>
      <div class="pl-fail"><div class="pf-h"></div><div class="pf-b"></div><div class="pf-acts"></div></div>

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

      <div class="pl-skipintro" data-pf="skipintro">
        ${I.skipfwd}<span class="si-label">Skip Intro</span><span class="si-kbd">OK</span>
        <span class="si-fill"></span>
      </div>

      <div class="pl-picker">
        <div class="pl-picker-head">
          <div class="pl-tab foc cur" data-tab="audio">${I.audio}<span>Audio</span><span class="pl-tab-flag" data-flag="audio"></span></div>
          <div class="pl-tab foc" data-tab="subs">${I.cc}<span>Subtitles</span><span class="pl-tab-flag" data-flag="subs"></span></div>
        </div>
        <div class="pl-cols"></div>
      </div>

      <div class="pl-nextup">
        <div class="nu-kick"><span class="nu-kicktext">Up Next</span><span class="nu-stinger">${I.scene}<span>Scene after the credits</span></span></div>
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
      nextBtn: q('.nextbtn'), buffer: q('.pl-buffer'), blab: q('.pl-buffer .blab'), bstill: q('.pl-buffer .bstill'),
      fail: q('.pl-fail'), failH: q('.pl-fail .pf-h'), failB: q('.pl-fail .pf-b'), failActs: q('.pl-fail .pf-acts'),
      pickCols: q('.pl-cols'),
      nu: { ep: q('.pl-nu-ep'), title: q('.pl-nu-title'), desc: q('.pl-nu-desc'),
            img: q('.pl-nu-shot img'), g: q('.pl-nu-shot .g'), kick: q('.nu-kicktext'),
            ringFg: q('.nu-ring-fg'), ringNum: q('.nu-ring-num'), ct: q('.pl-nu-btn .ct'), stay: q('.nu-stay-label'),
            primary: q('[data-pf="nu-play"]'), ring: q('.pl-nu-shot .ring'), stinger: q('.nu-stinger') },
      si: q('.pl-skipintro'), siFill: q('.si-fill'),
      epchipText: q('.pl-epchip .ectext'), erSeason: q('.er-season'), erTrack: q('.er-track'),
    };

    // ---- state ----
    let ctx = null, pos = 0, playing = false, ended = false;
    let open = false, scrubbing = false, scrubPos = 0;
    let pickerTab = 'audio', pickIdx = 0, sel = { audio: 0, subs: 0 };
    let pickLevel = 'lang', pickGroup = 0;   // R195: two-level picker
    let focus = 'play';           // current focusable id in transport
    let epIdx = 0;                // focused episode in the rail
    let countdown = COUNTDOWN;
    let tickTimer = null, hideTimer = null, bufferTimer = null, cdTimer = null, stillTimer = null, epRailTimer = null;
    let failActs = [], failIdx = 0;   // R237's failed-start card
    let introEntered = false, skipPromptOn = false, skipTimer = null;
    let cardMode = 'next', cardDismissed = false;

    // transport focus order; nextbtn only when series
    function order() {
      const o = [];
      if (root.classList.contains('skipintro')) o.push('skipintro');
      o.push('bar', 'back10', 'play', 'fwd10', 'tracks');
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
      refreshSkipIntro();
      clearTimeout(hideTimer);
      if (playing && !pickerOpen() && !root.classList.contains('nextup')) scheduleHide();
    }
    function scheduleHide() {
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => {
        if (playing && !pickerOpen() && !root.classList.contains('nextup') && !scrubbing) {
          root.classList.remove('chrome'); renderSub(); refreshSkipIntro();
        }
      }, HIDE_MS);
    }
    function pickerOpen() { return root.classList.contains('picker'); }

    /* ---------- playback tick ---------- */
    function startTick() { stopTick(); tickTimer = setInterval(() => {
      if (!playing || scrubbing) return;
      pos = Math.min(ctx.duration, pos + 1);
      paintTime(); paintFrame(pos);
      handleIntroTick();
      const seg = ctx.segments;
      const creditsAt = seg && seg.creditsStart ? seg.creditsStart : null;
      const hasCard = !!(creditsAt || ctx.nextMeta);
      const reached = creditsAt != null ? pos >= creditsAt : (ctx.duration - pos) <= NEXTUP_AT;
      if (hasCard && reached && !cardDismissed && !root.classList.contains('nextup') && !root.classList.contains('eprail')) openCreditsCard();
      if (pos >= ctx.duration) onEnd();
    }, 1000); }
    function handleIntroTick() {
      const s = ctx && ctx.segments;
      if (!s || !(s.introEnd > s.introStart)) return;
      const inside = pos >= s.introStart && pos < s.introEnd;
      if (inside && !introEntered) { introEntered = true; skipPromptOn = true; startSkipCountdown(s.skipSecs || 6); }
      if (!inside && introEntered) { introEntered = false; skipPromptOn = false; }
      refreshSkipIntro();
    }
    function stopTick() { if (tickTimer) clearInterval(tickTimer); tickTimer = null; }

    function onEnd() {
      stopTick(); ended = true;
      if (ctx.nextMeta || (ctx.segments && ctx.segments.stinger)) { if (!root.classList.contains('nextup')) openCreditsCard(); }
      else { setPlaying(false); flash('✓ Finished · ' + ctx.title); exit(); }
    }

    /* ---------- buffering ---------- */
    /* R218 — one treatment, one string, whatever the cause: no delivery method, no timing hint
       (R180 FR-RV-ASP1-2). R237 FR-R237-5 adds exactly ONE line, at ~5 s, and only where a first
       attempt can have failed — a session start, never a seek. Nothing else about R218 changes. */
    function buffer(ms, label, then, opts) {
      root.classList.add('buffering');
      els.blab.innerHTML = label || PT('pl_loading', 'Loading…');
      els.bstill.textContent = '';
      clearTimeout(stillTimer);
      if (opts && opts.still) stillTimer = setTimeout(() => { els.bstill.textContent = PT('pl_still', 'Still trying…'); }, STILL_AT);
      clearTimeout(bufferTimer);
      bufferTimer = setTimeout(() => {
        root.classList.remove('buffering'); clearTimeout(stillTimer); els.bstill.textContent = '';
        if (then) then();
      }, ms);
    }
    /* A start that cannot succeed stops pretending to load. Mockup-only trigger: ?playfail=
       (reauth | forbidden | gone | unreachable | slow) — a mock has no real 409 to classify. */
    function failStart(kind) {
      const f = PL_FAIL[kind]; if (!f) return;
      stopTick(); clearTimeout(bufferTimer); clearTimeout(stillTimer);
      root.classList.remove('buffering', 'chrome', 'picker', 'nextup', 'eprail');
      els.bstill.textContent = '';
      els.failH.textContent = PT(f.h[0], f.h[1]);
      els.failB.textContent = PT(f.b[0], f.b[1]);
      els.failB.style.display = els.failB.textContent ? '' : 'none';
      failActs = f.acts; failIdx = 0;
      els.failActs.innerHTML = f.acts.map((a, i) =>
        `<div class="pf-btn${i === 0 ? ' foc' : ''}${a !== 'back' ? ' pri' : ''}" data-pfa="${a}">${PT(PL_ACT[a][0], PL_ACT[a][1])}</div>`).join('');
      root.classList.add('failed');
    }
    function paintFailFocus() {
      [...els.failActs.children].forEach((el, i) => el.classList.toggle('foc', i === failIdx));
    }
    function failAct() {
      const a = failActs[failIdx];
      if (a === 'retry') { root.classList.remove('failed'); showChrome(); buffer(1400, null, () => setPlaying(true), { still: true }); return; }
      if (a === 'signin') flash('Sign in — this profile signs in again');   // R234's existing forced sign-out exit
      root.classList.remove('failed'); exit();
    }

    /* ---------- scrubbing ---------- */
    function beginScrub() { if (scrubbing) return; scrubbing = true; scrubPos = pos; els.barWrap.classList.add('scrubbing'); showChrome(); paintTime(); }
    function scrub(dir) { beginScrub(); const step = Math.max(5, Math.round(ctx.duration * 0.012)); scrubPos = Math.max(0, Math.min(ctx.duration, scrubPos + dir * step)); paintTime(); }
    function commitScrub() {
      if (!scrubbing) return;
      const to = scrubPos; scrubbing = false; els.barWrap.classList.remove('scrubbing');
      buffer(550, PT('pl_seeking', 'Seeking…'), () => { pos = to; ended = false; paintTime(); paintFrame(pos); if (playing) startTick(); });
      paintTime();
    }
    function skip(sec) {
      pos = Math.max(0, Math.min(ctx.duration, pos + sec)); ended = false;
      paintTime(); paintFrame(pos); showChrome();
      els.pausemark.innerHTML = sec > 0 ? I.fwd10 : I.back10; // brief glyph reuse
    }

    /* ---------- track picker (R195: language first, versions inside) ---------- */
    function plGroups(list) {
      const g = [], by = {};
      list.forEach((o, i) => {
        const key = o.off ? '__off' : (o.lang || ('x' + (o.kind || o.label)));
        if (!by[key]) { by[key] = { key, lang: o.lang, off: o.off, label: o.label, items: [] }; g.push(by[key]); }
        by[key].items.push({ o, i });
      });
      return g;
    }
    // true when nothing but stream order tells these apart
    function plSameSig(items) {
      const sig = x => [x.o.label, x.o.kind || '', x.o.sub || '', x.o.region || ''].join('|');
      return items.length > 1 && items.every(x => sig(x) === sig(items[0]));
    }
    function plVarName(g, o) {
      if (o.kind === 'commentary') return 'Commentary';
      return o.sub ? `${g.label} <span class="on-sub">${o.sub}</span>` : g.label;
    }
    function plBlurb(o) {
      if (o.kind === 'sdh') return 'Adds speaker names and sound-effect notes.';
      if (o.kind === 'forced') return 'Only the on-screen text and foreign lines.';
      if (o.kind === 'describe') return 'Narrates what happens on screen.';
      if (o.kind === 'commentary') return 'A recorded commentary on this title.';
      if (o.sub) return `The ${o.sub} version.`;
      return 'The full version of everything spoken.';
    }
    function openPicker() { root.classList.add('picker'); showChrome(); pickerTab = 'audio'; pickLevel = 'lang'; pickIdx = plLevelIndexOf(sel.audio); renderPicker(); }
    function closePicker() { root.classList.remove('picker'); pickLevel = 'lang'; scheduleHide(); }
    function plLevelIndexOf(trackIdx) {
      const gs = plGroups(pickerTab === 'audio' ? ctx.audio : ctx.subs);
      const n = gs.findIndex(g => g.items.some(x => x.i === trackIdx));
      return n < 0 ? 0 : n;
    }
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
      const groups = plGroups(list);
      root.classList.toggle('pk-sub-level', pickLevel === 'var');
      if (pickLevel === 'var') {
        const g = groups[Math.min(pickGroup, groups.length - 1)];
        const ord = plSameSig(g.items);
        els.pickCols.innerHTML =
          `<div class="pl-crumb">${plFlag(g.items[0].o)}<span class="cr-t">${g.label}</span>` +
          `<span class="cr-s">${g.items.length} versions</span></div>` +
          g.items.map((x, n) => {
            const o = x.o, rgn = o.region && PL_CC[o.lang] !== o.region;
            return `<div class="pl-opt pl-var foc${x.i === selIdx ? ' sel' : ''}${n === pickIdx ? ' focused' : ''}" data-oi="${n}">
               <span class="tick">✓</span>
               ${rgn ? `<span class="fi fi-${o.region} pl-rgn"></span>` : '<span class="pl-rgn ghost"></span>'}
               <span class="ol"><span class="on2">${plVarName(g, o)}</span>` +
               plBadges(Object.assign({}, o, ord ? { note: `Version ${n + 1} of ${g.items.length}` } : {}), isAudio) +
               `<span class="pl-vh">${plBlurb(o)}</span></span></div>`;
          }).join('');
      } else {
        els.pickCols.innerHTML = groups.map((g, n) => {
          const multi = g.items.length > 1;
          const isSel = g.items.some(x => x.i === selIdx);
          return `<div class="pl-opt foc${isSel ? ' sel' : ''}${n === pickIdx ? ' focused' : ''}" data-oi="${n}">
             <span class="tick">✓</span>
             ${plFlag(g.items[0].o)}
             <span class="ol"><span class="on2">${g.label}</span>${multi ? '' : plBadges(g.items[0].o, isAudio)}</span>
             ${multi ? `<span class="pl-more">${g.items.length} versions ›</span>` : ''}
           </div>`;
        }).join('');
      }
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
    function plLevelLen() {
      const groups = plGroups(pickerTab === 'audio' ? ctx.audio : ctx.subs);
      return pickLevel === 'var' ? groups[Math.min(pickGroup, groups.length - 1)].items.length : groups.length;
    }
    function pickerNav(d) {
      pickIdx = Math.max(0, Math.min(plLevelLen() - 1, pickIdx + d));
      root.querySelectorAll('.pl-opt').forEach((e, i) => e.classList.toggle('focused', i === pickIdx));
      scrollFocusIntoView();
    }
    function pickerSwitchTab(tab) { if (tab === pickerTab) return; pickerTab = tab; pickLevel = 'lang'; pickIdx = plLevelIndexOf(tab === 'audio' ? sel.audio : sel.subs); renderPicker(); }
    function pickerBack() { if (pickLevel === 'var') { pickLevel = 'lang'; pickIdx = pickGroup; renderPicker(); } else closePicker(); }
    function pickerChoose() {
      const isAudio = pickerTab === 'audio';
      const groups = plGroups(isAudio ? ctx.audio : ctx.subs);
      if (pickLevel === 'lang') {
        const g = groups[pickIdx];
        if (g.items.length > 1) { pickGroup = pickIdx; pickLevel = 'var'; pickIdx = 0; renderPicker(); return; }
        if (isAudio) sel.audio = g.items[0].i; else sel.subs = g.items[0].i;
        renderPicker(); renderSub();
        flash((isAudio ? '🔊 Audio · ' : '💬 Subtitles · ') + g.label);
        return;
      }
      const g = groups[pickGroup], x = g.items[pickIdx];
      if (isAudio) sel.audio = x.i; else sel.subs = x.i;
      renderPicker(); renderSub();
      flash((isAudio ? '🔊 Audio · ' : '💬 Subtitles · ') + g.label + (x.o.kind ? ' · ' + (PL_KIND[x.o.kind] || '') : ''));
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
    function armEpRailTimer() { clearTimeout(epRailTimer); epRailTimer = setTimeout(closeEpRail, EPRAIL_HIDE_MS); }
    function openEpRail() { if (!ctx.episodes) return; root.classList.add('eprail'); showChrome(); clearTimeout(hideTimer); epIdx = ctx.epIndex; paintEpRail(); armEpRailTimer(); }
    function closeEpRail() { clearTimeout(epRailTimer); root.classList.remove('eprail'); paintFocus(); scheduleHide(); }
    function epNav(d) { epIdx = Math.max(0, Math.min(ctx.episodes.length - 1, epIdx + d)); paintEpRail(); armEpRailTimer(); }
    function chooseEp() {
      if (epIdx === ctx.epIndex) { closeEpRail(); return; }
      const c = ctx.resolveEpisode ? ctx.resolveEpisode(epIdx) : null;
      if (c) load(c, true); else closeEpRail();
    }

    /* ---------- skip intro (R18x segment-driven) ---------- */
    function introActiveNow() {
      const s = ctx && ctx.segments;
      return !!(s && s.introEnd > s.introStart && pos >= s.introStart && pos < s.introEnd);
    }
    function startSkipCountdown(secs) {
      clearTimeout(skipTimer);
      const f = els.siFill;
      if (f) { f.style.transition = 'none'; f.style.width = '100%'; void f.offsetWidth;
        f.style.transition = 'width ' + secs + 's linear'; f.style.width = '0%'; }
      skipTimer = setTimeout(() => { skipPromptOn = false; refreshSkipIntro(); }, secs * 1000);
    }
    function refreshSkipIntro() {
      const active = introActiveNow();
      const show = active && (skipPromptOn || root.classList.contains('chrome'))
        && !pickerOpen() && !root.classList.contains('nextup') && !root.classList.contains('eprail');
      const was = root.classList.contains('skipintro');
      root.classList.toggle('skipintro', show);
      if (show && !was) { focus = 'skipintro'; paintFocus(); }
      if (!show && was && focus === 'skipintro') { focus = 'play'; paintFocus(); }
    }
    function skipIntro() {
      const s = ctx && ctx.segments; if (!s) return;
      pos = Math.min(ctx.duration - 1, s.introEnd); ended = false;
      introEntered = false; skipPromptOn = false; clearTimeout(skipTimer);
      root.classList.remove('skipintro'); focus = 'play';
      paintTime(); paintFrame(pos); paintFocus();
      flash('⏭ Skipped intro'); showChrome();
    }

    /* ---------- credits / next-up card (stinger-aware, R18x) ----------
       Always offers 'Watch credits' (dismiss) + exactly ONE action, by priority:
       Skip to scene (stinger) → Next Episode (series) → Skip credits (fallback). */
    function creditsMode() {
      const s = ctx && ctx.segments;
      if (s && s.stinger) return 'stinger';
      if (ctx && ctx.nextMeta) return 'next';
      return 'skip';
    }
    function openCreditsCard() {
      root.classList.add('nextup'); root.classList.remove('skipintro'); showChrome();
      const mode = creditsMode(); cardMode = mode;
      if (els.nu.stinger) els.nu.stinger.style.display = mode === 'stinger' ? '' : 'none';
      els.nu.kick.style.display = mode === 'stinger' ? 'none' : '';
      els.nu.kick.textContent = mode === 'next' ? 'Up Next' : 'Credits';
      let thumbGrad = ctx.grad, thumbFrame = null;
      if (mode === 'next') {
        const n = ctx.nextMeta;
        els.nu.ep.textContent = n.ep || '';
        els.nu.title.textContent = n.title || '';
        els.nu.desc.textContent = n.desc || '';
        thumbGrad = n.grad || ctx.grad; thumbFrame = n.frame || null;
      } else if (mode === 'stinger') {
        els.nu.ep.textContent = ctx.type === 'episode' ? ctx.kicker : (ctx.title || '');
        els.nu.title.textContent = 'There’s a scene after this';
        els.nu.desc.textContent = 'A scene plays after the credits — auto-skip is paused so you don’t miss it.';
      } else {
        els.nu.ep.textContent = ctx.type === 'episode' ? ctx.kicker : (ctx.title || '');
        els.nu.title.textContent = 'End of ' + (ctx.type === 'episode' ? 'episode' : 'film');
        els.nu.desc.textContent = 'The credits are rolling.';
      }
      if (thumbFrame) { els.nu.img.style.display = ''; els.nu.img.src = thumbFrame; els.nu.g.style.display = 'none'; }
      else { els.nu.img.style.display = 'none'; els.nu.g.style.display = ''; els.nu.g.style.background = thumbGrad; }
      const P = els.nu.primary;
      if (P) {
        if (mode === 'stinger') P.innerHTML = I.scene + '<span>Skip to scene</span>';
        else if (mode === 'next') P.innerHTML = I.next + '<span>Next Episode <span class="ct">in ' + COUNTDOWN + 's</span></span>';
        else P.innerHTML = I.skipfwd + '<span>Skip credits</span>';
        els.nu.ct = P.querySelector('.ct');
      }
      els.nu.stay.textContent = 'Watch credits';
      focus = 'nu-play';
      clearInterval(cdTimer);
      if (mode === 'next') {
        if (els.nu.ring) els.nu.ring.style.display = '';
        countdown = COUNTDOWN; updateRing();
        cdTimer = setInterval(() => { countdown--; updateRing(); if (countdown <= 0) { clearInterval(cdTimer); advanceNext(); } }, 1000);
      } else {
        if (els.nu.ring) els.nu.ring.style.display = 'none';
      }
      paintFocus();
    }
    function updateRing() {
      const C = 2 * Math.PI * 23;
      els.nu.ringFg.style.strokeDasharray = C;
      els.nu.ringFg.style.strokeDashoffset = C * (1 - countdown / COUNTDOWN);
      els.nu.ringNum.textContent = Math.max(0, countdown);
      if (els.nu.ct) els.nu.ct.textContent = 'in ' + Math.max(0, countdown) + 's';
    }
    function closeNextUp() { root.classList.remove('nextup'); clearInterval(cdTimer); }
    function cardPrimary() {
      if (cardMode === 'stinger') { seekToStinger(); return; }
      if (cardMode === 'next') { advanceNext(); return; }
      closeNextUp();
      const next = ctx.resolveNext ? ctx.resolveNext() : null;
      if (next) load(next, true); else exit();
    }
    function seekToStinger() {
      const s = ctx && ctx.segments;
      closeNextUp(); cardDismissed = true;
      if (s && s.stinger) { pos = Math.min(ctx.duration - 1, s.stinger.at); ended = false; paintTime(); paintFrame(pos); }
      if (!playing) setPlaying(true); else showChrome();
      flash('⏭ Jumped to the post-credits scene');
    }
    function advanceNext() {
      closeNextUp();
      const next = ctx.resolveNext ? ctx.resolveNext() : null;
      if (!next) { exit(); return; }
      load(next, true);
    }
    function stayThrough() {
      closeNextUp(); cardDismissed = true;
      if (ctx.type === 'episode' || (ctx.segments && ctx.segments.creditsStart)) { showChrome(); if (!playing) setPlaying(true); }
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

      // FAILED START owns input (R237) — Back always leaves, as it does in every other state
      if (root.classList.contains('failed')) {
        if (k === 'ArrowLeft') { failIdx = Math.max(0, failIdx - 1); paintFailFocus(); }
        else if (k === 'ArrowRight') { failIdx = Math.min(failActs.length - 1, failIdx + 1); paintFailFocus(); }
        else if (k === 'Enter' || k === ' ') failAct();
        else if (k === 'Backspace' || k === 'Escape') { root.classList.remove('failed'); exit(); }
        return;
      }

      // NEXT-UP card owns input
      if (root.classList.contains('nextup')) {
        if (k === 'ArrowLeft') { focus = 'nu-play'; paintFocus(); }
        else if (k === 'ArrowRight') { focus = 'nu-stay'; paintFocus(); }
        else if (k === 'Enter' || k === ' ') { clearInterval(cdTimer); focus === 'nu-play' ? cardPrimary() : stayThrough(); }
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
        else if (k === 'Backspace' || k === 'Escape') pickerBack();
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
      else if (k === 'ArrowDown') { if (focus === 'bar') { focus = 'play'; paintFocus(); } else if (focus === 'skipintro') { focus = 'play'; paintFocus(); } else if (ctx.episodes) openEpRail(); }
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
        case 'skipintro': skipIntro(); break;
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
        if (id === 'nu-play') { clearInterval(cdTimer); cardPrimary(); return; }
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
      introEntered = false; skipPromptOn = false; cardDismissed = false; clearTimeout(skipTimer);
      clearTimeout(epRailTimer);
      root.classList.remove('picker', 'nextup', 'eprail', 'skipintro');
      root.classList.toggle('series', !!c.episodes);
      paintMeta(); paintTime(); paintFrame(pos); paintFocus();
      if (c.episodes) buildEpRail();
      setPlaying(false);
      showChrome();
      /* One string for every cause (R218). The ?playfail= knob is a mockup affordance: `slow` runs the
         cold start long enough to show R237's extra line, the four causes render its failure card. */
      const forced = (new URLSearchParams(location.search).get('playfail') || '').toLowerCase();
      if (PL_FAIL[forced]) { buffer(1200, null, () => failStart(forced), { still: false }); return; }
      buffer(forced === 'slow' ? 14000 : 900, null, () => {
        setPlaying(true);
        flashCenter();
      }, { still: true });
    }
    function start(c) {
      open = true; ended = false;
      root.classList.add('on');
      load(c, true);
    }
    function exit() {
      stopTick(); clearTimeout(hideTimer); clearTimeout(bufferTimer); clearTimeout(stillTimer); clearInterval(cdTimer); clearTimeout(epRailTimer);
      root.classList.remove('on', 'picker', 'nextup', 'buffering', 'chrome', 'paused', 'failed');
      open = false; playing = false;
      const watched = ctx ? { title: ctx.title, pos, duration: ctx.duration } : null;
      restoreFocus(watched);
    }

    window.addEventListener('keydown', onKey, true);

    return { open: start, close: exit, isOpen: () => open };
  }

  window.initRaviloPlayer = initRaviloPlayer;
})();
