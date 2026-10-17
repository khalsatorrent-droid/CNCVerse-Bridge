package com.cncverse.stremiobridge.server

/** The page served at /home (see [WebSite]): one self-contained file, no build step. No dollar signs in here: this is a Kotlin raw string. */
internal object WebPage {
    val HTML: String = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0a1215">
<title>CNCVerse</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,500..800&display=swap" rel="stylesheet">
<style>
:root{--bg:#0a1215;--s1:#101c20;--s2:#17272c;--line:#223539;--tx:#e8efec;--mut:#8aa09b;--lamp:#ffb23e;--bad:#ff8a7a;
--disp:'Bricolage Grotesque',system-ui,-apple-system,Segoe UI,sans-serif;--r:6px}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;-webkit-font-smoothing:antialiased}
button,input,select{font:inherit;color:inherit}
button{white-space:nowrap;cursor:pointer;border:0;border-radius:var(--r);padding:10px 20px;background:var(--lamp);color:#1a1204;font-weight:650;transition:filter .15s,transform .15s}
button:hover{filter:brightness(1.08)}
button:active{transform:translateY(1px)}
button.quiet{background:rgba(255,255,255,.08);color:var(--tx);font-weight:550}
button.quiet:hover{background:rgba(255,255,255,.14)}
:focus-visible{outline:2px solid var(--lamp);outline-offset:2px}
input{background:var(--s1);border:1px solid var(--line);border-radius:var(--r);padding:11px 14px;outline:none;min-width:0}
input:focus{border-color:var(--lamp)}
.hide{display:none!important}

/* sign in */
#login{min-height:100vh;display:grid;grid-template-columns:1.1fr 1fr;background:radial-gradient(120% 90% at 0% 100%,#173038 0%,transparent 60%),var(--bg)}
#login .brand{display:flex;flex-direction:column;justify-content:flex-end;padding:56px}
.mark{font:700 clamp(54px,9vw,128px)/.9 var(--disp);letter-spacing:-.045em;margin:0}
.mark i{font-style:normal;color:var(--lamp)}
#login .brand p{color:var(--mut);max-width:30ch;margin:18px 0 0}
#login form{display:flex;flex-direction:column;justify-content:center;gap:12px;padding:56px;max-width:440px;width:100%}
#login label{font-weight:600}
.err{color:var(--bad);min-height:20px;font-size:14px}
@media(max-width:820px){#login{grid-template-columns:1fr}#login .brand{padding:40px 24px 0}#login form{padding:24px;max-width:none}}

/* header */
header{position:fixed;top:0;left:0;right:0;z-index:10;display:flex;align-items:center;gap:20px;padding:14px 4vw;background:linear-gradient(180deg,rgba(10,18,21,.95),rgba(10,18,21,0));transition:background .2s}
header.solid{background:rgba(10,18,21,.94);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
.logo{font:700 22px var(--disp);letter-spacing:-.03em;cursor:pointer;white-space:nowrap;background:none;color:var(--tx);padding:0}
.logo i{font-style:normal;color:var(--lamp)}
.sbox{margin-left:auto;display:flex;gap:8px;align-items:center}
#q{width:min(34vw,340px);background:rgba(255,255,255,.07);border-color:transparent}
#q:focus{background:var(--s1)}
main{padding-bottom:60px;min-height:100vh}
.pad{padding:0 4vw}
h2.rt{font:650 21px var(--disp);letter-spacing:-.01em;margin:34px 4vw 12px}

/* hero */
.hero{position:relative;min-height:min(78vh,720px);display:flex;align-items:flex-end;background:var(--s1) center top/cover no-repeat}
.hero:before{content:"";position:absolute;inset:0;background:linear-gradient(0deg,var(--bg) 4%,rgba(10,18,21,.35) 55%,rgba(10,18,21,.7)),linear-gradient(90deg,rgba(10,18,21,.85),rgba(10,18,21,0) 60%)}
.hero .in{position:relative;padding:0 4vw 54px;max-width:760px}
.hero.fade{animation:rise .9s ease both}
@keyframes rise{from{opacity:0}to{opacity:1}}
.hero h1{font:700 clamp(36px,6vw,76px)/.98 var(--disp);letter-spacing:-.04em;margin:0 0 14px;text-wrap:balance}
.facts{color:var(--mut);display:flex;flex-wrap:wrap;gap:6px 18px;margin-bottom:14px}
.facts b{color:var(--lamp);font-weight:650}
.hero p.ov{color:#cfdad6;max-width:56ch;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;margin:0 0 20px}
.acts{display:flex;gap:10px;flex-wrap:wrap}
.hero.sk{background:var(--s1)}
.top-gap{height:84px}
.nav{background:none;color:var(--mut);padding:8px 12px;font-weight:600}
.nav:hover{color:var(--tx);filter:none}
.nav[aria-current=page]{color:var(--lamp)}
.tvbar{display:flex;gap:10px;padding:0 4vw;margin-bottom:6px}
.tvbar input{flex:1;max-width:420px}
.tvl{padding:8px 4vw 14px;margin-top:0}
.ch .p{aspect-ratio:16/9;object-fit:contain;padding:10px}
.more{display:block;margin:26px auto 0}

/* rows */
.rowwrap{position:relative}
.row{display:flex;gap:14px;overflow-x:auto;padding:4px 4vw 12px;scroll-snap-type:x proximity;scrollbar-width:none}
.row::-webkit-scrollbar{display:none}
.arrow{position:absolute;top:0;bottom:12px;width:4vw;min-width:44px;z-index:2;display:flex;align-items:center;justify-content:center;background:linear-gradient(90deg,rgba(10,18,21,.9),transparent);opacity:0;transition:opacity .2s;border-radius:0;padding:0;color:var(--tx);font-size:26px}
.arrow.r{right:0;background:linear-gradient(270deg,rgba(10,18,21,.9),transparent)}
.arrow.l{left:0}
.rowwrap:hover .arrow{opacity:1}
.card{flex:0 0 var(--w,150px);scroll-snap-align:start;cursor:pointer;position:relative;border-radius:var(--r);outline-offset:3px;background:none;padding:0;text-align:left;color:inherit;font-weight:400}
.card:hover{filter:none}
.big .card{--w:190px}
.card .p{width:100%;aspect-ratio:2/3;border-radius:var(--r);background:var(--s2);object-fit:cover;display:block;transition:transform .2s,box-shadow .2s}
.card:hover .p,.card:focus-visible .p{transform:translateY(-4px);box-shadow:0 12px 30px rgba(0,0,0,.55)}
.card .t{margin-top:8px;font-size:13.5px;font-weight:550;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.card .s{font-size:12.5px;color:var(--mut)}
.card .rt{position:absolute;top:8px;left:8px;background:rgba(10,18,21,.82);color:var(--lamp);font-size:12px;font-weight:650;padding:1px 7px;border-radius:99px}
.noimg{display:flex;align-items:center;justify-content:center;text-align:center;padding:10px;color:var(--mut);font-size:13px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:22px 14px;padding:0 4vw}
.grid .card{flex:initial}
.sk-card{flex:0 0 var(--w,150px);aspect-ratio:2/3;border-radius:var(--r);background:linear-gradient(100deg,var(--s1) 30%,var(--s2) 50%,var(--s1) 70%) 0 0/300% 100%;animation:sh 1.4s linear infinite}
@keyframes sh{to{background-position:-300% 0}}
.msg{color:var(--mut);padding:0 4vw;margin:26px 0}

/* title page */
.tp{position:relative;background:var(--s1) center top/cover no-repeat;min-height:480px;display:flex;align-items:flex-end}
.tp:before{content:"";position:absolute;inset:0;background:linear-gradient(0deg,var(--bg) 2%,rgba(10,18,21,.55) 60%,rgba(10,18,21,.8)),linear-gradient(90deg,rgba(10,18,21,.9),rgba(10,18,21,.15) 70%)}
.tp .in{position:relative;display:flex;gap:30px;padding:120px 4vw 34px;align-items:flex-end;width:100%}
.tp img.pp{width:200px;border-radius:var(--r);box-shadow:0 18px 50px rgba(0,0,0,.6);flex:none}
.tp h1{font:700 clamp(32px,5vw,60px)/1 var(--disp);letter-spacing:-.035em;margin:0 0 12px;text-wrap:balance}
.tp p.ov{color:#d3deda;max-width:64ch;margin:0 0 18px}
.tabs{display:flex;gap:6px;overflow-x:auto;padding:22px 4vw 6px;scrollbar-width:none}
.tab{background:none;color:var(--mut);padding:8px 14px;border-radius:99px;font-weight:550;white-space:nowrap;border:1px solid var(--line)}
.tab:hover{color:var(--tx);filter:none}
.tab[aria-selected=true]{background:var(--tx);color:var(--bg);border-color:var(--tx)}
.eps{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:18px 16px;padding:14px 4vw}
.ep{cursor:pointer;background:none;color:inherit;font-weight:400;padding:0;text-align:left}
.ep:hover{filter:none}
.ep .th{position:relative;aspect-ratio:16/9;border-radius:var(--r);background:var(--s2) center/cover;overflow:hidden}
.ep .th b{position:absolute;left:8px;bottom:6px;font:700 28px var(--disp);text-shadow:0 2px 10px rgba(0,0,0,.8)}
.ep .th:after{content:"";position:absolute;inset:0;background:rgba(255,178,62,0);transition:background .15s}
.ep:hover .th:after{background:rgba(255,178,62,.14)}
.ep .en{margin-top:7px;font-weight:600;font-size:14px}
.ep small{display:block;color:var(--mut);font-size:12.5px;line-height:1.4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
@media(max-width:640px){header{gap:10px}.sbox{flex:1}#q{width:auto;flex:1}button.quiet#out{padding:10px 12px}.tp .in{flex-direction:column;align-items:flex-start;padding-top:100px}.tp img.pp{width:120px}}

/* links panel */
#shade{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:20;opacity:0;pointer-events:none;transition:opacity .2s}
#shade.on{opacity:1;pointer-events:auto}
#panel{position:fixed;top:0;right:0;bottom:0;width:min(560px,100vw);background:var(--s1);border-left:1px solid var(--line);z-index:21;transform:translateX(102%);transition:transform .25s ease;display:flex;flex-direction:column}
#panel.on{transform:none}
#ph{padding:20px 22px 12px;border-bottom:1px solid var(--line)}
#ph .tr{display:flex;gap:12px;align-items:flex-start;justify-content:space-between}
#ph h3{font:650 20px/1.2 var(--disp);margin:0}
#ph .sub{color:var(--mut);font-size:13.5px;margin-top:4px}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-top:12px}
.chip{background:none;border:1px solid var(--line);color:var(--mut);padding:4px 12px;border-radius:99px;font-size:13px;font-weight:550}
.chip:hover{filter:none;color:var(--tx)}
.chip[aria-pressed=true]{border-color:var(--lamp);color:var(--lamp)}
#pl{overflow:auto;padding:8px 14px 24px;flex:1}
.st{display:block;width:100%;text-align:left;background:none;color:inherit;font-weight:400;border-radius:var(--r);padding:12px 10px;border-bottom:1px solid var(--line);border-radius:0}
.st:hover{background:var(--s2);filter:none}
.st .h{display:flex;gap:10px;align-items:baseline}
.st .res{flex:none;font:700 13px var(--disp);color:var(--lamp);min-width:46px}
.st .nm{font-weight:600;font-size:14px}
.st pre{margin:4px 0 0 56px;font:12.5px/1.45 system-ui,sans-serif;color:var(--mut);white-space:pre-wrap;word-break:break-word}
.wait{padding:24px 10px;color:var(--mut)}
.bar{height:3px;background:var(--s2);border-radius:3px;overflow:hidden;margin-top:12px}
.bar i{display:block;height:100%;width:40%;background:var(--lamp);border-radius:3px;animation:sl 1.3s ease-in-out infinite}
@keyframes sl{0%{margin-left:-40%}100%{margin-left:100%}}

/* player */
#player{position:fixed;inset:0;z-index:30;background:#000}
#player video{width:100%;height:100%;background:#000;outline:none}
#ptop{position:absolute;top:0;left:0;right:0;display:flex;align-items:center;gap:14px;padding:16px 22px;background:linear-gradient(180deg,rgba(0,0,0,.75),transparent);transition:opacity .3s}
#player.idle #ptop{opacity:0}
#player.idle{cursor:none}
#ptitle{font:600 17px var(--disp)}
#pmsg{position:absolute;bottom:90px;left:0;right:0;text-align:center;color:var(--bad);padding:0 20px;pointer-events:none;text-shadow:0 1px 6px #000}
video::cue{color:#fff;background:transparent;font-weight:700;font-size:1.3em;text-shadow:-1px -1px 2px #000,1px -1px 2px #000,-1px 1px 2px #000,1px 1px 2px #000,0 0 7px #000}

@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}html{scroll-behavior:auto}}
</style></head>
<body>
<div id="login" class="hide">
 <div class="brand"><h1 class="mark">CNC<br><i>Verse</i></h1><p>Your own library, on your own device.</p></div>
 <form id="lf">
  <label for="pw">Password</label>
  <input id="pw" type="password" autocomplete="current-password" autofocus>
  <button type="submit">Open library</button>
  <div class="err" id="lerr" role="alert"></div>
 </form>
</div>

<div id="app" class="hide">
 <header id="hd"><button class="logo" id="home">CNC<i>Verse</i></button>
  <button class="nav" id="navmov" type="button">Movies &amp; series</button><button class="nav" id="navtv" type="button">Live TV</button>
  <div class="sbox"><input id="q" type="search" placeholder="Search movies and series" aria-label="Search"><button class="quiet" id="out">Log out</button></div>
 </header>
 <main id="main"></main>
</div>

<div id="shade"></div>
<aside id="panel" aria-label="Links">
 <div id="ph"><div class="tr"><div><h3 id="pht"></h3><div class="sub" id="phs"></div></div><button class="quiet" id="pcl">Close</button></div><div class="chips" id="chips"></div></div>
 <div id="pl"></div>
</aside>
<div id="player" class="hide"><video id="v" controls autoplay playsinline></video>
 <div id="ptop"><button class="quiet" id="pclose">Back</button><span id="ptitle"></span></div><div id="pmsg"></div></div>

<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js"></script>
<script>
var cfg={bases:[]},hls=null,cur=null,streamRun=0;
function g(id){return document.getElementById(id)}
function el(tag,cls,txt){var e=document.createElement(tag);if(cls)e.className=cls;if(txt!=null)e.textContent=txt;return e}
function api(path){return fetch(path,{credentials:'same-origin'}).then(function(r){if(r.status===401){showLogin();throw new Error('auth')}if(!r.ok)throw new Error('http '+r.status);return r.json()})}
function showLogin(){g('app').classList.add('hide');g('login').classList.remove('hide');g('pw').focus()}
function showApp(){g('login').classList.add('hide');g('app').classList.remove('hide');api('/web/api/config').then(function(c){cfg=c});route()}

g('lf').addEventListener('submit',function(ev){ev.preventDefault();g('lerr').textContent='';
 fetch('/web/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'password='+encodeURIComponent(g('pw').value)}).then(function(r){
  if(r.ok){g('pw').value='';showApp()}else if(r.status===429)g('lerr').textContent='Too many wrong tries. Wait a minute, then try again.';else g('lerr').textContent='That password is wrong. Try again.'})
 .catch(function(){g('lerr').textContent='Could not reach the bridge. Check that it is running.'})});
g('out').onclick=function(){fetch('/web/logout',{method:'POST',credentials:'same-origin'}).then(showLogin)};
g('home').onclick=function(){g('q').value='';location.hash='#/'};
g('navmov').onclick=function(){g('q').value='';location.hash='#/'};g('navtv').onclick=function(){location.hash='#/tv'};
g('q').addEventListener('keydown',function(e){if(e.key==='Enter'){var s=g('q').value.trim();location.hash=s?('#/s/'+encodeURIComponent(s)):'#/'}});
window.addEventListener('scroll',function(){g('hd').classList.toggle('solid',window.scrollY>40)},{passive:true});
window.addEventListener('hashchange',function(){if(!g('app').classList.contains('hide'))route()});

function route(){var h=location.hash.replace(/^#\/?/,'').split('/');window.scrollTo(0,0);closePanel();
 g('navtv').setAttribute('aria-current',h[0]==='tv'?'page':'false');g('navmov').setAttribute('aria-current',h[0]==='tv'?'false':'page');
 if(h[0]==='tv')liveTv();else if(h[0]==='t'&&h[2])openTitle(h[1],h[2]);else if(h[0]==='s'&&h[1])search(decodeURIComponent(h[1]));else goHome()}

function recent(){try{return JSON.parse(localStorage.getItem('cncRecent')||'[]')}catch(e){return[]}}
function remember(t){try{var l=recent().filter(function(x){return !(x.id===t.id&&x.type===t.type)});l.unshift({id:t.id,type:t.type,title:t.title,year:t.year,poster:t.poster});localStorage.setItem('cncRecent',JSON.stringify(l.slice(0,20)))}catch(e){}}

function card(it){var c=el('button','card');c.type='button';
 if(it.poster){var i=el('img','p');i.loading='lazy';i.alt='';i.src=it.poster;c.appendChild(i)}else{c.appendChild(el('div','p noimg',it.title))}
 if(it.rating)c.appendChild(el('span','rt',String(it.rating)));
 c.appendChild(el('div','t',it.title));c.appendChild(el('div','s',[it.year,it.type==='tv'?'Series':'Movie'].filter(Boolean).join('  ')));
 c.onclick=function(){location.hash='#/t/'+it.type+'/'+it.id};return c}
function rowEl(title,items,big){var m=g('main');m.appendChild(el('h2','rt',title));var w=el('div','rowwrap'+(big?' big':''));var row=el('div','row');items.forEach(function(it){row.appendChild(card(it))});
 var l=el('button','arrow l','‹'),r=el('button','arrow r','›');l.type=r.type='button';l.setAttribute('aria-label','Scroll left');r.setAttribute('aria-label','Scroll right');
 l.onclick=function(){row.scrollBy({left:-row.clientWidth*.8,behavior:'smooth'})};r.onclick=function(){row.scrollBy({left:row.clientWidth*.8,behavior:'smooth'})};
 w.appendChild(l);w.appendChild(row);w.appendChild(r);m.appendChild(w)}
function skeleton(){var m=g('main');m.textContent='';m.appendChild(el('div','hero sk'));var row=el('div','row');for(var i=0;i<9;i++)row.appendChild(el('div','sk-card'));m.appendChild(row)}

function goHome(){skeleton();document.title='CNCVerse';api('/web/api/rows').then(function(rows){var m=g('main');m.textContent='';
 if(!rows.length){m.appendChild(el('div','top-gap'));m.appendChild(el('p','msg','The catalog did not load because TMDB did not answer. Reload in a moment.'));return}
 var first=rows[0].items[0];var hero=el('section','hero');m.appendChild(hero);
 if(first)api('/web/api/title?type='+first.type+'&id='+first.id).then(function(t){hero.classList.add('fade');if(t.backdrop)hero.style.backgroundImage='url('+t.backdrop+')';var in_=el('div','in');
  in_.appendChild(el('h1',null,t.title));var f=el('div','facts');if(t.rating)f.appendChild(el('b',null,String(t.rating)));[t.year,t.type==='tv'?'Series':'Movie',(t.genres||[]).slice(0,3).join(', ')].filter(Boolean).forEach(function(x){f.appendChild(el('span',null,x))});in_.appendChild(f);
  in_.appendChild(el('p','ov',t.overview||''));var a=el('div','acts');var b=el('button',null,t.type==='movie'?'Play':'Choose episode');b.onclick=function(){location.hash='#/t/'+t.type+'/'+t.id};a.appendChild(b);in_.appendChild(a);hero.appendChild(in_)}).catch(function(){});
 var rc=recent();if(rc.length)rowEl('Recently opened',rc,true);
 rows.forEach(function(r,i){rowEl(r.title,r.items,i===0&&!rc.length)})}).catch(function(e){if(e.message!=='auth'){g('main').textContent='';g('main').appendChild(el('p','msg','Could not load the catalog. Check the bridge connection and reload.'))}})}

function search(s){document.title=s+' - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));m.appendChild(el('h2','rt','Results for "'+s+'"'));var gr=el('div','grid');for(var i=0;i<12;i++)gr.appendChild(el('div','sk-card'));m.appendChild(gr);
 api('/web/api/search?q='+encodeURIComponent(s)).then(function(res){gr.remove();if(!res.length){m.appendChild(el('p','msg','Nothing matches "'+s+'". Check the spelling or try a shorter title.'));return}
 var g2=el('div','grid');res.forEach(function(it){g2.appendChild(card(it))});m.appendChild(g2)}).catch(function(e){if(e.message!=='auth'){gr.remove();m.appendChild(el('p','msg','Search failed. Try again.'))}})}

function openTitle(type,id){skeleton();api('/web/api/title?type='+type+'&id='+id).then(function(t){remember(t);document.title=t.title+' - CNCVerse';var m=g('main');m.textContent='';
 var tp=el('section','tp');if(t.backdrop)tp.style.backgroundImage='url('+t.backdrop+')';var in_=el('div','in');
 if(t.poster){var pi=el('img','pp');pi.src=t.poster;pi.alt='';in_.appendChild(pi)}
 var inf=el('div');inf.appendChild(el('h1',null,t.title));var f=el('div','facts');if(t.rating)f.appendChild(el('b',null,String(t.rating)));
 [t.year,t.type==='tv'?'Series':'Movie',t.runtime?(t.runtime+' min'):'',(t.genres||[]).join(', ')].filter(Boolean).forEach(function(x){f.appendChild(el('span',null,x))});inf.appendChild(f);
 inf.appendChild(el('p','ov',t.overview||''));
 if(t.type==='movie'){var b=el('button',null,'Play');b.onclick=function(){streams('movie',t.id,null,null,t.title,t.year)};inf.appendChild(b)}
 in_.appendChild(inf);tp.appendChild(in_);m.appendChild(tp);
 if(t.type==='tv'){var tabs=el('div','tabs');tabs.setAttribute('role','tablist');var list=el('div','eps');m.appendChild(tabs);m.appendChild(list);var seasons=t.seasons||[];
  var pick=function(n){Array.prototype.forEach.call(tabs.children,function(x){x.setAttribute('aria-selected',String(+x.dataset.n===n))});list.textContent='';list.appendChild(el('p','msg','Loading episodes'));
   api('/web/api/season?id='+t.id+'&n='+n).then(function(eps){list.textContent='';if(!eps.length)list.appendChild(el('p','msg','This season has no episodes listed yet.'));
    eps.forEach(function(e){var r=el('button','ep');r.type='button';var th=el('div','th');if(e.still)th.style.backgroundImage='url('+e.still+')';th.appendChild(el('b',null,String(e.e)));r.appendChild(th);
     r.appendChild(el('div','en',e.name));if(e.overview)r.appendChild(el('small',null,e.overview));r.onclick=function(){streams('tv',t.id,n,e.e,t.title,t.year)};list.appendChild(r)})}).catch(function(){})};
  seasons.forEach(function(s){var b=el('button','tab',s.name);b.type='button';b.setAttribute('role','tab');b.dataset.n=s.n;b.onclick=function(){pick(s.n)};tabs.appendChild(b)});
  if(seasons.length)pick(seasons[0].n)}
 }).catch(function(e){if(e.message!=='auth'){g('main').textContent='';g('main').appendChild(el('p','msg','Could not load this title. Go back and try again.'))}})}

/* live TV: every channel of every TV catalog, A-Z */
var tvq='',tvl='';
function liveTv(){document.title='Live TV - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));m.appendChild(el('h2','rt','Live TV'));
 var bar=el('div','tvbar');var qi=document.createElement('input');qi.type='search';qi.placeholder='Search channels';qi.setAttribute('aria-label','Search channels');qi.value=tvq;bar.appendChild(qi);m.appendChild(bar);
 var chips=el('div','chips tvl');m.appendChild(chips);var info=el('p','msg');m.appendChild(info);var gr=el('div','grid');m.appendChild(gr);var more=el('button','quiet more','Show more');more.type='button';more.classList.add('hide');m.appendChild(more);
 var off=0,run=0;
 var load=function(reset){if(reset){off=0;gr.textContent='';}var my=++run;info.textContent=off?info.textContent:'Loading channels. The first time can take half a minute.';more.classList.add('hide');
  api('/web/api/tv?q='+encodeURIComponent(tvq)+'&l='+encodeURIComponent(tvl)+'&offset='+off).then(function(d){if(my!==run)return;
   d.items.forEach(function(c){var b=el('button','card ch');b.type='button';if(c.poster){var i=el('img','p');i.loading='lazy';i.alt='';i.src=c.poster;i.onerror=function(){var n=el('div','p noimg',c.name);i.replaceWith(n)};b.appendChild(i)}else{b.appendChild(el('div','p noimg',c.name))}
    b.appendChild(el('div','t',c.name));b.onclick=function(){openChannel(c)};gr.appendChild(b)});
   off+=d.items.length;info.textContent=d.total?(off+' of '+d.total+' channels'):'No channel matches. Try another spelling or letter.';
   if(off<d.total)more.classList.remove('hide')}).catch(function(e){if(e.message!=='auth')info.textContent='Could not load the channels. Reload in a moment.'})};
 var letters=['','#'].concat('ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split(''));
 letters.forEach(function(l){var c=el('button','chip',l===''?'All':l);c.type='button';c.dataset.l=l;c.onclick=function(){tvl=l;mark();load(true)};chips.appendChild(c)});
 var mark=function(){Array.prototype.forEach.call(chips.children,function(c){c.setAttribute('aria-pressed',String(c.dataset.l===tvl))})};mark();
 var t=null;qi.addEventListener('input',function(){clearTimeout(t);t=setTimeout(function(){tvq=qi.value.trim();load(true)},300)});
 more.onclick=function(){load(false)};load(true)}
function openChannel(c){var run=++streamRun;g('pht').textContent=c.name;g('phs').textContent='Live TV';g('chips').textContent='';var pl=g('pl');g('panel').classList.add('on');g('shade').classList.add('on');pl.textContent='';
 var w=el('div','wait','Opening the channel');w.appendChild(el('div','bar')).appendChild(el('i'));pl.appendChild(w);
 fetch('/stream/tv/'+encodeURIComponent(c.id)+'.json').then(function(r){return r.json()}).then(function(d){if(run!==streamRun)return;
  var list=(d.streams||[]).filter(function(x){return x.url&&!SKIP.test((x.name||'')+' '+(x.description||x.title||''))});
  if(list.length===1){closePanel();play(list[0],c.name)}else render(list,c.name,function(){openChannel(c)})
 }).catch(function(){if(run!==streamRun)return;pl.textContent='';pl.appendChild(el('div','wait','The channel did not answer. Try again.'))})}

/* links panel */
function closePanel(){streamRun++;g('panel').classList.remove('on');g('shade').classList.remove('on')}
g('shade').onclick=closePanel;g('pcl').onclick=closePanel;
function fix(u){var b=(cfg.bases||[]).slice().sort(function(a,c){return c.length-a.length});for(var i=0;i<b.length;i++){if(u.indexOf(b[i])===0)return location.origin+u.slice(b[i].length)}return u}
var SKIP=/force refresh|support the project/i;
function resOf(x){var m=((x.name||'')+' '+(x.description||x.title||'')).match(/(2160|1440|1080|720|576|480|360)p/i);if(m)return m[1]+'p';if(/\b4k\b/i.test((x.name||'')+(x.description||'')))return '2160p';return ''}
function streams(type,id,s,e,title,year){var run=++streamRun;g('pht').textContent=title;g('phs').textContent=[year,type==='tv'?'Series':'Movie'].filter(Boolean).join('  ');g('chips').textContent='';
 var pl=g('pl');g('panel').classList.add('on');g('shade').classList.add('on');
 var go=function(){var my=++streamRun;g('chips').textContent='';pl.textContent='';var w=el('div','wait','Searching every source. This can take up to a minute.');w.appendChild(el('div','bar')).appendChild(el('i'));pl.appendChild(w);
  var sid='tmdb:'+id+(s!=null?(':'+s+':'+e):'');
  fetch('/stream/'+(type==='tv'?'series':'movie')+'/'+encodeURIComponent(sid)+'.json').then(function(r){return r.json()}).then(function(d){if(my!==streamRun)return;
   var list=(d.streams||[]).filter(function(x){return x.url&&!SKIP.test((x.name||'')+' '+(x.description||x.title||''))});render(list,title,go)
  }).catch(function(){if(my!==streamRun)return;pl.textContent='';pl.appendChild(el('div','wait','The search failed. Try again.'));var b=el('button',null,'Search again');b.onclick=go;pl.appendChild(b)})};
 go()}
function render(list,title,again){var pl=g('pl'),chips=g('chips');pl.textContent='';chips.textContent='';
 if(!list.length){var w=el('div','wait','No playable links yet. Some sources answer late, so search again in a few seconds.');pl.appendChild(w);var b=el('button',null,'Search again');b.onclick=again;w.appendChild(el('br'));w.appendChild(b);return}
 var set={};list.forEach(function(x){var r=resOf(x);if(r)set[r]=1});var rs=Object.keys(set).sort(function(a,b){return parseInt(b)-parseInt(a)});var sel='';
 var draw=function(){pl.textContent='';var n=0;list.forEach(function(x){var r=resOf(x);if(sel&&r!==sel)return;n++;var row=el('button','st');row.type='button';var h=el('div','h');h.appendChild(el('span','res',r||'Auto'));h.appendChild(el('span','nm',(x.name||'Link').replace(/\s*\n\s*/g,'  ')));row.appendChild(h);
   var d=x.description||x.title||'';if(d)row.appendChild(el('pre',null,d));row.onclick=function(){closePanel();play(x,title)};pl.appendChild(row)});
  Array.prototype.forEach.call(chips.children,function(c){c.setAttribute('aria-pressed',String((c.dataset.r||'')===sel))})};
 if(rs.length){[''].concat(rs).forEach(function(r){var c=el('button','chip',r||('All '+list.length));c.type='button';c.dataset.r=r;c.onclick=function(){sel=r;draw()};chips.appendChild(c)});var rb=el('button','chip','Search again');rb.type='button';rb.dataset.r='*';rb.onclick=again;chips.appendChild(rb)}
 draw()}

/* player */
function isHls(u){return /\.m3u8(\?|#|${'$'})/i.test(u)||(u.indexOf('/media/')>=0&&u.indexOf('/pl/')>0)}
function stopVideo(){var v=g('v');if(hls){hls.destroy();hls=null}v.pause();v.removeAttribute('src');while(v.firstChild)v.removeChild(v.firstChild);v.load()}
var idleT=null;function wake(){var p=g('player');p.classList.remove('idle');clearTimeout(idleT);idleT=setTimeout(function(){if(!g('v').paused)p.classList.add('idle')},2800)}
g('player').addEventListener('mousemove',wake);g('player').addEventListener('touchstart',wake,{passive:true});
function play(x,title){var v=g('v');stopVideo();g('pmsg').textContent='';g('ptitle').textContent=title;g('player').classList.remove('hide');wake();
 var url=fix(x.url);var tried=false;
 (x.subtitles||[]).forEach(function(s,i){if(!s.url||s.url.indexOf('http')!==0)return;var t=document.createElement('track');t.kind='subtitles';t.label=s.lang||('Track '+(i+1));t.srclang=(s.lang||'und').slice(0,3);t.src='/subshift?ms=0&u='+encodeURIComponent(s.url);v.appendChild(t)});
 var useHls=function(){if(window.Hls&&Hls.isSupported()){hls=new Hls({maxBufferLength:60});hls.loadSource(url);hls.attachMedia(v);hls.on(Hls.Events.ERROR,function(_,d){if(d.fatal)fail()})}else{v.src=url}};
 var useFile=function(){v.src=url};
 var fail=function(){if(!tried){tried=true;if(hls){hls.destroy();hls=null}if(isHls(url))useFile();else useHls();v.play().catch(function(){});return}g('pmsg').textContent='This link will not play in the browser. Go back and pick another one.'};
 v.onerror=fail;
 if(isHls(url))useHls();else useFile();
 v.play().catch(function(){});v.focus()}
function closePlayer(){stopVideo();g('player').classList.add('hide')}
g('pclose').onclick=closePlayer;
document.addEventListener('keydown',function(e){var open=!g('player').classList.contains('hide');
 if(e.key==='Escape'){if(open)closePlayer();else closePanel();return}
 if(!open||e.target.tagName==='BUTTON')return;var v=g('v');
 if(e.key===' '){e.preventDefault();v.paused?v.play():v.pause()}else if(e.key==='ArrowRight'){v.currentTime+=10}else if(e.key==='ArrowLeft'){v.currentTime-=10}
 else if(e.key==='f'){document.fullscreenElement?document.exitFullscreen():g('player').requestFullscreen()}else if(e.key==='m'){v.muted=!v.muted}});

fetch('/web/api/session',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():{ok:false}}).then(function(s){if(s.ok)showApp();else showLogin()}).catch(showLogin);
</script></body></html>"""
}
