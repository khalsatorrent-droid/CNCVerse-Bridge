package com.cncverse.stremiobridge.server

/** The video player of the website (see [WebPage]): styles, markup and script. No dollar signs in the raw strings. */
internal object WebPlayer {
    val CSS = """/* player */
#player{position:fixed;inset:0;z-index:30;background:#000;color:#fff;user-select:none;-webkit-user-select:none;touch-action:manipulation;overflow:hidden}
#player video{position:absolute;inset:0;width:100%;height:100%;background:#000;outline:none;object-fit:contain}
#v{--cs:1.3em;--cc:#fff;--cb:transparent;--csh:-1px -1px 2px #000,1px -1px 2px #000,-1px 1px 2px #000,1px 1px 2px #000,0 0 7px #000}
#psubcv{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:1}
video::cue{color:var(--cc,#fff);background:var(--cb,transparent);font-size:var(--cs,1.3em);font-weight:700;font-family:Roboto,'Segoe UI',Arial,sans-serif;text-shadow:var(--csh,0 0 6px #000)}
#pclick{position:absolute;inset:0;z-index:1}
#player.idle #pclick{cursor:none}
#ptop,#pbar{position:absolute;left:0;right:0;z-index:3;transition:opacity .25s}
#ptop{top:0;display:flex;align-items:center;gap:12px;padding:14px 18px;background:linear-gradient(180deg,rgba(0,0,0,.8),transparent)}
#pbar{bottom:0;padding:34px 16px 12px;background:linear-gradient(0deg,rgba(0,0,0,.85),transparent)}
#player.idle #ptop,#player.idle #pbar{opacity:0;pointer-events:none}
#ptop .tt{min-width:0;flex:1}
#ptitle{font:650 17px var(--disp);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#psub{font-size:12.5px;color:#c4d2cd;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#ptop .pr{display:flex;gap:6px}
.pb{background:none;color:#fff;padding:0;min-width:40px;height:40px;border-radius:99px;display:inline-flex;align-items:center;justify-content:center;font-weight:600;font-size:14px;gap:4px}
.pb:hover{background:rgba(255,255,255,.16);filter:none}
.pb.tx{padding:0 12px;min-width:0;border-radius:8px}
.pb.off svg{opacity:.45}
.pb[aria-pressed=true]{color:var(--lamp)}
#pseek{position:relative;height:22px;display:flex;align-items:center;cursor:pointer;touch-action:none}
#pseek:before{content:"";position:absolute;left:0;right:0;height:4px;border-radius:4px;background:rgba(255,255,255,.28);transition:height .12s}
#pseek:hover:before,#pseek.drag:before{height:6px}
#pbuf,#pprog{position:absolute;left:0;height:4px;border-radius:4px;width:0;pointer-events:none;transition:height .12s}
#pseek:hover #pbuf,#pseek:hover #pprog,#pseek.drag #pbuf,#pseek.drag #pprog{height:6px}
#pbuf{background:rgba(255,255,255,.4)}
#pprog{background:var(--lamp)}
#pknob{position:absolute;width:14px;height:14px;border-radius:50%;background:var(--lamp);margin-left:-7px;left:0;transform:scale(0);transition:transform .12s;pointer-events:none}
#pseek:hover #pknob,#pseek.drag #pknob,#pseek:focus-visible #pknob{transform:scale(1)}
#ptip{position:absolute;bottom:26px;transform:translateX(-50%);background:rgba(0,0,0,.85);padding:2px 8px;border-radius:6px;font-size:12.5px;pointer-events:none;white-space:nowrap}
#prow{display:flex;align-items:center;gap:2px;flex-wrap:wrap}
#prow .sp{flex:1}
#ptime{font-size:13.5px;margin:0 8px;white-space:nowrap;font-variant-numeric:tabular-nums}
#plive{background:#e53935;color:#fff;font-size:12px;font-weight:700;padding:2px 9px;border-radius:99px;margin:0 6px}
#plive.back{background:#555}
#pvol{width:78px;accent-color:var(--lamp);padding:0;background:none;border:0;height:24px}
#pspin{position:absolute;inset:0;z-index:2;display:flex;align-items:center;justify-content:center;pointer-events:none}
#pspin i{width:54px;height:54px;border-radius:50%;border:4px solid rgba(255,255,255,.25);border-top-color:var(--lamp);animation:spn .8s linear infinite}
@keyframes spn{to{transform:rotate(360deg)}}
#pnote{position:absolute;top:50%;left:0;right:0;margin-top:44px;text-align:center;color:#d6e2de;padding:0 20px;pointer-events:none;text-shadow:0 1px 6px #000;z-index:3}
#pmsg{position:absolute;bottom:130px;left:0;right:0;text-align:center;color:var(--bad);padding:0 20px;pointer-events:none;text-shadow:0 1px 6px #000;z-index:3}
#pfix{position:absolute;bottom:130px;left:0;right:0;text-align:center;padding:0 20px;color:#d6e2de;text-shadow:0 1px 6px #000;z-index:4}
#pfix button,#pfix a{display:inline-block;margin:8px 4px 0;padding:8px 14px;border-radius:8px;border:1px solid #ffffff55;background:#000a;color:#fff;text-decoration:none;font:inherit;cursor:pointer}
#ptoast{position:absolute;left:18px;bottom:112px;z-index:4;background:rgba(20,28,31,.94);border:1px solid var(--line);padding:9px 14px;border-radius:10px;display:flex;gap:12px;align-items:center;max-width:min(520px,calc(100vw - 36px));font-size:14px}
#ptoast button{padding:4px 10px;font-size:13px}
#pskipb{position:absolute;right:18px;bottom:112px;z-index:4;background:rgba(20,28,31,.96);border:1px solid var(--line);padding:14px 16px;border-radius:12px;max-width:340px}
#pskipb b{display:block;margin-bottom:2px}
#pskipb span{color:var(--mut);font-size:13px;display:block;margin-bottom:10px}
#pskipb button{margin-right:8px;padding:7px 14px}
#pstats{position:absolute;top:70px;left:18px;z-index:4;background:rgba(0,0,0,.78);padding:10px 14px;border-radius:8px;font:12.5px/1.55 ui-monospace,Menlo,Consolas,monospace;white-space:pre;pointer-events:none}
#amenu{position:absolute;right:16px;bottom:106px;z-index:5;background:rgba(16,28,32,.97);border:1px solid var(--line);border-radius:12px;padding:6px;min-width:230px;max-width:min(340px,calc(100vw - 32px));max-height:min(64vh,520px);overflow:auto;backdrop-filter:blur(8px)}
#amenu button{display:block;width:100%;text-align:left;background:none;color:var(--tx);font-weight:500;padding:9px 12px;border-radius:6px;white-space:normal}
#amenu button:hover{background:var(--s2);filter:none}
#amenu button[aria-checked=true]{color:var(--lamp)}
#amenu button[aria-checked=true]:before{content:"\2713  "}
#amenu small{display:block;color:var(--mut);font-size:12px;font-weight:400}
#amenu .h{color:var(--mut);font-size:12px;padding:8px 12px 4px;text-transform:uppercase;letter-spacing:.04em}
#amenu .mrow{display:flex;align-items:center;gap:8px;padding:4px 12px}
#amenu .mrow span{flex:1}
#amenu .mrow button{width:auto;display:inline-block;background:rgba(255,255,255,.1);padding:6px 11px}
#pflash{position:absolute;top:50%;margin-top:-30px;z-index:2;font:700 22px var(--disp);background:rgba(0,0,0,.55);border-radius:99px;padding:14px 20px;pointer-events:none;opacity:0}
#pflash.on{animation:fl .55s ease-out}
@keyframes fl{0%{opacity:1}100%{opacity:0}}
.tag{display:inline-block;font:600 11.5px system-ui;padding:1px 8px;border-radius:99px;margin:6px 6px 0 0;background:rgba(255,255,255,.08);color:var(--mut)}
.tag.warn{background:rgba(255,178,62,.14);color:var(--lamp)}
.tag.lang{background:rgba(120,200,255,.12);color:#9fd6ff}
@media(max-width:640px){#pvol{display:none}#pbar{padding:28px 8px 8px}#ptop{padding:10px 8px}.pb{min-width:38px;height:38px}#amenu{right:8px;bottom:100px}#ptime{margin:0 4px}}
"""

    val MARKUP = """<div id="player" class="hide" tabindex="-1">
 <video id="v" playsinline autoplay></video><canvas id="psubcv"></canvas>
 <div id="pclick"></div>
 <div id="pspin" class="hide"><i></i></div><div id="pflash"></div>
 <div id="ptop"><button class="pb" id="pclose" aria-label="Back" type="button">&#8592;</button><div class="tt"><div id="ptitle"></div><div id="psub"></div></div>
  <div class="pr"><button class="pb tx hide" id="pch" type="button">Channels</button><button class="pb tx hide" id="plinks" type="button">Links</button><button class="pb" id="pstat" aria-label="Stats" type="button">i</button></div></div>
 <div id="pnote"></div><div id="pmsg"></div><div id="pfix" class="hide"></div><div id="ptoast" class="hide"></div><div id="pskipb" class="hide"></div><div id="pstats" class="hide"></div>
 <div id="pbar">
  <div id="pseek" role="slider" aria-label="Seek" tabindex="0"><div id="pbuf"></div><div id="pprog"></div><div id="pknob"></div><div id="ptip" class="hide"></div></div>
  <div id="prow">
   <button class="pb" id="pplay" aria-label="Play or pause" type="button"></button>
   <button class="pb hide" id="pprev" aria-label="Previous channel" type="button"></button>
   <button class="pb hide" id="pnext" aria-label="Next channel" type="button"></button>
   <button class="pb tx" id="pb10" aria-label="Back 10 seconds" type="button">&minus;10</button><button class="pb tx" id="pf10" aria-label="Forward 10 seconds" type="button">+10</button>
   <button class="pb" id="pmute" aria-label="Mute" type="button"></button><input id="pvol" type="range" min="0" max="1" step="0.02" aria-label="Volume">
   <span id="ptime"></span><span id="plive" class="hide">LIVE</span><button class="pb tx hide" id="pgolive" type="button">Go live</button>
   <span class="sp"></span>
   <button class="pb tx hide" id="pintro" type="button">Skip intro</button>
   <button class="pb tx hide" id="pnep" type="button">Next episode</button>
   <button class="pb tx hide" id="pspd" aria-haspopup="true" type="button">1x</button>
   <button class="pb hide" id="paud" aria-label="Audio" aria-haspopup="true" type="button"></button>
   <button class="pb hide" id="pcc" aria-label="Subtitles" aria-haspopup="true" type="button"></button>
   <button class="pb tx hide" id="pq" aria-haspopup="true" type="button">Auto</button>
   <button class="pb hide" id="ppip" aria-label="Picture in picture" type="button"></button>
   <button class="pb" id="pmore" aria-label="More" aria-haspopup="true" type="button">&#8943;</button>
   <button class="pb" id="pfs" aria-label="Full screen" type="button"></button>
  </div></div>
 <div id="amenu" class="hide" role="menu"></div>
 <input type="file" id="subfile" accept=".srt,.vtt,.txt" class="hide">
</div>
"""

