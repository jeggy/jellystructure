/* Ravilo create-flow directions — filters (channels/rows) + hero items.
   Static high-fidelity compositions rendered inside design-canvas.jsx.
   Inline-expanding-panel surface, set in the real ravilo-config list context.
   Uses wf.css / app.css tokens + .cf-* helpers from the host page. */

/* ---------- shared atoms ---------- */
function Frame({ children, pad = 24 }) {
  return (
    <div style={{ height: '100%', width: '100%', background: 'var(--bg)', position: 'relative', overflow: 'hidden',
      fontFamily: "'Sora', system-ui, sans-serif", color: 'var(--ink)' }}>
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background:
        'radial-gradient(820px 560px at 8% -12%, rgba(123,110,240,.16), transparent 60%),' +
        'radial-gradient(820px 620px at 100% 0%, rgba(0,164,220,.12), transparent 55%)' }} />
      <div style={{ position: 'relative', padding: pad, height: '100%', boxSizing: 'border-box', overflow: 'hidden' }}>{children}</div>
    </div>
  );
}

const RAV = (
  <svg viewBox="12 20 76 76" style={{ width: 22, height: 22, verticalAlign: 'middle', filter: 'drop-shadow(0 0 8px rgba(123,110,240,.5))' }} aria-hidden="true">
    <defs><linearGradient id="cfj" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#7b6ef0" /><stop offset="1" stopColor="#3fb6f5" /></linearGradient></defs>
    <path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#cfj)" />
    <g stroke="url(#cfj)" strokeWidth="4.5" strokeLinecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9" /><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72" /><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72" /><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9" /></g>
  </svg>
);

/* section card shell — mimics a ravilo-config section with its header + add button active */
function Section({ title, badge, addLabel, sub, children }) {
  return (
    <div className="card" style={{ padding: 18 }}>
      <div className="row center">
        <h3 style={{ fontSize: '1.05rem', margin: 0 }}>{title}</h3>
        {badge && <span className="badge info" style={{ marginLeft: 8 }}>{badge}</span>}
        <span className="spacer"></span>
        <span className="btn sm primary">{addLabel}</span>
      </div>
      {sub && <p className="tiny muted" style={{ margin: '6px 0 14px' }}>{sub}</p>}
      {children}
    </div>
  );
}

const G = (a, b) => `linear-gradient(150deg, hsl(${a}), hsl(${b}))`;
function MiniPoster({ title, g, w = 58, h = 86, badge, ratio }) {
  const hh = ratio === 'land' ? Math.round(w * 0.56) : h;
  return (
    <div className="mp" style={{ width: w, height: hh, background: g }}>
      {badge && <span className="bdg">{badge}</span>}
      <span className="t">{title}</span>
    </div>
  );
}

/* live match count + preview strip */
function MatchStrip({ n, items, ratio, note }) {
  return (
    <div>
      <div className="row center" style={{ gap: 10 }}>
        <span className="matchcount"><span className="n">{n}</span><span className="l">titles match</span></span>
        <span className="spacer"></span>
        {note && <span className="tiny muted">{note}</span>}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 11, overflow: 'hidden' }}>
        {items.map((it, i) => <MiniPoster key={i} {...it} ratio={ratio} />)}
        <div style={{ display: 'flex', alignItems: 'center', paddingLeft: 4, color: 'var(--ink-soft)', fontSize: '.78rem' }}>+{n - items.length} more</div>
      </div>
    </div>
  );
}

const POOL = [
  { title: 'Cosmos Laundromat', g: G('28 48% 38%', '60 52% 15%'), badge: '4K' },
  { title: 'Agent 327', g: G('210 48% 36%', '250 52% 15%') },
  { title: 'Hraðar Ljós', g: G('345 48% 36%', '15 52% 14%') },
  { title: 'Spring', g: G('150 44% 34%', '180 50% 14%') },
  { title: 'Caminandes 3', g: G('120 44% 36%', '160 50% 14%') },
  { title: 'Wing It!', g: G('300 48% 38%', '330 52% 15%') },
];

/* ===================================================================
   BOARD A — Guided inline filter builder (simple by default + Advanced)
   =================================================================== */
