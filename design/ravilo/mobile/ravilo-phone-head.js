/* Ravilo — the phone (head: ?frame=window + window.RaviloTheme). Lifted out of "Ravilo Mobile.html" on 2026-10-04 so the one Ravilo file (Ravilo.html ?surface=phone)
   can load it. THIS is the maintained copy now; "Ravilo Mobile.html" keeps its old inline copy as a frozen snapshot until it is deleted. */
/* ?frame=window — the desktop app's compact window (<600 dp) is this same UI with no bezel or status bar (Desktop - D1.html embeds it). */(function(){var u=new URLSearchParams(location.search);if(u.get('frame')==='window')document.documentElement.classList.add('rv-window');})();
/* Themes (owner 2026-09-30): five, each light or dark. The pair is the VIEWER's, synced across devices (owner, same day);
   localStorage stands in for the profile here. Only light/dark itself is the device's. "Match the phone" picks one light and one dark theme
   (IntelliJ's Sync with OS) and follows the system live. ?skin= pins one; ?appearance=light|dark stands in for the OS. */
(function(){
  var u=new URLSearchParams(location.search), K='ravilo-theme', LIGHT={daylight:1};
  var T=window.RaviloTheme={list:[['aurora','Aurora'],['midnight','Midnight'],['noir','Noir'],['graphite','Graphite'],['daylight','Daylight']],
    isLight:function(k){return !!LIGHT[k];}, pref:{sync:true,light:'daylight',dark:'aurora',single:'aurora'}, sim:u.get('appearance')};
  try{Object.assign(T.pref,JSON.parse(localStorage.getItem(K)||'{}'));}catch(e){}
  if(u.get('skin')){T.pref={sync:false,light:'daylight',dark:'aurora',single:u.get('skin')};T.pinned=1;}
  var mq=window.matchMedia?matchMedia('(prefers-color-scheme: dark)'):null;
  T.osDark=function(){return T.sim?T.sim!=='light':(mq?mq.matches:true);};
  T.current=function(){return T.pref.sync?(T.osDark()?T.pref.dark:T.pref.light):T.pref.single;};
  T.apply=function(){var k=T.current(),d=document.documentElement;d.setAttribute('data-skin',k);d.setAttribute('data-appearance',LIGHT[k]?'light':'dark');if(T.onchange)T.onchange();};
  T.set=function(p){Object.assign(T.pref,p);if(!T.pinned)try{localStorage.setItem(K,JSON.stringify(T.pref));}catch(e){}T.apply();};
  if(mq&&mq.addEventListener)mq.addEventListener('change',function(){if(!T.sim)T.apply();});
  T.apply();
})();
