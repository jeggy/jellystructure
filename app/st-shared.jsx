// st-shared.jsx — Series Triage: shared data + content blocks used by all
// three layout directions. Exports to window at the end.
// Styling uses the app's wf.css / app.css component classes; the artboards
// render in the app's default (dark) token set.

const { useState } = React;

/* ───────────────────────── DATA ───────────────────────── */
// A fictional Nordic-noir series. Audio is Faroese (fo) original + Danish (da);
// one episode leans English/Danish. Two episodes have untagged tracks; a couple
// are missing artwork / overview. Every episode is a step; problems are flagged.

const SERIES = {
  title: 'Nordvest',
  year: '2023',
  season: 1,
  episodes: 8,
  plot: 'In a fog-bound Faroese fishing town, a detective returns home to a death that reopens a buried family secret.',
  genres: ['Crime', 'Drama', 'Thriller'],
  network: 'Kringvarp',
  art: { poster: true, backdrop: true, logo: false },
};

// Per-episode resolved metadata language (from each episode's own tracks).
const LANG_DIST = [
  { lang: 'fo', name: 'Føroyskt', count: 7 },
  { lang: 'da', name: 'Dansk', count: 1 },
];

const EPISODES = [
  {
    id: 'S01E01', n: 1, title: 'Hvalvík', dur: '58 min', resolved: 'fo',
    overview: 'Detective Eyð Restorff steps off the ferry into a town that would rather forget her.',
    hasArt: true, hasOverview: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: 'dub' },
      { idx: '0:s:0', kind: 'subtitle', lang: 'da', codec: 'srt', def: false, title: '' },
    ],
  },
  {
    id: 'S01E02', n: 2, title: 'Bóndin', dur: '54 min', resolved: 'fo',
    overview: '', hasArt: false, hasOverview: false,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: '' },
    ],
  },
  {
    id: 'S01E03', n: 3, title: 'Grindadráp', dur: '61 min', resolved: 'fo',
    overview: 'A whale hunt on the black beach surfaces a body that has been in the water far too long.',
    hasArt: true, hasOverview: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: '', codec: 'ac3 · stereo', def: false, title: 'commentary' },
      { idx: '0:s:0', kind: 'subtitle', lang: 'da', codec: 'srt', def: false, title: '' },
    ],
  },
  {
    id: 'S01E04', n: 4, title: 'Útróður', dur: '57 min', resolved: 'da',
    overview: 'A Copenhagen prosecutor arrives; the case — and the language in the room — shifts south.',
    hasArt: true, hasOverview: true, oddLang: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'da', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'fo', codec: 'aac · stereo', def: false, title: '' },
      { idx: '0:a:2', kind: 'audio', lang: 'en', codec: 'aac · stereo', def: false, title: '' },
    ],
  },
  {
    id: 'S01E05', n: 5, title: 'Foss', dur: '55 min', resolved: 'fo',
    overview: 'Eyð follows the money upriver to a salmon farm that everyone in town depends on.',
    hasArt: true, hasOverview: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: '' },
      { idx: '0:s:0', kind: 'subtitle', lang: 'fo', codec: 'srt', def: false, title: '' },
    ],
  },
  {
    id: 'S01E06', n: 6, title: 'Náttúra', dur: '59 min', resolved: 'fo',
    overview: 'A storm strands the island and the suspect list narrows to the people Eyð grew up with.',
    hasArt: true, hasOverview: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: '' },
      { idx: '0:s:0', kind: 'subtitle', lang: '', codec: 'srt', def: false, title: 'forced?' },
    ],
  },
  {
    id: 'S01E07', n: 7, title: 'Heim', dur: '56 min', resolved: 'fo',
    overview: 'The secret her father carried to sea finally washes back to the harbour wall.',
    hasArt: true, hasOverview: true,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: '' },
    ],
  },
  {
    id: 'S01E08', n: 8, title: 'Endi', dur: '63 min', resolved: 'fo',
    overview: '', hasArt: true, hasOverview: false,
    tracks: [
      { idx: '0:a:0', kind: 'audio', lang: 'fo', codec: 'eac3 · 5.1', def: true, title: '' },
      { idx: '0:a:1', kind: 'audio', lang: 'da', codec: 'aac · stereo', def: false, title: '' },
      { idx: '0:s:0', kind: 'subtitle', lang: 'da', codec: 'srt', def: false, title: '' },
    ],
  },
];

// Derive the issue set for an episode.
function epIssues(ep) {
  const out = [];
  if (ep.tracks.some(t => !t.lang)) out.push('untagged');
  if (!ep.hasArt) out.push('art');
  if (!ep.hasOverview) out.push('overview');
  return out;
}
function epState(ep) { return epIssues(ep).length ? 'attn' : 'done'; }

