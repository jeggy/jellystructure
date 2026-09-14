/* Ravilo — focus detail. What a highlighted title says before you open it.
   Round 2 outcome (see “Focus Detail - Round 2 Directions.html”): the owner rejected
   the floating plate. Two directions survive, and neither overlays a tile:

     L · the status line   — a permanent 88px strip at the foot of the screen that
                             always describes the focused title. Facts, no synopsis.
                             DEFAULT ON. Small, reversible, can't regress anything.
     J · the row opens     — the focused poster grows in place to 300×450 and the
                             text sits beside it inside the row's own band, with the
                             rest of the row continuing past it. Richer, and the one
                             with a real hardware question (see below).
                             DEFAULT OFF, behind a Jellystructure switch.

   J supersedes L when enabled: the open row already carries every field the line
   does, so showing both would state the same facts twice on one screen.

   Two rules this module keeps, because they are the ones a spec would keep:
   1. RENDER, NEVER COMPUTE. The app hands us one resolved object per item via
      fieldsFor(); every string arrives finished, exactly as the TV would receive it
      from /api/tv/**. Nothing is derived here.
   2. NO FOCUS STOPS. Everything drawn is inert. Down from a tile goes where it went
      before, Play never moves, and nothing new is reachable with the D-pad.

   J's known cost, not resolved by any drawing but by device measurement: the row's
   height and its tiles' positions change on every focus move, which is the reflow
   constitution invariant 11 exists to protect. A 2026-09-13 sweep-and-trace pass on
   the stue BRAVIA (46 real settled opens, 2.8-3.2% janky frames, 0 missed vsync;
   see the R240 spec) closed that question, so J now defaults on.

   R242 (2026-09-14) gives J its own backdrop: while a row is open, the title's
   backdrop fills the whole screen behind everything, fading in on open and back to
   plain --bg on close. L stays exactly as above — facts only, no artwork. This is
   NOT round 1's hero mirror or ambience wash come back: those needed a backdrop
   fetch that didn't exist for a row item at all; today every MediaCard (hero or
   row alike) already carries backdropUrl, so this renders a field that's already
   shipping rather than adding one. Nothing rides the wire that wasn't there before.

   Scope: Home content rows only (not the channel rail, not the hero, not browse,
   search, Discover or Live TV — those rows aren't library items in the same shape).

   Config: owned by Jellystructure per Jellyfin user, mocked here through localStorage
   and written by app/ravilo-config.html. Two booleans, no other keys. */
