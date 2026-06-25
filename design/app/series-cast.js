/* Cast & crew (Phase 76) — scope-aware Series / Season / Episode; write-through to DB.
   Series main cast = TMDB aggregate_credits; episodes inherit it + add guest stars & crew. */
(function () {
  const PAL = ['#7b6ef0','#2dd49a','#f5b542','#3fb6f5','#e36588','#5b8def','#19d6c6','#b15cd0'];
  const colorFor = n => PAL[[...n].reduce((a,c)=>a+c.charCodeAt(0),0) % PAL.length];
  const initials = n => n.split(/\s+/).filter(Boolean).map(s=>s[0]).slice(0,2).join('').toUpperCase();
  const grad = n => 'linear-gradient(150deg,'+colorFor(n)+',rgba(0,0,0,.42))';
  const esc = s => (s==null?'':String(s)).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
  const q = id => document.getElementById(id);
  const pad = n => String(n).padStart(2,'0');
  function toast(msg){ const t=document.createElement('div'); t.className='toast'; t.textContent=msg; document.body.appendChild(t); setTimeout(()=>t.remove(),1800); }

  const SEASONS = [{ n:1, episodes:8 }];   // Nordvest — 1 season, 8 episodes
  let mainCast = [
    { name:'Sofie Gråbøl', role:'Hanne Vinter', eps:{1:[1,2,3,4,5,6,7,8]} },
    { name:'Lars Mikkelsen', role:'Detective Holm', eps:{1:[1,2,3,4,5,6,7,8]} },
    { name:'Trine Dyrholm', role:'Eva Lund', eps:{1:[1,3,4,6,7,8]} },
  ];
  let crew = [
    { dept:'Directing', job:'Director', name:'Tobias Lindholm' },
    { dept:'Writing', job:'Writer', name:'Tobias Lindholm' },
    { dept:'Production', job:'Producer', name:'Birgitte Hald' },
    { dept:'Sound', job:'Composer', name:'Mikkel Maltha' },
    { dept:'Camera', job:'Cinematographer', name:'Magnus Nordenhof Jønck' },
  ];
  let guests = { 1: { 3:[{name:'Pilou Asbæk',role:'Karl Berg'},{name:'Paprika Steen',role:'Dr. Holm'}], 4:[{name:'Pilou Asbæk',role:'Karl Berg'}], 7:[{name:'Paprika Steen',role:'Dr. Holm'}] } };
  let epCrew = { 1: { 3:[{job:'Director',name:'Charlotte Bruus'},{job:'Writer',name:'Tobias Lindholm'}] } };

  function epCrewOf(s,e){ return (epCrew[s] && epCrew[s][e]) || [{job:'Director',name:'Tobias Lindholm'},{job:'Writer',name:'Tobias Lindholm'}]; }
  function guestsOf(s,e){ return (guests[s] && guests[s][e]) || []; }
  function totalEps(p){ return Object.values(p.eps||{}).reduce((a,arr)=>a+arr.length,0); }
  function inEp(p,s,e){ return (p.eps[s]||[]).includes(e); }
  function guestSeasonCount(name,s){ const m=guests[s]||{}; return Object.keys(m).filter(e=>m[e].some(g=>g.name===name)).length; }
  function ensureEpCrew(){ epCrew[selSeason]=epCrew[selSeason]||{}; if(!epCrew[selSeason][selEp]) epCrew[selSeason][selEp]=epCrewOf(selSeason,selEp).map(c=>({job:c.job,name:c.name})); }

  const body = q('cast-body'), scopeSw = q('cast-scope');
  if (!body) return;
  let scope='series', selSeason=1, selEp=1, matrixView='all';

  /* ---------- card builders ---------- */
  function castCard(p,i,o){ o=o||{};
    return '<div class="person'+(o.inh?' inh':'')+'" '+(o.drag?'draggable="true"':'')+' data-i="'+i+'">'
      + (o.epb?'<span class="epb">▸ '+totalEps(p)+' eps</span>':'')
      + (o.inh?'<span class="tag">⤓ inherited</span>':'')
      + (o.guest?'<span class="gtag">guest</span>':'')
      + (o.rm?'<button class="prm" data-rm="'+i+'">✕</button>':'')
      + '<div class="ph" style="background:'+grad(p.name)+';color:#fff;">'+initials(p.name)+'</div>'
      + '<div class="pbody"><div class="pname">'+esc(p.name)+'</div><div class="prole"'+(o.editRole?' data-role="'+i+'"':'')+'>'+(p.role?esc(p.role):'<span class="muted">＋ role</span>')+'</div></div>'
      + '</div>';
  }
  function crewSeriesHTML(){
    const byDept={}; crew.forEach((c,i)=>{(byDept[c.dept||'Crew']=byDept[c.dept||'Crew']||[]).push(Object.assign({i},c));});
    return Object.keys(byDept).map(d=>'<div class="crew-dept"><h5>'+esc(d)+'</h5>'+byDept[d].map(c=>
      '<div class="crew-row"><span class="crew-av" style="background:'+grad(c.name)+';">'+initials(c.name)+'</span><div><div class="crew-name">'+esc(c.name)+'</div><div class="crew-job">'+esc(c.job)+'</div></div><span class="crew-rm" data-crewrm="'+c.i+'">✕</span></div>'
    ).join('')+'</div>').join('') || '<div class="tiny muted">No crew yet.</div>';
  }
  function epCrewHTML(list){
    return list.map((c,i)=>'<div class="crew-row"><span class="crew-av" style="background:'+grad(c.name)+';">'+initials(c.name)+'</span><div><div class="crew-name">'+esc(c.name)+'</div><div class="crew-job">'+esc(c.job)+'</div></div><span class="crew-rm" data-epcrewrm="'+i+'">✕</span></div>').join('') || '<div class="tiny muted">No episode crew.</div>';
  }

  /* ---------- SERIES scope ---------- */
  function renderSeries(){
    body.innerHTML =
      '<div class="card" style="margin-bottom:16px;">'
      + '<div class="row center"><h4 style="margin:0;">Cast</h4><span class="badge info" style="margin-left:7px;">'+mainCast.length+'</span><span class="spacer"></span><span class="tiny muted">drag to reorder · ▸ eps from TMDB · written to tvshow.nfo</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + '<div class="cast-grid">'+mainCast.map((p,i)=>castCard(p,i,{epb:true,rm:true,editRole:true,drag:true})).join('')+'<div class="person add" id="mc-add">＋ Add cast</div></div>'
      + '</div>'
      + '<div class="note blue" style="display:flex;gap:10px;align-items:flex-start;padding:11px 13px;margin-bottom:16px;">'
      + '<span style="flex:none;">ℹ</span>'
      + '<div class="tiny" style="line-height:1.55;"><b>“Main cast” comes straight from TMDB.</b> It’s <span class="mono">aggregate_credits</span> in TMDB’s billing <span class="mono">order</span>; the <span class="mono">▸ N eps</span> badge is each role’s <span class="mono">total_episode_count</span>. Written verbatim to <span class="mono">tvshow.nfo</span> and inherited by every episode. Reorder / edit a role / add / remove freely — manual edits are preserved across re-fetches (like JS tags).</div>'
      + '</div>'
      + '<div class="card">'
      + '<div class="row center"><h4 style="margin:0;">Crew</h4><span class="badge info" style="margin-left:7px;">'+crew.length+'</span><span class="spacer"></span><span class="btn sm ghost" id="crew-add">＋ Add crew</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + crewSeriesHTML()
      + '</div>';
  }

  /* ---------- SEASON scope — presence matrix ---------- */
  function guestNameList(){
    const out=[]; Object.keys(guests).forEach(s=>Object.keys(guests[s]).forEach(e=>guests[s][e].forEach(g=>{ if(!out.find(x=>x.name===g.name)) out.push({name:g.name, role:g.role}); }))); return out;
  }
  function renderSeason(){
    const guestNames = guestNameList();
    let table;
    if (matrixView==='all'){
      const cols = SEASONS.map(s=>'<th>S'+s.n+'</th>').join('')+'<th>Total</th>';
      const rowFor = (p,isGuest)=>{
        const cells = SEASONS.map(s=>{ const c = isGuest?guestSeasonCount(p.name,s.n):(p.eps[s.n]||[]).length; return '<td>'+(c?'<span class="ndot '+(isGuest?'g':'on')+'">'+c+'</span>':'<span class="ndot off">·</span>')+'</td>'; }).join('');
        const total = isGuest?Object.keys(guests).reduce((a,s)=>a+guestSeasonCount(p.name,+s),0):totalEps(p);
        return '<td class="name">'+esc(p.name)+' <span class="r">· '+esc(p.role)+(isGuest?' (guest)':'')+'</span></td>'+cells+'<td class="mono muted">'+total+'</td>';
      };
      table = '<table class="matrix"><thead><tr><th class="name" style="text-align:left;">Actor</th>'+cols+'</tr></thead><tbody>'
        + mainCast.map(p=>'<tr>'+rowFor(p,false)+'</tr>').join('')
        + guestNames.map(p=>'<tr>'+rowFor(p,true)+'</tr>').join('')
        + '</tbody></table>';
    } else {
      const sn=+matrixView, eps=SEASONS.find(s=>s.n===sn).episodes;
      const head = Array.from({length:eps},(_,k)=>'<th>E'+(k+1)+'</th>').join('');
      const mrow = p=>{ const idx=mainCast.indexOf(p); return '<td class="name">'+esc(p.name)+' <span class="r">· '+esc(p.role)+'</span></td>'+Array.from({length:eps},(_,k)=>{ const e=k+1; return '<td class="cell" data-cell="main:'+idx+':'+sn+':'+e+'"><span class="dot '+(inEp(p,sn,e)?'on':'off')+'"></span></td>'; }).join(''); };
      const grow = p=>'<td class="name">'+esc(p.name)+' <span class="r">· '+esc(p.role)+' (guest)</span></td>'+Array.from({length:eps},(_,k)=>{ const e=k+1; const on=guestsOf(sn,e).some(g=>g.name===p.name); return '<td class="cell" data-cell="guest:'+esc(p.name)+':'+sn+':'+e+'"><span class="dot '+(on?'g':'off')+'"></span></td>'; }).join('');
      table = '<table class="matrix"><thead><tr><th class="name" style="text-align:left;">Actor</th>'+head+'</tr></thead><tbody>'
        + mainCast.map(p=>'<tr>'+mrow(p)+'</tr>').join('')
        + guestNames.map(p=>'<tr>'+grow(p)+'</tr>').join('')
        + '</tbody></table>';
    }
    body.innerHTML = '<div class="card">'
      + '<div class="row center" style="gap:12px;flex-wrap:wrap;"><h4 style="margin:0;">Presence matrix</h4>'
      + '<div class="seg-pill" id="mview"><button data-mview="all" class="'+(matrixView==='all'?'on':'')+'">All seasons</button>'+SEASONS.map(s=>'<button data-mview="'+s.n+'" class="'+(matrixView===String(s.n)?'on':'')+'">S'+s.n+'</button>').join('')+'</div>'
      + '<span class="spacer"></span><span class="tiny muted" style="font-size:.72rem;">'+(matrixView==='all'?'number = episodes that season':'tap a cell to toggle · writes episodedetails.nfo')+'</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + '<div style="overflow-x:auto;">'+table+'</div>'
      + '<div class="mlegend"><span><span class="dot on"></span>recurring</span><span><span class="dot g"></span>guest star</span>'+(matrixView==='all'?'<span>number = episodes in that season</span>':'<span><span class="dot off"></span>not in episode</span>')+'</div>'
      + '</div>';
  }

  /* ---------- EPISODE scope ---------- */
  function renderEpisode(){
    const sn=selSeason, e=selEp, season=SEASONS.find(s=>s.n===sn);
    const present = mainCast.filter(p=>inEp(p,sn,e));
    const gs = guestsOf(sn,e);
    const ec = epCrewOf(sn,e);
    body.innerHTML =
      '<div class="row center" style="gap:10px;margin-bottom:16px;flex-wrap:wrap;">'
      + '<span class="tiny muted">Episode</span>'
      + '<div class="eppick" id="eppick">'+Array.from({length:season.episodes},(_,k)=>'<button data-ep="'+(k+1)+'" class="'+(k+1===e?'on':'')+'">E'+(k+1)+'</button>').join('')+'</div>'
      + '<span class="spacer"></span><span class="btn sm" id="ep-fetch">↻ Fetch this episode</span>'
      + '</div>'
      + '<div class="card" style="margin-bottom:16px;">'
      + '<div class="row center"><h4 style="margin:0;">Main cast</h4><span class="tiny muted" style="margin-left:8px;">inherited from series</span><span class="spacer"></span><span class="tiny muted">tvshow.nfo → episode</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + '<div class="cast-grid">'+(present.length?present.map((p,i)=>castCard(p,i,{inh:true})).join(''):'<div class="tiny muted">No main-cast members tagged for this episode — toggle them in the Season matrix.</div>')+'</div>'
      + '<div class="tiny muted" style="margin-top:9px;font-size:.72rem;">Inherited members are read-only here — edit them on the <b>Series</b> scope.</div>'
      + '</div>'
      + '<div class="card" style="margin-bottom:16px;">'
      + '<div class="row center"><h4 style="margin:0;">Guest stars</h4><span class="tiny muted" style="margin-left:8px;">this episode only</span><span class="spacer"></span><span class="tiny muted">S'+pad(sn)+'E'+pad(e)+'.nfo</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + '<div class="cast-grid">'+gs.map((p,i)=>castCard(p,i,{guest:true,rm:true,editRole:true})).join('')+'<div class="person add" id="guest-add">＋ Add guest star</div></div>'
      + '</div>'
      + '<div class="card">'
      + '<div class="row center"><h4 style="margin:0;">Episode crew</h4><span class="spacer"></span><span class="tiny muted">S'+pad(sn)+'E'+pad(e)+'.nfo · &lt;director&gt;/&lt;writer&gt;</span></div>'
      + '<hr class="dash" style="margin:10px 0 14px;">'
      + epCrewHTML(ec)
      + '<div style="margin-top:10px;"><span class="btn sm ghost" id="epcrew-add">＋ Add crew</span></div>'
      + '</div>';
  }

  function render(){
    scopeSw.querySelectorAll('button').forEach(b=>b.classList.toggle('on', b.dataset.scope===scope));
    if (scope==='series') renderSeries();
    else if (scope==='season') renderSeason();
    else renderEpisode();
  }

  /* ---------- interactions ---------- */
  function editRole(i, el){
    const target = scope==='series' ? mainCast[i] : guestsOf(selSeason,selEp)[i];
    if (!target) return;
    const cur = target.role||'';
    el.innerHTML = '<input class="prole-input input" value="'+esc(cur).replace(/"/g,'&quot;')+'" placeholder="Character">';
    const inp = el.querySelector('input'); inp.focus(); inp.select(); let done=false;
    const commit = ()=>{ if(done) return; done=true; target.role=inp.value.trim(); if(target.role) toast(target.name+' · role saved to library'); render(); };
    inp.addEventListener('blur', commit);
    inp.addEventListener('keydown', ev=>{ if(ev.key==='Enter') inp.blur(); else if(ev.key==='Escape'){ done=true; render(); } });
  }
  function toggleCell(key){
    const parts = key.split(':'), kind=parts[0], sn=+parts[parts.length-2], ep=+parts[parts.length-1];
    if (kind==='main'){ const p=mainCast[+parts[1]]; if(!p) return; p.eps[sn]=p.eps[sn]||[]; const idx=p.eps[sn].indexOf(ep);
      if(idx>=0){ p.eps[sn].splice(idx,1); toast(p.name+' removed from S'+pad(sn)+'E'+pad(ep)); } else { p.eps[sn].push(ep); toast(p.name+' added to S'+pad(sn)+'E'+pad(ep)); } }
    else { const name=parts.slice(1,parts.length-2).join(':'); const m=(guests[sn]=guests[sn]||{}); m[ep]=m[ep]||[]; const idx=m[ep].findIndex(g=>g.name===name);
      if(idx>=0){ m[ep].splice(idx,1); toast(name+' removed from S'+pad(sn)+'E'+pad(ep)); } else { m[ep].push({name,role:''}); toast(name+' added to S'+pad(sn)+'E'+pad(ep)); } }
    render();
  }

  scopeSw.addEventListener('click', e=>{ const b=e.target.closest('[data-scope]'); if(!b) return; scope=b.dataset.scope; render(); });
  const castFetch=q('cast-fetch'); if(castFetch) castFetch.addEventListener('click', ()=>toast('Fetched cast & crew from TMDB'));

  body.addEventListener('click', e=>{
    if (e.target.closest('#mc-add')) return openPerson('cast');
    if (e.target.closest('#crew-add')) return openPerson('crew');
    if (e.target.closest('#guest-add')) return openPerson('guest');
    if (e.target.closest('#epcrew-add')) { ensureEpCrew(); return openPerson('epcrew'); }
    if (e.target.closest('#ep-fetch')) return toast('Fetched guests + crew for S'+pad(selSeason)+'E'+pad(selEp)+' from TMDB');
    const ep=e.target.closest('[data-ep]'); if(ep){ selEp=+ep.dataset.ep; render(); return; }
    const mv=e.target.closest('[data-mview]'); if(mv){ matrixView=mv.dataset.mview; render(); return; }
    const cell=e.target.closest('[data-cell]'); if(cell){ toggleCell(cell.dataset.cell); return; }
    const crm=e.target.closest('[data-crewrm]'); if(crm){ const i=+crm.dataset.crewrm, n=crew[i].name; crew.splice(i,1); toast(n+' removed from crew'); render(); return; }
    const ecrm=e.target.closest('[data-epcrewrm]'); if(ecrm){ ensureEpCrew(); const i=+ecrm.dataset.epcrewrm, a=epCrew[selSeason][selEp], n=a[i].name; a.splice(i,1); toast(n+' removed'); render(); return; }
    const rm=e.target.closest('[data-rm]'); if(rm){ const i=+rm.dataset.rm;
      if (scope==='series'){ const n=mainCast[i].name; mainCast.splice(i,1); toast(n+' removed from cast'); }
      else { const gs=guestsOf(selSeason,selEp), n=gs[i].name; gs.splice(i,1); toast(n+' removed'); }
      render(); return; }
    const role=e.target.closest('[data-role]'); if(role){ editRole(+role.dataset.role, role); return; }
  });

  let dragI=null;
  body.addEventListener('dragstart', e=>{ const p=e.target.closest('.person[draggable]'); if(!p) return; dragI=+p.dataset.i; p.classList.add('dragging'); });
  body.addEventListener('dragend', e=>{ const p=e.target.closest('.person'); if(p) p.classList.remove('dragging'); });
  body.addEventListener('dragover', e=>{ if(scope==='series') e.preventDefault(); });
  body.addEventListener('drop', e=>{ if(scope!=='series'||dragI===null) return; e.preventDefault(); const p=e.target.closest('.person[draggable]'); const to=p?+p.dataset.i:mainCast.length-1; if(to!==dragI){ const m=mainCast.splice(dragI,1)[0]; mainCast.splice(to,0,m); toast('Cast order saved to library'); } dragI=null; render(); });

  /* ---------- person search modal ---------- */
  const POOL = ['Sofie Gråbøl','Lars Mikkelsen','Trine Dyrholm','Tobias Lindholm','Birgitte Hald','Mikkel Maltha','Magnus Nordenhof Jønck','Pilou Asbæk','Nikolaj Lie Kaas','Paprika Steen','Mads Mikkelsen','Connie Nielsen','Charlotte Bruus'];
  const pm=q('person-modal'), presults=q('person-results'), psearch=q('person-search'), pextra=q('person-extra');
  let pmode='cast';
  function openPerson(mode){ pmode=mode; q('person-title').textContent = mode==='crew'?'Add crew member':(mode==='epcrew'?'Add episode crew':(mode==='guest'?'Add guest star':'Add cast')); pextra.style.display=(mode==='crew'||mode==='epcrew')?'flex':'none'; psearch.value=''; drawResults(''); pm.classList.add('open'); setTimeout(()=>psearch.focus(),30); }
  function closePerson(){ pm.classList.remove('open'); }
  function drawResults(query){ const list=POOL.filter(n=>n.toLowerCase().includes(query.toLowerCase())).slice(0,8); presults.innerHTML = list.map(n=>'<div class="prow" data-add="'+esc(n).replace(/"/g,'&quot;')+'"><span class="crew-av" style="background:'+grad(n)+';">'+initials(n)+'</span><span class="crew-name">'+esc(n)+'</span><span style="flex:1;"></span><span class="btn sm">Add</span></div>').join('') || '<div class="tiny muted" style="padding:8px;">No matches — type a name.</div>'; }
  psearch.addEventListener('input', ()=>drawResults(psearch.value));
  presults.addEventListener('click', e=>{ const row=e.target.closest('[data-add]'); if(!row) return; const name=row.dataset.add;
    if (pmode==='cast'){ const o={}; o[selSeason]=[]; mainCast.push({name,role:'',eps:o}); toast(name+' added to cast'); }
    else if (pmode==='crew'){ const dept=q('person-dept').value, job=q('person-job').value.trim()||'Crew'; crew.push({dept,job,name}); toast(name+' added · '+job); }
    else if (pmode==='epcrew'){ ensureEpCrew(); const job=q('person-job').value.trim()||'Crew'; epCrew[selSeason][selEp].push({job,name}); toast(name+' added · '+job); }
    else { const m=(guests[selSeason]=guests[selSeason]||{}); m[selEp]=m[selEp]||[]; m[selEp].push({name,role:''}); toast(name+' added as guest · S'+pad(selSeason)+'E'+pad(selEp)); }
    closePerson(); render();
  });
  q('person-x').addEventListener('click', closePerson);
  pm.addEventListener('click', e=>{ if(e.target===pm) closePerson(); });
  document.addEventListener('keydown', e=>{ if(e.key==='Escape'&&pm.classList.contains('open')) closePerson(); });

  render();
})();