function BoardA() {
  return (
    <Frame>
      <Section title="Content rows" badge="5–20 rows" addLabel="＋ Add row"
        sub="The vertical stack on Home. This same set is reused inside every channel view, scoped to that channel.">
        {/* existing row for context */}
        <div className="cfg-row" style={{ opacity: .5 }}>
          <span className="grab">⠿</span><span className="badge ok" style={{ flex: 'none' }}>system</span>
          <div style={{ flex: 1 }}><div className="nm">Newly Added</div><div className="src">movies + series · sort newest</div></div>
          <span className="muted tiny">show</span><span className="toggle on"></span>
        </div>

        {/* INLINE EXPANDING PANEL */}
        <div className="cf-panel" style={{ marginTop: 9 }}>
          <div className="cf-panel-head">
            <span className="cf-grab">⠿</span>
            <b style={{ fontSize: '.95rem' }}>New row</b>
            <span className="seg" style={{ marginLeft: 6 }}><span className="on">Content row</span><span>Channel</span></span>
            <span className="spacer"></span>
            <span className="tiny muted">esc to cancel</span>
            <span style={{ cursor: 'pointer', color: 'var(--ink-soft)', fontSize: '1rem' }}>✕</span>
          </div>

          <div style={{ display: 'flex', gap: 0 }}>
            {/* builder */}
            <div style={{ flex: 1, padding: 18, minWidth: 0 }}>
              <span className="eyebrow">Filter</span>
              <div className="row center" style={{ gap: 9, marginTop: 9, flexWrap: 'wrap' }}>
                <span className="facet-sel"><span className="k">Match</span> Genre <span style={{ color: 'var(--ink-soft)' }}>▾</span></span>
                <span className="k tiny" style={{ color: 'var(--ink-soft)' }}>is any of</span>
                <span className="vchip">Action <span className="x">✕</span></span>
                <span className="vchip">Adventure <span className="x">✕</span></span>
                <span className="vchip add">＋ value</span>
              </div>

              <div className="row center" style={{ gap: 10, marginTop: 16, flexWrap: 'wrap' }}>
                <div><span className="eyebrow">Include</span>
                  <div className="seg" style={{ marginTop: 7 }}><span className="on">All</span><span>Movies</span><span>Series</span></div></div>
                <div style={{ flex: 1, minWidth: 150 }}><span className="eyebrow">Row title</span>
                  <div className="input" style={{ marginTop: 7 }}>Action &amp; Adventure</div></div>
              </div>

              <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="opt-chip" style={{ borderStyle: 'dashed' }}>⚙ Advanced ▾</span>
                <span className="tiny muted">add conditions, AND/OR, exclusions, year &amp; rating</span>
              </div>

              <div className="note blue" style={{ marginTop: 16, display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px' }}>
                <span className="badge info" style={{ flex: 'none' }}>reusable</span>
                <span className="tiny" style={{ flex: 1 }}>This exact filter also works as a <b>Library</b> filter — open it there anytime from <span className="mono">Library → Filter</span>.</span>
              </div>
            </div>

            {/* live preview rail */}
            <div style={{ width: 320, flex: 'none', borderLeft: '1px solid var(--line)', padding: 18, background: 'var(--fill-2)' }}>
              <span className="eyebrow">Live preview</span>
              <div style={{ marginTop: 12 }}>
                <MatchStrip n={47} items={POOL.slice(0, 4)} note="updates as you edit" />
              </div>
              <div className="tiny muted" style={{ marginTop: 14, lineHeight: 1.5 }}>How the row appears on the TV — newest first, the row title on the left.</div>
              <div style={{ marginTop: 12, padding: 11, borderRadius: 10, background: '#000', border: '1px solid var(--line)' }}>
                <div className="tiny" style={{ color: '#cfd3e2', fontWeight: 700, marginBottom: 7, fontFamily: "'Space Grotesk',sans-serif" }}>Action &amp; Adventure</div>
                <div style={{ display: 'flex', gap: 6 }}>{POOL.slice(0, 5).map((p, i) => <MiniPoster key={i} {...p} w={44} h={66} />)}</div>
              </div>
            </div>
          </div>

          <div className="row center" style={{ padding: '13px 16px', borderTop: '1px solid var(--line)', background: 'var(--fill-2)' }}>
            <span className="tiny muted">Added to the bottom of the stack · drag to reorder after.</span>
            <span className="spacer"></span>
            <span className="btn sm ghost">Cancel</span>
            <span className="btn sm primary">Add row</span>
          </div>
        </div>
      </Section>
    </Frame>
  );
}

/* ===================================================================
   BOARD B — Workbench builder (Advanced open) + Channel extras
   =================================================================== */
function CondRow({ join, children }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span className="cond-join" style={{ width: 36, textAlign: 'center', visibility: join ? 'visible' : 'hidden' }}>{join || 'AND'}</span>
      <div className="cond-row" style={{ flex: 1 }}>{children}</div>
    </div>
  );
}
function BoardB() {
  return (
    <Frame>
      <Section title="Channels" addLabel="＋ Add channel"
        sub="The Disney+-style logo row under the hero. Each opens a view with the same content rows, filtered to that channel.">
        <div className="cfg-row" style={{ opacity: .5 }}>
          <span className="grab">⠿</span>
          <div className="studio-wm" style={{ background: 'linear-gradient(135deg,#3b2a78,#15102e)' }}>HBO</div>
          <div style={{ flex: 1 }}><div className="nm">HBO</div><div className="src">network = “HBO”</div></div>
          <span className="muted tiny">show</span><span className="toggle on"></span>
        </div>

        <div className="cf-panel" style={{ marginTop: 9 }}>
          <div className="cf-panel-head">
            <span className="cf-grab">⠿</span><b style={{ fontSize: '.95rem' }}>New channel</b>
            <span className="seg" style={{ marginLeft: 6 }}><span>Content row</span><span className="on">Channel</span></span>
            <span className="badge" style={{ marginLeft: 6 }}>⚙ Advanced</span>
            <span className="spacer"></span>
            <span style={{ cursor: 'pointer', color: 'var(--ink-soft)', fontSize: '1rem' }}>✕</span>
          </div>

          <div style={{ display: 'flex' }}>
            {/* LEFT — condition stack + channel styling */}
            <div style={{ flex: 1, padding: 18, minWidth: 0 }}>
              <div className="row center" style={{ gap: 8 }}>
                <span className="eyebrow">Match</span>
                <span className="seg"><span className="on">ALL</span><span>ANY</span></span>
                <span className="tiny muted">of these conditions</span>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 11 }}>
                <CondRow>
                  <span className="facet-sel">Network <span style={{ color: 'var(--ink-soft)' }}>▾</span></span>
                  <span className="k tiny" style={{ color: 'var(--ink-soft)' }}>is</span>
                  <span className="vchip">HBO <span className="x">✕</span></span>
                  <span className="vchip">Max Originals <span className="x">✕</span></span>
                  <span className="vchip add">＋</span>
                </CondRow>
                <CondRow join="AND">
                  <span className="facet-sel">Genre <span style={{ color: 'var(--ink-soft)' }}>▾</span></span>
                  <span className="k tiny" style={{ color: 'var(--ink-soft)' }}>is not</span>
                  <span className="vchip">Kids <span className="x">✕</span></span>
                  <span className="vchip add">＋</span>
                </CondRow>
                <CondRow join="AND">
                  <span className="facet-sel">Year <span style={{ color: 'var(--ink-soft)' }}>▾</span></span>
                  <span className="k tiny" style={{ color: 'var(--ink-soft)' }}>is on or after</span>
                  <span className="vchip">2015 <span className="x">✕</span></span>
                </CondRow>
                <div style={{ paddingLeft: 44 }}><span className="vchip add" style={{ padding: '7px 12px' }}>＋ Add condition</span></div>
              </div>

              <hr className="dash" style={{ margin: '16px 0' }} />

              <span className="eyebrow">Channel button</span>
              <div className="row center" style={{ gap: 14, marginTop: 11, flexWrap: 'wrap' }}>
                <div><div className="tiny muted" style={{ marginBottom: 6 }}>Style</div>
                  <span className="seg"><span className="on">Logo</span><span>Text</span></span></div>
                <div><div className="tiny muted" style={{ marginBottom: 6 }}>Brand color</div>
                  <div style={{ display: 'flex', gap: 7 }}>
                    <span className="sw on" style={{ background: 'linear-gradient(135deg,#3b2a78,#15102e)' }}></span>
                    <span className="sw" style={{ background: 'linear-gradient(135deg,#e3122b,#7d0a1a)' }}></span>
                    <span className="sw" style={{ background: 'linear-gradient(135deg,#0a93a6,#063d47)' }}></span>
                    <span className="sw" style={{ background: 'linear-gradient(135deg,#1455d8,#0a2766)' }}></span>
                  </div></div>
                <div style={{ flex: 1, minWidth: 130 }}><div className="tiny muted" style={{ marginBottom: 6 }}>Name</div>
                  <div className="input">HBO Max</div></div>
              </div>
            </div>

            {/* RIGHT — live results */}
            <div style={{ width: 300, flex: 'none', borderLeft: '1px solid var(--line)', padding: 18, background: 'var(--fill-2)' }}>
              <span className="eyebrow">Matches now</span>
              <div className="row center" style={{ gap: 10, marginTop: 10 }}>
                <span className="matchcount"><span className="n">32</span><span className="l">titles</span></span>
                <span className="spacer"></span>
                <span className="badge ok">live</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 7, marginTop: 12 }}>
                {[...POOL, ...POOL.slice(0, 2)].map((p, i) => <MiniPoster key={i} {...p} w={56} h={84} badge={undefined} />)}
              </div>
              <div className="studio-wm" style={{ background: 'linear-gradient(135deg,#3b2a78,#15102e)', width: '100%', height: 46, marginTop: 14, fontSize: '1.1rem' }}>HBO Max</div>
              <div className="tiny muted" style={{ marginTop: 8, textAlign: 'center' }}>channel button preview</div>
              <a href="#" className="tiny" style={{ display: 'block', marginTop: 12, color: 'var(--acc-ink)' }}>Open these 32 in Library ↗</a>
            </div>
          </div>

          <div className="row center" style={{ padding: '13px 16px', borderTop: '1px solid var(--line)', background: 'var(--fill-2)' }}>
            <span className="tiny muted">The same conditions power the channel's scoped rows on the TV.</span>
            <span className="spacer"></span>
            <span className="btn sm ghost">Cancel</span>
            <span className="btn sm primary">Add channel</span>
          </div>
        </div>
      </Section>
    </Frame>
  );
}

