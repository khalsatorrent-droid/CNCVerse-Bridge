package com.cncverse.stremiobridge.server

/**
 * The website player's own media engine: opens an MKV / MP4 file in pieces, separates the picture from the
 * audio tracks and feeds the browser's Media Source, so the audio track can be switched (a plain <video>
 * can only play the first one). Written as one script that the page includes.
 */
internal object WebMse {
    val JS = """/* ===== CNC media engine: plays MKV / MP4 files with several audio tracks through Media Source Extensions ===== */
var Mse=(function(){
'use strict';
var AHEAD=30;
/* ---------- bytes ---------- */
function cat(l){var n=0,i;for(i=0;i<l.length;i++)n+=l[i].length;var o=new Uint8Array(n),p=0;for(i=0;i<l.length;i++){o.set(l[i],p);p+=l[i].length}return o}
function b32(n){return new Uint8Array([(n>>>24)&255,(n>>>16)&255,(n>>>8)&255,n&255])}
function b16(n){return new Uint8Array([(n>>>8)&255,n&255])}
function b8(n){return new Uint8Array([n&255])}
function b64(n){return cat([b32(Math.floor(n/4294967296)),b32(n%4294967296)])}
function t4(s){return new Uint8Array([s.charCodeAt(0),s.charCodeAt(1),s.charCodeAt(2),s.charCodeAt(3)])}
function u32(b,o){return ((b[o]*16777216)+(b[o+1]<<16)+(b[o+2]<<8)+b[o+3])}
function u16(b,o){return (b[o]<<8)|b[o+1]}
function u64(b,o){return u32(b,o)*4294967296+u32(b,o+4)}
function s32(b,o){return (b[o]<<24)|(b[o+1]<<16)|(b[o+2]<<8)|b[o+3]}
function box(type){var p=[],i;for(i=1;i<arguments.length;i++)p.push(arguments[i]);var body=cat(p);return cat([b32(body.length+8),t4(type),body])}
function fbox(type,ver,flags){var p=[type,b32(ver*16777216+flags)],i;for(i=3;i<arguments.length;i++)p.push(arguments[i]);return box.apply(null,p)}
function hex(n,w){var s=n.toString(16).toUpperCase();while(s.length<(w||2))s='0'+s;return s}
function str(b,o,n){var s='';for(var i=0;i<n;i++)s+=String.fromCharCode(b[o+i]);return s}

/* ---------- fMP4 writing ---------- */
var MATRIX=cat([b32(0x10000),b32(0),b32(0),b32(0),b32(0x10000),b32(0),b32(0),b32(0),b32(0x40000000)]);
function desc(tag,body){var l=body.length;return cat([b8(tag),new Uint8Array([0x80|((l>>21)&0x7f),0x80|((l>>14)&0x7f),0x80|((l>>7)&0x7f),l&0x7f]),body])}
function esds(oti,asc){var dsi=asc&&asc.length?desc(5,asc):new Uint8Array(0);var dcd=desc(4,cat([b8(oti),b8(0x15),new Uint8Array(3),b32(0),b32(0),dsi]));return fbox('esds',0,0,desc(3,cat([b16(1),b8(0),dcd,desc(6,b8(2))])))}
function vEntry(fourcc,w,h,cfg){return box(fourcc,new Uint8Array(6),b16(1),b16(0),b16(0),new Uint8Array(12),b16(w),b16(h),b32(0x480000),b32(0x480000),b32(0),b16(1),new Uint8Array(32),b16(0x18),b16(0xFFFF),cfg)}
function aEntry(fourcc,ch,rate,cfg){return box(fourcc,new Uint8Array(6),b16(1),new Uint8Array(8),b16(ch),b16(16),b16(0),b16(0),b32(((rate&0xFFFF)*65536)),cfg)}
/* tr: {type,ts,w,h,entry} */
function initSeg(tr){var aud=tr.type==='audio';
 var mvhd=fbox('mvhd',0,0,b32(0),b32(0),b32(1000),b32(0),b32(0x10000),b16(0x0100),b16(0),b32(0),b32(0),MATRIX,new Uint8Array(24),b32(2));
 var tkhd=fbox('tkhd',0,3,b32(0),b32(0),b32(1),b32(0),b32(0),b32(0),b32(0),b16(0),b16(0),b16(aud?0x0100:0),b16(0),MATRIX,b32((tr.w||0)*65536),b32((tr.h||0)*65536));
 var mdhd=fbox('mdhd',0,0,b32(0),b32(0),b32(tr.ts),b32(0),b16(0x55C4),b16(0));
 var hdlr=fbox('hdlr',0,0,b32(0),t4(aud?'soun':'vide'),new Uint8Array(12),new Uint8Array([0]));
 var mh=aud?fbox('smhd',0,0,b16(0),b16(0)):fbox('vmhd',0,1,b16(0),new Uint8Array(6));
 var dinf=box('dinf',fbox('dref',0,0,b32(1),fbox('url ',0,1)));
 var stbl=box('stbl',fbox('stsd',0,0,b32(1),tr.entry),fbox('stts',0,0,b32(0)),fbox('stsc',0,0,b32(0)),fbox('stsz',0,0,b32(0),b32(0)),fbox('stco',0,0,b32(0)));
 var trak=box('trak',tkhd,box('mdia',mdhd,hdlr,box('minf',mh,dinf,stbl)));
 var mvex=box('mvex',fbox('trex',0,0,b32(1),b32(1),b32(0),b32(0),b32(0)));
 return cat([box('ftyp',t4('isom'),b32(512),t4('isom'),t4('iso6'),t4('mp41')),box('moov',mvhd,trak,mvex)])}
/* frames: [{dts,cts,dur,key,data}] in decode order, ticks of the track */
var seqNo=1;
function fragment(frames,audio){var n=frames.length,i,ent=[],data=[];
 for(i=0;i<n;i++){var f=frames[i];ent.push(b32(f.dur),b32(f.data.length),b32(audio||f.key?0x02000000:0x01010000),b32(f.cts|0));data.push(f.data)}
 var mk=function(off){return box('moof',fbox('mfhd',0,0,b32(seqNo)),box('traf',fbox('tfhd',0,0x020000,b32(1)),fbox('tfdt',1,0,b64(frames[0].dts)),fbox('trun',1,0xF01,b32(n),b32(off),cat(ent))))};
 var moof=mk(0);moof=mk(moof.length+8);seqNo++;
 return cat([moof,box('mdat',cat(data))])}

/* ---------- codec strings and sample entries ---------- */
function bits(b){var p=0;return {r:function(n){var v=0;while(n--){v=v*2+((b[p>>3]>>(7-(p&7)))&1);p++}return v}}}
function revBits32(x){var r=0,i;for(i=0;i<32;i++){r=r*2+((x>>>i)&1)}return r}
function hvcCodec(c){var sp=c[1]>>6,tier=(c[1]>>5)&1,prof=c[1]&31,comp=revBits32(u32(c,2)),lvl=c[12],cons=[],i;for(i=0;i<6;i++)cons.push(c[6+i]);while(cons.length&&cons[cons.length-1]===0)cons.pop();
 return 'hvc1.'+(sp?'ABC'[sp-1]:'')+prof+'.'+comp.toString(16).toUpperCase()+'.'+(tier?'H':'L')+lvl+(cons.length?'.'+cons.map(function(x){return hex(x)}).join('.'):'')}
function av1Codec(c){var prof=c[1]>>5,lvl=c[1]&31,tier=c[2]>>7,hb=(c[2]>>6)&1,tw=(c[2]>>5)&1;return 'av01.'+prof+'.'+(lvl<10?'0':'')+lvl+(tier?'H':'M')+'.'+(tw?'12':(hb?'10':'08'))}
function aacRate(asc){var T=[96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350];var b=bits(asc);var aot=b.r(5);if(aot===31)aot=32+b.r(6);var i=b.r(4);var rate=i===15?b.r(24):T[i];return {aot:aot,rate:rate||48000,ch:0}}
function ac3Info(f){/* first AC-3 syncframe -> dac3 bytes */
 if(f.length<8||f[0]!==0x0B||f[1]!==0x77)return null;var b=bits(f.subarray(4));var fscod=b.r(2),frm=b.r(6),bsid=b.r(5),bsmod=b.r(3),acmod=b.r(3);
 if((acmod&1)&&acmod!==1)b.r(2);if(acmod&4)b.r(2);if(acmod===2)b.r(2);var lfe=b.r(1);
 var v=(fscod<<22)|(bsid<<17)|(bsmod<<14)|(acmod<<11)|(lfe<<10)|((frm>>1)<<5);
 return {dac3:new Uint8Array([(v>>16)&255,(v>>8)&255,v&255]),rate:[48000,44100,32000][fscod]||48000,ch:[2,1,2,3,3,4,4,5][acmod]+lfe}}
/* the sample entry + codec string of one track, from a Matroska CodecID */
function mkvEntry(t){var id=t.codec,cp=t.priv||new Uint8Array(0);
 if(t.type==='video'){
  if(id==='V_MPEG4/ISO/AVC'&&cp.length>4)return {entry:vEntry('avc1',t.w,t.h,box('avcC',cp)),codec:'avc1.'+hex(cp[1])+hex(cp[2])+hex(cp[3]),ts:90000};
  if(id==='V_MPEGH/ISO/HEVC'&&cp.length>13)return {entry:vEntry('hvc1',t.w,t.h,box('hvcC',cp)),codec:hvcCodec(cp),ts:90000};
  if(id==='V_AV1'&&cp.length>=4)return {entry:vEntry('av01',t.w,t.h,box('av1C',cp)),codec:av1Codec(cp),ts:90000};
  if(id==='V_VP9')return {entry:vEntry('vp09',t.w,t.h,fbox('vpcC',1,0,b8(0),b8(10),b8(0x82),b8(2),b8(2),b8(2),b16(0))),codec:'vp09.00.10.08',ts:90000};
  return null}
 if(t.type==='audio'){var ch=t.ch||2,rate=Math.round(t.rate||48000);
  if(/^A_AAC/.test(id)&&cp.length>=2){var a=aacRate(cp);return {entry:aEntry('mp4a',ch,rate,esds(0x40,cp)),codec:'mp4a.40.'+a.aot,ts:a.rate,frame:1024}}
  if(id==='A_OPUS'&&cp.length>=19){var pre=cp[10]+cp[11]*256,fam=cp[18];var d=[b8(0),b8(cp[9]),b16(pre),b32(48000),b16(0),b8(fam)];if(fam)d.push(cp.subarray(19,21+cp[9]));return {entry:aEntry('Opus',cp[9],48000,box('dOps',cat(d))),codec:'opus',ts:48000,frame:0}}
  if(id==='A_AC3'||id==='A_EAC3'){return {entry:null,codec:id==='A_AC3'?'ac-3':'ec-3',ts:rate,frame:1536,late:id}}
  if(id==='A_MPEG/L3'||id==='A_MPEG/L2')return {entry:aEntry('mp4a',ch,rate,esds(id==='A_MPEG/L3'?0x6B:0x6B,null)),codec:'mp3',ts:rate,frame:id==='A_MPEG/L3'?(rate<32000?576:1152):1152};
  if(id==='A_FLAC'&&cp.length>8)return {entry:aEntry('fLaC',ch,rate,fbox('dfLa',0,0,cp.subarray(4))),codec:'flac',ts:rate,frame:0};
  return null}
 return null}

/* ---------- reading the file ---------- */
function Src(url,hdrs){this.url=url;this.size=0;this.hdrs=hdrs||{};this.win=null;this.ws=0}
Src.prototype.read=function(start,len){var self=this,end=start+len-1;if(self.size&&end>=self.size)end=self.size-1;if(end<start)return Promise.resolve(new Uint8Array(0));
 var w=self.win;if(w&&start>=self.ws&&end<self.ws+w.length)return Promise.resolve(w.subarray(start-self.ws,end-self.ws+1));
 var want=Math.max(len,262144);var e2=start+want-1;if(self.size&&e2>=self.size)e2=self.size-1;
 return fetch(self.url,{headers:{Range:'bytes='+start+'-'+e2},credentials:'same-origin'}).then(function(r){
  if(r.status!==206&&!(r.status===200&&start===0))throw new Error('range '+r.status);
  var cr=r.headers.get('Content-Range');if(cr){var m=/\/(\d+)${'$'}/.exec(cr);if(m)self.size=+m[1]}else if(r.status===200){var cl=+r.headers.get('Content-Length');if(cl)self.size=cl}
  return r.arrayBuffer()}).then(function(ab){var u=new Uint8Array(ab);if(u.length>len+(1<<22)){u=u.subarray(0,len)}self.win=u;self.ws=start;return u.subarray(0,Math.min(len,u.length))})};

/* ---------- Matroska ---------- */
var ID={EBML:0x1A45DFA3,Seg:0x18538067,Seek:0x114D9B74,Info:0x1549A966,Tracks:0x1654AE6B,Cues:0x1C53BB6B,Cluster:0x1F43B675,Tags:0x1254C367,Chap:0x1043A770,Att:0x1941A469};
function vid(b,p){var f=b[p],n=1;if(f===undefined)return null;var m=0x80;while(n<=4&&!(f&m)){m>>=1;n++}if(n>4)return null;var v=0;for(var i=0;i<n;i++)v=v*256+b[p+i];return {v:v,n:n}}
function vsz(b,p){var f=b[p];if(f===undefined)return null;var n=1,m=0x80;while(n<=8&&!(f&m)){m>>=1;n++}if(n>8)return null;var v=f&(m-1),i,unk=(v===m-1);for(i=1;i<n;i++){v=v*256+b[p+i];if(b[p+i]!==255)unk=false}return {v:unk?-1:v,n:n}}
function eachEl(b,s,e,fn){var p=s;while(p<e){var i=vid(b,p);if(!i)break;var z=vsz(b,p+i.n);if(!z)break;var ds=p+i.n+z.n,de=z.v<0?e:ds+z.v;if(de>e)de=e;if(fn(i.v,ds,de)===false)return;p=de}}
function uint(b,s,e){var v=0;for(var i=s;i<e;i++)v=v*256+b[i];return v}
function flt(b,s,e){var dv=new DataView(b.buffer,b.byteOffset+s,e-s);return e-s===4?dv.getFloat32(0):(e-s===8?dv.getFloat64(0):0)}
function utf(b,s,e){var t=new TextDecoder().decode(b.subarray(s,e));var z=t.indexOf('\0');return z<0?t:t.slice(0,z)}
function parseTrack(b,s,e){var t={num:0,type:'',codec:'',priv:null,lang:'eng',name:'',def:false,w:0,h:0,rate:0,ch:0,dur:0,enc:false};
 eachEl(b,s,e,function(id,a,z){switch(id){case 0xD7:t.num=uint(b,a,z);break;case 0x83:var ty=uint(b,a,z);t.type=ty===1?'video':ty===2?'audio':ty===17?'sub':'other';break;case 0x86:t.codec=utf(b,a,z);break;case 0x63A2:t.priv=b.slice(a,z);break;case 0x22B59C:t.lang=utf(b,a,z);break;case 0x22B59D:t.lang=utf(b,a,z);break;case 0x536E:t.name=utf(b,a,z);break;case 0x88:t.def=uint(b,a,z)===1;break;case 0x23E383:t.dur=uint(b,a,z);break;case 0x6D80:t.enc=true;break;
  case 0xE0:eachEl(b,a,z,function(i2,a2,z2){if(i2===0xB0)t.w=uint(b,a2,z2);else if(i2===0xBA)t.h=uint(b,a2,z2)});break;
  case 0xE1:eachEl(b,a,z,function(i2,a2,z2){if(i2===0xB5)t.rate=flt(b,a2,z2);else if(i2===0x9F)t.ch=uint(b,a2,z2)});break}});return t}
function parseMkvHead(b){var out={scale:1000000,dur:0,tracks:[],cues:0,segStart:0,tracksAt:0,cuesAt:0,firstCluster:0};var top=vid(b,0);if(!top||top.v!==ID.EBML)return null;var z=vsz(b,top.n);var p=top.n+z.n+z.v;
 var si=vid(b,p);if(!si||si.v!==ID.Seg)return null;var sz=vsz(b,p+si.n);var ss=p+si.n+sz.n;out.segStart=ss;var q=ss;
 while(q+12<b.length){var i=vid(b,q);if(!i)break;var zz=vsz(b,q+i.n);if(!zz)break;var ds=q+i.n+zz.n;
  if(i.v===ID.Cluster){out.firstCluster=q;break}
  if(i.v===ID.Seek){eachEl(b,ds,Math.min(ds+zz.v,b.length),function(id,a,e){if(id===0x4DBB){var sid=0,pos=0;eachEl(b,a,e,function(i2,a2,e2){if(i2===0x53AB)sid=uint(b,a2,e2);else if(i2===0x53AC)pos=uint(b,a2,e2)});if(sid===ID.Cues)out.cuesAt=ss+pos;else if(sid===ID.Tracks)out.tracksAt=ss+pos}})}
  else if(i.v===ID.Info){eachEl(b,ds,Math.min(ds+zz.v,b.length),function(id,a,e){if(id===0x2AD7B1)out.scale=uint(b,a,e);else if(id===0x4489)out.dur=flt(b,a,e)})}
  else if(i.v===ID.Tracks){if(ds+zz.v<=b.length){eachEl(b,ds,ds+zz.v,function(id,a,e){if(id===0xAE)out.tracks.push(parseTrack(b,a,e))});out.tracksAt=q}}
  if(zz.v<0)break;q=ds+zz.v}
 return out}
function parseCues(b){var cues=[];eachEl(b,0,b.length,function(id,a,e){if(id===ID.Cues){eachEl(b,a,e,function(i2,a2,e2){if(i2===0xBB){var t=0,tr=0,pos=0;eachEl(b,a2,e2,function(i3,a3,e3){if(i3===0xB3)t=uint(b,a3,e3);else if(i3===0xB7){eachEl(b,a3,e3,function(i4,a4,e4){if(i4===0xF7)tr=uint(b,a4,e4);else if(i4===0xF1)pos=uint(b,a4,e4)})}});cues.push({t:t,tr:tr,pos:pos})}})}});return cues}
/* one cluster at 'pos' (absolute): {frames:[{tr,tc,key,data,dur}],next} or null at the end */
function readCluster(src,head,pos){
 return src.read(pos,16).then(function(h){if(h.length<4)return null;var i=vid(h,0);if(!i)return null;var z=vsz(h,i.n);if(!z)return null;
  if(i.v!==ID.Cluster){if(z.v<0)return null;return {frames:[],next:pos+i.n+z.n+z.v}}
  var hl=i.n+z.n;var total=z.v<0?Math.min(src.size-pos,32<<20):hl+z.v;if(pos+total>src.size&&src.size)total=src.size-pos;
  return src.read(pos,total).then(function(b){var frames=[],tcl=0,end=b.length,next=pos+(z.v<0?b.length:hl+z.v);
   var blk=function(a,e,dur,ref){var t=vid(b,a);if(!t)return;var tn=vsz(b,a).v,tl=vsz(b,a).n;var tc=(b[a+tl]<<8|b[a+tl+1]);if(tc&0x8000)tc-=0x10000;var fl=b[a+tl+2];if(fl&6)return;frames.push({tr:tn,tc:tcl+tc,key:ref===undefined?!!(fl&0x80):ref,data:b.subarray(a+tl+3,e),dur:dur||0})};
   eachEl(b,hl,end,function(id,a,e){if(id===0xE7)tcl=uint(b,a,e);else if(id===0xA3)blk(a,e,0);else if(id===0xA0){var bs=0,be=0,d=0,rf=false;eachEl(b,a,e,function(i2,a2,e2){if(i2===0xA1){bs=a2;be=e2}else if(i2===0x9B)d=uint(b,a2,e2);else if(i2===0xFB)rf=true});if(be){var tlen=vsz(b,bs).n;var fl2=b[bs+tlen+2];blk(bs,be,d,!rf&&false?true:(!rf))}}});
   return {frames:frames,next:next}})})}

/* ---------- MP4 ---------- */
function boxes(b,s,e,fn){var p=s;while(p+8<=e){var sz=u32(b,p),ty=str(b,p+4,4),hs=8;if(sz===1){sz=u64(b,p+8);hs=16}else if(sz===0)sz=e-p;if(sz<hs)break;if(fn(ty,p+hs,Math.min(p+sz,e),p)===false)return;p+=sz}}
function entryCodec(e){var fc=str(e,4,4),p=8;
 if(fc==='avc1'||fc==='avc3'||fc==='hvc1'||fc==='hev1'||fc==='av01'||fc==='vp09')p=8+78;else if(fc==='encv'||fc==='enca')return null;else{var ver=u16(e,16);p=8+28+(ver===1?16:0)}
 var out=null;boxes(e,p,e.length,function(ty,a,z){
  if(ty==='avcC'&&z-a>=4)out='avc1.'+hex(e[a+1])+hex(e[a+2])+hex(e[a+3]);
  else if(ty==='hvcC'&&z-a>=13)out=hvcCodec(e.subarray(a,z));
  else if(ty==='av1C'&&z-a>=4)out=av1Codec(e.subarray(a,z));
  else if(ty==='vpcC'&&z-a>=8)out='vp09.'+hex(e[a+4])+'.'+hex(e[a+5])+'.'+hex(e[a+6]>>4);
  else if(ty==='dOps')out='opus';else if(ty==='dfLa')out='flac';else if(ty==='dac3')out='ac-3';else if(ty==='dec3')out='ec-3';
  else if(ty==='esds'){var q=a+4,oti=0,asc=null;
   var rd=function(){var tag=e[q++],l=0,c;do{c=e[q++];l=(l<<7)|(c&127)}while(c&128);return {tag:tag,l:l}};
   var d=rd();if(d.tag===3){q+=2;var fl=e[q++];if(fl&0x80)q+=2;if(fl&0x40){q+=e[q]+1}if(fl&0x20)q+=2;d=rd();if(d.tag===4){oti=e[q];q+=13;if(q<z){d=rd();if(d.tag===5)asc=e.subarray(q,q+d.l)}}}
   out=oti===0x40&&asc?'mp4a.40.'+aacRate(asc).aot:(oti===0x6B?'mp3':'mp4a.'+hex(oti).toLowerCase())}});
 if(!out&&(fc==='ac-3'||fc==='ec-3'||fc==='Opus'||fc==='fLaC'))out=fc==='Opus'?'opus':fc==='fLaC'?'flac':fc;return out}
function parseMoov(b,s,e){var res={ts:1000,dur:0,tracks:[],frag:false};
 boxes(b,s,e,function(ty,a,z){
  if(ty==='mvhd'){var v=b[a];res.ts=u32(b,a+(v?20:12));res.dur=v?u64(b,a+24):u32(b,a+16)}
  else if(ty==='mvex')res.frag=true;
  else if(ty==='trak'){var t={id:0,ts:1,type:'',lang:'und',entry:null,tab:null};var st={};
   boxes(b,a,z,function(t2,a2,z2){if(t2==='tkhd'){t.id=u32(b,a2+(b[a2]?20:12))}
    else if(t2==='mdia'){boxes(b,a2,z2,function(t3,a3,z3){
     if(t3==='mdhd'){var v=b[a3];t.ts=u32(b,a3+(v?20:12));var lc=u16(b,a3+(v?32:20));t.lang=String.fromCharCode(((lc>>10)&31)+96,((lc>>5)&31)+96,(lc&31)+96)}
     else if(t3==='hdlr'){var h=str(b,a3+8,4);t.type=h==='vide'?'video':h==='soun'?'audio':'other'}
     else if(t3==='minf'){boxes(b,a3,z3,function(t4_,a4,z4){if(t4_==='stbl'){boxes(b,a4,z4,function(t5,a5,z5){
       if(t5==='stsd'){var ep=a5+8;var sz=u32(b,ep);t.entry=b.slice(ep,ep+sz)}else st[t5]=[a5,z5]})}})}})}});
   if(t.entry&&st.stts&&st.stsz&&(st.stco||st.co64)&&st.stsc){t.st=st;res.tracks.push(t)}}});
 return res}
function buildTable(b,t){var st=t.st,i,j,k;
 var p=st.stsz[0]+4,cs=u32(b,p),n=u32(b,p+4),size=new Uint32Array(n);p+=8;for(i=0;i<n;i++)size[i]=cs?cs:u32(b,p+i*4);
 var co=st.stco?st.stco[0]:st.co64[0],nc=u32(b,co+4),big=!st.stco,off=new Float64Array(nc);for(i=0;i<nc;i++)off[i]=big?u64(b,co+8+i*8):u32(b,co+8+i*4);
 var sc=st.stsc[0],ne=u32(b,sc+4),first=[],per=[];for(i=0;i<ne;i++){first.push(u32(b,sc+8+i*12));per.push(u32(b,sc+12+i*12))}
 var so=new Float64Array(n),si=0;for(i=0;i<nc&&si<n;i++){var cnt=per[0];for(j=0;j<ne;j++)if(first[j]<=i+1)cnt=per[j];var o=off[i];for(k=0;k<cnt&&si<n;k++){so[si]=o;o+=size[si];si++}}
 var dts=new Float64Array(n),dur=new Uint32Array(n),tp=st.stts[0],tn=u32(b,tp+4),d=0;si=0;for(i=0;i<tn&&si<n;i++){var c=u32(b,tp+8+i*8),dl=u32(b,tp+12+i*8);for(j=0;j<c&&si<n;j++){dts[si]=d;dur[si]=dl;d+=dl;si++}}
 var cto=new Int32Array(n);if(st.ctts){var cp=st.ctts[0],cn=u32(b,cp+4);si=0;for(i=0;i<cn&&si<n;i++){var c2=u32(b,cp+8+i*8),o2=s32(b,cp+12+i*8);for(j=0;j<c2&&si<n;j++)cto[si++]=o2}}
 var sync=new Uint8Array(n);if(st.stss){var sp=st.stss[0],sn=u32(b,sp+4);for(i=0;i<sn;i++){var x=u32(b,sp+8+i*4)-1;if(x<n)sync[x]=1}}else sync.fill(1);
 return {n:n,off:so,size:size,dts:dts,dur:dur,cto:cto,sync:sync}}
function findIdx(tab,time){var lo=0,hi=tab.n-1,r=0;while(lo<=hi){var m=(lo+hi)>>1;if(tab.dts[m]<=time){r=m;lo=m+1}else hi=m-1}return r}

/* ---------- what can this browser play ---------- */
function okType(kind,codec){try{return !!codec&&MediaSource.isTypeSupported(kind+'/mp4; codecs="'+codec+'"')}catch(e){return false}}
var LANGN=null;
function langName(l){try{if(l&&l!=='und'&&l!=='zxx'){var n=new Intl.DisplayNames(['en'],{type:'language'}).of(l.length===3?(l2(l)):l);if(n&&n!==l)return n}}catch(e){}return l&&l!=='und'?l.toUpperCase():''}
var L3={eng:'en',hin:'hi',tam:'ta',tel:'te',mal:'ml',kan:'kn',ben:'bn',mar:'mr',pan:'pa',guj:'gu',urd:'ur',jpn:'ja',kor:'ko',chi:'zh',zho:'zh',spa:'es',fre:'fr',fra:'fr',ger:'de',deu:'de',ita:'it',por:'pt',rus:'ru',ara:'ar',tha:'th',tur:'tr',ind:'id',may:'ms',msa:'ms',vie:'vi',dut:'nl',nld:'nl',pol:'pl',swe:'sv',heb:'he'};
function l2(l){return L3[l]||l}
function chName(n){return n===1?'Mono':n===2?'Stereo':n===6?'5.1':n===8?'7.1':n?n+' ch':''}
function audioLabel(t){var base=t.name||'',ln=langName(t.lang);var c=t.codecName+(t.ch?' '+chName(t.ch):'');
 var l=ln&&base.toLowerCase().indexOf(ln.toLowerCase())<0?(ln+(base?' - '+base:'')):(base||ln||('Audio '+t.idx));return l+(c?' ('+c.trim()+')':'')}
function codecLabel(c){return /^mp4a\.40/.test(c)?'AAC':c==='opus'?'Opus':c==='ac-3'?'Dolby Digital':c==='ec-3'?'Dolby Digital Plus':c==='mp3'?'MP3':c==='flac'?'FLAC':c}
function mkAudio(base,idx){base.idx=idx;base.codecName=codecLabel(base.codec||'');base.supported=okType('audio',base.codec);base.label=audioLabel(base)+(base.supported?'':' - not playable in this browser');return base}

/* ---------- probing ---------- */
function probe(url){var src=new Src(url);
 return src.read(0,262144).then(function(h){if(!h||h.length<16)return null;
  if(h[0]===0x1A&&h[1]===0x45&&h[2]===0xDF&&h[3]===0xA3)return probeMkv(src,h);
  if(str(h,4,4)==='ftyp')return probeMp4(src);return null}).catch(function(e){return null})}
function probeMkv(src,h){var head=parseMkvHead(h);if(!head)return null;
 var getTracks=head.tracks.length||!head.tracksAt?Promise.resolve():src.read(head.tracksAt,1<<17).then(function(b){eachEl(b,0,b.length,function(id,a,e){if(id===ID.Tracks)eachEl(b,a,e,function(i2,a2,e2){if(i2===0xAE)head.tracks.push(parseTrack(b,a2,e2))})})});
 return getTracks.then(function(){
  var vt=head.tracks.filter(function(t){return t.type==='video'&&!t.enc})[0];var ats=head.tracks.filter(function(t){return t.type==='audio'});
  if(!vt||!ats.length||!head.cuesAt||!src.size)return null;
  var v=mkvEntry(vt);if(!v||!okType('video',v.codec))return null;
  var vT={num:vt.num,type:'video',ts:90000,w:vt.w,h:vt.h,entry:v.entry,codec:v.codec,fd:vt.dur?Math.round(vt.dur*90/1e6):0};
  var audios=[];var needAc3=[];
  ats.forEach(function(t,i){if(t.enc)return;var m=mkvEntry(t);if(!m)m={entry:null,codec:'',ts:Math.round(t.rate||48000),frame:0};
   var a={num:t.num,type:'audio',ts:m.ts,entry:m.entry,codec:m.codec,frame:m.frame,lang:t.lang,name:t.name,def:t.def,ch:t.ch,rate:t.rate,dur:t.dur,late:m.late,mkvId:t.codec};
   audios.push(mkAudio(a,i+1));if(m.late==='A_AC3')needAc3.push(a)});
  var cuesP=src.read(head.cuesAt,16).then(function(c){var i=vid(c,0),z=vsz(c,i.n);var len=Math.min(i.n+z.n+z.v,16<<20);return src.read(head.cuesAt,len)}).then(function(b){return parseCues(b)});
  var firstP=needAc3.length?readCluster(src,head,head.firstCluster):Promise.resolve(null);
  return Promise.all([cuesP,firstP]).then(function(r){var cues=r[0].filter(function(c){return c.tr===vt.num});if(!cues.length)cues=r[0];
   if(r[1])needAc3.forEach(function(a){var f=r[1].frames.filter(function(x){return x.tr===a.num})[0];var inf=f&&ac3Info(f.data);if(inf){a.entry=aEntry('ac-3',inf.ch,inf.rate,box('dac3',inf.dac3));a.ts=inf.rate;a.ch=inf.ch;mkAudio(a,a.idx)}});
   audios=audios.filter(function(a){return a.entry||!a.supported}).map(function(a){if(!a.entry)a.supported=false;return a});
   return {kind:'mkv',src:src,dur:head.dur*head.scale/1e9,video:vT,audios:audios,st:{D:undefined},
    open:function(t,aud){return new MkvReader(this,head,cues,t,aud)}}})})}
function probeMp4(src){var pos=0,hops=0;
 var hop=function(){if(hops++>24||(src.size&&pos>=src.size))return Promise.resolve(null);
  return src.read(pos,16).then(function(h){if(h.length<8)return null;var sz=u32(h,0),ty=str(h,4,4),hs=8;if(sz===1){sz=u64(h,8);hs=16}else if(sz===0)sz=src.size-pos;
   if(ty==='moov'){if(sz>48<<20)return null;return src.read(pos,sz).then(function(b){return b.length<sz?null:{b:b,s:hs,e:sz}})}
   if(sz<8)return null;pos+=sz;return hop()})};
 return hop().then(function(m){if(!m)return null;var mv=parseMoov(m.b,m.s,m.e);if(mv.frag||!mv.tracks.length)return null;
  var vts=mv.tracks.filter(function(t){return t.type==='video'});var ats=mv.tracks.filter(function(t){return t.type==='audio'});if(!vts.length||!ats.length)return null;
  var vt=vts[0],vc=entryCodec(vt.entry);if(!vc||!okType('video',vc))return null;
  var vT={id:vt.id,type:'video',ts:vt.ts,w:u16(vt.entry,32),h:u16(vt.entry,34),entry:vt.entry,codec:vc,tab:buildTable(m.b,vt)};
  var audios=[];ats.forEach(function(t,i){var c=entryCodec(t.entry);if(!c)return;var a={id:t.id,type:'audio',ts:t.ts,entry:t.entry,codec:c,lang:t.lang,name:'',ch:u16(t.entry,24),def:false,tab:buildTable(m.b,t)};audios.push(mkAudio(a,i+1))});
  if(!audios.length)return null;
  return {kind:'mp4',src:src,dur:mv.dur/mv.ts,video:vT,audios:audios,st:{D:0},open:function(t,aud){return new Mp4Reader(this,t,aud)}}})}

/* ---------- readers: the frames of a file from a time on, in small batches ---------- */
function opusDur(d){var toc=d[0],c=toc&3,cfg=toc>>3,fr=cfg<12?[10,20,40,60][cfg&3]:cfg<16?[10,20][cfg&1]:[2.5,5,10,20][cfg&3];var n=c===0?1:c===3?(d[1]&63):2;return Math.round(fr*n*48)}
function mpaDur(d,T){var v=(d[1]>>3)&3,l=(d[1]>>1)&3;return l===1?(v===3?1152:576):1152}
function MkvReader(info,head,cues,t,audio){this.i=info;this.h=head;this.a=audio;this.src=info.src;this.started=false;this.vk=false;this.ast={next:undefined};this.end=false;
 var ms=Math.max(0,t*1000),best=null,k;for(k=0;k<cues.length;k++){var ct=cues[k].t*head.scale/1e6;if(ct<=ms+1)best=cues[k];else break}
 this.pos=best?head.segStart+best.pos:head.firstCluster}
MkvReader.prototype.next=function(){var self=this,h=self.h,info=self.i;if(self.end||self.pos>=self.src.size)return Promise.resolve(null);
 return readCluster(self.src,h,self.pos).then(function(c){if(!c){self.end=true;return null}self.pos=c.next;if(!c.frames.length){return self.next()}
  var vn=info.video.num,an=self.a&&self.a.num,vf=[],af=[],sc=h.scale,i,f;
  for(i=0;i<c.frames.length;i++){f=c.frames[i];if(f.tr===vn)vf.push(f);else if(f.tr===an)af.push(f)}
  var out={v:[],a:[]};
  if(vf.length){var fr=vf;if(!self.vk){var s0=0;while(s0<fr.length&&!fr[s0].key)s0++;fr=fr.slice(s0);if(fr.length)self.vk=true}
   if(fr.length){var pts=fr.map(function(x){return Math.round(x.tc*sc*9e-5)});var sorted=pts.slice().sort(function(a,b){return a-b});
    var need=0;for(i=0;i<pts.length;i++)need=Math.max(need,sorted[i]-pts[i]);
    var fd=info.video.fd||0;if(!fd){var df=[];for(i=1;i<sorted.length;i++)if(sorted[i]>sorted[i-1])df.push(sorted[i]-sorted[i-1]);df.sort(function(a,b){return a-b});fd=df.length?df[df.length>>1]:3600}
    if(info.st.D===undefined)info.st.D=need;var D=info.st.D;
    for(i=0;i<fr.length;i++)out.v.push({dts:sorted[i],cts:pts[i]+D-sorted[i],dur:i+1<fr.length?Math.max(1,sorted[i+1]-sorted[i]):fd,key:fr[i].key,data:fr[i].data});
    out.vEnd=(sorted[sorted.length-1]+fd)/90000}}
  if(af.length&&self.a){var T=self.a,ts=T.ts,Da=Math.round((info.st.D||0)/90000*ts),st=self.ast;
   for(i=0;i<af.length;i++){f=af[i];var p=Math.round(f.tc*sc/1e9*ts),d=T.frame||0;
    if(!d&&T.codec==='opus')d=opusDur(f.data);else if(!d&&T.codec==='mp3')d=mpaDur(f.data);
    if(!d&&f.dur)d=Math.round(f.dur*sc/1e9*ts);if(!d&&T.dur)d=Math.round(T.dur/1e9*ts);if(!d)d=i+1<af.length?Math.max(1,Math.round(af[i+1].tc*sc/1e9*ts)-p):Math.round(0.02*ts);
    if(T.codec==='mp3'||T.codec==='opus'||T.frame){if(st.next===undefined||Math.abs(p-st.next)>ts*0.08)st.next=p}else st.next=p;
    out.a.push({dts:st.next+Da,cts:0,dur:d,key:true,data:f.data});st.next+=d}
   var la=out.a[out.a.length-1];out.aEnd=(la.dts+la.dur-Da)/ts}
  return out})};
function Mp4Reader(info,t,audio){this.i=info;this.a=audio;this.src=info.src;var V=info.video.tab;this.vi=V.n?findIdx(V,t*info.video.ts):0;while(this.vi>0&&!V.sync[this.vi])this.vi--;
 var A=audio.tab;this.ai=A.n?findIdx(A,t*audio.ts):0}
Mp4Reader.prototype.next=function(){var self=this,V=self.i.video.tab,A=self.a.tab,vts=self.i.video.ts,ats=self.a.ts;if(self.vi>=V.n&&self.ai>=A.n)return Promise.resolve(null);
 var vt=self.vi<V.n?V.dts[self.vi]/vts:1e12,at=self.ai<A.n?A.dts[self.ai]/ats:1e12;var w=Math.min(vt,at)+4,vj,aj,lo,hi,tries=0;
 for(;;){vj=self.vi;while(vj<V.n&&V.dts[vj]/vts<w)vj++;aj=self.ai;while(aj<A.n&&A.dts[aj]/ats<w)aj++;if(vj===self.vi&&aj===self.ai){w+=2;continue}
  lo=1e18;hi=0;if(vj>self.vi){lo=Math.min(lo,V.off[self.vi]);hi=Math.max(hi,V.off[vj-1]+V.size[vj-1])}if(aj>self.ai){lo=Math.min(lo,A.off[self.ai]);hi=Math.max(hi,A.off[aj-1]+A.size[aj-1])}
  if(hi-lo>40*1048576&&tries++<6){w=Math.min(vt,at)+(w-Math.min(vt,at))/2;continue}break}
 var vi=self.vi,ai=self.ai;self.vi=vj;self.ai=aj;
 return self.src.read(lo,hi-lo).then(function(b){var out={v:[],a:[]},i;
  for(i=vi;i<vj;i++){var o=V.off[i]-lo;out.v.push({dts:V.dts[i],cts:V.cto[i],dur:V.dur[i],key:!!V.sync[i],data:b.subarray(o,o+V.size[i])})}
  for(i=ai;i<aj;i++){var o2=A.off[i]-lo;out.a.push({dts:A.dts[i],cts:0,dur:A.dur[i],key:true,data:b.subarray(o2,o2+A.size[i])})}
  if(out.v.length){var l=out.v[out.v.length-1];var mx=0;out.v.forEach(function(f){mx=Math.max(mx,f.dts+f.cts+f.dur)});out.vEnd=mx/vts}
  if(out.a.length){var la=out.a[out.a.length-1];out.aEnd=(la.dts+la.dur)/ats}
  return out})};

/* ---------- the player: Media Source + the readers ---------- */
function sleep(ms){return new Promise(function(r){setTimeout(r,ms)})}
function Engine(v,info,o){var self=this,ms=new MediaSource(),objUrl=URL.createObjectURL(ms),vsb=null,asb=null,gen=0,rdr=null,fedV=0,fedA=0,dead=false,audio=null,ahead=AHEAD,restarting=false,opened=false,wantT=0;
 o=o||{};self.info=info;
 function mime(t,kind){return kind+'/mp4; codecs="'+t.codec+'"'}
 function idle(sb){return new Promise(function(res){(function chk(){if(!sb||!sb.updating)res();else setTimeout(chk,15)})()})}
 function app(sb,data,g){return idle(sb).then(function(){if(g!==gen||dead)return;return new Promise(function(res,rej){var done=function(){sb.removeEventListener('updateend',ok);sb.removeEventListener('error',er)};var ok=function(){done();res()},er=function(){done();rej(new Error('append failed'))};
   sb.addEventListener('updateend',ok);sb.addEventListener('error',er);try{sb.appendBuffer(data)}catch(e){done();rej(e)}})}).catch(function(e){if(e&&e.name==='QuotaExceededError'){ahead=Math.max(8,ahead/2);var cut=Math.max(0,v.currentTime-6);return idle(sb).then(function(){try{sb.remove(0,cut)}catch(x){}return idle(sb)}).then(function(){return sleep(100)}).then(function(){if(g!==gen||dead)return;return app(sb,data,g)})}throw e})}
 function clear(sb){return idle(sb).then(function(){try{if(ms.readyState==='open')sb.abort()}catch(e){}try{sb.remove(0,1e9)}catch(e){}return idle(sb)})}
 function makeAudioSb(){if(asb&&ms.sourceBuffers.length>1){try{ms.removeSourceBuffer(asb)}catch(e){}}asb=ms.addSourceBuffer(mime(audio,'audio'))}
 function aheadNow(){var e=fedV;if(asb&&fedA)e=Math.min(e,fedA);return e-v.currentTime}
 function trim(g){var c=v.currentTime-30;if(c<5)return Promise.resolve();return Promise.all([vsb,asb].map(function(sb){return idle(sb).then(function(){if(g!==gen||dead)return;try{if(sb.buffered.length&&sb.buffered.start(0)<c-2)sb.remove(0,c)}catch(e){}})}))}
 function begin(t){gen++;var g=gen;wantT=t;restarting=true;fedV=fedA=0;
  return Promise.all([clear(vsb),clear(asb)]).then(function(){if(g!==gen||dead)return;rdr=info.open(t,audio);
   return app(vsb,initSeg(info.video),g).then(function(){return app(asb,initSeg(audio),g)})}).then(function(){if(g!==gen||dead)return;restarting=false;return loop(g)}).catch(function(e){if(g===gen&&!dead)fatal(e)})}
 function loop(g){return (function step(){if(g!==gen||dead)return;if(aheadNow()>ahead){return sleep(250).then(step)}
  return rdr.next().then(function(b){if(g!==gen||dead)return;
   if(!b){return Promise.all([idle(vsb),idle(asb)]).then(function(){try{if(ms.readyState==='open')ms.endOfStream()}catch(e){}})}
   if(!b.v.length&&!b.a.length)return step();
   var off=-(info.st.D||0)/90000;if(vsb.timestampOffset!==off){vsb.timestampOffset=off;asb.timestampOffset=off}
   var p=Promise.resolve();
   if(b.v.length)p=p.then(function(){return app(vsb,fragment(b.v,false),g)}).then(function(){if(b.vEnd)fedV=b.vEnd});
   if(b.a.length)p=p.then(function(){return app(asb,fragment(b.a,true),g)}).then(function(){if(b.aEnd)fedA=b.aEnd});
   return p.then(function(){return trim(g)}).then(step)})})()}
 function fatal(e){if(dead)return;try{console.warn('MSE engine',e)}catch(x){}self.err=e;if(o.onFatal)o.onFatal(e)}
 function inBuf(t){try{var a=[vsb,asb];for(var i=0;i<2;i++){var b=a[i].buffered,ok=false;for(var j=0;j<b.length;j++)if(t>=b.start(j)-0.05&&t<=b.end(j)+0.05)ok=true;if(!ok)return false}return true}catch(e){return false}}
 function onSeeking(){if(dead||restarting)return;var t=v.currentTime;if(inBuf(t))return;begin(t)}
 v.addEventListener('seeking',onSeeking);
 ms.addEventListener('sourceopen',function(){if(opened)return;opened=true;try{ms.duration=info.dur;vsb=ms.addSourceBuffer(mime(info.video,'video'));audio=o.pick?o.pick(info.audios):info.audios[0];makeAudioSb();var t0=o.start||0;begin(t0).then(function(){if(t0>1)v.currentTime=t0})}catch(e){fatal(e)}});
 self.audios=function(){return info.audios.map(function(a){return {id:a.idx,label:a.label,lang:a.lang,on:a===audio,supported:a.supported}})};
 self.setAudio=function(idx){var a=info.audios.filter(function(x){return x.idx===idx})[0];if(!a||!a.supported||a===audio||dead)return;var prev=audio;audio=a;gen++;var t=v.currentTime;restarting=true;
  idle(asb).then(function(){if(mime(prev,'audio')!==mime(a,'audio')){if(asb.changeType)asb.changeType(mime(a,'audio'));else makeAudioSb()}return begin(t)}).catch(function(e){fatal(e)})};
 self.destroy=function(){dead=true;gen++;v.removeEventListener('seeking',onSeeking);try{URL.revokeObjectURL(objUrl)}catch(e){}};
 v.src=objUrl}
function usable(){return typeof MediaSource!=='undefined'&&!!MediaSource.isTypeSupported&&typeof fetch==='function'}
return {probe:probe,Engine:Engine,usable:usable,_t:{cat:cat,box:box,initSeg:initSeg,fragment:fragment,Src:Src,parseMkvHead:parseMkvHead}}
})();
"""
}
