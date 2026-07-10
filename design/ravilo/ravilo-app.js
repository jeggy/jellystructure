/* Ravilo app engine — render + hero carousel + D-pad focus + viewport scaling.
   mountRavilo(stage, { interactive }) where stage is the 1920×1080 element. */
(function () {
  const R = window.RAVILO;
  const W = R.watched;

  function el(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function artFill(item) { return item.image ? `<img class="tile-img" src="${item.image}" alt="">` : `<div class="grad" style="background:${item.grad}"></div><div class="tt">${item.title}</div>`; }
  function artGrad(item) { return `<div class="grad" style="background:${item.grad}"></div><div class="tt">${item.title}</div>`; }

  function mountRavilo(stage, opts) {
    opts = opts || {};
    const interactive = opts.interactive !== false;
    // set the interface language from the active user (set in Jellystructure) before building chrome
    try {
      const uid = localStorage.getItem('js-ravilo-user');
      const u = (R.profiles || []).find(p => p.id === uid);
      if (u && window.setRaviloLang) window.setRaviloLang(resolveLang(u));
    } catch (e) {}
    try { const sk = localStorage.getItem('js-ravilo-skin'); if (sk) document.documentElement.setAttribute('data-skin', sk); } catch (e) {}
    let view = { type: 'home', studio: null };
    let autoSeasonDone = null;   // R150 — per-series guard for season auto-select
    let heroIdx = 0, heroTimer = null;

    // ---- app bar ----
    const appbar = el('div', 'appbar');
    appbar.innerHTML = `
      <div class="brand">
        <svg class="mark" viewBox="12 20 76 76" aria-hidden="true">
          <defs><linearGradient id="ravJelly" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="var(--accent)"/><stop offset="1" stop-color="var(--accent-2)"/></linearGradient></defs>
          <path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#ravJelly)"/>
          <g stroke="url(#ravJelly)" stroke-width="4.5" stroke-linecap="round" fill="none">
            <path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/>
            <path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/>
            <path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/>
            <path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/>
          </g>
        </svg>
        <span class="wm">Ravilo</span>
      </div>
      <div class="topnav focus-row">
        <div class="navitem foc cur" data-nav="home">${t('nav_home')}</div>
        <div class="navitem foc" data-nav="movies">${t('nav_movies')}</div>
        <div class="navitem foc" data-nav="series">${t('nav_series')}</div>
        <div class="navitem foc" data-nav="discover" id="rv-nav-discover">${t('nav_discover')}</div>
      </div>
      <div class="right">
        <div class="search-ic foc" data-nav="search"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"></circle><line x1="16.5" y1="16.5" x2="21" y2="21"></line></svg></div>
        <div class="clock"></div>
        <div class="avatar foc" data-nav="profile" id="rv-avatar">ER</div>
      </div>`;
    stage.appendChild(appbar);

    // ---- profile menu (avatar dropdown: My List / Continue / Settings / Unpair / Switch) ----
    const profmenu = el('div', 'profmenu');
    profmenu.innerHTML = `
      <div class="pm-head">
        <div class="pm-av" id="pm-av">ER</div>
        <div class="pm-id"><div class="pm-name" id="pm-name">Profile</div><div class="pm-sub" id="pm-sub"></div></div>
        <div class="pm-switch foc" data-pm="switch">${t('pm_switch')}</div>
      </div>
      <div class="pm-div"></div>
      <div class="pm-row foc" data-pm="mylist"><span class="pm-ic">＋</span> ${t('nav_mylist')}</div>
      <div class="pm-row foc" data-pm="adduser"><span class="pm-ic">＋</span> ${t('add_user')}</div>
      <div class="pm-row foc" data-pm="settings"><span class="pm-ic">⚙</span> ${t('pm_settings')}</div>
      <div class="pm-row foc" data-pm="unpair"><span class="pm-ic">⏏</span> ${t('pm_unpair')}</div>`;
    stage.appendChild(profmenu);
    function pmItems() { return [...profmenu.querySelectorAll('.foc')]; }
    function openProfMenu() {
      const u = currentUser() || {};
      const av = profmenu.querySelector('#pm-av'); if (av) { av.textContent = u.initials || 'ER'; if (u.color) av.style.background = u.color; }
      const nm = profmenu.querySelector('#pm-name'); if (nm) nm.textContent = u.name || 'Profile';
      const sb = profmenu.querySelector('#pm-sub'); if (sb) sb.textContent = (u.kid ? 'Kids · ' : '') + String(u.lang || 'en').toUpperCase();
      profmenu.classList.add('on'); stopHero();
      pmItems().forEach(e => e.classList.remove('focused'));
      const first = profmenu.querySelector('[data-pm="mylist"]'); if (first) first.classList.add('focused');
    }
    function closeProfMenu() { profmenu.classList.remove('on'); if (view.type === 'home') startHero(); }
    function moveProfMenu(dr) {
      if (!dr) return; const its = pmItems();
      let i = its.findIndex(e => e.classList.contains('focused')); if (i < 0) i = 0;
      i = Math.max(0, Math.min(its.length - 1, i + dr));
      its.forEach(e => e.classList.remove('focused')); its[i].classList.add('focused');
    }
    function activateProfMenu() {
      const f = profmenu.querySelector('.foc.focused'); if (!f) return;
      const a = f.dataset.pm; closeProfMenu();
      if (a === 'mylist') go({ type: 'grid', kind: 'mylist', title: t('nav_mylist'), nav: 'mylist' });
      else if (a === 'adduser') openSignin();
      else if (a === 'settings') openSettings();
      else if (a === 'switch') openProfiles('switch');
      else if (a === 'unpair') { renderUnpairConfirm(); prof.style.display = 'flex'; }
    }
    profmenu.addEventListener('click', e => { e.stopPropagation(); const r = e.target.closest('.foc'); if (!r) return; pmItems().forEach(x => x.classList.remove('focused')); r.classList.add('focused'); activateProfMenu(); });

    const screen = el('div', 'screen');
    const scroll = el('div', 'screen-scroll');
    screen.appendChild(scroll);
    stage.appendChild(screen);

    // ---- detail overlay ----
    const overlay = el('div', 'overlay');
    overlay.innerHTML = `<div class="sheet"><div class="art"><div class="grad"></div></div>
      <div class="info"><h2></h2><div class="m"></div><p></p>
      <div class="acts"><div class="btn primary foc" data-ov="play"><span class="ic">▶</span> ${t('play')}</div>
      <div class="btn ghost foc" data-ov="list"><span class="ic">＋</span> ${t('add_list')}</div>
      <div class="btn ghost foc" data-ov="close">${t('close')}</div></div></div></div>`;
    stage.appendChild(overlay);

    /* ---------------- PLAYER (R14) ----------------
       Builds a player context from a library item and opens the shared chrome.
       In production these fields come from POST /api/tv/playback/start (the
       StreamTicket) + the detail payload; the engine streams bytes straight
       from Jellyfin while progress flows back through /api/tv/playback/*. */
    const BBB_FRAMES = ['assets/bbb-backdrop-opening.png', 'assets/bbb-backdrop-landscape.png', 'assets/bbb-backdrop-bunny.png', 'assets/bbb-backdrop-rodents.png'];
    const SAMPLE_FO = 'Hann er farin. Báturin kom aldri aftur.';
    function isBBB(item) { return item && item.title === 'Big Buck Bunny'; }
    function tracksFor(item) {
      // audio tracks carry an optional ISO-639-1 `lang`; tracks with no language tag
      // (e.g. commentary) intentionally omit it so the flag strip skips them.
      if (isBBB(item)) return {
        audio: [
          { label: 'English', lang: 'en', desc: 'Stereo · AAC' },
          { label: "Director's Commentary", desc: 'Stereo · AAC' },
          { label: 'Føroyskt', lang: 'fo', desc: '5.1 · AC-3 · dub' },
          { label: 'Dansk', lang: 'da', desc: '5.1 · E-AC-3 · dub' },
          { label: 'Deutsch', lang: 'de', desc: '5.1 · AC-3 · dub' },
          { label: 'Español', lang: 'es', desc: 'Stereo · AAC · dub' },
          { label: 'Français', lang: 'fr', desc: 'Stereo · AAC · dub' },
          { label: 'Italiano', lang: 'it', desc: 'Stereo · AAC · dub' },
          { label: 'Nederlands', lang: 'nl', desc: 'Stereo · AAC · dub' },
          { label: 'Português', lang: 'pt', desc: 'Stereo · AAC · dub' },
        ],
        subs: [{ label: 'Off', off: true }, { label: 'English', lang: 'en' }, { label: 'Føroyskt', lang: 'fo', desc: 'Faroese' }, { label: 'Dansk', lang: 'da' }],
        audioDefault: 0, subsDefault: 0,
      };
      return {
        audio: [
          { label: 'Føroyskt', lang: 'fo', desc: '5.1 · AC-3' },
          { label: 'Dansk', lang: 'da', desc: '5.1 · E-AC-3 · dub' },
          { label: 'English', lang: 'en', desc: 'Stereo · AAC · dub' },
        ],
        subs: [{ label: 'Off', off: true }, { label: 'Føroyskt', lang: 'fo', desc: 'Full' }, { label: 'English', lang: 'en' }, { label: 'Dansk', lang: 'da', desc: 'Signs only', flag: 'Forced' }],
        audioDefault: 0, subsDefault: 1,
      };
    }
    function streamFor(item) {
      if (isBBB(item)) return { mode: 'Direct Play', detail: 'MP4 · H.264 · 1080p' };
      const hls = item.title.length % 5 === 0;
      return hls ? { mode: 'HLS', detail: 'Transcode · H.264 · 720p', hls: true } : { mode: 'Direct Play', detail: 'HEVC · 1080p' };
    }
    function durFor(item) { return isBBB(item) ? 596 : (item.kind === 'series' ? 52 : 112) * 60; }
    function movieCtx(item) {
      const ts = tracksFor(item), dur = durFor(item);
      const mst = W.itemState(item.title), mp = mst.pct || item.pct || 0;
      const resume = (mp > 0 && mp < 100) ? Math.round(dur * mp / 100) : 0;
      return {
        type: 'film', kicker: item.tagline || 'Film', title: item.title,
        sub2: `${item.year || ''} · ${item.genre || ''} · <b>${item.rating}+</b>`,
        duration: dur, position: resume,
        resumeNote: resume ? Math.round((dur - resume) / 60) + ' min left' : null,
        frames: isBBB(item) ? BBB_FRAMES : null, grad: item.grad,
        stream: streamFor(item), audio: ts.audio, subs: ts.subs, audioDefault: ts.audioDefault, subsDefault: ts.subsDefault,
        sampleSub: isBBB(item) ? 'It’s going to be a beautiful day.' : SAMPLE_FO,
        nextMeta: null, resolveNext: null,
      };
    }
    function episodeCtx(seriesItem, season, eps, idx) {
      const e = eps[idx], ts = tracksFor(seriesItem), dur = (parseInt(e.dur) || 50) * 60;
      const est = W.epState(seriesItem.title, season, e.n, e.pct);
      const resume = (est.pct > 0 && est.pct < 100) ? Math.round(dur * est.pct / 100) : 0;
      const ni = idx + 1, hasNext = ni < eps.length;
      return {
        type: 'episode', kicker: seriesItem.title, title: e.title,
        sub2: `S${season + 1}:E${e.n} · ${e.dur} · <b>${seriesItem.rating}+</b>`,
        duration: dur, position: resume,
        resumeNote: resume ? Math.round((dur - resume) / 60) + ' min left' : null,
        frames: null, grad: e.grad,
        stream: streamFor(seriesItem), audio: ts.audio, subs: ts.subs, audioDefault: ts.audioDefault, subsDefault: ts.subsDefault,
        sampleSub: SAMPLE_FO,
        nextMeta: hasNext ? { ep: `S${season + 1}:E${eps[ni].n}`, title: eps[ni].title, desc: eps[ni].desc, grad: eps[ni].grad } : null,
        resolveNext: hasNext ? () => episodeCtx(seriesItem, season, eps, ni) : null,
        seasonLabel: 'Season ' + (season + 1),
        epIndex: idx,
        episodes: eps.map(x => { const xs = W.epState(seriesItem.title, season, x.n, x.pct); return { n: x.n, title: x.title, dur: x.dur, grad: x.grad, pct: xs.pct || 0, watched: xs.watched }; }),
        resolveEpisode: (i) => episodeCtx(seriesItem, season, eps, i),
      };
    }
    function playItem(item, season) {
      if (item.kind === 'series') {
        season = season || 0;
        const eps = R.episodesFor(item, season);
        const prog = seriesProgressFrom(eps.map(e => W.epState(item.title, season, e.n, e.pct)));
        openPlayer(episodeCtx(item, season, eps, prog.idx));
      } else {
        openPlayer(movieCtx(item));
      }
    }
    function openPlayer(ctx) { stopHero(); player.open(ctx); }
    const player = window.initRaviloPlayer(stage, {
      flash,
      restoreFocus: function (info) {
        // info = { title, pos, duration } from the player (or null). Write the resulting
        // watched-state through to the store for library movies; series episodes are toggled
        // from the detail page. (R07/R08 — server-pushed in production.)
        let nowWatched = false;
        if (info && info.duration && catalogHas(info.title)) {
          const pct = Math.round(info.pos / info.duration * 100);
          if (pct >= 90) { W.setItemWatched(info.title, true); nowWatched = true; }
          else if (pct > 2) W.setItem(info.title, { pct: pct, watched: false });
        }
        const reRender = info && (view.type === 'movie' || view.type === 'series') && view.item && view.item.title === info.title;
        if (view.type === 'home') startHero();
        if (reRender) { renderDetail(view.item); setTimeout(() => { const ai = rows().findIndex(r => r.classList.contains('dactions')); focusRC(ai > 0 ? ai : 1, 0); }, 24); }
        else { const all = rows(); const its = items(all[cur.r] || all[0]); if (its[cur.c]) focusEl(its[cur.c]); else focusRowByIndex(0); }
        if (info) flash(nowWatched ? t('toast_marked_watched') : '✓ Progress saved — jellystructure → Jellyfin');
      },
    });

    function clock() {
      const d = new Date();
      const c = appbar.querySelector('.clock');
      if (c) c.textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    }
    clock(); setInterval(clock, 10000);

    /* ---------------- HERO ---------------- */
    function buildHero(items, heightPct) {
      items = items || R.hero;
      heroIdx = 0;
      const hero = el('div', 'hero focus-row');
      hero._items = items;
      hero.style.height = (heightPct || 56) + '%';
      items.forEach((it, i) => {
        const s = el('div', 'hero-slide' + (i === 0 ? ' on' : ''));
        s.innerHTML = `
          <div class="hero-bg">${it.backdrop ? `<img class="hero-backdrop" src="${it.backdrop}" alt="">` : `<div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div>`}<div class="hero-scrim"></div></div>
          <div class="hero-body">
            <div class="hero-kicker"><span>${it.tagline}</span><span class="n">${it.kind === 'series' ? 'Series' : 'Film'}</span></div>
            ${it.logo ? `<img class="hero-logo" src="${it.logo}" alt="${esc(it.title)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block';"><div class="hero-title" style="display:none;">${esc(it.title)}</div>` : `<div class="hero-title">${esc(it.title)}</div>`}
            <div class="hero-meta"><span class="tag">${it.badge}</span><span>${it.year}</span><span>${it.genre}</span><span class="rt">${it.rating}+</span></div>
            <div class="hero-syn">${it.syn}</div>
          </div>`;
        hero.appendChild(s);
      });
      const dots = el('div', 'hero-dots');
      items.forEach((_, i) => { const d = el('div', 'd' + (i === 0 ? ' on' : '')); d.dataset.dot = i; dots.appendChild(d); });
      hero.appendChild(dots);
      // whole hero is one focusable target → select opens detail; ←/→ cycle the carousel
      const hit = el('div', 'hero-hit foc'); hit.dataset.hero = '1';
      hero.appendChild(hit);
      return hero;
    }
    function setHero(i) {
      const hero = scroll.querySelector('.hero'); if (!hero) return;
      const slides = hero.querySelectorAll('.hero-slide');
      heroIdx = (i + slides.length) % slides.length;
      slides.forEach((s, k) => s.classList.toggle('on', k === heroIdx));
      hero.querySelectorAll('.hero-dots .d').forEach((d, k) => d.classList.toggle('on', k === heroIdx));
    }
    function startHero() { stopHero(); if (interactive) heroTimer = setInterval(() => setHero(heroIdx + 1), 6500); }
    function stopHero() { if (heroTimer) clearInterval(heroTimer); heroTimer = null; }

    function upcomingLabel() { return t('upcoming'); }
    function airBadge(iso) {
      const d = new Date(iso + 'T00:00:00Z');
      return isNaN(d) ? iso : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
    }
    /* ---------------- ROWS ---------------- */
    function tile(item, kind) {
      const t = el('div', 'tile ' + (kind === 'land' ? 'land' : 'poster') + (kind === 'continue' ? ' cont' : '') + ' foc');
      t._item = item;
      if (kind === 'land') {
        t.innerHTML = `<div class="art">${item.image ? `<img class="tile-img" src="${item.image}" alt="">` : `<div class="grad" style="background:${item.grad}"></div>`}
          <div class="meta-ep">${item.ep || ''}</div>
          <div class="play"><span>▶</span></div>
          <div class="pbar"><i style="width:${item.pct || 0}%"></i></div></div>
          <div class="label">${item.title}</div><div class="sub">${item.next ? 'Next up' : (item.genre || '')}</div>`;
      } else if (kind === 'continue') {
        // poster shape, but keep the resume progress + time-left from Continue Watching
        t.innerHTML = `<div class="art">${artFill(item)}
          ${item.next ? '<div class="cont-badge">Next up</div>' : ''}
          <div class="play"><span>▶</span></div>
          ${(item.pct || 0) > 0 ? `<div class="pbar"><i style="width:${item.pct}%"></i></div>` : ''}</div>
          <div class="label">${item.title}</div><div class="sub cont-sub">${item.ep || ''}</div>`;
      } else {
        const ws = W.itemState(item.title);
        if (ws.watched) t.classList.add('watched');
        const air = item.kind === 'series' ? R.nextAiringFor(item) : null;
        const wmark = ws.watched ? `<div class="tile-check">✓</div>` : (ws.pct > 0 ? `<div class="tile-prog"><i style="width:${ws.pct}%"></i></div>` : '');
        t.innerHTML = `<div class="art">${artFill(item)}${item.badge ? `<div class="badge">${item.badge}</div>` : ''}${air ? `<div class="tile-air"><span class="tile-air-dot"></span>${upcomingLabel()}</div>` : ''}${wmark}</div>
          <div class="label">${item.title}</div><div class="sub">${item.year} · ${item.genre}</div>`;
      }
      return t;
    }
    // localize system row titles by id; genre rows keep their content title
    function rowTitle(rc) {
      const map = { 'continue': 'row_continue', 'new-movies': 'row_new_movies', 'new-series': 'row_new_series', 'new-all': 'row_newly_added' };
      return map[rc.id] ? t(map[rc.id]) : rc.title;
    }
    function contentRow(rowCfg) {
      const r = el('div', 'crow');
      r.innerHTML = `<div class="crow-head"><h2>${rowTitle(rowCfg)}</h2>${rowCfg.cfg ? `<span class="cfg">${rowCfg.cfg}</span>` : ''}<span class="more">${t('see_all')} ›</span></div>`;
      const track = el('div', 'track focus-row');
      rowCfg.items.forEach(it => track.appendChild(tile(it, rowCfg.kind)));
      r.appendChild(track);
      return r;
    }
    function studioRail() {
      const r = el('div', 'rail');
      r.innerHTML = `<div class="rail-head"><h2>${t('channels')}</h2><span class="more">${t('channels_sub')}</span></div>`;
      const track = el('div', 'track focus-row');
      R.studios.forEach(s => {
        const c = el('div', 'studio foc');
        c._studio = s;
        c.style.background = s.bg;
        const mark = s.logo ? `<img class="logo-img" src="${s.logo}" alt="${s.name}">` : `<div class="wm">${s.wm}</div>`;
        c.innerHTML = `<div class="sheen"></div>${mark}`;
        track.appendChild(c);
      });
      r.appendChild(track);
      return r;
    }

    function rowSet() {
      // same row set everywhere; merged-newly-added is one config alternative
      return R.rows;
    }

    /* ---------------- VIEWS ---------------- */
    function renderHome() {
      stopHero();
      scroll.innerHTML = '';
      scroll.appendChild(buildHero());
      scroll.appendChild(studioRail());
      rowSet().forEach(rc => scroll.appendChild(contentRow(rc)));
      scroll.appendChild(el('div', 'screen-end'));
      setHero(0); startHero();
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === 'home'));
    }
    function renderCategory(studioId) {
      stopHero();
      const s = R.studios.find(x => x.id === studioId) || R.studios[0];
      scroll.innerHTML = '';
      const hasHero = s.hero && s.hero.length;
      if (hasHero) scroll.appendChild(buildHero(s.hero));   // height follows the global Home hero height
      const head = el('div', 'cathead' + (hasHero ? ' with-hero' : ''));
      head.innerHTML = `<div class="logo" style="background:${s.bg}">${s.logo ? `<img class="logo-img" src="${s.logo}" alt="${s.name}">` : s.wm}</div>
        <div><div class="back">‹ ${t('back_home')} &nbsp;·&nbsp; ${t('channel')}</div><h1>${s.name}</h1>
        <div class="sub">${t('channel_sub', { name: s.name })}</div></div>`;
      scroll.appendChild(head);
      // a focus row of just nothing for the header; start rows below
      rowSet().forEach(rc => {
        // scope: shuffle items deterministically so it reads as "filtered"
        const scoped = { ...rc, items: rc.items.slice().sort((a, b) => (a.title.length % 3) - (b.title.length % 3)) };
        scroll.appendChild(contentRow(scoped));
      });
      scroll.appendChild(el('div', 'screen-end'));
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
      if (hasHero) startHero();
    }

    /* ---------------- DETAIL PAGE ---------------- */
    function detailBg(it) {
      return it.backdrop
        ? `<img class="hero-backdrop" src="${it.backdrop}" alt="">`
        : `<div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div>`;
    }
    function detailTitle(it) {
      if (!it.logo) return `<div class="dhero-title">${esc(it.title)}</div>`;
      // R130 — fall back to the styled title on null OR load failure (the logo proxy 404s for no-clearlogo titles)
      return `<img class="dhero-logo" src="${it.logo}" alt="${esc(it.title)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block';"><div class="dhero-title" style="display:none;">${esc(it.title)}</div>`;
    }
    function epAirLabel(iso) {
      const d = new Date(iso + 'T00:00:00Z');
      if (isNaN(d)) return iso;
      return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' });
    }
    function episodeCard(e, st, idx) {
      st = st || {};
      const cls = 'ep-card' + (st.watched ? ' watched' : '') + (st.inprogress ? ' inprogress' : '') + (st.upnext ? ' upnext' : '');
      const card = el('div', cls);
      const pct = st.watched ? 100 : (st.pct || e.pct || 0);
      const play = el('div', 'ep-play foc'); play._ep = e; play._epidx = idx;
      play.innerHTML = `<div class="ep-still"><div class="grad" style="background:${e.grad}"></div>
        <span class="ep-num">${e.n}</span><span class="ep-dur">${e.dur}</span>
        ${st.upnext ? '<span class="ep-ribbon">UP NEXT</span>' : ''}
        ${st.watched ? '<span class="ep-check">✓</span>' : ''}
        <div class="play"><span>▶</span></div>
        ${(st.inprogress || st.watched) ? `<div class="ep-prog"><i style="width:${pct}%"></i></div>` : ''}</div>
        <div class="ep-info"><div class="ep-t">${e.n}. ${e.title}${st.watched ? ` <span class="ep-tag">${t('watched')}</span>` : ''}</div>${e.air ? `<div class="ep-date">${epAirLabel(e.air)}</div>` : ''}<div class="ep-d">${e.desc}</div></div>`;
      const done = el('div', 'ep-done foc' + (st.watched ? ' on' : '')); done._epdone = true; done._epn = e.n; done._epidx = idx; done.setAttribute('data-epidx', idx);
      done.innerHTML = `<span class="ep-done-ic">${st.watched ? '✓' : ''}</span><span class="ep-done-tx">${st.watched ? t('watched') : t('mark_watched')}</span>`;
      card.appendChild(play); card.appendChild(done);
      return card;
    }
    function seriesProgress(eps) {
      let watched = 0;
      eps.forEach(e => { if (e.pct >= 100) watched++; });
      let idx = eps.findIndex(e => e.pct > 0 && e.pct < 100);
      if (idx < 0) idx = eps.findIndex(e => !e.pct);
      if (idx < 0) idx = eps.length - 1;
      return { watched, idx };
    }
    function minsLeft(e) { const d = parseInt(e.dur) || 50; return Math.max(1, Math.round(d * (1 - (e.pct || 0) / 100))); }
    function minsLeftPct(e, pct) { const d = parseInt(e.dur) || 50; return Math.max(1, Math.round(d * (1 - (pct || 0) / 100))); }
    function seriesProgressFrom(states) {
      let watched = 0; states.forEach(s => { if (s.watched) watched++; });
      let idx = states.findIndex(s => s.pct > 0 && !s.watched);
      if (idx < 0) idx = states.findIndex(s => !s.watched && !s.pct);
      if (idx < 0) idx = states.length - 1;
      return { watched, idx };
    }
    function castCircle(c) {
      const t = el('div', 'cast foc'); t._cast = c;
      t.innerHTML = `<div class="cast-av" style="background:${R.grad(c.n)}">${R.initials(c.n)}</div>
        <div class="cast-n">${c.n}</div><div class="cast-r">${c.r}</div>`;
      return t;
    }
    // ---- audio-language flag strip (detail hero) ----
    // ISO-639-1 → ISO-3166-1-alpha-2 (flag-icons country code) + English display name.
    const LANG_CC = { en: 'gb', fr: 'fr', de: 'de', es: 'es', da: 'dk', fo: 'fo', is: 'is', no: 'no', sv: 'se', fi: 'fi', nl: 'nl', it: 'it', pt: 'pt', pl: 'pl', ru: 'ru', ja: 'jp', ko: 'kr', zh: 'cn', ar: 'sa', hi: 'in' };
    const LANG_NAME = { en: 'English', fr: 'French', de: 'German', es: 'Spanish', da: 'Danish', fo: 'Faroese', is: 'Icelandic', no: 'Norwegian', sv: 'Swedish', fi: 'Finnish', nl: 'Dutch', it: 'Italian', pt: 'Portuguese', pl: 'Polish', ru: 'Russian', ja: 'Japanese', ko: 'Korean', zh: 'Chinese', ar: 'Arabic', hi: 'Hindi' };
    const FLAG_MAX = 5;
    function audioFlagsHTML(item) {
      // R134 — one merged line: Audio 🅐🅑 · Subtitles 🅒🅓, rendering only the groups that exist.
      const ts = tracksFor(item);
      const uniq = arr => { const seen = new Set(), out = []; arr.filter(l => l && LANG_CC[l]).forEach(l => { if (!seen.has(l)) { seen.add(l); out.push(l); } }); return out; };
      const flagRow = langs => {
        const shown = langs.slice(0, FLAG_MAX), extra = langs.length - shown.length;
        const flags = shown.map(l => `<span class="fi fi-${LANG_CC[l]} aflag" title="${LANG_NAME[l] || l}"></span>`).join('');
        const more = extra > 0 ? `<span class="aflag-more" title="${extra} more language${extra > 1 ? 's' : ''}">+${extra}</span>` : '';
        return flags + more;
      };
      const aud = uniq((ts.audio || []).map(a => a.lang));                  // physical track order
      const sub = uniq((ts.subs || []).filter(s => !s.off).map(s => s.lang)); // one flag per language
      if (!aud.length && !sub.length) return '';
      const groups = [];
      if (aud.length) groups.push(`<span class="aflag-group"><span class="aflag-label">Audio</span><span class="aflag-row">${flagRow(aud)}</span></span>`);
      if (sub.length) groups.push(`<span class="aflag-group"><span class="aflag-label">Subtitles</span><span class="aflag-row">${flagRow(sub)}</span></span>`);
      return `<div class="dhero-flags">${groups.join('<span class="aflag-div"></span>')}</div>`;
    }
    // ---- IMDb rating chip (detail hero) — data from imdbapi.dev, stored + synced (R164) ----
    function fmtVotes(n) { return n >= 1e6 ? (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K' : String(n); }
    function imdbHTML(item) {
      const im = R.imdbFor(item); if (!im) return '';
      return `<span class="imdb" title="IMDb ${im.rating.toFixed(1)}/10 · ${im.votes.toLocaleString()} votes"><span class="imdb-wm"><span class="imdb-star">★</span>IMDb</span><span class="imdb-val">${im.rating.toFixed(1)}</span><span class="imdb-votes">${fmtVotes(im.votes)}</span></span>`;
    }
    function renderDetail(item) {
      stopHero();
      scroll.innerHTML = '';
      const isSeries = item.kind === 'series';
      const seasons = isSeries ? R.seasonsFor(item) : 0;
      // R150-2 — auto-select the first incomplete season, once per series
      const seasonWatched = (s) => R.episodesFor(item, s).every(e => W.epState(item.title, s, e.n, e.pct).watched);
      if (isSeries && autoSeasonDone !== item.title) {
        autoSeasonDone = item.title;
        let target = seasons - 1;
        for (let s = 0; s < seasons; s++) { if (!seasonWatched(s)) { target = s; break; } }
        view.season = target;
      }
      const season = view.season || 0;
      const eps = isSeries ? R.episodesFor(item, season) : [];
      const states = isSeries ? eps.map(e => W.epState(item.title, season, e.n, e.pct)) : [];
      const prog = isSeries ? seriesProgressFrom(states) : null;
      const rEp = isSeries ? eps[prog.idx] : null;
      const rState = isSeries ? states[prog.idx] : null;
      const wItem = W.itemState(item.title);
      const nextAir = isSeries ? R.nextAiringFor(item) : null;
      const d = el('div', 'detail');

      let playLabel, upNote = '';
      if (isSeries) {
        if (prog.watched === 0 && !rState.pct) playLabel = t('play') + ' · E1';
        else if (rState.pct > 0 && rState.pct < 100) { playLabel = t('resume') + ` · E${rEp.n}`; upNote = `Resume S${season + 1} · E${rEp.n} “${rEp.title}” · ${minsLeftPct(rEp, rState.pct)} min left`; }
        else { playLabel = t('play') + ` · E${rEp.n}`; upNote = `Up next · S${season + 1} · E${rEp.n} “${rEp.title}”`; }
      } else {
        const mp = wItem.pct || item.pct || 0;
        const mLeft = Math.max(1, Math.round(durFor(item) * (1 - mp / 100) / 60));
        playLabel = wItem.watched ? t('play_again') : ((mp > 0 && mp < 100) ? t('resume') + ` · ${mLeft} min left` : t('play'));
      }

      const nextAirHTML = nextAir ? `<div class="dnext air"><span class="dnext-dot"></span>${t('next_ep')} · S${nextAir.season}:E${nextAir.ep}${nextAir.title ? ` “${nextAir.title}”` : ''} · ${t('airs')} ${epAirLabel(nextAir.date)}</div>` : '';
      const rating = R.ratingFor(item);
      const certHTML = rating ? `<span class="cert lvl-${rating.tier}" title="${esc(rating.regionName)} · ${esc(rating.system)}${rating.fallback ? ' (fallback)' : ''}"><span class="cert-rg">${rating.region}</span><span class="cert-code">${esc(rating.code)}</span></span>` : '';
      const dhero = el('div', 'dhero');
      dhero.innerHTML = `<div class="hero-bg">${detailBg(item)}<div class="hero-scrim"></div></div>
        <div class="dhero-body">
          <div class="hero-kicker"><span>${item.tagline || (isSeries ? 'Series' : 'Film')}</span><span class="n">${isSeries ? seasons + ' Season' + (seasons > 1 ? 's' : '') : (item.year || '')}</span></div>
          ${detailTitle(item)}
          <div class="hero-meta"><span class="tag">${item.badge || 'HD'}</span><span>${item.year}</span><span>${item.genre}</span>${certHTML}${imdbHTML(item)}${wItem.watched ? `<span class="dmeta-watched">✓ ${t('watched')}</span>` : ''}</div>
          ${audioFlagsHTML(item)}
          <div class="dsyn-block focus-row"><div class="hero-syn dsyn foc" data-syn="1">${item.syn || 'A standout from your Ravilo library — streamed from Jellyfin, organised by Jellystructure.'}</div><span class="syn-toggle">▾ more</span></div>
          ${(upNote || nextAirHTML) ? `<div class="dnext-row">${upNote ? `<div class="dnext"><span class="dnext-dot"></span>${upNote}</div>` : ''}${nextAirHTML}</div>` : ''}
          <div class="dactions focus-row">
            <div class="btn primary foc" data-play="1"><span class="ic">▶</span> ${playLabel}</div>
            ${!isSeries ? `<div class="btn ghost foc${wItem.watched ? ' watched-on' : ''}" data-mark="1"><span class="ic">${wItem.watched ? '✓' : '○'}</span> ${wItem.watched ? t('watched') : t('mark_watched')}</div>` : ''}
            ${R.trailerFor(item) ? `<div class="btn ghost foc" data-trailer="1"><span class="ic">▷</span> ${t('trailer')}</div>` : ''}
            <div class="btn ghost foc" data-list="1"><span class="ic">＋</span> ${t('add_list')}</div>
          </div>
        </div>`;
      d.appendChild(dhero);

      if (isSeries) {
        const pctWatched = Math.round(prog.watched / eps.length * 100);
        const sec = el('div', 'dsec');
        sec.innerHTML = `<div class="dsec-head"><h2>Episodes</h2>
          <span class="dsec-sub">${t('watched_of', { w: prog.watched, n: eps.length })}</span>
          <span class="seasonbar"><i style="width:${pctWatched}%"></i></span></div>`;
        const pills = el('div', 'seasonpills focus-row');
        for (let i = 0; i < seasons; i++) {
          const seps = R.episodesFor(item, i);
          const w = seps.reduce((a, e) => a + (W.epState(item.title, i, e.n, e.pct).watched ? 1 : 0), 0);
          const done = w === seps.length, part = w > 0 && !done;
          const p = el('div', 'spill foc' + (i === season ? ' cur' : '') + (done ? ' done' : part ? ' partial' : ''));
          p._season = i;
          p.innerHTML = 'Season ' + (i + 1)
            + (done ? '<span class="spill-check" aria-label="all watched">✓</span>'
                    : part ? `<span class="spill-frac" aria-label="${w} of ${seps.length} watched">${w}/${seps.length}</span>` : '');
          if (part) { const pr = el('span', 'spill-prog'); pr.style.width = Math.round(w / seps.length * 100) + '%'; p.appendChild(pr); }
          pills.appendChild(p);
        }
        sec.appendChild(pills);
        d.appendChild(sec);
        const epRow = el('div', 'crow eprow');
        const track = el('div', 'track focus-row');
        track.dataset.def = prog.idx * 2;
        eps.forEach((e, i) => track.appendChild(episodeCard(e, {
          watched: states[i].watched, inprogress: states[i].pct > 0 && !states[i].watched, upnext: i === prog.idx, pct: states[i].pct,
        }, i)));
        epRow.appendChild(track);
        d.appendChild(epRow);
      }

      const castRow = el('div', 'crow');
      castRow.innerHTML = `<div class="crow-head"><h2>Cast &amp; Crew</h2></div>`;
      const ctrack = el('div', 'track focus-row');
      R.castFor(item).forEach(c => ctrack.appendChild(castCircle(c)));
      castRow.appendChild(ctrack);
      d.appendChild(castRow);

      const rel = el('div', 'crow');
      rel.innerHTML = `<div class="crow-head"><h2>${t('more_like_this')}</h2></div>`;
      const rtrack = el('div', 'track focus-row');
      R.relatedFor(item).forEach(it => rtrack.appendChild(tile(it, 'poster')));
      rel.appendChild(rtrack);
      d.appendChild(rel);

      d.appendChild(el('div', 'screen-end'));
      scroll.appendChild(d);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
    }

    /* ---------------- DISCOVER / TOP 10 (separate from library detail) ---------------- */
    function listsForUser() {
      const u = currentUser(); const sel = (u && u.discover && u.discover.lists) || [];
      const byId = {}; (R.discover.lists || []).forEach(l => byId[l.id] = l);
      return sel.map(id => byId[id]).filter(Boolean);
    }
    function activeSource() { return (R.discover.sources || []).find(s => s.enabled) || R.discover.sources[0]; }
    function trendBadge(it) {
      if (it.trend === 'new') return `<span class="rtrend new">${t('new_this_week')}</span>`;
      if (it.trend === 'up') return `<span class="rtrend up">▲</span>`;
      if (it.trend === 'down') return `<span class="rtrend down">▼</span>`;
      return `<span class="rtrend same">=</span>`;
    }
    function fetchShort(it) {
      if (it.kind === 'series' && it.epsTotal != null) { let s = (it.epsDone || 0) + '/' + it.epsTotal; if (it.stalled) s += ' · ' + t('stalled'); return s; }
      let s = (it.progress || 0) + '%'; if (it.stalled) s += ' · ' + t('stalled'); else if (it.metadata) s += ' · ' + t('starting'); return s;
    }
    function fetchLong(it) {
      if (it.kind === 'series' && it.epsTotal != null) { let s = t('fetching') + ' · ' + (it.epsDone || 0) + '/' + it.epsTotal; if (it.stalled) s += ' · ' + t('stalled'); return s; }
      let s = t('fetching') + ' · ' + (it.progress || 0) + '%'; if (it.stalled) s += ' · ' + t('stalled'); else if (it.metadata) s += ' · ' + t('starting'); return s;
    }
    function statusMark(it) {
      const fl = reqFlag(it);
      switch (it.status) {
        case 'available':   return `<span class="rstat avail" title="In Library">✓</span>`;
        case 'requested':   return `<span class="rstat req"><span class="dot"></span>${t('requested')}${fl}</span>`;
        case 'queued':      return `<span class="rstat queue">${t('in_queue')}${it.queuePos ? ' · #' + it.queuePos : ''}${fl}</span>`;
        case 'downloading': return `<span class="rstat fetch"><span class="spin"></span><span class="pct">${fetchShort(it)}</span>${fl}</span>`;
        case 'importing':   return `<span class="rstat importing"><span class="spin"></span>${t('importing')}${fl}</span>`;
        case 'failed':      return `<span class="rstat failed">${t('failed')}</span>`;
        default: return '';
      }
    }
    function rankTile(it, list) {
      const tl = el('div', 'rtile foc'); tl._ditem = it; tl._dlist = list;
      const meta = [it.year, it.genre].filter(Boolean).join(' · ');
      tl.innerHTML = `
        <div class="rposter"><div class="art">${artGrad(it)}${statusMark(it)}</div></div>
        <div class="label">${it.title}</div>
        <div class="sub">${meta}${it.rating ? ' · ' + it.rating + '+' : ''}</div>`;
      return tl;
    }
    function discoverRow(list) {
      const r = el('div', 'crow drow');
      r.innerHTML = `<div class="crow-head"><h2>${list.title}</h2></div>`;
      const track = el('div', 'track focus-row');
      list.items.forEach(it => track.appendChild(rankTile(it, list)));
      r.appendChild(track);
      return r;
    }
    function seerrEnabled() { return !!(R.config && R.config.seerr); }
    function discSegment(active) {
      const seg = el('div', 'discseg');
      const mk = (tab, label) => '<div class="dseg foc' + (tab === active ? ' cur' : '') + '" data-disctab="' + tab + '">' + label + '</div>';
      let html = '';
      if (upcomingEnabled()) html += mk('coming', t('seg_coming'));
      if (seerrEnabled()) html += mk('request', t('seg_request'));
      seg.innerHTML = html;
      return seg;
    }
    function renderDiscover() {
      stopHero(); scroll.innerHTML = '';
      const src = activeSource();
      const region = R.discover.config.regionName || R.discover.config.region;
      const wrap = el('div', 'discoverscreen');
      const head = el('div', 'dischead');
      head.innerHTML = `<div class="dischead-row"><h1>${t('nav_discover')}</h1><span class="disc-sub">${t('request_sub')}</span></div>`;
      const dctrl = el('div', 'dischead-controls focus-row');
      dctrl.appendChild(discSegment('request'));
      const sp = el('div', 'dsearch foc'); sp.dataset.seerrsearch = '1';
      sp.innerHTML = `<span class="sic">⌕</span>${t('search_seerr')}`;
      dctrl.appendChild(sp);
      head.appendChild(dctrl);
      wrap.appendChild(head);
      const lists = listsForUser();
      const rail = inProgressRail();
      if (rail) wrap.appendChild(rail);
      lists.forEach(l => wrap.appendChild(discoverRow(l)));
      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === 'discover'));
    }

    function requestFetch(it) {
      it.status = 'requested'; it.progress = 0; it.queuePos = 3;
      userReq.add(it);
      flash('＋ ' + it.title + ' · ' + t('requested_via'));
      renderDiscoverDetail(it, view.list);
      setTimeout(() => focusRC(1, 0), 20);
      ensureDiscTicker();
    }
    function discTerminal(it) { return it.status === 'available' || it.status === 'failed' || it.status === 'not_requested'; }
    function advanceItem(it) {
      switch (it.status) {
        case 'requested': it.status = 'queued'; it.queuePos = it.queuePos || 3; return true;
        case 'queued': if (it.queuePos > 1) { it.queuePos--; return true; } it.status = 'downloading'; it.metadata = true; it.progress = 0; return true;
        case 'downloading':
          if (it.metadata) { it.metadata = false; return true; }
          if (it.stalled) { if (Math.random() < 0.45) { it.stalled = false; return true; } return false; }
          if (it.kind === 'series' && it.epsTotal != null) {
            if (it.epsDone < it.epsTotal) { it.epsDone++; it.firstAvailable = it.epsDone >= 1; if (it.epsDone >= it.epsTotal) it.status = 'importing'; return true; }
            return false;
          }
          it.progress = Math.min(100, (it.progress || 0) + 8 + Math.floor(Math.random() * 13));
          if (it.progress >= 100) it.status = 'importing';
          return true;
        case 'importing': it.status = 'available'; return true;
        default: return false;
      }
    }
    function patchDiscoverItem(it) {
      scroll.querySelectorAll('.rtile').forEach(tl => {
        if (tl._ditem !== it) return;
        const art = tl.querySelector('.rposter .art'); if (!art) return;
        const old = art.querySelector('.rstat'); if (old) old.remove();
        const tmp = document.createElement('div'); tmp.innerHTML = statusMark(it);
        if (tmp.firstElementChild) art.appendChild(tmp.firstElementChild);
      });
      if (view.type === 'discoverDetail' && view.item === it) {
        const sl = scroll.querySelector('.ddt-body .ddt-state');
        if (sl) { const tmp = document.createElement('div'); tmp.innerHTML = discoverStatusLine(it); if (tmp.firstElementChild) sl.replaceWith(tmp.firstElementChild); }
        const acts = scroll.querySelector('.ddt-actions');
        if (acts) { const tmp = document.createElement('div'); tmp.innerHTML = discoverActions(it); if (tmp.firstElementChild) { acts.replaceWith(tmp.firstElementChild); const f = scroll.querySelector('.ddt-actions .foc'); if (f) focusEl(f); } }
      }
    }
    let discTicker = null;
    const userReq = new Set();   // only titles the viewer requests this session animate live;
                                 // seeded items stay put as static status exemplars.
    function ensureDiscTicker() {
      if (!interactive || discTicker) return;
      discTicker = setInterval(() => {
        let any = false;
        userReq.forEach(it => {
          if (discTerminal(it)) { userReq.delete(it); return; }
          any = true;
          if (advanceItem(it)) patchDiscoverItem(it);
        });
        if (!any) { clearInterval(discTicker); discTicker = null; }
      }, 1500);
    }
    function discoverStatusLine(it) {
      const fl = reqFlag(it);
      const x = it.reqLang ? reqIntent(it.reqLang) : null;
      if (x && x.strict && (it.status === 'requested' || it.status === 'queued'))
        return `<span class="ddt-state fetch"><span class="dot"></span> ${t('req_waiting_for', { lang: x.waitLabel || x.label })} ${fl}</span>`;
      switch (it.status) {
        case 'available':   return `<span class="ddt-state avail">✓ ${t('in_library')}</span>`;
        case 'downloading': return `<span class="ddt-state fetch"><span class="spin"></span> ${fetchLong(it)} ${fl}</span>`;
        case 'queued':      return `<span class="ddt-state fetch">${t('in_queue')}${it.queuePos ? ' · #' + it.queuePos : ''} ${fl}</span>`;
        case 'requested':   return `<span class="ddt-state fetch"><span class="dot"></span> ${t('requested')} ${fl}</span>`;
        case 'importing':   return `<span class="ddt-state fetch"><span class="spin"></span> ${t('importing')} ${fl}</span>`;
        case 'failed':      return `<span class="ddt-state failed">${t('failed')}</span>`;
        default: return `<span class="ddt-state none">${t('not_in_library')}</span>`;
      }
    }
    function discoverActions(it) {
      const fl = reqFlag(it);
      let primary;
      if (it.status === 'available') primary = `<div class="btn primary foc" data-dact="watch"><span class="ic">▶</span> ${t('watch_now')}</div>`;
      else if (it.status === 'downloading' && it.kind === 'series' && it.firstAvailable) primary = `<div class="btn primary foc" data-dact="watch"><span class="ic">▶</span> ${t('watch_e1')}</div>`;
      else if (it.status === 'downloading') primary = `<div class="btn fetching foc" data-dact="progress"><span class="spin"></span> ${fetchLong(it)} ${fl}</div>`;
      else if (it.status === 'queued') primary = `<div class="btn fetching foc" data-dact="progress">${t('in_queue')}${it.queuePos ? ' · #' + it.queuePos : ''} ${fl}</div>`;
      else if (it.status === 'requested') primary = `<div class="btn fetching foc" data-dact="progress"><span class="dot"></span> ${t('requested')} ${fl}</div>`;
      else if (it.status === 'importing') primary = `<div class="btn fetching foc" data-dact="progress"><span class="spin"></span> ${t('importing')} ${fl}</div>`;
      else if (it.status === 'failed') primary = `<div class="btn primary foc" data-dact="request"><span class="ic">↻</span> ${t('retry_fetch')}</div>`;
      else primary = `<div class="btn primary foc" data-dact="request"><span class="ic">＋</span> ${t('request_fetch')}</div>`;
      const prog = (it.status === 'downloading' && it.kind === 'series' && it.firstAvailable)
        ? `<div class="btn fetching foc" data-dact="progress"><span class="spin"></span> ${fetchLong(it)}</div>` : '';
      const pending = ['requested', 'queued', 'downloading', 'importing'].includes(it.status);
      const change = (pending && it.reqLang) ? `<div class="btn ghost foc" data-dact="reqlang"><span class="ic">⇄</span> ${t('req_change_language')}</div>` : '';
      return `<div class="ddt-actions focus-row">
        ${primary}${prog}${change}
        ${it.status === 'available' ? `<div class="btn ghost foc" data-dact="list"><span class="ic">＋</span> ${t('add_list')}</div>` : ''}
      </div>`;
    }
    function renderDiscoverDetail(it, list) {
      stopHero(); scroll.innerHTML = '';
      const src = activeSource();
      const region = R.discover.config.regionName || R.discover.config.region;
      const isCountry = list && list.scope === 'country';
      const statusLine = discoverStatusLine(it);
      const d = el('div', 'ddt');
      d.innerHTML = `
        <div class="ddt-hero">
          <div class="ddt-bg"><div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div><div class="ddt-scrim"></div></div>
          <div class="ddt-body">
            <div class="ddt-kicker"><span class="ddt-srcwm" style="background:${src.accent}">${src.wm}</span>${src.name} ${t('via_source', { src: src.via })}<span class="ddt-rank">${t('rank_in', { n: it.rank, region })}</span></div>
            <div class="ddt-title">${it.title}</div>
            <div class="hero-meta"><span class="tag">${it.rating}+</span><span>${it.year}</span><span>${it.genre}</span><span>${it.kind === 'series' ? 'Series' : 'Film'}</span>${statusLine}</div>
            <div class="hero-syn">${it.syn || 'Trending on ' + src.name + ' right now. Not yet in your library — request it and it will be added and organised automatically.'}</div>
            ${discoverActions(it)}
          </div>
        </div>
        <div class="screen-end"></div>`;
      scroll.appendChild(d);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
    }

    /* ---------------- UPCOMING (Sonarr + Radarr backed — never surfaced to the viewer) ---------------- */
    function upDateObj(iso) { return new Date(iso + 'T00:00:00'); }
    function upDow(iso) { return upDateObj(iso).toLocaleDateString(undefined, { weekday: 'short' }); }
    function upDowLong(iso) { return upDateObj(iso).toLocaleDateString(undefined, { weekday: 'long' }); }
    function upDayNum(iso) { return String(upDateObj(iso).getDate()).padStart(2, '0'); }
    function upMon(iso) { return upDateObj(iso).toLocaleDateString(undefined, { month: 'short' }); }
    function upRel(offset) { return offset === 0 ? t('up_today') : offset === 1 ? t('up_tomorrow') : null; }
    function upSrcBadge(it) {
      return it.source === 'sonarr'
        ? `<span class="up-src sonarr">${t('up_episode')}</span>`
        : `<span class="up-src radarr">${t('up_movie')}</span>`;
    }
    function upStatusPill(it) {
      // "Already available" — we already have it; downloading — live %; missing — overdue.
      // everything else on this screen is simply "upcoming", so no redundant pill.
      if (it.status === 'available') return `<span class="up-stat avail-lib">✓ ${t('up_available')}</span>`;
      if (it.status === 'missing') return `<span class="up-stat missing">${t('up_missing')}</span>`;
      if (it.status === 'downloading') return `<span class="up-stat dl"><span class="spin"></span>${fetchShort(it)}</span>`;
      return '';
    }
    function upCardSub(it) {
      if (it.kind === 'series') return it.ep + (it.epTitle ? ' · ' + it.epTitle : '');
      return it.release || 'Film';
    }
    function upcomingCard(it) {
      const c = el('div', 'up-card foc'); c._upitem = it;
      c.innerHTML = `
        <div class="up-art">
          <div class="grad" style="background:${it.grad}"></div>
          <div class="up-art-top">${upSrcBadge(it)}${upStatusPill(it)}</div>
          <div class="up-art-bot"><span class="up-time">${it.time || it.release || ''}</span></div>
        </div>
        <div class="up-cbody">
          <div class="up-ctitle">${esc(it.title)}</div>
          <div class="up-csub">${esc(upCardSub(it))}</div>
          <div class="up-cnet">${esc(it.kind === 'series' ? (it.network || it.genre) : it.genre)} · ${it.rating}+</div>
          ${it.status === 'missing' ? `<div class="up-cdue"><span class="up-cdue-ic">!</span>${t('up_due')} ${epAirLabel(it.date)}</div>` : ''}
        </div>`;
      return c;
    }
    function upDayChip(day) {
      const c = el('div', 'up-day foc' + (day.offset === 0 ? ' today' : '')); c._upday = day.date;
      const rel = upRel(day.offset);
      c.innerHTML = `<span class="up-dow">${rel || upDow(day.date)}</span>
        <span class="up-dnum">${upDayNum(day.date)}</span>
        <span class="up-dmon">${upMon(day.date)}</span>
        <span class="up-dcount">${day.items.length}</span>`;
      return c;
    }
    function renderUpcoming() {
      stopHero(); scroll.innerHTML = '';
      const src = view.upSource || 'all';
      const match = it => src === 'all' || (src === 'series' && it.source === 'sonarr') || (src === 'movies' && it.source === 'radarr');
      const days = R.upcomingByDay()
        .map(d => ({ date: d.date, offset: d.offset, items: d.items.filter(match) }))
        .filter(d => d.items.length);
      // overdue / missing — released in the past (up to ~6 months back), still not in the library
      const missing = (R.overdue || []).filter(it => it.offset >= -183 && match(it)).sort((a, b) => b.offset - a.offset);

      const wrap = el('div', 'upscreen');
      const head = el('div', 'uphead');
      head.innerHTML = `<div class="uphead-row"><h1>${t('nav_discover')}</h1><span class="disc-sub">${t('upcoming_sub')}</span></div>`;
      const chips = el('div', 'upfilter focus-row');
      chips.appendChild(discSegment('coming'));
      [['all', t('up_all')], ['series', t('up_series')], ['movies', t('up_movies')]].forEach(([k, l]) => {
        const ch = el('div', 'upchip foc' + (k === src ? ' cur' : '')); ch._upsrc = k;
        ch.innerHTML = `<span class="upchip-dot ${k}"></span>${l}`;
        chips.appendChild(ch);
      });
      head.appendChild(chips);
      if (missing.length) {
        const jump = el('div', 'up-missjump foc'); jump._upjump = true;
        jump.innerHTML = `<span class="up-missjump-ic">!</span>${t('up_missing')}<span class="up-missjump-n">${missing.length}</span>`;
        chips.appendChild(jump);
      }
      wrap.appendChild(head);

      if (!days.length && !missing.length) {
        wrap.appendChild(el('div', 'up-empty', t('up_nothing')));
      }

      // calendar date rail — only days that actually have releases (nothing is expected every day,
      // so empty days are simply skipped and the rail jumps to the next day with content)
      if (days.length) {
        const railWrap = el('div', 'crow up-railwrap');
        railWrap.innerHTML = `<div class="crow-head"><h2 class="up-railh">${upMon(days[0].date)} — ${upDayNum(days[days.length - 1].date)} ${upMon(days[days.length - 1].date)}</h2></div>`;
        const railTrack = el('div', 'track focus-row up-rail');
        days.forEach(day => railTrack.appendChild(upDayChip(day)));
        railWrap.appendChild(railTrack);
        wrap.appendChild(railWrap);

        days.forEach(day => {
          const sec = el('div', 'crow up-sec'); sec.dataset.upday = day.date;
          const rel = upRel(day.offset);
          sec.innerHTML = `<div class="crow-head up-sechead">
            <div class="up-daybadge${day.offset === 0 ? ' today' : ''}"><span class="dow">${upDow(day.date)}</span><span class="dnum">${upDayNum(day.date)}</span></div>
            <div class="up-sectitle"><h2>${rel || upDowLong(day.date)}</h2><span class="up-secfull">${upDowLong(day.date)}, ${upMon(day.date)} ${upDayNum(day.date)}</span></div>
            <span class="up-seccount">${t(day.items.length === 1 ? 'up_release' : 'up_releases', { n: day.items.length })}</span></div>`;
          const track = el('div', 'track focus-row');
          day.items.forEach(it => track.appendChild(upcomingCard(it)));
          sec.appendChild(track);
          wrap.appendChild(sec);
        });
      }

      // missing / overdue overview — an at-a-glance list of what we should already have
      if (missing.length) {
        const sec = el('div', 'crow up-sec up-missing');
        sec.innerHTML = `<div class="crow-head up-sechead up-mhead">
          <div class="up-mbadge">!</div>
          <div class="up-sectitle"><h2>${t('up_missing_title')}</h2><span class="up-secfull">${t('up_missing_sub')}</span></div>
          <span class="up-seccount">${t(missing.length === 1 ? 'up_release' : 'up_releases', { n: missing.length })}</span></div>`;
        const track = el('div', 'track focus-row');
        missing.forEach(it => track.appendChild(upcomingCard(it)));
        sec.appendChild(track);
        wrap.appendChild(sec);
      }

      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === 'discover'));
    }
    function renderUpcomingDetail(it) {
      stopHero(); scroll.innerHTML = '';
      const isSeries = it.kind === 'series';
      const isMissing = it.status === 'missing';
      const kindLabel = isMissing ? t('up_missing') : (isSeries ? t('up_new_episode') : t('up_premiere'));
      const kindDot = isMissing ? 'missing' : (isSeries ? 'series' : 'movie');
      const dago = Math.abs(it.offset);
      const rel = it.offset === 0 ? t('up_airs_today')
        : it.offset === 1 ? t('up_airs_tomorrow')
        : it.offset > 1 ? t('up_airs_in', { n: it.offset })
        : dago === 1 ? (isSeries ? t('up_aired_yest') : t('up_released_yest'))
        : (isSeries ? t('up_aired_ago', { n: dago }) : t('up_released_ago', { n: dago }));
      const rating = R.ratingFor(it);
      const certHTML = rating
        ? `<span class="cert lvl-${rating.tier}"><span class="cert-rg">${rating.region}</span><span class="cert-code">${esc(rating.code)}</span></span>`
        : `<span class="tag">${it.rating}+</span>`;
      const d = el('div', 'ddt updt');
      d.innerHTML = `
        <div class="ddt-hero">
          <div class="ddt-bg"><div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div><div class="ddt-scrim"></div></div>
          <div class="ddt-body">
            <div class="ddt-kicker up-kicker"><span class="up-kdot ${kindDot}"></span>${kindLabel}<span class="ddt-rank up-airchip${isMissing ? ' missing' : ''}"><span class="up-airdot"></span>${rel}</span></div>
            <div class="ddt-title">${esc(it.title)}</div>
            <div class="hero-meta">${certHTML}<span>${it.year}</span><span>${it.genre}</span><span>${isSeries ? 'Series' : 'Film'}</span>${isSeries && it.ep ? `<span class="up-epchip">${it.ep}${it.epTitle ? ' · ' + esc(it.epTitle) : ''}</span>` : `<span class="up-epchip">${esc(it.release || '')}</span>`}</div>
            <div class="hero-syn">${esc(it.syn || ('Coming soon to your library — ' + (isSeries ? 'the new episode is added automatically when it airs' : 'added automatically when it releases') + '.'))}</div>
          </div>
        </div>
        <div class="ddt-why">
          <h2>${t('up_schedule')}</h2>
          <div class="ddt-stats">
            <div class="ddt-stat"><div class="k">${isSeries ? t('up_airdate') : t('up_reldate')}</div><div class="v up-vsm">${epAirLabel(it.date)}</div></div>
            <div class="ddt-stat"><div class="k">${isSeries ? t('up_airtime') : t('up_release_type')}</div><div class="v up-vsm">${isSeries ? (it.time || '—') : (it.release || '—')}</div></div>
            ${isSeries ? `<div class="ddt-stat"><div class="k">${t('up_network')}</div><div class="v up-vsm">${it.network || '—'}</div></div>` : ''}
            <div class="ddt-stat"><div class="k">${t('up_quality')}</div><div class="v up-vsm">${it.qp || '—'}</div></div>
          </div>
          <div class="ddt-foot">${isMissing
            ? 'Released, but not in your library yet — it hasn’t been downloaded.'
            : (isSeries ? 'New episode — added to your library automatically when it airs.' : 'Added to your library automatically when it releases.')}</div>
        </div>
        <div class="screen-end"></div>`;
      scroll.appendChild(d);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
    }
    function upcomingEnabled() { return !!(R.config && (R.config.sonarr || R.config.radarr)); }
    function updateUpcomingNav() { const e = document.getElementById('rv-nav-upcoming'); if (e) e.style.display = upcomingEnabled() ? '' : 'none'; }

    /* ---------------- BROWSE GRID + SEARCH ---------------- */
    let _catalog = null;
    function catalog() {
      if (_catalog) return _catalog;
      const seen = {}, out = [];
      const push = it => { if (it && it.title && !seen[it.title]) { seen[it.title] = 1; out.push(it); } };
      R.hero.forEach(push);
      R.rows.forEach(r => r.items.forEach(push));
      return _catalog = out;
    }
    function buildGridRows(grid, items) {
      grid.innerHTML = '';
      if (!items.length) { grid.innerHTML = '<div class="empty">No titles found.</div>'; return; }
      const per = 6;
      for (let i = 0; i < items.length; i += per) {
        const row = el('div', 'grid-row focus-row');
        items.slice(i, i + per).forEach(it => row.appendChild(tile(it, 'poster')));
        grid.appendChild(row);
      }
    }
    function renderGrid(v) {
      stopHero(); scroll.innerHTML = '';
      const c = catalog();
      const items = v.kind === 'mylist' ? c.slice(0, 14) : c.filter(it => it.kind === v.kind);
      v._all = items;
      const wrap = el('div', 'gridscreen');
      wrap.innerHTML = `<div class="gridhead"><h1>${v.title}</h1><span class="gridcount">${items.length} titles</span></div>`;
      if (v.kind !== 'mylist') {
        const fr = el('div', 'gridfilter focus-row');
        ['All', 'Drama', 'Crime', 'Sci-Fi', 'Comedy', 'Family'].forEach((g, i) => { const ch = el('div', 'gchip foc' + (i === 0 ? ' cur' : '')); ch._genre = g; ch.textContent = g; fr.appendChild(ch); });
        wrap.appendChild(fr);
      }
      const grid = el('div', 'pgrid'); buildGridRows(grid, items); wrap.appendChild(grid);
      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === v.nav));
    }
    function seerrCatalog() {
      const seen = {}, out = [];
      (R.discover.lists || []).forEach(l => (l.items || []).forEach(it => { if (it && it.title && !seen[it.title]) { seen[it.title] = 1; out.push(it); } }));
      return out;
    }
    function searchFilter(q) {
      const c = (view && view.seerr) ? seerrCatalog() : catalog();
      if (!q.trim()) return c.slice(0, 18);
      const l = q.toLowerCase(); return c.filter(it => it.title.toLowerCase().includes(l));
    }
    function renderSearchResults(container, items) {
      if (view && view.seerr) {
        container.innerHTML = '';
        if (!items.length) { container.innerHTML = '<div class="empty">Nothing on Seerr matches.</div>'; return; }
        const per = 6;
        for (let i = 0; i < items.length; i += per) {
          const row = el('div', 'grid-row focus-row');
          items.slice(i, i + per).forEach(it => row.appendChild(rankTile(it, { scope: 'global', metric: '' })));
          container.appendChild(row);
        }
      } else buildGridRows(container, items);
    }
    function renderSearch(v) {
      stopHero(); scroll.innerHTML = '';
      v.query = v.query || '';
      const wrap = el('div', 'searchscreen');
      wrap.innerHTML = `<div class="searchbar"><span class="sic">⌕</span><span class="sq">${v.query ? esc(v.query) : '<i>' + (v.seerr ? 'Search Seerr — films, series…' : 'Search movies &amp; series…') + '</i>'}</span></div>`;
      const kb = el('div', 'keyboard');
      ['ABCDEFGHIJ', 'KLMNOPQRST', 'UVWXYZ0123', '456789'].forEach(rk => {
        const kr = el('div', 'kbd-row focus-row');
        rk.split('').forEach(ch => { const k = el('div', 'key foc'); k._key = ch; k.textContent = ch; kr.appendChild(k); });
        kb.appendChild(kr);
      });
      const kr2 = el('div', 'kbd-row focus-row');
      [['space', 'Space'], ['del', '⌫ Delete'], ['clear', 'Clear']].forEach(([a, l]) => { const k = el('div', 'key wide foc'); k._key = a; k.textContent = l; kr2.appendChild(k); });
      kb.appendChild(kr2);
      wrap.appendChild(kb);
      wrap.appendChild(el('div', 'gridhead small', `<h2 class="sres-h">${v.query ? 'Results' : 'Suggestions'}</h2>`));
      const res = el('div', 'pgrid sresults'); renderSearchResults(res, searchFilter(v.query)); wrap.appendChild(res);
      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === (v.seerr ? 'discover' : 'search')));
    }
    function esc(s) { return s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }
    function catalogHas(title) { return catalog().some(it => it.title === title); }
    function refocusSel(sel) {
      setTimeout(() => {
        const node = scroll.querySelector(sel); if (!node) return;
        const all = rows();
        for (let r = 0; r < all.length; r++) { const c = items(all[r]).indexOf(node); if (c >= 0) { cur = { r, c }; focusEl(node); return; } }
      }, 24);
    }
    function syncSeriesItemState(item, season) {
      const eps = R.episodesFor(item, season);
      const st = eps.map(e => W.epState(item.title, season, e.n, e.pct));
      const w = st.filter(s => s.watched).length;
      if (w === st.length) W.setItem(item.title, { watched: true, pct: 100 });
      else if (w === 0) W.setItem(item.title, { watched: false, pct: st.some(s => s.pct > 0) ? Math.round(st.reduce((a, s) => a + (s.pct || 0), 0) / st.length) : 0 });
      else W.setItem(item.title, { watched: false, pct: Math.round(w / st.length * 100) });
    }
    function toggleItemWatched(item) {
      const ws = W.itemState(item.title);
      W.setItemWatched(item.title, !ws.watched);
      flash(!ws.watched ? t('toast_marked_watched') : t('toast_marked_unwatched'));
      renderDetail(item); refocusSel('[data-mark]');
    }
    function toggleSeasonWatched(item, season) {
      const eps = R.episodesFor(item, season);
      const allW = eps.map(e => W.epState(item.title, season, e.n, e.pct)).every(s => s.watched);
      eps.forEach(e => W.setEpWatched(item.title, season, e.n, !allW));
      syncSeriesItemState(item, season);
      flash(!allW ? t('toast_all_watched') : t('toast_all_unwatched'));
      renderDetail(item); refocusSel('[data-markall]');
    }
    function toggleEpisodeWatched(item, season, n, idx) {
      const eps = R.episodesFor(item, season);
      const e = eps.find(x => x.n === n) || eps[idx];
      const st = W.epState(item.title, season, e.n, e.pct);
      W.setEpWatched(item.title, season, e.n, !st.watched);
      syncSeriesItemState(item, season);
      flash(!st.watched ? t('toast_marked_watched') : t('toast_marked_unwatched'));
      renderDetail(item); refocusSel('.ep-done[data-epidx="' + idx + '"]');
    }

    function go(v) {
      view = v;
      if (v.type === 'home') renderHome();
      else if (v.type === 'category') renderCategory(v.studio);
      else if (v.type === 'movie' || v.type === 'series') renderDetail(v.item);
      else if (v.type === 'discover') renderDiscover();
      else if (v.type === 'upcoming') renderUpcoming();
      else if (v.type === 'upcomingDetail') renderUpcomingDetail(v.item);
      else if (v.type === 'discoverDetail') renderDiscoverDetail(v.item, v.list);
      else if (v.type === 'grid') renderGrid(v);
      else if (v.type === 'search') renderSearch(v);
      scroll.scrollTop = 0;
      setTimeout(() => {
        if (v.type === 'home') focusRowByIndex(0);
        else if (v.type === 'category' || v.type === 'discover') focusRowByIndex(firstContentRowIndex());
        else if (v.type === 'upcoming') focusRC(firstContentRowIndex(), 0);
        else if (v.type === 'movie' || v.type === 'series') { const ai = rows().findIndex(r => r.classList.contains('dactions')); focusRC(ai > 0 ? ai : 1, 0); } // entry focus stays on Play (R135)
        else if (v.type === 'upcomingDetail') { const ni = items(appbar).findIndex(n => n.dataset && n.dataset.nav === 'upcoming'); focusRC(0, ni > 0 ? ni : 0); } // info-only page → rest focus on the nav
        else focusRC(1, 0); // discoverDetail / grid / search → first focusable row
      }, 30);
    }

    /* ---------------- FOCUS ENGINE ---------------- */
    let cur = { r: 0, c: 0 };
    function rows() { return [appbar, ...scroll.querySelectorAll('.focus-row')]; }
    function items(row) { return row ? [...row.querySelectorAll('.foc')] : []; }
    function firstContentRowIndex() { const all = rows(); for (let i = 1; i < all.length; i++) if (all[i].closest('.crow') || all[i].closest('.rail')) return i; return 1; }

    function clearFocus() { stage.querySelectorAll('.foc.focused').forEach(e => e.classList.remove('focused')); stage.querySelectorAll('.crow.active,.rail.active').forEach(e => e.classList.remove('active')); }
    function focusEl(node) {
      clearFocus(); if (!node) return;
      node.classList.add('focused');
      const cr = node.closest('.crow') || node.closest('.rail'); if (cr) cr.classList.add('active');
      // horizontal: keep tile in view
      const track = node.closest('.track');
      if (track) { const target = node.offsetLeft - 64; track.scrollTo({ left: Math.max(0, target), behavior: 'smooth' }); }
      // vertical: keep row comfortably in view
      const rowWrap = node.closest('.crow') || node.closest('.rail') || node.closest('.hero') || node.closest('.dhero') || node.closest('.dsec') || node.closest('.cathead') || node.closest('.grid-row') || node.closest('.gridfilter') || node.closest('.kbd-row') || node.closest('.sresults');
      const inAppbar = !!node.closest('.appbar');
      if (inAppbar) scroll.scrollTo({ top: 0, behavior: 'smooth' });
      else if (rowWrap && (rowWrap.classList.contains('hero') || rowWrap.classList.contains('dhero'))) scroll.scrollTo({ top: 0, behavior: 'smooth' });
      else if (rowWrap) {
        const top = rowWrap.offsetTop - 150;
        scroll.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
      }
    }
    function focusRowByIndex(ri) {
      const all = rows(); ri = Math.max(0, Math.min(all.length - 1, ri));
      const its = items(all[ri]);
      if (!its.length) { return; }
      cur.r = ri; cur.c = Math.min(cur.c, its.length - 1);
      focusEl(its[cur.c]);
    }
    function focusRC(r, c) {
      const all = rows(); if (!all[r]) return;
      const its = items(all[r]); if (!its.length) return;
      cur = { r, c: Math.max(0, Math.min(c, its.length - 1)) };
      focusEl(its[cur.c]);
    }
    function move(dr, dc) {
      if (profmenu.classList.contains('on')) { moveProfMenu(dr); return; }
      if (overlay.classList.contains('on')) { moveOverlay(dc); return; }
      const all = rows();
      if (dr) {
        let ni = cur.r + dr;
        while (ni > 0 && ni < all.length - 1 && !items(all[ni]).length) ni += dr;
        ni = Math.max(0, Math.min(all.length - 1, ni));
        if (!items(all[ni]).length) return;
        cur.r = ni;
        const fr = all[cur.r]; const its = items(fr);
        if (fr.dataset && fr.dataset.def != null && !fr.dataset.seen) { cur.c = Math.min(+fr.dataset.def, its.length - 1); fr.dataset.seen = '1'; }
        else cur.c = Math.min(cur.c, its.length - 1);
        focusEl(its[cur.c]);
      } else if (dc) {
        const its = items(all[cur.r]); if (!its.length) return;
        // hero is a single focus target: left/right cycles the carousel instead of moving
        if (its[cur.c] && its[cur.c].dataset.hero) { setHero(heroIdx + dc); startHero(); return; }
        cur.c = Math.max(0, Math.min(its.length - 1, cur.c + dc));
        focusEl(its[cur.c]);
      }
    }
    function moveOverlay(dc) {
      if (!dc) return;
      const its = [...overlay.querySelectorAll('.foc')];
      let i = its.findIndex(e => e.classList.contains('focused'));
      i = Math.max(0, Math.min(its.length - 1, i + dc));
      its.forEach(e => e.classList.remove('focused')); its[i].classList.add('focused');
    }

    function activate() {
      if (profmenu.classList.contains('on')) { activateProfMenu(); return; }
      if (overlay.classList.contains('on')) {
        const f0 = overlay.querySelector('.foc.focused');
        if (f0 && f0.dataset.ov === 'play') { const it = overlay._item; closeOverlay(); if (it) playItem(it); } else closeOverlay();
        return;
      }
      const all = rows(); const f = items(all[cur.r])[cur.c]; if (!f) return;
      const toDetail = (it) => go({ type: it.kind === 'series' ? 'series' : 'movie', item: it, from: view });
      if (f.dataset.nav) {
        if (f.dataset.nav === 'home') go({ type: 'home' });
        else if (f.dataset.nav === 'search') go({ type: 'search', query: '' });
        else if (f.dataset.nav === 'movies') go({ type: 'grid', kind: 'film', title: 'Movies', nav: 'movies' });
        else if (f.dataset.nav === 'series') go({ type: 'grid', kind: 'series', title: 'Series', nav: 'series' });
        else if (f.dataset.nav === 'top10') go({ type: 'discover' });
        else if (f.dataset.nav === 'discover') go({ type: upcomingEnabled() ? 'upcoming' : 'discover' });
        else if (f.dataset.nav === 'upcoming') go({ type: 'upcoming' });
        else if (f.dataset.nav === 'mylist') go({ type: 'grid', kind: 'mylist', title: 'My List', nav: 'mylist' });
        else if (f.dataset.nav === 'profile') openProfMenu();
        return;
      }
      if (f.dataset.disctab) { go({ type: f.dataset.disctab === 'coming' ? 'upcoming' : 'discover' }); return; }
      if (f.dataset.seerrsearch) { go({ type: 'search', query: '', seerr: true }); return; }
      if (f._genre) {
        const wrap = scroll.querySelector('.gridscreen'); const grid = wrap.querySelector('.pgrid');
        const base = view._all || [];
        const items = f._genre === 'All' ? base : base.filter(it => (it.genre || '').toLowerCase().includes(f._genre.toLowerCase()));
        buildGridRows(grid, items);
        wrap.querySelector('.gridcount').textContent = items.length + ' titles';
        scroll.querySelectorAll('.gchip').forEach(x => x.classList.toggle('cur', x === f));
        return;
      }
      if (f._key) {
        if (f._key === 'del') view.query = view.query.slice(0, -1);
        else if (f._key === 'clear') view.query = '';
        else if (f._key === 'space') view.query += ' ';
        else view.query += f._key;
        scroll.querySelector('.sq').innerHTML = view.query ? esc(view.query) : '<i>' + (view.seerr ? 'Search Seerr — films, series…' : 'Search movies &amp; series…') + '</i>';
        scroll.querySelector('.sres-h').textContent = view.query.trim() ? 'Results' : 'Suggestions';
        renderSearchResults(scroll.querySelector('.sresults'), searchFilter(view.query));
        return;
      }
      if (f.dataset.hero) { const he = f.closest('.hero'); toDetail((he && he._items ? he._items : R.hero)[heroIdx]); return; }
      if (f.dataset.syn) { const exp = f.classList.toggle('expanded'); const tg = f.parentElement.querySelector('.syn-toggle'); if (tg) tg.textContent = exp ? '▴ less' : '▾ more'; return; }
      if (f.dataset.play) { playItem(view.item, view.season || 0); return; }
      if (f.dataset.mark) { toggleItemWatched(view.item); return; }
      if (f.dataset.markall) { toggleSeasonWatched(view.item, view.season || 0); return; }
      if (f._epdone) { toggleEpisodeWatched(view.item, view.season || 0, f._epn, f._epidx); return; }
      if (f.dataset.trailer) { openTrailer(view.item); return; }
      if (f.dataset.list) { flash('＋ Added ' + view.item.title + ' to My List'); return; }
      if (f._season != null) {
        if (f._season !== (view.season || 0)) { view.season = f._season; renderDetail(view.item); setTimeout(() => { const sp = rows().findIndex(r => r.classList.contains('seasonpills')); focusRC(sp > 0 ? sp : 2, f._season); }, 20); }
        return;
      }
      if (f._ep) { const eps = R.episodesFor(view.item, view.season || 0); const idx = eps.findIndex(x => x.n === f._ep.n); openPlayer(episodeCtx(view.item, view.season || 0, eps, Math.max(0, idx))); return; }
      if (f._cast) { flash(f._cast.n + ' · ' + f._cast.r); return; }
      if (f._src) { if (f._src.enabled) { renderDiscover(); setTimeout(() => focusRowByIndex(firstContentRowIndex()), 20); } else flash(f._src.name + ' · coming soon'); return; }
      if (f._ditem) { go({ type: 'discoverDetail', item: f._ditem, list: f._dlist, from: { type: 'discover' } }); return; }
      if (f._upsrc) { view.upSource = f._upsrc; renderUpcoming(); setTimeout(() => focusRC(1, ['all', 'series', 'movies'].indexOf(f._upsrc)), 20); return; }
      if (f._upjump) {
        const sec = scroll.querySelector('.up-missing');
        if (sec) {
          const card = sec.querySelector('.foc');
          if (card) { const all = rows(); for (let r = 0; r < all.length; r++) { const c = items(all[r]).indexOf(card); if (c >= 0) { cur = { r, c }; focusEl(card); break; } } }
          scroll.scrollTop = Math.max(0, sec.offsetTop - 120);   // guarantee the jump lands on the section
        }
        return;
      }
      if (f._upday) {
        const secEl = scroll.querySelector('.up-sec[data-upday="' + f._upday + '"]');
        const card = secEl && secEl.querySelector('.foc');
        if (card) { const all = rows(); for (let r = 0; r < all.length; r++) { const c = items(all[r]).indexOf(card); if (c >= 0) { cur = { r, c }; focusEl(card); break; } } }
        return;
      }
      if (f._upitem) {
        const uit = f._upitem, ufrom = { type: 'upcoming', upSource: view.upSource };
        // if we already have it in the library, open the real detail page:
        //  - "available" items (movie or series) we hold ahead of the air/release date
        //  - any series we already track (some episodes on disk)
        const tgt = catalog().find(x => x.title === uit.title && (uit.kind === 'series' ? x.kind === 'series' : x.kind !== 'series'));
        if (tgt && (uit.status === 'available' || uit.kind === 'series')) {
          go({ type: tgt.kind === 'series' ? 'series' : 'movie', item: tgt, from: ufrom }); return;
        }
        go({ type: 'upcomingDetail', item: uit, from: ufrom });
        return;
      }
      if (f.dataset.dact) {
        const it = view.item;
        if (f.dataset.dact === 'watch') playItem(it);
        else if (f.dataset.dact === 'request') openLangPicker(it, false);
        else if (f.dataset.dact === 'reqlang') openLangPicker(it, true);
        else if (f.dataset.dact === 'progress') flash(fetchLong(it));
        else if (f.dataset.dact === 'list') flash('＋ ' + it.title);
        return;
      }
      if (f._studio) { go({ type: 'category', studio: f._studio.id }); return; }
      if (f._item) { if (f.classList.contains('land')) playItem(f._item, 0); else toDetail(f._item); return; }
    }
    function back() {
      if (trailerOpen) { closeTrailer(); return; }
      if (profmenu.classList.contains('on')) { closeProfMenu(); return; }
      if (overlay.classList.contains('on')) { closeOverlay(); return; }
      if (view.type === 'discoverDetail') { go(view.from || { type: 'discover' }); return; }
      if (view.type === 'upcomingDetail') { go(view.from || { type: 'upcoming' }); return; }
      if (view.type === 'movie' || view.type === 'series') { go(view.from || { type: 'home' }); return; }
      if (view.type === 'search' && view.seerr) { go({ type: 'discover' }); return; }
      if (view.type !== 'home') go({ type: 'home' });
    }
    function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

    /* ---- overlay ---- */
    function openOverlay(item) {
      overlay._item = item;
      overlay.querySelector('.art .grad').style.background = item.grad;
      overlay.querySelector('h2').textContent = item.title;
      overlay.querySelector('.m').innerHTML = `<span class="rt" style="border:1px solid var(--line);padding:2px 8px;border-radius:6px">${item.rating}+</span><span>${item.year}</span><span>${item.genre}</span><span>${item.kind === 'series' ? 'Series' : 'Film'}</span>`;
      overlay.querySelector('p').textContent = item.syn || 'A standout from your Ravilo library — pulled live from Jellyfin, organised by Jellystructure.';
      overlay.querySelectorAll('.foc').forEach(e => e.classList.remove('focused'));
      overlay.querySelector('[data-ov="play"]').classList.add('focused');
      overlay.classList.add('on'); stopHero();
    }
    function closeOverlay() { overlay.classList.remove('on'); if (view.type === 'home') startHero(); }

    /* ---- trailer overlay (TMDB YouTube/Vimeo embed, fullscreen — R163) ----
       TMDB trailers live on YouTube/Vimeo, so they can't play through the ExoPlayer-style
       engine (there's no direct stream); we embed the provider's own iframe player
       fullscreen, chrome-matched to the real player. The button only renders when
       R.trailerFor(item) exists (TMDB had a video for the title). */
    const trailerEl = el('div', 'rv-trailer'); trailerEl.style.display = 'none';
    trailerEl.innerHTML = `<div class="rv-tr-frame"></div>
      <div class="rv-tr-top">
        <div class="rv-tr-title"></div>
        <div class="rv-tr-close foc focused" data-trclose="1">✕ ${t('close')}</div>
      </div>`;
    stage.appendChild(trailerEl);
    let trailerOpen = false;
    function trailerEmbed(tr) {
      if (tr.site === 'vimeo') return `https://player.vimeo.com/video/${tr.key}?autoplay=1&title=0&byline=0&portrait=0`;
      return `https://www.youtube-nocookie.com/embed/${tr.key}?autoplay=1&rel=0&modestbranding=1&playsinline=1`;
    }
    function openTrailer(item) {
      const tr = R.trailerFor(item); if (!tr) return;
      stopHero();
      const src = tr.site === 'vimeo' ? 'Vimeo' : 'YouTube';
      trailerEl.querySelector('.rv-tr-title').innerHTML =
        `<span class="rv-tr-kick">${t('trailer')}</span><span class="rv-tr-name">${esc(item.title)}${tr.name ? ' · ' + esc(tr.name) : ''}</span><span class="rv-tr-src">${src}</span>`;
      trailerEl.querySelector('.rv-tr-frame').innerHTML =
        `<iframe src="${trailerEmbed(tr)}" title="${esc(item.title)} — ${t('trailer')}" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>`;
      trailerEl.querySelector('.rv-tr-close').classList.add('focused');
      trailerEl.style.display = 'flex'; trailerOpen = true;
    }
    function closeTrailer() {
      if (!trailerOpen) return;
      trailerEl.querySelector('.rv-tr-frame').innerHTML = '';   // stop playback
      trailerEl.style.display = 'none'; trailerOpen = false;
      if (view.type === 'home') startHero();
      setTimeout(() => refocusSel('[data-trailer]'), 12);
    }
    trailerEl.addEventListener('click', e => { if (e.target.closest('[data-trclose]')) { e.stopPropagation(); closeTrailer(); } });
    // capture-phase so the fullscreen trailer owns Back / Select while open
    window.addEventListener('keydown', e => {
      if (!trailerOpen) return;
      const k = e.key;
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ', 'Backspace', 'Escape'].includes(k)) { e.preventDefault(); e.stopImmediatePropagation(); }
      if (k === 'Backspace' || k === 'Escape' || k === 'Enter' || k === ' ') closeTrailer();
    }, true);

    /* ---- request-language picker (R172) — flags-first Original vs Dansk/Nordic ----
       The intent catalog is authored in admin Settings (Phase 139); here it's the two
       live intents. Pressing Request opens this popup with the viewer's resolved default
       pre-focused, so Select is usually one confirm; a strict (Nordic) request that's still
       waiting can be switched to another language later. 0–1 intents → no popup. */
    const REQ_INTENTS = [
      { id: 'original', label: 'Original', desc: 'Standard release', cc: null },
      { id: 'nordic',   label: 'Dansk / Nordic', waitLabel: 'Dansk', desc: 'Nordic release · usually incl. English', cc: 'dk', strict: true },
    ];
    const REQ_DEFAULT = 'nordic';   // resolved per-viewer default (admin config · kids), pre-selected
    function reqIntent(id) { return REQ_INTENTS.find(x => x.id === id) || REQ_INTENTS[0]; }
    function reqFlag(it) {
      if (!it || !it.reqLang) return '';
      const x = reqIntent(it.reqLang);
      return x.cc ? `<span class="fi fi-${x.cc} reqflag" title="${x.label}"></span>`
                  : `<span class="reqflag globe" title="${x.label}">🌐</span>`;
    }
    const langEl = el('div', 'rv-lang'); langEl.style.display = 'none'; stage.appendChild(langEl);
    let langCtx = null;
    function langPickerOpen() { return langEl.style.display !== 'none'; }
    function openLangPicker(it, change) {
      if (REQ_INTENTS.length < 2) { requestFetch(it); return; }   // no real choice → fire as before
      langCtx = { it, change };
      const pre = it.reqLang || REQ_DEFAULT;
      const rows = REQ_INTENTS.map(x => {
        const flag = x.cc ? `<span class="fi fi-${x.cc} rv-lang-flag"></span>` : `<span class="rv-lang-flag globe">🌐</span>`;
        return `<div class="rv-lang-opt foc${x.id === pre ? ' focused' : ''}" data-intent="${x.id}">` +
          `${flag}<span class="rv-lang-l"><b>${x.label}</b><span class="rv-lang-desc">${x.desc}</span></span>` +
          `<span class="rv-lang-tick">✓</span></div>`;
      }).join('');
      langEl.innerHTML = `<div class="rv-lang-card">` +
        `<div class="rv-lang-h">${change ? t('req_change_language') : t('req_in_language')}</div>` +
        `<div class="rv-lang-sub">${esc(it.title)}</div>` +
        `<div class="rv-lang-rows">${rows}</div>` +
        `<div class="rv-lang-foot">${t('req_confirm_hint')}</div></div>`;
      langEl.style.display = 'flex';
    }
    function closeLangPicker() { langEl.style.display = 'none'; langCtx = null; }
    function langMove(dir) {
      const opts = [...langEl.querySelectorAll('.rv-lang-opt')];
      let i = opts.findIndex(o => o.classList.contains('focused'));
      i = (i + dir + opts.length) % opts.length;
      opts.forEach((o, k) => o.classList.toggle('focused', k === i));
    }
    function langChoose() {
      const cur = langEl.querySelector('.rv-lang-opt.focused'); if (!cur || !langCtx) return;
      const id = cur.dataset.intent, it = langCtx.it, change = langCtx.change;
      it.reqLang = id; closeLangPicker();
      if (change) { patchDiscoverItem(it); flash(reqIntent(id).label + ' · ' + t('requested_via')); }
      else requestFetch(it);
    }
    langEl.addEventListener('click', e => {
      const opt = e.target.closest('.rv-lang-opt');
      if (opt) { langEl.querySelectorAll('.rv-lang-opt').forEach(o => o.classList.toggle('focused', o === opt)); langChoose(); return; }
      if (e.target === langEl) closeLangPicker();
    });
    window.addEventListener('keydown', e => {
      if (!langPickerOpen()) return;
      const k = e.key;
      if (['ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Enter',' ','Backspace','Escape'].includes(k)) { e.preventDefault(); e.stopImmediatePropagation(); }
      if (k === 'ArrowUp' || k === 'ArrowLeft') langMove(-1);
      else if (k === 'ArrowDown' || k === 'ArrowRight') langMove(1);
      else if (k === 'Enter' || k === ' ') langChoose();
      else if (k === 'Backspace' || k === 'Escape') closeLangPicker();
    }, true);

    /* In-progress rail (R172 §D2): the viewer's own not-yet-available requests, so a strict
       pick from days ago is easy to re-find and switch. Seeded once as static exemplars. */
    let myReqSeeded = false; const myRequests = [];
    function seedMyRequests() {
      if (myReqSeeded) return; myReqSeeded = true;
      const cat = seerrCatalog();
      if (cat[0]) { cat[0].reqLang = 'nordic';   cat[0].status = 'requested';   cat[0].queuePos = 4; myRequests.push(cat[0]); }
      if (cat[1]) { cat[1].reqLang = 'original'; cat[1].status = 'downloading'; cat[1].progress = 42; cat[1].metadata = false; myRequests.push(cat[1]); }
    }
    function inProgressRail() {
      seedMyRequests();
      const items = myRequests.filter(it => it.status && it.status !== 'available' && it.status !== 'not_requested');
      if (!items.length) return null;
      const r = el('div', 'crow drow inprog');
      r.innerHTML = `<div class="crow-head"><h2>${t('req_in_progress')}</h2></div>`;
      const track = el('div', 'track focus-row');
      items.forEach(it => track.appendChild(rankTile(it, null)));
      r.appendChild(track);
      return r;
    }

    /* ---- toast ---- */
    function flash(msg) {
      let t = stage.querySelector('.rv-toast');
      if (!t) { t = el('div', 'rv-toast'); t.style.cssText = 'position:absolute;left:50%;bottom:60px;transform:translateX(-50%);background:rgba(10,12,19,.92);backdrop-filter:blur(10px);border:1px solid var(--line);color:var(--ink);font-size:20px;font-weight:600;padding:16px 28px;border-radius:14px;z-index:90;box-shadow:0 18px 50px rgba(0,0,0,.5);transition:opacity .25s;'; stage.appendChild(t); }
      t.textContent = msg; t.style.opacity = '1'; clearTimeout(t._h); t._h = setTimeout(() => t.style.opacity = '0', 1700);
    }

    /* ---- server message toast (Jellyfin “DisplayMessage” general command) ----
       Shown top-right; on-screen time follows the client display formula (75ms/char,
       1.5s base, clamped 3–15s). A server-supplied TimeoutMs, when > 0, wins. */
    function calculateToastDurationMs(message) {
      const baseTimeMs = 1500, msPerCharacter = 75, minDurationMs = 3000, maxDurationMs = 15000;
      const calculatedTime = baseTimeMs + ((message ? message.length : 0) * msPerCharacter);
      return Math.min(Math.max(calculatedTime, minDurationMs), maxDurationMs);
    }
    function showServerMessage(message, timeoutMs) {
      const text = (message == null ? '' : String(message)).trim();
      if (!text) return null;
      let stack = stage.querySelector('.rv-msgstack');
      if (!stack) { stack = el('div', 'rv-msgstack'); stage.appendChild(stack); }
      const dur = (typeof timeoutMs === 'number' && timeoutMs > 0) ? timeoutMs : calculateToastDurationMs(text);
      const m = el('div', 'rv-msg');
      m.innerHTML = `<div class="ic" aria-hidden="true">✉</div><div class="tx">${esc(text)}</div><span class="rv-msg-bar"></span>`;
      stack.appendChild(m);
      const dismiss = () => { if (m._done) return; m._done = true; m.classList.remove('in'); m.classList.add('out'); setTimeout(() => m.remove(), 340); };
      requestAnimationFrame(() => {
        m.classList.add('in');
        const bar = m.querySelector('.rv-msg-bar');
        if (bar) { bar.style.transition = `transform ${dur}ms linear`; bar.style.transform = 'scaleX(0)'; }
      });
      m._h = setTimeout(dismiss, dur);
      m.addEventListener('click', () => { clearTimeout(m._h); dismiss(); });
      return m;
    }
    // Jellyfin admin → client entry point (in production, fed by the WS GeneralCommand handler).
    window.raviloSendMessage = showServerMessage;

    /* ---- input ---- */
    if (interactive) {
      window.addEventListener('keydown', e => {
        const k = e.key;
        if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', 'Backspace', 'Escape', ' '].includes(k)) e.preventDefault();
        if (k === 'ArrowUp') move(-1, 0);
        else if (k === 'ArrowDown') move(1, 0);
        else if (k === 'ArrowLeft') move(0, -1);
        else if (k === 'ArrowRight') move(0, 1);
        else if (k === 'Enter' || k === ' ') activate();
        else if (k === 'Backspace' || k === 'Escape') back();
      });
      // pointer fallbacks
      stage.addEventListener('click', e => {
        if (e.target.closest('.profiles')) return;   // the profiles/settings overlay owns its own clicks
        const dot = e.target.closest('.hero-dots .d'); if (dot) { setHero(+dot.dataset.dot); return; }
        const f = e.target.closest('.foc'); if (!f) { if (e.target.closest('.overlay') && !e.target.closest('.sheet')) closeOverlay(); return; }
        const all = rows(); for (let r = 0; r < all.length; r++) { const c = items(all[r]).indexOf(f); if (c >= 0) { cur = { r, c }; break; } }
        if (f.closest('.overlay')) { focusElOverlay(f); activate(); } else { focusEl(f); activate(); }
      });
      function focusElOverlay(f) { overlay.querySelectorAll('.foc').forEach(e => e.classList.remove('focused')); f.classList.add('focused'); }
    }

    // ---- multi-user profiles (login + fast switching; tokens cached client-side) ----
    const PKEY = 'js-ravilo-user';            // current user id (persisted on the TV)
    const profiles = (R.profiles || []).slice();
    const prof = el('div', 'profiles');
    prof.style.display = 'none';
    stage.appendChild(prof);
    let pIdx = 0, pMode = 'gate', pTiles = [];

    function currentUser() {
      let id = null; try { id = localStorage.getItem(PKEY); } catch (e) {}
      return profiles.find(p => p.id === id) || null;
    }
    function applyUser(p) {
      try { localStorage.setItem(PKEY, p.id); } catch (e) {}
      if (window.setRaviloLang) window.setRaviloLang(resolveLang(p));
      relabelChrome();
      updateDiscoverNav();
      updateUpcomingNav();
      const av = document.getElementById('rv-avatar');
      if (av) { av.textContent = p.initials; av.style.background = p.color; }
    }
    // In-app interface-language override — one of the few prefs controllable on the TV itself.
    // Defaults to the user's Jellystructure-set language; an in-app choice is stored per user id.
    function langOverride(uid) { try { return localStorage.getItem('js-ravilo-lang:' + (uid || '_')); } catch (e) { return null; } }
    function resolveLang(p) { return langOverride(p && p.id) || (p && p.lang) || 'en'; }
    function setUserLang(code) {
      const u = currentUser(), uid = u ? u.id : '_';
      try { localStorage.setItem('js-ravilo-lang:' + uid, code); } catch (e) {}
      if (window.setRaviloLang) window.setRaviloLang(code);
      relabelChrome(); updateDiscoverNav(); updateUpcomingNav();
      go(view);          // re-localize the screen behind the overlay
      if (pMode === 'settings') renderSettings(); else renderProfiles();  // re-render the open panel (new labels + highlight)
    }
    function langSectionHTML() {
      const cur = window.getRaviloLang ? window.getRaviloLang() : 'en';
      const chips = (window.RAVILO_LANGS_UI || []).map(l =>
        '<div class="lang-chip foc' + (l.code === cur ? ' cur' : '') + '" data-pid="__lang:' + l.code + '">' +
        '<span class="lang-endo">' + l.endo + '</span><span class="lang-name">' + l.name + '</span></div>').join('');
      return '<div class="prof-langs"><div class="prof-langs-h">' + t('language') + '</div><div class="lang-row">' + chips + '</div></div>';
    }
    // re-label the persistent app-bar nav after a language switch (screens re-localize via go())
    function relabelChrome() {
      const map = { home: 'nav_home', movies: 'nav_movies', series: 'nav_series', discover: 'nav_discover', top10: 'nav_top10', mylist: 'nav_mylist', upcoming: 'nav_upcoming' };
      appbar.querySelectorAll('.navitem').forEach(n => { const k = map[n.dataset.nav]; if (k) n.textContent = t(k); });
    }
    // Top 10 tab is gated: Radarr enabled in Jellystructure AND the user's per-user discover config.
    function discoverEnabled() {
      const u = currentUser();
      return !!(R.discover && R.discover.config && R.discover.config.radarr && u && u.discover && u.discover.enabled && (u.discover.lists || []).length);
    }
    function updateDiscoverNav() {
      const el = document.getElementById('rv-nav-discover');
      if (el) el.style.display = (upcomingEnabled() || seerrEnabled()) ? '' : 'none';
    }
    function renderProfiles() {
      const signed = profiles.filter(p => p.signedIn);
      prof.className = 'profiles' + (pMode === 'switch' ? ' switch' : '');
      prof.innerHTML =
        '<h2>' + (pMode === 'switch' ? t('switch_profile') : t('whos_watching')) + '</h2>' +
        '<div class="grid">' +
          signed.map(p => '<div class="prof foc" data-pid="' + p.id + '"><div class="pic" style="background:' + p.color + '"><div class="sheen"></div>' + p.initials + (p.kid ? '<span class="badge-k">' + t('kids') + '</span>' : '') + '</div><div class="nm">' + p.name + '</div>' + (p.isAdmin ? '<div class="tag">' + t('admin') + '</div>' : '') + '</div>').join('') +
          '<div class="prof foc" data-pid="__add"><div class="pic add">＋</div><div class="nm">' + t('add_user') + '</div></div>' +
          (pMode === 'switch' ? '<div class="prof foc" data-pid="__settings"><div class="pic settings">⚙</div><div class="nm">' + t('settings') + '</div></div>' : '') +
        '</div>' +
        (pMode === 'switch' ? '<div class="ft"><span class="btn ghost foc" data-pid="__close">' + t('cancel') + '</span></div>' : '<div class="ft"><span class="tiny" style="color:var(--ink-dim);font-size:15px;">' + t('profiles_hint') + '</span></div>');
      pTiles = [...prof.querySelectorAll('.foc')];
      pIdx = Math.min(pIdx, pTiles.length - 1);
      paintP();
    }
    function paintP() { pTiles.forEach((el, i) => el.classList.toggle('focused', i === pIdx)); }
    // group the focusable tiles into visual rows (by on-screen top) so the D-pad can move
    // up/down between rows (theme → language → unpair → back) and left/right within a row.
    function pGrid() {
      const rows = [];
      pTiles.forEach((el, i) => {
        const top = Math.round(el.getBoundingClientRect().top);
        let row = rows.find(r => Math.abs(r.top - top) < 24);
        if (!row) { row = { top, idx: [] }; rows.push(row); }
        row.idx.push(i);
      });
      rows.sort((a, b) => a.top - b.top);
      return rows;
    }
    function movePidx(dir) {
      const rows = pGrid(); if (!rows.length) return;
      let r = 0, c = 0;
      for (let ri = 0; ri < rows.length; ri++) { const ci = rows[ri].idx.indexOf(pIdx); if (ci >= 0) { r = ri; c = ci; break; } }
      if (dir === 'left') c = Math.max(0, c - 1);
      else if (dir === 'right') c = Math.min(rows[r].idx.length - 1, c + 1);
      else if (dir === 'up') { r = Math.max(0, r - 1); c = Math.min(c, rows[r].idx.length - 1); }
      else if (dir === 'down') { r = Math.min(rows.length - 1, r + 1); c = Math.min(c, rows[r].idx.length - 1); }
      pIdx = rows[r].idx[c];
    }
    function openProfiles(mode) {
      pMode = mode || 'gate'; pIdx = 0; renderProfiles(); prof.style.display = 'flex';
    }
    function closeProfiles() { prof.style.display = 'none'; }
    // ---- Settings page (opened from the profile grid, right of “Add user”) ----
    function openSettings() { pMode = 'settings'; pIdx = 0; renderSettings(); prof.style.display = 'flex'; }
    function renderSettings() {
      prof.className = 'profiles switch settings-panel';
      const curSkin = document.documentElement.getAttribute('data-skin') || 'aurora';
      const themes = [['aurora', 'Aurora'], ['midnight', 'Midnight'], ['noir', 'Noir']];
      const themeChips = themes.map(function (tm) {
        return '<div class="lang-chip theme-chip foc' + (tm[0] === curSkin ? ' cur' : '') + '" data-pid="__theme:' + tm[0] + '">' +
          '<span class="theme-dot ' + tm[0] + '"></span><span class="lang-endo">' + tm[1] + '</span></div>';
      }).join('');
      prof.innerHTML =
        '<h2>' + t('settings') + '</h2>' +
        '<div class="set-sec"><div class="prof-langs-h">' + t('theme') + '</div><div class="lang-row">' + themeChips + '</div></div>' +
        langSectionHTML() +
        '<div class="set-sec set-danger"><div class="prof-langs-h">' + t('unpair') + '</div>' +
          '<div class="set-danger-desc">' + t('unpair_desc') + '</div>' +
          '<div class="btn danger foc" data-pid="__logout"><span class="ic">⏻</span> ' + t('unpair') + '</div></div>' +
        '<div class="ft"><span class="btn ghost foc" data-pid="__close">' + t('back') + '</span></div>';
      pTiles = [...prof.querySelectorAll('.foc')];
      pIdx = Math.min(pIdx, pTiles.length - 1);
      paintP();
    }
    function setSkin(skin) {
      document.documentElement.setAttribute('data-skin', skin);
      try { localStorage.setItem('js-ravilo-skin', skin); } catch (e) {}
      const pick = document.getElementById('skinpick');
      if (pick) pick.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.skin === skin));
      renderSettings();
    }
    function doLogout() {
      try { localStorage.removeItem(PKEY); } catch (e) {}
      flash(t('toast_unpaired'));
      openProfiles('gate');
    }
    // unpair is destructive — confirm before signing everyone out
    function renderUnpairConfirm() {
      pMode = 'unpair';
      prof.className = 'profiles switch';
      prof.innerHTML =
        '<div class="signin-panel"><h3>' + t('unpair_confirm') + '</h3>' +
        '<div class="sub">' + t('unpair_desc') + '</div>' +
        '<div class="row center" style="gap:14px;justify-content:center;display:flex;margin-top:6px;">' +
          '<span class="btn ghost foc" data-pid="__close">' + t('cancel') + '</span>' +
          '<span class="btn danger foc" data-pid="__logout-confirm"><span class="ic">⏻</span> ' + t('unpair_yes') + '</span>' +
        '</div></div>';
      pTiles = [...prof.querySelectorAll('.foc')]; pIdx = 0; paintP();
    }
    function pickProfile(pid) {
      if (pid === '__settings') { openSettings(); return; }
      if (pid && pid.indexOf('__theme:') === 0) { setSkin(pid.slice(8)); return; }
      if (pid && pid.indexOf('__lang:') === 0) { setUserLang(pid.slice(7)); return; }
      if (pid === '__logout') { renderUnpairConfirm(); return; }
      if (pid === '__logout-confirm') { doLogout(); return; }
      if (pid === '__close') { if (pMode === 'unpair') { openSettings(); return; } if (pMode === 'settings') { openProfiles('switch'); return; } if (pMode === 'signin') { openProfiles(sgBack); return; } closeProfiles(); return; }
      if (pid && pid.indexOf('__kb:') === 0) { sgKey(pid.slice(5)); return; }
      if (pid && pid.indexOf('__field:') === 0) { sgField = pid.slice(8); sgPaintFields(); return; }
      if (pid === '__login') { sgSubmit(); return; }
      if (pid === '__add') { openSignin(); return; }
      const p = profiles.find(x => x.id === pid); if (!p) return;
      applyUser(p); closeProfiles();
      go({ type: 'home' }); setTimeout(() => focusRC(0, 0), 30);
      flash(t('signed_in_as', { name: p.name }));
    }
    // sign-in (R175): username + password entered on the TV, proxied to Jellyfin via
    // POST /api/tv/login (Phase 141). No pairing code, no polling — each success appends a profile.
    let sgUser = '', sgPass = '', sgField = 'user', sgShift = false, sgErr = null, sgBusy = false, sgBack = 'gate';
    const SG_COLORS = ['linear-gradient(135deg,#7b6ef0,#3fb6f5)', 'linear-gradient(135deg,#19d6c6,#2a8cf0)', 'linear-gradient(135deg,#f5b542,#e0792f)', 'linear-gradient(135deg,#e0567a,#7b6ef0)'];
    function sgFieldHTML(id, label, val, ph) {
      const live = sgField === id;
      const shown = id === 'pass' ? '\u2022'.repeat(val.length) : val;
      const body = val.length
        ? '<span class="' + (id === 'pass' ? 'mask' : '') + '">' + esc(shown) + '</span>' + (live ? '<span class="cursor"></span>' : '')
        : (live ? '<span class="cursor"></span>' : '<span class="ph">' + esc(ph) + '</span>');
      return '<div class="signin-field"><label>' + label + '</label><div class="inp foc' + (live ? ' live' : '') + '" data-pid="__field:' + id + '" data-sgf="' + id + '">' + body + '</div></div>';
    }
    function sgKeyRow(chars) {
      return '<div class="kbd-row">' + chars.split('').map(c => {
        const shown = sgShift ? c.toUpperCase() : c;
        return '<div class="key foc" data-pid="__kb:ch:' + esc(c) + '">' + esc(shown) + '</div>';
      }).join('') + '</div>';
    }
    function openSignin() {
      if (pMode !== 'signin') sgBack = (pMode === 'switch' || pMode === 'gate') ? pMode : 'switch';
      pMode = 'signin';
      sgUser = ''; sgPass = ''; sgField = 'user'; sgShift = false; sgErr = null; sgBusy = false;
      prof.className = 'profiles switch';
      prof.style.display = 'flex';
      renderSignin();
    }
    function renderSignin() {
      prof.innerHTML =
        '<div class="login-wrap">' +
          '<div class="signin-panel">' +
            '<h3>' + t('login_title') + '</h3>' +
            '<div class="sub">' + t('login_sub') + '</div>' +
            sgFieldHTML('user', t('login_user'), sgUser, 'eyd') +
            sgFieldHTML('pass', t('login_pass'), sgPass, '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022') +
            (sgErr ? '<div class="signin-err">⚠ ' + sgErr + '</div>' : '') +
            '<div class="actions">' +
              '<span class="btn ghost foc" data-pid="__close">' + t('back') + '</span>' +
              '<span class="btn primary foc" data-pid="__login">' + (sgBusy ? '<span class="spin"></span> ' + t('login_busy') : t('login_btn')) + '</span>' +
            '</div>' +
          '</div>' +
          '<div class="login-kbd">' +
            sgKeyRow('1234567890') +
            sgKeyRow('qwertyuiop') +
            sgKeyRow('asdfghjkl-') +
            sgKeyRow('zxcvbnm._@') +
            '<div class="kbd-row">' +
              '<div class="key wide foc" data-pid="__kb:shift">' + t('key_shift') + '</div>' +
              '<div class="key wide foc" data-pid="__kb:space">' + t('key_space') + '</div>' +
              '<div class="key wide foc" data-pid="__kb:del">' + t('key_del') + '</div>' +
            '</div>' +
          '</div>' +
        '</div>';
      pTiles = [...prof.querySelectorAll('.foc')];
      if (pIdx >= pTiles.length) pIdx = 0;
      paintP();
    }
    function sgPaintFields() {
      prof.querySelectorAll('[data-sgf]').forEach(f => {
        const id = f.dataset.sgf, val = id === 'pass' ? sgPass : sgUser, live = sgField === id;
        const shown = id === 'pass' ? '\u2022'.repeat(val.length) : val;
        f.classList.toggle('live', live);
        f.innerHTML = val.length
          ? '<span class="' + (id === 'pass' ? 'mask' : '') + '">' + esc(shown) + '</span>' + (live ? '<span class="cursor"></span>' : '')
          : (live ? '<span class="cursor"></span>' : '<span class="ph">' + esc(id === 'pass' ? '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022' : 'eyd') + '</span>');
      });
    }
    function sgType(ch) {
      if (sgBusy) return;
      if (ch === '\b') { if (sgField === 'pass') sgPass = sgPass.slice(0, -1); else sgUser = sgUser.slice(0, -1); }
      else { if (sgField === 'pass') sgPass += ch; else sgUser += ch; }
      sgPaintFields();
    }
    function sgKey(code) {
      if (code === 'shift') { sgShift = !sgShift; renderSignin(); return; }
      if (code === 'space') { sgType(' '); return; }
      if (code === 'del') { sgType('\b'); return; }
      if (code.indexOf('ch:') === 0) { const c = code.slice(3); sgType(sgShift ? c.toUpperCase() : c); }
    }
    function sgSubmit() {
      if (sgBusy) return;
      if (!sgUser.trim()) { sgErr = t('login_err_user'); sgField = 'user'; renderSignin(); return; }
      if (!sgPass) { sgErr = t('login_err_pass'); sgField = 'pass'; renderSignin(); return; }
      sgErr = null; sgBusy = true; renderSignin();
      // demo: the password “wrong” shows the invalid-credentials state; anything else signs in
      setTimeout(() => {
        sgBusy = false;
        if (sgPass === 'wrong') { sgPass = ''; sgErr = t('login_err_cred'); sgField = 'pass'; renderSignin(); return; }
        const name = sgUser.trim();
        const initials = name.split(/[\s._-]+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase() || 'U';
        const p = { id: 'u-' + Date.now(), name: name[0].toUpperCase() + name.slice(1), initials, color: SG_COLORS[profiles.length % SG_COLORS.length], lang: 'en', signedIn: true };
        profiles.push(p);
        applyUser(p); closeProfiles();
        flash(t('signed_in_as', { name: p.name }));
      }, 900);
    }
    // capture-phase key handling so the gate/switcher owns input while open
    window.addEventListener('keydown', e => {
      if (prof.style.display === 'none') return;
      const k = e.key;
      // R175: while the login screen is open, printable keys + Backspace type into the active field
      if (pMode === 'signin' && !sgBusy) {
        if (k.length === 1 && k !== ' ' && !e.metaKey && !e.ctrlKey && !e.altKey) { e.preventDefault(); e.stopImmediatePropagation(); sgType(k); return; }
        if (k === 'Backspace') { e.preventDefault(); e.stopImmediatePropagation(); sgType('\b'); return; }
      }
      if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter',' ','Backspace','Escape'].includes(k)) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (k === 'Backspace' || k === 'Escape') { if (pMode === 'unpair') { openSettings(); return; } if (pMode === 'settings') { openProfiles('switch'); return; } if (pMode === 'signin') { openProfiles(sgBack); return; } if (pMode === 'switch') closeProfiles(); return; }
      if (k === 'Enter' || k === ' ') { const t = pTiles[pIdx]; if (t) pickProfile(t.dataset.pid); return; }
      if (k === 'ArrowRight') movePidx('right');
      else if (k === 'ArrowLeft') movePidx('left');
      else if (k === 'ArrowDown') movePidx('down');
      else if (k === 'ArrowUp') movePidx('up');
      paintP();
    }, true);
    prof.addEventListener('click', e => { const t = e.target.closest('.foc'); if (t) pickProfile(t.dataset.pid); });
    window.__raviloProfiles = { open: openProfiles };

    // ---- boot ----
    const startUser = currentUser();
    updateUpcomingNav();
    go({ type: 'home' });
    if (startUser) applyUser(startUser);
    setTimeout(() => focusRowByIndex(0), 40);
    cur = { r: 0, c: 0 }; focusEl(items(rows()[0])[0]);
    if (!startUser && interactive) openProfiles('gate');   // first run → "Who's watching?"

    // demo: simulate an admin sending a message from the Jellyfin dashboard (single message field)
    if (interactive) setTimeout(() => showServerMessage('Dinner in ten minutes — please pause your show and come to the kitchen.'), 2600);

    return { go, setSkin: () => {}, openProfiles };
  }

  window.mountRavilo = mountRavilo;

  /* ---- viewport scaling (shared) ---- */
  window.fitStage = function (stage) {
    function fit() {
      const vw = window.innerWidth, vh = window.innerHeight;
      const s = Math.min(vw / 1920, vh / 1080);
      stage.style.transform = `translate(-50%,-50%) scale(${s})`;
    }
    fit(); window.addEventListener('resize', fit); return fit;
  };
})();
