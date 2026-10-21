package com.cncverse.stremiobridge.server

/**
 * The page served at /home (see [WebSite]): one self-contained file, no build step. It is kept in pieces and joined when first
 * asked for, because one string constant cannot be longer than 65535 bytes. The player has its own file ([WebPlayer]).
 * No dollar signs in the raw strings: they are written as the character literal.
 */
internal object WebPage {
    val HTML: String by lazy { listOf(TOP, WebPlayer.CSS, STYLE_END, WebPlayer.MARKUP, SCRIPT_START, WebPlayer.JS, SCRIPT_END).joinToString("") }

    private val TOP = """<!doctype html>
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
.sbox{margin:0 0 0 auto;display:flex;gap:8px;align-items:center}
.srch{padding:10px 16px}
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
@media(max-width:640px){header{flex-wrap:wrap;gap:6px 8px;padding:10px 4vw;background:rgba(10,18,21,.94)}.logo{order:1;font-size:20px}.nav{order:2;padding:8px 8px;font-size:14px}#out{order:3;margin-left:auto;padding:8px 12px}.sbox{order:4;flex:1 1 100%;margin:0}#q{width:auto;flex:1}.top-gap{height:118px}.hero{padding-top:100px}.tp .in{flex-direction:column;align-items:flex-start;padding-top:140px}.tp img.pp{width:120px}}

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

.tvh{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 16px;margin:30px 4vw 10px}
.tvh h2{margin:0;font:700 34px var(--disp);letter-spacing:-.03em}
.tvh .cnt{color:var(--mut);font-size:14px}
.chrow{display:flex;gap:12px;overflow-x:auto;padding:2px 4vw 12px;scrollbar-width:none}
.chrow::-webkit-scrollbar{display:none}
.chrow .chan{flex:0 0 172px}
.chgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(172px,1fr));gap:12px;padding:0 4vw}
.chan{position:relative;background:var(--s1);border:1px solid var(--line);border-radius:var(--r);overflow:hidden;transition:border-color .15s,transform .15s}
.chan:hover,.chan:focus-within{border-color:var(--lamp);transform:translateY(-2px)}
.chan .open{display:block;width:100%;background:none;padding:0;text-align:left;color:inherit;font-weight:400;border-radius:0}
.chan .open:hover{filter:none}
.chan .lg{aspect-ratio:16/10;background:var(--s2);display:flex;align-items:center;justify-content:center;font:700 30px var(--disp);color:var(--mut);padding:14px}
.chan .lg img{max-width:100%;max-height:100%;object-fit:contain}
.chan .cn{padding:9px 12px 11px;font-size:13.5px;font-weight:600;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:3.3em}
.chan .src{position:absolute;left:8px;top:8px;background:rgba(10,18,21,.8);color:var(--mut);font-size:11px;padding:1px 7px;border-radius:99px}
.chan .fav{position:absolute;right:6px;top:6px;width:32px;height:32px;padding:0;border-radius:99px;background:rgba(10,18,21,.75);color:var(--mut);font-size:17px;line-height:1}
.chan .fav:hover{filter:none;color:var(--lamp)}
.chan .fav[aria-pressed=true]{color:var(--lamp)}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}html{scroll-behavior:auto}}
"""

