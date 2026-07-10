/* Ravilo Live TV — hi-fi prototype (React + Babel). Uses ravilo.css tokens/skins + livetv.css.
   Screens: Home ("On now" row) → Guide (EPG, 3 layouts) → Live player (channel bar, Now/Next,
   zapping, number entry). Variations exposed as Tweaks. D-pad (arrows/enter/esc) + mouse. */
const { useReducer, useEffect, useRef } = React;
const LT = window.LiveTV;
const CH = LT.channels;
const CATS = LT.CATS;
const CAT_LIST = ['all', ...Object.keys(CATS)];
const catLabel = c => c === 'all' ? 'All channels' : CATS[c].label;

const DENS = {
  compact:  { rowh: 66, chcol: 226, slotpx: 190 },
  spacious: { rowh: 88, chcol: 264, slotpx: 240 },
};

/* ---------- small helpers ---------- */
function ChLogo({ ch, className, style }) {
  return <span className={'ch-logo ' + (className || '')} style={{ background: ch.grad, ...(style || {}) }}>{ch.initials}</span>;
}
function progsWin(chId) {
  return (LT.schedule[chId] || []).filter(p => p.end > LT.WIN_START && p.start < LT.WIN_END);
}
function nowColIdx(chId) {
  const w = progsWin(chId); const i = w.findIndex(p => LT.NOW >= p.start && LT.NOW < p.end);
  return i < 0 ? 0 : i;
}
function filteredCh(cat) { return cat === 'all' ? CH : CH.filter(c => c.cat === cat); }

/* ---------- initial state / reducer ---------- */
const initState = {
  screen: 'home',
  home: { zone: 'guide', i: 0 },                 // zone: guide | row
  guide: { zone: 'grid', cat: 'all', r: 0, c: nowColIdx(CH[0].id) },
  player: { ch: 0, overlay: 'none', nn: 0, bar: true, num: '', zapSeq: 0 },
  toast: '',
};

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function reducer(s, a) {
  switch (a.type) {
    case 'GO_HOME': return { ...s, screen: 'home' };
    case 'GO_GUIDE': return { ...s, screen: 'guide' };
    case 'PLAY': {
      const idx = CH.findIndex(c => c.id === a.chId);
      return { ...s, screen: 'player', player: { ...s.player, ch: idx < 0 ? 0 : idx, overlay: 'none', bar: true, num: '', zapSeq: s.player.zapSeq + 1 } };
    }
    case 'HOME_FOCUS': return { ...s, home: { ...s.home, ...a.f } };
    case 'GUIDE_FOCUS': return { ...s, guide: { ...s.guide, ...a.f } };
    case 'SET_CAT': return { ...s, guide: { ...s.guide, cat: a.cat, r: 0, c: 0 } };
    case 'PLAYER': return { ...s, player: { ...s.player, ...a.p } };
    case 'ZAP': {
      const n = CH.length; const ch = (s.player.ch + a.d + n) % n;
      return { ...s, player: { ...s.player, ch, zapSeq: s.player.zapSeq + 1, bar: true, overlay: 'none' } };
    }
    case 'TUNE_NUM': {
      const idx = CH.findIndex(c => String(c.num) === s.player.num);
      if (idx < 0) return { ...s, player: { ...s.player, num: '' }, toast: 'No channel ' + s.player.num };
      return { ...s, player: { ...s.player, ch: idx, num: '', zapSeq: s.player.zapSeq + 1, bar: true } };
    }
    case 'TOAST': return { ...s, toast: a.msg };
    case 'BAR': return { ...s, player: { ...s.player, bar: a.v } };
    default: return s;
  }
}

