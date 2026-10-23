/* or-kit.js — the markup builders for the one-remote round.

   ONE builder per thing. The phone, the web and the Mac call the SAME `remote()` with the same
   item and the same place object and differ only in the wrapper class they sit in — so a
   difference between two of these pictures is a difference in CSS and never a second opinion
   about what the remote says. That is the round's whole argument made structural: if A1 and A2
   came out of two builders, "they are the same screen" would be a claim; out of one, it is a
   fact you can read in the source.

   `reach` (cast link or server) is deliberately never read here. Grep this file for it: no hit.

   Classes are prefixed `or-`. Tokens, fonts and colours come from the SHIPPED stylesheets that
   frames.html links; nothing is copied.
*/
window.ORKit = (function (OR) {
  'use strict';

  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const fill = (s, v) => String(s).replace(/\{(\w+)\}/g, (_, k) => (k in v ? v[k] : '{' + k + '}'));
  let L = OR.I18N.en;
  const setLang = c => { L = OR.I18N[c] || OR.I18N.en; };
  const t = (k, v) => (v ? fill(L[k], v) : L[k]);

  /* ---- glyphs. Minted ids are not needed: none of these defines a paint server. ---- */
  const sv = (p, w) => '<svg viewBox="0 0 24 24"' + (w ? ' style="stroke-width:' + w + '"' : '') + '>' + p + '</svg>';
  const G = {
    tv: sv('<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8"/>'),
    spk: sv('<rect x="6" y="2" width="12" height="20" rx="3"/><circle cx="12" cy="15" r="3"/><circle cx="12" cy="7" r="1.2"/>'),
    phone: sv('<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18.5h4"/>'),
    laptop: sv('<rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 19h20"/>'),
    move: sv('<path d="M4 12h13M13 7l5 5-5 5"/>'),
    down: sv('<path d="M6 9l6 6 6-6"/>'),
    play: '<svg viewBox="0 0 24 24" class="fil"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" class="fil"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/></svg>',
    back10: sv('<path d="M11 5.2V2.4L6 6.3l5 3.9V7.4a5 5 0 1 1-5 5"/>'),
    fwd30: sv('<path d="M13 5.2V2.4L18 6.3l-5 3.9V7.4a5 5 0 1 0 5 5"/>'),
    prev: sv('<path d="M18 6l-9 6 9 6zM7 6v12"/>'),
    next: sv('<path d="M6 6l9 6-9 6zM17 6v12"/>'),
    shuffle: sv('<path d="M16 4h4v4M20 4l-6.5 6.5M4 20l6-6M16 20h4v-4M4 4l4.5 4.5"/>'),
    repeat: sv('<path d="M17 2l3 3-3 3M20 5H7a3 3 0 0 0-3 3v2M7 22l-3-3 3-3M4 19h13a3 3 0 0 0 3-3v-2"/>'),
    cc: sv('<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M7 14h4M14 14h3"/>'),
    ep: sv('<path d="M5 6l9 6-9 6z"/><path d="M19 6v12"/>'),
    stop: sv('<rect x="6" y="6" width="12" height="12" rx="2"/>'),
    vol: sv('<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 9.5a3.5 3.5 0 0 1 0 5M19.5 7a7 7 0 0 1 0 10"/>'),
    mute: sv('<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 9.5l5 5M22 9.5l-5 5"/>'),
    list: sv('<path d="M4 7h16M4 12h16M4 17h10"/>'),
    lyr: sv('<path d="M9 18V5l10-2v13"/><circle cx="7" cy="18" r="2.4"/><circle cx="17" cy="16" r="2.4"/>'),
    warn: sv('<path d="M12 4l9 16H3z"/><path d="M12 10v4M12 17.2v.2"/>'),
    ck: sv('<path d="M5 12.5l4.5 4.5L19 7.5"/>'),
    x: sv('<path d="M6 6l12 12M18 6L6 18"/>'),
    person: sv('<circle cx="12" cy="8" r="3.6"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>'),
    sp: '<span class="or-eq"><i></i><i></i><i></i></span>',
  };
  const pIcon = p => G[p.icon] || G.spk;

  /* ---- places ------------------------------------------------------------------------------
     A remote is pointed at ONE place-set. `line` is what the viewer reads; it is the shipped
     "Stue + Gæsteværelse" join and it never says how the place is reached. */
  function placeSet(ids) {
    const ps = ids.map(i => OR.PLACES[i]);
    return { ids, places: ps, line: ps.map(p => p.name).join(' + '), icon: ps[0].icon, multi: ps.length > 1 };
  }
  const P = {
    stuetv: placeSet(['stuetv']), sovetv: placeSet(['sovetv']),
    speakers: placeSet(['stue', 'gaeste']), threeRooms: placeSet(['stue', 'gaeste', 'kontor']),
    pixelPlace: placeSet(['pixel']),
  };

  /* ---- a slider drawn rather than an <input type=range>, so the picture carries the exact
     track height, knob size and fill the Compose port has to hit. ---- */
  function slider(pct, opts) {
    const o = opts || {};
    return '<span class="or-sl' + (o.dis ? ' dis' : '') + '">'
      + '<i class="tk"></i><i class="dn" style="width:' + pct + '%"></i>'
      + (o.dis ? '' : '<i class="kb" style="left:' + pct + '%"></i>') + '</span>';
  }

  /* ---- the volume block ----------------------------------------------------------------------
     Decision 1's LEAN: a film on a TV gets a slider, exactly as speakers do. One place → one row.
     Several rooms → a master row labelled "Volume" and one room row each, every room with its own
     mute. A room that does not report shows "—", its slider is disabled, and the reason is a line
     under it — the only place in the remote where a control is drawn and disabled rather than
     absent, because the place EXISTS and simply cannot answer. */
  function volume(set, vols, opt) {
    const o = opt || {};
    const row = (label, pct, cls, muteGlyph) =>
      '<div class="or-vrow' + (cls || '') + '">'
      + '<button class="or-vb" aria-label="' + esc(label) + '">' + (muteGlyph || G.vol) + '</button>'
      + '<span class="or-vn">' + esc(label) + '</span>'
      + slider(pct, { dis: cls && cls.indexOf('dis') >= 0 })
      + '<span class="or-vv">' + (pct === null ? '—' : pct + ' %') + '</span></div>';

    /* A greyed transport means the place cannot be reached, and a slider it cannot reach is
       exactly as inert as a play button it cannot reach. The block greys with it. */
    const g = o.greyed ? ' greyed' : '';
    if (!set.multi) {
      const p = set.places[0];
      if (!p.reports) return '<div class="or-vol' + g + '">' + row(t('volume'), null, ' dis')
        + '<p class="or-vwhy">' + esc(t('no_volume', { place: p.name })) + '</p></div>';
      return '<div class="or-vol' + g + '">' + row(t('volume'), vols.master) + '</div>';
    }
    let h = '<div class="or-vol' + g + '">' + row(t('volume'), vols.master, ' mst');
    set.places.forEach(p => {
      const v = vols[p.id];
      h += (p.reports && v != null)
        ? row(p.name, v, ' rm')
        : row(p.name, null, ' rm dis') + '<p class="or-vwhy">' + esc(t('no_volume', { place: p.name })) + '</p>';
    });
    return h + '</div>' + (o.panel ? '' : '');
  }

  /* ---- the state line under the title --------------------------------------------------------
     ONE function, so "what is it doing" is answered the same way on every surface and in every
     state. Returns {text, tone} — tone drives colour only, never meaning on its own. */
  function stateLine(st, ctx) {
    const place = ctx.set.line, who = ctx.who;
    switch (st) {
      /* Someone ELSE'S playback says whose, whether or not you may control it — the place is
         already named in the pill above, and "who" is the thing that changes what this screen
         means. Yours says where, because there is no other answer worth the line. */
      case 'playing': return who ? { text: t(ctx.item.kind === 'music' ? 'listening' : 'watching', { who }), tone: '' }
        : { text: t('playing_on', { place }), tone: '' };
      case 'paused': return who ? { text: t(ctx.item.kind === 'music' ? 'listening' : 'watching', { who }), tone: '' }
        : { text: t('paused_on', { place }), tone: '' };
      case 'buffering': return { text: t('starting_on', { place }), tone: '' };
      case 'moving': return { text: t('moving_to', { place: ctx.toPlace }), tone: 'busy' };
      case 'movefail': return { text: t('move_failed', { place: ctx.toPlace }), tone: 'bad' };
      case 'lost': return { text: t('lost', { place }), tone: 'bad' };
      case 'offline': return { text: t('offline', { place, t: OR.hms(ctx.item.pos) }), tone: 'bad' };
      case 'stopped': return { text: t('stopped_by', { who }), tone: 'off' };
      case 'ended': return { text: t('ended'), tone: 'off' };
      case 'other': return { text: t(ctx.item.kind === 'music' ? 'listening' : 'watching', { who }), tone: '' };
      default: return { text: t('playing_on', { place }), tone: '' };
    }
  }

  /* ---- the art card. 16:9 for a film or an episode, square for music or a book, always a card
     on solid ground, and the wordmark tile when there is no picture. No ink ever sits on it. ---- */
  function artCard(item, opt) {
    const o = opt || {};
    const sq = item.kind === 'music' || item.kind === 'book';
    const cls = 'or-art' + (sq ? ' sq' : ' w') + (o.dim ? ' dim' : '') + (o.cls ? ' ' + o.cls : '');
    const inner = o.wordmark
      ? '<span class="or-wm">Ravilo</span>'
      : '';
    const spin = o.spinner ? '<span class="or-ovl"><i class="or-spin"></i></span>' : '';
    return '<div class="' + cls + '" style="background-image:' + OR.art(item.key) + '">' + inner + spin + '</div>';
  }

  /* ---- the header: ONE control, and it names the place. Decision 2's lean. ------------------- */
  function header(set, opt) {
    const o = opt || {};
    const dis = o.readonly ? ' disabled' : '';
    return '<div class="or-top">'
      + '<button class="or-ic" aria-label="Close">' + G.down + '</button>'
      + '<button class="or-place' + (o.readonly ? ' ro' : '') + '"' + dis + '>'
      + pIcon(set.places[0]) + '<span>' + esc(set.line) + '</span>'
      + (o.readonly ? '' : G.move) + '</button>'
      + '<span class="or-ic ghost"></span></div>';
  }

  /* ---- the seek bar ---- */
  function seek(item, opt) {
    const o = opt || {};
    const pct = (item.pos / item.dur * 100);
    return '<div class="or-seek' + (o.dis ? ' dis' : '') + '">'
      + slider(pct.toFixed(2), { dis: o.dis })
      + '<div class="or-tm"><span>' + OR.hms(item.pos) + '</span>'
      + '<span>−' + OR.hms(item.dur - item.pos) + '</span></div></div>';
  }

  /* ---- the transport. A film or an episode gets ±skip; music gets shuffle/prev/next/repeat.
     THAT is direction A: the kind picks the controls, never how the place is reached. ---- */
  function transport(item, st, opt) {
    const o = opt || {};
    const greyed = o.greyed ? ' greyed' : '';
    const mid = st === 'buffering'
      ? '<span class="or-pp busy"><i class="or-spin"></i></span>'
      : '<button class="or-pp">' + (st === 'playing' ? G.pause : G.play) + '</button>';
    if (item.kind === 'music' || item.kind === 'book') {
      return '<div class="or-tr' + greyed + '">'
        + '<button class="or-tb sm">' + G.shuffle + '</button>'
        + '<button class="or-tb">' + G.prev + '</button>' + mid
        + '<button class="or-tb">' + G.next + '</button>'
        + '<button class="or-tb sm">' + G.repeat + '</button></div>';
    }
    return '<div class="or-tr' + greyed + '">'
      + '<button class="or-tb sk">' + G.back10 + '<b>10 s</b></button>' + mid
      + '<button class="or-tb sk">' + G.fwd30 + '<b>30 s</b></button></div>';
  }

  /* ---- the footer. Three slots at most; an action a place cannot do is ABSENT. ---- */
  function footer(item, opt) {
    const o = opt || {};
    const b = (cls, g, label) => '<button class="or-fb' + (cls ? ' ' + cls : '') + '">' + g + '<span>' + esc(label) + '</span></button>';
    let h = '<div class="or-foot">';
    if (item.kind === 'music' || item.kind === 'book') {
      /* On a surface wide enough to draw the queue inline, the button that opens it is noise —
         the Mac showed "Up next" twice, once as a button and once as the list under it. */
      if (!o.queueInline) h += b('', G.list, t('up_next'));
      h += b('', G.lyr, t('lyrics'));
    } else {
      h += b('', G.cc, t('subs_audio'));
      if (item.kind === 'episode') h += b('', G.ep, t('next_episode'));
    }
    if (!o.readonly) h += b('stop', G.stop, t('stop'));
    return h + '</div>';
  }

  /* ---- a banner: the ONE place an error or an in-flight move is stated. --------------------- */
  function banner(kind, text, action) {
    return '<div class="or-ban ' + kind + '">' + (kind === 'bad' ? G.warn : '<i class="or-spin sm"></i>')
      + '<span>' + esc(text) + '</span>'
      + (action ? '<button class="or-ba">' + esc(action) + '</button>' : '') + '</div>';
  }

  /* ---- next-up, mirrored from the place. The place owns the countdown; the remote shows it. -- */
  function nextUp(item) {
    return '<div class="or-nx"><div class="or-nxl">'
      + '<b>' + esc(t('play_in', { n: item.next.inSec })) + '</b>'
      + '<span>' + esc(item.next.code + ' · ' + item.next.title) + '</span></div>'
      + '<button class="or-nxb">' + esc(t('watch_credits')) + '</button></div>';
  }

  /* =============================================================================================
     THE ONE REMOTE — direction A. Called identically for a cast and for a server place.
     ============================================================================================= */
  function remote(cfg) {
    const item = cfg.item, set = cfg.set, st = cfg.state || 'playing';
    const ro = st === 'other' || st === 'stopped' || st === 'ended';
    /* GREYED means the place cannot be reached right now. A move that failed did not break the
       place it is still playing on — the song carries on in Stue + Gæsteværelse and every control
       still works. Greying it there said the opposite of what happened. */
    const greyed = st === 'lost' || st === 'offline';
    const sl = stateLine(st, { set, who: cfg.who, item, toPlace: cfg.toPlace });

    let h = '<div class="or-rem">' + header(set, { readonly: st === 'other' });

    h += artCard(item, {
      dim: st === 'paused' || st === 'offline' || ro,
      wordmark: !!cfg.wordmark,
      /* No spinner on the art: the transport already stands one in for ⏯, which is the shipped
         rule, and two spinners for one wait is two claims about one fact. */
    });

    /* A banner states the connection event in full, so the line under the title must NOT repeat
       it. One sentence, one place: these three states have a banner and no state line, and every
       other state has a state line and no banner. */
    const banners = (st === 'lost' || st === 'movefail' || st === 'moving');
    h += '<div class="or-meta">'
      + (item.kicker ? '<div class="k">' + esc(item.kicker) + '</div>' : '')
      + '<h3>' + esc(item.title) + '</h3>'
      + (item.kind === 'music'
        ? '<div class="alb">' + esc(item.album + ' · ' + t('song_of', { i: item.index, n: item.of })) + '</div>'
        : '')
      + (banners ? '' : '<div class="st' + (sl.tone ? ' ' + sl.tone : '') + '">'
        + (cfg.who && st !== 'stopped' ? G.person
          : (st === 'playing' || st === 'nextup') ? G.sp : '')
        + esc(sl.text) + '</div>')
      + '</div>';

    if (st === 'nextup') h += nextUp(item);
    if (st === 'moving') h += banner('busy', t('moving_to', { place: cfg.toPlace }));
    if (st === 'movefail') h += banner('bad', t('move_failed', { place: cfg.toPlace }), t('try_again'));
    if (st === 'lost') h += banner('bad', t('lost', { place: set.line }), t('try_again'));

    if (st === 'ended') {
      /* Two actions, and the second is NOT "Up next" — that word already means the queue in the
         music footer, and a film with nothing after it has no next. The place is idle; the only
         other thing available is to leave. */
      h += '<div class="or-end"><button class="or-eb">' + esc(t('watch_again')) + '</button>'
        + '<button class="or-eb">' + esc(t('done')) + '</button></div>';
    } else {
      h += seek(item, { dis: greyed || ro });
      if (ro) {
        /* Decision 3's LEAN: the SAME remote, the transport absent, and one line naming who —
           which the state line under the title already is. This card says the OTHER thing, the
           one the viewer actually needs: why there is nothing to press. Shipped copy. */
        h += '<div class="or-ro">' + G.person + '<span>'
          + esc(st === 'other'
            ? 'You can see what ' + cfg.who + ' is playing. Controlling each other’s is off for this household.'
            : st === 'stopped' ? 'This stopped on ' + set.line + '.'
              : 'This finished on ' + set.line + '.') + '</span></div>';
      } else {
        /* Moving, a failed move and lost contact are not pauses: the LAST state the place
           reported still stands, so ⏯ keeps its glyph. Drawing ▶ there told the viewer the
           playback had stopped, which is a thing the frontend invented — the one thing
           invariant 4 forbids. */
        const glyphState = (st === 'nextup' || st === 'moving' || st === 'movefail' || st === 'lost')
          ? 'playing' : st;
        h += transport(item, glyphState, { greyed });
        h += volume(set, cfg.vols || { master: OR.VOL.master }, { greyed });
      }
    }

    if (cfg.confirm) {
      h += '<div class="or-cf"><p>' + esc(t('stop_q', { who: cfg.who, title: item.title })) + '</p>'
        + '<div class="bs"><button class="go">' + esc(t('stop')) + '</button>'
        + '<button>' + esc(t('cancel')) + '</button></div></div>';
    }

    if (!ro || cfg.canStopOther) h += footer(item, { readonly: ro && !cfg.canStopOther });
    else h += '<div class="or-foot"></div>';

    return h + '</div>';
  }

  /* =============================================================================================
     DIRECTION B — today's session remote, extended. One scrolling page for every kind: the place
     line, a seek bar, shuffle·prev·⏯·next·repeat for everything, the volume block, the chips, and
     the rows. What it GAINS is ±skip, the next-up countdown and the full tracks sheet.
     ============================================================================================= */
  function remoteB(cfg) {
    const item = cfg.item, set = cfg.set, st = cfg.state || 'playing';
    const row = (g, nm, sub, cls) => '<button class="or-brow' + (cls ? ' ' + cls : '') + '">'
      + '<span class="ic">' + g + '</span><span class="tx"><span class="nm">' + esc(nm) + '</span>'
      + (sub ? '<span class="sub">' + esc(sub) + '</span>' : '') + '</span></button>';

    let h = '<div class="or-remb"><div class="or-bsh"><h4>' + esc(t('playing_on', { place: set.line }))
      + '</h4><button class="or-ic">' + G.x + '</button></div><div class="or-bscroll">';
    h += '<div class="or-bart" style="background-image:' + OR.art(item.key) + '"'
      + (item.kind === 'music' ? '' : ' data-w="1"') + '></div>';
    h += '<div class="or-btt"><b>' + esc(item.title) + '</b><span>' + esc(item.sub) + '</span></div>';
    h += '<button class="or-place">' + pIcon(set.places[0]) + '<span>' + esc(set.line) + '</span>' + G.move + '</button>';
    h += seek(item, {});
    /* B's transport is the same five controls for every kind — that is the direction's cost. */
    h += '<div class="or-tr b">'
      + '<button class="or-tb sm">' + G.shuffle + '</button>'
      + '<button class="or-tb">' + G.prev + '</button>'
      + '<button class="or-pp">' + (st === 'playing' ? G.pause : G.play) + '</button>'
      + '<button class="or-tb">' + G.next + '</button>'
      + '<button class="or-tb sm">' + G.repeat + '</button></div>';
    /* …plus the ±skip it has to gain, which lands as a SECOND transport row. */
    h += '<div class="or-tr b skiprow"><button class="or-tb sk">' + G.back10 + '<b>10 s</b></button>'
      + '<button class="or-tb sk">' + G.fwd30 + '<b>30 s</b></button></div>';
    h += volume(set, cfg.vols || { master: OR.VOL.master }, {});
    h += '<div class="or-chips"><span class="or-chip">' + esc(t('audio')) + ' · Dansk 5.1</span>'
      + '<span class="or-chip">' + esc(t('subtitles')) + ' · Dansk</span>'
      + (item.kind === 'music' ? '' : '<span class="or-chip">' + esc(t('subtitle_size')) + ' · M</span>') + '</div>';
    if (item.kind === 'episode') h += row(G.ep, t('next_episode'), item.next ? item.next.code + ' · ' + item.next.title : '');
    h += row(G.move, t('move_to'), '');
    h += row(G.phone, t('play_here'), t('this_phone'));
    h += row(G.stop, t('stop'), set.line, 'stop');
    if (item.kind === 'music') {
      h += '<div class="or-bsec">' + esc(t('up_next')) + '</div>';
      item.upNext.forEach(s => { h += row('<b class="or-n">' + s.n + '</b>', s.title, OR.mmss(s.len)); });
    }
    return h + '</div></div>';
  }

  /* =============================================================================================
     SHEETS
     ============================================================================================= */

  /* The tracks sheet: the SAME component the local player opens. One line differs. */
  function tracksSheet(item, set) {
    /* The shipped flag asset set, linked not copied: design/flags.css + design/flags/4x3/*.svg. */
    const flag = f => f ? '<span class="fi fi-' + f + '"></span>' : '<span class="or-noflag"></span>';
    const line = (o, on) => '<button class="or-trk' + (on ? ' on' : '') + '">' + flag(o.flag)
      /* "Off" is a UI word, not a track name, so it translates with the interface. Every other
         label in this list is the file's own and never does. */
      + '<span class="nm">' + esc(o.label === 'Off' ? t('off') : o.label) + '</span>'
      + (o.ch ? '<span class="ch">' + esc(o.ch) + '</span>' : '')
      + '<span class="rt">' + (on ? G.ck : '') + '</span></button>';
    let h = '<div class="or-scrim"></div><div class="or-sheet"><div class="or-sh"><h4>' + esc(t('subs_audio')) + '</h4>'
      + '<button class="or-ic">' + G.x + '</button></div>'
      + '<p class="or-applies">' + esc(t('applies_on', { place: set.line })) + '</p>';
    h += '<div class="or-ssec">' + esc(t('audio')) + '</div>';
    item.audio.forEach(a => { h += line(a, a.on); });
    h += '<div class="or-ssec">' + esc(t('subtitles')) + '</div>';
    item.subs.forEach(s => { h += line(s, s.on); });
    h += '<div class="or-ssec">' + esc(t('subtitle_size')) + '</div>'
      + '<div class="or-seg"><button>' + t('size_s') + '</button><button class="on">' + t('size_m')
      + '</button><button>' + t('size_l') + '</button></div>';
    return h + '</div>';
  }

  /* Move to… — opened from the place line, with Play here inside it. */
  function moveSheet(cur, item) {
    const ids = item.kind === 'music' ? ['stue', 'gaeste', 'kontor'] : ['stuetv', 'sovetv'];
    let h = '<div class="or-scrim"></div><div class="or-sheet"><div class="or-sh"><h4>' + esc(t('move_to')) + '</h4>'
      + '<button class="or-ic">' + G.x + '</button></div>'
      + '<p class="or-applies">One place. ' + (item.kind === 'music'
        ? 'The song carries on where it is.' : 'It picks up 2 seconds back.') + '</p>';
    ids.forEach(id => {
      const p = OR.PLACES[id], on = cur.ids.indexOf(id) >= 0;
      const busy = id === 'sovetv' && cur.ids.indexOf('sovetv') < 0;
      h += '<button class="or-mrow' + (on ? ' on' : '') + (busy ? ' dis' : '') + '">'
        + '<span class="ic">' + pIcon(p) + '</span><span class="tx"><span class="nm">' + esc(p.name) + '</span>'
        + '<span class="sub">' + esc(on ? t('playing_here') : busy ? t('busy_with', { who: OR.OTHER.name }) : t('ready'))
        + '</span></span><span class="rt">' + (on ? G.ck : '') + '</span></button>';
    });
    h += '<div class="or-sdiv"></div>';
    h += '<button class="or-mrow"><span class="ic">' + G.phone + '</span><span class="tx">'
      + '<span class="nm">' + esc(t('play_here')) + '</span><span class="sub">' + esc(t('this_phone'))
      + '</span></span><span class="rt"></span></button>';
    return h + '</div>';
  }

  /* =============================================================================================
     THE ONE MINI BAR. Today there are two. This is one component: art, title, the place line, a
     +N chip when something else is playing too, and ⏯. Nothing in it says how the place is
     reached — which is why the cast bar and the elsewhere bar stop being two things.
     ============================================================================================= */
  function miniBar(item, set, opt) {
    const o = opt || {};
    const pct = (item.pos / item.dur * 100).toFixed(2);
    return '<div class="or-mini"><i class="pg" style="width:' + pct + '%"></i>'
      + '<span class="th' + (item.kind === 'music' ? ' sq' : '') + '" style="background-image:' + OR.art(item.key) + '"></span>'
      + '<span class="tx"><span class="n">' + esc(item.title) + '</span>'
      + '<span class="d">' + pIcon(set.places[0]) + esc(set.line) + '</span></span>'
      + (o.more ? '<button class="n2" aria-label="' + esc(t('more_playing', { n: o.more })) + '">+' + o.more + '</button>' : '')
      + '<button class="pp">' + (o.paused ? G.play : G.pause) + '</button></div>';
  }

  /* =============================================================================================
     WEB. The same remote, in a browser. The web has NO Chromecast: its places are Ravilo apps
     reached through the server, so Stue TV never appears here — Soveværelse TV and the Pixel do.
     ============================================================================================= */
  function webRemote(cfg) {
    return '<div class="or-web"><div class="or-wchrome"><span class="or-wdots"><i></i><i></i><i></i></span>'
      + '<span class="or-wurl">ravilo.example.net</span></div>'
      + '<div class="or-wbody">' + remote(cfg) + '</div></div>';
  }

  /* =============================================================================================
     MAC. The desktop Playing page IS the remote — the same blocks, laid out in two columns, with
     the capsule bar along the bottom. The volume popover hangs off the capsule's volume button.
     ============================================================================================= */
  function macPlaying(cfg) {
    const item = cfg.item, set = cfg.set, st = cfg.state || 'playing';
    const sl = stateLine(st, { set, who: cfg.who, item });
    let right = '<div class="or-mright">'
      + '<div class="or-mplace"><button class="or-place">' + pIcon(set.places[0])
      + '<span>' + esc(set.line) + '</span>' + G.move + '</button></div>'
      + '<div class="or-mmeta">' + (item.kicker ? '<div class="k">' + esc(item.kicker) + '</div>' : '')
      + '<h2>' + esc(item.title) + '</h2>'
      + (item.kind === 'music'
        ? '<div class="alb">' + esc(item.album + ' · ' + t('song_of', { i: item.index, n: item.of })) + '</div>'
        : '')
      + '<div class="st">' + (st === 'playing' ? G.sp : '') + esc(sl.text) + '</div></div>'
      + seek(item, {}) + transport(item, st, {}) + volume(set, cfg.vols || { master: OR.VOL.master }, {})
      + footer(item, { queueInline: item.kind === 'music' });
    if (item.kind === 'music') {
      right += '<div class="or-msec">' + esc(t('up_next')) + '</div><div class="or-mq">'
        + item.upNext.map(s => '<div class="or-mqr"><b>' + s.n + '</b><span>' + esc(s.title)
          + '</span><i>' + OR.mmss(s.len) + '</i></div>').join('') + '</div>';
    }
    right += '</div>';
    return macWindow('Playing', '<div class="or-mcols">'
      + '<div class="or-mleft">' + artCard(item, { cls: 'big' }) + '</div>' + right + '</div>',
      capsule(item, set, cfg));
  }

  /* The window shell. `cur` is the nav item the page belongs to — the Playing page says Playing,
     and the frame that shows the capsule's volume popover says Films, because a popover you open
     from the capsule is for when you are NOT on the Playing page. Drawing it over the Playing
     page would have put the same three sliders on screen twice. */
  function macWindow(cur, body, cap) {
    const nav = ['Home', 'Films', 'Series', 'Music', 'Playing']
      .map(n => '<span' + (n === cur ? ' class="on"' : '') + '>' + n + '</span>').join('');
    return '<div class="or-mac"><div class="or-mside">'
      + '<span class="or-lights"><i></i><i></i><i></i></span>'
      + '<div class="or-mnav">' + nav + '</div></div>'
      + '<div class="or-mmain">' + body + (cap || '') + '</div></div>';
  }

  /* A browse page, as scaffolding only: eight gradient tiles and a heading. Nothing here is a
     proposal about Films — it exists so the popover has somewhere to be opened from. */
  function macBrowse(item, set, cfg) {
    const tiles = [FILMTINT(0), FILMTINT(1), FILMTINT(2), FILMTINT(3),
      FILMTINT(4), FILMTINT(5), FILMTINT(6), FILMTINT(7)].join('');
    return macWindow('Films', '<h2 class="or-bh">Films</h2><div class="or-grid">' + tiles + '</div>',
      capsule(item, set, cfg));
  }
  const FILMTINT = i => '<i style="background:linear-gradient(145deg,hsl('
    + (198 + i * 23) + ' 24% 24%),hsl(' + (214 + i * 19) + ' 28% 14%))"></i>';

  function capsule(item, set, cfg) {
    const pct = (item.pos / item.dur * 100).toFixed(2);
    return '<div class="or-cap">'
      + '<span class="th' + (item.kind === 'music' ? ' sq' : '') + '" style="background-image:' + OR.art(item.key) + '"></span>'
      + '<button class="or-tb sk sm nolbl">' + G.back10 + '</button>'
      + '<button class="or-pp sm">' + G.pause + '</button>'
      + '<button class="or-tb sk sm nolbl">' + G.fwd30 + '</button>'
      + '<span class="tx"><span class="n">' + esc(item.title) + '</span>'
      + '<span class="d">' + pIcon(set.places[0]) + esc(set.line) + '</span></span>'
      + '<span class="or-capseek"><i class="tk"></i><i class="dn" style="width:' + pct + '%"></i></span>'
      + '<span class="tm">' + OR.hms(item.pos) + '</span>'
      + '<button class="or-vb cap' + (cfg && cfg.volOpen ? ' on' : '') + '">' + G.vol + '</button>'
      + (cfg && cfg.volOpen ? '<div class="or-pop">' + volume(set, cfg.vols || { master: OR.VOL.master }, {}) + '</div>' : '')
      + '</div>';
  }

  return {
    setLang, t, G, P, placeSet, remote, remoteB, tracksSheet, moveSheet, miniBar,
    webRemote, macPlaying, macBrowse, capsule, artCard, volume, esc,
  };
})(window.OR);
