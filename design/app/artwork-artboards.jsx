/* Artwork manager — design directions for the Artwork tab (movie + series).
   Renders inside design-canvas.jsx artboards. Uses wf.css / app.css tokens.
   Static, high-fidelity compositions (one representative state each). */

/* ---------- tiny shared pieces ---------- */
function Frame({ children, pad = 26 }) {
  return (
    <div style={{ height: '100%', width: '100%', background: 'var(--bg)', position: 'relative', overflow: 'hidden',
      fontFamily: "'Sora', system-ui, sans-serif", color: 'var(--ink)' }}>
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background:
        'radial-gradient(900px 600px at 10% -10%, rgba(177,92,208,.14), transparent 60%),' +
        'radial-gradient(900px 700px at 100% 0%, rgba(0,164,220,.12), transparent 55%)' }} />
      <div style={{ position: 'relative', padding: pad, height: '100%', boxSizing: 'border-box' }}>{children}</div>
    </div>
  );
}

/* detail-page context header: crumb · title · resolved-language · tab strip */
function DetailHead({ crumb, title, year, tabs, sub }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <div className="crumb" style={{ marginBottom: 8 }}>{crumb}</div>
      <div className="pagebar" style={{ marginBottom: 12 }}>
        <h1 style={{ fontSize: '1.7rem' }}>{title} <span className="muted" style={{ fontWeight: 400 }}>({year})</span></h1>
        <span className="badge info" title="The metadata language resolved from this title's audio tracks. Artwork language preferences default to it.">resolved language <span className="lang" style={{ marginLeft: 4 }}>en</span></span>
        <span className="spacer"></span>
        <span className="btn sm ghost">Jellyfin ↗</span>
        <span className="btn sm ghost">TMDB ↗</span>
      </div>
      <div className="tabs2" style={{ marginBottom: 0 }}>
        {tabs.map((t, i) => <span key={i} className={t === 'Artwork' ? 'on' : ''}>{t}</span>)}
      </div>
    </div>
  );
}

function Sel({ label, value }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {label && <span style={{ fontSize: '.66rem', textTransform: 'uppercase', letterSpacing: '.06em', color: 'var(--ink-soft)', fontWeight: 600 }}>{label}</span>}
      <span className="select" style={{ minWidth: 130, fontSize: '.82rem', padding: '7px 11px', minHeight: 34 }}>{value}</span>
    </div>
  );
}

function LangChips({ items }) {
  return (
    <div className="pill-row">
      {items.map((it, i) => (
        <span key={i} className={'chip lchip' + (it.on ? ' on' : ' off') + (it.tl ? ' tl' : '')}>
          {it.tl ? 'Textless' : it.label}<span className="n">{it.n}</span>
        </span>
      ))}
    </div>
  );
}

function FilterLine({ children }) {
  return <div className="filterline"><span className="ic">ⓘ</span><span>{children}</span></div>;
}

/* poster candidate (2:3) */
function PCand({ c, w = 124, h = 186 }) {
  const tl = c.lang === 'TL';
  return (
    <div className={'art-cand' + (c.current ? ' current' : '') + (c.sel ? ' sel' : '')} style={{ width: w, height: h }}>
      <div className="art-fill" style={{ filter: `hue-rotate(${c.hue}deg)` }} />
      {c.current && <div className="ribbon">ON DISK</div>}
      <div className="tagrow">
        <span className={'lpill' + (tl ? ' tl' : '')}>{tl ? 'textless' : c.lang.toLowerCase()}</span>
        <span className="vote">★ {c.vote}</span>
      </div>
      <div className="meta"><span>{c.res}</span><span style={{ opacity: .65 }}>2:3</span></div>
    </div>
  );
}

/* generic landscape candidate (backdrop / still) */
function LCand({ c, w = 196, h = 110, logo }) {
  const tl = c.lang === 'TL';
  return (
    <div className={'art-cand' + (c.current ? ' current' : '') + (c.sel ? ' sel' : '')} style={{ width: w, height: h }}>
      {logo
        ? <div className="checker" style={{ position: 'absolute', inset: 0 }}><div className="logomark" style={{ fontSize: w / 9 }}>Sintel</div></div>
        : <div className="art-fill" style={{ filter: `hue-rotate(${c.hue}deg)` }} />}
      {c.current && <div className="ribbon">ON DISK</div>}
      <div className="tagrow">
        <span className={'lpill' + (tl ? ' tl' : '')}>{tl ? 'textless' : c.lang.toLowerCase()}</span>
        <span className="vote">★ {c.vote}</span>
      </div>
      <div className="meta"><span>{c.res}</span></div>
    </div>
  );
}

