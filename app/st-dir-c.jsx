// st-dir-c.jsx — Direction C: master-detail filmstrip.
// Left column is a thumbnail filmstrip of the Series + every episode (still +
// status corner). Right is the work panel. Non-linear: jump to anything; an
// "issues" filter floats the problems up. Feels like an editor, not a wizard.
// The rail is collapsible: a persistent toggle reclaims space on desktop and
// turns it into an overlay drawer on narrow / mobile viewports.

const { STEPS: STEPS_C, StepContent: StepContent_C, FIRST_ISSUE: FI_C } = window;
const C_BP = 820; // rail collapses below this CONTAINER width (not viewport)

function DirectionC() {
  const { useState, useEffect, useRef } = React;
  const rootRef = useRef(null);
  const [active, setActive] = useState(FI_C);
  const [onlyIssues, setOnlyIssues] = useState(false);
  const [narrow, setNarrow] = useState(false);
  const [railOpen, setRailOpen] = useState(true);
  const step = STEPS_C[active];
  const attn = STEPS_C.filter(s => s.kind === 'episode' && s.state === 'attn').length;
  const visible = STEPS_C.filter((s, i) => i === 0 || !onlyIssues || s.state === 'attn');

  // React to the panel's OWN width — it sits next to the global sidebar, so the
  // viewport width is not what matters. ResizeObserver tracks the real space.
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    let prev = null;
    const apply = w => {
      const n = w < C_BP;
      if (n === prev) return;
      prev = n; setNarrow(n); setRailOpen(!n);
    };
    apply(el.clientWidth);
    const ro = new ResizeObserver(es => { for (const e of es) apply(e.contentRect.width); });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Selecting an episode closes the drawer on narrow screens.
  const select = i => { setActive(i); if (narrow) setRailOpen(false); };

  return (
    <div ref={rootRef} style={cRoot}>
      <div style={cTop} className="c-top">
        <span className="rail-toggle" onClick={() => setRailOpen(o => !o)}
          title={railOpen ? 'Hide episodes' : 'Show episodes'} aria-label="Toggle episode list">
          {railOpen && !narrow ? '⟨' : '☰'}
        </span>
        <div style={{ minWidth: 0 }}>
          <div className="crumb c-crumb" style={{ fontSize: narrow ? '.68rem' : '' }}>Library / TV / <b style={{ color: 'var(--ink)' }}>Nordvest</b> · Season 1</div>
          <h1 className="c-h1" style={{ fontSize: narrow ? '1.15rem' : '1.5rem', margin: '2px 0 0' }}>Series triage</h1>
        </div>
        <span className="spacer"></span>
        <span className="chip"><span className="dot bad"></span> {attn}{!narrow && <span>&nbsp;episodes need attention</span>}</span>
        {!narrow && <span className="btn">Save → disk</span>}
        <span className="btn primary">Save{!narrow && <span>&nbsp;&amp; tell Jellyfin</span>} ↻</span>
      </div>

      <div style={{ display: 'flex', flex: 1, minHeight: 0, position: 'relative' }}>
        {/* backdrop behind the drawer on narrow screens */}
        {narrow && railOpen && <div onClick={() => setRailOpen(false)} style={cBackdrop}></div>}

        {/* filmstrip — inline column on desktop, overlay drawer on narrow */}
        {railOpen && (
          <aside style={narrow ? cRailOverlay : cRail}>
            <div className="row center" style={{ margin: '0 0 10px' }}>
              <h4 style={{ margin: 0 }}>Episodes</h4>
              <span className="spacer"></span>
              <span className="seg" style={{ fontSize: '.7rem' }}>
                <span className={!onlyIssues ? 'on' : ''} onClick={() => setOnlyIssues(false)}>All</span>
                <span className={onlyIssues ? 'on' : ''} onClick={() => setOnlyIssues(true)}>Issues</span>
              </span>
              {narrow && <span className="rail-close" onClick={() => setRailOpen(false)} aria-label="Close">✕</span>}
            </div>

            <div className="col" style={{ gap: 8 }}>
              {visible.map(s => {
                const i = STEPS_C.indexOf(s);
                const on = i === active;
                if (s.kind === 'series') {
                  return (
                    <div key={s.id} onClick={() => select(i)} style={cSeriesCard(on)}>
                      <div className="imgslot" style={{ width: 46, height: 64, flex: 'none' }}><span style={{ fontSize: '.5rem' }}>poster</span></div>
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontWeight: 700, fontSize: '.92rem' }}>Series level</div>
                        <div className="tiny muted">Nordvest · {window.SERIES.episodes} eps</div>
                        <div className="pill-row" style={{ marginTop: 4 }}><span className="badge info" style={{ fontSize: '.56rem' }}>lang · fo</span></div>
                      </div>
                    </div>
                  );
                }
                return (
                  <div key={s.id} onClick={() => select(i)} style={cEpCard(on)}>
                    <div style={{ position: 'relative', flex: 'none' }}>
                      <div className="imgslot" style={{ width: 92, height: 52 }}>
                        <span style={{ fontSize: '.52rem' }}>{s.ep.hasArt ? 'still' : 'no still'}</span>
                      </div>
                      <span style={cCorner(s.state)}></span>
                    </div>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div className="row center" style={{ gap: 6 }}>
                        <span className="num" style={{ fontSize: '.74rem' }}>{s.ep.id}</span>
                        <span className="lang" style={{ fontSize: '.64rem' }}>{s.ep.resolved}</span>
                      </div>
                      <div style={{ fontWeight: on ? 700 : 600, fontSize: '.86rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.ep.title}</div>
                      {s.state === 'attn'
                        ? <div className="pill-row" style={{ marginTop: 3 }}>{s.issues.map(x => <span key={x} className="badge bad" style={{ fontSize: '.54rem' }}>{x}</span>)}</div>
                        : <div className="tiny" style={{ color: 'var(--ok)', fontSize: '.66rem', marginTop: 2 }}>✓ complete</div>}
                    </div>
                  </div>
                );
              })}
            </div>
          </aside>
        )}

        {/* work panel */}
        <main style={cMain} className="c-main">
          <StepContent_C step={step} />
          <div className="row center" style={{ marginTop: 18, gap: 8 }}>
            <span className="btn ghost">Skip</span>
            <span className="spacer"></span>
            <span className="btn">Mark resolved</span>
            <span className="btn primary">Next issue ↓</span>
          </div>
        </main>
      </div>
    </div>
  );
}