/* ===================================================================
   BOARD C — Hero item builder with live 16:9 preview
   =================================================================== */
const BADGES = ['New Season', '4K', 'Top 10', 'Premiere'];
function BoardC() {
  return (
    <Frame>
      <Section title="Hero carousel" badge="1–10 items" addLabel="＋ Add featured"
        sub="The top banner. These are specific titles from your Jellyfin library — pick one, then dress it for the carousel.">
        <div className="cf-panel" style={{ marginTop: 2 }}>
          <div className="cf-panel-head">
            <span className="cf-grab">⠿</span><b style={{ fontSize: '.95rem' }}>New featured item</b>
            <span className="spacer"></span>
            <span className="tiny muted">esc to cancel</span>
            <span style={{ cursor: 'pointer', color: 'var(--ink-soft)', fontSize: '1rem' }}>✕</span>
          </div>

          <div style={{ display: 'flex' }}>
            {/* LEFT — search + presentation */}
            <div style={{ width: 420, flex: 'none', padding: 18, borderRight: '1px solid var(--line)' }}>
              <span className="eyebrow">1 · Pick a title</span>
              <div className="input ph" style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span>⌕</span> cosmos
              </div>
              {/* search results dropdown */}
              <div className="card" style={{ padding: 6, marginTop: 6 }}>
                <div className="row center" style={{ gap: 10, padding: 7, borderRadius: 8, background: 'var(--hi-soft)', border: '1px solid rgba(123,110,240,.35)' }}>
                  <MiniPoster title="" g={POOL[0].g} w={30} h={45} />
                  <div style={{ flex: 1 }}><div style={{ fontWeight: 600, fontSize: '.9rem' }}>Cosmos Laundromat</div><div className="tiny muted">Film · 2015 · Sci-Fi · Adventure</div></div>
                  <span className="badge ok">selected</span>
                </div>
                <div className="row center" style={{ gap: 10, padding: 7 }}>
                  <MiniPoster title="" g={G('200 30% 30%','230 40% 14%')} w={30} h={45} />
                  <div style={{ flex: 1 }}><div style={{ fontWeight: 600, fontSize: '.9rem' }}>Cosmos: Possible Worlds</div><div className="tiny muted">Series · 2020 · Documentary</div></div>
                </div>
                <div className="tiny muted" style={{ padding: '4px 7px' }}>Multi-language search — matches every title an item has ever had.</div>
              </div>

              <hr className="dash" style={{ margin: '16px 0' }} />
              <span className="eyebrow">2 · Dress it for the carousel</span>

              <div className="field" style={{ marginTop: 12, marginBottom: 12 }}>
                <label>Badge</label>
                <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
                  {BADGES.map((b, i) => <span key={i} className={'opt-chip' + (b === '4K' ? ' on' : '')}>{b}</span>)}
                  <span className="opt-chip" style={{ borderStyle: 'dashed' }}>＋ Custom</span>
                  <span className="opt-chip">None</span>
                </div>
              </div>

              <div className="field" style={{ marginBottom: 12 }}>
                <label>Tagline / kicker</label>
                <div className="input">Featured Film</div>
                <span className="hint">Small line above the title — e.g. “Kringvarp Original”, “Ravilo Premiere”.</span>
              </div>

              <div className="row center" style={{ gap: 14, flexWrap: 'wrap' }}>
                <div><label className="eyebrow" style={{ display: 'block', marginBottom: 7 }}>Backdrop</label>
                  <div style={{ display: 'flex', gap: 7 }}>
                    <MiniPoster title="" g={G('28 48% 38%','60 52% 15%')} w={66} ratio="land" />
                    <div style={{ position: 'relative' }}><MiniPoster title="" g={G('20 40% 30%','45 48% 12%')} w={66} ratio="land" /><span style={{ position: 'absolute', inset: 0, border: '2px solid var(--hi)', borderRadius: 7 }}></span></div>
                    <MiniPoster title="" g={G('250 30% 30%','280 40% 12%')} w={66} ratio="land" />
                  </div></div>
                <div><label className="eyebrow" style={{ display: 'block', marginBottom: 7 }}>Clearlogo overlay</label>
                  <div className="row center" style={{ gap: 8 }}><span className="toggle on"></span><span className="tiny muted">use logo (not text title)</span></div></div>
              </div>
            </div>

            {/* RIGHT — live hero preview */}
            <div style={{ flex: 1, padding: 18, minWidth: 0, background: 'var(--fill-2)' }}>
              <div className="row center"><span className="eyebrow">Live preview</span><span className="spacer"></span><span className="badge ok" style={{ fontSize: '.6rem' }}>as seen on TV</span></div>
              <div className="hero-prev" style={{ marginTop: 12 }}>
                <div className="grad" style={{ background: G('28 50% 30%', '60 55% 10%') }}></div>
                <div className="scrim"></div>
                <div className="body">
                  <div style={{ fontSize: '.72rem', letterSpacing: '.12em', textTransform: 'uppercase', color: '#cdd2e4', fontWeight: 700 }}>Featured Film</div>
                  <div style={{ fontFamily: "'Space Grotesk',sans-serif", fontWeight: 800, fontSize: '2.1rem', lineHeight: 1.02, margin: '6px 0', color: '#fff', textShadow: '0 2px 16px rgba(0,0,0,.6)' }}>Cosmos<br />Laundromat</div>
                  <div className="row center" style={{ gap: 8, marginBottom: 10 }}>
                    <span className="badge" style={{ background: 'rgba(255,255,255,.16)', color: '#fff', border: 'none' }}>4K</span>
                    <span className="tiny" style={{ color: '#cdd2e4' }}>2015 · Sci-Fi · Adventure · 12</span>
                  </div>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <span style={{ background: '#fff', color: '#111', fontWeight: 700, fontSize: '.78rem', padding: '7px 16px', borderRadius: 7 }}>▶ Play</span>
                    <span style={{ background: 'rgba(255,255,255,.18)', color: '#fff', fontWeight: 600, fontSize: '.78rem', padding: '7px 14px', borderRadius: 7 }}>More info</span>
                  </div>
                </div>
                {/* carousel dots */}
                <div style={{ position: 'absolute', right: '6%', bottom: '8%', display: 'flex', gap: 6 }}>
                  <span style={{ width: 22, height: 4, borderRadius: 3, background: '#fff' }}></span>
                  <span style={{ width: 8, height: 4, borderRadius: 3, background: 'rgba(255,255,255,.4)' }}></span>
                  <span style={{ width: 8, height: 4, borderRadius: 3, background: 'rgba(255,255,255,.4)' }}></span>
                </div>
              </div>
              <div className="tiny muted" style={{ marginTop: 10, lineHeight: 1.5 }}>Backdrop, logo, tagline and badge update live. The hero height &amp; auto-advance are set once for the whole carousel below.</div>
            </div>
          </div>

          <div className="row center" style={{ padding: '13px 16px', borderTop: '1px solid var(--line)', background: 'var(--fill-2)' }}>
            <span className="tiny muted">Position 6 of up to 10 · drag to reorder after adding.</span>
            <span className="spacer"></span>
            <span className="btn sm ghost">Cancel</span>
            <span className="btn sm primary">Add to carousel</span>
          </div>
        </div>
      </Section>
    </Frame>
  );
}

