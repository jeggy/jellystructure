/* Injects the persistent left sidebar, mobile drawer, ambient scan dock, and the
   floating Triage navigation dock into every app page.
   Each page provides <main class="app-main2" data-page="..."> as its only body content.
   Opt-outs / opt-ins via attributes on <main>:
     data-no-dock   — suppress BOTH docks (full-screen editing surfaces)
     data-scan      — also show the ambient scan dock (demo of an active scan)
   Theme: three-way Light / Dark / System picker, persisted in localStorage 'js-theme'. */
(function () {

  /* ---- apply saved appearance ASAP (before building DOM) ---- */
  const root = document.documentElement;
  root.setAttribute('data-booting', '');
  root.setAttribute('data-dir', 'aurora');
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  function storedMode() { return localStorage.getItem('js-theme') || 'system'; }
  function resolveTheme(mode) { return mode === 'system' ? (mq.matches ? 'dark' : 'light') : mode; }
  function applyTheme() { root.setAttribute('data-theme', resolveTheme(storedMode())); }
  applyTheme();
  mq.addEventListener('change', () => { if (storedMode() === 'system') applyTheme(); });
  requestAnimationFrame(() => requestAnimationFrame(() => root.removeAttribute('data-booting')));

  /* ---- icons (simple geometric line set) ---- */
  const I = {
    dashboard: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="3" width="7.5" height="7.5" rx="1.5"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5"/></svg>',
    library:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3.5" y="4" width="7" height="16" rx="1.5"/><rect x="13.5" y="4" width="7" height="16" rx="1.5"/></svg>',
    activity:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    metadata:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M4 7.5 11 3.5a2 2 0 0 1 2 0l7 4v9l-7 4a2 2 0 0 1-2 0l-7-4z"/><circle cx="8.4" cy="9.2" r="1.1" fill="currentColor" stroke="none"/></svg>',
    settings:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><line x1="4" y1="8" x2="20" y2="8"/><line x1="4" y1="16" x2="20" y2="16"/><circle cx="15" cy="8" r="2.4" fill="var(--fill-2)"/><circle cx="9" cy="16" r="2.4" fill="var(--fill-2)"/></svg>',
    signout:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4"/><path d="M9 8l-4 4 4 4"/><line x1="5" y1="12" x2="15" y2="12"/></svg>',
    attn:      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M12 4 21 19H3z"/><line x1="12" y1="10" x2="12" y2="14" stroke-linecap="round"/><circle cx="12" cy="16.6" r="0.4" fill="currentColor" stroke="none"/></svg>',
    menu:      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/></svg>',
    ravilo:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><rect x="3" y="4.5" width="18" height="12.5" rx="2"/><path d="M10 8.5l4.5 2.75L10 14z" fill="currentColor" stroke="none"/><line x1="8.5" y1="20" x2="15.5" y2="20" stroke-linecap="round"/></svg>'
  };

  /* ---- brand mark: “Quartet Play” (structure tile with the open slot as a play) ---- */
  let _bm = 0;
  function BRAND(size) {
    const id = 'jsg-' + (++_bm);
    return '<svg class="brand-mark" width="' + size + '" height="' + size + '" viewBox="0 0 100 100" aria-hidden="true">' +
      '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="1" y2="1">' +
        '<stop offset="0" stop-color="#b15cd0"/><stop offset=".52" stop-color="#7b6ef0"/><stop offset="1" stop-color="#00a4dc"/>' +
      '</linearGradient></defs>' +
      '<rect width="100" height="100" rx="23" fill="url(#' + id + ')"/>' +
      '<g transform="translate(18 18) scale(.64)" fill="#fff">' +
        '<rect x="10" y="10" width="35" height="35" rx="9"/>' +
        '<rect x="55" y="10" width="35" height="35" rx="9" opacity=".5"/>' +
        '<rect x="10" y="55" width="35" height="35" rx="9" opacity=".5"/>' +
        '<rect x="55" y="55" width="35" height="35" rx="9" fill="none" stroke="#fff" stroke-width="6"/>' +
        '<path d="M68 64 L84 72.5 L68 81 Z"/>' +
      '</g></svg>';
  }

  const NAV = [
    { href: 'index.html',    label: 'Dashboard', icon: 'dashboard', page: 'dashboard' },
    { href: 'library.html',  label: 'Library',   icon: 'library',   page: 'library'   },
    { href: 'activity.html', label: 'Activity',  icon: 'activity',  page: 'activity'  },
    { group: 'Setup' },
    { href: 'metadata.html', label: 'Metadata',  icon: 'metadata',  page: 'metadata'  },
    { href: 'settings.html', label: 'Settings',  icon: 'settings',  page: 'settings'  },
    { group: 'Apps' },
    { href: 'ravilo-config.html', label: 'Ravilo TV', icon: 'ravilo', page: 'ravilo' },
  ];
  const here = (location.pathname.split('/').pop() || 'index.html');
  const main = document.querySelector('.app-main2');
  const current = main ? main.getAttribute('data-page') : '';

  /* ---- attention queue (drives the Triage dock + sidebar count) ---- */
  const ATTN = [
    { title: 'Big Buck Bunny', sub: '2 untagged audio tracks', href: 'media.html' },
    { title: 'Sintel',         sub: 'wrong default audio (fra → eng)', href: 'media.html' },
    { title: 'Nordvest',       sub: 'S01E02 · missing still + overview', href: 'series.html' },
    { title: 'Babel Fish',     sub: 'S02E05 · multiple default audio', href: 'series.html' },
    { title: 'Caminandes 2',   sub: 'missing poster artwork', href: 'media.html' },
    { title: 'The Daily Show', sub: 'no longer in Jellyfin — kept, flagged for triage', href: 'series.html' },
  ];
  const ATTN_TOTAL = 214;

  /* ---- build shell ---- */
  const shell = document.createElement('div');
  shell.className = 'shell';

  const side = document.createElement('aside');
  side.className = 'app-side';
  side.innerHTML =
    '<div class="logo">' + BRAND(30) + ' Jellystructure</div>' +
    NAV.map(item => {
      if (item.group) return '<div class="group-label">' + item.group + '</div>';
      const active = (item.href === here) || (item.page === current) ? ' active' : '';
      return '<a class="nav' + active + '" href="' + item.href + '">' +
        '<span class="l"><span class="ico">' + I[item.icon] + '</span>' + item.label + '</span></a>';
    }).join('') +
    '<div class="grow"></div>' +
    '<div class="status">' +
      '<div class="row"><span class="dot ok"></span> Jellyfin online</div>' +
      '<div class="row"><span class="dot ok"></span> TMDB key OK</div>' +
      '<div class="row"><span class="dot warn"></span> ' + ATTN_TOTAL + ' need attention</div>' +
    '</div>' +
    '<a class="nav" href="login.html" style="margin-top:8px"><span class="l"><span class="ico">' + I.signout + '</span>Sign out</span></a>' +
    '<div class="theme-seg" id="themeseg" role="group" aria-label="Appearance">' +
      ['light','dark','system'].map(m =>
        '<button type="button" data-mode="' + m + '">' + m.charAt(0).toUpperCase() + m.slice(1) + '</button>'
      ).join('') +
    '</div>';

  shell.appendChild(side);
  if (main) { main.parentNode.removeChild(main); shell.appendChild(main); }
  document.body.insertBefore(shell, document.body.firstChild);

  /* ---- mobile top bar + drawer backdrop (CSS hides these on desktop) ---- */
  const topbar = document.createElement('div');
  topbar.className = 'app-topbar';
  topbar.innerHTML =
    '<button class="burger" id="navburger" aria-label="Open menu" aria-expanded="false">' + I.menu + '</button>' +
    '<span class="tb-logo">' + BRAND(24) + ' Jellystructure</span>';
  document.body.insertBefore(topbar, document.body.firstChild);

  const backdrop = document.createElement('div');
  backdrop.className = 'nav-backdrop';
  document.body.appendChild(backdrop);

  const burger = topbar.querySelector('#navburger');
  function setNav(open) {
    document.body.classList.toggle('nav-open', open);
    burger.setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  burger.addEventListener('click', () => setNav(!document.body.classList.contains('nav-open')));
  backdrop.addEventListener('click', () => setNav(false));
  side.addEventListener('click', (e) => { if (e.target.closest('a.nav')) setNav(false); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setNav(false); });
  window.addEventListener('resize', () => { if (window.innerWidth > 980) setNav(false); });

  /* ---- three-way theme picker ---- */
  const seg = side.querySelector('#themeseg');
  function paintSeg() {
    const mode = storedMode();
    seg.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.mode === mode));
  }
  seg.addEventListener('click', (e) => {
    const b = e.target.closest('button'); if (!b) return;
    localStorage.setItem('js-theme', b.dataset.mode);
    applyTheme(); paintSeg();
  });
  paintSeg();

  /* ---- docks ---- */
  const noDock = main && main.hasAttribute('data-no-dock');

  // Both docks live in one bottom-right flex stack so they reflow cleanly when
  // either is collapsed or hidden (no hardcoded offsets).
  function dockStack() {
    let s = document.getElementById('dock-stack');
    if (!s) { s = document.createElement('div'); s.id = 'dock-stack'; document.body.appendChild(s); }
    return s;
  }

  // Ambient scan dock — only on pages that opt in with data-scan (demo of an active scan).
  if (main && main.hasAttribute('data-scan') && !noDock && current !== 'activity') {
    const dock = document.createElement('div');
    dock.className = 'dock scan-dock';
    dock.innerHTML =
      '<div class="dock-head">' +
        '<span class="dot ok"></span><b>Scanning · Movies</b>' +
        '<span class="spacer"></span>' +
        '<span class="tiny mono">88/142</span>' +
        '<span class="kbd toggle-dock">⌄</span>' +
      '</div>' +
      '<div class="dock-body">' +
        '<div class="bar"><i style="width:62%"></i></div>' +
        '<div class="mono tiny" style="margin-top:9px">Glass Half · ffmpeg 44% · ~2m left · 4 workers</div>' +
        '<div class="row center" style="gap:7px;margin-top:11px">' +
          '<span class="chip" style="font-size:.68rem"><span class="dot ok"></span> 71 done</span>' +
          '<span class="chip" style="font-size:.68rem"><span class="dot bad"></span> 3 attention</span>' +
          '<span class="spacer"></span>' +
          '<a href="activity.html" class="tiny">console ↗</a>' +
        '</div>' +
      '</div>';
    dockStack().appendChild(dock);
    dock.querySelector('.toggle-dock').addEventListener('click', (e) => {
      e.stopPropagation();
      dock.classList.toggle('collapsed');
      e.target.textContent = dock.classList.contains('collapsed') ? '⌃' : '⌄';
    });
  }

  // Floating Triage dock — navigation only. Persists across pages; steps through
  // the attention queue, opening each item's media detail page where fixing happens.
  if (!noDock && current !== 'activity' && ATTN.length) {
    let idx = 0;
    const scanShown = main && main.hasAttribute('data-scan');
    const dock = document.createElement('div');
    dock.className = 'dock triage-dock';
    dock.innerHTML =
      '<div class="dock-head">' +
        '<span class="ico-attn">' + I.attn + '</span><b>Needs attention</b>' +
        '<span class="spacer"></span>' +
        '<span class="tiny mono" id="td-pos"></span>' +
        '<span class="kbd toggle-dock" title="Collapse">⌄</span>' +
        '<span class="kbd dock-close" title="Close" aria-label="Close">✕</span>' +
      '</div>' +
      '<div class="dock-body">' +
        '<div class="td-item"><div class="td-title" id="td-title"></div><div class="tiny muted" id="td-sub"></div></div>' +
        '<div class="row center" style="gap:8px;margin-top:12px">' +
          '<button class="btn sm ghost" id="td-prev">‹ Prev</button>' +
          '<a class="btn sm primary" id="td-open" style="flex:1;justify-content:center">Open &amp; fix →</a>' +
          '<button class="btn sm ghost" id="td-next">Next ›</button>' +
        '</div>' +
      '</div>';
    dockStack().appendChild(dock);
    const $ = id => dock.querySelector(id);
    function paint() {
      const it = ATTN[idx];
      $('#td-pos').textContent = (idx + 1) + ' / ' + ATTN_TOTAL;
      $('#td-title').textContent = it.title;
      $('#td-sub').textContent = it.sub;
      $('#td-open').setAttribute('href', it.href);
    }
    $('#td-prev').addEventListener('click', () => { idx = (idx - 1 + ATTN.length) % ATTN.length; paint(); });
    $('#td-next').addEventListener('click', () => { idx = (idx + 1) % ATTN.length; paint(); });
    dock.querySelector('.toggle-dock').addEventListener('click', (e) => {
      e.stopPropagation();
      dock.classList.toggle('collapsed');
      e.target.textContent = dock.classList.contains('collapsed') ? '⌃' : '⌄';
    });

    // Close (hide) the dock + remember it across pages; reopenable from the Dashboard.
    const CLOSED_KEY = 'js-attn-dock-closed';
    function setClosed(closed) {
      dock.classList.toggle('dock-hidden', closed);
      try { localStorage.setItem(CLOSED_KEY, closed ? '1' : '0'); } catch (e) {}
    }
    dock.querySelector('.dock-close').addEventListener('click', (e) => {
      e.stopPropagation();
      setClosed(true);
    });
    // honor a previously-closed state
    let startClosed = false;
    try { startClosed = localStorage.getItem(CLOSED_KEY) === '1'; } catch (e) {}
    if (startClosed) dock.classList.add('dock-hidden');
    // expose a reopener for the Dashboard (and anywhere else)
    window.openAttentionDock = function () { setClosed(false); dock.classList.remove('collapsed'); dock.querySelector('.toggle-dock').textContent = '⌄'; };

    paint();
  }

  /* ---- command palette (⌘K) + attention-queue keyboard nav (Phase 38) ---- */
  const palette = document.createElement('div');
  palette.id = 'cmd-palette';
  palette.innerHTML =
    '<div class="cmdp-box">' +
      '<input id="cmdp-input" placeholder="Search pages, items, actions…  (Esc to close)" autocomplete="off">' +
      '<div id="cmdp-list"></div>' +
    '</div>';
  document.body.appendChild(palette);

  const cmds = [
    ...NAV.filter(n => !n.group).map(n => ({ label: n.label, kind: 'Page', href: n.href, icon: I[n.icon] })),
    { label: 'Start library scan', kind: 'Action', href: 'activity.html', icon: I.activity },
    { label: 'Re-pull artwork (all)', kind: 'Action', href: 'index.html', icon: I.dashboard },
    ...ATTN.map(a => ({ label: a.title, kind: 'Needs attention', sub: a.sub, href: a.href, icon: I.attn })),
  ];
  const cmdInput = palette.querySelector('#cmdp-input');
  const cmdList = palette.querySelector('#cmdp-list');
  let cmdSel = 0, cmdFiltered = cmds;
  function renderCmds() {
    const q = cmdInput.value.toLowerCase();
    cmdFiltered = cmds.filter(c => c.label.toLowerCase().includes(q) || c.kind.toLowerCase().includes(q));
    if (cmdSel >= cmdFiltered.length) cmdSel = 0;
    cmdList.innerHTML = cmdFiltered.map((c, i) =>
      '<div class="cmdp-item' + (i === cmdSel ? ' sel' : '') + '" data-href="' + c.href + '">' +
        '<span class="cmdp-ico">' + (c.icon || '') + '</span>' +
        '<span class="cmdp-label">' + c.label + (c.sub ? ' <span class="cmdp-sub">· ' + c.sub + '</span>' : '') + '</span>' +
        '<span class="cmdp-kind">' + c.kind + '</span>' +
      '</div>').join('') || '<div class="cmdp-empty">No matches</div>';
  }
  function openPalette() { palette.classList.add('open'); cmdInput.value = ''; cmdSel = 0; renderCmds(); setTimeout(() => cmdInput.focus(), 30); }
  function closePalette() { palette.classList.remove('open'); }
  function goCmd() { const c = cmdFiltered[cmdSel]; if (c) location.href = c.href; }
  cmdInput.addEventListener('input', renderCmds);
  cmdList.addEventListener('click', e => { const it = e.target.closest('[data-href]'); if (it) location.href = it.dataset.href; });
  palette.addEventListener('click', e => { if (e.target === palette) closePalette(); });
  cmdInput.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { e.preventDefault(); cmdSel = Math.min(cmdSel + 1, cmdFiltered.length - 1); renderCmds(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); cmdSel = Math.max(cmdSel - 1, 0); renderCmds(); }
    else if (e.key === 'Enter') { e.preventDefault(); goCmd(); }
    else if (e.key === 'Escape') closePalette();
  });

  // discoverability: a search pill under the sidebar logo
  const searchPill = document.createElement('button');
  searchPill.className = 'cmdk-pill';
  searchPill.innerHTML = '<span>⌕ Search…</span><span class="kbd">⌘K</span>';
  const logoEl = side.querySelector('.logo');
  if (logoEl) logoEl.insertAdjacentElement('afterend', searchPill);
  searchPill.addEventListener('click', openPalette);

  function typingInField() {
    const a = document.activeElement;
    return a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable);
  }
  document.addEventListener('keydown', e => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); openPalette(); return; }
    if (palette.classList.contains('open') || typingInField()) return;
    if (e.key === 'n') document.getElementById('td-next') && document.getElementById('td-next').click();
    else if (e.key === 'p') document.getElementById('td-prev') && document.getElementById('td-prev').click();
    else if (e.key === 'o') document.getElementById('td-open') && document.getElementById('td-open').click();
  });
})();
