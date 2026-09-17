/* Row Sorting — Directions: demo data + honest sorting so every frame shows a real order. */
(function(){
  const T=(t,y,a,k,n,s)=>({t,y,a,k,n,s:s||t});
  // a = days since added (smaller = newer). n = network/tag for the collection-scope frame.
  const TITLES=[
    T('Fjollerne',2005,2,'series','dk'), T('Borgen',2010,40,'series','dk'), T('The Patriarch',1972,120,'movie','us','Godfather'),
    T('Offboarding',2022,5,'series','us'), T('Trom',2022,9,'series','fo'), T('Riget',1994,300,'series','dk'),
    T('Badehotellet',2013,60,'series','dk'), T('Oppenheimer',2023,15,'movie','us'), T('Succession',2018,210,'series','us'),
    T('Chernobyl',2019,180,'series','us'), T('Dune',2021,30,'movie','us'), T('Arven',2014,75,'series','dk'),
    T('The Bear',2022,12,'series','us','Bear'), T('Tunnelen',2011,400,'series','dk'), T('Parasite',2019,95,'movie','kr'),
    T('Efterforskningen',2007,250,'series','dk'), T('Anatomy of a Fall',2023,22,'movie','fr'), T('Herrens Veje',2017,130,'series','dk'),
    T('Kongekabale',2004,330,'movie','dk'), T('Druk',2020,50,'movie','dk'), T('Skam',2015,160,'series','no'), T('A Real Pain',2024,1,'movie','us','Real Pain')
  ];
  const byTitle=(x,y)=>x.s.localeCompare(y.s,'da');
  const SORTS={
    added:{ label:'Date added', dir:['Newest first','Oldest first'], fn:(x,y)=>(x.a-y.a)||byTitle(x,y) },
    title:{ label:'Title',      dir:['A → Z','Z → A'],               fn:byTitle },
    year: { label:'Release year', dir:['Newest release first','Oldest release first'], fn:(x,y)=>(y.y-x.y)||byTitle(x,y) }
  };
  function sortBy(list,key,rev){ const s=list.slice().sort(SORTS[key].fn); return rev?s.reverse():s; }
  function hue(s){ let h=0; for(let i=0;i<s.length;i++) h=(h*31+s.charCodeAt(i))%360; return h; }
  function grad(s){ const h=hue(s); return `linear-gradient(150deg, hsl(${h} 46% 36%), hsl(${(h+40)%360} 52% 14%))`; }
  const byName=n=>TITLES.find(x=>x.t===n);
  function poster(t,o){ o=o||{}; return `<div class="cf-mp ${o.cls||''}" title="${t.t} · ${t.y}" style="background:${grad(t.t)};${o.w?`width:${o.w}px;`:''}">${o.rank?`<span class="rk">${o.rank}</span>`:''}${o.stale?`<span class="stale">!</span>`:''}<span class="t">${t.t}</span></div>`; }
  function pinned(names,pool){ return names.map(byName).filter(t=>!pool||pool.includes(t)); }
  function rest(pins,pool,key,rev){ return sortBy(pool.filter(t=>!pins.includes(t)),key,rev); }
  const $=id=>document.getElementById(id);

  /* Frame A — editor side panel, Title A→Z */
  (function(){
    const el=$('fa-side'); if(!el) return;
    const s=sortBy(TITLES,'title');
    el.innerHTML=s.slice(0,12).map((t,i)=>poster(t,{rank:i+1})).join('');
    $('fa-count').textContent=TITLES.length;
    $('fa-more').textContent='+'+(TITLES.length-12)+' more · the TV shows the first 30 in this order, See all shows every one';
  })();

  const PINS=['Trom','Fjollerne','Offboarding','Badehotellet','Borgen'];

  /* Direction 1 — pinned list */
  (function(){
    const el=$('d1-list'); if(!el) return;
    const p=pinned(PINS);
    el.innerHTML=p.map((t,i)=>`<div class="pl-item"><span class="grab">⠿</span><span class="pl-n">${i+1}</span>${poster(t,{w:26})}<span class="pl-t"><b>${t.t}</b><span class="muted"> · ${t.y}</span></span><button type="button" class="cf-herorm" title="Release">✕</button></div>`).join('');
    const r=rest(p,TITLES,'title');
    $('d1-sugg').innerHTML=r.filter(t=>/^(dr|du|ba)/i.test(t.t)).slice(0,3).map(t=>`<div class="pl-sugg">${poster(t,{w:22})}<span>${t.t} <span class="muted">· ${t.y}</span></span><span class="spacer"></span><span class="tiny muted">pin as #${p.length+1}</span></div>`).join('');
    $('d1-rest').innerHTML=r.slice(0,6).map(t=>poster(t,{cls:'auto',w:44})).join('')+`<span class="tiny muted" style="align-self:center;margin-left:4px;">+${r.length-6} more, A → Z</span>`;
  })();

  /* Direction 2 — arrange the row itself */
  function strip(el,pins,pool,key,rev,opts){
    opts=opts||{};
    const r=rest(pins,pool,key,rev);
    const seamLabel=opts.seam||('then '+SORTS[key].label.toLowerCase()+' · '+SORTS[key].dir[rev?1:0].toLowerCase());
    el.innerHTML=`<div class="zone pins">${pins.map((t,i)=>poster(t,{rank:i+1,stale:opts.stale&&opts.stale.includes(t.t),w:opts.w})).join('')||`<div class="tiny muted" style="padding:8px 6px;width:120px;line-height:1.5;">Drag a title here to hand-pick it</div>`}</div><div class="seam"><span>${seamLabel}</span></div><div class="zone autoz">${r.slice(0,opts.n||8).map(t=>poster(t,{cls:'auto',w:opts.w})).join('')}<span class="more">+${Math.max(0,r.length-(opts.n||8))}</span></div>`;
  }
  (function(){
    const el=$('d2-strip'); if(!el) return;
    strip(el,pinned(PINS),TITLES,'title',false,{n:4});
  })();

  /* Direction 3 — rank on the grid */
  (function(){
    const el=$('d3-grid'); if(!el) return;
    const p=pinned(PINS);
    const r=rest(p,TITLES,'title');
    el.innerHTML=p.map((t,i)=>poster(t,{rank:i+1})).join('')+r.slice(0,11).map(t=>poster(t,{cls:'auto'})).join('');
  })();

  /* State — a hand-picked title that no longer matches (Include switched to Series; Druk is a film) */
  (function(){
    const el=$('s1-strip'); if(!el) return;
    const pins=pinned(['Trom','Fjollerne','Druk','Badehotellet']);
    const pool=TITLES.filter(t=>t.k==='series');
    strip(el,pins,pool,'title',false,{stale:['Druk'],n:4,w:52});
  })();

  /* State — the same row rendered inside the “Dansk TV” collection: pins outside the collection drop out */
  (function(){
    const el=$('s2-strip'); if(!el) return;
    const pool=TITLES.filter(t=>t.n==='dk');
    strip(el,pinned(PINS,pool),pool,'title',false,{n:5,w:52});
    $('s2-home').innerHTML=pinned(PINS).map((t,i)=>poster(t,{rank:i+1,w:40,cls:pool.includes(t)?'':'dropped'})).join('');
  })();

  /* TV row — what the viewer sees: an order, never a reason */
  (function(){
    const el=$('tv-strip'); if(!el) return;
    const p=pinned(PINS), r=rest(p,TITLES,'title');
    const all=p.concat(r).slice(0,8);
    el.innerHTML=all.map((t,i)=>`<div class="tvp ${i===1?'foc':''}" style="background:${grad(t.t)};"><span>${t.t}</span></div>`).join('')+`<div class="tvp seeall"><span>See all<br><b>${TITLES.length}</b></span></div>`;
  })();
})();