// Step model: series step (index 0) + one step per episode.
function buildSteps() {
  const steps = [{ kind: 'series', id: 'series', title: 'Series', sub: SERIES.title }];
  EPISODES.forEach(ep => steps.push({
    kind: 'episode', id: ep.id, ep, title: ep.id, sub: ep.title,
    state: epState(ep), issues: epIssues(ep),
  }));
  return steps;
}
const STEPS = buildSteps();
// Default landing + "next issue" order: problem episodes first.
const FIRST_ISSUE = STEPS.findIndex(s => s.kind === 'episode' && s.state === 'attn');

/* ─────────────────────── SMALL PARTS ─────────────────────── */

function Lang({ code }) {
  return code
    ? <span className="lang">{code}</span>
    : <span className="lang muted">??</span>;
}

function ArtSlot({ label, w, h, missing, style }) {
  return (
    <div className="imgslot" style={{ width: w, height: h, flex: 'none', ...style }}>
      {missing && <span style={{ background: 'var(--bad-soft)', borderColor: 'var(--bad)', color: 'var(--bad)' }}>{label} · missing</span>}
      {!missing && <span>{label}</span>}
    </div>
  );
}

function SectionTitle({ children, right }) {
  return (
    <div className="row center" style={{ margin: '0 0 10px' }}>
      <h4 style={{ margin: 0, whiteSpace: 'nowrap', flex: '0 0 auto' }}>{children}</h4>
      <span className="spacer"></span>
      {right && <span className="st-actions" style={{ flex: 'none', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'nowrap' }}>{right}</span>}
    </div>
  );
}

/* Language distribution bar across episodes (series step). */
function LangDist() {
  const total = LANG_DIST.reduce((a, d) => a + d.count, 0);
  const winner = LANG_DIST.slice().sort((a, b) => b.count - a.count)[0];
  return (
    <div>
      <div className="bar" style={{ height: 26, display: 'flex', padding: 0, border: '1px solid var(--line)' }}>
        {LANG_DIST.map((d, i) => (
          <div key={d.lang} title={d.name}
            style={{
              width: (d.count / total * 100) + '%',
              background: d.lang === winner.lang ? 'var(--grad)' : 'var(--fill-3)',
              color: d.lang === winner.lang ? '#fff' : 'var(--ink-soft)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontFamily: 'JetBrains Mono, monospace', fontSize: '.74rem', fontWeight: 600,
              borderRight: i < LANG_DIST.length - 1 ? '1px solid var(--bg)' : 'none',
            }}>
            {d.lang} · {d.count}
          </div>
        ))}
      </div>
      <div className="legend" style={{ marginTop: 10 }}>
        {LANG_DIST.map(d => (
          <span key={d.lang}>
            <span className="lang">{d.lang}</span>
            <span className="muted tiny">{d.name} · {d.count}/{total} eps</span>
          </span>
        ))}
      </div>
    </div>
  );
}

/* Unified track manager — one section that does BOTH triage (assign/fix the
   language on any track, including untagged ones) AND manual ordering + default
   flag, split by track kind via an Audio / Subtitles tab. Reordering and the
   default flag are scoped to the active tab; subtitles reorder in their own tab. */
const LANG_CHOICES = ['fo', 'da', 'en', 'is'];
const LANG_NAMES = { fo: 'Faroese', da: 'Danish', en: 'English', is: 'Icelandic' };