    val JS = """/* ===== player ===== */
var IC={play:'M8 5v14l11-7z',pause:'M6 19h4V5H6v14zm8-14v14h4V5h-4z',vol:'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4z',fs:'M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z',fsx:'M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z',cc:'M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zM11 11H9.5v-.5h-2v3h2V13H11v1a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1z',aud:'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z',pip:'M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z',prev:'M6 6h2v12H6zm3.5 6 8.5 6V6z',next:'M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z'};
function svg(n){return '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="currentColor" d="'+IC[n]+'"/></svg>'}
[['pplay','play'],['pprev','prev'],['pnext','next'],['pmute','vol'],['pfs','fs'],['pcc','cc'],['paud','aud'],['ppip','pip']].forEach(function(a){g(a[0]).innerHTML=svg(a[1])});

/* settings, remembered in this browser */
function lsGet(k){try{return localStorage.getItem(k)||''}catch(e){return ''}}
function lsSet(k,v){try{localStorage.setItem(k,v)}catch(e){}}
function cfgAll(){try{return JSON.parse(lsGet('cncSet')||'{}')}catch(e){return{}}}
function S(k,d){var c=cfgAll();return c[k]==null?d:c[k]}
function Sset(k,v){var c=cfgAll();c[k]=v;lsSet('cncSet',JSON.stringify(c))}

/* what this browser can decode, and what a link says it contains */
var CAP=(function(){var v=document.createElement('video'),a=document.createElement('audio');function ok(e,t){try{return !!e.canPlayType(t)}catch(x){return false}}
 return {hevc:ok(v,'video/mp4; codecs="hvc1.1.6.L93.B0"')||ok(v,'video/mp4; codecs="hev1.1.6.L93.B0"'),av1:ok(v,'video/mp4; codecs="av01.0.05M.08"'),ac3:ok(a,'audio/mp4; codecs="ac-3"'),eac3:ok(a,'audio/mp4; codecs="ec-3"')}})();
function risks(x){var t=((x.name||'')+' '+(x.description||x.title||'')+' '+(x.url||'')).toLowerCase(),o=[];
 if(!CAP.hevc&&/hevc|x265|h\.?265|hvc1/.test(t))o.push('HEVC video');
 if(!CAP.av1&&/\bav1\b/.test(t))o.push('AV1 video');
 if(/\bdts\b|dts-hd|dts:x|truehd/.test(t))o.push('DTS or TrueHD audio');
 if(!CAP.eac3&&/e-?ac-?3|dd\+|ddp/.test(t))o.push('Dolby Digital Plus audio');
 else if(!CAP.ac3&&/\bac-?3\b|\bdd ?[257]\.[01]/.test(t))o.push('Dolby Digital audio');
 return o}
var LANGS=['Hindi','English','Tamil','Telugu','Malayalam','Kannada','Bengali','Punjabi','Marathi','Urdu','Japanese','Korean','Spanish','French','German','Italian','Russian','Chinese','Arabic','Turkish','Thai'];
function langsOf(x){var t=((x.name||'')+' '+(x.description||x.title||'')).toLowerCase(),o=[];LANGS.forEach(function(l){if(t.indexOf(l.toLowerCase())>=0)o.push(l)});return o}
/* the list as the panel shows it: playable-here links first inside each resolution */
function orderList(list){if(!S('compat',true))return list;var by={},keys=[];list.forEach(function(x){var r=resOf(x)||'-';if(!by[r]){by[r]=[];keys.push(r)}by[r].push(x)});
 var out=[];keys.forEach(function(r){var a=by[r].filter(function(x){return !risks(x).length}),b=by[r].filter(function(x){return risks(x).length});out=out.concat(a,b)});return out}

/* libraries that are only needed for some links are loaded when one comes up */
var libs={};function lib(url,test){if(test())return Promise.resolve();if(libs[url])return libs[url];libs[url]=new Promise(function(ok,no){var s=document.createElement('script');s.src=url;s.onload=ok;s.onerror=function(){delete libs[url];no(new Error('lib'))};document.head.appendChild(s)});return libs[url]}
var MPEGTS='https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.js',DASH='https://cdn.jsdelivr.net/npm/dashjs@4.7.4/dist/dash.all.min.js';

var P={x:null,url:'',kind:'file',live:false,gen:0,key:'',ctx:null,poster:'',sub2:'',title:'',sub:null,subDelay:0,subWant:lsGet('cncSub')||'',startAt:0,fit:'contain',stats:false,localSubs:[]};
var mp=null,eng=null,dash=null,stallT=null,bufT=null,silT=null,idleT=null,menuKind='',seeking=false,dragFrac=0,lastSave=0,toastT=null;
function isHls(u){return /\.m3u8(\?|#|${'$'})/i.test(u)||(u.indexOf('/media/')>=0&&u.indexOf('/pl/')>0)}
function kindOf(u,x){if(isHls(u))return 'hls';if(/\.mpd(\?|#|${'$'})/i.test(u))return 'dash';if(tvMode&&/\.(ts|flv)(\?|#|${'$'})/i.test(u.split('?')[0]+'#'))return 'ts';return 'file'}
function fmt(t){if(!isFinite(t)||t<0)t=0;t=Math.floor(t);var h=Math.floor(t/3600),m=Math.floor(t/60)%60,s=t%60;return (h?h+':'+('0'+m).slice(-2):m)+':'+('0'+s).slice(-2)}
function toast(msg,label,fn,ms){var t=g('ptoast');t.textContent='';t.appendChild(el('span',null,msg));if(label){var b=el('button',null,label);b.type='button';b.onclick=function(){t.classList.add('hide');fn()};t.appendChild(b)}t.classList.remove('hide');clearTimeout(toastT);toastT=setTimeout(function(){t.classList.add('hide')},ms||6500)}
function flash(txt,side){var f=g('pflash');f.textContent=txt;f.style.left=side<0?'18%':(side>0?'auto':'50%');f.style.right=side>0?'18%':'auto';f.style.marginLeft=side===0?'-40px':'0';f.classList.remove('on');void f.offsetWidth;f.classList.add('on')}

/* ----- subtitles: every track any extension found for this title, grouped by language ----- */
var subPool=[];
function normLang(l){l=String(l||'').trim();if(!l)return 'Unknown';var d=l;try{if(/^[a-z]{2,3}(-[A-Za-z]{2,4})?${'$'}/.test(l))d=new Intl.DisplayNames(['en'],{type:'language'}).of(l)||l}catch(e){}return d.charAt(0).toUpperCase()+d.slice(1)}
/* closed-caption / SDH files describe the sounds as well, which suits a dubbed episode */
function isCc(s,u){var t=(String(s.id||'')+' '+String(s.label||s.name||'')+' '+u);try{t=decodeURIComponent(t)}catch(e){}return /(^|[^a-z])(sdh|cc|hearing[ ._-]?impaired|closed[ ._-]?caption(s|ed)?)([^a-z]|${'$'})/i.test(t)}
function isDubNow(){var t=P.x?((P.x.name||'')+' '+(P.x.description||P.x.title||'')):'';if(/(^|[^a-z])dub(bed)?([^a-z]|${'$'})/i.test(t))return true;var a=audList();if(a.length>1){var on=a.filter(function(x){return x.on})[0];
 if(on&&/^(en|eng|english)/.test(String(on.key||'')+' '+String(on.label||'').toLowerCase())&&a.some(function(x){return /^(ja|jp|jpn|japanese)/.test(String(x.key||'')+' '+String(x.label||'').toLowerCase())}))return true}return false}
function bestSub(lang){var gp=subGroups()[lang]||[],i;if(!gp.length)return 0;var dub=lang.toLowerCase()==='english'&&isDubNow();for(i=0;i<gp.length;i++){if(!!gp[i].cc===dub)return i}return 0}
/* the pick made by itself is redone when the audio changes to or from a dub; a pick made by hand is left alone */
function reAuto(){var a=P.autoPick;if(!a||!P.sub||P.sub.lang!==a.lang||P.sub.idx!==a.idx)return;var i=bestSub(a.lang);if(i!==a.idx){P.autoPick={lang:a.lang,idx:i};showSub(a.lang,i)}}
function addSubs(l){var n=0;(l||[]).forEach(function(s){if(!s||!s.url||String(s.url).indexOf('http')!==0)return;var u=String(s.url).split('#')[0];if(subPool.some(function(p){return p.u===u}))return;subPool.push({u:u,full:s.url,lang:normLang(s.lang),cc:isCc(s,u)});n++});if(n){ccAuto();updateBtns()}}
function subGroups(){var gp={};subPool.concat(P.localSubs).forEach(function(s){(gp[s.lang]=gp[s.lang]||[]).push(s)});return gp}
function langOrder(){var gp=subGroups(),k=Object.keys(gp),want=(P.subWant||'').toLowerCase();return k.sort(function(a,b){function w(x){var l=x.toLowerCase();return l===want?0:(l==='english'?1:(x==='Unknown'?9:(x==='My file'?2:5)))}return w(a)-w(b)||a.localeCompare(b)})}
function p2(n){return ('0'+n).slice(-2)}
function shiftVtt(t,ms){return t.replace(/(\d{2}):(\d{2}):(\d{2})[.,](\d{3})/g,function(m,h,mi,s,f){var v=((+h*60+ +mi)*60+ +s)*1000+ +f+ms;if(v<0)v=0;return p2(Math.floor(v/3600000))+':'+p2(Math.floor(v/60000)%60)+':'+p2(Math.floor(v/1000)%60)+'.'+('00'+(v%1000)).slice(-3)})}
function subUrl(s){if(s.text!=null){var t=String(s.text);if(t.indexOf('WEBVTT')!==0)t='WEBVTT\n\n'+t;return URL.createObjectURL(new Blob([shiftVtt(t,P.subDelay)],{type:'text/vtt'}))}return '/subshift?ms='+P.subDelay+'&u='+encodeURIComponent(s.full||s.u)}
function clearTracks(){var v=g('v');Array.prototype.slice.call(v.querySelectorAll('track')).forEach(function(t){try{if(t.track)t.track.mode='disabled'}catch(e){}t.remove()})}
function showSub(lang,idx){clearTracks();var gp=subGroups()[lang];if(!gp||!gp.length){P.sub=null;updateBtns();return}idx=((idx||0)%gp.length+gp.length)%gp.length;var s=gp[idx];P.sub={lang:lang,idx:idx,n:gp.length,s:s};
 var t=document.createElement('track');t.kind='subtitles';t.label=lang;t.srclang='und';t.src=subUrl(s);t.default=true;t.onerror=function(){toast('That subtitle file did not load.',gp.length>1?'Try another version':'',function(){showSub(lang,idx+1)})};g('v').appendChild(t);setTimeout(function(){try{if(t.track)t.track.mode='showing'}catch(e){}},0);updateBtns()}
function subOff(){clearTracks();P.sub=null;if(hls&&hls.subtitleTracks&&hls.subtitleTracks.length)hls.subtitleTrack=-1;updateBtns()}
/* choose the language the viewer wants (the last one picked, else the language set in Settings, else English) as soon as it is available */
function ccAuto(){if(P.sub||P.subDone||g('player').classList.contains('hide'))return;var want=(P.subWant||S('sublang','English')||'').toLowerCase();if(want==='off'||!want){return}
 var gp=subGroups(),k=Object.keys(gp).filter(function(x){return x.toLowerCase()===want})[0];if(k){P.subDone=true;var bi=bestSub(k);P.autoPick={lang:k,idx:bi};showSub(k,bi);return}
 if(hls&&hls.subtitleTracks&&hls.subtitleTracks.length){for(var i=0;i<hls.subtitleTracks.length;i++){var t=hls.subtitleTracks[i];if(String(t.lang||t.name||'').toLowerCase().indexOf(want.slice(0,3))===0){P.subDone=true;hls.subtitleDisplay=true;hls.subtitleTrack=i;return}}}}
/* hardcoded subtitles: the text is painted onto the picture (white, bold, black outline) instead of the browser's own layer */
var burnRaf=0,burnKey='';
function burnOn(){return !!S('subburn',false)}
function burnTracks(on){var v=g('v');Array.prototype.forEach.call(v.textTracks||[],function(t){if(t.kind!=='subtitles'&&t.kind!=='captions')return;
 if(on&&t.mode==='showing'){t._bh=1;t.mode='hidden'}else if(!on&&t._bh&&t.mode==='hidden'){t._bh=0;t.mode='showing'}})}
function burnText(){var v=g('v'),o=[];Array.prototype.forEach.call(v.textTracks||[],function(t){if((t.mode==='hidden'||t.mode==='showing')&&t.activeCues)Array.prototype.forEach.call(t.activeCues,function(c){if(c.text)o.push(c.text)})});
 return o.join('\n').replace(/<[^>]*>/g,'').replace(/&nbsp;/g,' ').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&').replace(/\{[^}]*\}/g,'').trim()}
function burnDraw(){var cv=g('psubcv'),v=g('v'),w=cv.clientWidth,h=cv.clientHeight,d=window.devicePixelRatio||1;if(!w||!h)return;
 burnTracks(true);var txt=burnText(),key=txt+'|'+w+'x'+h+'|'+S('subsz','m')+S('subcol','white')+S('subbg','shadow')+P.fit+'|'+v.videoWidth;if(key===burnKey)return;burnKey=key;
 if(cv.width!==Math.round(w*d)||cv.height!==Math.round(h*d)){cv.width=Math.round(w*d);cv.height=Math.round(h*d)}
 var x=cv.getContext('2d');x.setTransform(d,0,0,d,0,0);x.clearRect(0,0,w,h);if(!txt)return;
 var vw=v.videoWidth||w,vh=v.videoHeight||h,sc=P.fit==='fill'?0:(P.fit==='cover'?Math.max(w/vw,h/vh):Math.min(w/vw,h/vh)),pw=sc?vw*sc:w,ph=sc?vh*sc:h,px=(w-pw)/2,py=(h-ph)/2;
 /* the part of the picture that is on screen */
 var vx=Math.max(0,px),vy=Math.max(0,py),vr=Math.min(w,px+pw),vb=Math.min(h,py+ph),aw=vr-vx,ah=vb-vy;
 var k={s:.75,m:1,l:1.3,xl:1.7}[S('subsz','m')]||1,fs=Math.max(12,Math.round(ah*.05*k)),col={white:'#fff',yellow:'#ffe066',cyan:'#7de8ff'}[S('subcol','white')]||'#fff',bg=S('subbg','shadow');
 x.font='700 '+fs+"px Roboto,'Segoe UI',Arial,sans-serif";x.textAlign='center';x.textBaseline='alphabetic';x.lineJoin='round';
 var lines=[];txt.split('\n').forEach(function(L){L=L.trim();if(!L)return;var cur='';L.split(/\s+/).forEach(function(wd){var t=cur?cur+' '+wd:wd;if(cur&&x.measureText(t).width>aw*.9){lines.push(cur);cur=wd}else cur=t});if(cur)lines.push(cur)});
 var lh=fs*1.22,y=vb-ah*.065-(lines.length-1)*lh,cx=vx+aw/2;
 lines.forEach(function(L,i){var yy=y+i*lh;
  if(bg==='box'||bg==='solid'){var tw=x.measureText(L).width;x.fillStyle=bg==='box'?'rgba(0,0,0,.7)':'#000';x.fillRect(cx-tw/2-fs*.3,yy-fs*.95,tw+fs*.6,fs*1.2)}
  else{x.strokeStyle='#000';x.lineWidth=Math.max(2,fs*.16);x.strokeText(L,cx,yy)}
  x.fillStyle=col;x.fillText(L,cx,yy)})}
function burnLoop(){burnRaf=0;if(!burnOn())return;burnDraw();burnRaf=requestAnimationFrame(burnLoop)}
function burnApply(){var cv=g('psubcv');if(burnOn()){cv.style.display='block';burnKey='';burnTracks(true);if(!burnRaf)burnRaf=requestAnimationFrame(burnLoop)}
 else{cv.style.display='none';burnKey='';try{cv.getContext('2d').clearRect(0,0,cv.width,cv.height)}catch(e){}burnTracks(false);if(burnRaf){cancelAnimationFrame(burnRaf);burnRaf=0}}}
function burnToggle(){Sset('subburn',!burnOn());burnApply();toast(burnOn()?'Subtitles are now drawn onto the picture.':'Subtitles are back to the normal layer.')}
function subStyle(){var v=g('v'),sz={s:'1em',m:'1.3em',l:'1.7em',xl:'2.2em'}[S('subsz','m')]||'1.3em',col={white:'#fff',yellow:'#ffe066',cyan:'#7de8ff'}[S('subcol','white')]||'#fff',bg=S('subbg','shadow');
 v.style.setProperty('--cs',sz);v.style.setProperty('--cc',col);v.style.setProperty('--cb',bg==='box'?'rgba(0,0,0,.7)':(bg==='solid'?'#000':'transparent'));v.style.setProperty('--csh',bg==='shadow'?'-1px -1px 2px #000,1px -1px 2px #000,-1px 1px 2px #000,1px 1px 2px #000,0 0 7px #000':'none')}
g('subfile').onchange=function(){var f=this.files&&this.files[0];this.value='';if(!f)return;var r=new FileReader();r.onload=function(){var s={lang:'My file',u:'local:'+f.name,text:String(r.result||'')};P.localSubs=P.localSubs.filter(function(x){return x.u!==s.u}).concat([s]);P.subDone=true;P.subWant='My file';showSub('My file',P.localSubs.length-1);toast('Loaded '+f.name)};r.readAsText(f)};

/* ----- audio: tracks inside the stream, or the same title as another link with the language you want ----- */
function audName(t,n){var l=String(t.lang||t.language||'').toLowerCase();var nm=t.name||t.label||'';var d='';try{if(l&&l!=='und')d=new Intl.DisplayNames(['en'],{type:'language'}).of(l)||''}catch(e){}
 if(d&&nm&&nm.toLowerCase().indexOf(d.toLowerCase())<0)return d+' - '+nm;return nm||d||('Audio '+(n+1))}
function pickAud(list){var want=lsGet('cncAudio'),ok=list.filter(function(a){return a.supported}),i;if(want)for(i=0;i<ok.length;i++)if(String(ok[i].lang||ok[i].idx).toLowerCase()===want)return ok[i];for(i=0;i<ok.length;i++)if(ok[i].def)return ok[i];return ok[0]||list[0]}
function audList(){var v=g('v'),out=[],i;
 if(eng){return eng.audios().map(function(a){return {label:a.label,key:String(a.lang||a.id).toLowerCase(),on:a.on,set:function(){if(a.supported)eng.setAudio(a.id)}}})}
 if(hls&&hls.audioTracks&&hls.audioTracks.length>1){hls.audioTracks.forEach(function(t,k){out.push({label:audName(t,k),key:String(t.lang||t.name||k).toLowerCase(),on:hls.audioTrack===k,set:function(){hls.audioTrack=k}})})}
 else if(dash&&dash.getTracksFor&&dash.getTracksFor('audio').length>1){var dt=dash.getTracksFor('audio'),cu=dash.getCurrentTrackFor('audio');dt.forEach(function(t,k){out.push({label:audName({lang:t.lang,name:(t.labels&&t.labels[0]&&t.labels[0].text)||''},k),key:String(t.lang||k).toLowerCase(),on:cu&&cu.index===t.index,set:function(){dash.setCurrentTrack(t)}})})}
 else if(v.audioTracks&&v.audioTracks.length>1){var at=v.audioTracks;for(i=0;i<at.length;i++)(function(k){out.push({label:audName(at[k],k),key:String(at[k].language||at[k].label||k).toLowerCase(),on:!!at[k].enabled,set:function(){for(var j=0;j<at.length;j++)at[j].enabled=(j===k)}})})(i)}
 return out}
function otherLangs(){if(P.live||!cur||!cur.list)return[];var mine=P.x?langsOf(P.x):[],res=P.x?resOf(P.x):'',out=[];
 LANGS.forEach(function(L){var c=cur.list.filter(function(x){return x!==P.x&&langsOf(x).indexOf(L)>=0});if(!c.length)return;
  c.sort(function(a,b){function sc(x){return (resOf(x)===res?0:2)+(risks(x).length?1:0)}return sc(a)-sc(b)});out.push({lang:L,link:c[0],n:c.length,here:mine.indexOf(L)>=0})});return out}
var audApplied=false;
function audRefresh(){var l=audList();updateBtns();if(l.length<2)return;if(!audApplied){audApplied=true;var want=lsGet('cncAudio');if(want){for(var k=0;k<l.length;k++){if(l[k].key===want&&!l[k].on){l[k].set();return}}}}}

/* ----- quality ----- */
function otherRes(){if(P.live||!cur||!cur.list||!P.x)return[];var mine=langsOf(P.x),me=resOf(P.x),by={};
 cur.list.forEach(function(x){var r=resOf(x);if(!r)return;var l=langsOf(x);if(mine.length&&l.length&&!l.some(function(k){return mine.indexOf(k)>=0}))return;
  var o=by[r];if(!o||(x===P.x)||(o!==P.x&&risks(x).length<risks(o).length))by[r]=x});
 return Object.keys(by).sort(function(a,b){return parseInt(b)-parseInt(a)}).map(function(r){return {res:r,link:by[r],here:by[r]===P.x||r===me}})}
function qList(){var out=[];
 if(dash&&dash.getBitrateInfoListFor){var bl=dash.getBitrateInfoListFor('video');if(bl.length>1){var auto=dash.getSettings().streaming.abr.autoSwitchBitrate.video;out.push({label:'Auto',on:auto,set:function(){dash.updateSettings({streaming:{abr:{autoSwitchBitrate:{video:true}}}})}});bl.slice().reverse().forEach(function(b){out.push({label:(b.height||Math.round(b.bitrate/1000)+' kbps')+(b.height?'p':''),on:!auto&&dash.getQualityFor('video')===b.qualityIndex,set:function(){dash.updateSettings({streaming:{abr:{autoSwitchBitrate:{video:false}}}});dash.setQualityFor('video',b.qualityIndex)}})})}return out}
 if(!hls||!hls.levels||hls.levels.length<2){var orr=otherRes();return orr.length<2?out:orr.map(function(o){return {label:o.res,sub:o.here?'':'switches the link, keeps your place',on:o.here,set:function(){if(!o.here)switchLink(o.link)}}})}
 var best={};hls.levels.forEach(function(L,i){var h=L.height||0;if(!h)return;if(best[h]==null||(L.bitrate||0)>(hls.levels[best[h]].bitrate||0))best[h]=i});
 var hs=Object.keys(best).map(Number).sort(function(a,b){return b-a});
 if(hs.length<2){var ids=[];hls.levels.forEach(function(L,i){if(L.bitrate)ids.push(i)});ids.sort(function(a,b){return hls.levels[b].bitrate-hls.levels[a].bitrate});
  return ids.length<2?[]:[{label:'Auto',on:hls.autoLevelEnabled,set:function(){hls.currentLevel=-1;lsSet('cncQuality','auto')}}].concat(ids.map(function(i){var L=hls.levels[i];return {label:Math.round(L.bitrate/1000)+' kbps',on:!hls.autoLevelEnabled&&hls.currentLevel===i,set:function(){hls.currentLevel=i}}}))}
 out.push({label:'Auto',on:hls.autoLevelEnabled,set:function(){hls.currentLevel=-1;lsSet('cncQuality','auto')}});
 hs.forEach(function(h){var i=best[h];out.push({label:h+'p',on:!hls.autoLevelEnabled&&hls.levels[hls.currentLevel]&&hls.levels[hls.currentLevel].height===h,set:function(){hls.currentLevel=i;lsSet('cncQuality',String(h))}})});return out}
function qApply(){var want=lsGet('cncQuality');if(!hls||!want||want==='auto'||!hls.levels)return;var h=parseInt(want,10),pk=-1,ph=-1;
 hls.levels.forEach(function(L,i){var lh=L.height||0;if(lh&&lh<=h&&(lh>ph||(lh===ph&&(L.bitrate||0)>(hls.levels[pk].bitrate||0)))){ph=lh;pk=i}});
 if(pk<0){var lo=1e9;hls.levels.forEach(function(L,i){if(L.height&&L.height<lo){lo=L.height;pk=i}})}
 if(pk>=0)hls.currentLevel=pk}
function qLabel(){var l=qList(),o=l.filter(function(x){return x.on})[0];return o?o.label:(P.x&&resOf(P.x))||'Auto'}

/* ----- menus ----- */
var SPEEDS=[0.5,0.75,1,1.25,1.5,1.75,2];
var MENUS={
 q:{title:'Quality',items:function(){return qList()}},
 spd:{title:'Speed',items:function(){var v=g('v');return SPEEDS.map(function(s){return {label:s===1?'Normal':s+'x',on:Math.abs(v.playbackRate-s)<.01,set:function(){v.playbackRate=s;lsSet('cncSpeed',String(s))}}})}},
 aud:{title:'Audio',items:function(){var a=audList(),o=[];if(a.length){o.push({head:'In this file'});a.forEach(function(x){o.push({label:x.label,on:x.on,set:function(){x.set();lsSet('cncAudio',x.key);setTimeout(reAuto,400)}})})}
  var ol=otherLangs();if(ol.length){o.push({head:'Other links of this title'});ol.forEach(function(x){o.push({label:x.lang+(x.here?' (this link has it)':''),sub:x.n+' link'+(x.n>1?'s':'')+' - switches the link, keeps your place',on:false,set:function(){switchLink(x.link)}})})}
  if(!a.length&&!ol.length)o.push({head:'No other audio found'});if(!a.length&&!P.live)o.push({head:'This browser shows one audio track of a plain file. Pick another link tagged with the language you want.'});return o}},
 cc:{title:'Subtitles',items:function(){var o=[{label:'Off',on:!P.sub&&!(hls&&hls.subtitleTrack>=0),set:function(){P.subDone=true;P.subWant='off';lsSet('cncSub','off');subOff()}}],gp=subGroups();
  if(hls&&hls.subtitleTracks&&hls.subtitleTracks.length){o.push({head:'In the stream'});hls.subtitleTracks.forEach(function(t,k){o.push({label:audName(t,k),on:hls.subtitleTrack===k&&hls.subtitleDisplay!==false,set:function(){clearTracks();P.sub=null;P.subDone=true;hls.subtitleDisplay=true;hls.subtitleTrack=k}})})}
  var ks=langOrder();if(ks.length)o.push({head:'From the extensions ('+subPool.length+')'});ks.forEach(function(L){o.push({label:L,sub:gp[L].length>1?gp[L].length+' versions':'',on:!!P.sub&&P.sub.lang===L,set:function(){P.subDone=true;P.subWant=L;lsSet('cncSub',L);showSub(L,0)}})});
  if(P.sub&&P.sub.n>1)o.push({label:'Try another version ('+(P.sub.idx+1)+' of '+P.sub.n+')',keep:true,set:function(){showSub(P.sub.lang,P.sub.idx+1)}});
  if(P.sub)o.push({row:{label:'Delay '+(P.subDelay>0?'+':'')+(P.subDelay/1000).toFixed(1)+' s',btns:[['-0.5',function(){P.subDelay-=500;showSub(P.sub.lang,P.sub.idx)}],['+0.5',function(){P.subDelay+=500;showSub(P.sub.lang,P.sub.idx)}],['0',function(){P.subDelay=0;showSub(P.sub.lang,P.sub.idx)}]]}});
  o.push({label:'Look of the subtitles',keep:true,set:function(){menuKind='sty'}});o.push({label:'Load a subtitle file (.srt or .vtt)',set:function(){g('subfile').click()}});
  if(!ks.length)o.push({head:'No subtitle found for this title yet'});return o}},
 sty:{title:'Subtitle look',items:function(){function pk(key,def,opts){return opts.map(function(a){return {label:a[1],on:S(key,def)===a[0],keep:true,set:function(){Sset(key,a[0]);subStyle()}}})}
  return [{label:'Back',keep:true,set:function(){menuKind='cc'}},{label:'Hardcode subtitles into the picture',sub:'White bold text with a black outline, painted on the video',on:burnOn(),keep:true,set:burnToggle},{head:'Size'}].concat(pk('subsz','m',[['s','Small'],['m','Medium'],['l','Large'],['xl','Huge']]),[{head:'Colour'}],pk('subcol','white',[['white','White'],['yellow','Yellow'],['cyan','Cyan']]),[{head:'Background'}],pk('subbg','shadow',[['shadow','Outline'],['box','Dim box'],['solid','Black box']]))}},
 sleep:{title:'Sleep timer',items:function(){function o(label,min){return {label:label,on:(min===0&&!P.sleep)||(P.sleep&&P.sleep.min===min),set:function(){setSleep(min);menuKind='more'},keep:true}}
  return [{label:'Back',keep:true,set:function(){menuKind='more'}},o('Off',0),o('15 minutes',15),o('30 minutes',30),o('45 minutes',45),o('1 hour',60),o('End of this episode',-1)]}},
 more:{title:'More',items:function(){var v=g('v');return [
  {label:'Sleep timer'+(P.sleep?(P.sleep.min<0?': end of episode':': '+Math.max(1,Math.ceil((P.sleep.at-Date.now())/60000))+' min left'):''),keep:true,set:function(){menuKind='sleep'}},
  {label:'Skip ahead 85 s',set:function(){skip(85)}},
  {label:'Picture: '+({contain:'fit',cover:'fill the screen',fill:'stretch'}[P.fit]),keep:true,set:function(){P.fit=P.fit==='contain'?'cover':(P.fit==='cover'?'fill':'contain');v.style.objectFit=P.fit}},
  {label:'Hardcode subtitles into the picture',on:burnOn(),keep:true,set:burnToggle},
  {label:'Stats for nerds',on:P.stats,keep:true,set:function(){P.stats=!P.stats;g('pstats').classList.toggle('hide',!P.stats);statsTick()}},
  {label:'Prefer links that play here',on:!!S('compat',true),keep:true,set:function(){Sset('compat',!S('compat',true))}},
  {label:'Open in VLC',set:function(){openVlc()}},{label:'Cast to a TV or speaker',hide:!(v.remote&&v.remote.prompt),set:function(){try{v.remote.prompt().catch(function(){toast('No cast device found.')})}catch(e){toast('Casting is not available in this browser.')}}},{label:'Copy the link',set:function(){copyUrl()}},{label:'Keyboard shortcuts',set:function(){toast('Space play  S skip 85 s  ← → 10 s  ↑ ↓ volume  F full screen  M mute  C subtitles  A audio  Q quality  < > speed  N next  P previous  I stats  0-9 jump',null,null,12000)}}]}},
 ch:{title:'Channels',items:function(){return tvCtx.list.map(function(c,i){return {label:c.name,on:i===tvCtx.i,set:function(){tvCtx.i=i;tuneCur()}}})}}};
function renderMenu(){var m=g('amenu'),d=MENUS[menuKind];if(!d)return;m.textContent='';m.appendChild(el('div','h',d.title));
 d.items().forEach(function(a){if(a.hide)return;if(a.head){m.appendChild(el('div','h',a.head));return}
  if(a.row){var r=el('div','mrow');r.appendChild(el('span',null,a.row.label));a.row.btns.forEach(function(b){var x=el('button',null,b[0]);x.type='button';x.onclick=function(e){e.stopPropagation();b[1]();renderMenu()};r.appendChild(x)});m.appendChild(r);return}
  var x=el('button',null,a.label);x.type='button';x.setAttribute('role','menuitemradio');x.setAttribute('aria-checked',String(!!a.on));if(a.sub)x.appendChild(el('small',null,a.sub));
  x.onclick=function(){a.set();if(a.keep)renderMenu();else m.classList.add('hide');setTimeout(updateBtns,120)};m.appendChild(x)});
 var cu=m.querySelector('[aria-checked=true]');if(cu&&cu.scrollIntoView)cu.scrollIntoView({block:'nearest'})}
function openMenu(k){var m=g('amenu');if(!m.classList.contains('hide')&&menuKind===k){m.classList.add('hide');return}menuKind=k;renderMenu();m.classList.remove('hide');wake()}
[['pq','q'],['pspd','spd'],['paud','aud'],['pcc','cc'],['pmore','more'],['pch','ch']].forEach(function(a){g(a[0]).onclick=function(e){e.stopPropagation();openMenu(a[1])}});
document.addEventListener('click',function(e){var m=g('amenu');if(!m.classList.contains('hide')&&!m.contains(e.target)&&!/^(pq|pspd|paud|pcc|pmore|pch)${'$'}/.test((e.target.closest('button')||{}).id||''))m.classList.add('hide')});

function updateBtns(){var v=g('v'),live=P.live;
 g('pq').classList.toggle('hide',qList().length<2);g('pq').textContent=qLabel();
 g('pspd').classList.toggle('hide',live);g('pspd').textContent=(v.playbackRate===1?'1':String(v.playbackRate))+'x';
 g('paud').classList.toggle('hide',live?audList().length<2:(audList().length<2&&!otherLangs().length));
 g('pcc').classList.toggle('hide',live&&!Object.keys(subGroups()).length&&!(hls&&hls.subtitleTracks&&hls.subtitleTracks.length));g('pcc').classList.toggle('off',!P.sub&&!(hls&&hls.subtitleTrack>=0));
 g('plive').classList.toggle('hide',!live);g('ptime').classList.toggle('hide',live);g('pnep').classList.toggle('hide',live||!P.nextEp);
 g('pch').classList.toggle('hide',!(live&&tvCtx.list.length>1));g('ppip').classList.toggle('hide',!(document.pictureInPictureEnabled||v.webkitSetPresentationMode));
 var m=g('amenu');if(!m.classList.contains('hide')&&MENUS[menuKind])renderMenu()}

/* ----- sleep timer: pauses after a time, or at the end of the episode ----- */
function setSleep(min){if(!min){P.sleep=null;toast('Sleep timer off');return}P.sleep=min<0?{min:-1}:{min:min,at:Date.now()+min*60000};toast(min<0?'Pausing at the end of this episode':'Pausing in '+min+' minutes')}
function sleepTick(){var s=P.sleep;if(!s||s.min<0)return;if(Date.now()>=s.at){P.sleep=null;var v=g('v');v.pause();toast('Sleep timer: paused. Good night.',null,null,12000)}}

/* ----- the seek bar, the time and the buttons ----- */
function seekRange(){var v=g('v');if(P.live||v.duration===Infinity){var s=v.seekable;if(s&&s.length)return [s.start(0),s.end(s.length-1)];return [0,0]}return [0,v.duration||0]}
function bufEnd(){var v=g('v'),b=v.buffered,t=v.currentTime;for(var i=0;i<b.length;i++){if(b.start(i)<=t+.3&&b.end(i)>=t)return b.end(i)}return t}
function paint(){sleepTick();var v=g('v'),r=seekRange(),d=r[1]-r[0],t=seeking?r[0]+dragFrac*d:v.currentTime,f=d>0?Math.min(1,Math.max(0,(t-r[0])/d)):0;
 g('pprog').style.width=(f*100)+'%';g('pknob').style.left=(f*100)+'%';g('pbuf').style.width=(d>0?Math.min(100,Math.max(0,(bufEnd()-r[0])/d*100)):0)+'%';
 g('pseek').setAttribute('aria-valuenow',String(Math.round(f*100)));
 if(P.live){var behind=Math.max(0,r[1]-v.currentTime);g('plive').classList.toggle('back',behind>12);g('pgolive').classList.toggle('hide',behind<=12)}else{g('pgolive').classList.add('hide');g('ptime').textContent=fmt(t)+' / '+fmt(v.duration)}
 g('pplay').innerHTML=svg(v.paused?'play':'pause');
 g('pintro').classList.toggle('hide',!(!P.live&&P.ctx&&P.ctx.type==='tv'&&v.currentTime>2&&v.currentTime<300&&v.duration>900))}
function seekTo(t){var v=g('v'),r=seekRange();if(r[1]>0)t=Math.min(Math.max(t,r[0]),r[1]);try{v.currentTime=t}catch(e){}}
function skip(d){var v=g('v');seekTo(v.currentTime+d);flash((d>0?'+':'−')+Math.abs(d)+' s',d>0?1:-1);wake()}
(function(){var sk=g('pseek');function fr(e){var b=sk.getBoundingClientRect();return Math.min(1,Math.max(0,(e.clientX-b.left)/b.width))}
 function tip(e){var r=seekRange(),f=fr(e),tp=g('ptip');tp.classList.remove('hide');tp.style.left=(f*100)+'%';tp.textContent=P.live?('-'+fmt(r[1]-(r[0]+f*(r[1]-r[0])))):fmt(r[0]+f*(r[1]-r[0]))}
 sk.addEventListener('pointerdown',function(e){seeking=true;dragFrac=fr(e);sk.classList.add('drag');try{sk.setPointerCapture(e.pointerId)}catch(x){}tip(e);paint()});
 sk.addEventListener('pointermove',function(e){tip(e);if(seeking){dragFrac=fr(e);paint()}});
 sk.addEventListener('pointerleave',function(){if(!seeking)g('ptip').classList.add('hide')});
 function up(e){if(!seeking)return;seeking=false;sk.classList.remove('drag');var r=seekRange();seekTo(r[0]+fr(e)*(r[1]-r[0]));g('ptip').classList.add('hide');paint()}
 sk.addEventListener('pointerup',up);sk.addEventListener('pointercancel',function(){seeking=false;sk.classList.remove('drag')});
 sk.addEventListener('keydown',function(e){if(e.key==='ArrowRight'){skip(5);e.preventDefault();e.stopPropagation()}else if(e.key==='ArrowLeft'){skip(-5);e.preventDefault();e.stopPropagation()}})})();
function togglePlay(){var v=g('v');if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){})}else v.pause()}
function fsOn(){return document.fullscreenElement||document.webkitFullscreenElement}
function toggleFs(){var p=g('player'),v=g('v');if(fsOn()){(document.exitFullscreen||document.webkitExitFullscreen).call(document);try{screen.orientation.unlock()}catch(e){}}else if(p.requestFullscreen||p.webkitRequestFullscreen){var r=(p.requestFullscreen||p.webkitRequestFullscreen).call(p);if(r&&r.then)r.then(function(){try{if(/Mobi|Android/i.test(navigator.userAgent))screen.orientation.lock('landscape').catch(function(){})}catch(e){}}).catch(function(){})}else if(v.webkitEnterFullscreen)v.webkitEnterFullscreen()}
function togglePip(){var v=g('v');try{if(document.pictureInPictureElement)document.exitPictureInPicture();else if(v.requestPictureInPicture)v.requestPictureInPicture();else if(v.webkitSetPresentationMode)v.webkitSetPresentationMode(v.webkitPresentationMode==='picture-in-picture'?'inline':'picture-in-picture')}catch(e){}}
function absUrl(){var u=P.url||'';return u.indexOf('http')===0?u:location.origin+u}
function copyUrl(){var u=absUrl();try{navigator.clipboard.writeText(u);toast('Link copied')}catch(e){window.prompt('Link',u)}}
function openVlc(){var abs=absUrl(),android=/Android/i.test(navigator.userAgent);location.href=android?('intent://'+abs.replace(/^https?:\/\//,'')+'#Intent;scheme='+(abs.indexOf('https')===0?'https':'http')+';package=org.videolan.vlc;type=video/*;end'):('vlc://'+abs)}
g('pplay').onclick=togglePlay;g('pb10').onclick=function(){skip(-10)};g('pf10').onclick=function(){skip(10)};g('pfs').onclick=toggleFs;g('ppip').onclick=togglePip;
g('pmute').onclick=function(){g('v').muted=!g('v').muted};g('pvol').oninput=function(){var v=g('v');v.volume=+this.value;v.muted=+this.value===0};
g('pintro').onclick=function(){skip(85)};g('pgolive').onclick=function(){var r=seekRange();seekTo(hls&&hls.liveSyncPosition?hls.liveSyncPosition:r[1]-1);try{g('v').play()}catch(e){}};
g('pstat').onclick=function(){P.stats=!P.stats;g('pstats').classList.toggle('hide',!P.stats);statsTick()};
g('pprev').onclick=function(){zap(-1)};g('pnext').onclick=function(){zap(1)};
g('plinks').onclick=function(){if(cur&&cur.list.length){closePanel();render(cur.list,cur.title,tvMode?tuneCur:null,tvMode);g('pht').textContent=cur.title;g('phs').textContent='Choose another link';g('pst').textContent='';g('panel').classList.add('on');g('shade').classList.add('on')}};
function wake(){var p=g('player');p.classList.remove('idle');clearTimeout(idleT);idleT=setTimeout(function(){var v=g('v');if(!v.paused&&g('amenu').classList.contains('hide')&&!seeking&&!g('pbar').matches(':hover')&&!g('ptop').matches(':hover'))p.classList.add('idle');else if(!v.paused)wake()},7000)}
g('player').addEventListener('mousemove',wake);g('player').addEventListener('touchstart',wake,{passive:true});

/* taps: one click plays / pauses (touch: shows the controls), a double tap on a side skips 10 s, in the middle it goes full screen */
(function(){var last=0,lx=0,tm=null,c=g('pclick');
 c.addEventListener('click',function(e){var now=Date.now(),w=c.clientWidth,x=e.clientX,touch=e.pointerType==='touch'||(e.pointerType==null&&window.matchMedia&&matchMedia('(pointer:coarse)').matches);
  if(now-last<320){clearTimeout(tm);last=0;var f=x/w;if(f<.34)skip(-10);else if(f>.66)skip(10);else toggleFs();return}
  last=now;lx=x;clearTimeout(tm);tm=setTimeout(function(){last=0;if(touch){var p=g('player');if(p.classList.contains('idle')){wake()}else{clearTimeout(idleT);g('amenu').classList.add('hide');p.classList.add('idle')}}else togglePlay()},300)})})();

/* ----- stats ----- */
var stT=null;function statsTick(){clearTimeout(stT);if(!P.stats){return}var v=g('v'),q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null,lv=hls&&hls.levels&&hls.levels[hls.currentLevel],L=[];
 L.push('Player      '+({hls:'HLS (hls.js)',dash:'DASH (dash.js)',ts:'MPEG-TS (mpegts.js)',file:'plain file'}[P.kind]||P.kind)+(P.live?', live':''));
 L.push('Picture     '+v.videoWidth+' x '+v.videoHeight+(lv&&lv.videoCodec?'  ('+lv.videoCodec+')':''));
 if(lv){L.push('Level       '+Math.round((lv.bitrate||0)/1000)+' kbps'+(lv.audioCodec?'  audio '+lv.audioCodec:''))}
 if(hls&&hls.bandwidthEstimate)L.push('Bandwidth   '+(hls.bandwidthEstimate/1e6).toFixed(1)+' Mbit/s');
 L.push('Buffered    '+Math.max(0,bufEnd()-v.currentTime).toFixed(1)+' s ahead (limit 30 s for streams)');
 if(q)L.push('Frames      '+q.totalVideoFrames+' shown, '+q.droppedVideoFrames+' dropped');
 if(P.live&&hls&&typeof hls.latency==='number')L.push('Latency     '+hls.latency.toFixed(1)+' s');
 g('pstats').textContent=L.join('\n');stT=setTimeout(statsTick,1000)}

/* ----- remembering where you were ----- */
function posAll(){try{return JSON.parse(lsGet('cncPos')||'{}')}catch(e){return{}}}
function posPut(o){var k=Object.keys(o);if(k.length>80){k.sort(function(a,b){return (o[a].at||0)-(o[b].at||0)});k.slice(0,k.length-80).forEach(function(x){delete o[x]})}lsSet('cncPos',JSON.stringify(o))}
function posSave(){var v=g('v');if(!P.key||P.live||!(v.duration>120)||v.currentTime<20)return;var o=posAll();
 if(v.currentTime>v.duration-90){delete o[P.key];var d=cfgAll();var dn=(d.done||{});dn[P.key]=1;Sset('done',dn)}
 else{var c=P.ctx||{};o[P.key]={t:Math.floor(v.currentTime),d:Math.floor(v.duration),at:Date.now(),type:c.type,id:c.id,s:c.s,e:c.e,title:c.title,year:c.year,poster:P.poster,data:c.data,sub2:c.sub2}}posPut(o)}

/* ----- next episode ----- */
var epCache={};
function epList(id,n){var k=id+':'+n;if(epCache[k])return epCache[k];epCache[k]=api('/web/api/season?id='+id+'&n='+n).catch(function(){delete epCache[k];return[]});return epCache[k]}
function findNext(){P.nextEp=null;var c=P.ctx;if(!c||c.type!=='tv')return;var gen=P.gen;
 epList(c.id,c.s).then(function(l){var i=-1;l.forEach(function(x,k){if(x.e===c.e)i=k});if(i>=0&&l[i+1])return {s:c.s,e:l[i+1].e,name:l[i+1].name};
  return epList(c.id,c.s+1).then(function(m){return m.length?{s:c.s+1,e:m[0].e,name:m[0].name}:null})}).then(function(n){if(gen===P.gen){P.nextEp=n;updateBtns()}}).catch(function(){})}
var skipT=null;
function offerNext(){var n=P.nextEp,c=P.ctx;if(!n||!c||!S('autonext',true)||P.live)return;var b=g('pskipb'),left=10;b.textContent='';
 b.appendChild(el('b',null,'Next: S'+n.s+' E'+n.e));b.appendChild(el('span',null,(n.name||'')));var cnt=el('span',null,'');b.appendChild(cnt);
 var go=el('button',null,'Play now'),no=el('button','quiet','Cancel');go.type=no.type='button';b.appendChild(go);b.appendChild(no);b.classList.remove('hide');
 var fin=function(){clearInterval(skipT);b.classList.add('hide')},run=function(){fin();playEpisode(n)};
 go.onclick=run;no.onclick=fin;cnt.textContent='Starting in '+left+' s';skipT=setInterval(function(){left--;cnt.textContent='Starting in '+left+' s';if(left<=0)run()},1000)}
function playEpisode(n){var c=P.ctx;if(!c)return;clearInterval(skipT);g('pskipb').classList.add('hide');streams('tv',c.id,n.s,n.e,c.title,c.year,true)}
g('pnep').onclick=function(){if(P.nextEp)playEpisode(P.nextEp)};

/* ----- starting and stopping ----- */
function destroyEngines(){if(hls){try{hls.destroy()}catch(e){}hls=null}if(mp){try{mp.destroy()}catch(e){}mp=null}if(dash){try{dash.reset()}catch(e){}dash=null}if(eng){try{eng.destroy()}catch(e){}eng=null}}
function stopVideo(){clearInterval(liveT);clearTimeout(stallT);clearTimeout(silT);clearTimeout(bufT);clearInterval(skipT);stallT=null;g('pfix').classList.add('hide');g('pskipb').classList.add('hide');var v=g('v');destroyEngines();v.pause();v.removeAttribute('src');clearTracks();v.load();g('amenu').classList.add('hide');g('pspin').classList.add('hide')}
function shell(title,note){P.recon=0;stopVideo();g('pmsg').textContent='';g('pnote').textContent=note||'';g('ptitle').textContent=title;g('psub').textContent=tvMode?'Live':'';P.title=title;P.live=!!tvMode;P.key='';P.ctx=null;P.poster='';P.sub2='';P.sub=null;P.subDone=false;P.nextEp=null;P.x=null;g('player').classList.remove('hide');g('pspin').classList.remove('hide');updateBtns();wake()}
function hlsCfg(){var c={maxBufferLength:30,maxMaxBufferLength:30,maxBufferSize:60*1000*1000,startFragPrefetch:true,enableWorker:true,capLevelToPlayerSize:true,fragLoadingMaxRetry:6,manifestLoadingMaxRetry:4,levelLoadingMaxRetry:4,fragLoadingTimeOut:30000,abrBandWidthUpFactor:.8,maxBufferHole:.6};
 if(tvMode){c.backBufferLength=90;c.liveSyncDurationCount=3;c.liveMaxLatencyDurationCount=10;c.maxLiveSyncPlaybackRate=1.08;c.manifestLoadingTimeOut=8000;c.levelLoadingTimeOut=8000;c.fragLoadingTimeOut=12000}else{c.backBufferLength=20;c.abrEwmaFastVoD=2.5;c.abrEwmaSlowVoD=8}return c}
/* a link whose sound or picture this browser cannot play: the same title from another link (same language, same resolution if possible, no risky format) takes over, keeping the place */
function altLink(){if(!cur||!cur.list||!P.x)return null;cur.bad=cur.bad||{};cur.bad[P.x.url]=1;var mine=langsOf(P.x),res=parseInt(resOf(P.x))||0,best=null,bs=1e9;
 cur.list.forEach(function(x){if(!x||!x.url||cur.bad[x.url]||risks(x).length)return;var l=langsOf(x),ov=!mine.length||!l.length||l.some(function(a){return mine.indexOf(a)>=0});
  var sc=(ov?0:50)+Math.abs((parseInt(resOf(x))||0)-res)/100;if(sc<bs){bs=sc;best=x}});return best}
function autoSwitch(why){if(P.live||!S('autoswitch',true))return false;var old=P.x,nx=altLink();if(!nx)return false;
 toast('Switched to another link: '+why,'Undo',function(){if(cur&&cur.bad)cur.bad[nx.url]=1;switchLink(old)},9000);switchLink(nx);return true}
function switchLink(x){if(!cur||!cur.list)return;var v=g('v'),t=P.live?0:v.currentTime;cur.idx=cur.list.indexOf(x);cur.auto=false;P.startAt=t;play(x,cur.title)}
var pendingCtx=null;
function pick(list,x,title,live){P.recon=0;tvMode=!!live;var v=g('v'),pc=live?null:pendingCtx,same=!pc||pc.key===P.key,t=(!live&&same&&!g('player').classList.contains('hide')&&v.currentTime>5)?v.currentTime:0;cur={list:list,idx:list.indexOf(x),title:title,auto:false};setTvUi(tvMode);P.startAt=t;
 if(pc){P.ctx=pc;P.key=pc.key;P.poster=pc.poster||'';P.sub2=pc.sub2||''}else if(live){P.ctx=null;P.key='';P.sub2=''}
 play(x,title)}
function showFix(msg){var f=g('pfix');f.textContent='';f.appendChild(el('div',null,msg));var abs=absUrl();var a=el('a',null,'Open in VLC');a.href='#';a.onclick=function(e){e.preventDefault();openVlc()};f.appendChild(a);
 var l=el('button',null,'Other links');l.type='button';l.onclick=function(){g('plinks').click()};f.appendChild(l);var b=el('button',null,'Copy link');b.type='button';b.onclick=copyUrl;f.appendChild(b);var c=el('button',null,'Hide');c.type='button';c.onclick=function(){f.classList.add('hide')};f.appendChild(c);f.classList.remove('hide')}
function noSound(v){if(v.paused||v.currentTime<2||v.muted||v.volume===0)return false;
 if(typeof v.webkitAudioDecodedByteCount==='number')return v.webkitAudioDecodedByteCount===0;
 if(typeof v.mozHasAudio==='boolean')return !v.mozHasAudio;
 if(v.audioTracks&&typeof v.audioTracks.length==='number')return v.audioTracks.length===0;return false}
function silentWatch(tries){clearTimeout(silT);silT=setTimeout(function(){var v=g('v');if(!v.src&&!hls&&!mp&&!dash)return;var why=risks(P.x||{}).join(', ');
 if(v.readyState>=2&&v.videoWidth===0&&v.currentTime>1.5&&!P.live){if(autoSwitch('the first one has no picture in this browser'))return;showFix('Sound but no picture: this file\'s video format'+(why?' ('+why+')':'')+' is not supported by this browser. Pick another link or play it in a media player.');return}
 if(noSound(v)){if(autoSwitch('the first one has no sound in this browser'))return;showFix('No sound: this file\'s audio format'+(why?' ('+why+')':' (usually AC3, EAC3 or DTS)')+' is not supported by this browser. Pick another link, or play it in a media player.');return}
 if(tries>0)silentWatch(tries-1)},tries===3?4000:3000)}
var liveT=null;
/* a live picture that freezes or ends is reopened (3 times) before the next link is tried */
function reconnect(){if(!P.live||!P.x)return;P.recon=(P.recon||0)+1;if(P.recon>3){P.recon=0;if(cur&&cur.auto)giveUpNow();else{g('pnote').textContent='';g('pmsg').textContent='The live stream stopped. Try Links or another channel.'}return}
 g('pnote').textContent='Reconnecting ('+P.recon+')';var x=P.x,t=P.title;P.startAt=0;play(x,t)}
function giveUpNow(){if(cur&&cur.auto&&cur.idx+1<cur.list.length){cur.idx++;g('pnote').textContent='Trying link '+(cur.idx+1)+' of '+cur.list.length;play(cur.list[cur.idx],cur.title)}else{g('pnote').textContent='';g('pmsg').textContent='No link for this channel would play. Try Links or another channel.'}}
function liveWatch(gen){clearInterval(liveT);var last=-1,still=0;liveT=setInterval(function(){if(gen!==P.gen||g('player').classList.contains('hide')){clearInterval(liveT);return}
 var v=g('v');if(v.paused||seeking||v.readyState===0){still=0;return}if(Math.abs(v.currentTime-last)<.05){still++}else{still=0;last=v.currentTime}
 if(still>=4){still=0;clearInterval(liveT);if(hls&&P.recon===0)try{hls.startLoad();hls.recoverMediaError()}catch(e){}reconnect()}},4000)}
function play(x,title){var v=g('v');stopVideo();var gen=++P.gen;P.err='';P.x=x;P.title=title;P.live=!!tvMode;P.sub=null;P.subDone=false;P.subDelay=0;P.nextEp=null;
 g('pmsg').textContent='';g('pnote').textContent='';g('ptitle').textContent=title;g('psub').textContent=P.live?'Live':(P.sub2||'');g('player').classList.remove('hide');g('pspin').classList.remove('hide');wake();subStyle();burnApply();
 var url=fix(x.url);P.url=url;P.kind=kindOf(url,x);var order=P.kind==='dash'?['dash']:(P.kind==='ts'?['ts','hls','file']:(P.kind==='hls'?(tvMode?['hls','ts','file']:['hls','file']):(tvMode?['file','hls','ts']:['file','hls']))),oi=0,startAt=P.startAt;P.startAt=0;audApplied=false;if(v.audioTracks){v.audioTracks.onaddtrack=audRefresh;v.audioTracks.onchange=audRefresh}
 addSubs(x.subtitles);if(!P.live)findNext();
 v.playbackRate=P.live?1:(parseFloat(lsGet('cncSpeed'))||1);
 var fail,giveUp,next;
 var startHls=function(){if(window.Hls&&Hls.isSupported()){hls=new Hls(hlsCfg());hls.loadSource(url);hls.attachMedia(v);var net=0,med=0;
   hls.on(Hls.Events.ERROR,function(_,d){if(!d.fatal||gen!==P.gen)return;P.err=String(d.details||d.type)+(d.response&&d.response.code?' HTTP '+d.response.code:'');if((d.details==='manifestIncompatibleCodecsError'||d.details==='bufferAddCodecError')&&autoSwitch('its format is not supported here'))return;if(d.type===Hls.ErrorTypes.NETWORK_ERROR&&net<(P.live?3:2)){net++;setTimeout(function(){if(hls&&gen===P.gen)hls.startLoad()},net*800);return}if(d.type===Hls.ErrorTypes.MEDIA_ERROR&&med<2){med++;if(med===2)hls.swapAudioCodec();hls.recoverMediaError();return}if(tvMode&&cur&&cur.auto)giveUp();else fail()});
   hls.on(Hls.Events.AUDIO_TRACKS_UPDATED,audRefresh);hls.on(Hls.Events.AUDIO_TRACK_SWITCHED,audRefresh);hls.on(Hls.Events.MANIFEST_PARSED,function(){qApply();audRefresh();ccAuto();updateBtns()});
   hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED,function(){ccAuto();updateBtns()});hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH,updateBtns);hls.on(Hls.Events.LEVEL_SWITCHED,updateBtns);
   hls.on(Hls.Events.LEVEL_LOADED,function(_,d){if(d&&d.details&&d.details.live&&!P.live){P.live=true;g('psub').textContent='Live';updateBtns()}})}else{v.src=url}};
 var plain=function(){v.src=url;var pr=v.play();if(pr&&pr.catch)pr.catch(function(){})};
 var startFile=function(){v.preload='auto';
  /* a file with several audio tracks goes through the media engine, which can switch them */
  if(!tvMode&&window.Mse&&Mse.usable()&&/\.(mkv|mp4|m4v|mov|webm)(\?|#|${'$'})/i.test(url.split('?')[0]+'#')){
   Mse.probe(url).then(function(info){if(gen!==P.gen)return;if(!info||info.audios.length<2){plain();return}
    P.kind='mse';eng=new Mse.Engine(v,info,{start:0,pick:pickAud,onFatal:function(e){if(gen!==P.gen)return;P.err='engine '+((e&&e.message)||e);destroyEngines();v.removeAttribute('src');plain()}});
    audRefresh();var pr=v.play();if(pr&&pr.catch)pr.catch(function(){})}).catch(function(){if(gen===P.gen)plain()})}
  else v.src=url};
 var startTs=function(){lib(MPEGTS,function(){return window.mpegts}).then(function(){if(gen!==P.gen)return;if(!mpegts.isSupported()){startFile();return}
   mp=mpegts.createPlayer({type:/\.flv(\?|#|${'$'})/i.test(url.split('?')[0]+'#')?'flv':'mpegts',isLive:true,url:url},{enableWorker:true,liveBufferLatencyChasing:true,liveBufferLatencyMaxLatency:10,liveBufferLatencyMinRemainTime:2,autoCleanupSourceBuffer:true,autoCleanupMaxBackwardDuration:60,autoCleanupMinBackwardDuration:30,lazyLoad:false});
   mp.attachMediaElement(v);mp.load();mp.on(mpegts.Events.ERROR,function(){if(gen!==P.gen)return;if(tvMode&&cur&&cur.auto)giveUp();else fail()});var pr=mp.play();if(pr&&pr.catch)pr.catch(function(){})}).catch(function(){if(gen===P.gen)fail()})};
 var startDash=function(){lib(DASH,function(){return window.dashjs}).then(function(){if(gen!==P.gen)return;dash=dashjs.MediaPlayer().create();
   dash.updateSettings({streaming:{buffer:{bufferTimeAtTopQuality:30,bufferTimeAtTopQualityLongForm:30,bufferToKeep:20,bufferPruningInterval:20,fastSwitchEnabled:true}}});
   dash.on('error',function(e){if(gen===P.gen){P.err='dash '+((e&&e.error&&(e.error.message||e.error.code))||'error');fail()}});dash.on('streamInitialized',function(){audRefresh();ccAuto();updateBtns()});dash.on('qualityChangeRendered',updateBtns);dash.initialize(v,url,true)}).catch(function(){if(gen===P.gen)fail()})};
 var launch=function(k){P.kind=k;if(k==='hls')startHls();else if(k==='dash')startDash();else if(k==='ts')startTs();else startFile()};var begin=function(){launch(order[0])};
 fail=function(){if(gen!==P.gen)return;
  if(eng){P.err='engine playback error';destroyEngines();v.removeAttribute('src');plain();return}
  if(oi+1<order.length){oi++;destroyEngines();v.removeAttribute('src');launch(order[oi]);var p=v.play();if(p&&p.catch)p.catch(function(){});return}
  if(cur&&cur.auto&&cur.idx+1<cur.list.length){cur.idx++;g('pnote').textContent='Trying link '+(cur.idx+1)+' of '+cur.list.length;play(cur.list[cur.idx],cur.title);return}
  g('pnote').textContent='';g('pspin').classList.add('hide');var why=risks(x).join(', ');
  if(tvMode)g('pmsg').textContent='No link for this channel would play'+(P.err?' ['+P.err+']':'')+'. Try Links or another channel.';
  else{g('pmsg').textContent='';if(autoSwitch('the first one would not play'))return;showFix('This link will not play in the browser'+(why?' ('+why+' is not supported here)':'')+(P.err?' ['+P.err+']':'')+'. Pick another link, or play it in a media player.')}};
 next=function(){if(cur&&cur.auto&&cur.idx+1<cur.list.length){cur.idx++;g('pnote').textContent='Trying link '+(cur.idx+1)+' of '+cur.list.length;play(cur.list[cur.idx],cur.title);return true}return false};
 giveUp=function(){clearTimeout(stallT);stallT=null;if(next())return;if(cur){cur.stalled=true;cur.next=next}if(cur&&cur.done){g('pnote').textContent='';g('pspin').classList.add('hide');g('pmsg').textContent='No link for this channel would play. Try Links or another channel.'}else g('pnote').textContent='Still looking for a working link'};
 clearTimeout(stallT);stallT=null;if(cur&&cur.auto&&tvMode)stallT=setTimeout(giveUp,8000);
 v.onerror=function(){if(gen!==P.gen)return;if(tvMode&&cur&&cur.auto)giveUp();else fail()};
 v.onplaying=function(){if(P.live&&P.recon&&!P.reconT){P.reconT=setTimeout(function(){P.reconT=null;if(g('v').currentTime>0&&!g('v').paused)P.recon=0},20000)}silentWatch(3);clearTimeout(stallT);stallT=null;clearTimeout(bufT);g('pnote').textContent='';g('pmsg').textContent='';g('pspin').classList.add('hide');updateBtns()};
 v.onwaiting=function(){g('pspin').classList.remove('hide');if(tvMode&&cur&&!stallT)stallT=setTimeout(function(){stallT=null;if(!v.paused&&v.readyState<3){cur.auto=true;giveUp()}},12000);clearTimeout(bufT);bufT=setTimeout(function(){if(!v.paused&&v.readyState<3&&!g('pmsg').textContent)g('pnote').textContent='Buffering. The source is slow, hold on or try another link.'},4000)};
 v.onloadedmetadata=function(){if(gen!==P.gen)return;var r=null;
  if(!P.live&&v.duration!==Infinity){if(startAt>5){seekTo(startAt)}else if(P.key){r=posAll()[P.key];if(r&&r.t>30&&r.t<v.duration-90){seekTo(r.t);toast('Resumed at '+fmt(r.t),'Start over',function(){seekTo(0)})}}}
  audRefresh();ccAuto();updateBtns();mediaSession()};
 if(P.live)liveWatch(gen);begin();var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});v.focus({preventScroll:true})}

/* the browser's own media keys and lock-screen controls */
function mediaSession(){if(!('mediaSession' in navigator))return;try{var v=g('v');navigator.mediaSession.metadata=new MediaMetadata({title:P.title||'',artist:P.sub2||(P.live?'Live':'CNCVerse'),artwork:P.poster?[{src:P.poster,sizes:'512x512'}]:[]});
 navigator.mediaSession.setActionHandler('play',function(){v.play()});navigator.mediaSession.setActionHandler('pause',function(){v.pause()});
 navigator.mediaSession.setActionHandler('seekbackward',function(){skip(-10)});navigator.mediaSession.setActionHandler('seekforward',function(){skip(10)});
 navigator.mediaSession.setActionHandler('previoustrack',function(){if(tvMode)zap(-1);else seekTo(0)});navigator.mediaSession.setActionHandler('nexttrack',function(){if(tvMode)zap(1);else if(P.nextEp)playEpisode(P.nextEp)})}catch(e){}}

/* video element events (set once) */
(function(){var v=g('v');
 var sv=parseFloat(lsGet('cncVol'));if(sv>=0&&sv<=1)v.volume=sv;if(lsGet('cncMuted')==='1')v.muted=true;g('pvol').value=v.muted?0:v.volume;
 function vol(){g('pvol').value=v.muted?0:v.volume;g('pmute').classList.toggle('off',v.muted||v.volume===0);lsSet('cncVol',String(v.volume));lsSet('cncMuted',v.muted?'1':'0')}
 v.addEventListener('volumechange',vol);vol();
 ['timeupdate','progress','durationchange','play','pause','seeked','loadedmetadata'].forEach(function(n){v.addEventListener(n,function(){if(!seeking)paint();if(n==='timeupdate'&&Date.now()-lastSave>5000){lastSave=Date.now();posSave()}})});
 v.addEventListener('seeking',function(){g('pspin').classList.remove('hide')});v.addEventListener('canplay',function(){g('pspin').classList.add('hide')});v.addEventListener('seeked',function(){g('pspin').classList.add('hide')});
 v.addEventListener('ratechange',updateBtns);v.addEventListener('loadedmetadata',function(){setTimeout(reAuto,600)});
 v.addEventListener('ended',function(){posSave();if(P.live){reconnect();return}if(P.sleep&&P.sleep.min<0){P.sleep=null;toast('Sleep timer: stopped after this episode. Good night.',null,null,12000);return}offerNext()});
 document.addEventListener('fullscreenchange',function(){g('pfs').setAttribute('aria-pressed',String(!!fsOn()))});
 setInterval(function(){if(!g('player').classList.contains('hide')&&!seeking)paint()},500)})();
function closePlayer(){posSave();stopVideo();g('player').classList.add('hide');g('pnote').textContent='';cur=null;P.live=false;P.key='';if(fsOn())try{(document.exitFullscreen||document.webkitExitFullscreen).call(document)}catch(e){}try{if(document.pictureInPictureElement)document.exitPictureInPicture()}catch(e){}if(location.hash.indexOf('#/t/')===0||location.hash===''||location.hash==='#/')refreshMarks()}
g('pclose').onclick=closePlayer;
document.addEventListener('keydown',function(e){var open=!g('player').classList.contains('hide');
 if(e.key==='Escape'){if(open){var m=g('amenu');if(!m.classList.contains('hide'))m.classList.add('hide');else if(fsOn())toggleFs();else closePlayer()}else closePanel();return}
 var tag=e.target.tagName;if(!open||tag==='INPUT'||tag==='SELECT'||tag==='TEXTAREA'||e.ctrlKey||e.metaKey||e.altKey)return;if(tag==='BUTTON'&&(e.key===' '||e.key==='Enter'))return;var v=g('v'),k=e.key;wake();
 if(k===' '||k==='k'){e.preventDefault();togglePlay()}else if(k==='ArrowRight'||k==='l'){e.preventDefault();skip(10)}else if(k==='ArrowLeft'||k==='j'){e.preventDefault();skip(-10)}
 else if(k==='ArrowUp'){e.preventDefault();v.muted=false;v.volume=Math.min(1,v.volume+.05)}else if(k==='ArrowDown'){e.preventDefault();v.volume=Math.max(0,v.volume-.05)}
 else if(k==='f'){toggleFs()}else if(k==='m'){v.muted=!v.muted}else if(k==='a'){if(!g('paud').classList.contains('hide'))openMenu('aud')}else if(k==='c'){if(P.sub||(hls&&hls.subtitleTrack>=0)){P.subDone=true;subOff()}else{P.subDone=false;var ks=langOrder();if(ks.length)showSub(ks[0],0)}}
 else if(k==='q'){if(!g('pq').classList.contains('hide'))openMenu('q')}else if(k==='n'){if(tvMode)zap(1);else if(P.nextEp)playEpisode(P.nextEp)}else if(k==='p'){if(tvMode)zap(-1)}
 else if(k==='s'&&!P.live){skip(85)}else if(k==='i'){P.stats=!P.stats;g('pstats').classList.toggle('hide',!P.stats);statsTick()}
 else if(k==='>'||k==='.'){if(!P.live){var i=SPEEDS.indexOf(v.playbackRate);v.playbackRate=SPEEDS[Math.min(SPEEDS.length-1,(i<0?2:i)+1)];toast(v.playbackRate+'x',null,null,1200)}}else if(k==='<'||k===','){if(!P.live){var j=SPEEDS.indexOf(v.playbackRate);v.playbackRate=SPEEDS[Math.max(0,(j<0?2:j)-1)];toast(v.playbackRate+'x',null,null,1200)}}
 else if(k==='PageUp'&&tvMode)zap(-1);else if(k==='PageDown'&&tvMode)zap(1);
 else if(/^[0-9]${'$'}/.test(k)&&!P.live){var r=seekRange();seekTo(r[0]+(+k/10)*(r[1]-r[0]))}});
"""
}