/* ===================================================================
   DIRECTION A — asset rail + inline gallery (single pane workhorse)
   =================================================================== */
const POSTERS_A = [
  { hue: 0, vote: '8.4', res: '2000×3000', lang: 'EN', current: true },
  { hue: 18, vote: '8.1', res: '1400×2100', lang: 'EN', sel: true },
  { hue: 200, vote: '7.9', res: '2000×3000', lang: 'TL' },
  { hue: 280, vote: '7.7', res: '1000×1500', lang: 'EN' },
  { hue: 150, vote: '7.6', res: '2000×3000', lang: 'TL' },
  { hue: 320, vote: '7.2', res: '680×1000', lang: 'EN' },
  { hue: 90, vote: '7.0', res: '1400×2100', lang: 'TL' },
  { hue: 240, vote: '6.8', res: '2000×3000', lang: 'EN' },
  { hue: 50, vote: '6.5', res: '1000×1500', lang: 'TL' },
  { hue: 170, vote: '6.1', res: '680×1000', lang: 'EN' },
];
const RAIL = [
  { nm: 'Poster', sub: '2:3 · en · 2000×3000', st: 'ok', active: true, hue: 0, w: 30, h: 45 },
  { nm: 'Backdrop', sub: '16:9 · textless · 3840×2160', st: 'ok', hue: 200, land: true, w: 48, h: 27 },
  { nm: 'Clearlogo', sub: 'transparent · missing', st: 'bad', logo: true, w: 48, h: 27 },
  { nm: 'Banner', sub: '5.4:1 · low-res 1000×185', st: 'warn', hue: 300, land: true, w: 48, h: 18 },
];

