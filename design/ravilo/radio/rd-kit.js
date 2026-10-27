/* rd-kit.js — the builders. Every frame in frames.html is one call into this file.

   THE ADOPTED MOCKUP, not the round. The owner picked on 2026-10-27 (card on DEB-2628): the mark
   is **A, the corner glyph**, the TV radio player is **A, the Now-playing layout**, and the Radio
   row is **D, the last row**. What is drawn here is those three and nothing else — the two
   rejected marks, the second player, the three other row placements and the wide tile are gone
   with the round, and git history holds them at the round's own commit (`design/incoming/`,
   2026-10-27). So there is no `mark` parameter and no `pos` parameter: the mark is the mark, and
   the row is last.

   The rule this file follows, everywhere: a station is built by the SAME function as a channel,
   with `kind === 'radio'` as the only input that differs. That is FR-6's "the one difference from
   a channel tile" made true in the source and not only in the caption — if a station tile and a
   channel tile ever diverge in anything but the mark and the no-guide line, it is a bug here and
   not a decision.
*/
window.RDKit = (function (D) {
  'use strict';

  const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const fill = (s, v) => String(s).replace(/\{(\w+)\}/g, (_, k) => (k in v ? v[k] : '{' + k + '}'));
  let L = D.I18N.en;
  const setLang = c => { L = D.I18N[c] || D.I18N.en; };
  const t = (k, v) => (v ? fill(L[k], v) : L[k]);
  const fmt = D.fmt, pct = D.pct;

  /* Mint every SVG id from a counter. No paint server is defined in these glyphs, but the rule is
     cheap to keep and a later revision that adds a gradient would otherwise be the trap
     PROCESS.md names: a gradient defined in a display:none frame paints nothing in a visible one. */
  let uid = 0;
  const nextId = p => p + '-' + (++uid);

  /* =============================================================================================
     THE MARK (FR-6, owner's pick A) — a corner glyph: the broadcast arcs over a dot, in a filled
     chip, in the one corner a channel tile leaves empty. It does not rely on colour, it is a
     shape, so its cost does not grow with the language, and the one drawing carries to six
     surfaces at six sizes. It carries the accessible label *Radio* as an `aria-label` on the chip.
     ============================================================================================= */

  /* The glyph, at a size the caller picks: ((•)) — a dot with two arcs EITHER SIDE of it.
     NOT a fan of arcs rising from a dot. The first render of this round drew that, and every one
     of the four frames read as a Wi-Fi icon: an upward fan over a dot is the signal-strength mark
     and a TV tile is the last place to borrow it, because the thing beside it is a stream that
     really can lose signal. Arcs on BOTH sides of a centred dot is the broadcast mark, it is
     symmetrical, and at three metres it is a silhouette rather than a word. */
  /* `w` is optional. WITH a width the glyph is sized inline, which is how every `rd-*` surface in
     this file draws it. WITHOUT one it carries no size and no `rd-waves` class at all, and the
     stroke treatment and the size come from the stylesheet — which is what the admin does, because
     there the rules are `wf.css`'s `.radiomark svg` and the archive should be drawing the
     production rule rather than standing in front of it. */
  const wavesSVG = (w) => '<svg viewBox="0 0 24 24"'
    + (w ? ' class="rd-waves" style="width:' + w + 'px;height:' + w + 'px"' : '') + '>'
    + '<circle cx="12" cy="12" r="2.3"/>'
    + '<path d="M15.7 8.3A5.2 5.2 0 0 1 15.7 15.7"/>'
    + '<path d="M18.2 5.8A8.8 8.8 0 0 1 18.2 18.2"/>'
    + '<path d="M8.3 8.3A5.2 5.2 0 0 0 8.3 15.7"/>'
    + '<path d="M5.8 5.8A8.8 8.8 0 0 0 5.8 18.2"/>'
    + '</svg>';

  /* The mark, at a size the caller picks. The chip's PLACE is the stylesheet's, never a parameter:
     the corner on a tile, inline in the guide cell, in Now/Next and in the zap banner. */
  const mark = (size) => '<span class="rd-markA" aria-label="' + esc(t('radio')) + '" role="img">'
    + wavesSVG(size || 26) + '</span>';

  /* The LIVE badge, R177's own and unchanged — the mark carries the kind, so the badge does not. */
  const liveBadge = () => '<span class="badge-live">' + esc(t('live')) + '</span>';

  /* The channel logo plate — the mock's own `.lt-chlogo`: initials on the channel's gradient. */
  const logo = (ch, cls) => '<span class="lt-chlogo ' + (cls || '')
    + '" style="background:' + ch.grad + '">' + esc(ch.initials) + '</span>';

  /* =============================================================================================
     TV — the On now row (FR-6). `.crow` / `.track` / `.lt-onnow` are the shipped components; the
     round adds the mark and the no-guide line and nothing else.
     ============================================================================================= */
  function onNowCard(ch, opt) {
    const o = opt || {};
    const radio = ch.kind === 'radio';
    const focused = o.focused ? ' focused' : '';
    const hasGuide = !!ch.now;
    let h = '<div class="lt-onnow' + focused + (radio ? ' rd-radio' : '') + '">'
      + '<div class="art"><div class="grad" style="background:' + ch.grad + '"></div><div class="scrim"></div>'
      + liveBadge()
      /* The chip sits in the corner the LIVE badge does not use. On a TV channel that corner is
         empty, which is the whole of the mark's argument. */
      + (radio ? mark(30) : '')
      + '<div class="biglogo">' + esc(ch.initials) + '</div></div>'
      + '<div class="meta"><div class="chrow"><span class="chnum">' + ch.num + '</span>'
      + '<span class="chname">' + esc(ch.name) + '</span></div>';
    if (hasGuide) {
      h += '<div class="ptitle">' + esc(ch.now.title) + '</div>'
        + '<div class="ptime">' + fmt(ch.now.start) + '–' + fmt(ch.now.end)
        + (ch.next ? ' · ' + esc(t('next')) + ': ' + esc(ch.next.title) : '') + '</div>'
        + '<div class="pbar"><i style="width:' + pct(ch.now).toFixed(1) + '%"></i></div>';
    } else {
      /* FR-6: "A station with no guide shows its name and Live radio in place of a programme,
         with no progress bar." Read literally that is two things to ADD, and the first render of
         this round did exactly that — the tile said *Harbour Radio 2* in the channel line, then
         *Harbour Radio 2* again at the programme's weight, then *Live radio*. The name is
         already on the tile. So the name stays where a channel's name is, and what goes in the
         programme's place is *Live radio* alone, at the programme's weight.
         The progress bar is ABSENT, not an empty track: an empty track at three metres is a bar
         at zero, which says a programme has just started. */
      h += '<div class="ptitle rd-noguide-t">' + esc(t('live_radio')) + '</div>';
    }
    return h + '</div></div>';
  }

  /* The leading Open-TV-guide tile, the shipped row's own. `shift` is the row's scroll, and it is
     a NEGATIVE MARGIN on this tile rather than a transform on the track. A transform moves the
     track's own box, and `.track` is `overflow-x: auto` — so the first render clipped the row at
     1 672 px instead of 1 920 and lost a third of the very tile the frame was focusing. The
     content moves inside the box; the box does not move. */
  const guideTile = (shift) => '<div class="lt-guidetile"'
    + (shift ? ' style="margin-left:-' + shift + 'px"' : '') + '><div class="grid-ic">▦</div>'
    + '<div class="gt-k">' + esc(t('live_tv')) + '</div>'
    + '<div class="gt-t">' + esc(t('open_guide')) + '</div>'
    + '<div class="gt-s">' + esc(t('guide_sub')) + '</div></div>';

  /* The row. `focusNum` is the lineup number of the focused tile, so a frame says which tile is
     focused in the brief's own vocabulary rather than by array index. */
  function onNowRow(opt) {
    const o = opt || {};
    const cards = D.ROW.map(ch => onNowCard(ch, { focused: ch.num === o.focusNum })).join('');
    /* `scrollX` is what the app's focus engine does, written as data: six 340 px tiles behind a
       232 px guide tile are 2 168 px of track in a 1 920 px row, so a frame that focuses the FIFTH
       tile has to have scrolled, and a frame that draws it unscrolled would be a picture of a
       state the app never shows. The number is the shift that brings the focused tile's right
       edge inside the 64 px gutter — measured, not guessed. */
    return '<div class="crow lt-onnow-row">'
      + '<div class="crow-head lt-onnow-head"><h2><span class="live-dot"></span>' + esc(t('on_now')) + '</h2>'
      + '<span class="more">' + D.LINEUP.length + ' ' + esc(t('channels')) + ' · ' + esc(t('guide_auto')) + '</span></div>'
      + '<div class="track">' + guideTile(o.scrollX) + cards + '</div></div>';
  }

  /* =============================================================================================
     TV — the guide (FR-7). The shipped EPG grid, with the stations as rows in lineup order.
     ============================================================================================= */
  const WIN_START = 19 * 60, WIN_END = 24 * 60 + 30, SLOT = 30;
  const DENS = { rowh: 88, chcol: 320, slot: 240 };

  function guide(opt) {
    const o = opt || {};
    const ppm = DENS.slot / SLOT;
    const timeW = (WIN_END - WIN_START) * ppm;
    let slots = '';
    for (let s = WIN_START; s < WIN_END; s += SLOT)
      slots += '<div class="lt-epg-slot" style="width:' + DENS.slot + 'px">' + fmt(s) + '</div>';

    let rows = '';
    D.LINEUP.forEach(function (ch, ri) {
      let cells = '';
      if (ch.kind === 'radio' && !ch.now) {
        /* FR-7: "A station with no guide shows one block across the whole window, reading Live
           radio." One cell, the full window wide, and no live progress bar — there is no
           programme boundary for a bar to measure against. */
        /* The block's label sits at the LEFT of the block, not its centre. The block is 2 634 px
           wide and only about 1 600 of them are on screen, so a centred label lands 1 637 px in —
           off the right edge of the picture and, in the app, wherever the guide happens to be
           scrolled to. In the app it sticks to the left edge of the visible window as the guide
           scrolls; in a still it is simply left. */
        cells = '<div class="lt-epg-cell live rd-blockrow" style="left:0;width:' + (timeW - 6) + 'px">'
          + '<div class="rd-blocklab"><div class="pt">' + esc(t('live_radio')) + '</div>'
          + '<div class="ps"><span class="liveflag">' + esc(t('live')) + '</span></div></div></div>';
      } else if (ch.kind === 'radio') {
        /* Harbour Radio's two programmes, and nothing before 20:00 or after 22:00 — a radio guide
           that holds two entries holds two, and drawing filler either side would be inventing a
           schedule the estate does not have. The gaps are the honest picture. */
        [ch.now, ch.next].forEach(function (p, ci) {
          const l = (Math.max(p.start, WIN_START) - WIN_START) * ppm;
          const w = Math.max((Math.min(p.end, WIN_END) - Math.max(p.start, WIN_START)) * ppm - 6, 40);
          const live = D.NOW >= p.start && D.NOW < p.end;
          cells += '<div class="lt-epg-cell' + (live ? ' live' : '') + (live && o.focus ? ' focused' : '')
            + '" style="left:' + l + 'px;width:' + w + 'px">'
            + '<div class="pt">' + esc(p.title) + '</div>'
            + '<div class="ps">' + fmt(p.start) + '–' + fmt(p.end)
            + (live ? '<span class="liveflag">' + esc(t('live')) + '</span>' : '') + '</div>'
            + (live ? '<div class="cbar" style="width:' + (pct(p) / 100 * w).toFixed(1) + 'px"></div>' : '')
            + '</div>';
          void ci;
        });
      } else {
        /* A TV channel's row, straight out of the shipped mock's schedule for that channel. */
        (window.LiveTV.schedule[ch.id] || [])
          .filter(p => p.end > WIN_START && p.start < WIN_END)
          .forEach(function (p) {
            const l = (Math.max(p.start, WIN_START) - WIN_START) * ppm;
            const w = Math.max((Math.min(p.end, WIN_END) - Math.max(p.start, WIN_START)) * ppm - 6, 40);
            const live = D.NOW >= p.start && D.NOW < p.end;
            cells += '<div class="lt-epg-cell' + (live ? ' live' : '') + '" style="left:' + l + 'px;width:' + w + 'px">'
              + '<div class="pt">' + esc(p.title) + '</div>'
              + '<div class="ps">' + fmt(p.start) + '–' + fmt(p.end)
              + (live ? '<span class="liveflag">' + esc(t('live')) + '</span>' : '') + '</div>'
              + (live ? '<div class="cbar" style="width:' + (pct(p) / 100 * w).toFixed(1) + 'px"></div>' : '')
              + '</div>';
          });
      }
      const radio = ch.kind === 'radio';
      rows += '<div class="lt-epg-row">'
        + '<div class="lt-epg-chcol' + (radio ? ' rd-radio' : '') + '">'
        + '<span class="cnum">' + ch.num + '</span>'
        + logo(ch)
        /* FR-7: "The channel cell carries the same radio mark." The same chip, at the cell's own
           scale — a 54×40 logo plate has no corner to spare. */
        /* THE MARK GOES IN THE META LINE, NOT AFTER THE NAME. The channel column is 320 px wide
           and about 128 of them are the name; the first render put the glyph after the name and
           it truncated *Harbour Radio 2* and *Assembly Radio* to *Harbour Ra…* and *Assembly R…*
           while leaving the shorter *Harbour Radio* intact — three station rows, two names
           damaged, to carry a mark the line under them had room for. The meta line is where the
           category already is, so the glyph costs no name at all. */
        + '<div class="cn"><div class="cname"><span class="nm">' + esc(ch.name) + '</span></div>'
        + '<div class="rd-cmeta">' + (radio ? mark(14) : '')
        + (radio ? esc(t('radio')) : esc(ch.catLabel)) + '</div></div>'
        + '</div><div class="lt-epg-track">' + cells + '</div></div>';
      void ri;
    });

    const nowLeft = DENS.chcol + (D.NOW - WIN_START) * ppm;
    return '<div class="lt-guide spacious" style="--lt-rowh:' + DENS.rowh + 'px;--lt-chcol:' + DENS.chcol + 'px">'
      + '<div class="lt-guide-head"><h1>' + esc(t('tv_guide')) + '</h1>'
      + '<span class="sub">' + D.LINEUP.length + ' ' + esc(t('channels')) + '</span>'
      + '<span class="now-ts"><span class="live-dot"></span>' + esc(t('now')) + ' · ' + fmt(D.NOW) + '</span></div>'
      /* The category chips are unchanged (FR-7), plus the one the operator can give the stations. */
      + '<div class="lt-cats">'
      + ['all_channels', 'news', 'nordic', 'ent', 'radio'].map(function (c) {
        const lab = c === 'all_channels' ? t('all_channels')
          : c === 'radio' ? t('radio')
            : window.LiveTV.CATS[c].label;
        return '<div class="lt-cat' + (c === 'all_channels' ? ' on' : '') + '">' + esc(lab) + '</div>';
      }).join('') + '</div>'
      + '<div class="lt-epg"><div class="lt-epg-scroll"><div class="lt-epg-canvas" style="width:'
      + (DENS.chcol + timeW) + 'px">'
      + '<div class="lt-epg-timebar"><div class="corner">' + esc(t('channel')) + '</div>' + slots + '</div>'
      + rows
      + '<div class="lt-epg-nowline" style="left:' + nowLeft + 'px"></div>'
      + '</div></div></div></div>';
  }

  /* =============================================================================================
     TV — the radio player (FR-8, owner's pick A): the TV's own Now-playing language. The logo
     large and centred, the station under it, the programme under that, the live bar, then Next.
     NO bottom bar at all — which is what makes the burn-in step move everything on screen, the
     only version of that step that protects the bright text. It carries the LIVE mark, the
     station's name and number, the programme now with its times and live progress, and the next
     programme (FR-8's list), and shows *Live radio* with no programme when the station has no
     guide.

     The rejected direction B kept R177's bottom bar, and the three costs the pictures showed are
     recorded in the round's REPORT.md (`design/incoming/2026-10-27-radio/REPORT.md`): the shipped
     field at `opacity: .85` with no picture behind it, the station's name said twice, and a bottom
     bar that cannot take part in the burn-in step.
     ============================================================================================= */

  /* The LIVE pill, shared. */
  const livePill = () => '<span class="rd-live"><i></i>' + esc(t('live')) + '</span>';

  /* The audio layout's content — the thing that MOVES for burn-in. One element, so the burn-in
     step is one transform and not four. */
  function audioStack(ch, opt) {
    const o = opt || {};
    const guided = !!ch.now;
    let h = '<div class="rd-stack' + (o.moved ? ' moved' : '') + '"'
      + (o.moved ? ' style="--bx:' + o.bx + 'px;--by:' + o.by + 'px"' : '') + '>'
      + '<div class="rd-plate" style="background:' + ch.grad + '">'
      + '<span class="in">' + esc(ch.initials) + '</span>'
      /* The mark, large, ON the plate: at three metres the plate is the only thing legible from
         the sofa, and a station whose player looks exactly like a channel's player with the
         picture missing is the one thing a viewer would read as a fault. */
      + '<span class="rd-pmark">' + wavesSVG(44) + '</span>'
      + '</div>'
      + '<div class="rd-pname"><span class="num">' + ch.num + '</span>' + esc(ch.name) + '</div>'
      /* STOPPED IS NOT LIVE. The first render of `radio-playerA-stopped` carried the red LIVE
         pill over a card that said *Stopped* — the one thing on screen the viewer would trust,
         contradicted by the one thing above it. The pill goes; the programme and its bar stay,
         because the programme really is still on air and seeing what is running is the reason to
         press play again. */
      + '<div class="rd-prow">' + (o.stopped ? '' : livePill())
      + '<span class="rd-pkind">' + esc(t('radio')) + '</span></div>';
    if (guided) {
      h += '<div class="rd-pnow">' + esc(ch.now.title) + '</div>'
        + '<div class="rd-ptimes">' + fmt(ch.now.start) + '–' + fmt(ch.now.end)
        + ' · ' + esc(ch.now.sub) + '</div>'
        + '<div class="rd-pbar"><span class="t">' + fmt(ch.now.start) + '</span>'
        + '<div class="tr"><i style="width:' + pct(ch.now).toFixed(1) + '%"></i></div>'
        + '<span class="t">' + fmt(ch.now.end) + '</span></div>'
        + (ch.next ? '<div class="rd-pnext">' + esc(t('next')) + ' <b>' + esc(ch.next.title)
          + '</b> · ' + fmt(ch.next.start) + '</div>' : '');
    } else {
      /* With no guide: the name, Live radio and LIVE — and no bar, as on the tile. */
      h += '<div class="rd-pnow rd-noguide-big">' + esc(t('live_radio')) + '</div>';
    }
    if (o.stopped) {
      h += '<div class="rd-stopped">' + esc(t('stopped')) + '<span>' + esc(t('tune_in_again')) + '</span></div>';
    }
    return h + '</div>';
  }

  /* The zap banner, R177's own, with the mark when the banner names a station — which is the one
     place the viewer learns, mid-zap, that the next press lands on radio. */
  function zapBanner(ch) {
    return '<div class="lt-zap">' + logo(ch)
      + '<div><div><span class="cnum">' + ch.num + '</span> <span class="cname">' + esc(ch.name) + '</span>'
      + (ch.kind === 'radio' ? mark(18) : '') + '</div>'
      + '<div class="now">' + (ch.now ? esc(ch.now.title) : esc(t('live_radio'))) + '</div></div></div>';
  }

  /* The number-entry OSD, R177's own, unchanged. */
  const numOSD = (digits) => '<div class="lt-numosd on">' + esc(digits)
    + '<span class="dim">' + '_'.repeat(Math.max(0, 3 - digits.length)) + '</span></div>';

  /* ↑ Now/Next. FR-8: "lists stations with their mark." The whole lineup, five rows around the
     current one, which here is the whole lineup. */
  function nowNext(curNum, focusNum) {
    const rows = D.LINEUP.map(function (c) {
      const radio = c.kind === 'radio';
      return '<div class="lt-nn-row' + (c.num === curNum ? ' cur' : '') + (c.num === focusNum ? ' focused' : '') + '">'
        + '<span class="cnum">' + c.num + '</span>' + logo(c)
        + '<span class="cname">' + esc(c.name) + (radio ? mark(16) : '') + '</span>'
        + '<span class="nn-col nn-now"><span class="nn-k">' + esc(t('now').toUpperCase()) + '</span>'
        + (c.now ? esc(c.now.title) : esc(t('live_radio'))) + '</span>'
        + '<span class="nn-col nn-next"><span class="nn-k">' + esc(t('next').toUpperCase()) + '</span>'
        + (c.next ? esc(c.next.title) : '—') + '</span></div>';
    }).join('');
    return '<div class="lt-nownext"><div class="lt-nn-h">' + esc(t('now_next'))
      + ' · ↑↓ ' + esc(t('browse')) + ' · ↵ ' + esc(t('tune')) + '</div>' + rows + '</div>';
  }

  /* The player. */
  function player(ch, opt) {
    const o = opt || {};
    const b = D.BURN.offsets[D.BURN.step];
    let h = '<div class="lt-player on rd-pA">'
      /* The field behind it is the station's own gradient at a low alpha over --bg: the live
         player's `.video .bg` is the channel's gradient, and keeping that keeps one station
         recognisable from the sofa before a word is read. */
      + '<div class="rd-field" style="background:' + ch.grad + '"></div>'
      + audioStack(ch, { moved: !!o.moved, bx: b.x, by: b.y, stopped: !!o.stopped })
      + '<div class="rd-hint">← → ' + esc(t('change_channel')) + ' · ↑ ' + esc(t('now_next')) + '</div>';
    if (o.zapTo) h += zapBanner(o.zapTo);
    if (o.num) h += numOSD(o.num);
    if (o.nowNext) h += nowNext(ch.num, o.nowNextFocus == null ? ch.num : o.nowNextFocus);
    return h + '</div>';
  }

  /* =============================================================================================
     ADMIN — the lineup table (FR-12). The shipped `.card`, `.chip`, `.badge`, `.toggle` and the
     page's own `.lineup` table. The mark here is the SAME glyph as the tile's, at a 1 rem row's
     scale, because an operator reading the table and a viewer reading the tile should be reading
     one mark and not two.

     THE MARK'S CLASS IS `radiomark`, NOT `rd-amark`: this is the one rule of the round that is
     promoted into `design/app/wf.css` (FR-12, 2026-10-27), so the archive draws the production
     class and a diff between this file and the admin is a diff of markup only.

     The round's *Live now* block, which sat under this table, is NOT part of the archive: the
     owner did not adopt it. A radio listener shows in the playback sessions list (FR-5).
     ============================================================================================= */
  function adminLineup() {
    const rows = D.LINEUP.map(function (ch) {
      const radio = ch.kind === 'radio';
      /* FR-3: "A station arrives hidden and badged new." The three stations are exactly that
         until the operator shows them, which is the switch-on DEB-2625 deferred — so the drawn
         table is the state the operator MEETS: three new, hidden stations under three shown
         channels, with the toggle as the only thing that changes it. */
      const shown = !radio;
      /* FR-12: a hidden row that is still badged *new* is drawn at FULL strength. `off` alone
         fades it to 50 %, which made the three stations the operator opened the page to find the
         faintest rows on it (the round's Q2, answered 2026-10-27). The second class is what wins
         the cascade against the page-local fade — see `.lineup tr.off.new td` in wf.css. */
      const rowCls = shown ? '' : (radio ? ' class="off new"' : ' class="off"');
      return '<tr' + rowCls + '>'
        + '<td><span class="ord"><button>▲</button><button>▼</button></span></td>'
        + '<td><span class="toggle' + (shown ? ' on' : '') + '"></span></td>'
        + '<td class="cnum">' + ch.num + '</td>'
        + '<td class="cname">' + (radio ? '<span class="radiomark" aria-label="' + esc(t('radio'))
          + '" role="img">' + wavesSVG() + '</span>' : '') + esc(ch.name) + '</td>'
        + '<td><span class="chip">' + esc(radio ? t('radio') : ch.catLabel) + '</span></td>'
        + '<td>' + (ch.now ? '<span class="badge ok">' + esc(t('epg')) + '</span>'
          : '<span class="badge warn">' + esc(t('no_guide')) + '</span>') + '</td>'
        + '<td>' + (radio ? '<span class="badge info">' + esc(t('new_since')) + '</span>' : '') + '</td>'
        + '</tr>';
    }).join('');
    const filters = ['all', 'shown_in_ravilo', 'hidden', 'brand_new', 'unavailable', 'radio']
      .map(function (f) {
        /* FR-12: the filters "gain Radio, beside all · shown · hidden · new · unavailable" —
           so Radio goes at the END of the existing five and does not reorder them. */
        return '<span class="chip fchip' + (f === 'all' ? ' on' : '') + '">'
          + (f === 'radio' ? '<span class="radiomark">' + wavesSVG() + '</span>' : '')
          + esc(t(f)) + '</span>';
      }).join('');
    return '<div class="card rd-admin">'
      + '<div class="row center"><h3 style="margin:0;font-size:1.05rem;">' + esc(t('lineup')) + '</h3>'
      + '<span class="spacer"></span>'
      + '<span class="badge ok">● connected via Jellyfin</span></div>'
      + '<div class="filterrow">' + filters + '</div>'
      + '<table class="lineup"><thead><tr>'
      + '<th style="width:44px;">' + esc(t('order')) + '</th>'
      /* The shipped head is `In&nbsp;Ravilo` in a 64 px column for a reason: without the hard
         space it wraps to two lines and the head row grows. Kept. */
      + '<th style="width:74px;">' + esc(t('shown_in_ravilo')).replace(/ /g, '&nbsp;') + '</th>'
      + '<th style="width:54px;">#</th><th>' + esc(t('channel')) + '</th>'
      + '<th style="width:150px;">' + esc(t('category')) + '</th>'
      + '<th style="width:96px;">' + esc(t('guide_col')) + '</th>'
      + '<th style="width:190px;">' + esc(t('status')) + '</th>'
      + '</tr></thead><tbody>' + rows + '</tbody></table>'
      + '<div class="rd-lumore muted tiny">Showing all ' + D.LINEUP.length
      + ' channels Jellyfin currently provides · 3 of them radio.</div>'
      + '</div>';
  }

  /* =============================================================================================
     PHONE — Music Home with the Radio row (FR-9, owner's pick D): the LAST row, after the genres,
     with square logo tiles the size of the album cards. It pushes nothing down, and it costs
     putting radio at the end of a long scroll — which is why every frame that draws it is
     scrolled to the end: at 412 the row is below the fold, and a picture of the place it is not
     would answer nothing.

     The album, artist and genre rows come out of the shipped corpus through the shipped classes,
     so the Radio row is drawn against the real neighbours it will have. The three rejected
     placements and the wide tile are in the round's REPORT.md.
     ============================================================================================= */
  const M = () => window.MUSIC;

  const albumCard = (id) => {
    const a = M().album(id);
    return '<div class="mu-acard"><div class="mu-cv" style="' + M().coverStyle(a) + '"></div>'
      + '<div class="t">' + esc(a.title) + '</div>'
      + '<div class="s">' + esc(M().artist(a.artistId).name) + ' · ' + a.year + '</div></div>';
  };
  const artistCard = (r) => '<div class="mu-arc"><div class="c" style="' + M().artistStyle(r) + '">'
    + esc(M().initials(r.name)) + '</div><div class="t">' + esc(r.name) + '</div></div>';

  const songRow = (tr, i) => '<button class="mu-tr"><span class="n">' + (i + 1) + '</span>'
    + '<span class="t">' + esc(tr.title) + '</span>'
    + '<span class="ln">' + esc(M().fmtLen(tr.len)) + '</span>'
    + '<span class="ly"></span></button>';

  const muRow = (title, inner, more) => '<div class="row"><h3>' + esc(title)
    + (more ? '<span class="more">' + esc(more) + ' ›</span>' : '') + '</h3>' + inner + '</div>';

  /* The Radio TILE: the logo, the name, the programme (or *Live radio*) and the mark — FR-9's four
     things, in the album card's own 142 px box, so the row lines up with the album rows above it
     tile for tile and radio reads as part of the library. */
  function stationTile(ch) {
    const sub = ch.now ? ch.now.title : t('live_radio');
    return '<div class="mu-acard rd-scard">'
      + '<div class="mu-cv rd-slogo" style="background:' + ch.grad + '">'
      + '<span class="wm">' + esc(ch.initials) + '</span>'
      + '<span class="rd-tmark" aria-label="' + esc(t('radio')) + '" role="img">' + wavesSVG(15) + '</span></div>'
      + '<div class="t">' + esc(ch.name) + '</div>'
      + '<div class="s">' + esc(sub) + '</div></div>';
  }

  function radioRow() {
    return muRow(t('radio'),
      '<div class="mu-track">' + D.ST.map(stationTile).join('') + '</div>');
  }

  /* Music Home. The Radio row is LAST — not a parameter any more: the owner picked the place on
     2026-10-27 and a mockup that can still draw it elsewhere is a mockup of a decision that has
     been made. `scrollToEnd` is the frame's own knob, not the design's. */
  function musicHome(opt) {
    const o = opt || {};
    const rows = [];
    rows.push(muRow(t('recent_albums'), '<div class="mu-track">'
      + D.RECENT_ALBUMS.map(albumCard).join('') + '</div>'));
    rows.push(muRow(t('recent_played'), '<div class="mu-list">'
      + M().tracksOf('salt-on-the-window').slice(0, 3).map(songRow).join('') + '</div>', t('see_all')));
    rows.push(muRow(t('artists'), '<div class="mu-track">'
      + M().artists.filter(r => M().albumsOf(r.id).length).slice(0, 8).map(artistCard).join('') + '</div>'));
    M().genres().filter(g => g.albums >= 4).slice(0, 2).forEach(function (g) {
      const al = M().albums.filter(a => a.genres.some(x => x.name === g.name)).slice(0, 8);
      rows.push(muRow(g.name.charAt(0).toUpperCase() + g.name.slice(1),
        '<div class="mu-track">' + al.map(a => albumCard(a.id)).join('') + '</div>'));
    });
    rows.push(radioRow());
    /* THE TAIL SPACER HAS TO KNOW ABOUT THE MINI BAR. 120 px clears the 74 px bottom nav, and
       that was enough in the round, where the Radio row sat second and nothing was ever under it.
       With the row LAST and the frame scrolled to the end, the mini bar lands exactly on the
       tiles' two label lines: the one frame whose whole job is to show the row and the bar
       together was the one frame that hid the row. `.mu-mini.abovenav` is an overlay, so in the
       app the scroller's own bottom inset grows by the bar's height — 74 px more. Seen in the
       render; nothing in the markup says it. */
    return '<div class="rd-scroll' + (o.scrollToEnd ? ' end' : '') + '">' + rows.join('')
      + '<div style="height:' + (o.mini ? 194 : 120) + 'px"></div></div>';
  }

  /* The phone's bottom bar, drawn flat: four items, Listen current. Not a proposal — it is the
     frame's floor, so the Radio row is judged with the space it actually has. */
  const bnav = () => '<div class="rd-bnav">'
    + ['nav_listen', 'nav_library', 'nav_search', 'nav_settings'].map(function (k, i) {
      return '<span class="' + (i === 0 ? 'on' : '') + '"><i></i>' + esc(t(k)) + '</span>';
    }).join('') + '</div>';

  /* =============================================================================================
     PHONE — a station playing (FR-10). The shipped Now-playing sheet, with:
       · the programme as the title and the station as the line under it
       · the seek bar REPLACED by a LIVE mark
       · ⏯ alone, with no shuffle, previous, next or repeat
       · Up next showing nothing
     ============================================================================================= */
  const IC = {
    down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 9l6 6 6-6"/></svg>',
    more: '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
    play: '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/></svg>',
    queue: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h11M4 12h11M4 17h7M18 10v8"/><circle cx="20" cy="17" r="2"/></svg>',
    cast: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M3 20h4"/></svg>',
    /* A SPEAKER, not a screen. Radio never goes to a TV (FR-11's last clause), so every place
       named anywhere in this round is a speaker or a speaker group — and the first render drew
       the cast-to-a-screen rectangle beside *Playing on Downstairs*. */
    spk: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="6" y="2" width="12" height="20" rx="3"/><circle cx="12" cy="15" r="3"/><circle cx="12" cy="7" r="1.2"/></svg>',
    warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 3l9 17H3z"/><path d="M12 9v5M12 17h.01"/></svg>',
    /* A drawn glyph, not an emoji. The first render of `radio-np-mac-bar` had a 🔊 in the
       capsule, which Chromium draws in its own monochrome outline face beside three glyphs in
       ours — one control in a different hand from every other control on the bar. */
    vol: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 9.5a3.5 3.5 0 0 1 0 5M19.5 7a7 7 0 0 1 0 10"/></svg>',
  };

  /* The LIVE mark that stands where the seek bar was. The seek bar's whole slot — track, thumb
     and the two times — goes; what replaces it is ONE line, because a bar with no thumb at three
     feet still invites a drag. */
  function liveSlot(ch, stopped) {
    /* STOPPED IS NOT LIVE, here as on the TV. The first render of `radio-np-phone-stopped` drew
       the ▶ glyph under a lit LIVE pill. The pill is replaced by the word, in the quiet ink, so
       the slot keeps its height and the sentence changes. */
    return '<div class="rd-liveslot">'
      + (stopped
        ? '<span class="rd-stopped-pill">' + esc(t('stopped')) + '</span>'
        : '<span class="rd-live big"><i></i>' + esc(t('live')) + '</span>')
      + (ch.now ? '<span class="tm">' + fmt(ch.now.start) + '–' + fmt(ch.now.end) + '</span>' : '')
      + '</div>';
  }

  /* The transport: ⏯ alone. The four other buttons are ABSENT, not greyed — FR-10 says the
     transport IS ⏯, and a greyed Next on a live stream is a control that will never work. */
  const radioTransport = (paused) => '<div class="mu-tp rd-tp">'
    + '<button class="mu-pp">' + (paused ? IC.play : IC.pause) + '</button></div>';

  function nowPlaying(ch, opt) {
    const o = opt || {};
    const title = ch.now ? ch.now.title : ch.name;
    const second = ch.now ? ch.name : t('live_radio');
    const tint = 'background:radial-gradient(120% 70% at 50% 18%, hsl(' + ch.hue + ' 45% 26%), var(--bg) 78%)';
    let body = '<div class="mu-nspacer"></div>'
      + '<div class="mu-ncov rd-ncov"><div class="mu-cv rd-slogo big" style="background:' + ch.grad + '">'
      + '<span class="wm">' + esc(ch.initials) + '</span>'
      + '<span class="rd-tmark big" aria-label="' + esc(t('radio')) + '" role="img">' + wavesSVG(22) + '</span>'
      + '</div></div>'
      + '<div class="mu-nmeta"><div class="b">'
      + '<div class="mu-mq"><span>' + esc(title) + '</span></div>'
      + '<div class="mu-nsub">' + esc(second)
      + (ch.now ? '' : '') + '</div>'
      + (o.on ? '<div class="mu-ndev">' + IC.spk + esc(t('playing_on', { d: o.on })) + '</div>' : '')
      + '</div></div>';
    if (o.failed) {
      body += '<div class="rd-fail">' + IC.warn + '<span>' + esc(t('cant_play', { x: ch.name }))
        + '</span><button>' + esc(t('try_again')) + '</button></div>';
    }
    /* A station that COULD NOT START is not live, and ⏯ is not a pause. The first render drew
       the failure banner with a LIVE mark over it and the pause glyph under it — three claims
       about one fact, two of them false. The LIVE mark is absent while the failure stands, and
       the transport offers ▶ beside *Try again*, which are the same action. */
    body += (o.failed ? '' : liveSlot(ch, !!o.paused))
      + radioTransport(!!o.paused || !!o.failed) + '<div class="mu-nspacer"></div>';
    return '<div class="mu-now on rd-now"><div class="bgt" style="' + tint + '"></div>'
      + '<div class="mu-ntop"><button class="mu-ib">' + IC.down + '</button>'
      + '<div class="ctx">' + esc(t('radio')) + '<b>' + esc(ch.name) + '</b></div>'
      + '<button class="mu-ib">' + IC.more + '</button></div>'
      + '<div class="mu-nmain">' + body + '</div>'
      /* `Up next` opens a screen that, for a station, has nothing in it — so the button stays
         (it is the app's, on every kind) and the SCREEN says so. The round draws the button lit
         by nothing, and the sentence is in the strings table for the queue screen. */
      + '<div class="mu-nbot"><span class="mu-ib ghost"></span>'
      + '<button class="mu-ib">' + IC.queue + '</button>'
      + '<button class="mu-ib' + (o.on ? ' on' : '') + '">' + IC.cast + '</button>'
      + '<button class="mu-ib">' + IC.more + '</button></div>'
      + '</div>';
  }

  /* The mini bar, over Music Home. The shipped `.mu-mini`, with the LIVE pill where the elapsed
     progress line would be — a station has no progress, and the shipped `.pg` hairline at 0 %
     reads as a track that has just started. */
  function miniBar(ch, opt) {
    const o = opt || {};
    return '<div class="mu-mini on abovenav rd-mini">'
      + '<span class="mu-cv sm rd-slogo" style="background:' + ch.grad + '">'
      + '<span class="wm">' + esc(ch.initials) + '</span></span>'
      + '<span class="tx"><span class="n">' + esc(ch.now ? ch.now.title : ch.name) + '</span>'
      + '<span class="d">' + '<span class="rd-live sm"><i></i>' + esc(t('live')) + '</span>'
      + '<span class="rd-mwhere">' + esc(ch.name) + '</span></span></span>'
      + '<button>' + (o.paused ? IC.play : IC.pause) + '</button></div>';
  }

  /* =============================================================================================
     THE REMOTE — a station on a speaker (FR-11). Started from the music shape in
     design/incoming/2026-10-23-one-remote/, with its own classes, because that round is still
     under review: when it is adopted this radio shape is one modifier on its builder, and until
     it is, a copy of its stylesheet in this tree would be a stale production-shaped file.

     What it keeps from that shape: the place pill, the art card, the title block, the volume
     block with the group's level, its rooms and *Add a speaker…*.
     What it drops, because FR-11 says so: shuffle, previous, next, repeat and skip. ⏯ alone.
     ============================================================================================= */
  const volRow = (name, v, cls) => '<div class="rd-vrow' + (cls ? ' ' + cls : '') + '">'
    + '<span class="nm">' + esc(name) + '</span>'
    + '<span class="tr"><i style="width:' + v + '%"></i><em style="left:' + v + '%"></em></span>'
    + '<span class="pc">' + v + '</span></div>';

  function remote(ch, set, opt) {
    const o = opt || {};
    const title = ch.now ? ch.now.title : ch.name;
    const second = ch.now ? ch.name : t('live_radio');
    return '<div class="rd-rem">'
      + '<div class="rd-rhead"><button class="rd-ric">' + IC.down + '</button>'
      + '<span class="rd-pill">' + IC.spk + '<span>' + esc(set.line) + '</span>'
      + '<i class="arr">→</i></span>'
      + '<button class="rd-ric">' + IC.more + '</button></div>'
      + '<div class="rd-rctx">' + esc(t('radio')) + '</div>'
      + '<div class="rd-rart" style="background:' + ch.grad + '"><span class="in">' + esc(ch.initials) + '</span>'
      + '<span class="rd-tmark big" aria-label="' + esc(t('radio')) + '" role="img">' + wavesSVG(22) + '</span></div>'
      /* NO state line under the station. The place pill at the top IS the shipped device chip's
         sentence — `cast.playing_on`, *Playing on {device}* — so a *Playing on Downstairs* line
         here is the same sentence twice, nine lines apart, which the first render showed. */
      + '<div class="rd-rmeta"><h3>' + esc(title) + '</h3>'
      + '<div class="sub">' + esc(second) + '</div></div>'
      + liveSlot(ch, !!o.paused)
      + '<div class="rd-rtp"><button class="rd-pp">' + (o.paused ? IC.play : IC.pause) + '</button></div>'
      + '<div class="rd-vol">'
      + volRow(set.group ? set.line : set.rooms[0].name, set.master, 'master')
      + (set.group ? '<div class="rd-vsec">' + esc(t('rooms')) + '</div>'
        + set.rooms.map(r => volRow(r.name, r.vol)).join('') : '')
      + '<button class="rd-addspk">+ ' + esc(t('add_speaker')) + '</button>'
      + '</div>'
      /* NO *Up next* here. FR-11 enumerates the radio shape — "⏯, and R371's volume block with
         rooms and Add a speaker…" — and a queue is not in it; FR-10 says the queue is gone while
         a station plays. The first render offered *Up next* beside Stop, which is a button that
         opens an empty screen. Stop alone. (The PHONE's own bottom row keeps its queue button,
         deliberately: FR-10 says the queue SCREENS show nothing for a station, so that button
         has to stay reachable. The two surfaces differ because the spec makes them differ.) */
      + '<div class="rd-rfoot"><button class="rd-fb stop">■<span>' + esc(t('stop')) + '</span></button></div>'
      + '</div>';
  }

  /* =============================================================================================
     MAC — the window shell, Music Home with the Radio row, and the music bar.
     ============================================================================================= */
  function macWindow(cur, body, bar) {
    const nav = ['Home', 'Films', 'Series', 'Music', 'Playing']
      .map(n => '<span' + (n === cur ? ' class="on"' : '') + '>' + esc(n) + '</span>').join('');
    return '<div class="rd-mac"><div class="rd-mside">'
      + '<span class="rd-lights"><i></i><i></i><i></i></span>'
      + '<div class="rd-mnav">' + nav + '</div></div>'
      + '<div class="rd-mmain">' + body + (bar || '') + '</div></div>';
  }

  /* The Mac's Radio row: the same four facts per tile, at the desktop's card size. */
  function macRadioRow() {
    return '<div class="rd-msec"><h2>' + esc(t('radio')) + '</h2></div>'
      + '<div class="rd-mrow">' + D.ST.map(function (s) {
        return '<div class="rd-mcard">'
          + '<div class="rd-mlogo" style="background:' + s.grad + '"><span>' + esc(s.initials) + '</span>'
          + '<span class="rd-tmark" aria-label="' + esc(t('radio')) + '" role="img">' + wavesSVG(16) + '</span></div>'
          + '<div class="t">' + esc(s.name) + '</div>'
          + '<div class="s">' + esc(s.now ? s.now.title : t('live_radio')) + '</div></div>';
      }).join('') + '</div>';
  }

  function macMusicHome() {
    const albumRow = (title, ids) => '<div class="rd-msec"><h2>' + esc(title) + '</h2></div>'
      + '<div class="rd-mrow">' + ids.map(function (id) {
        const a = M().album(id);
        return '<div class="rd-mcard"><div class="rd-mcv" style="' + M().coverStyle(a) + '"></div>'
          + '<div class="t">' + esc(a.title) + '</div>'
          + '<div class="s">' + esc(M().artist(a.artistId).name) + ' · ' + a.year + '</div></div>';
      }).join('') + '</div>';
    /* The Radio row is LAST on the Mac too. FR-9's place is Music Home's, and the Mac's Music Home
       is the same surface at a different width — a row that is last on the phone and second in the
       window would be two answers to one question. It was drawn second in the round, where the
       place was still the thing being chosen. */
    return macWindow('Music',
      '<div class="rd-mbody">'
      + albumRow(t('recent_albums'), D.RECENT_ALBUMS.slice(0, 6))
      + albumRow(t('recent_played'), D.RECENT_ALBUMS.slice(2, 8))
      + macRadioRow()
      + '</div>');
  }

  /* The Mac's music bar with a station: the capsule, ⏯ alone, the LIVE mark where the seek was. */
  function macBar(ch, set) {
    return '<div class="rd-cap">'
      + '<span class="th" style="background:' + ch.grad + '"><span>' + esc(ch.initials) + '</span></span>'
      + '<button class="rd-cpp">' + IC.pause + '</button>'
      + '<span class="tx"><span class="n">' + esc(ch.now ? ch.now.title : ch.name) + '</span>'
      + '<span class="d"><span class="rd-tmark sm">' + wavesSVG(13) + '</span>' + esc(ch.name)
      + (set ? ' · ' + esc(t('playing_on', { d: set.line })) : '') + '</span></span>'
      + '<span class="rd-live cap"><i></i>' + esc(t('live')) + '</span>'
      + '<button class="rd-cvb">' + IC.vol + '</button></div>';
  }

  function macPlayingBar(ch, set) {
    return macWindow('Music', '<div class="rd-mbody">'
      + '<div class="rd-msec"><h2>' + esc(t('recent_albums')) + '</h2></div>'
      + '<div class="rd-mrow">' + D.RECENT_ALBUMS.slice(0, 6).map(function (id) {
        const a = M().album(id);
        return '<div class="rd-mcard"><div class="rd-mcv" style="' + M().coverStyle(a) + '"></div>'
          + '<div class="t">' + esc(a.title) + '</div>'
          + '<div class="s">' + esc(M().artist(a.artistId).name) + ' · ' + a.year + '</div></div>';
      }).join('') + '</div>'
      + macRadioRow()
      + '</div>', macBar(ch, set));
  }

  /* The burn-in orbit, as a diagram on the light canvas — NOT a product frame.
     `radio-playerA-burnin` is the product frame, and a single still of a layout that has moved
     28 px cannot show that it moved: the viewer has nothing to compare it against. This frame is
     the comparison, drawn at a quarter scale with the four positions on one screen box and the
     numbers written beside it, so "how far and how often" (the brief's own question) is answered
     by a picture rather than by a sentence in the report. */
  function burnDiagram() {
    const B = D.BURN;
    /* The screen box is 1920×1080 at a QUARTER. The orbit is drawn at 1:1 over it, deliberately,
       and the picture says so: at a quarter the four positions are 7 px and 4.5 px apart and the
       first render of this frame stacked the four digits into one unreadable blob. The four
       labels sit at four DIFFERENT corners of their own outline for the same reason — the
       outlines overlap by design, and only the corners are far enough apart to read.
       `corner` is the class that picks which one. */
    const CORNER = ['tl', 'tr', 'br', 'bl'];
    const marks = B.offsets.map(function (o, i) {
      return '<span class="rd-bo ' + CORNER[i] + (i === B.step ? ' on' : '') + '"'
        + ' style="transform:translate(' + o.x + 'px,' + o.y + 'px)">'
        + '<i></i><b>' + (i + 1) + '</b></span>';
    }).join('');
    const travel = (Math.max.apply(null, B.offsets.map(o => o.x)) - Math.min.apply(null, B.offsets.map(o => o.x)))
      + ' × ' + (Math.max.apply(null, B.offsets.map(o => o.y)) - Math.min.apply(null, B.offsets.map(o => o.y)));
    return '<div class="rd-burn">'
      + '<div class="rd-bscreen"><div class="rd-bstack">' + marks + '</div>'
      + '<span class="rd-blab">1920 × 1080</span></div>'
      + '<dl class="rd-bfacts">'
      + '<dt>every</dt><dd>' + B.stepMinutes + ' minutes</dd>'
      + '<dt>positions</dt><dd>' + B.offsets.length + ', in a loop of '
      + (B.stepMinutes * B.offsets.length) + ' minutes</dd>'
      + '<dt>travel</dt><dd>' + travel + ' px — 2.9 % of the width, 3.3 % of the height</dd>'
      + '<dt>the step</dt><dd>a ' + B.glideMs + ' ms glide; under reduced motion, a cut</dd>'
      + '<dt>what moves</dt><dd>the whole audio layout, as one block — the plate, the name, '
      + 'LIVE, the programme, the bar and Next</dd>'
      + '<dt>this picture</dt><dd>the screen box is 1920 × 1080 at a quarter; the four positions '
      + 'are drawn at 1:1 over it, so the travel is visible at all</dd>'
      + '</dl></div>';
  }

  return {
    setLang: setLang, t: t, esc: esc, nextId: nextId, wavesSVG: wavesSVG, mark: mark,
    onNowCard: onNowCard, onNowRow: onNowRow, guide: guide,
    burnDiagram: burnDiagram,
    player: player, nowNext: nowNext,
    adminLineup: adminLineup,
    musicHome: musicHome, bnav: bnav, nowPlaying: nowPlaying, miniBar: miniBar,
    remote: remote, macMusicHome: macMusicHome, macPlayingBar: macPlayingBar,
  };
})(window.RD);
