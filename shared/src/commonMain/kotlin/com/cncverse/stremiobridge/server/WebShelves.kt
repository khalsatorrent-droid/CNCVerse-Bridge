package com.cncverse.stremiobridge.server

/** The website's "Short dramas" and "Music" pages. */
internal object WebShelves {
    val JS = """
/* ----- "Short dramas" (every short-drama extension in one catalog) and "Music" (Monochrome): one catalog page each ----- */
function shelf(kind){var my=++xrun;var isM=kind==='music';var name=isM?'Music':'Short dramas';document.title=name+' - CNCVerse';var m=g('main');m.textContent='';m.appendChild(el('div','top-gap'));
 var head=el('div','tvh');head.appendChild(el('h2',null,name));var src=el('span','cnt');head.appendChild(src);m.appendChild(head);
 var bar=el('div','brbar');m.appendChild(bar);var sb=document.createElement('input');sb.type='search';sb.placeholder=isM?'Search songs, albums, artists':'Search every short-drama extension';sb.style.flex='1 1 260px';sb.style.maxWidth='460px';sb.setAttribute('aria-label',sb.placeholder);bar.appendChild(sb);
 var chips=el('div','chips');chips.style.padding='0 4vw 10px';m.appendChild(chips);
 var info=el('p','msg','Loading');m.appendChild(info);var gr=el('div','grid');m.appendChild(gr);var more=el('button','quiet more','Show more');more.type='button';more.classList.add('hide');m.appendChild(more);
 var st={genre:'',q:'',skip:0},run=0,drawn=false;
 function load(reset){var r=++run;if(reset){st.skip=0;gr.textContent='';info.textContent='Loading';info.classList.remove('hide')}more.classList.add('hide');
  api('/web/api/shelf?kind='+kind+'&skip='+st.skip+'&genre='+encodeURIComponent(st.genre)+'&q='+encodeURIComponent(st.q)).then(function(d){if(my!==xrun||r!==run)return;
   if(d.none){info.textContent=isM?'No music extension is installed. Install Monochrome from the Watchuu repository in the app.':'No short-drama extension is installed (or none is marked as one in Settings > Catalogs).';return}
   if(d.source)src.textContent=d.source;
   if(!drawn){drawn=true;var all=[''].concat(d.genres||[]);if(all.length>1)all.forEach(function(gn){var c=el('button','chip',gn||'All');c.type='button';c.setAttribute('aria-pressed',String(gn===st.genre));c.onclick=function(){st.genre=gn;Array.prototype.forEach.call(chips.children,function(x){x.setAttribute('aria-pressed',String(x===c))});load(true)};chips.appendChild(c)})}
   var items=d.items||[];items.forEach(function(it){var c=card({poster:it.poster,title:it.title,year:it.year,type:'tv'});var s=c.querySelector('.s');if(s)s.textContent=it.year||(isM?'Music':'Short drama');c.onclick=function(){location.hash='#/xt/'+encodeURIComponent(it.ext)+'/'+encodeURIComponent(it.url)};gr.appendChild(c)});
   st.skip+=items.length;
   if(!gr.children.length)info.textContent=st.q?'Nothing found. Try another word.':'Nothing here yet. The extensions may still be loading: try again in a minute.';else info.classList.add('hide');
   if(items.length)more.classList.remove('hide')
  }).catch(function(e){if(e.message!=='auth'&&r===run)info.textContent='Could not load this page. Try again.'})}
 more.onclick=function(){load(false)};
 var tm=0;sb.oninput=function(){clearTimeout(tm);tm=setTimeout(function(){st.q=sb.value.trim();load(true)},500)};
 load(true)}
"""
}
