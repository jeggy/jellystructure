/* invite-kit.js — builds every frame of this round from window.IV and nothing else.

   frames.html is parameterised:
       ?set=admin | join | setup-tv | setup-phone | setup-desk | canvas
       &lang=en|da|fo        &skin=aurora|midnight|noir|graphite|daylight
       &theme=dark|light     (the admin only — it is the shipped `[data-theme]`)

   THE SET DECIDES WHICH SHIPPED STYLESHEET frames.html LOADED, and that is not a convenience.
   wf.css, ravilo.css, ravilo-phone.css and desktop-directions.css each name their tokens the SAME
   way (--bg, --ink, --card, --accent) and each carries bare selectors; loading two into one
   document lets one answer for the other and the picture is then of a system that does not exist.
   That was round 9's cost for linking production CSS instead of copying it, and linking is still
   the right trade.

   EVERY SVG id IS MINTED FROM A COUNTER. A document holding several frames hides the ones for
   another set, and Chromium will not resolve a paint server defined inside a display:none subtree:
   every later reference to that id then paints nothing, in a VISIBLE frame, and it does not
   reproduce when you open that frame alone.
*/
(function () {
  'use strict';

  var IV = window.IV;
  var Q = new URLSearchParams(location.search);
  var SET   = Q.get('set')   || 'admin';
  var LANG  = Q.get('lang')  || 'en';
  var SKIN  = Q.get('skin')  || 'aurora';
  var THEME = Q.get('theme') || 'dark';

  var root = document.getElementById('frames');
  var idSeq = 0;
  function nid() { return 'iv' + (++idSeq); }

  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  }
  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* The id a PNG is named after. render-design.sh takes `--all .iv-hold` and names each file from
     the element's own id, so the id IS the file name and `<round>-<direction>-<state>.png` is what
     lets whoever writes the decision comment build it without opening every picture. */
  function hold(id, tag, title, sub) {
    var h = el('div', 'iv-hold');
    h.id = id;
    var cap = el('div', 'iv-cap');
    cap.appendChild(el('b', null, tag));
    cap.appendChild(el('h3', null, title));
    if (sub) cap.appendChild(el('span', null, sub));
    h.appendChild(cap);
    root.appendChild(h);
    return h;
  }

  /* The language and the skin become a suffix, so one render per combination writes PNGs that
     cannot collide. English + aurora + dark takes no suffix, which keeps the common case readable. */
  var SFX = (LANG === 'en' ? '' : '-' + LANG)
          + (SKIN === 'aurora' ? '' : '-' + SKIN)
          + (THEME === 'dark' ? '' : '-' + THEME);

  /* ================================================================================================
     ICONS. Strokes only, no paint server, except MARK which has a gradient and therefore an id.
     ================================================================================================ */
  var IC = {
    link: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M10 13a5 5 0 0 0 7.07 0l2.12-2.12a5 5 0 0 0-7.07-7.07L10.5 5.5"/><path d="M14 11a5 5 0 0 0-7.07 0L4.8 13.1a5 5 0 0 0 7.07 7.07L13.5 18.5"/></svg>',
    tv:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 20.5h8"/></svg>',
    warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 3.6 2.6 20h18.8L12 3.6Z"/><path d="M12 9.5v4.4"/><path d="M12 17h.01"/></svg>',
    copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M15 5.5A2.5 2.5 0 0 0 12.5 3H5.5A2.5 2.5 0 0 0 3 5.5v7A2.5 2.5 0 0 0 5.5 15"/></svg>',
    tick: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M4.5 12.5 9.5 17.5 19.5 6.5"/></svg>',
    globe:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3c2.6 3 2.6 15 0 18-2.6-3-2.6-15 0-18Z"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M12 3.5v12"/><path d="M7.5 11.5 12 16l4.5-4.5"/><path d="M4.5 19.5h15"/></svg>',
    play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2 19 12 8 18.8V5.2Z"/></svg>',
    /* the device glyphs: a shape each, not a vendor logo. A vendor wordmark in a mockup of our own
       page would be the one thing on it we do not own. */
    android: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="6" y="3.5" width="12" height="17" rx="2.4"/><path d="M10.5 17.5h3"/></svg>',
    mac:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="3" y="4.5" width="18" height="12" rx="1.8"/><path d="M1.5 19.5h21"/></svg>',
    linux:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M12 3c3 0 4 3 3.4 6.2-.3 1.6 3 4.4 3 7.3 0 2.6-2.8 4-6.4 4s-6.4-1.4-6.4-4c0-2.9 3.3-5.7 3-7.3C8 6 9 3 12 3Z"/></svg>',
    tvbox:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="2.5" y="5" width="19" height="12" rx="1.8"/><path d="M9 20.5h6"/></svg>'
  };

  /* The shipped jellyfish mark, with its gradient id minted. */
  function mark(px) {
    var id = nid();
    return IV.MARK.replace(/\{W\}/g, px).replace(/\{ID\}/g, id);
  }
  function ic(name, cls) { return '<span class="' + (cls || 'ic') + '">' + IC[name] + '</span>'; }

  /* ================================================================================================
     SURFACE 1 — THE ADMIN INVITE SHEET
     ================================================================================================ */

  /* The page the sheet opens over. It is context, not design: enough of Users & devices that the
     sheet is drawn where it lives, and no more. */
  function adminPage() {
    var shell = el('div', 'iv-shell');

    var rail = el('div', 'iv-rail');
    var brand = el('div', 'iv-brand');
    brand.innerHTML = mark(26) + '<strong>jellystructure</strong>';
    rail.appendChild(brand);
    rail.appendChild(el('div', 'iv-navgroup', 'Library'));
    ['Dashboard', 'Media', 'Activity'].forEach(function (t) {
      rail.appendChild(el('div', 'iv-nav', '<i></i>' + esc(t)));
    });
    rail.appendChild(el('div', 'iv-navgroup', 'Ravilo'));
    ['Users &amp; devices', 'Ravilo config', 'Settings'].forEach(function (t, i) {
      rail.appendChild(el('div', 'iv-nav' + (i === 0 ? ' on' : ''), '<i></i>' + t));
    });
    shell.appendChild(rail);

    var main = el('div', 'iv-main');
    var bar = el('div', 'iv-pagebar');
    bar.appendChild(el('h1', null, 'Users &amp; devices'));
    bar.appendChild(el('span', 'badge info', 'Ravilo'));
    bar.appendChild(el('span', 'spacer'));
    bar.appendChild(el('span', 'btn sm ghost', '&#8635; Refresh'));
    /* FR-2: the new button on the page bar, beside Refresh. Primary, because it is the one thing
       on this page an admin comes here to START rather than to read. */
    bar.appendChild(el('span', 'btn sm primary', esc(IV.ADMIN.button)));
    main.appendChild(bar);
    main.appendChild(el('div', 'iv-pagesub',
      'Everyone who can sign in to Ravilo on this server, and the devices they are signed in on.'));

    var card = el('div', 'card');
    card.style.padding = '6px 6px';
    [[IV.STAND.admin, 'AD', 'Signed in on 2 devices'],
     ['— no other viewers yet —', '?', 'Invite someone to get started']].forEach(function (r, i) {
      var row = el('div', 'iv-urow');
      if (i === 0) {
        row.appendChild(el('span', 'iv-uav', esc(r[1])));
      } else {
        var av = el('span', 'iv-uav', esc(r[1]));
        av.style.background = 'var(--fill-3)';
        av.style.color = 'var(--ink-dim)';
        row.appendChild(av);
      }
      var m = el('div', 'iv-umain');
      m.appendChild(el('b', null, esc(r[0])));
      m.appendChild(el('div', null, esc(r[2])));
      row.appendChild(m);
      if (i === 0) row.appendChild(el('span', 'badge ok', 'admin'));
      card.appendChild(row);
    });
    main.appendChild(card);
    shell.appendChild(main);
    return shell;
  }

  /* opts: { dir: 'A1'|'A2', state: 'ready'|'copied'|'unset'|'long' } */
  function adminSheet(opts) {
    var A = IV.ADMIN, S = IV.STAND;
    var long = opts.state === 'long';
    /* qrLongLink, not longLink: 42 bytes is the version 3-M ceiling and the longer host does not
       fit. invite-data.js STAND says why, and REPORT.md carries it as a requirement on FR-2. */
    var link = long ? S.qrLongLink : S.link;
    var host = long ? S.qrLongHost : S.host;

    var sheet = el('div', 'card iv-sheet' + (opts.dir === 'A2' ? ' a2' : ''));

    var head = el('div', 'iv-sheethead');
    head.appendChild(el('div', 'iv-sheetic', IC.link));
    var t = el('div', 't');
    t.appendChild(el('h2', null, esc(A.title)));
    t.appendChild(el('p', null, esc(A.sub)));
    head.appendChild(t);
    head.appendChild(el('div', 'iv-x', '&#10005;'));
    sheet.appendChild(head);

    if (opts.state === 'unset') {
      /* FR-2's unset state: no link, no QR, no Copy. */
      var u = el('div', 'iv-unset');
      u.appendChild(el('div', 'iv-unsetic', IC.warn));
      u.appendChild(el('h3', null, esc(A.unsetTitle)));
      u.appendChild(el('p', null, esc(A.unsetBody)));
      u.appendChild(el('span', 'btn primary', esc(A.unsetBtn)));
      sheet.appendChild(u);
      return sheet;
    }

    var copyBtn = el('span', 'btn iv-copy' + (opts.state === 'copied' ? ' done' : ''),
      (opts.state === 'copied' ? IC.tick + esc(A.copied) : IC.copy + esc(A.copy)));
    var linkRow = el('div', 'iv-linkrow');
    linkRow.appendChild(el('div', 'iv-linkbox', esc(link)));
    linkRow.appendChild(copyBtn);

    var qr = el('div', 'iv-qrplate', window.IVQr.svg(link, IV.GEO.admin.qr,
      { quiet: IV.GEO.admin.qrQuiet, dark: '#0a0c13', light: '#ffffff' }));

    if (opts.dir === 'A2') {
      /* A2 — the code and the link side by side, the code first. */
      var split = el('div', 'iv-split');
      split.appendChild(qr);
      var right = el('div', 'right');
      right.appendChild(el('div', 'iv-flabel', esc(A.linkLabel)));
      right.appendChild(linkRow);
      split.appendChild(right);
      sheet.appendChild(split);
    } else {
      /* A1 — the link first at full width, the code under it, centred and captioned. */
      sheet.appendChild(el('div', 'iv-flabel', esc(A.linkLabel)));
      var lw = el('div');
      lw.style.marginTop = '6px';
      lw.appendChild(linkRow);
      sheet.appendChild(lw);
      var qw = el('div', 'iv-qrwrap');
      qw.appendChild(qr);
      qw.appendChild(el('div', 'iv-qrcap', esc(A.scan)));
      sheet.appendChild(qw);
    }

    var lines = el('div', 'iv-lines');
    var tv = el('div', 'iv-line tv');
    tv.innerHTML = '<span class="ic">' + IC.tv + '</span><span>' + esc(A.tvLine)
      + ' <span class="mono">' + esc(host) + '</span></span>';
    lines.appendChild(tv);
    lines.appendChild(el('div', 'iv-line', esc(A.who)));
    sheet.appendChild(lines);
    return sheet;
  }

  function adminFrame(opts) {
    var f = el('div', 'iv-adminframe');
    f.style.width = IV.GEO.admin.frameW + 'px';
    f.style.height = IV.GEO.admin.frameH + 'px';
    f.appendChild(adminPage());
    var back = el('div', 'iv-back');
    back.appendChild(adminSheet(opts));
    f.appendChild(back);
    return f;
  }

  /* ================================================================================================
     SURFACE 2 — THE LANDING PAGE `/join`
     ================================================================================================ */

  /* One part of the page, by name. `words` is BRIEF or SPEC, chosen by the caller, so the two
     wordings cannot drift apart inside the builder. */
  function joinPart(name, words, opts) {
    var J = IV.JOIN, S = IV.STAND;
    var p = el('div', 'iv-part');

    if (name === 'open') {
      p.appendChild(el('div', 'iv-plabel', 'Have the app?'));
      /* ONE primary per page. The first render of L3 drew two gradient buttons — Use Ravilo in the
         browser and Open in Ravilo — which a reader scans as two equal answers to one question;
         round 9 landed the same defect from the other direction. The primary is whichever ACTION
         the order puts first, and every other button on the page is an outline. */
      p.appendChild(el('a', opts.openPrimary ? 'iv-pbtn' : 'iv-sbtn',
        ic('play') + esc(words.open)));
      /* FR-3's line, which belongs to the OPEN part in L1: a viewer who just installed the app
         comes back to this page and this is the button they need. */
      p.appendChild(el('p', 'iv-pagain', esc(J.again)));
      return p;
    }

    if (name === 'getApp') {
      p.appendChild(el('div', 'iv-plabel', esc(words.getApp)));
      var rows = el('div', 'iv-btnrow');
      var downloads = [];
      IV.PLATFORMS.forEach(function (pl) {
        if (opts.noWeb && pl.kind === 'web') return;   /* FR-3: with webBase() null, no Android */
        if (pl.kind === 'web') {
          /* The owner's 2026-11-27 change. ONE button, labelled like the others, and NO
             "open this link again" line — it never needs an install. It is a primary only when
             the page has no other primary; here Open in Ravilo holds that, so it is a secondary
             of the same weight as its siblings, which is what "like the others" means. */
          rows.appendChild(el('a', 'iv-sbtn', ic('android') + esc(pl.label)));
        } else {
          downloads.push(pl);
        }
      });
      /* macOS and Linux two-up, Samsung TV under them: "the desktop, then the TV". */
      var two = el('div', 'iv-two');
      downloads.slice(0, 2).forEach(function (pl) {
        two.appendChild(el('a', 'iv-sbtn half', ic(pl.id === 'macos' ? 'mac' : 'linux')
          + esc(pl.label)));
      });
      rows.appendChild(two);
      downloads.slice(2).forEach(function (pl) {
        rows.appendChild(el('a', 'iv-sbtn half', ic('tvbox') + esc(pl.label)));
      });
      p.appendChild(rows);
      /* The line sits under the DOWNLOAD group only. */
      p.appendChild(el('p', 'iv-pnote', ic('down', 'ic') + ' ' + esc(J.afterInstall)));
      return p;
    }

    if (name === 'browser') {
      /* FR-3: "Absent when that is null." The Ravilo Web address is what both this part and the
         Android button are built from, so one null removes both. */
      if (opts.noWeb) return null;
      p.appendChild(el('div', 'iv-plabel', 'No app needed'));
      p.appendChild(el('a', opts.browserPrimary ? 'iv-pbtn' : 'iv-sbtn',
        ic('globe') + esc(words.browser)));
      return p;
    }

    /* the TV line */
    var tv = el('div', 'iv-ptv');
    tv.innerHTML = esc(J.tvLine) + ' <span class="mono">' + esc(S.host) + '</span>';
    return tv;
  }

  /* opts: { order: 'L1'|'L2'|'L3', wording: 'brief'|'spec', wide: bool, noWeb: bool,
             notFound: bool, chrome: bool } */
  function joinBody(opts) {
    var J = IV.JOIN, S = IV.STAND;
    var words = J[opts.wording === 'spec' ? 'spec' : 'brief'];
    var page = el('div', 'iv-page' + (opts.wide ? ' wide' : '') + (opts.tall ? ' tall' : ''));
    var inner = el('div', 'iv-pinner');

    var head = el('div', 'iv-phead');
    head.innerHTML = mark(opts.wide ? 40 : 34) + '<b>' + esc(J.brand) + '</b>';
    inner.appendChild(head);

    if (opts.notFound) {
      /* FR-3's 404: one line, in the same shape. */
      var g = el('div', 'iv-pglyph');
      g.innerHTML = IC.warn;
      g.style.color = IV.SSO.tones.warn;
      inner.appendChild(g);
      inner.appendChild(el('h1', null, esc(J.notFound.title)));
      inner.appendChild(el('p', 'body', esc(J.notFound.body)));
      page.appendChild(inner);
      return page;
    }

    inner.appendChild(el('p', 'iv-plead', esc(J.hostLead)));
    inner.appendChild(el('div', 'iv-phost', esc(S.host)));

    var order = IV.ORDERS[opts.order].parts;
    /* Exactly one primary on the page. It is the first ACTION the order names — `getApp` is a list
       of platforms rather than an action, so an order that leads with it (L2) leaves the page's one
       primary in its SECOND group, and that is L2's real cost rather than a drawing accident.
       Computed here, once, so no two frames can disagree about which button is filled. */
    var firstAction = order.filter(function (n) {
      return n === 'open' || (n === 'browser' && !opts.noWeb);
    })[0];
    order.forEach(function (name) {
      var part = joinPart(name, words, {
        noWeb: opts.noWeb,
        openPrimary: (name === 'open' && firstAction === 'open'),
        browserPrimary: (name === 'browser' && firstAction === 'browser')
      });
      if (part) inner.appendChild(part);
    });
    page.appendChild(inner);
    return page;
  }

  function joinPhone(opts) {
    var g = IV.GEO.join;
    var bez = el('div', 'iv-bez' + (opts.chrome ? ' chromed' : ''));
    bez.style.width = (opts.pixel ? g.pixelW : g.phoneW) + 'px';
    bez.style.height = (opts.pixel ? g.pixelH : g.phoneH) + 'px';
    var inner = el('div', 'inner');
    var sb = el('div', 'iv-statusbar');
    sb.appendChild(el('span', null, IV.CLOCK));
    sb.appendChild(el('span', null, '&#9679;&#9679;&#9679;'));
    inner.appendChild(sb);
    if (opts.chrome) {
      var c = el('div', 'iv-chrome');
      c.appendChild(el('div', 'd'));
      c.appendChild(el('div', 'u', esc(IV.STAND.link)));
      inner.appendChild(c);
    }
    inner.appendChild(joinBody(opts));
    bez.appendChild(inner);
    bez.appendChild(el('div', 'punch'));
    return bez;
  }

  function joinWide(opts) {
    var g = IV.GEO.join;
    var f = el('div', 'iv-pageframe chromed');
    f.style.width = g.wideW + 'px';
    f.style.height = g.wideH + 'px';
    f.style.borderRadius = '14px';
    f.style.boxShadow = '0 24px 60px rgba(0,0,0,.4)';
    var c = el('div', 'iv-chrome');
    c.appendChild(el('div', 'd'));
    c.appendChild(el('div', 'u', esc(IV.STAND.link)));
    f.appendChild(c);
    opts.wide = true;
    f.appendChild(joinBody(opts));
    return f;
  }

  /* ================================================================================================
     SURFACE 3 — THE SERVER-SETUP SCREEN
     ================================================================================================ */

  function setupColumn(key) {
    var st = IV.SETUP_STATES[key];
    var c = el('div', 'iv-col');
    c.appendChild(el('div', 'iv-wordmark', 'Ravilo'));
    c.appendChild(el('div', 'iv-prompt', esc(IV.str('setup.server_prompt', LANG))));

    /* FR-6: pre-filled from a link, ONE line above the field. */
    if (st.invite) {
      var iv = el('div', 'iv-invite');
      iv.appendChild(el('div', 't', esc(IV.str('setup.from_invite', LANG))));
      c.appendChild(iv);
    }

    /* the TV's 3 dp ring and the field's own accent underline both belong to the ONE focused
       element, and only to it */
    var onField = st.focus === 'field';
    var wrap = el('div', onField ? 'iv-ring' : '');
    var field = el('div', 'iv-field' + (onField ? ' focus' : ''));
    field.appendChild(el('label', null, esc(IV.str('setup.server_label', LANG))));
    if (st.field == null) {
      /* The new example, on a field nobody has typed in. The caret goes BEFORE the placeholder,
         because the field is empty and the insertion point is at offset 0 — the first render put
         it after the text, which draws a field that already contains `ravilo.example.com`. */
      field.appendChild(el('div', 'ph', '<span class="caret lead"></span>' + esc(st.placeholder)));
    } else {
      field.appendChild(el('div', 'val', esc(st.field)));
    }
    wrap.appendChild(field);
    c.appendChild(wrap);

    /* FR-7's not-found line, under the field. */
    if (st.notFound) {
      var nf = el('div', 'iv-notfound');
      nf.appendChild(el('div', 't', esc(IV.str('receiver.setup_not_found', LANG))));
      c.appendChild(nf);
    }

    /* DEB-5002 FR-4's hidden-character warning, under the field, in the SAME column as the invite
       line above it. The two together is the whole point of drawing them in one round. */
    if (st.hidden) {
      var w = el('div', 'iv-warn');
      w.appendChild(el('div', 'sentence', esc(IV.str('setup.hidden_line_break', LANG))));
      var marks = st.marks.map(function (seg) {
        return seg.mark ? '<i>' + esc(IV.GLYPHS[seg.mark]) + '</i>' : esc(seg.t);
      }).join('');
      w.appendChild(el('div', 'iv-marks', marks));
      w.appendChild(el('button', 'iv-fix' + (st.focus === 'fix' ? ' focused' : ''),
        esc(IV.str('setup.use_fixed_address', LANG))
          .replace('{address}', '<span class="addr">' + esc(st.cleaned) + '</span>')));
      c.appendChild(w);
    }

    /* The waiting button is the shipped string and NOTHING else. The first render put a three-dot
       mark after it, and `receiver.setup_trying` already ends in an ellipsis: two "still working"
       signals in one control, one of which the string table owns and one of which the design would
       have invented. The dimmed fill is the third, and it is the one that costs no pixels. */
    var label = esc(IV.str(
      st.connect === 'trying' ? 'receiver.setup_trying' : 'receiver.setup_connect', LANG));
    c.appendChild(el('div', 'iv-connect ' + st.connect, label));
    return c;
  }

  function applyTone(node) {
    var t = IV.TONE[SKIN] || IV.TONE.aurora;
    /* `info: null` means the skin's own accent, derived in invite.css — setting it here would
       hard-code one skin's hue onto all of them, which is what the first render did. */
    if (t.info) node.style.setProperty('--tone-info', t.info);
    node.style.setProperty('--tone-bad', t.bad);
    node.style.setProperty('--warn-ink', t.warn);
    node.style.setProperty('--warn-rail', t.warn);
  }

  function setupTv(key)   { var n = el('div', 'iv-app iv-tv');   applyTone(n); n.appendChild(setupColumn(key)); return n; }
  function setupDesk(key) {
    var n = el('div', 'iv-app iv-desk'); applyTone(n);
    n.appendChild(el('div', 'lights', '<i></i><i></i><i></i>'));
    n.appendChild(setupColumn(key));
    return n;
  }
  function setupPhone(key) {
    var p = el('div', 'iv-app iv-phone'); applyTone(p);
    var s = el('div', 'screen');
    var sb = el('div', 'iv-sbar');
    sb.appendChild(el('span', null, IV.CLOCK));
    sb.appendChild(el('span', null, '&#9679;&#9679;&#9679;'));
    s.appendChild(sb);
    s.appendChild(setupColumn(key));
    p.appendChild(s);
    p.appendChild(el('div', 'punch'));
    return p;
  }

  /* ================================================================================================
     THE FRAME LISTS. A variant renders FEWER frames than the base: the Danish and Faroese runs draw
     the frames where length is the question, the skin runs draw the ones that prove the skin, the
     light run draws the admin. Which is which is HERE, so REPORT.md's render commands and this file
     cannot disagree about what exists.
     ================================================================================================ */
  var BUILD = {

    admin: function () {
      /* The admin console is English only (FR-2) and has no da/fo run. It has two THEMES, and a
         direction that only exists in the dark is half a direction. */
      hold('admin-a1' + SFX, 'A1 · ' + THEME.toUpperCase(), 'The invite sheet — link first',
        'the link at full width with Copy, the code under it, then the TV line and the account line'
      ).appendChild(adminFrame({ dir: 'A1', state: 'ready' }));

      hold('admin-a2' + SFX, 'A2 · ' + THEME.toUpperCase(), 'The invite sheet — code first',
        'the code and the link side by side in the same 440 px modal'
      ).appendChild(adminFrame({ dir: 'A2', state: 'ready' }));

      hold('admin-a1-copied' + SFX, 'A1 · ' + THEME.toUpperCase(), 'Copy pressed',
        'a local confirmation, which is the one thing on this sheet that is not server-pushed — a question for Bárður'
      ).appendChild(adminFrame({ dir: 'A1', state: 'copied' }));

      hold('admin-unset' + SFX, 'A1 · ' + THEME.toUpperCase(), 'No public address configured',
        'FR-2’s unset state: no link, no code, no Copy'
      ).appendChild(adminFrame({ dir: 'A1', state: 'unset' }));

      hold('admin-a1-long' + SFX, 'A1 · ' + THEME.toUpperCase(), 'The longest host',
        'a host no stand-in names — flagged in the report — inside the shipped 440 px modal'
      ).appendChild(adminFrame({ dir: 'A1', state: 'long' }));
    },

    join: function () {
      /* The brief says the drawing is English only; the Danish and Faroese drafts are in the
         report's flat list and in invite-data.js, not in these pictures. */
      ['L1', 'L2', 'L3'].forEach(function (o) {
        hold('join-' + o.toLowerCase() + '-phone', o, IV.ORDERS[o].name,
          IV.ORDERS[o].note).appendChild(joinPhone({ order: o, wording: 'brief', chrome: true }));
      });

      hold('join-l1-pixel', 'L1 · 412', 'L1 on the wider phone',
        'Pixel 9 412×915 — the same page, 19 px more of it'
      ).appendChild(joinPhone({ order: 'L1', wording: 'brief', chrome: true, pixel: true }));

      hold('join-l1-wide', 'L1 · 1100', 'L1 over the page’s own 900 px breakpoint',
        'the shipped .in goes 420 → 560 and the heading 27 → 34 px'
      ).appendChild(joinWide({ order: 'L1', wording: 'brief' }));

      hold('join-l1-noweb', 'L1 · no web', 'With no Ravilo Web address configured',
        'FR-3: the Android button and the browser part are both absent — the page is downloads and the TV line'
      ).appendChild(joinPhone({ order: 'L1', wording: 'brief', chrome: true, noWeb: true }));

      hold('join-l1-spec', 'L1 · SPEC words', 'L1 with FR-3’s own wording',
        'Open the Ravilo app / Get the app / Watch in your browser — the strings the spec carries'
      ).appendChild(joinPhone({ order: 'L1', wording: 'spec', chrome: true }));

      hold('join-404', '404', 'The invite link isn’t set up',
        'FR-3’s 404, one line in the same shape'
      ).appendChild(joinPhone({ notFound: true, order: 'L1', wording: 'brief', chrome: true }));

      hold('join-404-wide', '404 · 1100', 'The 404 at desktop width', ''
      ).appendChild(joinWide({ notFound: true, order: 'L1', wording: 'brief' }));
    },

    'setup-tv': function () {
      var list = [
        ['invite',  'From an invite', 'FR-6: the address is shown and the viewer presses Connect'],
        ['example', 'The new example', 'setup.server_example replaces the hard-coded 192.168.1.1:8097'],
        ['trying',  'Trying…', 'FR-7: the probe is out, focus has not moved, a second press does nothing'],
        ['notFound','Not found', 'FR-7: the text stays, the line shows, focus stays on Connect'],
        ['hidden',  'A hidden line break AND the invite line',
          'the two lines in one column — the invite line above the field, the warning below it'],
        ['longest', 'The longest address', 'a host no stand-in names — flagged in the report']
      ];
      list.forEach(function (s) {
        hold('setup-tv-' + s[0].toLowerCase() + SFX, 'TV · 1920', s[1], s[2])
          .appendChild(setupTv(s[0]));
      });
    },

    'setup-phone': function () {
      [['invite', 'From an invite'], ['trying', 'Trying…'], ['notFound', 'Not found'],
       ['hidden', 'A hidden line break AND the invite line'], ['example', 'The new example']
      ].forEach(function (s) {
        hold('setup-phone-' + s[0].toLowerCase() + SFX, 'PHONE · 412', s[1], '')
          .appendChild(setupPhone(s[0]));
      });
    },

    'setup-desk': function () {
      [['invite', 'From an invite'], ['trying', 'Trying…'], ['notFound', 'Not found'],
       ['example', 'The new example']
      ].forEach(function (s) {
        hold('setup-desk-' + s[0].toLowerCase() + SFX, 'DESKTOP · 1200', s[1], '')
          .appendChild(setupDesk(s[0]));
      });
    },

    /* The canvas: the three orders side by side as a table, and the strings that disagree. No
       shipped stylesheet is loaded for this set — these are the round's own comparison blocks. */
    canvas: function () {
      var h = hold('canvas-orders', 'STUDY', 'The question, as a choice',
        'what each order gives and what it costs');
      var t = el('table', 'iv-tbl');
      t.innerHTML = '<tr><th>Order</th><th>The page, top to bottom</th><th>Gives</th><th>Costs</th></tr>'
        + '<tr><td><b>L1</b> Open in Ravilo first<span class="iv-pick">recommended</span></td>'
        + '<td class="mono">open · get-app · browser · tv</td>'
        + '<td>A viewer who has the app is one press from done. A viewer who does not sees it fail '
        + 'softly and the next thing on the page is the fix.</td>'
        + '<td>The first button is the one that does nothing on a phone with no app — and on a '
        + 'phone with no app it is also the <em>only</em> button whose failure is silent.</td></tr>'
        + '<tr><td><b>L2</b> Get the app first</td>'
        + '<td class="mono">get-app · open · browser · tv</td>'
        + '<td>The page leads with what always works.</td>'
        + '<td>Four buttons before the one press most invited viewers need, and the page’s primary '
        + 'is then a list rather than an action.</td></tr>'
        + '<tr><td><b>L3</b> Browser first <span class="iv-pick iv-against">spec as written</span></td>'
        + '<td class="mono">browser · open · get-app · tv</td>'
        + '<td>The one route that needs nothing installed and no second step.</td>'
        + '<td>It sends the viewer to the browser client when the app is the better player, and it '
        + 'is the order <em>FR-3 already says</em> — so picking L1 or L2 needs a dated amendment.</td></tr>';
      h.appendChild(t);

      var h2 = hold('canvas-strings', 'STUDY', 'Where the three sources disagree',
        'every row is a decision for Bárður, not a defect to fix in the drawing');
      var t2 = el('table', 'iv-tbl');
      t2.innerHTML = '<tr><th>What</th><th>Shipped / spec</th><th>Brief</th><th>Drawn</th></tr>'
        + '<tr><td class="mono">setup.server_prompt</td>'
        + '<td>i18n ships <em>Enter your <b>jellystructure</b> server address</em>; DEB-5836 FR-3 '
        + 'replaces it with <em>Enter your Ravilo server address</em></td>'
        + '<td>—</td><td>the Ravilo wording: a viewer never reads that word (owner, 2026-10-23), '
        + 'and the constitution outranks the i18n rule</td></tr>'
        + '<tr><td>the page’s part labels</td>'
        + '<td class="mono">Open the Ravilo app · Get the app · Watch in your browser</td>'
        + '<td class="mono">Open in Ravilo · Get the app · Use Ravilo in the browser</td>'
        + '<td>the brief’s</td></tr>'
        + '<tr><td>the page’s part ORDER</td>'
        + '<td class="mono">browser · open · get-app · tv</td>'
        + '<td class="mono">open · get-app · browser · tv</td>'
        + '<td>all three, as L1/L2/L3</td></tr>'
        + '<tr><td>the account line</td>'
        + '<td>FR-2: “must exist in <em>Jellyfin</em> first”</td>'
        + '<td>“who can sign in (a Jellyfin account on this server)”</td>'
        + '<td>“must exist <em>on this server</em> first” — the admin reads this sentence out to a '
        + 'viewer, and a viewer never reads that word either</td></tr>'
        + '<tr><td>an iPhone button</td>'
        + '<td>FR-3 names Android, macOS, Linux, Samsung TV; iOS is in the Out list</td>'
        + '<td>“Android, iPhone/web, desktop”</td>'
        + '<td>no iPhone button — the browser part is the iPhone route</td></tr>';
      h2.appendChild(t2);
    }
  };

  (BUILD[SET] || BUILD.admin)();

  /* ================================================================================================
     THE FOLD PASS — measured, not asserted.

     The shipped page centres its column in the viewport (`body { display:flex; align-items:center }`
     with `min-height:100vh`). A column TALLER than the viewport then centres its overflow, which
     clips the top as well as the bottom — a page whose first line is cut off is not a drawing
     decision, it is a bug that only the picture shows. So every page frame is measured after
     layout: when the column overflows, the frame is pinned to the top (which is what the viewer
     reaches when they scroll to it) and the caption says by how much. A number in a caption that
     the browser computed cannot drift from the picture it is printed on.
     ================================================================================================ */
  Array.prototype.forEach.call(root.querySelectorAll('.iv-hold'), function (h) {
    var page = h.querySelector('.iv-page');
    if (!page) return;
    var inner = page.querySelector('.iv-pinner');
    if (!inner) return;
    var pad = 80;                                  /* the page's own 40 px top and bottom padding */
    var over = Math.round(inner.getBoundingClientRect().height + pad - page.clientHeight);
    var cap = h.querySelector('.iv-cap span');
    if (over > 0) {
      page.classList.add('tall');
      if (cap) cap.innerHTML += (cap.innerHTML ? ' · ' : '')
        + '<em>the page scrolls: ' + over + ' px below the fold</em>';
    } else if (cap) {
      cap.innerHTML += (cap.innerHTML ? ' · ' : '') + '<i>fits the viewport, ' + (-over) + ' px spare</i>';
    }
  });
})();