function DirA() {
  return (
    <Frame>
      <DetailHead crumb="Movies / Sintel (2010)" title="Sintel" year="2010"
        tabs={['Overview', 'Tracks & order', 'Artwork', 'NFO (raw)', 'History']} />
      <div style={{ display: 'flex', gap: 18, marginTop: 18, alignItems: 'flex-start' }}>

        {/* asset rail */}
        <div className="card" style={{ width: 282, flex: 'none', padding: 14 }}>
          <div className="row center" style={{ marginBottom: 4 }}>
            <h4 style={{ margin: 0 }}>Assets</h4><span className="spacer"></span>
            <span className="badge warn">1 missing</span>
          </div>
          <hr className="dash" style={{ margin: '10px 0' }} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            {RAIL.map((r, i) => (
              <div key={i} className={'rail-item' + (r.active ? ' active' : '')}>
                <div className="th" style={{ width: r.w, height: r.h }}>
                  {r.logo
                    ? <div className="checker" style={{ position: 'absolute', inset: 0 }} />
                    : <div className="art-fill" style={{ filter: `hue-rotate(${r.hue || 0}deg)` }} />}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="nm">{r.nm}</div>
                  <div className="sub">{r.sub}</div>
                </div>
                <span className={'dot ' + r.st}></span>
              </div>
            ))}
          </div>
          <div className="pill-row" style={{ marginTop: 13 }}>
            <span className="btn sm primary" style={{ flex: 1, justifyContent: 'center' }}>Fetch all missing</span>
          </div>
          <div className="tiny muted" style={{ marginTop: 9, lineHeight: 1.5 }}>Written beside the movie file as <span className="mono">poster.jpg</span>, <span className="mono">fanart.jpg</span>, <span className="mono">clearlogo.png</span>.</div>
        </div>

        {/* inline gallery for the selected asset */}
        <div className="card fill" style={{ padding: 16 }}>
          <div className="row center">
            <h4 style={{ margin: 0 }}>Poster <span className="muted" style={{ fontWeight: 400 }}>· 2:3</span></h4>
            <span className="badge ok" style={{ marginLeft: 8 }}>on disk · en · 2000×3000</span>
            <span className="spacer"></span>
            <span className="mono tiny muted">31 candidates on TMDB</span>
          </div>

          <div className="dropz" style={{ marginTop: 13 }}>
            <span className="di">⤓</span>
            <div style={{ flex: 1 }}><b style={{ color: 'var(--ink)' }}>Drag an image here</b> to replace the poster <span className="muted">— or</span></div>
            <span className="btn sm">Upload</span>
            <span className="btn sm ghost">Paste URL</span>
          </div>

          {/* filter toolbar */}
          <div className="row center" style={{ marginTop: 16, gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <span className="eyebrow">Language</span>
              <div style={{ marginTop: 6 }}>
                <LangChips items={[
                  { label: 'English', n: 12, on: true },
                  { tl: true, n: 5, on: true },
                  { label: 'German', n: 6 },
                  { label: 'French', n: 4 },
                  { label: '日本語', n: 3 },
                  { label: 'All', n: 31 },
                ]} />
              </div>
            </div>
            <Sel label="Sort" value="Vote score ↓" />
            <Sel label="Min resolution" value="≥ 1000w" />
          </div>

          <div style={{ marginTop: 12, padding: '11px 13px', background: 'var(--info-soft)', border: '1px solid rgba(63,182,245,.3)', borderRadius: 10 }}>
            <FilterLine>
              Showing <b>English</b> + <b>textless</b> — matched to this title's resolved language <span className="lang">en</span>, with textless preferred for clean posters. <b>13</b> other-language posters hidden. <a href="#">Show all 31 →</a>
            </FilterLine>
          </div>

          {/* candidate grid */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 14, marginTop: 16, justifyItems: 'center' }}>
            {POSTERS_A.map((c, i) => <PCand key={i} c={c} />)}
          </div>
          <div className="tiny muted" style={{ marginTop: 12 }}>Click a candidate to stage it · the green outline is what's currently on disk · changes write on <b>Save → disk</b>.</div>
        </div>
      </div>
    </Frame>
  );
}

/* ===================================================================
   DIRECTION B — overview board (all assets) + focused picker modal
   =================================================================== */
const BACKDROPS_B = [
  { hue: 200, vote: '8.6', res: '3840×2160', lang: 'TL', current: true },
  { hue: 230, vote: '8.2', res: '1920×1080', lang: 'TL', sel: true },
  { hue: 280, vote: '7.9', res: '3840×2160', lang: 'EN' },
  { hue: 160, vote: '7.5', res: '1920×1080', lang: 'TL' },
  { hue: 320, vote: '7.1', res: '1280×720', lang: 'EN' },
  { hue: 60, vote: '6.7', res: '1920×1080', lang: 'TL' },
];

function AssetTile({ title, ratio, w, h, st, stLabel, logo, hue, banner }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
      <div style={{ width: w, height: h, borderRadius: 9, overflow: 'hidden', position: 'relative', border: '1px solid var(--line)' }}>
        {logo
          ? <div className="checker" style={{ position: 'absolute', inset: 0 }}><div className="logomark" style={{ fontSize: w / 8 }}>Sintel</div></div>
          : <div className="art-fill" style={{ filter: `hue-rotate(${hue || 0}deg)` }} />}
        {st === 'bad' && <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bad-soft)' }}><span className="badge bad">missing</span></div>}
      </div>
      <div className="row center" style={{ gap: 8 }}>
        <span className="statusdot"><span className={'dot ' + st}></span>{title}</span>
        <span className="spacer"></span>
        <span className="tiny muted mono">{ratio}</span>
      </div>
      <div className="pill-row">
        <span className={'btn sm' + (st === 'bad' ? ' primary' : ' ghost')} style={{ padding: '4px 10px' }}>{st === 'bad' ? 'Fetch' : 'Replace'}</span>
        {st !== 'bad' && <span className="tiny muted">{stLabel}</span>}
      </div>
    </div>
  );
}