/* ===================================================================
   BOARD D — Library parity: save-as-hero + save-filter round trip
   =================================================================== */
function LibPoster({ title, year, g, badge, hover }) {
  return (
    <div style={{ position: 'relative', width: 124 }}>
      <div className="mp" style={{ width: 124, height: 186, background: g, boxShadow: hover ? '0 0 0 2px var(--hi)' : '0 4px 12px rgba(0,0,0,.4)' }}>
        {hover && (
          <div className="save-as-hero">
            <span className="btn sm primary">★ Save as hero item</span>
            <span className="btn sm ghost" style={{ color: '#fff', borderColor: 'rgba(255,255,255,.3)' }}>Open detail</span>
          </div>
        )}
      </div>
      <div style={{ fontWeight: 600, fontSize: '.86rem', marginTop: 7 }}>{title}</div>
      <div className="tiny muted">{year}</div>
    </div>
  );
}
function BoardD() {
  const libs = [
    { title: 'Cosmos Laundromat', year: '2015', g: G('28 48% 38%', '60 52% 15%'), hover: true },
    { title: 'Agent 327', year: '2017', g: G('210 48% 36%', '250 52% 15%') },
    { title: 'Hraðar Ljós', year: '2024', g: G('345 48% 36%', '15 52% 14%') },
    { title: 'Spring', year: '2019', g: G('150 44% 34%', '180 50% 14%') },
    { title: 'Wing It!', year: '2023', g: G('300 48% 38%', '330 52% 15%') },
  ];
  return (
    <Frame>
      <div className="pagebar" style={{ marginBottom: 6 }}>
        <h1 style={{ fontSize: '1.5rem' }}>Library</h1>
        <span className="spacer"></span>
        <span className="input ph" style={{ width: 200, display: 'inline-flex', alignItems: 'center', gap: 6 }}>⌕ search title…</span>
        <span className="seg"><span className="on">Movies</span><span>TV</span></span>
      </div>
      <p className="page-sub" style={{ marginTop: 0 }}>Everything Jellystructure manages — and the bridge to Ravilo. Build a filter here, then save it straight into a viewer's TV layout.</p>

      {/* filter bar — same builder vocabulary as the Ravilo flow */}
      <div className="row center" style={{ gap: 9, flexWrap: 'wrap', marginBottom: 12 }}>
        <span className="muted tiny">filter:</span>
        <span className="facet-sel"><span className="k">＋</span> Add filter</span>
        <span className="chip libfx">Genre is Action, Adventure <span style={{ cursor: 'pointer', opacity: .6 }}>✕</span></span>
        <span className="chip libfx">Year ≥ 2015 <span style={{ cursor: 'pointer', opacity: .6 }}>✕</span></span>
        <span className="matchcount" style={{ marginLeft: 4 }}><span className="n" style={{ fontSize: '1.1rem' }}>47</span><span className="l">match</span></span>
        <span className="spacer" style={{ flex: 1 }}></span>
        {/* the round-trip action */}
        <span className="btn sm" style={{ display: 'inline-flex', gap: 7 }}>{RAV} Save filter as… ▾</span>
      </div>

      {/* the save-as dropdown, open */}
      <div className="row" style={{ gap: 16, alignItems: 'flex-start' }}>
        <div className="poster-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 124px)', gap: 18, flex: 1 }}>
          {libs.map((p, i) => <LibPoster key={i} {...p} />)}
        </div>

        <div className="card" style={{ width: 280, flex: 'none', padding: 14 }}>
          <div className="row center" style={{ marginBottom: 4 }}>{RAV}<b style={{ marginLeft: 6 }}>Save to Ravilo</b></div>
          <p className="tiny muted" style={{ margin: '6px 0 12px' }}>Push this filter — or any title — into a viewer's TV layout without leaving the Library.</p>
          <div className="navitem" style={{ justifyContent: 'flex-start', gap: 10 }}><span>📺</span><div><div style={{ fontWeight: 600, fontSize: '.88rem' }}>As a Channel</div><div className="tiny muted">logo button + scoped rows</div></div></div>
          <div className="navitem" style={{ justifyContent: 'flex-start', gap: 10 }}><span>☰</span><div><div style={{ fontWeight: 600, fontSize: '.88rem' }}>As a Content row</div><div className="tiny muted">one row on Home</div></div></div>
          <div className="navitem" style={{ justifyContent: 'flex-start', gap: 10 }}><span>★</span><div><div style={{ fontWeight: 600, fontSize: '.88rem' }}>Hover a poster → Hero</div><div className="tiny muted">feature a single title</div></div></div>
          <hr className="dash" style={{ margin: '12px 0' }} />
          <div className="field" style={{ margin: 0 }}><label>For viewer</label><span className="select"><span>Eyð Restorff</span></span></div>
          <span className="btn sm primary fill" style={{ justifyContent: 'center', marginTop: 12 }}>Open in Ravilo config →</span>
          <div className="tiny muted" style={{ marginTop: 9, textAlign: 'center' }}>Same per-user store · syncs to all devices</div>
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
      <DCSection id="filters" title="Create a filter · Channels & Content rows"
        subtitle="Inline-expanding panel in the Ravilo config list. One unified builder — simple by default, Advanced reveals the condition stack. Channels add logo/text + brand color.">
        <DCArtboard id="A" label="A · Guided builder (simple + Advanced)" width={980} height={780} style={{ background: 'var(--bg)' }}>
          <BoardA />
          <DCPostIt top={-4} right={-150} width={176} rotate={2}>Friendly default: one facet, multiple values, live strip. “Advanced” unfolds the power.</DCPostIt>
        </DCArtboard>
        <DCArtboard id="B" label="B · Workbench (Advanced open + channel extras)" width={1000} height={872} style={{ background: 'var(--bg)' }}>
          <BoardB />
          <DCPostIt top={-4} right={-150} width={176} rotate={-2}>Power end: AND/OR condition stack, exclusions, live result grid + channel button preview.</DCPostIt>
        </DCArtboard>
      </DCSection>

      <DCSection id="hero" title="Create a hero carousel item"
        subtitle="Pick a specific library title (multi-language search), then dress it — badge, tagline, backdrop, clearlogo — with a live 16:9 preview of the TV banner.">
        <DCArtboard id="C" label="Hero item builder" width={1040} height={988} style={{ background: 'var(--bg)' }}><BoardC /></DCArtboard>
      </DCSection>

      <DCSection id="parity" title="Library parity · the round-trip"
        subtitle="The same filter builder lives on the Library page; a viewer-targeted “Save filter as Channel/Row” and a per-poster “Save as hero item” push straight into the per-user Ravilo layout.">
        <DCArtboard id="D" label="Library — Save as hero / Save filter" width={1040} height={712} style={{ background: 'var(--bg)' }}><BoardD /></DCArtboard>
      </DCSection>
    </DesignCanvas>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
