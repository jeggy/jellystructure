/* Injects the shared top nav and wires variant tabs. */
(function () {
  const PAGES = [
    ['index.html',            'Overview'],
    ['01-triage.html',        'Triage'],
    ['02-media-detail.html',  'Media Detail'],
    ['03-track-reorder.html', 'Track Order'],
    ['04-cascade-rules.html', 'Cascade Rules'],
    ['05-settings.html',      'Settings'],
    ['06-scan-progress.html', 'Scan Activity'],
    ['Requirements & Phases.html', 'Requirements ↗'],
    ['Implementation Plan.html', 'Build Plan ↗'],
  ];
  const here = (location.pathname.split('/').pop() || 'index.html');
  const dec = s => decodeURIComponent(s);

  const nav = document.createElement('div');
  nav.className = 'wf-topnav';
  nav.innerHTML =
    '<div class="brand"><span class="glyph"></span> Jellystructure</div>' +
    '<nav>' + PAGES.map(([href, label]) => {
      const active = dec(href) === dec(here) ? ' class="active"' : '';
      return '<a href="' + href + '"' + active + '>' + label + '</a>';
    }).join('') + '</nav>' +
    '<span class="spacer"></span>' +
    '<span class="tag">low-fi wireframes · v0.1</span>';
  document.body.insertBefore(nav, document.body.firstChild);

  // variant tabs: any .variant-tabs with data-target buttons toggling sibling .variant[data-variant]
  document.querySelectorAll('.variant-tabs').forEach(tabs => {
    const scope = tabs.closest('[data-variant-scope]') || document;
    const btns = [...tabs.querySelectorAll('button')];
    btns.forEach(btn => btn.addEventListener('click', () => {
      const id = btn.getAttribute('data-target');
      btns.forEach(b => b.classList.toggle('active', b === btn));
      scope.querySelectorAll('.variant').forEach(v =>
        v.classList.toggle('active', v.getAttribute('data-variant') === id));
    }));
  });
})();
