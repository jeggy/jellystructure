/* ts-kit.js — the builders every frame in the round is made of.

   Round `2026-10-23-tv-signin`. One builder per surface, called with options, so that "is this
   the same screen in two states?" is settled by the source and not only by the caption. Nothing
   here invents a string or a number: both come from ts-data.js.

   SVG ids come from a counter (`uid`), because PROCESS.md's trap is real — a document holding
   frames for another width hides them, and Chromium will not resolve a gradient defined inside a
   `display:none` subtree, so every later reference to that id paints nothing in a VISIBLE frame
   and it does not reproduce when you open that frame alone.

   ES5 register, to match `or-kit.js` and the shipped mockups.
*/
window.TSKit = (function (D) {
  'use strict';

  var lang = 'en';
  function setLang(l) { lang = l; }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  /* A string, in the current language, with {placeholders} filled. A missing key is a loud
     failure rather than an empty span: a silently blank line in a picture is the defect this
     round can least afford. */
  function t(key, vars) {
    var row = D.S[key];
    if (!row) throw new Error('ts-kit: no string ' + key);
    var s = row[lang];
    if (s === undefined) throw new Error('ts-kit: ' + key + ' has no ' + lang);
    if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) s = s.split('{' + k + '}').join(vars[k]);
    return s;
  }

  var ids = 0;
  function uid(p) { return (p || 'ts') + (++ids); }

  /* ---------------------------------------------------------------------------------------------
     The Ravilo mark. The shipped drawing from ravilo-phone-markup.js, with its gradient id minted
     per call. `tone` picks whether the tentacles take the accent or a flat ink, because the
     browser pages of surface 4 are ONE FILE and cannot reach for a token.
     --------------------------------------------------------------------------------------------- */
  function mark(px, a, b) {
    var g = uid('tsMark');
    a = a || 'var(--accent)'; b = b || 'var(--accent-2)';
    return '<svg class="ts-mark" width="' + px + '" height="' + px + '" viewBox="12 20 76 76" aria-hidden="true">'
      + '<defs><linearGradient id="' + g + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0" stop-color="' + a + '"/><stop offset="1" stop-color="' + b + '"/></linearGradient></defs>'
      + '<path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#' + g + ')"/>'
      + '<g stroke="url(#' + g + ')" stroke-width="4.5" stroke-linecap="round" fill="none">'
      + '<path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/>'
      + '<path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/>'
      + '<path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/>'
      + '<path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/>'
      + '</g></svg>';
  }
  function wordmark(px, a, b) {
    return '<span class="ts-wm" style="--wmpx:' + px + 'px">' + mark(px) + '<b>Ravilo</b></span>';
  }

  /* The admin's own mark, the quartet-and-play glyph, copied from BrandMark.kt so the admin frame
     is the admin's page and not a Ravilo page wearing its CSS. */
  function adminMark(attrs) {
    var g = uid('jsg');
    return '<svg ' + attrs + ' viewBox="0 0 100 100" aria-hidden="true"><defs>'
      + '<linearGradient id="' + g + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0" stop-color="#b15cd0"/><stop offset=".52" stop-color="#7b6ef0"/>'
      + '<stop offset="1" stop-color="#00a4dc"/></linearGradient></defs>'
      + '<rect width="100" height="100" rx="30" fill="url(#' + g + ')"/>'
      + '<g transform="translate(13 13) scale(.74)" fill="#fff">'
      + '<rect x="10" y="10" width="35" height="35" rx="9"/>'
      + '<rect x="55" y="10" width="35" height="35" rx="9" opacity=".5"/>'
      + '<rect x="10" y="55" width="35" height="35" rx="9" opacity=".5"/>'
      + '<rect x="55" y="55" width="35" height="35" rx="9" fill="none" stroke="#fff" stroke-width="6"/>'
      + '<path d="M68 64 L84 72.5 L68 81 Z"/></g></svg>';
  }

  /* ---------------------------------------------------------------------------------------------
     ICONS — stroked 24-box glyphs, matching the shipped set's weight (1.7) and round caps.
     --------------------------------------------------------------------------------------------- */
  var IC = {
    lang: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M3.6 9h16.8M3.6 15h16.8M12 3c2.4 2.4 2.4 15.6 0 18M12 3c-2.4 2.4-2.4 15.6 0 18',
    note: 'M9 18V6l10-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0M19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0',
    life: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M9.2 9.3A3 3 0 0 1 12 7.5c1.7 0 2.9 1 2.9 2.5 0 1.3-.8 1.9-1.9 2.6-.8.5-1 .9-1 1.6M12 17.2v.1',
    key: 'M15.5 3.5a5 5 0 0 1 0 10 5 5 0 0 1-2.3-.6L11 15H8.5v2.5H6V20H3v-3l8.1-8.1a5 5 0 0 1-.1-1.4 5 5 0 0 1 4.5-4M16.7 7.3v.1',
    gear: 'M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.9 2.9l-.1-.1a1.7 1.7 0 0 0-2.9 1.2v.2a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-2.9-1.3l-.1.1a2 2 0 1 1-2.9-2.9l.1-.1a1.7 1.7 0 0 0-1.2-2.9H3a2 2 0 1 1 0-4h.2a1.7 1.7 0 0 0 1.2-2.9l-.1-.1a2 2 0 1 1 2.9-2.9l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.2a1.7 1.7 0 0 0 2.9 1.2l.1-.1a2 2 0 1 1 2.9 2.9l-.1.1a1.7 1.7 0 0 0 1.2 2.9h.2a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.6 1',
    tv: 'M4 5.5h16a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 20 17.5H4A1.5 1.5 0 0 1 2.5 16V7A1.5 1.5 0 0 1 4 5.5M8.5 21h7',
    chev: 'M9 6l6 6-6 6',
    tick: 'M5 12.5l4.5 4.5L19 7',
    warn: 'M12 3.6 1.9 20.4h20.2zM12 9.6v5M12 17.6v.1',
    bad: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M8.6 8.6l6.8 6.8M15.4 8.6l-6.8 6.8',
    globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M3.6 9h16.8M3.6 15h16.8M12 3c2.4 2.4 2.4 15.6 0 18M12 3c-2.4 2.4-2.4 15.6 0 18',
    cam: 'M4 19.5h16a1.5 1.5 0 0 0 1.5-1.5V9A1.5 1.5 0 0 0 20 7.5h-2.6l-1.3-2.2a1.5 1.5 0 0 0-1.3-.8H9.2a1.5 1.5 0 0 0-1.3.8L6.6 7.5H4A1.5 1.5 0 0 0 2.5 9v9A1.5 1.5 0 0 0 4 19.5M12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7',
    spin: 'M12 3.5v3.2M12 17.3v3.2M3.5 12h3.2M17.3 12h3.2M6 6l2.3 2.3M15.7 15.7 18 18M18 6l-2.3 2.3M8.3 15.7 6 18'
  };
  function ic(name, cls) {
    return '<svg class="ts-ic ' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
      + '<path d="' + IC[name] + '"/></svg>';
  }

  /* =============================================================================================
     SURFACE 1 — THE TV
     ============================================================================================= */

  /* The QR tile. A LIGHT tile on the dark stage with dark modules, which is what every TV code
     screen does and what a camera wants: most scanners cope with an inverted code, none of them
     has to here, and a bright square is also what draws the eye to the one thing to point a
     phone at. The quiet zone is inside the SVG (4 modules), so the tile's own padding is extra
     margin rather than the standard's. */
  function qrTile(px) {
    return '<div class="ts-qrtile" style="--qr:' + px + 'px">'
      + window.TSQr.svg(D.LINK, D.M.qrModule, { dark: '#0a0c13', light: '#ffffff' })
      + '</div>';
  }

  /* The code, grouped 3 + 3. One builder, so the grouping and the tracking cannot drift between
     the TV and anything that quotes it. */
  function codeBlock(px) {
    return '<div class="ts-code" style="--codepx:' + (px || D.M.codePx) + 'px">'
      + '<div class="lbl">' + esc(t('tvsignin.code_label')) + '</div>'
      + '<div class="val">' + esc(D.grouped(D.CODE)) + '</div>'
      + '</div>';
  }

  /* The TV's name. FR-5 asks for "this TV's name" and nothing more; it is labelled, because a
     bare proper noun on a sign-in screen reads as a greeting rather than as an answer to "which
     screen am I typing at?" — the question a household with two sets actually has. */
  function tvName(name) {
    return '<div class="ts-tvname"><span class="k">' + esc(t('tvsignin.this_tv')) + '</span>'
      + '<b>' + esc(name) + '</b></div>';
  }

  function orGoTo() {
    return '<div class="ts-goto"><span>' + esc(t('tvsignin.or_go_to')) + '</span>'
      + '<b>' + esc(D.HOST + '/tv') + '</b></div>';
  }

  /* The approving line. Drawn where the instruction sits (the brief offers "there, or under the
     code") — it REPLACES the instruction rather than joining it, because the instruction is
     telling the viewer to do a thing that someone has now already done. */
  function approvingLine(name) {
    return '<div class="ts-approving">'
      + '<span class="ts-dots"><i></i><i></i><i></i></span>'
      + '<span>' + esc(t('tvsignin.approving', { name: name })) + '</span></div>';
  }

  /* The footer: the one focusable thing on the screen, drawn FOCUSED, because FR-5 makes it the
     first focus target. The treatment is the shipped `.btn.focused` — a ring plus a lift — and
     not a third invention. `addUser` adds the Back hint, which is the only difference between
     state (a) and state (d). */
  function tvFooter(opts) {
    return '<div class="ts-tvfoot">'
      + '<button class="btn ghost' + (opts.unfocused ? '' : ' focused') + '">'
      + esc(t('tvsignin.password_instead')) + '</button>'
      + (opts.addUser ? '<span class="ts-backhint">' + esc(t('tvsignin.back_to_profiles')) + '</span>' : '')
      + '</div>';
  }

  /* ---- DIRECTION A — "Two columns": the QR on the left, the words on the right. ----
     The lean. The QR is the biggest thing on the screen and sits where a left-to-right reader's
     eye starts; the code, the instruction and the TV's name stack beside it in reading order, so
     a viewer who cannot scan reads straight down the one column that concerns them.

     opts: { qr, name, approving, addUser, address, codePx, unfocused } */
  function tvA(opts) {
    opts = opts || {};
    var qr = opts.qr !== false, name = opts.name || D.TV;
    /* With no QR the code is the only thing left to read across the room, so it takes the larger
       of the two sizes. An explicit `codePx` in the options still wins, because that is what the
       96 px comparison frame is for. */
    var codePx = opts.codePx || (qr ? D.M.codePx : D.M.codePxNoQr);
    var right = codeBlock(codePx)
      + (opts.approving
        ? approvingLine(opts.approving)
        /* While someone is approving, the instruction AND the printed address both go: both of
           them tell the viewer to start a thing that has already been started. The first render
           replaced the sentence and left "Or go to ravilo.example.net/tv" underneath it. */
        : '<p class="ts-how">' + esc(t(qr ? 'tvsignin.how' : 'tvsignin.how_noqr')) + '</p>')
      + (opts.address !== false && qr && !opts.approving ? orGoTo() : '')
      + tvName(name);
    return '<div class="ts-tv' + (qr ? '' : ' noqr') + '">'
      + '<div class="ts-tvhead">' + wordmark(44) + '</div>'
      + '<div class="ts-tvbody ' + (qr ? 'two' : 'one') + '">'
      + (qr ? '<div class="ts-tvleft">' + qrTile(D.M.qrSide) + '</div>' : '')
      + '<div class="ts-tvright">'
      + '<h1 class="ts-tvtitle">' + esc(t('tvsignin.title')) + '</h1>'
      + right
      + '</div></div>'
      + tvFooter(opts)
      + '</div>';
  }

  /* ---- DIRECTION B — "One column, centred": everything stacked down the middle. ----
     The brief's alternative. Same blocks, same strings, same sizes — only the arrangement
     differs, which is what makes the two comparable at all. */
  function tvB(opts) {
    opts = opts || {};
    var qr = opts.qr !== false, name = opts.name || D.TV;
    /* B's QR and code are SMALLER than A's, and that is the measurement the two directions turn
       on rather than a styling choice: stacked on 1080 lines, FR-5's five blocks plus a 444 px
       QR do not fit. `qrSideB` and `codePxB` are the largest pair that does. The arithmetic is
       in ts-data.js and the consequence is in the report. */
    return '<div class="ts-tv b' + (qr ? '' : ' noqr') + '">'
      + '<div class="ts-tvhead">' + wordmark(44) + '</div>'
      + '<div class="ts-tvstack">'
      + '<h1 class="ts-tvtitle">' + esc(t('tvsignin.title')) + '</h1>'
      + (qr ? qrTile(D.M.qrSideB) : '')
      + codeBlock(opts.codePx || (qr ? D.M.codePxB : D.M.codePx))
      + (opts.approving
        ? approvingLine(opts.approving)
        : '<p class="ts-how">' + esc(t(qr ? 'tvsignin.how' : 'tvsignin.how_noqr')) + '</p>')
      + (opts.address !== false && qr && !opts.approving ? orGoTo() : '')
      + tvName(name)
      + '</div>'
      + tvFooter(opts)
      + '</div>';
  }

  /* The shipped password screen, UNCHANGED, drawn once as context so the reviewer can see what
     "one press away" lands on. This is R175's `.signin-panel`, built from the shipped classes;
     nothing in this round changes it. The blinking cursor is left off — the shipped rule is an
     INFINITE animation and the canvas rules forbid those because they break the render. */
  function tvPassword() {
    return '<div class="ts-tv plain">'
      + '<div class="login-wrap">'
      + '<div class="signin-panel">'
      + '<h3>' + esc(t('login.title')) + '</h3>'
      + '<p class="sub">' + esc(t('login.subtitle')) + '</p>'
      + '<div class="signin-field"><label>' + esc(t('login.username')) + '</label>'
      + '<div class="inp focused"><span class="ph">—</span></div></div>'
      + '<div class="signin-field"><label>' + esc(t('login.password')) + '</label>'
      + '<div class="inp"><span class="ph">—</span></div></div>'
      + '<div class="actions"><button class="btn ghost">' + esc(t('action.back')) + '</button>'
      + '<button class="btn primary">' + esc(t('login.sign_in')) + '</button></div>'
      + '</div></div></div>';
  }

  /* The ordinary could-not-reach-the-server state FR-5 defers to, drawn because the brief's
     states list it nowhere and SKILL-house.md requires it of any surface with a socket. The
     words are the SHIPPED key, not a new one. */
  function tvUnreachable() {
    return '<div class="ts-tv plain">'
      + '<div class="ts-tverr">' + ic('bad', 'big')
      + '<h2>' + esc(t('login.error_unreachable')) + '</h2>'
      + '<div class="ts-tvfoot inline"><button class="btn primary focused">' + esc(t('action.retry')) + '</button>'
      + '<button class="btn ghost">' + esc(t('tvsignin.password_instead')) + '</button></div>'
      + '</div></div>';
  }

  /* The profile picker, as context for state (d): Add user is where this screen is reached from
     when Anna is already on the TV. The shipped `.profiles` / `.prof` classes, unchanged. Its
     `.switch` variant is backdrop-filter in the shipped file and this is the plain one, so no
     canvas rule is touched. */
  function tvProfiles() {
    function p(name, tint, focused) {
      return '<div class="prof' + (focused ? ' focused' : '') + '">'
        + '<div class="pic" style="background:' + tint + '"><span class="sheen"></span>'
        + esc(name.slice(0, 2).toUpperCase()) + '</div>'
        + '<div class="nm">' + esc(name) + '</div></div>';
    }
    return '<div class="ts-tv plain">'
      + '<div class="profiles"><h2>Who’s watching?</h2><div class="grid">'
      + p(D.ANNA, 'linear-gradient(140deg,#7b6ef0,#3fb6f5)', false)
      + p(D.JONAS, 'linear-gradient(140deg,#b15cd0,#7b6ef0)', false)
      + '<div class="prof focused"><div class="pic add">＋</div>'
      + '<div class="nm">' + esc(t('profile.add_user')) + '</div></div>'
      + '</div></div></div>';
  }

  /* =============================================================================================
     SURFACE 2 and 3 — THE PHONE, RAVILO WEB AND THE DESKTOP
     These bodies are shared: `phone` and `desk` call the SAME builders, which is the honest model
     of a design that is one flow on three surfaces.
     ============================================================================================= */

  /* The phone's account page, with the new row in its real company. */
  function accountPage() {
    var rows = D.ACCOUNT_ROWS.map(function (r) {
      var label = r.isNew ? t(r.label) : r.label;
      return '<button class="pft-row' + (r.isNew ? ' ts-newrow' : '') + '">'
        + ic(r.icon)
        + '<span>' + esc(label) + (r.sub ? '<div class="rsub">' + esc(r.sub) + '</div>' : '') + '</span>'
        + ic('chev', 'chev') + '</button>';
    }).join('');
    return '<div class="ts-acct">'
      + '<div class="pft-id">'
      + '<div class="pft-av" style="background:linear-gradient(140deg,#7b6ef0,#3fb6f5)">AN</div>'
      + '<div><div class="pft-nm">' + esc(D.ANNA) + '</div><div class="pft-sub">Admin</div></div>'
      + '</div>'
      + '<div class="acct-sec">Account</div>'
      + '<div class="pft-grp">' + rows + '</div>'
      + '<button class="pft-out">' + esc(t('profile.sign_out')) + '</button>'
      + '<div class="pft-ver">Ravilo <b>1.38</b> · ' + esc(D.HOST) + '</div>'
      + '</div>';
  }

  /* The code field. Decision 3's lean is ONE field that groups as you type and takes a paste;
     `boxes: true` draws the alternative, six separate boxes. Both are drawn in both the empty
     and the typed state, so the comparison is on like for like.

     opts: { typed, boxes, focused } */
  function codeField(opts) {
    opts = opts || {};
    var v = opts.typed ? D.grouped(D.CODE) : '';
    var inner;
    if (opts.boxes) {
      var cells = '';
      for (var i = 0; i < 6; i++) {
        var ch = opts.typed ? D.CODE.charAt(i) : '';
        cells += '<span class="cell' + (ch ? ' on' : '') + (!opts.typed && i === 0 ? ' caret' : '') + '">'
          + esc(ch) + '</span>' + (i === 2 ? '<span class="gap"></span>' : '');
      }
      inner = '<div class="ts-boxes">' + cells + '</div>';
    } else {
      inner = '<div class="ts-onefield' + (opts.focused === false ? '' : ' focused') + '">'
        + (v ? '<b>' + esc(v) + '</b>' : '<span class="ph">' + esc(D.grouped('——————')) + '</span>')
        + '</div>';
    }
    return '<div class="fld"><label>' + esc(t('tvcode.field')) + '</label>' + inner + '</div>';
  }

  /* The code screen. `disabled` follows from `typed`, which is the point of drawing both: the
     control is SHOWN and disabled, never hidden (the owner's own rule, round 9). */
  function codeScreen(opts) {
    opts = opts || {};
    return '<div class="acct-bar"><button>‹</button><h2>' + esc(t('tvcode.row')) + '</h2></div>'
      + '<div class="acct-body">'
      + '<p class="pf-sub">' + esc(t('tvcode.body')) + '</p>'
      + codeField(opts)
      + '<div class="pf-btns"><button class="pf-btn primary' + (opts.typed ? '' : ' off') + '"'
      + (opts.typed ? '' : ' disabled') + '>' + esc(t('tvcode.continue')) + '</button></div>'
      + '</div>';
  }

  /* The confirm screen. `switchable` adds FR-6's "Not you? Switch", which appears only when the
     device has more than one profile on it. */
  function confirmScreen(opts) {
    opts = opts || {};
    var who = opts.name || D.ANNA;
    return '<div class="acct-bar"><button>‹</button><h2>' + esc(t('tvcode.row')) + '</h2></div>'
      + '<div class="acct-body ts-confirm">'
      + '<div class="ts-tvcard">' + ic('tv', 'big') + '<b>' + esc(opts.tv || D.TV) + '</b>'
      + '<span class="ts-codechip">' + esc(D.grouped(D.CODE)) + '</span></div>'
      + '<h3 class="ts-q">' + esc(t('tvcode.confirm', { tv: opts.tv || D.TV, name: who })) + '</h3>'
      + '<p class="pf-sub">' + esc(t('tvcode.confirm_body')) + '</p>'
      + '<div class="pf-btns col"><button class="pf-btn primary">' + esc(t('login.sign_in')) + '</button>'
      + '<button class="pf-btn">' + esc(t('action.cancel')) + '</button></div>'
      + (opts.switchable
        ? '<div class="ts-notyou"><div class="ts-av">' + esc(who.slice(0, 2).toUpperCase()) + '</div>'
          + '<span>' + esc(who) + '</span><button>' + esc(t('tvcode.not_you')) + '</button></div>'
        : '')
      + '</div>';
  }

  /* One outcome, from the OUTCOMES table, so the phone and the Mac cannot show different sets. */
  function outcomeScreen(o) {
    var glyph = o.tone === 'ok' ? 'tick' : (o.tone === 'bad' ? 'bad' : 'warn');
    return '<div class="acct-bar"><button>‹</button><h2>' + esc(t('tvcode.row')) + '</h2></div>'
      + '<div class="acct-body ts-outcome ' + o.tone + '">'
      + '<span class="ts-oglyph">' + ic(glyph, 'big') + '</span>'
      + '<h3>' + esc(t(o.key, { tv: o.tv })) + '</h3>'
      + (o.body ? '<p class="pf-sub">' + esc(t(o.body)) + '</p>' : '')
      + (o.cta ? '<div class="pf-btns"><button class="pf-btn primary">' + esc(t(o.cta)) + '</button></div>' : '')
      + '</div>';
  }

  /* ---- SURFACE 3 — the sign-in screen with the Sign in with ‹name› button(s). ----
     FR-12 puts the buttons ABOVE the username field. Decision 4's lean is a full-width filled
     button with an `or` divider before the fields; `outlined: true` draws the alternative.

     opts: { names: [], outlined, waiting, wide } */
  function signinScreen(opts) {
    opts = opts || {};
    var names = opts.names || [];
    /* DECISION 4, AND WHY THE LEAN MOVED. The brief's lean was "a full-width FILLED button above
       the fields, with an `or` divider before them". Drawn, that put TWO filled gradient buttons
       on one screen — the provider button and the form's own Sign in — and the first render of
       `ph-signin-two` and `admin-signin-two` shows a page with two equally loud primaries and no
       answer to "which one is the way in?". One filled control per screen is the house rule
       ("spend your boldness in one place").
       So the round draws BOTH weightings and the pictures decide:
         default        — the provider buttons are full-width and OUTLINED, the form's Sign in
                          keeps the filled primary. Recommended.
         `boldSso`      — the brief's lean: the first provider button is filled and the form's
                          Sign in steps down to a plain button, so there is still only one.
       Either way it is full width, above the fields, with the `or` divider — which is the part
       of decision 4 the pictures agree on. */
    var btns = names.map(function (n, i) {
      var filled = opts.boldSso && i === 0;
      return '<button class="ts-ssobtn' + (filled ? ' filled' : ' outline') + '">'
        + ic('globe') + '<span>' + esc(t('sso.button', { name: n })) + '</span></button>';
    }).join('');
    /* Exactly one filled control on the screen, whichever weighting is drawn. */
    var submitCls = 'pf-btn' + (opts.boldSso && names.length ? '' : ' primary');

    if (opts.waiting) {
      return '<div class="ts-signin' + (opts.wide ? ' wide' : '') + '">'
        + '<div class="ts-sihead">' + wordmark(opts.wide ? 40 : 34) + '</div>'
        + '<div class="ts-waiting">'
        + '<span class="ts-spin">' + ic('spin', 'big') + '</span>'
        + '<h3>' + esc(t('sso.waiting')) + '</h3>'
        + '<div class="pf-btns"><button class="pf-btn">' + esc(t('action.cancel')) + '</button></div>'
        + '</div></div>';
    }
    return '<div class="ts-signin' + (opts.wide ? ' wide' : '') + '">'
      + '<div class="ts-sihead">' + wordmark(opts.wide ? 40 : 34) + '</div>'
      + '<h2>' + esc(t('login.title')) + '</h2>'
      + (names.length ? '<div class="ts-ssorow">' + btns + '</div>'
        + '<div class="ts-or"><i></i><span>' + esc(t('sso.or')) + '</span><i></i></div>' : '')
      + '<p class="pf-sub">' + esc(t('login.subtitle')) + '</p>'
      + '<div class="fld"><label>' + esc(t('login.username')) + '</label><div class="ts-inp"><span class="ph">—</span></div></div>'
      + '<div class="fld"><label>' + esc(t('login.password')) + '</label><div class="ts-inp"><span class="ph">—</span></div></div>'
      + '<div class="pf-btns"><button class="' + submitCls + '">' + esc(t('login.sign_in')) + '</button></div>'
      + '<div class="ts-siftr">' + esc(t('login.change_server')) + ' <b>' + esc(D.HOST) + '</b></div>'
      + '</div>';
  }

  /* ---- the shells ---- */

  /* A phone screen: the status bar and whatever the body is. */
  var STATUS = '<div class="ts-sb"><span>9:41</span><span class="ic">▮▮▮</span></div>';
  function phone(body) { return STATUS + body; }

  /* A browser window. `url` is drawn in the bar, because surface 2(c) and surface 4 are both
     "which address am I on?" questions. */
  function browser(url, body, cls) {
    return '<div class="ts-web ' + (cls || '') + '">'
      + '<div class="ts-wchrome"><span class="ts-wdots"><i></i><i></i><i></i></span>'
      + '<span class="ts-wurl">' + esc(url) + '</span></div>'
      + '<div class="ts-wbody">' + body + '</div></div>';
  }

  /* The Mac window: the shipped sidebar shape, with the account page as the current nav item.
     `cur` is the nav item the page belongs to. */
  function macWindow(cur, body) {
    var nav = ['nav.home', 'nav.movies', 'nav.series', 'mode.music', 'mnav.now_short']
      .map(function (k) {
        return '<span' + (k === cur ? ' class="on"' : '') + '>' + esc(t(k)) + '</span>';
      }).join('');
    return '<div class="ts-mac"><div class="ts-mside">'
      + '<span class="ts-lights"><i></i><i></i><i></i></span>'
      + '<div class="ts-mnav">' + nav + '</div>'
      + '<div class="ts-mwho"><div class="ts-av">AN</div><span>' + esc(D.ANNA) + '</span></div>'
      + '</div><div class="ts-mmain">' + body + '</div></div>';
  }

  /* The Mac's account page: the same rows, laid out for a desk. */
  function macAccount() {
    var rows = D.ACCOUNT_ROWS.map(function (r) {
      var label = r.isNew ? t(r.label) : r.label;
      return '<button class="ts-drow' + (r.isNew ? ' ts-newrow' : '') + '">'
        + ic(r.icon) + '<span>' + esc(label) + (r.sub ? '<i>' + esc(r.sub) + '</i>' : '') + '</span>'
        + ic('chev', 'chev') + '</button>';
    }).join('');
    return '<div class="ts-dacct"><h1>Account</h1>'
      + '<div class="ts-did"><div class="ts-av lg">AN</div>'
      + '<div><b>' + esc(D.ANNA) + '</b><i>Admin · ' + esc(D.HOST) + '</i></div></div>'
      + '<div class="ts-dgrp">' + rows + '</div></div>';
  }

  /* A desk-width card, for the code, confirm and outcome screens on the Mac and on wide web.
     The SAME builder bodies as the phone, in a 520 px column — which is the claim the round is
     making about these three surfaces, made in the source. */
  /* `title: false` draws the card with NO page heading. The sign-in screen carries its own
     wordmark and its own "Sign in", so a page title above it printed the words twice — which
     the first render of `web-desk-signin` shows and the markup does not. */
  function deskCard(body, title) {
    return '<div class="ts-dpane' + (title === false ? ' bare' : '') + '">'
      + (title === false ? '' : '<h1>' + esc(title || t('tvcode.row')) + '</h1>')
      + '<div class="ts-dcard">' + body + '</div></div>';
  }
  /* The phone bodies minus their own top bar, which a desk window does not have. */
  function stripBar(html) { return html.replace(/^<div class="acct-bar">[\s\S]*?<\/div>/, ''); }

  /* =============================================================================================
     SURFACE 4 — THE BROWSER PAGES THE SERVER RENDERS
     These are ONE FILE each when they ship: no hosted font, no second stylesheet, no image file.
     So they are drawn with what one page can carry — a system font stack as the fallback behind
     the one embedded face, the mark as inline SVG, and flat colour instead of the token files.
     The `.ts-page` block in the round's CSS writes its own colours for exactly that reason, and
     the report lists what the shipped page needs.
     ============================================================================================= */
  function ssoPage(p, wide) {
    var glyph = p.tone === 'ok' ? 'tick' : (p.tone === 'bad' ? 'bad' : 'warn');
    return '<div class="ts-page ' + p.tone + (wide ? ' wide' : '') + '">'
      + '<div class="ts-pinner">'
      + '<div class="ts-phead">' + mark(wide ? 54 : 44, '#7b6ef0', '#3fb6f5') + '<b>Ravilo</b></div>'
      + '<span class="ts-pglyph">' + ic(glyph, 'big') + '</span>'
      + '<h1>' + esc(t(p.key)) + '</h1>'
      + (p.body ? '<p>' + esc(t(p.body)) + '</p>' : '')
      + (p.cta ? '<button class="ts-pbtn">' + esc(t(p.cta)) + '</button>' : '')
      + (p.hand ? '<div class="ts-phand"><i></i><i></i><i></i></div>' : '')
      + '</div></div>';
  }

  /* =============================================================================================
     THE ADMIN'S SIGN-IN PAGE
     Login.kt's markup, reproduced class for class from the source, with FR-12's button added
     above the username field. The page is the OPERATOR's, so Jellyfin may appear on it and its
     existing copy is left exactly as it is. `--blur: none` is set on the frame, because
     backdrop-filter is production CSS on every admin card and breaks the screenshot render.
     ============================================================================================= */
  function adminSignin(names, boldSso) {
    /* Same weighting question as the clients, and `.btn.ghost` was the wrong answer twice over:
       in wf.css it is transparent background AND transparent border, so the second provider
       drew as a line of grey text on the card with nothing to say it was pressable. The plain
       `.btn` is the admin's outlined control and is what a secondary action uses here. */
    var btns = (names || []).map(function (n, i) {
      return '<button class="btn' + (boldSso && i === 0 ? ' primary' : '') + ' ts-adminsso">'
        + ic('globe') + '<span>Sign in with ' + esc(n) + '</span></button>';
    }).join('');
    var submitCls = 'btn' + (boldSso && names && names.length ? '' : ' primary');
    return '<div class="ts-admin">'
      + '<div class="ts-adminwrap">'
      + '<div class="ts-adminbrand">'
      + adminMark('style="width:40px;height:40px;flex:none;border-radius:12px"')
      + '<strong>Jellystructure</strong></div>'
      + '<div class="card">'
      + '<h2>' + esc(D.S['admin.signin.title'].en) + '</h2>'
      + '<p class="muted">' + D.S['admin.signin.sub'].en + '</p>'
      + (btns ? '<div class="ts-adminssorow">' + btns + '</div>'
        + '<div class="ts-or admin"><i></i><span>or</span><i></i></div>' : '')
      + '<div class="field"><label>' + esc(D.S['admin.signin.user'].en) + '</label>'
      + '<div class="input">—</div></div>'
      + '<div class="field"><label>Password</label><div class="input">—</div></div>'
      + '<button class="' + submitCls + ' ts-adminsubmit">Sign in</button>'
      + '<hr class="dash">'
      + '<div class="row center ts-conn"><span class="dot ok"></span>'
      + '<span class="muted">Connected to</span><span class="mono">jellyfin</span>'
      + '<span class="spacer"></span><span class="badge ok">online</span></div>'
      + '</div>'
      + '<div class="tiny muted ts-adminfoot">' + D.S['admin.signin.foot'].en + '</div>'
      + '</div></div>';
  }

  return {
    setLang: setLang, t: t, esc: esc, ic: ic, mark: mark, wordmark: wordmark,
    qrTile: qrTile, codeBlock: codeBlock,
    tvA: tvA, tvB: tvB, tvPassword: tvPassword, tvUnreachable: tvUnreachable, tvProfiles: tvProfiles,
    accountPage: accountPage, codeScreen: codeScreen, confirmScreen: confirmScreen,
    outcomeScreen: outcomeScreen, signinScreen: signinScreen,
    phone: phone, browser: browser, macWindow: macWindow, macAccount: macAccount,
    deskCard: deskCard, stripBar: stripBar, ssoPage: ssoPage, adminSignin: adminSignin
  };
})(window.TS);
