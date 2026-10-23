/* mpl-kit.js — the frame builders for the music-playlists round (window.MPLK).
   Every count, name and length comes from mpl-data.js; nothing is totalled or invented here.

   The phone and desktop SHELLS (bezel, status bar, app bar, bottom bar, mini player, sidebar,
   player bar) are the ones the last music round settled. Their classes come from the linked
   stylesheet ../../ravilo/music-play-all/music-play-all.css, and the glyphs come from that round's
   own kit through window.MPK.ic, so a glyph is literally the same path in both rounds rather than
   a redrawing that nearly matches. Only the markup that has no builder exported to reach (the
   sidebar, the player bar) is restated here, at its own classes and values.

   Every SVG id is minted from a counter: a document holding several frames hides the ones it is
   not shooting, and Chromium will not resolve a paint server defined inside a display:none
   subtree, so a shared id paints nothing in a VISIBLE frame and does not reproduce when that frame
   is opened alone. */
(function () {
  'use strict';
  const D = window.MPL;
  const ic = window.MPK.ic;

  /* ---- new glyphs this round, in the shipped stroke style (2 px, round caps, 24 box) ---- */
  const IC2 = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    /* the shipped Play next glyph (S.next in ravilo-music-player.js): a short list with a row
       being pushed in after the current one — NOT the queue glyph, which is Add to queue's */
    next: '<path d="M4 7h10M4 12h10M4 17h6"/><path d="M17 10v8M13 14h8"/>',
    /* Remove from playlist: a minus in a ring. Deliberately not the trash, which this round
       reserves for deleting the playlist itself. */
    minusc: '<circle cx="12" cy="12" r="8.5"/><path d="M8 12h8"/>',
    grip: '<path d="M4 8h16M4 12h16M4 16h16"/>',
    grip6: '<circle cx="9" cy="6" r="1.5"/><circle cx="15" cy="6" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="9" cy="18" r="1.5"/><circle cx="15" cy="18" r="1.5"/>',
    trash: '<path d="M4 7h16M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/><path d="M10.5 10.5v6M13.5 10.5v6"/>',
    pencil: '<path d="M4 20l.9-4.2L16 4.7a2 2 0 0 1 2.8 0l.5.5a2 2 0 0 1 0 2.8L8.2 19.1 4 20z"/><path d="M14.5 6.5l3 3"/>',
    reorder: '<path d="M4 7h9M4 12h9M4 17h9"/><path d="M17 5v14"/><path d="M14.5 7.5L17 5l2.5 2.5M14.5 16.5L17 19l2.5-2.5"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    minus: '<path d="M5 12h14"/>',
    check: '<path d="M4 12.5l5 5L20 6.5"/>',
    warn: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><circle cx="12" cy="16.3" r="1"/>',
    save: '<path d="M5 5h11l3 3v11H5z"/><path d="M8.5 5v4.5h6V5M8 19v-5h8v5"/>',
    heart: '<path d="M12 20s-7-4.4-7-9.2A4 4 0 0 1 12 8a4 4 0 0 1 7-1.2C19 15.6 12 20 12 20z"/>',
    shared: '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c.8-3.3 3.2-5 6-5s5.2 1.7 6 5"/><path d="M16 5.6a3.2 3.2 0 0 1 0 4.8M18.4 3.4a6.4 6.4 0 0 1 0 9.2"/>',
  };
  const FILL2 = { grip6: 1 };
  const i2 = (n, c) => `<svg class="ic${FILL2[n] ? ' f' : ''}${c ? ' ' + c : ''}" viewBox="0 0 24 24">${IC2[n]}</svg>`;

  /* ---- words ---- */
  let LANG = 'en';
  const setLang = l => { LANG = l; };
  const T = (k, v) => {
    const s = (D.W[LANG] && D.W[LANG][k] != null) ? D.W[LANG][k] : D.W.en[k];
    return String(s == null ? k : s).replace(/\{(\w+)\}/g, (m, x) => (v && v[x] != null ? v[x] : m));
  };
  const songsN = n => (n === 1 ? T('one_song') : T('songs_n', { n: n }));

  /* ---- covers: the music mockup's placeholder covers, hash and gradient restated exactly from
     music-play-all-kit.js so a cover for a given title is the same pixel in both rounds ---- */
  const H = s => { let h = 0; for (let k = 0; k < s.length; k++) h = (h * 31 + s.charCodeAt(k)) >>> 0; return h % 360; };
  const cov = t => { const h = H(t); return `background:radial-gradient(circle at 72% 28%,hsla(${(h + 50) % 360},70%,72%,.55),transparent 46%),linear-gradient(135deg,hsl(${h} 42% 24%),hsl(${(h + 34) % 360} 52% 52%))`; };
  /* the four-tile collage the Playlists chip draws. An empty playlist has no artwork at all, so it
     gets the dashed tile rather than four blank squares pretending to be covers. */
  const collage = p => (p.cov && p.cov.length
    ? p.cov.slice(0, 4).map(t => `<i style="${cov(t)}"></i>`).join('')
    : '');

  let MARKN = 0;
  const SKC = { aurora: ['#7b6ef0', '#3fb6f5'], midnight: ['#19d6c6', '#2a8cf0'], noir: ['#f5b542', '#e0792f'] };
  const MARK = sk => {
    const [a, b] = SKC[sk] || SKC.aurora, id = 'mplJ' + (++MARKN);
    return `<svg class="mark" viewBox="12 20 76 76" aria-hidden="true"><defs><linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#${id})"/><g stroke="url(#${id})" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>`;
  };

  /* ================================================================= PHONE ================ */
  const SB_H = 34, AP_H = 56, BN_H = 94, KB_H = 224;

  const sbar = () => `<div class="mpa-sb"><span>9:41</span><span class="si"><i></i><i></i><i></i></span></div>`;
  const bnav = on => {
    const it = [['listen', 'Listen'], ['browse', 'Browse'], ['nowp', 'Playing'], ['queue', 'Queue']];
    return `<div class="mpa-bnv"><div class="brow">${it.map(([g, l]) => `<div class="mpa-bn${l.toLowerCase() === on ? ' on' : ''}"><span class="p">${ic(g)}</span><b>${l}</b></div>`).join('')}<div class="mpa-bn"><span class="p"><span class="av">ER</span></span><b>Profile</b></div></div><div class="safe"></div></div>`;
  };
  const mini = bottom => {
    const q = D.QUEUE[0];
    return `<div class="mpa-mini" style="bottom:${bottom}px"><div class="pg"><i></i></div><span class="gr"></span><div class="mpa-cv sm" style="${cov(q[2])}"></div><div class="tx"><div class="n">${q[0]}</div><div class="d">${q[1]}</div></div><span class="bt">${ic('pause')}</span><span class="bt">${ic('next')}</span></div>`;
  };

  /* a picker row, drawn as the Playlists chip draws one: collage · name · N songs */
  const plrow = (p, hot) => `<div class="mpl-plrow${hot ? ' hot' : ''}"><div class="col">${collage(p)}</div><div class="b"><div class="t">${p.name}</div><div class="s">${songsN(p.n)}</div></div></div>`;
  /* N1: the same row with a + tile. No second line — the playlist rows' second line is their
     count, and this row has none; inventing a hint there made the two rows disagree about what
     the second line is for, which the first render showed plainly. */
  const newrow = () => `<div class="mpl-plrow new"><div class="mpl-newtile">${i2('plus')}</div><div class="b"><div class="t">${T('new_playlist')}</div></div></div>`;
  /* N2: the same action as a button pinned under the list */
  const newbtn = () => `<div class="mpl-newbtn">${i2('plus')}${T('new_playlist')}</div>`;

  /* ---- surface 1: the picker, as a phone sheet ----
     o.list  'five' | 'none' | 'long'
     o.dir   'N1' (New playlist as the first row — the lean) | 'N2' (pinned button under the list)
     o.scroll  px the long list is scrolled by */
  function picker(o) {
    const dir = o.dir || 'N1';
    const src = o.list === 'long' ? D.MINE_LONG : o.list === 'none' ? [] : D.MINE;
    const head = `<div class="mpl-sh"><div class="tx"><h4>${T('add_playlist').replace('…', '')}</h4><div class="sub">${D.SONG.t} — ${D.SONG.a}</div></div></div>`;
    if (!src.length) {
      /* FR-4: with no playlist of the viewer's own, only New playlist. The row IS the invitation
         to act, so there is no empty-state card under it — a card saying "make one" beneath a
         button saying "New playlist" is the same sentence twice. One quiet line says what the
         list would hold, and nothing repeats the control. */
      return head
        + (dir === 'N1' ? newrow() : '')
        + `<div class="mpl-hint">${T('pl_none')}</div>`
        + (dir === 'N2' ? newbtn() : '');
    }
    const rows = src.map(p => plrow(p)).join('');
    const list = o.list === 'long'
      ? `<div class="mpl-plscroll"><div class="in" style="margin-top:${-(o.scroll || 0)}px">${dir === 'N1' ? newrow() : ''}${rows}</div><div class="mpl-fade"></div></div>`
      : (dir === 'N1' ? newrow() : '') + rows;
    return head + list + (dir === 'N2' ? `<div class="mpl-pinbar">${newbtn()}</div>` : '');
  }

  /* ---- surface 2: the name sheet (create and rename) ----
     o.state 'empty' | 'typing' | 'error' | 'rename' */
  function nameSheet(o) {
    const st = o.state || 'empty';
    const rename = st === 'rename';
    const title = rename ? T('pl_rename') : T('new_playlist');
    const btn = rename ? T('save') : T('pl_create');
    /* The sheet opens with the field already focused, as the mockup's newPlaylist() does, so the
       empty state is an empty focused field with a caret — NOT a placeholder repeating the label
       above it. The first render said "Playlist name" twice in two type sizes. */
    const inner = st === 'empty' ? `<span class="car"></span>`
      : st === 'typing' ? `<span class="val">Road Tr</span><span class="car"></span>`
        /* the error is a 400 from the server on a name that trims to nothing, so the field is
           NOT empty — it holds spaces. Drawn empty it contradicted the frame beside it, where an
           empty field keeps Create disabled and no error can be raised at all. */
        : st === 'error' ? `<span class="val">&nbsp;&nbsp;&nbsp;</span><span class="car"></span>`
          : `<span class="val sel">Road Trip</span>`;
    const cls = st === 'error' ? ' err' : ' on';
    return `<div class="mpl-sh"><h4>${title}</h4></div>`
      + `<div class="mpl-fld"><label>${T('pl_name')}</label><div class="inp${cls}">${inner}</div></div>`
      + (st === 'error' ? `<div class="mpl-err">${i2('warn')}${T('pl_name_empty')}</div>` : '')
      + `<div class="mpl-sbtn${st === 'empty' ? ' off' : ''}">${btn}</div>`
      /* Cancel is a text row, not a second bordered button: with Create shown-and-disabled, a
         bordered Cancel reads as the stronger of the two and sends the eye to the way out. */
      + `<div class="mpl-cancel">${T('cancel')}</div>`;
  }

  /* ---- surface 5: the delete confirm ---- */
  const confirmSheet = name => `<div class="mpl-cfm"><h4>${T('pl_delete_q', { name: name })}</h4><p>${T('pl_delete_line')}</p><div class="acts"><div class="bt">${T('cancel')}</div><div class="bt bad">Delete</div></div></div>`;

  /* ---- the song ⋯ sheet (FR-4 live, FR-9's row inside your own playlist) ----
     o.inPlaylist  draws Remove from playlist directly after Add to playlist… */
  function songSheet(o) {
    const s = D.SONG;
    const r = (g, label, cls) => `<div class="mpl-row${cls ? ' ' + cls : ''}"><span class="gl">${g}</span><span><span class="nm">${label}</span></span></div>`;
    return `<div class="mpl-sh" style="gap:12px"><div class="mpl-cv sm" style="${cov(s.al)}"></div><div class="tx"><h4>${s.t}</h4><div class="sub">${s.a} — ${s.al}</div></div></div>`
      + r(i2('next'), T('play_next'))
      + r(ic('queue'), T('add_queue'))
      /* no Coming later tag, and not dimmed: this is the row the round turns on */
      + r(ic('plist'), T('add_playlist'))
      + (o.inPlaylist ? r(i2('minusc'), T('pl_remove')) : '')
      + r(ic('disc'), T('go_album'))
      + r(ic('person'), T('go_artist'))
      + r(i2('heart'), T('my_list'));
  }

  /* ---- surfaces 3, 4, 6: a detail page body (playlist or album) ----
     o.kind  'playlist' | 'album'
     o.pl    the playlist (when kind is playlist)
     o.mine  draws the ⋯; false draws the shared header, which has none
     o.menu  the ⋯ menu is open
     o.edit  edit mode
     o.lift  index of the row drawn lifted mid-drag
     o.swipe index of the row drawn mid-swipe */
  function detailBody(o) {
    const pl = o.kind === 'album' ? null : (o.pl || D.MINE[3]);   /* Road Trip */
    const title = pl ? pl.name : D.ALBUM.t;
    const art = pl ? (pl.cov[0] || pl.name) : D.ALBUM.t;
    const n = pl ? pl.n : D.ALBUM.n;
    const tracks = pl ? D.ROAD_TRIP : D.ALBUM_TRACKS.map(([t, d]) => [t, D.ALBUM.a, D.ALBUM.t, d]);

    /* the header: Play · Shuffle · ⋯ , or Done alone in edit mode (FR-10).
       An EMPTY playlist of your own cannot be played, so Play and Shuffle are drawn shown and
       disabled rather than removed — the owner's own rule, and the one round 9 got backwards.
       The brief does not say which; REPORT.md puts it back as a question. */
    const emptyPl = !!(pl && n === 0);
    let acts;
    if (o.edit) {
      /* Done is the way OUT of edit mode, not the action of it — dragging is. It takes the
         secondary fill so the rows lead; a full-width gradient slab made Done the loudest thing
         on a screen whose whole content is the list. */
      acts = `<div class="mpl-acts"><div class="bt done">${T('pl_done')}</div></div>`;
    } else {
      const mb = o.mine === false ? '' : `<div class="bt mb${o.menu ? ' hot' : ''}">${ic('more')}</div>`;
      if (o.editions === 'merged') {
        /* The proposal. Both ▾s give up their split and the ONE ⋯ carries every secondary way to
           act on the album: Play extras, Shuffle extras, Add to playlist…. Three controls, every
           label whole, and the page gains a single place to look for "anything else" instead of
           two dropdowns plus a ⋯. Drawn because the straight reading of FR-6 does not fit. */
        acts = `<div class="mpl-acts"><div class="bt pri">${ic('play')}Play album</div><div class="bt">${ic('shuffle')}${T('shuffle')}</div>${mb}`
          + (o.menu ? `<div class="mpl-menu"><div class="mi">${ic('play')}Play extras</div><div class="mi">${ic('shuffle')}Shuffle extras</div><div class="sep"></div><div class="mi">${ic('plist')}${T('add_playlist')}</div></div>` : '') + `</div>`;
      } else if (o.editions) {
        /* An album that has editions ALREADY carries two split controls here (.re-acts /
           .re-split in ravilo-music.css): Play album ▾ and Shuffle ▾. Adding the ⋯ makes it
           five tap targets across 324 dp. Drawn so the crowding can be judged rather than
           discovered during the build. */
        acts = `<div class="mpl-acts ed"><div class="sp"><div class="bt pri a">${ic('play')}<span class="lb">Play album</span></div><div class="bt pri dd">${ic('chev')}</div></div>`
          + `<div class="sp sh"><div class="bt a">${ic('shuffle')}<span class="lb">${T('shuffle')}</span></div><div class="bt dd">${ic('chev')}</div></div>${mb}`
          + (o.menu ? albumMenu() : '') + `</div>`;
      } else {
        acts = `<div class="mpl-acts"><div class="bt pri${emptyPl ? ' off' : ''}">${ic('play')}${T('play')}</div><div class="bt${emptyPl ? ' off' : ''}">${ic('shuffle')}${T('shuffle')}</div>${mb}`
          + (o.menu ? (o.kind === 'album' ? albumMenu() : menuPhone(o)) : '') + `</div>`;
      }
    }

    let body;
    if (emptyPl) {
      body = `<div class="mpl-empty"><b>${T('pl_empty')}</b><span>${T('pl_empty_hint')}</span></div>`;
    } else if (o.edit) {
      body = `<div class="mpl-ed">${editList(tracks, o)}</div>`;
    } else if (pl) {
      /* A playlist's rows are the shipped .mu-song: cover · title · artist — not the album
         page's numbered .mu-tr. The brief says the playlist page stays as it is apart from what
         this round adds, and the first render had quietly restyled it. */
      body = `<div class="mpa-list" style="margin-top:14px">${tracks.map(([t, a, al, d]) =>
        `<div class="mpa-song"><div class="mpl-cv sm" style="${cov(al)}"></div><div class="b"><div class="t">${t}</div><div class="s">${a} — ${al}</div></div><span class="ln">${d}</span><span class="mo">${ic('more')}</span></div>`).join('')}</div>`;
    } else {
      body = `<div class="mpl-trs">${tracks.map(([t, , , d], k) =>
        `<div class="mpl-tr"><span class="n">${k + 1}</span><span class="t">${t}</span><span class="ln">${d}</span><span class="mo">${ic('more')}</span></div>`).join('')}</div>`;
    }

    if (pl) {
      /* THE SHIPPED PLAYLIST HEADER (playlistHTML in ravilo-music.js): .mu-ahead — centred, with
         NO artwork at all, the title, the count, and nothing else. A playlist has no cover of its
         own on this page today and this round does not give it one.
         `art` is therefore unused on this branch; it is kept for the album branch below. */
      return `<div class="mpl-ahead"><div class="mpl-dback">‹</div><div class="mpl-dt">${title}</div>`
        + `<div class="mpl-dm c">${songsN(n)}${o.badge ? `<span class="mpl-shared">${i2('shared')}${T('shared_by')}</span>` : ''}</div></div>`
        + `<div class="mpl-dbody">${acts}${body}<div style="height:40px"></div></div>`;
    }
    return `<div class="mpl-dtop"><div class="mpl-cv" style="${cov(art)};border-radius:0"></div><div class="mpl-dback">‹</div></div>`
      + `<div class="mpl-dbody"><div class="mpl-dt">${title}</div>`
      + `<div class="mpl-dby">${D.ALBUM.a}</div>`
      + `<div class="mpl-dm"><span>${D.ALBUM.year}</span><span>·</span><span>${songsN(n)}</span><span>·</span><span>${D.ALBUM.len}</span></div>`
      + acts + body + '<div style="height:40px"></div></div>';
  }

  /* The edit list, with the drag drawn as it reads: the row has LEFT its slot (an empty dashed
     gap stays behind at its index) and is floating two slots lower with the lift shadow. The
     first render translated the row and let it cover its neighbour, which read as a missing song
     rather than a move. */
  function editList(tracks, o) {
    if (o.lift == null) return tracks.map((t, k) => editRow(t, k, o)).join('');
    const from = o.lift, to = Math.min(from + 2, tracks.length - 1);
    const rest = tracks.filter((_, k) => k !== from);
    let out = '';
    rest.forEach((t, k) => {
      if (k === from) out += '<div class="mpl-gap"></div>';
      out += editRow(t, k, { swipe: null, lift: null });
      if (k === to - 1) out += editRow(tracks[from], -1, { lift: null, swipe: null, forceLift: true });
    });
    return out;
  }

  /* an edit-mode row: the queue's row, its grip, its lifted look and its swipe panel */
  function editRow(t, k, o) {
    /* `o.swipe != null` first: the floating row is rendered with k = -1, and a bare `k === o.swipe`
       made it match a sentinel -1 and take the swipe branch — a red Remove panel appeared in the
       drag frame, which only the picture showed. */
    if (o.swipe != null && k === o.swipe) {
      return `<div class="mpl-qr"><div class="rm">${i2('close')}${T('remove')}</div><div class="in" style="transform:translateX(-132px)"><div class="mpl-cv sm" style="${cov(t[2])}"></div><div class="b"><div class="t">${t[0]}</div><div class="s">${t[1]}</div></div><span class="mpl-grip">${i2('grip')}</span></div></div>`;
    }
    const lifted = !!o.forceLift;
    return `<div class="mpl-qr${lifted ? ' lift' : ''}"><div class="in"><div class="mpl-cv sm" style="${cov(t[2])}"></div><div class="b"><div class="t">${t[0]}</div><div class="s">${t[1]}</div></div><span class="mpl-grip">${i2('grip')}</span></div></div>`;
  }

  /* the playlist page's ⋯ menu (FR-8): Edit, Rename, Delete playlist; an empty one drops Edit */
  function menuPhone(o) {
    const empty = o.pl && o.pl.n === 0;
    return `<div class="mpl-menu">`
      + (empty ? '' : `<div class="mi">${i2('reorder')}${T('pl_edit')}</div>`)
      + `<div class="mi">${i2('pencil')}${T('pl_rename')}</div>`
      + `<div class="sep"></div>`
      + `<div class="mi bad">${i2('trash')}${T('pl_delete')}</div></div>`;
  }

  /* the album page's ⋯ menu (FR-6): one row */
  const albumMenu = () => `<div class="mpl-menu"><div class="mi">${ic('plist')}${T('add_playlist')}</div></div>`;

  /* ---- surface 7: the Queue tab, with Save as playlist beside Clear queue ---- */
  function queueBody() {
    const row = (t, now) => `<div class="mpl-qr"><div class="in"${now ? ' style="background:rgba(var(--fg),.05)"' : ''}><div class="mpl-cv sm" style="${cov(t[2])}"></div><div class="b"><div class="t"${now ? ' style="color:var(--accent)"' : ''}>${t[0]}</div><div class="s">${t[1]}</div></div>${now ? '' : `<span class="mpl-grip">${i2('grip')}</span>`}</div></div>`;
    return `<div class="mpa-row first"><h3>${T('queue')}<span class="more">7 songs · 28 min left</span></h3></div>`
      + `<div class="mpl-qlab">${T('now_playing')}</div>${row(D.QUEUE[0], true)}`
      /* The two actions take a line of their own under the heading rather than sharing it. On the
         same line at 360 dp the English wrapped UP NEXT to two lines and the Faroese
         "Goym sum spælilista" pushed Clear queue clean off the right edge. They are still beside
         each other, which is what FR-7 asks; they are just not beside the word as well. */
      + `<div class="mpl-qlab">${T('up_next')}</div>`
      + `<div class="mpl-qacts"><span class="mpl-qact">${i2('save')}${T('pl_save_queue')}</span><span class="mpl-qact">${i2('close')}${T('clear_queue')}</span></div>`
      + D.QUEUE.slice(1).map(t => row(t)).join('')
      + '<div style="height:30px"></div>';
  }

  /* ---- the whole phone frame ---- */
  function phone(o) {
    o = Object.assign({ skin: 'aurora', page: 'playlist', playing: true }, o);
    let h = sbar();
    const detail = o.page === 'playlist' || o.page === 'album';
    let top = SB_H;

    if (!detail) {
      h += `<div class="mpa-apb" style="top:${SB_H}px"><div class="mpa-brand">${MARK(o.skin)}<span class="wm">Ravilo</span><span class="nt">♪</span></div><span class="sp"></span><span class="mpa-ib">${ic('cast')}</span></div>`;
      top += AP_H;
    }

    const body = detail ? detailBody(o) : o.page === 'queue' ? queueBody() : '';
    h += `<div class="mpa-cnt" style="top:${top}px"><div class="mpa-in" style="padding-bottom:200px;margin-top:${-(o.pscroll || 0)}px">${body}</div></div>`;

    const miniB = BN_H + 8;
    if (o.playing && !o.sheet) h += mini(miniB);

    /* an overlay sheet dims the page under it */
    if (o.sheet) {
      const kb = o.sheet === 'name' && o.kb !== false;
      const inner = o.sheet === 'picker' ? picker(o)
        : o.sheet === 'name' ? nameSheet(o)
          : o.sheet === 'confirm' ? confirmSheet(o.plName || 'Road Trip')
            : songSheet(o);
      h += `<div class="mpl-scrim"></div><div class="mpl-sheet"${kb ? ` style="bottom:${KB_H}px"` : ''}><div class="mpl-grab"></div>${inner}</div>`;
      if (kb) h += keyboard();
    }

    if (o.toasts) h += toastStack(o);
    h += bnav(o.page === 'queue' ? 'queue' : 'browse');
    if (o.pins) h += o.pins;
    return `<div class="fr mpa-ph" data-skin="${o.skin}" lang="${LANG}">${h}</div>`;
  }

  /* the system keyboard. Static keys: no caret blink and no key animation, because an infinite
     animation breaks the screenshot render. */
  function keyboard() {
    const r1 = 'qwertyuiop'.split(''), r2 = 'asdfghjkl'.split(''), r3 = 'zxcvbnm'.split('');
    const k = c => `<span class="k">${c}</span>`;
    return `<div class="mpl-kb">`
      + `<div class="kr">${r1.map(k).join('')}</div>`
      + `<div class="kr">${r2.map(k).join('')}</div>`
      + `<div class="kr"><span class="k w">⇧</span>${r3.map(k).join('')}<span class="k w">⌫</span></div>`
      + `<div class="kr"><span class="k w">123</span><span class="k sp"> </span><span class="k go">done</span></div>`
      + `<div class="safe"></div></div>`;
  }

  /* the six toast lines, in the shipped toast style, one frame */
  function toastStack(o) {
    const list = D.TOASTS.map(([, key, v]) => `<div class="mpa-tst">${T(key, v)}</div>`).join('');
    return `<div class="mpl-tstack" style="bottom:${BN_H + (o.playing ? 84 : 16)}px">${list}</div>`;
  }

  /* ================================================================ DESKTOP =============== */
  /* The sidebar and the player bar restate music-play-all-kit.js's dside()/capbar, which are not
     exported. Same classes, same values; Playlists now carries 6, the five of the viewer's own
     plus the one shared with them, which is what FR-2 says the list shows. */
  function dside(on) {
    const nav = [
      [null, [['phones', 'Listen', 'listen'], ['wave', 'Playing'], ['lq', 'Queue', 'queue']]],
      ['Library', [['person', 'Artists', 'artists', 20], ['disc', 'Albums', 'albums', 30], ['note', 'Songs', 'songs', 385], ['tag', 'Genres', 'genres', 18], ['plist', 'Playlists', 'playlists', 6], ['book', 'Audiobooks', null, 1]]],
    ];
    return `<aside class="mpa-side"><div class="mpa-lights"><i></i><i></i><i></i></div><div class="mpa-seg"><b>Films &amp; series</b><b class="on">Music</b></div><div class="mpa-sf">${ic('search')}<span>Search music</span></div><div class="mpa-nav">${nav.map(([hh, it]) => (hh ? `<h6>${hh}</h6>` : '') + it.map(([g, l, k, n]) => `<a class="${k && k === on ? 'on' : ''}">${ic(g)}<span>${l}</span>${n != null ? `<span class="n">${n}</span>` : ''}</a>`).join('')).join('')}</div><div class="mpa-who"><span class="av">E</span><div><div class="nm">Eyð</div><div class="sub">Settings · Sign out</div></div></div></aside>`;
  }
  const capbar = () => {
    const q = D.QUEUE[0];
    return `<div class="mpa-capbar"><span class="art" style="${cov(q[2])}"></span><div class="tx"><div class="t">${q[0]}</div><div class="s">${q[1]}</div></div><div class="ctl">${ic('prev')}<span class="pp">${ic('pause')}</span>${ic('next')}</div><div class="sk"><span>1:04</span><i></i><span>3:36</span></div><div class="r">${ic('lyr')}${ic('lq')}${ic('cast')}${ic('vol')}</div></div>`;
  };

  const dplrow = (p, hot) => `<div class="mpl-dpl${hot ? ' hot' : ''}"><div class="col">${collage(p)}</div><div class="b"><div class="t">${p.name}</div><div class="s">${songsN(p.n)}</div></div></div>`;
  const dnewrow = () => `<div class="mpl-dpl"><div class="mpl-dnew">${i2('plus')}</div><div class="b"><div class="t">${T('new_playlist')}</div></div></div>`;

  /* the desktop picker's list, shared by the popover and the dialog so the two directions differ
     only in the container — which is the whole question being asked */
  function dpickList(o) {
    const dir = o.dir || 'N1';
    const src = o.list === 'long' ? D.MINE_LONG : o.list === 'none' ? [] : D.MINE;
    if (!src.length) {
      return (dir === 'N1' ? dnewrow() : '')
        + `<div class="mpl-dhint">${T('pl_none')}</div>`
        + (dir === 'N2' ? dnewbtnEl() : '');
    }
    const rows = src.map((p, k) => dplrow(p, k === o.hot)).join('');
    const inner = (dir === 'N1' ? dnewrow() + '<div class="mpl-dsep"></div>' : '') + rows;
    const scrolled = o.list === 'long'
      ? `<div class="sc"><div style="margin-top:${-(o.scroll || 0)}px">${inner}</div><div class="mpl-dfade"></div></div>`
      : inner;
    return scrolled + (dir === 'N2' ? `<div class="mpl-dsep"></div>${dnewbtnEl()}` : '');
  }
  const dnewbtnEl = () => `<div class="mpl-dnewbtn">${i2('plus')}${T('new_playlist')}</div>`;

  /* the desktop detail header */
  function dhead(o) {
    const pl = o.kind === 'album' ? null : (o.pl || D.MINE[3]);
    const title = pl ? pl.name : D.ALBUM.t;
    const kind = pl ? 'Playlist' : 'Album';
    const empty = !!(pl && pl.n === 0);
    /* A playlist's artwork is its four-tile collage everywhere else in Ravilo, so the desktop
       page uses the collage rather than one member album's cover blown up. (The phone page has
       no artwork at all, which is what it ships with; the desktop page has no shipped version to
       disagree with.) An empty playlist has no artwork, so it gets the dashed tile. */
    const art = pl
      ? (pl.cov.length ? `<div class="art col">${collage(pl)}</div>` : `<div class="art col none">${ic('note')}</div>`)
      : `<div class="art" style="${cov(D.ALBUM.t)}"></div>`;
    const ml = pl
      ? `<span>${songsN(pl.n)}</span>${o.badge ? `<span class="mpl-shared">${i2('shared')}${T('shared_by')}</span>` : ''}`
      : `<span>${D.ALBUM.a} · ${D.ALBUM.year} · ${songsN(D.ALBUM.n)} · ${D.ALBUM.len}</span>`;
    let btns;
    if (o.edit) {
      btns = `<div class="btns"><div class="mpl-bt">${T('pl_done')}</div></div>`;
    } else {
      const mb = o.mine === false ? '' : `<div class="mpl-bt mpl-dmb${o.menu ? ' hot' : ''}">${ic('more')}</div>`;
      btns = `<div class="btns"><div class="mpl-bt ${empty ? 'off' : 'pri'}">${ic('play')}${T('play')}</div><div class="mpl-bt${empty ? ' off' : ''}">${ic('shuffle')}${T('shuffle')}</div>${mb}${o.menu ? dmenu(o) : ''}</div>`;
    }
    return `<div class="mpl-dhead">${art}<div><div class="k">${kind}</div><h1>${title}</h1><div class="ml">${ml}</div>${btns}</div></div>`;
  }
  function dmenu(o) {
    if (o.kind === 'album') return `<div class="mpl-dmenu" style="left:auto;right:0"><div class="mi hot">${ic('plist')}${T('add_playlist')}</div></div>`;
    const empty = o.pl && o.pl.n === 0;
    return `<div class="mpl-dmenu" style="left:auto;right:0">`
      + (empty ? '' : `<div class="mi">${i2('reorder')}${T('pl_edit')}</div>`)
      + `<div class="mi">${i2('pencil')}${T('pl_rename')}</div><div class="sep"></div>`
      + `<div class="mi bad">${i2('trash')}${T('pl_delete')}</div></div>`;
  }

  /* the desktop track table. In edit mode the # column becomes the grip; E1 puts a × at the row's
     end on hover, E2 leaves the row's own ⋯ as the only way to remove. */
  function dtrk(o) {
    const pl = o.kind === 'album' ? null : (o.pl || D.MINE[3]);
    const rows = pl ? D.ROAD_TRIP : D.ALBUM_TRACKS.map(([t, d]) => [t, D.ALBUM.a, D.ALBUM.t, d]);
    const edit = !!o.edit, e2 = o.edir === 'E2';
    const hd = `<div class="hd"><span>${edit ? '' : '#'}</span><span>Title</span><span>Artist</span><span>Album</span><span>Time</span><span></span></div>`;
    /* the drag reads as a move, not as a missing song: the row has left its slot (an empty dashed
       gap stays at its index) and floats two slots lower with the lift shadow */
    const dragRow = ([t, a, al, d]) => `<div class="tr lift"><span class="gp">${i2('grip6')}</span><span class="tt">${t}</span><span class="dm">${a}</span><span class="dm">${al}</span><span class="du">${d}</span><span class="en"></span></div>`;
    const seq = (edit && o.lift != null)
      ? (() => { const r = rows.filter((_, k) => k !== o.lift); r.splice(o.lift, 0, null); r.splice(Math.min(o.lift + 3, r.length), 0, rows[o.lift]); return r; })()
      : rows;
    const dragAt = (edit && o.lift != null) ? Math.min(o.lift + 3, rows.length) : -1;
    const body = seq.map((row, k) => {
      if (row === null) return '<div class="mpl-dgap"></div>';
      if (k === dragAt) return dragRow(row);
      const [t, a, al, d] = row;
      const hot = k === o.hot;
      const first = edit ? `<span class="gp">${i2('grip6')}</span>` : `<span class="nr">${k + 1}</span>`;
      const end = edit && !e2
        ? `<span class="en">${hot ? i2('close') : ''}</span>`
        : `<span class="en">${hot ? ic('more') : ''}</span>`;
      return `<div class="tr${hot ? ' hot' : ''}">${first}<span class="tt">${t}</span><span class="dm">${a}</span><span class="dm">${al}</span><span class="du">${d}</span>${end}</div>`;
    }).join('');
    return `<div class="mpl-dtrk${edit ? ' edit' : ''}">${hd}${body}</div>`;
  }

  /* the desktop queue panel (FR-7) */
  function dqueue() {
    const row = (t, now) => `<div class="mpl-dqr${now ? ' now' : ''}"><span class="c" style="${cov(t[2])}"></span><div class="b"><div class="t">${t[0]}</div><div class="s">${t[1]}</div></div>${now ? '' : `<span class="g">${i2('grip6')}</span>`}</div>`;
    return `<div class="mpl-dq"><h5>${T('now_playing')}</h5>${row(D.QUEUE[0], true)}`
      /* the same crowding as the phone, for the same reason: at 300 px the two pills and the
         heading on one line wrapped all three, English included. The actions take their own row. */
      + `<h5>${T('up_next')}</h5><div class="mpl-dqacts"><span class="mpl-dqact">${i2('save')}${T('pl_save_queue')}</span><span class="mpl-dqact">${i2('close')}${T('clear_queue')}</span></div>`
      + D.QUEUE.slice(1, 6).map(t => row(t)).join('') + '</div>';
  }

  function desk(o) {
    o = Object.assign({ skin: 'aurora', page: 'playlist' }, o);
    const side = o.page === 'queue' ? 'queue' : o.page === 'playlist' ? 'playlists' : 'albums';
    let b = '';
    if (o.page === 'playlist' || o.page === 'album') {
      const pl = o.kind === 'album' ? null : (o.pl || D.MINE[3]);
      b = dhead(o) + (pl && pl.n === 0
        ? `<div class="mpl-empty" style="margin:10px 0 0;max-width:460px"><b>${T('pl_empty')}</b><span>${T('pl_empty_hint')}</span></div>`
        : dtrk(o));
    } else if (o.page === 'playlists') {
      b = `<div class="mpa-dh"><div class="h1">${T('playlists')}</div></div><div class="mpa-ml">6 playlists</div>`
        + `<div style="max-width:520px">${D.MINE_LONG.slice(0, 0).join('')}${D.MINE.concat([D.SHARED]).map(p => dplrow(p)).join('')}</div>`;
    } else {
      b = `<div class="mpa-dh"><div class="h1">Songs</div></div><div class="mpa-ml">385 songs</div>` + dtrk({ kind: 'album' });
    }

    let ov = '';
    if (o.overlay === 'pop') {
      /* anchored to the ⋯ the frame draws: the popover's left edge and beak line up with it */
      const x = o.popX != null ? o.popX : 236, y = o.popY != null ? o.popY : 318;
      /* the beak sits under the ⋯ the popover came from, which is not always near its left edge:
         a popover opened from a song row's ⋯ is anchored by its RIGHT edge instead */
      const bx = o.beakX != null ? o.beakX : 18;
      ov = `<div class="mpl-pop${o.popUp ? ' up' : ''}" style="left:${x}px;top:${y}px"><span class="beak" style="left:${bx}px"></span>`
        + `<div class="hd">${T('add_playlist').replace('…', '')}<span>${D.SONG.t} — ${D.SONG.a}</span></div>`
        + dpickList(o) + `</div>`;
    } else if (o.overlay === 'dlg-picker') {
      ov = `<div class="mpl-dscrim"></div><div class="mpl-dlg"><div class="hd"><h4>${T('add_playlist').replace('…', '')}</h4><div class="sub">${D.SONG.t} — ${D.SONG.a}</div></div><div class="bd rows">${dpickList(o)}</div><div class="ft"><div class="mpl-bt">${T('cancel')}</div></div></div>`;
    } else if (o.overlay === 'dlg-name') {
      const st = o.state || 'empty', rename = st === 'rename';
      const inner = st === 'empty' ? `<span class="ph">${T('pl_name')}</span>`
        : st === 'typing' ? `Road Tr<span class="car"></span>`
          : st === 'error' ? `<span class="car"></span>`
            : `<span class="sel">Road Trip</span>`;
      ov = `<div class="mpl-dscrim"></div><div class="mpl-dlg sm"><div class="hd"><h4>${rename ? T('pl_rename') : T('new_playlist')}</h4></div>`
        + `<div class="bd"><div class="mpl-dlabel">${T('pl_name')}</div><div class="mpl-dfld${st === 'empty' ? '' : st === 'error' ? ' err' : ' on'}">${inner}</div>`
        + (st === 'error' ? `<div class="mpl-derr">${i2('warn')}${T('pl_name_empty')}</div>` : '') + `</div>`
        + `<div class="ft"><div class="mpl-bt">${T('cancel')}</div><div class="mpl-bt ${st === 'empty' ? 'off' : 'pri'}">${rename ? T('save') : T('pl_create')}</div></div></div>`;
    } else if (o.overlay === 'dlg-confirm') {
      ov = `<div class="mpl-dscrim"></div><div class="mpl-dlg sm"><div class="hd"><h4>${T('pl_delete_q', { name: o.plName || 'Road Trip' })}</h4></div><div class="bd"><div style="font-size:13px;line-height:1.55;color:var(--ink-soft)">${T('pl_delete_line')}</div></div><div class="ft"><div class="mpl-bt">${T('cancel')}</div><div class="mpl-bt bad">Delete</div></div></div>`;
    }

    const q = o.queue ? dqueue() : '';
    const tst = o.toast ? `<div class="mpa-dtst">${T(o.toastKey || 'pl_added', { name: 'Road Trip', n: 10 })}</div>` : '';
    const inner = `${dside(side)}<div class="mpa-main"><div class="mpa-mtb"><span class="mpa-gl">${ic('back')}${ic('fwd', 'dim')}</span><span class="sp" style="flex:1"></span><span class="mpa-gl">${ic('cast')}</span></div><div class="mpa-body">${b}</div>${q}${capbar()}${tst}${ov}${o.pins || ''}</div>`;
    return `<div class="fr mpa-dk" data-skin="${o.skin}" lang="${LANG}">${inner}</div>`;
  }

  window.MPLK = { phone, desk, setLang, T, cov, i2, ic };
})();
