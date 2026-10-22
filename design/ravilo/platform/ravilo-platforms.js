/* The one Ravilo file — what each platform loads (2026-10-04).
   Ravilo.html is BOTH the shell and every surface:
     Ravilo.html                       → the shell: one design-only fence (Platform · Theme · Mode · per-platform controls) over a stage
     Ravilo.html?surface=tv            → the TV app, 1920×1080 scaled
     Ravilo.html?surface=phone         → the phone (Pixel 9 / iPhone 16 bezel); &frame=window = the desktop's compact window
     Ravilo.html?surface=desktop       → the desktop window (macOS or Linux, Large → Compact)
   The shell shows the picked surface in a frame of the same file, so the three apps never share globals or CSS.
   A surface opened on its own (no shell around it) shows its own preview pickers; inside the shell they are hidden
   and mirrored into the fence (html.rv-embed). Add a platform's files HERE, nowhere else. */
(function () {
  const U = new URLSearchParams(location.search);
  const surface = U.get('surface');
  let embedded = false; try { embedded = !!surface && window.parent !== window && !!window.parent.RAVILO_SHELL; } catch (e) {}
  const css = h => `<link rel="stylesheet" href="${h}">`;
  const js = s => `<script src="${s}"><\/script>`;
  const FONTS = '<link href="https://fonts.googleapis.com/css2?family=Sora:wght@300;400;500;600;700&family=Space+Grotesk:wght@500;600;700&family=JetBrains+Mono:wght@500;600;700&display=swap" rel="stylesheet">';
  const MUSIC = ['../app/music-data.js', '../app/versions.js', '../app/editions.js', 'mobile/ravilo-versions.js', 'mobile/ravilo-editions.js'];

  const P = {
    tv: {
      title: 'Ravilo — TV',
      /* no font link: ravilo.css / ravilo-player.css @import their own, exactly as Ravilo TV.html */
      head: [css('ravilo.css'), css('ravilo-player.css'), css('ravilo-livetv.css'), css('../flags.css')],
      body: [js('platform/tv-markup.js'), ...['ravilo-i18n.js', 'ravilo-data.js', 'ravilo-livetv-data.js', 'ravilo-player.js', 'ravilo-livetv.js', 'ravilo-browse.js', 'ravilo-focus.js', 'ravilo-app.js', 'ravilo-states.js', 'platform/tv-boot.js'].map(js)]
    },
    phone: {
      title: 'Ravilo — Phone',
      /* the phone's own font link, exactly as Ravilo Mobile.html had it (no JetBrains Mono: its few mono spots fall back, as before) */
      head: [js('mobile/ravilo-phone-head.js'), '<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&family=Sora:wght@300;400;500;600;700&display=swap" rel="stylesheet">', css('../flags.css'), css('mobile/ravilo-mobile-player.css'), css('mobile/ravilo-phone.css'), css('mobile/ravilo-music.css'), css('mobile/ravilo-sessions.css'), css('mobile/ravilo-support.css'), css('mobile/ravilo-motion.css')],
      body: [js('mobile/ravilo-phone-markup.js'), ...['ravilo-i18n.js', 'ravilo-data.js', 'mobile/ravilo-phone.js', ...MUSIC, 'mobile/ravilo-music.js', 'mobile/ravilo-music-player.js', '../app/audiobook-data.js', 'mobile/ravilo-books.js', 'sessions-live.js', 'mobile/ravilo-speakers.js', 'mobile/ravilo-sessions.js', 'mobile/ravilo-support.js', 'mobile/ravilo-web-states.js', 'mobile/ravilo-phone-state.js'].map(js)]
    },
    desktop: {
      title: 'Ravilo — Desktop',
      head: [FONTS, css('desktop-directions.css'), css('desktop/ravilo-desktop.css'), '<style>a{color:#9d93ff}a:hover{color:#c7c0ff}</style>'],
      /* the compact window embeds the phone from THIS file, not from the frozen Ravilo Mobile.html */
      body: ['<script>window.RAVILO_PHONE_SRC="Ravilo.html?surface=phone&";<\/script>', ...['desktop-kit.js', 'ravilo-data.js', ...MUSIC, '../app/audiobook-data.js', 'sessions-live.js', 'desktop/ravilo-desktop.js'].map(js)]
    },
    shell: {
      title: 'Ravilo',
      head: [FONTS, css('platform/ravilo-shell.css')],
      body: [js('platform/ravilo-shell.js')]
    }
  };
  const p = P[surface] || P.shell;
  if (!P[surface]) window.RAVILO_SHELL = true;
  const d = document.documentElement;
  if (surface) d.classList.add('rv-surface', 'rv-' + surface);
  if (embedded) d.classList.add('rv-embed');
  if (surface === 'tv') d.setAttribute('data-skin', U.get('skin') || 'aurora');
  document.title = p.title;
  window.RaviloPlatforms = {
    surface: P[surface] ? surface : null, embedded,
    head() { document.write(p.head.join('') + (surface ? css('platform/surface.css') : '')); },
    body() { document.write(p.body.join('')); }
  };
})();
