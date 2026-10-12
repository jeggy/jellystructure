/* Ravilo — the phone (the app: tabs, pages, detail, player, cast, profile — was the inline <script>). Lifted out of "Ravilo Mobile.html" on 2026-10-04 so the one Ravilo file (Ravilo.html ?surface=phone)
   can load it. THIS is the maintained copy now; "Ravilo Mobile.html" keeps its old inline copy as a frozen snapshot until it is deleted. */
(function(){
  const R = window.RAVILO;
  const app = document.getElementById('app');
  const scroll = document.getElementById('scroll');
  const detail = document.getElementById('detail');
  const dscroll = document.getElementById('dscroll');
  /* Escapes for BOTH text and attribute positions — quotes included, because most call sites here
     interpolate into attributes (`data-title="…"`, `data-tx="…"`). Without the quote pair a title or
     a search query containing `"` breaks out of its attribute and re-parses the surrounding markup. */
  const esc = s => (s==null?'':String(s)).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let tab = (new URLSearchParams(location.search).get('tab')) || 'home';
  const TAXO_TABS = ['networks', 'studios', 'genres'];
  const TAXO_LABEL = { studios: 'Studios', networks: 'Networks', genres: 'Genres' };
  const TAXO_SUB = { studios: 'Films by studio', networks: 'Series by network', genres: 'Everything by genre' };
  /* R268 — one declared order, library first, gating FILTERS it and never re-orders it. Coming Soon
     needs Sonarr/Radarr and Request needs Seerr (R243 FR-R243-1), so either may be absent; the three
     taxonomy tabs index the library itself and are always present. R366 (owner, 2026-10-04): Coming
     Soon is first, so Discover opens on it wherever Sonarr/Radarr is set up, else on Networks.
     The strip scrolls — five chips do not fit a portrait phone. */
  const SEG_ORDER = ['coming', 'networks', 'studios', 'genres', 'request'];
  const SEG_LABEL = { networks:'Networks', studios:'Studios', genres:'Genres', coming:'Coming Soon', request:'Request' };
  // R310: a library wall with no values for this viewer has no chip; Discover lands on the first chip left.
  // Mockup-only: ?emptywalls=networks,studios empties those walls.
  const EMPTY_WALLS = (new URLSearchParams(location.search).get('emptywalls') || '').split(',').filter(Boolean);
  const wallHas = s => { if(EMPTY_WALLS.indexOf(s) >= 0) return false; let who = null; try { who = me; } catch(e){} return !R.taxonomy || R.taxonomy(s, who).length > 0; };
  const segAvailable = s => s==='coming' ? !!(R.config && (R.config.sonarr || R.config.radarr))
                          : s==='request' ? !!(R.config && R.config.seerr) : wallHas(s);
  const discSegs = () => SEG_ORDER.filter(segAvailable);
  let discSeg = discSegs()[0];   // the first chip left (R310 FR-R310-2); undefined = nothing to discover
  const discSegHTML = active => '<div class="tabs discsegs">'
    + discSegs().map(s=>'<div class="tab'+(s===active?' on':'')+'" data-discseg="'+s+'">'+SEG_LABEL[s]+'</div>').join('')
    + '</div>';
  let txsel = null;   // a picked studio / network / genre, i.e. the filtered grid
  const me = (R.profiles || []).find(p => p.signedIn) || null;

  /* ---- fit phone to viewport ---- */
  function fit(){
    const ph = document.querySelector('.phone');
    const ios = ph.classList.contains('ios');
    // the music round's review panel takes the right edge when open; the frame size is measured so
    // the landscape preview (.phone.land) fits too. Same numbers as before for both portrait bezels.
    const pw = document.body.classList.contains('muq-open') && window.innerWidth > 1000 ? 380 : 0;
    void ios;
    const s = Math.min((window.innerWidth - pw)/(ph.offsetWidth+40), (window.innerHeight-(document.documentElement.classList.contains('rv-embed')?56:152))/(ph.offsetHeight+66), 1);
    ph.style.transform = 'scale('+s+')';
    // The device picker swaps the bezel (412×915 ↔ 393×852), which changes the nav's width — the
    // pill's offset is measured, so it has to be re-measured here or it stays where the previous
    // frame put it (and on the narrower iPhone frame it hangs off the last item).
    paintNav();
  }
  window.addEventListener('resize', fit); fit();

  /* ---- device picker (mockup-only preview chrome; ?dev=ios also works) ---- */
  function setDev(kind){
    const ph = document.querySelector('.phone');
    ph.classList.toggle('ios', kind === 'ios');
    document.getElementById('devLabel').textContent =
      kind === 'ios' ? 'Ravilo · iPhone 16 · portrait' : 'Ravilo · Pixel 9 · portrait';
    document.querySelectorAll('#devPick button').forEach(b => b.classList.toggle('on', b.dataset.dev === kind));
    fit();
  }
  document.getElementById('devPick').addEventListener('click', e => {
    const b = e.target.closest('button[data-dev]');
    if (b) setDev(b.dataset.dev);
  });
  if ((new URLSearchParams(location.search)).get('dev') === 'ios') setDev('ios');

  /* ---- tiles & rows ---- */
  function tile(it){
    const badge = /^(4K|HDR|4K HDR|Dolby Vision)$/i.test(String(it.badge||'').trim()) ? '<span class="pb">'+esc(it.badge)+'</span>' : ''; // owner 2026-09-28: tiles show picture quality only
    return '<div class="tile" data-title="'+esc(it.title)+'">'
      + '<div class="poster" style="background:'+it.grad+';">'+badge+'<span class="ini">'+esc(it.initials)+'</span></div>'
      + '<div class="tlabel">'+esc(it.title)+'</div><div class="tsub">'+esc(String(it.year))+' · '+esc(R.normGenre ? R.normGenre(it.genre.split(' · ')[0]) : it.genre.split(' · ')[0])+'</div></div>';
  }
  function rowHTML(r){
    return '<div class="row"><h3>'+esc(r.title)+'<span class="more">See all ›</span></h3>'
      + '<div class="track">'+r.items.map(tile).join('')+'</div></div>';
  }

  /* ---- hero ---- */
  function heroHTML(items){
    const slides = items.map(h=>{
      const bg = h.backdrop ? 'url('+h.backdrop+') center/cover' : h.grad;
      return '<div class="hslide" data-title="'+esc(h.title)+'"><div class="hcard" style="background:'+bg+';">'
        + (h.badge?'<span class="hbadge">'+esc(h.badge)+'</span>':'')
        + '<div class="scrim"></div><div class="body">'
        + (h.tagline?'<div class="htag">'+esc(h.tagline)+'</div>':'')
        + '<div class="htitle">'+esc(h.title)+'</div>'
        + '<div class="hmeta"><span>'+esc(String(h.year))+'</span><span>·</span><span>'+esc(h.genre)+'</span><span class="pg">'+esc(h.rating)+'</span></div>'
        + '<div class="hbtns"><button class="btn play" data-play="'+esc(h.title)+'">▶ Play</button><button class="btn ghost" data-title="'+esc(h.title)+'">ⓘ Info</button></div>'
        + '</div></div></div>';
    }).join('');
    const dots = items.map((_,i)=>'<i class="'+(i===0?'on':'')+'"></i>').join('');
    return '<div class="hero" id="hero">'+slides+'</div><div class="hdots" id="hdots">'+dots+'</div>';
  }

  /* R267 (round 2) — Movies and Series are no longer top-level pages on a phone: the bar is three
     items (Home · Search · Discover) so Search can hold the middle, and the two type browses became
     the first two Discover segments. One helper serves both. */
  function rowsForKind(kind){
    const k = kind === 'movies' ? 'film' : 'series';
    return R.rows.filter(r=>r.items.some(i=>i.kind===k))
      .map(r=>({title:r.title, items:r.items.filter(i=>i.kind===k)})).filter(r=>r.items.length);
  }
  function rowsForTab(){ return R.rows; }

  function renderHome(){
    const heroItems = tab==='movies' ? R.hero.filter(h=>h.kind==='film') : tab==='series' ? R.hero.filter(h=>h.kind==='series') : R.hero;
    let html = (window.RaviloWeb ? RaviloWeb.homeTop() : '') + heroHTML(heroItems);
    if(tab==='home'){
      html += '<div class="row"><h3>Channels</h3><div class="studios">'
        + R.studios.map(s=>'<div class="studio" style="background:'+s.bg+';">'+s.wm.replace(/<small>/g,'<small>').replace(/<\/small>/g,'</small>')+'</div>').join('')
        + '</div></div>';
    }
    html += rowsForTab().slice(0,6).map(rowHTML).join('');
    html += '<div style="height:30px;"></div>';
    app.innerHTML = html;
    wireHero();
  }

  /* R267 — Library: Movies and Series are one page on a phone, with the type as a dropdown (the
     "Kategorier" idiom, on this page only — never in the app bar). Music videos are a real library
     type (phase 172) and are offered even where the household holds none: an honest empty state
     beats hiding a filter that the admin can fill next week. */
  const LIB_KINDS = [
    { id:'all',    label:'All' },
    { id:'movies', label:'Movies', kind:'film' },
    { id:'series', label:'Series', kind:'series' },
    // music VIDEOS (phase 172) — renamed from "Music" now that music itself is a mode of its own (brief 2026-09-27)
    { id:'music',  label:'Music videos',  kind:'music' },
  ];
  let libKind = 'all', libOpen = false;
  function libRows(){
    const k = (LIB_KINDS.find(x=>x.id===libKind)||{}).kind;
    if(!k) return R.rows;
    return R.rows.filter(r=>r.items.some(i=>i.kind===k))
      .map(r=>({title:r.title, items:r.items.filter(i=>i.kind===k)})).filter(r=>r.items.length);
  }
  function libCount(id){
    const k = (LIB_KINDS.find(x=>x.id===id)||{}).kind;
    const lib = R.libraryFor ? R.libraryFor(me) : [];
    return k ? lib.filter(i=>i.kind===k).length : lib.length;
  }
  function paintLibSlot(){
    const slot = document.getElementById('libSlot');
    if(tab !== 'library'){ slot.innerHTML = ''; slot.classList.remove('open'); return; }
    const cur = LIB_KINDS.find(x=>x.id===libKind) || LIB_KINDS[0];
    slot.classList.toggle('open', libOpen);
    slot.innerHTML = '<button class="libpill" id="libPill">' + esc(cur.label)
      + '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>'
      + '<div class="libmenu">' + LIB_KINDS.map(k=>'<button data-libkind="'+k.id+'"'+(k.id===libKind?' class="on"':'')+'>'
          + esc(k.label) + ' <span class="ct">' + libCount(k.id) + '</span><span class="tk">✓</span></button>').join('')
      + '</div>';
  }
  /* Owner decision 3 (2026-09-27 audit): the phone Library as BUILT — R187's browse grid with its facet bar,
     titled Library (R267 FR-5b), not Home-style rows. The type pill above still picks the kind. */
  /* As built (R317 + 269/R318): one entry per FIELD with its direction in words; choosing the field in use flips it.
     Recommended is first and the default when the server sends a recommended order (it has no direction). */
  const LIB_SORTS = [['Recommended',null,'The order the server suggests'],['Date added',['Newest first','Oldest first']],['Release year',['Newest first','Oldest first']],['Title',['A to Z','Z to A']],['Size',['Largest first','Smallest first']]];
  let libSort = 0, libRev = false;
  const libWay = () => { const d = LIB_SORTS[libSort][1]; return d ? d[libRev?1:0] : LIB_SORTS[libSort][2]; };
  function libItems(){
    const k = (LIB_KINDS.find(x=>x.id===libKind)||{}).kind;
    let items = (R.libraryFor ? R.libraryFor(me) : []).filter(i => !k || i.kind===k);
    const f = LIB_SORTS[libSort][0];
    const sz = it => { let h = 0; for (const c of it.title) h = (h*31 + c.charCodeAt(0)) >>> 0; return (it.kind==='series'?40:4) + h % 60; };
    if(f==='Title') items = items.slice().sort((a,b)=>a.title.localeCompare(b.title));
    else if(f==='Size') items = items.slice().sort((a,b)=>sz(b)-sz(a));
    else if(f==='Release year') items = items.slice().sort((a,b)=>(b.year||0)-(a.year||0));
    else if(f==='Recommended') items = items.slice().sort((a,b)=>(sz(a)*7%13)-(sz(b)*7%13));
    if(libRev) items = items.slice().reverse();
    return items;
  }
  function renderLibrary(){
    const cur = LIB_KINDS.find(x=>x.id===libKind) || LIB_KINDS[0];
    const items = libItems();
    const f = LIB_SORTS[libSort][0], way = LIB_SORTS[libSort][1] ? libWay() : '';
    app.innerHTML = '<div class="row" style="margin-top:4px;"><h3>Library<span class="more" style="cursor:default">'+countLabel(items.length)+'</span></h3></div>'
      + '<div class="lbfbar">'
      +   ['Genre','Year','Watched','Audio','Maturity'].map(x=>'<button class="lbf">'+x+' <svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>').join('')
      +   '<button class="lbf sort" data-libsort="1"><span class="lb">Sort</span>'+esc(f)+(way?' · '+esc(way):'')+'</button>'
      + '</div>'
      + (items.length ? '<div class="txgrid">'+items.map(tile).join('')+'</div>'
          : '<div class="txmeta" style="padding-top:18px;">'+(cur.id==='music' ? 'No music videos yet' : 'Nothing here for this profile yet')+'</div>')
      + '<div style="height:30px;"></div>';
  }

  /* Request (R170/R171) — Seerr-backed trending, with the live request status per title. */
  function renderRequest(){
    const list = R.discover.lists[0];
    const statusClass = s => s==='available'?'avail':(s==='downloading'||s==='fetching'?'fetch':'none');
    const statusText = it => it.status==='available'?'In library':(it.status==='downloading'||it.status==='fetching'?'Fetching…':'Not in library');
    // As built: Seerr's feeds, one row each, with the request status on the tile — no ranks, no view counts,
    // no source wordmark (the chart era, phases 136/137, is retired).
    void list; void statusClass;
    app.innerHTML = discSegHTML('request')
      + (R.discover.lists || []).map(l => '<div class="row"><h3>'+esc(l.title)+'</h3><div class="track">'
          + l.items.map(it => tile(Object.assign({}, it, { badge: it.status==='available' ? '✓ '+statusText(it) : (it.status==='downloading'||it.status==='fetching' ? statusText(it) : null) }))).join('')
          + '</div></div>').join('')
      + '<div style="height:30px;"></div>';
  }

  /* Coming Soon (R160/R188) — the merged Sonarr/Radarr calendar, grouped by day, with the
     overdue set last. Phone half of the TV's date-railed screen; no speculation the profile
     is not allowed to see (R188), which R.upcomingByDay already scopes. */
  function renderComing(){
    const days = R.upcomingByDay();
    const dayLabel = iso => {
      const d = new Date(iso + 'T00:00:00'), today = new Date(); today.setHours(0,0,0,0);
      const off = Math.round((d - today) / 86400000);
      if(off === 0) return 'Today';
      if(off === 1) return 'Tomorrow';
      return d.toLocaleDateString(undefined, { weekday:'short', day:'numeric', month:'short' });
    };
    const card = it => '<div class="t10row" data-title="'+esc(it.title)+'">'
      + '<div class="t10p" style="background:'+(it.grad||R.grad(it.title))+';">'+esc(it.initials||R.initials(it.title))+'</div>'
      + '<div class="t10b"><div class="nm">'+esc(it.title)+'</div>'
      + '<div class="mt">'+[it.ep ? esc(it.ep) : esc(it.release||'Film'), it.time?esc(it.time):'', it.network?esc(it.network):''].filter(Boolean).join(' · ')+'</div>'
      + (it.status==='available' ? '<span class="st avail"><span class="dot"></span>Already available</span>'
        : it.status==='missing' ? '<span class="st none"><span class="dot"></span>Missing</span>'
        : it.status==='downloading' ? '<span class="st fetch"><span class="dot"></span>Fetching…</span>' : '')
      + '</div></div>';
    let html = discSegHTML('coming');
    days.forEach(d => {
      html += '<div class="row" style="margin-top:2px;"><h3 style="padding-bottom:4px;">'+esc(dayLabel(d.date))
        + '<span class="more">'+d.items.length+'</span></h3></div><div class="t10">'+d.items.map(card).join('')+'</div>';
    });
    const missing = (R.overdue||[]).filter(it => it.status === 'missing');
    if(missing.length) html += '<div class="row" style="margin-top:2px;"><h3 style="padding-bottom:4px;">Released, still missing'
      + '<span class="more">'+missing.length+'</span></h3></div><div class="t10">'+missing.map(card).join('')+'</div>';
    if(!days.length && !missing.length) html += '<div class="txmeta" style="padding-top:20px;">Nothing scheduled</div>';
    app.innerHTML = html + '<div style="height:30px;"></div>';
  }

  /* ---- Discover → library taxonomies ----
     Counts and grouping come from R.taxonomy / R.taxoValues, the same resolver the TV wall
     uses and scoped to this profile, so the two surfaces can never state different numbers. */
  function countLabel(n){ return n===1 ? '1 title' : n+' titles'; }
  function renderTaxo(kind){
    const list = R.taxonomy ? R.taxonomy(kind, me) : [];
    const sum = R.taxonomySummary ? R.taxonomySummary(kind, me) : {groups:list.length, titles:0};
    let html = discSegHTML(kind)
      + '<div class="txmeta" style="padding-top:0;"><b>'+sum.groups+' '+kind+'</b> · '+countLabel(sum.titles)+' · '+TAXO_SUB[kind].toLowerCase()
      + (me && me.kid ? ' · counting only what this profile can watch' : '') + '</div>';
    html += '<div class="txwall'+(kind==='genres'?' gen':'')+'">'
      + list.map(e=>'<div class="txc" data-tx="'+esc(e.name)+'">'
          + '<div class="txcard">'+(e.logo
              ? '<img class="txlogo'+(kind!=='genres' && (e.logoReink || e.logoInk==='light') ? ' reink' : '')+'" src="'+esc(e.logo)+'" alt="'+esc(e.name)+'">'
              : '<span class="txwm">'+esc(e.name)+'</span>')+'</div>'
          + '<div class="txcap">'+(e.logo?'<span class="txnm">'+esc(e.name)+'</span>':'')
          + '<span class="txct">'+countLabel(e.count)+'</span></div></div>').join('')
      + '</div>';
    if(!list.length) html = '<div class="txmeta" style="padding-top:20px;">Nothing here for this profile yet</div>';
    app.innerHTML = html + '<div style="height:30px;"></div>';
  }
  function renderTaxoList(kind, name){
    const items = (R.libraryFor ? R.libraryFor(me) : []).filter(it => (R.taxoValues ? R.taxoValues(it, kind) : []).indexOf(name) >= 0);
    app.innerHTML = '<div class="txback" data-txback="1">‹ '+TAXO_LABEL[kind]+'</div>'
      + '<div class="row" style="margin-top:0;"><h3>'+esc(name)+'<span class="more">'+countLabel(items.length)+'</span></h3></div>'
      + '<div class="txgrid">'+items.map(tile).join('')+'</div>'
      + '<div style="height:30px;"></div>';
  }

  /* R268 FR-R268-7 — the selected chip must be visible, whatever the strip's width. The mockup
     rebuilds the strip on every segment change (R262 FR-R262-1 is the architectural fix: one
     segment bar in the frame, only the region below it swaps), so rather than remember a scroll
     offset we DERIVE it from the selection — which also means re-entering Discover on a household
     whose last segment was Request opens scrolled to it, not at Networks with the selection off-screen. */
  function paintSegScroll(){
    const put = () => {
      const strip = app.querySelector('.discsegs'); if(!strip) return;
      const on = strip.querySelector('.tab.on'); if(!on) return;
      strip.scrollLeft = Math.max(0, on.offsetLeft - 18);   // 18px = the page gutter, so chip 1 stays flush
    };
    // Twice, deliberately: on the first paint of a cold load the webfont has not swapped in yet, so
    // the chips are narrower, the strip is not yet overflowing and the assignment clamps to 0 —
    // the offset has to be re-applied once layout is final, or the selected chip stays off-screen.
    put(); requestAnimationFrame(put);
    if(document.fonts && document.fonts.ready) document.fonts.ready.then(put);
  }

  /* R267 — Search as a page. The shell (field + results) is built once and only the results are
     re-filled as you type, so the input keeps focus and its caret across keystrokes; re-rendering
     the whole page on every letter is what makes a mockup search field feel broken. */
  let srchQ = '';
  function searchHits(){
    const q = srchQ.trim().toLowerCase();
    if(!q) return [];
    return (R.libraryFor ? R.libraryFor(me) : []).filter(it =>
      String(it.title).toLowerCase().includes(q) || String(it.genre||'').toLowerCase().includes(q));
  }
  function fillSearch(){
    const hits = searchHits();
    const meta = document.getElementById('srchMeta'), res = document.getElementById('srchRes');
    if(!meta || !res) return;
    meta.textContent = !srchQ.trim() ? 'Titles and genres — everything this profile can watch'
      : hits.length ? countLabel(hits.length) + ' found'
      : 'Nothing matches “' + srchQ.trim() + '”';
    res.innerHTML = hits.length ? '<div class="txgrid">' + hits.map(tile).join('') + '</div>' : '';
  }
  function renderSearch(){
    app.innerHTML = '<div class="srch"><div class="fld">'
      + '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20.5 20.5l-4.2-4.2"/></svg>'
      + '<input id="srchInput" type="search" placeholder="Search Ravilo" autocomplete="off">'
      + '<button class="clr" id="srchClr" type="button" hidden>Clear</button>'
      + '</div></div><div class="srchmeta" id="srchMeta"></div><div id="srchRes"></div><div style="height:30px;"></div>';
    const input = document.getElementById('srchInput');
    input.value = srchQ;   // assigned, never interpolated into the attribute — nothing to escape
    const clr = document.getElementById('srchClr');
    clr.hidden = !srchQ;
    input.addEventListener('input', () => { srchQ = input.value; clr.hidden = !srchQ; fillSearch(); });
    clr.addEventListener('click', () => { srchQ = ''; input.value = ''; clr.hidden = true; fillSearch(); });
    fillSearch();
  }
  function focusSearch(){ const i = document.getElementById('srchInput'); if(i) i.focus(); }

  /* R267 · the bottom bar: paint the selected page and slide the gradient pill onto it. The pill
     is one element that moves, not four that light up — which is what makes the bar read as a
     single object (and is the M3 behaviour a phone viewer already expects). */
  function paintNav(){
    const items = [...document.querySelectorAll('#bnav .bn')];
    if(!items.length) return;
    const on = items.find(b => b.dataset.tab === tab) || items[0];
    items.forEach(b => b.classList.toggle('on', b === on));
    const ind = document.getElementById('bnind'), p = on.querySelector('.p');
    if(ind && p) ind.style.transform = 'translateX(' + (p.offsetLeft + on.offsetLeft) + 'px)';
  }
  /* Belt and braces: any box change on the bar re-seats the pill, so no future width tweak,
     orientation change or font swap can leave it at a stale offset. */
  if(window.ResizeObserver) new ResizeObserver(() => paintNav()).observe(document.getElementById('bnav'));

  function render(){
    // R280 (mockup PREVIEW): a page that failed to load shows its one LoadErrorState instead of the page
    if(window.RaviloWeb && RaviloWeb.error(tab)){ paintNav(); paintLibSlot(); scroll.scrollTop = 0; app.innerHTML = RaviloWeb.error(tab); return; }
    if(window.RaviloMusic && RaviloMusic.handles(tab)){ paintNav(); paintLibSlot(); scroll.scrollTop = 0; RaviloMusic.render(tab); return; }
    paintNav();
    paintLibSlot();
    document.querySelectorAll('#bnav .bn').forEach(t=>t.classList.toggle('on', t.dataset.tab===tab));
    scroll.scrollTop = 0;
    if(tab==='discover'){
      if(discSegs().indexOf(discSeg) < 0) discSeg = discSegs()[0];   // R310 FR-R310-4: a chip that left moves you on
      if(!discSeg) app.innerHTML = '<div class="txmeta" style="padding-top:20px;">Nothing here for this profile yet</div>';   // FR-R310-5
      else if(TAXO_TABS.indexOf(discSeg) >= 0) { if(txsel) renderTaxoList(discSeg, txsel); else renderTaxo(discSeg); }
      else if(discSeg === 'coming') renderComing();
      else renderRequest();
      paintSegScroll();
    }
    else if(tab==='library') renderLibrary();
    else if(tab==='search') renderSearch();
    else if(tab==='profile') renderProfile();
    else renderHome();
  }

  /* ---- hero dots on scroll ---- */
  function wireHero(){
    const hero = document.getElementById('hero'), dots = document.getElementById('hdots');
    if(!hero||!dots) return;
    hero.addEventListener('scroll', ()=>{
      const i = Math.round(hero.scrollLeft / (hero.firstChild.offsetWidth + 0));
      const slideW = hero.scrollWidth / hero.children.length;
      const idx = Math.round(hero.scrollLeft / slideW);
      [...dots.children].forEach((d,k)=>d.classList.toggle('on', k===idx));
    }, {passive:true});
  }

  /* ---- detail ---- */
  function mPad(n){ return String(n).padStart(2,'0'); }
  function mEpUnits(eps){ const u=[]; let cur=null; eps.forEach(e=>{ if(e.file){ if(cur&&cur.file===e.file){ cur.eps.push(e); } else { cur={file:e.file,eps:[e]}; u.push(cur); } } else { u.push({single:e}); cur=null; } }); return u; }
  /* R343 (direction B) — the same rules as the TV: finished = every episode watched by this viewer;
     Shuffle on the Episodes header of any series with 9+ episodes; Reset progress (two taps) once finished. */
  const MW = R.watched;
  const M_SHUF = '<svg viewBox="0 0 24 24"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>';
  const M_RESET = '<svg viewBox="0 0 24 24"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>';
  function mW(it, s, e){ return !!(MW && MW.epState(it.title, s, e.n, e.pct).watched); }
  function mSeasons(it){ return R.seasonsFor ? R.seasonsFor(it) : 1; }
  function mEpCount(it){ let c = 0; for(let s = 0; s < mSeasons(it); s++) c += R.episodesFor(it, s).length; return c; }
  function mFinished(it){
    if(it.kind !== 'series' || !R.episodesFor || !MW) return false;
    for(let s = 0; s < mSeasons(it); s++) if(!R.episodesFor(it, s).every(e => mW(it, s, e))) return false;
    return true;
  }
  function episodesHTML(it){
    if(it.kind!=='series' || !R.episodesFor) return '';
    const eps = R.episodesFor(it, 0);
    const units = mEpUnits(eps);
    const cards = units.map(u=>{
      if(u.single){ const e=u.single; return '<div class="merow-single">'
        + '<div class="methumb" style="background:'+e.grad+'"><span class="mn">'+mPad(e.n)+'</span>'+(mW(it,0,e)?'<span class="mwk">✓</span>':'')+'</div>'
        + '<div class="memeta2"><div class="mt2">'+esc(e.title)+'</div><div class="md2">'+esc(e.dur)+'</div>'+epSlowHTML(it, e)+'</div></div>'; }
      const eps3=u.eps.slice(0,3);
      const tiles=eps3.map((e,i)=>'<div class="tp tp'+(i+1)+'" style="background:'+e.grad+'"></div>').join('');
      const nums=eps3.map((e,i)=>'<span class="tnum tnum'+(i+1)+'">'+mPad(e.n)+'</span>').join('');
      const first=u.eps[0].n, last=u.eps[u.eps.length-1].n;
      const durSum=u.eps.reduce((a,e)=>a+(parseInt(e.dur)||0),0);
      const rows=u.eps.map(e=>'<div class="merow"><span class="n">'+mPad(e.n)+'</span><span class="t">'+esc(e.title)+'</span><span class="d">'+esc(e.dur)+'</span></div>').join('');
      return '<div class="mecombo">'
        + '<div class="mestill"><div class="trip">'+tiles+'<span class="seam seam1"></span><span class="seam seam2"></span>'+nums+'</div>'
        + '<span class="merng">Episodes '+first+'\u2013'+last+'</span></div>'
        + '<div class="mebody"><div class="met">Episodes '+first+'\u2013'+last+'</div>'
        + '<div class="merows">'+rows+'</div>'
        + '<div class="mefile">\u25a4 1 file \u00b7 '+u.eps.length+' episodes \u00b7 '+durSum+'m</div>'
        + epSlowHTML(it, u.eps[0])+'</div></div>';
    }).join('');
    const tot = mEpCount(it), done = mFinished(it);
    return '<div class="seclbl mrow">Episodes <span style="font-weight:400;color:var(--ink-dim);font-family:Sora,sans-serif;font-size:12px;">Season 1 \u00b7 '+units.length+' files</span>'
      + (tot >= 9 ? '<button class="mshuf" data-mshuf="'+esc(it.title)+'">'+M_SHUF+'Shuffle</button>' : '') + '</div>'
      + (done ? '<div class="mdone"><span class="l"><span class="ck">\u2713</span>All '+tot+' episodes watched</span><button class="mreset" data-mreset="'+esc(it.title)+'">'+M_RESET+'<span>Reset progress</span></button></div>' : '')
      + '<div class="melist">'+cards+'</div>';
  }
  function findItem(title){
    const all = [].concat(R.hero, ...R.rows.map(r=>r.items), ...R.discover.lists.map(l=>l.items));
    return all.find(x=>x.title===title) || {title, year:'', genre:'', rating:'12', grad:R.grad(title), initials:R.initials(title)};
  }
  /* R221 — every genre, first one primary. TMDB's order; no cap on the phone. */
  function genresHTML(it){
    const gs = R.genresFor ? R.genresFor(it) : String(it.genre||'').split(' · ').filter(Boolean);
    if(!gs.length) return '';
    return '<div class="dgenres">'+gs.map((g,i)=>'<span class="dgen'+(i===0?' lead':'')+'">'+esc(g)+'</span>').join('')+'</div>';
  }
  // R239 amendment (2026-09-13, FR-R239-7) — the phone had NO audio/subtitle flag strip at all (this
  // was the one real remaining gap the R239 spec called out — not a regression, never built). Ported
  // from ravilo-app.js's LANG_CC/LANG_NAME/countStrip/tracksFor/audioFlagsHTML (TV-only file, not
  // shared with this self-contained mockup) rather than pulling in the whole TV script. Kept the exact
  // same demo track data so Big Buck Bunny renders identical flags on phone and TV for comparison.
  const LANG_CC = {
    en:'gb', fr:'fr', de:'de', es:'es', da:'dk', fo:'fo', is:'is', no:'no', sv:'se', fi:'fi', nl:'nl', it:'it', pt:'pt', pl:'pl', ru:'ru', ja:'jp', ko:'kr', zh:'cn', ar:'sa', hi:'in',
    sr:'rs', srp:'rs', bg:'bg', bul:'bg', id:'id', ind:'id', ms:'my', msa:'my', may:'my', sl:'si', slv:'si', et:'ee', est:'ee', lv:'lv', lav:'lv', lt:'lt', lit:'lt', tl:'ph', fil:'ph',
    ca:'ct', cat:'ct',
    // Tamil/Telugu (tam/tel) CLOSED 2026-09-13, permanently — no legitimate flag exists for either
    // (India's is already Hindi's, and reusing it would collapse three distinct languages onto one
    // shown flag); "no flag" is the intended final rendering, not an open TODO. See AudioFlagStrip.kt.
  };
  const LANG_NAME = {
    en:'English', fr:'French', de:'German', es:'Spanish', da:'Danish', fo:'Faroese', is:'Icelandic', no:'Norwegian', sv:'Swedish', fi:'Finnish', nl:'Dutch', it:'Italian', pt:'Portuguese', pl:'Polish', ru:'Russian', ja:'Japanese', ko:'Korean', zh:'Chinese', ar:'Arabic', hi:'Hindi',
    sr:'Serbian', srp:'Serbian', bg:'Bulgarian', bul:'Bulgarian', id:'Indonesian', ind:'Indonesian', ms:'Malay', msa:'Malay', may:'Malay', sl:'Slovenian', slv:'Slovenian', et:'Estonian', est:'Estonian', lv:'Latvian', lav:'Latvian', lt:'Lithuanian', lit:'Lithuanian', tl:'Filipino', fil:'Filipino',
    ca:'Catalan', cat:'Catalan',
  };
  const FLAG_MAX = 5;
  function countStrip(langs){
    const flags = [], seenCC = new Set(), seenRaw = new Set();
    (langs||[]).forEach(l => {
      if(!l) return;
      const cc = LANG_CC[l];
      if(cc){ if(!seenCC.has(cc)){ seenCC.add(cc); flags.push(l); } }
      else seenRaw.add(l);
    });
    return { flags, total: seenCC.size + seenRaw.size };
  }
  function tracksFor(item){
    if(item && item.title === 'Big Buck Bunny') return {
      audio: [
        { lang:'en' }, {}, { lang:'fo' }, { lang:'da' }, { lang:'de' }, { lang:'es' }, { lang:'fr' }, { lang:'it' }, { lang:'nl' }, { lang:'pt' },
      ],
      subs: [ { off:true }, { lang:'en' }, { lang:'en' }, { lang:'fo' }, { lang:'da' } ],
    };
    return {
      audio: [ { lang:'fo' }, { lang:'da' }, { lang:'da' }, { lang:'en' }, {} ],
      subs: [
        { off:true }, { lang:'fo' }, { lang:'da' }, { lang:'da' }, { lang:'en' }, { lang:'en' }, { lang:'en' },
        { lang:'pt' }, { lang:'pt' }, { lang:'pt' }, { lang:'zh' }, { lang:'zh' }, { lang:'fr' }, { lang:'fr' }, { lang:'fr' }, { lang:'fi' },
      ],
    };
  }
  function flagsHTML(it){
    const ts = tracksFor(it);
    const flagRow = c => {
      const shown = c.flags.slice(0, FLAG_MAX), extra = c.total - shown.length;
      const flags = shown.map(l => '<span class="fi fi-'+LANG_CC[l]+'" title="'+esc(LANG_NAME[l]||l)+'"></span>').join('');
      const more = extra > 0 ? '<span class="fmore" title="'+extra+' more language'+(extra>1?'s':'')+'">+'+extra+'</span>' : '';
      return flags + more;
    };
    const aud = countStrip((ts.audio||[]).map(a => a.lang));
    const sub = countStrip((ts.subs||[]).filter(s => !s.off).map(s => s.lang));
    if(!aud.total && !sub.total) return '';
    const groups = [];
    if(aud.total) groups.push('<span class="fgroup"><span class="flabel">Audio</span><span class="frow">'+flagRow(aud)+'</span></span>');
    if(sub.total) groups.push('<span class="fgroup"><span class="flabel">Subtitles</span><span class="frow">'+flagRow(sub)+'</span></span>');
    return '<div class="dflags">'+groups.join('<span class="fdiv"></span>')+'</div>';
  }
  // R222 — the phone renders the same server-pushed field. In production the backend answers it
  // for THIS phone (its own decoder, its own history), so a title that carries the note on the TV
  // may carry nothing here; the mock substitutes the phone's name to show that it is per-device.
  // i18n keys: slow_lead · slow_tail_expected · slow_tail_measured · this_phone.
  function slowBody(n){
    const tail = (n.basis==='measured' && n.seconds) ? 'The last few times it took about '+n.seconds+' seconds.'
      : 'Give it a moment after you press play.';
    return '<b>Slow to start on Pixel 8.</b> '+tail;
  }
  // FR-R222-4/5 — films carry the line above the action row; a SERIES never does, because its
  // episodes are different files. The episode rows below carry their own (one file, one line).
  function slowHTML(it){
    if(it.kind==='series') return '';
    const n = R.playbackNoteFor ? R.playbackNoteFor(it) : null;
    if(!n) return '';
    return '<div class="dslow"><i></i><span>'+slowBody(n)+'</span></div>';
  }
  function epSlowHTML(it, ep){
    const n = (R.playbackNoteFor && ep) ? R.playbackNoteFor(it, 0, ep) : null;
    if(!n) return '';
    return '<div class="meslow">'+slowBody(n)+'</div>';
  }
  function aboutHTML(it){
    const ab = R.aboutFor ? R.aboutFor(it) : null;
    if(!ab) return '';
    const isS = it.kind==='series';
    const seasons = isS && R.seasonsFor ? R.seasonsFor(it) : 0;
    const epTotal = isS && R.episodesFor ? R.episodesFor(it,0).length*seasons : 0;
    const d = iso => { const x=new Date(iso+'T00:00:00Z'); return isNaN(x)?iso:x.toLocaleDateString(undefined,{day:'numeric',month:'long',year:'numeric',timeZone:'UTC'}); };
    const f = (k,v) => v ? '<div><div class="k">'+k+'</div><div class="v">'+esc(String(v))+'</div></div>' : '';
    return '<div class="aboutp"><div class="seclbl" style="margin:0;">About</div><div class="g">'
      + f('Runtime', isS ? ab.runtime+' min per episode' : ab.runtime+' min')
      + f(isS?'First aired':'Released', d(ab.aired))
      + f('Director', ab.director)
      + f(isS?'Network':'Studio', ab.network)
      + f('Country', ab.country)
      + f('Original language', ab.lang)
      + (isS ? f('Seasons', seasons+' · '+epTotal+' episodes') : '')
      + f('In your library since', d(ab.added))
      + '</div></div>';
  }
  let detTitle = null;   // the one Ravilo file reads where you are (mobile/ravilo-phone-state.js)
  function openDetail(title){
    detTitle = title;
    const it = findItem(title);
    const art = R.artFor ? R.artFor(it) : { backdrop: it.backdrop, grad: it.grad };
    const bg = art.backdrop ? 'url('+art.backdrop+') center/cover' : art.grad;
    const syn = (R.synFor ? R.synFor(it) : it.syn) || 'A standout title from your library, ready to play.';
    const more = (R.relatedFor ? R.relatedFor(it) : R.rows[0].items).slice(0,10);
    dscroll.innerHTML = '<div class="dhero" style="background:'+bg+';"><button class="dback" id="dback">‹</button><div class="scrim"></div></div>'
      + '<div class="dbody">'
      + '<div class="dtitle">'+esc(it.title)+'</div>'
      + '<div class="dmeta"><span>'+esc(String(it.year))+'</span><span class="pg">'+esc(it.rating)+'</span>'+(it.kind?'<span>·</span><span>'+(it.kind==='series'?'Series':'Film')+'</span>':'')+'</div>'
      + genresHTML(it)
      + flagsHTML(it)
      + slowHTML(it)
      + '<div class="dbtns"><button class="btn play" data-mplay="'+esc(it.title)+'">'+(CAST ? '<span class="rc-ic" style="width:18px;height:15px"><span class="bx"></span><span class="fl"></span></span> Play on '+esc(CAST.device) : '▶ '+esc(mFinished(it) ? 'Play · S01E01' : (window.RaviloWeb ? RaviloWeb.playLabel(it) : 'Play')))+'</button><button class="btn ghost">＋ My List</button></div>'
      + '<div class="dsyn">'+esc(syn)+'</div>'
      + (it.kind==='series' ? episodesHTML(it) : '')
      + aboutHTML(it)
      + '<div class="seclbl">More like this</div>'
      + '<div class="track" style="padding-left:0;padding-right:0;">'+more.map(tile).join('')+'</div>'
      + '<div style="height:24px;"></div></div>';
    detail.classList.add('on');
    dscroll.scrollTop = 0;
    paintMiniOffset();
  }
  function closeDetail(){ detail.classList.remove('on'); paintMiniOffset(); }
  /* R343 Q3 — two taps: the first says what will happen, for 4 s; the second clears this viewer's ticks */
  function resetTap(b){
    const it = findItem(b.dataset.mreset), lbl = b.querySelector('span');
    if(!b.classList.contains('armed')){
      b.classList.add('armed'); lbl.textContent = 'Tap again · all '+mEpCount(it)+' back to unwatched';
      clearTimeout(b._t); b._t = setTimeout(()=>{ b.classList.remove('armed'); lbl.textContent = 'Reset progress'; }, 4000);
      return;
    }
    clearTimeout(b._t);
    for(let s = 0; s < mSeasons(it); s++) R.episodesFor(it, s).forEach(e => MW.setEpWatched(it.title, s, e.n, false));
    MW.setItem(it.title, { watched:false, pct:0 });
    const y = dscroll.scrollTop; openDetail(it.title); dscroll.scrollTop = y;
  }

  /* ---- events ---- */
  /* Re-tapping the page you are already on always scrolls that page back to the top — the platform
     behaviour on every phone bottom bar. Two pages do one more thing on top of that: Discover also
     returns to its FIRST available segment (R268's declared order, so Networks on every household),
     and Search also puts the caret back in its field — every time (R277 FR-R277-2). ARRIVING at
     Search never does: the keyboard is half the screen and the viewer has not asked to type yet
     (FR-R277-1). R278: from a pushed screen (Settings, Your profile), any item — the lit one too —
     leaves for that page rather than counting as a re-tap. */
  document.getElementById('bnav').addEventListener('click', e=>{
    const b = e.target.closest('[data-tab]'); if(!b) return;
    const next = b.dataset.tab;
    const fromPushed = acct.classList.contains('on');
    if(fromPushed) closeAcct();
    const again = next === tab && !fromPushed;
    if(again && window.RaviloMusic && RaviloMusic.reselect(next)){ /* music: Library steps its chips, Search focuses */ }
    else if(again && next === 'discover'){ discSeg = discSegs()[0]; txsel = null; render(); }
    else if(again && next === 'search'){ focusSearch(); }
    else if(again && next === 'library'){ libOpen = false; render(); }
    else if(!again){ tab = next; txsel = null; libOpen = false; render(); }
    scroll.scrollTo({ top:0, behavior:'smooth' });
  });
  /* the type dropdown lives in the app bar now, so its handlers do too; a tap anywhere else
     closes it, which is what a menu in a bar has to do. */
  document.getElementById('libSlot').addEventListener('click', e=>{
    const slot = document.getElementById('libSlot');
    // toggle the class only — rebuilding the slot's innerHTML here would detach the very node this
    // click is still bubbling from, and the document handler below would then read it as an
    // outside tap and close the menu in the same gesture that opened it.
    if(e.target.closest('#libPill')){ libOpen = !libOpen; slot.classList.toggle('open', libOpen); return; }
    const lk = e.target.closest('[data-libkind]');
    if(lk){ libKind = lk.dataset.libkind; libOpen = false; render(); }
  });
  document.addEventListener('click', e=>{
    if(!libOpen || !e.target.isConnected) return;
    if(!e.target.closest('#libSlot')){ libOpen = false; paintLibSlot(); }
  });

  app.addEventListener('click', e=>{
    const pf = e.target.closest('[data-pf]');
    if(pf){ const a = pf.dataset.pf;
      if(a==='photo') openAcct(profileHTML());
      else if(a==='password') openAcct(passwordHTML(null));
      else if(a==='lang') openAcct(langHTML());
      else if(a==='settings') openAcct(settingsHTML(), 'settings');
      else if(a==='mylist') openAcct(myListHTML(), 'mylist');
      else if(a==='signout') openSheet(true);
      return; }
    if(e.target.closest('[data-libsort]')){ window.RaviloWeb && RaviloWeb.sortSheet(LIB_SORTS, libSort, libRev, i => { if(i===libSort){ if(LIB_SORTS[i][1]) libRev = !libRev; } else { libSort = i; libRev = false; } renderLibrary(); }); return; }
    const ds = e.target.closest('[data-discseg]'); if(ds){ discSeg=ds.dataset.discseg; txsel=null; render(); return; }
    const tb = e.target.closest('[data-txback]'); if(tb){ txsel=null; render(); return; }
    const tx = e.target.closest('[data-tx]'); if(tx){ txsel=tx.dataset.tx; render(); return; }
    const play = e.target.closest('[data-play]'); if(play){ openDetail(play.dataset.play); return; }
    const el = e.target.closest('[data-title]'); if(el){ openDetail(el.dataset.title); }
  });
  dscroll.addEventListener('click', e=>{
    if(e.target.closest('#dback')){ closeDetail(); return; }
    const shf = e.target.closest('[data-mshuf]'); if(shf){ const tt = shf.dataset.mshuf; closeDetail(); openPlayer(tt, { shuffle:true }); return; }
    const rst = e.target.closest('[data-mreset]'); if(rst){ resetTap(rst); return; }
    const el = e.target.closest('[data-title]'); if(el){ openDetail(el.dataset.title); }
  });
  document.getElementById('searchBtn') && document.getElementById('searchBtn').addEventListener('click', ()=>{ tab='search'; render(); focusSearch(); });

  /* ---- profile sheet → Your profile (photo) + Settings → Account (password) ----
     R234. Photo picking is the real control the product would ship: a file input for the
     gallery, the same input with capture="user" for the camera, a drop zone for web, and a
     preset-colour row for people who want no photo at all. The chosen photo is stored per
     user, so the TV mockup and the admin's user row read the same one — one stored fact,
     one representation, exactly like the decode-ceiling note. */
  const AV = (R.avatars||{});
  const ME = (R.profiles||[]).find(p=>p.id==='eyd') || { id:'eyd', name:'Eyð', initials:'ER', color:'linear-gradient(145deg,#7b6ef0,#3fb6f5)' };
  const pmSheet = document.getElementById('pmSheet'), pmScrim = document.getElementById('pmScrim');
  const acct = document.getElementById('acct'), acctInner = document.getElementById('acctInner');
  let acctNote = null;   // {kind:'ok'|'bad', text} — cleared on every navigation

  function faceOf(){ return AV.avatarFace ? AV.avatarFace(ME) : { photo:null, style:'background:'+ME.color, label:ME.initials }; }
  function paintFace(node, label){
    if(!node) return;
    const f = faceOf();
    node.classList.toggle('has-photo', !!f.photo);
    node.style.cssText = f.style;
    node.innerHTML = f.photo ? '<img class="av-img" src="'+f.photo+'" alt="">' : (label===false ? '' : esc(f.label));
  }
  function paintAvatars(){
    paintFace(document.getElementById('avatarBtn'));
    paintFace(document.getElementById('pmAv'));
    const big = document.getElementById('pfAv'); if(big) paintFace(big);
  }
  function openSheet(on){ pmSheet.classList.toggle('on', on); pmScrim.classList.toggle('on', on); }
  let acctWhat = null;
  function openAcct(html, what){ acctWhat = what || null; acctNote=null; acctInner.innerHTML = html; acct.classList.add('on'); acct.scrollTop = 0; paintMiniOffset(); }
  function closeAcct(){ acct.classList.remove('on'); paintMiniOffset(); }
  if (new URLSearchParams(location.search).get('sheet') === 'settings') setTimeout(() => openAcct(settingsHTML()), 0);
  function noteHTML(){ return acctNote ? '<div class="pf-note '+acctNote.kind+'">'+(acctNote.kind==='ok'?'✓':'⚠')+' <span>'+esc(acctNote.text)+'</span></div>' : ''; }

  function profileHTML(){
    const f = faceOf();
    const fine = window.matchMedia('(pointer:fine)').matches;   // web gets drag-and-drop, touch doesn't
    return '<div class="acct-bar"><button data-acct="back">‹</button><h2>Your profile</h2></div>'
      + '<div class="acct-body">'
      + '<div class="pf-photo">'
      +   '<div class="pf-av'+(f.photo?' has-photo':'')+'" id="pfAv" style="'+f.style+'">'+(f.photo?'<img class="av-img" src="'+f.photo+'" alt="">':esc(f.label))+'</div>'
      +   '<div class="pf-nm">'+esc(ME.name)+'</div>'
      +   '<div class="pf-sub">Your photo shows on every device you watch on — including the TV.</div>'
      +   '<div class="pf-btns">'
      +     '<button class="pf-btn primary" data-acct="choose">⬆ Choose a photo</button>'
      +     '<button class="pf-btn" data-acct="camera">◎ Take a photo</button>'
      +     (f.photo ? '<button class="pf-btn danger" data-acct="remove">✕ Remove photo</button>' : '')
      +   '</div>'
      +   (fine ? '<div class="pf-drop">or drop an image on the circle above</div>' : '')
      + '</div>'
      + noteHTML()
      + '<input type="file" accept="image/*" id="pfFile" hidden>'
      + '<input type="file" accept="image/*" capture="user" id="pfCam" hidden>'
      + '</div>';
  }
  /* ---- the Profile page ---- */
  let pftNote = null;
  const PF_IC = {
    gear:  '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.6M12 18.6v2.6M4.2 7.5l2.2 1.3M17.6 15.2l2.2 1.3M4.2 16.5l2.2-1.3M17.6 8.8l2.2-1.3"/></svg>',
    photo: '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="9" r="4"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/></svg>',
    lang:  '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"/></svg>',
    key:   '<svg class="ic" viewBox="0 0 24 24"><circle cx="8" cy="14" r="4"/><path d="M11 11l9-9M16 6l3 3"/></svg>',
    out:   '<svg class="ic" viewBox="0 0 24 24" style="stroke:currentColor"><path d="M14 5H6v14h8"/><path d="M10 12h10M17 9l3 3-3 3"/></svg>',
    chev:  '<svg class="chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
    pen:   '<svg viewBox="0 0 24 24"><path d="M4 20l4-1 11-11-3-3L5 16z"/></svg>',
  };
  // Stand-in: this project has no My List data of its own, so a fixed slice of what the profile
  // can watch plays the part. The page shape is the point, not which titles are in it.
  function myList(){ return (R.libraryFor ? R.libraryFor(me) : []).filter((_, i) => i % 4 === 1).slice(0, 7); }
  function renderProfile(){
    const f = faceOf(), list = myList();
    app.innerHTML = '<div class="pft"><div class="pft-id">'
      +   '<button class="pft-av'+(f.photo?' has-photo':'')+'" id="pftAv" data-pf="photo" aria-label="Change your photo" style="'+f.style+'">'
      +     (f.photo ? '<img class="av-img" src="'+f.photo+'" alt="">' : esc(f.label)) + '<span class="ed">'+PF_IC.pen+'</span></button>'
      +   '<div><div class="pft-nm">'+esc(ME.name)+'</div><div class="pft-sub">Admin</div></div>'
      + '</div></div>'
      + (window.RaviloMusic ? RaviloMusic.profileTop() : '')
      + (pftNote ? '<div class="pft" style="margin-top:14px"><div class="pf-note ok">✓ <span>'+esc(pftNote)+'</span></div></div>' : '')
      + '<div class="row"><h3>My List'+(list.length ? '<span style="font-family:Sora,sans-serif;font-size:12px;font-weight:500;color:var(--ink-dim)">'+list.length+'</span><span class="more" data-pf="mylist">See all ›</span>' : '')+'</h3>'
      +   (list.length ? '<div class="track">'+list.map(tile).join('')+'</div>'
                       : '<div class="pft-empty">Nothing here yet. Tap <b>＋ My List</b> on any title and it waits for you here — and on your TV.</div>')
      + '</div>'
      + '<div class="pft"><div class="acct-sec" style="margin-top:26px">Account</div>'
      +   '<div class="pft-grp">'
      +     '<button class="pft-row" data-pf="lang">'+PF_IC.lang+'<span>App language<div class="rsub">'+LANGS[pfLang]+'</div></span>'+PF_IC.chev+'</button>'
      +     (window.RaviloMusic ? RaviloMusic.profileRow() : '')
      +     '<button class="pft-row" data-pf="password">'+PF_IC.key+'<span>Change password</span>'+PF_IC.chev+'</button>'
      +     '<button class="pft-row" data-pf="settings">'+PF_IC.gear+'<span>Settings</span>'+PF_IC.chev+'</button>'
      +   '</div>'
      +   '<button class="pft-out" data-pf="signout">'+PF_IC.out+'Sign out</button>'
      +   '<div class="pft-ver">Ravilo <b>1.38</b> · signed in to ravilo.mit-hus.dk</div>'
      +   '<div class="pft-foot"></div>'
      + '</div>';
    pftNote = null;
  }
  // App language: Ravilo's own words on this phone. Not the audio or subtitle language (those follow
  // the profile), and not the server's — a household can read Danish on one phone and English on another.
  const LANGS = { en:'English', da:'Dansk', fo:'Føroyskt' };
  let pfLang = 'en';
  /* Owner decision 4 (2026-09-27): the phone keeps a Settings row, as built (dev review item 2 of R304):
     the skin and the playback switches live there. */
  const phPrefs = { autoplay:true, progress:true };
  function settingsHTML(){
    const skin = document.documentElement.getAttribute('data-skin') || 'aurora';
    const tog = (k, label) => '<button class="pft-row" data-acct="tog:'+k+'"><span>'+label+'</span><span class="ph-sw'+(phPrefs[k]?' on':'')+'"></span></button>';
    return '<div class="acct-bar"><button data-acct="back-settings">‹</button><h2>Settings</h2></div>'
      + '<div class="pft"><div class="acct-sec">Theme</div>' + themeHTML()
      + '<div class="acct-sec" style="margin-top:22px">Playback</div><div class="pft-grp">'
      +   tog('progress','Show progress on Continue Watching') + tog('autoplay','Autoplay next episode')
      +   (window.RaviloMusic ? RaviloMusic.settingsRows() : '')
      + '</div></div>';
  }
  /* Owner 2026-09-30: a light theme and a dark theme, chosen once; the phone's own light/dark picks between them, live. */
  function themeHTML(){
    const T = window.RaviloTheme, p = T.pref, cur = T.current();
    const chips = (list, on, slot) => '<div class="ph-skins wrap">' + list.map(x => '<button data-acct="th:'+slot+':'+x[0]+'" class="'+(x[0]===on?'on':'')+'"><i class="thsw" data-th="'+x[0]+'"></i>'+x[1]+'</button>').join('') + '</div>';
    const L = T.list.filter(x => T.isLight(x[0])), D = T.list.filter(x => !T.isLight(x[0]));
    return '<div class="pft-grp"><button class="pft-row" data-acct="th:sync"><span>Match the phone’s light and dark<small class="thsub">Switches the moment the phone does</small></span><span class="ph-sw'+(p.sync?' on':'')+'"></span></button></div>'
      + (p.sync
        ? '<div class="thslot"><b>'+(T.osDark()?'':'● ')+'When the phone is light</b>' + chips(L, p.light, 'light') + '</div><div class="thslot"><b>'+(T.osDark()?'● ':'')+'When the phone is dark</b>' + chips(D, p.dark, 'dark') + '</div>'
        : '<div class="thslot">' + chips(T.list, p.single, 'single') + '</div>')
      + '<p class="thsub" style="margin:12px 2px 0">Saved to your profile: every TV, phone and computer you sign in on uses these.</p>';
  }
  window.RaviloTheme.onchange = () => { if (acct.classList.contains('on') && acctInner.querySelector('.thslot')) acctInner.innerHTML = settingsHTML(); };
  function langHTML(){
    return '<div class="acct-bar"><button data-acct="back">‹</button><h2>App language</h2></div>'
      + '<div class="pft" style="padding-top:6px"><div class="pft-grp">'
      + Object.entries(LANGS).map(([k,v])=>'<button class="pft-row" data-lang="'+k+'"><span>'+v+'</span>'
          + (k===pfLang ? '<svg class="chev" viewBox="0 0 24 24" style="stroke:var(--ink)"><path d="M5 12l5 5 9-10"/></svg>' : '')+'</button>').join('')
      + '</div><p class="pft-ver" style="text-align:left">Menus and buttons only. Audio and subtitles keep following your profile’s languages.</p></div>';
  }
  function myListHTML(){
    const list = myList();
    return '<div class="acct-bar"><button data-acct="back">‹</button><h2>My List</h2></div>'
      + '<div style="padding:4px 0 0"><div class="txgrid">'+list.map(tile).join('')+'</div></div>';
  }
  function phToast(msg){
    const ph = document.getElementById('pmSheet').parentNode;
    const t = document.createElement('div'); t.className = 'ph-toast'; t.textContent = msg; ph.appendChild(t);
    setTimeout(()=>t.remove(), 2200);
  }
  function passwordHTML(err){
    return '<div class="acct-bar"><button data-acct="back-settings">‹</button><h2>Change password</h2></div>'
      + '<div class="acct-body">'
      + '<div class="pf-sub" style="max-width:none;margin:0 0 18px;text-align:left;">You’ll need the new one the next time you sign in on any device.</div>'
      + '<div class="fld"><label>Current password</label><input type="password" id="pwCur" autocomplete="current-password"></div>'
      + '<div class="fld"><label>New password</label><input type="password" id="pwNew" autocomplete="new-password"></div>'
      + '<div class="fld"><label>Repeat new password</label><input type="password" id="pwRep" autocomplete="new-password"></div>'
      + (err ? '<div class="pf-note bad">⚠ <span>'+esc(err)+'</span></div>' : '')
      + '<div class="pf-btns"><button class="pf-btn primary" data-acct="pwsave">Save password</button></div>'
      + '</div>';
  }

  function readPhoto(input){
    const file = input && input.files && input.files[0]; if(!file) return;
    const fr = new FileReader();
    fr.onload = ()=>{ if(AV.setPhoto) AV.setPhoto(ME.id, fr.result); acctNote={kind:'ok',text:'Photo updated.'}; acctInner.innerHTML = profileHTML(); paintAvatars(); };
    fr.readAsDataURL(file);
  }
  pmScrim.addEventListener('click', ()=>openSheet(false));
  pmSheet.addEventListener('click', e=>{
    const b = e.target.closest('[data-pm]'); if(!b) return;
    const a = b.dataset.pm; openSheet(false);
    if(a==='signout-go' && window.RaviloMusic) RaviloMusic.signOut();   // sign-out clears the mode
    if(a==='signout-go') phToast('Signed out · the sign-in screen would open here');
  });
  acct.addEventListener('click', e=>{
    const b = e.target.closest('[data-acct]'); if(!b) return;
    const a = b.dataset.acct;
    if(a==='back'){ closeAcct(); if(tab==='profile') renderProfile(); }
    else if(a==='back-settings'){ closeAcct(); if(tab==='profile') renderProfile(); }
    else if(a==='th:sync'){ const T = RaviloTheme; T.set(T.pref.sync ? { sync:false, single:T.current() } : { sync:true }); acctInner.innerHTML = settingsHTML(); }
    else if(a.indexOf('th:')===0){ const [, slot, k] = a.split(':'); RaviloTheme.set({ [slot]: k }); acctInner.innerHTML = settingsHTML(); }
    else if(a.indexOf('tog:')===0){ const k = a.slice(4); phPrefs[k] = !phPrefs[k]; acctInner.innerHTML = settingsHTML(); }
    else if(a==='profile') openAcct(profileHTML());
    else if(a==='password') openAcct(passwordHTML(null));
    else if(a==='choose') document.getElementById('pfFile').click();
    else if(a==='camera') document.getElementById('pfCam').click();
    else if(a==='remove'){ if(AV.clearPhoto) AV.clearPhoto(ME.id); acctNote={kind:'ok',text:'Photo removed — back to your initials.'}; acctInner.innerHTML = profileHTML(); paintAvatars(); }
    else if(a==='pwsave'){
      const cur=document.getElementById('pwCur').value, nw=document.getElementById('pwNew').value, rp=document.getElementById('pwRep').value;
      if(!cur){ acctInner.innerHTML = passwordHTML('Enter your current password.'); return; }
      if(nw.length<6){ acctInner.innerHTML = passwordHTML('Use at least 6 characters.'); return; }
      if(nw!==rp){ acctInner.innerHTML = passwordHTML('The two new passwords don’t match.'); return; }
      if(cur==='wrong'){ acctInner.innerHTML = passwordHTML('That current password isn’t right.'); return; }
      closeAcct(); pftNote = 'Password changed.'; tab = 'profile'; render();
    }
  });
  acct.addEventListener('click', e=>{
    const lg = e.target.closest('[data-lang]');
    if(lg){ pfLang = lg.dataset.lang; if(window.setRaviloLang) setRaviloLang(pfLang); if(window.RaviloMusic) RaviloMusic.onLang(); closeAcct(); pftNote = 'App language: ' + LANGS[pfLang] + '.'; render(); return; }
    const t = e.target.closest('[data-title]'); if(!t || !acct.contains(t)) return;
    closeAcct(); openDetail(t.dataset.title);
  });
  acct.addEventListener('change', e=>{ if(e.target.id==='pfFile'||e.target.id==='pfCam') readPhoto(e.target); });
  acct.addEventListener('dragover', e=>{ const av=e.target.closest('#pfAv'); if(av){ e.preventDefault(); av.classList.add('drop'); } });
  acct.addEventListener('dragleave', e=>{ const av=e.target.closest('#pfAv'); if(av) av.classList.remove('drop'); });
  acct.addEventListener('drop', e=>{
    const av=e.target.closest('#pfAv'); if(!av) return;
    e.preventDefault(); av.classList.remove('drop');
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if(!file || !/^image\//.test(file.type)) return;
    const fr = new FileReader();
    fr.onload = ()=>{ if(AV.setPhoto) AV.setPhoto(ME.id, fr.result); acctNote={kind:'ok',text:'Photo updated.'}; acctInner.innerHTML = profileHTML(); paintAvatars(); };
    fr.readAsDataURL(file);
  });
  paintAvatars();

  /* ============================================================
     R244 — the phone media player (direction 2 · "Thumb rail")
     R245 — casting: the remote ("Now playing"), the mini bar, the cast button
     Both built 2026-09-16 from the round-1 picks. Design reference:
     "Mobile Player - Directions.html" and "Casting - Directions.html".
     Rules held here, not just drawn: 46px targets · 13px type floor ·
     auto-hide 3000ms held open by a sheet or a drag and suspended while
     locked · no delivery method named anywhere (R180) · R218's cold start
     and R237's failure copy reused verbatim · ONE picker component with two
     destinations, local and cast · no playback speed, at all (owner
     decision 2026-09-16 — no control, no sheet, no seam, no string).
     ============================================================ */
  let CAST = null;                 // null | {device}  — playing on a screen, through the backend
  let AP = null;                   // null | {device}  — AirPlay: the picture left, the player stayed
  let TIER2 = false;               // "All your TVs" remembers its open state (FR-R265-3)
  /* R265 · the device list is the SERVER's answer, never a scan: `nearby` is a flag on
     GET /api/remote/devices, so the phone has no discovery code and no spinner. A row is a
     screen (R264's receiver-only TV app) or, on the Android app only, a Chromecast the Cast
     SDK found. `mine` marks a screen playing something this profile started — the only
     playing row that is tappable; someone else's is busy and must never be hijacked (236's 409). */
  const SCREENS = [
    { name:'Stovan',            kind:'tv',   nearby:true,  state:'ready' },
    { name:'Køkið',             kind:'tv',   nearby:true,  state:'playing', title:'Ruffy · S03E12', mine:true },
    { name:'Barnarúmið',        kind:'tv',   nearby:false, state:'busy',    user:'Eydun' },
    { name:'Summarhúsið',       kind:'tv',   nearby:false, state:'offline', when:'Tuesday' },
    { name:'Stovan Chromecast', kind:'cast', nearby:false, state:'ready', androidOnly:true }
  ];
  const PAIR_CODE = 'K7F3Q2';      // what the receiver mockup is showing on its idle screen
  const isIOS = () => document.querySelector('.phone').classList.contains('ios');
  const HIDE_MS = 3000;            // picked over the TV's 3600 — see the directions canvas

  const SUBS = [
    { off:true, nm:'Off' },
    { nm:'Føroyskt', cc:'fo', vers:[{nm:'Føroyskt', sub:'The full version of everything spoken.'},{nm:'Føroyskt', bdg:'Signs only', sub:'Only the on-screen text and foreign lines.'}] },
    { nm:'Dansk', cc:'dk', vers:[{nm:'Dansk', sub:'The full version of everything spoken.'},{nm:'Dansk', bdg:'Sound described', sub:'Adds speaker names and sound-effect notes.'},{nm:'Dansk', bdg:'Signs only', sub:'Only the on-screen text and foreign lines.'}] },
    { nm:'English', cc:'gb', vers:[{nm:'English', sub:'The full version of everything spoken.'},{nm:'English', bdg:'Sound described', sub:'Adds speaker names and sound-effect notes.'},{nm:'English', bdg:'Signs only', sub:'Only the on-screen text and foreign lines.'}] },
    { nm:'Íslenska', cc:'is', vers:[{nm:'Íslenska', sub:'The full version of everything spoken.'}] }
  ];
  const AUDIO = [
    { nm:'Dansk', cc:'dk', vers:[{nm:'Dansk', sub:'Surround'},{nm:'Dansk', sub:'Stereo'}] },
    { nm:'English', cc:'gb', vers:[{nm:'English', sub:'Surround'}] },
    { nm:'Føroyskt', cc:'fo', vers:[{nm:'Føroyskt', sub:'Stereo'}] }
  ];
  const SIZE_LABEL = { s:'Small', m:'Medium', l:'Large' };
  const STILLS = ['assets/bbb-backdrop-landscape.png','assets/bbb-backdrop-rodents.png','assets/bbb-backdrop-opening.png','assets/bbb-backdrop-bunny.png'];

  /* one state object, shared by the local player and the remote — the remote is a
     view of a session, never its own truth. On a real cast it is rebuilt from the
     receiver on app start; here the mockup keeps the same shape so the handover
     between local and cast is the same act in both directions. */
  const S = { title:'', kind:'movie', ep:null, art:STILLS[0], pos:862, dur:2280, playing:true,
              sub:1, ver:0, aud:0, audVer:0, size:'m', locked:false, hidden:false, buffering:false, tab:'subs' };
  let hideTimer = null, tickTimer = null, sheetOpen = null, dragging = false;

  const $ = id => document.getElementById(id);
  const mp = $('mp'), rcRem = $('rcRem'), rcMini = $('rcMini'), rcBar = $('rcBar');
  const fmt = s => { s = Math.max(0, Math.round(s));
    const h = Math.floor(s/3600), m = Math.floor(s%3600/60), x = s%60;
    return (h?String(h).padStart(2,'0')+':':'00:') + String(m).padStart(2,'0') + ':' + String(x).padStart(2,'0'); };
  const ppIcon = on => on
    ? '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="7" y="5" width="3.6" height="14" rx="1.2"/><rect x="13.4" y="5" width="3.6" height="14" rx="1.2"/></svg>'
    : '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M8 5l11 7-11 7z"/></svg>';
  const chev = '<svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>';
  const tick = '<svg viewBox="0 0 24 24"><path d="M4 12.5l5 5L20 6.5"/></svg>';
  const subLabel = () => { const g = SUBS[S.sub]; if(!g || g.off) return 'Off';
    const v = g.vers[S.ver] || g.vers[0]; return v.nm + (v.bdg ? ' · ' + v.bdg : ''); };

  /* ---- shared render ---- */
  /* FR-R267-8 / R278 FR-R278-4 — the mini bar is raised by the nav's height only while the nav is
     on screen. Where the nav is absent (a title, the player, the remote) a raised mini bar would
     float over that page's own content; an account screen keeps the nav, so it keeps the dock. */
  function paintMiniOffset(){
    const pushed = detail.classList.contains('on')
      || mp.classList.contains('on') || rcRem.classList.contains('on');
    rcMini.classList.toggle('abovenav', !pushed);
  }
  function paint(){
    paintMiniOffset();
    const pct = Math.min(100, S.pos / S.dur * 100);
    $('mpDone').style.width = pct + '%'; $('mpKnob').style.left = pct + '%';
    $('mpPos').textContent = fmt(S.pos); $('mpLeft').textContent = '−' + fmt(S.dur - S.pos);
    $('mpPP').innerHTML = ppIcon(S.playing); $('mpPP').setAttribute('aria-label', S.playing ? 'Pause' : 'Play');
    $('rcDone').style.width = pct + '%'; $('rcKnob').style.left = pct + '%';
    $('rcPos').textContent = fmt(S.pos); $('rcLeft').textContent = '−' + fmt(S.dur - S.pos);
    $('rcPP').innerHTML = ppIcon(S.playing); $('rcPP').setAttribute('aria-label', S.playing ? 'Pause' : 'Play');
    $('rcMiniPP').innerHTML = ppIcon(S.playing);
    $('rcMiniBar').style.width = pct + '%';
    rcRem.classList.toggle('paused', !S.playing);
    const failed = !!(CAST && CAST.failed);
    rcRem.classList.toggle('rc-failed', failed); rcRem.classList.toggle('rc-dim', failed);
    $('rcOn').textContent = failed ? CAST.device + ' couldn’t play this'
      : (S.playing ? 'Playing on ' : 'Paused on ') + (CAST ? CAST.device : '—');
    // Skip intro lives in the first 30s; next-up in the last 60. Neither may overlap the rail.
    $('mpSkip').classList.toggle('on', !S.locked && S.pos < 30 && !sheetOpen);
    $('mpSkipCd').textContent = Math.max(0, Math.ceil(30 - S.pos)) + ' s';
    const nup = S.kind === 'series' && S.dur - S.pos <= 60;
    $('mpNup').classList.toggle('on', nup && !S.locked && !sheetOpen);
    if(nup){ $('mpNupCd').textContent = 'Starts in ' + Math.max(0, Math.ceil(S.dur - S.pos)) + ' s · ' + (S.ep ? 'S2 E' + (S.ep + 1) : ''); }
    // the subtitle line only renders when a track is actually chosen (R239 honesty)
    const sb = $('mpSub'), on = S.sub > 0;
    sb.style.display = on ? 'block' : 'none';
    sb.className = 'mp-sub ' + S.size;
    sb.style.top = 'calc(50% + 78px)';
    sb.textContent = on ? 'Eg veit ikki hvat tú meinar.' : '';
  }
  function armHide(){
    clearTimeout(hideTimer);
    if(S.locked || sheetOpen || dragging || !S.playing) return;   // a paused player keeps its chrome
    hideTimer = setTimeout(()=>{ mp.classList.add('hid'); }, HIDE_MS);
  }
  function showChrome(){ mp.classList.remove('hid'); armHide(); }
  function startTick(){
    clearInterval(tickTimer);
    tickTimer = setInterval(()=>{
      if(!S.playing || S.buffering) return;
      if(!mp.classList.contains('on') && !rcRem.classList.contains('on') && !CAST) return;
      S.pos = Math.min(S.dur, S.pos + 1); paint();
    }, 1000);
  }

  /* ---- open / close ---- */
  function openPlayer(title, opts){
    if(window.RaviloMusic) RaviloMusic.onVideoStart();   // starting a video stops the music; switching mode does not
    const it = findItem(title) || {};
    S.title = it.title || title; S.kind = it.kind || 'movie';
    S.ep = S.kind === 'series' ? 7 : null;
    S.art = STILLS[(S.title.length) % STILLS.length];
    S.pos = 862; S.dur = 2280; S.playing = true; S.locked = false; S.buffering = true;
    $('mpArt').src = S.art;
    $('mpKick').textContent = S.kind === 'series' ? 'S02E' + String(S.ep).padStart(2, '0') : (it.year ? String(it.year) : 'Film');
    const logo = (R.artFor ? R.artFor(it).logo : it.logo) || '';
    const ident = $('mpIdent'), lg = $('mpLogo');
    const showName = () => { lg.removeAttribute('src'); $('mpShow').textContent = S.kind === 'series' ? S.title : ''; ident.className = 'mp-ident' + (S.kind === 'series' ? ' name' : ''); };
    if (logo) { lg.onerror = showName; lg.src = logo; lg.alt = S.title; $('mpShow').textContent = ''; ident.className = 'mp-ident logo' + (it.logoInk === 'dark' ? ' plate' : ''); }
    else showName();
    $('mpTitle').textContent = S.kind === 'series' ? 'Threshold Night' : S.title;
    $('mpBufKick').textContent = S.title;
    $('mpBufTitle').textContent = S.kind === 'series' ? 'Threshold Night' : S.title;
    $('mpBufSub').textContent = S.kind === 'series' ? 'Season 2 · Episode ' + S.ep : (it.year ? String(it.year) : '');
    $('mpNupTitle').textContent = 'Bitter Honey';
    const nupK = document.querySelector('#mpNup .k'); if(nupK) nupK.textContent = 'Next episode';
    if(opts && opts.shuffle && S.kind === 'series' && R.episodesFor){   // R343 — whole series, random order
      const pool = []; for(let s = 0; s < mSeasons(it); s++) R.episodesFor(it, s).forEach(e => pool.push({ s, e }));
      for(let i = pool.length - 1; i > 0; i--){ const j = Math.floor(Math.random() * (i + 1)); [pool[i], pool[j]] = [pool[j], pool[i]]; }
      const a = pool[0], b = pool[1], cd = (s, n) => 'S' + String(s + 1).padStart(2, '0') + 'E' + String(n).padStart(2, '0');
      $('mpKick').textContent = 'Shuffle · ' + cd(a.s, a.e.n); $('mpTitle').textContent = a.e.title;
      $('mpBufTitle').textContent = a.e.title; $('mpBufSub').textContent = 'Shuffle · ' + cd(a.s, a.e.n);
      if(b){ $('mpNupTitle').textContent = cd(b.s, b.e.n) + ' · ' + b.e.title; if(nupK) nupK.textContent = 'Next · shuffled'; }
      S.pos = 0; S.dur = (parseInt(a.e.dur) || 11) * 60;
    }
    mp.classList.add('on'); mp.classList.remove('hid', 'lock');
    closeSheet();
    // R218: 400ms debounce, then direction B's cold start. An instant start shows nothing.
    $('mpBuf').classList.remove('on');
    const t = setTimeout(()=>{ if(S.buffering) $('mpBuf').classList.add('on'); }, 400);
    setTimeout(()=>{ clearTimeout(t); S.buffering = false; $('mpBuf').classList.remove('on'); paint(); armHide(); }, 1900);
    paint(); startTick();
  }
  function closePlayer(){ mp.classList.remove('on'); closeSheet(); clearTimeout(hideTimer); }

  /* ---- sheets ---- */
  function openSheetHTML(kind, html){
    sheetOpen = kind; $('mpSheet').innerHTML = html;
    $('mpSheet').classList.add('on'); $('mpScrim').classList.add('on');
    showChrome(); clearTimeout(hideTimer); paint();
  }
  function closeSheet(){ sheetOpen = null; $('mpSheet').classList.remove('on'); $('mpScrim').classList.remove('on'); armHide(); paint(); }
  function langRows(list, sel, kindAttr){
    return list.map((g, i) => {
      if(g.off) return '<button class="mp-row' + (sel === i ? ' on' : '') + '" data-' + kindAttr + '="' + i + '"><span class="nm">Off</span>'
        + (sel === i ? '<span class="rt">' + tick + '</span>' : '') + '</button>';
      return '<button class="mp-row' + (sel === i ? ' on' : '') + '" data-' + kindAttr + '="' + i + '">'
        + '<span class="fi fi-' + g.cc + '"></span><span><span class="nm">' + esc(g.nm) + '</span>'
        + (g.vers.length > 1 ? '<span class="sub">' + g.vers.length + ' versions</span>' : '') + '</span>'
        + '<span class="rt">' + (sel === i ? tick : '') + chev + '</span></button>';
    }).join('');
  }
  function pickerHTML(where){
    const isSubs = S.tab === 'subs';
    return '<div class="mp-grab"></div>'
      + '<div class="mp-sh"><h4>Audio &amp; subtitles</h4><span class="sp"></span><button class="mp-b flat" data-mp="closesheet" aria-label="Close"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>'
      + (where === 'cast' ? '<div class="mp-note" style="padding-top:0;padding-bottom:12px">Applies on <b style="color:var(--ink-soft);font-weight:600">' + esc(CAST ? CAST.device : '—') + '</b></div>' : '')
      + '<div class="mp-seg"><button data-mp="tab-aud"' + (isSubs ? '' : ' class="on"') + '>Audio</button><button data-mp="tab-subs"' + (isSubs ? ' class="on"' : '') + '>Subtitles</button></div>'
      + (isSubs ? langRows(SUBS, S.sub, 'sublang') : langRows(AUDIO, S.aud, 'audlang'))
      + (isSubs ? '<button class="mp-row" data-mp="size"><span><span class="nm">Subtitle size</span></span><span class="rt">' + SIZE_LABEL[S.size] + chev + '</span></button>' : '');
  }
  function versionsHTML(gi){
    const g = SUBS[gi];
    return '<div class="mp-grab"></div>'
      + '<button class="mp-back" data-mp="backpicker">' + '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg> Subtitles</button>'
      + '<div class="mp-sh" style="padding-top:4px"><span class="fi fi-' + g.cc + '" style="width:26px;height:19.5px;border-radius:3px"></span><h4>' + esc(g.nm) + '</h4></div>'
      + g.vers.map((v, i) => '<button class="mp-row' + (S.sub === gi && S.ver === i ? ' on' : '') + '" data-subver="' + gi + ':' + i + '">'
          + '<span><span class="nm">' + esc(v.nm) + (v.bdg ? ' <span class="mp-bdg">' + v.bdg + '</span>' : '') + '</span><span class="sub">' + esc(v.sub) + '</span></span>'
          + (S.sub === gi && S.ver === i ? '<span class="rt">' + tick + '</span>' : '') + '</button>').join('')
      + '<div class="mp-note">' + g.vers.length + ' tracks are tagged ' + esc(g.nm) + ' on this file. Ravilo never picks the signs-only one for you.</div>';
  }
  function sizeHTML(){
    return '<div class="mp-grab"></div>'
      + '<button class="mp-back" data-mp="backpicker"><svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg> Subtitles</button>'
      + '<div class="mp-sh" style="padding-top:4px"><h4>Subtitle size</h4></div>'
      + '<div class="mp-sizes">' + ['s','m','l'].map(k => '<button data-subsize="' + k + '"' + (S.size === k ? ' class="on"' : '') + '>' + k.toUpperCase() + '</button>').join('') + '</div>'
      + '<div class="mp-note">Applies on this phone only. The size you set here is also what a cast uses on the TV.</div>';
  }
  function episodesHTMLSheet(){
    const eps = [[6,'Attila','38 min · watched',100],[7,'Threshold Night','38 min · playing now',38],[8,'Bitter Honey','35 min',0],[9,'Warm Harbor','41 min',0]];
    return '<div class="mp-grab"></div>'
      + '<div class="mp-sh"><h4>Season 2</h4><span class="sp"></span><span style="font-size:13px;color:var(--ink-dim)">10 episodes</span><button class="mp-b flat" data-mp="closesheet" aria-label="Close"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>'
      + eps.map((e, i) => '<button class="mp-ep' + (e[0] === S.ep ? ' on' : '') + '" data-ep="' + e[0] + '">'
          + '<span class="th"><img src="' + STILLS[i % STILLS.length] + '" alt="">' + (e[3] ? '<span class="pb"><i style="width:' + e[3] + '%"></i></span>' : '') + '</span>'
          + '<span><span class="n">' + e[0] + ' · ' + esc(e[1]) + '</span><span class="m">' + esc(e[2]) + '</span></span></button>').join('');
  }
  /* R237 — per-cause copy, reused verbatim. Named no product, protocol or status code. */
  function failHTML(){
    return '<div class="mp-fail"><h4>This phone needs to be signed in again</h4>'
      + '<p>Sign in again to keep watching.</p>'
      + '<div class="acts"><button class="pri" data-mp="closesheet">Sign in</button><button data-mp="leave">Back</button></div></div>';
  }

  /* ---- player events ---- */
  mp.addEventListener('click', e => {
    const b = e.target.closest('button'); const inSheet = e.target.closest('#mpSheet');
    if(b){
      const id = b.id, act = b.dataset.mp;
      if(id === 'mpBack'){ closePlayer(); return; }
      if(id === 'mpPP'){ S.playing = !S.playing; paint(); armHide(); return; }
      if(id === 'mpBack10'){ S.pos = Math.max(0, S.pos - 10); paint(); showChrome(); return; }
      if(id === 'mpFwd30'){ S.pos = Math.min(S.dur, S.pos + 30); paint(); showChrome(); return; }
      if(id === 'mpSubsBtn'){ S.tab = 'subs'; openSheetHTML('picker', pickerHTML('local')); return; }
      if(id === 'mpEpsBtn'){ openSheetHTML('eps', episodesHTMLSheet()); return; }
      if(id === 'mpNextBtn'){ S.ep = (S.ep || 7) + 1; S.pos = 0; $('mpKick').textContent = 'S02E' + String(S.ep).padStart(2, '0'); $('mpTitle').textContent = 'Bitter Honey'; paint(); showChrome(); return; }
      if(id === 'mpLockBtn'){ S.locked = true; mp.classList.add('lock'); clearTimeout(hideTimer); paint(); return; }
      if(id === 'mpUnlock'){ $('mpLockNote').classList.add('on'); setTimeout(()=>$('mpLockNote').classList.remove('on'), 1200); return; }
      if(id === 'mpSkip'){ S.pos = 92; paint(); showChrome(); return; }
      if(id === 'mpNupGo'){ S.ep = (S.ep || 7) + 1; S.pos = 0; $('mpTitle').textContent = 'Bitter Honey'; $('mpKick').textContent = 'S02E' + String(S.ep).padStart(2, '0'); paint(); return; }
      if(id === 'mpNupNo'){ S.dur = S.pos + 600; paint(); return; }
      if(id === 'mpCast'){ openScreensSheet(); return; }
      if(id === 'mpRotate'){ $('mpToast').textContent = 'Rotate to full screen'; $('mpToast').classList.add('on'); setTimeout(()=>$('mpToast').classList.remove('on'), 900); return; }
      if(act === 'closesheet'){ closeSheet(); return; }
      if(act === 'leave'){ closeSheet(); closePlayer(); return; }
      if(act === 'tab-aud'){ S.tab = 'aud'; openSheetHTML('picker', pickerHTML(sheetOpen === 'picker-cast' ? 'cast' : 'local')); return; }
      if(act === 'tab-subs'){ S.tab = 'subs'; openSheetHTML('picker', pickerHTML(sheetOpen === 'picker-cast' ? 'cast' : 'local')); return; }
      if(act === 'size'){ openSheetHTML('size', sizeHTML()); return; }
      if(act === 'backpicker'){ S.tab = 'subs'; openSheetHTML('picker', pickerHTML('local')); return; }
      if(b.dataset.sublang !== undefined){
        const i = +b.dataset.sublang;
        if(SUBS[i].off){ S.sub = 0; S.ver = 0; closeSheet(); }
        else if(SUBS[i].vers.length === 1){ S.sub = i; S.ver = 0; closeSheet(); }
        else openSheetHTML('vers', versionsHTML(i));
        return;
      }
      if(b.dataset.subver !== undefined){ const p = b.dataset.subver.split(':'); S.sub = +p[0]; S.ver = +p[1]; closeSheet(); return; }
      if(b.dataset.audlang !== undefined){ S.aud = +b.dataset.audlang; closeSheet(); return; }
      if(b.dataset.subsize !== undefined){ S.size = b.dataset.subsize; openSheetHTML('size', sizeHTML()); paint(); return; }
      if(b.dataset.ep !== undefined){ S.ep = +b.dataset.ep; S.pos = 0; $('mpKick').textContent = 'S02E' + String(S.ep).padStart(2, '0'); closeSheet(); return; }
      return;
    }
    if(inSheet) return;
    if(e.target.closest('#mpScrim')){ closeSheet(); return; }
  });

  /* Tap and double-tap live on pointerdown, where coordinates are reliable and
     there is one code path for both. FR-R244-4: repeats accumulate (10 → 20 → 30)
     while the ripple is up, and the chrome does not rise on a seek tap.
     The tap window is kept on the element rather than in a closure so it is
     observable when this is debugged on a real device. */
  let ripTimer = null;
  mp.addEventListener('pointerdown', e => {
    if(e.target.closest('button, #mpBar, #mpSheet, #mpScrim, #mpBuf')) return;
    if(S.locked){ $('mpLockNote').classList.add('on'); setTimeout(()=>$('mpLockNote').classList.remove('on'), 1200); return; }
    const r = mp.getBoundingClientRect();
    const cx = e.clientX;
    let x = (r.width && typeof cx === 'number' && isFinite(cx)) ? (cx - r.left) / r.width : .5;
    if(!isFinite(x)) x = .5;
    const side = x < .38 ? 'l' : (x > .62 ? 'r' : null);
    const now = Date.now();
    const prevSide = mp.dataset.tapSide || '';
    const prevAt = +(mp.dataset.tapAt || 0);
    const rip = side === 'l' ? $('mpRipL') : (side === 'r' ? $('mpRipR') : null);
    // in-window if the ripple is still up on this side, or the last tap was
    // recent and on the same side — the ripple test is what makes a third and
    // fourth tap accumulate rather than re-toggle the chrome.
    if(side && rip && (rip.classList.contains('on') || (prevSide === side && now - prevAt < 600))){
      const step = side === 'l' ? -10 : 30;
      const accum = (+(mp.dataset.tapAccum || 0)) + step;
      S.pos = Math.max(0, Math.min(S.dur, S.pos + step));
      mp.dataset.tapSide = side; mp.dataset.tapAt = String(now); mp.dataset.tapAccum = String(accum);
      rip.querySelector('b').textContent = (accum < 0 ? '−' : '+') + Math.abs(accum) + ' s';
      rip.classList.remove('on'); void rip.offsetWidth; rip.classList.add('on');
      clearTimeout(ripTimer);
      ripTimer = setTimeout(()=>{ rip.classList.remove('on'); mp.dataset.tapAccum = '0'; mp.dataset.tapSide = ''; }, 760);
      paint(); return;
    }
    mp.dataset.tapSide = side || ''; mp.dataset.tapAt = String(now); mp.dataset.tapAccum = '0';
    if(mp.classList.contains('hid')) showChrome(); else { clearTimeout(hideTimer); mp.classList.add('hid'); }
  });

  /* drag-to-scrub, on both bars. The bubble rides the thumb; auto-hide is
     suspended for the duration of the drag. */
  /* drag-to-scrub, on both bars. pointermove/pointerup are window-level, so each
     handler must bail unless it owns the live drag — otherwise a drag on the local
     player's bar also runs the remote's handler, whose bar is display:none and
     therefore zero-width, and (clientX-0)/0 sends the position to the end. */
  let dragBar = null;
  function wireBar(bar, onSet){
    const set = e => {
      const r = bar.getBoundingClientRect();
      if(!r.width) return;
      const cx = e.touches ? e.touches[0].clientX : e.clientX;
      if(typeof cx !== 'number' || !isFinite(cx)) return;
      const x = Math.min(1, Math.max(0, (cx - r.left) / r.width));
      S.pos = Math.round(x * S.dur); onSet(x); paint();
    };
    bar.addEventListener('pointerdown', e => {
      dragBar = bar; dragging = true; bar.classList.add('drag'); clearTimeout(hideTimer); set(e); e.preventDefault();
    });
    window.addEventListener('pointermove', e => { if(dragBar === bar) set(e); });
    window.addEventListener('pointerup', () => {
      if(dragBar !== bar) return;
      dragBar = null; dragging = false; bar.classList.remove('drag');
      $('mpBub').classList.remove('on'); armHide();
    });
  }
  wireBar($('mpBar'), x => { const b = $('mpBub'); b.classList.add('on'); b.style.left = (x * 100) + '%'; b.textContent = fmt(S.pos); });
  wireBar($('rcBarSeek'), () => {});

  /* ---- casting → "Play on a TV": one glyph, three tiers (R265) ----------------------
     This replaces the 2026-09-16 frame that drew the PLATFORM's Cast dialog. The brief of
     2026-09-18 supersedes FR-R245-2: the picker is Ravilo's own sheet on every platform, so
     a Tizen TV, a Chromecast and AirPlay are reachable from one place. Rules held here:
       · tier 1 is absent when empty — no empty box, no "searching…", ever;
       · a busy or offline row is dimmed and not tappable;
       · AirPlay is drawn only where the browser reports it (iPhone frame), one step below
         the TV rows, with its notice as a second line — never a tooltip;
       · a Chromecast row exists only in the Android app. */
  const tvGlyph = '<svg viewBox="0 0 24 24" style="width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M9 21h6"/></svg>';
  function screenState(s){
    if(CAST && CAST.device === s.name) return 'Playing ' + esc(S.title || '');
    if(s.state === 'ready')   return 'Ready';
    if(s.state === 'playing') return 'Playing ' + esc(s.title);
    // R270 as built: the name is resolved server-side (now_playing_user); null reads "In use",
    // never a sentence with a hole in it. Last seen is whole calendar days — a weekday inside
    // seven, a date beyond — so "Offline · last seen just now" cannot happen.
    if(s.state === 'busy')    return s.user ? 'Busy · ' + esc(s.user) + ' is watching' : 'In use';
    return 'Offline · last seen ' + esc(s.when);
  }
  function screenRow(s){
    const live = CAST && CAST.device === s.name;
    const off = s.state === 'busy' || s.state === 'offline';
    const tappable = !off;
    return '<button class="mp-row sc-row' + (off ? ' sc-off' : '') + (live ? ' on' : '') + '"'
      + (tappable ? ' data-dev="' + esc(s.name) + '"' : ' disabled aria-disabled="true"') + '>'
      + '<span class="sc-ic">' + (s.kind === 'cast'
          ? '<span class="rc-ic" style="width:20px;height:18px"><span class="bx"></span><span class="fl"></span></span>' : tvGlyph) + '</span>'
      + '<span class="tx"><span class="nm">' + esc(s.name) + '</span>'
      + '<span class="sub' + ((live || (s.state === 'playing' && s.mine)) ? ' sc-play' : '') + '">' + screenState(s) + '</span></span>'
      + (tappable ? '<span class="rt"><svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></span>' : '') + '</button>';
  }
  function screensSheetHTML(extra){
    extra = extra || {};
    const list = SCREENS.filter(s => !(s.androidOnly && isIOS())).filter(s => !(extra.skip && extra.skip.indexOf(s.name) >= 0));
    const near = list.filter(s => s.nearby), rest = list.filter(s => !s.nearby);
    let h = '<div class="mp-grab"></div>'
      + '<div class="mp-sh"><h4>' + (extra.title || 'Play on a TV') + '</h4><span class="sp"></span><button class="mp-b flat" data-mp="closesheet" aria-label="Close"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>';
    // tier 1 — the server's judgement, and absent rather than empty
    if(near.length || extra.rows) h += '<div class="sc-sec">On this network</div>' + (extra.rows || '') + near.map(screenRow).join('');
    // tier 2 — collapsed by default, remembers
    if(rest.length){
      h += '<button class="mp-row sc-col" data-mp="tier2"><span class="nm">All your TVs</span>'
        + '<span class="sc-n">(' + rest.length + ')</span><span class="rt">'
        + '<svg viewBox="0 0 24 24"><path d="' + (TIER2 ? 'M18 15l-6-6-6 6' : 'M6 9l6 6 6-6') + '"/></svg></span></button>';
      if(TIER2) h += rest.map(screenRow).join('');
    }
    // tier 3 — only where the browser reported AirPlay. OWNER PICK 2026-09-18: the FOOTNOTE
    // form, not a full row — one quiet line under the collapsible, with the caveat inside the
    // label itself so the row cannot be read as the recommended route. (The full-row
    // alternative, with the notice as a second line, is drawn in the directions canvas.)
    if(extra.foot) h += extra.foot;
    if(isIOS()){
      h += '<button class="sc-air" data-mp="airplay">'
        + '<span class="sc-ic"><svg viewBox="0 0 24 24" style="width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round"><path d="M5 17a9 9 0 0 1 14 0"/><path d="M8.5 20.5 12 16l3.5 4.5z"/></svg></span>'
        + '<span class="nm">AirPlay (phone must stay on)</span></button>';
    }
    // R360 (main, 2026-10-03): "Add a TV" is gone from the sheet — pairing by code was the Tizen receiver's, and Tizen is paused
    if(extra.after) h += extra.after;
    if(CAST) h += '<button class="mp-row" data-mp="stopcast"><span class="nm" style="color:#ff9b8a">Stop casting</span></button>';
    return h;
  }
  /* Add a TV · FR-R265-5. A phone has a keyboard, so this is a native input — never a
     hand-rolled one (R234 FR-R234-6's rule, from the other direction). Unknown, expired and
     already-used are ONE string: the household's fix is the same for all three. */
  function codeSheetHTML(err){
    return '<div class="mp-grab"></div>'
      + '<div class="mp-sh"><h4>Add a TV</h4><span class="sp"></span><button class="mp-b flat" data-mp="screens" aria-label="Back"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>'
      + '<div class="sc-code"><input id="scCode" class="sc-inp" maxlength="6" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="······" aria-label="Code from the TV">'
      + (err ? '<div class="sc-err">That code didn\'t work</div>'
             : '<div class="sc-hint">The code is on the TV\'s screen.</div>') + '</div>';
  }
  function openScreensSheet(extra){ csSheetOpen(screensSheetHTML(extra)); }
  function openCodeSheet(err){
    csSheetOpen(codeSheetHTML(err));
    const i = $('scCode'); if(i){ i.focus();
      i.addEventListener('input', () => {
        i.value = i.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
        if(i.value.length < 6) return;
        if(i.value === PAIR_CODE){
          // success: the sheet you were in comes back with the TV in place, briefly outlined
          const s = SCREENS.find(x => x.name === 'Stovan'); if(s) s.justPaired = true;
          openScreensSheet();
          const row = $('csSheet').querySelector('[data-dev="Stovan"]');
          if(row){ row.classList.add('sc-new'); setTimeout(()=>row.classList.remove('sc-new'), 2200); }
        } else { openCodeSheet(true); }
      });
    }
  }
  /* AirPlay · FR-R265-4. Tapping hands over to Apple's picker, which we do not redraw — the
     mockup names it and moves on. While it runs there is NO mini bar and NO remote: the
     phone's own player IS the remote, because it drives the <video> the TV is mirroring.
     Anything else would be a drawing of a control that cannot work. The notice the footnote
     compresses is shown in full here, once, at the moment it becomes true. */
  function startAirplay(){
    csSheetClose();
    AP = { device: 'Stovan TV' };
    if(!S.title){ const lib = R.libraryFor ? R.libraryFor(me) : (R.library || []);
      const first = lib[0]; if(first){ S.title = first.title; S.kind = first.kind || 'movie'; S.ep = 7;
        S.art = STILLS[(first.title.length) % STILLS.length]; } }
    if(!mp.classList.contains('on')) openPlayer(S.title);
    mp.classList.add('mp-ap');
    apChip();
    bar('<span class="dot"></span> Playing on ' + esc(AP.device) + ' · keep Ravilo open');
    setTimeout(()=>{ rcBar.classList.remove('on'); }, 2600);
    paintCast();
  }
  function apChip(){
    let c = $('mpApChip');
    if(!AP){ if(c) c.remove(); return; }
    if(!c){ c = document.createElement('div'); c.id = 'mpApChip'; c.className = 'sc-apchip';
      $('mpCast').insertAdjacentElement('beforebegin', c); }
    c.innerHTML = '<span class="rc-ic rc-on" style="width:16px;height:14px"><span class="bx"></span><span class="fl"></span></span>' + esc(AP.device);
  }
  function stopAirplay(){ AP = null; mp.classList.remove('mp-ap'); apChip(); paintCast(); }
  function csSheetOpen(html){ $('csSheet').innerHTML = html; $('csSheet').classList.add('on'); $('csScrim').classList.add('on'); }
  function csSheetClose(){ $('csSheet').classList.remove('on'); $('csScrim').classList.remove('on'); }
  $('csScrim').addEventListener('click', csSheetClose);
  $('csSheet').addEventListener('click', e => {
    const b = e.target.closest('button'); if(!b || b.disabled) return;
    const act = b.dataset.mp;
    if(act === 'closesheet'){ csSheetClose(); return; }
    if(act === 'screens'){ openScreensSheet(); return; }
    if(act === 'tier2'){ TIER2 = !TIER2; openScreensSheet(); return; }
    if(act === 'airplay'){ startAirplay(); return; }
    if(act === 'stopcast'){ csSheetClose(); stopCast(); return; }
    if(b.dataset.dev !== undefined){
      csSheetClose();
      if(AP) stopAirplay();
      // pressing cast inside the player hands the current position over and swaps
      // straight to the remote (decision 6, 2026-09-16)
      const at = mp.classList.contains('on') ? S.pos : null;
      if(mp.classList.contains('on')) closePlayer();
      connect(b.dataset.dev, at); return;
    }
  });
  function bar(html, cls){ rcBar.className = 'rc-bar on'; rcBar.innerHTML = html; if(cls) rcBar.classList.add(cls); }
  /* FR-R265-7 — nothing is "connecting": the phone asks the backend to start a title on a
     screen, so the two moments are "Sending to X…" and "Playing on X", and the bar then
     retires itself. On app start there is no bar at all — either the mini bar with the live
     position, or nothing. */
  function connect(device, handoffPos){
    CAST = { device: device };
    bar('<span class="sp"></span> Sending to ' + esc(device) + '…');
    paintCast();
    setTimeout(()=>{
      bar('<span class="dot"></span> Playing on ' + esc(device));
      setTimeout(()=>{ rcBar.classList.remove('on'); }, 2000);
      if(handoffPos != null) S.pos = handoffPos;
      openRemote();
    }, 1400);
  }
  function stopCast(){
    CAST = null; rcBar.classList.remove('on'); rcRem.classList.remove('on');
    rcMini.classList.remove('on'); paintCast();
  }
  function paintCast(){
    $('castGlyph').className = 'rc-ic' + ((CAST || AP) ? ' rc-on' : '');
    $('rcDevName').textContent = CAST ? CAST.device : '—';
    $('rcMiniDev').textContent = CAST ? CAST.device : '—';
    $('rcMiniTitle').textContent = S.kind === 'series' ? 'Threshold Night' : (S.title || '—');
    $('rcMiniArt').src = S.art; $('rcArtImg').src = S.art;
    $('rcKick').textContent = S.kind === 'series' ? S.title + ' · S2 E' + S.ep : (S.kind === 'movie' && S.title ? 'Film' : (S.title || '—'));
    $('rcTitle').textContent = S.kind === 'series' ? 'Threshold Night' : (S.title || '—');
    // mini bar shows only while casting, and never dismisses while it runs
    rcMini.classList.toggle('on', !!CAST && !CAST.failed && !rcRem.classList.contains('on'));
    // FR-R245-19 — the receiver's stream is a conversion, not the file. Mockup proxy:
    // "is casting" stands in for the real ticket's directPlay==false, since there is no
    // real transcode signal here. Verbatim copy from the built Kotlin (CastRemoteScreen.kt).
    $('rcConv').style.display = CAST && !CAST.failed ? '' : 'none';
    if (CAST) {
      $('rcConvDev').textContent = CAST.device;
      $('rcConvDev2').textContent = CAST.device;
      $('rcConvBody').textContent = CAST.device + " can't play this file as it is, so your server is converting it while it plays. The picture is a step below the original. For the best quality, play it in the Ravilo app on the TV.";
    }
    paint();
  }
  function openRemote(){
    if(!CAST) return;
    closePlayer();
    rcRem.classList.add('on'); rcMini.classList.remove('on');
    $('rcNext').style.display = S.kind === 'series' ? '' : 'none';
    paintCast();
  }
  function closeRemote(){ rcRem.classList.remove('on'); paintCast(); }
  $('rcConv').addEventListener('click', () => { $('rcConvScrim').classList.add('on'); });
  $('rcConvScrim').addEventListener('click', () => { $('rcConvScrim').classList.remove('on'); });

  $('castBtn').addEventListener('click', ()=>{
    if(CAST){ openRemote(); return; }
    if(!S.title){
      const lib = R.libraryFor ? R.libraryFor(me) : (R.library || []);
      const first = (lib || []).find(i => i && i.title);
      if(first){ S.title = first.title; S.kind = first.kind || 'movie'; S.ep = 7; S.art = STILLS[(first.title.length) % STILLS.length]; }
    }
    openScreensSheet();
  });
  mp.addEventListener('click', e => {
    if(e.target.closest('[data-mp="stopcast"]')){ closeSheet(); stopCast(); return; }
  });
  rcMini.addEventListener('click', e => {
    if(e.target.closest('#rcMiniPP')){ S.playing = !S.playing; paint(); return; }
    openRemote();
  });
  rcRem.addEventListener('click', e => {
    const b = e.target.closest('button'); if(!b) return;
    const id = b.id, act = b.dataset.mp;
    if(id === 'rcClose'){ closeRemote(); return; }
    if(id === 'rcPP'){ S.playing = !S.playing; paint(); return; }
    if(id === 'rcBack10'){ S.pos = Math.max(0, S.pos - 10); paint(); return; }
    if(id === 'rcFwd30'){ S.pos = Math.min(S.dur, S.pos + 30); paint(); return; }
    if(id === 'rcStop' || id === 'rcFailStop'){ stopCast(); return; }
    if(id === 'rcPlayHere'){ const t = S.title || 'Big Buck Bunny'; stopCast(); S.pos = 0; openPlayer(t); return; }
    if(id === 'rcDev'){ rcSheetOpen(screensSheetHTML()); return; }
    if(id === 'rcNext'){ S.ep = (S.ep || 7) + 1; S.pos = 0; paintCast(); return; }
    if(id === 'rcSubs'){ S.tab = 'subs'; rcSheetOpen(pickerHTML('cast')); return; }
    if(act === 'closesheet'){ rcSheetClose(); return; }
    if(act === 'tab-aud'){ S.tab = 'aud'; rcSheetOpen(pickerHTML('cast')); return; }
    if(act === 'tab-subs'){ S.tab = 'subs'; rcSheetOpen(pickerHTML('cast')); return; }
    if(act === 'size'){ rcSheetOpen(sizeHTML()); return; }
    if(act === 'backpicker'){ S.tab = 'subs'; rcSheetOpen(pickerHTML('cast')); return; }
    if(act === 'stopcast'){ rcSheetClose(); stopCast(); return; }
    if(b.dataset.sublang !== undefined){
      const i = +b.dataset.sublang;
      if(SUBS[i].off){ S.sub = 0; S.ver = 0; rcSheetClose(); }
      else if(SUBS[i].vers.length === 1){ S.sub = i; S.ver = 0; rcSheetClose(); }
      else rcSheetOpen(versionsHTML(i));
      return;
    }
    if(b.dataset.subver !== undefined){ const p = b.dataset.subver.split(':'); S.sub = +p[0]; S.ver = +p[1]; rcSheetClose(); return; }
    if(b.dataset.audlang !== undefined){ S.aud = +b.dataset.audlang; rcSheetClose(); return; }
    if(b.dataset.subsize !== undefined){ S.size = b.dataset.subsize; rcSheetOpen(sizeHTML()); return; }
    if(b.dataset.dev !== undefined){ CAST = { device: b.dataset.dev }; rcSheetClose(); paintCast(); return; }
  });
  function rcSheetOpen(html){ $('rcSheet').innerHTML = html; $('rcSheet').classList.add('on'); $('rcScrim').classList.add('on'); }
  function rcSheetClose(){ $('rcSheet').classList.remove('on'); $('rcScrim').classList.remove('on'); }
  $('rcScrim').addEventListener('click', rcSheetClose);

  /* Play on a detail screen: casts if connected, plays locally if not. Either
     way the position goes with it — the handover is one act, not two. */
  dscroll.addEventListener('click', e => {
    const p = e.target.closest('[data-mplay]'); if(!p) return;
    e.stopPropagation();
    const title = p.dataset.mplay;
    if(CAST){ const it = findItem(title) || {}; S.title = it.title || title; S.kind = it.kind || 'movie'; S.ep = 7;
      S.art = STILLS[(S.title.length) % STILLS.length]; S.pos = 862; S.playing = true; closeDetail(); openRemote(); return; }
    closeDetail(); openPlayer(title);
  });
  /* mockup-only preview control (fenced in the markup): drives the two states a
     mockup cannot otherwise reach — R237's failed start, and FR-R244-7's rotate
     button, which the product shows ONLY when the system has portrait locked. */
  let portraitLocked = false;
  document.getElementById('prevPick').addEventListener('click', e => {
    const b = e.target.closest('button[data-pv]'); if(!b) return;
    if(b.dataset.pv === 'nocast'){
      const ph = document.querySelector('.phone');
      if(!ph.classList.contains('nocast') && (CAST || (window.RaviloSpeakers && document.querySelector('.mu-ndev')))){ phToast('A cast is on — the icon stays until it ends (FR-R360-2)'); return; }
      b.classList.toggle('on', ph.classList.toggle('nocast')); return;
    }
    if(b.dataset.pv === 'appearance'){ const T = RaviloTheme; T.sim = T.osDark() ? 'light' : 'dark'; T.apply(); b.classList.toggle('on', T.sim === 'light'); return; }
    if(b.dataset.pv === 'fail'){
      if(!mp.classList.contains('on')) openPlayer(S.title || 'Big Buck Bunny');
      S.buffering = false; $('mpBuf').classList.remove('on');
      openSheetHTML('fail', failHTML());
      return;
    }
    if(b.dataset.pv === 'castfail'){
      if(!CAST) CAST = { device:'Soveværelse TV' };
      if(!S.title){ S.title = 'Big Buck Bunny'; S.kind = 'movie'; S.ep = null; S.art = STILLS[S.title.length % STILLS.length]; S.pos = 0; S.dur = 2280; }
      S.playing = false;
      CAST.failed = true; rcBar.classList.remove('on'); openRemote();
      return;
    }
    if(b.dataset.pv === 'portraitlock'){
      portraitLocked = !portraitLocked;
      b.classList.toggle('on', portraitLocked);
      $('mpRotate').style.display = portraitLocked ? '' : 'none';
    }
  });

  paintCast(); startTick();

  /* the music mode's hooks (ravilo-music*.js) — the host owns tab, render and the page chrome */
  window.RaviloHost = {
    get tab(){ return tab; }, setTab(tb){ tab = tb; }, render, paintNav, app, scroll, esc, phToast, closeAcct, openScreensSheet: x => openScreensSheet(x), closeScreensSheet: () => csSheetClose(),
    openPlayer: tl => { closeDetail(); openPlayer(tl); }, openSignOut: () => openSheet(true),
    /* the one Ravilo file's state hand-off (mobile/ravilo-phone-state.js) */
    get libKind(){ return libKind; }, setLibKind(k){ libKind = k; libOpen = false; },
    get detTitle(){ return detail.classList.contains('on') ? detTitle : null; },
    get acctWhat(){ return acct.classList.contains('on') ? acctWhat : null; },
    get srchQ(){ return srchQ; }, setSrchQ(v){ srchQ = v; },
    openDetail, closeDetail, openMyList: () => openAcct(myListHTML(), 'mylist'),
    knows: title => [].concat(R.hero, ...R.rows.map(r=>r.items), ...R.discover.lists.map(l=>l.items)).some(x => x && x.title === title),
  };

  render();
})();
