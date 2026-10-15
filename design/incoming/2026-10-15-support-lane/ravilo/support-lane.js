/* Support lane — screen renderers for "Support Lane - Directions.html". Every [data-scr] mount in the
   canvas is filled from here; the data model is the three stand-in tickets and the one seven-message
   thread the brief names. No phase number anywhere, by design. */
(function(){
const SVG={
 back:'<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
 send:'<svg viewBox="0 0 24 24"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
 check:'<svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>',
 plus:'<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
 chev:'<svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
 search:'<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
 home:'<svg viewBox="0 0 24 24"><path d="M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>',
 movies:'<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/></svg>',
 series:'<svg viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="13" rx="2"/><path d="M8 2l4 4 4-4"/></svg>',
 disc:'<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M15 9l-2 5-5 2 2-5z"/></svg>',
 chat:'<svg viewBox="0 0 24 24"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-5A8 8 0 1 1 21 12z"/></svg>',
 sig:'<svg viewBox="0 0 18 12" width="18" height="12"><rect x="0" y="8" width="3" height="4" rx="1" fill="currentColor"/><rect x="5" y="5" width="3" height="7" rx="1" fill="currentColor"/><rect x="10" y="2" width="3" height="10" rx="1" fill="currentColor"/><rect x="15" y="0" width="3" height="12" rx="1" fill="currentColor"/></svg>'
};
const T={
 en:{ph:'Write to support…',ph_re:'Write here again to reopen',you:'you',sup:'support',writing:'AI Agent is writing',open:'Open',resolved:'Resolved',w:'with',owner:'the owner',replied:'replied in 12 minutes',fwdago:'forwarded 2 days ago',waiting:'waiting for a first reply',ownerline:'Hervør will answer here',by:'by',bug:'Bug',request:'Request',question:'Question',sending:'Sending…',notsent:'Not sent — you’re offline. Your message is kept here.',retry:'Try again',offline:'Offline — showing what was last loaded',mytk:'My tickets',write:'Write to support',hint_p:'Tell us what happened and where — which TV or phone helps.',from:'Sent from Ravilo on the phone · Jákup',empty_t:'No tickets yet',empty_p:'Something wrong, or something you wish Ravilo did? Write to support — a reply usually comes within minutes.',same:'Same here',tvro:'Reply from Ravilo on the phone or the web.',showrest:'Show the rest',profile:'Profile',new1:'1 new reply'},
 da:{ph:'Skriv til support…',ph_re:'Skriv her igen for at genåbne',you:'du',sup:'support',writing:'AI Agent skriver',open:'Åben',resolved:'Løst',w:'hos',owner:'ejeren',replied:'svarede på 12 minutter',fwdago:'sendt videre for 2 dage siden',waiting:'venter på første svar',ownerline:'Hervør svarer her',by:'af',bug:'Fejl',request:'Ønske',question:'Spørgsmål',sending:'Sender…',notsent:'Ikke sendt — du er offline. Din besked er gemt her.',retry:'Prøv igen',offline:'Offline — viser det senest hentede',mytk:'Mine sager',write:'Skriv til support',hint_p:'Fortæl hvad der skete og hvor — hvilket tv eller hvilken telefon hjælper.',from:'Sendt fra Ravilo på telefonen · Jákup',empty_t:'Ingen sager endnu',empty_p:'Er noget galt, eller ønsker du dig noget af Ravilo? Skriv til support — svaret kommer som regel inden for få minutter.',same:'Det samme her',tvro:'Svar fra Ravilo på telefonen eller nettet.',showrest:'Vis resten',profile:'Profil',new1:'1 nyt svar'},
 fo:{ph:'Skriva til supportina…',ph_re:'Skriva her aftur fyri at lata upp aftur',you:'tú',sup:'support',writing:'AI Agent skrivar',open:'Opin',resolved:'Loyst',w:'hjá',owner:'eigaranum',replied:'svaraði á 12 minuttum',fwdago:'sent víðari fyri 2 døgum síðani',waiting:'bíðar eftir fyrsta svari',ownerline:'Hervør svarar her',by:'av',bug:'Feilur',request:'Ynski',question:'Spurningur',sending:'Sendir…',notsent:'Ikki sent — tú ert uttan samband. Boðini eru goymd her.',retry:'Royn aftur',offline:'Uttan samband — vísir tað seinast heinta',mytk:'Mínar sakir',write:'Skriva til supportina',hint_p:'Sig frá, hvat hendi og hvar — hvat sjónvarp ella fartelefon hjálpir.',from:'Sent frá Ravilo á fartelefonini · Jákup',empty_t:'Ongar sakir enn',empty_p:'Er okkurt galið, ella ynskir tú tær okkurt av Ravilo? Skriva til supportina — svar kemur vanliga innan fáar minuttir.',same:'Sama her',tvro:'Svara frá Ravilo á fartelefonini ella á netinum.',showrest:'Vís restina',profile:'Vangamynd',new1:'1 nýtt svar'}
};
/* the viewer's first message, verbatim — it stays a message */
const FIRST={en:'Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room',da:'Nordvest S02E04 mister lyden efter cirka ti minutter, på tv’et i stuen',fo:'Nordvest S02E04 missir ljóðið eftir umleið tíggju minuttum, á sjónvarpinum í stovuni'};
/* the case title — written by AI Agent from that first message, never the message itself */
const TITLE={en:'Sound drops in Nordvest S02E04 on the sitting-room TV',da:'Lyden falder ud i Nordvest S02E04 på tv’et i stuen',fo:'Ljóðið dettur út í Nordvest S02E04 á sjónvarpinum í stovuni'};
const T41={
 en:[
  {k:'me',t:FIRST.en,time:'20:14'},
  {k:'sys',v:'recv',t:'Received · <b>#41</b> · logged as a bug · titled “Sound drops in Nordvest S02E04 on the sitting-room TV”',time:'20:14'},
  {k:'ai',t:'Thanks, Jákup. That sounds like the second audio track dropping at a chapter change. Does the sound come back if you pause and play, or only after restarting the episode?',time:'20:14'},
  {k:'me',t:'Only after restarting. It happened twice tonight.',time:'20:16'},
  {k:'ai',t:'I’ll forward this and they will answer you soon.',time:'20:16'},
  {k:'sys',v:'fwd',t:'AI Agent forwarded this to <b>Hervør</b>',time:'20:16'},
  {k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> is handling this',time:'20:21'},
  {k:'hv',t:'Hi Jákup — the episode carries two audio tracks, and the TV in the sitting room switches between them at 09:52. I’ve pinned the Danish track for that TV. Could you try it again tonight?',time:'20:28'},
  {k:'me',t:'That did it. The sound stayed on.',time:'21:40'},
  {k:'hv',t:'Great — I’ll close this one. Write here again if it comes back.',time:'21:43'},
  {k:'sys',v:'ok',t:'Resolved by <b>Hervør</b>',time:'21:43'},
  {k:'me',t:'It’s back, same place.',time:'Wed 19:05'},
  {k:'sys',v:'reopen',t:'Reopened — you wrote again',time:'19:05'}],
 da:[
  {k:'me',t:FIRST.da,time:'20:14'},
  {k:'sys',v:'recv',t:'Modtaget · <b>#41</b> · registreret som fejl · med titlen “Lyden falder ud i Nordvest S02E04 på tv’et i stuen”',time:'20:14'},
  {k:'ai',t:'Tak, Jákup. Det lyder som om det andet lydspor falder ud ved et kapitelskift. Kommer lyden tilbage, hvis du sætter på pause og afspiller igen, eller først når du genstarter afsnittet?',time:'20:14'},
  {k:'me',t:'Kun når jeg genstarter. Det skete to gange i aften.',time:'20:16'},
  {k:'ai',t:'Jeg sender det videre, og de svarer dig snart.',time:'20:16'},
  {k:'sys',v:'fwd',t:'AI Agent sendte det videre til <b>Hervør</b>',time:'20:16'},
  {k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> tager sig af det',time:'20:21'},
  {k:'hv',t:'Hej Jákup — afsnittet har to lydspor, og tv’et i stuen skifter mellem dem ved 09:52. Jeg har låst det danske spor til det tv. Vil du prøve igen i aften?',time:'20:28'}],
 fo:[
  {k:'me',t:FIRST.fo,time:'20:14'},
  {k:'sys',v:'recv',t:'Móttikið · <b>#41</b> · skrásett sum feil · við heitinum “Ljóðið dettur út í Nordvest S02E04 á sjónvarpinum í stovuni”',time:'20:14'},
  {k:'ai',t:'Takk, Jákup. Tað ljóðar sum um annað ljóðsporið dettur út við eitt kapittulskifti. Kemur ljóðið aftur, um tú steðgar og spælir aftur, ella bara um tú byrjar sendingina av nýggjum?',time:'20:14'},
  {k:'me',t:'Bara um eg byrji av nýggjum. Tað hendi tvær ferðir í kvøld.',time:'20:16'},
  {k:'ai',t:'Eg sendi hetta víðari, og tey svara tær skjótt.',time:'20:16'},
  {k:'sys',v:'fwd',t:'AI Agent sendi hetta víðari til <b>Hervør</b>',time:'20:16'},
  {k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> tekur sær av hesum',time:'20:21'},
  {k:'hv',t:'Hey Jákup — sendingin hevur tvey ljóðspor, og sjónvarpið í stovuni skiftir millum tey við 09:52. Eg havi fest danska sporið til tað sjónvarpið. Kanst tú royna aftur í kvøld?',time:'20:28'}]
};
const T38=[
 {k:'me',who:'Ása',t:'could it remember my audio language per series',time:'Mon 18:02'},
 {k:'sys',v:'recv',t:'Received · <b>#38</b> · logged as a request · titled “Remember audio language per series”',time:'18:02'},
 {k:'ai',t:'Logged. Remembering the audio language per series is a change to how Ravilo works, so it needs the owner’s decision — I can’t promise it here. I’ve passed it on, and you’ll hear back in this thread.',time:'18:02'},
 {k:'sys',v:'owner',av:'hv',t:'Forwarded to the owner · <b>Hervør</b> will answer here',time:'18:03'}];
const T36=[
 {k:'me',who:'Sunnvør',t:'how do I keep subtitles on by default',time:'3 Oct 21:10'},
 {k:'sys',v:'recv',t:'Received · <b>#36</b> · logged as a question · titled “Keeping subtitles on by default”',time:'21:10'},
 {k:'ai',t:'Open your profile → Settings → Playback → Subtitles and pick <b>Always on</b>. It applies to every device you’re signed in on, including the TV in the sitting room.',time:'21:10'},
 {k:'me',who:'Sunnvør',t:'found it, thanks',time:'21:14'},
 {k:'sys',v:'ok',t:'Resolved by <b>Hervør</b>',time:'4 Oct 09:20'}];
const LONG=(function(){const p='Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room. The picture keeps going and the subtitles keep going, but the audio simply stops. I have tried it four evenings in a row now and it is always the same place, give or take a few seconds — just after the scene on the quay. Pausing does nothing, skipping back thirty seconds does nothing, and changing the audio track in the picker brings the sound back for about two seconds and then it drops again. The only thing that works is leaving the episode and starting it over, and then it plays through to the end without a problem, which is what makes me think it is something about resuming rather than the file itself. It does not happen on Ravilo on the phone, where the same episode plays with sound from start to finish, and it did not happen on S02E03 the night before on the same TV. ';let s='';while(s.length<2000)s+=p;return s.slice(0,1999)+'…';})();

function sbar(){return `<div class="sl-sb"><span>9:41</span><span class="ic">${SVG.sig}<i class="bat"></i></span></div>`;}
function av(k,c){c=c||'';if(k==='ai')return `<span class="av ai ${c}"><i></i></span>`;if(k==='hv')return `<span class="av hv ${c}">H</span>`;if(k==='ad')return `<span class="av ad ${c}">Ha</span>`;if(k==='me')return `<span class="av me ${c}">J</span>`;if(k==='asa')return `<span class="av me ${c}">Á</span>`;if(k==='okc')return `<span class="av okc ${c}">${SVG.check}</span>`;return `<span class="av none ${c}"></span>`;}
function who(m,lang,admin){const L=T[lang];const k=m.k;
 if(k==='me')return admin?`<b>${m.who==='Ása'?'Ása Dam':m.who==='Sunnvør'?'Sunnvør Holm':'Jákup Hansen'}</b><span class="hnd">${m.who==='Ása'?'asa':m.who==='Sunnvør'?'sunnva':'jakup'}</span>`:`<b>${m.who||'Jákup'}</b><span>· ${L.you}</span>`;
 if(k==='ai')return `<b>AI Agent</b><span class="bdg ai">AI</span>`;
 if(k==='hv')return `<b>Hervør</b><span class="bdg ai">AI</span><span class="bdg">${L.sup}</span>`;
 if(k==='ad')return `<b>Hallur</b><span class="bdg">admin</span>`;return '';}
function msg(m,lang,admin){lang=lang||'en';
 if(m.k==='sys')return `<div class="sys ${m.v||''}"><span>${m.av?av(m.av):''}<i></i>${m.t}${m.time?`<time>${m.time}</time>`:''}</span></div>`;
 if(m.k==='typing')return `<div class="m ai">${av('ai')}<div class="b"><div class="w">${who({k:'ai'},lang)}<span class="wr">${T[lang].writing}</span></div><div class="t typing"><i></i><i></i><i></i></div></div></div>`;
 const side=admin?(m.k==='ad'?' me':''):(m.k==='me'?' me':'');
 return `<div class="m ${m.k}${side}${m.cls?' '+m.cls:''}">${av(m.k==='me'&&m.who==='Ása'?'asa':m.k)}<div class="b"><div class="w">${who(m,lang,admin)}<time>${m.time}</time></div><div class="t${m.clamp?' clamp':''}">${m.t}</div>${m.clamp?`<button class="more">${T[lang].showrest}</button>`:''}</div></div>`;}
function pres(o){const L=T[o.lang];let txt,kind;
 if(o.st==='ok'){txt=`${L.resolved} · ${L.by} Hervør`;kind='ok';}
 else if(o.st==='wait'){txt=`${L.open} · ${L.waiting}`;kind='wait';}
 else if(o.st==='owner'){txt=`${L.open} · ${L.w} ${L.owner} · ${L.ownerline}`;kind='own';}
 else if(o.st==='ai'){txt=`${L.open} · ${L.w} AI Agent`;kind='';}
 else{txt=`${L.open} · ${L.w} Hervør${o.extra?' · '+L[o.extra]:''}`;kind='';}
 return `<div class="sl-pres ${kind}"><i></i><span>${txt}</span></div>`;}
function head(o){const L=T[o.lang];const num=o.num||41,title=o.title||TITLE[o.lang];
 if(o.dir==='B')return `<div class="sl-hd b"><span class="bk">${SVG.back}</span><div class="tx"><div class="kk">#${num}</div><h1>${title}</h1></div></div>`;
 if(o.dir==='C')return `<div class="sl-hd"><span class="bk">${SVG.back}</span><div class="tx"><h1>Ticket #${num}</h1></div></div><div class="scard"><div><s>Status</s><b class="prl">Open</b></div><div><s>Assigned to</s><b>Hervør</b></div><div><s>Type</s><b>Bug</b></div><div><s>Opened</s><b>Tue 20:14</b></div><div><s>Last reply</s><b>12 min</b></div><div><s>Device</s><b>TV · sitting room</b></div></div>`;
 return `<div class="sl-hd"><span class="bk">${SVG.back}</span><div class="tx"><div class="kk">#${num}${o.cls?` <span class="cl">${L[o.cls]}</span>`:''}</div><h1>${title}</h1>${pres(o)}</div>${av(o.holder||'none','lg hold')}</div>`;}
function comp(o){const L=T[o.lang];const m=o.mode||'';
 const field=m?`<div class="f has${m==='lock'?' lock':''}">${o.text}</div>`:`<div class="f">${o.re?L.ph_re:L.ph}</div>`;
 const btn=m==='lock'?`<span class="s"><i class="rg"></i></span>`:(m?`<span class="s">${SVG.send}</span>`:`<span class="s off">${SVG.send}</span>`);
 return `<div class="cp${m?' '+(m==='fail'?'nsent':m):''}">${m==='lock'?'<i class="prog"></i>':''}${field}${btn}</div>`;}
function intro(o){const L=T[o.lang];return `<div class="intro"><h2>${L.write}</h2><p>${L.hint_p}</p><small>${L.from}</small></div>`;}
function thread(o){const L=T[o.lang];const note=o.note?`<div class="cpn ${o.note==='fail'?'nsent':o.note}">${o.note==='lock'?L.sending:L.notsent}${o.note==='fail'?`<b>${L.retry}</b>`:''}</div>`:'';
 const compact=o.compact?`<div class="cbar">${av('hv','sm')}<span>${L.open} · ${L.w} Hervør</span><time>20:21</time></div>`:'';
 return `<div class="scn" lang="${o.lang}">${sbar()}${head(o)}${o.offline?`<div class="offb"><i></i>${L.offline}</div>`:''}${compact}<div class="th${o.top?' top':''}">${o.intro?intro(o):''}${(o.items||[]).map(m=>msg(m,o.lang)).join('')}</div>${note}${comp(Object.assign({lang:o.lang},o.comp||{}))}</div>`;}
function bnav(on,dot,lang){const L=T[lang||'en'];const it=[['home','Home'],['movies','Movies'],['series','Series'],['disc','Discover']];
 return `<div class="sl-bn">${it.map((x,i)=>`<div class="it${i===on?' on':''}"><span class="p">${SVG[x[0]]}</span><b>${x[1]}</b></div>`).join('')}<div class="it${on===4?' on':''}"><span class="p"><span class="av me sm">J${dot?'<u></u>':''}</span></span><b>${L.profile}</b></div></div>`;}
function tkRow(o){return `<div class="tk${o.un?' un':''}">${av(o.hold)}<div class="b"><div class="r1"><span class="num">#${o.num}</span><span class="cl">${o.cls}</span><time>${o.time}</time></div><div class="ti">${o.un?'<u></u>':''}${o.ti}</div><div class="sl-pres ${o.kind||''}"><i></i><span>${o.pr}</span></div></div></div>`;}
function list(o){const L=T[o.lang];let body='';
 if(o.mode==='loading')body=[0,1,2].map(()=>`<div class="tk"><span class="sk av"></span><div class="b"><span class="sk l1"></span><span class="sk l2"></span><span class="sk l3"></span></div></div>`).join('');
 else if(o.mode==='empty')body=`<div class="emp"><span class="eic">${SVG.chat}</span><h2>${L.empty_t}</h2><p>${L.empty_p}</p></div><b class="cta">${L.write}</b>`;
 else body=tkRow({num:41,cls:L.bug,time:'21:43',ti:TITLE[o.lang],pr:`${L.open} · ${L.w} Hervør · ${L.replied}`,hold:'hv',un:true})+tkRow({num:38,cls:L.request,time:'Mon',ti:'Remember audio language per series',pr:`${L.open} · ${L.w} ${L.owner} · ${L.fwdago}`,hold:'hv',kind:'own'})+tkRow({num:36,cls:L.question,time:'3 Oct',ti:'Keeping subtitles on by default',pr:`${L.resolved} · ${L.by} Hervør`,hold:'okc',kind:'ok'})+`<b class="cta ghost">${L.write}</b>`;
 return `<div class="scn" lang="${o.lang}">${sbar()}<div class="sl-top"><span class="bk">${SVG.back}</span><h1>${L.mytk}</h1></div><div class="sl-pg">${body}</div>${bnav(4,o.mode==='list',o.lang)}</div>`;}
function home(o){const rows=`<div class="hsk"></div><div class="rsk"><i></i><i></i><i></i><i></i></div><div class="rsk"><i></i><i></i><i></i><i></i></div>`;
 const menu=o.sheet?`<div class="scrim"></div><div class="sheet pm"><div class="grab"></div><div class="pmh">${av('me','lg')}<div><b>Jákup Hansen</b><small>jakup</small></div></div><div class="pmr">Your profile</div><div class="pmr">Continue Watching</div><div class="pmr">My List</div><div class="pmr hot"><span>Support</span><em>1 new reply</em><u></u></div><div class="pmr">Settings</div><div class="pmr">Switch</div><div class="pmr mute">Sign out</div></div>`:'';
 return `<div class="scn">${sbar()}<div class="sl-ab"><span class="wm">Ravilo</span><span class="sp"></span><span class="ib">${SVG.search}</span><span class="av me">J<u></u></span></div><div class="sl-pg">${rows}</div>${bnav(0,true)}${menu}</div>`;}
function altnav(){return `<div class="scn short"><div class="sl-bn alt"><div class="it on"><span class="p">${SVG.home}</span><b>Home</b></div><div class="it"><span class="p">${SVG.movies}</span><b>Movies</b></div><div class="it"><span class="p">${SVG.series}</span><b>Series</b></div><div class="it"><span class="p">${SVG.chat}<u class="ct">1</u></span><b>Support</b></div><div class="it"><span class="p"><span class="av me sm">J</span></span><b>Profile</b></div></div></div>`;}
function chooser(){const L=T.en;return `<div class="scn">${sbar()}${head({lang:'en',holder:'none',st:'wait',title:'New ticket',num:'—'})}<div class="th"><div class="intro"><h2>${L.write}</h2><p>What is this about?</p><div class="chs"><b class="on">Bug</b><b>Request</b><b>Question</b></div><small>${L.from}</small></div></div>${comp({lang:'en'})}</div>`;}
function tv(o){const its=T41.en.slice(0,8).concat(o.said?[{k:'sys',v:'same',av:'asa',t:'<b>Ása</b> said same here · from the TV in the sitting room',time:'now'}]:[]);
 return `<div class="tv"><div class="tvl"><div class="kk">Support · #41 · Bug</div><h1>${TITLE.en}</h1>${pres({lang:'en',extra:'replied'})}<div class="filed">${av('me')}Jákup Hansen · filed from Ravilo on the phone</div><p class="ro">${T.en.tvro}</p>${o.said?`<b class="same done">${SVG.check}You said same here</b>`:`<b class="same focus">${SVG.plus}${T.en.same}</b>`}<span class="hint">${o.said?'Hervør will see it in the thread':'OK on the remote · adds one line, no typing'}</span></div><div class="tvr"><div class="prof">${av('asa')}Ása</div><div class="th">${its.map(m=>msg(m,'en')).join('')}</div></div></div>`;}
/* ---- admin ---- */
const NAV=[['Dashboard'],['Library'],['Suggestions'],['Activity'],['AI',true,2],['Setup'],['Metadata'],['Settings'],['Ravilo'],['Layout'],['Live TV'],['Requests'],['Users & devices'],['Preferences']];
function side(){return `<div class="adm-side"><div class="logo"><i></i>Jellystructure</div>${NAV.map(n=>n.length===1&&(n[0]==='Setup'||n[0]==='Ravilo')?`<div class="gl">${n[0]}</div>`:`<a class="${n[1]?'on':''}"><span>${n[0]}</span>${n[2]?`<em class="cnt">${n[2]}</em>`:''}</a>`).join('')}</div>`;}
const aiTabs=()=>`<div class="tabs top"><b>Fetchers</b><b class="on">Support<em class="cnt">2</em></b></div>`;
function admList(){const r=(n,v,h,t,c,s,hold,last,un)=>`<tr class="${un?'un':''}"><td class="mono">${un?'<u></u>':''}#${n}</td><td><b>${v}</b><span class="hnd">${h}</span></td><td class="tt">${t}</td><td><span class="cl">${c}</span></td><td><span class="st ${s[1]}">${s[0]}</span></td><td class="hold">${hold}</td><td class="mono mute">${last}</td></tr>`;
 return `<div class="adm"><div class="adm-shell">${side()}<div class="adm-main"><div class="pb"><h1>AI</h1><span class="sp"></span></div>${aiTabs()}<div class="pb"><span class="flt"><b class="on">All · 3</b><b>Open · 2</b><b>With me</b><b>Resolved · 1</b><b>Flagged</b></span></div><div class="sub">Every viewer’s tickets, under AI because AI Agent and Hervør answer them. The sidebar count is the number of open tickets. What the viewer sees is the thread, the state and the holder — never this page.</div><table class="adm-tbl"><thead><tr><th>#</th><th>Viewer</th><th>Ticket</th><th>Class</th><th>State</th><th>Holder</th><th>Last</th></tr></thead><tbody>${r(41,'Jákup Hansen','jakup',TITLE.en,'Bug',['Open','open'],av('hv','sm')+'Hervør · support','Hervør · 21:43',true)}${r(38,'Ása Dam','asa','Remember audio language per series','Request',['Open','open'],av('none','sm')+'Owner · Hervør answers','forwarded 2 days ago')}${r(36,'Sunnvør Holm','sunnva','Keeping subtitles on by default','Question',['Resolved','ok'],av('okc','sm')+'—','Hervør · 4 Oct')}</tbody></table></div></div></div>`;}
function rail(o){const flag=o.flag;
 return `<aside class="rail"><div class="rc"><div class="kv"><s>State</s><b><span class="st ${flag?'bad':'open'}">${flag?'Flagged':'Open'}</span></b></div><div class="kv"><s>Class</s><b>${flag?'Question':'Bug'} <i class="dd">${SVG.chev}</i></b></div><div class="kv"><s>Holder</s><b>${flag?'<span class="mute">Nobody · no reply will be sent</span>':av('hv','sm')+'Hervør · support'}</b></div><div class="kv"><s>Viewer sees</s><b class="mute">${flag?'Open · waiting for a first reply':'Open · with Hervør · replied in 12 minutes'}</b></div></div>
 <div class="rc${flag?' off':''}"><h4>Forward to</h4><label class="ro on"><i></i>${av('hv','sm')}<span>Hervør · support</span></label><label class="ro"><i></i>${av('ad','sm')}<span>Hallur · admin</span></label><label class="ro"><i></i>${av('none','sm')}<span>Owner<small>Cannot answer here — Hervør keeps the thread and owes the next reply.</small></span></label><div class="acts"><b class="pri">Forward</b><b class="ok">Resolve</b></div></div>
 <div class="rc"><b class="${flag?'ghost':'bad'} wide">${flag?'Remove flag':'Flag as abuse'}</b>${flag?'':'<small>Stops every reply, including AI Agent’s. The viewer is told nothing.</small>'}</div><a class="tl">AI transcript for #${flag?36:41} →</a></aside>`;}
function admDetail(o){const flag=o.flag;const its=flag?T36.slice(0,3):T41.en.slice(0,10);const num=flag?36:41;const title=flag?'Keeping subtitles on by default':TITLE.en;
 return `<div class="adm"><div class="adm-shell">${side()}<div class="adm-main det"><div class="bkr">${SVG.back}AI · Support</div><div class="dgrid"><section><div class="dh"><div class="kk">#${num} · ${flag?'Sunnvør Holm · sunnva · Ravilo on the web':'Jákup Hansen · jakup · the TV in the sitting room'} · opened ${flag?'3 Oct 21:10':'Tue 20:14'}</div><h1>${title}</h1></div>${flag?`<div class="flagb"><i></i><div><b>Flagged as abuse by Hallur · 4 Oct 09:20.</b> No reply will be sent, not even AI Agent’s. The viewer sees an open ticket and nothing about the flag.</div></div>`:''}<div class="th">${its.map(m=>msg(m,'en',true)).join('')}</div>${flag?`<div class="cp dead"><div class="f">Replies are off while this ticket is flagged.</div></div>`:`<div class="cp"><div class="f">Reply as Hallur · admin — it sends itself and lands in the thread as <b>Hallur · admin</b></div><span class="s">${SVG.send}</span></div>`}</section>${rail(o)}</div></div></div></div>`;}
function admAlt(){const L=(t,who,what,ref)=>`<div class="lg"><time>${t}</time><b>${who}</b><span>${what}</span><i>${ref}</i></div>`;return `<div class="adm alt"><div class="adm-shell"><div class="adm-main"><div class="pb"><h1>Activity</h1></div><div class="tabs"><b>Scans</b><b>AI spend</b><b>Transcripts</b><b class="on">Support log</b></div><div class="sub">History only — what every agent did and every action taken on a ticket. Nothing here is actionable; the tickets themselves live under AI › Support.</div><div class="lgs">${L('Tue 21:43',av('hv','sm')+'Hervør','resolved','#41')}${L('Tue 20:28',av('hv','sm')+'Hervør','replied · 12 min after forward','#41')}${L('Tue 20:21',av('hv','sm')+'Hervør','took the ticket','#41')}${L('Tue 20:16',av('ai','sm')+'AI Agent','forwarded to Hervør · could not answer','#41')}${L('Tue 20:14',av('ai','sm')+'AI Agent','classified as bug · replied','#41')}${L('Mon 18:03',av('ai','sm')+'AI Agent','forwarded to the owner · request','#38')}</div></div></div></div>`;}
/* ---- vocabulary strip ---- */
function vocab(){return `<div class="scn vocab"><div class="vg"><h4>Authors</h4>${msg({k:'me',t:'The viewer. Their own name, marked “you”, gradient bubble, no avatar on their own device.',time:'20:14'},'en')}${msg({k:'ai',t:'AI Agent. Exactly that string; a square mark with a diamond, never a face, never an initial. The badge reads AI.',time:'20:14'},'en')}${msg({k:'hv',t:'Hervør · support. A named AI agent: a first name with the AI badge and the seat badge beside it, and a square mark with her initial — so the name never reads as a human being.',time:'20:28'},'en')}${msg({k:'ad',t:'Hallur · admin. The one human seat that answers: round mark, one badge.',time:'20:31'},'en')}</div><div class="vg"><h4>System lines — server state, drawn in the thread</h4>${[{k:'sys',v:'recv',t:'Received · <b>#41</b> · logged as a bug · titled “Sound drops in Nordvest S02E04 on the sitting-room TV”',time:'20:14'},{k:'sys',v:'fwd',t:'AI Agent forwarded this to <b>Hervør</b>',time:'20:16'},{k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> is handling this',time:'20:21'},{k:'sys',v:'owner',av:'hv',t:'Forwarded to the owner · <b>Hervør</b> will answer here',time:'18:03'},{k:'sys',v:'ok',t:'Resolved by <b>Hervør</b>',time:'21:43'},{k:'sys',v:'reopen',t:'Reopened — you wrote again',time:'19:05'},{k:'sys',v:'same',av:'asa',t:'<b>Ása</b> said same here · from the TV in the sitting room',time:'now'}].map(m=>msg(m,'en')).join('')}</div><div class="vg"><h4>Presence line — the header’s second line, five shapes</h4><div class="prl">${pres({lang:'en',st:'wait'})}${pres({lang:'en',st:'ai'})}${pres({lang:'en',extra:'replied'})}${pres({lang:'en',st:'owner'})}${pres({lang:'en',st:'ok'})}</div><h4>Class chips, inferred, shown after the receipt</h4><div class="prl row"><span class="cl">Bug</span><span class="cl">Request</span><span class="cl">Question</span></div><h4>Composer-local — never in the thread</h4><div class="cpn lock">Sending…</div><div class="cpn nsent">Not sent — you’re offline. Your message is kept here.<b>Try again</b></div></div></div>`;}

const SCR={
 'A-thread':()=>thread({lang:'en',cls:'bug',holder:'hv',extra:'replied',items:T41.en.slice(2,10),comp:{}}),
 'B-thread':()=>thread({lang:'en',dir:'B',compact:true,items:T41.en.slice(3,10),comp:{}}),
 'C-thread':()=>thread({lang:'en',dir:'C',items:[T41.en[0],T41.en[2],T41.en[3],T41.en[4],T41.en[7]],comp:{}}),
 'first-composer':()=>thread({lang:'en',holder:'none',st:'wait',title:'New ticket',num:'—',intro:true,items:[],comp:{mode:'text',text:'Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room'}}),
 'sending':()=>thread({lang:'en',holder:'none',st:'wait',title:'New ticket',num:'—',intro:true,items:[],note:'lock',comp:{mode:'lock',text:'Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room'}}),
 'waiting-first-reply':()=>thread({lang:'en',holder:'none',st:'wait',items:T41.en.slice(0,2),comp:{}}),
 'ai-composing':()=>thread({lang:'en',holder:'ai',st:'ai',cls:'bug',items:T41.en.slice(0,2).concat([{k:'typing'}]),comp:{}}),
 'ai-answered':()=>thread({lang:'en',holder:'ai',st:'ai',cls:'bug',items:T41.en.slice(0,3),comp:{}}),
 'forward':()=>thread({lang:'en',holder:'hv',cls:'bug',items:T41.en.slice(0,6),comp:{}}),
 'system-line':()=>thread({lang:'en',holder:'hv',cls:'bug',extra:'replied',items:T41.en.slice(3,8),comp:{}}),
 'held-by-owner':()=>thread({lang:'en',num:38,title:'Remember audio language per series',holder:'hv',st:'owner',cls:'request',items:T38,comp:{}}),
 'resolved':()=>thread({lang:'en',holder:'okc',st:'ok',cls:'bug',items:T41.en.slice(6,11),comp:{re:true}}),
 'reopened':()=>thread({lang:'en',holder:'hv',cls:'bug',items:T41.en.slice(7,13),comp:{}}),
 'abuse-viewer':()=>thread({lang:'en',num:36,title:'Keeping subtitles on by default',holder:'none',st:'wait',items:T36.slice(0,2),comp:{}}),
 'unread-nav':()=>home({sheet:true}),
 'my-tickets':()=>list({lang:'en',mode:'list'}),
 'empty':()=>list({lang:'en',mode:'empty'}),
 'loading':()=>list({lang:'en',mode:'loading'}),
 'error-send-failed':()=>thread({lang:'en',holder:'hv',cls:'bug',extra:'replied',offline:true,items:T41.en.slice(4,8),note:'fail',comp:{mode:'fail',text:'Tried again tonight — still drops at the same place.'}}),
 'offline':()=>thread({lang:'en',holder:'hv',cls:'bug',extra:'replied',offline:true,items:T41.en.slice(3,8),comp:{}}),
 'longest-string':()=>thread({lang:'en',holder:'none',st:'wait',cls:'bug',items:[{k:'me',t:LONG,time:'20:14',clamp:true},T41.en[1]],comp:{}}),
 'faroese':()=>thread({lang:'fo',holder:'hv',cls:'bug',extra:'replied',items:T41.fo.slice(3,8),comp:{}}),
 'danish':()=>thread({lang:'da',holder:'hv',cls:'bug',extra:'replied',items:T41.da.slice(3,8),comp:{}}),
 'light':()=>thread({lang:'en',holder:'hv',cls:'bug',extra:'replied',items:T41.en.slice(3,8),comp:{}}),
 'chooser':()=>chooser(),
 'altnav':()=>altnav(),
 'tv-focus':()=>tv({}),
 'tv-same-here':()=>tv({said:true}),
 'admin-list':()=>admList(),
 'admin-detail':()=>admDetail({}),
 'abuse-admin':()=>admDetail({flag:true}),
 'admin-alt':()=>admAlt(),
 'vocab':()=>vocab()
};
document.querySelectorAll('[data-scr]').forEach(el=>{const f=SCR[el.dataset.scr];if(f)el.innerHTML=f();});
})();
