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
        /* `noVol` is decision 1's ALTERNATIVE: no slider on a cast at all, because volume
           belongs to the TV and a slider invites a fight with the TV's own remote. The phone's
           hardware keys stay the control and raise a transient pill instead. */
        if (!cfg.noVol) h += volume(set, cfg.vols || { master: OR.VOL.master }, { greyed });
      }
    }

    if (cfg.confirm) {
      h += '<div class="or-cf"><p>' + esc(t('stop_q', { who: cfg.who, title: item.title })) + '</p>'
        + '<div class="bs"><button class="go">' + esc(t('stop')) + '</button>'
        + '<button>' + esc(t('cancel')) + '</button></div></div>';
    }

    if (!ro || cfg.canStopOther) h += footer(item, { readonly: ro && !cfg.canStopOther });
    else h += '<div class="or-foot"></div>';

    if (cfg.hwVol != null) h += hwPill(set, cfg.hwVol);
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
    const music = item.kind === 'music' || item.kind === 'book';
    return '<div class="or-cap">'
      + '<span class="th' + (item.kind === 'music' ? ' sq' : '') + '" style="background-image:' + OR.art(item.key) + '"></span>'
      /* The kind picks the controls here too: ±10/30 s for a film or an episode, previous/next
         for a song. Round 1 drew ±10 s on a 3:41 song, which the Mac music render showed. */
      + '<button class="or-tb sk sm nolbl">' + (music ? G.prev : G.back10) + '</button>'
      + '<button class="or-pp sm">' + G.pause + '</button>'
      + '<button class="or-tb sk sm nolbl">' + (music ? G.next : G.fwd30) + '</button>'
      + '<span class="tx"><span class="n">' + esc(item.title) + '</span>'
      + '<span class="d">' + sIcon(set) + esc(set.line) + '</span></span>'
      + '<span class="or-capseek"><i class="tk"></i><i class="dn" style="width:' + pct + '%"></i></span>'
      + '<span class="tm">' + OR.hms(item.pos) + '</span>'
      /* Stop, one tap — and from EVERY page, not only Playing. The desktop's left column is
         380 px wide and the place pill alone fills it, so a Stop beside the pill truncated the
         place; the capsule is 1 000 px and is where a desktop's transport already lives. */
      + (cfg && cfg.stopInCap ? '<button class="or-stopb cap">' + G.stop
        + '<span>' + esc(t('stop')) + '</span></button>' : '')
      + '<button class="or-vb cap' + (cfg && cfg.volOpen ? ' on' : '') + '">' + G.vol + '</button>'
      + (cfg && cfg.volOpen ? '<div class="or-pop">' + volume(set, cfg.vols || { master: OR.VOL.master }, {}) + '</div>' : '')
      + '</div>';
  }

  /* ===============================================================================================
     MUSIC — rev 2. The SHIPPED Playing page, not a new layout.
     ===============================================================================================

     Round 1 drew music as its own screen and that was the defect: local music would have kept
     today's Playing tab while music elsewhere got a second player, which is the complaint the
     round exists to remove. So these builders emit the SHIPPED markup — `mu-ntop`, `mu-ncov`,
     `mu-nmeta`, `mu-nsub`, `mu-seek`, `mu-tp`, `mu-nbot`, `bnav` — and frames.html links the
     shipped `ravilo-music.css` beside `ravilo-phone.css`. Every class below without an `or-`
     prefix is a shipped class and its look is not this round's to propose.

     The page is read from the Kotlin (`MusicPlayingScreen`, portrait branch) and cross-checked
     against the mockup (`design/ravilo/mobile/ravilo-music-player.js`). Where they disagree the
     CODE wins and the report says so — the cover is 80 % of the width in Compose
     (`SwipeCover`: `fillMaxWidth(0.8f)`) and 84 % in `ravilo-music.css`.

     What the page GAINS, and nothing else:
       1. the device chip becomes the place pill — it still says *Playing on {place}*, which is
          the shipped chip's own sentence, and it now opens *Move to…*;
       2. a *Stop* pill in the shipped bottom row, so Stop is one tap from the page;
       3. the someone-else line and round 1's state banners, under the pill;
       4. volume stays where it ships — behind ⋯ — and that sheet gains the rooms. Reasoned in
          the report: three room rows are 138 px and four with a reason line are over 200, and
          the page's own slack at 412×915 is about 100. An inline row would push the transport
          below the fold, which the brief forbids.
     ----------------------------------------------------------------------------------------- */

  /* The shipped music glyphs, redrawn from ravilo-music-player.js's own `IC` so the picture is
     the shipped icon and not a near miss. */
  const MI = {
    down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
    prev: '<svg viewBox="0 0 24 24"><path d="M18 6v12l-9-6z" fill="currentColor" stroke="none"/><rect x="5" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M6 6v12l9-6z" fill="currentColor" stroke="none"/><rect x="16.6" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6.5" y="5" width="4" height="14" rx="1.3"/><rect x="13.5" y="5" width="4" height="14" rx="1.3"/></svg>',
    repeat: '<svg viewBox="0 0 24 24"><path d="M4 11V9a3 3 0 0 1 3-3h12M16 3l3 3-3 3"/><path d="M20 13v2a3 3 0 0 1-3 3H5M8 21l-3-3 3-3"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24"><path d="M3 7h3.5c5 0 6 10 11 10H21"/><path d="M3 17h3.5c2 0 3.2-1.6 4.2-3.5M14 8.5c1-1 2-1.5 3.5-1.5H21"/><path d="M18.5 4.5 21 7l-2.5 2.5M18.5 14.5 21 17l-2.5 2.5"/></svg>',
    lyr: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.8" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.8" fill="currentColor" stroke="none"/></svg>',
    cast: '<svg viewBox="0 0 24 24"><path d="M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9.5V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"/></svg>',
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
    heartOn: '<svg viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    listen: '<svg viewBox="0 0 24 24"><circle cx="7.5" cy="17.5" r="2.8"/><path d="M10.3 17.5V5.5l9.2-2v12"/><circle cx="16.7" cy="15.5" r="2.8"/></svg>',
    browse: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/></svg>',
    nowp: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8.5 15.5v-3M11 15.5V9M13.5 15.5v-5M16 15.5v-2"/></svg>',
    queue: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h12M4 16h7"/><path d="M16 14l5 3.2-5 3.2z"/></svg>',
    /* The shipped speaker-GROUP glyph (ravilo-speakers.js `G.group`). A place-set of several rooms
       is one place in the product's words and one glyph on the screen. */
    group: '<svg viewBox="0 0 24 24"><rect x="2.5" y="5" width="8.5" height="15" rx="2.4"/><rect x="13" y="5" width="8.5" height="15" rx="2.4"/><circle cx="6.75" cy="14" r="2.1"/><circle cx="17.25" cy="14" r="2.1"/></svg>',
  };
  /* ONE place glyph for the music surfaces: the group mark for a set of rooms, the place's own
     otherwise. The round-1 film frames keep `pIcon` and are untouched. */
  const sIcon = set => (set.multi ? MI.group : pIcon(set.places[0]));

  /* The shipped music-mode bottom bar: owner 2026-09-27, `BARS.cnp` + Profile — Listen · Browse ·
     Playing · Queue · Profile, Playing in the middle because Search merged into Browse. The pill
     (`.bnind`) is placed by JS in the app; here it is offset to the lit item's own cell. */
  function bnav(onIndex) {
    const items = [
      ['nav_listen', MI.listen], ['nav_browse', MI.browse], ['nav_playing', MI.nowp],
      ['nav_queue', MI.queue], ['nav_profile', null],
    ];
    const cell = 412 / 5;                      /* five items share the 412 px row */
    const left = cell * onIndex + (cell - 56) / 2;   /* the 56 px pill, centred in its item */
    return '<nav class="bnav"><div class="brow">'
      + '<i class="bnind" style="transform:translateX(' + left.toFixed(1) + 'px)"></i>'
      + items.map((it, i) => '<button class="bn' + (i === onIndex ? ' on' : '') + '">'
        + '<span class="p">' + (it[1] || '<span class="avatar sm">' + esc(OR.ME.name.charAt(0)) + '</span>') + '</span>'
        + '<b>' + esc(t(it[0])) + '</b></button>').join('')
      + '</div><div class="bnsafe"></div></nav>';
  }

  /* The shipped cover: a `.mu-cv` filling `.mu-ncov`. The art is this round's deterministic
     gradient, so no frame of the household's library can reach a picture. */
  const muCover = (item, cls) => '<div class="mu-cv' + (cls ? ' ' + cls : '')
    + '" style="background:' + OR.art(item.key) + '"></div>';

  /* The shipped seek line, with the two clocks `.mu-seek .tm` computes from one duration. */
  function muSeek(item) {
    const pct = (item.pos / item.dur * 100).toFixed(2);
    return '<div class="mu-seek"><div class="tr">'
      + '<span class="dn" style="width:' + pct + '%"></span>'
      + '<span class="kb" style="left:' + pct + '%"></span></div>'
      + '<div class="tm"><span>' + OR.mmss(item.pos) + '</span>'
      + '<span>−' + OR.mmss(item.dur - item.pos) + '</span></div></div>';
  }

  /* The shipped transport: shuffle · previous · ⏯ (64 dp) · next · repeat.
     `glyph` is passed in rather than derived, because moving, a failed move and lost contact are
     NOT pauses — the last state the place reported still stands, so ⏯ keeps its glyph. */
  function muTransport(glyph, opt) {
    const o = opt || {};
    return '<div class="mu-tp' + (o.greyed ? ' or-greyed' : '') + '">'
      + '<button class="mu-ib sm">' + MI.shuffle + '</button>'
      + '<button class="mu-ib fill">' + MI.prev + '</button>'
      + '<button class="mu-pp">' + (glyph === 'playing' ? MI.pause : MI.play) + '</button>'
      + '<button class="mu-ib fill">' + MI.next + '</button>'
      + '<button class="mu-ib sm">' + MI.repeat + '</button></div>';
  }

  /* R344's version chips, at the start of the artist · album line, in the shipped `.rv-vb`. */
  const verChips = item => (item.versions || []).length
    ? '<span class="rv-vb">' + item.versions.map(v =>
      '<span class="rv-v" style="--c:' + v.c + '">' + esc(v.label) + '</span>').join('') + '</span>'
    : '';

  /* ---- the place pill. The shipped device chip's SLOT and the shipped device chip's SENTENCE
     (cast.playing_on / cast.paused_on — "Playing on {device}"), plus the one thing it gains: it
     opens Move to…, so the chevron the Cast remote's pill carries comes with it. A place that is
     not reachable keeps the pill and greys it; it never disappears, because the place still
     exists and the viewer still needs to read where. ---- */
  function placePill(set, st, opt) {
    const o = opt || {};
    const key = (st === 'paused' || st === 'offline') ? 'paused_on' : 'playing_on';
    return '<button class="mu-ndev or-pill' + (o.greyed ? ' or-greyed' : '')
      + (o.readonly ? ' or-ro' : '') + '">'
      + sIcon(set)
      + '<span>' + esc(t(key, { place: set.line })) + '</span>'
      + (o.readonly ? '' : G.move) + '</button>';
  }

  /* ---- THE PHONE'S PLAYING PAGE ----------------------------------------------------------- */
  function musicPlaying(cfg) {
    const item = cfg.item, set = cfg.set, st = cfg.state || 'playing';
    const ro = st === 'other' || st === 'stopped' || st === 'ended';
    const greyed = st === 'lost' || st === 'offline';
    /* `today` draws the page exactly as it ships: the device chip, no pill, no Stop, no banner.
       It exists so the owner can see what changed rather than be told. */
    const today = !!cfg.today;
    const banners = (st === 'lost' || st === 'movefail' || st === 'moving');

    /* The ground. The mockup paints a radial tint off the album's hue; the Kotlin
       (`playingGround()`) paints a VERTICAL gradient from `accentDim` at 60 % into the
       background, and flat background in noir. The code wins, and it matters: the album-hue
       radial is a dark wash and in daylight it painted a brown stain across a white page. The
       colours are in one-remote.css so the skin can answer for itself. */
    let h = '<div class="mu-now on tabbed">' + '<div class="bgt"></div>';

    /* 1 — Playing from {context}, and ⋯. `music.from` with its {x} removed and the context as
       the shipped `<b>` under it. */
    h += '<div class="mu-ntop"><button class="mu-ib">' + MI.down + '</button>'
      + '<div class="ctx">♪ ' + esc(t('from', { x: '' }).replace(/\s*$/, ''))
      + '<b>' + esc(item.album) + '</b></div>'
      + '<button class="mu-ib">' + MI.more + '</button></div>';

    h += '<div class="mu-nmain"><div class="mu-nspacer"></div>';

    /* 2 — the cover. 80 % of the width (the Kotlin's `SwipeCover`), swipe for next/previous. */
    h += '<div class="mu-ncov' + (ro || st === 'paused' ? ' or-dim' : '') + '">'
      + muCover(item) + '</div>';

    /* 3 — title, then the version chips · artist · album line, then the ♥.
       The ♥ is in THIS row and the chip is a row of its own under it — that is the Kotlin
       (`Credits(…)` then `DeviceChip()` as siblings). The mockup nests the chip inside
       `.mu-nmeta .b`, which puts the ♥ beside the chip instead of beside the title. The code
       wins; the report names the disagreement. */
    h += '<div class="mu-nmeta"><div class="b">'
      + '<div class="mu-mq"><span>' + esc(item.title) + '</span></div>'
      + '<div class="mu-nsub">' + verChips(item)
      + '<a>' + esc(item.artist) + '</a> · <a>' + esc(item.album) + '</a></div>'
      + '</div>'
      + '<button class="mu-ib or-fav' + (item.fav ? ' on' : '') + '">'
      + (item.fav ? MI.heartOn : MI.heart) + '</button></div>';

    /* 4 — the device chip's row. Today: the chip. Here: the place pill, and under it the one
       sentence this state needs. A banner states the connection event in full, so the line
       under the pill must not repeat it — one sentence, one place: lost, couldn't move and
       moving get a banner and no line, and every other state a line and no banner. */
    if (today) {
      h += '<div class="mu-ndev">' + sIcon(set)
        + '<span>' + esc(t('playing_on', { place: set.line })) + '</span></div>';
    } else {
      h += placePill(set, st, { greyed, readonly: st === 'other' });
      if (ro) {
        h += '<div class="or-mline">' + G.person + '<span>' + esc(
          st === 'other' ? t('listening', { who: cfg.who })
            : st === 'stopped' ? t('stopped_by', { who: cfg.who })
              : t('ended')) + '</span></div>';
      }
      if (st === 'moving') h += banner('busy', t('moving_to', { place: cfg.toPlace }));
      if (st === 'movefail') h += banner('bad', t('move_failed', { place: cfg.toPlace }), t('try_again'));
      if (st === 'lost') h += banner('bad', t('lost', { place: set.line }), t('try_again'));
    }

    h += muSeek(item);
    /* Moving, a failed move and lost contact are not pauses — see muTransport. */
    const glyph = (st === 'moving' || st === 'movefail' || st === 'lost') ? 'playing' : st;
    h += muTransport(ro ? 'paused' : glyph, { greyed });
    h += '<div class="mu-nspacer"></div></div>';

    /* 5 — the shipped bottom row: Lyrics · the cast glyph · ⋯, and the one control it gains.
       Stop is a LABELLED pill, not a bare ■: the row is 46 dp tall and has 180 px of slack at
       412, so naming the destructive action costs no height. Absent on a read-only playback —
       there is nothing there to press. */
    h += '<div class="mu-nbot or-bot"><button class="mu-ib">' + MI.lyr + '</button>'
      /* Lit in BOTH: the shipped cast button is lit whenever a speaker is playing, and `today`
         must differ from the new page in exactly the three intended places and nowhere else. */
      + '<button class="mu-ib on">' + MI.cast + '</button>';
    if (!today && !ro) h += '<button class="or-stopb">' + G.stop + '<span>' + esc(t('stop')) + '</span></button>';
    h += '<span style="flex:1"></span><button class="mu-ib">' + MI.more + '</button></div>';

    h += '</div>' + bnav(2);

    /* The ⋯ sheet, open, when the frame is about volume. */
    if (cfg.volSheet) h += volumeSheet(set, cfg.vols || { master: OR.VOL.master });
    /* The transient hardware-key pill. */
    if (cfg.hwVol != null) h += hwPill(set, cfg.hwVol);
    return h;
  }

  /* ---- volume where it ships: behind ⋯, in 5 % steps, now with a row per room. ------------- */
  function volumeSheet(set, vols) {
    /* The heading is the PLACE, drawn as the shipped speakers panel draws it (`.spk-mh`: the
       group glyph and `roomsLine()`), not the word "Volume" — the sheet already has a master row
       labelled Volume and an "Applies on {place}" line would have said the place a third time. */
    return '<div class="or-scrim or-hi"></div><div class="or-sheet or-hi">'
      + '<div class="or-sh or-vsh"><h4>' + sIcon(set) + '<span>' + esc(set.line) + '</span></h4>'
      + '<button class="or-ic">' + G.x + '</button></div>'
      + volume(set, vols, {})
      + '</div>';
  }

  /* ---- the transient pill a hardware volume key raises. The shipped toast shape
     (MusicToastHost: #E6202433, 20 dp radius, a 1 px white .08 border, 16/10 padding, 14 sp
     SemiBold Sora, bottom-centre). Its TEXT is drawn, not quoted — see the report. ---- */
  const hwPill = (set, pct) => '<div class="or-hw">'
    + esc(t('hw_vol', { place: set.line, n: pct })) + '</div>';

  /* ---- the music mini bar, in music mode, above the music bar.

     Rev 2 draws the SHIPPED bar, which already names the place: `.mu-mini .d` becomes
     `<span class="spk-ar">{artist}</span><span class="spk-on">· 🔊 {rooms}</span>` the moment a
     speaker is playing (ravilo-speakers.js `afterMini`, and the `:has(.spk-on)` rules in
     ravilo-music.css), with the rooms line joined exactly as `roomsLine()` joins it and the
     group glyph for more than one room.

     So the music bar is NOT a redraw after all: round 1 replaced the artist with the place, and
     the shipped bar carries both. One component with one place slot, in accent-2 behind a place
     glyph — the same slot the film bar fills with the place alone, because a film has no artist
     to put in front of it. The only thing it gains is the +N chip. ---- */
  function musicMini(item, set, opt) {
    const o = opt || {};
    const pct = (item.pos / item.dur * 100).toFixed(2);
    return '<div class="mu-mini on abovenav">'
      + '<div class="pg"><i style="width:' + pct + '%"></i></div>'
      + muCover(item, 'sm')
      + '<div class="tx"><div class="n">' + esc(item.title) + '</div>'
      /* The second line is the PLACE, and only the place. The shipped bar puts the artist in
         front of it (`afterMini`, `.mu-mini .d:has(.spk-on)`) — and at 412 with a two-room line
         `.spk-on`'s `flex:none` takes the whole row, collapses `.spk-ar` to nothing and still
         clips the place, so the bar reads "· 🔊 Stue + Gæstevær…" with a stray dot where the
         artist was. Found in the render, not in the markup; reported. */
      + '<div class="d or-where">' + sIcon(set) + '<span>' + esc(set.line) + '</span></div></div>'
      + (o.more ? '<button class="or-n2">+' + o.more + '</button>' : '')
      + '<button>' + MI.pause + '</button><button>' + MI.next + '</button></div>';
  }

  /* ---- a stand-in Listen page for the mini bar to sit on. Scaffolding, not a proposal. ---- */
  const musicHome = () => '<div class="or-mhome">'
    + '<div class="or-mhh">Recently played</div>'
    + '<div class="or-mhrow"><i></i><i></i><i></i></div>'
    + '<div class="or-mhh">Albums</div>'
    + '<div class="or-mhrow"><i></i><i></i><i></i></div></div>';

  /* ===============================================================================================
     MAC — the desktop Playing page, as it ships: the cover beside the lyrics, the transport under
     the cover, the capsule along the bottom.

     `.np` and `.lyr` live in design/ravilo/desktop-directions.css, which the frames do NOT link:
     that file sets `body{background:#1b1c20}` and defines a bare `.ic`, `.cap` and `.note`, all of
     which reach this round's own canvas and the two Mac frames the owner has already accepted. The
     numbers are transcribed into `or-np*` instead, out of `.np`/`.lyr` — 380 px cover, 48 px
     column gap, 70/48 padding, h2 28 px, `.by` 15 px, a 5 px seek line, 26 px transport gap, a
     58 px ⏯, lyrics 30 px/700/1.25 with the shipped top-and-bottom mask — and the report says so.
     =============================================================================================== */
  /* The `.np` body on its own, so the Mac window and the browser pane can both hold it. Ravilo Web
     at a desk width IS the desktop layout — `MusicPlayingScreen` branches on `isDesktopLayout`
     before it ever reaches the portrait page — so the 1440 web music frame is this, in browser
     chrome, and not a wide version of the phone. */
  function npBody(cfg) {
    const item = cfg.item, set = cfg.set, st = cfg.state || 'playing';
    let head = '<div class="or-nphead">'
      + '<div class="or-npcv" style="background:' + OR.art(item.key) + '"></div>'
      /* title · ♥ · ⋯, which is the shipped desktop head row. */
      + '<div class="or-nptt"><h2>' + esc(item.title) + '</h2>'
      + '<button class="or-npib' + (item.fav ? ' on' : '') + '">' + (item.fav ? MI.heartOn : MI.heart) + '</button>'
      + '<button class="or-npib">' + MI.more + '</button></div>'
      + '<div class="or-npby"><span>' + esc(item.artist) + '</span> · <span>' + esc(item.album) + '</span></div>'
      + '<div class="or-npver">' + verChips(item) + '</div>'
      /* the device chip's row — the pill, and beside it the Stop the page gains. At a desk width
         there is room for both on one line, which is why the Mac puts Stop here and the phone
         puts it in the bottom row. */
      + '<div class="or-nppl">' + placePill(set, st, {}) + '</div>'
      + '<div class="or-npseek"><span class="tr"><i style="width:'
      + (item.pos / item.dur * 100).toFixed(2) + '%"></i></span>'
      + '<span class="tm"><span>' + OR.mmss(item.pos) + '</span>'
      + '<span>' + OR.mmss(item.dur) + '</span></span></div>'
      + '<div class="or-nptp"><button class="sm">' + MI.shuffle + '</button>'
      + '<button>' + MI.prev + '</button>'
      + '<span class="pp">' + MI.pause + '</span>'
      + '<button>' + MI.next + '</button>'
      + '<button class="sm">' + MI.repeat + '</button></div>'
      + '</div>';
    const lyr = '<div class="or-nplyr">' + item.lyrics.map(l =>
      '<p' + (l.now ? ' class="now"' : '') + '>' + esc(l.t) + '</p>').join('') + '</div>';
    return '<div class="or-np">' + head + lyr + '</div>';
  }

  /* The Mac window around it, with the capsule along the bottom. */
  const macMusicPlaying = cfg =>
    macWindow('Playing', npBody(cfg),
      capsule(cfg.item, cfg.set, Object.assign({ stopInCap: true }, cfg)));

  /* The same page in a browser pane at a desk width. No Chromecast on the web: the places here are
     Ravilo apps reached through the server, so Stue TV never appears in a web frame. */
  const webMusic = cfg => '<div class="or-web"><div class="or-wchrome">'
    + '<span class="or-wdots"><i></i><i></i><i></i></span>'
    + '<span class="or-wurl">ravilo.example.net</span></div>'
    + '<div class="or-wbody or-wnp">' + npBody(cfg) + '</div>'
    /* The browser pane gets the same capsule the Mac window has — it is the desktop layout, and
       Stop has to be reachable from a page that is not Playing. */
    + capsule(cfg.item, cfg.set, Object.assign({ stopInCap: true }, cfg)) + '</div>';

  return {
    setLang, t, G, P, placeSet, remote, remoteB, tracksSheet, moveSheet, miniBar,
    webRemote, macPlaying, macBrowse, capsule, artCard, volume, esc,
    musicPlaying, musicMini, musicHome, macMusicPlaying, webMusic, npBody,
    bnav, hwPill, volumeSheet,
  };
})(window.OR);