    private val STYLE_END = """
/* library features */
.pgb{position:absolute;left:0;right:0;bottom:0;height:4px;background:rgba(255,255,255,.22)}
.pgb i{display:block;height:100%;background:var(--lamp)}
.card .pw{position:relative;border-radius:var(--r);overflow:hidden}
.card .x{position:absolute;top:6px;right:6px;width:28px;height:28px;padding:0;border-radius:99px;background:rgba(10,18,21,.8);color:var(--mut);font-size:15px;line-height:1;opacity:0}
.card:hover .x,.card:focus-within .x{opacity:1}
.cw .card{--w:230px}.cw .card .p{aspect-ratio:16/9}
.tagline{color:var(--lamp);font-style:italic;margin:-4px 0 12px}
.btns{display:flex;gap:10px;flex-wrap:wrap;margin-top:6px}
.btns button.on{background:var(--tx);color:var(--bg)}
.cast{display:flex;gap:16px;overflow-x:auto;padding:4px 4vw 10px;scrollbar-width:none}
.cast::-webkit-scrollbar{display:none}
.cm{flex:0 0 96px;text-align:center}
.cm .ph{width:84px;height:84px;border-radius:50%;margin:0 auto 6px;background:var(--s2) center/cover no-repeat;display:flex;align-items:center;justify-content:center;color:var(--mut);font-weight:700}
.cm b{display:block;font-size:12.5px;line-height:1.25}.cm span{display:block;font-size:11.5px;color:var(--mut);line-height:1.25}
.ep.seen .th{opacity:.55}
.ep .ok{position:absolute;right:8px;top:8px;background:rgba(10,18,21,.85);color:var(--lamp);border-radius:99px;font-size:12px;padding:1px 8px;font-weight:650}
.ep .th{position:relative}
#modal{position:fixed;inset:0;z-index:40;background:rgba(0,0,0,.82);display:flex;align-items:center;justify-content:center;padding:18px}
#modal .mbox{position:relative;width:min(960px,100%);max-height:94vh;overflow:auto;background:var(--s1);border:1px solid var(--line);border-radius:12px;padding:18px}
#modal .mx{position:absolute;top:10px;right:10px;z-index:2}
#modal iframe{width:100%;aspect-ratio:16/9;border:0;border-radius:8px;display:block}
#modal h3{font:650 20px var(--disp);margin:2px 0 14px}
.setr{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:11px 0;border-bottom:1px solid var(--line)}
.setr small{display:block;color:var(--mut)}
.setr select{background:var(--s2);color:var(--tx);border:1px solid var(--line);border-radius:6px;padding:7px 10px}
.setr input[type=checkbox]{width:20px;height:20px;accent-color:var(--lamp)}
.kbd{color:var(--mut);font-size:13px;line-height:1.7;margin-top:12px}
.st .tags{margin-left:56px}
.gear{padding:8px 12px}
@media(max-width:640px){.cw .card{--w:180px}.gear{order:3}}

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
  <form class="sbox" id="sf" role="search"><input id="q" type="search" enterkeyhint="search" placeholder="Search movies and series" aria-label="Search"><button class="srch" id="sbtn" type="submit" aria-label="Search">Search</button></form>
  <button class="quiet gear" id="cfgb" type="button" aria-label="Settings">Settings</button><button class="quiet" id="out" type="button">Log out</button>
 </header>
 <main id="main"></main>
</div>

<div id="modal" class="hide" role="dialog" aria-modal="true"><div class="mbox"><button class="quiet mx" id="mx" type="button">Close</button><div id="mbody"></div></div></div>
<div id="shade"></div>
<aside id="panel" aria-label="Links">
 <div id="ph"><div class="tr"><div><h3 id="pht"></h3><div class="sub" id="phs"></div><div class="sub" id="pst"></div></div><button class="quiet" id="pcl">Close</button></div><div class="chips" id="chips"></div></div>
 <div id="pl"></div>
</aside>
"""

