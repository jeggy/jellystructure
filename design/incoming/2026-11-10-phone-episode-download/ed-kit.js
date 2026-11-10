/* ed-kit.js — the builders. Every value comes out of ed-data.js (window.ED) or out of the
   shipped corpus (window.RAVILO); nothing here invents a number, a title or a word.

   No <defs>, no gradient element and no SVG `id` exists anywhere in this round, so the
   hidden-subtree paint-server trap PROCESS.md warns about cannot apply: every fill is either
   `currentColor` or a CSS background. */
(function () {
  'use strict';

  var D = window.ED;
  var LANG = 'en';

  function setLang(l) { LANG = l; }
  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function t(key, vars) {
    var s = D.STR[LANG][key];
    if (s === undefined) s = D.STR.en[key];
    if (s === undefined) return key;
    if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
    return s;
  }

  /* Chromium has no Faroese locale and toLocaleDateString falls back to English in silence, so
     the month names and the date ORDER are tabled per language in ed-data.js and assembled here.
     A round that leaned on the platform would be reporting a translation it never drew. */
  var MONTH_KEYS = ['month.jan', 'month.feb', 'month.mar', 'month.apr', 'month.may', 'month.jun',
    'month.jul', 'month.aug', 'month.sep', 'month.oct', 'month.nov', 'month.dec'];
  function airLabel(iso) {
    var p = String(iso || '').split('-');
    if (p.length !== 3) return '';
    return t('date.month_day_year', {
      month: t(MONTH_KEYS[parseInt(p[1], 10) - 1]),
      day: String(parseInt(p[2], 10)),
      year: p[0]
    });
  }

  /* ---- the glyphs ----------------------------------------------------------------------
     All drawn, all `currentColor`, so each takes its control's own tint. The arrow-into-a-line,
     the clock and the warning triangle are the shipped DownloadGlyph's three shapes
     (DownloadUi.kt), at the same proportions. */
  var ICON = {
    dl: '<path d="M12 3.2v10.4M7.8 10.2 12 14.4l4.2-4.2M4.8 20.2h14.4"/>',
    clock: '<circle cx="12" cy="12" r="8.4"/><path d="M12 6.7V12l3.8 2.3"/>',
    warn: '<path d="M12 3.2 21 20.2H3Z"/><path d="M12 9.6v5.3"/><circle cx="12" cy="18" r=".9" fill="currentColor" stroke="none"/>',
    check: '<path d="M4.6 12.4 9.6 17.4 19.4 7"/>',
    ring: '<circle cx="12" cy="12" r="8.6"/>',
    back: '<path d="M14.6 5.4 7.6 12l7 6.6"/>',
    play: '<path d="M7.5 4.6 19 12 7.5 19.4Z" fill="currentColor" stroke="none"/>',
    trash: '<path d="M4.6 7h14.8M9.4 7V4.4h5.2V7M6.6 7l.9 13h9l.9-13M10.3 10.6v6M13.7 10.6v6"/>',
    shuffle: '<path d="M15.6 4.6h4v4M19.6 4.6 4.6 19.4M4.6 4.6l4.6 4.6M15.6 19.4h4v-4M19.6 19.4l-4.6-4.6"/>',
    pause: '<path d="M9.4 5.4v13.2M14.6 5.4v13.2"/>',
    wifi: '<path d="M3.4 9.2a13 13 0 0 1 17.2 0M6.6 12.8a8.4 8.4 0 0 1 10.8 0"/><circle cx="12" cy="17.6" r="1.4" fill="currentColor" stroke="none"/>',
    batt: '<rect x="3.2" y="7.4" width="16" height="9.2" rx="2.4"/><path d="M21.2 10.6v2.8"/><rect x="5.4" y="9.6" width="9.6" height="4.8" rx="1.2" fill="currentColor" stroke="none"/>',
    cell: '<path d="M4 18.6V15M9.3 18.6v-6.4M14.7 18.6V8.4M20 18.6V4.6"/>'
  };
  function ic(name, cls) {
    return '<svg class="' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';
  }
  /* The downloaded mark's glyph: the arrow-into-a-line at 12 x 12, stroke 2.4, in #0a0c13 — the
     shipped DownloadedMark, whose whole point is that a chip with an arrow is never a green
     circle, because that shape already means *watched*. */
  function markGlyph() {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="#0a0c13" stroke-width="2.9"'
      + ' stroke-linecap="round" stroke-linejoin="round">'
      + '<path d="M12 2.5v11.5M6.8 10.2 12 15.4l5.2-5.2M5.3 21.3h13.4"/></svg>';
  }

  /* ---- the status bar ------------------------------------------------------------------ */
  function sb() {
    return '<div class="ed-sb"><span>' + esc(D.CLOCK) + '</span><span class="ed-sbi">'
      + ic('cell') + ic('wifi') + ic('batt') + '</span></div>';
  }

  /* ---- the episodes, merged ------------------------------------------------------------
     The titles, durations, synopses and air dates are the corpus's hand-written Nordvest
     Season 1; the download state and the watched flag are the brief's. */
  function episodes(list) {
    var corpus = window.RAVILO.episodesFor({ title: D.SERIES.title, kind: 'series' }, 0);
    return (list || D.EPISODES).map(function (s) {
      var c = corpus[s.n - 1];
      var o = { n: s.n, title: c.title, dur: c.dur, desc: c.desc, air: c.air, grad: c.grad };
      Object.keys(s).forEach(function (k) { o[k] = s[k]; });
      return o;
    });
  }
  function code(n) { return 'S' + (D.SERIES.seasonIndex < 10 ? '0' : '') + D.SERIES.seasonIndex
    + 'E' + (n < 10 ? '0' : '') + n; }

  /* ---- the download control (both directions use the same one) --------------------------
     Six phases, five control states: `none` → the verb's glyph; `queued` and all three waits →
     the clock; `running` and `paused` → the percentage over a fill; `downloaded` → the green
     check; `failed` → the amber triangle. That mapping is the shipped DownloadPhase.controlState(),
     not this round's invention — the one thing added is that a PAUSED fill is drained of its
     accent, so a stopped download does not look identical to a moving one on a card that has no
     status line under it. */
  function ctl(ep) {
    var st = ep.state, cls, inner, a11y;
    /* The *Downloaded* control is the ARROW-INTO-A-LINE in green, never a check. The shipped
       DownloadedMark exists precisely because "a chip with an arrow is never a green circle:
       that shape and colour already mean watched". A check was drawn here first, and the rail
       picture showed the cost at once — a green tick in the control and a green tick in the
       Watched toggle, 240 dp apart on one line, meaning two different things. The film page can
       use a check because the word *Downloaded* is beside it; this control has no word. */
    if (st === 'downloaded') { cls = ep.green ? 'donegreen' : 'done'; inner = ic('dl'); a11y = t('download.downloaded'); }
    else if (st === 'failed') { cls = 'fail'; inner = ic('warn'); a11y = t('download.retry'); }
    else if (st === 'running' || st === 'paused') {
      cls = st;
      inner = '<i class="fill" style="width:' + (ep.pct || 0) + '%"></i>'
        + '<span class="pct">' + (ep.pct || 0) + '&thinsp;%</span>';
      a11y = st === 'paused' ? t('download.paused') : (ep.pct || 0) + ' %';
    } else if (st === 'queued' || st === 'waitingWifi' || st === 'waitingNetwork' || st === 'waitingServer') {
      cls = 'queued'; inner = ic('clock');
      a11y = st === 'queued' ? t('download.queued')
        : st === 'waitingWifi' ? t('download.waiting_wifi')
        : st === 'waitingNetwork' ? t('download.waiting_network') : t('download.waiting_server');
    } else { cls = 'idle'; inner = ic('dl'); a11y = t('download.episode_a11y', { code: code(ep.n) }); }
    return '<button class="ed-ctl ' + cls + '" aria-label="' + esc(a11y) + '">' + inner + '</button>';
  }

  /* ---- the status line ------------------------------------------------------------------
     The word that says why nothing is moving, in the AIR DATE's place — never a new line, never
     on the picture. Only while the download is not at rest; a resting card (none, downloaded)
     keeps its air date and is exactly today's card. Without this the three waits are one clock
     glyph and a viewer has no way at all to learn why (see a-rail-icononly, drawn without it). */
  function statusLine(ep) {
    var k = null, bad = false;
    switch (ep.state) {
      case 'queued': k = 'download.queued'; break;
      case 'waitingWifi': k = 'download.waiting_wifi'; break;
      case 'waitingNetwork': k = 'download.waiting_network'; break;
      case 'waitingServer': k = 'download.waiting_server'; break;
      case 'paused': k = 'download.paused'; break;
      case 'failed': k = 'download.failed'; bad = true; break;
      default: return null;
    }
    return '<div class="s' + (bad ? ' bad' : ' wait') + '">' + esc(t(k)) + '</div>';
  }

  /* ---- the Watched toggle, as shipped -------------------------------------------------- */
  function tog(watched) {
    return '<button class="ed-tog' + (watched ? ' on' : '') + '">'
      + (watched ? '<span style="color:#2dd49a;display:flex">' + ic('check') + '</span>' : ic('ring'))
      + '<span>' + esc(watched ? t('action.watched') : t('action.mark_watched')) + '</span></button>';
  }

  /* ---- one episode card ----------------------------------------------------------------
     `opts.resume` puts the UP NEXT ribbon on the still, which is what lifts the downloaded mark
     24 dp clear of the corner it would otherwise share. `opts.watchPct` draws the shipped 4 dp
     watch-progress bar, so "the mark must not fight the progress bar" has a picture behind it.
     `dir` picks where the control goes: 'a' leaves the body alone, 'b' puts the control in it. */
  function card(ep, dir, opts) {
    opts = opts || {};
    var still = '<div class="ed-still" style="background:' + ep.grad + '">'
      + '<span class="num">' + ep.n + '</span>'
      + '<span class="dur">' + esc(ep.dur) + '</span>'
      + (opts.resume ? '<span class="upnext">' + esc(t('player.up_next')) + '</span>' : '')
      + (ep.state === 'downloaded'
          ? '<span class="ed-mark' + (opts.resume ? ' lift' : '') + '">' + markGlyph() + '</span>' : '')
      + (ep.watched ? '<span class="seen-tick" style="color:#06281c">' + ic('check') + '</span>' : '')
      + (opts.watchPct && !ep.watched
          ? '<span class="ed-pbar"><i style="width:' + opts.watchPct + '%"></i></span>' : '')
      + '</div>';
    var st = opts.icononly ? null : statusLine(ep);
    var text = '<div class="t">' + esc(code(ep.n) + ' · ' + ep.title) + '</div>'
      + (st || '<div class="a">' + esc(airLabel(ep.air)) + '</div>')
      + '<div class="o">' + esc(ep.desc) + '</div>';
    var body = dir === 'b'
      ? '<div class="ed-cbody"><div class="ed-ctext">' + text + '</div>'
        + '<div class="ed-cdiv"></div>' + ctl(ep) + '</div>'
      : '<div class="ed-cbody">' + text + '</div>';
    return '<div class="ed-card' + (dir === 'b' ? ' dirb' : '') + (ep.watched ? ' seen' : '')
      + (opts.focusish ? ' focusish' : '') + '">' + still + body + '</div>';
  }

  /* ---- one rail slot: the card, 6 dp, then the under-card line ------------------------- */
  function slot(ep, dir, opts) {
    opts = opts || {};
    /* FR-2 — the operator's switch is off for this viewer, or this phone's config has no
       downloads: NONE of this is drawn. Not a disabled control, not a greyed chip; the page is
       exactly today's, and the under-card line is the centred Watched toggle it is now. */
    if (opts.notAllowed) {
      return '<div class="ed-slot">' + card(ep, 'a', opts)
        + '<div class="ed-line bline">' + tog(ep.watched) + '</div></div>';
    }
    var line = dir === 'b'
      ? '<div class="ed-line bline">' + tog(ep.watched) + '</div>'
      : '<div class="ed-line">' + tog(ep.watched) + '<span class="sp"></span>' + ctl(ep) + '</div>';
    return '<div class="ed-slot">' + card(ep, dir, opts) + line + '</div>';
  }

  /* ---- the multi-episode card ----------------------------------------------------------
     Two episodes in one physical file are ONE rail slot (episodeGroups + MultiEpisodeCard) and
     therefore one download: one size, one progress, ONE control. The still is the shipped
     triptych's two-panel case; the body lists the contained episodes as the shipped card does. */
  function multiSlot(dir) {
    var eps = episodes();
    var a = eps[D.MULTI.ns[0] - 1], b = eps[D.MULTI.ns[1] - 1];
    var group = { n: a.n, state: D.MULTI.state, pct: D.MULTI.pct, mb: D.MULTI.mb, watched: D.MULTI.watched };
    var dur = (parseInt(a.dur, 10) + parseInt(b.dur, 10)) + 'm';
    var still = '<div class="ed-still">'
      + '<i style="position:absolute;inset:0;background:' + a.grad + ';clip-path:polygon(0 0,54% 0,46% 100%,0 100%)"></i>'
      + '<i style="position:absolute;inset:0;background:' + b.grad + ';clip-path:polygon(54% 0,100% 0,100% 100%,46% 100%)"></i>'
      + '<span style="position:absolute;top:-8%;height:116%;width:2.5px;left:50%;'
      + 'transform:rotate(8.1deg);background:rgba(255,255,255,.9);z-index:4"></span>'
      + '<span class="num" style="z-index:5">' + a.n + '</span>'
      + '<span class="dur" style="z-index:5">' + esc(dur) + '</span></div>';
    /* The shipped MultiEpisodeCard's body: the span of codes, then the contained episodes. No
       size and no percentage is typed here — the ONE control carries the file's progress, which
       is the whole point of the frame. */
    var text = '<div class="t">' + esc(code(a.n) + '–' + code(b.n)) + '</div>'
      + '<div class="a">' + esc(a.title + ' · ' + b.title) + '</div>'
      + '<div class="o">' + esc(a.desc) + '</div>';
    var body = dir === 'b'
      ? '<div class="ed-cbody"><div class="ed-ctext">' + text + '</div><div class="ed-cdiv"></div>' + ctl(group) + '</div>'
      : '<div class="ed-cbody">' + text + '</div>';
    var line = dir === 'b'
      ? '<div class="ed-line bline">' + tog(false) + '</div>'
      : '<div class="ed-line">' + tog(false) + '<span class="sp"></span>' + ctl(group) + '</div>';
    return '<div class="ed-slot"><div class="ed-card' + (dir === 'b' ? ' dirb' : '') + '">'
      + still + body + '</div>' + line + '</div>';
  }

  /* ---- the rail ------------------------------------------------------------------------
     `resumeOn` is the episode that owns the UP NEXT ribbon: the first unwatched one, which with
     the brief's stand-in is E03 — the downloaded episode, so the mark and the ribbon share a
     corner and the mark's 24 dp lift is in the main picture rather than a footnote. */
  function rail(eps, dir, opts) {
    opts = opts || {};
    var resumeOn = opts.resumeOn !== undefined ? opts.resumeOn : firstUnwatched(eps);
    return '<div class="ed-rail">' + eps.map(function (e) {
      return slot(e, dir, {
        resume: e.n === resumeOn,
        icononly: opts.icononly,
        notAllowed: opts.notAllowed,
        watchPct: opts.watchPct && e.n === opts.watchPct.n ? opts.watchPct.pct : 0
      });
    }).join('') + '</div>';
  }
  function firstUnwatched(eps) {
    for (var i = 0; i < eps.length; i++) if (!eps[i].watched) return eps[i].n;
    return 0;
  }

  /* ---- the Episodes header, with the *Download season* chip --------------------------- */
  function epHead(eps, opts) {
    opts = opts || {};
    var seen = eps.filter(function (e) { return e.watched; }).length;
    var allDown = eps.every(function (e) { return e.state === 'downloaded'; });
    var chip = '';
    if (!opts.notAllowed) {
      chip = allDown && opts.spentChip
        ? '<button class="ed-chip spent">' + ic('check') + esc(t('download.season_all')) + '</button>'
        : '<button class="ed-chip">' + ic('dl') + esc(t('download.season')) + '</button>';
    }
    var mode = opts.oneRow ? ' onerow' : opts.lhs2 ? ' lhs2' : '';
    return '<div class="ed-ephead' + mode + '">'
      + '<span class="lhs"><span class="h">' + esc(t('detail.episodes')) + '</span>'
      + '<span class="wc">' + seen + ' / ' + eps.length + ' ' + esc(t('action.watched').toLowerCase()) + '</span></span>'
      + '<span class="chips"><span class="sp"></span>' + chip
      + '<button class="ed-chip">' + ic('shuffle') + esc(t('detail.shuffle')) + '</button></span></div>';
  }

  /* ---- the whole series page ----------------------------------------------------------- */
  function seriesPage(dir, opts) {
    opts = opts || {};
    var eps = episodes(opts.list);
    return '<div class="ed-detail"><div class="ed-dscroll">'
      + '<div class="ed-dhero" style="background:' + window.RAVILO.grad(D.SERIES.title) + '">'
      +   '<div class="ed-hscrim"></div></div>'
      + '<div class="ed-dbody">'
      +   '<div class="ed-dtitle">' + esc(D.SERIES.title) + '</div>'
      +   '<div class="ed-dmeta"><span>' + esc(t('detail.season') + ' ' + D.SERIES.seasonIndex) + '</span>'
      +     '<span class="pg">' + esc(D.SERIES.cert) + '</span>'
      +     '<span>' + esc(D.SERIES.genre) + '</span><span>' + D.SERIES.year + '</span></div>'
      +   '<div class="ed-dsyn">' + esc(window.RAVILO.synFor
            ? window.RAVILO.synFor({ title: D.SERIES.title, kind: 'series' }) : '') + '</div>'
      +   epHead(eps, opts)
      + '</div>'
      + rail(eps, dir, opts)
      + '</div>'
      + '<button class="ed-back">' + ic('back') + '</button>'
      + (opts.sheet || '') + '</div>';
  }

  /* ---- the Downloaded control's Play / Delete choice (FR-8), and the delete confirm ----
     The same shape as the film page's `b-film-downloaded-menu`. The title the confirm names is
     the SERIES and the code, never the episode's own title: *Grindadráp* alone does not say what
     is being deleted, and the corpus's episode titles overlap the film pool (the 2026-11-09
     round hit that collision). Flagged in the report as a choice, not a given. */
  function downloadedMenu(ep) {
    return '<div class="ed-scrim"></div><div class="ed-sheet">'
      + '<div class="grab"></div>'
      + '<div class="sh">' + esc(D.SERIES.title + ' · ' + code(ep.n)) + '</div>'
      + '<div class="ss">' + esc(t('download.downloaded') + ' · ' + D.mb(ep.mb)) + '</div>'
      + '<button class="row">' + ic('play') + '<span>' + esc(t('action.play')) + '</span></button>'
      + '<button class="row danger">' + ic('trash') + '<span>' + esc(t('download.delete'))
      +   '</span><span class="sz">' + esc(D.mb(ep.mb)) + '</span></button>'
      + '</div>';
  }
  function deleteConfirm(ep) {
    return '<div class="ed-scrim"></div><div class="ed-sheet">'
      + '<div class="grab"></div>'
      + '<div class="ask">' + esc(t('download.delete_one_confirm', {
          title: D.SERIES.title + ' · ' + code(ep.n), size: D.mb(ep.mb) })) + '</div>'
      + '<div class="acts"><button class="ed-btn">' + esc(t('action.cancel')) + '</button>'
      + '<button class="ed-btn danger">' + esc(t('download.delete')) + '</button></div>'
      + '</div>';
  }

  /* ---- the running control's own choice ------------------------------------------------
     FR-8 names what a *Downloaded* control offers and says nothing about a running one. A
     42 % control with no second target is a download a viewer cannot stop from the page they
     started it on, so the sheet is drawn and the report asks for it. */
  function runningMenu(ep) {
    return '<div class="ed-scrim"></div><div class="ed-sheet">'
      + '<div class="grab"></div>'
      + '<div class="sh">' + esc(D.SERIES.title + ' · ' + code(ep.n)) + '</div>'
      + '<div class="ss">' + esc((ep.pct || 0) + ' % · ' + D.mb(D.onDisk(ep)) + ' / ' + D.mb(ep.mb)) + '</div>'
      + '<button class="row">' + ic('pause') + '<span>' + esc(t('download.pause')) + '</span></button>'
      /* NOT `action.cancel`. In a confirm that word means "do not do it"; here it means "stop
         this download and throw the 273 MB away", and one word cannot be both. */
      + '<button class="row danger">' + ic('trash') + '<span>' + esc(t('download.cancel_dl')) + '</span></button>'
      + '</div>';
  }

  window.EDKit = {
    esc: esc, t: t, setLang: setLang, ic: ic, sb: sb, code: code, airLabel: airLabel,
    episodes: episodes, ctl: ctl, tog: tog, card: card, slot: slot, rail: rail, epHead: epHead,
    seriesPage: seriesPage, multiSlot: multiSlot, statusLine: statusLine,
    downloadedMenu: downloadedMenu, deleteConfirm: deleteConfirm, runningMenu: runningMenu,
    markGlyph: markGlyph
  };
})();