/* ---------- keyboard handling per screen ---------- */
function handleKey(s, dispatch, t, key) {
  const back = key === 'Escape' || key === 'Backspace';
  if (s.screen === 'home') {
    if (key === 'ArrowDown' && s.home.zone === 'guide') return dispatch({ type: 'HOME_FOCUS', f: { zone: 'row' } });
    if (key === 'ArrowUp' && s.home.zone === 'row') return dispatch({ type: 'HOME_FOCUS', f: { zone: 'guide' } });
    if (s.home.zone === 'row') {
      const on = LT.onNow();
      if (key === 'ArrowRight') return dispatch({ type: 'HOME_FOCUS', f: { i: clamp(s.home.i + 1, 0, on.length - 1) } });
      if (key === 'ArrowLeft') return dispatch({ type: 'HOME_FOCUS', f: { i: clamp(s.home.i - 1, 0, on.length - 1) } });
      if (key === 'Enter') return dispatch({ type: 'PLAY', chId: on[s.home.i].ch.id });
    }
    if (key === 'Enter' && s.home.zone === 'guide') return dispatch({ type: 'GO_GUIDE' });
    return;
  }
  if (s.screen === 'guide') {
    if (back) return dispatch({ type: 'GO_HOME' });
    const chans = filteredCh(s.guide.cat);
    const layout = t.epgLayout;
    if (s.guide.zone === 'chips') {
      const ci = CAT_LIST.indexOf(s.guide.cat);
      if (key === 'ArrowRight') return dispatch({ type: 'SET_CAT', cat: CAT_LIST[clamp(ci + 1, 0, CAT_LIST.length - 1)] });
      if (key === 'ArrowLeft') return dispatch({ type: 'SET_CAT', cat: CAT_LIST[clamp(ci - 1, 0, CAT_LIST.length - 1)] });
      if (key === 'ArrowDown' || key === 'Enter') return dispatch({ type: 'GUIDE_FOCUS', f: { zone: layout === 'timeline' ? 'rail' : 'grid', r: 0, c: 0 } });
      return;
    }
    // in the grid/list/timeline body
    if (key === 'ArrowUp' && (s.guide.r === 0)) return dispatch({ type: 'GUIDE_FOCUS', f: { zone: 'chips' } });
    if (layout === 'timeline') {
      if (s.guide.zone === 'rail') {
        if (key === 'ArrowDown') return dispatch({ type: 'GUIDE_FOCUS', f: { r: clamp(s.guide.r + 1, 0, chans.length - 1) } });
        if (key === 'ArrowUp') return dispatch({ type: 'GUIDE_FOCUS', f: { r: clamp(s.guide.r - 1, 0, chans.length - 1) } });
        if (key === 'ArrowRight') return dispatch({ type: 'GUIDE_FOCUS', f: { zone: 'strip', c: 0 } });
        if (key === 'Enter') return dispatch({ type: 'PLAY', chId: chans[s.guide.r].id });
      } else { // strip
        const w = progsWin(chans[s.guide.r].id);
        if (key === 'ArrowRight') return dispatch({ type: 'GUIDE_FOCUS', f: { c: clamp(s.guide.c + 1, 0, w.length - 1) } });
        if (key === 'ArrowLeft') return s.guide.c === 0 ? dispatch({ type: 'GUIDE_FOCUS', f: { zone: 'rail' } }) : dispatch({ type: 'GUIDE_FOCUS', f: { c: s.guide.c - 1 } });
        if (key === 'Enter') return dispatch({ type: 'PLAY', chId: chans[s.guide.r].id });
      }
      return;
    }
    // grid + list share row movement
    if (key === 'ArrowDown') return dispatch({ type: 'GUIDE_FOCUS', f: { r: clamp(s.guide.r + 1, 0, chans.length - 1), c: layout === 'list' ? clamp(s.guide.c, 0, 1) : s.guide.c } });
    if (key === 'ArrowUp') return dispatch({ type: 'GUIDE_FOCUS', f: { r: clamp(s.guide.r - 1, 0, chans.length - 1) } });
    if (layout === 'list') {
      if (key === 'ArrowRight') return dispatch({ type: 'GUIDE_FOCUS', f: { c: clamp(s.guide.c + 1, 0, 1) } });
      if (key === 'ArrowLeft') return dispatch({ type: 'GUIDE_FOCUS', f: { c: clamp(s.guide.c - 1, 0, 1) } });
      if (key === 'Enter') return dispatch({ type: 'PLAY', chId: chans[s.guide.r].id });
    } else { // grid
      const w = progsWin(chans[s.guide.r].id);
      if (key === 'ArrowRight') return dispatch({ type: 'GUIDE_FOCUS', f: { c: clamp(s.guide.c + 1, 0, w.length - 1) } });
      if (key === 'ArrowLeft') return dispatch({ type: 'GUIDE_FOCUS', f: { c: clamp(s.guide.c - 1, 0, w.length - 1) } });
      if (key === 'Enter') return dispatch({ type: 'PLAY', chId: chans[s.guide.r].id });
    }
    return;
  }
  if (s.screen === 'player') {
    const p = s.player;
    if (/^[0-9]$/.test(key)) { const num = (p.num + key).slice(0, 3); return dispatch({ type: 'PLAYER', p: { num, bar: true } }); }
    if (p.num) { if (key === 'Enter') return dispatch({ type: 'TUNE_NUM' }); if (back) return dispatch({ type: 'PLAYER', p: { num: '' } }); }
    if (back) { if (p.overlay === 'nownext') return dispatch({ type: 'PLAYER', p: { overlay: 'none' } }); return dispatch({ type: 'GO_GUIDE' }); }
    if (p.overlay === 'nownext') {
      if (key === 'ArrowUp') return dispatch({ type: 'PLAYER', p: { nn: clamp(p.nn - 1, 0, CH.length - 1) } });
      if (key === 'ArrowDown') return dispatch({ type: 'PLAYER', p: { nn: clamp(p.nn + 1, 0, CH.length - 1) } });
      if (key === 'Enter') return dispatch({ type: 'PLAYER', p: { ch: p.nn, overlay: 'none', bar: true, zapSeq: p.zapSeq + 1 } });
      return;
    }
    if (key === 'ArrowUp') return dispatch({ type: 'PLAYER', p: { overlay: 'nownext', nn: p.ch, bar: true } });
    if (key === 'ArrowDown') return dispatch({ type: 'BAR', v: !p.bar });
    if (key === 'ArrowRight') return dispatch({ type: 'ZAP', d: 1 });
    if (key === 'ArrowLeft') return dispatch({ type: 'ZAP', d: -1 });
    if (key === 'Enter') return dispatch({ type: 'BAR', v: !p.bar });
    return;
  }
}

