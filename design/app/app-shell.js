/* Injects the persistent left sidebar + ambient scan dock into every app page.
   Each page provides <main class="app-main2" data-page="..."> as its only body content.
   Also wires the light/dark theme toggle (persisted). */
(function () {

  /* ---- apply saved appearance ASAP (before building DOM) ---- */
  const root = document.documentElement;
  root.setAttribute('data-booting', '');
  const theme = localStorage.getItem('js-theme') || 'dark';
  root.setAttribute('data-dir', 'aurora');
  root.setAttribute('data-theme', theme);
  requestAnimationFrame(() => requestAnimationFrame(() => root.removeAttribute('data-booting')));

  /* ---- icons (simple geometric line set) ---- */
  const I = {
    dashboard: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg>',
    library:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m16 6 4 14"/><path d="M12 6v14"/><path d="M8 8v12"/><path d="M4 4v16"/></svg>',
    triage:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>',
    activity:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
    language:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/><path d="M2 12h20"/></svg>',
    settings:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><line x1="3" y1="8" x2="10" y2="8"/><circle cx="12" cy="8" r="2"/><line x1="14" y1="8" x2="21" y2="8"/><line x1="3" y1="16" x2="7" y2="16"/><circle cx="9" cy="16" r="2"/><line x1="11" y1="16" x2="21" y2="16"/></svg>',
    signout:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>',
    sun:       '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="4.2"/><line x1="12" y1="2.5" x2="12" y2="5"/><line x1="12" y1="19" x2="12" y2="21.5"/><line x1="2.5" y1="12" x2="5" y2="12"/><line x1="19" y1="12" x2="21.5" y2="12"/><line x1="5.4" y1="5.4" x2="7.1" y2="7.1"/><line x1="16.9" y1="16.9" x2="18.6" y2="18.6"/><line x1="5.4" y1="18.6" x2="7.1" y2="16.9"/><line x1="16.9" y1="7.1" x2="18.6" y2="5.4"/></svg>',
    moon:      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z"/></svg>',
    menu:      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/></svg>'
  };

  const NAV = [
    { href: 'index.html',    label: 'Dashboard',     icon: 'dashboard', page: 'dashboard' },
    { href: 'library.html',  label: 'Library',       icon: 'library',   page: 'library'   },
    { href: 'triage.html',   label: 'Triage',        icon: 'triage',    page: 'triage', count: 214 },
    { href: 'activity.html', label: 'Activity',      icon: 'activity',  page: 'activity'  },
    { group: 'Setup' },
    { href: 'language.html', label: 'Language',      icon: 'language',  page: 'language'  },
    { href: 'settings.html', label: 'Settings',      icon: 'settings',  page: 'settings'  },
  ];
  const here = (location.pathname.split('/').pop() || 'index.html');
  const main = document.querySelector('.app-main2');
  const current = main ? main.getAttribute('data-page') : '';

  /* ---- build shell ---- */
  const shell = document.createElement('div');
  shell.className = 'shell';

  const side = document.createElement('aside');
  side.className = 'app-side';
  side.innerHTML =
    '<div class="logo"><span class="glyph"></span> Jellystructure</div>' +
    NAV.map(item => {
      if (item.group) return '<div class="group-label">' + item.group + '</div>';
      const active = (item.href === here) || (item.page === current) ? ' active' : '';
      const cnt = item.count ? '<span class="count">' + item.count + '</span>' : '';
      return '<a class="nav' + active + '" href="' + item.href + '">' +
        '<span class="l"><span class="ico">' + I[item.icon] + '</span>' + item.label + '</span>' + cnt + '</a>';
    }).join('') +
    '<div class="grow"></div>' +
    '<div class="status">' +
      '<div class="row"><span class="dot ok"></span> Jellyfin online</div>' +
      '<div class="row"><span class="dot ok"></span> TMDB key OK</div>' +
      '<div class="row"><span class="dot warn"></span> 214 to triage</div>' +
    '</div>' +
    '<a class="nav" href="login.html" style="margin-top:8px"><span class="l"><span class="ico">' + I.signout + '</span>Sign out</span></a>' +
    '<div class="switcher">' +
      '<div class="sw-row">' +
        '<button class="theme-btn wide" id="themebtn" title="Toggle light / dark"></button>' +
      '</div>' +
    '</div>';

  shell.appendChild(side);
  if (main) { main.parentNode.removeChild(main); shell.appendChild(main); }
  document.body.insertBefore(shell, document.body.firstChild);

  /* ---- mobile top bar + drawer backdrop (CSS hides these on desktop) ---- */
  const topbar = document.createElement('div');
  topbar.className = 'app-topbar';
  topbar.innerHTML =
    '<button class="burger" id="navburger" aria-label="Open menu" aria-expanded="false">' + I.menu + '</button>' +
    '<span class="tb-logo"><span class="glyph"></span> Jellystructure</span>';
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
  // close the drawer when a nav destination is chosen, or on Escape / desktop resize
  side.addEventListener('click', (e) => { if (e.target.closest('a.nav')) setNav(false); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setNav(false); });
  window.addEventListener('resize', () => { if (window.innerWidth > 980) setNav(false); });

  /* ---- theme toggle wiring ---- */
  function paintSwitcher() {
    const t = root.getAttribute('data-theme');
    const btn = side.querySelector('#themebtn');
    btn.innerHTML = (t === 'dark' ? I.sun : I.moon) + '<span>' + (t === 'dark' ? 'Light mode' : 'Dark mode') + '</span>';
  }
  side.querySelector('#themebtn').addEventListener('click', () => {
    const next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next); localStorage.setItem('js-theme', next); paintSwitcher();
  });
  paintSwitcher();

  /* ---- ambient scan dock (hidden on the activity page + any data-no-dock page) ---- */
  const noDock = main && main.hasAttribute('data-no-dock');
  if (current !== 'activity' && !noDock) {
    const dock = document.createElement('div');
    dock.className = 'dock';
    dock.innerHTML =
      '<div class="dock-head">' +
        '<span class="dot ok"></span><b>Processing · Movies</b>' +
        '<span class="spacer"></span>' +
        '<span class="tiny mono">88/142</span>' +
        '<span class="kbd toggle-dock">⌄</span>' +
      '</div>' +
      '<div class="dock-body">' +
        '<div class="bar"><i style="width:62%"></i></div>' +
        '<div class="mono tiny" style="margin-top:9px">Glass Half · ffmpeg 44% · ~2m left</div>' +
        '<div class="row center" style="gap:7px;margin-top:11px">' +
          '<span class="chip" style="font-size:.68rem"><span class="dot ok"></span> 71</span>' +
          '<span class="chip" style="font-size:.68rem"><span class="dot bad"></span> 3 → triage</span>' +
          '<span class="spacer"></span>' +
          '<a href="activity.html" class="tiny">open console ↗</a>' +
        '</div>' +
      '</div>';
    document.body.appendChild(dock);
    dock.querySelector('.toggle-dock').addEventListener('click', (e) => {
      e.stopPropagation();
      dock.classList.toggle('collapsed');
      e.target.textContent = dock.classList.contains('collapsed') ? '⌃' : '⌄';
    });
  }
})();
