/* Injects the persistent left sidebar + ambient scan dock into every app page.
   Each page provides <main class="app-main2" data-page="..."> as its only body content. */
(function () {
  const NAV = [
    { href: 'index.html',       label: 'Dashboard',     icon: '▦', page: 'dashboard' },
    { href: 'library.html',     label: 'Library',       icon: '▤', page: 'library'   },
    { href: 'triage.html',      label: 'Triage',        icon: '!', page: 'triage', count: 214 },
    { href: 'activity.html',    label: 'Activity',      icon: '◷', page: 'activity'  },
    { group: 'Setup' },
    { href: 'cascade.html',     label: 'Cascade rules', icon: '≣', page: 'cascade'   },
    { href: 'settings.html',    label: 'Settings',      icon: '⚙', page: 'settings'  },
  ];
  const here = (location.pathname.split('/').pop() || 'index.html');
  const main = document.querySelector('.app-main2');
  const current = main ? main.getAttribute('data-page') : '';

  // build shell
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
      return '<a class="' + active.trim() + '" href="' + item.href + '">' +
        '<span class="l"><span class="ico">' + item.icon + '</span>' + item.label + '</span>' + cnt + '</a>';
    }).join('') +
    '<div class="grow"></div>' +
    '<div class="status">' +
      '<div class="row"><span class="dot ok"></span> Jellyfin online</div>' +
      '<div class="row"><span class="dot ok"></span> TMDB key OK</div>' +
      '<div class="row"><span class="dot warn"></span> 214 to triage</div>' +
    '</div>';

  // move main into shell
  shell.appendChild(side);
  if (main) {
    main.parentNode.removeChild(main);
    shell.appendChild(main);
  }
  document.body.insertBefore(shell, document.body.firstChild);

  // ambient dock (hidden on the dedicated activity page)
  if (current !== 'activity') {
    const dock = document.createElement('div');
    dock.className = 'dock';
    dock.innerHTML =
      '<div class="dock-head">' +
        '<span class="dot ok"></span><b>Cascade · Movies</b>' +
        '<span class="spacer" style="flex:1"></span>' +
        '<span class="tiny" style="opacity:.7">88/142</span>' +
        '<span class="kbd toggle-dock">▾</span>' +
      '</div>' +
      '<div class="dock-body">' +
        '<div class="bar"><i style="width:62%"></i></div>' +
        '<div class="mono tiny" style="margin-top:7px">Glass Half · ffmpeg 44% · ~2m left</div>' +
        '<div class="row" style="display:flex;gap:7px;margin-top:9px;align-items:center">' +
          '<span class="chip">71 ✓</span><span class="chip">3 ✗ → triage</span>' +
          '<span style="flex:1"></span>' +
          '<a href="activity.html" class="tiny">open console ↗</a>' +
        '</div>' +
      '</div>';
    document.body.appendChild(dock);
    dock.querySelector('.toggle-dock').addEventListener('click', (e) => {
      e.stopPropagation();
      dock.classList.toggle('collapsed');
      e.target.textContent = dock.classList.contains('collapsed') ? '▴' : '▾';
    });
  }
})();