/* ============================ screens ============================ */
function AppBar({ screen }) {
  // Live TV has NO top-nav tab — it's surfaced on Home ("On now" row) + optionally as a collection.
  const nav = [['home', 'Home'], ['movies', 'Movies'], ['series', 'Series'], ['discover', 'Discover']];
  return (
    <div className="lt-bar">
      <div className="lt-brand"><span className="mk"></span>Ravilo</div>
      <div className="lt-nav">
        {nav.map(n => <div key={n[0]} className={'lt-navitem' + (n[0] === 'home' ? ' cur' : '')}>{n[1]}</div>)}
      </div>
      <div className="right"><span className="lt-clock">{LT.fmt(LT.NOW)}</span><span className="lt-av">ER</span></div>
    </div>
  );
}

function OnNowCard({ x, shape, focused, onClick }) {
  const { ch, now, next } = x;
  const pc = LT.pct(now);
  return (
    <div className={'onnow v-' + shape + (focused ? ' focused' : '')} onClick={onClick}>
      <div className="art" style={{ position: 'relative' }}>
        <div className="grad" style={{ background: ch.grad }}></div>
        <div className="scrim"></div>
        <span className="badge-live">LIVE</span>
        {shape === 'logo'
          ? <ChLogo ch={ch} />
          : <div className="biglogo" style={{ position: 'relative', zIndex: 2, width: '100%', textAlign: 'center', paddingBottom: 12, color: 'rgba(255,255,255,.92)', fontFamily: "'Space Grotesk',sans-serif", fontWeight: 700, fontSize: shape === 'poster' ? 26 : 30 }}>{ch.initials}</div>}
      </div>
      <div className="meta">
        <div className="chrow"><span className="chnum">{ch.num}</span><span className="chname">{ch.name}</span></div>
        <div className="ptitle">{now.title}</div>
        <div className="ptime">{LT.fmt(now.start)}–{LT.fmt(now.end)}{next ? ' · Next: ' + next.title : ''}</div>
        <div className="pbar"><i style={{ width: pc + '%' }}></i></div>
      </div>
    </div>
  );
}