const cRoot = { width: '100%', height: '100%', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', fontFamily: "'Sora',sans-serif", overflow: 'hidden' };
const cTop = { display: 'flex', alignItems: 'center', gap: 12, padding: '14px 20px', borderBottom: '1px solid var(--line)', background: 'var(--fill-2)' };
const cRail = { width: 336, flex: 'none', borderRight: '1px solid var(--line)', padding: '18px 16px', overflow: 'auto', background: 'linear-gradient(180deg,var(--fill-2),var(--bg))' };
const cRailOverlay = { position: 'absolute', top: 0, left: 0, bottom: 0, width: 'min(340px, 86vw)', zIndex: 30, padding: '18px 16px', overflow: 'auto', borderRight: '1px solid var(--line)', background: 'linear-gradient(180deg,var(--fill-2),var(--bg-2))', boxShadow: '12px 0 40px rgba(0,0,0,.5)' };
const cBackdrop = { position: 'absolute', inset: 0, zIndex: 20, background: 'rgba(8,10,16,.6)', backdropFilter: 'blur(2px)' };
const cMain = { flex: 1, minWidth: 0, padding: '22px 26px', overflow: 'auto' };
const cSeriesCard = on => ({ display: 'flex', gap: 11, padding: 10, borderRadius: 12, cursor: 'pointer', border: '1px solid', borderColor: on ? 'var(--hi)' : 'var(--line)', background: on ? 'var(--hi-soft)' : 'var(--fill-2)', alignItems: 'center' });
const cEpCard = on => ({ display: 'flex', gap: 11, padding: 8, borderRadius: 12, cursor: 'pointer', border: '1px solid', borderColor: on ? 'var(--hi)' : 'transparent', background: on ? 'var(--hi-soft)' : 'var(--fill-2)', alignItems: 'center', boxShadow: on ? '0 0 0 3px var(--hi-soft)' : 'none' });
function cCorner(state) {
  const base = { position: 'absolute', top: -4, right: -4, width: 13, height: 13, borderRadius: '50%', border: '2px solid var(--bg)' };
  return { ...base, background: state === 'attn' ? 'var(--bad)' : 'var(--ok)', boxShadow: state === 'attn' ? '0 0 8px rgba(255,111,97,.7)' : '0 0 8px rgba(45,212,154,.6)' };
}

window.DirectionC = DirectionC;