(function () {
  const KEY = 'js-ravilo-focusdetail';
  /* The detail appears only once the D-pad has been still this long. Without a dwell,
     holding Right opens and collapses a row per keypress and the whole page pumps —
     round 1's direction E (the dwell ladder) as a rule rather than a look. It is a
     household setting rather than a constant because how long "settled" feels depends
     on who is holding the remote; 0 means show it immediately. */
  const DEF = { line: true, rowOpen: true, delay: 170 };

  function readCfg() {
    let raw = {};
    try { raw = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) {}
    // round-1 keys (on/variant/mirror/tint) are retired; ignore whatever is stored
    const d = Math.round(Number(raw.delay));
    return {
      line: raw.line !== false,
      rowOpen: raw.rowOpen !== false,
      // any non-negative whole number of ms is a valid setting — 10 and 10000 both are, and so is
      // anything between; only junk falls back to the default. No ceiling.
      delay: Number.isFinite(d) && d >= 0 ? d : DEF.delay,
    };
  }
  window.raviloFocusConfig = readCfg;

  window.initRaviloFocus = function (stage, o) {
    const cfg = readCfg();
    let line = null, openTile = null, panel = null, lastNode = null, dwellT = null, heldTrack = null, settleT = null, rampT = 0, closing = null, closingTile = null, closeT = null;
    let bg = null, bgImgs = null, bgCur = 0, bgKey = null;

    /* ---------- shared field rendering ---------- */
    function metaBits(f) {
      return [f.badge ? `<span class="fp-tag">${f.badge}</span>` : '',
        f.year ? `<span>${f.year}</span>` : '',
        f.count ? `<span>${f.count}</span>` : '',
        f.runtime ? `<span>${f.runtime}</span>` : '',
        f.certHTML || '',
        f.imdb ? `<span class="fd-imdb">★ ${f.imdb}</span>` : ''].filter(Boolean).join('');
    }
    function flagGroup(label, list, more) {
      if (!list || !list.length) return '';
      return `<span class="fd-lbl">${label}</span>` +
        list.map(cc => `<span class="fi fi-${cc} pflag"></span>`).join('') +
        (more ? `<span class="fd-more">+${more}</span>` : '');
    }
    function flagsBits(f) {
      const a = flagGroup(o.t('fd_audio'), f.audio, f.audioMore);
      if (!a) return '';
      const s = flagGroup(o.t('fd_subs'), f.subs, f.subsMore);
      return a + (s ? `<span class="fd-div"></span>${s}` : '');
    }
    function resumeBit(f) {
      return f.resume ? `<span class="fd-resume"><span class="fd-dot"></span>${f.resume}</span>` : '';
    }

    /* ---------- L · the status line ----------
       One element, created once, living in the stage above the scroll surface. It never
       moves and never covers a tile: the scroll surface carries matching bottom padding
       so the last row can always clear it. */
    function lineEl() {
      if (!line) {
        line = document.createElement('div');
        line.className = 'fdline';
        stage.appendChild(line);
        stage.classList.add('fd-line');
        // the mockup's own preview chrome (D-pad legend + skin picker) is fixed to the
        // bottom-right of the viewport, outside the stage, so it needs a hook of its own
        // to be lifted clear of the line. Mockup affordance yields to product surface.
        document.body.classList.add('fd-line');
      }
      return line;
    }
    function showLine(f) {
      const n = lineEl();
      const flags = flagsBits(f), res = resumeBit(f);
      n.innerHTML = `<span class="fdl-title">${f.title}</span>` +
        `<span class="fd-div"></span>` + metaBits(f) +
        (flags ? `<span class="fd-div"></span>${flags}` : '') +
        (res ? `<span class="fd-div"></span>${res}` : '') +
        `<span class="fdl-gap"></span>` +
        (f.genres && f.genres.length ? `<span class="fdl-genres">${f.genres.join(' · ')}</span>` : '');
      n.classList.add('on');
    }
    function hideLine() { if (line) line.classList.remove('on'); }
    /* Switching the feature off is not the same as having nothing focused: the reserved
       88px at the foot is what makes L not an overlay, so it stays while L is configured
       and only goes away when L is off (or superseded by J). */
    function lineOff() {
      if (!line) return;
      line.remove(); line = null;
      stage.classList.remove('fd-line'); document.body.classList.remove('fd-line');
    }

    /* ---------- R242 · J's own backdrop ----------
       While a row is open, the focused title's own backdrop fills the whole screen
       behind everything else — inserted as #stage's very first child so it paints
       behind the app bar, every row, and the open panel itself, and never scrolls
       with .screen-scroll. It renders ONLY the item that has actually committed to
       opening (never ambiently, never on a still-dwelling focus move — see openRow's
       own call site), and it fades on its own, slower clock than the .22s panel/tile
       tween: the panel snaps into place, the room catches up a beat later.
       No new fetch, no new field: R.artFor(item).backdrop is the exact URL the hero
       already renders — round 1's B/D needed a backdrop that didn't exist for a row
       item at all; this one already ships on every card. */
    function bgLayer() {
      if (!bg) {
        bg = document.createElement('div'); bg.className = 'jbg';
        bg.innerHTML = '<div class="jbg-img"></div><div class="jbg-img"></div><div class="jbg-scrim"></div>';
        bgImgs = bg.querySelectorAll('.jbg-img');
        stage.insertBefore(bg, stage.firstChild);
      }
      return bg;
    }
    function showBg(item) {
      const layer = bgLayer();
      const key = item && (item.title || item.id);
      if (key && key === bgKey) { layer.classList.add('on'); return; }
      bgKey = key;
      const art = (o.backdropFor && item) ? o.backdropFor(item) : null;
      const next = bgImgs[1 - bgCur], cur = bgImgs[bgCur];
      next.style.background = (art && art.backdrop) ? `center 30%/cover no-repeat url("${art.backdrop}")` : ((art && art.grad) || '');
      next.classList.add('on'); cur.classList.remove('on');
      bgCur = 1 - bgCur;
      layer.classList.add('on');
    }
    function hideBg() { bgKey = null; if (bg) bg.classList.remove('on'); }

    /* ---------- J · the row opens ----------
       The tile grows in its own slot and one inert panel is inserted after it, inside
       the same .track, so the remainder of the row simply continues past it. Nothing is
       positioned absolutely and nothing overlays: the row band itself gets taller. */
    function openRow(node, f) {
      showBg(node._item);
      panel = document.createElement('div');
      panel.className = 'jpanel';
      panel.innerHTML = `<div class="jp-body"><div class="jp-title">${f.title}</div>
        <div class="jp-meta">${metaBits(f)}</div>
        ${f.genres && f.genres.length ? `<div class="jp-chips">${f.genres.slice(0, 4).map((g, i) => `<span class="pchip${i === 0 ? ' lead' : ''}">${g}</span>`).join('')}</div>` : ''}
        ${f.syn ? `<div class="jp-syn">${f.syn}</div>` : `<div class="jp-empty">${o.t('fd_nodesc')}</div>`}
        <div class="jp-foot">${flagsBits(f)}${f.resume ? `<span class="fd-div"></span>${resumeBit(f)}` : ''}</div></div>`;
      node.classList.add('jopen');
      node.parentNode.insertBefore(panel, node.nextSibling);
      openTile = node;
      /* The row band grows once, on the first title you settle on, and then holds that
         height for as long as you stay in the row — so moving along the row moves
         nothing below it. The reservation is taken IMMEDIATELY (a mid-tween height is
         fine) and only ever raised once the open has settled: a lock that arrives late
         leaves a window in which a lateral move drops the band back to its natural
         height, which is the pump this rule exists to remove. */
      const track = heldTrack, tile = node;
      /* The band is raised on every frame of the open tween, never lowered: the poster
         and the panel reach their full size over .22s, so a single measurement taken at
         any one instant is either too small (mid-tween, and the band pumps later) or
         too late (and the band drops back first). Monotonic max over the tween is the
         only reading that is right the whole way through. */
      let frames = 0;
      holdOpened(track, tile);            // reserve the opened height up front, exactly
      (function ramp() {
        if (!panel || openTile !== tile || heldTrack !== track) return;
        hold(track);
        if (++frames * 16 < 300) rampT = requestAnimationFrame(ramp);
        else if (o.reveal) o.reveal(tile);   // reveal after the growth, never racing it
      })();
      // rAF is throttled in a background tab; the timer is what guarantees the settle
      settleT = setTimeout(() => {
        if (!panel || openTile !== tile || heldTrack !== track) return;
        hold(track);
        if (o.reveal) o.reveal(tile);
      }, 300);
    }
    /* The reserved height is the height the row WILL have once open, measured by asking
       the browser for the tile's final width rather than by sampling a tween in
       progress: a ramp alone can read a pre-transition layout on its first frames and
       under-reserve. The ramp still runs, so any later growth (wrapped chips, a long
       synopsis) is picked up, and it never lowers what it holds. */
    function hold(track) {
      if (!track) return;
      const pad = getComputedStyle(track).boxSizing === 'content-box' ? 40 : 0;
      track.style.minHeight = Math.max(parseFloat(track.style.minHeight) || 0, track.scrollHeight - pad) + 'px';
    }
    function holdOpened(track, tile) {
      if (!track || !tile) return;
      const w = tile.style.width, fb = tile.style.flexBasis, tr = tile.style.transition;
      tile.style.transition = 'none';
      tile.style.width = tile.style.flexBasis = getComputedStyle(tile).getPropertyValue('--jopen-w') || '300px';
      hold(track);
      tile.style.width = w; tile.style.flexBasis = fb; tile.style.transition = tr;
    }
    function closeRow() {
      const was = !!panel;
      clearTimeout(settleT);
      cancelAnimationFrame(rampT);
      dropClosing();                       // one collapse at a time, never two overlapping
      /* The collapse is an animation too, not a removal: the panel narrows back to
         nothing and the poster tweens back to 210, so the tiles to its right slide home
         instead of teleporting. The element only leaves the DOM once it has closed. */
      if (panel) {
        const p = panel; panel = null;
        p.classList.add('jclose');
        closing = p;
        // the node leaves the DOM when the close animation ends; the timer is only a
        // fallback for an animation that never starts (throttled tab, reduced motion)
        const done = () => { if (closing === p) { p.remove(); closing = null; } };
        p.addEventListener('animationend', done, { once: true });
        closeT = setTimeout(done, 400);
      }
      if (openTile) { openTile.classList.remove('jopen'); closingTile = openTile; openTile = null; }
      return was;
    }
    function dropClosing() {
      clearTimeout(closeT);
      if (closing) { closing.remove(); closing = null; }
      closingTile = null;
    }
    /* No .jpanel may outlive its open tile. An open superseded before its animation
       starts, or a collapse whose bookkeeping was cleared while its timer was pending,
       would otherwise strand a zero-width node in the row. */
    function sweepPanels() {
      stage.querySelectorAll('.jpanel').forEach(p => { if (p !== panel && p !== closing) p.remove(); });
    }
    /* Holding the height belongs to the row, not to the open tile: leaving the row is
       the only thing that gives the space back. */
    function holdRow(track) {
      if (heldTrack === track) return;
      if (heldTrack) heldTrack.style.minHeight = '';
      heldTrack = track || null;
    }

    function clear() { clearTimeout(dwellT); hideLine(); hideBg(); closeRow(); dropClosing(); holdRow(null); sweepPanels(); }

    function eligible(node) {
      if (!node || !node._item) return false;
      if (!cfg.line && !cfg.rowOpen) return false;
      const v = o.getView && o.getView();
      if (!v || v.type !== 'home') return false;   // Home rows only, this phase
      return !!node.closest('.crow');              // not the channel rail, not the hero
    }

    /* onFocus reports WHICH direction rendered, because the caller has to know: J
       changes the row's height after the app already decided where to park the page,
       so the app's focus code re-runs its reveal rule. L never needs that. */
    const api = {
      cfg: cfg,
      onFocus(node) {
        lastNode = node;
        clearTimeout(dwellT);
        if (!eligible(node)) { clear(); return null; }
        const f = o.fieldsFor(node._item);
        if (!f) { clear(); return null; }
        if (cfg.rowOpen) {                                   // J supersedes L
          hideLine();
          closeRow();
          sweepPanels();
          holdRow(node.closest('.track'));
          // at 0 the open is not deferred at all, rather than deferred by a zero timer:
          // a settled viewer should never wait a frame they did not ask for
          if (cfg.delay) dwellT = setTimeout(() => {
            if (cfg.rowOpen && lastNode === node && node.isConnected) openRow(node, f);
          }, cfg.delay);
          else openRow(node, f);
          return 'row';
        }
        closeRow();
        if (!cfg.line) return null;
        /* The line waits too, and while it waits it says NOTHING. Leaving the previous
           title's facts up during a sweep would be the one thing this surface must not
           do: state something true of a title that is no longer focused. */
        if (cfg.delay) { hideLine(); dwellT = setTimeout(() => { if (cfg.line && lastNode === node && node.isConnected) showLine(f); }, cfg.delay); }
        else showLine(f);
        return 'line';
      },
      clear: clear,
      /* The product pushes this config from Jellystructure; in the mockup it arrives
         from localStorage — either from the admin page in another tab (storage event)
         or from the TV preview's own picker (ravilo:focuscfg). Re-reading has to be
         live: a setting you must reload to see is a setting nobody compares. */
      apply(next) {
        if (cfg.line === next.line && cfg.rowOpen === next.rowOpen && cfg.delay === next.delay) return;
        cfg.line = next.line; cfg.rowOpen = next.rowOpen; cfg.delay = next.delay;
        clearTimeout(dwellT); closeRow(); dropClosing(); holdRow(null); hideBg();
        if (!cfg.line || cfg.rowOpen) lineOff(); else hideLine();
        const n = lastNode && lastNode.isConnected ? lastNode : null;
        if (n && o.refocus) o.refocus(n); else if (n) api.onFocus(n);
      },
    };
    window.addEventListener('storage', e => { if (e.key === KEY) api.apply(readCfg()); });
    window.addEventListener('ravilo:focuscfg', () => api.apply(readCfg()));
    return api;
  };
})();
