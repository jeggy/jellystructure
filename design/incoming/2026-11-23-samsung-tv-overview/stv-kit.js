/* stv-kit.js — the builders. Three directions, one screen each, all from window.STV.

   The one rule this file keeps: a badge, a version line and a primary button are COMPUTED from
   the card's three data fields, never written into the data. So the same `cardState()` answers
   for all three directions, and a direction cannot disagree with another about what 1.70 against
   1.71 means.

   Every SVG id in this document is minted from a counter (PROCESS.md's hidden-subtree trap:
   Chromium will not resolve a paint server defined inside a display:none subtree, and this
   document holds every frame at once). In fact this round defines no paint server at all - every
   glyph is a stroke in currentColor and every gradient is a CSS background - but the counter is
   here so that adding one cannot reintroduce the trap.
*/
(function () {
  'use strict';

  var D = window.STV;
  var LANG = 'en';
  var WIFI = true;   /* set per screen; a card may not claim a scan that never ran */
  var GLYPH = false; /* the x4/x5 option: a small TV tile at the head of every card */
  var SVG_N = 0;
  function uid(p) { SVG_N += 1; return 'stv-' + p + '-' + SVG_N; }

  function setLang(l) { LANG = l; }
  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function t(key, vars) {
    var s = D.STR[LANG][key];
    if (s === undefined) s = D.STR.en[key];
    if (s === undefined) return key;
    if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
    return s;
  }

  /* ---- the glyphs. All stroked currentColor, so each takes its control's own tint. ---------- */
  var ICON = {
    back:  '<path d="M14.6 5.4 7.6 12l7 6.6"/>',
    check: '<path d="M4.6 12.4 9.6 17.4 19.4 7"/>',
    arrow: '<path d="M12 4.6v11.2M7.3 11.1 12 15.8l4.7-4.7"/>',
    up:    '<path d="M12 19.4V8.2M7.3 12.9 12 8.2l4.7 4.7"/>',
    wifi:  '<path d="M3.4 9.2a13 13 0 0 1 17.2 0M6.6 12.8a8.4 8.4 0 0 1 10.8 0"/>'
           + '<circle cx="12" cy="17.6" r="1.4" fill="currentColor" stroke="none"/>',
    wifino: '<path d="M3.4 9.2a13 13 0 0 1 17.2 0"/><path d="M4.2 4.2 19.8 19.8"/>'
           + '<circle cx="12" cy="17.6" r="1.4" fill="currentColor" stroke="none"/>',
    batt:  '<rect x="3.2" y="7.4" width="16" height="9.2" rx="2.4"/><path d="M21.2 10.6v2.8"/>'
           + '<rect x="5.4" y="9.6" width="9.6" height="4.8" rx="1.2" fill="currentColor" stroke="none"/>',
    cell:  '<path d="M4 18.6V15M9.3 18.6v-6.4M14.7 18.6V8.4M20 18.6V4.6"/>',
    /* The TV: the same flat set-and-stand the shipped guide's Art.TV draws. */
    tv:    '<rect x="2.6" y="4.4" width="18.8" height="12.6" rx="2.2"/>'
           + '<path d="M8.6 20.2h6.8M12 17v3.2"/>',
    /* A static arc, not a spinning ring: an infinite animation breaks the screenshot render. */
    arc:   '<path d="M12 3.4a8.6 8.6 0 1 1-8.6 8.6"/>',
    chev:  '<path d="M9.4 5.4 16 12l-6.6 6.6"/>'
  };
  function ic(name, cls, sw) {
    return '<svg class="' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="' + (sw || 2.1) + '" stroke-linecap="round" stroke-linejoin="round">'
      + ICON[name] + '</svg>';
  }

  /* ---- chrome ------------------------------------------------------------------------------ */

  /* The shipped .statusbar, 34 dp. `wifi:false` swaps the Wi-Fi glyph for the struck-through one,
     so state 8's picture says "no Wi-Fi" in the one place a phone always says it, and not only in
     the sentence. */
  function sb(wifi) {
    return '<div class="stv-sb"><span>' + esc(D.CLOCK) + '</span><span class="stv-sbi">'
      + ic('cell') + ic(wifi === false ? 'wifino' : 'wifi') + ic('batt') + '</span></div>';
  }

  /* The guide's own page chrome: a back chevron in a 44 dp target. The shipped guide draws a bare
     "<" glyph at 26 sp with 10/4 dp padding; this is the same control with the stroked chevron the
     rest of the phone app uses, and a real 44 dp target. Reported as a change, small as it is. */
  function head(title) {
    return '<div class="stv-hd">'
      + '<button class="stv-bk" type="button">' + ic('back', '', 2.3) + '</button>'
      + '<h1>' + esc(title) + '</h1>'
      + '</div>';
  }

  /* ---- the derivation: one function, all three directions ----------------------------------
     This is FR-10 and FR-11 read back as code. `badge` comes from the request alone (the server);
     `onTv` from the TV alone; `kind` - which is what decides the button, the second version line
     and whether there is a button at all - from the two together. */

  function badgeOf(c) {
    if (c.req === 'ready' || c.req === 'approved') return { k: 'approved', s: t('badge.approved') };
    if (c.req === 'requested') return { k: 'waiting', s: t('badge.waiting') };
    if (c.req === 'rejected') return { k: 'rejected', s: t('badge.rejected') };
    return { k: 'notsetup', s: t('badge.notsetup') };
  }

  /* Versions compare as MAJOR and MINOR only, so v1.71 and 1.71.0 are equal (FR-11). */
  function mm(v) {
    var p = String(v).replace(/^v/, '').split('.');
    return [parseInt(p[0], 10) || 0, parseInt(p[1], 10) || 0];
  }
  function older(a, b) {
    var x = mm(a), y = mm(b);
    return x[0] < y[0] || (x[0] === y[0] && x[1] < y[1]);
  }

  function cardState(c) {
    if (c.offline) return { kind: 'offline' };
    if (c.pending) return { kind: 'pending' };
    if (c.req === null || c.req === undefined) return { kind: 'notsetup' };
    if (c.req === 'requested') return { kind: 'waiting' };
    if (c.req === 'rejected') return { kind: 'rejected' };
    if (c.req === 'approved') return { kind: 'next' };          /* no release covers it yet */
    if (c.onTv === null || c.onTv === undefined) return { kind: 'install', why: 'unknown' };
    if (c.onTv === 'none') return { kind: 'install', why: 'notinstalled' };
    if (older(c.onTv, c.cover)) return { kind: 'update' };
    return { kind: 'uptodate' };
  }

  /* What "On the TV" reads. The dash is FR-11's; `Not installed` is this round's draft for the
     404 branch FR-10 leaves without a line, and only the x2 study uses it. */
  function onTvText(c) {
    if (c.onTv === null || c.onTv === undefined) return t('ov.unknown');
    if (c.onTv === 'none') return t('ov.not_installed');
    return c.onTv;
  }
  function primaryLabel(c, st) {
    if (st.kind === 'update') return t('ov.update_to', { v: c.cover });
    if (st.kind === 'install') return t('ready.cta');
    if (st.kind === 'rejected') return t('rejected.again');
    return null;
  }

  /* The count line and Search again, item 4's quiet footer. `found` is counted from the cards, not
     stated: a picture cannot claim two TVs and show three. */
  function foot(s) {
    var found = (s.cards || []).filter(function (c) { return !c.offline; }).length;
    if (s.wifi === false) {
      /* Nothing was scanned, so there is nothing to search again. The shipped `retry` is offered
         instead - it re-opens the screen, which re-reads the Wi-Fi address. */
      return '<div class="stv-foot"><button class="stv-flink" type="button">'
        + esc(t('retry')) + '</button></div>';
    }
    var txt = found === 0 ? t('ov.found_none')
      : found === 1 ? t('ov.found_one') : t('ov.found_n', { n: found });
    return '<div class="stv-foot"><span>' + nbwifi(esc(txt)) + '</span>'
      + '<button class="stv-flink" type="button">' + esc(t('ov.search_again')) + '</button></div>';
  }

  /* The searching line, item 1. A static arc and the sentence; underneath, two skeleton cards, so
     the picture is the SLOW case a 254-host sweep actually looks like rather than a spinner alone. */
  function scanLine() {
    return '<div class="stv-scan">' + ic('arc', 'stv-arc', 2.4)
      + '<span>' + nbwifi(esc(t('ov.searching'))) + '</span></div>';
  }
  /* `one` is the server-first study: the viewer's own rows are already drawn above, so a single
     short placeholder stands for whatever else the sweep is still finding. */
  function skeletons(dir, one) {
    var sk = dir === 'b'
      ? (one ? '<div class="stv-skrow"></div>' : '<div class="stv-skrow"></div><div class="stv-skrow"></div>')
      : (one ? '<div class="stv-skcard sm"></div>' : '<div class="stv-skcard"></div><div class="stv-skcard sm"></div>');
    return '<div class="stv-sk">' + sk + '</div>';
  }

  function nowifi() {
    return '<div class="stv-notice">' + ic('wifino', 'stv-nicon', 2.1)
      + '<p>' + nbwifi(esc(t('intro.nowifi'))) + '</p></div>';
  }

  /* The order the cards are drawn in. FR-10 fixes only that the server-only rows come after the
     found ones (item 4); within the found list the data order would otherwise decide, and in the
     first render of state 7 that put a *Not set up* TV above the viewer's own approved one. The
     owner's ask is "the one TV has already been approved", so the approved TV leads, always.
     Rank: Approved/Ready, then Waiting for approval, then Not approved, then Not set up; the
     server-only rows keep that same rank among themselves and sit below every found TV. Equal
     ranks keep the server's order, so the list does not reshuffle between two scans. */
  var RANK = { ready: 0, approved: 0, requested: 1, rejected: 2 };
  function ordered(cards) {
    return (cards || []).map(function (c, i) { return { c: c, i: i }; })
      .sort(function (x, y) {
        var a = (x.c.offline ? 10 : 0) + (RANK[x.c.req] === undefined ? 3 : RANK[x.c.req]);
        var b = (y.c.offline ? 10 : 0) + (RANK[y.c.req] === undefined ? 3 : RANK[y.c.req]);
        return a - b || x.i - y.i;
      })
      .map(function (x) { return x.c; });
  }

  /* "Wi-Fi" never breaks at its hyphen. At the 200 % font setting the count line split it as
     "Wi-" / "Fi", in all three languages. In Compose this is a non-breaking hyphen in the string
     rather than a span; here the span keeps the shipped spelling intact. */
  function nbwifi(s) { return s.split('Wi-Fi').join('<span class="stv-nb">Wi-Fi</span>'); }

  function badgePill(c) {
    var b = badgeOf(c);
    return '<span class="stv-badge ' + b.k + '">' + esc(b.s) + '</span>';
  }

  /* The two labelled version lines - direction A's reading of the open decision. Label left,
     value right, the value in JetBrains Mono because a version is an id (house rule). */
  function verRowsA(c, st) {
    /* `word` drops JetBrains Mono: the mono face is for ids and versions, and *Not installed*
       set in it read as a value the TV had returned. */
    var unknown = (c.onTv === null || c.onTv === undefined);
    var rows = '<div class="stv-vrow"><span class="l">' + esc(t('ov.on_tv')) + '</span>'
      + '<span class="v' + (unknown || c.onTv === 'none' ? ' dim' : '')
      + (c.onTv === 'none' ? ' word' : '') + '">'
      + esc(onTvText(c)) + '</span></div>';
    if (st.kind === 'next') {
      rows += '<div class="stv-vrow next"><span class="l full">'
        + esc(t('ov.next_release')) + '</span></div>';
    } else {
      rows += '<div class="stv-vrow"><span class="l">' + esc(t('ov.latest')) + '</span>'
        + '<span class="v' + (st.kind === 'update' ? ' hi' : '') + '">' + esc(c.cover)
        + '</span></div>';
    }
    return '<div class="stv-vers">' + rows + '</div>';
  }

  /* Direction B's reading: one line, 1.70 -> 1.71. */
  function verLineB(c, st) {
    if (st.kind === 'next') {
      return '<div class="stv-bver next">' + esc(t('ov.next_release')) + '</div>';
    }
    if (st.kind === 'uptodate') {
      return '<div class="stv-bver"><b>' + esc(c.onTv) + '</b>'
        + '<i class="ok">' + ic('check', '', 2.6) + '</i>'
        + '<span>' + esc(t('ov.up_to_date')) + '</span></div>';
    }
    return '<div class="stv-bver"><b' + (c.onTv === 'none' ? ' class="word"' : '') + '>'
      + esc(onTvText(c)) + '</b>'
      + '<i>' + ic('chev', '', 2.2) + '</i><b class="hi">' + esc(c.cover) + '</b></div>';
  }

  /* Direction C's reading: one labelled inline pair under the headline. */
  function verLineC(c, st) {
    var on = '<span class="p"><span class="l">' + esc(t('ov.on_tv')) + '</span>'
      + '<b class="' + (c.onTv === null || c.onTv === 'none' ? 'dim ' : '')
      + (c.onTv === 'none' ? 'word' : '') + '">' + esc(onTvText(c)) + '</b></span>';
    /* No release covers the TV, so there is no Latest to pair it with - the headline carries
       that. FR-10's *On the TV* line stays: dropped with the sentence at first, which left the
       card saying nothing at all about the TV's own version. */
    if (st.kind === 'next') return '<div class="stv-cver">' + on + '</div>';
    var la = '<span class="p"><span class="l">' + esc(t('ov.latest')) + '</span>'
      + '<b' + (st.kind === 'update' ? ' class="hi"' : '') + '>' + esc(c.cover) + '</b></span>';
    return '<div class="stv-cver">' + on + la + '</div>';
  }

  function primaryBtn(label, cls) {
    return '<button class="stv-cta ' + (cls || '') + '" type="button">' + esc(label) + '</button>';
  }
  function quietLink(label) {
    return '<button class="stv-quiet" type="button">' + esc(label) + '</button>';
  }

  /* =======================================================================================
     DIRECTION A - Stack
     Cards. The approved TV's card is the big one and carries its own button. Two labelled
     version lines. The badge sits at the end of the name row, so the badges form one column
     down the right edge of the screen.
     ======================================================================================= */
  function cardA(c) {
    var tv = D.TVS[c.tv], st = cardState(c);
    var big = (st.kind === 'update' || st.kind === 'uptodate' || st.kind === 'install'
      || st.kind === 'next');
    var h = '<div class="stv-card' + (big ? ' big' : '') + ' k-' + st.kind + '">';
    h += '<div class="stv-nm' + (GLYPH ? ' g' : '') + '">'
      + (GLYPH ? '<span class="stv-tile">' + ic('tv', '', 1.9) + '</span>' : '')
      + '<span class="t">'
      + '<b>' + esc(tv.name) + '</b><em>' + esc(tv.model) + '</em></span>'
      + badgePill(c) + '</div>';

    if (st.kind === 'offline') {
      if (WIFI) h += '<div class="stv-off"><span>' + nbwifi(esc(t('ov.not_found')))
        + '</span></div>';
    } else if (st.kind === 'pending') {
      h += '<div class="stv-vers"><div class="stv-vrow"><span class="l">'
        + esc(t('ov.on_tv')) + '</span><span class="sk"></span></div>'
        + (c.cover ? '<div class="stv-vrow"><span class="l">' + esc(t('ov.latest'))
          + '</span><span class="v">' + esc(c.cover) + '</span></div>' : '') + '</div>';
    } else if (big) {
      h += verRowsA(c, st);
      if (st.kind === 'uptodate') {
        /* One line, not two: the state on the left and the only thing left to do on the right.
           Drawn stacked first, which left 40 dp of air between a green sentence and a blue link
           that read as unrelated. */
        h += '<div class="stv-okline">' + ic('check', '', 2.6)
          + '<span>' + esc(t('ov.up_to_date')) + '</span>'
          + quietLink(t('ov.reinstall')) + '</div>';
      } else if (st.kind !== 'next') {
        h += primaryBtn(primaryLabel(c, st));
      }
    } else if (st.kind === 'notsetup') {
      h += quietLink(t('ov.set_up'));
    } else if (st.kind === 'rejected') {
      if (c.note) h += '<p class="stv-note">' + esc(c.note) + '</p>';
      h += quietLink(t('rejected.again'));
    }
    return h + '</div>';
  }

  /* =======================================================================================
     DIRECTION B - Ledger
     No cards. Full-bleed rows on hairlines, every TV the same weight. The version pair is one
     line with an arrow. The one primary button is pinned to the bottom of the screen, and
     carries the name of the TV it acts on above it - without that line nothing on the screen
     says which row the button belongs to, which is this direction's whole cost.
     ======================================================================================= */
  function rowB(c) {
    var tv = D.TVS[c.tv], st = cardState(c);
    var h = '<div class="stv-brow k-' + st.kind + '">';
    h += '<span class="stv-bt"><b>' + esc(tv.name) + '</b><em>' + esc(tv.model) + '</em>';
    if (st.kind === 'offline') {
      if (WIFI) h += '<span class="stv-boff">' + nbwifi(esc(t('ov.not_found'))) + '</span>';
    } else if (st.kind === 'pending') {
      h += '<span class="stv-bsk"></span>';
    } else if (st.kind === 'update' || st.kind === 'uptodate' || st.kind === 'install'
      || st.kind === 'next') {
      h += verLineB(c, st);
    } else if (st.kind === 'rejected' && c.note) {
      h += '<span class="stv-bnote">' + esc(c.note) + '</span>';
    }
    h += '</span>' + badgePill(c);
    if (st.kind === 'notsetup') h += '<span class="stv-bchev">' + ic('chev', '', 2.2) + '</span>';
    if (st.kind === 'uptodate') h += quietLink(t('ov.reinstall'));
    return h + '</div>';
  }

  function dockB(s) {
    var act = (s.cards || []).map(function (c) { return { c: c, st: cardState(c) }; })
      .filter(function (x) { return x.st.kind === 'update' || x.st.kind === 'install'; })[0];
    /* No dock at all when nothing is to be done. Drawn with one first, and the up-to-date screen
       then said *Up to date* twice, 680 dp apart — in the row and again in the dock. A pinned bar
       whose only content repeats a line already on screen is a bar with nothing in it. The
       Reinstall link stays in the row, which is where the TV it reinstalls is named. */
    if (!act) return '';
    return '<div class="stv-dock"><span class="stv-dfor">' + esc(D.TVS[act.c.tv].name)
      + '</span>' + primaryBtn(primaryLabel(act.c, act.st)) + '</div>';
  }

  /* =======================================================================================
     DIRECTION C - Signal
     The state is the headline and the TV is the kicker above it: it answers "what is the state
     of everything" before it answers "which TV". Versions are one labelled inline pair. The
     button is inside the block. Every other TV is a compact row under a section head.
     ======================================================================================= */
  var HEAD_KEY = { update: 'head.update', uptodate: 'head.uptodate', install: 'head.install',
    next: 'head.next', offline: 'head.offline' };

  function blockC(c) {
    var tv = D.TVS[c.tv], st = cardState(c);
    var h = '<div class="stv-sig k-' + st.kind + '"><i class="rail"></i>'
      + '<div class="stv-sigb">'
      /* The badge stays on the lead block too. Drawn without one first, on the reasoning that
         the headline says more than *Approved* does — but FR-10 puts a badge on every card, and
         a screen where one TV has a pill and the other does not reads as an omission. */
      + '<div class="stv-kick">' + ic('tv', '', 1.9) + '<span>' + esc(tv.name) + '</span>'
      + '<em>' + esc(tv.model) + '</em>' + badgePill(c) + '</div>'
      + '<h2>' + nbwifi(esc(t(HEAD_KEY[st.kind] || 'head.install'))) + '</h2>';
    if (st.kind === 'next') {
      /* FR-10's own sentence, *Comes with the next Ravilo release*, is dropped here: C's headline
         already says it, and the two printed one under the other. That is a cost of this
         direction, not a tidy-up — it retires a string the spec writes. The *On the TV* line
         stays. */
      h += verLineC(c, st);
    } else if (st.kind === 'offline') {
      /* No body line: the headline *Not on this Wi-Fi* already is FR-10's sentence, and the two
         printed one under the other in the first render. */
    } else {
      h += verLineC(c, st);
      if (st.kind === 'uptodate') h += quietLink(t('ov.reinstall'));
      else h += primaryBtn(primaryLabel(c, st));
    }
    return h + '</div></div>';
  }

  function rowC(c) {
    var tv = D.TVS[c.tv], st = cardState(c);
    var h = '<div class="stv-crow k-' + st.kind + '">'
      + '<span class="stv-ct"><b>' + esc(tv.name) + '</b><em>' + esc(tv.model) + '</em>';
    if (st.kind === 'offline' && WIFI) {
      h += '<span class="stv-boff">' + nbwifi(esc(t('ov.not_found'))) + '</span>';
    }
    if (st.kind === 'rejected' && c.note) h += '<span class="stv-bnote">' + esc(c.note) + '</span>';
    h += '</span>' + badgePill(c);
    if (st.kind === 'notsetup') {
      h += '<span class="stv-bchev">' + ic('chev', '', 2.2) + '</span>';
    } else if (st.kind === 'rejected') {
      h += '<span class="stv-bchev">' + ic('chev', '', 2.2) + '</span>';
    }
    return h + '</div>';
  }

  /* ---- the screen -------------------------------------------------------------------------- */

  /* Which cards a direction treats as its lead. For A and B that is nothing special - every card
     is in the one list. For C the lead is the first card whose state has a headline. */
  /* A TV that ANSWERED leads over one that did not. Drawn the other way first — the first
     render of state 7 gave the hero block to the TV that is not on the network and a one-line row
     to the one that is. An offline row leads only when nothing found has a state worth a
     headline. */
  function leadIndexC(cards) {
    var HEADED = ['update', 'uptodate', 'install', 'next'];
    for (var i = 0; i < cards.length; i += 1) {
      if (HEADED.indexOf(cardState(cards[i]).kind) >= 0) return i;
    }
    for (var j = 0; j < cards.length; j += 1) {
      if (cardState(cards[j]).kind === 'offline') return j;
    }
    return -1;
  }

  function screen(dir, s) {
    var body = '', cards = ordered(s.cards);
    /* With no Wi-Fi there is no scan, so no card may say *Not found on this Wi-Fi* — the notice
       at the top already says why nothing was found, and the two sentences contradicted each
       other in the first render of state 8. The card is then name, model and badge alone. */
    WIFI = s.wifi !== false;
    GLYPH = s.glyph === true;
    if (s.wifi === false) body += nowifi();
    if (s.scanning) body += scanLine();
    if (s.scanning && !s.serverFirst) body += skeletons(dir, false);

    if (dir === 'a') {
      body += '<div class="stv-list">' + cards.map(cardA).join('') + '</div>';
    } else if (dir === 'b') {
      body += '<div class="stv-blist">' + cards.map(rowB).join('') + '</div>';
    } else {
      /* With no Wi-Fi nothing is known about any TV, so there is no state to headline: every
         card falls back to a plain row. Drawn with the headline left in, and the lead block then
         said *Not on this Wi-Fi* directly under a notice saying the phone is not ON a Wi-Fi. */
      var li = WIFI ? leadIndexC(cards) : -1;
      if (li >= 0) body += blockC(cards[li]);
      var rest = cards.filter(function (_, i) { return i !== li; });
      if (rest.length) {
        body += '<div class="stv-chead">' + nbwifi(esc(t('ov.others'))) + '</div>'
          + '<div class="stv-clist">' + rest.map(rowC).join('') + '</div>';
      }
    }
    if (s.scanning && s.serverFirst) body += skeletons(dir, true);
    if (!s.scanning) body += foot(s);

    return sb(s.wifi) + head(t('ov.title'))
      + '<div class="stv-scroll">' + body + '</div>'
      + (dir === 'b' ? dockB(s) : '');
  }

  window.STVKit = {
    setLang: setLang, t: t, esc: esc, uid: uid, ic: ic,
    cardState: cardState, badgeOf: badgeOf, older: older, screen: screen
  };
})();