function HomeScreen({ st, dispatch, t }) {
  const on = LT.onNow();
  return (
    <div className="lt-scroll"><div className="lt-home">
      <h1>Live TV</h1>
      <div className="sub">{CH.length} channels from Jellyfin · guide updates automatically</div>
      <div className={'lt-guidebtn' + (st.home.zone === 'guide' ? ' focused' : '')} onClick={() => dispatch({ type: 'GO_GUIDE' })}>
        <span className="ic">▦</span> Open TV Guide
      </div>
      <div className="rowlabel"><span className="live-dot"></span> On now</div>
      <div className="onnow-row">
        {on.slice(0, 8).map((x, i) => (
          <OnNowCard key={x.ch.id} x={x} shape={t.onNowShape}
            focused={st.home.zone === 'row' && st.home.i === i}
            onClick={() => dispatch({ type: 'PLAY', chId: x.ch.id })} />
        ))}
      </div>
    </div></div>
  );
}

function CatChips({ st, dispatch }) {
  return (
    <div className="cat-chips">
      {CAT_LIST.map(c => (
        <span key={c} className={'cat-chip' + (c === st.guide.cat ? ' on' : '') + (st.guide.zone === 'chips' && c === st.guide.cat ? ' focused' : '')}
          onClick={() => dispatch({ type: 'SET_CAT', cat: c })}>{catLabel(c)}</span>
      ))}
    </div>
  );
}

