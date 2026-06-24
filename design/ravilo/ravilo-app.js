/* Ravilo app engine — render + hero carousel + D-pad focus + viewport scaling.
   mountRavilo(stage, { interactive }) where stage is the 1920×1080 element. */
(function () {
  const R = window.RAVILO;

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
      if (u && window.setRaviloLang) window.setRaviloLang(u.lang || 'en');
    } catch (e) {}
    let view = { type: 'home', studio: null };
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
        <div class="navitem foc" data-nav="top10" id="rv-nav-top10" style="display:none">${t('nav_top10')}</div>
        <div class="navitem foc" data-nav="mylist">${t('nav_mylist')}</div>
      </div>
      <div class="right">
        <div class="search-ic foc" data-nav="search"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"></circle><line x1="16.5" y1="16.5" x2="21" y2="21"></line></svg></div>
        <div class="clock"></div>
        <div class="avatar foc" data-nav="profile" id="rv-avatar">ER</div>
      </div>`;
    stage.appendChild(appbar);

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
      if (isBBB(item)) return {
        audio: [{ label: 'English', desc: 'Stereo · AAC' }, { label: "Director's Commentary", desc: 'Stereo · AAC' }],
        subs: [{ label: 'Off', off: true }, { label: 'English' }, { label: 'Føroyskt', desc: 'Faroese' }, { label: 'Dansk' }],
        audioDefault: 0, subsDefault: 0,
      };
      return {
        audio: [{ label: 'Føroyskt', desc: '5.1 · AC-3' }, { label: 'English', desc: 'Stereo · AAC · dub' }],
        subs: [{ label: 'Off', off: true }, { label: 'Føroyskt', desc: 'Full' }, { label: 'English' }, { label: 'Dansk', desc: 'Signs only', flag: 'Forced' }],
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
      const resume = (item.pct > 0 && item.pct < 100) ? Math.round(dur * item.pct / 100) : 0;
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
      const resume = (e.pct > 0 && e.pct < 100) ? Math.round(dur * e.pct / 100) : 0;
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
        episodes: eps.map(x => ({ n: x.n, title: x.title, dur: x.dur, grad: x.grad, pct: x.pct || 0, watched: (x.pct || 0) >= 100 })),
        resolveEpisode: (i) => episodeCtx(seriesItem, season, eps, i),
      };
    }
    function playItem(item, season) {
      if (item.kind === 'series') {
        season = season || 0;
        const eps = R.episodesFor(item, season);
        const prog = seriesProgress(eps);
        openPlayer(episodeCtx(item, season, eps, prog.idx));
      } else {
        openPlayer(movieCtx(item));
      }
    }
    function openPlayer(ctx) { stopHero(); player.open(ctx); }
    const player = window.initRaviloPlayer(stage, {
      flash,
      restoreFocus: function (watched) {
        if (view.type === 'home') startHero();
        const all = rows(); const its = items(all[cur.r] || all[0]);
        if (its[cur.c]) focusEl(its[cur.c]); else focusRowByIndex(0);
        if (watched) flash('✓ Progress saved — jellystructure → Jellyfin');
      },
    });

    function clock() {
      const d = new Date();
      const c = appbar.querySelector('.clock');
      if (c) c.textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    }
    clock(); setInterval(clock, 10000);

    /* ---------------- HERO ---------------- */
    function buildHero() {
      const hero = el('div', 'hero');
      hero.style.height = '56%';
      R.hero.forEach((it, i) => {
        const s = el('div', 'hero-slide' + (i === 0 ? ' on' : ''));
        s.innerHTML = `
          <div class="hero-bg">${it.backdrop ? `<img class="hero-backdrop" src="${it.backdrop}" alt="">` : `<div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div>`}<div class="hero-scrim"></div></div>
          <div class="hero-body">
            <div class="hero-kicker"><span>${it.tagline}</span><span class="n">${it.kind === 'series' ? 'Series' : 'Film'}</span></div>
            ${it.logo ? `<img class="hero-logo" src="${it.logo}" alt="${it.title}">` : `<div class="hero-title">${it.title}</div>`}
            <div class="hero-meta"><span class="tag">${it.badge}</span><span>${it.year}</span><span>${it.genre}</span><span class="rt">${it.rating}+</span></div>
            <div class="hero-syn">${it.syn}</div>
          </div>`;
        hero.appendChild(s);
      });
      // R53: button-less hero — one focusable hit area covers the whole hero; select opens detail,
      // Left/Right pages the carousel. (Buttons removed; the detail screen owns Play/resume.)
      const hit = el('div', 'hero-hit focus-row');
      hit.innerHTML = `<div class="hero-cta foc" data-hero="1"></div>`;
      hero.appendChild(hit);

      const dots = el('div', 'hero-dots');
      R.hero.forEach((_, i) => { const d = el('div', 'd' + (i === 0 ? ' on' : '')); d.dataset.dot = i; dots.appendChild(d); });
      hero.appendChild(dots);
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

    /* ---------------- ROWS ---------------- */
    function tile(item, kind) {
      const t = el('div', 'tile ' + (kind === 'land' ? 'land' : 'poster') + ' foc');
      t._item = item;
      if (kind === 'land') {
        t.innerHTML = `<div class="art">${item.image ? `<img class="tile-img" src="${item.image}" alt="">` : `<div class="grad" style="background:${item.grad}"></div>`}
          <div class="meta-ep">${item.ep || ''}</div>
          <div class="play"><span>▶</span></div>
          <div class="pbar"><i style="width:${item.pct || 0}%"></i></div></div>
          <div class="label">${item.title}</div><div class="sub">${item.next ? 'Next up' : (item.genre || '')}</div>`;
      } else {
        t.innerHTML = `<div class="art">${artFill(item)}${item.badge ? `<div class="badge">${item.badge}</div>` : ''}</div>
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
      const head = el('div', 'cathead');
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
    }

    /* ---------------- DETAIL PAGE ---------------- */
    function detailBg(it) {
      return it.backdrop
        ? `<img class="hero-backdrop" src="${it.backdrop}" alt="">`
        : `<div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div>`;
    }
    function detailTitle(it) {
      return it.logo ? `<img class="dhero-logo" src="${it.logo}" alt="${it.title}">` : `<div class="dhero-title">${it.title}</div>`;
    }
    function episodeCard(e, st) {
      st = st || {};
      const cls = 'ep-card foc' + (st.watched ? ' watched' : '') + (st.inprogress ? ' inprogress' : '') + (st.upnext ? ' upnext' : '');
      const t = el('div', cls); t._ep = e;
      t.innerHTML = `<div class="ep-still"><div class="grad" style="background:${e.grad}"></div>
        <span class="ep-num">${e.n}</span><span class="ep-dur">${e.dur}</span>
        ${st.upnext ? '<span class="ep-ribbon">UP NEXT</span>' : ''}
        ${st.watched ? '<span class="ep-check">✓</span>' : ''}
        <div class="play"><span>▶</span></div>
        ${(st.inprogress || st.watched) ? `<div class="ep-prog"><i style="width:${st.watched ? 100 : e.pct}%"></i></div>` : ''}</div>
        <div class="ep-info"><div class="ep-t">${e.n}. ${e.title}${st.watched ? ' <span class="ep-tag">Watched</span>' : ''}</div><div class="ep-d">${e.desc}</div></div>`;
      return t;
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
    function castCircle(c) {
      const t = el('div', 'cast foc'); t._cast = c;
      t.innerHTML = `<div class="cast-av" style="background:${R.grad(c.n)}">${R.initials(c.n)}</div>
        <div class="cast-n">${c.n}</div><div class="cast-r">${c.r}</div>`;
      return t;
    }
    function renderDetail(item) {
      stopHero();
      scroll.innerHTML = '';
      const isSeries = item.kind === 'series';
      const seasons = isSeries ? R.seasonsFor(item) : 0;
      const season = view.season || 0;
      const eps = isSeries ? R.episodesFor(item, season) : [];
      const prog = isSeries ? seriesProgress(eps) : null;
      const rEp = isSeries ? eps[prog.idx] : null;
      const d = el('div', 'detail');

      let playLabel, upNote = '';
      if (isSeries) {
        if (prog.watched === 0 && !rEp.pct) playLabel = t('play') + ' · E1';
        else if (rEp.pct > 0 && rEp.pct < 100) { playLabel = t('resume') + ` · E${rEp.n}`; upNote = `Resume S${season + 1} · E${rEp.n} “${rEp.title}” · ${minsLeft(rEp)} min left`; }
        else { playLabel = t('play') + ` · E${rEp.n}`; upNote = `Up next · S${season + 1} · E${rEp.n} “${rEp.title}”`; }
      } else {
        playLabel = (item.pct > 0 && item.pct < 100) ? t('resume') + ` · ${minsLeft(item)} min left` : t('play');
      }

      const dhero = el('div', 'dhero');
      dhero.innerHTML = `<div class="hero-bg">${detailBg(item)}<div class="hero-scrim"></div></div>
        <div class="dhero-body">
          <div class="hero-kicker"><span>${item.tagline || (isSeries ? 'Series' : 'Film')}</span><span class="n">${isSeries ? seasons + ' Season' + (seasons > 1 ? 's' : '') : (item.year || '')}</span></div>
          ${detailTitle(item)}
          <div class="hero-meta"><span class="tag">${item.badge || 'HD'}</span><span>${item.year}</span><span>${item.genre}</span><span class="rt">${item.rating}+</span></div>
          <div class="hero-syn">${item.syn || 'A standout from your Ravilo library — streamed from Jellyfin, organised by Jellystructure.'}</div>
          ${upNote ? `<div class="dnext"><span class="dnext-dot"></span>${upNote}</div>` : ''}
          <div class="dactions focus-row">
            <div class="btn primary foc" data-play="1"><span class="ic">▶</span> ${playLabel}</div>
            <div class="btn ghost foc" data-trailer="1"><span class="ic">▷</span> ${t('trailer')}</div>
            <div class="btn ghost foc" data-list="1"><span class="ic">＋</span> ${t('add_list')}</div>
          </div>
        </div>`;
      d.appendChild(dhero);

      if (isSeries) {
        const pctWatched = Math.round(prog.watched / eps.length * 100);
        const sec = el('div', 'dsec');
        sec.innerHTML = `<div class="dsec-head"><h2>Episodes</h2>
          <span class="dsec-sub">${prog.watched} of ${eps.length} watched</span>
          <span class="seasonbar"><i style="width:${pctWatched}%"></i></span></div>`;
        const pills = el('div', 'seasonpills focus-row');
        for (let i = 0; i < seasons; i++) { const p = el('div', 'spill foc' + (i === season ? ' cur' : '')); p._season = i; p.textContent = 'Season ' + (i + 1); pills.appendChild(p); }
        sec.appendChild(pills);
        d.appendChild(sec);
        const epRow = el('div', 'crow eprow');
        const track = el('div', 'track focus-row');
        track.dataset.def = prog.idx;
        eps.forEach((e, i) => track.appendChild(episodeCard(e, {
          watched: e.pct >= 100, inprogress: e.pct > 0 && e.pct < 100, upnext: i === prog.idx,
        })));
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
    function statusMark(it) {
      if (it.status === 'available') return `<span class="rstat avail" title="In Library">✓</span>`;
      if (it.status === 'fetching') return `<span class="rstat fetch" title="Fetching"><span class="spin"></span><span class="pct">${it.progress || 0}%</span></span>`;
      return '';
    }
    function rankTile(it, list) {
      const tl = el('div', 'rtile foc'); tl._ditem = it; tl._dlist = list;
      const sub = list.metric === 'rank'
        ? t('weeks_on', { n: it.weeks })
        : (it.views ? t('views_week', { v: it.views }) : t('weeks_on', { n: it.weeks }));
      tl.innerHTML = `
        <div class="rtop">
          <div class="rnum">${it.rank}</div>
          <div class="rposter"><div class="art">${artGrad(it)}${statusMark(it)}</div></div>
        </div>
        <div class="label">${it.title}</div>
        <div class="sub">${sub} ${trendBadge(it)}</div>`;
      return tl;
    }
    function discoverRow(list) {
      const r = el('div', 'crow drow');
      r.innerHTML = `<div class="crow-head"><h2>${list.title}</h2><span class="cfg">${list.scope === 'country' ? R.discover.config.region : (list.scope === 'alltime' ? 'All-time' : 'Global')}</span><span class="more">${list.note || ''}</span></div>`;
      const track = el('div', 'track focus-row');
      list.items.forEach(it => track.appendChild(rankTile(it, list)));
      r.appendChild(track);
      return r;
    }
    function renderDiscover() {
      stopHero(); scroll.innerHTML = '';
      const src = activeSource();
      const region = R.discover.config.regionName || R.discover.config.region;
      const wrap = el('div', 'discoverscreen');
      const head = el('div', 'dischead');
      head.innerHTML = `<div class="dischead-row"><h1>${t('nav_top10')}</h1><span class="disc-sub">${t('top10_sub', { region })}</span></div>`;
      const srcRow = el('div', 'srcpick focus-row');
      (R.discover.sources || []).forEach(s => {
        const c = el('div', 'srcchip foc' + (s.enabled ? (s.id === src.id ? ' cur' : '') : ' off')); c._src = s;
        c.innerHTML = `<span class="srcwm" style="background:${s.accent}">${s.wm}</span><span class="srcnm">${s.name}</span><span class="srcvia">${s.enabled ? t('via_source', { src: s.via }) : 'soon'}</span>`;
        srcRow.appendChild(c);
      });
      head.appendChild(srcRow);
      wrap.appendChild(head);
      const lists = listsForUser();
      lists.forEach(l => wrap.appendChild(discoverRow(l)));
      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === 'top10'));
    }

    function requestFetch(it) {
      it.status = 'fetching'; it.progress = 1;
      flash('＋ ' + it.title + ' · ' + t('requested_via'));
      renderDiscoverDetail(it, view.list);
      setTimeout(() => focusRC(1, 0), 20);
    }
    function discoverActions(it) {
      let primary;
      if (it.status === 'available') primary = `<div class="btn primary foc" data-dact="watch"><span class="ic">▶</span> ${t('watch_now')}</div>`;
      else if (it.status === 'fetching') primary = `<div class="btn fetching foc" data-dact="progress"><span class="spin"></span> ${t('fetching')} · ${it.progress || 0}%</div>`;
      else primary = `<div class="btn primary foc" data-dact="request"><span class="ic">＋</span> ${t('request_fetch')}</div>`;
      return `<div class="ddt-actions focus-row">
        ${primary}
        <div class="btn ghost foc" data-dact="list"><span class="ic">＋</span> ${t('add_list')}</div>
      </div>`;
    }
    function renderDiscoverDetail(it, list) {
      stopHero(); scroll.innerHTML = '';
      const src = activeSource();
      const region = R.discover.config.regionName || R.discover.config.region;
      const isCountry = list && list.scope === 'country';
      const statusLine = it.status === 'available'
        ? `<span class="ddt-state avail">✓ ${t('in_library')}</span>`
        : it.status === 'fetching'
          ? `<span class="ddt-state fetch"><span class="spin"></span> ${t('fetching')} · ${it.progress || 0}%</span>`
          : `<span class="ddt-state none">${t('not_in_library')}</span>`;
      const d = el('div', 'ddt');
      d.innerHTML = `
        <div class="ddt-hero">
          <div class="ddt-bg"><div class="grad" style="position:absolute;inset:0;background:${it.grad}"></div><div class="hero-noise"></div><div class="ddt-scrim"></div></div>
          <div class="ddt-body">
            <div class="ddt-kicker"><span class="ddt-srcwm" style="background:${src.accent}">${src.wm}</span>${src.name} ${t('via_source', { src: src.via })}<span class="ddt-rank">${t('rank_in', { n: it.rank, region })}</span></div>
            <div class="ddt-title">${it.title}</div>
            <div class="hero-meta"><span class="tag">${it.rating}+</span><span>${it.year}</span><span>${it.genre}</span><span>${it.kind === 'series' ? 'Series' : 'Film'}</span>${statusLine}</div>
            <div class="hero-syn">${it.syn || 'Trending on ' + src.name + ' right now. Not yet in your Jellyfin library — request it and Radarr will grab it, then Jellystructure organises it automatically.'}</div>
            ${discoverActions(it)}
          </div>
        </div>
        <div class="ddt-why">
          <h2>${t('why_trending')}</h2>
          <div class="ddt-stats">
            <div class="ddt-stat"><div class="k">${t('rank_in', { n: it.rank, region: isCountry ? region : (list.scope === 'alltime' ? 'all-time' : 'global') })}</div><div class="v">#${it.rank}</div></div>
            <div class="ddt-stat"><div class="k">${list && list.metric === 'rank' ? 'On chart' : 'Views'}</div><div class="v">${list && list.metric === 'rank' ? t('weeks_on', { n: it.weeks }) : (it.views ? it.views : '—')}</div></div>
            <div class="ddt-stat"><div class="k">Trend</div><div class="v ddt-trend">${trendBadge(it)}</div></div>
          </div>
          ${isCountry ? `<div class="ddt-foot">Country charts are ranking only — no view counts. Source: ${src.name} via ${src.via}.</div>` : `<div class="ddt-foot">Source: ${src.name} via ${src.via}.</div>`}
        </div>
        <div class="screen-end"></div>`;
      scroll.appendChild(d);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.remove('cur'));
    }

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
    function searchFilter(q) { const c = catalog(); if (!q.trim()) return c.slice(0, 18); const l = q.toLowerCase(); return c.filter(it => it.title.toLowerCase().includes(l)); }
    function renderSearch(v) {
      stopHero(); scroll.innerHTML = '';
      v.query = v.query || '';
      const wrap = el('div', 'searchscreen');
      wrap.innerHTML = `<div class="searchbar"><span class="sic">⌕</span><span class="sq">${v.query ? esc(v.query) : '<i>Search movies &amp; series…</i>'}</span></div>`;
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
      const res = el('div', 'pgrid sresults'); buildGridRows(res, searchFilter(v.query)); wrap.appendChild(res);
      wrap.appendChild(el('div', 'screen-end'));
      scroll.appendChild(wrap);
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === 'search'));
    }
    function esc(s) { return s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

    function go(v) {
      view = v;
      if (v.type === 'home') renderHome();
      else if (v.type === 'category') renderCategory(v.studio);
      else if (v.type === 'movie' || v.type === 'series') renderDetail(v.item);
      else if (v.type === 'discover') renderDiscover();
      else if (v.type === 'discoverDetail') renderDiscoverDetail(v.item, v.list);
      else if (v.type === 'grid') renderGrid(v);
      else if (v.type === 'search') renderSearch(v);
      scroll.scrollTop = 0;
      setTimeout(() => {
        if (v.type === 'home') focusRowByIndex(0);
        else if (v.type === 'category' || v.type === 'discover') focusRowByIndex(firstContentRowIndex());
        else focusRC(1, 0); // detail / discoverDetail / grid / search → first focusable row
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
        // R53: on the hero, left/right pages the carousel (one focusable, no columns).
        if (its[0].dataset.hero) { setHero(heroIdx + dc); return; }
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
        else if (f.dataset.nav === 'mylist') go({ type: 'grid', kind: 'mylist', title: 'My List', nav: 'mylist' });
        else if (f.dataset.nav === 'profile') openProfiles('switch');
        return;
      }
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
        scroll.querySelector('.sq').innerHTML = view.query ? esc(view.query) : '<i>Search movies &amp; series…</i>';
        scroll.querySelector('.sres-h').textContent = view.query.trim() ? 'Results' : 'Suggestions';
        buildGridRows(scroll.querySelector('.sresults'), searchFilter(view.query));
        return;
      }
      if (f.dataset.hero) { toDetail(R.hero[heroIdx]); return; } // R53: whole hero → detail
      if (f.dataset.play) { playItem(view.item, view.season || 0); return; }
      if (f.dataset.trailer) { flash('▷ Trailer · ' + view.item.title); return; }
      if (f.dataset.list) { flash('＋ Added ' + view.item.title + ' to My List'); return; }
      if (f._season != null) {
        if (f._season !== (view.season || 0)) { view.season = f._season; renderDetail(view.item); setTimeout(() => focusRC(2, f._season), 20); }
        return;
      }
      if (f._ep) { const eps = R.episodesFor(view.item, view.season || 0); const idx = eps.findIndex(x => x.n === f._ep.n); openPlayer(episodeCtx(view.item, view.season || 0, eps, Math.max(0, idx))); return; }
      if (f._cast) { flash(f._cast.n + ' · ' + f._cast.r); return; }
      if (f._src) { if (f._src.enabled) { renderDiscover(); setTimeout(() => focusRowByIndex(firstContentRowIndex()), 20); } else flash(f._src.name + ' · coming soon'); return; }
      if (f._ditem) { go({ type: 'discoverDetail', item: f._ditem, list: f._dlist, from: { type: 'discover' } }); return; }
      if (f.dataset.dact) {
        const it = view.item;
        if (f.dataset.dact === 'watch') playItem(it);
        else if (f.dataset.dact === 'request') requestFetch(it);
        else if (f.dataset.dact === 'progress') flash(t('fetching') + ' · ' + (it.progress || 0) + '% · Radarr');
        else if (f.dataset.dact === 'list') flash('＋ ' + it.title);
        return;
      }
      if (f._studio) { go({ type: 'category', studio: f._studio.id }); return; }
      if (f._item) { if (f.classList.contains('land')) playItem(f._item, 0); else toDetail(f._item); return; }
    }
    function back() {
      if (overlay.classList.contains('on')) { closeOverlay(); return; }
      if (view.type === 'discoverDetail') { go(view.from || { type: 'discover' }); return; }
      if (view.type === 'movie' || view.type === 'series') { go(view.from || { type: 'home' }); return; }
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

    /* ---- toast ---- */
    function flash(msg) {
      let t = stage.querySelector('.rv-toast');
      if (!t) { t = el('div', 'rv-toast'); t.style.cssText = 'position:absolute;left:50%;bottom:60px;transform:translateX(-50%);background:rgba(10,12,19,.92);backdrop-filter:blur(10px);border:1px solid var(--line);color:var(--ink);font-size:20px;font-weight:600;padding:16px 28px;border-radius:14px;z-index:90;box-shadow:0 18px 50px rgba(0,0,0,.5);transition:opacity .25s;'; stage.appendChild(t); }
      t.textContent = msg; t.style.opacity = '1'; clearTimeout(t._h); t._h = setTimeout(() => t.style.opacity = '0', 1700);
    }

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
      if (window.setRaviloLang) window.setRaviloLang(p.lang || 'en');
      relabelChrome();
      updateDiscoverNav();
      const av = document.getElementById('rv-avatar');
      if (av) { av.textContent = p.initials; av.style.background = p.color; }
    }
    // re-label the persistent app-bar nav after a language switch (screens re-localize via go())
    function relabelChrome() {
      const map = { home: 'nav_home', movies: 'nav_movies', series: 'nav_series', top10: 'nav_top10', mylist: 'nav_mylist' };
      appbar.querySelectorAll('.navitem').forEach(n => { const k = map[n.dataset.nav]; if (k) n.textContent = t(k); });
    }
    // Top 10 tab is gated: Radarr enabled in Jellystructure AND the user's per-user discover config.
    function discoverEnabled() {
      const u = currentUser();
      return !!(R.discover && R.discover.config && R.discover.config.radarr && u && u.discover && u.discover.enabled && (u.discover.lists || []).length);
    }
    function updateDiscoverNav() {
      const el = document.getElementById('rv-nav-top10');
      if (el) el.style.display = discoverEnabled() ? '' : 'none';
    }
    function renderProfiles() {
      const signed = profiles.filter(p => p.signedIn);
      prof.className = 'profiles' + (pMode === 'switch' ? ' switch' : '');
      prof.innerHTML =
        '<h2>' + (pMode === 'switch' ? t('switch_profile') : t('whos_watching')) + '</h2>' +
        '<div class="grid">' +
          signed.map(p => '<div class="prof foc" data-pid="' + p.id + '"><div class="pic" style="background:' + p.color + '"><div class="sheen"></div>' + p.initials + (p.kid ? '<span class="badge-k">' + t('kids') + '</span>' : '') + '</div><div class="nm">' + p.name + '</div>' + (p.isAdmin ? '<div class="tag">' + t('admin') + '</div>' : '') + '</div>').join('') +
          '<div class="prof foc" data-pid="__add"><div class="pic add">＋</div><div class="nm">' + t('add_user') + '</div></div>' +
        '</div>' +
        (pMode === 'switch' ? '<div class="ft"><span class="btn ghost foc" data-pid="__close">' + t('cancel') + '</span></div>' : '<div class="ft"><span class="tiny" style="color:var(--ink-dim);font-size:15px;">' + t('profiles_hint') + '</span></div>');
      pTiles = [...prof.querySelectorAll('.foc')];
      pIdx = Math.min(pIdx, pTiles.length - 1);
      paintP();
    }
    function paintP() { pTiles.forEach((el, i) => el.classList.toggle('focused', i === pIdx)); }
    function openProfiles(mode) {
      pMode = mode || 'gate'; pIdx = 0; renderProfiles(); prof.style.display = 'flex';
    }
    function closeProfiles() { prof.style.display = 'none'; }
    function pickProfile(pid) {
      if (pid === '__close') { closeProfiles(); return; }
      if (pid === '__add') { openSignin(); return; }
      const p = profiles.find(x => x.id === pid); if (!p) return;
      applyUser(p); closeProfiles();
      go({ type: 'home' }); setTimeout(() => focusRC(0, 0), 30);
      flash(t('signed_in_as', { name: p.name }));
    }
    // sign-in: pairing-code flow (matches the Ravilo pairing model — no password typed on the TV)
    function openSignin() {
      pMode = 'signin';
      prof.className = 'profiles switch';
      const code = Math.random().toString(36).slice(2, 8).toUpperCase();
      prof.innerHTML =
        '<div class="signin-panel"><h3>' + t('add_title') + '</h3>' +
        '<div class="sub">' + t('add_sub') + '</div>' +
        '<div class="pair-code">' + code + '</div>' +
        '<div class="row center" style="gap:14px;justify-content:flex-end;display:flex;"><span class="btn ghost foc" data-pid="__close">' + t('back') + '</span><span class="btn primary foc" data-pid="__waiting">' + t('waiting') + '</span></div></div>';
      pTiles = [...prof.querySelectorAll('.foc')]; pIdx = 0; paintP();
    }
    // capture-phase key handling so the gate/switcher owns input while open
    window.addEventListener('keydown', e => {
      if (prof.style.display === 'none') return;
      const k = e.key;
      if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter',' ','Backspace','Escape'].includes(k)) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (k === 'Backspace' || k === 'Escape') { if (pMode === 'switch' || pMode === 'signin') closeProfiles(); return; }
      if (k === 'Enter' || k === ' ') { const t = pTiles[pIdx]; if (t) pickProfile(t.dataset.pid); return; }
      // grid is a single wrapping row of tiles → left/right (and up/down) step through
      if (k === 'ArrowRight' || k === 'ArrowDown') pIdx = Math.min(pTiles.length - 1, pIdx + 1);
      else if (k === 'ArrowLeft' || k === 'ArrowUp') pIdx = Math.max(0, pIdx - 1);
      paintP();
    }, true);
    prof.addEventListener('click', e => { const t = e.target.closest('.foc'); if (t) pickProfile(t.dataset.pid); });
    window.__raviloProfiles = { open: openProfiles };

    // ---- boot ----
    const startUser = currentUser();
    go({ type: 'home' });
    if (startUser) applyUser(startUser);
    setTimeout(() => focusRowByIndex(0), 40);
    cur = { r: 0, c: 0 }; focusEl(items(rows()[0])[0]);
    if (!startUser && interactive) openProfiles('gate');   // first run → "Who's watching?"

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