function DirB() {
  return (
    <Frame>
      <DetailHead crumb="Movies / Sintel (2010)" title="Sintel" year="2010"
        tabs={['Overview', 'Tracks & order', 'Artwork', 'NFO (raw)', 'History']} />

      {/* complete-set board */}
      <div className="card" style={{ marginTop: 18, padding: 18 }}>
        <div className="row center">
          <h4 style={{ margin: 0 }}>Complete set</h4>
          <span className="badge warn" style={{ marginLeft: 8 }}>3 of 4 present</span>
          <span className="spacer"></span>
          <span className="btn sm primary">Fetch all from TMDB</span>
          <span className="btn sm ghost">Upload…</span>
        </div>
        <hr className="dash" style={{ margin: '14px 0' }} />
        <div style={{ display: 'flex', gap: 26, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <AssetTile title="Poster" ratio="2:3" w={130} h={195} st="ok" stLabel="en" hue={0} />
          <AssetTile title="Backdrop" ratio="16:9" w={300} h={169} st="ok" stLabel="textless" hue={200} />
          <AssetTile title="Clearlogo" ratio="transparent" w={230} h={92} st="bad" logo />
          <AssetTile title="Banner" ratio="5.4:1" w={300} h={56} st="warn" stLabel="low-res" hue={300} />
        </div>
      </div>

      {/* focused picker modal over a dimmed board */}
      <div className="ov-back">
        <div className="ov-modal">
          <div className="row center" style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
            <h4 style={{ margin: 0 }}>Backdrop · pick from TMDB</h4>
            <span className="badge ok" style={{ marginLeft: 8 }}>17 results</span>
            <span className="spacer"></span>
            <span style={{ cursor: 'pointer', color: 'var(--ink-soft)', fontSize: '1.1rem' }}>✕</span>
          </div>

          <div className="row center" style={{ padding: '12px 20px', gap: 10, borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
            <LangChips items={[{ tl: true, n: 12, on: true }, { label: 'English', n: 5, on: true }, { label: 'All', n: 17 }]} />
            <span className="spacer"></span>
            <Sel value="Vote score ↓" />
          </div>
          <div style={{ padding: '9px 20px', borderBottom: '1px solid var(--line)' }}>
            <FilterLine>Backdrops are usually textless — showing <b>textless</b> + <b>English</b> first. No <span className="lang">en</span>-specific backdrop is required here.</FilterLine>
          </div>

          <div style={{ display: 'flex', minHeight: 0 }}>
            {/* gallery */}
            <div style={{ flex: 1, padding: 18, display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 13, justifyItems: 'center', alignContent: 'start' }}>
              {BACKDROPS_B.map((c, i) => <LCand key={i} c={c} w={196} h={110} />)}
            </div>
            {/* compare rail */}
            <div style={{ width: 236, flex: 'none', borderLeft: '1px solid var(--line)', padding: 18, background: 'var(--fill-2)' }}>
              <span className="eyebrow">Compare</span>
              <div style={{ marginTop: 12 }}>
                <div className="tiny muted" style={{ marginBottom: 6 }}>On disk now</div>
                <div style={{ width: '100%', height: 108, borderRadius: 9, overflow: 'hidden', position: 'relative', border: '1px solid var(--line)' }}>
                  <div className="art-fill" style={{ filter: 'hue-rotate(200deg)' }} /><div className="ribbon" style={{ background: 'var(--ok)', color: '#04140d', position: 'absolute', top: 0, right: 0, fontSize: '.56rem', fontWeight: 700, padding: '3px 8px', borderBottomLeftRadius: 9 }}>CURRENT</div>
                </div>
                <div className="tiny muted mono" style={{ marginTop: 5 }}>textless · 3840×2160</div>
              </div>
              <div style={{ textAlign: 'center', color: 'var(--acc-ink)', fontSize: '1.2rem', margin: '8px 0' }}>↓</div>
              <div>
                <div className="tiny muted" style={{ marginBottom: 6 }}>Selected</div>
                <div style={{ width: '100%', height: 108, borderRadius: 9, overflow: 'hidden', position: 'relative', border: '2px solid var(--hi)' }}>
                  <div className="art-fill" style={{ filter: 'hue-rotate(230deg)' }} />
                </div>
                <div className="tiny muted mono" style={{ marginTop: 5 }}>textless · 1920×1080</div>
              </div>
              <div className="pill-row" style={{ marginTop: 16 }}>
                <span className="btn primary" style={{ flex: 1, justifyContent: 'center' }}>Use this backdrop</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Frame>
  );
}

/* ===================================================================
   DIRECTION C — split compare + language-fallback ladder (logo case)
   =================================================================== */
const LOGOS_C = [
  { vote: '8.3', res: '800×310', lang: 'TL', sel: true },
  { vote: '7.8', res: '500×200', lang: 'TL' },
  { vote: '7.1', res: '400×155', lang: 'TL' },
  { vote: '8.0', res: '800×310', lang: 'DE' },
  { vote: '7.4', res: '600×240', lang: 'FR' },
  { vote: '6.9', res: '500×200', lang: 'JA' },
];

function DirC() {
  return (
    <Frame>
      <DetailHead crumb="Movies / Sintel (2010)" title="Sintel" year="2010"
        tabs={['Overview', 'Tracks & order', 'Artwork', 'NFO (raw)', 'History']} />
      <div style={{ display: 'flex', gap: 18, marginTop: 18, alignItems: 'flex-start' }}>

        {/* left: chosen art + fallback ladder */}
        <div style={{ width: 360, flex: 'none', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card" style={{ padding: 16 }}>
            <div className="row center"><h4 style={{ margin: 0 }}>Clearlogo</h4><span className="badge bad" style={{ marginLeft: 8 }}>not on disk</span></div>
            <div className="checker" style={{ height: 132, borderRadius: 10, marginTop: 12, position: 'relative', border: '1px solid var(--line)' }}>
              <div className="logomark" style={{ fontSize: 34 }}>Sintel</div>
              <span className="badge ok" style={{ position: 'absolute', bottom: 8, left: 8 }}>staged · textless</span>
            </div>
            <div className="tiny muted mono" style={{ marginTop: 8 }}>→ writes clearlogo.png · 800×310</div>
          </div>

          <div className="card" style={{ padding: 16 }}>
            <div className="row center"><h4 style={{ margin: 0 }}>Language fallback</h4><span className="spacer"></span><span className="badge info">auto</span></div>
            <div className="tiny muted" style={{ margin: '8px 0 4px', lineHeight: 1.55 }}>We try this title's language first and walk down until something exists — so you never see an empty picker when TMDB has art.</div>
            <hr className="dash" style={{ margin: '10px 0' }} />
            <div className="ladder">
              <div className="ladder-step skip">
                <div className="ladder-num">1</div>
                <div><div className="ladder-lab"><span className="lang">en</span> English <span className="badge bad" style={{ marginLeft: 2 }}>0 found</span></div><div className="ladder-sub">This title's resolved language — but TMDB has no English clearlogo.</div></div>
              </div>
              <div className="ladder-step use">
                <div className="ladder-num">2</div>
                <div><div className="ladder-lab">Textless <span className="badge ok" style={{ marginLeft: 2 }}>3 found · using</span></div><div className="ladder-sub">No-language art — recommended for logos anyway. Selected automatically.</div></div>
              </div>
              <div className="ladder-step">
                <div className="ladder-num">3</div>
                <div><div className="ladder-lab">Any language <span className="badge" style={{ marginLeft: 2 }}>9 more</span></div><div className="ladder-sub">German, French, Japanese… shown below the textless set as alternatives.</div></div>
              </div>
            </div>
          </div>
        </div>

        {/* right: candidate grid + compact filter */}
        <div className="card fill" style={{ padding: 16 }}>
          <div className="row center" style={{ flexWrap: 'wrap', gap: 10 }}>
            <h4 style={{ margin: 0 }}>TMDB clearlogos</h4>
            <span className="spacer"></span>
            <LangChips items={[{ tl: true, n: 3, on: true }, { label: 'German', n: 4 }, { label: 'French', n: 3 }, { label: '日本語', n: 2 }, { label: 'All', n: 12 }]} />
            <Sel value="Vote ↓" />
          </div>

          <div style={{ marginTop: 12, padding: '11px 13px', background: 'var(--warn-soft)', border: '1px solid rgba(245,181,66,.35)', borderRadius: 10 }}>
            <FilterLine><b>No English clearlogo on TMDB.</b> Falling back to <b>textless</b> (3) — the best choice for logos. Other languages shown below as alternatives.</FilterLine>
          </div>

          <div className="eyebrow" style={{ marginTop: 16, display: 'block' }}>Textless · recommended</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 13, marginTop: 10, justifyItems: 'center' }}>
            {LOGOS_C.slice(0, 3).map((c, i) => <LCand key={i} c={c} w={196} h={92} logo />)}
          </div>
          <div className="eyebrow" style={{ marginTop: 18, display: 'block' }}>Other languages</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 13, marginTop: 10, justifyItems: 'center' }}>
            {LOGOS_C.slice(3).map((c, i) => <LCand key={i} c={c} w={196} h={92} logo />)}
          </div>
          <div className="tiny muted" style={{ marginTop: 14 }}>Selected logo is outlined in violet · click any alternative to override the automatic textless pick.</div>
        </div>
      </div>
    </Frame>
  );
}

/* ===================================================================
   SERIES BOARD — season posters + per-episode stills + batch fetch
   =================================================================== */
const SEASONS = [
  { nm: 'Season 1', hue: 0, st: 'ok', sub: 'en · 1400×2100' },
  { nm: 'Season 2', hue: 150, st: 'ok', sub: 'en · 2000×3000' },
  { nm: 'Specials', hue: 300, st: 'bad', sub: 'missing' },
];
const EPISODES = [
  { ep: 'S01E01', t: 'The Gathering Storm', st: 'ok', hue: 200 },
  { ep: 'S01E02', t: 'Forging Ahead', st: 'ok', hue: 250 },
  { ep: 'S01E03', t: 'Into the Mountain', st: 'bad' },
  { ep: 'S01E04', t: 'The Reckoning', st: 'bad' },
  { ep: 'S01E05', t: 'Aftermath', st: 'warn', hue: 320 },
];

function SeriesBoard() {
  return (
    <Frame>
      <DetailHead crumb="TV / Tears of Steel" title="Tears of Steel" year="2012"
        tabs={['Overview', 'Episodes', 'Artwork', 'NFO (raw)', 'History']} />
      <div style={{ display: 'flex', gap: 18, marginTop: 18, alignItems: 'flex-start' }}>

        {/* series-level rail */}
        <div style={{ width: 240, flex: 'none', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card" style={{ padding: 14 }}>
            <h4 style={{ margin: '0 0 10px' }}>Series poster</h4>
            <div style={{ height: 300, borderRadius: 10, overflow: 'hidden', position: 'relative', border: '1px solid var(--line)' }}>
              <div className="art-fill" style={{ filter: 'hue-rotate(20deg)' }} />
              <div className="meta" style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: '16px 8px 7px', display: 'flex', justifyContent: 'space-between', fontFamily: "'JetBrains Mono', monospace", fontSize: '.62rem', color: '#e3e6f0', background: 'linear-gradient(0deg, rgba(0,0,0,.88), transparent)' }}><span>en · 2000×3000</span><span style={{ opacity: .65 }}>2:3</span></div>
            </div>
            <div className="pill-row" style={{ marginTop: 11 }}><span className="btn sm ghost">Replace</span><span className="btn sm ghost">Fanart</span><span className="btn sm ghost">Logo</span></div>
          </div>
          <div className="override" style={{ padding: 14 }}>
            <div className="row center"><h4 style={{ margin: 0, fontSize: '.95rem' }}>Artwork language</h4><span className="badge warn" style={{ marginLeft: 6 }}>mixed</span></div>
            <div className="tiny" style={{ marginTop: 8, lineHeight: 1.55 }}>Episodes resolve their own languages; the series uses the majority <span className="lang">en</span>. Stills follow each episode's language first, then textless.</div>
          </div>
        </div>

        <div className="col fill" style={{ gap: 16 }}>
          {/* season posters */}
          <div className="card" style={{ padding: 16 }}>
            <div className="row center"><h4 style={{ margin: 0 }}>Season posters</h4><span className="spacer"></span><span className="btn sm ghost">Fetch missing</span></div>
            <hr className="dash" style={{ margin: '12px 0' }} />
            <div style={{ display: 'flex', gap: 18 }}>
              {SEASONS.map((s, i) => (
                <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'center' }}>
                  <div style={{ width: 112, height: 168, borderRadius: 9, overflow: 'hidden', position: 'relative', border: '1px solid var(--line)' }}>
                    {s.st === 'bad'
                      ? <div className="checker" style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><span className="badge bad">missing</span></div>
                      : <div className="art-fill" style={{ filter: `hue-rotate(${s.hue}deg)` }} />}
                  </div>
                  <span className="statusdot"><span className={'dot ' + s.st}></span>{s.nm}</span>
                  <span className="tiny muted mono">{s.sub}</span>
                  <span className={'btn sm' + (s.st === 'bad' ? ' primary' : ' ghost')} style={{ padding: '4px 12px' }}>{s.st === 'bad' ? 'Fetch' : 'Replace'}</span>
                </div>
              ))}
            </div>
          </div>

          {/* episode stills */}
          <div className="card" style={{ padding: 16 }}>
            <div className="row center" style={{ flexWrap: 'wrap', gap: 10 }}>
              <h4 style={{ margin: 0 }}>Episode stills</h4>
              <span className="badge warn" style={{ marginLeft: 4 }}>2 missing · 1 low-res</span>
              <span className="spacer"></span>
              <span className="btn sm primary">Fetch 2 missing stills</span>
            </div>
            <div style={{ margin: '12px 0', padding: '10px 13px', background: 'var(--info-soft)', border: '1px solid rgba(63,182,245,.3)', borderRadius: 10 }}>
              <FilterLine>Stills are fetched per episode in <b>that episode's language</b> first, then <b>textless</b>. Written beside each file as <span className="mono">{'{episode}'}-thumb.jpg</span>.</FilterLine>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
              {EPISODES.map((e, i) => (
                <div key={i} className="row center" style={{ gap: 13, padding: '8px 10px', border: '1px solid var(--line)', borderRadius: 10, background: e.st === 'bad' ? 'var(--bad-soft)' : 'var(--fill-2)' }}>
                  <div style={{ width: 120, height: 68, borderRadius: 7, overflow: 'hidden', position: 'relative', border: '1px solid var(--line)', flex: 'none' }}>
                    {e.st === 'bad'
                      ? <div className="checker" style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><span className="tiny mono muted">no still</span></div>
                      : <div className="art-fill" style={{ filter: `hue-rotate(${e.hue}deg)` }} />}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="row center" style={{ gap: 8 }}><span className="mono tiny" style={{ color: 'var(--acc-ink)' }}>{e.ep}</span><b style={{ fontSize: '.92rem' }}>{e.t}</b></div>
                    <div className="tiny muted" style={{ marginTop: 3 }}>
                      {e.st === 'ok' && <span><span className="lang">en</span> · 1280×720</span>}
                      {e.st === 'warn' && <span className="warn" style={{ color: 'var(--warn)' }}>low-res 640×360 — a better still is available</span>}
                      {e.st === 'bad' && <span className="bad" style={{ color: 'var(--bad)' }}>no still on disk</span>}
                    </div>
                  </div>
                  <span className={'btn sm' + (e.st === 'ok' ? ' ghost' : ' primary')}>{e.st === 'ok' ? 'Replace' : 'Fetch still'}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </Frame>
  );
}

/* ---------- compose ---------- */
function App() {
  const { DesignCanvas, DCSection, DCArtboard, DCPostIt } = window;
  return (
    <DesignCanvas>
      <DCSection id="movie" title="Movie detail · Artwork manager"
        subtitle="Three layout directions for the Artwork tab — Sintel (2010), resolved metadata language EN">
        <DCArtboard id="A" label="A · Asset rail + inline gallery" width={1200} height={944} style={{ background: 'var(--bg)' }}><DirA /></DCArtboard>
        <DCArtboard id="B" label="B · Overview board + focused picker" width={1240} height={884} style={{ background: 'var(--bg)' }}>
          <DirB />
          <DCPostIt top={-4} right={-150} width={172} rotate={2}>Picker opens as a focused modal — good when the board is the “home” and editing is occasional.</DCPostIt>
        </DCArtboard>
        <DCArtboard id="C" label="C · Split compare + language fallback ladder" width={1180} height={860} style={{ background: 'var(--bg)' }}><DirC /></DCArtboard>
      </DCSection>
      <DCSection id="series" title="Series detail · Seasons & episode stills"
        subtitle="Tears of Steel — season posters and per-episode stills with batch fetch + per-episode language fallback">
        <DCArtboard id="S" label="Seasons & episode stills" width={1240} height={1164} style={{ background: 'var(--bg)' }}><SeriesBoard /></DCArtboard>
      </DCSection>
    </DesignCanvas>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