/* ----- GUIDE: grid ----- */
function GuideGrid({ st, dispatch, t }) {
  const d = DENS[t.density];
  const chans = filteredCh(st.guide.cat);
  const ppm = d.slotpx / 30;
  const timeW = (LT.WIN_END - LT.WIN_START) * ppm;
  const scrollRef = useRef(null);
  useEffect(() => {
    const el = scrollRef.current; if (!el || st.guide.zone !== 'grid') return;
    const w = progsWin(chans[st.guide.r].id); const p = w[st.guide.c]; if (!p) return;
    const left = (Math.max(p.start, LT.WIN_START) - LT.WIN_START) * ppm;
    const top = st.guide.r * d.rowh;
    if (left < el.scrollLeft) el.scrollLeft = Math.max(0, left - 20);
    else if (left + 120 > el.scrollLeft + (el.clientWidth - d.chcol)) el.scrollLeft = left - 40;
    if (top < el.scrollTop) el.scrollTop = Math.max(0, top - d.rowh);
    else if (top + d.rowh > el.scrollTop + el.clientHeight) el.scrollTop = top - el.clientHeight + d.rowh * 2;
  }, [st.guide.r, st.guide.c, st.guide.zone, st.guide.cat, t.density]);
  const nowLeft = d.chcol + (LT.NOW - LT.WIN_START) * ppm;
  return (
    <div className={'epg-grid ' + t.density} style={{ '--chcol': d.chcol + 'px' }}>
      <div className="epg-scroll" ref={scrollRef} style={{ top: 0 }}>
        <div className="epg-canvas" style={{ width: d.chcol + timeW }}>
          <div className="epg-timebar">
            <div style={{ width: d.chcol, flex: 'none', position: 'sticky', left: 0, zIndex: 7, background: 'var(--bg)', height: '100%' }}></div>
            {LT.slots().map(sl => <div key={sl} className="epg-slot" style={{ width: d.slotpx }}>{LT.fmt(sl)}</div>)}
          </div>
          <div className="epg-nowline" style={{ left: nowLeft, top: 40 }}></div>
          {chans.map((ch, ri) => {
            const w = progsWin(ch.id);
            return (
              <div className="epg-row" key={ch.id} style={{ height: d.rowh }}>
                <div className="epg-chcol">
                  <ChLogo ch={ch} /><div className="cn"><div className="cnum">{ch.num}</div><div className="cname">{ch.name}</div></div>
                </div>
                <div className="epg-track" style={{ width: timeW }}>
                  {w.map((p, ci) => {
                    const l = (Math.max(p.start, LT.WIN_START) - LT.WIN_START) * ppm;
                    const width = (Math.min(p.end, LT.WIN_END) - Math.max(p.start, LT.WIN_START)) * ppm - 6;
                    const live = LT.NOW >= p.start && LT.NOW < p.end;
                    const foc = st.guide.zone === 'grid' && st.guide.r === ri && st.guide.c === ci;
                    return (
                      <div key={ci} className={'epg-cell' + (live ? ' live' : '') + (foc ? ' focused' : '')}
                        style={{ left: l, width: Math.max(width, 40) }}
                        onClick={() => dispatch({ type: 'PLAY', chId: ch.id })}>
                        <div className="pt">{p.title}</div>
                        <div className="ps">{live && <span className="liveflag">● LIVE</span>}{LT.fmt(p.start)}</div>
                        {live && <div className="cbar" style={{ width: LT.pct(p) + '%' }}></div>}
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* ----- GUIDE: list ----- */
function GuideList({ st, dispatch }) {
  const chans = filteredCh(st.guide.cat);
  const scrollRef = useRef(null);
  useEffect(() => {
    const el = scrollRef.current; if (!el) return;
    const node = el.querySelector('.epg-lrow.rfoc'); if (node) {
      if (node.offsetTop < el.scrollTop) el.scrollTop = Math.max(0, node.offsetTop - 20);
      else if (node.offsetTop + node.offsetHeight > el.scrollTop + el.clientHeight) el.scrollTop = node.offsetTop - el.clientHeight + node.offsetHeight + 20;
    }
  }, [st.guide.r, st.guide.cat]);
  return (
    <div className="epg-list" ref={scrollRef} style={{ overflowY: 'hidden' }}>
      {chans.map((ch, ri) => {
        const { now, next } = LT.nowNext(ch.id);
        const rf = st.guide.r === ri;
        const card = (p, isNow, ci) => p && (
          <div className={'epg-lcard' + (isNow ? ' live' : '') + (rf && st.guide.c === ci && st.guide.zone === 'grid' ? ' focused' : '')}
            onClick={() => dispatch({ type: 'PLAY', chId: ch.id })}>
            <div className={'k' + (isNow ? ' now' : '')}>{isNow ? '● Now' : 'Next'}</div>
            <div className="pt">{p.title}</div>
            <div className="ps">{LT.fmt(p.start)}–{LT.fmt(p.end)} · {p.sub}</div>
            {isNow && <div className="cbar" style={{ width: LT.pct(p) + '%' }}></div>}
          </div>
        );
        return (
          <div className={'epg-lrow' + (rf ? ' rfoc' : '')} key={ch.id}>
            <div className="epg-lchan"><ChLogo ch={ch} /><div><div className="cnum" style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 13, color: 'var(--ink-dim)' }}>{ch.num}</div><div style={{ fontSize: 17, fontWeight: 600 }}>{ch.name}</div></div></div>
            {card(now, true, 0)}
            {card(next, false, 1)}
          </div>
        );
      })}
    </div>
  );
}

/* ----- GUIDE: timeline ----- */
function GuideTimeline({ st, dispatch }) {
  const chans = filteredCh(st.guide.cat);
  const ch = chans[clamp(st.guide.r, 0, chans.length - 1)];
  const { now } = LT.nowNext(ch.id);
  const upcoming = progsWin(ch.id).filter(p => p.end > LT.NOW);
  const railRef = useRef(null);
  useEffect(() => {
    const el = railRef.current; if (!el) return; const node = el.querySelector('.epg-tl-ch.focused');
    if (node) { if (node.offsetTop < el.scrollTop) el.scrollTop = Math.max(0, node.offsetTop - 20); else if (node.offsetTop + node.offsetHeight > el.scrollTop + el.clientHeight) el.scrollTop = node.offsetTop - el.clientHeight + node.offsetHeight + 20; }
  }, [st.guide.r]);
  return (
    <div className="epg-tl">
      <div className="epg-tl-rail" ref={railRef} style={{ overflowY: 'hidden' }}>
        {chans.map((c, ri) => (
          <div key={c.id} className={'epg-tl-ch' + (ri === st.guide.r ? ' cur' : '') + (ri === st.guide.r && st.guide.zone === 'rail' ? ' focused' : '')}
            onClick={() => dispatch({ type: 'PLAY', chId: c.id })}>
            <ChLogo ch={c} /><div><div className="cnum">{c.num}</div><div className="cname">{c.name}</div></div>
          </div>
        ))}
      </div>
      <div className="epg-tl-main">
        <div className="epg-tl-hero">
          <ChLogo ch={ch} />
          <div><div className="now-k">● NOW · {ch.num} {ch.name}</div><div className="now-t">{now ? now.title : '—'}</div><div className="now-s">{now ? LT.fmt(now.start) + '–' + LT.fmt(now.end) + ' · ' + now.sub : ''}</div></div>
        </div>
        <div className="epg-tl-strip">
          {upcoming.map((p, ci) => (
            <div key={ci} className={'epg-tl-card' + (st.guide.zone === 'strip' && st.guide.c === ci ? ' focused' : '')}
              onClick={() => dispatch({ type: 'PLAY', chId: ch.id })}>
              <div className="tm">{LT.fmt(p.start)}{ci === 0 && LT.NOW < p.end && LT.NOW >= p.start ? ' · ● live' : ''}</div>
              <div className="pt">{p.title}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function GuideScreen({ st, dispatch, t }) {
  return (
    <div className="epg">
      <div className="epg-head"><h2>TV Guide</h2><span className="now-ts">Now · {LT.fmt(LT.NOW)}</span></div>
      <CatChips st={st} dispatch={dispatch} />
      <div style={{ flex: 1, position: 'relative' }}>
        {t.epgLayout === 'grid' && <GuideGrid st={st} dispatch={dispatch} t={t} />}
        {t.epgLayout === 'list' && <GuideList st={st} dispatch={dispatch} />}
        {t.epgLayout === 'timeline' && <GuideTimeline st={st} dispatch={dispatch} />}
      </div>
    </div>
  );
}

/* ----- PLAYER ----- */
function PlayerScreen({ st, dispatch, t }) {
  const p = st.player; const ch = CH[p.ch];
  const { now, next } = LT.nowNext(ch.id);
  const chrome = t.playerChrome;
  const showBar = p.bar && p.overlay !== 'nownext';
  return (
    <div className="lt-player">
      <div className="lt-video">
        <div className="bg" style={{ background: ch.grad }}></div>
        <div className="noise"></div>
        <div className="biglogo"><ChLogo ch={ch} /><div className="cn">{ch.num} · {ch.name}</div></div>
      </div>
      <div className="lt-live-badge"><span className="d"></span> LIVE</div>

      {p.num && <div className="lt-numosd">{p.num}<span style={{ opacity: .35 }}>{'_'.repeat(Math.max(0, 3 - p.num.length))}</span></div>}

      {/* brief zap banner keyed to zapSeq */}
      <ZapBanner key={p.zapSeq} ch={ch} now={now} />

      {/* Now/Next overlay */}
      <div className={'lt-nownext' + (p.overlay === 'nownext' ? '' : ' hidden')}>
        <div className="lt-nn-h">Now / Next · ↑↓ browse · ↵ tune</div>
        {CH.slice(Math.max(0, p.nn - 2), Math.max(0, p.nn - 2) + 5).map((c) => {
          const gi = CH.indexOf(c); const nn = LT.nowNext(c.id);
          return (
            <div key={c.id} className={'lt-nn-row' + (gi === p.ch ? ' cur' : '') + (gi === p.nn ? ' focused' : '')}
              onClick={() => dispatch({ type: 'PLAYER', p: { ch: gi, overlay: 'none', bar: true, zapSeq: p.zapSeq + 1 } })}>
              <span className="cnum">{c.num}</span><ChLogo ch={c} /><span className="cname">{c.name}</span>
              <span className="nn-now"><span className="nn-k">NOW</span>{nn.now ? nn.now.title : '—'}</span>
              <span className="nn-next"><span className="nn-k">NEXT</span>{nn.next ? nn.next.title : '—'}</span>
            </div>
          );
        })}
      </div>

      {/* bottom channel bar */}
      <div className={'lt-chanbar' + (showBar ? '' : ' hidden')}>
        <div className="lt-chanbar-top">
          <ChLogo ch={ch} /><span className="cnum">{ch.num}</span><span className="cname">{ch.name}</span>
          {chrome !== 'minimal' && <span className="catpill">{ch.catLabel}</span>}
          <span style={{ marginLeft: 'auto', fontSize: 15, color: 'var(--ink-dim)' }}>← → change channel · ↑ guide</span>
        </div>
        {chrome !== 'minimal' && now && <div className="now-t">{now.title}</div>}
        {chrome !== 'minimal' && now && <div className="now-times">{LT.fmt(now.start)}–{LT.fmt(now.end)} · {now.sub}</div>}
        {chrome === 'rich' && next && <div className="next-t">Next <b>{next.title}</b> · {LT.fmt(next.start)}</div>}
        <div className="lt-prog">
          <span className="t">{LT.fmt(now ? now.start : LT.NOW)}</span>
          <div className="track"><i style={{ width: LT.pct(now) + '%' }}></i></div>
          <span className="liveedge"><span className="d" style={{ width: 8, height: 8, borderRadius: '50%', background: '#ff3b3b', display: 'inline-block' }}></span>LIVE</span>
        </div>
      </div>
    </div>
  );
}

function ZapBanner({ ch, now }) {
  const [show, setShow] = React.useState(true);
  useEffect(() => { setShow(true); const h = setTimeout(() => setShow(false), 2600); return () => clearTimeout(h); }, []);
  if (!show) return null;
  return (
    <div className="lt-zap"><ChLogo ch={ch} /><div><div><span className="cnum">{ch.num}</span> <span className="cname">{ch.name}</span></div><div className="now">{now ? now.title : ''}</div></div></div>
  );
}

/* ============================ app root ============================ */
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "epgLayout": "grid",
  "playerChrome": "standard",
  "onNowShape": "landscape",
  "density": "spacious",
  "skin": "aurora"
}/*EDITMODE-END*/;

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [st, dispatch] = useReducer(reducer, initState);
  const stageRef = useRef(null);

  useEffect(() => { document.documentElement.setAttribute('data-skin', t.skin); }, [t.skin]);

  // stage scaling
  useEffect(() => {
    function fit() {
      const el = stageRef.current; if (!el) return;
      const s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
      el.style.transform = `translate(-50%,-50%) scale(${s})`;
    }
    fit(); window.addEventListener('resize', fit); return () => window.removeEventListener('resize', fit);
  }, []);

  // keyboard
  useEffect(() => {
    function onKey(e) {
      const keys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', 'Escape', 'Backspace'];
      if (keys.includes(e.key) || /^[0-9]$/.test(e.key)) { e.preventDefault(); handleKey(st, dispatch, t, e.key); }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [st, t]);

  // auto-hide the player bar after a few seconds
  useEffect(() => {
    if (st.screen !== 'player' || !st.player.bar) return;
    const h = setTimeout(() => dispatch({ type: 'BAR', v: false }), 4500);
    return () => clearTimeout(h);
  }, [st.screen, st.player.bar, st.player.zapSeq]);

  // number-entry auto-tune
  useEffect(() => {
    if (!st.player.num) return;
    const h = setTimeout(() => dispatch({ type: 'TUNE_NUM' }), 1600);
    return () => clearTimeout(h);
  }, [st.player.num]);

  // toast auto-clear
  useEffect(() => { if (!st.toast) return; const h = setTimeout(() => dispatch({ type: 'TOAST', msg: '' }), 1800); return () => clearTimeout(h); }, [st.toast]);

  return (
    <div id="viewport">
      <div id="stage" ref={stageRef} data-screen-label={'Live TV · ' + st.screen}>
        <div className="lt-app">
          {st.screen !== 'player' && <AppBar screen={st.screen} />}
          <div className="lt-body">
            {st.screen === 'home' && <HomeScreen st={st} dispatch={dispatch} t={t} />}
            {st.screen === 'guide' && <GuideScreen st={st} dispatch={dispatch} t={t} />}
            {st.screen === 'player' && <PlayerScreen st={st} dispatch={dispatch} t={t} />}
          </div>
          {st.toast && <div className="lt-toast">{st.toast}</div>}
        </div>
      </div>

      <TweaksPanel title="Tweaks">
        <TweakSection label="Guide" />
        <TweakRadio label="EPG layout" value={t.epgLayout} options={['grid', 'list', 'timeline']} onChange={v => setTweak('epgLayout', v)} />
        <TweakRadio label="Density" value={t.density} options={['compact', 'spacious']} onChange={v => setTweak('density', v)} />
        <TweakSection label="Player" />
        <TweakRadio label="Player chrome" value={t.playerChrome} options={['minimal', 'standard', 'rich']} onChange={v => setTweak('playerChrome', v)} />
        <TweakSection label="Home" />
        <TweakRadio label="On-now card" value={t.onNowShape} options={['landscape', 'poster', 'logo']} onChange={v => setTweak('onNowShape', v)} />
        <TweakSection label="Theme" />
        <TweakRadio label="Skin" value={t.skin} options={['aurora', 'midnight', 'noir']} onChange={v => setTweak('skin', v)} />
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
