package com.cncverse.stremiobridge.server

/** The page served at /home (see [WebSite]): one self-contained file, no build step. No dollar signs in here: this is a Kotlin raw string. */
internal object WebPage {
    val HTML: String = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CNCVerse</title>
<style>
:root{--bg:#0b0b0f;--card:#16161d;--line:#26262f;--tx:#f2f2f5;--mut:#9a9aa8;--ac:#e5484d}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
button,input,select{font:inherit;color:inherit}
button{cursor:pointer;border:0;border-radius:8px;padding:9px 16px;background:var(--ac);color:#fff;font-weight:600}
button.ghost{background:var(--card);border:1px solid var(--line);font-weight:500}
input,select{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:9px 12px;outline:none}
input:focus,select:focus{border-color:var(--ac)}
.hide{display:none!important}
#login{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}
#login form{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:28px;width:100%;max-width:340px;display:flex;flex-direction:column;gap:12px}
#login h1{margin:0 0 4px;font-size:22px}
.err{color:#ff7b80;min-height:18px;font-size:13px}
header{position:sticky;top:0;z-index:5;display:flex;gap:12px;align-items:center;padding:12px 20px;background:rgba(11,11,15,.92);backdrop-filter:blur(8px);border-bottom:1px solid var(--line)}
header b{font-size:18px;cursor:pointer;white-space:nowrap}
header b span{color:var(--ac)}
header input{flex:1;max-width:520px}
main{padding:20px}
h2{margin:22px 0 10px;font-size:18px}
.row{display:flex;gap:12px;overflow-x:auto;padding-bottom:10px;scrollbar-width:thin}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:14px}
.card{flex:0 0 140px;cursor:pointer}
.grid .card{flex:initial}
.card .p{width:100%;aspect-ratio:2/3;border-radius:10px;background:var(--card);object-fit:cover;display:block;transition:transform .15s}
.card:hover .p{transform:scale(1.04)}
.card .t{margin-top:6px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.card .s{font-size:12px;color:var(--mut)}
.hero{position:relative;border-radius:14px;overflow:hidden;background:var(--card) center/cover no-repeat;margin-bottom:18px}
.hero .shade{background:linear-gradient(90deg,rgba(11,11,15,.96) 35%,rgba(11,11,15,.55));padding:26px;display:flex;gap:22px}
.hero img{width:170px;border-radius:10px;align-self:flex-start}
.hero h1{margin:0 0 6px;font-size:28px}
.meta{color:var(--mut);margin-bottom:10px}
.hero p{max-width:720px;color:#d6d6de}
.eps{display:flex;flex-direction:column;gap:8px;margin-top:12px}
.ep{display:flex;gap:12px;align-items:center;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px;cursor:pointer}
.ep:hover{border-color:var(--ac)}
.ep img{width:120px;aspect-ratio:16/9;object-fit:cover;border-radius:6px;background:#000;flex:none}
.ep small{color:var(--mut);display:block;margin-top:2px;max-width:640px}
#modal,#player{position:fixed;inset:0;z-index:20;background:rgba(0,0,0,.78);display:flex;align-items:flex-start;justify-content:center;overflow:auto;padding:30px 12px}
#player{background:#000;align-items:center;padding:0;z-index:30}
.box{background:var(--card);border:1px solid var(--line);border-radius:14px;width:100%;max-width:860px;padding:18px}
.box h3{margin:0 0 4px}
.st{border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin-top:8px;cursor:pointer;background:var(--bg)}
.st:hover{border-color:var(--ac)}
.st b{display:block;font-size:14px}
.st pre{margin:4px 0 0;font:12px/1.35 inherit;color:var(--mut);white-space:pre-wrap;word-break:break-word}
.bar{display:flex;gap:8px;align-items:center;justify-content:space-between;margin-bottom:8px}
.spin{display:inline-block;width:16px;height:16px;border:2px solid var(--line);border-top-color:var(--ac);border-radius:50%;animation:r .8s linear infinite;vertical-align:-3px;margin-right:8px}
@keyframes r{to{transform:rotate(360deg)}}
video{width:100%;height:100%;background:#000;outline:none}
video::cue{color:#fff;background:transparent;font-weight:700;font-size:1.25em;text-shadow:-1px -1px 2px #000,1px -1px 2px #000,-1px 1px 2px #000,1px 1px 2px #000,0 0 6px #000}
#pclose{position:absolute;top:14px;left:14px;z-index:2;opacity:.85}
#pmsg{position:absolute;bottom:70px;left:0;right:0;text-align:center;color:#ffb3b6;padding:0 16px;pointer-events:none}
@media(max-width:640px){.hero .shade{flex-direction:column}.hero img{width:120px}header{padding:10px 12px}main{padding:12px}}
</style></head>
<body>
<div id="login" class="hide"><form id="lf">
<h1>CNC<span style="color:var(--ac)">Verse</span></h1>
<div style="color:var(--mut);font-size:13px">Enter the password to continue</div>
<input id="pw" type="password" placeholder="Password" autocomplete="current-password" autofocus>
<button type="submit">Open</button>
<div class="err" id="lerr"></div>
</form></div>

<div id="app" class="hide">
<header><b id="home">CNC<span>Verse</span></b><input id="q" type="search" placeholder="Search movies and series"><button class="ghost" id="out">Log out</button></header>
<main id="main"></main>
</div>
<div id="modal" class="hide"><div class="box" id="mbox"></div></div>
<div id="player" class="hide"><button id="pclose" class="ghost">Close</button><video id="v" controls autoplay playsinline></video><div id="pmsg"></div></div>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js"></script>
<script>
var cfg={bases:[]},hls=null,rowsHtmlCache=null;
function g(id){return document.getElementById(id)}
function el(tag,cls,txt){var e=document.createElement(tag);if(cls)e.className=cls;if(txt!=null)e.textContent=txt;return e}
function api(path){return fetch(path,{credentials:'same-origin'}).then(function(r){if(r.status===401){showLogin();throw new Error('auth')}if(!r.ok)throw new Error('http '+r.status);return r.json()})}
function showLogin(){g('app').classList.add('hide');g('login').classList.remove('hide');g('pw').focus()}
function showApp(){g('login').classList.add('hide');g('app').classList.remove('hide');api('/web/api/config').then(function(c){cfg=c});goHome()}

g('lf').addEventListener('submit',function(ev){ev.preventDefault();g('lerr').textContent='';
 fetch('/web/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'password='+encodeURIComponent(g('pw').value)}).then(function(r){
  if(r.ok){g('pw').value='';showApp()}else if(r.status===429)g('lerr').textContent='Too many tries. Wait a minute.';else g('lerr').textContent='Wrong password'})
 .catch(function(){g('lerr').textContent='Could not reach the bridge'})});
g('out').onclick=function(){fetch('/web/logout',{method:'POST',credentials:'same-origin'}).then(showLogin)};
g('home').onclick=function(){g('q').value='';goHome()};
g('q').addEventListener('keydown',function(e){if(e.key==='Enter'){var s=g('q').value.trim();if(s)search(s);else goHome()}});

function card(it){var c=el('div','card');var i=el('img','p');i.loading='lazy';i.alt='';if(it.poster)i.src=it.poster;c.appendChild(i);
 c.appendChild(el('div','t',it.title));c.appendChild(el('div','s',[it.year,it.type==='tv'?'Series':'Movie',it.rating?('* '+it.rating):''].filter(Boolean).join(' - ')));
 c.onclick=function(){openTitle(it.type,it.id)};return c}
function busy(m){var m2=g('main');m2.textContent='';var d=el('div','meta');d.appendChild(el('span','spin'));d.appendChild(document.createTextNode(m||'Loading'));m2.appendChild(d)}
function goHome(){window.scrollTo(0,0);busy('Loading catalog');api('/web/api/rows').then(function(rows){var m=g('main');m.textContent='';
 if(!rows.length){m.appendChild(el('div','meta','The catalog could not be loaded (TMDB did not answer). Try again in a moment.'));return}
 rows.forEach(function(r){m.appendChild(el('h2',null,r.title));var row=el('div','row');r.items.forEach(function(it){row.appendChild(card(it))});m.appendChild(row)})}).catch(function(e){if(e.message!=='auth')g('main').textContent='Could not load the catalog.'})}
function search(s){window.scrollTo(0,0);busy('Searching');api('/web/api/search?q='+encodeURIComponent(s)).then(function(res){var m=g('main');m.textContent='';m.appendChild(el('h2',null,'Results for "'+s+'"'));
 if(!res.length){m.appendChild(el('div','meta','Nothing found.'));return}var gr=el('div','grid');res.forEach(function(it){gr.appendChild(card(it))});m.appendChild(gr)}).catch(function(e){if(e.message!=='auth')g('main').textContent='Search failed.'})}

function openTitle(type,id){window.scrollTo(0,0);busy('Loading');api('/web/api/title?type='+type+'&id='+id).then(function(t){var m=g('main');m.textContent='';
 var hero=el('div','hero');if(t.backdrop)hero.style.backgroundImage='url('+t.backdrop+')';var sh=el('div','shade');
 if(t.poster){var im=el('img');im.src=t.poster;im.alt='';sh.appendChild(im)}
 var inf=el('div');inf.appendChild(el('h1',null,t.title));
 inf.appendChild(el('div','meta',[t.year,t.type==='tv'?'Series':'Movie',t.runtime?(t.runtime+' min'):'',t.rating?('* '+t.rating):'',(t.genres||[]).join(', ')].filter(Boolean).join(' - ')));
 inf.appendChild(el('p',null,t.overview||''));
 if(t.type==='movie'){var b=el('button',null,'Play');b.onclick=function(){streams('movie',t.id,null,null,t.title)};inf.appendChild(b)}
 sh.appendChild(inf);hero.appendChild(sh);m.appendChild(hero);
 if(t.type==='tv'){var bar=el('div','bar');var sel=el('select');(t.seasons||[]).forEach(function(s){var o=el('option',null,s.name+' ('+s.episodes+')');o.value=s.n;sel.appendChild(o)});bar.appendChild(sel);m.appendChild(bar);
  var list=el('div','eps');m.appendChild(list);
  var load=function(){list.textContent='';list.appendChild(el('div','meta','Loading episodes'));var n=sel.value;
   api('/web/api/season?id='+t.id+'&n='+n).then(function(eps){list.textContent='';eps.forEach(function(e){var r=el('div','ep');var si=el('img');si.loading='lazy';si.alt='';if(e.still)si.src=e.still;r.appendChild(si);
    var d=el('div');d.appendChild(el('b',null,e.e+'. '+e.name));if(e.date)d.appendChild(el('small',null,e.date));if(e.overview)d.appendChild(el('small',null,e.overview.length>180?e.overview.slice(0,180)+'...':e.overview));
    r.appendChild(d);r.onclick=function(){streams('tv',t.id,+n,e.e,t.title+' S'+n+'E'+e.e)};list.appendChild(r)})})};
  sel.onchange=load;if(t.seasons&&t.seasons.length)load()}
 }).catch(function(e){if(e.message!=='auth')g('main').textContent='Could not load this title.'})}

function closeModal(){g('modal').classList.add('hide')}
g('modal').addEventListener('click',function(e){if(e.target===g('modal'))closeModal()});
function fix(u){var b=(cfg.bases||[]).slice().sort(function(a,c){return c.length-a.length});for(var i=0;i<b.length;i++){if(u.indexOf(b[i])===0)return location.origin+u.slice(b[i].length)}return u}
var SKIP=/force refresh|support the project/i;
function streams(type,id,s,e,title){var box=g('mbox');box.textContent='';g('modal').classList.remove('hide');
 var bar=el('div','bar');bar.appendChild(el('h3',null,title));var cl=el('button','ghost','Close');cl.onclick=closeModal;bar.appendChild(cl);box.appendChild(bar);
 var body=el('div');box.appendChild(body);
 var run=function(){body.textContent='';var w=el('div','meta');w.appendChild(el('span','spin'));w.appendChild(document.createTextNode('Searching all sources. This can take up to a minute.'));body.appendChild(w);
  var sid='tmdb:'+id+(s!=null?(':'+s+':'+e):'');
  fetch('/stream/'+(type==='tv'?'series':'movie')+'/'+encodeURIComponent(sid)+'.json').then(function(r){return r.json()}).then(function(d){
   var list=(d.streams||[]).filter(function(x){return x.url&&!SKIP.test((x.name||'')+' '+(x.description||x.title||''))});
   body.textContent='';var top=el('div','bar');top.appendChild(el('span','meta',list.length+' link(s)'));var rb=el('button','ghost','Search again');rb.onclick=run;top.appendChild(rb);body.appendChild(top);
   if(!list.length)body.appendChild(el('div','meta','No playable links yet. The search may still be running: try Search again in a few seconds.'));
   list.forEach(function(x){var row=el('div','st');row.appendChild(el('b',null,(x.name||'Stream').replace(/\n/g,' ')));var d2=x.description||x.title||'';if(d2)row.appendChild(el('pre',null,d2));
    row.onclick=function(){closeModal();play(x,title)};body.appendChild(row)})
  }).catch(function(){body.textContent='';body.appendChild(el('div','meta','The search failed. Try again.'));var rb=el('button',null,'Search again');rb.onclick=run;body.appendChild(rb)})};
 run()}

function isHls(u){return /\.m3u8(\?|#|${'$'})/i.test(u)||(u.indexOf('/media/')>=0&&u.indexOf('/pl/')>0)}
function stopVideo(){var v=g('v');if(hls){hls.destroy();hls=null}v.pause();v.removeAttribute('src');while(v.firstChild)v.removeChild(v.firstChild);v.load()}
function play(x,title){var v=g('v');stopVideo();g('pmsg').textContent='';g('player').classList.remove('hide');document.title=title+' - CNCVerse';
 var url=fix(x.url);var tried=false;
 (x.subtitles||[]).forEach(function(s,i){if(!s.url||s.url.indexOf('http')!==0)return;var t=document.createElement('track');t.kind='subtitles';t.label=s.lang||('Track '+(i+1));t.srclang=(s.lang||'und').slice(0,3);t.src='/subshift?ms=0&u='+encodeURIComponent(s.url);v.appendChild(t)});
 var useHls=function(){if(window.Hls&&Hls.isSupported()){hls=new Hls({maxBufferLength:60});hls.loadSource(url);hls.attachMedia(v);hls.on(Hls.Events.ERROR,function(_,d){if(d.fatal)fail()})}else{v.src=url}};
 var useFile=function(){v.src=url};
 var fail=function(){if(!tried){tried=true;if(hls){hls.destroy();hls=null}if(isHls(url))useFile();else useHls();v.play().catch(function(){});return}g('pmsg').textContent='This link would not play here. Close and pick another one.'};
 v.onerror=fail;
 if(isHls(url))useHls();else useFile();
 v.play().catch(function(){})}
g('pclose').onclick=function(){stopVideo();g('player').classList.add('hide');document.title='CNCVerse'};
document.addEventListener('keydown',function(e){if(e.key==='Escape'){if(!g('player').classList.contains('hide'))g('pclose').click();else closeModal()}});

fetch('/web/api/session',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():{ok:false}}).then(function(s){if(s.ok)showApp();else showLogin()}).catch(showLogin);
</script></body></html>"""
}