function TrackManager({ ep }) {
  const [kind, setKind] = useState('audio');
  const [assigned, setAssigned] = useState({});      // idx -> fixed language
  const [picking, setPicking] = useState(null);       // idx whose lang picker is open
  const [orderA, setOrderA] = useState(() => ep.tracks.filter(t => t.kind === 'audio'));
  const [orderS, setOrderS] = useState(() => ep.tracks.filter(t => t.kind === 'subtitle'));
  const [def, setDef] = useState(() => {
    const d = { audio: null, subtitle: null };
    ep.tracks.forEach(t => { if (t.def) d[t.kind] = t.idx; });
    return d;
  });

  const list = kind === 'audio' ? orderA : orderS;
  const setList = kind === 'audio' ? setOrderA : setOrderS;
  const untaggedCount = k => ep.tracks.filter(t => t.kind === k && !t.lang && !assigned[t.idx]).length;
  const count = k => ep.tracks.filter(t => t.kind === k).length;

  // The default AUDIO track's language vs the resolved metadata language.
  // These are set by different controls (★ vs ▲▼ order) and can legitimately
  // disagree — surface it so it's a deliberate choice, not a surprise.
  const defAudioTrack = orderA.find(t => t.idx === def.audio);
  const defAudioLang = defAudioTrack ? (defAudioTrack.lang || assigned[defAudioTrack.idx]) : null;
  const langMismatch = defAudioLang && ep.resolved && defAudioLang !== ep.resolved;
  const nm = c => LANG_NAMES[c] || c;

  const move = (i, dir) => setList(prev => {
    const j = i + dir;
    if (j < 0 || j >= prev.length) return prev;
    const next = prev.slice();
    [next[i], next[j]] = [next[j], next[i]];
    return next;
  });
  const assign = (idx, l) => { setAssigned(a => ({ ...a, [idx]: l })); setPicking(null); };

  const Tab = ({ k, label }) => {
    const bad = untaggedCount(k);
    return (
      <span className={kind === k ? 'on' : ''} onClick={() => { setKind(k); setPicking(null); }}>
        {label} <span className="mono" style={{ opacity: .7 }}>· {count(k)}</span>
        {bad > 0 && <span style={{ marginLeft: 5, color: 'var(--bad)', fontWeight: 700 }}>⚠ {bad}</span>}
      </span>
    );
  };

  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="row center" style={{ gap: 10, flexWrap: 'wrap' }}>
        <span className="seg">
          <Tab k="audio" label="Audio" />
          <Tab k="subtitle" label="Subtitles" />
        </span>
        <span className="spacer"></span>
        <span className="muted tiny"><b>▲▼</b> order · <b>★</b> default {kind === 'audio' ? 'audio' : 'subtitle'} — explained below</span>
      </div>

      {list.length === 0 && (
        <div className="box flat tiny muted" style={{ padding: '14px 12px', textAlign: 'center' }}>
          No {kind === 'audio' ? 'audio' : 'subtitle'} tracks in this episode.
        </div>
      )}

      <div className="col" style={{ gap: 8 }}>
        {list.map((t, i) => {
          const fixed = assigned[t.idx];
          const eff = t.lang || fixed;
          const untagged = !eff;
          const isDef = def[kind] === t.idx;
          const open = picking === t.idx;
          return (
            <div key={t.idx} className="box flat"
              style={{ padding: '9px 11px',
                background: untagged ? 'var(--bad-soft)' : 'var(--fill-2)',
                borderColor: untagged ? 'var(--bad)' : (isDef ? 'rgba(245,184,64,.45)' : 'var(--line)') }}>
              <div className="row center" style={{ gap: 11 }}>
                <span className="drag-handle" title="Drag to reorder">⋮⋮</span>
                <span className="col" style={{ gap: 2, flex: 'none' }}>
                  <span className="ord-btn" onClick={() => move(i, -1)} style={{ opacity: i === 0 ? .3 : 1 }}>▲</span>
                  <span className="ord-btn" onClick={() => move(i, 1)} style={{ opacity: i === list.length - 1 ? .3 : 1 }}>▼</span>
                </span>
                <span className="num" style={{ width: 30, textAlign: 'center' }}>{i + 1}</span>
                <span className="num" style={{ width: 50 }}>{t.idx}</span>

                {untagged
                  ? <span className="badge bad">no language</span>
                  : <span onClick={() => setPicking(open ? null : t.idx)}
                      style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 5 }} title="Change language">
                      <Lang code={eff} />
                      {fixed && !t.lang && <span className="badge ok" style={{ fontSize: '.56rem' }}>fixed</span>}
                      <span className="muted" style={{ fontSize: '.7rem' }}>▾</span>
                    </span>}

                <span className="muted tiny mono">{t.codec}{t.title ? ' · "' + t.title + '"' : ''}</span>
                <span className="spacer"></span>

                {isDef
                  ? <span className="badge warn">★ default</span>
                  : <span className="btn sm ghost" onClick={() => setDef(d => ({ ...d, [kind]: t.idx }))}>set default ★</span>}
              </div>

              {(untagged || open) && (
                <div className="pill-row" style={{ marginTop: 10, paddingLeft: 2 }}>
                  <span className="muted tiny" style={{ marginRight: 2 }}>{untagged ? 'assign' : 'change to'}:</span>
                  {LANG_CHOICES.map(l => (
                    <span key={l} className={'btn sm' + (eff === l ? ' primary' : '')} onClick={() => assign(t.idx, l)}>{l}</span>
                  ))}
                  <span className="btn sm ghost">other…</span>
                  {open && !untagged && <span className="btn sm ghost" onClick={() => setPicking(null)}>cancel</span>}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* What the two controls actually do — they write different container
          properties and are easy to confuse, so spell it out. */}
      <div className="note blue" style={{ marginTop: 2 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '7px 12px', alignItems: 'baseline' }}>
          <b style={{ whiteSpace: 'nowrap' }}>▲▼ Order</b>
          <span className="tiny">
            The track's <b>index</b> in the file — its position, nothing more.
            {kind === 'audio'
              ? <> Jellystructure reads audio tracks <b>top-down</b> to decide the <b>metadata language</b> (first match wins), and players fall back to the first track when no other preference applies.</>
              : <> Sets the order subtitles are listed in; a player falls back to the first one when nothing else matches.</>}
          </span>
          <b style={{ whiteSpace: 'nowrap' }}>★ Default</b>
          <span className="tiny">
            The track the player <b>auto-selects on playback</b>. Independent of order — the default {kind === 'audio' ? 'audio' : 'subtitle'} track needn't be first, and changing it <b>never</b> affects the metadata language.
          </span>
        </div>
      </div>

      {kind === 'audio' && (
        <div className="tiny muted">
          This episode resolves to <Lang code={ep.resolved} /> — the first matched audio language in the order above. Untagged tracks are skipped until you tag them.
        </div>
      )}

      {kind === 'audio' && langMismatch && (
        <div className="note" style={{ background: 'var(--warn-soft)', borderColor: 'rgba(245,181,66,.4)' }}>
          <div className="tiny"><b style={{ color: 'var(--warn)' }}>⚠ Default ≠ metadata language.</b> The default audio track is <Lang code={defAudioLang} /> ({nm(defAudioLang)}), but metadata resolves to <Lang code={ep.resolved} /> ({nm(ep.resolved)}) from the track order. Viewers would <b>read {nm(ep.resolved)}</b> descriptions while <b>hearing {nm(defAudioLang)}</b>. That's allowed — just confirm it's intentional, or move <Lang code={ep.resolved} /> to the top / set it default to align them.</div>
        </div>
      )}

      <div className="tiny muted">
        Order &amp; the default flag are both <b>manual</b> — written with <span className="mono">mkvpropedit</span> on your command. Nothing is reordered or re-flagged automatically.
      </div>
    </div>
  );
}

/* ─────────────────────── STEP CONTENT ─────────────────────── */

function SeriesStep() {
  const counts = {
    attn: EPISODES.filter(e => epState(e) === 'attn').length,
    untagged: EPISODES.filter(e => e.tracks.some(t => !t.lang)).length,
    art: EPISODES.filter(e => !e.hasArt).length,
    overview: EPISODES.filter(e => !e.hasOverview).length,
  };
  return (
    <div className="col" style={{ gap: 16 }}>
      <div className="row center" style={{ gap: 10 }}>
        <span className="kicker2">STEP 1 · SERIES LEVEL</span>
        <span className="badge info">tvshow.nfo</span>
      </div>

      {/* metadata + poster */}
      <div className="card">
        <div className="row" style={{ alignItems: 'flex-start' }}>
          <ArtSlot label="poster" w={120} h={178} />
          <div className="fill col" style={{ gap: 10 }}>
            <div className="row">
              <div className="field fill" style={{ margin: 0 }}><label>Series title</label><div className="input">{SERIES.title}</div></div>
              <div className="field" style={{ margin: 0, width: 100 }}><label>Year</label><div className="input">{SERIES.year}</div></div>
            </div>
            <div className="field" style={{ margin: 0 }}><label>Plot</label>
              <div className="input" style={{ minHeight: 56, alignItems: 'flex-start' }}>{SERIES.plot}</div>
            </div>
            <div className="field fill" style={{ margin: 0 }}><label>Genres</label>
              <div className="pill-row">
                {SERIES.genres.map(g => <span key={g} className="chip">{g} ✕</span>)}
                <span className="chip ghost">＋</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* series artwork */}
      <div className="card">
        <SectionTitle right={<span className="pill-row"><span className="btn sm">Fetch all</span><span className="btn sm ghost">Upload</span></span>}>Series artwork</SectionTitle>
        <div className="row" style={{ gap: 12 }}>
          <ArtSlot label="poster" w={120} h={150} />
          <ArtSlot label="backdrop" w={266} h={150} />
          <ArtSlot label="clearlogo" w={200} h={150} missing />
        </div>
      </div>

      {/* language across episodes */}
      <div className="override">
        <SectionTitle right={<span className="badge info">majority wins</span>}>Metadata language across episodes</SectionTitle>
        <div className="tiny" style={{ margin: '0 0 14px' }}>
          Each episode resolves its own TMDB fetch language from its own audio tracks. The <b>series</b> (tvshow.nfo) uses the language most episodes share. <b>7 of 8</b> resolve to <Lang code="fo" /> → that wins. One episode (<span className="mono">S01E04</span>) resolves to <Lang code="da" />; it still gets its own metadata.
        </div>
        <LangDist />
        <hr className="dash" style={{ margin: '14px 0' }} />
        <div className="row center" style={{ gap: 14, flexWrap: 'wrap' }}>
          <div className="field" style={{ margin: 0, width: 230 }}>
            <label>Series language (override)</label>
            <span className="select"><span><Lang code="fo" /> &nbsp;Føroyskt · auto</span></span>
          </div>
          <div className="tiny muted fill" style={{ maxWidth: '42ch' }}>Auto-picked from the distribution above. Override only if the majority is wrong — episodes are unaffected.</div>
        </div>
      </div>

      {/* episode summary */}
      <div className="card">
        <SectionTitle>This season · {SERIES.episodes} episodes</SectionTitle>
        <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
          <span className="chip"><span className="dot bad"></span> {counts.attn} need attention</span>
          <span className="chip"><span className="dot bad"></span> {counts.untagged} untagged tracks</span>
          <span className="chip"><span className="dot warn"></span> {counts.art} missing artwork</span>
          <span className="chip"><span className="dot warn"></span> {counts.overview} missing overview</span>
        </div>
      </div>
    </div>
  );
}

function EpisodeStep({ ep }) {
  const issues = epIssues(ep);
  return (
    <div className="col" style={{ gap: 16 }}>
      <div className="row center" style={{ gap: 10, flexWrap: 'wrap' }}>
        <span className="kicker2">{ep.id} · {ep.title}</span>
        <span className="muted tiny">{ep.dur}</span>
        <span className="badge info">episodedetails.nfo</span>
        <span className="spacer"></span>
        {issues.length === 0
          ? <span className="badge ok">✓ complete</span>
          : <span className="badge bad">{issues.length} to fix</span>}
      </div>

      {/* still + fields */}
      <div className="card">
        <div className="row" style={{ alignItems: 'flex-start' }}>
          <ArtSlot label="still 16:9" w={228} h={128} missing={!ep.hasArt} />
          <div className="fill col" style={{ gap: 10 }}>
            <div className="field" style={{ margin: 0 }}><label>Episode title</label><div className="input">{ep.title}</div></div>
            <div className="field" style={{ margin: 0 }}><label>Overview</label>
              {ep.hasOverview
                ? <div className="input" style={{ minHeight: 64, alignItems: 'flex-start' }}>{ep.overview}</div>
                : <div className="input ph" style={{ minHeight: 64, alignItems: 'flex-start', background: 'var(--bad-soft)', borderColor: 'var(--bad)' }}>No overview — fetch from TMDB or write one…</div>}
            </div>
            <div className="pill-row">
              <span className="chip mono">S01E{String(ep.n).padStart(2, '0')}</span>
              <span className="btn sm">Re-pull from TMDB</span>
            </div>
          </div>
        </div>
      </div>

      {/* episode artwork */}
      <div className="card">
        <SectionTitle right={<span className="pill-row"><span className="btn sm">Fetch still</span><span className="btn sm ghost">Upload</span></span>}>Episode artwork</SectionTitle>
        <div className="row" style={{ gap: 12 }}>
          <ArtSlot label="thumb / still" w={228} h={128} missing={!ep.hasArt} />
          <div className="tiny muted fill">Written as <span className="mono">{ep.id}-thumb.jpg</span> next to the episode file. Jellyfin shows it on the episode card.</div>
        </div>
      </div>

      {/* tracks — unified triage + order */}
      <div className="card">
        <SectionTitle right={<a className="btn sm ghost" href="language.html">resolver ↗</a>}>
          Tracks — language &amp; order
          {ep.tracks.some(t => !t.lang) && <span className="badge bad" style={{ marginLeft: 8 }}>untagged</span>}
        </SectionTitle>
        <TrackManager key={ep.id} ep={ep} />
      </div>
    </div>
  );
}

/* Renders the content for any step descriptor. */
function StepContent({ step }) {
  return step.kind === 'series' ? <SeriesStep /> : <EpisodeStep ep={step.ep} />;
}

Object.assign(window, {
  SERIES, EPISODES, STEPS, LANG_DIST, FIRST_ISSUE,
  epIssues, epState,
  Lang, ArtSlot, SectionTitle, LangDist, TrackManager,
  SeriesStep, EpisodeStep, StepContent,
});
