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
    /* Rev 3 — the book's own skips are BOTH 30 s (`ab.skip_back` / `ab.skip_fwd`), so the back
       glyph is the forward one mirrored and not the film's 10 s mark reused. */
    back30: sv('<path d="M11 5.2V2.4L6 6.3l5 3.9V7.4a5 5 0 1 1-5 5"/>'),
    sleep: sv('<path d="M20.5 14.3A8.5 8.5 0 0 1 9.7 3.5a8.5 8.5 0 1 0 10.8 10.8z"/>'),
    chapters: sv('<path d="M4 5h9M4 10h9M4 15h6"/><path d="M17 4v16l3-2.4 3 2.4V4z" transform="translate(-2)"/>'),
    bookmark: sv('<path d="M7 3h10v18l-5-4-5 4z"/>'),
    /* The drag handle on a queue row — the shipped reorder affordance (FR-R322-8). */
    drag: sv('<path d="M8 8h8M8 12h8M8 16h8"/>'),
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.9" fill="currentColor" stroke="none"/></svg>',
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
    /* The ♪ the shipped page puts in front of *Playing from* (`MusicIcon.NOTE`). */
    note: sv('<path d="M9 18V6l10-2.4v12"/><circle cx="6.4" cy="18" r="2.6"/><circle cx="16.4" cy="15.6" r="2.6"/>'),
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
  /* The SAME place with one field different: its volume goes to a soundbar, so it cannot report
     one. A copy rather than a second entry in `PLACES`, so the frame is Soveværelse TV and not a
     fictional TV invented to carry a state. */
  const noReport = s => Object.assign({}, s, {
    places: s.places.map(p => Object.assign({}, p, { reports: false })),
  });
  const P = {
    stuetv: placeSet(['stuetv']), sovetv: placeSet(['sovetv']),
    speakers: placeSet(['stue', 'gaeste']), threeRooms: placeSet(['stue', 'gaeste', 'kontor']),
    pixelPlace: placeSet(['pixel']),
  };
  P.sovetvBar = noReport(P.sovetv);

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
  /* DECISION 9 (rev 3) — the two outcomes of a drag, and nothing in between.
     `o.note` is a map keyed by the row ('master' or a place id) to one short line drawn UNDER that
     row. It is the ONLY mark either outcome has:

       (a) released, the place has not confirmed yet — the thumb stays where the finger left it and
           there is NO mark. A spinner, a greying or a ghost thumb would all say "this may not have
           worked" on the 95 % of drags that do, and the viewer is already holding the phone that
           just moved it. Nothing is drawn because nothing has gone wrong yet.
       (b) the place never took it — the thumb goes back to the value the place is actually at, and
           one line says so for 3 s. The revert alone is ambiguous (a thumb that moves back on its
           own reads as a bug); the line is what makes it a report. */
  function volume(set, vols, opt) {
    const o = opt || {};
    const note = o.note || {};
    const row = (key, label, pct, cls, muteGlyph) =>
      '<div class="or-vrow' + (cls || '') + '">'
      + '<button class="or-vb" aria-label="' + esc(label) + '">' + (muteGlyph || G.vol) + '</button>'
      + '<span class="or-vn">' + esc(label) + '</span>'
      + slider(pct, { dis: cls && cls.indexOf('dis') >= 0 })
      + '<span class="or-vv">' + (pct === null ? '—' : pct + ' %') + '</span></div>'
      + (note[key] ? '<p class="or-vnote">' + esc(note[key]) + '</p>' : '');

    /* A greyed transport means the place cannot be reached, and a slider it cannot reach is
       exactly as inert as a play button it cannot reach. The block greys with it. */
    const g = o.greyed ? ' greyed' : '';
    if (!set.multi) {
      const p = set.places[0];
      if (!p.reports) return '<div class="or-vol' + g + '">' + row('master', t('volume'), null, ' dis')
        + '<p class="or-vwhy">' + esc(t('no_volume', { place: p.name })) + '</p></div>';
      return '<div class="or-vol' + g + '">' + row('master', t('volume'), vols.master) + '</div>';
    }
    let h = '<div class="or-vol' + g + '">' + row('master', t('volume'), vols.master, ' mst');
    set.places.forEach(p => {
      const v = vols[p.id];
      h += (p.reports && v != null)
        ? row(p.id, p.name, v, ' rm')
        : row(p.id, p.name, null, ' rm dis') + '<p class="or-vwhy">' + esc(t('no_volume', { place: p.name })) + '</p>';
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
      /* `here` — the same playback, on the phone you are holding. It is the ONE sentence that
         cannot be *Playing on {place}*, because "this phone" is not a place you are told about.
         Everything else on the screen is identical, which is the pair's whole point. */
      case 'playing': return who ? { text: t(ctx.item.kind === 'music' ? 'listening' : 'watching', { who }), tone: '' }
        : ctx.here ? { text: t('playing_here'), tone: '' }
          : { text: t('playing_on', { place }), tone: '' };
      case 'paused': return who ? { text: t(ctx.item.kind === 'music' ? 'listening' : 'watching', { who }), tone: '' }
        : { text: t('paused_on', { place }), tone: '' };
      case 'buffering': return { text: t('starting_on', { place }), tone: '' };
      case 'moving': return { text: t('moving_to', { place: ctx.toPlace }), tone: 'busy' };
      case 'movefail': return { text: t('move_failed', { place: ctx.toPlace }), tone: 'bad' };
      case 'lost': return { text: t('lost', { place }), tone: 'bad' };
      case 'offline': return { text: t('offline', { place, t: OR.hms(ctx.item.pos) }), tone: 'bad' };
      /* IDLE — FR-R322-3's screen: nothing is playing anywhere, and the last-played song is
         loaded and paused where it was left. There is no sentence, because there is no place to
         say anything about: the ⏯ and the clocks are the whole report. A line reading *Paused on
         this phone* would be the frontend claiming a playback that does not exist. */
      case 'idle': return { text: '', tone: '' };
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
    /* Rev 3 — SWIPE. `SwipeCover` slides the cover off and fades it by `abs(dx)/w` capped at .6,
       and the next song's cover is underneath. A gesture cannot be drawn on a still screen by an
       arrow or a hint badge — the shipped page has neither, and inventing one would be proposing a
       control the owner did not ask for. So the swipe is drawn as the MID-GESTURE state, which is
       a real frame of the real animation: dragged 34 % of the width, alpha 1 − .34 = .66 (the
       Kotlin's own formula), with the next cover behind it. */
    if (o.swipe) {
      const dx = 34;                                   /* % of the cover's own width */
      const a = (1 - Math.min(dx / 100, 0.6)).toFixed(2);
      return '<div class="' + cls + ' or-swipe">'
        + '<i class="or-under" style="background-image:' + OR.art(item.nextKey || 'song2') + '"></i>'
        + '<i class="or-over" style="background-image:' + OR.art(item.key)
        + ';transform:translateX(-' + dx + '%);opacity:' + a + '"></i>'
        + '</div>';
    }
    return '<div class="' + cls + '" style="background-image:' + OR.art(item.key) + '">' + inner + spin + '</div>';
  }

  /* ---- the header: ONE control, and it names the place. Decision 2's lean. -------------------

     Rev 3 gives it two options, both of which are read off the shipped code rather than invented:

     - `tab` — FR-R322-2: *"no close chevron (its place is empty)"*. The Playing tab is a page with
       the bar under it, so the chevron's slot is held open and left empty rather than closed up:
       the pill then sits in the same place in both frames, and the remote opened over something
       else is the same screen with one control added back. The spacer is `.ghost`, which is what
       the right-hand slot has always been.
     - `menu` — the track ⋯. It goes in the header's right slot, which was already a ghost. The
       shipped `BottomRow` puts ⋯ at the right of the bottom row, but the remote's bottom row is
       three LABELLED buttons (Up next · Lyrics · Stop) and a fourth bare glyph beside them reads
       as a fourth action of the same weight. The header's right slot is empty, 46 dp, and already
       where a screen's own menu lives in this app. Named in the report as a deliberate move. */
  function header(set, opt) {
    const o = opt || {};
    const dis = o.readonly ? ' disabled' : '';
    return '<div class="or-top">'
      + (o.tab ? '<span class="or-ic ghost"></span>'
        : '<button class="or-ic" aria-label="Close">' + G.down + '</button>')
      + '<button class="or-place' + (o.readonly ? ' ro' : '') + '"' + dis + '>'
      + (o.pin || pIcon(set.places[0])) + '<span>' + esc(o.label || set.line) + '</span>'
      + (o.readonly ? '' : G.move) + '</button>'
      + (o.menu ? '<button class="or-ic" aria-label="' + esc(t('more')) + '">' + G.more + '</button>'
        : '<span class="or-ic ghost"></span>') + '</div>';
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

  /* ---- rev 3: the book's clocks and bars -----------------------------------------------------
     Every number here is computed from `BOOK.chapters` and nothing is written twice. The two
     formatters are the shipped ones, transcribed: `fmtLen` is `m:ss`/`h:mm:ss` for a chapter, and
     `fmtTotal` ROUNDS TO THE NEAREST MINUTE and reads *{h} h {m} min* — which is why the
     whole-book bar says *4 h 12 min / 9 h 39 min* and not the brief's 4:12:05 of 9:38:40. Found by
     reading `MusicCommon.kt`, not by assuming; named in the report. */
  const fmtLen = s => {
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = Math.floor(s % 60);
    return (h > 0 ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(x).padStart(2, '0');
  };
  const fmtTotal = s => {
    const m = Math.round(s / 60);
    return m >= 60 ? t('hours_minutes', { h: Math.floor(m / 60), m: m % 60 }) : t('minutes', { n: m });
  };
  /* `speedText` — 1.0, 1.25, 1.75, exactly as the Kotlin writes it. */
  const speedText = s => {
    const c = Math.round(s * 100);
    return c % 10 === 0 ? (c / 100) + '.' + (Math.floor(c / 10) % 10) : (Math.floor(c / 100)) + '.' + String(c % 100).padStart(2, '0');
  };

  /* The chapter's own seek bar, in book time underneath (`ChapterSeekBar`: *12:40 / 29:05*). */
  function bookSeek(item) {
    const into = item.pos - item.chStart;
    const pct = (into / item.chLen * 100).toFixed(2);
    return '<div class="or-seek">' + slider(pct, {})
      + '<div class="or-tm"><span>' + fmtLen(into) + '</span>'
      + '<span>' + fmtLen(item.chLen) + '</span></div></div>';
  }
  /* `WholeBookBar` — the word BOOK in caps, a 2 px line with a 10 px knob, and elapsed / total; and
     `ab.left` under it. */
  function wholeBook(item) {
    const pct = (item.pos / item.dur * 100).toFixed(2);
    return '<div class="or-wb">'
      + '<span class="lb">' + esc(t('whole_book').toUpperCase()) + '</span>'
      + '<span class="tr"><i class="dn" style="width:' + pct + '%"></i>'
      + '<i class="kb" style="left:' + pct + '%"></i></span>'
      + '<span class="tm">' + esc(fmtTotal(item.pos)) + ' / ' + esc(fmtTotal(item.dur)) + '</span></div>'
      + '<p class="or-wbleft">' + esc(t('left', { t: fmtTotal(item.dur - item.pos) })) + '</p>';
  }
  /* `ChapterStrip` — the chapters as proportional segments: the one being read lit, the finished
     ones quieter, each at least 1.2 % wide so a short chapter is still a target. */
  function chapterStrip(item) {
    return '<div class="or-cst">' + item.chapters.map((len, i) =>
      '<i class="' + (i === item.ch ? 'now' : i < item.ch ? 'done' : '')
      + '" style="flex:' + Math.max(len / item.dur, 0.012).toFixed(4) + '"></i>').join('') + '</div>';
  }

  /* ---- the transport. A film or an episode gets ±skip; music gets shuffle/prev/next/repeat.
     THAT is direction A: the kind picks the controls, never how the place is reached. ---- */
  function transport(item, st, opt) {
    const o = opt || {};
    const greyed = o.greyed ? ' greyed' : '';
    const mid = st === 'buffering'
      ? '<span class="or-pp busy"><i class="or-spin"></i></span>'
      : '<button class="or-pp">' + (st === 'playing' ? G.pause : G.play) + '</button>';
    /* A BOOK is not a song: `BookTransport` is speed · −30 s · ⏯ · +30 s · sleep. The brief named
       four of the five; the sleep timer is the fifth and the code wins, so it is drawn. */
    if (item.kind === 'book') {
      return '<div class="or-tr book' + greyed + '">'
        + '<button class="or-spd' + (item.speed !== 1 ? ' on' : '') + '">'
        + esc(speedText(item.speed)) + '×</button>'
        + '<button class="or-tb sk30">' + G.back30 + '<b>30 s</b></button>' + mid
        + '<button class="or-tb sk30">' + G.fwd30 + '<b>30 s</b></button>'
        + '<button class="or-tb sm">' + G.sleep + '</button></div>';
    }
    if (item.kind === 'music') {
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
    if (item.kind === 'book') {
      /* `BookPlayingScreen`'s own bottom row is chapters · bookmark · ⋯. The ⋯ is in the header
         here (see `header`), so the third slot is Stop, as it is for every other kind. */
      h += b('', G.chapters, t('chapters'));
      h += b('', G.bookmark, t('bookmark_add'));
    } else if (item.kind === 'music') {
      /* On a surface wide enough to draw the queue inline, the button that opens it is noise —
         the Mac showed "Up next" twice, once as a button and once as the list under it. */
      if (!o.queueInline) h += b('', G.list, t('up_next'));
      /* FR-R322-4: *Lyrics (absent when none — never greyed)*, and lit while they show. */
      if (item.hasLyrics) h += b(o.lyricsOn ? 'lit' : '', G.lyr, t('lyrics'));
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
    const here = !!cfg.here;
    const sl = stateLine(st, { set, who: cfg.who, item, toPlace: cfg.toPlace, here });
    /* Music and a book are the two kinds the Playing page used to own. Rev 3 draws them in THIS
       builder — the cast remote's shape — with everything that page did. */
    const sq = item.kind === 'music' || item.kind === 'book';
    const lyr = !!cfg.lyricsOn;

    let h = '<div class="or-rem' + (sq ? ' sq' : '') + (lyr ? ' lyr' : '')
      + (cfg.tab ? ' or-tabbed' : '') + '">'
      + header(set, {
        readonly: st === 'other', tab: !!cfg.tab, menu: sq && !ro,
        /* The ONE field that differs between the elsewhere frame and the this-phone frame. */
        pin: here ? G.phone : null, label: here ? t('this_phone') : null,
      });

    /* 1 — *Playing from {context}* and the ♪, which is the shipped page's own top line
       (`music.from` with `contextLabel`). A BOOK's top line is the book's title and nothing else,
       which is what `BookPlayingScreen` draws there. */
    if (sq) h += '<div class="or-ctx">' + G.note
      + '<span>' + esc(item.kind === 'book' ? item.title : t('from', { x: item.album })) + '</span></div>';

    /* A banner states the connection event in full, so the line under the title must NOT repeat
       it. One sentence, one place: these three states have a banner and no state line, and every
       other state has a state line and no banner. */
    const banners = (st === 'lost' || st === 'movefail' || st === 'moving');
    const stLine = (banners || !sl.text) ? '' : '<div class="st' + (sl.tone ? ' ' + sl.tone : '') + '">'
      + (cfg.who && st !== 'stopped' ? G.person
        : (st === 'playing' || st === 'nextup') ? G.sp : '')
      + esc(sl.text) + '</div>';

    if (lyr) {
      /* FR-R322-7 — with lyrics up the head shrinks to a 52 dp cover and the title, and the lines
         fill what the cover had. The place pill stays in the header, so the one thing the remote
         adds to this page is the one thing that does not move. */
      h += '<div class="or-lyh">'
        + '<i class="cv" style="background-image:' + OR.art(item.key) + '"></i>'
        + '<span class="tx"><b>' + esc(item.title) + '</b>'
        + '<span>' + esc(item.artist) + '</span></span></div>'
        /* The state line has to be INSIDE an `.or-meta`, because that is where its rules live —
           the first render drew it with no gap, no colour and no flex, the glyph jammed into the
           first word. A wrapper, not a second copy of the rules. */
        + '<div class="or-meta lyrst">' + stLine + '</div>'
        /* `LyricsView`: the line being sung is lit, the lines ALREADY SUNG are `textDim` and the
           ones to come are `textSecondary`. Three tones, not two, and the "past" one is the
           quietest — so the column reads downwards even in a still picture. The index comes off
           the one `now` flag in or-data.js; nothing is written twice. */
        + (function () {
          const n = item.lyrics.findIndex(l => l.now);
          /* `LyricsView` keeps the line being sung at ~40 % of the column. A still frame has no
             scroller, so the column is shifted up by the same amount: a 24/31 line in a 7 px pad
             is a 45 px box, and 100 px is about two fifths of the space the lyrics get here. */
          const off = 0;   /* measured after layout in frames.html — see the note there */
          return '<div class="or-lyc"><div class="in" style="margin-top:-' + off + 'px">'
            + item.lyrics.map((l, i) =>
              '<p class="' + (i === n ? 'now' : i < n ? 'past' : '') + '">' + esc(l.t) + '</p>').join('')
            + '</div></div>';
        })();
    } else {
      h += artCard(item, {
        dim: st === 'paused' || st === 'offline' || st === 'idle' || ro,
        wordmark: !!cfg.wordmark,
        swipe: !!cfg.swipe,
        /* No spinner on the art: the transport already stands one in for ⏯, which is the shipped
           rule, and two spinners for one wait is two claims about one fact. */
      });

      h += '<div class="or-meta' + (sq ? ' sq' : '') + '">'
        /* No kicker on a song or a book: the artist is in the line UNDER the title, and the
           first render drew MARRA QUAY above the title and *Marra Quay* under it. */
        + (!sq && item.kicker ? '<div class="k">' + esc(item.kicker) + '</div>' : '')
        /* 3 — the title, and the ♥ beside it. `Credits()` puts ♥ level with the TITLE, not with
           the chip under it (which is where the mockup's nesting put it). The code wins. */
        + (sq
          ? '<div class="or-ttl"><h3>' + esc(item.kind === 'book' ? t('chapter_n', { n: item.ch + 1 }) : item.title) + '</h3>'
            + (item.kind === 'music'
              ? '<button class="or-fav' + (item.fav ? ' on' : '') + '" aria-label="' + esc(t('my_list')) + '">'
                + (item.fav ? MI.heartOn : MI.heart) + '</button>'
              : '') + '</div>'
          : '<h3>' + esc(item.title) + '</h3>')
        /* R344's version chips at the START of the artist · album line, then the artist in accent
           and the album in secondary — both links. A book has the book and its author instead. */
        + (item.kind === 'music'
          ? '<div class="or-sub">' + verChips(item)
            + '<a class="ar">' + esc(item.artist) + '</a><em>·</em><a>' + esc(item.album) + '</a></div>'
          : item.kind === 'book'
            ? '<div class="or-sub"><a>' + esc(item.title) + '</a><em>·</em><a class="ar">' + esc(item.author) + '</a></div>'
            : '')
        + stLine
        + '</div>';
    }

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
      /* A book's two bars: the chapter's own, then the whole book's, then *… left*. */
      h += (item.kind === 'book' ? bookSeek(item) + wholeBook(item) : seek(item, { dis: greyed || ro }));
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
        /* The chapters, as proportional segments, where the shipped page puts them. */
        if (item.kind === 'book') h += chapterStrip(item);
        /* `noVol` is decision 1's ALTERNATIVE: no slider on a cast at all, because volume
           belongs to the TV and a slider invites a fight with the TV's own remote. The phone's
           hardware keys stay the control and raise a transient pill instead. */
        if (!cfg.noVol) h += volume(set, cfg.vols || { master: OR.VOL.master },
          { greyed, note: cfg.volNote });
      }
    }

    if (cfg.confirm) {
      h += '<div class="or-cf"><p>' + esc(t('stop_q', { who: cfg.who, title: item.title })) + '</p>'
        + '<div class="bs"><button class="go">' + esc(t('stop')) + '</button>'
        + '<button>' + esc(t('cancel')) + '</button></div></div>';
    }

    /* Stop is absent on IDLE as well as on a read-only playback: nothing is playing, so there is
       nothing to press. The round's rule is that an action a place cannot do is absent, not grey —
       and the first render of `a-music-idle` offered Stop on a paused song that was not playing
       anywhere. */
    if (!ro || cfg.canStopOther) h += footer(item,
      { readonly: (ro && !cfg.canStopOther) || st === 'idle', lyricsOn: lyr });
    else h += '<div class="or-foot"></div>';

    if (cfg.hwVol != null) h += hwPill(set, cfg.hwVol);
    h += '</div>';
    /* FR-R322-2's lean: the Playing TAB is a page with the bar under it. The bar is outside
       `.or-rem` because it is the app's, not the remote's. */
    if (cfg.tab) h += bnav(cfg.tabIndex == null ? 2 : cfg.tabIndex);
    /* The queue, over the remote, as a sheet. */
    if (cfg.queue) h += queueSheet(item);
    if (cfg.moveSheet) h += moveSheet(set, item, { here, hi: !!cfg.tab });
    return h;
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

  /* Move to… — opened from the place line, with *Play on this phone* inside it.
     Rev 3: `o.here` is the frame where the playback is ALREADY on this phone, so the phone's row
     is the ticked one and the places are the things to move to. Same sheet, same rows, one tick in
     a different place — which is the pair's argument again, one level down. */
  function moveSheet(cur, item, o) {
    const here = !!(o && o.here);
    const ids = item.kind === 'music' || item.kind === 'book'
      ? ['stue', 'gaeste', 'kontor'] : ['stuetv', 'sovetv'];
    /* Over a TABBED remote the sheet has to clear the bottom bar, which is z-index 88: a sheet is
       in front of the bar it is opened over. Without it the first render of `a-music-move-to` had
       the bar painted across the one row the frame exists to show — *This phone · Playing here*,
       ticked. */
    const hi = (o && o.hi) ? ' or-hi' : '';
    let h = '<div class="or-scrim' + hi + '"></div><div class="or-sheet' + hi + '"><div class="or-sh"><h4>' + esc(t('move_to')) + '</h4>'
      + '<button class="or-ic">' + G.x + '</button></div>'
      + '<p class="or-applies">One place. ' + (item.kind === 'music'
        ? 'The song carries on where it is.' : 'It picks up 2 seconds back.') + '</p>';
    ids.forEach(id => {
      const p = OR.PLACES[id], on = !here && cur.ids.indexOf(id) >= 0;
      const busy = id === 'sovetv' && cur.ids.indexOf('sovetv') < 0;
      h += '<button class="or-mrow' + (on ? ' on' : '') + (busy ? ' dis' : '') + '">'
        + '<span class="ic">' + pIcon(p) + '</span><span class="tx"><span class="nm">' + esc(p.name) + '</span>'
        + '<span class="sub">' + esc(on ? t('playing_here') : busy ? t('busy_with', { who: OR.OTHER.name }) : t('ready'))
        + '</span></span><span class="rt">' + (on ? G.ck : '') + '</span></button>';
    });
    h += '<div class="or-sdiv"></div>';
    h += '<button class="or-mrow' + (here ? ' on' : '') + '"><span class="ic">' + G.phone + '</span><span class="tx">'
      + '<span class="nm">' + esc(here ? t('this_phone') : t('play_here')) + '</span>'
      + '<span class="sub">' + esc(here ? t('playing_here') : t('this_phone'))
      + '</span></span><span class="rt">' + (here ? G.ck : '') + '</span></button>';
    return h + '</div>';
  }

  /* ---- rev 3: the QUEUE, over the remote ------------------------------------------------------
     FR-R322-8. A SHEET rather than a page, because it is opened from the remote and the remote is
     what you go back to — a page would push the thing you are controlling off the screen, and the
     queue is a list you glance at and dismiss. The Queue TAB stays: it is the queue you go to
     deliberately, with *Clear queue* and the whole list, and it is not reached from here. (The
     brief's lean, and the sheet is R322's own "the queue sheet is the same list".)

     *Now playing* has the bars; every other row has a DRAG HANDLE, because a tap plays from there
     and a drag reorders. The count and the time left are computed from `upNext`, so the heading
     cannot disagree with the rows under it. */
  function queueSheet(item) {
    const rest = item.upNext;
    const leftSec = (item.dur - item.pos) + rest.reduce((a, s) => a + s.len, 0);
    let h = '<div class="or-scrim or-hi"></div><div class="or-sheet or-hi or-qsheet">'
      + '<div class="or-sh"><h4>' + esc(t('up_next')) + '</h4>'
      + '<button class="or-ic">' + G.x + '</button></div>'
      + '<p class="or-qcount">' + esc(t('queue_left', { n: rest.length, t: fmtTotal(leftSec) })) + '</p>';
    h += '<div class="or-qsec">' + esc(t('now_playing')) + '</div>';
    h += '<div class="or-qrow now"><span class="hd">' + G.sp + '</span>'
      + '<span class="nm">' + esc(item.title) + '</span>'
      + '<span class="ln">' + OR.mmss(item.dur) + '</span></div>';
    h += '<div class="or-qsec">' + esc(t('up_next')) + '</div>';
    rest.forEach(s => {
      h += '<div class="or-qrow"><span class="hd">' + G.drag + '</span>'
        + '<span class="nm">' + esc(s.title) + '</span>'
        + '<span class="ln">' + OR.mmss(s.len) + '</span></div>';
    });
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

  /* The phone's Playing page — rev 2's `musicPlaying`, `muSeek`, `muTransport` and `volumeSheet` —
     is GONE from this file. The owner retired that page on 2026-10-23 ("todays playing page is a
     bit bad UI, so that one goes"), and music is drawn by `remote()` above, on the cast remote's
     shape, with everything the page did. A builder left behind for a retired page is a
     specification of intent for a screen that no longer exists, and this file is what the Kotlin
     gets ported from. The page itself is in git: `5c48c448`. */
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
      /* Rev 3 — the volume block, INLINE, under the transport. The owner's decision is an
         on-screen slider, the same rule as speakers, and a popover hanging off the capsule is not
         on screen: you have to know it is there. It goes in the cover's column because that is
         where this page's controls are, and it is the same block, in the same order, as the phone
         and the film remote. The capsule keeps its volume button — that is for the pages that are
         NOT Playing, which is what `mac-volume-popover` draws. */
      + volume(cfg.set, cfg.vols || { master: OR.VOL.master }, { note: cfg.volNote })
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
    musicMini, musicHome, macMusicPlaying, webMusic, npBody,
    bnav, hwPill, queueSheet,
  };
})(window.OR);