    private val SCRIPT_START = """

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

function openSettings(){var d=el('div');d.appendChild(el('h3',null,'Settings'));
 function toggle(label,sub,key,def){var r=el('label','setr');var t=el('div');t.appendChild(el('b',null,label));if(sub)t.appendChild(el('small',null,sub));r.appendChild(t);var c=document.createElement('input');c.type='checkbox';c.checked=!!S(key,def);c.onchange=function(){Sset(key,c.checked)};r.appendChild(c);d.appendChild(r)}
 function choose(label,sub,key,def,opts){var r=el('div','setr');var t=el('div');t.appendChild(el('b',null,label));if(sub)t.appendChild(el('small',null,sub));r.appendChild(t);var s=document.createElement('select');opts.forEach(function(o){var op=document.createElement('option');op.value=o[0];op.textContent=o[1];if(String(S(key,def))===o[0])op.selected=true;s.appendChild(op)});s.onchange=function(){Sset(key,s.value);subStyle()};r.appendChild(s);d.appendChild(r)}
 toggle('Play the next episode by itself','Shows a 10 second countdown when an episode ends','autonext',true);
 toggle('Put links that play here first','Links that mention HEVC, DTS or other formats this browser cannot play go below the others','compat',true);
 var langs=[['off','Off']].concat(LANGS.map(function(l){return [l,l]}));
 choose('Subtitle language','Used when a title has subtitles in this language','sublang','English',langs);
 choose('Subtitle size',null,'subsz','m',[['s','Small'],['m','Medium'],['l','Large'],['xl','Huge']]);
 choose('Subtitle colour',null,'subcol','white',[['white','White'],['yellow','Yellow'],['cyan','Cyan']]);
 choose('Subtitle background',null,'subbg','shadow',[['shadow','Outline'],['box','Dim box'],['solid','Black box']]);
 var cl=el('div','btns');var b1=el('button','quiet','Clear continue watching');b1.type='button';b1.onclick=function(){lsSet('cncPos','{}');Sset('done',{});refreshMarks();b1.textContent='Cleared'};
 var b2=el('button','quiet','Clear My list');b2.type='button';b2.onclick=function(){lsSet('cncList','[]');b2.textContent='Cleared'};cl.appendChild(b1);cl.appendChild(b2);d.appendChild(cl);
 d.appendChild(el('div','kbd','Player keys: Space play/pause, ← → 10 s, ↑ ↓ volume, F full screen, M mute, C subtitles, A audio, Q quality, < > speed, N / P next / previous, I stats, 0-9 jump. Double-tap the sides of the picture to skip 10 s.'));
 openModal(d)}
g('cfgb').onclick=openSettings;

g('out').onclick=function(){fetch('/web/logout',{method:'POST',credentials:'same-origin'}).then(showLogin)};
g('home').onclick=function(){g('q').value='';location.hash='#/'};
g('navmov').onclick=function(){g('q').value='';location.hash='#/'};g('navtv').onclick=function(){location.hash='#/tv'};
g('sf').addEventListener('submit',function(e){e.preventDefault();var s=g('q').value.trim();g('q').blur();if(location.hash==='#/s/'+encodeURIComponent(s))route();else location.hash=s?('#/s/'+encodeURIComponent(s)):'#/'});
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

/* what you watched, what you saved */
function cwEntries(){var o=posAll();return Object.keys(o).map(function(k){var e=o[k];e.k=k;return e}).filter(function(e){return e.id&&e.t&&e.d}).sort(function(a,b){return b.at-a.at}).slice(0,16)}
var cwHost=null;
function cwRow(){var l=cwEntries();if(!l.length)return null;var m=el('div');m.appendChild(el('h2','rt','Continue watching'));var w=el('div','rowwrap cw');var row=el('div','row');
 l.forEach(function(e){var c=el('button','card');c.type='button';var pw=el('div','pw');if(e.poster){var i=el('img','p');i.loading='lazy';i.alt='';i.src=e.poster;pw.appendChild(i)}else pw.appendChild(el('div','p noimg',e.title));
  var pb=el('div','pgb');pb.appendChild(el('i'));pb.firstChild.style.width=Math.min(100,e.t/e.d*100)+'%';pw.appendChild(pb);c.appendChild(pw);
  c.appendChild(el('div','t',e.title));c.appendChild(el('div','s',(e.type==='tv'?('S'+e.s+' E'+e.e+'  '):'')+fmt(e.d-e.t)+' left'));
  var x=el('button','x','×');x.type='button';x.title='Remove from this list';x.onclick=function(ev){ev.stopPropagation();var o=posAll();delete o[e.k];posPut(o);refreshMarks()};c.appendChild(x);
  c.onclick=function(){streams(e.type,e.id,e.type==='tv'?e.s:null,e.type==='tv'?e.e:null,e.title,e.year)};row.appendChild(c)});
 w.appendChild(row);m.appendChild(w);return m}
function listRow(){var l=myList();if(!l.length)return null;var m=el('div');m.appendChild(el('h2','rt','My list'));var w=el('div','rowwrap');var row=el('div','row');l.forEach(function(it){row.appendChild(card(it))});w.appendChild(row);m.appendChild(w);return m}
function refreshMarks(){if(cwHost&&document.body.contains(cwHost)){var n=cwRow();cwHost.textContent='';if(n)cwHost.appendChild(n)}
 Array.prototype.forEach.call(document.querySelectorAll('.ep[data-k]'),epMark);if(window.contUpd)window.contUpd()}
function epMark(r){var k=r.dataset.k,p=posAll()[k],dn=(S('done',{})||{})[k];r.classList.toggle('seen',!!dn);var th=r.querySelector('.th'),o=r.querySelector('.pgb'),ok=r.querySelector('.ok');if(o)o.remove();if(ok)ok.remove();
 if(p&&p.d){var pb=el('div','pgb');pb.appendChild(el('i'));pb.firstChild.style.width=Math.min(100,p.t/p.d*100)+'%';th.appendChild(pb)}else if(dn){th.appendChild(el('span','ok','Watched'))}}
function goHome(){skeleton();document.title='CNCVerse';api('/web/api/rows').then(function(rows){var m=g('main');m.textContent='';
 if(!rows.length){m.appendChild(el('div','top-gap'));m.appendChild(el('p','msg','The catalog did not load because TMDB did not answer. Reload in a moment.'));return}
 var first=rows[0].items[0];var hero=el('section','hero');m.appendChild(hero);
 if(first)api('/web/api/title?type='+first.type+'&id='+first.id).then(function(t){hero.classList.add('fade');if(t.backdrop)hero.style.backgroundImage='url('+t.backdrop+')';var in_=el('div','in');
  in_.appendChild(el('h1',null,t.title));var f=el('div','facts');if(t.rating)f.appendChild(el('b',null,String(t.rating)));[t.year,t.type==='tv'?'Series':'Movie',(t.genres||[]).slice(0,3).join(', ')].filter(Boolean).forEach(function(x){f.appendChild(el('span',null,x))});in_.appendChild(f);
  in_.appendChild(el('p','ov',t.overview||''));var a=el('div','acts');var b=el('button',null,t.type==='movie'?'Play':'Choose episode');b.onclick=function(){location.hash='#/t/'+t.type+'/'+t.id};a.appendChild(b);
  if(t.trailer){var tb=el('button','quiet','Trailer');tb.type='button';tb.onclick=function(){trailer(t)};a.appendChild(tb)}in_.appendChild(a);hero.appendChild(in_)}).catch(function(){});
 cwHost=el('div');m.appendChild(cwHost);var cw=cwRow();if(cw)cwHost.appendChild(cw);var ml=listRow();if(ml)m.appendChild(ml);
 var rc=recent();if(rc.length)rowEl('Recently opened',rc,true);
 rows.forEach(function(r,i){rowEl(r.title,r.items,i===0&&!rc.length&&!cw&&!ml)});
 var token=m;api('/web/api/rows?more=1').then(function(more){if(g('main')!==token||!document.body.contains(m)||location.hash.replace('#','').replace(/^\//,'')!=='')return;more.forEach(function(r){rowEl(r.title,r.items,false)})}).catch(function(){})
 }).catch(function(e){if(e.message!=='auth'){g('main').textContent='';g('main').appendChild(el('p','msg','Could not load the catalog. Check the bridge connection and reload.'))}})}

function search(s){document.title=s+' - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));m.appendChild(el('h2','rt','Results for "'+s+'"'));var gr=el('div','grid');for(var i=0;i<12;i++)gr.appendChild(el('div','sk-card'));m.appendChild(gr);
 api('/web/api/search?q='+encodeURIComponent(s)).then(function(res){gr.remove();if(!res.length){m.appendChild(el('p','msg','Nothing matches "'+s+'". Check the spelling or try a shorter title.'));return}
 var g2=el('div','grid');res.forEach(function(it){g2.appendChild(card(it))});m.appendChild(g2)}).catch(function(e){if(e.message!=='auth'){gr.remove();m.appendChild(el('p','msg','Search failed. Try again.'))}})}

var curMeta=null;
function myList(){return store('cncList')}
function inList(t){return myList().some(function(x){return String(x.id)===String(t.id)&&x.type===t.type})}
function toggleList(t){var l=myList(),n=l.filter(function(x){return !(String(x.id)===String(t.id)&&x.type===t.type)});if(n.length===l.length)n.unshift({id:t.id,type:t.type,title:t.title,year:t.year,poster:t.poster});put('cncList',n.slice(0,100))}
function openModal(node){var b=g('mbody');b.textContent='';b.appendChild(node);g('modal').classList.remove('hide')}
function closeModal(){g('modal').classList.add('hide');g('mbody').textContent=''}
g('mx').onclick=closeModal;g('modal').addEventListener('click',function(e){if(e.target===g('modal'))closeModal()});
function trailer(t){if(!t.trailer)return;var f=document.createElement('iframe');f.src='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(t.trailer)+'?autoplay=1&rel=0';f.allow='autoplay; encrypted-media; picture-in-picture; fullscreen';f.allowFullscreen=true;f.title='Trailer';openModal(f)}
function castRow(m,cast){if(!cast||!cast.length)return;m.appendChild(el('h2','rt','Cast'));var r=el('div','cast');cast.forEach(function(c){var d=el('div','cm');var p=el('div','ph');if(c.photo)p.style.backgroundImage='url('+c.photo+')';else p.textContent=c.name.slice(0,1);d.appendChild(p);d.appendChild(el('b',null,c.name));if(c.as)d.appendChild(el('span',null,c.as));r.appendChild(d)});m.appendChild(r)}
function openTitle(type,id){skeleton();api('/web/api/title?type='+type+'&id='+id).then(function(t){remember(t);curMeta=t;document.title=t.title+' - CNCVerse';var m=g('main');m.textContent='';
 var tp=el('section','tp');if(t.backdrop)tp.style.backgroundImage='url('+t.backdrop+')';var in_=el('div','in');
 if(t.poster){var pi=el('img','pp');pi.src=t.poster;pi.alt='';in_.appendChild(pi)}
 var inf=el('div');inf.appendChild(el('h1',null,t.title));if(t.tagline)inf.appendChild(el('div','tagline',t.tagline));var f=el('div','facts');if(t.rating)f.appendChild(el('b',null,String(t.rating)));
 [t.year,t.type==='tv'?'Series':'Movie',t.runtime?(t.runtime+' min'):'',(t.genres||[]).join(', ')].filter(Boolean).forEach(function(x){f.appendChild(el('span',null,x))});inf.appendChild(f);
 inf.appendChild(el('p','ov',t.overview||''));
 var bt=el('div','btns');inf.appendChild(bt);var key='tmdb:'+t.id;
 var playB=null;
 if(t.type==='movie'){playB=el('button',null,'Play');playB.type='button';playB.onclick=function(){streams('movie',t.id,null,null,t.title,t.year)};bt.appendChild(playB)}
 else{playB=el('button','hide',''); playB.type='button';bt.appendChild(playB)}
 window.contUpd=function(){var p=t.type==='movie'?posAll()[key]:null;if(t.type==='tv'){var best=null,o=posAll();Object.keys(o).forEach(function(k){if(k.indexOf(key+':')===0&&(!best||o[k].at>best.at))best=o[k]});p=best}
  if(p&&p.d){playB.classList.remove('hide');playB.textContent=(t.type==='tv'?('Continue S'+p.s+' E'+p.e):'Resume')+' ('+fmt(p.d-p.t)+' left)';playB.onclick=function(){streams(t.type,t.id,t.type==='tv'?p.s:null,t.type==='tv'?p.e:null,t.title,t.year)}}
  else if(t.type==='movie'){playB.textContent='Play';playB.onclick=function(){streams('movie',t.id,null,null,t.title,t.year)}}else playB.classList.add('hide')};window.contUpd();
 var lb=el('button','quiet');lb.type='button';var lbSet=function(){lb.textContent=inList(t)?'✓ In My list':'+ My list';lb.classList.toggle('on',inList(t))};lb.onclick=function(){toggleList(t);lbSet()};lbSet();bt.appendChild(lb);
 if(t.trailer){var tb=el('button','quiet','Trailer');tb.type='button';tb.onclick=function(){trailer(t)};bt.appendChild(tb)}
 in_.appendChild(inf);tp.appendChild(in_);m.appendChild(tp);
 if(t.type==='tv'){var tabs=el('div','tabs');tabs.setAttribute('role','tablist');var list=el('div','eps');m.appendChild(tabs);m.appendChild(list);var seasons=t.seasons||[];
  var pk=function(n){Array.prototype.forEach.call(tabs.children,function(x){x.setAttribute('aria-selected',String(+x.dataset.n===n))});list.textContent='';list.appendChild(el('p','msg','Loading episodes'));
   epList(t.id,n).then(function(eps){list.textContent='';if(!eps.length)list.appendChild(el('p','msg','This season has no episodes listed yet.'));
    eps.forEach(function(e){var r=el('button','ep');r.type='button';r.dataset.k=key+':'+n+':'+e.e;var th=el('div','th');if(e.still)th.style.backgroundImage='url('+e.still+')';th.appendChild(el('b',null,String(e.e)));r.appendChild(th);
     r.appendChild(el('div','en',e.name));if(e.overview)r.appendChild(el('small',null,e.overview));r.onclick=function(){streams('tv',t.id,n,e.e,t.title,t.year)};list.appendChild(r);epMark(r)})}).catch(function(){})};
  seasons.forEach(function(s){var b=el('button','tab',s.name);b.type='button';b.setAttribute('role','tab');b.dataset.n=s.n;b.onclick=function(){pk(s.n)};tabs.appendChild(b)});
  if(seasons.length){var cs=seasons[0].n,o=posAll(),bs=null;Object.keys(o).forEach(function(k){if(k.indexOf(key+':')===0&&(!bs||o[k].at>bs.at))bs=o[k]});if(bs&&seasons.some(function(s){return s.n===bs.s}))cs=bs.s;pk(cs)}}
 castRow(m,t.cast);
 if(t.similar&&t.similar.length)rowEl('More like this',t.similar,false);
 }).catch(function(e){if(e.message!=='auth'){g('main').textContent='';g('main').appendChild(el('p','msg','Could not load this title. Go back and try again.'))}})}

/* live TV: every channel of every TV catalog, A-Z, same names from several sources merged */
var tvq='',tvl='',tvMode=false,tvCtx={list:[],i:0},cur=null;
function store(k){try{return JSON.parse(localStorage.getItem(k)||'[]')}catch(e){return[]}}
function put(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}
function isFav(c){return store('cncFav').some(function(f){return f.name===c.name})}
function toggleFav(c){var l=store('cncFav'),n=l.filter(function(f){return f.name!==c.name});if(n.length===l.length)n.unshift({name:c.name,poster:c.poster,ids:c.ids});put('cncFav',n.slice(0,80))}
function addSeen(c){var l=store('cncTv').filter(function(f){return f.name!==c.name});l.unshift({name:c.name,poster:c.poster,ids:c.ids});put('cncTv',l.slice(0,16))}
function ini(n){return n.replace(/[^A-Za-z0-9]/g,'').slice(0,2).toUpperCase()||'TV'}
function chanTile(c,arr){var b=el('div','chan');var o=el('button','open');o.type='button';var lg=el('div','lg');
 if(c.poster){var im=el('img');im.loading='lazy';im.alt='';im.src=c.poster;im.onerror=function(){im.remove();lg.textContent=ini(c.name)};lg.appendChild(im)}else lg.textContent=ini(c.name);
 o.appendChild(lg);o.appendChild(el('div','cn',c.name));o.onclick=function(){tune(arr,arr.indexOf(c))};var hv=null;o.addEventListener('mouseenter',function(){hv=setTimeout(function(){prefetchCh(c)},180)});o.addEventListener('mouseleave',function(){clearTimeout(hv)});o.addEventListener('focus',function(){prefetchCh(c)});b.appendChild(o);
 if(c.ids&&c.ids.length>1)b.appendChild(el('span','src',c.ids.length+' sources'));
 var f=el('button','fav','★');f.type='button';f.setAttribute('aria-label','Favourite');f.setAttribute('aria-pressed',String(isFav(c)));f.onclick=function(e){e.stopPropagation();toggleFav(c);f.setAttribute('aria-pressed',String(isFav(c)))};b.appendChild(f);return b}
function liveTv(){document.title='Live TV - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));
 var head=el('div','tvh');head.appendChild(el('h2',null,'Live TV'));var cnt=el('span','cnt');head.appendChild(cnt);m.appendChild(head);
 var bar=el('div','tvbar');var qi=document.createElement('input');qi.type='search';qi.placeholder='Search channels';qi.setAttribute('aria-label','Search channels');qi.value=tvq;bar.appendChild(qi);m.appendChild(bar);
 var chips=el('div','chips tvl');m.appendChild(chips);var shelves=el('div');m.appendChild(shelves);var info=el('p','msg');m.appendChild(info);var gr=el('div','chgrid');m.appendChild(gr);var more=el('button','quiet more','Show more');more.type='button';more.classList.add('hide');m.appendChild(more);
 var off=0,run=0,all=[];
 var shelf=function(title,list){if(!list.length)return;shelves.appendChild(el('h2','rt',title));var r=el('div','chrow');list.forEach(function(c){r.appendChild(chanTile(c,list))});shelves.appendChild(r)};
 var drawShelves=function(){shelves.textContent='';if(tvq||tvl)return;shelf('Favourites',store('cncFav'));shelf('Recently watched',store('cncTv'));if(shelves.firstChild)shelves.appendChild(el('h2','rt','All channels'))};
 var load=function(reset){if(reset){off=0;gr.textContent='';all=[];drawShelves()}var my=++run;if(!off)info.textContent='Loading channels. The first time can take half a minute.';more.classList.add('hide');
  api('/web/api/tv?q='+encodeURIComponent(tvq)+'&l='+encodeURIComponent(tvl)+'&offset='+off).then(function(d){if(my!==run)return;
   d.items.forEach(function(c){all.push(c);gr.appendChild(chanTile(c,all))});off+=d.items.length;
   cnt.textContent=d.total+' channel'+(d.total===1?'':'s');info.textContent=d.total?'':'No channel matches. Try another spelling or letter.';
   if(off<d.total)more.classList.remove('hide')}).catch(function(e){if(e.message!=='auth')info.textContent='Could not load the channels. Reload in a moment.'})};
 var letters=['','#'].concat('ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split(''));
 var mark=function(){Array.prototype.forEach.call(chips.children,function(c){c.setAttribute('aria-pressed',String(c.dataset.l===tvl))})};
 letters.forEach(function(l){var c=el('button','chip',l===''?'All':l);c.type='button';c.dataset.l=l;c.onclick=function(){tvl=l;mark();load(true)};chips.appendChild(c)});mark();
 var t=null;qi.addEventListener('input',function(){clearTimeout(t);t=setTimeout(function(){tvq=qi.value.trim();load(true)},300)});
 more.onclick=function(){load(false)};load(true)}

function tune(arr,i){tvMode=true;tvCtx={list:arr,i:i};tuneCur()}
var tvCache={};
function tvSrc(id){var c=tvCache[id];if(c&&Date.now()-c.t<90000)return c.p;var p=fetch('/web/api/tvstreams?id='+encodeURIComponent(id),{credentials:'same-origin'}).then(function(r){if(r.status===401){showLogin();throw new Error('auth')}return r.json()}).then(function(d){return(d.streams||[]).filter(function(x){return x.url&&!SKIP.test((x.name||'')+' '+(x.description||x.title||''))})}).catch(function(){return[]});
 tvCache[id]={t:Date.now(),p:p};return p}
function prefetchCh(c){(c.ids||[]).slice(0,3).forEach(tvSrc)}
function tuneCur(){var c=tvCtx.list[tvCtx.i];if(!c)return;addSeen(c);var run=++streamRun;shell(c.name,'Tuning in');setTvUi(true);
 var seen={},started=false,answered=0,total=c.ids.length;cur={list:[],idx:0,title:c.name,auto:true,done:false};
 c.ids.forEach(function(id){tvSrc(id).then(function(list){if(run!==streamRun)return;answered++;
  list.forEach(function(x){if(!seen[x.url]){seen[x.url]=1;cur.list.push(x)}});if(answered===total)cur.done=true;
  if(started&&cur.stalled&&cur.next){cur.stalled=false;cur.next()}
  else if(!started&&cur.list.length){started=true;cur.idx=0;play(cur.list[0],c.name)}
  else if(!started&&answered===total){g('pnote').textContent='';g('pmsg').textContent='No link answered for '+c.name+'. Try another channel.'}
  if(cur.list.length>1)g('plinks').classList.remove('hide')})})}
function setTvUi(on){var many=on&&tvCtx.list.length>1;g('pprev').classList.toggle('hide',!many);g('pnext').classList.toggle('hide',!many);g('plinks').classList.toggle('hide',!(on||(cur&&cur.list&&cur.list.length>1)));g('pch').classList.toggle('hide',!many)}
function zap(d){if(!tvMode)return;var n=tvCtx.i+d;if(n<0||n>=tvCtx.list.length)return;tvCtx.i=n;tuneCur()}
g('pprev').onclick=function(){zap(-1)};g('pnext').onclick=function(){zap(1)};
g('plinks').onclick=function(){if(cur&&cur.list.length){render(cur.list,cur.title,tvMode?tuneCur:null,tvMode);g('pht').textContent=cur.title;g('phs').textContent='Choose another link';g('panel').classList.add('on');g('shade').classList.add('on')}};

/* links panel */
function closePanel(){streamRun++;g('panel').classList.remove('on');g('shade').classList.remove('on')}
g('shade').onclick=closePanel;g('pcl').onclick=closePanel;
function fix(u){var b=(cfg.bases||[]).slice().sort(function(a,c){return c.length-a.length});for(var i=0;i<b.length;i++){if(u.indexOf(b[i])===0)return location.origin+u.slice(b[i].length)}return u}
var SKIP=/force refresh|support the project/i;
function resOf(x){var m=((x.name||'')+' '+(x.description||x.title||'')).match(/(2160|1440|1080|720|576|480|360)p/i);if(m)return m[1]+'p';if(/\b4k\b/i.test((x.name||'')+(x.description||'')))return '2160p';return ''}
function streams(type,id,s,e,title,year,auto){var sid='tmdb:'+id+(s!=null?(':'+s+':'+e):'');var t=type==='tv'?'series':'movie';var sub2=type==='tv'?('S'+s+' E'+e):'';
 pendingCtx={type:type,id:id,s:s,e:e,title:title,year:year,key:sid,poster:(curMeta&&String(curMeta.id)===String(id))?curMeta.poster:'',sub2:sub2};subPool=[];P.localSubs=[];
 streamRun++;g('pht').textContent=title+(sub2?'  '+sub2:'');g('phs').textContent=[year,type==='tv'?'Series':'Movie'].filter(Boolean).join('  ');g('pst').textContent='';g('chips').textContent='';
 var pl=g('pl');if(!auto){g('panel').classList.add('on');g('shade').classList.add('on')}else{g('pnote').textContent='Finding the best link for '+title+'  '+sub2;g('pspin').classList.remove('hide')}
 var go=function(){var my=++streamRun;g('chips').textContent='';pl.textContent='';var w=el('div','wait','Starting the search');w.appendChild(el('div','bar')).appendChild(el('i'));pl.appendChild(w);g('pst').textContent='';
  var sig='',t0=Date.now(),first=true,manual=!auto;
  var poll=function(){if(my!==streamRun)return;if(auto&&!manual&&g('player').classList.contains('hide')){streamRun++;return}
   fetch('/web/api/streams?type='+t+'&id='+encodeURIComponent(sid)+(first?'&start=1':''),{credentials:'same-origin'}).then(function(r){if(r.status===401){showLogin();throw new Error('auth')}if(!r.ok)throw new Error('http');return r.json()}).then(function(d){if(my!==streamRun)return;first=false;
    addSubs(d.subs);(d.streams||[]).forEach(function(x){addSubs(x.subtitles)});
    var list=orderList((d.streams||[]).filter(function(x){return x.url&&!SKIP.test((x.name||'')+' '+(x.description||x.title||''))}));
    var fin=d.finished||(Date.now()-t0>150000);
    g('pst').textContent=fin?('Search complete: '+(d.total?(d.done+' of '+d.total+' sources answered, '):'')+list.length+' link(s)'):('Still searching: '+d.done+' of '+d.total+' sources answered, '+list.length+' link(s) so far');
    if(auto&&!manual&&list.length&&(fin||Date.now()-t0>3500)){streamRun++;pick(list,list[0],title,false);return}
    if(auto&&!manual&&fin&&!list.length){manual=true;g('pnote').textContent='';g('panel').classList.add('on');g('shade').classList.add('on')}
    if(!auto||manual){var ns=list.length+':'+(list[0]&&list[0].url)+':'+(list[list.length-1]&&list[list.length-1].url);
     if(ns!==sig){sig=ns;if(list.length)render(list,title,go,false,true)}
     if(!list.length&&fin){render(list,title,go)}}
    if(!fin)setTimeout(poll,first?400:1500)
   }).catch(function(e){if(my!==streamRun||e.message==='auth')return;if(!sig){pl.textContent='';pl.appendChild(el('div','wait','The search failed. Try again.'));var b=el('button',null,'Search again');b.onclick=go;pl.appendChild(b)}else setTimeout(poll,3000)})};
  poll()};
 go()}
var keepSel='';function render(list,title,again,live,refresh){var pl=g('pl'),chips=g('chips');var top=pl.scrollTop;pl.textContent='';chips.textContent='';
 if(!list.length){var w=el('div','wait','No playable links yet. Some sources answer late, so search again in a few seconds.');pl.appendChild(w);var b=el('button',null,'Search again');b.onclick=again;w.appendChild(el('br'));w.appendChild(b);return}
 var set={};list.forEach(function(x){var r=resOf(x);if(r)set[r]=1});var rs=Object.keys(set).sort(function(a,b){return parseInt(b)-parseInt(a)});var sel=refresh?keepSel:'';if(!refresh)keepSel='';
 var draw=function(){pl.textContent='';var n=0;list.forEach(function(x){var r=resOf(x);if(sel&&r!==sel)return;n++;var row=el('button','st');row.type='button';var h=el('div','h');h.appendChild(el('span','res',r||'Auto'));h.appendChild(el('span','nm',(x.name||'Link').replace(/\s*\n\s*/g,'  ')));row.appendChild(h);
   var rk=live?[]:risks(x),ln=live?[]:langsOf(x);if(rk.length||ln.length){var tg=el('div','tags');if(rk.length)tg.appendChild(el('span','tag warn','May not play here: '+rk.join(', ')));if(ln.length)tg.appendChild(el('span','tag lang',ln.join(' \u00b7 ')));row.appendChild(tg)}
   var d=x.description||x.title||'';if(d)row.appendChild(el('pre',null,d));row.onclick=function(){closePanel();pick(list,x,title,!!live)};pl.appendChild(row)});
  Array.prototype.forEach.call(chips.children,function(c){c.setAttribute('aria-pressed',String((c.dataset.r||'')===sel))})};
 if(rs.length){[''].concat(rs).forEach(function(r){var c=el('button','chip',r||('All '+list.length));c.type='button';c.dataset.r=r;c.onclick=function(){sel=r;keepSel=r;draw()};chips.appendChild(c)});var rb=el('button','chip','Search again');rb.type='button';rb.dataset.r='*';rb.onclick=again;chips.appendChild(rb)}
 draw();pl.scrollTop=top}

"""

    private val SCRIPT_END = """
fetch('/web/api/session',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():{ok:false}}).then(function(s){if(s.ok)showApp();else showLogin()}).catch(showLogin);
</script></body></html>"""
}
